# Incremento 1 — Modelo y editor visual

> **Estado:** plan reescrito y aprobado en sesión, sin empezar.
> **Producto:** diagramador colaborativo de clases UML 2.5 (PRIMER PARCIAL, Ingeniería de Software I).
> **Rama:** `feat/incremento-1-modelo-y-editor`, apilada sobre `feat/incremento-0-contrato-y-riesgos`.
> **Cambio respecto del plan anterior:** el lienzo gráfico **entra** a este incremento. El plan previo lo
> excluía explícitamente («El corte visible es semántico; la disposición visual entra cuando la semántica esté
> firme»). Las decisiones D2, D3 y D4 de §4 registran por qué cambió.

## 1. Corte visible que define el incremento

El incremento se cierra cuando se puede hacer esto, de punta a punta, a mano y en el navegador:

> **Crear un proyecto, arrastrar una clase al lienzo, agregarle atributos y operaciones, conectarla con otra
> mediante una relación, recargar la página y encontrar el mismo modelo con la misma disposición visual.**

Ese recorrido es RF-01, RF-02 y RF-03. Lo que se ve en pantalla son **nombres, tipos y relaciones**: nunca un
UUID, un `classifier_id`, una tabla ni un nombre de comando. Esa restricción no es estética, es de producto, y
se verifica en T10.

## 2. Alcance

| Pieza | Qué entra |
| --- | --- |
| Modelo canónico | `Project`, `UmlModel` con revisión, `Classifier` (clase, clase abstracta, interfaz, paquete), `Attribute`, `Operation`, `Parameter`, `TypeReference`, `Relationship` con sus dos extremos, y la separación explícita entre semántica y layout |
| Capa de comandos | Sobre de operación completo (`operationId`, autor, `baseRevision`, objetivos estables, precondiciones, `idempotencyKey`, causalidad) con los 20 tipos de comando de §6.2 |
| Persistencia | PostgreSQL 16 con migraciones Flyway que **poseen el esquema**; JPA solo valida (`ddl-auto: validate`) |
| Validación | Nombres de clasificador únicos por espacio de nombres, tipos resolubles, revisiones monotónicas, rechazo explícito de constructos fuera de la línea base |
| API | Crear y listar proyectos, abrir el modelo, aplicar comandos, recuperar el modelo y su disposición |
| Lienzo | Editor visual tipo draw.io: crear, mover, redimensionar, conectar y editar elementos con ratón y teclado, sobre una grilla, con zoom y desplazamiento |
| Disposición visual | Persistida en el servidor (D3), con escritura coalescida para que el arrastre no emita un comando por cuadro |
| Interfaz mínima | Lista de proyectos, alta de proyecto, apertura, paleta de elementos, panel de propiedades y recarga |

### 2.1 Constructos UML soportados

Manda la línea base UML-BL-1: **23 constructos soportados**. Nada fuera de ella se implementa a medias.

| Grupo | Constructos |
| --- | --- |
| Clasificadores | Clase (`BL-CLS-01`), clase abstracta (`BL-CLS-02`), interfaz (`BL-CLS-03`), paquete (`BL-CLS-06`), tipo primitivo o de dominio (`BL-CLS-07`) |
| Características | Atributo (`BL-FEA-01`), operación (`BL-FEA-02`), parámetro (`BL-FEA-03`), valor predeterminado (`BL-FEA-04`), estático y abstracto **solo en operación** (`BL-FEA-05`, `BL-FEA-06`) |
| Visibilidad | Pública (`BL-VIS-01`) y privada (`BL-VIS-02`) |
| Relaciones | Asociación (`BL-REL-01`), agregación (`BL-REL-02`), composición (`BL-REL-03`), generalización (`BL-REL-04`), realización de interfaz (`BL-REL-05`), dependencia (`BL-REL-06`) |
| Extremos | Multiplicidad por categoría (`BL-REL-07`), nombre de rol (`BL-REL-08`), navegabilidad (`BL-REL-09`), extremo de relación (`BL-REL-10`) |
| Complementarios | Restricción (`BL-CMP-02`), identificador estable (`BL-CMP-04`), disposición visual separada (`BL-CMP-05`) |

**Restricción declarada sobre `BL-CMP-02`:** la línea base lo declara soportado en persistencia y el esquema
físico tiene `uml_constraint` con dueño polimórfico, así que **no puede quedar fuera sin contradecir la línea
base**. Entra con comando propio (`constraint.add`, `constraint.remove`) y se renderiza como `{expresión}`
dentro de la caja del clasificador. En este incremento el dueño admitido es **solo el clasificador**; los demás
dueños que el esquema permite (`ATTRIBUTE`, `OPERATION`, `PARAMETER`, `RELATIONSHIP`) se rechazan con un motivo
que los nombra, en vez de ignorarse en silencio.

## 3. Fuera de alcance, y por qué

