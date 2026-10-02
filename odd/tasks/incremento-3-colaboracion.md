# Incremento 3 — Colaboración en tiempo real

> **Estado:** plan cerrado, ejecución por etapas. La planificación fue autorizada por el usuario en esta sesión.
> **Producto:** diagramador colaborativo de clases UML 2.5 (PRIMER PARCIAL, Ingeniería de Software I).
> **Rama:** `feat/incremento-3-colaboracion`, apilada sobre `feat/incremento-2-generacion-y-exportacion`
> (90 commits adelante de `main`, sin mergear). Se apila porque el Incremento 2 se apiló sobre el 1: el corte
> visible del 3 necesita el ZIP del 2 para poder bloquearlo.
> **Autoridad del alcance:** pedido del usuario de esta sesión, más [contratos transversales §3–§5](../../docs/puds/contratos/01-contratos-transversales.md),
> [comparación de colaboración](../../docs/puds/contratos/02-comparacion-colaboracion.md) (A+B adoptado),
> [dialecto XMI EA](../../docs/puds/contratos/04-dialecto-xmi-ea.md), y CU-03, CU-07 A3, CU-13, CU-14, CU-17.

## 1. Corte visible que define el incremento

El incremento se cierra cuando se puede hacer esto, de punta a punta y en dos navegadores:

> **Registrar dos usuarios distintos, iniciar sesión con cada uno, y que el creador invite al otro como EDITOR.
> Los dos entran al mismo diagrama: cada uno ve quién está, con su color, el elemento que el otro toca resaltado
> con su color y su nombre, y el cursor remoto en vivo. Un cambio de uno aparece en el otro sin recargar. Los dos
> chatean en el panel del diagrama y el historial sobrevive a la recarga. Mientras uno tiene una edición activa,
> la exportación —el ZIP del backend generado y el XMI del diagrama— queda bloqueada, con el nombre del editor y
> un botón para reclamar el control cuando su lease vence. El XMI exportado es del dialecto de EA y trae su
> reporte de pérdidas.**

Ese recorrido cubre RF-04 (colaboración), RF-01/RF-04 en su parte de acceso, RNF-11 (SLO de colaboración),
CU-03, CU-13, CU-14, CU-07 A3, y adelanta RF-09/CU-17 (exportar XMI) que el plan del Incremento 2 había dejado
en el Incremento 5.

## 2. Alcance

| Pieza | Qué entra |
| --- | --- |
| Identidad | Registro y login propios con **usuario y contraseña**, hash **BCrypt**, y sesión por **token opaco** en `Authorization: Bearer`. Sin Spring Security (no está en la caché en la versión fijada) |
| Autorización | Rol por proyecto (`OWNER` / `EDITOR` / `VIEWER`) verificado **en el servidor** en comandos, modelo, generación, despliegue y exportación |
| Invitaciones | Invitar por usuario o correo con rol propuesto, aceptar, revocar, cambiar rol (CU-03, CU-13) |
| Tiempo real | Un canal **SSE** por proyecto (`SseEmitter`, de `spring-web`, ya presente) con heartbeat y reconexión |
| Presencia | Quién está en el diagrama, **color determinista por usuario**, **elemento que cada uno toca** resaltado con color y nombre, y **cursor remoto en vivo** (CU-07 A3, CU-14 A3) |
| Sincronización | Un comando aceptado se publica por el canal y las otras sesiones convergen **sin recargar**, sin mover la autoridad del servidor (CU-14) |
| Chat | Chat **persistente por proyecto** con historial, autor, hora y evento en vivo |
| Bloqueo de exportación | **Lease de edición** efímero; mientras un editor lo tiene, la **generación y descarga del ZIP** y la **exportación XMI** quedan bloqueadas, nombrando al editor, con **reclamo de control** al vencer |
| Exportación XMI | Emisión del dialecto EA desde el modelo canónico, con **reporte de pérdidas declarado** (RF-09, CU-17) |

### 2.1 Fuera de alcance, y por qué

| Fuera | Motivo |
| --- | --- |
| Importación XMI (CU-16) | **Entró al incremento por encargo del usuario a la sesión `01a0f830`**, después de que este plan la declarara fuera de alcance. Esa sesión la implementa en su propio worktree; ver S6 y S6b. El límite que no cambia: el round-trip contra Enterprise Architect en vivo sigue sin poder verificarse (GAP-07) |
| Round-trip contra EA en vivo (Q3) | **GAP-07**: el servidor de EA no responde desde este entorno. Se exporta y se mide la pérdida contra el fixture; el round-trip sigue sin poder verificarse |
| Offline y reconciliación de conflictos (CU-15) | Es su propia unidad: requiere outbox local, clasificación pre/post revocación y panel de conflictos. Este incremento sincroniza **online** y declara la frontera |
| `model_conflict` / `name_collision` como panel | El esquema ya los tiene, pero el pedido no los toca. La sincronización de este incremento publica lo **aceptado**; lo rechazado sigue rechazándose con diagnóstico, sin panel nuevo |
| Proveedor de identidad real (OAuth/OIDC) | GAP declarado del diseño. Se implementa identidad propia y se declara el límite |
| Varios diagramas por proyecto | `uml_model.project_id` sigue `UNIQUE`. El chat y la presencia son **por modelo**, que hoy es uno por proyecto |

## 3. Decisiones cerradas en esta sesión

| # | Decisión | Alternativa descartada | Motivo |
| --- | --- | --- | --- |
| C1 | **Transporte SSE**, con `SseEmitter` | WebSocket | **Medido**: `spring-boot-starter-websocket` sólo está en la caché en **3.3.8**; el proyecto está clavado en Boot **3.4.5** (Framework 6.2.x) y `spring-websocket` sólo existe en **6.1.16**. Meterlo rompe la línea base D12 y el build sin red. `SseEmitter` sale de `spring-web`, que ya está |
| C2 | **Login propio con usuario y contraseña**, BCrypt desde `spring-security-crypto` **sin el starter** | Spring Security completo | **Medido**: `spring-boot-starter-security` no está en 3.4.5, pero Boot 3.4.5 **gestiona** Spring Security **6.4.5** y `spring-security-crypto-6.4.5.jar` **sí está** en la caché. Se usa el módulo de hash (que es el único que hace falta) y se declara el límite: el resto de la seguridad es propio |
| C3 | La sesión es un **token opaco** en `Authorization: Bearer`, guardado en el cliente | Cookie httpOnly | El editor corre en `:5173` contra una API en otro origen con CORS explícito (`EditorCorsConfig` ya permite `Authorization`). Un bearer evita `credentials: include` y funciona igual en el arnés de dos sesiones. Límite declarado: un token en almacenamiento del navegador es vulnerable a XSS; se documenta |
| C4 | **Presencia y lease son efímeros, en memoria** | Persistir presencia y lease | El contrato §4.1 los declara efímeros y §9 deja «efímero vs persistido» como propuesto. Persistir cursor y latido escribiría en la base cientos de filas por minuto sin valor. **Límite declarado**: el diagramador corre en **una sola instancia**; con dos instancias el canal no cruza |
| C5 | **El chat sí se persiste** (`diagram_message`) | Chat efímero | Decisión del usuario. Un chat que desaparece no se puede mostrar como evidencia ni auditar; el pedido lo quiere usable entre usuarios |
| C6 | El lease de edición vence y se puede **reclamar** | Bloqueo indefinido mientras alguien tenga la pestaña abierta | Un bloqueo duro sin vencimiento deja el producto trabado cuando un navegador se cierra sin avisar. Con TTL corto renovado por latido, el bloqueo es real mientras se edita y se libera solo cuando el editor desaparece |
| C7 | **La exportación XMI va última en el plan** | Ponerla antes | Con la etapa XMI el incremento triplica su tamaño. Ponerla al final deja todas las demás etapas cerrables y verificables, y convierte el XMI en una frontera declarada si el tiempo no alcanza |
| C8 | Los `payload` de chat y los eventos SSE usan **esquema versionado** (`schemaVersion`) | Formato suelto | El contrato pide esquemas versionados por familia de payload y política de evolución; un evento sin versión no se puede evolucionar sin romper clientes |
| C9 | El color de presencia es **determinista por `userId`** | Color asignado por el servidor al conectar | Un color asignado al conectar cambia al recargar, y el usuario deja de reconocer «el verde es Ana». Derivarlo del identificador hace el color **estable y reconstruible** por cualquier cliente sin coordinación |

