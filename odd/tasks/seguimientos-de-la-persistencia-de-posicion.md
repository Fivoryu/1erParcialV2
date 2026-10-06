# Seguimientos declarados de la persistencia de posición

**Estado:** unidad autorizada por el usuario el 2026-10-06 («1.»), sobre los dos seguimientos que dejó declarados la unidad PPL (`odd/tasks/posicion-persistida-y-perdidas-declaradas.md` §5).
**Origen:** el aviso `R3-001` de la revisión nativa de esa unidad, y el origen `MANUAL` con que viaja la colocación automática de geometría.
**Rama:** `fix/seguimientos-persistencia-de-posicion`, desde `main` (`efe56141`).

## 0. Lo que ya sabemos, separando medido de inferido

| Ítem | Estado | Detalle |
| --- | --- | --- |
| El comando automático viaja con origen `MANUAL` | **Medido** | `ModelCommandRequest` no tiene campo `origin` y `buildModelCommandEnvelope` cae en el default (`web/src/api/envelope.ts`; armado en `web/src/pages/ProjectEditorPage.tsx:475`). El worker lo reportó en vez de rodearlo |
| Que eso entre al historial de undo y marque el elemento como tocado | **Inferido** | Es consecuencia del origen, no una medición. Esta unidad lo mide antes de decidir nada |
| El enum del backend no tiene un origen de sistema | **Medido** | `CommandOrigin`: `AI/VOICE/PHOTO/XMI/RESOLUTION/ADMIN/OFFLINE/IMPORT`. `MANUAL` es lo que hay **por falta de alternativa**, no por semántica (aportado por el peer de `feat/context-menu-multiplicidad`) |
| La escritura la dispara la **llegada** del modelo, no un arrastre | **Medido, por lectura** | El efecto corre al montar y cuando llega la clasificación sin geometría; el arrastre es otro camino (`settleNodeGeometry` en `onNodeDragStop`) |
| `R3-001`: una guarda marca la clase antes de enviar, así que un rechazo no se reintenta en ese montaje | **Medido, por construcción** | `UmlCanvas.tsx:384-389`. Fue un canje deliberado al escribir el efecto (así no hay lazo), y la lente de confiabilidad lo señaló |

## 1. Decisiones

| Decisión | Valor | Por qué |
| --- | --- | --- |
| SPP-01 | **Medir la consecuencia antes de tocar código**: ¿el comando automático entra al historial de undo y al resaltado de «tocado»? | Es el único punto que quedó inferido, y sin esa medición cualquier arreglo del origen es especulación |
| SPP-02 | Si ningún valor del enum corresponde y agregar uno es invasivo, **`MANUAL` se queda** y la consecuencia medida se declara | El peer ya argumentó que la prioridad es baja, y el enum no tiene un valor de sistema: inventar semántica en un enum persistido es más caro que documentarlo |
| SPP-03 | `R3-001` se arregla con una **máquina de estado pura y testeable**, no con un contador suelto en el efecto | El helper `buildUnplacedGeometryRequests` ya es puro y es donde el peer va a buscar el nombre; la política de reintento va con él |
| SPP-04 | El reintento es **acotado** (no infinito) y se dispara cuando el modelo sigue sin geometría para esa clase | Un comando rechazado de verdad (sin concesión de edición) no debe reintentarse para siempre; y un rechazo transitorio debe recuperarse |

## 2. Plan de trabajo