| Fuera | Motivo |
| --- | --- |
| **Enumeraciones y literales** | No hay dónde guardar los literales (GAP-02). `classifier_kind` incluye `ENUMERATION`, así que el sistema **rechaza explícitamente** ese valor en vez de aceptarlo sin literales |
| Notas UML | Sin entidad (GAP-03) |
| Estereotipos | Sin entidad (GAP-04) |
| Multiplicidad con cotas numéricas | Solo se soportan las cuatro categorías (GAP-05); `RANGE` se rechaza |
| Visibilidad protegida y de paquete | Sin restricción de dominio que las sostenga (GAP-06) |
| Restricciones con dueño distinto del clasificador | El esquema las admite (`uml_constraint.owner_type` es polimórfico), pero la UI de este incremento solo las edita sobre el clasificador. Se rechazan nombrando el dueño, no se descartan |
| **Autenticación real** | El proveedor de identidad es un GAP de los contratos. Se siembra un usuario propietario determinista y el sobre viaja con ese autor; no se finge un login |
| Colaboración, presencia, conflictos | Incremento 3. El esquema y el contrato ya los prevén, pero no se implementan |
| **Undo/redo** | `undo`/`redo` son sobres nuevos que referencian la operación original (§2.3 de los contratos) y exigen historial de inversas. Entra en el próximo incremento; se declara acá para no fingir que existe |
| IA, voz, fotografía | Incrementos 4 y 6 |
| XMI | Incremento 5. Las tablas `xmi_*` no se crean todavía |
| Generación de backend | Incremento 2. Las reglas ya están probadas en `spikes/uml2spring/`, pero el spike no es código de producto |
| Edición colaborativa en vivo, deshacer compartido, comentarios | Fuera de RF-01–RF-03 |
| Aplicación móvil Flutter | Incremento posterior; `mobile/` no se crea en este incremento |

## 4. Decisiones cerradas en esta sesión

| # | Decisión | Alternativa descartada | Motivo |
| --- | --- | --- | --- |
| D1 | El diagrama soporta **los 23 constructos de UML-BL-1** | Línea base + enums y notas; o solo clases y atributos | Un diagrama sin relaciones no es un diagrama de clases, y RF-02 pide «los elementos y relaciones definidos en la línea base». Cerrar GAP-02 y GAP-03 toca un contrato que espera ratificación docente (GAP-01) |
| D2 | El lienzo **entra** al Incremento 1 como corte visible | Incremento 1.5 aparte | El usuario quiere entregable demostrable ahora. El costo aceptado es que el plan previo se reescribe y la etapa D deja de ser «interfaz mínima» |
| D3 | La disposición visual se **persiste en el backend** | Solo `localStorage` | RF-01 exige recuperar el proyecto sin perder la disposición. Las tablas `diagram_view`, `element_geometry` y `connector_path` ya existen |
| D4 | Lienzo sobre **`@xyflow/react`** (React Flow 12) con nodos y aristas propios | Lienzo SVG a mano; Konva; tldraw | HTML dentro del nodo es lo que permite editar el nombre y los atributos en línea, como draw.io. Es MIT y no exige atribución: la propia documentación admite que quitarla es legal. Para un proyecto no comercial **se deja la insignia visible** y se declara acá, en vez de borrarla en silencio |
| D5 | **El esquema físico manda** sobre el documento lógico en los nombres divergentes (GAP-09) | Corregir el esquema para que coincida con el documento lógico | El esquema físico es el artefacto ejecutable y verificable; el documento lógico se anota como pendiente. Cambiar el ejecutable para que coincida con la prosa es invertir la autoridad |
| D6 | Base package `com.diagramador`, base de datos `diagramador`, esquema `diagrammer` | — | Nombre neutro y corto; el esquema ya está fijado por el esquema físico |

**D4 queda sujeta a verificación de licencia en T7**, que además comprueba el peso y la licencia del árbol de
dependencias realmente instalado, no solo lo que dice el sitio.

### 4.1 Decisiones cerradas durante la implementación

Estas no estaban en el plan y aparecieron al chocar el contrato con el esquema. Se registran con su motivo
porque una decisión no escrita es una decisión que nadie puede revisar.

| # | Decisión | Alternativa descartada y motivo |
| --- | --- | --- |
| D7 | El `operationId` del sobre **es** la clave primaria `command_id` de `model_command` | Agregar una columna `operation_id`. El esquema físico no la tiene y el contrato pide un id opaco, generado por el autor y estable entre versiones: insertarlo explícitamente cumple las tres cosas sin tocar el esquema |
| D8 | El `payload JSONB` guarda el **sobre canónico completo**; los escalares consultables se proyectan en sus columnas | Guardar solo el `payload` suelto. El comentario de esa misma columna dice que guarda el sobre con precondiciones y argumentos, y el contrato separa `payload`, `preconditions` y la lista de `targets`, que el esquema no tiene desglosados |
| D9 | Un nombre de clasificador **duplicado se rechaza al crearlo**, con `PRECONDITION_FAILED` que nombra a los dos | Registrarlo en `name_collision` y bloquear recién al final, como describe el contrato §5.1. El modelo de «registrar y bloquear al final» existe para la edición concurrente, que es el Incremento 3: sin concurrencia, un duplicado es siempre un error del autor y rechazarlo temprano es mejor producto. La comparación es **sensible a mayúsculas**, porque UML distingue `Usuario` de `usuario`. **Debe revisarse cuando entre la colaboración**: ahí los duplicados pasan a ser legítimos y hay que registrarlos |

**Alcance del rechazo de duplicados (D9):** el índice único es lógico, sobre `(model_id, namespace_id, nombre)`, y
lo aplica la capa de comando porque el esquema **no** tiene `UNIQUE` a propósito. `classifier.rename` valida lo
mismo que `classifier.create`, así que renombrar hacia un nombre tomado también se rechaza.

