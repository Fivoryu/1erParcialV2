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
- [ ] **T2** — S1–S3 — `delegated` — Navegar desde el proyecto a su lista de solicitudes de fusión usando la sesión y API existentes, sin mutaciones; incluir pruebas y documentación. Estado: `blocked`, commit de T2 espera identidad Git de autor; revisión nativa pendiente; riesgo: ítem 2 (sesión y acceso autenticado).
- [ ] **T7** — S1–S3 — `delegated` — Completar comparación semántica y actividad parcial de solicitudes y commits en la experiencia móvil, con pruebas y documentación; depende de T2. Estado: pendiente; commit: pendiente; riesgo: ítem 2 (sesión y acceso autenticado).
- [ ] **T3** — S1–S3, S5 — `inline` — Completar acciones de revisión solicitadas, incluida la capacidad faltante de pedir cambios, según permisos y decisión de fusión. Estado: pendiente; commit: pendiente; riesgo: ítems 2 y 4 (autorización y concurrencia).
- [ ] **T4** — S3–S4 — `delegated` — Permitir seleccionar y conservar revisores por solicitud en web y backend, con pruebas de permisos. Estado: pendiente; commit: pendiente; riesgo: ítems 1, 2 y 4 (persistencia/migración, permisos y concurrencia).
- [ ] **T5** — S4–S5 — `inline` — Entregar avisos push Android a participantes para los eventos elegidos mediante el servicio elegido. Estado: pendiente; commit: pendiente; riesgo: ítems 2, 4 y 5 (datos de dispositivo, concurrencia y dependencias/servicio).
- [ ] **T6** — S1–S5 — `inline` — Ejecutar suites aplicables y verificación funcional/visual en el entorno disponible; actualizar el handoff solo al terminar. Estado: pendiente; commit: pendiente; riesgo: según los cambios integrados.
- [ ] **T8** — S6 — `inline` — Preparar y entregar a main solo las tres maquetas, su manifiesto, el sistema de diseño y este plan, preservando los cambios previos. Estado: `done`, los siete archivos están en `main` en esta PC; commit de preparación: `343aba6`; riesgo: ítem 5 (publicación).

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

**L31 — Continuación en otra PC.** El usuario indicó «Ese mismo, te autorizo a completarlo :D» después de la propuesta acotada para T2. `main` está en `5e8dbce` y contiene los siete archivos entregados; se creó la rama local `feat/revision-diagramas-movil`. El worktree anterior no existe en este clon. La observación 5323 no está disponible en el Engram de esta sesión; espejo pendiente de reconciliación, sin reemplazarla. No se encontró `flutter` en PATH. Se explora T2 antes de implementar; siguen pendientes sus pruebas y verificación independiente.

**L32 — Resultado parcial de T2.** El agente `mw7o35h5-2-yr9u` implementó un candidato de navegación y listado con pruebas y documentación, sin ejecutar ninguna prueba: Flutter y Dart no están en PATH. No hay RED, GREEN ni compilación; faltan pruebas de navegación, sesión obsoleta y preservación del comportamiento. `git diff --check` pasó para archivos rastreados, no cubre los archivos nuevos. La evaluación de riesgo escalada al ítem 2 requiere verificador independiente; la evaluación nativa no pudo calcular el candidato por archivos no rastreados. No se hizo commit. Espera disponibilidad del SDK y una nueva solicitud explícita de verificación acotada; el espejo Engram sigue pendiente.

**L33 — SDK y verificación.** El usuario autorizó instalar Flutter y luego respondió «si» a una nueva verificación acotada de T2, con hasta una corrección. Flutter 3.47.9 y Dart 3.13.7 están instalados en `/home/trevor/develop/flutter`, con SHA-256 de descarga comprobado. No hay agentes activos; hay espacio disponible y falta preparar las dependencias locales. Se preparan y ejecutan las pruebas; no se ha observado aún ningún resultado.

**L34 — Verificación acotada.** Dependencias preparadas con `flutter pub get --enforce-lockfile`. La primera suite pasó 196 pruebas; el agente agregó cobertura de navegación, 401, 403/500 y sesiones obsoletas, sin corregir implementación; prueba enfocada 8/8 y suite 201/201. El verificador independiente reprodujo 8/8 y 201/201 en copia aislada y agregó una prueba independiente de respuestas vacías y malformadas, que pasó. Su veredicto es incompleto: no hizo pruebas independientes de carreras, autorización real del backend ni comparación de navegación con base; no hubo verificación visual ni RED previo. No se reproducen defectos pero T2 no queda cerrado. La evaluación nativa no calculó el candidato por archivos no rastreados; la revisión nativa y el commit están pendientes. Se requiere decisión del usuario sobre la prueba faltante; no repetir automáticamente el verificador.

