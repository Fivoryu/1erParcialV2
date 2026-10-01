# Arnés de aceptación de la generación y el despliegue

Este arnés prueba el corte visible del incremento **desde afuera del JVM y sin manos humanas**: crea un modelo,
genera dos backends independientes, compila cada uno sin red, despliega cada uno con su propia PostgreSQL,
ejercita el CRUD generado y devuelve la máquina a su estado inicial.

## El arnés hermano: el despliegue por el camino del producto

`acceptance_deployment.py` prueba el **mismo corte por el camino que usa el usuario**, no alrededor de él. El
arnés de arriba despliega un proyecto generado directamente con Compose para probar el artefacto; éste **ordena
un despliegue por la API** y deja que `tools/deploy-operator/` lo haga, que es lo que hace el botón del editor.

```sh
python tools/generation-acceptance/acceptance_deployment.py --base-url http://127.0.0.1:PORT
```

Crea un proyecto con dos clases relacionadas y una corrida terminada, ordena el despliegue de **esa corrida**,
corre el operador una vez, y después comprueba:

- que el estado quede `UP` con su proyecto Compose;
- que `health` responda `UP` **desde `127.0.0.1` y desde la dirección de esta máquina en la red**, porque la de
  la red es la que va a usar el teléfono;
- el **CRUD generado contra esa dirección**, incluida la foránea resuelta, el padre inexistente como 400 y el id
  desconocido como 404;
- que una **segunda orden del mismo destino se rechace**;
- que ordenar detener, correr el operador otra vez y esperar deje el conteo de **contenedores y volúmenes de
  vuelta en su valor inicial**.

`--keep-running` deja el despliegue arriba e imprime su comando `--stop`, que sirve para capturar el panel o
para probar la URL desde el teléfono.

**Lo que este arnés no hace:** llegar al backend desde **otro** dispositivo. Eso es el paso de un comando del
teléfono, declarado en `docs/puds/generacion/04-despliegue-desde-el-editor.md` §3.

## Qué necesita

- **Un diagramador corriendo.** Levantá uno con el arnés del Incremento 1, que imprime su URL base:

  ```sh
  python tools/acceptance/acceptance.py --keep-running
  ```

- Python 3, Java 21, Maven y Docker con las imágenes `postgres:16-alpine` y `eclipse-temurin:21-jre-alpine` **ya
  disponibles localmente**. El arnés **inspecciona las dos imágenes y falla antes de arrancar nada** si falta
  alguna, porque la corrida nunca descarga. La imagen base del runtime necesita una descarga declarada la
  primera vez (unos 149 MB).
- El conjunto de dependencias de Spring Boot 3.4.5 en la caché local de Maven, para que `mvn -o` pueda compilar
  el proyecto generado.

## Cómo se corre

```sh
python tools/generation-acceptance/acceptance_generation.py --base-url http://127.0.0.1:PORT
```

Por defecto genera y despliega **dos destinos** del mismo modelo. `--destinations N` acepta de 1 a 4.

Los puertos del host salen del convenio del repositorio: `1900N` para HTTP y `2543N` para PostgreSQL, indexados
desde `19001`/`25433`. El arnés los reserva antes de empezar y falla claramente si alguno está tomado.

Cada destino se genera en `tools/generation-acceptance/.work/<runId>/<targetKey>/`, que guarda el ZIP bajado, el
proyecto descomprimido y el `.env` que el despliegue necesita.

### Dejar los destinos levantados

```sh
python tools/generation-acceptance/acceptance_generation.py --base-url http://127.0.0.1:PORT --keep-running
```

Eso imprime un comando de parada. El manifiesto en `.state/<runId>.json` graba los proyectos Compose y el conteo
de contenedores, así que la parada elimina **sólo** lo que creó esta corrida y después verifica que el conteo
volvió al valor registrado:

```sh
python tools/generation-acceptance/acceptance_generation.py --stop <RUN_ID>
```

`--keep-running` existe para que la colección de Postman de `tools/postman/` pueda ejecutarse contra un backend
generado ya desplegado.

## Qué afirma

| Paso | Afirmación |
| --- | --- |
| Preflight | Docker, Maven, la imagen local de PostgreSQL, la imagen base del runtime y un diagramador que responde `health UP` |
| Modelo | Proyecto y dos clases, tres atributos, una operación y una asociación, cada comando `ACCEPTED` con su revisión siguiente exacta |
| Destinos | Dos destinos creados por HTTP, con sus claves y sus nombres de base |
| Generación | Una corrida por destino que llega a `SUCCEEDED` sin diagnóstico bloqueante |
| Descarga | `HTTP 200`, bytes que empiezan con `PK`, y el SHA-256 del archivo **igual al `artifactZipHash` que la corrida persistió** |
| Archivo | Los artefactos de despliegue están adentro: `docker-compose.yml`, `.env.example`, `Dockerfile`, `pom.xml`, `README.md`, la migración. Se imprimen el conteo de artefactos y el `artifactSetHash` |
| Compilación | `mvn -o -B package` sale 0 para cada proyecto generado, desde la caché local, sin red |
| Compose | `docker compose config` resuelve, y `docker compose up -d --build --pull never` levanta el destino sin descargar |
| Salud | `/actuator/health` responde `HTTP 200` con `status: UP` en el puerto propio del destino |
| CRUD | `POST /api/customer` 201; `POST /api/order` 201 **con la foránea resuelta**; releer por id la conserva; el listado responde; un padre inexistente es **400 y nunca 500**; un id desconocido es **404** |
| Independencia | `artifactSetHash` distintos, nombres de base distintos, filas con identidad distinta, volúmenes distintos, y los dos contestando **a la vez** |
| Teardown | `docker compose down -v` para cada proyecto Compose, y después el conteo de contenedores de vuelta en su valor inicial, ningún proyecto Compose sobreviviente y ningún volumen sobreviviente |

## Límites declarados

- El arnés **despliega en esta máquina**, con Docker Compose. No dice nada de un despliegue en la nube; la
  preparación AWS/Floci está registrada en `docs/puds/generacion/03-despliegue-propio.md`.
- **No compila ni levanta el diagramador.** Necesita uno, así que el empaquetado del diagramador queda donde
  está: el JAR es del operador. Esa separación es también lo que evita que un JVM vivo bloquee el JAR que este
  arnés reemplazaría — el fallo que en Windows aparece después como un error de `repackage` sin relación con el
  código.
- **No mide el alcance desde otro dispositivo.** Levanta y verifica en `127.0.0.1`. Que un teléfono de la misma
  red llegue es otra medición, y está declarada en
  `docs/puds/generacion/04-despliegue-desde-el-editor.md` §3.
- Prueba que el corte se repite con un comando. **No** prueba que el backend generado sea seguro:
  `spring-boot-starter-security` no se emite para la versión 3.4.5.
