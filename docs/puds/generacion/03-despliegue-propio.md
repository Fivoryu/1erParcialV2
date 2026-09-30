# Own deployment of a generated backend, and the AWS/Floci preparation

> Increment 2, task T13. This document records a deployment that was executed, the emulator steps that were
> run, and the limits that were not verified. Nothing here is projected from intent.

[Design](01-diseno-generador.md) · [Local execution](02-ejecucion-local.md) · [Task plan](../../../odd/tasks/incremento-2-generacion-y-exportacion.md)

## 1. What this covers

Increment 2 delivers a generated Spring Boot backend as a ZIP. This document covers the step after that: the
ZIP is unpacked on a machine that is not the diagrammer's, built, and started with **its own** PostgreSQL, and
two destinations of the same model run **at the same time without interfering**. It also records the AWS
preparation executed against the local AWS emulator.

Two facts define the shape of the artifact:

- A destination is independent. Its Compose project, its containers, its database and its volume carry its own
  identity, so two destinations never share state even when their generated content is identical.
- The generated project carries **no secret**. Every credential enters through the environment of the machine
  that deploys it.

## 2. The deployment artifacts inside the ZIP

The emitter writes three artifacts for deployment, alongside the project source:

| Artifact | Role |
| --- | --- |
| `docker-compose.yml` | The destination's own stack: the application service, its PostgreSQL, the datasource wiring and both healthchecks. |
| `.env.example` | Every value the destination needs, documented, with no usable secret. The operator copies it to `.env`. |
| `Dockerfile` | Already present since T6: the runtime base image from the classpath baseline, and the packaged jar. |

Design rules that the emitter enforces, each with a test that fails without it:

1. **Identity comes from the target key.** The Compose project is `generated-<slug>`, and the same slug names
   the application service, the database service and the database volume. `<slug>` is the kebab-case form of the
   target key, the same identity the Maven artifact already uses. Two destinations cannot collide on a service
   or a volume name, by construction.
2. **The database image and the runtime base image come from the classpath baseline**
   (`backend/src/main/resources/baseline.properties`), so a baseline change reaches the destination.
3. **Ports are the destination's own configuration, not a guess.** The Compose file reads
   `GENERATED_HTTP_HOST_PORT` and `GENERATED_POSTGRES_HOST_PORT`, defaulting to the first pair of the
   convention in [02-ejecucion-local.md](02-ejecucion-local.md). The pair lives in the destination's `.env`, so
   nothing invents a port from an identifier.
4. **No secret is emitted.** The example file documents the variables; it carries a value that is clearly not
   usable. The emitted `.gitignore` excludes `.env`, so a real value never reaches version control.
5. **The Compose file is valid YAML.** It is parsed through a real YAML parser in the suite, not compared
   against a string. Section 6 explains why that guard exists.

The documented order is deliberate: the `Dockerfile` copies `target/*.jar`, so the jar is built first.

```sh
mvn -o -B package                     # from the local Maven cache, no network
cp .env.example .env                  # then set every value
docker compose up -d --build
curl http://localhost:19001/actuator/health
```

## 3. The executed run

Environment: Windows 11, Docker Desktop 29.8.0, Java 21, Maven 3.9.15, PostgreSQL images already local,
`eclipse-temurin:21-jre-alpine` downloaded once for this run (the declared re-download). The diagrammer was
started by `tools/acceptance/acceptance.py --keep-running` on `postgres:16`, which is Debian-based.

The model was created through the public command API: two classes, three attributes (`String`, `String` with a
default, `BigDecimal`), one operation returning `String`, and one association whose many end is navigable, so
the generated `Order` owns the foreign key to `Customer`.

### 3.1 Generation

| Destination | Target key | Database | `artifactSetHash` | `artifactZipHash` | ZIP bytes |
| --- | --- | --- | --- | --- | --- |
| Reporting service | `reporting-service` | `reporting_db` | `de111ac47713e1ae6c29a6facf4c125adb86501bcd418eb5933215418a42b054` | `35d6a7a9f2ae306ef8a399f8107e50a13f11eaef986bf588215cdcdfb2e5e8cb` | 42086 |
| Billing service | `billing-service` | `billing_db` | `87119262b6eec2abd123cc686762360d67e874cdb41d3eab3be5b3f0ce2405d4` | `93e95e7579117385598843ee97da5fe19291c45c04183dc6cb5d746f483f70be` | 41900 |