**L35 — Preparación de backend aislado.** El usuario respondió «dale» a comprobar si esta PC puede levantar el backend aislado. No hay agentes activos. Java está disponible, pero `docker` y `mvn` no están en PATH; `backend` no contiene wrapper Maven. Las pruebas de integración usan Testcontainers y `postgres:16-alpine` (`AbstractPostgresIntegrationTest`). Hay 199 GiB libres. No se ejecutaron pruebas de backend ni se instaló software. Se requiere configurar Docker y Maven antes de una nueva solicitud acotada de verificación.

**L36 — Herramientas del backend.** El usuario indicó «Ya esta». Maven 3.9.9 y Docker 26.1.5 responden; el usuario actual pertenece al grupo `docker`. Java activo es 25.0.5; el proyecto declara Java 21. La imagen `postgres:16-alpine` y el repositorio Maven local no estaban disponibles entonces. Ninguna prueba de backend se ejecutó todavía.

**L37 — Preparación para nueva verificación.** El usuario autorizó descargar la imagen y dependencias y hacer un nuevo intento acotado de verificación de T2: «Si dale». `job-4` descargó `postgres:16-alpine` y ejecutó `mvn -B -ntp -Dmaven.repo.local=/home/trevor/.m2/repository dependency:go-offline` con código 0; preparación completa, cero pruebas ejecutadas. No hay agentes activos; se inicia la verificación acotada independiente.

**L38 — Verificación aislada incompleta.** El verificador `mw7r53ny-5-gmir` intentó `VcsVersioningApiTest` en copia aislada, pero Maven offline no resolvió siete dependencias pese a `dependency:go-offline`; terminó con código 1 antes de compilar o iniciar PostgreSQL, cero pruebas backend. La prueba Flutter existente pasó 8/8, pero no se hicieron pruebas independientes de carreras ni comparación funcional con baseline. T2 sigue sin prueba de autorización real. Scratch informado: `/tmp/gpPrZp2n/ga5rbFpa/tmp.1c9hFC6UT6`, no eliminado. No se repitió el verificador; espera decisión sobre preparación y nueva verificación.

**L39 — Reparación autorizada.** El usuario respondió «si dale» a preparar las dependencias y realizar un nuevo intento acotado. `job-5`: `mvn -B -ntp -Dmaven.repo.local=/home/trevor/.m2/repository -DskipTests test-compile` terminó código 0, compilando también las pruebas; cero pruebas ejecutadas. No hay agentes activos; se inicia el nuevo intento independiente.

**L40 — Nuevo bloqueo de preparación.** El verificador `mw7ra1up-6-tc6t` terminó código 1 antes de ejecutar pruebas: Maven offline no encontró `org.apache.maven.surefire:surefire-junit-platform:3.5.3`. `test-compile` de L39 no prepara ese proveedor; cero pruebas ejecutadas en este intento. No se verificaron carreras independientes, autorización real, HTTP ni comparación con baseline. Se preservan el fallo de L38 y el scratch informado `/tmp/gpPrZp2n/ga0pzOGe/tmp.mXbI99aeGA`; no se reintentó. Se espera decisión sobre reparar el proveedor y nueva verificación.

**L41 — Descarga del proveedor.** El usuario indicó «Descarga el proveedor». `job-6` ejecutó Maven dependency:get para `org.apache.maven.surefire:surefire-junit-platform:3.5.3` y dependencias transitivas; terminó con código 0. Cero pruebas ejecutadas. No se ha comprobado aún si el entorno offline tiene todos los artefactos necesarios; no se autorizó un nuevo intento de verificación.

**L42 — Nueva autorización.** El usuario respondió «dale» al nuevo intento acotado de verificación independiente. Sin agentes activos, Docker tiene la imagen PostgreSQL, el JAR del proveedor existe y `/tmp` tiene 7,6 GiB libres; se inicia el intento, sin afirmar que todos los artefactos offline estén presentes.

**L43 — Tercer bloqueo de preparación.** El verificador `mw7reo9f-7-e4a8` compiló en aislamiento pero Surefire no pudo resolver offline `org.junit.platform:junit-platform-launcher:1.11.4`; código 1, cero pruebas ejecutadas. Persisten sin verificar autorización real, carreras independientes y comparación con baseline. Se preserva scratch informado `/tmp/gpPrZp2n/ga8szxsT/tmp.3NkvkKLsJ2`. No se reintentó; se requiere decisión sobre preparación y nueva verificación.

**L44 — Nueva preparación autorizada.** El usuario indicó «Dale sin miedo hasta terminar» a descargar el launcher y ejecutar una verificación acotada aislada con acceso a Maven Central. `job-7` descargó `junit-platform-launcher:1.11.4` y dependencias transitivas, código 0; cero pruebas ejecutadas. Se inicia un nuevo intento sin imponer modo offline.