- [x] **T1 (medición)** ¿Cuál es la consecuencia real del origen `MANUAL`? **No existe, y se midió de dos formas** (lectura y datos) — ver §5.
- [x] **T2 (decisión)** Con la medición en mano: **no se toca el origen**; el seguimiento se cierra sin código y la pregunta que queda se declara — ver §5.
- [x] **T3 (RED)** Prueba de la política de reintento en el módulo puro. Rojo observado **dos veces**: 15 fallos en la implementación inicial, y 7 de 17 al refinar la cadencia (ver §6).
- [x] **T4 (GREEN)** Máquina de estado en `unplaced-geometry.ts` y cableado en `UmlCanvas.tsx`, sin tocar el efecto del fit diferido ni el de sincronización del modelo. Verde: 15/15 y después 17/17; suite web **455 pasan / 4 salteadas**; `lint` limpio (ver §6).
- [x] **T5 (verificación)** Verificador independiente sobre la base `main` `efe56141`: `mvn -o -B -f backend/pom.xml verify` = **460 pruebas, 0 fallos, 0 errores, 0 salteadas, BUILD SUCCESS**; `python -m pytest tools -q` (por el Python de Windows: en WSL no hay pytest) = **54 pasan**; arnés de aceptación XMI = **18 pasan, 0 fallan** con sha256 de la emisión `0133b9620d06115b169f89597491f8881cb002b141a5462f74a998ffdc7e4235`; `npm test` = **455 pasan / 4 salteadas** (el helper solo: 17 pruebas); `npm run lint` = limpio. El alcance del diff reportado son exactamente los 3 archivos declarados.
- [x] **T6 (docs y commits)** Acta en §7, nota de cierre en §5 de la unidad PPL, y los commits de la unidad: `cf8ab7f` (el reintento) más el commit de estos documentos.

## 3. Fuera de alcance, y por qué

| Ítem | Motivo |
| --- | --- |
| Agregar un origen nuevo al enum persistido | Solo si T1+T2 lo justifican; hoy no hay evidencia de que valga el costo |
| Reintentar comandos que fallan por falta de concesión de edición | Ese rechazo es correcto: el lienzo no debe insistir contra la concesión de otro |
| Cambiar el camino del arrastre (`settleNodeGeometry`) | Ya funciona y su origen sí es manual de verdad |

## 4. Evidencia de la medición

- Unidad que dejó los seguimientos: `odd/tasks/posicion-persistida-y-perdidas-declaradas.md`.
- Código señalado: `web/src/diagram/UmlCanvas.tsx:374-395` (efecto) y `:384-389` (la guarda antes de enviar).

## 5. Resultado de T1 y T2: el origen no gobierna nada de eso

**Lectura del camino del undo.** `UndoCaptureService.recordAccepted` (`backend/src/main/java/com/diagramador/undo/UndoCaptureService.java:99`) guarda una fila de `undo_log` por **cada comando aceptado** con fila de modelo, y la instantánea previa sale de los *targets* del sobre por `scope` (clasificación, atributo, operación, relación, parámetro, restricción). **No hay ninguna rama sobre `origin` en ese camino**, ni en `UndoService` cuando arma la inversa.

**Lectura del resaltado.** `web/src/pages/ProjectEditorPage.tsx:507` arma `touchedElements` desde `presence` (`groupTouchesByElement(presence)`), o sea desde la **presencia por SSE** — no desde el origen del comando.

**Medición sobre la base real** (read-only, `docker exec … psql`):

| Consulta | Resultado |
| --- | --- |
| Orígenes registrados en `model_command` | `MANUAL` **213**, `AI` **20** |
| Tipos capturados en `undo_log` | `classifier.create` 79, **`geometry.set` 70**, `attribute.add` 35, `relationship.create` 11, `classifier.delete` 2, `attribute.update` 1 |
| `undo_log` × origen | `geometry.set\|MANUAL` **70**; y también hay filas `AI`: `attribute.add\|AI` 10, `classifier.create\|AI` 9, `relationship.create\|AI` 1 |

**Conclusión, y una rectificación.** El origen **no** excluye nada del undo (se capturan comandos `AI` igual que `MANUAL`) y **no** participa del resaltado. Por lo tanto, plomar el origen para que la colocación automática viaje como `AI` **no cambiaría nada observable**: la consecuencia que esta unidad tenía como *inferida* queda **refutada por medición**, y el seguimiento se cierra **sin código**. Dicho sea de paso, la intuición del peer —«se queda como está»— era correcta, aunque por otra razón: no es que el origen sea genuinamente manual, es que el origen no gobierna ninguna de las dos cosas que se le atribuían.

**La pregunta que sí queda, declarada y no inventada.** La colocación automática **sí es deshacible**: hay 70 filas de `geometry.set` en el `undo_log`, así que un `Ctrl+Z` puede revertir una posición que el usuario nunca eligió; y como la guarda es por montaje, al recargar la posición vuelve. Eso es un hecho medido, y decidir si vale la pena excluir esa normalización del historial es una **decisión de producto nueva**, no un seguimiento de esta unidad: exigiría una señal en el camino de captura (el origen es el candidato natural, y hoy no se lee). No se implementa sin que se pida.