| # | Decisión | Alternativa descartada y motivo |
| --- | --- | --- |
| D10 | Los comandos que crean un elemento (`*.create`, `*.add`) reciben el **UUID del elemento nuevo desde el cliente** | Generarlo el servidor y devolverlo en la respuesta. El editor necesita direccionar el elemento apenas lo crea para poder editarlo, y la cola offline del contrato §4.2 necesita referenciarlo antes de que el servidor conteste. Es la misma regla que D7 aplicada a los elementos: el id lo genera quien crea, es opaco y no cambia. Un id repetido se rechaza nombrandolo |

**Semántica de `targets` que se aplica en T3 y T4:** `targets` lleva los ids de los elementos **existentes**
sobre los que actúa el comando (el clasificador dueño en `attribute.add`, el clasificador en
`classifier.rename`). El id del elemento **nuevo** viaja en el `payload`, según D10. El primer `target` es el que
se proyecta en `model_command.target_type` / `target_id`, según D8.

| # | Decisión | Alternativa descartada y motivo |
| --- | --- | --- |
| D11 | **Una relación puede unir un clasificador consigo mismo** (auto-relación) | Rechazarla, como decía la primera redacción de T4. UML la permite y es un ejemplo de clase habitual (`Empleado —jefe→ Empleado`). El escenario de aceptación del parcial es modelar **un dominio no codificado previamente** (RF-12), así que un dominio propuesto por el docente puede perfectamente traerla y rechazarla sería fallar en vivo. React Flow dibuja auto-lazos de forma nativa, así que tampoco cuesta en el lienzo. El esquema ya la representa: dos extremos con ordinal 1 y 2 sobre el mismo clasificador |
| D12 | `geometry.set` y `geometry.remove` son la **única** operación de disposición, discriminada por `targetType`: un rectángulo para un elemento, una lista de puntos para una relación | Inventar `connector.set` y `connector.remove` para el ruteo. El catálogo de los contratos §2.1 tiene `geometry.*` y **no** tiene `connector.*`, y las dos tablas de layout del esquema (`element_geometry` y `connector_path`) corresponden una a una con los dos casos. Inventar tipos nuevos rompería la fidelidad al catálogo para ahorrar una rama |
| D13 | La disposición solo aplica a `CLASSIFIER` (rectángulo) y a `RELATIONSHIP` (trazado); cualquier otro `targetType` se rechaza **nombrando el valor** | Aceptar geometría para `ATTRIBUTE`, `OPERATION`, `PARAMETER` o `CONSTRAINT`. En el lienzo esos elementos se dibujan **dentro** de la caja del clasificador, como en draw.io, así que no tienen posición propia: aceptarla crearía filas que ninguna pantalla puede mostrar, que es exactamente la sobredeclaración que este incremento existe para evitar. `RELATIONSHIP_END` y `DIAGRAM_VIEW` tampoco tienen geometría propia |

**Regla de `aggregation_kind` que T4a debe sostener:** `kind = AGGREGATION` implica `SHARED` en el extremo origen
y `NONE` en el destino; `kind = COMPOSITION` implica `COMPOSITE` en el origen y `NONE` en el destino. Para los
otros cuatro `kind`, los dos extremos van en `NONE`. El `payload` **no acepta** `aggregationKind`: `kind` es la
única fuente de verdad, por la misma razón por la que `classifier.create` no acepta `isAbstract`. Sin esto el
rombo podría quedar del lado equivocado del conector sin que ninguna prueba lo note.

| # | Decisión | Alternativa descartada y motivo |
| --- | --- | --- |
| D14 | `POST /projects/{id}/commands` responde **siempre HTTP 200** con el `CommandResult`; los códigos HTTP se reservan a los fallos de transporte | Derivar el código HTTP del `ErrorCode` del rechazo. El contrato §2.2 describe la respuesta del servidor como un cuerpo con `status: REJECTED` y `diagnostics`, **no** como un error HTTP, y la cola offline del cliente necesita leer el diagnóstico para reconciliar. Los 4xx quedan para lo que nunca llegó al pipeline: 404 de proyecto desconocido, 400 de cuerpo malformado o parámetro faltante, y 500 con la forma de §3.2 para lo inesperado, sin rastro de excepción |

**Los identificadores en la API son la excepción a la regla de presentación.** El editor necesita los ids
estables para direccionar elementos, así que la API los expone. La regla de la §1 es de **pantalla**: lo que no
deaparece en el lienzo es el UUID, no del contrato. Nadie debe «limpiar» los ids de la respuesta para cumplir
la regla de presentación.

**Nota sobre el arnés de T6.** Cuando imprime un paso, a veces muestra crudo el valor que sondeó en lugar del
resultado interpretado (por ejemplo, en el paso de teardown del backend imprime el objeto del sondeo). Es
**cosmético**: el veredicto y el código de salida son correctos, y el resumen final es el que manda. Queda
anotado para no confundir a quien lea la salida por primera vez.

**Hallazgo de T3b, corregido en su alcance.** El enum `target_type` tiene ocho miembros — `CLASSIFIER`,
`ATTRIBUTE`, `OPERATION`, `PARAMETER`, `RELATIONSHIP`, `RELATIONSHIP_END`, `CONSTRAINT` y `DIAGRAM_VIEW` — y
ninguno se llama como `type_reference`, `element_geometry` o `connector_path`. La consecuencia **no es la misma
para los tres**, y vale la pena decirlo con precisión en vez de agruparlos:

- `element_geometry` y `connector_path` quedan cubiertos **de forma transitiva** por el tombstone de su dueño:
  una geometría se identifica por el elemento que ubica, y un trazado por su relación, así que
  `(CLASSIFIER, id)` y `(RELATIONSHIP, id)` ya dicen que esos hijos se fueron. No hace falta un miembro propio.
