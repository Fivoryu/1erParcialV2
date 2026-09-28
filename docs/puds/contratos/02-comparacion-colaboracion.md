# Comparación de mecanismos de colaboración — decisión técnica

> Registro de decisión de Diseño (ADR) para el corte D3/D4: cómo ordenar, aplicar y resolver operaciones concurrentes y offline en el diagramador. No es un requisito de producto: es la comparación explícita que exige el [Análisis](../02-analisis.md) §3.1 y el checklist de [D3](01-contratos-transversales.md).

[Índice del PUDS](../../esqueleto-proceso-desarrollo-parcial-2.md) · [Contratos transversales](01-contratos-transversales.md) · [Diseño](../03-diseno.md)

## 1. Problema

Varios colaboradores (online y offline) modifican el mismo modelo UML, su disposición compartida y su historial. Hay que decidir, sin cerrar las puertas futuras:

1. cómo se **ordenan** las operaciones;
2. cómo se **aplican** al modelo canónico;
3. cómo se **resuelven** las diferencias que no se pueden integrar con seguridad.

El comportamiento observable ya está fijado por requisitos (Q6, RNF-01/RNF-11, invariantes del Análisis): modelo canónico válido, sobre de operación trazable, autoridad del servidor, merge seguro por campos independientes, delete-wins, panel de conflictos, revocación offline auditada y SLO medibles.

## 2. Alternativas evaluadas

### A. Registro de comandos + servidor autoritativo (baseline)

El servidor recibe sobres de operación con `baseRevision`, valida precondiciones, aplica en orden causal, versiona y audita. Los clientes envían intención; el servidor confirma.

- **Complejidad:** baja-media. Es el modelo de negocio natural del esquema (`model_command`, `model_version`).
- **Offline:** el cliente encola con `idempotencyKey`; al reconectar reenvía y el servidor decide.
- **Verificabilidad:** alta. Cada rechazo/éxito es auditable y reproducible con los mismos sobres.
- **Riesgo:** la latencia percibida depende del round-trip; se mitiga con optimismo local (preview) sin confirmación.

### B. Merge por campos independientes (sobre A)

Cuando dos operaciones tocan campos o elementos disjuntos y el modelo completo sigue válido, se fusionan automáticamente; si no, una alternativa queda en el panel lateral.

- **Complejidad:** media. Requiere un comparador de sobres y validación completa post-merge.
- **Offline:** mejora la tasa de aceptación en reconexión (más ediciones no conflictivas se integran).
- **Verificabilidad:** alta. La regla "disjuntos + válido" es comprobable con fixtures.
- **Riesgo:** necesita definir con precisión qué es "independiente" (por campo, no por elemento).

### C. CRDT (estructuras convergentes por elemento) + validación semántica

Cada elemento/versión sería un registro CRDT que converge sin coordinador; la validación UML cierra la brecha semántica.

- **Complejidad:** alta. Implica elegir/implementar estructuras (por registro, por documento) y mantener el modelo UML válido encima; el fixture/dialecto XMI no aporta soporte.
- **Offline:** excelente convergencia, pero la validez UML y las colisiones de nombres pueden **divergir igual**, exigiendo el mismo panel/resolución.
- **Verificabilidad:** media (convergencia reproducible) con overhead de simulación.
- **Riesgo:** costo de desarrollo y demo alto, sin beneficio observable frente a A+B para este alcance; además, [CRDT no es requisito](../../puds/02-analisis.md).

## 3. Criterios

| Criterio | Peso | A | B | C |
| --- | --- | --- | --- | --- |
| Complejidad y mantenibilidad | Alto | Bajo | Medio | Alto |
| Verificabilidad (reproducir, auditar, diagnosticar) | Alto | Alta | Alta | Media |
| Operación offline y reconexión | Alto | Suficiente | Mejor | Mejor |
| Idempotencia y trazabilidad (RNF-02/RNF-03) | Alto | Alta | Alta | Media (convergencia opaca) |
| Validez UML y colisiones | Crítico | Por servidor | Por servidor + regla | Requiere capa extra |
| Alcance de la demostración (tiempo disponible) | Alto | Ajustado | Ajustado | En riesgo |

## 4. Decisión

**Adoptar A + B como línea base de implementación**:

1. **Orden y aplicación:** registro de comandos con `baseRevision` causal, servidor autoritativo (A).
2. **Integración automática:** merge por campos independientes solo si el modelo completo sigue válido (B); en caso contrario, panel lateral y resolución por comando nuevo.
3. **CRDT queda descartado para V1** como mecanismo de convergencia, sin declararlo prohibido: la decisión se revisa si un requisito futuro exige convergencia sin coordinador. No se compra ninguna librería ni algoritmo en esta decisión.

Cierre del checklist D3: ✅ comparación explícita realizada con criterios y decisión registrada.

## 5. Consecuencias

- Los contratos D3 permanecen tal cual (no dependen del mecanismo).
- El esquema D2 permanece tal cual (A+B ya están cubiertos por `model_command`, `model_version`, `model_conflict`, `name_collision`).
- D4 mantiene los 6 diagramas de secuencia; el escenario `Conflicto y delete-wins` refleja A+B.
- D5 debe incluir pruebas de idempotencia, merge por campos disjuntos y validación post-merge.

[Volver a Contratos](01-contratos-transversales.md) · [Volver a Diseño](../03-diseno.md)