## 6. Resultado de T3 y T4: el reintento, y un defecto que apareció al revisar el propio arreglo

**Primera implementación.** La guarda de «una vez por clase» se reemplazó por un mapa de intentos y el helper pasó a devolver la decisión completa (`{ requests, attempts }`), conservando el nombre exportado. Rojo observado (15 fallos) → verde 15/15; suite web 453+4; `lint` limpio.

**El defecto del propio arreglo, encontrado al revisar el diff.** El contador subía en **cada corrida del efecto**, y ese efecto depende de `nodes`: React Flow reemplaza los nodos cuando mide el DOM, uno o dos frames después del montaje y bastante antes de que llegue la respuesta del servidor. En el camino feliz eso mandaba el **mismo `geometry.set` dos o tres veces** para una sola clase, y como todo comando aceptado se registra en el `undo_log` (§5), dejaba **dos o tres filas de undo por una sola colocación automática**: una regresión de limpieza introducida por el arreglo del reintento.

**Segunda implementación (el refinamiento).** El bookkeeping pasó a `{ count, revision }` por clase:

```ts
export interface UnplacedGeometryAttempt { readonly count: number; readonly revision: number }
export type UnplacedGeometryAttempts = ReadonlyMap<string, UnplacedGeometryAttempt>;
export const DEFAULT_UNPLACED_GEOMETRY_ATTEMPTS = 3;
```

Con eso la cadencia es **un pedido por clase y por revisión del modelo**: si la clase ya se pidió en la revisión actual, no se pide de nuevo aunque los nodos cambien; el reintento aparece solo cuando llega una **revisión nueva** y la clase sigue sin geometría, y el tope de 3 se acumula entre revisiones. Rojo observado (7 de 17) → verde 17/17; suite web **455+4**; `lint` limpio.

**Propiedades declaradas del reintento:** un comando rechazado reintenta en la próxima revisión del modelo, hasta tres veces por montaje, y después espera al próximo montaje; si el modelo no cambia, no hay reintento (es la semántica de SPP-04: la señal de «no aceptado» es que el modelo siga sin geometría); y una clase que gana geometría libera su contador, así que un borrado y una reaparición posterior vuelven a pedir desde cero.

**Contrato con el peer, avisado.** El nombre exportado se mantiene, pero **la firma cambió** (de `(model, nodes, alreadyRequested: ReadonlySet<string>) => GeometryUpdate[]` a `(model, nodes, attempts?, maxAttempts?) => { requests, attempts }`). Se le avisó antes de mergear, porque había dicho que iba a reutilizar el helper por su nombre.

## 7. Cierre

**Commits** en `fix/seguimientos-persistencia-de-posicion`: `cf8ab7f` (el reintento acotado y por revisión) y el commit de estos documentos.

**Compuertas medidas** por un verificador independiente sobre la base `main` (`efe56141`): `mvn -o -B -f backend/pom.xml verify` = **460 pruebas, 0 fallos, 0 errores, 0 salteadas, BUILD SUCCESS**; `python -m pytest tools -q` (por el Python de Windows) = **54 pasan**; arnés de aceptación XMI = **18 pasan, 0 fallan** con sha256 `0133b9620d06115b169f89597491f8881cb002b141a5462f74a998ffdc7e4235`; `npm test` = **455 pasan / 4 salteadas**; `npm run lint` = limpio. El alcance del diff que reportó son exactamente los 3 archivos declarados, sin residuos.

**Dos rondas de TDD con rojo observado**: 15 fallos → 15/15 (reintento acotado) y 7 de 17 → 17/17 (puerta por revisión del modelo).

**Lo que queda declarado de esta unidad:** el reintento se agota a los tres intentos por montaje y espera al próximo montaje; si el modelo no cambia, no reintenta, porque la señal de «no aceptado» es exactamente que el modelo siga sin geometría (SPP-04). Y el contrato con el peer: el nombre exportado se mantiene y la firma cambió, avisado antes de mergear.

**Lo que esta unidad NO decide:** si la colocación automática debe quedar fuera del historial de undo. Es un hecho medido (§5) y una decisión de producto pendiente del usuario, no un seguimiento que se haya tomado por propia cuenta.
