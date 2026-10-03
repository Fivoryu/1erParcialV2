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
| C14 | Web: hook SSE, panel de presencia con color y nombre, y resaltado del elemento tocado | Pruebas de componente del panel y del resaltado. **Partida en C14 y C14b por tamaño de revisión** (ver *S3 — C14 partida*). **C14 cerrada** en `a59e67a` (ver *S3 progreso — C14*) |
| C14b | Web: **resaltado del elemento que cada uno toca** en el lienzo, con el color de quien lo toca y su nombre | Prueba de componente del resaltado: el nodo tocado se marca con el color y el nombre de quien lo toca, y deja de marcarse cuando el otro suelta. **Cerrada CON DESVIACIÓN DECLARADA** en `da3c2d6` (ver *S3 progreso — C14b*) |
| C15 | Web: cursores remotos en vivo, con coalescencia para no romper el SLO de RNF-11 | Prueba del coalescedor; medición de frecuencia de envío (21 emisiones para 100 actualizaciones sobre 1 s: **20/s**, dentro de la banda 10–30). **Cerrada CON DESVIACIÓN DECLARADA** en `c80b1f1` (ver *S3 progreso — C15*) |
| C15b | Web: **dibujo de los cursores remotos** en el lienzo, con color y nombre, paneando y zoomeando con la vista | Prueba de componente del overlay y del join puro. **Cerrada CON DESVIACIÓN DECLARADA** en `805aa57` (ver *S3 progreso — C15b*) |

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



### S3 — C14 partida, y la restricción medida que fija su diseño

C14 (“web: hook SSE, panel de presencia y resaltado del elemento tocado”) entra en **una sola tarea del plan** pero toca nueve archivos entre transporte,
panel y lienzo. Se parte por carga de revisión, no por comodidad: **C14** entrega *ver quién está* (transporte + reporte del toque propio + panel con color
y nombre) y **C14b** entrega *ver qué toca el otro* (el resaltado en el lienzo). Cada una tiene su corte visible y su propia prueba de componente.

**Restricción medida antes de lanzar al escritor, y es la que Fija el diseño:** el entorno de pruebas web **no tiene DOM** —`jsdom` y `happy-dom`
no están instalados, `vitest` corre sin `environment` (o sea, `node`) y las dependencias son las que la línea offline ya tiene—. Consecuencias
congeladas:

1. **No hay pruebas de hooks.** Un `renderHook` necesitaría un DOM y por lo tanto una dependencia nueva, que rompería la línea offline. Por eso el
transporte es un **módulo puro** (`web/src/collaboration/projectEvents.ts`) con el `fetch` **inyectado** —como el cliente de C10— y sin archivo de hook:
la página lo usa dentro de un `useEffect` con limpieza.
2. **Los componentes se prueban con `renderToStaticMarkup`**, que es el patrón que C10 ya usa y no necesita DOM.
3. El parseo de las tramas SSE se prueba con un `Response` con cuerpo en streaming, usando los globales de Node: sin navegador y sin red real.



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

### S3 progreso — C14 cerrada (2026-10-01)

**Commit:** `a59e67a` — *feat(web): show who is in the diagram, with their colour* (10 archivos, +756/−13).

**Revisión nativa:** linaje `review-7370c6ae0dd1ad3b`, tier `medium`, **una** lente (`review-reliability`), 10 caminos, 769 líneas, presupuesto
de corrección 200. Cerró **aprobada al primer intento** —el relay se recuperó despuÉs de los dos fallos de transporte de C12b, que siguen con su
remediación abierta— con autoridad quemada (artefacto del revisor: prompt 48.810 bytes, resultado 2.032 bytes).

**Dos hallazgos informativos** (ninguno abre corrección, ninguno reabre la revisión): `R3-malformed-presence` en
`collaboration/PresencePanel.tsx:23-29` y `R3-presence-touch-order` en `pages/ProjectEditorPage.tsx:313`, ambos `WARNING` con disposición
`informational`. Lecturas propias, **atribuidas a esta sesión y no al revisor**: en el primero, el panel dibuja los campos de cada entrada sin
validar la entrada misma (la página sí valida que `payload.presence` sea un arreglo), así que una entrada malformada renderizaría nombre o color
vacíos; en el segundo, el toque se reporta al cambiar la selección y un reporte que llegue antes de que la sesión se haya dado de alta devuelve
`false` por diseño, así que el elemento inicial de un lector puede no llegar a los demás hasta que cambie la selección — es el mismo límite ya
declarado en C12b («un reporte sin sesión viva no crea nada»).

**Medido al cerrar:** `npm run lint` 0 errores · `npm test` **120 pasadas, 4 omitidas** (117 de base + 3) · `npm run build` 0 errores (221 módulos).

**Historia de esta unidad, y por qué ocupó cuatro rondas de verificación** (se registra porque explica el tamaño y porque cada ronda encontró algo
real, no ruido):

1. **Ronda 1**: una respuesta tardía de `getModel` podía pisar el modelo del proyecto abierto (el refresco por evento no tenía guarda, y la ruta
   reusa el mismo elemento de página).
2. **Ronda 2**, sobre la corrección: las cachés por proyecto (`canonicalModelRef`, `revisionRef`, `pendingCommandsRef`) sobrevivían al cambio de
   proyecto, así que un modelo del proyecto nuevo podía rechazarse como «más viejo» contra la revisión del anterior; y la condición del reintento
   encolado no tenía ninguna prueba que pudiera fallar si se revertía.
3. **Ronda 3**: la clase no estaba cerrada: `reconcileAfterCommand`, el `.catch`/`.finally` de la carga inicial, el camino del comando encolado y el
   callback de presencia seguían escribiendo estado por proyecto tras el cambio. El padre **cortó la carrera guarda-por-guarda** y aplicó el arreglo
   sistémico: **remontar el editor con `key={projectId}`** (`web/src/App.tsx`), de modo que la instancia anterior se descarta entera y su stream se
   aborta; las guardas por camino quedan como cinturón y tirantes.
4. **Ronda 4** (cierre): confirmó que la clave aísla la instancia y que ninguna escritura de una instancia descartada afecta la pantalla actual; los
dos caminos que quedaron —el comando encolado que sigue aplicándose al proyecto donde se pidió, y una carrera preexistente del 401 con la sesión
global— se declaran abajo.

**Desviación de ruteo declarada:** de los cinco escritores delegados de esta unidad, **dos fallaron** (un error de proveedor y una **cuarentena de
capacidad** del arnés), y el padre escribió **dos cosas en línea**: la clave por proyecto en `App.tsx` (el arreglo sistémico, ~10 líneas) y la
corrección de su **propio** comentario exagerado —el texto decía que el cleanup aborta las peticiones en vuelo, y sólo aborta el stream de presencia—.
El resto de la unidad la escribieron escritores delegados, validados por el padre y por cuatro verificaciones independientes.

**Límites declarados de esta unidad:**

1. **Sin prueba automatizada para las guardas ni para la navegación**: la página no se puede renderizar sin DOM, así que la guarda por proyecto, el
   reseteo de cachés y la clave se verificaron **por lectura** más los tres comandos. No hay prueba de navegación A→B ni de navegador.
2. **Política de la cola de comandos, deliberada**: un comando que el usuario ya emitió **sigue aplicándose al proyecto donde lo emitió** aunque
   navegue. Cancelarlo en silencio sería perder trabajo suyo; la clave remonta la página pero no vacía la cola del servidor.
3. **Carrera preexistente y ajena**: en `api/client.ts` un 401 de una petición superada (token viejo, tras cerrar y volver a iniciar sesión) limpia la
   **sesión global**, y eso puede afectar la pantalla de la sesión nueva. Es preexistente (no la introdujo esta unidad) y queda como trabajo posterior.
4. **Fetch redundante**: el cliente refresca el modelo al recibir su **propio** `operation.applied`, y la reconciliación posterior a su comando ya lo
   había hecho. No corrompe nada (el servidor es la autoridad y es un GET), y queda declarado.
5. **StrictMode, sólo en desarrollo**: el ciclo montar/desmontar/remontar abre una conexión de más y puede duplicar el POST inicial del toque. El
   cleanup cierra la primera, así que no queda suscripción duplicada y en producción no ocurre.

### S3 progreso — C14b implementada y verificada, SIN COMMITEAR (revisión bloqueada por el relay) — 2026-10-01

> **Actualización (2026-10-02): commiteada con DESVIACIÓN DECLARADA, autorizada por el usuario.** Commit `da3c2d6`. El árbol ya no la contiene sin commitear.
> La remediación sigue abierta: revisar el **rango commiteado** cuando el relay responda. La regla de revisar antes de commitear **sigue vigente** para lo que venga.

**Estado:** el código está escrito, verificado y **sin commitear** (7 archivos: 5 modificados y 2 nuevos). **No se commiteó a propósito**: la regla acordada
con el usuario es no commitear sin revisión nativa cuando el relay está caído.

**Medido:** `npm run lint` 0 · `npm test` **128 pasadas, 4 omitidas** · `npm run build` 0 (222 módulos).

**Verificación independiente (dos rondas, ambas con hallazgos reales):**

1. **Ronda 1**: la función pura de agrupación **no deduplicaba**, así que dos entradas con el mismo `userId` sobrevivían, el orden podía depender de la entrada y la chip
   habría llevado **claves de React repetidas**. Bajo la invariante del servidor (el snapshot colapsa por usuario) no puede pasar, pero la función promete determinismo
   incondicional — y es la misma familia del hallazgo `R3-malformed-presence` del revisor de C14. Corregido con una regla canónica: gana el par `(displayName, color)`
   lexicográficamente menor, de modo que el resultado es independiente del orden **para cualquier entrada**.
