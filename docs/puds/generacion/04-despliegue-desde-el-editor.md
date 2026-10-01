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

## 4. Lo que esta unidad **no** hace

- **No expone nada a Internet.** Sin túneles. El alcance es la red local.
- **No agrega autenticación al backend generado.** `spring-boot-starter-security` no está disponible para
  3.4.5 en la caché offline. Cualquiera en la misma red que sepa la URL puede leer y escribir en la base de ese
  backend. Está declarado en el propio proyecto generado y el panel lo avisa.
- **No despliega en AWS.** El camino sigue documentado en [03-despliegue-propio.md](03-despliegue-propio.md).
- **No implementa la app móvil.** Entrega su precondición.

## 5. Estado de las tareas

Ver §9 del [plan](../../../odd/tasks/despliegue-desde-el-editor.md). D1 (endurecer el Compose) y D2 (esta
medición) están cerradas.
