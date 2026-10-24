# Incremento 5 — Versionado tipo Git para diagramas

> **Producto:** diagramador colaborativo de clases UML 2.5. **Rama:** `feat/incremento-5-versionado`,
> apilada sobre `feat/web-ai-memory-02-agent` (36 commits adelante de `main`) siguiendo la práctica del repo.
> **Estado:** ejecución por etapas. Decisiones de producto tomadas por el usuario en esta sesión (L2).

## Specs

- **S1 — Ramas por diagrama con `main` por defecto.** Cada diagrama (hoy: el modelo UML del proyecto) tiene
  ramas con nombre. `main` se crea sola y es la rama base del proyecto y la fuente para generar el backend.
- **S2 — Commits con autor, mensaje y fecha, e historial consultable.** El historial se ve como en GitHub: quién
  cambió qué y cuándo. Un commit guarda el estado completo del diagrama en ese instante.
- **S3 — El autoguardado se conserva y no crea commits.** El diagrama se sigue guardando automáticamente; los
  commits son explícitos y sólo lo manual entra al historial (L2/decisión 1).
- **S4 — Pull requests con conflictos y merge.** Existen PR entre ramas; el controlador ve si hay conflictos y
  puede hacer merge. Los conflictos se calculan por elemento y campo, no por "quién escribió último".
- **S5 — Permisos finos por diagrama y por usuario.** El creador configura `VER`, `CREAR`, `AÑADIR`, `ELIMINAR`
  (más `COMMIT`, `RAMAS`, `MERGE`, `ADMINISTRAR_PERMISOS`), por diagrama y por usuario (L2/decisión 3).
- **S6 — El admin entra con acceso total.** El admin es el principal `operator` global de la plataforma: entra a
  cualquier diagrama sin necesitar permiso y sin que los permisos finos lo limiten (L2/decisión 2).
- **S7 — El creador administra quién edita `main`.** `main` es rama protegida: sólo el creador decide quién
  puede commitear directo en ella.
- **S8 — La IA local es un miembro más del proyecto.** Consulta el repositorio (historial, diffs, autoría) con la
  identidad y los permisos del usuario que pregunta; nunca ve lo que ese usuario no puede ver.
- **S9 — Fuera de alcance de esta entrega: diagramas NoSQL.** Queda declarado como trabajo posterior; el
  snapshot JSONB es la base que lo habilita.

## Tasks

| # | Tarea | Specs | Ruta | Commit |
| --- | --- | --- | --- | --- |
| T1 | Esquema VCS: migración `V23`, entidades y repositorios (`vcs_snapshot`, `vcs_commit`, `vcs_branch`, `vcs_merge_request`, `vcs_permission`) | S1, S2, S4, S5 |  | `68151b0` |
| T2 | Serializador canónico del modelo + materializador con identidades preservadas + prueba de round-trip `state_hash` | S2 |  | `74c34d5` |
| T3 | Commits manuales, rama `main` implícita e historial por rama | S1, S2, S3 |  |  |
| T4 | Ramas: crear, listar y checkout con guardia de cambios sin commitear | S1, S7 |  |  |
| T5 | Merge request: apertura, diff de tres vías, conflictos y merge que materializa el resultado | S4 |  |  |
| T6 | Permisos por diagrama y usuario, con bypass del operador y rama protegida | S5, S6, S7 |  |  |
| T7 | Herramientas de IA sobre el repo: historial, diff y autoría (blame) con los permisos del usuario | S8 |  |  |
| T8 | Superficie web: historial, ramas, PR con conflictos y matriz de permisos | S1, S2, S4, S5 |  | `dc265be` |

## Log

- **L1 (pedido original, verbatim):**

