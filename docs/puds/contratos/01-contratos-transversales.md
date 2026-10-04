# Contratos transversales — D3

> Documento canónico de los contratos del diagramador colaborativo de clases UML 2.5. Define qué reciben y producen los componentes. La arquitectura de IA local está confirmada en dos runtimes independientes con Qwen 3.5 0.8B; la compatibilidad exacta de artefacto, runtime, cuantización y preprocesador permanece como gate técnico de verificación.

[Índice del PUDS](../../esqueleto-proceso-desarrollo-parcial-2.md) · [Captura de requisitos](../01-captura-de-requisitos.md) · [Análisis](../02-analisis.md) · [Diseño](../03-diseno.md) · [Mapeo de datos](../datos/01-mapeo-base-de-datos.md) · [Diseño del generador](../generacion/01-diseno-generador.md)

## Estado

**D3 — borrador creado.** Los contratos son Propuestos/Confirmados según su origen; ninguno cierra la línea base completa de Diseño. Q9 permanece abierta hasta el Ciclo 3 y Q3 sigue siendo una prueba de round-trip, no una pregunta de producto.

## Ruta rápida

| Tema | Sección |
| --- | --- |
| Cómo usar este documento | [1. Alcance y principios](#1-alcance-y-principios) |
| Sobre de operación | [2. Contrato de comandos](#2-contrato-de-comandos) |
| Recursos y errores | [3. Contrato API](#3-contrato-api) |
| Eventos, offline y revocación | [4. Tiempo real y offline](#4-tiempo-real-y-offline) |
| Merge y panel de conflictos | [5. Conflictos](#5-conflictos) |
| Importar/exportar con EA | [6. Contrato XMI](#6-contrato-xmi) |
| Propuestas revisables | [7. Contrato IA](#7-contrato-ia) |
| Generación determinista | [8. Contrato de generación](#8-contrato-de-generación) |
| Lo que falta decidir | [9. Decisiones abiertas y gaps](#9-decisiones-abiertas-y-gaps) |
| Verificación prevista | [10. Trazabilidad y verificación D5](#10-trazabilidad-y-verificación-d5) |

## 1. Alcance y principios

### 1.1 Entrada

- Requisitos y criterios de aceptación: [`01-captura-de-requisitos.md`](../01-captura-de-requisitos.md).
- Invariantes, responsabilidades y grupos conceptuales: [`02-analisis.md`](../02-analisis.md).
- Alcance del corte D3: [`03-diseno.md`](../03-diseno.md) §2.3.
- Modelo lógico de datos: [`01-mapeo-base-de-datos.md`](../datos/01-mapeo-base-de-datos.md) y esquema [02-esquema-fisico.sql](../datos/02-esquema-fisico.sql).

### 1.2 Principios que los contratos deben respetar

1. **Un solo modelo semántico.** Todos los adaptadores (manual, IA, voz, fotografía, XMI, offline) terminan en sobres de operación validados sobre el mismo modelo.
2. **Validez antes de efectos.** Ningún componente publica, exporta, genera ni confirma desde un modelo inválido o con colisiones de nombres abiertas.
3. **Autoridad del servidor.** La confirmación durable y la autorización ocurren en el servidor; una autorización local, cursor o presencia no publica cambios.
4. **Trazabilidad observable.** Toda operación conserva `operationId`, autor, objetivos estables, revisión base/causal y precondiciones (invariante del Análisis).
5. **Semántica separada de vista.** Los eventos de layout compartido no alteran el modelo UML; el viewport personal no abandona el cliente.
6. **Tecnología abierta donde corresponde.** Este documento define comportamiento y forma de los datos, no servidores concretos, librerías, protocolos de identidad, CRDT ni nombres de recursos cloud. Sí conserva la decisión de arquitectura: runtime backend-local Qwen en Docker, desplegable como contenedor en AWS, y runtime móvil-local/en dispositivo para Flutter, separados y sin memoria mutable compartida.
7. **Los clientes consumen un contrato frontend-agnóstico.** El backend generado y el editor pueden ser consumidos por cualquier cliente (RNF-07).
8. **Eficiencia y recorrido de la demostración siguen en Q9.** Los puntos de observación pueden diseñarse; los criterios globales de eficiencia se deciden en el Ciclo 3.
9. **La generación es independiente de IA.** El generador trabaja sobre una versión UML validada y no depende de ninguno de los dos runtimes; pesos, cachés, secretos y estado de runtime no entran en propuestas, imágenes o volúmenes destinados a exportación, ZIPs, logs ni hashes.

## 2. Contrato de comandos

### 2.1 Sobre de operación (envelope)

Toda modificación del modelo, el layout compartido o el proyecto viaja en un sobre con esta forma canónica:

```json
{
  "operationId": "op_01J1H...",
  "type": "classifier.create",
  "author": { "userId": "uuid-del-actor", "externalSubject": "opaco" },
  "projectId": "uuid",
  "modelId": "uuid",
  "baseRevision": 42,
  "targets": [
    { "scope": "classifier", "id": "uuid-identificador-estable" }
  ],
  "preconditions": [
    { "kind": "model_valid", "expect": true },
    { "kind": "permission", "expect": "EDITOR" },
    { "kind": "no_name_collision", "scope": { "namespaceId": "uuid", "name": "Usuario" }, "expect": false }
  ],
  "payload": { "name": "Usuario", "kind": "CLASS", "isAbstract": false },
  "origin": "MANUAL",
  "causalParentOperationId": "op_01J1G...",
  "idempotencyKey": "cliente-abc-123"
}
```

| Campo | Regla |
| --- | --- |
| `operationId` | UUID opaco generado por el autor; se conserva en historial y auditoría. |
| `type` | Catálogo de tipos: `project.*`, `member.*`, `classifier.*`, `attribute.*`, `operation.*`, `parameter.*`, `relationship.*`, `geometry.*`, `proposal.*`, `xmi.*`, `generation.*`, `undo`, `redo`, `resolve`. |
| `author` | Identidad referencial (`externalSubject` opaco). El esquema no fija proveedor de autenticación. |
| `targets` | Lista de objetivos estables (`scope` + UUID). Los IDs no cambian al recuperar versiones. |
| `baseRevision` | Revisión causal sobre la que se calculó el sobre; el servidor valida que el estado actual la contenga. |
| `preconditions` | Predicados verificables en el servidor antes de aplicar. Desconocidos → rechazo con diagnóstico. |
| `payload` | Campos específicos del tipo (JSONB en persistencia). Sin payload para `undo`/`redo` (referencia `targets`). |
| `origin` | Manual, IA, voz, fotografía, XMI, undo/redo, resolución, administración, offline, importación. |
| `causalParentOperationId` | Operación anterior en la historia causal local/remota (orden causal, no reloj físico). |
| `idempotencyKey` | Permite reenvío seguro de la cola offline sin duplicar efectos. |

### 2.2 Respuesta del servidor

```json
{
  "operationId": "op_01J1H...",
  "status": "ACCEPTED",
  "resultRevision": 43,
  "diagnostics": [],
  "authorizationClassification": "AUTHORIZED_ONLINE"
}
```

| `status` | Uso |
| --- | --- |
| `ACCEPTED` | Se aplicó; devuelve `resultRevision`. |
| `REJECTED` | No cumple precondiciones o autorización; `diagnostics` explica el motivo; no crea revisión. |
| `CONFLICT` | No se puede aplicar en el estado actual; alternativa conservada en el panel lateral. |
| `DEFERRED` | Aceptado para análisis posterior (casos ambiguos de revocación offline). |

### 2.3 Undo/redo

- `undo` y `redo` son sobres nuevos de tipo `undo`/`redo`, del mismo autor, que referencian la operación original en `targets`.
- El servidor aplica el comando inverso solo si las precondiciones del estado actual se cumplen.
- Nunca se reescribe historia: cada corrección crea revisión nueva.

### 2.4 Reglas para cascadas delete-wins

- Un `type` de eliminación incluye en `preconditions` el conteo de elementos afectados y exige confirmación explícita cuando hay cascada.
- La confirmación crea un `cascadeGroup` con todos los tombstone en una transacción atómica.
- Restaurar el conjunto completo es un comando `undo` válido contra ese grupo; no se admite restauración parcial.

## 3. Contrato API

### 3.1 Forma de los recursos

Recursos principales (frontend-agnósticos, sin fijar framework):

| Área | Recursos sugeridos |
| --- | --- |
| Proyectos | `POST/GET /projects`, `GET/PATCH /projects/{id}`, `GET /projects/{id}/members`, `POST /projects/{id}/invitations` |
| Modelo | `GET /projects/{id}/model`, `POST /projects/{id}/commands`, `GET /projects/{id}/versions`, `GET /projects/{id}/versions/{rev}` |
| Colaboración | `GET /projects/{id}/events`, `GET /projects/{id}/sessions`, `GET /projects/{id}/conflicts` |
| Propuestas | `POST /projects/{id}/proposals`, `GET /projects/{id}/proposals/{pid}`, `POST /projects/{id}/proposals/{pid}/apply` |
| XMI | `POST /projects/{id}/xmi/import`, `POST /projects/{id}/xmi/export`, `GET /projects/{id}/xmi/exchanges` |
| Generación | `POST /projects/{id}/generations`, `GET /projects/{id}/generations/{gid}`, `GET /projects/{id}/generations/{gid}/artifacts` |

- Los identificadores visibles (`name`) nunca son la clave de recursos; se usan UUID/IDs estables.
- Las operaciones de escritura aceptan el [sobre de operación](#2-contrato-de-comandos) o crean uno internamente desde la intención del usuario; la forma canónica de intercambio es siempre el sobre.

### 3.2 Convención de errores

```json
{
  "code": "PRECONDITION_FAILED",
  "message": "El modelo contiene colisiones de nombres abiertas en el namespace 'X'.",
  "details": [
    { "scope": "name_collision", "id": "uuid", "reason": "name.duplicate" }
  ],
  "operationId": "op_01J1H..."
}
```

| Código | Significado |
| --- | --- |
| `UNKNOWN_OPERATION` | Tipo de comando no soportado. |
| `INVALID_PAYLOAD` | Payload no válido para el tipo. |
| `PRECONDITION_FAILED` | Precondición incumplida (incluye permisos, validez, colisiones). |
| `REVISION_MISMATCH` | `baseRevision` no es alcanzable en el estado actual. |
| `UNAUTHORIZED` / `FORBIDDEN` | Sin autenticación / sin permiso. |
| `NAME_COLLISION_BLOCKS` | Validación final, XMI definitivo, generación o despliegue bloqueados por duplicados. |
| `MODEL_INVALID` | El modelo resultante sería inválido. |

- Los errores siempre referencian `operationId` y permiten reconstruir el diagnóstico en el panel.
- La API no expone excepciones internas; los diagnósticos son estables y versionados.

## 4. Tiempo real y offline

### 4.1 Eventos

Canal de eventos por proyecto (los nombres NO fijan protocolo de transporte):

| Evento | Contenido mínimo |
| --- | --- |
| `operation.applied` | `operationId`, `resultRevision`, `targets`, `author` |
| `operation.rejected` | `operationId`, diagnóstico |
| `conflict.opened` / `conflict.resolved` | `conflictId`, `target`, `kind`, `payloads` |
| `presence.updated` | usuario, proyecto, cursor/estado (efímero) |
| `message.posted` | `message`: `id`, `authorUserId`, `authorDisplayName`, `body`, `createdAt` (ISO 8601) |
| `layout.updated` | geometrías afectadas de la disposición compartida |
| `collision.registered` / `collision.resolved` | `nameCollisionId`, scope |
| `session.invalidated` | revocación de acceso (posible causa de `operation.rejected`) |

### 4.2 Cola local y orden causal

- El cliente mantiene un **outbox local** con sobres pendientes e `idempotencyKey`.
- El servidor asigna el orden por dependencia causal (`causalParentOperationId`/`baseRevision`); la hora del cliente no ordena.
- Reintentos: reenviar el mismo sobre (misma `idempotencyKey`); el servidor responde el resultado original.
- Persistencia: `idempotency_key` y `causal_parent_operation_id` se persisten en `model_command`; la unicidad de `idempotency_key` por modelo garantiza que el reenvío de la cola offline no duplique efectos.
- SLO de colaboración conforme a RNF-11: respuesta local ≤100 ms; presencia p95 ≤200 ms; comando remoto visible p95 ≤300 ms; confirmación durable p95 ≤1 s; arrastre coalescido 10–30 actualizaciones/s; “sincronizando” >500 ms y servicio degradado >1 s sostenido.

### 4.3 Clasificación de revocación offline

| Clasificación | Comportamiento |
| --- | --- |
| `VERIFIED_PRE_REVOCATION` | Evidencia temporal y de permisos suficiente → se aplica. |
| `VERIFIED_POST_REVOCATION` | Evidencia suficiente de que es posterior → se rechaza con diagnóstico. |
| `AMBIGUOUS` | No hay evidencia suficiente → queda fuera del canon, pasa a revisión administrativa auditada; no bloquea el resto del modelo. |

- La clasificación la hace el servidor con su versión de permisos y el historial causal; el cliente nunca se auto-clasifica como definitivo.

### 4.4 Concesión de edición (lease)

Una concesión **efímera por modelo, en memoria** (una sola instancia): mientras otro editor la tiene **viva**, las exportaciones quedan bloqueadas; la
generación, la descarga del ZIP y la exportación XMI. No bloquea la edición.

**Endpoints** (todos piden acceso de editor; el operador no entra a ninguno — la lista blanca del interceptor no incluye la ruta):

| Verbo y ruta | Operación | Cuerpo | Éxito |
| --- | --- | --- | --- |
| `POST /projects/{projectId}/editing-lease` | adquirir (es el latido si ya es tuya) | opcional `{targetType, targetId}` | 200 |
| `POST /projects/{projectId}/editing-lease/renew` | renovar (sólo titular vivo) | — | 200 |
| `DELETE /projects/{projectId}/editing-lease` | soltar (idempotente) | — | 200 |
| `POST /projects/{projectId}/editing-lease/reclaim` | reclamar (sólo si la ajena ya no está viva) | opcional `{targetType, targetId}` | 200 |
| `GET /projects/{projectId}/editing-lease` | estado actual | — | 200 |

Éxito único: `{ "lease": { modelId, editorUserId, editorDisplayName, targetType, targetId, acquiredAt, expiresAt } }`, con `"lease": null` cuando no hay
ninguna. `expiresAt` es la verdad del servidor; la vivacidad la calcula el cliente comparando contra su reloj (desfase declarado). El TTL por defecto es
30 s (dos latidos de 15 s) y es configurable (`diagrammer.collaboration.editing-lease-ttl`).

**La forma del bloqueo, con dos portadores:** código `EXPORT_BLOCKED_EDITING`; en endpoints de archivo, HTTP **409** con `ApiErrorResource` y
`details[0]`; en `POST /projects/{id}/generations`, HTTP **200** con `outcome=REJECTED` y las mismas cinco claves dentro de `result`. Las claves, en
orden exacto: `editorDisplayName`, `targetType`, `targetId`, `reclaimable`, `retryAfterSeconds`. `reclaimable: true` + `retryAfterSeconds: 0` significa
que la concesión ajena ya no está viva y puede reclamarse explícitamente; reclamar nunca es automático.

**Límites declarados:** una sola instancia (la concesión no cruza); sin eventos ni tabla; un editor con latido vivo no puede ser despojado (el TTL vence
sólo si se corta); el cierre de pestaña no garantiza el release (lo cubre el TTL); la guarda de exportación XMI está conectada en
`XmiExportService` tras la consolidación con la sesión de XMI.

## 5. Conflictos

### 5.1 Criterios

1. **Merge seguro automático:** solo campos independientes, cuando las precondiciones se cumplen y la validación del modelo completo pasa.
2. **Alternativas incompatibles:** se conservan en el **panel lateral** con sus payloads base/izquierda/derecha y su historia; jamás entran al canon sin decisión.
3. **delete-wins:** una eliminación gana sobre ediciones concurrentes mediante tombstone; la edición descartada queda visible en el historial y recuperable únicamente por un comando inverso válido (no por edición directa).
4. **Colisiones de nombres:** IDs distintos, ambos conservados y alertados; bloquean validación final, XMI definitivo, generación y despliegue hasta renombrar, mover o hacer merge explícito.

### 5.2 Resolución

- Resolver produce un **comando nuevo** (`origin: RESOLUTION`) con `targets` al conflicto/collision y `payload` de decisión.
- La resolución se valida igual que cualquier comando y genera nueva revisión.
- El panel lateral muestra el estado (abierto/resuelto/descartado) y la traza al comando que lo resolvió.

## 6. Contrato XMI

### 6.1 Perfil primario (Confirmado)

- Enterprise Architect **15.0.1514 build 1514**.
- Dialecto primario observado en `Prueba3.xml`: **XMI 2.1 / UML 2.1** con extensiones **EA 6.5** e información de diagrama (Q2).
- Fixture canónico: [`docs/fixtures/xmi/Prueba3.xml`](../../fixtures/xmi/Prueba3.xml).

### 6.2 Reglas del intercambio

- **Importar:** adaptador → normalización → validación del modelo canónico; pérdidas y advertencias se registran explícitamente (`xmi_exchange` + `xmi_loss_item`).
- **Exportar:** solo sobre una versión válida y sin colisiones abiertas; se conservan IDs estables, semántica soportada, relaciones y geometría representable.
- **Round-trip (Q3):** EA → aplicación → XMI → EA, comparando IDs, elementos soportados, relaciones y geometría; toda pérdida se reporta, nunca se declara conservación total por inferencia.
- El contrato no exige replicar el 100 % de la información EA; exige declarar la diferencia.

## 7. Contrato IA

### 7.1 Propuesta

La IA (texto, voz o fotografía) produce una **propuesta** revisable, nunca mutación directa. El siguiente es un contrato conceptual versionado, no una API runtime implementada:

```json
{
  "schemaVersion": "1.0",
  "proposalId": "uuid",
  "kind": "PHOTO",
  "baseRevision": 42,
  "provenance": {
    "runtime": "BACKEND_LOCAL",
    "modelId": "Qwen3.5-0.8B",
    "artifactId": "opaque-qwen-artifact-id",
    "executionMode": "BACKEND_LOCAL"
  },
  "input": {
    "sourceType": "PHOTO",
    "transcription": null,
    "transcriptionConfidence": null
  },
  "coverage": {
    "status": "PARTIAL",
    "detectedItems": 3,
    "unresolvedParts": ["tipo de atributo en 'x'", "relación en el borde derecho"]
  },
  "classes": [
    {
      "itemRef": "extract-1",
      "name": "Clase A",
      "confidence": 0.87,
      "attributes": [{ "name": "codigo", "type": "String", "confidence": 0.82 }],
      "operations": [{ "name": "validar", "parameters": [], "returnType": "boolean", "confidence": 0.69 }]
    }
  ],
  "relationships": [
    {
      "itemRef": "extract-2",
      "sourceClassRef": "extract-1",
      "targetClassRef": "extract-3",
      "kind": "ASSOCIATION",
      "multiplicities": { "source": "1", "target": "0..*" },
      "confidence": 0.61
    }
  ],
  "approximateLayout": [
    { "itemRef": "extract-1", "x": 120, "y": 80, "width": 220, "height": 140, "confidence": 0.74 }
  ],
  "missingOrAmbiguousElements": ["tipo de atributo en 'x'", "relación en el borde derecho"],
  "warnings": [
    { "code": "LOW_CONFIDENCE", "message": "...", "scope": { "itemRef": "extract-2" } }
  ],
  "canonicalCommands": [],
  "humanConfirmation": { "required": true, "status": "PENDING" },
  "status": "DRAFT"
}
```

- `kind`: `TEXT`, `VOICE` o `PHOTO`; el esquema se evoluciona mediante `schemaVersion`.
- `provenance` es obligatorio y contiene `runtime` (`BACKEND_LOCAL` o `MOBILE_LOCAL`), `modelId` (`Qwen3.5-0.8B`), `artifactId` y `executionMode` (`BACKEND_LOCAL` o `MOBILE_LOCAL`). Son metadatos de trazabilidad, no un acoplamiento entre runtimes.
- `coverage.status` puede ser `PARTIAL`: la propuesta contiene solo hechos detectados y declara lo que falta, es ilegible o sigue siendo incierto. Cada clase, atributo/tipo, operación, relación y multiplicidad conserva su propia confianza cuando sea observable.
- `classes`, `attributes`, `operations`, `relationships` y `multiplicities` describen la propuesta estructurada; `approximateLayout` solo informa la disposición aproximada observable.
- En voz, `input` conserva la transcripción y su confianza después del ASR local. El límite es `audio → ASR local → texto → Qwen → propuesta`; Qwen interpreta el texto, no audio directamente en este contrato.
- `missingOrAmbiguousElements` y `warnings` hacen visibles las partes faltantes, ilegibles o ambiguas. Los elementos inciertos permanecen fuera del modelo canónico hasta revisión.
- El usuario puede completar y reposicionar manualmente el diagrama. La confirmación humana y la validación UML preceden la conversión de los hechos confirmados a comandos; ningún runtime sobrescribe automáticamente el modelo.
- Revisar, corregir, confirmar o descartar la propuesta genera el comando correspondiente (`origin: AI|VOICE|PHOTO` o descarte).
- Ambos runtimes emiten este mismo contrato, pero son procesos/entornos independientes y no comparten memoria mutable. Qwen es obligatorio para interpretar texto y fotografías; la compatibilidad de artefacto, runtime, cuantización y preprocesador se verifica como gate técnico.

### 7.2 Frontera ASR y ejecución

- La captura de voz termina primero en una transcripción producida por ASR local; esa transcripción es revisable y conserva su confianza cuando esté disponible.
- La transcripción pasa después a la interpretación textual de Qwen 3.5 0.8B en el runtime local seleccionado y de allí a la propuesta común.
- El runtime backend-local en Docker y el runtime móvil-local/en dispositivo para Flutter son independientes. El contrato compartido no implica compartir proceso, memoria mutable, caché ni estado de ejecución.
- `executionMode` identifica el entorno (`BACKEND_LOCAL` o `MOBILE_LOCAL`) y `artifactId` identifica de forma opaca el artefacto ejecutado; ninguno contiene secretos.

## 8. Generation contract

This contract is frontend-agnostic and is designed for the generic-domain generation slice. The source is the user-created domain class diagram, not the current diagrammer metadata/architecture diagram. The implementation target is `backend-main-integration`; this documentation phase does not modify that repository's backend source.

### 8.1 Generation target and versioned identities

`GenerationTarget` (also called `BackendTarget` at the API/domain boundary and persisted as `generation_target`) is the stable identity of one independent generated backend/database destination. A target belongs to one UML model and has a project-local `targetKey`; it can be reused for several model versions. It is not a profile or a template.

- **Target identity:** `targetId` + `targetKey`; identifies an independent output project and its external database destination.
- **Profile identity:** `profileId` identifies one immutable `GenerationProfile` revision. `profileKey` names the logical profile family; `profileRevision` and `configurationHash` identify the exact rules and options.
- **Template identity:** `templateId` identifies one immutable `TemplateVersion` attached to the selected profile, with its own revision and `contentHash`.
- A model/version may therefore be generated for `target-a` and `target-b` using the same profile/template, producing independent runs, ZIPs, and database configurations.

A target request contains only target-safe database configuration:

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

`connectionConfigRef` is an opaque reference to an external secret/configuration manager, not a credential. Passwords, tokens, complete connection strings containing secrets, and private keys are never persisted. Generated source and ZIPs contain environment-variable placeholders or documented secret references only; logs and diagnostics expose at most `targetId` and `configurationRevision`; deterministic hashes exclude credentials, secret values, and secret references.

### 8.2 Immutable input snapshot

Generation freezes an `InputSnapshot` before mapping. Its minimum metadata is:

```json
{
  "source": "USER_DOMAIN_CLASS_DIAGRAM",
  "modelId": "uuid",
  "versionId": "uuid",
  "revision": 42,
  "modelStateHash": "sha256",
  "snapshotHash": "sha256",
  "validationState": "VALID"
}
```

The snapshot contains the validated semantic UML subset: classifiers, namespaces, attributes, operations, parameters, type references, relationships, relationship ends, multiplicities, navigability, visibility, defaults, modifiers, constraints, and stable IDs. It excludes diagrammer platform metadata, personal viewport state, and layout-only data. The current implementation prerequisite is explicit: the backend snapshot contract does not yet expose the full attributes, operations, and multiplicities required by this snapshot.

A snapshot is valid only after final model validation, with no blocking name collisions, dangling references, unsupported required elements, or invalid relationship ends. Live model edits after freezing do not alter the run; they require a new version and run.

### 8.3 Supported UML subset and intermediate representation

The V1 generator supports classes, abstract classes, interfaces, enumerations, packages, attributes, operations, parameters, primitive/domain types, visibility, static/abstract modifiers, default values, associations, aggregations, compositions, generalizations, interface realizations, dependencies, roles, navigability, multiplicities, stable IDs, and supported constraints/stereotypes. This is a subset, not a claim of complete UML 2.5 coverage.

The normalized `DomainModelIR` preserves stable source IDs and contains packages, type declarations, fields, operation signatures, relationship descriptors, multiplicity bounds, nullability/collection intent, naming decisions, and diagnostics. Mapping rules are deterministic:

- classes and abstract classes become JPA entities; interfaces become generated Java interfaces/contracts; enumerations become Java enums; packages become source namespaces;
- attributes become fields, columns, validation metadata, and migration columns; primitive and domain type references resolve through the IR;
- multiplicities map to nullability and collection/single-value associations; roles and navigability select the owning side and relation annotations;
- associations, aggregations, compositions, generalizations, and interface realizations map to the profile's JPA relation/inheritance rules, with lifecycle semantics preserved where supported;
- operations and parameters become supported domain/service methods and corresponding DTO/API contracts; naming, ordering, migration SQL, and file paths use canonical ordering;
- unsupported features produce a structured diagnostic with `code`, `severity`, `elementId`, `feature`, `message`, and `blocking`. A blocking unsupported feature prevents a complete run. A profile may allow a diagnostic-only preview, but it may not silently drop or falsely claim support.

### 8.4 Generation API and lifecycle

The resource contract is:

| Operation | Resource | Contract |
| --- | --- | --- |
| Create/list target | `POST/GET /projects/{projectId}/generation-targets` | Creates or returns a target-safe `GenerationTarget`; credentials are never accepted as persisted fields. |
| Select profile/template | `POST /projects/{projectId}/generation-profiles/{profileId}/select` | Accepts the exact `templateId`; CU-18 resolves immutable profile/template identities and validates compatibility with the snapshot/target. |
| Start generation | `POST /projects/{projectId}/generations` | Accepts `modelId`, `versionId`, `targetId`, `profileId`, `templateId`, and an idempotency key; returns `PENDING` or blocking diagnostics. |
| Read run | `GET /projects/{projectId}/generations/{runId}` | Returns frozen identities, lifecycle state, diagnostics, hashes, and verification summary. |
| Download artifacts | `GET /projects/{projectId}/generations/{runId}/artifacts` | Returns the complete artifact ZIP only when the run has a complete terminal result; partial output is explicitly preview/diagnostic output. |
| Verify run | `POST /projects/{projectId}/generations/{runId}/verification` | Runs CU-20 checks against this run's immutable artifact set and records evidence; it does not silently repair source. |

The lifecycle is `PENDING → RUNNING → SUCCEEDED|FAILED`. Validation and unsupported-feature analysis occur before code generation. A failed or incomplete run remains auditable but is never reported as a ready backend. `SUCCEEDED` means generation completed; it does not by itself mean verification passed. CU-20 separately records `PASSED`, `FAILED`, or `NOT_RUN` checks and the final verification status.

### 8.5 Artifact contract and deterministic hashing

Un ZIP completo contiene las cuatro capas requeridas —dominio/entidades, repositorios/persistencia, servicios/aplicación y controladores/API— además de DTO/mapeadores, validación, errores consistentes, migraciones PostgreSQL, OpenAPI, pruebas unitarias/integración, Docker Compose, configuración reproducible, seguridad, auditoría y artefactos de despliegue exigidos por RF-10/RF-11. También contiene procedencia legible por máquina: snapshot, target, identidades de perfil/plantilla, versión del subconjunto, diagnósticos, hashes de artefactos y estado de verificación. No contiene credenciales, pesos, cachés ni estado de ninguno de los runtimes de IA.

The canonical artifact set sorts normalized UTF-8 paths and hashes each content as SHA-256. The ZIP uses stable paths, normalized metadata, and deterministic ordering. The run records `input_snapshot_hash`, `artifact_set_hash`, and `artifact_zip_hash`; generated artifacts record individual `content_hash` values. For the same frozen snapshot, target identity/configuration revision, profile revision, and template version, equivalent runs must produce equivalent artifact contents. Timestamps, random UUIDs, execution paths, tool logs, secret values, and secret references are excluded from deterministic content and hashes. Different targets remain independent even when their source contents happen to be equal.

### 8.6 Verification metadata and traceability

`verification_result` records at least `checkName`, `state`, `evidence`, `toolName`, and creation time, and is always attached to one `runId`. CU-20 checks structure/four layers, JPA relations, repositories, services and operations, REST/DTO/OpenAPI, validation/errors, security/audit, migrations/PostgreSQL, compile, start, health, tests, Docker Compose, reproducibility, artifact hashes, and target-safe configuration. Evidence identifies the run and does not contain credentials.

This contract traces directly to RF-10/RF-11 and CU-18 (select immutable profile/template and target), CU-19 (freeze, map, generate, hash), and CU-20 (verify without conflating generation with verification). RNF-01, RNF-03, RNF-05, RNF-06, and RNF-07 apply. Q9 remains a C3 product question about the final demonstration sequence and efficiency; it does not authorize a hardcoded domain, a client-specific backend, or reduced generation scope.

## 9. Decisiones abiertas y gaps

| Tema | Estado | Dónde se decide |
| --- | --- | --- |
| Protocolo/proveedor de autenticación e identidad | GAP | Diseño de implementación; el contrato solo usa `externalSubject` + estado de revocación. |
| Algoritmo de colaboración (CRDT, merge, registro de comandos) | Propuesto | D3/D4 con comparación explícita; CRDT no es requisito. |
| Transporte de eventos (WebSocket/SSE/otros) | Propuesto | Implementación; el contrato define eventos, no transporte. |
| Almacenamiento de payloads variables (JSONB) | Decisión D2/D3 | Confirmada en el esquema; tamaño y validación de esquema JSON en implementación. |
| Sincronización de cursor/presencia (efímero vs persistido) | Propuesto | D4; el SLO es el mismo. |
| Reglas de retención y purga de historial/auditoría | GAP | Operación; fuera del flujo normal. |
| Compatibilidad del runtime de IA | La arquitectura backend-local/móvil-local y Qwen 3.5 0.8B están confirmados. | Verificar artefacto, runtime, cuantización, preprocesador de visión, ASR local, recursos y dispositivo objetivo (Q7), sin sustituir Qwen ni acoplar los runtimes. |
| Despliegue real y topología AWS | Backend AI en Docker, pesos/caché configurables en D:, contenedor desplegable en AWS y Floci como emulador local. | Precisar solo detalles operativos elegidos por el equipo; no inventar nombres de recursos ni costos. Comprobar también el almacenamiento WSL/VHDX de Docker Desktop. |
| Interacción exacta del asistente y eficiencia | Abierta (Q9) | Ciclo 3 con evidencia. |

## 10. Trazabilidad y verificación D5

### 10.1 Cobertura

| Contrato | Requisitos/casos principales |
| --- | --- |
| Comandos | RF-03, RF-04, RF-05, RF-06, RNF-02, RNF-03; CU-04…CU-08, CU-10, CU-15 |
| API | RF-12, RF-13, RNF-01, RNF-07; CU-01…CU-03, CU-21 |
| Tiempo real/offline | RF-04, RNF-11; CU-14, CU-15, CU-22 |
| Conflictos | RNF-01, RNF-03; CU-15, CU-16 E |
| XMI | RF-09, RNF-05; CU-16, CU-17, Q3 |
| IA | RF-05, RF-06, RF-07, RF-08, RF-14, RF-15, RNF-08; CU-09…CU-12, CU-22 |
| Generación | RF-10, RF-11, RF-12, RNF-03, RNF-05, RNF-06, RNF-07; CU-18…CU-20 |

### 10.2 Verificación prevista (para D5)

- **Comandos:** escenarios de aceptación/rechazo de precondiciones; idempotencia; undo/redo por autor.
- **API:** contrato OpenAPI consumido por un cliente sin modificar la lógica generada.
- **Real time:** simulador de concurrencia con métricas p50/p95/p99/jitter (RNF-11).
- **Conflictos:** merge seguro feliz + alternativas incompatibles + delete-wins + colisiones bloqueando efectos.
- **XMI:** round-trip Q3 con `Prueba3.xml` y reporte de pérdidas.
- **IA:** comprobar ambos runtimes independientes; texto y fotografía interpretados por Qwen 3.5 0.8B; voz con `audio → ASR local → texto → Qwen`; propuesta común con clases, atributos/tipos, operaciones, relaciones/multiplicidades, confianza, cobertura, faltantes, ambigüedades, layout aproximado y procedencia; revisión humana, validación UML y ausencia de mutación directa.
- **Generación:** generar la misma entrada congelada para dos targets independientes y repetir un target determinísticamente; inspeccionar identidad de target/perfil/plantilla, diagnósticos del subconjunto, ZIP completo y hashes; verificar compilación, inicio, health, contrato, persistencia, seguridad, auditoría y pruebas sin incluir secretos ni estado de IA.

## Checklist D3

- [x] Sobres de operación, `operationId`, objetivos estables, causalidad, precondiciones e idempotencia.
- [x] Respuestas, errores y recursos frontend-agnósticos.
- [x] Eventos, cola local, orden causal, revocación offline y SLO RNF-11.
- [x] Merge seguro, panel, delete-wins, cascadas y colisiones.
- [x] Perfil XMI primario y reglas de round-trip (Q3).
- [x] Contrato conceptual versionado para propuestas IA parciales, confianza por elemento, faltantes, layout aproximado, confirmación y offline.
- [x] Generic-domain generation, independent targets, deterministic artifacts, target-safe configuration, and verification of the complete backend.
- [x] Decisiones abiertas separadas de confirmadas; Q9 abierta.
- [ ] Comparar explícitamente alternativas de colaboración (CRDT vs merge vs registro de comandos) con criterios, antes de cerrar D4.
- [ ] Definir esquema JSON formal (versionado) de cada `payload` en D5.

[Volver a Diseño](../03-diseno.md) · [Volver al Análisis](../02-analisis.md)