2. **Ronda 2**: la aserción de «sin identificadores técnicos» cubría `markup` pero **no** `interfaceMarkup`, así que un id filtrado en la interfaz pasaba escondido.
   Corregido por el padre **en línea** (cambio de prueba, tres líneas) y declarado como tal.

**Lo que la revisión nativa no pudo hacer, medido:** tres intentos consecutivos de la corrida del revisor, los tres con `pi-host-relay-transport-failure`
(*WebSocket error*) a los **134 s, 172 s y 174 s** de un presupuesto de 2.400.000 ms, con `prepared_reviewers: 0` y `mutation_performed: false`. Se aplicó dos veces la
recuperación que indica el proveedor (STATUS fresco + binding exacto reofrecido). El linaje `review-1db88022feaea7dd` queda abierto en `reviewing` con su slot sin
satisfacer y **no se reusa**: el árbol sin commitear sigue siendo el mismo, así que puede reintentarse cuando el relay responda, o revisarse después sobre el rango
commiteado si se autoriza commitear con desviación declarada.

**Límites declarados de esta unidad:**

1. Sólo un toque cuyo objetivo sea un **clasificador** (clase, abstracta, interfaz, paquete) resalta una tarjeta; un toque sobre una **relación** o un **miembro**
   (atributo, operación) no resalta nada, porque viven en otros componentes que esta unidad no toca. Está escrito en el código, sobre el emparejamiento en `UmlCanvas`.
2. **Sin prueba de navegador**: el entorno de pruebas no tiene DOM, así que el resaltado se verifica por markup estático de la tarjeta y por pruebas puras del agrupamiento.
3. **Comparación contra la línea base no reproducible por el verificador**: la observó el padre (120 → 127 → 128) y el verificador confirmó los totales actuales, pero no
   pudo correr la revisión previa para comparar test por test.
4. Los tests de nombre-sólo y color-sólo en la deduplicación **no** están afirmados por separado: el que existe cambia nombre **y** color a la vez, así que esa rama
   queda cubierta por lectura, no por prueba.

### S3 progreso — C15 implementada y verificada, SIN COMMITEAR (revisión bloqueada por el relay) — 2026-10-02

> **Actualización (2026-10-02): commiteada con DESVIACIÓN DECLARADA, autorizada por el usuario.** Commit `c80b1f1`, que además incluyó
> `ProjectEditorPage.tsx` porque ese archivo lleva cambios de las dos unidades y no se pueden separar por hunks sin riesgo de romper el
> estado intermedio (los imports mezclan ambas). C14b quedó en `da3c2d6` sin ese archivo, y es coherente por sí solo porque el prop del
> resaltado es opcional. **Dos remediaciones abiertas**: revisar los rangos commiteados de C14b y de C15 cuando el relay responda.

**Estado:** código escrito, verificado y **sin commitear** (10 archivos propios, más 3 fixtures y la página compartida). **No se commiteó a propósito**: la regla
acordada es no commitear sin revisión nativa mientras el relay esté caído. Queda **junto a C14b**, que está en el mismo estado: **dos unidades sin commitear**.

**Medido, y reproducido por el verificador con sus propias corridas:**
`mvn -o -B -f backend/pom.xml verify` = **278 pruebas, 0 fallos, `BUILD SUCCESS`** (275 de base + 3) · `PresenceChannelTest` = **11 pruebas** ·
web `npm run lint` 0 · `npm test` **132 pasadas, 4 omitidas** · `npm run build` 0 (223 módulos).

**Qué entrega:** el cursor viaja como parte de `presence.updated` (el catálogo del contrato no tiene otro nombre de evento), con **dos formas de payload deliberadas**:
cambio de integrantes o de elemento → **snapshot completo**; movimiento de cursor → **sólo el cursor** (`userId`, `x`, `y`), para que hasta 30 actualizaciones por segundo por
usuario no rebroadcasteen la lista entera. Del lado del cliente, un **limitador de tasa** (intervalo de 50 ms = **20/s**, dentro de la banda que pide RNF-11) con reloj
inyectable: primera emisión inmediata, como máximo una por intervalo, y **siempre la última posición pendiente**. La medición que el plan pedía está hecha: 100
actualizaciones sobre 1 s de tiempo inyectado producen **21 emisiones**.

**Cuatro defectos que encontró la verificación independiente, los cuatro corregidos antes de esta foto:**

1. **Un reporte con una sola coordenada borraba el elemento tocado**: `hasCursor` exigía ambas, así que una coordenada suelta caía al camino del target y llamaba
   `touchUser(..., null)`. Ahora un par incompleto es un reporte inválido: **400 `INVALID_PAYLOAD`** con el sobre de siempre, y no cambia nada. Se prefirió rechazar
   el reporte malformado antes que adivinar. (El escritor lo había descrito como «sigue el camino legacy», que describía la rama pero **ocultaba el efecto**.)
2. **Abrir una segunda pestaña podía borrar el cursor visible**: `join` sólo heredaba campos de la misma sesión, así que la sesión nueva nacía con cursor nulo — y el
   snapshot elige la sesión **más reciente**. Ahora una sesión nueva del mismo usuario se **siembra** desde el estado vivo de ese usuario (cursor y elemento tocado),
   mientras que una sesión reutilizada por **otro** usuario sigue arrancando limpia: la regla que se arregló con medición en C12 no se toca.
3. **El cursor estaba tipado como opcional además de nulable** — una concesión del escritor para no tocar fixtures ajenas. El servidor **siempre** manda la clave, así
   que ahora es **requerido y nulable**, con las fixtures actualizadas y una aserción de tipos que falla si vuelve a ser opcional.
4. **El transporte era código muerto**: el coalescedor no tenía llamador y la página ignoraba los eventos de cursor. **Eso fue culpa del encargo**, que dejó el cableado
   para después; ahora la página consume los eventos de cursor de forma incremental, alimenta el coalescedor desde el movimiento del puntero, reporta y cancela al
   desmontar o cambiar de proyecto.

**Límites declarados de esta unidad:**

1. **El dibujo del cursor remoto no entra acá**: la página guarda el mapa de cursores pero **nadie lo lee todavía** hasta que se dibuje. Es un puente declarado entre
   dos unidades, no una función a medias.
2. **Sin prueba de integración de la página**: no hay DOM en el entorno de pruebas, así que el ciclo evento+puntero se verifica por lectura y por tipos, no por prueba.
3. **El registro no tiene barrido propio de sesiones obsoletas**: la limpieza depende del ciclo de vida del canal (C11/C12b), y sólo puede sembrar desde entradas
   todavía registradas.
4. Inherente a la revisión read-only: la comparación del delta de pruebas contra una foto anterior no es reproducible por el verificador; los totales actuales sí
   (278 backend, 132 web), y los observó él mismo.

**Incidentes de entorno de esta etapa, diagnosticados (importan para retomar):** los montajes del shell pasaron de `/c` a **`/mnt/c`** y de `/d` a **`/mnt/d`** (parecía
que el proyecto había desaparecido); el `PATH` perdió Java y Docker, así que el JDK y Maven existen pero **no se encontraban** (Maven hay que invocarlo por `cmd.exe` con
`JAVA_HOME` en formato Windows; Testcontainers sí llega a Docker por *named pipe*); y git perdió la identidad de autor, que se restauró a nivel de repositorio con la
**misma** que ya usaba la rama. Además, una corrida lanzada con `nohup` **no sobrevive** al fin de la llamada: la suite completa hay que correrla **en primer plano**.

### S3 progreso — C15b cerrada CON DESVIACIÓN DECLARADA (2026-10-02)

**Commit:** `805aa57` — *feat(web): draw the remote cursors on the diagram* (7 archivos, +280/−13).

> **Sin revisión nativa.** El relay del revisor falló **tres intentos consecutivos** (`pi-host-relay-transport-failure`, WebSocket error, 202 s / 152 s / 195 s
de 2.400.000, `prepared_reviewers: 0`, sin mutación) y se aplicó dos veces la recuperación que indica el proveedor. Se commiteó con **desviación declarada**
> asumiendo el mismo criterio que el usuario autorizó dos veces antes para esta misma situación; queda señalado para que pueda objetarlo.

**Qué entrega:** cada cursor remoto aparece **donde está en el diagrama**, con el color y el nombre de quien lo mueve, y **panea y hace zoom** con la vista. El
join de presencia a marcadores es un módulo puro (probado sin navegador) y el overlay tiene `pointer-events: none`, así que **no intercepta clics ni arrastres**:
un clic sobre una clase tapada por un cursor sigue seleccionándola.

**Dos defectos de coordenadas corregidos acá, y uno era introducido por C15 (ya commiteada):**

1. **El espacio de coordenadas era el de la ventana**, no el del diagrama. Dos clientes difieren en tamaño de ventana, scroll y ancho de panel, así que el cursor del
otro habría caído en el lugar equivocado. Ahora se captura dentro del lienzo con la transformación de React Flow.
2. **`screenToFlowPosition` respetaba el `snapToGrid` de 24 unidades del lienzo**, así que la posición reportada saltaba en escalones. La grilla se queda (existe para
   arrastrar nodos) y la conversión del cursor se saltea el ajuste.
3. **La colocación restaba el origen *transformado***, y eso cancelaba justo la traslación de la vista: al panear, el cursor remoto se quedaba pegado al panel en vez
de seguir su lugar. La posición local respecto del overlay es `punto * zoom + (viewport.x, viewport.y)`, que es la fórmula que quedó y la que el verificador confirmó
contra el código de la librería (`screenToFlowPosition` resta el origen del root; la inversa multiplica por el zoom y suma la traslación).

