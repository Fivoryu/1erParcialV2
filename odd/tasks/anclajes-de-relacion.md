# Anclajes de relación: elegir de qué lado sale y entra

**Estado:** unidad autorizada por el usuario el 2026-10-06 («Puntos en el borde de las clases (arrastrables)» + «Punto libre a lo largo del borde»).
**Origen:** después de cerrar el ajuste del trazado ortogonal el usuario midió lo que faltaba: «Se mueve, pero yo quiero poder moverlo a los diferentes lados, actualmente siempre se conecta con el centro de cada lado» — con dos capturas de sus diagramas donde varias relaciones caen en el **mismo punto** del costado de `Socio` y de `Reserva`.
**Rama:** `feat/rutas-ortogonales-ajustables` (misma que la unidad anterior: es la continuación del mismo pedido).
**Unidad previa:** `odd/tasks/rutas-ortogonales-ajustables.md` (T1–T3 y `a5f4302` entregados; T4 parcial).

## 0. Diagnóstico medido

| Medición | Resultado |
| --- | --- |
| Puntos de conexión que existen | **8**: `source-left/right/top/bottom` y `target-*` (`UmlClassNode.tsx:294-312`), todos en el **centro** de su lado |
| Quién elige el lado | `chooseHandles` (`UmlCanvas.tsx:213-229`) por eje dominante entre los centros de las dos clases; el usuario no participa |
| De dónde sale el trazado ortogonal | De los **rectángulos** (`orthogonal-routing.ts`): `uS/uT` son las aristas y `vS/vT` los centros de esos lados |
| Qué se guarda por relación | `view_style.route_adjustment` = `{axis, corridors, levels}` — **nada** sobre los extremos |
| `CHECK` de la columna | `jsonb_typeof(route_adjustment) = 'object'` (V14): agregar claves **no** necesita migración |
| Síntoma del usuario | Varias relaciones llegando al mismo centro de lado: no hay forma de separarlas |

## 1. Decisiones

| Decisión | Valor | Por qué |
| --- | --- | --- |
| ANC-01 | Los anclajes viajan **dentro del mismo `route_adjustment`**: `anchors: { source: {side, offset}, target: {side, offset} }` | Es parte del mismo ajuste de la ruta (dónde empieza y termina, no solo cómo dobla). El `CHECK` solo exige objeto, así que no hay migración: se amplía la lista blanca y la validación del handler |
| ANC-02 | `offset` es un entero con signo en px medido **desde el centro del lado** (positivo hacia +x en N/S y hacia +y en E/W) | Mantiene la decisión del usuario de la unidad anterior: el anclaje acompaña a la clase cuando se mueve o se redimensiona (ROR-02) |
| ANC-03 | El gesto es **un punto arrastrable por extremo**, proyectado sobre el borde del rectángulo (`projectAnchorOnBorder`) | Un solo punto que recorre el perímetro da gratis «deslizar por el lado y saltar al vecino en la esquina», sin lógica de cuatro puntos ni de saltos. Se reserva un margen de 12 px para no quedar en el vértice, donde el lado sería ambiguo |
| ANC-04 | Lados en el **mismo eje** (E/W + E/W, o N/S + N/S): la escalera actual, con `uS/vS` tomados del anclaje. Lados **perpendiculares**: **L forzada** con una sola esquina | La escalera tiene un eje; una pareja perpendicular no tiene grados de libertad ortogonales: la esquina queda donde se cruzan las dos líneas de anclaje. Declarado: los quiebres libres solo aplican al mismo eje |
| ANC-05 | El `axis` guardado sigue mandando sobre los corredores y niveles | Si los anclajes llevan la ruta al otro eje, `corridors`/`levels` se **ignoran** (trazado automático) pero **no se borran**; los anclajes sí se aplican. Es la regla ROR-04 llevada a los anclajes |
| ANC-06 | `chooseHandles` respeta el lado guardado | `sourceX/sourceY` alimentan FREE/STRAIGHT y la colocación de etiquetas: si no coinciden con la ruta dibujada, lo que se ve y lo que se exporta divergen. FREE/STRAIGHT **no** cambian su trazado: solo heredan el lado y su centro |

