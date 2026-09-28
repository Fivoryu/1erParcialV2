# Plan de verificación — D5

> Convierte cada decisión de Diseño (D1–D4) en comprobaciones reproducibles y define los criterios de aceptación para el cierre D6.

[Índice del PUDS](../../esqueleto-proceso-desarrollo-parcial-2.md) · [Contratos](../contratos/01-contratos-transversales.md) · [Diseño](../03-diseno.md) · [Mapeo](../datos/01-mapeo-base-de-datos.md)

## Estado

**D5 — borrador creado.** Define qué comprobar, con qué evidencia y con qué criterio de éxito, sin ejecutar todavía implementación. Q9 sigue abierta hasta el Ciclo 3.

## 1. Matriz de verificación por artefacto

| Artefacto (D1–D4) | Verificación | Evidencia mínima | Criterio de éxito |
| --- | --- | --- | --- |
| Despliegue y capas (D1) | Revisión de nodos, contenedores, capas y dependencias contra P01–P09 y Q5/Q10. | Vistas EA + ADR + comprobación operativa de Docker/WSL/VHDX. | Cada componente traza a responsabilidad y contrato; backend AI en Docker con pesos/caché configurables en D:, contenedor preparado para AWS, Floci como emulador local y sin recursos facturables no elegidos. |
| Modelo lógico de datos (D2) | Simetría clase ↔ tabla ↔ mapeo; FKs resueltas; sin UNIQUE sobre nombres UML. | Diagrama EA validado + parser estructural del SQL. | 32 clases UML ↔ 32 tablas de dominio; `generation_target` es metadata adicional del generador; `classifier.name` sin UNIQUE. |
| Contrato de comandos (D3) | Escenarios de precondiciones, idempotencia, causalidad y undo/redo por autor. | Pruebas de caja negra con sobres. | Aceptado/Rechazado/Conflicto/Definido según tabla de D3; reenvío con misma `idempotencyKey` no duplica. |
| API (D3) | OpenAPI servido y consumido por un cliente sin modificar lógica. | Contrato + cliente de prueba. | Cliente Flutter/web consumen recursos sin cambios de backend. |
| Tiempo real/offline (D3) | Simulación de concurrencia y desconexiones; RNF-11. | Registro p50/p95/p99/jitter por métrica. | Local ≤100 ms; remoto p95 ≤200/300 ms; durable p95 ≤1 s; drag 10–30/s; indicadores >500 ms y >1 s. |
| Conflictos (D3/D4) | Merge por campos disjuntos; alternativa en panel; delete-wins; colisiones bloqueando efectos. | Fixtures de escenarios + capturas. | Disjunto+válido → merge; disjunto inválido o no disjunto → panel; cascada atómica y undo de conjunto completo. |
| XMI (Q3) | Round-trip EA → aplicación → XMI → EA con el fixture. | Reporte de preservación/pérdidas con IDs, relaciones y geometría. | Sin pérdidas silenciosas; cada pérdida declarada con severidad. |
| IA (D3) | Verificar dos runtimes independientes con Qwen 3.5 0.8B: backend-local en Docker y móvil-local/en dispositivo en Flutter; texto y fotos de diagramas pasan por Qwen; voz sigue `audio → ASR local → transcripción → Qwen texto → propuesta`. | Propuestas de ambos runtimes, transcripción y sus confianzas, trazas de ejecución y matriz de compatibilidad. | Ambos emiten el contrato común con `runtime`, `modelId`, `artifactId`, `executionMode`, clases, atributos/tipos, operaciones, relaciones/multiplicidades, cobertura, confianza, faltantes, ambigüedades y layout aproximado; no hay memoria/proceso compartido ni mutación canónica directa. |
| Generación (D3) | Dos targets independientes desde una misma versión y repetición determinista por target/configuración + compilación, inicio, health, contrato y pruebas, sin depender de IA. | Hashes del run y reportes. | Mismo snapshot/target/configuración/perfil/plantilla ⇒ mismos artefactos; targets o configuraciones distintas permanecen independientes; pesos, cachés, secretos y estado de IA no aparecen en ZIPs, logs, propuestas ni hashes; verificación con `generation_state` y `verification_state`. |

## 2. Esquemas JSON de payloads (versionados)

Política de evolución:

- Cada `payload` declara `schemaVersion` y pertenece a una familia por `type` (ver D3 §2.1).
- Cambios **aditivos** conservan la versión; cambios que rompen suben `schemaVersion` y se registran en la tabla de versiones.
- El servidor rechaza sobres con `schemaVersion` desconocido o de familia distinta (`INVALID_PAYLOAD`).

### 2.1 `classifier.create` — v1

```json
{
  "schemaVersion": 1,
  "name": "Usuario",
  "kind": "CLASS",
  "isAbstract": false,
  "namespaceId": "uuid-opcional"
}
```

