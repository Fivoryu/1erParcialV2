# Generic domain backend generator — design

> Design-first artifact for the backend generation slice. This document defines the generator contract before implementation in `backend-main-integration`; it does not change backend source code.

[Design index](../03-diseno.md) · [Cross-cutting contracts](../contratos/01-contratos-transversales.md) §8 · [Relational mapping](../datos/01-mapeo-base-de-datos.md) · [Physical schema](../datos/02-esquema-fisico.sql)

## 1. Scope and source boundary

The **user-created domain class diagram** in a project is the generator source. It is the semantic UML model created for the domain supplied by the exam or by a future user. The current diagrammer metadata/architecture class diagram is platform design metadata only; it is **not generated-domain input** and must never be used as a domain fixture or hardcoded generator source.

Generation is generic for new domain diagrams. A single model/version may generate several independent backend projects and databases by selecting different `GenerationTarget` records. This slice defines design and contracts only. Implementation is planned in `backend-main-integration`; no backend source is changed here.

### 1.1 Independencia de IA y frontera operativa

La generación de código permanece desacoplada de los dos runtimes de IA. El runtime backend-local usa Qwen 3.5 0.8B en Docker, con pesos y caché configurables en `D:` y capacidad de desplegarse como contenedor en AWS; Floci es el emulador local de AWS. El runtime móvil-local/en dispositivo de Flutter usa la misma identidad de modelo, pero es otro proceso/entorno y no comparte memoria mutable, caché ni estado con el backend.

El generador consume únicamente el snapshot UML validado del dominio creado por el usuario. No carga pesos, cachés, secretos ni estado de ejecución de IA, y no los incluye en artefactos, volúmenes destinados a exportación, ZIPs, logs, diagnósticos o hashes. La compatibilidad exacta del artefacto, runtime, cuantización y preprocesador de Qwen para visión se verifica como gate técnico separado; no sustituye el requisito de interpretar fotografías.

The source boundary remains compatible with Q9/C3: the exact assistant interaction and the final approximately-three-minute demonstration remain open for C3 evidence. Q9 does not reduce generic-domain generation, authorize prebuilt domain classes, or change the client-independent backend boundary.

## 2. Core concepts and identities

### 2.1 GenerationTarget / BackendTarget

`GenerationTarget` (also called `BackendTarget` at the API/domain boundary) is the stable identity of one independent generated backend/database destination. It is model-scoped and has a project-local `targetKey`. A target can be reused for several model versions, while each generation run freezes the target configuration revision used for that run.

Target identity is distinct from generation strategy:

| Identity | Meaning | Frozen by a run |
| --- | --- | --- |
| `targetId` / `targetKey` | Which independent backend project and external database destination receive the result. | Yes, together with `targetConfigRevision`. |
| `profileId` / `profileKey` / `profileRevision` | Which versioned Spring Boot/PostgreSQL generation options and mapping rules are used. | Yes. |
| `templateId` / `revision` / `contentHash` | Which immutable template contents are used. | Yes. |
| `versionId` / `revision` / `stateHash` | Which validated UML model version is the input. | Yes, through the input snapshot. |

Therefore, `model version V + profile P + template T + target A` and `model version V + profile P + template T + target B` are independent runs. They may produce equal source contents, but they have different ownership, database destinations, artifact sets, verification evidence, and lifecycle state.

### 2.2 Target-safe database configuration

A target stores only non-secret destination metadata and an optional opaque reference to an external configuration/secret manager:

```json
{
  "targetKey": "exam-domain-dev",
  "name": "Exam domain development",
  "database": {
    "engine": "POSTGRESQL",
    "databaseName": "exam_domain",
    "schema": "public",
    "connectionConfigRef": "external-secret-config-ref",
    "configurationRevision": 1
  }
}
```

The following are mandatory rules:

- `connectionConfigRef` is a locator/reference, never a password, token, private key, or complete secret-bearing connection string.
- Credentials are resolved only at execution/verification time by the external configuration mechanism. They are not persisted in the diagrammer database.
- Generated source, migrations, Docker Compose files, OpenAPI, ZIPs, diagnostics, and logs use environment-variable placeholders or documented external secret references. They contain no credential values.
- Logs and diagnostics expose target identity and configuration revision, not connection values or secret references.
- Deterministic hashes exclude credentials, secret values, and secret references. They also exclude timestamps, random run IDs, execution paths, and tool logs.
- Security validation rejects a request or artifact if a credential-like value is detected in profile configuration, target metadata, generated content, ZIP entries, evidence, or logs.

## 3. Input snapshot

Generation first creates an immutable `InputSnapshot` from one validated `ModelVersion`:

```json
{
  "source": "USER_DOMAIN_CLASS_DIAGRAM",
  "modelId": "uuid",
  "versionId": "uuid",
  "revision": 42,
  "modelStateHash": "sha256",
  "snapshotHash": "sha256",
  "validationState": "VALID",
  "elements": []
}
```