- `type_reference` es el único que **no queda registrado de ninguna forma**. Es inocuo hoy porque esas filas se
  reutilizan por `(model_id, tipo)` y se recrean a demanda, pero se registra igual: un hueco que no molesta
  hoy sigue siendo un hueco.

**Hallazgo de T1, medido sobre el bytecode.** Hibernate 6.6 no obliga a que el enum Java se llame igual que el
tipo PostgreSQL. El desensamblado de `org.hibernate.dialect.PostgreSQLEnumJdbcType` muestra que
`Class.getSimpleName()` se usa **solo** para construir `Dialect.getCreateEnumTypeCommand`, y que ese método se
invoca **únicamente** desde `addAuxiliaryDatabaseObjects`, que es el camino de generación de DDL. Con
`ddl-auto: validate` y Flyway dueño del esquema ese camino no corre nunca. Los siete enums Java se llaman en
PascalCase y la validación y el round-trip lo confirman. Si el esquema alguna vez pasara a generarse con
Hibernate, el nombre del enum Java volvería a importar: queda anotado, no como adorno.

## 5. Restricciones de entorno medidas en esta sesión

Estas restricciones no son preferencias: son lo que el Incremento 0 dejó medido y lo que verifiqué en disco.

| Restricción | Valor medido | Consecuencia en el plan |
| --- | --- | --- |
| Java | 21.0.10 LTS | `pom.xml` con `<release>21</release>` |
| Maven | 3.9.15 | El backend se construye y verifica con Maven |
| **Spring Boot** | **3.4.5, no 3.5.0** | 3.4.5 gestiona Flyway 10.20.1, Hibernate 6.6.13 y el driver 42.7.5, que **están en la caché local**. Con 3.5.0 el build pide Flyway 11.7.2 y necesita red. Un proyecto que depende de la red no es reproducible |
| **Flyway** | 10.20.1 en caché; `flyway-maven-plugin` **ausente** | Flyway corre por **autoconfiguración de Spring**, no por plugin. No se agrega el plugin al `pom.xml` |
| Testcontainers | 1.21.3 en caché, con `testcontainers/ryuk:0.12.0` | Las pruebas de persistencia usan PostgreSQL real en contenedor, **sin red** |
| Imágenes PostgreSQL | `postgres:16`, `16-alpine`, `16.14`, `16.3-alpine` | Hay imagen local disponible; no se descarga nada |
| **H2** | **ausente en la caché** | Prohibido atajo con base en memoria: el esquema usa `CREATE TYPE … AS ENUM` y `gen_random_uuid()`, que H2 no ejecuta igual. Se prueba contra PostgreSQL |
| `psql` | ausente | La base se inspecciona por contenedor, no por cliente local |
| Node / npm | 22.23.0 / 10.9.8 | Web con React + TypeScript + Vite. El registro npm **responde** (verificado, HTTP 200), así que el frontend sí puede instalar |
| Docker | 29.8.0 | PostgreSQL por contenedor |

**Definición operativa de «sin red» para este incremento:** el backend compila y prueba con `mvn -o`; las
pruebas de persistencia levantan PostgreSQL desde la imagen local. El frontend puede instalar dependencias con
red una vez y luego construir con `npm run build`.

## 6. Etapas y tareas

Cada tarea cierra con **un commit de unidad de trabajo** con sus pruebas al lado. Las tareas están cortadas
para que cada una entre en **una sola corrida de worker**: el Incremento 0 demostró que una delegación
demasiado grande falla sin producir nada.

### T1 — Cimiento del backend y esquema propietario de Flyway (Etapa A)

- **Objetivo:** que exista un backend que arranca contra PostgreSQL y hace round-trip de un proyecto.
- **Entregable:** `backend/pom.xml` (Spring Boot 3.4.5, Java 21, sin `flyway-maven-plugin`); aplicación
  Spring; migración `db/migration/V1__baseline.sql` con el subconjunto del esquema físico que este incremento
  usa; entidades JPA; repositorios; `application.yml` con `ddl-auto: validate`; prueba de round-trip con
  Testcontainers.
- **Superficies de edición permitidas:** `backend/pom.xml`, `backend/src/main/java/**`,
  `backend/src/main/resources/**`, `backend/src/test/**`, `backend/.gitignore`.
- **Evidencia:** `mvn -o -B verify` en verde; el log de Flyway mostrando `V1` aplicada; la prueba de round-trip
  creando proyecto y modelo, releyéndolos y comparando.
- **Depende de:** nada. **Gate:** el usuario autoriza el commit.

### T2 — Sobre de operación: persistencia, idempotencia y revisión (Etapa B1)

- **Objetivo:** que el sobre de operación exista como contrato ejecutable, con idempotencia y revisión
  monotónica, antes de que exista un solo comando de modelo.
- **Entregable:** DTO del sobre con sus validaciones; persistencia en `model_command`; unicidad de
  `idempotency_key` por modelo con respuesta del resultado original al reenviar; cálculo de `resultRevision`;
  creación de `model_version` con `state_hash`; las cuatro respuestas (`ACCEPTED`, `REJECTED`, `CONFLICT`,
  `DEFERRED`); los códigos de error de §3.2 de los contratos.
