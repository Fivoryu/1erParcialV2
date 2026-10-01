# Despliegue del backend generado desde el editor

> Unidad **despliegue-desde-el-editor**, plan en
> [`odd/tasks/despliegue-desde-el-editor.md`](../../../odd/tasks/despliegue-desde-el-editor.md).
> Este documento registra **decisiones, mediciones y límites**. La sección de alcance de red se escribió en la
> tarea D2 y el resto lo completa D8.

[Diseño del generador](01-diseno-generador.md) · [Ejecución local](02-ejecucion-local.md) ·
[Despliegue propio](03-despliegue-propio.md)

## 1. Qué agrega esta unidad

Hasta el Incremento 2, conseguir un backend andando tenía dos pasos manuales: bajar el ZIP y correr, a mano,
`mvn -o -B package` y `docker compose up -d --build`. Esta unidad agrega un **botón Desplegar** en el editor
para que ese recorrido lo haga el producto, y deja el backend levantado con una **URL que un cliente de la misma
red puede usar** — que es la precondición del Incremento 7 (la app móvil).

## 2. Las dos decisiones que fijan el diseño

Tomadas por el usuario el 2026-09-30 después de ver el mapa de lo que ya existía:

| Decisión | Valor | Alternativa que descarta |
| --- | --- | --- |
| **Alcance** | La **misma red Wi-Fi**: el teléfono alcanza la máquina por su IP de LAN | Túneles gestionados o públicos, y AWS real. Exponer una API **sin autenticación** a Internet es lo que se evita |
| **Ejecutor** | Un **operador en la máquina**: el diagramador sólo registra la orden y el operador ejecuta el despliegue | Darle el socket de Docker al diagramador: quien llegue a la API podría crear contenedores en la máquina |

La forma queda así:

```text
  teléfono (misma Wi-Fi)
        │  http://<IP de la máquina>:1900N/api/...
        ▼
  ┌───────────────────────────── la máquina ─────────────────────────────┐
  │                                                                      │
  │  diagramador (API)  ◄── ordena el despliegue y guarda el estado       │
  │        ▲                                                             │
  │        │ reclama / reporta (HTTP)                                    │
  │  operador  ──► mvn -o -B package ──► docker compose up -d --build     │
  │                                          │                           │
  │                        ┌─────────────────┴────────────────┐          │
  │                        │ app publicada en 0.0.0.0:1900N    │          │
  │                        │ PostgreSQL sólo en 127.0.0.1      │          │
  │                        └───────────────────────────────────┘          │
  └──────────────────────────────────────────────────────────────────────┘
```

## 3. Alcance de red: la medición de D2

La decisión de alcance **no se supone**: se midió lo que se podía medir, y se declara lo que no prueba.

### 3.1 El hecho que decide

| Hecho medido | Valor |
| --- | --- |
| Perfil de red de la Wi-Fi | **`Public`** (`Get-NetConnectionProfile`) |
| Perfiles de firewall activos | los tres: `Domain`, `Private`, `Public` |
| Reglas de entrada de Docker Desktop | **`Allow`, perfil `Public`, puerto `Any`, proceso `com.docker.backend.exe`** |

**Por qué esto alcanza:** el backend generado se publica como un **puerto de un contenedor**, y el proceso que
escucha ese puerto en Windows es `com.docker.backend.exe`. Esa regla es explícita, está **habilitada**, cubre
**cualquier puerto** y aplica al perfil **`Public`**, que es exactamente el perfil de esta Wi-Fi. Conclusión: el
camino de LAN para el backend generado **debería funcionar sin agregar nada**.

**Y por qué importa para el diseño:** un **proceso del host** no tiene esa regla. Levantar el backend generado
como un `java -jar` suelto en la máquina no quedaría alcanzable desde la Wi-Fi sin una regla manual, mientras
que el camino de **puerto publicado por contenedor** sí. Eso confirma que el operador tiene que desplegar con
Compose, que es como ya despliega el arnés.

### 3.2 Lo que se midió, y lo que esa medición **no** prueba

Se levantaron dos escuchas —una publicada por contenedor (`19001`) y otra de un proceso del host (`19099`)— y se
las alcanzó desde tres orígenes:

| Origen | `19001` (contenedor publicado) | `19099` (proceso del host) |
| --- | --- | --- |
| La propia máquina, por su IP de LAN | `200` | `200` |
| **WSL** (otro namespace) | `200` | `200` |
| Un contenedor (otro namespace) | `200` | `200` |

**Límite declarado, y es el importante:** los tres orígenes recorren interfaces que **no** son la Wi-Fi, así que
**ninguno de estos resultados prueba que un teléfono entre**. El contenedor y WSL llegan por la `vEthernet` de
WSL, que Windows trata con otro perfil; el firewall de la Wi-Fi `Public` no participa en esas conexiones. Que
`19099` conteste desde un contenedor no significa que un teléfono pueda alcanzar un proceso del host.

**Lo único que prueba el camino del teléfono es el teléfono.** Por eso queda el paso de §3.3, que es un comando.

### 3.3 El paso que cierra la duda, desde el teléfono