> Mira quier oañadir para el proyecto el versionado como github para cada diagrama. Donde:
> El creador del diagrama puede configurar los permisos tanto para crear, añadir, eliminar, ver. El admin puede entrar a cada diagrama sin necesidad de permisos con el full acceso.
> Existirán diferentes ramas donde estarán los diagramas. Por defecto estará la rama main que será la base para generar el backend. El creador del proyecto se encarga de administrar los permisos si quieren editar la rama del main o no. Va a haber PR que el admin controlará y podrá ver si existen conflictos y podrá hacer merge. Se podrá ver el historial a través de commits como en github. Actualmente el diagrama se guarda automáticamente no romperemos eso, los commits funcionaran para que se pueda ver en el historial y se guardará.
> Además la IA local podrá ir consultando a través del repo y mencionar quien hizo tal cambio o algo así, la IA local es como un miembro más del proyecto.
> Más adelante intentaremos meter la capacidad para que los diagramas soporten no sql.

- **L2 (decisiones de producto de esta sesión):** (1) el autosave escribe sin commit, el commit es siempre
  manual; (2) el admin con acceso total es el **operador global** del sistema; (3) los permisos finos se
  configuran **por diagrama y por usuario**; (4) el alcance elegido es **implementar el incremento completo**,
  sabiendo que excede una sesión: se entrega por unidades verificadas y se declara lo que quede pendiente.
- **L3 (diseño, medido sobre el repo):** `model_version` es un contador con `state_hash` y sin contenido; el
  historial de autoría ya existe en `model_command` (autor, origen, payload). El snapshot se toma del mismo
  modelo canónico que sirve `GET /projects/{id}/model` (`ModelResource`), ordenado de forma determinista, y se
  materializa en las tablas vivas preservando identidades: borrar-y-recrear destruiría el chat y la memoria por
  cascada sobre `diagram_view`.
- **L4 (riesgo declarado):** el materializador es riesgo de pérdida de datos (item 1). Se cierra con la prueba de
  round-trip: serializar → materializar → `ModelStateHasher.hash` idéntico sobre un modelo que usa todas las
  tablas. Un campo que el snapshot no lleve hace fallar esa prueba, no un usuario.

## Contrato HTTP (congelado: backend y web se construyen contra esto)

Todas las rutas cuelgan de `/projects/{projectId}/vcs`, son JSON y exigen `Authorization: Bearer`.
La copia de trabajo es **una por diagrama**: el checkout es del proyecto, no del navegador.

| Método y ruta | Cuerpo | Respuesta |
| --- | --- | --- |
| `GET /status` | — | `{branch, headCommitId, dirty}` |
| `GET /branches` | — | `[{name, isProtected, headCommitId, headMessage, headAuthorDisplayName, headCreatedAt}]` |
| `POST /branches` | `{name, fromBranch?}` | rama (201) |
| `POST /branches/{name}/checkout` | — | `{branch, model}` (`model` es el `ModelResource` existente) |
| `PUT /branches/{name}` | `{isProtected}` | rama |
| `GET /commits?branch=&limit=` | — | `[commit]` (por defecto la rama activa) |
| `GET /commits/{commitId}` | — | `commit` + `changes:[{changeKind, targetType, targetId, targetName}]` |
| `POST /commits` | `{message}` | commit (201) |
| `GET /merge-requests` | — | `[mergeRequest]` |
| `POST /merge-requests` | `{sourceBranch, targetBranch, title}` | mergeRequest (201) |
| `GET /merge-requests/{mrId}` | — | mergeRequest |
| `POST /merge-requests/{mrId}/merge` | — | mergeRequest con `state:"MERGED"` |
| `POST /merge-requests/{mrId}/close` | — | mergeRequest con `state:"CLOSED"` |
| `GET /permissions` | — | `[{userId, username, displayName, actions:[...]}]` |
| `PUT /permissions/{userId}` | `{actions:[...]}` | misma fila |