- **Superficies de edición permitidas:** `backend/src/main/java/com/diagramador/command/**`,
  `backend/src/main/java/com/diagramador/persistence/**`, `backend/src/test/**`,
  `backend/src/main/resources/db/migration/**`.
- **Evidencia:** prueba de reenvío con la misma `idempotencyKey` que devuelve el resultado original y **no**
  crea una segunda revisión; prueba de `baseRevision` atrasada que devuelve `REVISION_MISMATCH`; prueba de tipo
  desconocido que devuelve `UNKNOWN_OPERATION`.
- **Depende de:** T1.

### T3 — Comandos de clasificador, atributo, operación y parámetro (Etapa B2)

- **Objetivo:** que la semántica del diagrama se pueda construir, modificar y destruir por comandos validados.
- **Se entrega en dos corridas**, porque incluye los comandos aditivos y además la cascada de borrado:
  - **T3a — aditivos y modificaciones:** `classifier.create`, `classifier.rename`, `classifier.setAbstract`,
    `attribute.add`, `attribute.update`, `attribute.remove`, `operation.add`, `operation.update`,
    `operation.remove`, `parameter.add`, `parameter.remove`, `constraint.add`, `constraint.remove`.
  - **T3b — borrado con cascada:** `classifier.delete`, con `cascade_group` y un `tombstone` por elemento
    afectado, y la precondición de confirmación explícita que exige el contrato §2.4.
- **Invariante que los comandos deben sostener:** `kind = ABSTRACT_CLASS` y `is_abstract = true` son la misma
  afirmación (BL-CLS-02 declara las dos). `classifier.create` y `classifier.setAbstract` mantienen las dos
  sincronizadas, y una prueba comprueba que **no pueden divergir** por ningún camino.
- **Validaciones exigidas:** nombre de clasificador duplicado en el mismo espacio de nombres rechazado según
  D9; tipo de atributo resoluble contra `type_reference` o rechazado; `ENUMERATION` rechazada nombrando
  `BL-CLS-04` y el GAP-02; visibilidad fuera de `PUBLIC`/`PRIVATE` rechazada nombrando `BL-VIS-03`/`BL-VIS-04`;
  `is_static`/`is_abstract` en atributo rechazados nombrando `BL-FEA-05`/`BL-FEA-06`; multiplicidad `RANGE`
  rechazada nombrando `BL-REL-07` y el GAP-05; `constraint.add` con dueño distinto de `CLASSIFIER` se rechaza
  nombrando el dueño.
- **Nota de diseño para T3b:** el handler corre **antes** de que exista la fila de `model_command`, y
  `cascade_group.root_command_id` y `tombstone.command_id` apuntan a esa fila. Por eso el handler devuelve los
  objetivos borrados y es el servicio el que crea el `cascade_group` y los `tombstone` después de persistir el
  comando: el handler muta el modelo, el servicio escribe la historia.
- **Superficies de edición permitidas:** `backend/src/main/java/com/diagramador/command/**`,
  `backend/src/main/java/com/diagramador/domain/**`, `backend/src/main/java/com/diagramador/persistence/**`,
  `backend/src/test/**`.
- **Evidencia:** una prueba por comando con su camino feliz y su rechazo; prueba de que el rechazo **nombra el
  constructo**, no que devuelve un error genérico.
- **Depende de:** T2.

### T4 — Comandos de proyecto, de relación y de disposición visual (Etapa B3)

- **Objetivo:** que las relaciones de UML-BL-1, la geometría y las operaciones de proyecto entren por el mismo
  sobre que la semántica.
- **Hueco del plan que esta redacción cierra:** `project.create`, `project.rename` y `view.create` figuraban en
  el catálogo de §6.2 pero **ninguna tarea los implementaba**. Sin `project.create` no hay proyecto, ni modelo,
  ni vista por defecto, así que el corte visible no puede empezar y `geometry.set` no tiene dónde apoyarse.
- **Se entrega en dos corridas, en este orden:**
  - **T4b — proyecto y vista:** `project.create` (crea el proyecto, el `uml_model` en revisión 0, la
    `diagram_view` por defecto y la membresía del autor como `OWNER`), `project.rename`, `view.create`.
  - **T4a — relación y geometría:** `relationship.create`, `relationship.update`, `relationship.delete`,
    `geometry.set`, `geometry.remove`.
- **Validaciones de relación exigidas:** exactamente dos extremos, ambos clasificadores del **mismo modelo**;
  `kind` dentro de los seis soportados; la auto-relación (D11) **se acepta**; el nombre de rol y la
  navegabilidad van por extremo; multiplicidad por categoría con `RANGE` rechazado nombrando `BL-REL-07` y
  GAP-05. `relationship.create` crea implícitamente el `connector_path` cuando el payload trae los puntos del
  trazado.
- **Validaciones de geometría exigidas:** la vista pertenece al mismo modelo; el `targetType` es uno de los
  ocho del enum y **el objetivo existe con ese tipo** (la integridad polimórfica que el esquema no puede
  garantizar); `w > 0` y `h > 0` como exige `chk_geometry_dims`; `geometry.set` es un **upsert** sobre
  `uq_geometry_view_target`.
- **Separación semántica que hay que probar, no afirmar:** un `geometry.set` **no** cambia ninguna fila de
  `classifier`, `uml_attribute`, `uml_operation`, `uml_relationship` ni `uml_constraint`. Sí crea una revisión
  nueva, porque es un comando del catálogo y la colaboración necesita su causalidad. El `state_hash` de
  `model_version` cubre el **estado versionado completo, incluida la disposición compartida**; el recorte del
  layout es del *snapshot de generación* del Incremento 2 (§8.2), que es otro artefacto.
