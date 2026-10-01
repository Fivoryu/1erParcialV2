# Despliegue del backend generado desde el editor

**Origen:** pedido del usuario del 2026-09-30: «si yo hago un diagrama a mano, quiero un botón para poder
desplegar ese backend para cuando la futura app móvil pueda conectarme a ese backend».

**Decisiones que el usuario ya tomó** el mismo día, después de ver el mapa de lo que existía:

1. **Alcance: la misma red Wi-Fi.** El backend corre en esta máquina y el teléfono lo alcanza por la IP de LAN.
   Sin túneles, sin cuentas, sin exposición pública.
2. **Ejecutor: un operador en la máquina.** El diagramador **solo registra la orden**; un proceso en el host
   ejecuta el despliegue y reporta. La API no gana privilegios sobre Docker.

**Estado:** plan escrito, implementación no empezada.

## 1. El corte visible que define esta unidad

> Con un diagrama ya generado, apretar **Desplegar** en el editor, ver el estado pasar de *en cola* a *arriba*,
> leer la **URL que tiene que usar el teléfono**, hacer desde el teléfono un `GET` y un `POST` contra ese
> backend, y después **detener** el despliegue.

## 2. Alcance

- Ordenar un despliegue desde la API y desde el panel del editor, sobre **una corrida exitosa concreta**.
- Persistir el estado del despliegue y hacerlo observable.
- Un **operador** en el host (`tools/deploy-operator/`) que reclama la orden, prepara el proyecto generado,
  compila sin red, levanta el stack con `docker compose up -d --build --pull never`, espera `health` `UP` y
  reporta el resultado, incluida la dirección de LAN.
- Detener: `docker compose down -v`, estado `STOPPED`, y verificación de que no quedó nada.
- La URL para el teléfono, visible en el panel, **sin** mostrar identificadores técnicos.
- Que la base **no** quede publicada fuera de la máquina.

## 3. Fuera de alcance, y por qué

| Fuera | Por qué |
| --- | --- |
| Túneles (cloudflared, ngrok, Tailscale) | El usuario eligió la misma red Wi-Fi. Quedan como camino documentado, no implementado |
| AWS real: ECR, tarea, base gestionada | Es el camino ya documentado en `docs/puds/generacion/03-despliegue-propio.md`, a un flag de distancia |
| Autenticación del backend generado | `spring-boot-starter-security` no está disponible para 3.4.5 en la caché offline. Se declara en el panel |
| La app móvil | Es el Incremento 7. Acá se entrega **su precondición**: un backend levantado y una URL |
| Varias máquinas | El operador y el diagramador asumen la misma máquina. Multi-host es otro diseño |

## 4. Decisiones cerradas en esta unidad

| # | Decisión | Alternativa que descarta |
| --- | --- | --- |
| D1 | El despliegue se ata a **una corrida** (`run_id`), no al destino | Desplegar «lo último» del destino: no sería el artefacto exacto y rompería el determinismo que sostiene todo el incremento |
| D2 | El diagramador **no** habla con Docker | Darle el socket de Docker a la API: quien llegue a la API podría crear contenedores en la máquina |
| D3 | El diagramador **asigna y persiste** el par de puertos al ordenar | Que el operador elija: dos operadores o dos corridas podrían pisarse, y la URL que ve el usuario cambiaría |
| D4 | La base se publica **sólo en loopback**; la app en todas las interfaces | Publicar la base como hoy: hoy el compose emitido publica `0.0.0.0:25433`, o sea la base de datos del backend generado abierta a la red |
| D5 | La URL para el teléfono la arma el panel con **el host que el navegador ya usó** más el puerto persistido | Guardar un host en la base: el host es de quien mira, no del servidor |
| D6 | **Ningún secreto en la fila de despliegue**; el `.env` lo escribe el operador en su directorio de trabajo | Guardar credenciales en la base: viola la regla que el incremento 2 ya sostiene |
| D7 | Un `claim` **atómico** por parte del operador | Lectura y luego escritura: dos operadores desplegarían lo mismo dos veces |
| D8 | Estados `PENDING → RUNNING → UP｜FAILED` y `STOPPING → STOPPED` | Un booleano «desplegado»: no distingue en curso de fallado |