Both archives were downloaded with `content-type: application/zip`, started with the `PK` signature, and their
SHA-256 equals the `artifactZipHash` the run persisted. **Two destinations of one model produce two different
sets and two different archives**, which is D14 observed rather than asserted. Each archive holds 24 artifacts
and 37 874 bytes of content.

### 3.2 Deployment, per destination

Each destination was unpacked into its own directory, packaged offline, given its own `.env`, and started with
its own Compose project:

| Check | Reporting service | Billing service |
| --- | --- | --- |
| `mvn -o -B package` | exit 0, `generated-reporting-service-0.1.0.jar` | exit 0, `generated-billing-service-0.1.0.jar` |
| `docker compose config` | exit 0, 1627 bytes rendered | exit 0, 1593 bytes rendered |
| `docker compose up -d --build` | exit 0, both containers healthy | exit 0, both containers healthy |
| `GET /actuator/health` | `{"status":"UP"}` | `{"status":"UP"}` |
| Own database | `reporting_db`, 3 tables, 1 customer, 1 order | `billing_db`, 3 tables, 1 customer, 1 order |
| Own volume | `generated-reporting-service_reporting-service-postgres-data` | `generated-billing-service_billing-service-postgres-data` |

Flyway applied the generated migration against the destination's own database inside the container
(`Migrating schema "public" to version "1 - init"`). The CRUD of the two generated resources answered
`201`/`200`, and the generated foreign key resolution held:

```text
POST /api/customer            -> 201 {"id":"bbd92548-…","email":"borges@example.org","displayName":"Borges"}
POST /api/order               -> 201 {"id":"827d8300-…","total":42.5,"customerId":"bbd92548-…"}
GET  /api/order/827d8300-…    -> 200 {"id":"827d8300-…","total":42.50,"customerId":"bbd92548-…"}
POST /api/order (unknown parent) -> 400 {"code":"INVALID_REFERENCE", …}
GET  /api/order/22222222-…    -> 404 {"code":"NOT_FOUND", …}
```

Both destinations were up **at the same time**, each answering on its own host port, each with its own database
and its own volume, and the two rows in the two databases have different identities. This is the criterion
"two distinct destinations of the same model produce two independent backends, even if their content is equal".

## 4. The AWS/Floci preparation that was executed

**Executed against the local emulator only.** Floci v2.1.0 is running on `127.0.0.1:4566` and is used by another
project: this run **consumed** it and administered nothing beyond the resources it created for itself. No real
AWS credential was used, nothing outside the emulator was created, and no cost was incurred.

| Step | Command shape | Observed |
| --- | --- | --- |
| Identity | `aws --endpoint-url http://127.0.0.1:4566 sts get-caller-identity` | `Account 000000000000`, `arn:aws:iam::000000000000:root` |
| Artifact store, read-only | `aws … s3api list-buckets` | One pre-existing bucket, `generation-artifacts`, belonging to another project. Listed and left untouched. |
| Configuration | `aws … ssm put-parameter /generated-<slug>/deployment` | Written and read back identical: 267 bytes and 261 bytes, **no secret field**. |
| Registry | `aws … ecr create-repository --repository-name generated-<slug>` | Both repositories created; `repositoryUri` `000000000000.dkr.ecr.us-east-1.localhost:4566/generated-<slug>`. |
| Image | `docker tag` the built image to the repository URI | Both images tagged locally under the emulator's URI. |
| Image push | `docker push <uri>:0.1.0` | **Refused.** See the declared limit below. |

Resource names are derived, never invented: the registry repository and the SSM parameter path both use the
emitted Maven artifact name `generated-<slug>`, which the POM already carries.

Against real AWS, every step above differs by a single flag: `--endpoint-url http://127.0.0.1:4566` disappears.
That substitution is the whole distance between this preparation and a real deployment.

### 4.1 Declared limits of the AWS path

- **The image push was not completed.** The emulator answers the ECR registry API, but the Docker registry path
  fails: with the URI host it returns `no such host`, and against `127.0.0.1:4566` it answers
  `400 Bad Request` on the blob upload. The image is built and tagged locally; the push is **documented and not
  claimed as executed**. A real registry is where this step is completed.