- **Superficies de edición permitidas:** `backend/src/main/java/com/diagramador/command/**`,
  `backend/src/main/java/com/diagramador/domain/**`,
  `backend/src/main/java/com/diagramador/persistence/**`, `backend/src/test/**`.
- **Evidencia:** prueba por cada una de las seis relaciones comprobando el marcador que le corresponde
  (agregación `SHARED`, composición `COMPOSITE`, generalización apuntando al padre); prueba de los cuatro
  valores de multiplicidad aceptados y de `RANGE` rechazado nombrando GAP-05; prueba de auto-relación aceptada;
  prueba de que `geometry.set` no altera la semántica; prueba de que borrar una relación deja su `tombstone` y
  limpia su trazado.
- **Depende de:** T3.

### T5 — API REST y convención de errores (Etapa C)

- **Objetivo:** exponer el modelo y los comandos por HTTP, con la forma de recurso que fijan los contratos.
- **Entregable:** `POST /projects`, `GET /projects`, `GET /projects/{id}`, `GET /projects/{id}/model`,
  `POST /projects/{id}/commands`; manejador de errores que emite `{code, message, details, operationId}`;
  configuración CORS para el origen de desarrollo del editor.
- **Superficies de edición permitidas:** `backend/src/main/java/com/diagramador/api/**`,
  `backend/src/main/java/com/diagramador/config/**`, `backend/src/test/**`.
- **Evidencia:** pruebas de capa web para cada recurso; prueba de que un error de precondición devuelve
  `PRECONDITION_FAILED` con `operationId` y no un `500` ni un rastro de excepción.
- **Depende de:** T4.

### T6 — Arnés de aceptación del corte visible por HTTP (Etapa C, evidencia)

- **Objetivo:** probar el corte de §1 **sin interfaz**, para que el fallo se localice antes de la UI.
- **Entregable:** `tools/acceptance/` con un arnés reproducible que levanta PostgreSQL, arranca el backend,
  ejecuta la secuencia de §1 por HTTP puro y compara el modelo recuperado contra el esperado, incluidas las
  geometrías.
- **Superficies de edición permitidas:** `tools/acceptance/**`.
- **Evidencia:** salida del arnés paso por paso, con el conteo de elementos y geometrías antes y después de la
  recarga; hash del modelo recuperado.
- **Depende de:** T5.

### T7 — Cimiento del editor web: cliente de API y proyectos (Etapa D)

- **Objetivo:** que el navegador pueda listar y crear proyectos contra el backend real.
- **Entregable:** `web/` con Vite + React + TypeScript; capa de cliente de la API tipada que arma el sobre de
  operación en el navegador; pantalla de proyectos con alta y apertura; router; estilos base minimalistas.
- **Superficies de edición permitidas:** `web/**`.
- **Evidencia:** `npm run build` en verde; pruebas de la capa de cliente cubriendo el armado del sobre y el
  mapeo de errores; **verificación de licencia y peso del árbol de dependencias instalado**
  (`npm ls --all` y la licencia real de cada dependencia directa), que cierra D4 con evidencia y no con la
  promesa del sitio.
- **Depende de:** T5.

### T8 — Lienzo UML con edición directa (Etapa E1)

- **Objetivo:** el corte de §1, hecho a mano en el navegador.
- **Entregable:** lienzo con React Flow sobre una grilla; nodo propio de clase con sus cuatro compartimentos
  (nombre, atributos, operaciones y restricciones `{expresión}`); selección; arrastre; redimensión; edición en
  línea del nombre y de los miembros; panel de propiedades para lo que no se edita en línea (visibilidad, tipo,
  valor por defecto, estático, abstracto, restricciones); paleta para agregar clase, clase abstracta e
  interfaz; zoom, ajuste y desplazamiento.
- **Regla de presentación, verificada en T10:** la pantalla muestra nombres, tipos y símbolos UML
  (`+`, `-`, `#`, `«interface»`, `{abstract}`). **Nunca** un UUID, un `classifier_id` o un nombre de tabla.
- **Superficies de edición permitidas:** `web/src/**`.
- **Evidencia:** `npm run build` en verde; pruebas del render del nodo a partir de un modelo dado; captura del
  lienzo con dos clases y un atributo.
- **Depende de:** T7.

### T9 — Conectores UML, panel de propiedades y persistencia del layout (Etapa E2)

- **Objetivo:** cerrar el corte con relaciones y con la disposición que sobrevive a la recarga.
- **Entregable:** creación de relaciones arrastrando desde el borde del nodo con elección de tipo; aristas
  propias con el marcador correcto (triángulo hueco para generalización, rombo hueco para agregación, rombo
  lleno para composición, punta abierta para asociación, flecha discontinua para dependencia); etiquetas de
  rol y multiplicidad en los extremos; borrado de elementos y relaciones; escritura **coalescida** de la
  geometría (un `geometry.set` al soltar y por temporizador, nunca por cuadro).
- **Superficies de edición permitidas:** `web/src/**`.
- **Evidencia:** prueba de la función de coalescido que demuestra que un arrastre de N movimientos emite un
  comando, no N; recorrido completo de §1 en el navegador con captura antes y después de la recarga.
- **Depende de:** T8.

### T10 — Cierre del incremento (Etapa F)

