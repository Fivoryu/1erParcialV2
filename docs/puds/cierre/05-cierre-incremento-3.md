# Acta de cierre del Incremento 3

**Fecha:** 2026-10-03
**Rama:** `feat/incremento-3-colaboracion`, apilada sobre el `main` del Incremento 2; la parte de intercambio XMI se implementó en paralelo en
`feat/xmi-intercambio` por la sesión `01a0ffa2` y fue consolidada (`f2fd947`, luego `0fac48e` y `a97a902`).
**Alcance cerrado:** S0 a S7 del plan
[`odd/tasks/incremento-3-colaboracion.md`](../../../odd/tasks/incremento-3-colaboracion.md) — C1 a C26, con S6 (C22 a C24) ejecutada por la sesión hermana
y consolidada. Las unidades de trabajo de este incremento son las de `git log --oneline 61bf2d2..HEAD`: **no se cita el número**, por la misma razón que
en las actas anteriores.
**Producto entregado:** dos usuarios registrados e invitados editando el mismo diagrama en vivo —presencia con color, cursor remoto, chat persistido—,
y **el bloqueo de exportación mientras un editor tiene la concesión**, con el editor nombrado y el reclamo de control; además del **intercambio XMI del
dialecto de EA con su reporte de pérdidas**, y el arnés de aceptación de dos sesiones contra la pila real.

## 1. El corte visible, con veredicto

> Registrar dos usuarios distintos, iniciar sesión con cada uno, y que el creador invite al otro como EDITOR.
> Los dos entran al mismo diagrama: cada uno ve quién está, el elemento que el otro toca resaltado y el cursor
> remoto en vivo. Un cambio de uno aparece en el otro sin recargar. Los dos chatean y el historial sobrevive a
> la recarga. Mientras uno tiene una edición activa, la exportación —el ZIP del backend generado y el XMI del
> diagrama— queda bloqueada, con el nombre del editor y un botón para reclamar el control cuando su lease vence.
> El XMI exportado es del dialecto de EA y trae su reporte de pérdidas.

| Pieza | Veredicto | Evidencia |
| --- | --- | --- |
| Identidad propia (registro, login, token opaco, BCrypt) | **Cerrado** | Unidad S1/S1b; las rutas se midieron en el arnés de aceptación (`AuthenticationController`) |
| Invitaciones y roles (CU-03, CU-13) | **Cerrado** | Unidad S2; invitación y aceptación verificadas de punta a punta en el arnés |
| Canal SSE, presencia y sincronización | **Cerrado, con el techo del arnés declarado** | Unidades S3 (C11–C15b). El corte de dos navegadores lo cubre la web; el arnés de aceptación lee el **snapshot inicial** de presencia y declara no medir la entrega en vivo del canal |
| Chat persistente (C16–C18) | **Cerrado** | Historial con autor y hora, evento en vivo, sobrevive a la recarga; probado en el arnés (publica A, lee B) |
| Concesión de edición: servicio (C19) | **Cerrado** | `EditingLeaseService` con reloj inyectable; 14 pruebas unitarias; linaje `review-43b6f804e24d884e` aprobado |
| Guardas de exportación y endpoints de la concesión (C20) | **Cerrado** | Cinco rutas `editing-lease` + guarda en `POST /generations` (outcome) y en la descarga (409); suite backend **347/0**; linaje `review-9d023011615ed35e` aprobado |
| Web: aviso, editor nombrado y Reclamar control (C21) | **Cerrado** | Componente presentacional con gate por `reclaimable`, mapeador canónico de los dos portadores, latido de 15 s sólo siendo titular; web **169/4**; linaje `review-0451a261a69d4f06` aprobado |
| Intercambio XMI, dialecto EA y reporte de pérdidas (C22–C24, sesión hermana) | **Cerrado, con GAP-07 vigente** | Exportación e importación medidas entre sí y contra el fixture `docs/fixtures/xmi/Prueba3.xml` byte-exacto; arnés XMI 18/0. **El round-trip contra EA en vivo sigue sin poder verificarse** |
| La guarda del export XMI conectada | **Cerrado** | Conectada por la sesión hermana al consolidar (`XmiExportService.java:61`, una línea tras el snapshot); verificada en **dos autoridades nativas** y reproducida en la suite consolidada (`XmiExportApiTest` 5/0) |
| Arnés de aceptación de dos sesiones (C25) | **Cerrado** | `tools/collaboration-acceptance/`: **34 checks en verde en dos corridas completas, exit 0**, conteo de contenedores por etiqueta **0/2/0**; linaje `review-2656a8f219489042` (tier `high`, cuatro lentes) aprobado |
| Contratos y acta (C26) | **Cerrado, sin capturas** | §4.4 del contrato transversal documenta la concesión; este acta. **Las capturas de pantalla no se produjeron** — no hay entorno de navegador en este flujo de trabajo, y se declara |