### 3.1 Divergencias del esquema que esta etapa sí arregla

Dos huecos reales del esquema físico que el alcance toca, ambos documentados en el mismo cambio:

1. **`invitation` no tiene columna de rol.** CU-13 dice «registra la invitación y **el permiso propuesto**», y la
   tabla del esquema físico no tiene dónde guardarlo. Se agrega `role project_member_role NOT NULL DEFAULT 'VIEWER'`
   y se actualiza `docs/puds/datos/02-esquema-fisico.sql`.
2. **`user_account` no tiene usuario ni contraseña.** El diseño dejó la autenticación como GAP y usó
   `external_subject` opaco. Al implementar identidad propia hacen falta `username` (único, `CITEXT`) y
   `password_hash`. Se agregan y se documenta que el día que entre un proveedor de identidad, `external_subject`
   vuelve a mandar y `password_hash` se puede retirar.

## 4. Plan de trabajo

Cada unidad de código de este incremento sigue el orden de **S0**: se verifica, se revisa sin commitear y sólo
entonces se commitea. Las reglas generales de encargo y la de verificación para lanzar escritores están al final de
este apartado.

### S0 — Revisión nativa por unidad

**Decisión del usuario (2026-10-01): revisión nativa por unidad**, una por cada commit de código del Incremento 3.
No se extiende la exclusión del Incremento 2, que estaba acotada a T7–T14 de aquel incremento.

Orden de cada unidad, que invierte el que se usó en el Incremento 2:

1. Implementar y verificar la unidad (`mvn -o -B verify`, o lint, pruebas y build en la web).
2. **Revisar el cambio sin commitear**, siguiendo la transición que devuelva la autoridad, incluida su captura si
   hay lentes.
3. Confirmar la aprobación con la continuación exacta que emita el proveedor.
4. **Solo entonces** commitear la unidad, con un mensaje que describa el comportamiento entregado.

El motivo del orden: así ningún commit de código de este incremento existe sin haber pasado por revisión. Si la
revisión pide una corrección, el cambio todavía está sin commitear y corregirlo no reescribe historia.

Cada tarea cierra con **al menos un commit de unidad de trabajo**, con sus pruebas y su documentación al lado
del comportamiento. El padre verifica el árbol y el diff de cada tarea: el escritor delegado no puede ejecutar
`gentle_review` y en una vuelta anterior reportó «files_changed: None» habiendo escrito 580 líneas.

**Regla de verificación para lanzar escritores** (aprendida a costo de tres vueltas perdidas): el encargo debe
decir de forma explícita que `mvn -o -B compile`, `mvn -o -B verify`, `npm run lint`, `npm test` y `npm run build`
están **permitidos**, incluido el PostgreSQL que Testcontainers levanta por su cuenta y las rutas de salida
generadas e ignoradas por git (`web/dist/**`, `target/**`, `node_modules/**`). La prohibición de contenedores
alcanza a los arneses de `tools/`, al operador de despliegue y a `docker` directo —el ciclo de vida del
humano—, **no** al build; y lo prohibido al escribir es **fuente** fuera de las superficies, no la salida del
build. Un encargo que prohíbe contenedores y exige `verify`, o que no autoriza la salida del build y exige
`npm run build`, se contradice y detiene al escritor. Un escritor que no corre su verificación no está
terminado: así se coló un test que no compilaba.

**Regla de propiedad de la revisión para lanzar escritores** (aprendida a costo de otra vuelta perdida): el
encargo debe decir **explícitamente** que la revisión nativa la posee el padre y que el escritor no la ejecuta,
no la inspecciona y no declara su disposición, porque la herramienta `gentle_review` es del padre y un escritor
no la tiene. Si falta esa frase, el recordatorio de revisión salta dentro del escritor, éste se detiene para
preguntar por una disposición que no le corresponde y **no escribe nada**: ocurrió con C9b, que volvió con
`files_changed: []` y `status: blocked` sin haber tocado un archivo. La frase tiene que decir que los
recordatorios de revisión se ignoran como acción propia y que el padre ya tiene resuelta esa disposición; decir
en su lugar que el candidato está excluido de revisión sería falso desde que el usuario eligió revisión por
unidad.

**Regla de superficies completas para lanzar escritores** (aprendida a costo de tres vueltas más, todas por el mismo motivo):
antes de lanzar, el padre tiene que **derivar** las superficies de los archivos cuyo comportamiento o forma cambia,
no de los archivos que se le ocurren. En concreto: si cambia la forma de un tipo, de un recurso o de un objeto
que se serializa, entra también **el test que fija esa forma**, aunque nadie lo edite a propósito; si cambia la
configuración, entra el archivo de configuración; si cambia un documento de autoridad, entra el documento. Los
casos que ya costaron una vuelta: `ProjectApiService` (C27), el `application.yml` y la salida del build (C27 y C7),
`DeploymentApiTest` (C5) y `LoginPage.test.tsx` (C10). Cada uno era **predecible** desde el cambio pedido, y en
los cinco el escritor se detuvo en lugar de inventar alcance, que es lo correcto: el costo lo pagó el padre por no
derivar la lista.

**Regla de la concesión y del presupuesto de tiempo** (aprendida en C10, y aplica a las 15 unidades que quedan):

- El sobre de concesión que emite el START **vence a los 10 minutos sin respuesta**. Si vence, el START **no crea linaje**
  (`lineage_created: false`, `mutation_performed: false`) y su diagnóstico dice exactamente qué hacer: volver a correr START
  para obtener un sobre fresco. Nunca se reenvía el binding vencido, y no hay que presentarle nada al usuario en ese caso:
  no hubo concesión que contestar.