```jsonc
// commit
{"commitId":"uuid","branchName":"main","kind":"MANUAL","origin":"MANUAL","message":"Agrego herencia",
 "authorUserId":"uuid","authorDisplayName":"Ana","parentCommitId":null,"secondParentCommitId":null,
 "stateHash":"<64 hex>","createdAt":"2026-10-23T12:00:00Z"}

// mergeRequest
{"mergeRequestId":"uuid","title":"Pedidos","state":"OPEN","sourceBranch":"pedidos","targetBranch":"main",
 "sourceCommitId":"uuid","targetCommitId":"uuid","mergeBaseCommitId":"uuid","hasConflicts":true,
 "conflicts":[{"kind":"FIELD_CONFLICT","targetType":"CLASSIFIER","targetId":"uuid","targetName":"Pedido",
   "field":"name","baseValue":"Pedido","sourceValue":"PedidoVenta","targetValue":"PedidoCompra",
   "message":"..."}],
 "openedByDisplayName":"Ana","decidedByDisplayName":null,"createdAt":"..."}
```

`kind` de commit: `INITIAL|MANUAL|MERGE`. `origin`: `MANUAL|AI|VOICE|IMPORT|MERGE`.
`kind` de conflicto: `FIELD_CONFLICT|DELETE_MODIFY|NAME_COLLISION`.
Acciones de permiso: `VIEW|CREATE|ADD|DELETE|COMMIT|BRANCH|MERGE|MANAGE_PERMISSIONS`.

Errores (`{code, message, details, operationId}` como el resto de la API): `FORBIDDEN` (403, incluye rama
protegida), `NOT_FOUND` (404), `INVALID_PAYLOAD` (400), `BRANCH_NAME_TAKEN` (409), `WORKING_COPY_DIRTY` (409,
checkout con cambios sin commitear), `WORKING_COPY_CLEAN` (409, commit sin cambios), `MERGE_CONFLICTS` (409),
`MERGE_REQUEST_EXISTS` (409).

- **L5 (defecto encontrado por la prueba, no por un usuario):** el materializador insertaba con
  `repository.save(...)` sobre entidades con id asignado. Spring Data lo lee como entidad *detached* y hace
  `merge`; la fila no existe y Hibernate responde `StaleObjectStateException`. `entityManager.persist(...)`
  tampoco sirve: `@GeneratedValue(strategy = UUID)` rechaza un id asignado
  (`detached entity passed to persist`). El patrón que este repositorio ya usa para escribir con identidad
  asignada es **SQL nativo con el id explícito** (`XmiImportHandler`, líneas ~556-575 y ~764), y es el que
  adopta `materialize`: `INSERT ... ON CONFLICT (<pk>) DO UPDATE`, con `CAST(:x AS diagrammer.<enum>)` en las
  columnas enumeradas, y `flush()` + `clear()` porque el contexto de persistencia no ve las escrituras nativas.
- **L6 (decisión de seguridad, tomada al chocar con una invariante existente):** `AuthenticationInterceptor`
  declara hoy que *el operador no puede crear proyectos, ejecutar comandos ni leer modelos*: nació como
  **operador de despliegue**, con lista blanca mínima y una prueba que lo fija. S6 pide que el admin sea el
  operador global con acceso total al diagrama, así que la lista blanca se amplía **solo** a
  `/projects/{uuid}/vcs/**` (GET, POST, PUT), validando el uuid como el resto de las rutas. Crear proyectos,
  ejecutar comandos y leer `/projects/{uuid}/model` siguen prohibidos y quedan cubiertos por una prueba en las
  dos direcciones. Consecuencia declarada: el operador pasa a poder **leer el contenido de los diagramas**
  (los snapshots incluyen el modelo completo), cosa que antes no podía. La alternativa descartada era inventar
  un principal nuevo `admin` separado del operador de despliegue: es la arquitectura más limpia, pero agrega
  configuración de credenciales y un segundo eje de autorización que el pedido no pidió.
