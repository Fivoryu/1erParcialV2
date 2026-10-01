# Operador de despliegue

Es el proceso que corre **en la máquina** y hace realidad lo que el editor ordenó. El diagramador registra qué
debería estar corriendo; este operador lo reclama, lo despliega y reporta.

**Por qué existe:** para que el diagramador **no** necesite acceso al socket de Docker. La API nunca toca
Docker y el operador nunca pide un privilegio: los dos sólo intercambian estado de despliegue.

## Qué necesita

- Python 3, Java 21, Maven y Docker.
- Las imágenes `postgres:16-alpine` y `eclipse-temurin:21-jre-alpine` **ya presentes localmente**. El operador
  **nunca descarga**: si falta una, lo reporta como `MISSING_LOCAL_IMAGE` nombrando cuál, porque descargarla es
  una decisión de una persona.
- El conjunto de dependencias de Spring Boot 3.4.5 en la caché local de Maven, para poder compilar con `mvn -o`.
- Un diagramador accesible. Levantalo con `python tools/acceptance/acceptance.py --keep-running`, que imprime su
  URL base.

## Token del operador

El backend y el operador deben recibir el mismo valor en `DIAGRAMMER_OPERATOR_TOKEN`. El backend lo lee desde
`application.yml`; si la variable está vacía o no existe, el principal de operador queda deshabilitado y esas
rutas requieren una sesión de usuario. No hay un valor por defecto y el secreto no se guarda en archivos
versionados.

Generá el valor en la terminal desde la que vas a iniciar ambos procesos, y mantenelo fuera del repositorio:

```sh
# Linux, macOS o WSL
export DIAGRAMMER_OPERATOR_TOKEN="$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"

# En PowerShell, en lugar de la línea anterior:
$env:DIAGRAMMER_OPERATOR_TOKEN = python -c "import secrets; print(secrets.token_urlsafe(32))"
```

Iniciá el backend desde ese entorno para que reciba la variable; por ejemplo, el proceso lanzado por
`tools/acceptance/acceptance.py --keep-running` la hereda. El operador también la toma del entorno. Si necesitás
pasarla explícitamente, usá `--token "$DIAGRAMMER_OPERATOR_TOKEN"` (o `--token $env:DIAGRAMMER_OPERATOR_TOKEN`
en PowerShell). No pongas el valor en `application.yml`, argumentos guardados en scripts ni archivos del repo.

## Cómo se corre

```sh
# Un ciclo: toma lo que haya pendiente, lo hace, y sale.
python tools/deploy-operator/deploy_operator.py --api http://127.0.0.1:PORT --once

# Un despliegue concreto, útil para probar o para reintentar.
python tools/deploy-operator/deploy_operator.py --api http://127.0.0.1:PORT --once --deployment <id>

# Modo servicio: sondea cada cinco segundos hasta que lo cortes.
python tools/deploy-operator/deploy_operator.py --api http://127.0.0.1:PORT
```

`--operator` fija la identidad con la que se reclama (por defecto `host-pid`). Esa identidad es la que queda
grabada en el despliegue, y **sólo el operador que la tiene puede detenerlo**: dos operadores en la misma
máquina no se pisan.

## Qué guarda en la máquina, y qué no manda nunca

| Dónde | Qué |
| --- | --- |
| `.work/<id del despliegue>/` | El ZIP bajado, el proyecto descomprimido y un `.env` generado |
| `.state/<id del despliegue>.json` | Dónde quedó ese despliegue, para poder detenerlo después |

**La base de datos guarda el estado, los puertos y el nombre del proyecto Compose. Nunca un path de la
máquina.** Por eso el directorio de trabajo vive acá: es del operador, no del modelo.

La contraseña de la base se **genera por despliegue**, se escribe sólo en ese `.env` y **no se imprime, no se
registra y no se reporta**. Los `.env` y todo este directorio están en el `.gitignore` de al lado.

## El ciclo de un despliegue

1. **Reclama** la orden. El reclamo es atómico en la base: si dos operadores corren a la vez, uno gana y el
   otro se saltea la orden sin romper nada.
2. Verifica las imágenes locales y que el diagramador responda.
3. Baja el ZIP de **esa corrida exacta** y lo descomprime.
4. Lee del destino su nombre de base y arma el `.env` con el par de puertos que el diagramador ya asignó.
5. Compila con `mvn -o -B package`, sin red.
6. Levanta con `docker compose up -d --build --pull never`.
7. Espera `health` `UP` en el puerto propio.
8. **Reporta `UP`** con el nombre del proyecto Compose, e imprime la dirección de LAN:

   ```text
   [INFO] from another device on the same network: http://192.168.0.11:19001/api/...
   ```

Al detener: `docker compose down -v --remove-orphans`, verifica que no sobrevivan contenedores ni volúmenes, y
recién entonces reporta `STOPPED`. Si sobrevive algo, reporta `STOP_INCOMPLETE` en vez de mentir.

## Interrupciones

Cortarlo con Ctrl-C **baja el stack antes de salir** y reporta el despliegue como `FAILED` con el código
`OPERATOR_INTERRUPTED`. En Windows un Ctrl-Break llega como otra señal (`SIGBREAK`), cuya acción por defecto
mataría el proceso sin limpieza, así que el operador maneja **las dos** y las trata igual.

`subprocess` mata a su hijo cuando el padre se interrumpe, así que un `docker compose up` en vuelo no
sobrevive para crear algo después de que la limpieza corrió. La evidencia de esto está en la transcripción:
con el stack ya creado, la interrupción dejó **cero contenedores y cero volúmenes**.

## Límites declarados

- **No es un servicio instalado.** Es un proceso que alguien corre. No hay reintentos automáticos, ni
  supervisor, ni credenciales propias.
- **Un operador por máquina es lo recomendado**, aunque la identidad permita distinguirlos. Dos operadores
  pueden desplegar destinos distintos, pero no se coordinan para nada más.
- **El alcance es la red local.** No abre túneles ni publica nada a Internet.
- **El backend generado no tiene autenticación.** `spring-boot-starter-security` no está disponible para 3.4.5
  en la caché offline, así que cualquiera en la misma red que sepa la URL puede leer y escribir en esa base.
  El panel del editor lo avisa.
- **La base no se publica fuera de la máquina**: el Compose emitido la ata a `127.0.0.1`.
- **Si el operador pierde su `.state`,** una orden de detener de un despliegue que ya no recuerda se reporta
  como `FAILED` con `UNKNOWN_DEPLOYMENT_DIRECTORY` en vez de quedar colgada en silencio.