- La corrida del revisor puede morir por **presupuesto agregado de tiempo** (`native-status-failed`,
  *"The negotiated review operation exceeded its aggregate time budget"*, `mutation_performed: false`). No se repite a
  ciegas: se consulta **STATUS fresco**, y si el estado y la revisión no cambiaron y el slot sigue abierto con el mismo
  binding y sin artefacto admitido, corresponde un **intento nuevo** sobre ese slot —que no es reenviar un resultado
  preparado, porque no hubo ninguno—. En C10 el primer intento murió así y el segundo admitió el artefacto.

### S1 — Identidad, sesión y autorización (login propio)

| # | Tarea | Evidencia que la cierra |
| --- | --- | --- |
| C1 | Migración `V7__identity_and_sessions.sql`: `user_account.username` + `password_hash`; tabla `user_session`; semilla del dueño con usuario y clave conocidos | Migración aplicada contra PostgreSQL real; prueba de esquema en verde |
| C2 | Dominio y repositorios: `UserAccount` ampliado, `UserSession`, `PasswordHasher` (BCrypt), `SessionTokenFactory` | Pruebas unitarias de hash (verify round-trip, hash distinto por sal) y de token |
| C3 | `IdentityService`: registrar, iniciar sesión, cerrar sesión, resolver usuario desde el bearer | Integración: alta, login correcto, login con clave mala, token revocado |
| C4 | Filtro de autenticación y `CurrentUser` en el contexto; 401 con cuerpo estable | Prueba de API: sin token, token vencido, token válido |
| C5 | `AuthorizationService` por proyecto y guardas en comandos, modelo, generación, despliegue y exportación; `project.create` deja al creador como `OWNER` | Integración: VIEWER no puede comandar, EDITOR sí, OWNER administra |
| C6 | API de identidad: `POST /auth/register`, `POST /auth/login`, `POST /auth/logout`, `GET /auth/me` | Prueba de API de los cuatro endpoints |
| C7 | Web: registro/login, sesión persistida, `Authorization` en el cliente, lista de proyectos por membresía | `npm run lint` y `npm test` en verde con pruebas nuevas |

### S1b — Acceso de máquina (bloqueadores descubiertos al verificar S1)

Exigir token rompió a los dos clientes que **no** son usuarios, y la suite Java no puede verlo: sus tests recibieron token, los arneses y el operador no. Son bloqueadores, no mejoras: sin ellos queda roto el corte verificado del Incremento 2.

| # | Tarea | Evidencia que la cierra |
| --- | --- | --- |
| C27 | Identidad de máquina para el **operador de despliegue**: token de operador configurable, resuelto por el interceptor como principal propio, con alcance enumerado a los endpoints que el operador realmente usa (`GET /projects`, `GET /projects/{id}/deployments`, `POST /deployments/{id}/claim|report`, `GET /projects/{id}/generations/{runId}/artifacts`) | Integración: el operador entra; un usuario común no obtiene el alcance de operador; el alcance no se extiende a comandos ni a miembros |
| C28 | Los **tres arneses** (`tools/acceptance/acceptance.py`, `acceptance_generation.py`, `acceptance_deployment.py`) se registran e inician sesión como un usuario real y mandan `Authorization` | Los tres arneses corren completos contra el backend y quedan verdes; la corrida del Incremento 2 vuelve a pasar |

### S2 — Invitaciones y permisos básicos (CU-03, CU-13)

| # | Tarea | Evidencia que la cierra |
| --- | --- | --- |
| C8 | Migración `V8__invitations.sql`: tabla `invitation` del físico **más la columna `role`** que falta | Migración y prueba de esquema |
| C9 | `InvitationService` y endpoints: invitar, listar, aceptar, revocar, cambiar rol; duplicado informado; revocar no borra historial | Integración de los cinco caminos y del duplicado |
| C10 | Web: panel de integrantes con roles, invitar, aceptar y revocar | Pruebas de componente y lint; **cerrada** en `c4b5581` (ver *S2 progreso*) |

### S2 progreso — C10 cerrada (2026-10-01)

**Commit:** `c4b5581` — *feat(web): manage project members and answer invitations* (17 archivos, +1133/−4).

**Revisión nativa:** linaje `review-b2e13cc0aa5260e6`, tier `medium`, **una** lente (`review-reliability`, orden 0), 17 caminos,
1137 líneas cambiadas, presupuesto de corrección 200. Cerró **aprobada** y su autoridad quedó **quemada**
(`burn_evidence: gentle-ai.review-acknowledged/v1`). El artefacto admitido del revisor: prompt 66.805 bytes, resultado 975 bytes.
El sobre cerró `approved` **sin abrir corrección**, así que no hay hallazgo que citar: el texto del hallazgo de la lente no se
expuso y no se inventa.

**Medido al cerrar** (por el padre, y repetido por un verificador independiente que corrió los comandos él mismo):
`npm run lint` 0 errores · `npm test` **111 pasadas, 4 omitidas**, 13 archivos · `npm run build` exit 0 ·
`mvn -o -B -f backend/pom.xml verify` **248 pruebas, 0 fallos, 0 errores, 0 omitidas** (la línea base de C9 era 232).

**Dos defectos propios, encontrados por la verificación independiente ANTES de la revisión** y corregidos dentro del mismo candidato
(por eso **no** quedaron como hallazgos del revisor; se registran acá atribuidos a esta sesión):

1. El selector de rol de un integrante ofrecía **Owner**, y `InvitationApiService.assignableRole` lo rechaza **siempre** con
   `400 INVALID_PAYLOAD`. Ahora el selector sólo ofrece Editor/Viewer y el tipo compartido
   `AssignableRole = Exclude<MemberRole,'OWNER'>` hace imposible producir ese valor. Es falsable: restaurar la opción
   `<option value="Owner">` rompe `MembersPanel.test.tsx`.
2. `EditorCorsConfig` sólo permitía `GET`, `POST` y `OPTIONS`, y C10 es el **primer** código del cliente que usa `PATCH` y `DELETE`
   (`updateProjectMemberRole`, `removeProjectMember`): en el modo de API directa (`VITE_API_BASE_URL`) el preflight del navegador
   rechazaba los dos verbos. Se sumaron y se fijaron con `EditorCorsPreflightTest` (preflight real por MockMvc; quitar `PATCH` de la
   configuración hace fallar la suite; control negativo: `PUT` no está permitido).

   En el mismo paso, `InvitationResource.inviteeUsername` e `inviteeDisplayName` pasaron a `string | null`, que es lo que el mapeador
   del servidor devuelve cuando la invitación no tiene invitado.

**Límite declarado:** cuando los dos campos del invitado vienen nulos, el encabezado de la fila queda vacío en lugar de fallar; está
leído en la fuente, **no** ejercitado por una prueba.

### S3 — Tiempo real: SSE, presencia y sincronización