**Límite declarado:** **ninguna de las dos correcciones de coordenadas se puede cubrir con una prueba automatizada en este entorno** —necesitan geometría real de
layout y no hay DOM—, así que se verificaron por lectura, por los tres comandos y por la verificación independiente contra la fuente de React Flow. El caso manual
que las mostraría funcionando es **panear y después mover el cursor remoto**. Tampoco hay prueba de navegador para el overlay.

> **Estado de las ramas al 2026-10-03 (medido por la sesión de XMI, no por ésta):** `main` se movió de `90f4f4a` a **`012ae6e`** por avance rápido
> (decisión explícita del usuario, asentada en §12.4 del acta de la sesión de XMI). Esa punta es la fusión que une **esta** rama (`61bf2d2`) con
> `feat/xmi-intercambio` (`7c2ad45`) en el commit de fusión `f3f8e33`. Consecuencia declarada y vigente: **`main` quedó atrás de esta rama** — C17 en
> adelante, S5 y S7 no están en `main`, y corregirlo lo vuelve a decidir el usuario. La **rama de esta sesión no está empujada** al remoto privado
> (`github.com/Fivoryu/1erParcialV2`), que la sesión de XMI creó; empujarla es decisión del usuario. La **revisión nativa de la fusión no se corrió**, con
> motivo medido: un merge no tiene base que aísle la resolución (sus únicas bases son los padres, y el alcance sería el contenido entero de una rama,
> 58 archivos/7.052 líneas o 41/11.048), así que quedó cubierta por el verificador independiente, las cuatro compuertas y la autocrítica de la sesión de
> XMI, que encontró y corrigió un defecto **propio** de su resolución manual —`previewXmi` caído del objeto de implementación, `TS2741`—. Eso es evidencia
> de otro tipo, no revisión, y así queda declarado.

### S4 — Chat persistente del diagrama

| # | Tarea | Evidencia que la cierra |
| --- | --- | --- |
| C16 | Migración `V9__diagram_messages.sql`: tabla `diagram_message` (nueva, no está en el físico) con índices por modelo y tiempo | Migración aplicada contra PostgreSQL real (Flyway 1–9, PostgreSQL 16.15) y prueba de esquema en verde (9 pruebas). **Cerrada CON DESVIACIÓN DECLARADA** en `3c777e5` (ver *S4 progreso — C16*) |
| C17 | `ChatService` y `GET/POST /projects/{id}/messages`, con autorización, saneado y evento `message.posted` | Integración: publicar, listar ordenado, y rechazo sin rol. **Cerrada CON DESVIACIÓN DECLARADA** en `d9d4d0a` (ver *S4 progreso — C17*): 8 pruebas nuevas y 286 en la suite |
| C18 | Web: panel de chat con historial, autor y hora, y recepción en vivo | Pruebas de componente y lint. **Cerrada CON DESVIACIÓN DECLARADA** (ver *S4 progreso — C18*): 152 pruebas web, lint 0, build 0 |

### S4 progreso — C16 cerrada CON DESVIACIÓN DECLARADA (2026-10-03)

**Commit:** `3c777e5` — *feat(backend): persist the diagram chat* (3 archivos, +27/−1).

> **Sin revisión nativa.** Tres fallos consecutivos de transporte del relay (`pi-host-relay-transport-failure`, WebSocket error, 110 s / 134 s / 167 s de
> 2.400.000, sin mutación) con la recuperación indicada aplicada dos veces. Se commiteó con **desviación declarada**, mismo criterio que el usuario ya
> autorizó para esta situación; queda señalado para que pueda objetarlo.

**Qué entrega:** la tabla `diagram_message` ligada al **modelo** (la API será por proyecto porque hoy un proyecto tiene un modelo y `uml_project` no existe:
`uml_model.project_id` es `UNIQUE`), con autor **restringido** al borrar y cascada por modelo, y un índice `(model_id, created_at, message_id)` — el tiempo es
lo que lee el historial y el id desempata dos mensajes del mismo instante. El comentario de la tabla declara el invariante: **revocar el acceso no borra lo que
se escribió mientras se tenía**. Sin enums, que importa porque dos de las compuertas del test son sobre columnas y etiquetas de enum.

**Y arregla una mentira del documento de autoridad:** `docs/puds/datos/02-esquema-fisico.sql` no declaraba esta tabla, así que la migración estaba creando algo
que el físico nunca mencionaba. Ahora está declarada ahí, con su índice, en la misma forma que las tablas vecinas y **sin tocar nada más** — ese archivo es punto
de fusión con la sesión de XMI, que le agrega dos tablas propias.

**Medido, y reproducido por el verificador con sus propias corridas:** `mvn -o -B -f backend/pom.xml test -Dtest=SchemaOwnershipTest` = **9 pruebas, 0 fallos**
(la corrida prueba que Flyway aplica 1–9 contra PostgreSQL **16.15** real) · `mvn -o -B -f backend/pom.xml verify` = **278 pruebas, 0 fallos**.

**Corrección de documentación aparte (`c7e17fc`):** el contrato congelado §S6b afirmaba que `diagram_message` estaba en `TABLES_RESERVED_FOR_LATER_TASKS` y que
había que sacarla. **Medido dos veces —esta sesión y un verificador independiente— no está ahí** (la lista tiene `model_conflict`, `name_collision`, `ai_proposal`,
`detected_element`, `xmi_exchange`, `xmi_loss_item`, `audit_log`); `invitation` salió al migrarse en C8 y `diagram_message` nunca estuvo. Cumplir en falso esa
instrucción habría significado «sacar» algo que no existía. Las que sí están y deben salir son las dos de la sesión de XMI.

**Límites declarados de esta unidad:**

1. **No hay prueba nueva**: la tabla queda forzada por las compuertas **exactas** que ya existían (`MAPPED_COLUMNS`, `SUPPORT_INDEXES`, la lista de versiones), así
   que el total de la suite no cambia. Es el diseño, no un descuido — pero conviene saberlo: **el verificador confirmó que no hay ningún método nuevo**.
2. Las compuertas comparan **conjuntos de nombres**, no el tipo físico de las columnas ni el **orden** de las columnas del índice: eso lo declara la migración y lo
   verificó la lectura del verificador, no una aserción.
3. **La fusión con la sesión de XMI no está probada**: este candidato no crea `V10` ni toca `xmi_exchange`/`xmi_loss_item`, y **medido: no existe ningún `V10`** en
   fuentes ni en `target` (la rareza «validated 10 migrations» que vio el verificador no corresponde a ningún archivo), pero la unión real contra los cambios de la
   otra sesión no se pudo verificar acá.

### S4 progreso — C17 cerrada CON DESVIACIÓN DECLARADA (2026-10-03)

**Commit:** `d9d4d0a` — *feat(backend): talk in the diagram* (7 archivos, +562).

> **Sin revisión nativa**: tres fallos consecutivos de transporte del relay (324 s / 236 s / 308 s de 2.400.000, sin mutación) con la recuperación indicada
> aplicada dos veces. **Desviación declarada**, mismo criterio autorizado por el usuario para esta situación.

**Qué entrega:** historial y publicación por proyecto. **Cualquier miembro participa, VIEWER incluido** —conversar sobre el diagrama no es editarlo— y esa regla está
fijada por una prueba y escrita en el código, porque es la clase de decisión que alguien "corrige" después asumiendo que sólo escriben los editores. El cuerpo se
recorta y se limita; vacío o excedido es `400` y **no escribe nada**. **No hay sanitización de HTML** y el comentario dice por qué: el servidor guarda texto plano y el
cliente lo renderiza como texto, así que escapar es una garantía del cliente y no algo que el servidor finja hacer. El historial ordena por hora y desempata por id.

**El evento `message.posted` no estaba en el catálogo del contrato §4.1** (medido): lo autoriza el plan, así que el escritor **agregó la fila al contrato** con su
contenido mínimo — la misma regla que se aplicó al esquema físico en C16, porque una autoridad que no menciona lo que el sistema emite está mintiendo.

**Un defecto que encontró la verificación independiente, y la solución superó lo pedido:** el escritor publicaba registrando una sincronización `afterCommit`
**incondicionalmente**. Con transacción activa (el camino del controlador) eso es correcto y **mejor** que publicar después del bloque, porque espera el commit externo;
pero sin proxy transaccional la registración lanza `IllegalStateException` **después** de que el mensaje ya se escribió: el mensaje quedaba guardado y el llamador
recibía un error. Ahora hay dos ramas —con sincronización espera el commit, sin ella publica apenas la escritura es durable— y una prueba que llega al servicio
**sin proxy** (`AopTestUtils.getTargetObject`) fija la guarda.

**Medido, y reproducido por el verificador con sus propias corridas:** `ChatApiTest` = **8 pruebas, 0 fallos** · `mvn -o -B -f backend/pom.xml verify` =
**286 pruebas, 0 fallos** (278 + 8).

**Límites declarados:**

1. **Sin prueba de rollback forzado**: la publicación espera el commit y la entrega por SSE está probada, pero no se forzó un rollback para ver que no publica.
2. Sin prueba del **borde exacto** de 2000 caracteres (el `> MAX_BODY_LENGTH` lo implica, no lo afirma una prueba).
3. La **cardinalidad del evento** no se afirma: se comprueba el contenido, no que sea exactamente uno por mensaje.

### S4 progreso — C18 cerrada CON DESVIACIÓN DECLARADA (2026-10-03) — **S4 COMPLETO**

**Commit:** el de esta unidad (9 archivos: panel de chat, formateador puro, tipos y métodos de cliente, cableado y estilos).

> **Sin revisión nativa, por fallo nativo del proveedor**: el START devolvió `native-operation-failed` / `candidate-owner-preparation-failed` («candidate view
> rejected before native START»), **sin crear linaje y sin mutación**, y su único `next_action` es «resolve-native-operation-failure», sin diagnóstico adicional.
> Medido antes de declararlo: el árbol tenía exactamente los 9 archivos declarados (5 modificados + 4 nuevos) y `.codegraph/` está ignorado, así que la falla **no**
> es por un archivo sin declarar. Es un modo nuevo, distinto del `pi-host-relay-transport-failure` de las unidades anteriores.

