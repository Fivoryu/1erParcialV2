# Validación semántica UML 2.5 (modelo canónico)

> **Estado:** incremento ejecutado y verificado (2026-10-05). T1–T4 cerradas; evidencia final `mvn -o -B verify` = 387 pruebas, 0 fallos, sin red. Exploración previa con evidencia (`gentle-ai-explore`, task `muum7brx-1-fapb`). Rama `feat/validacion-uml-semantica` desde `main` = `4dac15e`, en el worktree `1erParcialV2-validacion`.

## 1. Corte visible que define el incremento

Un usuario con un modelo guardado puede pedir la validación semántica del modelo canónico
(`GET /api/projects/{id}/model/validation`) y recibir una lista de diagnósticos de bien-formación
UML 2.5 con `code`, `severity`, `elementId` y `message`, sin necesidad de lanzar una generación.
Un modelo con herencias circulares o multiplicidades mal formadas se detecta **en el modelo**, no
solo cuando el generador falla.

## 2. Qué ya existe (no duplicar)

| Regla | Dónde vive hoy | Decisión |
| --- | --- | --- |
| Ends colgantes / ≠ 2 ends | `INVALID_RELATIONSHIP_ENDS`, `UNRESOLVED_RELATIONSHIP`, `UNRESOLVED_CLASSIFIER` — `generation/DomainModelIRMapper.java:285-289, 335-355` | No se duplica |
| Tipos desconocidos | `UNRESOLVABLE_TYPE` — `DomainModelIRMapper.java:376-410` (solo contra snapshot de generación) | Se reemite a nivel modelo como regla propia (independiente de generación) |
| Multiplicidades (subconjunto Java) | `UNSUPPORTED_MULTIPLICITY`, `UNSUPPORTED_MULTIPLICITY_COMBINATION` — `DomainModelIRMapper.java:181-189, 293-300` | Son restricciones de persistencia; las reglas nuevas son semántica UML 2.5, con códigos distintos |
| Ciclos de namespace | `INVALID_NAMESPACE_CYCLE` — `DomainModelIRMapper.java:523-527` | No toca: otro dominio de ciclos |

**Ciclos de generalización: greenfield total** (grep de `GENERALIZATION` solo aparece en enum y XMI; el mapper ni siquiera soporta generalizaciones para generar).

## 3. Alcance

### 3.1 Reglas (nivel modelo, semántica UML 2.5)

- **R1 — Ciclos de generalización/generalización-realización:** grafo dirigido sobre relaciones
  `GENERALIZATION` e `INTERFACE_REALIZATION`; ciclo ⇒ ERROR.
- **R2 — Auto-generalización:** un clasificador no puede ser padre e hijo de sí mismo.
- **R3 — Valididad de ends de generalización:** exactamente 2 ends, sin rol duplicado, sin
  multiplicidad to-many en un end de generalización (UML 2.5: los ends de Generalization no
  tienen multiplicidad).
- **R4 — Multiplicidad `RANGE` en atributos (reformulada 2026-10-05, decisión del usuario):** el modelo canónico no guarda bounds numéricos de `RANGE` (enum pelado, sin lower/upper en dominio/DB; XMI los descarta por diseño). Regla vigente: `RANGE` en atributo ⇒ `INCOMPLETE_MULTIPLICITY_SPECIFICATION`, WARNING (multiplicidad no completamente especificada). Sin comparaciones de bounds.
- **R5 — Nombres de miembros únicos por namespace:** atributos y operaciones no colisionan en
  nombre dentro de un clasificador.
- **R6 — Tipos resolvibles a nivel modelo:** cada `typeReference` de atributo/parámetro resuelve
  contra primitivos del perfil o clasificadores del modelo.

### 3.2 Fuera de alcance

- Cambiar diagnósticos existentes de generación (`DomainModelIRMapper`) o su contrato.
- Frontend (`web/` está ocupado por otra sesión en `feat/vista-compacta-editor`).
- XMI import/export.
- Bloqueo de comandos por validación (queda como read-only check; integrarlo al pipeline de
  comandos es un incremento posterior).

## 4. Diseño