**L45 — Verificación incompleta y descarga inesperada.** El verificador `mw7ribni-8-eaog` ejecutó en aislamiento `VcsVersioningApiTest`: 51 pruebas aprobadas, y `merge_requests_test.dart`: 8 aprobadas. La prueba existente valida el listado del propietario; no se ejecutaron probes independientes para carreras ni autorización de miembros, terceros y anónimos, ni comparación funcional con baseline. Testcontainers descargó inesperadamente la imagen Docker `testcontainers/ryuk:0.11.0` fuera del scratch, no autorizada explícitamente; también intentó localizar configuración Docker, que según su salida no existía. No se eliminó la imagen. Scratch preservado `/tmp/gpPrZp2n/gaNPSMVR/tmp.7uJ3qLmylx`. No se cierra T2; espera decisión sobre evidencia pendiente y efecto externo.

**L46 — Continuación acotada autorizada.** El usuario respondió «Dale» a una verificación independiente de carreras, autorización y navegación baseline, sin cambios de código y con una ejecución por comprobación; cualquier defecto se presentará antes de corregirlo. No hay agentes activos.

**L47 — Resultado del intento acotado.** El verificador `mw7ro1yo-9-i6zm` ejecutó una prueba independiente de carrera de sesión: código 1, cero aprobadas y una fallida. El propio probe respondió 401 a ambas sesiones y provocó `Bad state: Future already completed`; no demuestra un defecto del producto. No se ejecutaron carreras de proyecto, autorización real ni comparación baseline. El límite de una ejecución por comprobación se respetó; scratch preservado `/tmp/gpPrZp2n/garUV1ZK/tmp.j66jngiXci`. No se corrigió ni repitió el probe. Espera decisión del usuario.

**L48 — Continuación autorizada.** El usuario respondió «dale» a corregir la prueba experimental y ejecutar las comprobaciones pendientes de manera independiente, sin cambios de código de la app; cualquier defecto se presentará antes de corregirlo. No hay agentes activos.

**L49 — Evidencia independiente parcial.** El verificador `mw7ry66j-a-w36i` obtuvo 2/2 pruebas independientes de carreras; acceso real a lista vacía: propietario y VIEWER 200, tercero 403 FORBIDDEN, anónimo 401 UNAUTHENTICATED. Las pruebas de selección `workspace_switch_test.dart` pasaron 9/9 tanto en baseline como en candidato y las pruebas combinadas del candidato 17/17. El verificador declara que no se realizó una comparación funcional independiente completa de navegación; T2 no se cierra. Probes candidatos a incorporar están en scratch `/tmp/gpPrZp2n/gavddMxO/tmp.lFFGkrBPgR`; no fueron incorporados al repositorio. Quedan pendientes revisión nativa y commit.

**L50 — Continuación autorizada.** El usuario respondió «si» a completar la comparación funcional de navegación y revisar si corresponde incorporar las pruebas independientes; se mantiene pendiente la revisión nativa y el commit. No hay agentes activos.

**L51 — Comparación acotada.** El verificador `mw7s6um5-b-ilwb` comparó rutas existentes, selección y logout en baseline y candidato: probe final 1/1 en cada copia, sin diferencias. Dos versiones iniciales del probe fallaron en ambas por supuestos equivocados y fueron corregidas en scratch; no son defectos atribuidos al producto. No se probaron taps de desplegables ni botón físico Android. El verificador recomendó incorporar el probe, pero el scratch ya no está disponible para el padre; tampoco estaban disponibles los probes de L49. No se incorporaron pruebas ni se ejecutó nueva revisión nativa o commit. Espera decisión sobre recrear cobertura.

**L52 — Cobertura de regresión autorizada.** El usuario respondió «Ya dale» a recrear en el repositorio una prueba de regresión de las rutas existentes y ejecutarla antes de cerrar T2. No hay agentes activos.

**L53 — Regresión durable.** El agente `mw7sdnia-c-k048` agregó `mobile/test/navigation_regression_test.dart`; prueba enfocada 1/1 y suite móvil 202/202. No se observó RED por tratarse de preservación de comportamiento existente. La comparación independiente de L51 no detectó diferencias en el alcance comprobado; siguen pendientes validación visual Android y acciones posteriores. Se prepara el commit de T2 y su evaluación nativa; el handoff generado de diseño no se incluye.

**L54 — Commit bloqueado.** `git diff --check` pasó y se prepararon los nueve archivos de T2 y el plan; `git commit -m 'feat(mobile): list project merge requests'` falló con código 128 porque Git no tiene configurados nombre y correo de autor. No se creó commit. El plan registra este fallo como cambio adicional no preparado; se conservan el índice y el handoff de diseño no rastreado. Se espera identidad Git; la evaluación y revisión nativa del commit siguen pendientes.
