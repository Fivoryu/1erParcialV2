# Revisión de diagramas en Android

> Objetivo: implementar la lámina seleccionada en la app Flutter y las capacidades de revisión acordadas; rama `feat/revision-diagramas-movil` en `.atl/worktrees/revision-diagramas-movil`.
> Entrega: `ask-on-risk`, con `chain_strategy=feature-branch-chain` elegida; estimación inicial aproximada 1.500–2.500 líneas autorales. Pruebas previstas: Flutter y backend existentes; validación visual en dispositivo o emulador cuando esté disponible.
> Espejo Engram: `odd/revision-diagramas-movil/tasks` (observación 5323).

## Specs

**S1 — Solicitud original.** «Quiero añadir un app móvil donde pueda detectar cambios del diagrama como pull request, commits, etc. Tal y cómo es en github».

**S2 — Lámina y entrega solicitadas.**

> Implement the design artboard design/revision-diagramas-movil/03-comparacion.html
>
> Artboard: design/revision-diagramas-movil/03-comparacion.html
> Title: Comparación primero
> Tradeoff: Destaca diferencias del modelo desde el inicio; muestra menos actividad simultáneamente.
>
> Implement it in the project's real stack. Use the artboard as the visual spec: map its parts to the project's existing components and styles, and do not copy the artboard HTML verbatim.
> Take colors, typography, spacing, radius and shadows from design/system.md, the project's token source.
>
> Report progress with the gentle_design tool: action "handoff", target "revision-diagramas-movil/03-comparacion.html", status "in_progress" when you start; status "implemented" when it is done, with previewUrl set to the running app's loopback URL (localhost, 127.0.0.1 or [::1]) when one is running, so the canvas can compare it with the artboard.
> This design is not linked to an ODD feature document: size it under the project's ODD rules as usual.
> This handoff itself commits nothing.

**S3 — Alcance elegido en el diálogo.** Opciones seleccionadas: «Consultar y revisar», «En la app Flutter existente», «Solo los del diagramador», «Android» y «Ambas acciones» para «Pedir cambios sin cerrarla» y «Cerrar sin fusionar». Las acciones de revisión se presentan según permisos.

**S4 — Avisos y participantes elegidos en el diálogo.** Opciones seleccionadas: «También con notificaciones push»; eventos «Solicitudes de fusión» y «Revisiones»; destinatarios «Solo a quienes participan» y «El autor los elige en cada solicitud»; servicio «Usar Firebase Cloud Messaging». Los commits y las ediciones individuales no fueron seleccionados para avisos push.

**S5 — Límites y decisiones pendientes.** La elección posterior de implementar S2 sustituye la espera indicada en «No crees todavía sino hagamos un diseño y lo apruebo para poder implementarlo». El efecto de pedir cambios sobre la fusión no se decidió; no inventar credenciales, crear cuentas externas ni alterar reglas de fusión sin resolver las decisiones correspondientes.

**S6 — Entrega.** «Haz un push al main para que lo haga yo en otra pc».

## Tasks

- [x] **T1** — S1–S5 — `inline` — Preparar rama de trabajo y preservar el estado previo; comprobar el alcance Git sin incorporar cambios ajenos. Estado: `done`, worktree aislado creado y registrado; commit: no corresponde; riesgo: ninguno.
- [ ] **T2** — S1–S3 — `delegated` — Navegar desde el proyecto a su lista de solicitudes de fusión usando la sesión y API existentes, sin mutaciones; incluir pruebas y documentación. Estado: `blocked`, espera decisión del usuario tras agotarse la inicialización de CodeGraph; commit: pendiente; riesgo: ítem 2 (sesión y acceso autenticado).
- [ ] **T7** — S1–S3 — `delegated` — Completar comparación semántica y actividad parcial de solicitudes y commits en la experiencia móvil, con pruebas y documentación; depende de T2. Estado: pendiente; commit: pendiente; riesgo: ítem 2 (sesión y acceso autenticado).
- [ ] **T3** — S1–S3, S5 — `inline` — Completar acciones de revisión solicitadas, incluida la capacidad faltante de pedir cambios, según permisos y decisión de fusión. Estado: pendiente; commit: pendiente; riesgo: ítems 2 y 4 (autorización y concurrencia).
- [ ] **T4** — S3–S4 — `delegated` — Permitir seleccionar y conservar revisores por solicitud en web y backend, con pruebas de permisos. Estado: pendiente; commit: pendiente; riesgo: ítems 1, 2 y 4 (persistencia/migración, permisos y concurrencia).
- [ ] **T5** — S4–S5 — `inline` — Entregar avisos push Android a participantes para los eventos elegidos mediante el servicio elegido. Estado: pendiente; commit: pendiente; riesgo: ítems 2, 4 y 5 (datos de dispositivo, concurrencia y dependencias/servicio).
- [ ] **T6** — S1–S5 — `inline` — Ejecutar suites aplicables y verificación funcional/visual en el entorno disponible; actualizar el handoff solo al terminar. Estado: pendiente; commit: pendiente; riesgo: según los cambios integrados.
- [ ] **T8** — S6 — `inline` — Preparar y entregar a main solo las tres maquetas, su manifiesto, el sistema de diseño y este plan, preservando los cambios previos. Estado: `in_progress`, verificación previa al push; commit: `343aba6`; riesgo: ítem 5 (publicación).