- **No ECS, EC2, RDS or IAM resource was provisioned.** This run prepared the configuration and registry path
  only. The managed database and the task definition are outside the increment.
- **No cost is stated.** The emulator has no billable resource, and this document asserts nothing about the cost
  of a real deployment.

## 5. Return to the baseline

Everything this run created was removed and the result verified:

| Resource | Before | After |
| --- | --- | --- |
| Containers | 36 | 36 |
| Volumes | 68 | 68 |
| Emulator parameters | none | none |
| Emulator repositories | none | none |
| Emulator buckets | `generation-artifacts` | `generation-artifacts` (untouched) |

The two destination stacks were removed with `docker compose down -v`, the emulator resources with
`ecr delete-repository` and `ssm delete-parameter`, the diagrammer with the acceptance harness's own `--stop`,
and four orphaned anonymous volumes left by the harness's PostgreSQL containers and by one integration-test
container were identified by creation date and removed after checking that no container referenced them.

## 6. Two defects this run found

Both were found by executing, not by reading, and both are fixed. Each fix carries a test that fails without it,
verified by restoring the defect, observing the failure, and restoring the fix from a hash-checked copy.

**`RUN_NOT_COMPLETE` for a run that had succeeded.** The download answered
`{"code":"RUN_NOT_COMPLETE","message":"… its persisted artifact manifest is incomplete."}` although the run was
`SUCCEEDED` with both hashes persisted. Root cause: the manifest was compared against the persisted rows **by
position**, while the rows come back ordered by the database. A database collation is not the artifact's
canonical path order: under `en_US.utf8`, PostgreSQL returns `docker-compose.yml`, `Dockerfile`,
`.env.example`, `.gitignore`, …, while the emitter's manifest is in Java path order (`.env.example`,
`.gitignore`, `Dockerfile`, …). The integration suite runs on `postgres:16-alpine`, whose collation behaves as
`C`, so the positional comparison agreed there and the archive was unreachable only in the deployment path.
The comparison is now a set equality keyed by path, which is what it always meant.

**A Compose file that was not YAML.** The first generated `docker-compose.yml` wrote
`test: ["CMD-SHELL", "pg_isready -U "$${POSTGRES_USER}" -d "$${POSTGRES_DB}""]`: inner double quotes that were
never escaped, so `docker compose config` refused it with `did not find expected ',' or ']'`. The suite now
parses the emitted Compose file with a real YAML parser and asserts the healthcheck commands, so this class of
defect fails in the suite instead of at a deployment.

## 7. What was not verified

- **The image push to a registry**, for the reason in §4.1.
- **Any deployment outside this machine**: no cloud host, no managed database, no orchestrator.
- **A third party repeating the run.** The evidence is a transcript of the commands and their observed output;
  the harness that would make it repeatable without a human is task T10 and is not part of this document.
- **The generated backend's security posture.** `spring-boot-starter-security` is not emitted for version
  3.4.5, so the generated API has no authentication or authorization and must not be exposed to untrusted
  networks. This is a declared limit inherited from T6, not a result of this run.

## 8. A finding about the primitive vocabulary

The acceptance model of Increment 1 contains an operation returning `boolean`, and generation rejected it with a
blocking diagnostic naming the feature:

```text
code=UNRESOLVABLE_TYPE feature=returnType severity=ERROR blocking=True
message=Primitive type 'boolean' has no mapping in profile option 'primitiveTypeMappings.boolean'.
```

The profile's vocabulary is PascalCase (`Boolean`, `String`, `Integer`, `Long`, `Double`, `BigDecimal`,
`LocalDate`, `LocalDateTime`, `UUID`), and a model may carry any non-blank primitive name. Rejecting an unmapped
one **naming the feature** is the designed behaviour; what this run adds is the concrete case, and the fact that
the Increment 1 demonstration model is not generable for that reason. The T13 run therefore used a model
authored for the increment's declared subset.

## 9. Document numbering

The task plan names this document `02-despliegue-propio.md`. `02-ejecucion-local.md` already holds that number
and is referenced from the repository's Compose file, so this document is `03-despliegue-propio.md` and the
deviation is declared here rather than resolved by renaming a referenced file.