**Qué entrega:** el panel de chat del editor con historial (autor y hora por mensaje, del más viejo al más nuevo), envío, y recepción en vivo por `message.posted`.
Dos reglas que quedaron escritas en el código porque son la clase de cosa que alguien rompe después: **desduplicación por id** (quien publica recibe su propio evento, así
que el mensaje aparecería dos veces: se ignoran los ids ya presentes, en los dos órdenes posibles, y también al fusionar el historial) y **el cuerpo se renderiza como texto,
con `dangerouslySetInnerHTML` prohibido** (el servidor guarda texto plano y no sanitiza HTML, así que escapar es trabajo del cliente).

**Medido:** `npm run lint` 0 · `npx vitest run` = **152 pruebas pasadas, 4 omitidas** (141 de base + 11) · `npm run build` 0 (227 módulos).

**Verificación independiente:** un `FAILS` real, **defensivo y corregido antes del commit** — `new Date(null)` es la época Unix, así que un payload malformado con
`createdAt: null` mostraba **1970** en vez del fallback; ahora cualquier valor no utilizable cae en el mismo texto que ya usaba una cadena inválida. Todo lo demás HOLDS,
con lo importante confirmado por búsqueda exhaustiva: **ninguna vía de inyección de markup en todo `web/src`**, la hora de cada mensaje atada a **su propia fila**, y la
desduplicación funcionando con el evento antes y después de la respuesta del POST.

**Límites declarados:** el cableado de página (fetch, evento, POST) **no tiene prueba automatizada** y sin DOM no puede tenerla —queda verificado por lectura—; el cliente
acepta **cualquier 2xx** y no exige exactamente el 201 que manda el servidor (convención de `requestJson`, no descuido de esta unidad); y el locale/zona con que el panel
formatea la hora salen del entorno, mientras la **prueba** del formateador usa locale y zona explícitos para ser determinista.

### S5 — Bloqueo de exportación con aviso y reclamo

| # | Tarea | Evidencia que la cierra |
| --- | --- | --- |
| C19 | `EditingLeaseService`: adquirir, renovar por latido, vencer por TTL y reclamar | Pruebas unitarias con reloj controlado. **Cerrada** en `14053ce` (ver *S5 progreso — C19*) |
| C20 | Guarda en `POST /generations`, en la descarga de artefactos y en la exportación XMI; respuesta con el editor, el motivo y la posibilidad de reclamar | Integración: bloqueado con lease ajeno, permitido con lease propio y tras vencer |
| C21 | Web: botones de exportación con motivo y editor nombrado, y botón **Reclamar control** | Pruebas de componente de los tres estados |

### S5 — diseño congelado de la concesión de edición (antes de C19)

La tabla de S5 nombra las cuatro operaciones y §S6b congela **sólo** el nombre del tipo y `requireExportAllowed(UUID modelId)`, así que la
semántica de adquisición, vencimiento y reclamo queda decidida acá antes de que exista el primer archivo. Sin esto, C19, C20 y C21 se
diseñarían entre tres y el reclamo terminaría siendo un alias silencioso de la adquisición.

**Lo que el plan ya resolvía, y de dónde sale:** §1 pide «un botón para reclamar el control **cuando su lease vence**»; C6 se decide contra el
bloqueo indefinido porque «con TTL corto renovado por latido, el bloqueo es real mientras se edita y **se libera solo** cuando el editor
desaparece»; y la evidencia de C20 dice **«permitido con lease propio y tras vencer»**. Las tres frases juntas fijan: una concesión **viva**
es la única que bloquea, el TTL la mata sola, y reclamar es el acto explícito de un usuario, disponible recién cuando la anterior ya no está viva.

**Reglas congeladas de C19:**

1. Se guarda **una concesión por modelo**, efímera y en memoria (C4): `editorUserId`, `editorDisplayName`, `targetType`, `targetId`,
   `acquiredAt` y `expiresAt`. `targetType` es el enum que ya existe, `com.diagramador.domain.TargetType` — no se inventa vocabulario.
2. `viva(now)` es `now < expiresAt`. Sólo una concesión viva de **otro** usuario bloquea.
3. `acquire`: sin concesión, la toma; del **mismo** usuario, la refresca (es el latido); viva de otro usuario, `409 EXPORT_BLOCKED_EDITING`;
   **no viva de otro usuario, también `409`, pero con `reclaimable: true` y `retryAfterSeconds: 0`** — quitarle el control a otro es un acto
   explícito y no un efecto colateral de empezar a editar.
4. `renew`: sólo el titular vivo; extiende `expiresAt`. Una concesión vencida **no revive** por renovación.
5. `release`: sólo el titular; idempotente; sin titular no hace nada.
6. `reclaim`: es lo único que toma el control de otro usuario, y **sólo si esa concesión ya no está viva**; viva devuelve el mismo `409`.
7. `current(modelId)`: la concesión registrada (viva o no) o vacío; es la lectura del endpoint de estado de C20.
8. `requireExportAllowed(modelId)`: lanza `ApiException(409, "EXPORT_BLOCKED_EDITING")` **sólo** con una concesión viva ajena; el titular
   puede exportar su propio modelo y **el principal de operador queda exento**, como congela §S6b.
9. `details[0]` lleva exactamente `editorDisplayName`, `targetType`, `targetId`, `reclaimable` y `retryAfterSeconds`, en ese orden;
   `targetType` viaja como `TargetType.name()` y `retryAfterSeconds` es el techo de los segundos que faltan, nunca negativo.
10. C19 **no publica ningún evento**: el catálogo del contrato §4.1 no tiene un nombre para esto y no se le inventa uno.

**Límites declarados de esta decisión:** el TTL por defecto es **30 s** (dos latidos del canal, que late cada 15 s) y es configurable para que
C20 pueda vencerlo sin esperar; sigue siendo de **una sola instancia** (C4), así que con dos instancias la concesión no cruza; y **un editor cuyo
latido siga fluyendo no puede ser despojado** — la salida es que el TTL venza, y si el canal sigue vivo la concesión sigue viva. Eso último se
declara y no se finge resuelto.

### S5 — diseño congelado de los endpoints de la concesión (antes de C20)

C20 toca **tres superficies con dos formas de respuesta distintas** y agrega cinco endpoints que C21 consume y que la sesión de XMI
usa como molde para su guarda. Todo eso se decide acá porque hay un dato medido que un escritor habría improvisado: **`OperationOutcomeResource`
no tiene campo `details`** —es `(outcome, code, message, idempotencyKey, result)`, `GenerationResources.java:89-95`—, así que en la respuesta de
`POST /generations` las cinco claves de §S6b no tienen dónde viajar salvo dentro de `result`.

**Direccionamiento: el modelo se resuelve desde el proyecto, el cliente nunca manda un `modelId`.** Medido: `UmlModel` es uno a uno con `Project`
(único `project_id`), **no existe ninguna ruta `/models/{modelId}`**, y el código que ya existe resuelve el modelo desde el proyecto
(`GenerationApiService.requireModel` → `modelRepository.findByProjectId`, `:249` y `:272`). Las cinco rutas cuelgan del proyecto y el servidor
traduce a `UUID modelId` para la interfaz congelada de C19, que no cambia. El estilo es el de `ProjectPresenceController` (ruta completa en el
método, sin prefijo de clase, `POST /projects/{id}/presence`) y `DELETE` ya es verbo de esta casa (`InvitationController`).

| Verbo y ruta | Operación de C19 | Cuerpo | Éxito |
| --- | --- | --- | --- |
| `POST /projects/{projectId}/editing-lease` | `acquire` | opcional `{targetType, targetId}` | 200 |
| `POST /projects/{projectId}/editing-lease/renew` | `renew` | — | 200 |
| `DELETE /projects/{projectId}/editing-lease` | `release` | — | 200 |
| `POST /projects/{projectId}/editing-lease/reclaim` | `reclaim` | opcional `{targetType, targetId}` | 200 |
| `GET /projects/{projectId}/editing-lease` | `current` | — | 200 |

**Una sola forma de respuesta para los cinco**, agrupada en un `EditingLeaseResources.java` nuevo como ya se agrupan `ChatResources` y
`GenerationResources`:

```json
{ "lease": { "modelId": "…", "editorUserId": "…", "editorDisplayName": "…", "targetType": "CLASSIFIER",
             "targetId": "…", "acquiredAt": "…", "expiresAt": "…" } }
```

con `"lease": null` cuando no hay ninguna registrada —sólo posible en `GET` y después de `release`—. **Por qué un sobre y no `204` ni `404`:** el
cliente parsea un solo tipo; `null` distingue «nadie está editando» de un `403`/`404`, que significan otra cosa; y `204` obligaría a un caso especial
en el cliente para una lectura que hoy no existe en el proyecto. **Por qué viaja `expiresAt` y no `live` ni `reclaimable`:** la regla de vivacidad vive
dentro del servicio con su propio reloj (regla 2 de C19) y la interfaz no expone ni `now` ni una consulta de vivacidad; calcularla en el borde pediría
un **segundo reloj**, que es exactamente la deriva que este congelamiento evita. El cliente compara `expiresAt` con su reloj y el servidor sigue siendo
la autoridad: si el cliente calcula mal, la próxima escritura se rechaza y el rechazo trae la verdad.

**Autorización:** las cinco piden acceso de editor con el `ProjectAuthorizationService.requireEditor` que ya existe —operador o miembro activo
OWNER/EDITOR—, la misma compuerta que usa la generación (`GenerationApiService:113-115`). La identidad del solicitante (`userId` y `displayName`) sale
de `CurrentUserContext.require()`; **es la primera vez que un controlador lee `displayName()`**, y se declara.