## 2. Plan de trabajo

- [x] **T1 (RED/GREEN, geometría pura)** `web/src/diagram/orthogonal-routing.ts`: `AnchorSide`/`RelationshipAnchor`/`ANCHOR_CORNER_MARGIN=12`, `autoAnchorSide` (reproduce exactamente la elección histórica de lado), `anchorPoint` (offset con signo relativo al centro del lado, recorte por margen y degradación al centro si el lado es más corto que el margen), `projectAnchorOnBorder` (proyección total al borde más cercano —el salto de lado en la esquina sale gratis—) y `orthogonalRoute` honrando `anchors`: el eje pasa a derivarse de los **lados** resueltos, la escalera mantiene los extremos clavados en los anclajes, y la pareja perpendicular emite la **esquina forzada** `[S, esquina, T]` sin manijas. `OrthoRoute` suma `pair`, `sourceAnchor` y `targetAnchor`. **Rojo observado**: `Tests 11 failed | 30 passed (41)`. **Verde**: 41/41 en el archivo; `npm test` = **498 pasan / 4 salteadas** (44 archivos + el opt-in); `npm run lint` exit 0. Los cuatro trazados sin `anchors` quedaron **idénticos** (las pruebas ya fijadas no se tocaron). Hallazgo del worker fuera de su alcance: `npm run lint` estaba **rojo desde antes** por una variable sin usar en el arnés de navegador que yo había commiteado; se corrigió aparte. Commits: `1b8f0d9` (T1) y `c68cfed` (lint).
- [ ] **T2 (RED/GREEN, persistencia)** `StyleSetHandler` (+ `ModelResource`) validan `anchors`; tipos web (`RouteAdjustmentResource`) y `mergeViewStyleUpdate`. Pruebas: ida y vuelta, y rechazo de lado inválido, offset no entero, claves de más, y `anchors` con un solo extremo.
- [ ] **T3 (RED/GREEN, interacción)** `UmlRelationshipEdge.tsx` dibuja los dos puntos de anclaje arrastrables (con la misma ceremonia de puntero que los corredores y `aria-label="Move relationship end anchor"`); `UmlCanvas.chooseHandles` respeta el lado guardado. Pruebas: enhebrado de `anchors` y de los lados elegidos por `buildFlowEdges`/`chooseHandles`.
- [ ] **T4 (verificación en navegador, con tope de tiempo)** Reusar `web/scripts/verify-rutas-ortogonales.mjs` midiendo el arrastre de un anclaje de lado a lado y la persistencia después de F5. **Con tope explícito**: si el arnés no llega al verde en una corrida acotada, se declara el resultado medido y se le entrega al usuario el paso manual, sin abrir otra ronda larga (la unidad anterior se comió dos rondas de ~50 min en ese camino).
- [ ] **T5 (compuertas y cierre)** `npm test`, `npm run lint`, `mvn -o -B verify`; registrar el conteo y el commit.

## 3. Fuera de alcance, y por qué

| Ítem | Motivo |
| --- | --- |
| Quiebres libres en parejas perpendiculares | Esa pareja no tiene grados de libertad ortogonales (ANC-04); los anclajes ya la mueven |
| Soltar el anclaje sobre **otra** clase (reconectar el extremo) | Cambia la semántica del modelo (`relationship.update`), no solo su dibujo: merece su unidad |
| Aplicar `offset` al trazado de FREE/STRAIGHT | Ahí el trazado es la polilínea guardada de `connector_path`; mezclar ambos modelos es otra decisión |
| Ignorar el ajuste al cambiar de eje en vez de conservarlo | ANC-05 lo conserva a propósito: volver al eje original tiene que devolver el trabajo del usuario |

## 4. Evidencia

Se completa al cerrar cada tarea, con el conteo medido y el commit de la unidad.