## Log

**L1 — Solicitud original, literal.**

> hola :D
> Quiero añadir un app móvil donde pueda detectar cambios del diagrama como pull request, commits, etc. Tal y cómo es en github

**L2 — Corrección de alcance, literal.** «No crees todavía sino hagamos un diseño y lo apruebo para poder implementarlo». Se prepararon tres maquetas; luego el usuario solicitó expresamente implementar la lámina 03 (S2).

**L3 — Handoff y evidencias iniciales.** Gentle Design recibió `implement` y `handoff` `in_progress` para `revision-diagramas-movil/03-comparacion.html`. La app Flutter no tiene cliente VCS; existen endpoints de historial, diff, solicitudes, aprobación y cierre en `backend/src/main/java/com/diagramador/vcs/VcsController.java`. No se encontró asignación de revisores, petición de cambios ni push Android en las rutas examinadas. No se verificaron esas rutas en ejecución.

**L4 — Tooling y estado inicial.** Una comprobación anterior de herramientas agotó 20 s y una prueba mínima de shell agotó 10 s; no se repitieron. Después el usuario indicó «SI esta todo normal» y una comprobación nueva `cmd.exe /d /c flutter.bat --version` terminó con código 0: Flutter 3.47.6 y Dart 3.13.5. La lectura de `.git/HEAD` indicó `refs/heads/main`; un diff Git acotado sigue en segundo plano. No se ha afirmado que el árbol esté limpio ni se ha creado una rama.

**L5 — Memoria y verificación.** La búsqueda del espejo y `mem_review` devolvieron error de contrato por timeout; el espejo está pendiente y no se guardó otro tema sin comprobar si existía. Antes del último ajuste de la maqueta 01 pasaron comprobaciones estructurales, de interacción simulada y contraste; el último ajuste no obtuvo resultado de comprobación. La lámina 03 es la seleccionada. El renderizado real y la app implementada todavía no se verificaron.

**L6 — Estrategia de entrega.** Para la estimación inicial, el usuario eligió «Cadena sobre rama de funcionalidad»: `chain_strategy=feature-branch-chain`. Esta elección no crea PRs ni hace push.

**L7 — Servicio de notificaciones.** Se explicó que Firebase Cloud Messaging requiere servicios de Google Play y un proyecto Firebase, y que Android 13+ requiere permiso para mostrar notificaciones; el usuario eligió «Usar Firebase Cloud Messaging». No se creó una cuenta, proyecto ni credencial. Fuente: https://firebase.google.com/docs/cloud-messaging/android/get-started. El contenido del diagrama no se propone en el payload.

**L8 — Diff Git acotado.** `git --no-optional-locks diff --stat -- mobile backend web && git --no-optional-locks diff --cached --stat -- mobile backend web` terminó con código 0 tras 15 min 12 s. La salida mostrada enumera cambios previos en `mobile/lib/domain/agent_eval.dart`, `mobile/lib/domain/agent_models.dart`, `mobile/test/agent_eval_test.dart`, `mobile/test/agent_models_test.dart` y `mobile/test/agent_runner_query_test.dart`; no se deduce de la cola si pertenecen al índice o solo al árbol. Git emitió avisos de normalización CRLF/LF en otros archivos. Preservar esos cambios y el índice; el resultado no establece que no haya archivos no rastreados ni cambia la necesidad de comprobar la rama.

**L9 — Consulta Git inválida.** Un comando combinado para consultar índice, no rastreados y rama terminó con código 0, pero su salida mostró que `echo` imprimió los comandos en lugar de ejecutarlos por una composición incorrecta de `^&`. No se considera evidencia del estado Git. Se preparan consultas independientes, sin modificar el repositorio; el mensaje WSL sobre la sesión systemd tampoco establece una causa de lentitud.