**La forma única del conflicto, y de dónde sale.** `renew` falla con `Optional.empty()` (regla 4 de C19) mientras el resto **lanza**, y
`retryAfterSeconds` (regla 9) necesita el reloj del servicio. Así que las cinco claves se arman **adentro** del servicio y se exponen como un solo
registro:

```java
public record EditingLeaseConflict(String editorDisplayName, TargetType targetType, UUID targetId,
        boolean reclaimable, int retryAfterSeconds) {}
```

más **un** método en la interfaz congelada: `Optional<EditingLeaseConflict> blocker(UUID modelId)`, vacío sólo cuando no hay ninguna concesión
registrada. `requireExportAllowed` y los rechazos de `acquire`/`reclaim` se refactorizan para armar su `details[0]` desde ahí, así **el conjunto de
claves y su orden existen una sola vez** y no pueden derivar. `reclaimable` es «la concesión registrada no está viva»; `retryAfterSeconds` es el techo
de los segundos que faltan, nunca negativo. **`requireExportAllowed(UUID modelId)` conserva su firma y su comportamiento exactos**, porque §S6b la
congeló y la sesión de XMI depende de ella sin poder escribirla.

**Las dos formas del bloqueo, y la diferencia es del contrato, no del escritor:**

- **Endpoints de archivo** —la descarga de artefactos, y después la exportación XMI—: HTTP **409** con el sobre `ApiErrorResource` de siempre y
  `details` = una **lista con un objeto** con las cinco claves, serializada por el `@RestControllerAdvice` que ya existe (`ApiExceptionHandler:51-54`).
  La guarda **lanza**; el manejador arma el sobre. Ojo con un detalle que no se «unifica»: la descarga ya responde 200 con `outcome` para
  `RUN_NOT_COMPLETE`, pero el bloqueo por concesión **no** va por ese camino —§S6b congela 409 para endpoints de archivo—.
- **`POST /projects/{projectId}/generations`**: HTTP **200** con `outcome`, la convención D7 del Incremento 2. Como `OperationOutcomeResource` no tiene
  `details`, las cinco claves viajan adentro de **`result`**, como el mismo registro. El punto de traducción es un `try/catch (ApiException)` alrededor
  de `requireExportAllowed` dentro de `createGeneration`, que devuelve
  `outcome("REJECTED", "EXPORT_BLOCKED_EDITING", mensaje, request.idempotencyKey(), conflict)` y **re-lanza cualquier otra `ApiException`**. El `catch` es
  deliberado: mantiene al servicio como única autoridad de la vivacidad y de la exención del operador, en vez de reimplementarlas en el borde.
  Consecuencia declarada: **las mismas claves, en el mismo orden, con dos portadores** —`details[0]` en el camino de error, `result` en el camino de
  outcome—, porque la convención del Incremento 2 no admite un error HTTP ahí.

**Dónde va cada guarda, en orden exacto:**

1. `createGeneration`: después de `requireEditorProject` (403) y de `validateGenerationRequest` aceptado (`MODEL_NOT_IN_PROJECT` y compañía) y **antes**
   de `identityService.startRun`, para que una generación bloqueada no deje ningún run. El `modelId` sale de `request.modelId()`, que ya quedó validado
   como el del modelo del proyecto.
2. `downloadArtifacts`: después de la comprobación de que el run pertenece al modelo (`GenerationApiService:161-179`) y antes de armar el ZIP, así «no
   existe» sigue siendo «no existe» y no se producen bytes.
3. **Exportación XMI: sigue siendo la línea de una sola llamada de la sesión hermana** en el consolidado (§S6b). Hasta entonces el acta declara que la
   exportación XMI no está protegida, y esa frase no se borra.

**Los estados que la evidencia de C20 tiene que probar** (integración, con `@SpringBootTest` y PostgreSQL real, como `GenerationApiTest`, 632 líneas):

1. **Bloqueado con concesión viva ajena.** En `POST /generations`: 200, `outcome=REJECTED`, `code=EXPORT_BLOCKED_EDITING`, `result.editorDisplayName` del otro
editor, `reclaimable=false`, `retryAfterSeconds>0`, y **ningún run creado**. En la descarga: 409 y `details[0]` con las cinco claves exactas y en orden.
2. **Permitido con concesión propia.** El titular exporta y descarga: 200.
3. **Permitido después de vencer.** Con la concesión ajena ya no viva, la exportación se permite —el TTL la mata sola— y el `GET` la sigue mostrando.
4. **Exento por operador:** con una concesión viva ajena, el operador exporta igual (regla 8 de C19).
5. **Ciclo de los cinco endpoints:** `acquire` → `renew` → `GET` con la concesión → `DELETE` → `GET` con `lease: null`; `reclaim` sólo con la anterior
   no viva; `acquire` sobre una vencida ajena da 409 con `reclaimable: true` y `retryAfterSeconds: 0`; un par `targetType`/`targetId` incompleto da
   400 `INVALID_PAYLOAD`; quien no es editor da 403.

**Límites declarados de este congelamiento:**

1. **La vivacidad se calcula en el cliente** comparando `expiresAt` con su reloj. Con desfase de reloj, el cliente puede habilitar un botón que el
   servidor rechaza; el rechazo trae la verdad. No se agrega un segundo reloj en el servidor por esto.
2. **`targetType` viaja como texto en el cuerpo** y se parsea en el borde, para que un literal desconocido termine en 400 `INVALID_PAYLOAD` con el sobre
   propio y no en el error por defecto de Spring. El par incompleto lo sigue validando C19.
3. **`renew` que falla devuelve el mismo 409 con las cinco claves sólo si hay una concesión registrada.** Si no hay ninguna —nunca la tuviste, o la
   soltaste—, `details` queda **vacío**: no se inventa un editor ni se rellenan las claves con `null`, y la acción del cliente es adquirir de nuevo.
4. **La traducción del borde no reimplementa la vivacidad ni la exención del operador:** las dos siguen viviendo en el servicio.
5. **Una sola instancia (C4):** con dos instancias la concesión no cruza, y ninguna guarda de este borde lo arregla.
6. **La guarda de la generación no es atómica contra la concesión:** entre la comprobación y el arranque del run puede adquirirse una concesión —el
   *check-then-act* que ya quedó como `R3-001` en C19—. La ventana es de microsegundos y el efecto es un run que arranca justo cuando alguien empezaba a
   editar. Se declara y no se finge resuelto.
7. **Sin eventos y sin tabla:** C20 no publica nada nuevo ni persiste nada (regla 10 de C19); `targetType`/`targetId` son la respuesta a «qué está
   editando», no una suscripción.

### S5 — diseño congelado de la web de la concesión (antes de C21)

El §1 pide que «mientras uno tiene una edición activa» la exportación quede bloqueada, pero la fila de C21 en el plan describe **sólo el lado que
consume** el bloqueo: motivo, editor nombrado y botón de reclamo. **Ninguna unidad se hacía dueña de tomar y renovar la concesión desde la web**, y sin
eso las guardas de C20 quedan inertes en producción: nadie la tiene, nadie ve el bloqueo. Por decisión del usuario del 2026-10-03, C21 cubre **los dos
lados** y se congela acá antes de escribir la primera línea, igual que C19 y C20.

**Lo primero que hay que decir, porque ordena todo lo demás: la concesión NO bloquea la edición.** No impide editar; lo que bloquea es **exportar**
mientras la tiene otro. Con dos personas editando, **una sola** la tiene —`acquire` con una viva ajena es `409` y no se la roba (regla 3 de C19)—, la
otra sigue editando igual, y su actividad no renueva nada. Quien exporta es quien se topa con el aviso.

**Reglas congeladas:**

1. **Cuándo se toma.** El editor la pide en el primer acto que **modifica el modelo** y la vuelve a pedir en los siguientes: si ya es suya, `acquire` la
   refresca, así que la actividad misma es latido. **No se pide al montar la página**: estar mirando el diagrama no es estar editando.
2. **Latido.** Un temporizador de **15 s** (el TTL es 30 s = dos latidos, decisión C6) que actúa **sólo mientras la concesión es nuestra**: `renew`.
   Si **no** la tenemos, el latido no hace nada: reintentar `acquire` cada 15 s contra una concesión viva ajena produciría un 409 por minuto sin ningún
   valor. La adquisición se reintenta en la próxima actividad del usuario.
3. **Si `acquire` devuelve `409`.** La edición **no se interrumpe** y no se muestra un error genérico: se muestra el aviso con el nombre y la razón, y
   el botón **Reclamar control** queda habilitado **sólo** si la concesión ajena ya no está viva (`reclaimable: true`). Reclamar **nunca** es automático:
   quitarle el control a otro es un acto explícito (reglas 3 y 6 de C19, decisión C6).
4. **Al salir** se suelta con `release` al desmontar la página. Un cierre de pestaña **no se puede garantizar** —`beforeunload` no espera a la red— y por
   eso existe el TTL. Se declara y no se finge.
5. **Una sola forma canónica del conflicto.** Una misma función mapea **los dos portadores** que ya existen —`details[0]` del 409 y `result` del `outcome`
   200— a un objeto único con las cinco claves. El cliente **no recalcula la vivacidad cuando el servidor ya la mandó**: usa `reclaimable` y
   `retryAfterSeconds` del sobre. Cuando lee del `GET`, sí compara `expiresAt` con su propio reloj (desfase declarado abajo).
6. **Toda la decisión en módulos puros; el hook y los componentes, delgados.** Este proyecto no usa DOM ni testing-library: se prueba lógica pura y markup
   estático con `renderToStaticMarkup`. El latido es por lo tanto una **decisión pura** —qué corresponde hacer dado el estado y el instante—, probada sin
   temporizadores reales; el adaptador de React sólo la ejecuta.
