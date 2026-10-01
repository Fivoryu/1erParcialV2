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
  `backend/src/test/**`.
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

- [ ] El corte de §1 funciona de punta a punta y se puede repetir desde cero.
- [x] La base de datos del backend generado **no** queda publicada fuera de la máquina, y una prueba falla si se
      vuelve a publicar. Observado en D1 sobre el YAML parseado, con control en las dos direcciones.
- [ ] Ordenar un despliegue sobre una corrida que no terminó se rechaza **nombrando el motivo**.
- [ ] Un solo operador reclama una orden, probado con dos `claim` seguidos.
- [ ] El estado del despliegue se ve en el panel y **no** muestra ningún identificador técnico.
- [ ] La URL del teléfono se muestra y **está armada con el host real** que el usuario usó.
- [ ] Detener un despliegue deja el conteo de contenedores y volúmenes en su valor inicial, verificado.
- [ ] Ninguna fila de despliegue ni ningún log contiene un secreto, y una prueba lo comprueba.
- [ ] El alcance desde la LAN está **medido y declarado**, incluido lo que la medición no prueba.
- [ ] El documento de cierre declara lo que **no** se verificó.

## 9. Registro de progreso

| Tarea | Estado | Evidencia |
| --- | --- | --- |
| D1 — Endurecer el compose emitido | **hecho** | El compose emitido publica el puerto de PostgreSQL atado a `127.0.0.1` y el de la aplicación en todas las interfaces, con el motivo escrito en el propio archivo, en el `.env.example` y en el README emitidos — porque es un archivo que va a leer un tercero. **Aserciones en las dos direcciones:** el puerto de la base es exactamente `127.0.0.1:${GENERATED_POSTGRES_HOST_PORT:-25433}:5432` y el de la app es `${GENERATED_HTTP_HOST_PORT:-19001}:8080` y **no contiene** `127.0.0.1`. La aserción se hace sobre el YAML **parseado**, no sobre un string. **Falsificado:** reponer la base publicada abierta hace fallar el test con `expected ["127.0.0.1:…"] but was ["${…}:5432"]`; el archivo se restauró y se verificó por hash (`8d46cea4…`). `mvn -o -B verify` = **179 pruebas, 0 fallos**, sin red; el ZIP sigue siendo determinista (`artifactSetHash` `47e8f956…`, ZIP de 42.438 bytes, `byteIdentical=true`) |
| D2 — Medir el alcance desde la LAN | pendiente | — |
| D3 — Esquema y dominio del despliegue | pendiente | — |
| D4 — API de despliegues | pendiente | — |
| D5 — Operador de despliegue en el host | pendiente | — |
| D6 — Botón y estado en el panel | pendiente | — |
| D7 — Evidencia de aceptación end-to-end | pendiente | — |
| D8 — Cierre de la unidad | pendiente | — |

## 10. Trazabilidad

| Requisito | Dónde se cumple |
| --- | --- |
| RF-10 / RF-11 (generar un backend desde el diagrama) | D5: el backend generado se despliega sin salir del producto |
| RF-12 (dominio no codificado de antemano) | D7: el arnés despliega un modelo creado por él mismo |
| Múltiples backends independientes | D3, D4: el puerto se asigna por destino y no se repite entre despliegues activos |
| CU-21 (consumir el backend desde un cliente) | D1, D2, D5, D6: la **precondición** del caso de uso, no el caso de uso. La app móvil es el Incremento 7 |
| Seguridad y no filtración de secretos | D3, D4, D5, D6: ninguna fila, log o pantalla con credenciales |
| Contrato frontend-agnóstico | D6: el backend generado sigue sin saber que existe el editor |