**L10 — Reanudación.** El usuario pidió: «Continua con la nueva implementación». La lectura de archivos y Engram responde. En esta sesión, una consulta de estado Git y una consulta independiente de la versión de Git, ambas con límite de 20 segundos, fueron rechazadas por el ejecutor sin devolver un diagnóstico visible. No se volvió a ejecutar ninguna; no hay evidencia nueva del estado de Git ni se modificó código de la app. T1 espera un ejecutor operativo. Se inició exploración de solo lectura de T2; el handoff de la lámina 03 continúa `in_progress`. La búsqueda no encontró el espejo de tareas y el intento de crearlo falló al confirmar el registro de la sesión; Engram informó que no se envió ninguna escritura.

**L11 — Mapa de T2 y ejecutor.** La exploración de solo lectura confirmó que las rutas de solicitudes y `/repo/history` permiten un primer corte de comparación semántica, navegación y actividad parcial; no hay API de actividad completa ni snapshots UML completos en el diff. Evitar usar los GET de `/status`, `/branches` y `/commits` como lectura inocua: invocan `ensureMain` y pueden crear una rama y un commit iniciales. Reutilizar la sesión autenticada de Flutter y expirar la sesión solo ante HTTP 401. El comando independiente `cmd.exe /d /c flutter.bat --version`, que había respondido antes (L4), agotó 20 segundos en esta sesión. No se ejecutaron pruebas ni se escribió código. T1 continúa bloqueada por el ejecutor y T2 queda pendiente hasta resolver T1.

**L12 — Diagnóstico solicitado por el usuario.** «Usa un jobs para ver, ya que la pc se satura por culpa de micrsofot defender que ocupa 35 de cpu». El job `job-1`, acotado a cinco minutos, consultó la versión de Flutter con más margen y terminó con código 0 en 1 minuto y 9 segundos: Flutter 3.47.6 y Dart 3.13.5. WSL mostró una advertencia sobre la sesión de systemd, cuya causa no se ha establecido. No se modificó la configuración de Defender.

**L13 — Consulta Git en segundo plano.** `job-2` terminó con código 0 en 6 minutos y 31 segundos. La rama es `main`, en `2e1f8d71f0a760eae5c3989404ad831f935a5ada`. Hay cambios preparados en `.gitignore` y eliminaciones de documentación, cambios sin preparar en cinco archivos de `mobile`, y archivos no rastreados de backend, `design/`, evidencia y tareas. Todos deben preservarse; el árbol no está limpio. El listado WSL muestra worktrees antiguos como `prunable`, pero no se ha verificado que lo sean bajo Windows ni se los eliminará.

**L14 — Aislamiento.** Para no trasladar ni preparar cambios ajenos en la rama de la funcionalidad, `job-3` creó el worktree `.atl/worktrees/revision-diagramas-movil` y la rama `feat/revision-diagramas-movil` desde el commit anterior, con Git de Windows. Terminó con código 0 en 1 minuto y 1 segundo y el worktree quedó registrado en la sesión. No limpiar ni modificar worktrees preexistentes.

**L15 — Corte de implementación.** T2 se divide en un primer recorrido funcional de navegación y lista de solicitudes, y T7 para comparación y actividad; conserva el alcance y las pruebas de S1–S3. La copia activa de este documento queda en `.atl/worktrees/revision-diagramas-movil/odd/tasks/revision-diagramas-movil.md`; el archivo homónimo del árbol principal se conserva como instantánea previa.

**L16 — Espejo.** Engram confirmó la creación de la observación 5323. La primera lectura posterior falló; una lectura posterior recuperó el contenido completo para reconciliarlo con este documento.

**L17 — Implementación de T2.** El agente `mw748058-2-3bmi` recibió el trabajo en el worktree aislado para navegación y listado autenticado de solicitudes, con pruebas y documentación. Comparación y actividad quedan para T7.

**L18 — Primera entrega parcial.** El agente terminó `partial` sin modificar archivos: su consulta `git status --short --branch && git rev-parse HEAD` agotó 30 segundos. No obtuvo RED ni GREEN y no ejecutó pruebas Flutter. No se repitió esa consulta. La creación y registro del worktree ya se observaron en L14. La lectura del espejo coincide con el documento salvo un salto de línea final normalizado por Engram.