### 2.2 `classifier.update` — v1

```json
{
  "schemaVersion": 1,
  "name": "Cuenta",
  "isAbstract": true
}
```

Campos opcionales: `name`, `isAbstract`. La edición toca solo los campos presentes (independencia por campo para merge seguro).

### 2.3 `relationship.create` — v1

```json
{
  "schemaVersion": 1,
  "kind": "ASSOCIATION",
  "ends": [
    { "classifierId": "uuid", "role": "origen", "multiplicity": "1", "navigability": "NAVIGABLE" },
    { "classifierId": "uuid", "role": "destino", "multiplicity": "0..*", "navigability": "NAVIGABLE" }
  ],
  "name": "posee"
}
```

### 2.4 `geometry.update` — v1

```json
{
  "schemaVersion": 1,
  "target": { "scope": "classifier", "id": "uuid" },
  "x": 120, "y": -320, "w": 280, "h": 160, "zOrder": 3
}
```

No altera semántica: verificación dedicada a que mover un elemento no cambie el modelo.

### 2.5 `delete_wins` (con cascada) — v1

```json
{
  "schemaVersion": 1,
  "target": { "scope": "classifier", "id": "uuid" },
  "cascadeCount": 4,
  "confirmCascade": true
}
```

Server exige `confirmCascade=true` cuando `cascadeCount>0`; responde `cascadeGroupId`.

### 2.6 `resolve_conflict` — v1

```json
{
  "schemaVersion": 1,
  "conflictId": "uuid",
  "decision": "KEEP_RIGHT"
}
```

Solo `KEEP_RIGHT`, `KEEP_LEFT`, `MERGE_EXPLICIT`; crea comando `origin: RESOLUTION`.

### 2.7 `proposal.apply` — v1

```json
{
  "schemaVersion": 1,
  "proposalId": "uuid",
  "scope": { "accept": ["detected_id..."], "reject": ["detected_id..."] }
}
```

Igual que D3: confirmar/descartar genera los comandos derivados.

### 2.8 `xmi.import` / `xmi.export` — v1

```json
{
  "schemaVersion": 1,
  "direction": "IMPORT",
  "profile": "EA15/XMI21/UML21/EA6.5",
  "artifactRef": "uri-opcional",
  "modelRevision": 42
}
```

### 2.9 `generation.run` — v1

```json
{
  "schemaVersion": 1,
  "profileId": "uuid",
  "templateId": "uuid",
  "versionId": "uuid"
}
```

## 3. Entorno y fixtures

- **Fixture XMI:** [`docs/fixtures/xmi/Prueba3.xml`](../../fixtures/xmi/Prueba3.xml) (91.440 bytes, SHA-256 `336d13b1…0dab2`) para Q3.
- **Fixtures de IA:** texto y fotografía de diagramas con clases, atributos/tipos, operaciones, relaciones/multiplicidades, elementos faltantes y regiones ambiguas; entradas de audio para comprobar ASR local y su corrección antes de Qwen.
- **Dataset de concurrencia:** sobres preparados que ejercen merge disjunto, no disjunto, delete-wins y colisión de nombres.
- **Métricas RNF-11:** banco de pruebas con 2+ clientes y desconexión programada; se registran p50/p95/p99 y jitter por métrica.
- **Determinismo:** dos corridas con el mismo `(snapshot, target, target_config_revision, profile, template)` comparando hashes y contenido; además, dos targets distintos del mismo snapshot deben permanecer independientes.
- **Demo:** recorrido acotado por Q9 (Ciclo 3); la instrumentación de eficiencia se prepara aquí sin criterios globales.

## 4. Criterios de cierre hacia D6

- [ ] Matriz D5 ejecutada sin hallazgos bloqueantes en comandos, API, realtime, conflictos, XMI, ambos runtimes de IA y generación.
- [ ] Runtime backend-local y runtime móvil-local/en dispositivo verificados por separado, sin proceso ni memoria mutable compartida.
- [ ] Qwen 3.5 0.8B interpreta texto y fotografías; artefacto, runtime, cuantización y preprocesador compatibles quedan registrados como gates aprobados.
- [ ] ASR local produce una transcripción revisable que pasa a Qwen texto y a una propuesta antes de cualquier comando.
- [ ] Round-trip Q3 ejecutado y reportado (preservación + pérdidas declaradas).
- [ ] Esquemas JSON v1 validados por el servidor de pruebas (aceptación/rechazo por versión).
- [ ] SLO RNF-11 medidos en entorno controlado y documentados.
- [ ] Sin decisiones nuevas de producto sin registrar; Q9 permanece abierta.
- [ ] Riesgos y gaps de D3 actualizados con evidencia.

[Volver a Diseño](../03-diseno.md) · [Volver a Contratos](../contratos/01-contratos-transversales.md)