- **Objetivo:** dejar el incremento verificable y el recorrido repetible por otra persona.
- **Entregable:** capturas en `docs/capturas/incremento-1/`; `README.md` raíz actualizado con el estado real y
  las instrucciones de arranque; §9 de este documento completada; verificación de la regla de presentación
  (ausencia de identificadores en la pantalla).
- **Superficies de edición permitidas:** `docs/capturas/incremento-1/**`, `README.md`,
  `odd/tasks/incremento-1-modelo-y-editor.md`.
- **Evidencia:** el recorrido de §1 ejecutado de nuevo desde cero por alguien que no lo escribió; la búsqueda
  de UUID en el render de la pantalla sin resultados.
- **Depende de:** T9.

### 6.1 Orden y forma de trabajo

`T1 → T2 → T3 → T4 → T5 → T6` (backend, en ese orden) y después `T7 → T8 → T9` (web). T10 cierra.

Las tareas de backend no son paralelizables entre sí: comparten el mismo esquema y el mismo registro de
comandos. Las de web tampoco. **Los writes son de un solo hilo**, salvo que el usuario apruebe aislamiento por
worktree.

### 6.2 Catálogo de comandos de este incremento

| Grupo | Tipos |
| --- | --- |
| Proyecto | `project.create`, `project.rename` |
| Clasificador | `classifier.create`, `classifier.rename`, `classifier.setAbstract`, `classifier.delete` |
| Atributo | `attribute.add`, `attribute.update`, `attribute.remove` |
| Operación | `operation.add`, `operation.update`, `operation.remove` |
| Parámetro | `parameter.add`, `parameter.remove` |
| Relación | `relationship.create`, `relationship.update`, `relationship.delete` |
| Restricción | `constraint.add`, `constraint.remove` |
| Disposición | `geometry.set`, `geometry.remove` |

20 tipos. Todo lo demás del catálogo de los contratos (`member.*`, `proposal.*`, `xmi.*`, `generation.*`,
`undo`, `redo`, `resolve`) queda fuera y se rechaza con `UNKNOWN_OPERATION`.

Los 20 tipos se reparten así: `project.create` y `project.rename` en **T4b**; `view.create` en **T4b**;
los 13 de clasificador, atributo, operación, parámetro y restricción en **T3a**, más `classifier.delete` en
**T3b**; y los 5 de relación y geometría en **T4a**. Ninguno queda sin tarea.

## 7. Compromiso de proporción

El Incremento 0 se fue de mano. Acá rige lo contrario:

- Pruebas **donde pagan**: round-trip de persistencia, aceptación y rechazo de comandos, validaciones,
  coalescido, y el arnés de aceptación del corte.
- **Sin** verificación independiente por tarea. Se reserva para los contratos y para el cierre del incremento.
- **Sin** documentos nuevos salvo este, el `README.md` y las capturas que la entrega exija.
- Cada tarea cierra con **un commit de unidad de trabajo**, con sus pruebas al lado y sin atribución de IA.

## 8. Definición de terminado

- [x] El corte de §1 funciona de punta a punta y se puede repetir desde cero.
- [x] El esquema lo aplica Flyway, no JPA (`ddl-auto: validate` lo prueba).
- [x] Un comando inválido se rechaza con motivo, nombrando el constructo o el GAP que lo bloquea.
- [x] Un constructo fuera de la línea base (enumeración, nota, estereotipo, multiplicidad con cotas,
      visibilidad protegida) se rechaza explícitamente, no se ignora en silencio.
- [x] Las pruebas del backend pasan con `mvn -o`.
- [x] La disposición visual sobrevive a la recarga, y un arrastre de N movimientos emite un comando.
- [x] **La pantalla no muestra ningún identificador técnico.**
- [x] El `README.md` dice la verdad sobre el estado del repositorio.

**Lo único que queda debiendo este incremento, sin adorno:** **ningún humano miró el diagrama**. La regla de
no-IDs está probada sobre el markup —el test afirma que el HTML renderizado no matchea ningún patrón de UUID ni
contiene identificadores crudos, incluso con datos vivos del backend— y la notación UML está fijada por prueba.
Pero si el resultado *se ve* minimalista es un juicio de la persona para la que existe el incremento, y no lo
puede sustituir una aserción. La captura visual es lo primero que hay que cerrar antes de dar el incremento por
presentable.

## 9. Registro de progreso

