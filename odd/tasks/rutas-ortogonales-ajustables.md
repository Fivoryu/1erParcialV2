# Rutas ortogonales ajustables: acomodar la relación a mano

**Estado:** unidad autorizada por el usuario el 2026-10-06 («Las dos cosas» + «Se mantiene relativa al trazado»).
**Origen:** pedido del usuario: «Quiero poder mover las relaciones cuando está en ortogonal, ya que quiero que sea fácil de mover y poder acomodar las relaciones a mi manera».
**Rama:** `feat/rutas-ortogonales-ajustables`, creada desde `main` (`b607b2a`, merge del PR #6).

## 0. Diagnóstico medido

| Medición | Resultado |
| --- | --- |
| Estilos de ruta disponibles | `STRAIGHT`, `FREE`, `ORTHOGONAL` (`V12__view_style.sql:12`, `StyleSetHandler:69`) |
| Manijas en el lienzo | `props.selected && style.routing !== 'ORTHOGONAL'` (`UmlRelationshipEdge.tsx:731`): **en ortogonal no aparece ninguna** |
| Trazado en ortogonal | Se **regenera** en cada render desde los rectángulos (`orthogonal-routing.ts:21`); los `connector_path` guardados se **ignoran** |
| Grados de libertad del trazado actual | Uno solo: la coordenada del corredor (el punto medio entre las dos aristas). Un trazado horizontal-dominante es `arista origen → (c, yS) → (c, yT) → arista destino` |
| Caso degenerado | Si `yS === yT` el generador devuelve **2 puntos** (`orthogonal-routing.ts:32-34`): línea recta, sin corredor visible y sin ningún grado de libertad |
| Persistencia de trazos | `connector_path` (x, y, ordinal **absolutos**) vía `geometry.set`; `view_style` (routing, corner, stroke, dashed) vía `style.set` |
| `view_style` en el XMI | Declarado **fuera del XMI** (`V12__view_style.sql:23`); no participa del hash de estado ni del undo |

**El hueco:** en ortogonal el trazado es función pura de la geometría de las clases, así que el usuario no puede separar dos relaciones que se superponen ni correr una línea para que no cruce una clase. No es un defecto del generador: es que **no hay ninguna superficie para expresar el ajuste**.

## 1. Decisiones

| Decisión | Valor | Por qué |
| --- | --- | --- |
| ROR-01 | El ajuste se guarda en **`view_style`**, no en `connector_path` | `connector_path` guarda puntos **absolutos** para `FREE`/`STRAIGHT`. Guardar ahí un desplazamiento **relativo** dejaría filas con dos significados incompatibles: al pasar a `FREE` esos offsets se leerían como posiciones y las manijas saltarían al origen. `view_style` ya es por (vista, relación), está declarada fuera del XMI y no participa del hash de estado |
| ROR-02 | La representación persistida es **relativa al trazado automático**: `{ axis, corridors: int[], levels: int[] }`, con K corredores y K−1 niveles | Es la respuesta elegida por el usuario («se mantiene relativa al trazado»): al mover una clase, el trazado conserva la forma y acompaña |
| ROR-03 | El trazado es una **escalera** `(uS,vS) → (c1,vS) → (c1,v1) → (c2,v1) → … → (cK,v(K−1)) → (cK,vT) → (uT,vT)`, con los puntos duplicados y los colineales colapsados | Con K=1 y sin niveles reproduce **exactamente** el trazado actual, incluidos los dos puntos del caso alineado: la unidad no regresiona nada |
| ROR-04 | El eje dominante se guarda con el ajuste; si al mover las clases el eje cambia, **no se aplica** el ajuste (se usa el automático) pero **el ajuste sigue guardado** | Los corredores de un eje no tienen significado en el otro. Se declara como frontera medida, no se inventa una reinterpretación |
| ROR-05 | El comando es `style.set`, que suma `routeAdjustment` a su lista blanca. Se rechaza si `routing` no es `ORTHOGONAL`, si `levels` no mide `corridors − 1`, si algún valor no es entero o si sale de ±100000. `null` lo borra | Un ajuste ortogonal colgando de un `FREE` es un estado inválido; es más barato rechazarlo que defenderlo |
| ROR-06 | Las manijas aparecen **solo con la relación seleccionada y en ortogonal**: una por corredor visible, una por nivel, un `+` que agrega un quiebre y un `−` que quita el último. «Reset route» del panel también borra el ajuste | Es el mismo lenguaje que ya usa `FREE` (`UmlRelationshipEdge.tsx:731-760`), sin inventar una interacción nueva |
| ROR-07 | El ajuste **no se exporta al XMI** | `view_style` ya está declarada fuera del XMI; la ruta del conector en el export la decide EA. Igual que hoy |
| ROR-08 | El arrastre se verifica en **navegador real** (`web/scripts/`), no con la suite | El repositorio no tiene jsdom: `renderToStaticMarkup` no ejecuta efectos ni layout. La geometría se prueba en funciones puras; el cableado, en `tsc`/`eslint` y en la captura real. Misma declaración que PPL |

### Contrato de geometría (lo que hay que implementar)

Marco horizontal (`|Δx| ≥ |Δy|`): `u` = eje del recorrido, `v` = eje perpendicular.
`uS` = arista de salida del origen, `uT` = arista de entrada del destino, `vS`/`vT` = centros de los lados.
`autoCorridor = (uS + uT) / 2`; `levelMid = (vS + vT) / 2`.
`c_i = autoCorridor + corridors[i]`; `v_j = levelMid + levels[j]`.
Marco vertical: simétrico (se intercambian los papeles).

## 2. Plan de trabajo

- [x] **T1 (RED/GREEN, geometría pura)** `web/src/diagram/orthogonal-routing.ts`: `orthogonalRoute(source, target, adjustment?)` con el colapso de duplicados y colineales, más los helpers puros `isAdjustmentApplicable`, `addJog(adjustment, axis, levelOffset)`, `defaultJogLevelOffset(route)`, `removeJog`, `slideCorridor` y `slideLevel`; `orthogonalWaypoints` delega en `orthogonalRoute(...).points`. **Rojo observado**: `TypeError: (0, orthogonalRoute) is not a function`, `Tests 11 failed | 10 passed (21)`. **Verde**: `Tests 22 passed (22)`. **Defecto que encontró la revisión del padre**: en la base alineada (`vS === vT`) el `+` no cambiaba nada — el nivel caía en la misma recta y el colapso lo borraba, así que la prueba se había adaptado en lugar de resolverlo; se corrigió con `levelOffset` en `addJog` y `defaultJogLevelOffset(route)`, que devuelve `DEFAULT_BEND_SPACING` solo cuando el corredor base es degenerado. **Segundo rojo**: `Tests 2 failed | 20 passed (22)`, con `defaultJogLevelOffset is not a function` y el caso alineado todavía recto. **Compuertas**: `npm test` (Windows) = **467 pasan / 4 salteadas**, 44 archivos + el opt-in; `npm run lint` = exit 0. **Refutación del verificador independiente**: el trazado **renderizado** es idéntico en una barrida de 3125 casos; en rectángulos degenerados (ancho o alto 0) la versión nueva **colapsa** puntos duplicados que la vieja emitía —mismo dibujo, arreglo más corto—, y no muta las entradas ni lanza con ajustes malformados. Commits: `0470ed4`.
- [ ] **T2 (RED/GREEN, persistencia)** `V14__view_style_route_adjustment.sql` + `ViewStyle` + `StyleSetHandler` + `ModelResource.ViewStyleResource` + `ProjectApiService`, y en el web `api/types.ts` + `ViewStyleUpdate` de `commands.ts`. Pruebas: ida y vuelta del ajuste por `style.set`; rechazo de forma inválida, de valores fuera de rango y de ajuste sin `ORTHOGONAL`; `null` lo borra.
- [ ] **T3 (RED/GREEN, interacción)** `UmlRelationshipEdge.tsx` + `UmlCanvas.tsx` + `PropertiesPanel.tsx`: manijas en ortogonal, callback `onRouteAdjustmentChange` por el mismo coalescer hacia `style.set`, y «Reset route» que borra el ajuste. Pruebas de presencia/ausencia de manijas por marcado estático.
- [ ] **T4 (verificación en navegador)** Captura real con `web/scripts/`: seleccionar una relación ortogonal, ver las manijas, arrastrar el corredor, agregar un quiebre, recargar y comprobar que la forma persistió.
- [ ] **T5 (compuertas y cierre)** `npm test`, `npm run lint`, `mvn -o -B verify`; registrar el resultado y los commits.

## 3. Fuera de alcance, y por qué

| Ítem | Motivo |
| --- | --- |
| Exportar el ajuste al XMI | `view_style` está declarada fuera del XMI; ampliarlo es otra unidad y otro contrato |
| Reescribir el generador con evitación de obstáculos | El pedido es **poder acomodar a mano**, no que la app adivine |
| Guardar el ajuste en el undo colaborativo | Hoy `style.set` tampoco entra al undo (ni routing ni corner radius): sería una mejora aparte, no una regresión de esta unidad |
| Reinterpretar un ajuste de otro eje al cambiar el eje dominante | Inventar esa semántica exige una medición propia; se declara la frontera (ROR-04) |

## 4. Evidencia

Se completa al cerrar cada tarea, con el conteo medido y el commit de la unidad.