Con los dos en la **misma Wi-Fi**, y con el backend ya desplegado:

```text
http://<IP de la máquina>:19001/actuator/health
```

en el navegador del teléfono, o desde una terminal del teléfono:

```sh
curl http://<IP de la máquina>:19001/actuator/health
```

La respuesta esperada es `{"status":"UP"}`. La IP se obtiene en la máquina con `ipconfig`; la interfaz es
**Wi-Fi 2**.

Dos advertencias que ahorran tiempo:

- **La IP es DHCP** y cambia. Si el teléfono deja de llegar, lo primero es volver a mirar la IP; una reserva por
  DHCP o un nombre local lo evitan.
- **HTTP en claro está bloqueado por defecto en Androide 9+** (y por ATS en iOS). Un `curl` desde una terminal
  del teléfono funciona; una app Flutter nativa necesita declarar la excepción de desarrollo para ese host, o
  hablar HTTPS. Es un asunto del Incremento 7, pero conviene saberlo antes de escribir la app.

### 3.4 Si el teléfono no llega

Ninguna regla se agregó todavía, porque la evidencia dice que no hace falta. Si el paso de §3.3 falla, la regla
que falta es esta —**y sólo esta**, acotada al rango de puertos del convenio y al perfil que aplica:

```powershell
New-NetFirewallRule -DisplayName "Backend generado (LAN)" -Direction Inbound -Action Allow `
  -Protocol TCP -LocalPort 19001-19010 -Profile Public
```

Es un cambio de seguridad en la máquina del usuario, así que **no se ejecuta solo**: se pide. Se revierte con
`Remove-NetFirewallRule -DisplayName "Backend generado (LAN)"`.

## 4. Las piezas

### 4.1 El esquema: `V6__deployment.sql`

La tabla `deployment` y el tipo `deployment_state` se diseñaron **primero en la autoridad del esquema**
(`docs/puds/datos/02-esquema-fisico.sql`, sección 8) y la migración los reproduce, porque el guardián es una
transcripción suya: una tabla que sólo viva en la migración deja la autoridad describiendo un esquema que ya no
es el real.

Un despliegue se ata a **una corrida exacta**, nunca a «lo último» de un destino, así que los bytes que quedaron
desplegados son siempre el artefacto congelado.

**El invariante que salió del diseño:** los puertos viven en la máquina y no en el modelo, así que dos destinos de
**modelos distintos** podrían tomar el mismo puerto. Tres **índices únicos parciales** lo impiden en la base y no
sólo en el servicio: un despliegue activo por destino, y un solo dueño activo del puerto HTTP y del de PostgreSQL.

Como el operador escribe en esa tabla, hay una guarda que **falla si una columna puede nombrar una credencial o
un path de host**, y una segunda que **falla si el conjunto de estados activos de Java y el predicado del índice
parcial se separan**: uno vive en Java y el otro en SQL, y un estado agregado a uno solo dejaría de proteger el
puerto en silencio.

### 4.2 La API: ordenar, reclamar, reportar, detener

| Endpoint | Para quién |
| --- | --- |
| `GET /projects/{id}/deployments` | El panel: qué está corriendo |
| `POST /projects/{id}/deployments` | El panel: ordenar el despliegue de una corrida terminada |
| `POST /deployments/{id}/claim` | El operador: tomar la orden |
| `POST /deployments/{id}/report` | El operador: decir qué pasó |
| `POST /deployments/{id}/stop` | El panel: pedir que baje |

**El reclamo es atómico, y eso es portante.** El estado vive en el `WHERE` del `UPDATE`, así que todos los
operadores que leyeron la orden pendiente llegan a la base y **sólo uno cambia la fila**. Está probado con **8
operadores en simultáneo sobre una misma orden**: exactamente un ganador. Reemplazar el update condicional por el
«leer y después escribir» natural hace que **ganen los ocho**.

**El motivo de un fallo no puede llevar una credencial.** Se rechaza en vez de reescribirlo en silencio: el
operador arma su propio mensaje y no necesita incluir ningún valor del entorno. Sin esa comprobación, un
`password=…` **se guardaba en la tabla**.

Todos los rechazos **nombran el motivo y traen el dato**: el estado observado, quién tiene la orden, el estado
actual y el pedido de una transición imposible, y el hecho de que un nombre de proyecto Compose es un nombre y
**nunca un path**.

### 4.3 El operador: el proceso que corre en la máquina

`tools/deploy-operator/` es el proceso que hace realidad la orden. Existe para que el diagramador **no** necesite
acceso a Docker: la API nunca toca Docker y el operador nunca pide un privilegio.

Reclama, baja el ZIP de **esa corrida**, compila sin red, levanta con `docker compose up -d --build --pull never`,
espera `health` `UP`, reporta e imprime la dirección de la red. Al detener hace `compose down -v --remove-orphans`,
**verifica que no sobrevivan contenedores ni volúmenes** y recién entonces reporta.

| Dónde | Qué |
| --- | --- |
| `.work/<id>/` | El ZIP, el proyecto descomprimido y un `.env` generado |
| `.state/<id>.json` | Dónde quedó ese despliegue, para poder detenerlo después |

**La base no guarda ningún path de la máquina ni ninguna dirección.** La contraseña de la base se **genera por
despliegue**, se escribe sólo en ese `.env`, y **no se imprime, no se registra y no se reporta**.

**Cortarlo baja el stack antes de salir.** En Windows un Ctrl-Break llega como `SIGBREAK`, cuya acción por defecto
mataría el proceso sin limpieza; el operador maneja las dos señales y las trata igual. Con eso, interrumpir con el
stack ya creado deja **cero contenedores y cero volúmenes** y el despliegue reportado `FAILED` con
`OPERATOR_INTERRUPTED`.

### 4.4 El panel: el botón

El panel de generación muestra, con una corrida terminada: **Deploy backend**, el estado en lenguaje de producto,
**la dirección para el teléfono**, **Stop backend**, el motivo del fallo en lenguaje de producto, y el aviso de
que el backend generado **no tiene autenticación**.

**La dirección la arma el panel con el host que el navegador ya usó** más el puerto asignado, porque ese host es
una propiedad de quien mira y no del servidor: un servidor que adivinara su propia dirección estaría equivocado
para todo cliente que lo alcance por otro nombre. Si el editor se abrió por loopback, el panel **lo dice**, porque
esa dirección sólo sirve en esa máquina hasta que se abra el editor desde la de red.

El motivo del fallo se muestra **sólo si está limpio**: un mensaje con un path o un identificador se reemplaza por
la frase de producto del código, que sigue siendo verdadera.

El bloque se muestra también **después de recargar el editor**, cuando la corrida ya no está en memoria, buscando
el despliegue **del destino seleccionado**: el backend sigue corriendo y hay que poder verlo y detenerlo sin
generar de nuevo.

## 5. La evidencia ejecutada

| Qué | Resultado | Dónde |
| --- | --- | --- |
| Arnés del despliegue, camino del producto | **29 aserciones, 0 fallos**, teardown verificado | `docs/capturas/despliegue-editor/04-aceptacion-despliegue.txt` |
| Operador: desplegar, detener e interrumpir | **15 aserciones, 0 fallos** | `docs/capturas/despliegue-editor/01-operador-despliegue.txt` |
| Panel con el despliegue corriendo | **Running**, `http://192.168.0.11:19001`, botón de detener | `docs/capturas/despliegue-editor/02-*.png` y `03-*.png` |
| Backend | `mvn -o -B verify` = **198 pruebas, 0 fallos** | — |
| Editor | `npm run lint` 0 errores, `npx vitest run` = **87 pruebas, 0 fallos**, `npm run build` verde | — |
| Docker al terminar | **36 contenedores / 68 volúmenes**, la línea base | — |