| # | Tarea | Evidencia que la cierra |
| --- | --- | --- |
| C11 | `ProjectEventBus` y `GET /projects/{id}/events` con `SseEmitter`, heartbeat, limpieza de emisores y autorización por rol | Integración: dos clientes reciben el evento publicado; un tercero sin rol no conecta. **Cerrada** en `cb6a2ca` (ver *S3 progreso*) |
| C12 | `PresenceRegistry` efímero: alta/baja por sesión, color determinista por `userId`, elemento tocado | Pruebas unitarias del registro y del color estable. **Cerrada** en `c2d24a6` (ver *S3 progreso*) |
| C12b | **Cablear la presencia al canal** (hueco descubierto al derivar C14): alta en el `subscribe`, baja en las tres terminaciones del emisor, `presence.updated` publicado con el snapshot, y `POST /projects/{id}/presence` para el elemento tocado | Integración: dos suscriptores del mismo proyecto se ven entre sí con su color; el que se desconecta desaparece del snapshot siguiente; un toque de uno llega al otro como `presence.updated`; un tercero sin rol no puede reportar. **Cerrada CON DESVIACIÓN DECLARADA** en `84e16f1` — sin revisión nativa por fallo de transporte, autorizado por el usuario (ver *S3 progreso — C12b*) |
| C13 | Publicar `operation.applied` desde `CommandService` sin mover la autoridad del servidor | Integración: comando aceptado en una sesión llega a la otra. **Cerrada** en `c973dd5` (ver *S3 progreso*) |
| C14 | Web: hook SSE, panel de presencia con color y nombre, y resaltado del elemento tocado | Pruebas de componente del panel y del resaltado |
| C15 | Web: cursores remotos en vivo, con coalescencia para no romper el SLO de RNF-11 | Prueba del coalescedor; medición de frecuencia de envío |

### S3 — hueco descubierto al derivar C14 (tarea C12b)

Al derivar las superficies de C14 («web: hook SSE, panel de presencia y resaltado del elemento tocado») apareció un hueco **real** del plan,
y se declara acá como se hizo con S1b:

**El plan tenía el canal (C11), el registro (C12) y la web (C14), pero ninguna tarea cubría el cableado entre ellos.** Medido antes de añadir
nada: `PresenceRegistry.join/leave/touch` **no los llamaba nadie** y ningún evento `presence.updated` se publicaba. Sin esa pieza, C14 no puede
funcionar: un panel de presencia sobre un canal que nunca anuncia presencia es una pantalla muerta, y el «elemento que el otro toca» no tendría de
dónde venir. Por eso C14 **no** se lanzó primero: se declara **C12b** (backend) y C14 la consume después.

**Hecho medido que fija el diseño y evita romper C11:** en Spring Framework `ResponseBodyEmitter` **acumula** callbacks —`DefaultCallback` y
`ErrorCallback` guardan cada uno una `List` de delegados y los suman con `addDelegate(...)`— así que registrar `onCompletion`/`onTimeout`/`onError`
en el controlador **no pisa** la limpieza que `ProjectEventBus` ya registra. Comprobado con `javap -p` sobre `spring-webmvc-6.2.7.jar`. Consecuencia:
`ProjectEventBus` **no se toca** (queda intacto el archivo verificado en C11) y el cableado vive en los controladores y en un publicador chico.

**Corrección menor al plan:** la decisión C1 dice que `SseEmitter` sale de `spring-web`; en realidad vive en **`spring-webmvc`**
(`org.springframework.web.servlet.mvc.method.annotation`). No cambia la decisión —`spring-boot-starter-web` ya lo trae, que era el punto— pero el
nombre del artefacto estaba mal.



### S3 progreso — C11 cerrada (2026-10-01)

**Commit:** `cb6a2ca` — *feat(backend): stream project events over sse* (5 archivos nuevos, +507).

**Revisión nativa:** linaje `review-280103838dd3da8e`, tier `medium`, **una** lente (`review-reliability`, orden 0), 5 caminos,
507 líneas, presupuesto de corrección 200. Cerró **aprobada** al primer intento y su autoridad quedó quemada
(`gentle-ai.review-acknowledged/v1`). Artefacto del revisor: prompt 35.719 bytes, resultado 1.420 bytes.

**Hallazgo informativo que dejó la revisión — `R3-silent-stream-after-send-failure`**: lente `reliability`, ubicación
`collaboration/ProjectEventBus.java:86-87` (el `catch` que elimina el emisor muerto), severidad `WARNING`, disposición
`informational`. El proveedor fue explícito: **ninguno abre corrección, ninguno reabre la revisión y no se ofrece transición
de corrección para este candidato**, así que no se re-revisa. Lectura propia de esa región, **atribuida a esta sesión y no al
revisor**: un suscriptor cuyo envío falla se descarta **sin ningún diagnóstico** —no hay log ni métrica— así que un cliente
caído desaparece del canal en silencio. Es de la misma familia que `R3-001` de C8+C9 y queda como trabajo posterior candidato
(observabilidad al descartar un emisor), no como defecto de esta unidad.

**Medido al cerrar:** `mvn -o -B -f backend/pom.xml verify` = **255 pruebas, 0 fallos, 0 errores, 0 omitidas**, `BUILD SUCCESS`
(248 de base + 7 nuevas: 4 de `ProjectEventBusTest`, 3 de `ProjectEventApiTest`). La corrida del padre fue **desacoplada**
(`nohup` con log fuera del repo) y la repitió un verificador independiente, que obtuvo los mismos totales en ~3½ minutos.
La evidencia del plan —**dos clientes reciben el evento publicado; un tercero sin rol no conecta**— quedó probada por el
**endpoint autorizado** (`GET /projects/{id}/events`), no sólo a nivel del bus.

**Desviación de ruteo declarada:** los tres escritores delegados de esta unidad fallaron (dos desaparecieron y uno se colgó 30
minutos), y el padre terminó escribiendo **un archivo de prueba** en línea, `collaboration/ProjectEventBusTest.java`. El motivo
no fue impaciencia sino que la prueba del camino de error **afirmaba algo falso**: medía `expected: 1 but was: 2`, es decir
`SseEmitter.completeWithError` **no** dispara el callback `onError` del bus. Se reemplazó por la garantía que sí se sostiene
—un suscriptor muerto lo elimina el siguiente envío— y el límite quedó declarado en el propio archivo.

**Causa raíz de esos tres fallos, medida** (importa para las unidades que quedan): el escritor corre la suite completa como
**una sola llamada `bash` bloqueante**; bajo contención con la sesión hermana de XMI —que corre su propio Maven con
Testcontainers contra el mismo demonio Docker— esa corrida pasó de ~13 minutos a **más de 70**. El arnés mata al subagente
cuando una llamada lleva ~30 minutos corriendo, **muere el fork de Surefire y el lanzador de Maven queda esperando un hijo que
ya no existe, para siempre**: un JVM huérfano (591 MB) sosteniendo `backend/target/`, que es lo que hacía parecer colgada
cualquier corrida posterior. **Mitigación**: lanzar la suite desacoplada, con el log **fuera del repo** (un archivo nuevo dentro
del árbol entra al candidato de revisión y mueve el target congelado) y monitorear en ventanas acotadas; si aparece un JVM de
Maven huérfano, matarlo para liberar `target/`.

**Límites declarados de esta unidad** (los midió el verificador independiente y se registran, no se esconden):

1. **La suite no aísla las entradas de `onTimeout` ni de `onError`.** Si se borrara cualquiera de las dos del bus, la suite
   **igual pasaría**: sólo la de `onCompletion` está anclada a una aserción. La de `onError` no se puede ejercitar de forma
   fiel con MockMvc (un error de nivel contenedor no se puede producir ahí).