- **L7 (defecto de coherencia detectado en la revisión del padre, no en un test):** el materializador de T2
  delegaba el hash en `ModelStateHasher`, que **no** incluye `view_style`; al implementar T5 el escritor
  reemplazó esa delegación por una proyección propia que sí lo incluye. Resultado: dos convenciones de hash
  escribiendo la misma columna `model_version.state_hash` — una en el camino de comandos y otra en el de
  checkout/merge. Se unifica en `ModelStateHasher` agregando el grupo `view_style` (clave `(viewId, targetId)`),
  que además cierra un agujero real: un cambio **solo de estilo** era invisible para `dirty` y por lo tanto no
  se podía commitear. Costo declarado: el hash de un modelo existente cambia una vez, solo por esta razón.
- **L8 (vacío semántico encontrado por el escritor de T6):** la tabla `vcs_permission` solo guarda concesiones, así que
  "sin filas" no distingue *nunca configurado* de *configurado vacío*, y mi regla de fallback al rol chocaba con el
  test de revocación. Semántica cerrada sin cambiar esquema: `VIEW` **no** se puede quitar por concesión (un miembro
  activo siempre ve el diagrama; si no debe verlo, es un problema de membresía); para `CREATE`/`ADD`/`DELETE` el
  conjunto de filas es **autoritativo** cuando existe y el rol decide cuando no existe; y `{actions:[]}` significa
  **"volver al rol del proyecto"**, no "denegar todo". Descartado en esta entrega: representar el conjunto
  configurado-vacío con una tabla o un centinela, porque el único verbo donde importaba era `VIEW`.
- **L9 (segundo choque de contrato de T6):** `GET /permissions` no puede a la vez listar "todas las acciones
  efectivas" y devolver exactamente `[VIEW]` para un `EDITOR`. Se cierra con **acciones efectivas**: la matriz
  muestra lo que el usuario puede hoy, porque una casilla vacía tiene que significar "no puede". Regla: `VIEW`
  siempre para miembros; `CREATE`/`ADD`/`DELETE` desde las filas si existen y si no desde el rol; `COMMIT` solo
  para `OWNER`, operador o concesión explícita (la protección se re-chequea al commitear, la lista no puede
  depender de la rama activa); `BRANCH` para `EDITOR`/`OWNER`/operador; `MERGE` y `MANAGE_PERMISSIONS` solo
  `OWNER`/operador. Advertencia declarada en el javadoc de `replace(...)`: guardar una lista efectiva la
  convierte en configuración explícita, y desde ahí manda sobre el rol.
- **L10 (tercer cierre semántico de T6):** `GET /permissions` devuelve **una fila por miembro activo**, con sus
  acciones efectivas (L9). **No** hay fila sintética del operador: `setOperator()` no deja `UserAccount`, así que
  no tiene `userId`, `username` ni `displayName`, e inventarlos sería ficción en una matriz de personas. Los
  privilegios del operador se demuestran ejerciéndolos (`GET`, `PUT`, commit, rama y merge sobre un proyecto
  ajeno), no figurando en la lista. Un usuario común no miembro recibe `403` en esa ruta, para que no funcione
  como directorio de usuarios.
- **L11 (el rol es techo, y por qué no se toca `CommandService`):** `CommandService` tiene su propia guarda de
  rol como última línea de defensa, así que existían **dos** decisiones de autorización para la misma acción.
  Se resuelve sin tocar el dispatcher: `accionesEfectivas = techo(rol) ∩ (concesiones si existen, si no el
  techo)`. Un permiso fino **nunca eleva** por encima del rol: un `VIEWER` sigue siendo de solo lectura, y si el
  creador quiere que edite, cambia el rol. `PUT /permissions/{userId}` con una acción que excede el techo se
  rechaza con `INVALID_PAYLOAD` nombrando la acción y el rol, y no escribe nada — mejor un error explícito que una
  concesión guardada que no habilita nada. Descartado: relajar `CommandService` para honrar `ADD` sobre un
  `VIEWER`; dos capas que deciden distinto son la fábrica de agujeros clásica.