**Veredicto del corte:** **cerrado de punta a punta a nivel API, con la experiencia de dos navegadores cubierta por las pruebas de integración web.**
El arnés de aceptación reproduce el recorrido completo contra la pila real en Docker —no una simulación— y el techo que declara está escrito, no oculto.

## 2. Definición de terminado, con evidencia

- **El bloqueo nombra al editor y ofrece el reclamo.** Las cinco claves (`editorDisplayName`, `targetType`, `targetId`, `reclaimable`,
  `retryAfterSeconds`) viajan en orden exacto, construidas **una sola vez** en el servicio (`blocker(modelId)`) y con dos portadores congelados: 409 +
  `details[0]` en endpoints de archivo, 200 + `result` en `POST /generations` (convención D7). Las aserciones de las pruebas fijan el **orden** y la
  **presencia** de la clave, no sólo los valores.
- **El reclamo no es automático.** `acquire` sobre una concesión viva ajena es 409 sin despojar; `reclaim` sólo procede si la ajena ya no está viva;
  `reclaimable: true` + `retryAfterSeconds: 0` es la señal del servidor, y el botón de la web se habilita **sólo** con ella.
- **La vivacidad tiene una sola autoridad.** El servicio, con su reloj. El cliente compara `expiresAt` con el suyo y declara el desfase; el siguiente
  rechazo del servidor trae la verdad.
- **El TTL vence solo.** 30 s por defecto, configurable; observado en pruebas con reloj acortado por propiedad (`PT2S`) y **Awaitility**, sin dormir
  30 segundos en ninguna suite.
- **La exportación XMI quedó protegida.** No fue una línea de esta sesión: la conectó la hermana al consolidar, y quedó verificada por dos autoridades
  nativas independientes más la reproducción en la suite consolidada.
- **El arnés mide la pila real.** `docker compose -p collaboration-acceptance` con la imagen del Dockerfile del backend; conteo de contenedores **por
  etiqueta de proyecto** (nunca global: el daemon es compartido y hay Testcontainers ajenos); cláusula de rechazo al arranque ante restos etiquetados;
  teardown `down -v` verificado con el conteo en 0.

## 3. Defectos encontrados y corregidos dentro del incremento

1. **Doble fuente del conflicto en la guarda de generación (C20).** El `catch` prefería una lectura fresca de `blocker()` antes que el conflicto que la
   propia excepción ya llevaba: una segunda lectura del reloj y una carrera que cambiaba los valores observables. Corregido a **fuente única** (el
   conflicto lanzado), con aserción nueva probada no vacua por mutación.
2. **Guarda de desmontaje ausente en el adaptador web (C21).** Cuatro caminos asíncronos escribían estado sin protección tras el desmontaje (no-op
   silencioso en React 18, pero una garantía falsa). Corregido con `mountedRef`; los 13 escritores de estado del hook quedaron enumerados con su guarda.
3. **Concesión permitida a quien no debe tomarla (texto del diseño, no código).** El congelamiento de C20 decía que el operador entraba a los cinco
   endpoints; medido, la lista blanca del interceptor lo rechaza antes — y eso es lo correcto. Se corrigió el doc, no el código.
4. **Semilla faltante en el arnés (C25).** Dos corridas diagnósticas fallaron 2 checks por ausencia de un clasificador soportado; el arnés **las reportó
   y no las omitió**, y la semilla quedó incorporada siguiendo el fixture de `GenerationApiTest`.
5. **Enmiendas de diseño por medición del escritor (C25).** La presencia no tiene roster por HTTP (POST 204): el arnés lee el snapshot inicial de SSE y
   corta. Y la validación del run va **antes** de la guarda en la descarga: la prueba prepara un run primero. Ambas quedaron registradas como
   correcciones autorizadas con atribución.