7. **Los tres estados que la prueba de componente tiene que fijar** (es la evidencia que el plan pide para C21): sin concesión registrada; concesión
   **viva ajena** —nombre visible, motivo visible, botón **deshabilitado**—; concesión ajena **ya no viva** —botón **habilitado**—. Y un cuarto que se
   agrega: concesión **propia** no muestra aviso.
8. **Las copias van en inglés**, como el resto de la UI (medido: «Project workspace», «In this diagram», «Generate backend»), con el `displayName`
   interpolado.
9. **Sin dependencias nuevas y sin un segundo servicio de tiempo.**
10. **Archivos compartidos con la sesión de XMI:** se **agrega** en `web/src/api/client.ts` y en `web/src/pages/ProjectEditorPage.tsx`; **no se modifica**
    ninguna de sus regiones (`client.ts:27-30`, `:118-119`, `:333-346`; `ProjectEditorPage.tsx:30`, `:612`). El aviso queda como componente reutilizable
    para que la sesión de XMI lo monte en su propio panel del export si quiere, sin que nosotros toquemos el suyo.

**Límites declarados de este diseño:**

1. **Dos pestañas del mismo usuario comparten la concesión** (es por usuario, no por pestaña): la segunda refresca la primera en vez de pelearla.
2. **Una sola instancia** (C4): con dos instancias el latido no cruza y la guarda no ve la concesión de la otra.
3. **El desfase de reloj del cliente** al decidir sobre un `GET`; el servidor sigue siendo la autoridad y el siguiente rechazo trae la verdad.
4. **Si el usuario queda inactivo con la pestaña abierta, la concesión vence y otro puede reclamarla.** Es lo que la decisión C6 quiere —«se libera solo
   cuando el editor desaparece»— y tiene una consecuencia que se declara: estar presente no es estar editando.

### S5 progreso — C19 cerrada (2026-10-03)

**Commit:** `14053ce` — *feat(backend): hold an editing lease per model while someone edits* (4 archivos nuevos, +615). El diseño se congeló **antes** de
escribir la primera línea (`d9ebf54`), para que C19, C20 y C21 no se diseñaran entre tres y para que el revisor tuviera autoridad en el repo.

**Revisión nativa:** linaje `review-43b6f804e24d884e`, tier `medium`, **una** lente (`review-reliability`, orden 0), 4 caminos, 615 líneas,
presupuesto de corrección 200. Cerró **aprobada al primer intento** con autoridad quemada (`gentle-ai.review-acknowledged/v1`): es la primera unidad
desde C14 que no necesita ni una recuperación —después de que el relay respondiera, las siete anteriores quedaron con desviación declarada—. El slot
devolvió pronóstico antes de correr (`pi_host_relay`, 1 corrida, sin mutación) y se autorizó esa corrida exacta con `reviewerRunAcknowledged`.

**Hallazgo informativo — `R3-001`**: lente `reliability`, ubicación `collaboration/InMemoryEditingLeaseService.java:124-126` (las tres líneas de la
guarda: el `leases.get`, el `clock.instant()` y la condición), severidad `WARNING`, disposición `informational`. El proveedor fue explícito: **ninguno
abre corrección, ninguno reabre la revisión y no se ofrece transición de corrección para este candidato**; el sobre **no expuso el texto** del hallazgo
y no se inventa. Lectura propia de esa región, **atribuida a esta sesión y no al revisor**: `requireExportAllowed` es un *check-then-act* — lee la
concesión con `get` y recién después decide, sin que la lectura y la decisión sean una sola operación atómica—, así que un `acquire` o un `release`
concurrente en esa ventana de microsegundos puede permitir una exportación justo cuando otro toma el control, o bloquearla justo después de que lo soltó.
No corrompe nada (la exportación es una lectura y el servidor sigue siendo la autoridad) y no se toca: el proveedor prohíbe la transición de corrección y
arreglarlo pediría un candidato y un linaje nuevos. Queda como trabajo posterior candidato.

**Medido:** `EditingLeaseServiceTest` = **14 pruebas, 0 fallos** · `mvn -o -B -f backend/pom.xml verify` = **300 pruebas, 0 fallos, 0 errores,
0 omitidas**, `BUILD SUCCESS`, **4:27 min** (286 de base + 14 nuevas).

**Verificación independiente (read-only, con sus propias corridas):** los **diez ejes HOLDS**. La comprobación de mutación se hizo sobre copias **fuera
del repo** (un archivo nuevo adentro habría entrado al candidato) y las **cuatro mutaciones tumbaron la suite**, cada una por la aserción que le
toca: renombrar la clave `reclaimable` (4 fallos), tratar como viva la concesión en el instante exacto de vencimiento (2 fallos y 1 error), quitar la
exención del operador (1 fallo) y dejar que `acquire` se apropie de una concesión vencida ajena (1 fallo). Las aserciones que sostienen la firma
congelada son **portantes**, no decorativas.

**Límites declarados de esta unidad:**

1. **La guarda de TTL no positivo no tiene aserción.** El constructor rechaza cero y negativos con `IllegalArgumentException` y está leído en la fuente,
   pero ninguna prueba lo fija; el verificador lo marcó `UNPROVEN` por cobertura, no por sospecha de defecto.
2. **Los argumentos nulos del servicio tampoco están asertados.** Los `Objects.requireNonNull` existen; la prueba sólo afirma el rechazo de nulos en el record.
3. **La prueba de concurrencia no es una prueba de linealidad.** Ocho hilos con usuarios distintos contra un modelo libre dan **exactamente un ganador y
   siete conflictos**, pero una implementación con *check-then-act* podría pasarla si el planificador serializa sus pasos; la atomicidad real la aporta el
   `compute` por clave del `ConcurrentHashMap`, no la prueba.
4. **Una sola instancia y sin eventos** (C4): la concesión no cruza instancias ni publica nada. El catálogo del contrato no tiene un nombre de evento para
   esto y no se le inventó uno.
5. **Un editor cuyo latido sigue fluyendo no puede ser despojado**: el TTL vence sólo si el latido se corta. Declarado en el código y en §S5.
6. `details[0]` se arma con un `LinkedHashMap` y la prueba afirma el **orden** de las claves, además de que el mapa sea de ese tipo; la aserción del tipo
   concreto es más estricta de lo que el contrato pide y se registra como tal.

**Sin desviación de ruteo:** el escritor delegado entregó la unidad completa en **una** vuelta y el padre no escribió fuente en línea.

### S5 progreso — las siete remediaciones cerradas (2026-10-03)

> **Cierre de la deuda de revisión de S3 y S4.** Las siete unidades commiteadas con desviación declarada quedaron **revisadas y aprobadas** por el
> proveedor nativo, cada una en su propio linaje y con la autoridad quemada. **Ninguna exigió corrección; ninguna devolvió un hallazgo bloqueante.**

**El obstáculo, medido, y cómo se resolvió.** El intento original de remediar C12b usó `baseRef` = commit **padre** con `committedOnly: true`, y
funcionó porque en ese momento el HEAD del árbol estaba parado justo sobre la unidad. Ese ya no es el caso: se midió que `committedOnly` con `baseRef`
proyecta siempre **`baseRef..HEAD`** —con `baseRef = 155e93c` la proyección trajo **47 caminos**, todo lo acumulado desde C12b hasta la punta—, así que
aislar una unidad no se consigue con banderas. Se ancló **un árbol de trabajo por unidad** (`git worktree add --detach`), creado con el `git` de Windows
porque el proveedor registra rutas `D:/...` y el de WSL habría registrado `/mnt/...`, que el CLI nativo no resuelve. Las anclas viven dentro del proyecto,
bajo `backend/target/review-anchors/`; `target/` está ignorado, así que **nunca entraron al inventario de no versionados ni a ningún candidato**. Cada
revisión corrió con `workspaceRoot` apuntando a su ancla y `baseRef` = el padre de la unidad. El árbol principal no se tocó y quedó limpio en todo momento.

**Las siete, con lo que devolvió cada una:**

| Unidad | Commit | Linaje | Caminos | Líneas | Presupuesto | Resultado |
| --- | --- | --- | --- | --- | --- | --- |
| C12b | `84e16f1` | `review-287a0db8dee2e619` | 5 | 444 | 200 | aprobada · 2 informativos |
| C14b | `da3c2d6` | `review-fd66d6a0ccb2668d` | 6 | 279 | 140 | aprobada · 1 informativo |
| C15 | `c80b1f1` | `review-cc212394dbf3b6d9` | 12 | 633 | 200 | aprobada · 1 informativo |
| C15b | `805aa57` | `review-238b28a5b6836cbd` | 7 | 293 | 147 | aprobada · **sin hallazgos** |
| C16 | `3c777e5` | `review-8c370fb5a6fd4770` | 3 | 28 | 14 | aprobada · **sin hallazgos** |
| C17 | `d9d4d0a` | `review-c9527669f19152af` | 7 | 562 | 200 | aprobada · 1 informativo |
| C18 | `fc12692` | `review-fb6dde203156fda7` | 9 | 539 | 200 | aprobada · 1 informativo |

Las siete corrieron el mismo protocolo: `inspect` sobre el ancla → `start` con el **sha de commit** (el proveedor emite el del árbol, y el START lo rechaza
con `base-ref-unresolvable`) → `status` → captura con pronóstico previo (`pi_host_relay`, 1 corrida, sin mutación) → la misma captura exacta con
`reviewerRunAcknowledged` → `acknowledge-approved`. Tier `medium`, una lente (`review-reliability`, orden 0) en las siete.

