# Acceptance harness for backend generation and deployment

This harness proves the increment's visible cut from outside the JVM, without a human hand: it creates a
model, generates two independent backends, compiles each one offline, deploys each one with its own
PostgreSQL, exercises the generated CRUD, and returns the machine to its initial state.

## What it needs

- **A running diagrammer.** Start one with the Increment 1 harness, which prints its base URL:

  ```sh
  python tools/acceptance/acceptance.py --keep-running
  ```

- Python 3, Java 21, Maven, and Docker with the images `postgres:16-alpine` and
  `eclipse-temurin:21-jre-alpine` already available locally. The harness **inspects both images and fails
  before starting anything** if one is missing, because the run never pulls. The runtime base image needs one
  declared download the first time (about 149 MB).
- The Spring Boot 3.4.5 dependency set in the local Maven cache, so `mvn -o` can build the generated project.

## Running it

```sh
python tools/generation-acceptance/acceptance_generation.py --base-url http://127.0.0.1:PORT
```

By default it generates and deploys **two destinations** of the same model. `--destinations N` accepts 1 to 4.
The host ports come from the repository convention: `1900N` for HTTP and `2543N` for PostgreSQL, indexed from
`19001`/`25433`. The harness reserves them before starting and fails clearly if one is taken.

Each destination is generated into `tools/generation-acceptance/.work/<runId>/<targetKey>/`, which holds the
downloaded ZIP, the extracted project, and the `.env` the deployment needs.

### Leaving the destinations up

```sh
python tools/generation-acceptance/acceptance_generation.py --base-url http://127.0.0.1:PORT --keep-running
```

This prints a stop command. The manifest in `.state/<runId>.json` records the Compose projects and the
container count, so the stop path removes only what this run created and then verifies the count returned to
the recorded value:

```sh
python tools/generation-acceptance/acceptance_generation.py --stop <RUN_ID>
```

`--keep-running` exists so the Postman collection in `tools/postman/` can be executed against an already
deployed generated backend.

## What it asserts

| Step | Assertion |
| --- | --- |
| Preflight | Docker, Maven, the local PostgreSQL image, the local runtime base image, and a diagrammer answering `health UP`. |
| Model | Project plus two classes, three attributes, one operation and one association, each command answered `ACCEPTED` with its exact next revision. |
| Destinations | Two targets created over HTTP with their own keys and database names. |
| Generation | One run per destination reaching `SUCCEEDED` with no blocking diagnostic. |
| Download | `HTTP 200`, bytes starting with `PK`, and the archive's SHA-256 **equal to the `artifactZipHash` the run persisted**. |
| Archive | The deployment artifacts are inside: `docker-compose.yml`, `.env.example`, `Dockerfile`, `pom.xml`, `README.md`, the migration. The artifact count and the `artifactSetHash` are printed. |
| Compile | `mvn -o -B package` exits 0 for each generated project, from the local cache, with no network. |
| Compose | `docker compose config` resolves, and `docker compose up -d --build --pull never` starts the destination without pulling. |
| Health | `/actuator/health` answers `HTTP 200` with `status: UP` on the destination's own host port. |
| CRUD | `POST /api/customer` 201; `POST /api/order` 201 **with the parent reference resolved**; re-reading by id keeps it; listing works; a missing parent is **400 and never 500**; an unknown id is **404**. |
| Independence | Different set hashes, different database names, different row identities, different volumes, and both destinations answering at the same time. |
| Teardown | `docker compose down -v` for every Compose project, then the container count back at its initial value, no surviving Compose project, and no surviving volume. |

## Declared limits

- The harness **deploys on this machine**, with Docker Compose. It says nothing about a cloud deployment; the
  AWS/Floci preparation is recorded in `docs/puds/generacion/03-despliegue-propio.md`.
- It **does not build or start the diagrammer**. It needs one, so the diagrammer's own packaging stays where it
  is: the JAR is the operator's, and this separation is what keeps a live JVM from locking the JAR this
  harness would otherwise replace — the failure that on Windows surfaces later as an unrelated `repackage`
  error.
- It proves the cut is repeatable with one command. It does not prove the generated backend is secure:
  `spring-boot-starter-security` is not emitted for version 3.4.5.
