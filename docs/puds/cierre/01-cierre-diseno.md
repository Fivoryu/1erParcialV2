# Cierre de Diseño — D6 (línea base inicial)

> Acta de cierre del corte D6 para la **línea base inicial de Diseño**, suficiente para iniciar la implementación del **Ciclo 1** (CU-01–CU-08). La arquitectura de IA dual queda confirmada; no se cierra todavía la evidencia de colaboración/XMI/IA (C2) ni de generación/cliente/despliegue/eficiencia (C3), y esos umbrales se revalidan con su propia evidencia.

[Índice del PUDS](../../esqueleto-proceso-desarrollo-parcial-2.md) · [Diseño](../03-diseno.md) · [Contratos](../contratos/01-contratos-transversales.md) · [Verificación](../verificacion/01-verificacion.md)

## 1. Estado

**D6 — línea base inicial CERRADA** para el umbral C1. Los artefactos D1–D5 quedan aprobados como baseline revisable; las correcciones posteriores se registran como revisiones de la línea base, sin reabrir decisiones de alcance salvo aprobación explícita.

## 2. Artefactos aprobados

| Corte | Artefacto | Estado |
| --- | --- | --- |
| D1 | [Despliegue del Sistema](../../diagramas/arquitectura-despliegue.eapx) (Deployment, 21 objetos/14 enlaces) | Aprobado |
| D1 | [Arquitectura lógica organizada en capas](../../diagramas/arquitectura-despliegue.eapx) (Package, 12/12 `<<use>>`) | Aprobado |
| D2 | [Diagrama de Clases del Sistema](../../diagramas/arquitectura-despliegue.eapx) (Logical, 32 clases/40 relaciones con nombre y multiplicidad) | Aprobado |
| D2 | [Mapeo de base de datos](../datos/01-mapeo-base-de-datos.md) | Aprobado |
| D2 | [Esquema físico SQL](../datos/02-esquema-fisico.sql) (`diagrammer`, 33 tablas, incluyendo `generation_target`) | Aprobado |
| D3 | [Contratos transversales](../contratos/01-contratos-transversales.md) | Aprobado |
| D3/D4 | [Comparación de colaboración](../contratos/02-comparacion-colaboracion.md) — decisión A+B; CRDT descartado para V1 | Aprobado |
| D4 | [6 diagramas de secuencia](../../diagramas/arquitectura-despliegue.eapx) (Comportamiento, 44 mensajes) | Aprobado |
| D5 | [Plan de verificación](../verificacion/01-verificacion.md) y esquemas JSON v1 | Aprobado |

## 3. Checklist de cierre D6 (umbral inicial)

- [x] Captura y Análisis cerrados como entrada; Q3 registrada como tarea de prueba.
- [x] Arquitectura (despliegue + capas) aprobada y consistente con P01–P09 y Q5/Q10; distingue backend-local Qwen en Docker, Flutter móvil-local/en dispositivo, AWS y Floci.
- [x] Modelo lógico aprobado: 32 clases ↔ 32 tablas, sin UNIQUE sobre nombres UML.
- [x] Contratos de comandos/API/eventos aprobados con sobre de operación e idempotencia.
- [x] Contrato común de propuestas aprobado con procedencia (`runtime`, `modelId`, `artifactId`, `executionMode`) y regla de revisión humana antes de validación y mutación canónica.
- [x] Decisión de colaboración A+B registrada (CRDT descartado en V1).
- [x] Secuencias de escenarios críticos aprobadas (edición, IA revisable, conflicto/delete-wins, offline, XMI, generación).
- [x] Plan de verificación D5 y esquemas JSON v1 aprobados.
- [x] Riesgos y gaps registrados; ninguna decisión de producto nueva sin documentar.
- [x] Q9 permanece **abierta** (Ciclo 3) y no bloquea C1.
- [ ] Pendiente no bloqueante: vistas C4 de contexto/contenedores/componentes y ADR de arquitectura ampliados (refinamiento D1 post-C1).
- [ ] Pendiente no bloqueante: diagramas gráficos de casos de uso de Captura/Análisis (documental).
- [ ] Pendiente con evidencia: **Q3** round-trip XMI; sin ejecutarlo, el diseño del adaptador XMI (CU-16/CU-17) se revalida en el umbral C2.

## 4. Umbrales de revalidación

| Umbral | Cuándo | Cierra |
| --- | --- | --- |
| C1 (inicial) | **Hoy** | Línea base aprobada para CU-01–CU-08. |
| C2 | Antes de implementar CU-09–CU-17 | Diseño de colaboración/offline/XMI/IA **con evidencia Q3**; prueba de ambos runtimes, visión Qwen, ASR y gates de compatibilidad; refinamientos de contrato si la evidencia lo exige. |
| C3 | Antes de CU-18–CU-23 | Generación independiente de IA, cliente Flutter, runtime móvil, despliegue del runtime backend, topología Docker/D:/AWS/Floci y **resolución de Q9** con mediciones; cierre final del Diseño. |

Regla: cada umbral se abre con el checklist del corte, se registra evidencia y se actualizan riesgos/gaps. La existencia de pendientes no bloqueantes no impide la implementación de C1; la existencia de pendientes bloqueantes (decisión de producto sin registrar) sí la detiene.

## 5. Transición a implementación (C1)

- Base de datos: usar el esquema `diagrammer` (migraciones versionadas posteriores).
- Contrato de comandos: implementar validación de `baseRevision`, precondiciones e `idempotencyKey` desde el primer commit.
- Ciclo 1 cubre: CU-01–CU-08 (proyectos, modelo UML, edición manual, validación, disposición, historial básico, toolbar/atajos).
- Q3 puede ejecutarse en paralelo durante C1 porque condiciona el adaptador de C2; no se requiere para el alcance de C1.
- La verificación de IA de C2 debe comprobar por separado ambos runtimes, la interpretación Qwen de texto y fotografías, el límite ASR → Qwen texto y la ausencia de mutación canónica directa.

[Volver a Diseño](../03-diseno.md) · [Volver al PUDS](../../esqueleto-proceso-desarrollo-parcial-2.md)