- Paquete nuevo `com.diagramador.validation`:
  - `ModelValidationDiagnostic` record `(code, severity, elementId, feature, message)` — estilo
    `GenerationDiagnostic` (severa-INFO/WARNING/ERROR vía `SeverityKind`); no se reutiliza
    `command/Diagnostic` (mismo criterio que la capa de generación).
  - `ModelValidator` con reglas como métodos puros sobre un read-model inmutable del snapshot
    del modelo (estilo `InputSnapshotFactory`), sin acceso directo a repos dentro de las reglas.
  - `ModelValidationApiService` que carga el modelo por id y lo valida.
- Endpoint: `GET /projects/{projectId}/model/validation` en `api/ProjectController` (el controlador
  no usa prefijo `/api` en el código; corregido el 2026-10-05 al verificar la convención real).
- 404 si el proyecto no existe; modelo sin modelo definido ⇒ lista vacía.
- El test de API vive en `com.diagramador` (no `com.diagramador.api`): los `*ApiTest` del repo
  viven todos ahí porque `AbstractPostgresIntegrationTest` y `AuthenticatedApiTest` son
  package-private (resolución de T3, 2026-10-05).

## 5. Unidades

| Unidad | Contenido | Verificación |
| --- | --- | --- |
| T1 | Read-model del snapshot + `ModelValidationDiagnostic` + reglas R1–R2 (ciclos, auto-generalización) | `*Test` unitario puro, RED→GREEN; `mvn -o -B test` |
| T2 | Reglas R3–R6 | Mismo patrón, casos límite por regla |
| T3 | Endpoint API + `ModelValidationApiService` | `ModelValidationApiTest` con Postgres; `mvn -o -B verify` completo |
| T4 | Cierre: acta, README "Qué está verificado hoy", commit final | Revisión de evidencia |

## 6. Convenciones de ejecución

- Test-first: observar RED, GREEN, refactor; sin Spring en tests unitarios de reglas.
- Commits por unidad con Conventional Commits; evidencia registrada en este documento.
- Todo el trabajo en `D:\...\1erParcialV2-validacion`; jamás tocar `web/` ni el worktree principal.
- Idioma de artefactos técnicos: inglés (código, tests, mensajes de commit), según convención del repo.

## 7. Estado de ejecución

| Unidad | Estado | Evidencia |
| --- | --- | --- |
| T1 | **cerrado** | commit `feat(validation)` (ver §7 evidencia); RED observado (compile failure sin clases), GREEN 12/12, suite completa 367/0; convención de ends (ordinal 1=subtype, 2=supertype) verificada contra `XmiSemanticEmitter.appendGeneralization` (ends.get(0)=specific, get(1)=general) |
| T2 | **cerrado** | commit `feat(validation)` (ver evidencia abajo); RED observado (4 reglas + overload faltantes), GREEN 26/26 en `ModelValidatorTest`, suite completa 383/0 (367+16). Desviación R4 registrada en §3.1; R6 espeja el catálogo de primitivos del perfil (javadoc del constante) |
| T3 | **cerrado** | commit `feat(validation)` (ver evidencia abajo); RED observado (4 tests, 3 fallos 404 por endpoint ausente), GREEN 4/4, suite completa 387/0. Contrato: `GET /projects/{id}/model/validation`, 200 con `{"diagnostics":[…]}` (lista vacía si el modelo está bien formado o aún no existe `UmlModel`), 404 `NOT_FOUND` si el proyecto no existe; requiere miembro autenticado (`requireMember`, igual que `getModel`) |
| T4 | **cerrado** | acta finalizada; README actualizado (fila de validación semántica + backend 387); commit de cierre. Evidencia final: `mvn -o -B verify` = 387 pruebas, 0 fallos, BUILD SUCCESS, sin red (2026-10-04 23:44) |

Commits de unidad: `2752b78` (plan), `1e4e87b` (T1), `4674bb7` (T2), `1c5ea17` (T3), cierre en el commit final. Desviación registrada: R4 reformulada (§3.1); endpoint sin prefijo `/api` y test en `com.diagramador` (§4).

Evidencia T1: `ModelValidationReadModel.java`, `ModelValidationDiagnostic.java`, `ModelValidator.java` (Kosaraju SCC para R1), 12 tests puros; `mvn -o -B test` completa: 367 pruebas, 0 fallos. Nota: id de entidades en tests se fija con helper de reflexión (sin overloads con id en factories).