2. **La limpieza es diferida y acotada, no inmediata.** Un emisor muerto lo elimina el siguiente envío fallido, a más tardar el
   heartbeat (15 s por defecto). El verificador juzgó esto **aceptable para este canal** y descartó que sea una fuga sin límite.
3. **En memoria y de una sola instancia** (decisión C4): la presencia y este canal no cruzan instancias.
4. **El operador no puede suscribirse**: `AuthenticationInterceptor.isOperatorAllowed` no incluye `events`, así que se lo
   rechaza antes del controlador. Es la frontera buscada, no un descuido.

### S3 progreso — C12 cerrada (2026-10-01)

**Commit:** `c2d24a6` — *feat(backend): know who is in a diagram and what they touch* (3 archivos nuevos, +417).

**Revisión nativa:** linaje `review-3e650cd04245491c`, tier `medium`, **una** lente (`review-reliability`), 3 caminos, 417 líneas,
presupuesto de corrección 200. Cerró **aprobada** al primer intento, autoridad quemada, y **sin hallazgos**: el sobre no trajo `advisory_findings`
(artefacto del revisor: prompt 31.834 bytes, resultado 687 bytes).

**Medido al cerrar:** `mvn -o -B -f backend/pom.xml test -Dtest=PresenceRegistryTest` = **9 pruebas, 0 fallos** ·
`mvn -o -B -f backend/pom.xml verify` = **264 pruebas, 0 fallos, 0 errores, 0 omitidas** (255 de base + 9 nuevas).

**Defecto real que encontró la verificación independiente y se arregló ANTES del commit** (no quedó como hallazgo del revisor):
`join` arrastraba el `targetElementId` y el `joinedAt` de la entrada previa de una `sessionId` **sin comparar el usuario**, así que una sesión
reasignada a otro usuario —camino plausible: una pestaña cuyo id de sesión sobrevive al cierre de sesión— publicaba al nuevo usuario **tocando un
elemento que nunca tocó**, visible para todos los demás. Ahora la herencia sólo ocurre si el `userId` coincide. La prueba que lo cubre es
**falsable y con sensibilidad medida**: antes del arreglo la corrida acotada dio **9 pruebas con 1 fallo**, y la aserción que falló fue
exactamente la del target heredado.

**Acotación del color, declarada en el propio código:** la paleta tiene **10 colores**, así que con más de 10 usuarios simultáneos la colisión
está **garantizada** por palomar; el mismo usuario nunca cambia de color. **Medición propia de esta sesión** (atribuida acá, hecha con la
**clase real compilada** y no con una réplica): 200.000 UUID v4 por `colorFor` → aparecen los 10 colores con desvío máximo **−0,87 % / +0,73 %**
sobre los 20.000 esperados por color. La distribución es uniforme.

**Límites declarados de esta unidad:**

1. **No se midió si la prueba de concurrencia fallaría contra un `HashMap` sin sincronizar.** Probarlo exige compilar una copia mutada del
   registro y correr la clase de prueba contra ella con el andamiaje de JUnit armado a mano; ni el verificador (read-only) ni el padre lo hicieron.
   Queda declarado como **no medido**, con el motivo. Lo que sí se verificó: ocho hilos liberados por una barrera hacen `join`/`touch`/`refresh`/
   `leave`/`snapshot` concurrentes de verdad (no serializados), y `join` es un único `compute` anidado, sin ventana de leer-y-escribir.
2. **El registro no borra fantasmas por sí solo.** Quien llama a `join` y a `leave` es el ciclo de vida del canal, y ese cableado es de **C14**;
   está declarado en el comentario de la clase. Hasta que C14 cierre, la presencia no tiene alta/baja automática.
3. **Caveat del verificador, registrado como oportunidad y no como defecto:** la prueba del rebind no afirma el target en el snapshot *antes* de
   rebindear (se apoya en `touch(...).isTrue()`), y no fija `joinedAt`, nombre, color ni última actividad tras el rebind. La sensibilidad de la
   aserción principal sí está medida por el fallo previo al arreglo, que es más fuerte que una precondición.
4. **Comparación contra la línea base anterior no reproducible por el verificador:** la corrió el padre (255 → 264) y el verificador confirmó 264,
   pero no pudo correr la revisión previa al candidato para comparar test por test.

### S3 progreso — C13 cerrada (2026-10-01)

**Commit:** `c973dd5` — *feat(backend): announce an accepted command to the other sessions* (2 archivos, +245/−2).

**Revisión nativa:** linaje `review-81f65013fef6aef5`, tier `medium`, **una** lente (`review-reliability`), 2 caminos, 247 líneas,
presupuesto de corrección 124. Cerró **aprobada**, autoridad quemada (artefacto del revisor: prompt 27.756 bytes, resultado 1.571 bytes).

**Hallazgo informativo que dejó la revisión — `R3-replay-event-identity`**: lente `reliability`, ubicación `command/CommandService.java:112`
(la zona del replay idempotente), severidad `WARNING`, disposición `informational`. El proveedor fue explícito: **ninguno abre corrección y no se
ofrece transición de corrección para este candidato**. Es una **confirmación independiente del riesgo que esta sesión congeló y declaró** al
implementar: un replay con la misma `idempotencyKey` vuelve a anunciar la misma operación, así que el cliente no puede distinguir "aplicada ahora" de
"ya aplicada" salvo por deduplicar con `operationId`. Queda como trabajo posterior candidato, en la familia de observabilidad/identidad de eventos.

**Fallo de transporte del relay del revisor, recuperado (nuevo modo de falla, dos veces):**

- `pi-host-relay-transport-failure` — *"Reviewer completion failed for review-reliability: WebSocket error"*, con `prepared_reviewers: 0`,
  `submitted_reviewers: 0` y `mutation_performed: false`. La primera vez a los ~357 s y la segunda a los ~401 s, **muy por debajo** del
  `timeout_ms` de 2.400.000: no fue timeout, fue el transporte.
- El proveedor indica la recuperación exacta en cada intento: *«Call fresh STATUS and submit only an exact reoffered one-slot binding; never replay
  this capture from transcript inference»*. Se hizo eso: STATUS fresco (estado y revisión **sin cambios**, mismo slot reofrecido) y envío del binding
  exacto. **El tercer intento admitió el artefacto.** No se infirió ningún binding de la transcripción.

**Medido al cerrar:** `mvn -o -B -f backend/pom.xml test -Dtest=CommandEventDeliveryTest` = **3 pruebas, 0 fallos** ·
`mvn -o -B -f backend/pom.xml verify` = **267 pruebas, 0 fallos, 0 errores, 0 omitidas** (264 de base + 3 nuevas).

**Defecto propio encontrado y corregido ANTES de la revisión:** el payload se construía con `Map.of(...)` y `target.id().toString()`, así que un target
con `scope`/`id` **nulos** lanzaba `NPE`; el `catch` deliberado lo tragaba y **el evento se perdía en silencio** para un comando que igual quedaba
aceptado y durable. La construcción ahora es defensiva y hay una prueba que falla si se revierte (**medida**: antes del arreglo, 3 pruebas con 1 fallo
—el comando se aceptaba y no llegaba ningún evento).