The snapshot includes the semantic data needed by the generator: stable IDs, packages/namespaces, classifiers, attributes, operations, parameters, type references, relationships, relationship ends, roles, navigability, multiplicities, visibility, defaults, static/abstract modifiers, supported constraints and supported stereotypes. It excludes the diagrammer metadata/architecture diagram, personal viewport state, and layout-only geometry.

A snapshot is accepted only after final model validation. Open name collisions, dangling references, invalid relationship ends, invalid types, and unsupported required features block a complete generation. Changes made after snapshot creation do not mutate a run; they require a new model version and a new run.

**Implementation prerequisite:** the current backend snapshot contract does not yet provide the full attributes, operations, and multiplicities required by this snapshot. Backend-main-integration must expose those fields before implementation can satisfy this design.

## 4. Versioned profile and template

A generation profile is a versioned, immutable description of the backend baseline and mapping options. V1 proposes a Spring Boot/PostgreSQL profile with:

- Spring Boot and Java baseline versions;
- PostgreSQL and migration baseline;
- naming and package rules;
- JPA relationship and inheritance strategy;
- REST/DTO/error/OpenAPI conventions;
- validation, security, authorization, audit, test, Docker Compose, and deployment options;
- the supported UML subset and behavior for unsupported features.

`profileKey` names a logical profile family. `profileRevision` and `configurationHash` identify its exact non-secret configuration. A changed option creates a new profile revision; it never mutates a profile already used by a run.

A `TemplateVersion` belongs to one exact profile revision. Its `revision` and `contentHash` identify immutable template contents. A run records the exact profile and template identities, not only display names or the latest version.

Profiles and templates must not contain database credentials, tokens, private keys, or secret-bearing connection strings. Target connection configuration is external and target-specific.

## 5. Supported UML subset and diagnostics

The generator supports the project-confirmed subset:

- classes, abstract classes, interfaces, enumerations, and packages;
- attributes, operations, parameters, primitive/domain types, visibility, default values, static and abstract modifiers;
- associations, aggregations, compositions, generalizations, interface realizations, and dependencies;
- roles, navigability, multiplicities, stable identifiers, and supported constraints/stereotypes.

This is a supported subset, not a claim of complete UML 2.5 coverage. Every element or feature outside the subset is handled explicitly. A diagnostic has this minimum shape:

```json
{
  "code": "UNSUPPORTED_UML_FEATURE",
  "severity": "ERROR",
  "elementId": "uuid",
  "feature": "<feature-name>",
  "message": "The selected profile does not map this feature.",
  "blocking": true
}
```

Blocking diagnostics prevent a run from becoming a complete backend. A profile may allow a diagnostic-only preview, but no profile may silently drop a domain feature or claim support that was not verified. Warnings remain attached to the snapshot, run, ZIP provenance, and verification report.

## 6. Intermediate representation and mapping rules

The generator normalizes the snapshot into `DomainModelIR`. The IR retains source IDs and contains:

- ordered package and type declarations;
- field/type declarations with nullability and collection intent;
- operation signatures and parameters;
- relationship descriptors with ownership, multiplicity bounds, navigability, and lifecycle intent;
- deterministic names, source-to-artifact mappings, profile decisions, and diagnostics.

The baseline mapping rules are:

| UML input | Generated representation |
| --- | --- |
| Class / abstract class | JPA entity, table, repository, service, DTO/mapping and controller support as required by the profile. |
| Interface | Java interface/contract; implementation is generated only where the selected profile defines it. |
| Enumeration | Java enum and its persistence/API representation. |
| Package | Deterministic Java package and source path. |
| Attribute | Entity field, column, validation metadata, and migration column. |
| Primitive/domain type | Profile type mapping; unresolved types are blocking diagnostics. |
| Multiplicity | Nullability and single-value/collection association shape; invalid or ambiguous bounds are diagnostics. |
| Association/aggregation/composition | JPA relation and migration constraints according to ownership, navigability, multiplicity and profile rules; supported lifecycle semantics are preserved. |
| Generalization/interface realization | Profile-defined JPA inheritance/interface mapping. |
| Operation/parameter | Supported domain/service method and corresponding DTO/API contract. |

Names, ordering, package paths, SQL, migration identifiers, and generated file paths are canonicalized. The IR is the only input to templates; templates do not read the live model or diagram layout.

## 7. API and lifecycle

The frontend-agnostic resource contract is:

| Operation | Resource | Required behavior |
| --- | --- | --- |
| Create/list target | `POST/GET /projects/{projectId}/generation-targets` | Manage target identity and target-safe external database metadata. |
| Select profile/template | `POST /projects/{projectId}/generation-profiles/{profileId}/select` | Accept the exact `templateId`; resolve immutable profile/template versions and check compatibility with the snapshot and target. CU-18. |
| Start generation | `POST /projects/{projectId}/generations` | Accept `modelId`, `versionId`, `targetId`, `profileId`, `templateId`, and an idempotency key. Freeze input and return `PENDING` or blocking diagnostics. CU-19. |
| Read generation | `GET /projects/{projectId}/generations/{runId}` | Return frozen identities, state, diagnostics, hashes, ZIP metadata, and verification summary. |
| Download artifact ZIP | `GET /projects/{projectId}/generations/{runId}/artifacts` | Return a complete ZIP only for a complete terminal generation result; previews/partial output are labeled and never presented as ready. |
| Verify generation | `POST /projects/{projectId}/generations/{runId}/verification` | Verify the immutable artifact set and target-safe configuration without silently repairing generated source. CU-20. |