## 5. Restricciones de entorno medidas antes de este plan

| Hecho | Medición |
| --- | --- |
| Puertos del convenio | `19001`/`25433` es el primer par; el arnés de T10 ya los reserva y falla claro si están tomados |
| Perfil de red de la Wi-Fi | **Pública**, con los tres perfiles de firewall activos: alcanzar `192.168.0.11:19001` desde el teléfono **no está garantizado** y hay que medirlo (tarea D2) |
| Dirección de la máquina | `192.168.0.11` por **DHCP**: puede cambiar |
| Herramientas de túnel | `cloudflared`, `ngrok`, `tailscale` y `socat` **no** están instalados. Irrelevante para el alcance elegido |
| Migraciones existentes | `V1`–`V5`. La de esta unidad es **`V6`** |
| `generation_run.artifact_zip_uri` | La columna existe desde T1 y **nunca se escribe**: es el gancho natural para la ubicación del despliegue, pero **no se reusa** acá: una corrida puede desplegarse, detenerse y volver a desplegarse, y eso no es una propiedad inmutable de la corrida |

## 6. Tareas

### D1 — Endurecer el compose emitido: la base sólo en loopback (Etapa A)

- **Objetivo:** que el despliegue en una red compartida no publique la base de datos.
- **Entregable:** el compose emitido publica el puerto de la app en todas las interfaces (el teléfono lo
  necesita) y el de PostgreSQL atado a `127.0.0.1`; el `.env.example` y el README emitidos lo explican.
- **Superficies de edición permitidas:** `backend/src/main/java/com/diagramador/generation/**`,
  `backend/src/test/java/com/diagramador/generation/**`.
- **Evidencia:** la suite pasa; una prueba afirma que la cadena de puertos de PostgreSQL contiene `127.0.0.1` y
  que la de la app **no** (control en las dos direcciones); falsificación reponiendo la versión abierta.
- **Depende de:** nada.

### D2 — Medir el alcance desde la LAN y dejar el procedimiento (Etapa A, evidencia)

- **Objetivo:** saber si el teléfono puede alcanzar la máquina, en vez de suponerlo, y dejar el paso repetible.
- **Entregable:** una sección en `docs/puds/generacion/04-despliegue-desde-el-editor.md` con la medición hecha,
  la regla de firewall necesaria si falta, y **un comando de un solo paso** que el usuario corre desde el
  teléfono para confirmarlo.
- **Superficies de edición permitidas:** `docs/puds/generacion/**`.
- **Evidencia:** la medición con su resultado, y la lista de lo que **no** prueba (una medición desde la misma
  máquina no prueba el camino del teléfono; se declara).
- **Depende de:** nada.

### D3 — Esquema y dominio del despliegue (Etapa B1)

- **Objetivo:** que un despliegue exista como entidad con estado, puerto y resultado.
- **Entregable:** migración `V6__deployment.sql` con la tabla `deployment` y el tipo `deployment_state` en el
  esquema `diagrammer`, con sus índices declarados como la autoridad manda, más la entidad de dominio y su
  repositorio.
- **Superficies de edición permitidas:** `backend/src/main/resources/db/migration/**`,
  `backend/src/main/java/com/diagramador/domain/**`, `backend/src/main/java/com/diagramador/persistence/**`,
  `backend/src/test/**`, y **`docs/puds/datos/02-esquema-fisico.sql`**.
  **Desviación declarada, agregada al ejecutar D3:** la autoridad del esquema es el contrato de la base y el
  guardián es una transcripción suya. Una tabla nueva que no se declare ahí deja la autoridad describiendo un
  esquema que ya no es el real, que es exactamente el defecto que T1b encontró (V3 no copió los índices de la
  autoridad). Así que la tabla se diseña primero en la autoridad y la migración la reproduce.
- **Evidencia:** Flyway llega a `v6`; el guardián de índices cubre la tabla nueva con igualdad exacta en las dos
  direcciones y **se probó que dispara**; una prueba comprueba que no hay ninguna columna que pueda contener un
  secreto ni un path de host.