**Corrección de mi propio análisis, declarada:** describí el disparador como «un elemento `null` en el array `targets`» y eso es **inalcanzable** por
HTTP, porque `OperationEnvelope` aplica `List.copyOf(targets)` en su constructor compacto y rechaza elementos nulos. El disparador real son `scope` e `id`
nulos **como campos**, que `List.copyOf` sí acepta. El defecto era real; mi explicación del camino, no.

**Dos hallazgos del verificador independiente, corregidos en el mismo candidato:**

1. **El fallo tragado era invisible y mi comentario decía algo falso**: escribí que el proyecto no tiene framework de logging, y `spring-boot-starter-web`
trae slf4j/logback. Ahora `CommandService` tiene un logger y registra el fallo a `warn` con la excepción, así que el hueco deja de ser invisible; el
comentario dice la verdad (se registra y a propósito no se propaga, porque la entrega no puede deshacer una aceptación durable).
2. **La garantía «después del commit» estaba afirmada de forma incondicional.** `TransactionTemplate` con `PROPAGATION_REQUIRED` se une a una transacción
ambiente, así que un llamador futuro podría publicar antes de que esa transacción externa confirme. Hoy **ningún** llamador lo hace (los del
`ProjectApiService` no son transaccionales, verificado por el verificador).

**Limites declarados de esta unidad:**

1. **La garantía de orden es condicional, y se declara en lugar de implementarse.** Un llamador con transacción ambiente necesitaría un hook de
after-commit. **No se implementó a propósito**: agregaría un camino de código que ninguna prueba ejercita, y un camino sin ejercitar es peor que una
frontera declarada. El comentario en el código nombra la frontera.
2. **Un `Error` de la publicación no se atrapa** y puede hacer fallar la invocación aunque el comando ya sea durable. El verificador no lo clasificó como
defecto (no hay requisito de tragarse `Error`s de la JVM) y esta sesión coincide: atrapar `Error` sería peor.
3. **El replay vuelve a anunciar** el mismo `operationId` y `resultRevision` (hallazgo `R3-replay-event-identity` del revisor). Está declarado en el código
y queda como trabajo posterior si el cliente necesita distinguir identidad de recencia.

### S3 progreso — C12b cerrada CON DESVIACIÓN DECLARADA (2026-10-01)

**Commit:** `84e16f1` — *feat(backend): publish presence on the project channel* (2 modificados + 3 nuevos, +442/−2).

> ⚠️ **Esta unidad se commiteó SIN revisión nativa, por autorización explícita del usuario (2026-10-01).** La regla de S0 —verificar,
> revisar sin commitear y sólo entonces commitear— **sigue vigente** para todas las unidades siguientes; esta es la excepción, no un cambio de regla.

**Por qué no hubo revisión — causa medida, no supuesta:** el relay del revisor falló **tres intentos consecutivos** con
`pi-host-relay-transport-failure` (*"Reviewer completion failed for review-reliability: WebSocket error"*), a los **173 s, 151 s y 151 s** de un
presupuesto de 2.400.000 ms — o sea **no** fue timeout, fue transporte— y cada vez con `prepared_reviewers: 0`, `submitted_reviewers: 0` y
`mutation_performed: false`. Se aplicó la recuperación que indica el proveedor (*«Call fresh STATUS and submit only an exact reoffered one-slot
binding; never replay this capture from transcript inference»*) **dos veces**: STATUS fresco, estado y revisión sin cambios, binding exacto reofrecido.
A diferencia de C13 —donde el tercer intento admitió el artefacto— acá el tercero también falló, y **los fallos fueron más rápidos**, no más lentos.

**Estado del linaje `review-9a428806517d6f8a`:** quedó abierto en `reviewing` con su slot de lente sin satisfacer y **no se reusó**. El commit mueve
el árbol, así que su target congelado ya no coincide: se considera **quemado por deriva**. No se inventó ningún resultado de revisor para esta unidad.

**Remediación declarada (la desviación es temporal, no un agujero):** la revisión puede tomarse más adelante **sobre este rango ya commiteado**
(`baseRef` + `committedOnly: true`), que es una forma soportada por la autoridad. Queda pendiente para cuando el transporte del relay funcione, y así
consta para el acta.

**La remediación se intentó de inmediato, y también falló (2026-10-01):** se abrió un linaje nuevo sobre el rango commiteado
(`review-2cbe18d88f816489`, `baseRef=155e93c`, `committedOnly: true`, 6 caminos, 495 líneas) y su corrida del revisor murió con el **mismo**
`pi-host-relay-transport-failure` (*WebSocket error*, 224 s de 2.400.000 ms, `prepared_reviewers: 0`, sin mutación). O sea: el transporte del relay
está caído de forma sostenida, no es un fallo de esta unidad. **C12b queda commiteada sin revisión nativa**, con dos intentos registrados
(uno sobre el candidato sin commitear, uno sobre el rango commiteado) y la remediación abierta para cuando el relay vuelva.

**Dos detalles del procedimiento, medidos, que valen para el próximo intento:**

1. Un START sobre un rango commiteado **exige `idempotencyKey`** (es la operación graph-v1) y **exige `mode`** dentro del `input`; sin mode responde
   *«START supports only "ordinary" or "judgment-day" mode»* y no crea linaje.
2. El propio `inspect` emite el `--base-ref` con el **sha del árbol** de la base, y el START lo rechaza con `base-ref-unresolvable`: hay que pasar el
   **sha del commit** (`155e93cf62e8cafaddacd82b2a507ec27c845423`), no el del árbol. Es un defecto del proveedor que se registra acá como tal, no como
   algo que el operador deba adivinar.

**Lo que SÍ se hizo antes del commit:**

1. **Verificación independiente, sin fallos.** Resolvió los seis ejes: el orden de suscripción (emisor antes del `join`, así el recién llegado recibe el
   snapshot que produce su propia llegada), la fidelidad del cable (tipo `presence.updated`, claves `userId`/`displayName`/`color`/`targetElementId`,
   **sin** `sessionId`, color desde `colorFor`), la autorización del endpoint de toque (usuario del principal y no del cuerpo; 401 sin bearer, 403 sin
   rol, y en ambos casos el registro **sin cambios**), la semántica de `touchUser`, y las seis pruebas. `mvn -o -B -f backend/pom.xml verify` =
   **273 pruebas, 0 fallos** (267 de base + 6).
2. **Un defecto real, encontrado por esa verificación y corregido antes del commit:** las tres terminaciones del emisor no compartían guarda, así que
   timeout/error seguido de completion llamaba dos veces a `endSession` y **publicaba un segundo snapshot**. Ahora hay un `AtomicBoolean` compartido con
   `compareAndSet(false, true)`: la baja y la publicación ocurren **exactamente una vez** por suscripción, en cualquier orden de terminaciones.
3. **Dos pruebas que no probaban lo que decían, reforzadas:** la de completion afirmaba el stream **después** de un toque separado (que publica solo),
   así que no probaba que el `leave` publicara; ahora afirma el stream **inmediatamente** después del completion y con un conteo **exacto**
   (A entra → 1, B entra → 2, B se va → **exactamente 3**), de modo que una publicación duplicada lo lleva a 4 y falla. Y la de dos sesiones no podía
   falsificar una actualización parcial: ahora cierra la sesión **que el colapso elegiría** (A2) y afirma que **A1** conserva el target.

