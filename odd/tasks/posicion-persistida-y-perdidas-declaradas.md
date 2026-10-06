# Lo que se ve es lo que se exporta: posición persistida y pérdidas declaradas

**Estado:** unidad autorizada por el usuario el 2026-10-05 («Dale»), después del caso `Diagrama01`.
**Origen:** dos síntomas que el usuario reportó como uno: los atributos y **las relaciones** no aparecían al importar en EA. Los atributos ya se cerraron (`odd/tasks/xmi-compartimentos-ea.md`); acá va lo que el caso dejó al descubierto.
**Rama:** `fix/posicion-persistida-y-perdidas-declaradas`, creada desde `main` (`eb12a62`, el merge del PR #3) por pedido del peer: su incremento de voz sale de esa base y así ninguno de los dos arrastra diffs viejos en el próximo merge.

## 0. Diagnóstico medido

Sobre `Diagrama01` del usuario, ya con el contenedor actualizado:

| Medición | Resultado |
| --- | --- |
| Clases en el modelo | 4 (`Dueño`, `Animal`, `vacunas`, `cartilla_Veterinario`) |
| Geometría guardada en la vista por defecto (`element_geometry`) | **3** — sin geometría: `['Dueño']` |
| Lo que el lienzo muestra | **4 nodos** y la línea `Dueño—Animal`: las clases sin geometría caen en una **grilla de respaldo** (`unplacedClassifierIds`, `web/src/diagram/UmlCanvas.tsx:96-104`) |
| Cuándo se persiste una posición | Solo al arrastrar: `onNodeDragStop` → `settleNodeGeometry` (`UmlCanvas.tsx:428-436`, `:550`) |
| Figuras en el export | 3 (sin `Dueño`) |
| Colocación del conector | **ninguna** |
| Pérdida declarada por ese conector | **ninguna**: `appendExtensionConnectorPlacements` lo omite en silencio |
| Camino de la clase sin posición | La creó el asistente; es el caso que cerró `b403f41` («una clase creada por el asistente existía en el servidor y nunca se veía») |

**Dos huecos independientes, y los dos producen el mismo síntoma:**

1. **El lienzo dibuja posiciones que el modelo no tiene.** La grilla de respaldo es un estado de render, no del modelo: cualquier otro consumidor (el export, otra pestaña, EA) ve otra cosa. El usuario tuvo que arrastrar `Dueño` para que su posición existiera; recién ahí el export escribió la figura y el conector, y **EA dibujó la relación**. Confirmado por el usuario: «Si se ve».
2. **El emisor esconde esa pérdida.** Cuando un extremo no tiene figura, el conector no se puede colocar, y eso no aparece en la lista de pérdidas del export, cuando el resto del emisor declara todo lo que no puede transportar.

Y un tercer defecto cosmético ya declarado: los atributos se dibujan con **`{bag}`** porque nuestros `<ownedAttribute>` omiten `isStatic/isReadOnly/isDerived/isOrdered/isUnique/isDerivedUnion` que el export propio de EA sí escribe (`isUnique="true"` en `docs/fixtures/xmi/Prueba3.xml`). EA lee la ausencia como no-único.

## 1. Decisiones

| Decisión | Valor | Por qué |
| --- | --- | --- |
| PPL-01 | La **app** persiste la posición que ya está mostrando, una vez por clase y por el mismo camino que el arrastre (`geometryPayload` + el `coalescer` → `props.onCommand`) | Es la única forma de que «lo que se ve» sea «lo que el modelo tiene»; sin eso el síntoma vuelve cada vez que el asistente crea una clase |
| PPL-02 | El **backend no inventa posiciones** | El layout es del cliente: el backend no conoce la grilla y el emisor ya declara por principio que no sintetiza geometría |
| PPL-03 | El emisor declara la pérdida del conector no colocable | Una pérdida silenciosa es peor que una declarada: es la que hizo que este caso se viera como «el XMI está mal» |
| PPL-04 | Se emiten los flags de propiedad **medidos** del export de EA (`isStatic="false" isReadOnly="false" isDerived="false" isOrdered="false" isUnique="true" isDerivedUnion="false"`) | Son los defaults del metamodelo y lo que EA escribe; la omisión es la que produce el `{bag}`. Se mide el antes/después en EA |

## 2. Plan de trabajo

- [x] **T1 (RED)** `XmiSemanticEmitterTest`: una relación con un extremo sin figura tiene que declarar la pérdida del conector. **Rojo observado**: `could not find the following elements: [Loss[name=extension_connector_unplaced_end, count=1]]` — `Tests run: 9, Failures: 1`. La prueba nueva triangula: en la misma fixture hay una relación entre dos clases colocadas y afirma que esa **sí** emite colocación, y que la pérdida cuenta 1 y no 2.
- [x] **T2 (GREEN)** `XmiSemanticEmitter.appendExtensionConnectorPlacements` (`:793-806`): se separa el chequeo de emisibilidad del de figura; sin figura se registra `extension_connector_unplaced_end` y se sigue. Los motivos que ya tenían pérdida (`ends != 2`, clase de relación desconocida, extremo no emisible) siguen igual. **Verde**: `Tests run: 9, Failures: 0`.
- [x] **T3 (RED/GREEN)** `XmiSemanticEmitter.appendAttribute` (`:164-171`): emite los seis flags medidos de `docs/fixtures/xmi/Prueba3.xml` (`isUnique="true"`, el resto `"false"`), también en el camino de los literales de enumeración. **Rojo observado**: `nickname isStatic expected: "false" but was: ""` — `Tests run: 9, Failures: 1`; **verde**: `9/0`. Un solo cambio de expectativa preexistente quedó obsoleto (la lista completa de pérdidas de `emitsMeasuredExtensionElementAndAttributeLayoutWithoutInventingGeometry`, que ahora incluye 3 conectores sin figura) y se actualizó dentro del mismo archivo de pruebas. Commit: `10c7f97`.
- [x] **T4 (RED/GREEN, web)** Helper puro `web/src/diagram/unplaced-geometry.ts` con `buildUnplacedGeometryRequests(model, nodes, alreadyRequested)` (nombre estable, acordado con el peer para que lo reutilice el caso de relaciones sin geometría) y un **efecto aparte** en `UmlCanvas.tsx:374-395`, con guarda `persistedUnplacedRef` (`:323-325`) y el mismo camino que el arrastre (`coalescer.schedule` + `flush` → `props.onCommand`, clave `classifier:<id>`). **Rojo observado**: `Cannot find module './unplaced-geometry'`; **verde**: `8 tests passed` del módulo, suite web completa **339 pasan / 4 salteadas** (`canvas.live.test.ts` es opt-in) y `npm run lint` (`eslint . && tsc --noEmit`) limpio.
- [x] **T5 (verificación)** Verificador independiente: `mvn -o -B -f backend/pom.xml verify` = **460 pruebas, 0 fallos, 0 errores, 0 salteadas, BUILD SUCCESS** (la base es `main`, que ya trae el paquete de validación: no se compara contra el 427 de la rama anterior); `npm test` = **339 pasan / 4 salteadas** (39 archivos, y la salteada es `canvas.live.test.ts`, opt-in); `npm run lint` = exit 0 sin hallazgos; arnés de aceptación XMI = **18 pasan, 0 fallan**, con sha256 de la emisión `0133b9620d06115b169f89597491f8881cb002b141a5462f74a998ffdc7e4235`; y `extension_connector_unplaced_end` **no aparece** en el sample, que es lo correcto: en esa fixture todos los extremos tienen figura. `pytest tools -q` quedó bloqueado en el primer intento por una instrucción mía equivocada (dije que Python corría en WSL, y ahí no hay pytest); remedido por el Python de Windows: **54 pasan** (`python -m pytest tools -q`, exit 0). Seis compuertas, todas verdes.
- [x] **T6 (verificación en vivo)** Sonda sobre el sample regenerado (`C:\Users\HP\AppData\Local\Temp\ea-probe\bag-after\diagram-20261005-231301-Default.png`): las features siguen dibujándose y los atributos salen **sin `{bag}`** (`- label: String = guest`, `~ nickname: String [0..1]`), contra los renders anteriores donde cada atributo lo traía. La pérdida declarada no es demostrable en vivo sin fabricar una clase sin posición en el modelo del usuario: esa evidencia es la prueba determinista (T1) más la confirmación del verificador de que el camino existe y que el sample no lo ejercita.
- [ ] **T7 (docs y commits)** Registrar el resultado acá y en `odd/tasks/xmi-intercambio.md` §16, y un commit por unidad de trabajo.

## 3. Fuera de alcance, y por qué

| Ítem | Motivo |
| --- | --- |
| Que el asistente asigne posición al crear una clase | Resolvería el caso de origen, pero es un cambio de contrato del plan del asistente: merece su propia unidad |
| Reordenar el layout de las clases ya colocadas | Nada de lo reportado lo pide |
| Persistir también la geometría de los extremos de relación (`connector_path`) | El propio emisor lo declara como pérdida medida (§3.4 de `xmi-intercambio.md`); sigue igual |
| Mandar la colocación automática con un origen no-usuario | El backend ya tiene `CommandOrigin.AI` (lo usa el asistente), pero el pedido web no puede expresarlo: `ModelCommandRequest` no tiene campo `origin` y el sobre cae en `MANUAL` (`web/src/api/envelope.ts`, armado en `web/src/pages/ProjectEditorPage.tsx:475`). Consecuencia declarada: la colocación automática entra al historial de undo y marca el elemento como tocado. Plomar el origen son dos archivos fuera de la superficie de esta unidad |
| Afirmar el efecto del cableado con una prueba de componente | El repositorio no tiene jsdom ni testing-library: `canvas.live.test.ts` es opt-in (`VITE_API_BASE_URL`) y renderiza por `renderToStaticMarkup`, que no ejecuta efectos. La evidencia del cableado es el módulo puro más `tsc`/`eslint`, y así se declara |

## 4. Evidencia de la medición

- Export real: `C:\Users\HP\AppData\Local\Temp\ea-probe\live2.xmi` (3 figuras, 0 conectores, sin pérdida declarada).
- Render en EA del export real: `C:\Users\HP\AppData\Local\Temp\ea-probe\live-real\diagram-20261005-171013-Class_Diagram.png`.
- Sonda reutilizable: `C:\Users\HP\.pi\agent\skills\enterprise-architect-headless\assets\probe.ps1`.

## 5. Cierre

**Commits** en `fix/posicion-persistida-y-perdidas-declaradas`: `10c7f97` (backend: la pérdida declarada y los flags) y los commits de la parte web y de estos documentos.

**Lo que la unidad arregla, y cómo se sabe:**

| Hueco | Evidencia |
| --- | --- |
| El conector se perdía en silencio | Rojo observado (la pérdida esperada no se encontraba), verde 9/0, y una prueba que triangula contra una relación entre dos clases colocadas |
| Los atributos salían `{bag}` | Rojo observado (`nickname isStatic expected "false" but was ""`), verde 9/0, y el render en EA del sample regenerado sin ningún `{bag}` |
| La posición vivía solo en el render | Rojo observado (módulo inexistente), verde 8/8 del helper, 339/4 en la suite web y `lint` limpio |

**Lo que la unidad NO resuelve, declarado:**

1. **El origen `MANUAL`** de la colocación automática (undo e higiene de “tocado”): **cerrado por la unidad SPP, sin código**, y por medición. El origen no gobierna ninguna de las dos cosas: la captura del undo no lo lee (`UndoCaptureService.recordAccepted` guarda toda operación aceptada, y en la base hay filas `AI` capturadas igual que `MANUAL`) y el resaltado de «tocado» sale de `presence` (`ProjectEditorPage.tsx:507`). Plomarlo no cambiaría nada observable — la inferencia que esta unidad había dejado escrita quedó refutada. Detalle en `odd/tasks/seguimientos-de-la-persistencia-de-posicion.md` §5, con la pregunta que sí queda y que es **decisión de producto**: la colocación automática es deshacible (70 filas de `geometry.set` en el `undo_log`), y excluirla del historial exige una señal en el camino de captura.
2. **El cableado no tiene prueba de componente**: el repo no tiene jsdom y `canvas.live.test.ts` es opt-in y no ejecuta efectos. La evidencia es el módulo puro más `tsc`/`eslint`.
3. **La relación que originó todo** (`Dueño—Animal` en `Diagrama01`) se resolvió arrastrando la clase: con ambos extremos colocados el export ya emite la figura y el conector. Esta unidad evita que vuelva a pasar, pero no toca el caso ya resuelto en el modelo del usuario.

**Revisión nativa.** Linaje **`review-7d7fe534161a2a76`**: tier `medium`, una lente (`review-reliability`), **7 archivos / 455 líneas** —esta vez el alcance es exactamente la unidad, no la rama—, presupuesto de corrección 200, **aprobada al primer intento** y autoridad **quemada** (`gentle-ai.review-acknowledged/v1`), revisión consumida `sha256:3d5b57bf201f8c8713192ca87bf971836dff30f3ab05189f0f0f0498835531e6`.

Dejó **un aviso no bloqueante**, `R3-001` (lente de confiabilidad, `WARNING`, disposición `informational`) en `web/src/diagram/UmlCanvas.tsx:384-389`. El cierre no trae el texto del hallazgo —nombra el linaje y la ubicación, no la prosa—, así que **se reconstruye desde la ubicación y se declara como reconstrucción**: esas líneas son el `for` que agrega cada clase a `persistedUnplacedRef` **antes** de enviar el comando. La consecuencia es que un comando rechazado (sin concesión de edición, o una envoltura que falla) **no se reintenta en ese montaje**, y la clase vuelve a quedar sin geometría hasta el remount: el mismo síntoma que esta unidad arregla. Fue un canje deliberado al escribirla (marcar antes de enviar evita el lazo), y el aviso es correcto. **Cerrado por la unidad SPP** (`cf8ab7f`): el reintento es ahora una política pura, acotada y por revisión del modelo —un pedido por clase y por revisión, retirado cuando el rechazo deja de serlo—, con el detalle y una segunda corrección que salió de revisar ese mismo arreglo en `odd/tasks/seguimientos-de-la-persistencia-de-posicion.md` §6.

**Higiene.** Como en la unidad anterior, el cierre volvió a diferir la limpieza de la vista de candidato (`candidate-view-cleanup-failed`) y se retiró fuera de banda.
