# Almacenamiento híbrido SQL / NoSQL por clase

> **Producto:** diagramador colaborativo UML 2.5. **Rama:** `feat/almacenamiento-hibrido`.
> **Estrategia de entrega:** `ask-on-risk`. **Runner:** backend `mvn -o -B verify` (Docker para Testcontainers), web `npm test` / `npm run build`.

## Specs

- **S1 — Cada clase tiene un almacenamiento, automático por defecto.** Un diagrama de clases puede ser híbrido: cada
  clase persistible (`CLASS` / `ABSTRACT_CLASS`) se guarda en `SQL` o en `DOCUMENT`. "tiene que ser automatico": sin
  intervención del usuario el sistema decide y explica por qué. El usuario puede fijar el almacenamiento de una clase
  (override); quitar el override devuelve la clase a automático. Reglas automáticas, en este orden y con SQL por
  defecto (así un diagrama sin señales sigue generando exactamente lo de hoy):
  1. parte de una composición (extremo opuesto al extremo `COMPOSITE`) → `DOCUMENT`;
  2. tiene algún atributo con multiplicidad múltiple (lista) → `DOCUMENT`;
  3. en otro caso → `SQL`.
- **S2 — El generador soporta los dos a la vez, o solo uno.** "para que soporte los dos a la vez o sql o nosql".
  Clases `SQL`: JPA + migración Flyway + PostgreSQL (como hoy). Clases `DOCUMENT`: documentos Spring Data MongoDB +
  `MongoRepository`. Las dependencias, el `docker-compose`, el `application.yml` y el README incluyen cada motor solo
  si hay al menos una clase de ese motor (100 % SQL: salida idéntica a la actual; 100 % NoSQL: sin PostgreSQL ni
  Flyway). Las relaciones entre motores (y toda relación de una clase `DOCUMENT`) se generan como referencia por
  `UUID`, sin FK ni JOIN: la ausencia de integridad referencial se declara en el README y en la verificación.
- **S3 — El editor muestra y permite cambiar el almacenamiento.** "que se vea bien el diseño": cada clase muestra un
  distintivo `SQL` / `Document` y el panel de propiedades ofrece Automático / SQL / Document con el motivo de la
  decisión automática. Verificado visualmente con ego-browser.
- **S4 — La IA local conoce el almacenamiento.** "Tambien la IA local debería estar enterado sobre los documentos el
  diagrama de clases?" El contexto del diagrama que recibe el proveedor incluye el almacenamiento efectivo de cada
  clase y el prompt explica que las relaciones entre motores son referencias por id.
- **S5 — El almacenamiento viaja con el modelo.** El override se persiste, se versiona (snapshot, hash de estado,
  merge) y llega al generador. Un modelo sin overrides conserva sus hashes actuales.

## Supuestos (no pedidos; marcados)

- A1: las clases `DOCUMENT` exponen CRUD + listado; los endpoints `/query`, `/values` y `/aggregate` (Criteria JPA)
  se omiten para ellas y se declara en el README.
- A2: XMI no transporta el override en esta entrega (se declara; el override vuelve a automático al importar).
- A3: el asistente no propone cambios de almacenamiento todavía; solo lo ve.

## Tasks

- [x] **T1 — Modelo, clasificador y API** (S1, S5). Migración `V26__classifier_store.sql`, entidad, comando
  `classifier.setStore` (con deshacer), recurso (`storeOverride`, `store`, `storeReason`), `PersistenceStoreClassifier`,
  snapshot/hash/merge, `InputSnapshot`. Ruta: inline. RED: `PersistenceStoreClassifierTest` falló por compilación antes
  de la clase; los demás tests nacieron después del código (sin RED). GREEN: 5 + `T3aCommandHandlersTest` +
  `UndoServiceTest` + `ClassifierStoreApiTest`. Commit: —
- [x] **T2 — Generador híbrido** (S2). Ruta: inline. GREEN: `HybridProjectEmitterTest` (7), tests del generador
  existentes (modelo 100 % SQL sin cambios, incluye el build offline). Verificación real: el proyecto híbrido compila
  con Spring Boot 3.4.5 (build con red; la caché offline solo trae Mongo 3.3.8) y arranca contra PostgreSQL 16 y
  MongoDB 7: CRUD en ambos motores, referencia SQL→documento por UUID sin FK, Flyway crea solo la tabla `order`.
  Commit: —
- [x] **T3 — Editor web** (S3). Ruta: inline. GREEN: `classifierStore.test.tsx` (6), suite web 683, lint y build.
  Verificado en ego-browser (L4). Commit: ver Log.
- [x] **T4 — Contexto de la IA** (S4). `DiagramContextRenderer` + ambos prompts. GREEN: ver Log. Commit: —
- [x] **T5 — Verificación visual y documentación** (S1–S5). Ruta: inline. Capturas `odd/evidence/vcs-visual-20261024/20-22-*.png`. Commit: ver Log.

## Log

- **L1 (2026-10-24, usuario):** "Vamos con ello, que se vea bien el diseño. Recuerda que tienes ego-lite como tus
  ojos :D" (tras la conversación: "Un diagrama de clases puede ser hibrido para sql y no sql?", "como podriamos hacer
  el tema del generador de backend para que soporte los dos a la vez o sql o nosql, tiene que ser automatico.",
  "Tambien la IA local debería estar enterado sobre los documentos el diagrama de clases?").
- **L2:** mapa del explorador: costuras en `DomainModelIR.TypeDeclaration`, `DomainProjectEmitter` (entidad,
  repositorio, servicio, controlador, migración, pom, yml, compose, README), `GenerationVerificationService:271`
  (exige `V1__init.sql`), `DiagramContextRenderer:50`. Riesgo 1/3: el campo se persiste (V26) y entra al hash; para
  no romper hashes existentes el campo solo se hashea cuando el override no es nulo.
- **L3:** hallazgos de la ejecución. (a) `SchemaOwnershipTest.everyMappedColumnExistsInItsTable` falló en la suite
  completa: lista las columnas de `classifier` y faltaba `store_override`; se actualizó el catálogo. (b) La web ya
  refresca el modelo canónico tras cada comando, así que `store` y `storeReason` se leen del servidor y el reducer
  optimista solo proyecta una elección fija. (c) El perfil de generación sigue llamándose `spring-boot-postgresql`
  (supuesto A4: el modelo sin clases documento genera exactamente lo de antes; el perfil/plantilla no se versiona en
  esta entrega). (d) `/api/<doc>/query` responde 400 (el segmento `query` no es un UUID), no 404: el endpoint no existe.
  (e) No se subió `PROMPT_VERSION`: la regla nueva es informativa y el corpus `tools/ai-eval` se evalúa contra v4.
- **L4:** T4 verde: `DiagramContextRendererStoreTest` 3/3 y `LlamaCppAssistantProvider*Test` + `AssistantPlan*Test` (23) sin fallos.
  Verificación visual (jar nuevo contra PostgreSQL + `npm run dev`, proyecto sembrado por API): Order y Customer muestran SQL, OrderLine
  muestra Document por ser parte de la composición; el panel ofrece "Automatic (Document)" con el motivo "Part of a composition owned by Order.";
  al elegir "SQL (relational)" la insignia pasa a SQL con el pin de manual y el motivo "Set manually." (revisión 11→12).
  Pendiente: la suite completa del backend sobre el código final (la corrida se mató por poca memoria; no se relanzó).
  Risk: item 1 (columna nueva V26, formato persistido) e item 3 (hash y contrato; el hash solo cambia con override, constructores de compatibilidad).
