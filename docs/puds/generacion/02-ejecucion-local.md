# Local backend execution with Docker Compose

The backend and its PostgreSQL database can be started from the repository root with Docker Compose. The database uses a named volume so modeled projects survive container restarts. The volume is managed by the operator; it is not ephemeral container storage.

## Environment

Provide these required variables in the process environment when invoking Compose; no `.env` file is needed or created:

| Variable | Purpose |
| --- | --- |
| `DIAGRAMMER_DB_NAME` | PostgreSQL database name used by the database and backend. |
| `DIAGRAMMER_DB_USER` | PostgreSQL username passed to the backend datasource. |
| `DIAGRAMMER_DB_PASSWORD` | PostgreSQL password passed to the backend datasource. |
| `DIAGRAMMER_POSTGRES_HOST_PORT` | Optional host port for PostgreSQL; defaults to `25432`. |
| `DIAGRAMMER_HTTP_HOST_PORT` | Optional host port for the backend; defaults to `18090`. |

For example, set the required values inline for a local run (replace the example password with a local-only value):

```sh
DIAGRAMMER_DB_NAME=diagrammer \
DIAGRAMMER_DB_USER=diagrammer \
DIAGRAMMER_DB_PASSWORD='set-a-local-only-password' \
docker compose up -d --build
```

The backend reads `DIAGRAMADOR_DB_USERNAME` and `DIAGRAMADOR_DB_PASSWORD` from its environment. `application.yml` retains `diagramador` as the default for each, so existing local tests remain self-contained. Compose maps the required `DIAGRAMMER_DB_USER` and `DIAGRAMMER_DB_PASSWORD` values into those datasource variables.

The published health endpoint uses the default HTTP port:

```text
http://localhost:18090/actuator/health
```

## Port convention

The diagrammer owns `18090` for HTTP and `25432` for PostgreSQL. These defaults deliberately avoid `8080` and
`5432`, which are commonly already taken on a development machine.

A generated backend is an independent destination and takes its own pair, one per destination, from the
`1900N` and `2543N` ranges: `19001`/`25433` for the first, `19002`/`25434` for the second, and so on. The
overlap with the diagrammer's range would not break anything, but keeping them apart means the diagrammer and
two generated backends can be up at the same time while developing, which is exactly what the acceptance run
for backend generation needs to do. The chosen pair is part of the destination's configuration, so it is
recorded per target rather than guessed.

## Baseline consistency

`backend/src/main/resources/baseline.properties` is the single baseline-values file. It is on the runtime classpath so generation emission and consistency checks read the same Java, Spring Boot, PostgreSQL image, and runtime base-image values. `backend/src/test/java/com/diagramador/BaselineConsistencyTest.java` compares it with the Maven POM, Dockerfile, and this repository's Compose file. The POM's real `<java.version>` and Spring Boot parent `<version>` are authoritative Maven declarations. Compose defines its PostgreSQL tag once as an image anchor and references it for both the database image and Docker build argument.

## Emitted backend project

The generation emitter returns a lexically sorted POSIX path-to-content map. Generated files use UTF-8-compatible text, LF line endings, and a final newline. The target key determines the generated package and artifact names; model names are not used as a substitute for target identity. The emitted Maven project contains JPA entities, repositories, transaction-scoped services that map list results to DTOs before returning, REST controllers, environment-driven datasource configuration, an explicit `V1__init.sql`, and a Dockerfile based on the runtime image in the classpath baseline.

The generated project intentionally omits `spring-boot-starter-security`: version 3.4.5 is unavailable in the configured local Maven cache. No authentication or authorization layer is emitted, so the generated backend must not be exposed to untrusted networks until a reviewed security dependency and policy are supplied. The emitter's repeatability and generated-project build are covered by the backend verification suite.