- **Depende de:** nada.

### D4 — API de despliegues (Etapa B2)

- **Objetivo:** ordenar, consultar, reclamar, reportar y detener un despliegue desde afuera.
- **Entregable:** `GET /projects/{id}/deployments`, `POST /projects/{id}/deployments` (sobre una corrida
  `SUCCEEDED`), `POST /deployments/{id}/claim`, `POST /deployments/{id}/report` y `POST /deployments/{id}/stop`.
- **Superficies de edición permitidas:** `backend/src/main/java/com/diagramador/api/**`,
  `backend/src/test/java/com/diagramador/**`.
- **Evidencia:** ordenar sobre una corrida incompleta se **rechaza nombrando el motivo**; dos `claim` seguidos
  dan un solo ganador; el par de puertos se asigna por orden de destino y no se repite mientras el despliegue
  está activo; un reporte de `FAILED` guarda el motivo **sin** credenciales; la fila no contiene secretos.
- **Depende de:** D3.

### D5 — Operador de despliegue en el host (Etapa C)

- **Objetivo:** que el despliegue lo ejecute un proceso en la máquina, sin darle Docker al diagramador.
- **Entregable:** `tools/deploy-operator/` con su README: reclama órdenes, baja el ZIP por la API, lo
  descomprime en su propio directorio de trabajo, escribe el `.env` con los puertos asignados, compila con
  `mvn -o -B package`, levanta con `docker compose up -d --build --pull never`, espera `health` `UP`, reporta el
  resultado **y la dirección de LAN**, y atiende las órdenes de detener con `docker compose down -v`.
- **Superficies de edición permitidas:** `tools/deploy-operator/**`.
- **Evidencia:** un recorrido completo ordenando desde la API; el reporte incluye la dirección de LAN y el
  `health` observado; **detener** deja el conteo de contenedores y volúmenes en su valor inicial, verificado;
  Ctrl-C a mitad no deja un contenedor huérfano.
- **Depende de:** D4.

### D6 — Botón y estado en el panel del editor (Etapa D)

- **Objetivo:** que el usuario despliegue desde donde dibuja, y que vea la URL del teléfono.
- **Entregable:** en el panel de generación, con una corrida exitosa: botón **Desplegar**, estado del despliegue
  en lenguaje de producto, **la URL para el teléfono** y el botón de detener; más el aviso de que el backend
  generado **no tiene autenticación**.
- **Superficies de edición permitidas:** `web/src/**`.
- **Evidencia:** `npm run build`, `npm run lint` y `npm test` en verde; prueba de que la URL se arma con el host
  que el navegador ya usó y el puerto persistido; prueba de que un despliegue `FAILED` muestra el motivo en
  lenguaje de producto; y la prueba de no-identificadores extendida al estado de despliegue.
- **Depende de:** D4.

### D7 — Evidencia de aceptación end-to-end (Etapa E)

- **Objetivo:** probar el corte de §1 sin manos humanas.
- **Entregable:** extensión de `tools/generation-acceptance/` (o un guion hermano) que ordena el despliegue por
  la API, deja que el operador lo levante, comprueba `health` `UP` **desde la dirección de LAN**, hace el CRUD
  contra ese backend, ordena detener y verifica que no quedó nada.
- **Superficies de edición permitidas:** `tools/deploy-operator/**`, `tools/generation-acceptance/**`.
- **Evidencia:** transcripción paso a paso con el estado del despliegue, la dirección de LAN, el `health`, las
  dos respuestas del CRUD y el teardown verificado con el conteo de vuelta al valor inicial.
- **Depende de:** D5, D6.

### D8 — Cierre de la unidad (Etapa F)

- **Objetivo:** dejar el recorrido repetible por otra persona y el estado real escrito.
- **Entregable:** `docs/puds/generacion/04-despliegue-desde-el-editor.md` completo, `README.md` de la raíz con el
  paso nuevo, §9 de este documento, y las capturas del panel con la URL y el estado.
- **Superficies de edición permitidas:** `docs/puds/generacion/**`, `docs/capturas/**`, `README.md`,
  `odd/tasks/despliegue-desde-el-editor.md`.