**L19 — Corrección acotada.** Se reanudó el mismo agente como `mw74hvgr-3-fo5i` con la evidencia del worktree, sin repetir la consulta agotada y sin saltar protecciones.

**L20 — Segundo resultado parcial.** El agente informó que no escribió código: el índice CodeGraph del worktree falta y su alcance de edición no autoriza crearlo. Git de WSL devolvió `fatal: not a git repository` para el worktree creado por Windows; Git de Windows confirmó la raíz y mostró solo el tracker sin seguimiento antes de instalar dependencias. `flutter pub get --offline --enforce-lockfile` terminó con código 0. No se ejecutaron RED, GREEN, análisis ni suites. La corrección acotada se agotó; no se amplían los permisos del agente ni se usa otro para eludir la guarda. El proveedor de CodeGraph deja la inicialización a decisión del usuario.

**L21 — Autorización del índice.** Ante la solicitud de inicializar CodeGraph, el usuario preguntó: «No podes hacer en un jobs?». Se autorizó al padre a comprobar y, si la herramienta lo permite, inicializar solo el índice del worktree. La ruta `.codegraph` no existía. `job-4` terminó con código 0 en 51 segundos: el comando está disponible en Windows y `codegraph init --help` indica que inicializa y construye el índice.

**L22 — Inicialización.** `job-5` ejecutó `codegraph init .` en el worktree aislado, sin `--force`, tras comprobar que `.codegraph` no existía. Terminó con código 124 tras 20 minutos y 33 segundos. El comando informó inicialización, encontró 719 archivos y terminó el análisis sintáctico, pero la última salida estaba en resolución de referencias al 50 %; no hay confirmación de índice completo. No se repitió el comando ni se eliminó el índice generado. No hay prueba de que los posibles procesos hijos de Windows hayan terminado. T2 vuelve a quedar bloqueada; no se implementó ni verificó funcionalidad.

**L23 — Entrega solicitada.** El usuario pidió: «Haz un push al main para que lo haga yo en otra pc». No hay código implementado de esta función y existen cambios previos ajenos, incluidas eliminaciones preparadas.

**L24 — Alcance de entrega.** El usuario seleccionó «Maquetas y plan (recomendado)»: las tres maquetas y su manifiesto, `design/system.md`, `design/system.html` y este documento de tareas. Se informó que el plan menciona rutas y bloqueos de esta PC. No incluir `.codegraph`, código nuevo ni cambios locales ajenos. Preparar la entrega desde el worktree aislado sin modificar el índice del árbol principal.

**L25 — Preparación de entrega.** `job-6` terminó con código 0 y comparó las seis fuentes de diseño copiadas con sus originales, sin sobrescribir destinos. `job-7` terminó con código 0: el manifiesto referencia las tres maquetas existentes, están los dos archivos del sistema y no había cambios preparados en el worktree aislado. No se ejecutaron pruebas de implementación porque no se implementó código.

**L26 — Commit pendiente.** `job-8` preparó exactamente los siete archivos autorizados y mostró 339 líneas añadidas, pero el commit falló con código 1 porque Windows interpretó incorrectamente las comillas del mensaje. No se hizo commit ni push en ese intento; los archivos quedaron preparados en el worktree aislado. `job-9` confirmó `receipt-driven development: on`.

**L27 — Commit y evaluación.** La corrección `job-10` terminó con código 0 y creó `343aba6` (`docs: add mobile review design handoff`), con los siete archivos aprobados y 341 líneas añadidas. El análisis del rango desde `2e1f8d71f0a760eae5c3989404ad831f935a5ada` devolvió riesgo nativo medio, escalado al ítem 5 por la entrega a main; `reviewDue: false` y verificación independiente requerida. No se hizo push todavía.

**L28 — Verificación independiente.** El agente `mw78igh4-4-xx0j` confirmó los siete archivos autorizados, el manifiesto y la ausencia de indicadores de credenciales en el alcance revisado. Señaló como bloqueante que la versión del plan incluida en `343aba6` todavía decía `commit: pendiente`; esa actualización existía solo en el árbol de trabajo.

**L29 — Destino remoto.** `job-11` terminó con código 0: `origin/main` apunta a `2e1f8d71f0a760eae5c3989404ad831f935a5ada`, la base exacta del commit de entrega. La consulta no cambió refs locales.

**L30 — Corrección del plan.** Este documento registra el commit `343aba6` de maquetas y plan para que la entrega no aparezca como no comprometida en la otra PC. Al preparar esta corrección todavía no se había hecho push; el estado remoto definitivo debe comprobarse con Git.