El arnés del despliegue comprueba `health` **desde `127.0.0.1` y desde la dirección de red de la máquina**, corre el
CRUD generado **contra esa dirección**, rechaza una segunda orden del mismo destino, y verifica que detener
devuelve los conteos a su valor inicial.

## 6. Lo que esta unidad **no** hace

- **No expone nada a Internet.** Sin túneles. El alcance es la red local.
- **No agrega autenticación al backend generado.** `spring-boot-starter-security` no está disponible para
  3.4.5 en la caché offline. Cualquiera en la misma red que sepa la dirección puede leer y escribir en la base de
  ese backend. Está declarado en el propio proyecto generado y el panel lo avisa.
- **No despliega en AWS.** El camino sigue documentado en [03-despliegue-propio.md](03-despliegue-propio.md).
- **No implementa la app móvil.** Entrega su precondición.
- **No administra el operador**: no es un servicio, no reintenta, no tiene supervisor ni credenciales propias. Es
  un proceso que alguien corre.
- **No evita que un proceso ajeno ocupe el puerto**: el diagramador no puede ver los puertos del host porque vive
  en un contenedor, así que la asignación evita colisiones **entre despliegues que él conoce**. Un puerto tomado
  por otra cosa aparece como un despliegue fallido con su motivo.

## 7. Lo que **no** se verificó

- **Que el teléfono llegue.** El único paso que lo prueba es el de §3.3, y lo corre una persona desde el
  teléfono. Lo que sí se midió es que el backend responde en la dirección de red de la máquina.
- **Un despliegue fuera de esta máquina**: ni nube, ni base gestionada, ni orquestador.
- **Seguridad del backend generado**: no hay capa de autenticación que verificar.
- **Concurrencia de dos operadores en máquinas distintas**: el reclamo atómico está probado con 8 operadores
  simultáneos en la misma base, no con dos operadores reales en dos máquinas.
- **Un despliegue por corrida ante la caída del operador a mitad de camino**: si el proceso muere sin señal (por
  ejemplo, un `kill -9`), no corre su limpieza. Es el caso que la interrupción por señal sí cubre.
- **La app móvil consumiendo el backend**: es el Incremento 7.

## 8. Estado

Las ocho tareas del [plan](../../../odd/tasks/despliegue-desde-el-editor.md) están cerradas. El recorrido completo
son **dos comandos**: levantar el diagramador con `tools/acceptance/acceptance.py --keep-running`, y correr el
operador. Desde el editor, es un botón.