- **Evidencia:** el corte de §1 ejecutado de nuevo; `mvn -o -B verify`, `npm run lint`, `npm test` y el arnés en
  verde; y la lista de lo que **no** se verificó.
- **Depende de:** D1, D2, D7.

## 7. Orden y forma de trabajo

`D1 → D2` (endurecer el compose y medir la red), `D3 → D4` (esquema y API), `D5` (operador), `D6` (panel),
`D7` (evidencia end-to-end) y `D8` cierra. Cada tarea cierra con **un commit de unidad de trabajo** con sus
pruebas al lado y sin atribución de IA. **Los writes son de un solo hilo.**

## 8. Definición de terminado

- [x] El corte de §1 funciona de punta a punta y se puede repetir desde cero. **Observado en D7:** dos comandos
      —levantar el diagramador y correr `acceptance_deployment.py`— recorren el corte completo con **29
      aserciones, 0 fallos**, incluido el CRUD contra la dirección de red y el teardown verificado.
- [x] La base de datos del backend generado **no** queda publicada fuera de la máquina, y una prueba falla si se
      vuelve a publicar. Observado en D1 sobre el YAML parseado, con control en las dos direcciones.
- [x] Ordenar un despliegue sobre una corrida que no terminó se rechaza **nombrando el motivo**.
      `RUN_NOT_COMPLETE` con el estado observado en `details`; probado con una corrida todavía `PENDING`.
- [x] Un solo operador reclama una orden. Probado **más fuerte** que con dos `claim` seguidos: **8 en
      simultáneo** dan un ganador, y reemplazar el update condicional por leer-y-escribir hace ganar a los 8.
- [x] El estado del despliegue se ve en el panel y **no** muestra ningún identificador técnico. Observado en D6:
      la prueba recorre los **dos** estados (uno lleva el nombre del proyecto Compose, el otro el mensaje del
      operador) contra el patrón de UUID y contra `deploymentId`, `composeProject`, `operatorId`,
      `httpHostPort`, `postgresHostPort` y los nombres de tabla. **La primera versión de esta prueba pasaba por
      el fixture, no por el código:** al mostrar el proyecto Compose en pantalla seguía en verde, porque el
      caso `FAILED` lo tiene en nulo. Con los dos estados, la falsificación falla como debe.
- [x] La URL del teléfono se muestra y **está armada con el host real** que el usuario usó. `phoneBaseUrl`
      recibe el `hostname` del navegador —no una dirección adivinada por el servidor— y el puerto persistido; y
      si el editor se abrió por loopback, el panel avisa que esa dirección sólo sirve localmente.
- [x] Detener un despliegue deja el conteo de contenedores y volúmenes en su valor inicial, verificado.
      Observado en D5: `down -v`, verificación de que no sobrevive nada, y los conteos de vuelta en la línea
      base. La **interrupción a mitad de vuelo** también deja cero contenedores y cero volúmenes, después del
      defecto de `SIGBREAK` que se encontró y se corrigió.
- [x] Ninguna fila de despliegue ni ningún log contiene un secreto, y una prueba lo comprueba. **La fila está
      cubierta en D4:** un motivo con forma de credencial se rechaza y no se escribe (falsificado: sin la
      comprobación el secreto se guardaba), y la prueba de lista recorre el JSON completo buscando `password`,
      `secret`, `token` y paths de host. **Los logs del operador son de D5.**
- [x] El alcance desde la LAN está **medido y declarado**, incluido lo que la medición no prueba. Ver D2 y §3
del documento: la regla de entrada de Docker cubre el perfil `Public` con cualquier puerto, y los tres orígenes
medidos **no** recorren la Wi-Fi, así que el paso del teléfono queda como el único que cierra la duda.
- [x] El documento de cierre declara lo que **no** se verificó. §7 del documento: que el teléfono llegue —lo único
      que lo prueba es el paso de un comando que corre una persona—, un despliegue fuera de esta máquina, la
      seguridad del backend generado, dos operadores reales en dos máquinas, la muerte del operador sin señal, y
      la app móvil consumiéndolo.