**Límites declarados de esta unidad:**

1. **El orden «timeout/error y después completion» no es alcanzable desde MockMvc**, así que la guarda no se ejercita en ese orden exacto; lo que sí se
   ejercita es el **conteo exacto**, que falla si aparece una publicación de más desde cualquier camino.
2. **El contador de eventos usa `split` sobre substring**, no un parseo de tramas SSE: una carga útil que contuviera ese texto lo confundiría. Es
   robustez de la prueba, **no** un defecto de la implementación, y está declarado.
3. **Las pruebas no fijan el conjunto EXACTO de claves del payload**, sólo las requeridas y la ausencia de `sessionId`; una clave de más pasaría. Se
   declara en lugar de dar por cerrado lo que no se midió.
4. Un snapshot **vacío** (cuando se va el último miembro) se publica pero no puede llegar a nadie si no quedan suscriptores: inherente al canal.
5. El endpoint de toque exige cuerpo (`@RequestBody` requerido): `{}` limpia el target; **sin cuerpo** responde 400 y no limpia nada (no está probado
   por integración, leído en la fuente).
6. Los **targets por pestaña** no se modelan: `touchUser` actualiza todas las sesiones vivas de esa persona y el último toque gana para ella.

### S4 — Chat persistente del diagrama

| # | Tarea | Evidencia que la cierra |
| --- | --- | --- |
| C16 | Migración `V9__diagram_messages.sql`: tabla `diagram_message` (nueva, no está en el físico) con índices por modelo y tiempo | Migración y prueba de esquema |
| C17 | `ChatService` y `GET/POST /projects/{id}/messages`, con autorización, saneado y evento `message.posted` | Integración: publicar, listar ordenado, y rechazo sin rol |
| C18 | Web: panel de chat con historial, autor y hora, y recepción en vivo | Pruebas de componente y lint |

### S5 — Bloqueo de exportación con aviso y reclamo

| # | Tarea | Evidencia que la cierra |
| --- | --- | --- |
| C19 | `EditingLeaseService`: adquirir, renovar por latido, vencer por TTL y reclamar | Pruebas unitarias con reloj controlado |
| C20 | Guarda en `POST /generations`, en la descarga de artefactos y en la exportación XMI; respuesta con el editor, el motivo y la posibilidad de reclamar | Integración: bloqueado con lease ajeno, permitido con lease propio y tras vencer |
| C21 | Web: botones de exportación con motivo y editor nombrado, y botón **Reclamar control** | Pruebas de componente de los tres estados |

### S6 — Intercambio XMI (RF-09, CU-16, CU-17) — **cedida a otra sesión**

Estas tres tareas **no se ejecutan desde acá**. El 2026-10-01 la sesión `01a0f830`, con el mismo directorio de
trabajo, informó por intercom que el usuario le encargó el intercambio XMI **completo —exportación e
importación—** y propuso la frontera que se aceptó. Trabaja en el worktree `../1erParcialV2-xmi`, rama
`feat/xmi-intercambio`, y reclama como superficies propias `backend/src/main/java/com/diagramador/xmi/**`,
`backend/src/test/java/com/diagramador/xmi/**`, `web/src/xmi/**` y los endpoints `/xmi/**`. Verificado antes de
aceptar: no existía ningún archivo ni endpoint de XMI, así que el handover no duplica trabajo.

| # | Tarea | Estado |
| --- | --- | --- |
| C22 | `XmiExportService`: dialecto EA, determinista, con reporte de pérdidas | Cedida |
| C23 | Endpoints XMI con sus guardas | Cedida |
| C24 | Web: botón **Exportar XMI** con el reporte de pérdidas visible | Cedida |

**Cambio de alcance que esto implica.** §2.1 declaraba la importación (CU-16) **fuera** de este incremento. Con
el encargo del usuario a la otra sesión, la importación **entra**, y el acta de cierre (C26) tiene que incluirla
o el incremento quedaría mintiendo sobre su propio alcance. La autoridad del dialecto sigue siendo
[`docs/puds/contratos/04-dialecto-xmi-ea.md`](../../docs/puds/contratos/04-dialecto-xmi-ea.md).

**Guardarraíl que viaja con esa frontera:** el round-trip contra Enterprise Architect en vivo **no se puede
verificar** en este entorno (GAP-07, declarado en el propio contrato del dialecto). Exportación e importación se
pueden medir entre sí y contra el fixture `docs/fixtures/xmi/Prueba3.xml`, que es evidencia medida y byte-exacta;
**contra EA en vivo, no**. Nadie puede declarar round-trip verificado.

### S6b — Contrato congelado con la sesión de XMI

Para que la integración sea de una línea y no de una negociación, queda congelado acá. La sesión `01a0f830`
depende de estos nombres **sin poder escribirlos**; el dueño es esta sesión.

**Migraciones:** los números están repartidos para que no choquen. Esta sesión toma **`V8`** (invitaciones) y
**`V9`** (chat); la sesión de XMI toma **`V10`** para `xmi_exchange` y `xmi_loss_item`. Dos archivos con el mismo
número de versión no se pueden fusionar y Flyway los rechaza.

**Archivos compartidos** (conflicto mecánico, no de diseño, y se resuelve tomando lo de los dos):

- `backend/src/test/java/com/diagramador/SchemaOwnershipTest.java`. La lista de versiones de Flyway esperada conviene
  que termine en **1..10**: esta sesión suma `8` (invitaciones) y `9` (chat), la sesión de XMI suma `10`. **Medido:
  esa línea no es una compuerta** —usa `contains(...)`, que es subconjunto, así que sólo falla si *falta* una versión
  y nunca porque sobre— así que agregarla es higiene y no coordina nada. Lo que **sí** es compuerta exacta es la lista
  de tablas: las **cuatro** nuevas tienen que quedar en `MAPPED_COLUMNS` con sus columnas exactas y en
  `SUPPORT_INDEXES`: `invitation` (más `diagram_message`) de esta sesión, `xmi_exchange` y `xmi_loss_item` de la otra.
  Las cuatro están hoy en `TABLES_RESERVED_FOR_LATER_TASKS`, que **afirma que no existen**, y
  `doesNotContainAnyElementsOf` rechaza la suite si se migran sin sacarlas de ahí. Para las dos de XMI el esquema
  autoritativo declara **un solo** índice de soporte, `idx_exchange_model` sobre `xmi_exchange(model_id, direction)`;
  `xmi_loss_item` no tiene ninguno y declararle uno hace fallar el test exacto. Y hay **dos compuertas más** en ese archivo,
  descubiertas por la sesión de XMI al leer el resto: `nativeEnumColumnsUseTheirPostgresType` y
  `javaEnumsAndPostgresEnumsHaveTheSameLabels`. Toda columna enum nueva y todo tipo enum nuevo piden una línea en cada
  una. Esta sesión ya las escribió para `invitation.role`, `invitation.status` e `invitation_status` (cuatro etiquetas);
  la sesión de XMI sumará las suyas. `diagram_message` (V9) no añade ningún enum. Aviso para quien lea este archivo más
  adelante: un `UNIQUE` declarado como *constraint* no aparece en el recuento de índices de soporte, pero un
  `CREATE UNIQUE INDEX` **sí**, así que un índice único parcial tiene que estar en `SUPPORT_INDEXES`.