**Los seis hallazgos informativos** —todos `WARNING`, todos `disposition: informational`, ninguno abre corrección ni reabre la revisión— apuntan a
trabajo posterior, no a la unidad revisada:

1. `R3-001` · `api/ProjectEventController.java:41-46` (C12b).
2. `R3-002` · `collaboration/PresencePublisher.java:23-32` (C12b).
3. `R3-001` · `diagram/UmlClassNode.tsx:158` (C14b).
4. `R3-RemoteCursorNotRendered` · `pages/ProjectEditorPage.tsx:290` (C15): señalado en C15 y **pintado por C15b**, la unidad inmediatamente siguiente; el
   propio secuenciado lo explica, y por eso no queda como defecto pendiente.
5. `R3-unbounded-history` · `collaboration/ChatService.java:55-57` (C17).
6. `R3-chat-draft-loss` · `collaboration/ChatPanel.tsx:84` (C18).

El proveedor **no expuso el texto** de ningún hallazgo —sólo `id`, lente, ubicación, severidad y disposición—, así que ninguno se parafrasea: lo que hay
es lo que devolvió el sobre. Los identificadores con nombre propio (`R3-RemoteCursorNotRendered`, `R3-unbounded-history`, `R3-chat-draft-loss`) son del
proveedor y se citan literalmente.

**Lo que las remediaciones dejaron ver, como proceso:** C15 no fue una unidad web. `c80b1f1` metió **12 caminos** repartidos entre el backend de
presencia y cursores y la web del coalescedor —el único commit mixto de la tanda—. Como la revisión mira el candidato tal como está commiteado, quedó
revisado tal cual. La unidad de revisión estuvo bien; la de *commit* no.

**Límites declarados de esta tanda:**

1. **Nada de esto re-escribe historia.** Las siete siguen siendo unidades commiteadas sin revisión *en el momento del commit*; lo que cambió es que ahora
   **tienen** un linaje aprobado y quemado por unidad. La desviación declarada se conserva como registro histórico en las secciones de arriba.
2. **El texto de los hallazgos no se expuso** en ningún sobre: no se parafrasea ni se completa por inferencia.
3. **Las anclas son descartables.** `backend/target/review-anchors/{c12b,c14b,c15,c15b,c16,c17,c18}` se quitan al cerrar esta tanda; no son parte del repo
   y no dejan rastro en el índice.
4. **El relay falló siete veces seguidas y después respondió siete veces seguidas.** Es intermitente y no hay diagnóstico; lo que hay es una tanda cerrada.
5. **Un linaje sin trayectoria de corrección no prueba ausencia de defectos.** Prueba que el proveedor no encontró nada que justifique corregir este
   candidato; los hallazgos informativos quedan arriba como deuda visible.

**Sin desviación de ruteo:** en las remediaciones no había fuente que escribir —el candidato era el árbol ya commiteado—, así que la tarea fue de revisión
y registro, y el padre la ejecutó sin delegar escritura.

### S5 progreso — C20 cerrada (2026-10-03)

**Commit:** `9211b09` — *feat(api): expose the editing lease and block exports while it is held* (9 archivos: 5 nuevos y 4 modificados, +738/−19). El
diseño se congeló **antes** de escribir la primera línea (`c39605a`), y el contrato de rutas, payloads y respuestas no se movió durante la implementación.

**Escritor delegado, con RED observado de verdad:** antes de tocar producción la corrida enfocada dio **45 pruebas, 11 fallos y 3 errores** —los tres
errores pidiendo el `blocker(UUID)` que todavía no existía, y las pruebas de API recibiendo 404 por rutas inexistentes—. Después: **46/46 en 1:57**
(14 de C19 + 3 + 7 + 22). El TTL se acortó por propiedad (`diagrammer.collaboration.editing-lease-ttl=PT2S`) y el vencimiento se observa con
**Awaitility**, nunca con `Thread.sleep`.

**Verificación independiente, en dos rondas.** La primera cerró con **seis ejes HOLDS** y **6 de 7 mutaciones tumbadas**, cada una por su propia
aserción: quitar la exención del operador (`doesNotThrowAnyException`), devolver 200+`outcome` en la descarga (`expected:<409> but was:<200>`), quitar o
reordenar una clave de `toDetails()` (la aserción de claves exactas), invertir `reclaimable`, y mover la guarda de la generación después de `startRun`
(`expected: 0L but was: 1L` — el run que no debía existir). Todas las mutaciones corrieron sobre copias **fuera del repo**, nunca dentro del candidato. La
tabla completa, con la mutación que sobrevive y por qué, está en los límites de abajo.

**Dos defectos reales, uno de código y uno de diseño:**

1. **El `catch` de la guarda de generación prefería una lectura fresca de `blocker()`** antes que el conflicto que él mismo había lanzado. Eso agregaba
   una segunda lectura del reloj y una carrera: un `release`, un reemplazo o un vencimiento entre las dos lecturas cambia `retryAfterSeconds` o
   `reclaimable` respecto de lo que el servicio reportó. Va contra lo congelado («las cinco claves viajan como el mismo registro»). Corregido: el
   conflicto lanzado es la **fuente única** (neto −1 línea), y se agregó la aserción que faltaba —que `details` tenga **exactamente un** elemento—. La
   segunda ronda probó que esa aserción **no es vacua**: mutando el servicio en su copia externa para que la lista llevara dos entradas, el test falló
   con `Expected size: 1 but was: 2`.
2. **El operador NO entra a los cinco endpoints**, aunque la interfaz use `requireEditor`, que sí lo admite. La compuerta global
   `AuthenticationInterceptor` no tiene ninguna ruta `editing-lease` en su lista blanca (`AuthenticationWebConfig:18-22`,
   `AuthenticationInterceptor:89-109`), así que el operador recibe **403 antes del controlador**; y aun allowlisteada, `CurrentUserContext.require()` lo
   rechazaría por no tener identidad de usuario. **La conducta es la correcta** —el operador no es un editor interactivo y no tiene por qué tomar una
   concesión— y **el error estaba en el texto congelado**: el paréntesis «(operador o miembro activo OWNER/EDITOR)» de la sección de arriba es falso
   para estas cinco rutas, y queda corregido acá. Lo que sí importa —la exención para **exportar**— es alcanzable y está probada en la ruta de descarga,
   que es la que el operador sí puede usar.

**Un tercer hallazgo que se registra como conforme, no como defecto:** el verificador marcó `BROKEN` que un proyecto inexistente dé **403** y no 404. El
congelamiento nunca pidió 404: dice «quien no es editor da 403», y para un autenticado sin membresía `requireActiveMember` lanza `FORBIDDEN` antes de
resolver el modelo. Además tiene una propiedad deseable: un no-miembro no puede distinguir «proyecto inexistente» de «proyecto en el que no estoy». La
rama 404 del controlador sigue existiendo para el caso miembro-sin-modelo.

**Revisión nativa:** linaje `review-9d023011615ed35e`, tier `medium`, **una** lente (`review-reliability`, orden 0), 9 caminos, **757 líneas**,
presupuesto de corrección 200. Cerró **aprobada al primer intento** con la autoridad quemada (`gentle-ai.review-acknowledged/v1`); el slot devolvió
pronóstico antes de correr (`pi_host_relay`, 1 corrida, sin mutación), autorizado como esa corrida exacta.

**Hallazgo informativo — `R3-001`:** lente `reliability`, ubicación `collaboration/InMemoryEditingLeaseService.java:139`, severidad `WARNING`,
disposición `informational`. El proveedor fue explícito: **no abre corrección, no reabre la revisión y no se ofrece transición de corrección para este
candidato**. El sobre **no expuso el texto** y no se inventa. Lectura propia de esa línea, **atribuida a esta sesión y no al revisor**: es la
llamada `blocker(modelId)` dentro de `requireExportAllowed`, o sea el mismo *check-then-act* ensanchado que se declara abajo — las coordenadas del revisor
convergen con lo que el verificador y esta sesión ya habían marcado en el mismo lugar.

**Medido en la base consolidada, que es la que se empujaría:** la rama avanzó a `main` con `git merge --ff-only origin/main` (decisión del usuario) y la
verificación quedó intacta porque `main` no toca ninguno de los 9 caminos. `mvn -o -B -f backend/pom.xml verify` sobre **`b488e5e` + C20** = **347
pruebas, 0 fallos, 0 errores, 0 omitidas**, `BUILD SUCCESS`, **5:29 min**. Dentro de esa corrida, `XmiExportApiTest` de la sesión hermana pasó **5/0**: es
la evidencia dependiente de que este refactor de `requireExportAllowed` —firma, condición, 409 y literal idénticos; el cuerpo delega su `details[0]` en
`blocker(modelId)`— **no rompe la guarda que ellos conectaron**.

**Límites declarados de esta unidad:**

1. **El conflicto de `renew`/`release` se arma releyendo con `blocker()`**, lo que ensancha la ventana del *check-then-act* que ya era `R3-001` en C19. El
   revisor apuntó exactamente a esa línea. No corrompe nada —la escritura se rechaza con la verdad— y se declara.
2. **La mutación «capturar toda `ApiException`» sobrevive, y es equivalente.** `requireEditorProject` queda fuera del `try` y la única implementación
   lanza sólo `EXPORT_BLOCKED_EDITING`, así que ningún test puede observar la diferencia. Matarla pediría un doble que simule un servicio que no existe:
   probaría una hipótesis, no el contrato. Se declara con la tabla del verificador.
3. **La vivacidad la calcula el cliente** comparando `expiresAt` con su reloj (desfase posible), y la guarda de la generación **no es atómica** contra la
   concesión: entre la comprobación y el arranque del run cabe una adquisición. Declarado desde el congelamiento.
