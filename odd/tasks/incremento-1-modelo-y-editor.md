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

- **Objetivo:** que la semántica del diagrama se pueda construir y destruir por comandos validados.
- **Entregable:** manejadores de `classifier.create`, `classifier.rename`, `classifier.setAbstract`,
  `classifier.delete`, `attribute.add`, `attribute.update`, `attribute.remove`, `operation.add`,
  `operation.update`, `operation.remove`, `parameter.add`, `parameter.remove`, `constraint.add`,
  `constraint.remove`.
- **Validaciones exigidas:** nombre de clasificador único por espacio de nombres; tipo de atributo resoluble
  contra `type_reference`; `ENUMERATION` rechazada nombrando `BL-CLS-04` y el GAP-02; visibilidad fuera de
  `PUBLIC`/`PRIVATE` rechazada nombrando `BL-VIS-03`/`BL-VIS-04`; `is_static`/`is_abstract` en atributo
  rechazados nombrando `BL-FEA-05`/`BL-FEA-06`; `constraint.add` con dueño distinto de `CLASSIFIER` se rechaza
  nombrando el dueño.
- **Superficies de edición permitidas:** `backend/src/main/java/com/diagramador/command/**`,
  `backend/src/main/java/com/diagramador/domain/**`, `backend/src/test/**`.
- **Evidencia:** una prueba por comando con su camino feliz y su rechazo; prueba de que el rechazo **nombra el
  constructo**, no que devuelve un error genérico.
- **Depende de:** T2.

### T4 — Comandos de relación y de disposición visual (Etapa B3)

- **Objetivo:** que las relaciones de UML-BL-1 y la geometría entren por el mismo sobre que la semántica.
- **Entregable:** manejadores de `relationship.create`, `relationship.update`, `relationship.delete`,
  `geometry.set`, `geometry.remove`; creación implícita del `connector_path` al crear la relación; validación
  de que la relación tiene exactamente dos extremos del mismo modelo y que no une un clasificador consigo
  mismo.
- **Superficies de edición permitidas:** `backend/src/main/java/com/diagramador/command/**`,
  `backend/src/main/java/com/diagramador/domain/**`, `backend/src/test/**`.
- **Evidencia:** prueba por cada una de las seis relaciones, comprobando el marcador que le corresponde
  (agregación `SHARED`, composición `COMPOSITE`, generalización apuntando al padre); prueba de los cuatro
  valores de multiplicidad aceptados y de `RANGE` rechazado nombrando GAP-05; prueba de que `geometry.set` no
  altera la semántica.
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

## 7. Compromiso de proporción

El Incremento 0 se fue de mano. Acá rige lo contrario:

- Pruebas **donde pagan**: round-trip de persistencia, aceptación y rechazo de comandos, validaciones,
  coalescido, y el arnés de aceptación del corte.
- **Sin** verificación independiente por tarea. Se reserva para los contratos y para el cierre del incremento.
- **Sin** documentos nuevos salvo este, el `README.md` y las capturas que la entrega exija.
- Cada tarea cierra con **un commit de unidad de trabajo**, con sus pruebas al lado y sin atribución de IA.

## 8. Definición de terminado

- [ ] El corte de §1 funciona de punta a punta y se puede repetir desde cero.
- [ ] El esquema lo aplica Flyway, no JPA (`ddl-auto: validate` lo prueba).
- [ ] Un comando inválido se rechaza con motivo, nombrando el constructo o el GAP que lo bloquea.
- [ ] Un constructo fuera de la línea base (enumeración, nota, estereotipo, multiplicidad con cotas,
      visibilidad protegida) se rechaza explícitamente, no se ignora en silencio.
- [ ] Las pruebas del backend pasan con `mvn -o`.
- [ ] La disposición visual sobrevive a la recarga, y un arrastre de N movimientos emite un comando.
- [ ] **La pantalla no muestra ningún identificador técnico.**
- [ ] El `README.md` dice la verdad sobre el estado del repositorio.

## 9. Registro de progreso

| Tarea | Estado | Evidencia |
| --- | --- | --- |
| T1 — Cimiento del backend y Flyway | **hecho** | Commit `1ce4771`; `mvn -o -B verify` = 8 pruebas, 0 fallos, 0 errores, 0 saltadas; Flyway aplicó `V1` y `V2` al esquema `diagrammer`; las 15 tablas y los 7 tipos enum son **byte-idénticos** a `02-esquema-fisico.sql` (verificado con script, 0 discrepancias); `ddl-auto: validate` intacto; enums Java en PascalCase tras falsificar la afirmación de que debían ir en minúscula |
| T2 — Sobre de operación e idempotencia | pendiente | — |
| T3 — Comandos de clasificador y miembros | pendiente | — |
| T4 — Comandos de relación y geometría | pendiente | — |
| T5 — API REST y errores | pendiente | — |
| T6 — Arnés de aceptación por HTTP | pendiente | — |
| T7 — Cimiento del editor web | pendiente | — |
| T8 — Lienzo con edición directa | pendiente | — |
| T9 — Conectores y persistencia del layout | pendiente | — |
| T10 — Cierre del incremento | pendiente | — |

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