## 9. Registro de progreso

| Tarea | Estado | Evidencia |
| --- | --- | --- |
| D1 — Endurecer el compose emitido | **hecho** | El compose emitido publica el puerto de PostgreSQL atado a `127.0.0.1` y el de la aplicación en todas las interfaces, con el motivo escrito en el propio archivo, en el `.env.example` y en el README emitidos — porque es un archivo que va a leer un tercero. **Aserciones en las dos direcciones:** el puerto de la base es exactamente `127.0.0.1:${GENERATED_POSTGRES_HOST_PORT:-25433}:5432` y el de la app es `${GENERATED_HTTP_HOST_PORT:-19001}:8080` y **no contiene** `127.0.0.1`. La aserción se hace sobre el YAML **parseado**, no sobre un string. **Falsificado:** reponer la base publicada abierta hace fallar el test con `expected ["127.0.0.1:…"] but was ["${…}:5432"]`; el archivo se restauró y se verificó por hash (`8d46cea4…`). `mvn -o -B verify` = **179 pruebas, 0 fallos**, sin red; el ZIP sigue siendo determinista (`artifactSetHash` `47e8f956…`, ZIP de 42.438 bytes, `byteIdentical=true`) |
| D2 — Medir el alcance desde la LAN | **hecho** | Sección §3 de `docs/puds/generacion/04-despliegue-desde-el-editor.md`. **El hecho que decide:** la Wi-Fi está en perfil **`Public`**, y Docker Desktop tiene reglas de entrada **`Allow`, perfil `Public`, puerto `Any`, proceso `com.docker.backend.exe`**, habilitadas. Como el backend generado se publica como **puerto de un contenedor**, el proceso que escucha es ese, así que el camino de LAN **debería funcionar sin agregar nada**. **Hallazgo de diseño que sale de la misma medición:** un **proceso del host** no tiene esa regla, así que levantar el backend como `java -jar` suelto no sería alcanzable desde la Wi-Fi sin una regla manual → el operador tiene que desplegar con **Compose**, que es como ya despliega el arnés (D5). **Medición hecha:** dos escuchas (contenedor publicado `19001`, proceso del host `19099`) alcanzadas desde tres orígenes —la propia máquina por su IP de LAN, WSL y un contenedor—, las tres con `200` en los dos puertos. **Límite declarado, y es el importante:** los tres orígenes recorren interfaces que **no** son la Wi-Fi, así que **ninguno prueba que un teléfono entre**; lo único que prueba el camino del teléfono es el teléfono, y queda el paso de **un comando** para hacerlo (§3.3). También se declaran las dos trampas que ahorran tiempo: la IP es **DHCP**, y **HTTP en claro está bloqueado por defecto en Androide 9+** y por ATS en iOS. La regla de firewall necesaria si falla queda escrita y **no se ejecutó**: es un cambio de seguridad en la máquina del usuario y se pide antes. Sondas y escucha de host eliminadas; **36 contenedores y 68 volúmenes** |
| D3 — Esquema y dominio del despliegue | **hecho** | `V6__deployment.sql` con el tipo `deployment_state` (6 etiquetas) y la tabla `deployment` (14 columnas), **declarados primero en la autoridad del esquema** (`docs/puds/datos/02-esquema-fisico.sql`, sección 8 nueva; la de auditoría pasó a 9) y reproducidos por la migración. Entidad `Deployment` con **la máquina de estados completa en un solo mapa**, `DeploymentState` con `isActive()`/`isTerminal()`, y `DeploymentRepository`. **Un invariante que salió del diseño:** los puertos viven en la máquina y no en el modelo, así que dos destinos de **modelos distintos** podrían tomar el mismo puerto; por eso hay **tres índices únicos parciales** —destino activo, puerto HTTP activo y puerto PostgreSQL activo— que lo impiden en la base y no sólo en el servicio. **Guardián extendido** en sus cinco puntos (columnas exactas, índices exactos, versión `6`, tipo del enum, etiquetas) más **dos guardas nuevas**: (1) **deriva entre Java y SQL** — lee el predicado real de los tres índices parciales con `pg_get_indexdef` y exige que el conjunto de estados sea el mismo que `isActive()`; (2) **ninguna columna de `deployment` puede nombrar una credencial ni un path de host**. **Falsificadas las dos, cada una con lo suyo:** sacar `uq_deployment_active_http_port` de V6 hace fallar el guardián de índices nombrando la tabla; cambiar `isActive()` para que olvide `STOPPING` hace fallar la guarda de deriva nombrando `STOPPING` como elemento inesperado —y esto se hizo con V6 **restaurado**, porque la primera intentona falló por el conteo de índices y no por el conjunto de estados—; y agregar una columna `db_password` a V6 **y** a la lista esperada hace fallar **sólo** la guarda de credenciales. Los tres archivos se restauraron y se verificaron por hash. `mvn -o -B verify` = **181 pruebas, 0 fallos** |
| D4 — API de despliegues | **hecho** | `DeploymentController` con los cinco endpoints: `GET /projects/{id}/deployments`, `POST /projects/{id}/deployments` (201 con `Location`), `POST /deployments/{id}/claim`, `POST /deployments/{id}/report` y `POST /deployments/{id}/stop`. `DeploymentApiService` + `DeploymentResources`, y **17 pruebas** en `DeploymentApiTest`. **El reclamo es atómico y se probó con concurrencia real:** **8 operadores en simultáneo sobre una orden dan exactamente un ganador** y 7 rechazos; el estado vive en el `WHERE` del `UPDATE`, así que todos llegan a la base y sólo uno cambia la fila. **Falsificado:** reemplazar el update condicional por el «leer y después escribir» natural hace que **ganen los 8**, así que la atomicidad es portante y la prueba la detecta. **El motivo de un fallo no puede llevar una credencial, y también se falsificó:** sin la comprobación, un `password=sup3r-s3cret` **se guardaba en la base** (el reporte daba 200 en vez de 400). **Los rechazos nombran el motivo y traen el dato:** `RUN_NOT_COMPLETE` con el estado observado en `details`, `RUN_NOT_IN_PROJECT`, `DEPLOYMENT_ALREADY_ACTIVE` con el id y el estado del que la tiene, `DEPLOYMENT_STATE_CONFLICT` con el estado actual y el pedido, `UNKNOWN_DEPLOYMENT_OUTCOME` nombrando el outcome, `UNSAFE_COMPOSE_PROJECT` (un nombre de proyecto es un nombre, nunca un path), `UNSAFE_FAILURE_MESSAGE`, `UNSAFE_FAILURE_CODE`, `UNSAFE_OPERATOR_ID` y `NO_FREE_PORT_PAIR`. **Asignación de puertos:** el primer par libre de `1900N`/`2543N` **entre todos los despliegues activos de la máquina, no sólo los del modelo**, porque los puertos viven en el host; la prueba libera los despliegues activos entre casos, que es lo que hace determinista la afirmación del primer par. **Decisión declarada:** esta superficie nueva usa el **status HTTP** de `ApiException` con su `code` en vez del sobre `outcome: ACCEPTED|REJECTED` de la API de generación, que es anterior. **Cambio de visibilidad declarado:** `ConnectionConfigRefValidator` pasa de paquete a **público** para que la regla «una credencial no se persiste» viva en un solo lugar en vez de duplicar los regex. `mvn -o -B verify` = **198 pruebas, 0 fallos** |
| D5 — Operador de despliegue en el host | **hecho** | `tools/deploy-operator/` con su README y su `.gitignore`. **15 aserciones, 0 fallos, exit 0** en `docs/capturas/despliegue-editor/01-operador-despliegue.txt`. **Recorrido completo ordenando desde la API:** la orden reserva `19001`/`25433`, el operador reclama, baja el ZIP, compila `mvn -o -B package`, levanta con `docker compose up -d --build --pull never`, espera `health`, reporta **`UP`** con el proyecto Compose (`generated-d5-service-cd3b8`) e **imprime la dirección de LAN** (`http://192.168.0.11:19001/api/...`); una segunda orden del mismo destino se rechaza con **409**; el stack suma **exactamente 2 contenedores y 1 volumen**. **Detener:** `STOPPING` → el operador hace `compose down -v --remove-orphans`, **verifica que no sobrevivan contenedores ni volúmenes** y recién entonces reporta `STOPPED`, con los conteos de vuelta en la línea base. **Interrupción a mitad de vuelo, con el stack ya creado:** cero contenedores, cero volúmenes, conteos de vuelta, y el despliegue reportado `FAILED` con `OPERATOR_INTERRUPTED`. **Defecto real encontrado corriéndolo, que es exactamente lo que el plan pedía probar:** en Windows un Ctrl-Break llega como **`SIGBREAK`**, que Python **no** traduce a `KeyboardInterrupt` y cuya acción por defecto **mata el proceso en el acto**; el primer intento dejó **los dos contenedores y el volumen atrás** y el despliegue clavado en `RUNNING` sin reportarse. Corregido manejando **las dos** señales (`SIGINT` y `SIGBREAK`) y tratándolas igual, con el comentario de por qué. **Decisiones y límites:** la dirección de LAN se **imprime y queda en el estado del operador, no en la base** — la base no guarda direcciones de la máquina, y la URL del panel la arma el navegador con el host que el usuario ya usó (D6); el operador descubre órdenes **escaneando `/projects` y sus despliegues**, sin endpoint nuevo, a costa de N+1 pedidos por vuelta; sólo el operador que tiene la identidad grabada puede detener ese despliegue; si pierde su `.state`, una orden de detener se reporta `UNKNOWN_DEPLOYMENT_DIRECTORY` en vez de quedar colgada; **nunca descarga** y una imagen ausente se reporta como `MISSING_LOCAL_IMAGE` nombrando cuál. **Aprendizajes del arnés de prueba, no del producto:** en Windows no se puede `send_signal(SIGINT)` a un hijo —hace falta `CREATE_NEW_PROCESS_GROUP` + `CTRL_BREAK_EVENT`—, y volví a caer en que `estado == "UP" and composeProject` devuelve **el string** y no `True`, así que la aserción se veía `[OK]` y el resumen la contaba como fallada. **Desviación de superficie declarada:** se agregó `docs/capturas/despliegue-editor/**` para la transcripción |
| D6 — Botón y estado en el panel | **hecho** | El panel de generación ganó el bloque **Deployment**: botón **Deploy backend** (deshabilitado mientras la corrida no esté completa), el estado en lenguaje de producto, **la dirección para el teléfono**, el botón **Stop backend**, el motivo del fallo en lenguaje de producto y el aviso de que **el backend generado no tiene autenticación**. Muestra el despliegue **de la corrida que está en pantalla** (el activo si lo hay, si no el último intento) y **sondea mientras no sea terminal**, porque el operador trabaja en su propio tiempo. **La URL del teléfono la arma el panel con el host que el navegador ya usó** (`window.location.hostname`, leído en el contenedor) más el puerto persistido, con `phoneBaseUrl` como función pura y probada; y cuando el editor se abrió por loopback, **lo dice**: esa dirección sólo funciona acá y hay que abrir el editor desde la dirección de la máquina en la red. El motivo del operador se muestra **sólo si está limpio** (`safeDeploymentDetail`): un mensaje con un path de host o con un identificador se reemplaza por la frase de producto, que sigue siendo verdadera. **Dos defectos propios encontrados por las propias pruebas:** (1) el patrón de paths quedó como `:\|` —un solo backslash— así que la guarda era **vacua para paths de Windows y nunca habría detectado uno**; lo encontró el test que escribí justamente como evidencia de ese criterio; (2) **mi prueba de no-identificadores pasaba por el motivo equivocado**: el fixture `FAILED` tiene el nombre de proyecto Compose en nulo, así que renderizar el proyecto en pantalla **no** la hacía fallar; se extendió a los **dos** estados y recién entonces la falsificación disparó nombrando `generated-reporting-service-t0abcde`. El panel se restauró y se verificó por hash (`07d64dd0…`). `npm run lint` = **0 errores**, `npx vitest run` = **86 pruebas, 0 fallos** (eran 71), `npm run build` en verde |
| D7 — Evidencia de aceptación end-to-end | **hecho** | `tools/generation-acceptance/acceptance_deployment.py`, hermano del arnés de generación y por el **camino del producto**: ordena el despliegue por la API y deja que el operador lo haga, que es lo que hace el botón. **29 aserciones, 0 fallos, exit 0** en `docs/capturas/despliegue-editor/04-aceptacion-despliegue.txt`, con teardown verificado. Comprueba: el estado `UP` con su proyecto Compose; `health` `UP` **desde `127.0.0.1` y desde la dirección de esta máquina en la red** —la que va a usar el teléfono—; el **CRUD generado contra esa dirección**, con la foránea resuelta, el padre inexistente como 400 y el id desconocido como 404; una **segunda orden del mismo destino rechazada**; y detener con el conteo de contenedores y volúmenes **de vuelta en su valor inicial**. **Una guarda nueva en el arnés, por un error que ya cometí tres veces:** `report` ahora **rechaza cualquier aserción que no sea un `bool`**, porque `a == b and c` devuelve `c`, la línea se imprime `[OK]` y el conteo la trata como fallada: una transcripción verde que miente. Encontró dos casos en este mismo arnés. **Defecto real encontrado por la captura, no por las pruebas:** al recargar el editor el despliegue **desaparecía de la pantalla** y no se podía ver ni detener, porque el bloque dependía del run que sólo vive en memoria. La corrección es que el panel busque el despliegue **del destino seleccionado** y no sólo el de la corrida en pantalla. **Y una lección de alcance: mi prueba nueva pasó sin arreglar el defecto**, porque renderiza la **vista** con el despliegue ya resuelto y el error estaba en **la derivación del contenedor**; lo encontró la captura. Los tres hallazgos se corrigieron y el panel se verificó por hash. `npx vitest run` = **87 pruebas, 0 fallos**, `npm run lint` 0 y `npm run build` verde |
| D8 — Cierre de la unidad | **hecho** | `docs/puds/generacion/04-despliegue-desde-el-editor.md` completo: las cuatro piezas (esquema, API, operador, panel), la evidencia ejecutada con sus transcripciones, **lo que la unidad no hace** (§6) y **lo que no se verificó** (§7), más las capturas del panel en `docs/capturas/despliegue-editor/`. `README.md` de la raíz con la tabla de estado, los conteos reales y **el paso a paso del despliegue desde el editor**, incluida la advertencia de abrir el editor por la dirección de red y no por `localhost`, y las dos cosas que hay que saber antes de usarlo: que el backend generado no tiene autenticación y que cortar el operador baja el stack. **Verificación del cierre:** `mvn -o -B verify` = **198 pruebas, 0 fallos**; `npm run lint` = 0 errores; `npx vitest run` = **87 pruebas, 0 fallos**; `npm run build` en verde; y **36 contenedores / 68 volúmenes**, la línea base. **Mejora declarada en el arnés hermano:** se le agregó la misma guarda estricta de `bool` que se validó en el arnés del despliegue —donde encontró dos casos reales—; en el de generación **no puede romper nada**, porque su última corrida verde reportó `66 passed, 0 failed` contando con `value is True`, o sea que **las 66 aserciones ya eran `bool`** |

## 10. Trazabilidad

| Requisito | Dónde se cumple |
| --- | --- |
| RF-10 / RF-11 (generar un backend desde el diagrama) | D5: el backend generado se despliega sin salir del producto |
| RF-12 (dominio no codificado de antemano) | D7: el arnés despliega un modelo creado por él mismo |
| Múltiples backends independientes | D3, D4: el puerto se asigna por destino y no se repite entre despliegues activos |
| CU-21 (consumir el backend desde un cliente) | D1, D2, D5, D6: la **precondición** del caso de uso, no el caso de uso. La app móvil es el Incremento 7 |
| Seguridad y no filtración de secretos | D3, D4, D5, D6: ninguna fila, log o pantalla con credenciales |
| Contrato frontend-agnóstico | D6: el backend generado sigue sin saber que existe el editor |