## 4. Las siete remediaciones, sin adorno

Las unidades de S3 y S4 (C12b, C14b, C15, C15b, C16, C17, C18) quedaron commiteadas con desviación declarada cuando el relay de revisión estuvo caído.
**Todas fueron revisadas después**, cada una en su propio linaje con un worktree anclado por unidad (`baseRef` = padre, creado y borrado con el git de
Windows), y las siete cerraron **aprobadas, cero correcciones, cero bloqueos** — con seis hallazgos informativos que van a la deuda del §6. No reescriben
historia: la desviación original queda como registro, y lo que cambió es que cada unidad tiene hoy una autoridad quemada.

## 5. Lo que NO se verificó

| Hueco | Por qué | Qué lo cubre |
| --- | --- | --- |
| **Round-trip contra Enterprise Architect en vivo** | **GAP-07**: el servidor de EA no responde desde este entorno | Nada; la exportación e importación se miden entre sí y contra el fixture. Nadie puede declarar el round-trip verificado |
| **Entrega en vivo del canal SSE** desde el arnés de aceptación | El arnés lee el snapshot inicial y corta, por decisión de diseño | Las pruebas de integración web de C14/C15, que cubren la entrega en vivo |
| **El timing del TTL** en el arnés | Es territorio de C19 con reloj controlado | Las pruebas unitarias y de integración de la concesión |
| **La guarda de desmontaje del adaptador web**, en runtime | El proyecto no renderiza hooks | Lectura de código (`13/13` escritores guardados) y la mutación de remoción **medida como hueco declarado**: ninguna prueba la capturaría |
| **El desfase de reloj del cliente** | Inherente a exponer `expiresAt` sin un segundo reloj del servidor | El servidor sigue siendo autoridad; el rechazo trae la verdad |
| **Capturas de pantalla** | No hay entorno de navegador en este flujo | Las pruebas de markup estático y los estados fijados por prueba |
| **Escalado a múltiples instancias** | La concesión es en memoria de una sola instancia (C4) | Declarado; persistirla sería otro diseño |

## 6. Riesgo residual y deuda visible, con responsable

- **Diez hallazgos informativos acumulados** de las revisiones nativas (C19, las seis de las remediaciones, C20 y los dos de C21, más los cuatro de C25
  citados en el propio linaje): ninguno abrió corrección, el texto no expuesto no se inventa, y la evaluación/priorización queda como trabajo posterior.
  Entre ellos, los auto-atribuidos: el *check-then-act* del servicio de concesión (`InMemoryEditingLeaseService`) y del adaptador web (`useEditingLease`),
  el `acquire` por cada acto de edición (patrón deliberado del congelamiento), y el volumen/imagen del arnés que el teardown no borra.
- **Los límites declarados por unidad** quedan en el plan (§S3–§S7) y no se repiten acá: son contrato, no recordatorio.
- **El montaje del aviso de concesión en el panel XMI** quedó **pendiente por decisión del usuario** (§14.10 de la sesión hermana), con el contrato ya
  escrito y medido; retomarlo no pide decisiones nuevas.
- **Responsable de cada deuda:** la sesión `01a0f830` mantiene este plan y su espejo; la sesión `01a0ffa2` mantiene el arnés XMI y su §14.

## 7. Estado medido

- Backend: `mvn verify` sobre la base consolidada = **347 pruebas, 0 fallos, 0 errores, 0 omitidas**, BUILD SUCCESS.
- Web: **169 pasan / 4 omiten**, lint y `tsc` en cero, build Vite limpio.
- Arnés de colaboración: **34 checks, 0 fallos**, dos corridas, teardown verificado.
- Arnés XMI: **18/0**; `pytest tools`: 54.
- Toda unidad con revisión nativa aprobada y autoridad quemada; los shas y linajes están en el plan.

## 8. Entrada al próximo incremento

El producto queda en el estado del corte §1. Las fronteras que el próximo diseño hereda: **GAP-07** (round-trip de EA), la concesión de una sola
instancia, la deuda informativa del §6, y la importación XMI sin el aviso de concesión montado en su panel (pendiente del usuario, contrato listo).
Ninguna de las tres primeras lo bloquea; todas están escritas donde el próximo lector va a encontrarlas.