4. **La saturación del retry en `Integer.MAX_VALUE` no está ejercida**, y el verificador no tenía instantánea byte a byte de `GenerationApiTest` de su
   primera ronda, así que el «una sola línea agregada» ahí se apoya en mtimes y contenido, no en `cmp`.
5. **Una sola instancia y sin eventos ni tabla** (C4, regla 10 de C19). C20 no publica nada nuevo ni persiste nada.
6. **La guarda de la exportación XMI no es de esta unidad:** la conectó la sesión hermana al consolidar (§S6b) y su prueba pasó acá, en la corrida de 347.

**Sin desviación de ruteo:** un escritor por vez con superficies exactas, una ronda de corrección acotada después de verificar, la verificación
independiente en dos rondas, y la suite completa, la revisión nativa y los commits, en el padre.

### S5 progreso — C21 cerrada (2026-10-03)

**Commit:** `d2d5199` — *feat(web): hold the editing lease while editing and let the notice reclaim it* (11 archivos: 5 nuevos y 6 modificados, +658/−6).
El diseño se congeló antes de escribir (`5d3ed83`) y la decisión de alcance —que C21 cubre también **tomar y renovar** la concesión, no sólo mostrarla—
la tomó el usuario, porque ninguna unidad del plan era dueña de ese lado y sin él las guardas de C20 quedaban inertes en producción.

**Lo implementado:** `editingLease.ts` (107 líneas) con el mapeador canónico de los dos portadores —`details[0]` del 409 y `result` del outcome 200, con
validación por tipo de cada campo y `null` para cualquier otro código—, la derivación de los cinco estados y el latido como decisión pura con reloj
inyectado; `EditingLeaseNotice.tsx` (35) puramente presentacional —sin API, sin reloj, botón gobernado sólo por `reclaimable`—; `useEditingLease.ts`
(196) como adaptador fino: `GET` al montar, `acquire` en el primer despacho que modifica el modelo (`dispatchEnvelope`, `ProjectEditorPage.tsx:398`),
latido de 15 s que **sólo** renueva mientras la concesión es nuestra, `release` al desmontar, y un `GET` de refresco programado con el
`retryAfterSeconds` del servidor para que el botón de reclamo se habilite solo. **Coordinado con la sesión de XMI**: el componente quedó reutilizable
(export público del mapeador, props `conflict/onReclaim/busy`) para que su panel de export lo monte cuando el usuario lo ordene; su decisión del
2026-10-03 fue declararlo **pendiente, sin trabajarlo**, y así queda en su §14.10.

**Verificación independiente, en dos rondas, y corrigió una cosa de su propia ronda 1.** La primera cerró **HOLDS en siete ejes** con una tabla de
mutaciones 3 de 5 tumbadas y el veredicto «**no** es seguro commitear tal cual»: el adaptador no tenía guarda de desmontaje en cuatro caminos asíncronos
(sólo el `GET` de montaje estaba guardado), y sobrevivían tres mutaciones. **El defecto se arregló en vez de ablandar la promesa**: `mountedRef` global
del hook, +24 líneas, con el camino feliz intacto. La segunda ronda: **las tres mutaciones murieron** (mapeador aceptando cualquier código, intercambio
semántico de los cuerpos de `acquire`/`renew` —que además detecta un cambio de verbo—, y la validación de `retryAfterSeconds` negativo), la prueba del
panel **no es vacua** (falla con `expected … to contain 'Alex Rivera'` al quitar el montaje), y los **13 escritores de estado** del adaptador quedaron
enumerados uno por uno con su guarda: **ninguno sin guardar**. El verificador además **corrigió su propio registro**: su mutación (e) original era un
no-op semántico —reordenaba definiciones sin cambiar comportamiento—, así que su primera "supervivencia" probaba menos de lo reportado.

**Medido:** lint 0, `npm test` **169 pasan / 4 omiten** (base 158/4, +11 de esta unidad), build con `tsc` y Vite (231 módulos, ~1.9 s) sin avisos. Las
regiones XMI de los dos archivos compartidos **byte-idénticas a HEAD**, verificado dos veces por diff independiente contra `git show HEAD:<path>`.

**Revisión nativa:** linaje `review-0451a261a69d4f06`, tier `medium`, **una** lente (`review-reliability`, orden 0), 11 caminos, **664 líneas**,
presupuesto de corrección 200. **Aprobada al primer intento**, autoridad quemada (`gentle-ai.review-acknowledged/v1`); pronóstico previo
(`pi_host_relay`, 1 corrida, sin mutación) autorizado como esa corrida exacta.

**Dos hallazgos informativos**, ambos WARNING, ambos `informational`, ninguno abre corrección y el sobre no expuso el texto:

1. `R3-001` · `useEditingLease.ts:72-75`. Lectura propia, atribuida a esta sesión y no al revisor: es el `GET` de montaje, que **se dispara igual** aunque
   el usuario no tenga la página a la que responde —el `active` del efecto y el `mountedRef` lo protegen del estado, no del pedido—. Con una concesión
   ajena viva, entrar a la página consulta y no reclama: correcto por diseño, y el costo es un pedido por montaje.
2. `R3-002` · `useEditingLease.ts:140-145`. Es `acquireForEdit`: un `acquire` **por cada acto de edición** del usuario —cada despacho que modifica el
   modelo—, no un latido. El servidor lo trata como refresco (regla 3 de C19: mismo usuario ⇒ refresca), así que no es un error, pero con un usuario
   tecleando rápido significa varios pedidos por segundo contra `POST /editing-lease`. Se declara como patrón deliberado del congelamiento (regla 1:
   «la actividad misma es latido») y no se cambia sin rediseñar esa regla.

**Límites declarados de esta unidad:**

1. **La guarda de desmontaje no tiene prueba que la capture:** la mutación «nunca activar `mountedRef`» sobrevive (169 pasan) porque el proyecto no
   puede renderizar hooks. Se mide y se declara; la protección es de lectura, no de suite.
2. **El comportamiento del adaptador bajo temporizadores reales y orden asíncrono no está probado**, por el mismo límite estructural: toda su evidencia
   es de lectura de código, `tsc` y la suite de los módulos puros.
3. **El release al desmontar es incondicional**: se emite aunque el usuario nunca adquiriera (no-op según la regla 5 de C19, pero es un `DELETE` igual).
4. **El cierre de pestaña no garantiza el release** (`beforeunload` no espera la red); lo cubre el TTL, como declara el congelamiento.
5. **El desfase de reloj del cliente** al decidir sobre el `GET`; el servidor sigue siendo la autoridad.
6. **El cuerpo del pedido de `acquire`/`reclaim` no está asertado** (las pruebas fijan verbo y camino, no el cuerpo); lo cubre el backend con 400
   `INVALID_PAYLOAD` y la validación del mapeador del lado cliente.

**Sin desviación de ruteo:** escritor con superficies exactas, dos rondas de verificación, la corrección del defecto real antes de la revisión nativa, y
la suite, la revisión y los commits en el padre.

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
  **Medido dos veces —por esta sesión y por un verificador independiente— ese conjunto NO contiene `invitation` ni
  `diagram_message`**: hoy lista `model_conflict`, `name_collision`, `ai_proposal`, `detected_element`, `xmi_exchange`,
  `xmi_loss_item` y `audit_log`. Así que sacarlas de ahí no era trabajo pendiente de esta sesión: `invitation` salió al
  migrarse en C8 y `diagram_message` **nunca estuvo** (C16 lo verificó y no tocó la lista). Las que **sí** están, y tienen
  que salir al migrarse, son las dos de la sesión de XMI: para `xmi_exchange` y `xmi_loss_item` sí
  `doesNotContainAnyElementsOf` rechaza la suite si se migran sin sacarlas. Para las dos de XMI el esquema
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

**Pendiente declarado, y ya cerrado:** el endpoint de XMI nace **sin** la guarda de la concesión, porque C19 no está en su base.
Queda como integración pendiente de una línea al consolidar, y **así se declara en el acta**: hasta entonces, la
exportación XMI no está protegida contra una edición en curso.

**Cerrado el 2026-10-03 por la sesión `01a0ffa2`, y verificado desde acá.** La fusión **`f2fd947`** —padres `4f59e50` y
**`38882b4`**, comprobado con `git merge-base --is-ancestor`: mi commit es ancestro de su punta `2b47c66`— trajo la concesión a su
rama sin conflictos, y **`93a22bd`** conectó la guarda:

```java
editingLeaseService.requireExportAllowed(snapshot.id());   // xmi/XmiExportService.java:61
```

**Una línea después** del snapshot que la exportación ya leía (`ModelResource snapshot = projectApiService.getModel(projectId);`,
`:59`), así que la guarda ve exactamente el modelo exportado en vez de resolverlo dos veces — que es la frontera que este
contrato congeló, y con mi firma sin cambios. El commit toca tres archivos (+48/−4): el servicio, el controlador y una prueba
nueva, `backend/src/test/java/com/diagramador/XmiExportApiTest.java` (+39). **Su medición, no la mía:** esa prueba daba
**5 pruebas / 1 falla** —esperaba 409 y obtenía 200— y quedó en **5/0**; backend **332/0/0/0** (era 330), `pytest tools` 54,
web 158 pasan / 4 omitidas, arnés XMI 18/0.

**Lo que esto cambia en el acta, y una condición de fusión.** El acta de C26 **no** puede decir que la exportación XMI no está
protegida; tiene que decir que la conectó la sesión hermana al consolidar. Y agrega una condición: cuando aterrice mi C20, esa
prueba suya pasa a ser **evidencia dependiente** de mi refactor de `requireExportAllowed` —firma, condición de falla, 409 y
literal `EXPORT_BLOCKED_EDITING` idénticos; el cuerpo empieza a delegar su `details[0]` en `blocker(modelId)`—, así que hay que
volver a correrla antes de declarar cerrada una fusión.

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