| Tarea | Estado | Evidencia |
| --- | --- | --- |
| T1 — Cimiento del backend y Flyway | **hecho** | Commit `1ce4771`; `mvn -o -B verify` = 8 pruebas, 0 fallos, 0 errores, 0 saltadas; Flyway aplicó `V1` y `V2` al esquema `diagrammer`; las 15 tablas y los 7 tipos enum son **byte-idénticos** a `02-esquema-fisico.sql` (verificado con script, 0 discrepancias); `ddl-auto: validate` intacto; enums Java en PascalCase tras falsificar la afirmación de que debían ir en minúscula |
| T2 — Sobre de operación e idempotencia | **hecho** | Commit `58b8fef`; `mvn -o -B verify` = 19 pruebas, 0 fallos, 0 errores, 0 saltadas; `V3` con `model_version`, `model_command`, `cascade_group`, `tombstone` byte-idénticas al esquema autoridad (verificado con script); la idempotencia se comprueba **antes** de mutar y el replay no cambia conteos ni revisión; el bump de revisión es un `UPDATE` condicional y el conflicto es una excepción no chequeada, así que la carrera perdida hace rollback de las mutaciones del handler |
| T3 — Comandos de clasificador y miembros | **hecho** (T3a + T3b) | Commits `2c095af` (T3a) y `2da554b` (T3b); `mvn -o -B verify` = 30 pruebas, 0 fallos, 0 errores, 0 saltadas; los 13 comandos ejercitados por el pipeline completo con `baseRevision` incremental, cada efecto leído **de vuelta desde la base**; cascada de 8 elementos con `element_count` = 8 = tombstones = 8; una cascada rechazada deja el modelo intacto, la revisión sin mover y **ni una fila** de `cascade_group` ni `tombstone` |
| T4 — Comandos de proyecto, relación y geometría | **T4b hecho**, T4a pendiente | Commit `398a595` (T4b); `mvn -o -B verify` = 37 pruebas, 0 fallos, 0 errores, 0 saltadas; la relajación del pipeline es una propiedad declarada con default `false` que solo `project.create` sobreescribe, y el servicio exige proyecto y modelo ambos presentes o ambos ausentes; el replay de `project.create` no cambia ningún conteo |
| T5 — API REST y errores | **hecho** | Commit `6bc04aa`; `mvn -o -B verify` = 82 pruebas, 0 fallos, 0 errores, 0 saltadas; D14 probado: un comando rechazado devuelve **HTTP 200** con `status: REJECTED` y el `ErrorCode` en `diagnostics`; el handler de 500 devuelve un mensaje fijo sin texto de excepción ni rastro SQL; CORS limitado al origen de Vite |
| T6 — Arnés de aceptación por HTTP | **hecho** | `python tools/acceptance/acceptance.py` ejecutado y verificado por el mantenedor: **34 pasos OK, 0 fallos, salida 0**; el corte de §1 completo sin interfaz; 7 elementos (2 clasificadores, 1 atributo, 1 operación, 1 relación, 2 extremos) y 4 geometrías (2 rectángulos, 2 puntos de trazado) idénticos antes y después de la recarga; comparación campo a campo "All expected fields match"; hash del modelo releído `b4180200d2b973ce78c571cbed893204edf8b327d9c442e6a18bb70d3d8bed1c`; teardown verificado: proceso backend muerto, contenedor borrado por etiqueta, conteo de contenedores de vuelta en 35 |
| T7 — Cimiento del editor web | **hecho** | Commit `be9de52`; `npm run build` verde (270.93 kB JS, 8.83 kB CSS) y `npm test` = 4 pasan, 1 saltada (la viva, opcional); licencias leídas del árbol **instalado**: todo MIT salvo `typescript` en Apache-2.0 |
| T8 — Lienzo con edición directa | **hecho** | Commit `12fac29`; 10 pruebas verdes; la regla de no-IDs probada sobre el markup con UUIDs reales en el fixture (no matchea patrón de UUID, no aparecen `classifier_id`, `EXACTLY_ONE`, `ZERO_OR_MANY`, `NOT_NAVIGABLE` ni `ABSTRACT_CLASS`); multiplicidades como `1`, `0..1`, `1..*`, `0..*`; **D4 cerrada** con `@xyflow/react` 12.12.0, MIT, ~5 MB instalado y 205.90 kB de bundle, atribución visible por decisión |
| T9 — Conectores y persistencia del layout | **hecho** | Commit `d12a9b9`; 24 pruebas, **0 saltadas**, incluidos los dos tests vivos contra el backend real; el corte completo con dos clases, atributo, operación, relación, movimiento y recarga devuelve **la misma revisión, la misma geometría, las mismas posiciones, los mismos waypoints y los mismos miembros**; `geometryCommands` tiene longitud **1** tras un arrastre, que es el coalescido probado contra un backend real |
| T10 — Cierre del incremento | **hecho con una deuda declarada** | README raíz reescrito con el estado real; §8 marcada; **deuda: sin captura visual**. Ningún humano miró el diagrama, y si se ve minimalista es un juicio humano, no una aserción |

## 10. Trazabilidad

| Requisito | Dónde se cumple |
| --- | --- |
| RF-01 (crear, abrir, guardar proyectos) | T5, T7, y la segunda mitad —recuperar **con** disposición— en T4 y T9 |
| RF-02 (diagramas de clases UML 2.5) | §2.1, T3 y T4, con la línea base como techo declarado |
| RF-03 (editar manualmente) | T8 y T9 |
| RNF-04 (determinismo) | Queda fuera de este incremento: es del generador (Incremento 2) |
| RNF-07 (contrato frontend-agnóstico) | T5: la API no sabe que existe el editor |

## 11. Qué se aprovecha de lo ya hecho

| Artefacto del Incremento 0 | Uso en este incremento |
| --- | --- |
| `docs/puds/contratos/03-linea-base-uml.v1.json` | Define qué constructos se pueden implementar y cuáles se rechazan, y con qué nombre |
| `docs/puds/contratos/01-contratos-transversales.md` §2, §3 y §4.1 | Forma del sobre, de los recursos y de los eventos |
| `docs/puds/datos/02-esquema-fisico.sql` | **Autoridad del esquema** (D5); se copia el subconjunto necesario a `V1__baseline.sql` |
| `spikes/uml2spring/` | Prueba que Spring Boot **3.4.5** es la versión que construye sin red; reglas de emisión para el Incremento 2 |
| Restricciones de entorno de §5 | Por qué el `pom.xml` no lleva `flyway-maven-plugin` y por qué H2 está prohibido |