- `docs/puds/datos/02-esquema-fisico.sql`, por las mismas cuatro tablas.
- `web/src/pages/ProjectEditorPage.tsx`, que ya recibe el botón de la sesión de XMI además de los paneles de C14,
  C18 y C21.
- `backend/src/main/java/com/diagramador/command/CommandService.java` — **sólo esta sesión lo toca**, en C13, para
  publicar `operation.applied`. La sesión de XMI **no** necesita tocarlo: `CommandService` construye su mapa de
  handlers desde una `List<CommandHandler>` inyectada por Spring, así que un `@Component` nuevo que implemente
  `CommandHandler` con su `type()` se auto-registra. Se lo dije para que no reservara un conflicto que no tiene.

**Concesión de edición (la escribe esta sesión en `com.diagramador.collaboration`):**

```java
package com.diagramador.collaboration;

import java.util.UUID;

/** La concesión de edición de un modelo. Mientras otra sesión la tenga viva, exportar queda bloqueado. */
public interface EditingLeaseService {

    /**
     * Lanza {@code ApiException(409, "EXPORT_BLOCKED_EDITING")} cuando otra sesión tiene una concesión viva
     * sobre el modelo. El principal de operador queda exento: entrega un artefacto que un usuario ya ordenó.
     */
    void requireExportAllowed(UUID modelId);
}
```

**Forma compartida del bloqueo**, para que el endpoint de XMI y los de generación devuelvan lo mismo: código
`EXPORT_BLOCKED_EDITING`; en endpoints de archivo, HTTP **409** con el sobre `ApiErrorResource` de siempre; en
`POST /projects/{id}/generations`, el mismo código dentro del cuerpo `outcome` con HTTP 200, que es la
convención D7 del Incremento 2. Las claves de `details[0]` son exactamente `editorDisplayName`, `targetType`,
`targetId`, `reclaimable` y `retryAfterSeconds`.

**Pendiente declarado:** el endpoint de XMI nace **sin** la guarda de la concesión, porque C19 no está en su base.
Queda como integración pendiente de una línea al consolidar, y **así se declara en el acta**: hasta entonces, la
exportación XMI no está protegida contra una edición en curso.

### S7 — Cierre

| # | Tarea | Evidencia que la cierra |
| --- | --- | --- |
| C25 | `tools/collaboration-acceptance/`: arnés de dos sesiones concurrentes que prueba login, invitación, presencia, chat, bloqueo y XMI, con teardown verificado | Corrida completa sin fallos y con conteo de contenedores restaurado |
| C26 | Documentación (contratos §4 y §5, uso, capturas) y acta `docs/puds/cierre/05-cierre-incremento-3.md` | Documento y acta con límites declarados |

## 5. Riesgos declarados

| Riesgo | Cómo se trata |
| --- | --- |
| **Tamaño del incremento** | Siete etapas, 26 tareas. La etapa XMI va última (C7) para que las demás cierren solas |
| **Seguridad artesanal** | Se declara el límite en cada documento: identidad propia, BCrypt para la clave, token bearer en el cliente, sin protecciones de Spring Security (CSRF, cabeceras, sesión). No se declara «seguro», se declara **qué** hace |
| **Una sola instancia** | Presencia y lease viven en memoria (C4). Se declara; no se finge escalado horizontal |
| **SLO de RNF-11 sin medir** | Se miden latencias de propagación y se registran p50/p95, como pide el requisito, pero sobre esta máquina y sin simulador de carga |
| **Round-trip XMI sin verificar** | GAP-07 sigue abierto. La exportación se mide contra el fixture, no contra EA en vivo |
| **Deriva del esquema físico** | Las migraciones usan `V7`, `V8` y `V9` (Flyway exige una versión única por archivo). Lo que el físico no tenía —usuario/clave, tabla de sesión, rol de invitación y chat— se escribe también en `docs/puds/datos/02-esquema-fisico.sql` para que la autoridad no quede mintiendo |
| **Cifras ajenas que el acta va a citar** | El intercambio XMI lo mide otra sesión, y su escritor ya se equivocó una vez generalizando desde una muestra truncada (afirmó que el `Path` de los conectores estaba vacío en todas; son 8, 7 vacías y una con `622:-260$`). El acta cita esas cifras **atribuyéndolas a quien las midió**, y marca aparte las que esta sesión comprobó por su cuenta. No se adopta ninguna medición ajena sin repetirla. Verificadas por esta sesión: las 8 apariciones de `Path=` viven en el atributo `geometry` de la colocación (7 vacías, una con `622:-260$`) y `Mode`, `EOID` y `SOID` viven en `style`, con cero apariciones en `geometry` |
| **Trabajo diferido que dejó la revisión de C8+C9** | La revisión nativa de la unidad de invitaciones (`review-c61b0185ecca2bea`) cerró **aprobada** y dejó un hallazgo **no bloqueante**: `R3-001`, lente de fiabilidad, en `api/InvitationApiService.java:105-134` —el método `acceptInvitation`—, severidad `WARNING`, disposición informativa. El proveedor fue explícito: ninguno abre corrección, ninguna reabre la revisión y no se ofrece transición de corrección para este candidato, así que **no se re-revisa**. El sobre expuso la ubicación pero **no el texto del hallazgo**, y no se puede recuperar: la autoridad quedó quemada. No se inventa su contenido. Lectura propia de esa región, **atribuida a esta sesión y no al revisor**: `acceptInvitation` decide con `status == PENDING` y después escribe, sin bloquear la fila, así que dos aceptaciones simultáneas de la misma invitación pueden pasar las dos la comprobación y chocar contra la clave primaria de `project_member`, devolviendo un 500 en lugar del `409` que el contrato promete. Queda como candidato para trabajo posterior, con el texto del revisor pendiente de recuperar |
| **Clientes que no son usuarios** | El operador de despliegue y los tres arneses no mandan credenciales. S1b (C27, C28) los cubre; hasta que esas dos tareas cierren, el corte verificado del Incremento 2 **no se puede volver a correr** |
| **`/deployments/**` sin alcance por proyecto** | Cualquier usuario autenticado puede detener el despliegue de otro. Lo cierra C5 en las guardas de despliegue; se declara explícitamente para que no se pierda |
| **Test inestable por puerto** | `DomainProjectEmitterTest` elige un puerto libre y de vez en cuando choca (`Port 57054 was already in use`), abortando la clase en 1 de sus 11 tests: una corrida completa da 220 y esa da 210. Es previo a este incremento y se declara; la reparación es una tarea propia |
| **Enumeración de proyectos por código de estado** | `requireProject` corre antes de la comprobación de membresía, así que un no-miembro distingue 404 de 403. Es una fuga de existencia de severidad baja; se declara en lugar de ocultarla |
| **Escritura por petición** | `IdentityService.resolve` actualiza `last_seen_at` en cada llamada autenticada, así que cada petición escribe. Costo aceptado y declarado para esta escala; no se finge escalado |
