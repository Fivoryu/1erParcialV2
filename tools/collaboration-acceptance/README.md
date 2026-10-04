# Arnés de aceptación de colaboración

Ejecutalo desde la raíz del repositorio:

```sh
python tools/collaboration-acceptance/collaboration_acceptance.py
```

El arnés usa solo la biblioteca estándar de Python. Necesita Docker Compose y levanta la pila real con el archivo
`docker-compose.yml` de la raíz: PostgreSQL y el backend construido desde `backend/Dockerfile`. Los puertos que
**este arnés posee por defecto** son HTTP **18091** y PostgreSQL **25433**. Elegí otros si ya están ocupados. Si ejecutás desde WSL y Docker no está disponible en esa distro, podés usar
el Python de Windows desde la raíz (sin comillas anidadas):

```sh
cmd.exe /c python tools/collaboration-acceptance/collaboration_acceptance.py
```

## Opciones

```text
--db-name NAME             DIAGRAMMER_DB_NAME (default: collaboration_acceptance)
--db-user USER             DIAGRAMMER_DB_USER (default: harness)
--db-password PASSWORD     DIAGRAMMER_DB_PASSWORD (default: harness)
--http-port PORT           DIAGRAMMER_HTTP_HOST_PORT (default: 18091)
--postgres-port PORT       DIAGRAMMER_POSTGRES_HOST_PORT (default: 25433)
--request-timeout SECONDS  límite por petición y por evento SSE inicial (default: 15)
--compose-timeout SECONDS  límite para cada up/down (default: 1200)
```

Por ejemplo, para cambiar los dos puertos publicados:

```sh
python tools/collaboration-acceptance/collaboration_acceptance.py \
  --http-port 18191 --postgres-port 25533
```

El usuario y la clave de base son credenciales descartables de la pila aislada; el valor por defecto es `harness`.
El arnés las pasa al proceso de Compose y no las escribe en archivos.

## Qué levanta y cómo limpia

Compose corre con el proyecto fijo `collaboration-acceptance` y con estos valores iniciales:

```text
DIAGRAMMER_DB_NAME=collaboration_acceptance
DIAGRAMMER_DB_USER=harness
DIAGRAMMER_DB_PASSWORD=harness
DIAGRAMMER_HTTP_HOST_PORT=18091
DIAGRAMMER_POSTGRES_HOST_PORT=25433
```

Antes de ejecutar **cualquier comando Compose**, el arnés cuenta y enumera únicamente los contenedores que llevan
la etiqueta `com.docker.compose.project=collaboration-acceptance`. Si encuentra alguno, informa sus nombres y
estados y se niega a arrancar o borrar nada. Nunca cuenta contenedores globalmente ni opera sobre otros proyectos.

Después de `docker compose ... up -d --build --wait`, exige dos contenedores etiquetados (base de datos y backend).
Al terminar, incluso si una comprobación previa falla, ejecuta `docker compose ... down -v` y vuelve a contar por
la misma etiqueta; el conteo final debe ser cero. Un fallo de `down -v` o un contenedor que quede atrás hace fallar
el arnés. Si el build o el arranque falla, muestra su salida, informa el conteo y limpia lo que alcanzó a crear; no
intenta sustituir el backend ni convertir la corrida en una comprobación parcial.

La base nueva permite correrlo de nuevo sin reutilizar las cuentas, el proyecto o los artefactos de la corrida
anterior. Una segunda corrida completa comprueba además que el teardown anterior dejó en cero los contenedores del
proyecto.

## Recorrido medido

El arnés crea dos clientes HTTP independientes con credenciales y tokens propios. En orden, registra e inicia
sesión A y B; A crea el proyecto e invita a B como `EDITOR`; B acepta; y A prepara un target, obtiene el perfil y
la versión del modelo, y crea una corrida de generación **antes** de tomar el lease. Esa corrida solo provee un
`runId` real para la descarga posterior: su estado final no es una aserción del arnés.

La preparación agrega un `CLASSIFIER` con `POST /projects/{id}/commands`: la generación necesita un modelo
compatible, como muestra el flujo de API medido en `backend/src/test/java/com/diagramador/GenerationApiTest.java:110-179`.
El arnés vuelve a leer la versión actual, toma el perfil y su plantilla de `GET /projects/{id}/generation-profiles`,
crea un target y obtiene un `runId` real por `POST /projects/{id}/generations` (`GenerationController.java:33-53`).

El orden importa. `GenerationApiService.downloadArtifacts` resuelve y valida que la corrida pertenezca al modelo
antes de llamar `requireExportAllowed` (`backend/src/main/java/com/diagramador/api/GenerationApiService.java:180-186`).
Con un identificador inventado, el servidor devolvería `RUN_NOT_FOUND` antes de alcanzar el bloqueo por lease.
Con A sosteniendo el lease, B intenta crear una generación, descargar los artefactos de esa corrida y exportar
XMI. El arnés mide los estados HTTP, los códigos de bloqueo, el nombre de A y las cinco claves del conflicto.

Después A publica un mensaje y B confirma en el historial el cuerpo y el autor. Para presencia, los dos clientes
abren su conexión al canal de eventos y mantienen esas conexiones durante los reportes: `GET /projects/{id}/events`
registra cada sesión y publica su snapshot inicial (`ProjectEventController.java:36-49`). Cada cliente informa
presencia por `POST /projects/{id}/presence`, cuyo controlador devuelve `204 No Content` (`ProjectPresenceController.java:35-59`).
Luego cada uno abre una conexión SSE de una sola lectura y comprueba que el evento `presence.updated` contiene los
dos nombres. El sobre JSON (`schemaVersion`, `type`, `projectId`, `occurredAt`, `payload`) se define en
`ProjectEvent.java:19-31`; la forma `payload.presence` y los campos `displayName` se construyen en
`PresencePublisher.java:22-35`; el bus escribe el nombre del evento y el sobre JSON en
`ProjectEventBus.java:52-60`. El lector consume un solo evento inicial, con límite de tiempo y tamaño, y cierra
esa conexión: **no lee eventos posteriores**.

Por último B lee el lease de A, A lo libera, B lo adquiere y lo libera antes del teardown.

La salida enumera cada medición bajo **What was measured** y conserva los límites bajo **What was NOT measured**.
Cualquier aserción fallida, error de Docker/HTTP o fallo de teardown termina con estado distinto de cero. Una
respuesta sin el cuerpo esperado no se considera una comprobación omitible.

## Lo que no mide

- No abre navegadores; el corte visible de dos navegadores lo cubren las pruebas de integración web.
- ongoing/live SSE delivery is not measured here; only the initial snapshot on connect is read to obtain the presence roster.
- No mide el vencimiento temporal del lease; eso corresponde a pruebas unitarias/de integración con reloj controlado.
- El round-trip con Enterprise Architect sigue sin poder verificarse (**GAP-07**); esa frontera pertenece al arnés XMI.

El arnés mide la API sobre esta pila local; no sustituye las pruebas del navegador, las pruebas con reloj controlado
ni una importación en vivo en EA.