The generation state machine is:

```text
PENDING → RUNNING → SUCCEEDED
                  └→ FAILED
```

Validation, profile compatibility, target consistency, and unsupported-feature analysis occur before code generation. `SUCCEEDED` means that generation completed; it does not mean that the backend passed CU-20 verification. A failed or incomplete run remains auditable and cannot be reported as a ready backend. Idempotent retries return the existing run for the same target/configuration revision/version/profile/template combination.

## 8. Artifact ZIP and deterministic hashing

A complete artifact ZIP contains the four required layers—domain/entities, repositories/persistence, services/application, and controllers/API—and supporting artifacts required by RF-10/RF-11:

- DTOs and mappers;
- validation and consistent error handling;
- PostgreSQL configuration and migrations;
- OpenAPI contract;
- unit and integration tests;
- Docker Compose and reproducible execution configuration;
- authentication, authorization, and audit support;
- deployment artifacts;
- machine-readable provenance with snapshot, target, profile/template identities, subset version, diagnostics, artifact hashes, and verification status.

The ZIP contains no credentials, model weights, caches, or runtime state. A generation may expose preview or diagnostic files only when clearly labeled as incomplete; those files do not satisfy the complete artifact contract.

Determinism is defined over a frozen snapshot, target identity/configuration revision, exact profile revision, and exact template version:

1. Normalize paths and sort them lexicographically.
2. Encode artifact contents as canonical UTF-8.
3. Compute SHA-256 `contentHash` per artifact.
4. Compute `artifactSetHash` from the ordered path/content-hash manifest.
5. Build a ZIP with deterministic entry order and normalized metadata, then compute `artifactZipHash`.

`inputSnapshotHash`, `artifactSetHash`, and `artifactZipHash` are recorded on `generation_run`; each artifact records its `contentHash`. Secret values and secret references never participate in these hashes. Same frozen inputs produce equivalent artifacts; different targets remain independent even if their artifact contents are equal. AI model state, weights, caches, secrets, and execution state are never generation inputs or hash inputs.

## 9. Verification contract

CU-20 verifies the run-specific immutable artifact set and records one or more `VerificationResult` records. Each result identifies the `runId` and contains at least:

- `checkName` and `state` (`PASSED`, `FAILED`, or `NOT_RUN`);
- evidence and optional evidence hash, without credentials;
- descriptive `toolName` (the contract does not mandate a tool);
- start, finish, and creation timestamps where available.

The verification set covers structure and four layers, JPA entities/relations, repositories, services and supported operations, REST/DTO/OpenAPI, validation/errors, security, authorization, audit, migrations/PostgreSQL, compilation, startup, health, contract behavior, tests, Docker Compose, reproducibility, artifact hashes, and target-safe configuration. A missing or unexplained check prevents a complete verification claim.

Generation and verification are distinct lifecycle stages:

- CU-18 selects the target and exact versioned profile/template.
- CU-19 freezes the snapshot, builds the IR, maps supported UML, produces artifacts, records diagnostics and hashes, and returns a generation result.
- CU-20 verifies that result and records evidence; verification does not silently repair or regenerate it.

## 10. Traceability and acceptance

| Decision | Traceability |
| --- | --- |
| User-created domain diagram is the generic source; metadata diagram is excluded. | RF-10, RF-12; CU-19, CU-23; P02/P07 boundary. |
| Multiple independent targets from one model/version. | RF-10, RF-11; CU-18–CU-20; `generation_target` and target-aware run uniqueness. |
| Complete four-layer backend and supporting artifacts. | RF-10, RF-11; CU-19, CU-20. |
| Validation before effects and explicit unsupported diagnostics. | RNF-01, RNF-06; CU-08, CU-19, CU-20. |
| Deterministic artifacts and hashes. | RNF-03; CU-19, CU-20. |
| External target configuration with no secret leakage. | RNF-05; CU-20. |
| Client-independent generated contract. | RNF-07; CU-20, CU-21. |
| Demonstration and efficiency remain C3/Q9 decisions. | RNF-09; Q9; CU-23. |

Acceptance for this design slice is documentation-level: the source boundary, target identity, versioned profile/template, snapshot, UML subset, IR, mappings, diagnostics, API/lifecycle, ZIP/hash rules, security constraints, schema mapping, and snapshot prerequisite are explicit and mutually consistent. Runtime generation and backend verification are intentionally not claimed in this phase.
