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
| C11 | `ProjectEventBus` y `GET /projects/{id}/events` con `SseEmitter`, heartbeat, limpieza de emisores y autorización por rol | Integración: dos clientes reciben el evento publicado; un tercero sin rol no conecta |
| C12 | `PresenceRegistry` efímero: alta/baja por sesión, color determinista por `userId`, elemento tocado | Pruebas unitarias del registro y del color estable |
| C13 | Publicar `operation.applied` desde `CommandService` sin mover la autoridad del servidor | Integración: comando aceptado en una sesión llega a la otra |
| C14 | Web: hook SSE, panel de presencia con color y nombre, y resaltado del elemento tocado | Pruebas de componente del panel y del resaltado |
| C15 | Web: cursores remotos en vivo, con coalescencia para no romper el SLO de RNF-11 | Prueba del coalescedor; medición de frecuencia de envío |

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
