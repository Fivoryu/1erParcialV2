# Incremento 2 — Generación, exportación y despliegue del backend

> **Estado:** plan creado; ninguna tarea ejecutada todavía.
> **Producto:** diagramador colaborativo de clases UML 2.5 (PRIMER PARCIAL, Ingeniería de Software I).
> **Rama:** `feat/incremento-2-generacion-y-exportacion`, apilada sobre `feat/incremento-1-modelo-y-editor`.
> **Autoridad del alcance:** enunciado §10 puntos 10–12 y §11 «Incremento 2 — Generación vertical», más el
> [diseño del generador](../../docs/puds/generacion/01-diseno-generador.md) y los contratos transversales §8.
> **Alcance decidido por el usuario** (esta sesión): generar el backend, exportarlo como ZIP de Spring Boot
> descargable desde la aplicación, verificarlo con una colección Postman corrida a mano y documentada con
> capturas, dejarlo desplegable con base propia para que un cliente futuro se conecte, dockerizar el backend del
> diagramador para que todo backend generado nazca en **la misma versión**, y soportar **múltiples backends
> diferentes**.

## 1. Corte visible que define el incremento

El incremento se cierra cuando se puede hacer esto, de punta a punta:

> **Modelar dos clases relacionadas en el editor, pedir la generación desde la aplicación, descargar el ZIP del
> backend Spring Boot, descomprimirlo, compilarlo, levantarlo contra su propia PostgreSQL y ejecutar el CRUD de
> los dos recursos con Postman, viendo el mismo modelo que se dibujó.**

Y además:

> **Repetir la generación sobre dos destinos distintos del mismo modelo, obteniendo dos backends independientes,
> cada uno con su base de datos, y levantar el backend del diagramador con `docker compose up` sobre la misma
> línea base de versión que el artefacto generado.**

Ese recorrido es RF-10, RF-11 y RF-12, y es exactamente el escenario de aceptación del enunciado §10 puntos
10–12. La diferencia con el corte del Incremento 1 es que acá el artefacto que sale del sistema tiene que ser
**usable por un tercero**: no alcanza con que el diagramador diga «generé»; el ZIP tiene que compilar y andar en
otra máquina con su propia base de datos.

## 2. Alcance

| Pieza | Qué entra |
| --- | --- |
| Línea base de versión | **Una sola** declaración de Java, Spring Boot, PostgreSQL e imagen base, consumida por el `pom.xml` del diagramador y por el perfil del generador (D12) |
| Contenerización | `Dockerfile` y `docker-compose.yml` del backend del diagramador, con su PostgreSQL, sobre la misma imagen base que el backend generado (D13) |
| Esquema de generación | Migración `V4` con las seis tablas del esquema físico: `generation_target`, `generation_profile`, `template_version`, `generation_run`, `generated_artifact`, `verification_result`, más los tipos `generation_state` y `verification_state` |
| Snapshot | `InputSnapshot` inmutable construido desde una versión **validada** del modelo, con `input_snapshot_hash` determinista y sin disposición visual |
| Representación intermedia | `DomainModelIR` normalizada: paquetes, tipos, campos, operaciones, relaciones con propiedad, multiplicidad y navegabilidad, más diagnósticos |
| Reglas de mapeo | Clase → entidad JPA, tabla, repositorio, servicio y controlador; atributo → columna con nulabilidad y validación; asociación → relación JPA con **el lado propietario elegido por multiplicidad y navegabilidad**; paquete → paquete Java |
| **Multiplicidad de destinos** | Un mismo modelo puede tener **N destinos** y generar **N backends independientes**, cada uno con su base y su ciclo de vida. La identidad de corrida incluye el destino y su revisión de configuración (D14) |
| Emisor | Proyecto Maven de cuatro capas, `application.yml` por variables de entorno, migración Flyway, `docker-compose.yml`, `Dockerfile`, `.env.example`, `.gitignore`, documento OpenAPI y procedencia legible por máquina |
| Determinismo | Orden de rutas POSIX, LF, sin reloj, sin rutas absolutas, sin identificadores aleatorios; `content_hash` por artefacto, `artifact_set_hash` y `artifact_zip_hash` |
| Exportación | Endpoint que entrega el ZIP **sólo** si la corrida terminó completa, con historial de corridas y procedencia consultable |
| Verificación | `verification_result` por corrida con `checkName`, `state`, `evidence`, `toolName`; estructura de cuatro capas, migraciones, compilación, arranque, health, contrato, determinismo y configuración sin secretos |
| Evidencia funcional | Colección Postman versionada, corrida a mano contra el backend generado, con capturas en `docs/capturas/incremento-2/` |
| Interfaz | Panel de generación en el editor: destino, generación, estado, diagnósticos y descarga del ZIP |
| Despliegue | `docker-compose` propio del ZIP funcionando, más la preparación AWS/Floci **ejecutada** sin crear recursos facturables |

### 2.1 Subconjunto generable de este incremento

El diseño del generador declara un subconjunto V1 amplio (interfaces, enumeraciones, generalizaciones,
realizaciones, dependencias, estereotipos). **Este incremento implementa un recorte más chico y lo declara**, y
todo lo que queda afuera produce un diagnóstico **bloqueante que nombra la característica**, nunca un descarte
silencioso.

| Entra | Se genera |
| --- | --- |
| Clase y clase abstracta | Entidad JPA, tabla, repositorio, servicio y controlador |
| Atributo con tipo resuelto | Columna, nulabilidad derivada de `required`, validación cuando el tipo la admita |
| Visibilidad pública y privada | Modificador del campo y de la operación de servicio |
| Valor predeterminado | Valor inicial en la entidad y `DEFAULT` en la migración cuando el tipo lo admita |
| Paquete | Paquete Java y ruta de fuente |
| Operación y parámetro | Método de dominio o de servicio y su contrato en el documento OpenAPI |
| Asociación, agregación y composición **uno-a-muchos** | Relación JPA, clave foránea del lado correcto y restricción en la migración |
| Rol, navegabilidad y multiplicidad por categoría | Nombre del campo, lado propietario y forma de la colección |

| No entra en este incremento | Cómo se rechaza |
| --- | --- |
| Generalización y realización de interfaz | Diagnóstico bloqueante que nombra `GENERALIZATION` o `INTERFACE_REALIZATION` y dice que la estrategia JPA de herencia no está implementada. **Es el primer candidato a entrar si el incremento cierra temprano**, porque un dominio del docente puede traerlo |
| Interfaz y enumeración | Diagnóstico bloqueante que nombra el constructo; la enumeración además nombra `GAP-02` |
| Dependencia | Diagnóstico bloqueante: no tiene representación en las cuatro capas de este recorte |
| Estereotipo y nota | Diagnóstico bloqueante que nombra el constructo (GAP-03 y GAP-04 siguen abiertos) |
| Multiplicidad con cotas (`RANGE`) | No existe en el modelo: se rechaza al modelar (GAP-05) |
| Visibilidad protegida o de paquete | Rechazada al modelar (GAP-06) |
| Relación muchos-a-muchos y uno-a-uno | Diagnóstico bloqueante que nombra la combinación de multiplicidades. El spike probó que elegir el lado propietario sin esa regla produce la clave foránea equivocada |

**El perfil registra la versión del subconjunto** que produjo la corrida, así que la procedencia del ZIP dice
con qué reglas se generó y no sólo con qué nombre de perfil.

## 3. Fuera de alcance, y por qué

| Fuera | Motivo |
| --- | --- |
| **Varios modelos por proyecto** (varios diagramas en un mismo proyecto) | `uml_model.project_id` es **UNIQUE** en el esquema autoridad y el contrato §4 publica `GET /projects/{id}/model` en singular. Habilitarlo toca **dos autoridades**: el esquema físico (D5) y el contrato. **Decisión pendiente de §4**: hasta que se resuelva, la multiplicidad de backends se obtiene por destinos (D14) y por proyectos distintos, que no requieren cambio de contrato |
| Diagramas UML que no sean de clases | El producto es un diagramador de clases. Otras vistas (`view_kind`) son del mismo modelo semántico, no de otro dominio |
| Verificación completa de CU-20 | Es obligación de Ciclo 3. Acá se registran los checks que este incremento sí ejecuta y los demás quedan `NOT_RUN`, que es un estado del contrato y no un silencio |
| Autenticación real del diagramador | Sigue el usuario semilla determinista del Incremento 1; el proveedor de identidad es un GAP |
| Autenticación, autorización y auditoría **en el backend generado** | RF-11 las pide, pero el starter de seguridad no está en la caché local en la versión que este plan fija (ver §5). Se cierra por medición en T6: o se emite sin dependencias nuevas y se declara el límite, o se fija una versión que compile sin red. **No se declara soportado lo que no se probó** |
| IA, voz, fotografía | Incrementos 4 y 6. El generador **no** carga pesos, cachés ni estado de ninguno de los dos runtimes |
| XMI | Incremento 5. El ZIP no depende de que el modelo venga de XMI: consume el modelo canónico |
| Colaboración, presencia, conflictos, `undo`/`redo` | Incremento 3 |
| Aplicación móvil | Incremento 7. Acá se prueba que el backend generado corre contra **su** base y responde por HTTP, no que un cliente lo consuma |
| Despliegue AWS con recursos reales | Decisión confirmada: no se crean recursos facturables. Se ejecuta la preparación local y la emulación con Floci |
| Migración del diagramador a perfiles múltiples | Entra un perfil V1 y una plantilla V1, con sus identidades versionadas. Varios perfiles simultáneos no son alcance de este incremento |

## 4. Decisiones cerradas en esta sesión

| # | Decisión | Alternativa descartada | Motivo |
| --- | --- | --- | --- |
| D1 | El proyecto generado se fija en **Spring Boot 3.4.5** | 3.5.0 | Es la versión que el spike midió capaz de construir **sin red**: 3.5.0 pide Flyway 11.7.2, que no está en la caché. Un artefacto que necesita red no es reproducible, y el ZIP se entrega a un tercero |
| D2 | El **documento OpenAPI lo emite el generador** como artefacto determinista | Depender de `springdoc` en el proyecto generado | En la caché local sólo hay `springdoc-openapi-starter-webmvc-ui` **2.1.0**, que es de la línea de Spring Boot 3.1/3.2 y no de 3.4. Además, un documento emitido tiene hash estable y entra en el `artifact_set_hash`; uno generado en runtime, no |
| D3 | El snapshot **excluye toda la disposición visual** | Incluir geometría y waypoints | La semántica del diagrama no depende de dónde se dibujó una clase. La prueba que lo fija es discriminatoria: mover una clase **no** cambia el `input_snapshot_hash`; renombrarla, sí |
| D4 | El ZIP se entrega **sólo** con la corrida en estado terminal completo | Entregar siempre y que el cliente mire | Una salida parcial presentada como lista es exactamente la sobredeclaración que este proyecto existe para evitar. Una vista previa se etiqueta como tal y no satisface el contrato de artefacto |
| D5 | La verificación **externa** (compilar, arrancar, health) la ejecuta el arnés, no el proceso del backend | Que el backend invoque Maven en un subproceso | El backend no debe construir herramientas de build ni depender de la caché del host en tiempo de ejecución. El arnés ya probó ese camino en el spike y en `tools/acceptance/`, con limpieza verificada de procesos y contenedores |
| D6 | La verificación funcional con Postman es **manual, con capturas** | Newman automatizado en el arnés | Decisión del usuario. Se declara el límite: la evidencia manual no es repetible por un tercero, así que la **colección se versiona en el repositorio** y las capturas la acompañan. La compilación, el arranque y el health sí quedan automatizados, porque de otro modo no habría ninguna verificación repetible |
| D7 | Los rechazos de generación usan la convención **D14 del Incremento 1**: HTTP 200 con el diagnóstico en el cuerpo | Derivar el código HTTP del tipo de fallo | Es la convención que ya tiene el contrato de comandos y la que el cliente usa para reconciliar. Los 4xx quedan para fallos de transporte |
| D8 | La configuración de base de datos del destino guarda **sólo datos sin secretos** y una referencia externa opaca | Guardar cadena de conexión y credenciales del destino | RNF-05 y el contrato §8.1: ninguna credencial en la base del diagramador, en el ZIP, en los logs, en los diagnósticos ni en los hashes |
| D9 | El ZIP trae su propio `docker-compose.yml` y **su** PostgreSQL | Compartir la base del diagramador | El backend generado es un destino independiente: su base es suya. Compartir la base del diagramador mezclaría el dominio generado con la metadata del producto |
| D10 | La preparación AWS/Floci se **ejecuta** (imagen construida localmente y camino documentado) | Dejarla como párrafo de intención | Es el objetivo primario antes de la defensa y ya se pagó el costo de aprenderlo en el Incremento 0. Se ejecuta sin crear recursos facturables y se registra el resultado |
| D11 | El generador **rechaza de forma genérica** cualquier característica que no mapee | Una lista de casos conocidos | Es la corrección que la verificación independiente del spike forzó: una lista de casos deja pasar en silencio lo que no está en la lista. Un constructo nuevo del dominio del docente tiene que fallar ruidosamente |
| D12 | **Línea base de versión única.** Java, Spring Boot, PostgreSQL y la imagen base se declaran en **una sola fuente**, que el `pom.xml` del diagramador y el perfil del generador consumen | Dos declaraciones independientes (la del diagramador y la que el generador lleva escrita) | El usuario pidió que el backend generado nazca en la misma versión que el del proyecto. Con dos fuentes, la divergencia es cuestión de tiempo y aparece como «el generado no compila» o, peor, compila distinto. Una prueba compara la fuente contra el `pom.xml` real y falla si divergen, así una subida de versión no puede quedar a medias |
| D13 | El backend del diagramador se **conteneriza** con la misma imagen base que el backend generado | Dejar el diagramador como jar suelto y contenerizar sólo el generado | El usuario lo pidió y además reduce el problema: una sola imagen base, un solo runtime, un solo lugar donde el JRE cambia. El arnés de aceptación sigue corriendo el jar directo para las pruebas rápidas |
| D14 | Un modelo puede tener **N destinos** y generar **N backends independientes**, y la identidad de corrida incluye el destino y su revisión de configuración | Un backend por modelo, o reutilizar la misma base para todas las corridas | Es lo que el usuario pidió («múltiples backends diferentes») y ya está en el diseño y en el esquema: `generation_target` es por modelo, `target_key` es único por modelo y la unicidad de corrida es `(target, target_config_revision, version, profile, template)`. Dos destinos pueden producir contenido **igual** y siguen siendo dos backends: distinto destino, distinta base, distinto ciclo de vida y distinta verificación |

### 4.1 Decisión pendiente

| # | Cuestión | Estado |
| --- | --- | --- |
| **P1** | ¿Hace falta **más de un modelo por proyecto** (varios diagramas en un mismo proyecto), o alcanza con N destinos por modelo más proyectos separados? | **Pendiente del usuario.** Si la respuesta es sí, el incremento incorpora una tarea de contrato y esquema: quitar `UNIQUE` de `uml_model.project_id`, publicar `/projects/{id}/models` y actualizar `02-esquema-fisico.sql` —que es la autoridad (D5)— antes de codificar. Si es no, D14 cubre el requisito sin tocar ninguna autoridad |

## 5. Restricciones de entorno medidas en esta sesión

No son preferencias: son lo que medí en disco antes de escribir este plan.

| Restricción | Valor medido | Consecuencia en el plan |
| --- | --- | --- |
| Java / Maven / Python | 21.0.10 LTS / 3.9.15 / 3.14.6 | El generador vive en el backend Java; el arnés en Python |
| Docker / Compose | 29.8.0 / v5.5.1 | Despliegue y PostgreSQL por contenedor |
| Imágenes PostgreSQL | `postgres:16`, `16-alpine`, `16.14`, `16.3-alpine` | Nada se descarga; el arnés usa `--pull=never` |
| **Imágenes base JDK/JRE** | **ninguna en la caché** (hay `postgres`, `alpine`, `nginx`, `redis`, `caddy`) | Dockerizar exige **una** descarga de imagen base, una sola vez, registrada en la evidencia de T5. Después el build es offline |
| **`Dockerfile` y `docker-compose` del repositorio** | **no existen** | T5 los crea desde cero |
| Spring Boot en caché | 3.0.5, 3.3.8, **3.4.5**, 3.5.0 | D1 queda fijada en 3.4.5 |
| `spring-boot-starter-data-jpa` | **3.4.5** | La capa de persistencia del proyecto generado no necesita red |
| `flyway-core` / driver PostgreSQL | 10.20.1 / 42.7.5 | Las migraciones del proyecto generado no necesitan red |
| `spring-boot-starter-security` | 3.3.8 y 3.5.0 — **no hay 3.4.5** | Con el parent en 3.4.5 el starter no resuelve sin red. **T6 decide por medición**: emitir sin dependencias nuevas y declarar el límite, o fijar una versión compatible que compile offline |
| `springdoc-openapi-starter-webmvc-ui` | **sólo 2.1.0** | Confirma D2: el OpenAPI se emite, no se genera en runtime |
| `unzip` | Info-ZIP 6.00 | El arnés puede descomprimir el ZIP sin agregar dependencias |
| H2 | ausente | Prohibido probar el backend generado contra base en memoria: su migración es PostgreSQL |

**Definición operativa de «sin red» para este incremento:** el backend del diagramador compila y prueba con
`mvn -o`; el ZIP generado se compila con `mvn -o` desde la caché local; PostgreSQL sale de una imagen que ya
está en disco; el `docker build` no descarga nada porque su imagen base ya está. Un artefacto que necesita red
para compilar no cierra este incremento.

**Consecuencia de caché medida en T6 (obligatoria antes de escribir plantillas de `pom.xml`):** el `pom.xml`
generado declara **sólo** dependencias presentes en la caché para el parent 3.4.5. Se verifica construyendo el
proyecto generado con `mvn -o` y, si falla por resolución, la tarea falla y se corrige el perfil: no se acepta
un artefacto que compile sólo con red.

## 6. Etapas y tareas

Cada tarea cierra con **un commit de unidad de trabajo** con sus pruebas al lado, y está cortada para entrar en
**una sola corrida de worker**.

### T1 — Migración V4 con el esquema de generación (Etapa A)

- **Objetivo:** que las seis tablas de generación y sus dos tipos existan en el backend y sean idénticas al
  esquema autoridad, antes de que exista una sola línea de generador.
- **Entregable:** `db/migration/V4__generation.sql` con `generation_target`, `generation_profile`,
  `template_version`, `generation_run`, `generated_artifact`, `verification_result`, los tipos
  `generation_state` y `verification_state`, sus restricciones, comentarios e índices; entidades JPA y
  repositorios.
- **Superficies de edición permitidas:** `backend/src/main/resources/db/migration/**`,
  `backend/src/main/java/com/diagramador/domain/**`, `backend/src/main/java/com/diagramador/persistence/**`,
  `backend/src/test/**`.
- **Evidencia:** el mismo script de comparación que usó el Incremento 1, mostrando **byte-idénticas** las seis
  tablas y los dos tipos contra `docs/puds/datos/02-esquema-fisico.sql`, con 0 discrepancias; `mvn -o -B verify`
  en verde con `ddl-auto: validate` intacto.
- **Depende de:** nada.

### T2 — Snapshot inmutable e identidad de corrida (Etapa B1)

- **Objetivo:** congelar la entrada de una generación de forma que dos corridas con el mismo estado den el mismo
  hash, y que tocar el dibujo no lo cambie.
- **Entregable:** constructor de `InputSnapshot` desde una `ModelVersion` **validada** (rechaza `INVALID` y
  `PENDING` nombrando el estado); recorrido de clasificadores, atributos, operaciones, parámetros, relaciones,
  extremos y restricciones con orden canónico; `input_snapshot_hash`; exclusión explícita de geometría,
  `connector_path` y vista por defecto.
- **Superficies de edición permitidas:** `backend/src/main/java/com/diagramador/generation/**`,
  `backend/src/main/java/com/diagramador/persistence/**`, `backend/src/test/**`.
- **Evidencia:** prueba de que **dos** construcciones del mismo estado dan el mismo hash; prueba de que
  renombrar un atributo **cambia** el hash; prueba de que mover una clase, cambiar un waypoint o crear otra
  vista **no** lo cambia; prueba de que una versión `INVALID` se rechaza nombrando el estado. Las cuatro, porque
  un hash que no cambia nunca también pasaría la primera prueba.
- **Depende de:** T1.

### T3 — Representación intermedia y reglas de mapeo (Etapa B2)

- **Objetivo:** que las reglas de emisión existan como IR verificable, con la regla del lado propietario probada
  y todo lo no soportado fallando con diagnóstico.
- **Entregable:** `DomainModelIR` normalizada; reglas de mapeo de §2.1; **lado propietario por multiplicidad y
  navegabilidad**; resolución de tipos contra `type_reference` con diagnóstico bloqueante para lo irresoluble;
  diagnóstico estructurado con `code`, `severity`, `elementId`, `feature`, `message` y `blocking`; validación
  genérica que rechaza cualquier clave del modelo que no mapee (D11).
- **Superficies de edición permitidas:** `backend/src/main/java/com/diagramador/generation/**`,
  `backend/src/test/**`.
- **Evidencia:** la prueba fuerte del spike portada tal cual — **invertir los extremos de la asociación en el
  modelo no cambia la IR**; prueba de un extremo a uno no navegable rechazado; prueba de que una generalización
  produce un diagnóstico bloqueante que **nombra** `GENERALIZATION` y no un descarte silencioso; prueba de tipo
  irresoluble bloqueante.
- **Depende de:** T2.

### T4 — Perfil, plantilla y destinos versionados (Etapa B3)

- **Objetivo:** que una corrida tenga identidad congelada de estrategia y de destino, y que **un modelo pueda
  tener varios destinos** sin que se pisen.
- **Entregable:** perfil V1 `spring-boot-postgresql` con su `configuration_hash` y la **versión del subconjunto**
  de §2.1; incapacidad de mutar un perfil ya usado (un cambio de opción crea revisión nueva); `template_version`
  con `content_hash`; validación de compatibilidad entre perfil, plantilla, snapshot y destino;
  `target_config_revision`; unicidad de corrida `(target, target_config_revision, version, profile, template)`;
  alta y listado de **N destinos** por modelo, con `target_key` único por modelo.
- **Superficies de edición permitidas:** `backend/src/main/java/com/diagramador/generation/**`,
  `backend/src/main/java/com/diagramador/domain/**`, `backend/src/test/**`.
- **Evidencia:** prueba de que repetir la misma combinación devuelve la corrida existente en vez de crear una
  segunda; prueba de que cambiar una opción del perfil crea revisión nueva y **no** toca la anterior; prueba de
  que un perfil incompatible con el snapshot se rechaza nombrando la opción; **prueba de D14: dos destinos del
  mismo modelo producen dos corridas distintas, con `target_config_revision` propia, sin pisarse ni
  reutilizarse**; prueba de `target_key` repetido en el mismo modelo rechazado.
- **Depende de:** T2.

### T5 — Línea base de versión única y contenerización del diagramador (Etapa B4)

- **Objetivo:** que exista **una sola** declaración de la línea base (Java, Spring Boot, PostgreSQL, imagen base)
  que el backend del diagramador ejecute y que el generador lea.
- **Entregable:** `backend/Dockerfile` en dos etapas (construcción con Maven, ejecución sobre JRE);
  `docker-compose.yml` del diagramador con su PostgreSQL; la línea base declarada en **una** fuente versionada
  que el `pom.xml` del backend y el perfil del generador consuman (D12); imagen base fijada por etiqueta exacta
  y registrada.
- **Superficies de edición permitidas:** `backend/Dockerfile`, `docker-compose.yml`, `.dockerignore`,
  `backend/pom.xml`, `docs/puds/generacion/**`, `backend/src/test/**`.
- **Evidencia:** `docker compose up` sobre el repositorio deja el diagramador arriba, con `health` `UP` y su base
  migrada por Flyway; **prueba de divergencia**: una prueba compara la línea base declarada contra el `pom.xml`
  real y el Dockerfile, y **falla si no coinciden**, de modo que una subida de versión a medias no puede pasar.
  Se registra la descarga única de la imagen base como costo declarado.
- **Costo declarado:** no hay ninguna imagen base JDK/JRE en la caché local, así que esta tarea exige **una**
  descarga de imagen base. Se hace una vez y queda anotada; `docker build` posteriores son offline.
- **Depende de:** nada; **debe estar cerrada antes de T6**, que es quien lee la línea base.

### T6 — Emisor de artefactos de cuatro capas (Etapa C1)

- **Objetivo:** que de la IR salga un proyecto Maven que **compile sin red**, con las cuatro capas y en la misma
  versión que el diagramador.
- **Entregable:** `pom.xml` generado leyendo la línea base de T5, con **sólo** dependencias presentes en la caché
  local (§5); entidad JPA por clase con tabla en `snake_case`; repositorio, servicio y controlador por clase;
  DTO armado dentro de la transacción —corrección del defecto de `EAGER` que el spike declaró como límite—;
  validación y manejo consistente de errores; `application.yml` por variables de entorno; migración Flyway con
  tablas, columnas y claves foráneas explícitas; `Dockerfile` con la **misma imagen base** que el diagramador;
  `.gitignore`; orden canónico de archivos con LF.
- **Superficies de edición permitidas:** `backend/src/main/java/com/diagramador/generation/**`,
  `backend/src/main/resources/**`, `backend/src/test/**`.
- **Evidencia:** un proyecto generado desde un fixture de dos clases relacionadas compila con **`mvn -o -B
  package`, código 0**, sin red. Si la resolución de dependencias falla, **la tarea falla**: no se acepta un
  `pom.xml` que compile sólo con red. Prueba de que la versión de Spring Boot del artefacto generado es **la
  misma** que la del diagramador, leída de la fuente declarada. Prueba de que ninguna ruta del árbol emitido
  contiene la ruta del host, leída **sobre los bytes en disco** y con un control positivo que exija que el patrón
  detecte un caso real (la lección del guardián que nunca disparaba, del spike).
- **Depende de:** T3, T4 y T5.

### T7 — Procedencia, hashes y documento OpenAPI dentro del ZIP (Etapa C2)

- **Objetivo:** que el ZIP sea rastreable y determinista, y que su contrato viaje adentro.
- **Entregable:** documento OpenAPI emitido desde la IR (D2); `provenance.json` con identidades de snapshot,
  destino, perfil y plantilla, versión del subconjunto, diagnósticos, hashes por artefacto y estado de
  verificación; `content_hash` por artefacto; `artifact_set_hash` sobre el manifiesto ordenado;
  `artifact_zip_hash` con orden de entradas y metadata normalizados.
- **Superficies de edición permitidas:** `backend/src/main/java/com/diagramador/generation/**`,
  `backend/src/test/**`.
- **Evidencia:** dos generaciones desde el mismo fixture imprimen el **mismo** `artifact_set_hash` y el mismo
  `artifact_zip_hash`; prueba de que el documento OpenAPI contiene un camino por clase generada y **no** contiene
  datos de IA, credenciales ni rutas del host; prueba de que ningún hash cubre credenciales ni referencias de
  secretos. **Prueba de D14:** dos destinos distintos pueden dar el mismo `artifact_set_hash` y aun así ser dos
  corridas con procedencia distinta; el `provenance.json` de cada una nombra **su** destino.
- **Depende de:** T6.

### T8 — Verificación registrada por corrida (Etapa C3)

- **Objetivo:** que cada corrida tenga evidencia honesta de qué se comprobó y qué no.
- **Entregable:** escritura de `verification_result` con `checkName`, `state` (`PASSED`/`FAILED`/`NOT_RUN`),
  `evidence`, `evidence_hash` opcional y `toolName` descriptivo; checks de estructura de cuatro capas,
  migraciones, hashes, determinismo (dos corridas) y configuración sin secretos; los checks que este incremento
  no ejecuta quedan **explícitamente `NOT_RUN`**, no ausentes; separación entre generación y verificación.
- **Superficies de edición permitidas:** `backend/src/main/java/com/diagramador/generation/**`,
  `backend/src/main/java/com/diagramador/api/**`, `backend/src/test/**`.
- **Evidencia:** prueba de que un artefacto deliberadamente incompleto produce **`FAILED`** y no un `PASSED`
  silencioso; prueba de que un check no ejecutado se lee `NOT_RUN`; prueba de que la evidencia no contiene
  valores con forma de credencial.
- **Depende de:** T7.

### T9 — API de generación, destinos y descarga del ZIP (Etapa D)

- **Objetivo:** que la aplicación pueda pedir una generación —para el destino que elija— y bajarse el artefacto.
- **Entregable:** `POST/GET /projects/{id}/generation-targets` (varios destinos por modelo);
  `POST /projects/{id}/generations`; `GET /projects/{id}/generations/{runId}`; `GET
  /projects/{id}/generations/{runId}/artifacts`; `POST /projects/{id}/generations/{runId}/verification`;
  idempotencia por combinación congelada; rechazos con la convención D7; el ZIP se entrega **sólo** con corrida
  terminal completa (D4) y una vista previa viaja etiquetada.
- **Superficies de edición permitidas:** `backend/src/main/java/com/diagramador/api/**`,
  `backend/src/main/java/com/diagramador/generation/**`, `backend/src/test/**`.
- **Evidencia:** pruebas de capa web por recurso; prueba de que pedir el ZIP de una corrida parcial devuelve un
  rechazo que lo dice y no un archivo a medias; prueba de que pedir la misma generación dos veces devuelve la
  misma corrida y no crea una segunda; prueba de que **dos destinos del mismo modelo devuelven dos ZIP distintos
  con dos `runId` distintos**; prueba de que un destino sin credenciales se crea y uno con un valor con forma de
  credencial se rechaza.
- **Depende de:** T8.

### T10 — Arnés de aceptación del backend generado (Etapa E, evidencia)

- **Objetivo:** probar el corte de §1 sin intervención manual hasta dejar el backend generado corriendo.
- **Entregable:** `tools/generation-acceptance/` que crea proyecto y dos clases relacionadas por HTTP, pide la
  generación, descarga el ZIP, lo descomprime, compila con `mvn -o -B package`, levanta PostgreSQL desde la
  imagen local con `--pull=never`, arranca el backend generado en un puerto libre, espera `health` `UP`, ejecuta
  un smoke de los dos recursos y limpia. Termina procesos huérfanos **verificando** que murieron, porque en
  Windows un JVM vivo bloquea el jar y el fallo aparece como error de `repackage` sin relación con el código.
- **Superficies de edición permitidas:** `tools/generation-acceptance/**`.
- **Evidencia:** salida paso por paso con el conteo de artefactos del ZIP, el hash del conjunto, el resultado de
  la compilación sin red, el `health`, las dos respuestas del CRUD y el teardown verificado con el conteo de
  contenedores de vuelta al valor inicial. **Segundo recorrido con un destino distinto**, que debe producir otro
  ZIP y otra base sin tocar la primera. Modo `--keep-running` para que la colección de T11 corra contra el
  backend ya levantado.
- **Depende de:** T9.

### T11 — Colección Postman y corrida manual documentada (Etapa E, evidencia del usuario)

- **Objetivo:** la verificación funcional que el usuario decidió, con evidencia para el informe.
- **Entregable:** colección Postman versionada en `tools/postman/` con su entorno (URL base y variables, sin
  credenciales reales); recorrido de crear y listar los dos recursos generados, incluido el `POST` anidado que
  resuelve la clave foránea; capturas de la corrida en `docs/capturas/incremento-2/`.
- **Superficies de edición permitidas:** `tools/postman/**`, `docs/capturas/incremento-2/**`.
- **Evidencia:** capturas mostrando las respuestas `201`/`200` con la clave foránea resuelta y el `health` `UP`.
  **Límite declarado:** la corrida manual no es repetible por un tercero; la colección se versiona para que al
  menos el guion lo sea.
- **Depende de:** T10.

### T12 — Panel de generación en el editor (Etapa F)

- **Objetivo:** que la generación y la descarga se hagan desde la aplicación, que es el alcance que el usuario
  eligió.
- **Entregable:** pantalla o panel en `ProjectEditorPage` para crear o elegir **entre varios destinos** y el
  perfil, lanzar la generación, ver el estado y los diagnósticos, y descargar el ZIP; mapeo de los diagnósticos
  bloqueantes a lenguaje de producto.
- **Superficies de edición permitidas:** `web/src/**`.
- **Evidencia:** `npm run build` y `npm test` en verde; prueba de que un diagnóstico bloqueante se muestra con la
  característica nombrada y no como error genérico; prueba de que el panel lista más de un destino cuando el
  modelo tiene más de uno; captura del panel. **Regla heredada del Incremento 1:** la pantalla no muestra UUID,
  `run_id` ni nombres de tabla.
- **Depende de:** T9.

### T13 — Despliegue propio del ZIP y preparación AWS/Floci (Etapa G)

- **Objetivo:** que el backend generado se levante con su propia base, en la máquina de un tercero.
- **Entregable:** `docker-compose.yml` dentro del ZIP con el servicio de la aplicación, su PostgreSQL y la
  configuración por variables de entorno, más un `.env.example` documentado y sin secretos; camino de
  despliegue AWS/Floci **ejecutado** (imagen construida localmente y pasos de Floci corridos) documentado en
  `docs/puds/generacion/02-despliegue-propio.md`.
- **Superficies de edición permitidas:** `backend/src/main/java/com/diagramador/generation/**`,
  `tools/generation-acceptance/**`, `docs/puds/generacion/**`.
- **Evidencia:** `docker compose up` sobre el ZIP descomprimido deja el backend arriba con `health` `UP` y su
  base propia migrada por Flyway; **dos ZIP de dos destinos levantan a la vez sin pisarse**; el registro de la
  preparación AWS/Floci mostrado, **con cero recursos facturables creados**; verificación del espacio consumido
  por el VHDX de Docker Desktop si la imagen base lo movió.
- **Depende de:** T10.

### T14 — Cierre del incremento (Etapa H)

- **Objetivo:** dejar el incremento verificable y el recorrido repetible por otra persona.
- **Entregable:** acta en `docs/puds/cierre/04-cierre-incremento-2.md`; `README.md` raíz actualizado con el
  estado real y las instrucciones de generación, descarga y arranque; §9 de este documento completada; capturas
  y búsqueda de identificadores técnicos en pantalla.
- **Superficies de edición permitidas:** `docs/puds/cierre/**`, `docs/capturas/incremento-2/**`, `README.md`,
  `odd/tasks/incremento-2-generacion-y-exportacion.md`.
- **Evidencia:** el recorrido de §1 ejecutado de nuevo desde cero por alguien que no lo escribió; `mvn -o -B
  verify`, el arnés de aceptación y `npm test` en verde; el acta declarando lo que **no** se verificó y el estado
  de P1.
- **Depende de:** T11, T12 y T13.

### 6.1 Orden y forma de trabajo

`T1 → T2 → T3 → T4` (identidad y reglas), `T5` (línea base única y contenedores), `T6 → T7 → T8 → T9`
(emisión, hashes, verificación y API), `T10` (arnés), después `T11` (Postman) y `T12` (web), luego `T13`
(despliegue) y `T14` cierra.

Ninguna tarea de backend es paralelizable con otra: comparten el mismo esquema, el mismo perfil y el mismo
registro de corridas. **Los writes son de un solo hilo**, salvo que el usuario apruebe aislamiento por worktree.

**T6 es la tarea de riesgo del incremento.** Su resultado puede obligar a cambiar el perfil antes de que existan
plantillas, y por eso está antes que todo lo demás. T5 debe estar cerrada antes que T6 porque T6 lee su línea
base.

### 6.2 Superficies que este incremento agrega

| Superficie | Tipos o rutas |
| --- | --- |
| Tablas | `generation_target`, `generation_profile`, `template_version`, `generation_run`, `generated_artifact`, `verification_result` |
| Tipos PostgreSQL | `generation_state`, `verification_state` |
| Endpoints | destinos, generaciones, artefactos, verificación |
| Artefactos del ZIP | `pom.xml`, entidades, repositorios, servicios, controladores, DTO, migración Flyway, `application.yml`, `docker-compose.yml`, `Dockerfile`, `.env.example`, `.gitignore`, documento OpenAPI, `provenance.json` |
| Contenerización del diagramador | `backend/Dockerfile`, `docker-compose.yml`, `.dockerignore` |
| Herramientas | `tools/generation-acceptance/`, `tools/postman/` |

El catálogo de comandos del modelo **no cambia**: la generación no muta el modelo, lo lee. Si un comando nuevo
apareciera, sería una señal de que la frontera está mal puesta.

## 7. Compromiso de proporción

- Pruebas **donde pagan**: determinismo del snapshot y del conjunto de artefactos, regla del lado propietario,
  rechazo de lo no soportado, independencia de destinos (D14), divergencia de la línea base (D12), idempotencia
  de la corrida, entrega del ZIP sólo si está completo, y el arnés de aceptación del corte.
- **Sin** verificación independiente por tarea. Se reserva para el cierre del incremento.
- **Sin** documentos nuevos salvo este, el acta de cierre, el documento de despliegue y las capturas que la
  entrega exija.
- Cada tarea cierra con **un commit de unidad de trabajo**, con sus pruebas al lado y sin atribución de IA.

## 8. Definición de terminado

- [ ] El corte de §1 funciona de punta a punta y se puede repetir desde cero.
- [ ] El ZIP generado compila con **`mvn -o`**, sin red.
- [ ] El backend generado usa **la misma** línea base de versión que el diagramador, y una prueba falla si
      divergen.
- [ ] `docker compose up` levanta el diagramador con su base, y también levanta el backend generado con la suya.
- [ ] Dos generaciones desde el mismo estado producen el mismo `artifact_set_hash`.
- [ ] **Dos destinos distintos del mismo modelo producen dos backends independientes**, con su propia base,
      aun si su contenido es igual.
- [ ] Mover una clase en el lienzo **no** cambia el `input_snapshot_hash`; renombrarla, sí.
- [ ] Una característica no soportada se rechaza **nombrando la característica**, no se descarta en silencio.
- [ ] El ZIP se entrega sólo si la corrida terminó completa; una salida parcial se etiqueta y no se presenta
      como lista.
- [ ] El backend generado arranca contra **su** PostgreSQL, con las migraciones aplicadas por Flyway, y
      responde `health` `UP` y el CRUD de los dos recursos.
- [ ] La colección Postman queda versionada y su corrida documentada con capturas.
- [ ] Ningún secreto viaja en el ZIP, en la configuración del destino, en los diagnósticos, en los logs ni en
      los hashes.
- [ ] La preparación AWS/Floci se ejecutó **sin crear recursos facturables**.
- [ ] La pantalla del editor no muestra ningún identificador técnico.
- [ ] El acta de cierre declara lo que **no** se verificó y el estado de P1.

## 9. Registro de progreso

| Tarea | Estado | Evidencia |
| --- | --- | --- |
| T1 — Migración V4 con el esquema de generación | pendiente | — |
| T2 — Snapshot inmutable e identidad de corrida | pendiente | — |
| T3 — Representación intermedia y reglas de mapeo | pendiente | — |
| T4 — Perfil, plantilla y destinos versionados | pendiente | — |
| T5 — Línea base de versión única y contenerización | pendiente | — |
| T6 — Emisor de artefactos de cuatro capas | pendiente | — |
| T7 — Procedencia, hashes y OpenAPI | pendiente | — |
| T8 — Verificación registrada por corrida | pendiente | — |
| T9 — API de generación, destinos y descarga del ZIP | pendiente | — |
| T10 — Arnés de aceptación del backend generado | pendiente | — |
| T11 — Colección Postman y corrida manual | pendiente | — |
| T12 — Panel de generación en el editor | pendiente | — |
| T13 — Despliegue propio y preparación AWS/Floci | pendiente | — |
| T14 — Cierre del incremento | pendiente | — |

## 10. Trazabilidad

| Requisito | Dónde se cumple |
| --- | --- |
| RF-10 (generar un backend desde el diagrama) | T2, T3, T6, T9 |
| RF-11 (configuración y estructura completa) | T1, T4, T6, T7, T13, con el subset de §2.1 como techo declarado |
| RF-12 (dominio no codificado de antemano) | T2, T3, T10: el generador no conoce ninguna clase de dominio; la prueba usa un fixture creado por el arnés |
| Múltiples backends independientes | T4, T7, T9, T10, T13 (D14) |
| Versión única entre diagramador y backend generado | T5, T6 (D12), T13 (D13) |
| Determinismo | T2, T7, T8. **Divergencia de numeración declarada:** el enunciado lo numera **RNF-04** y los documentos PUDS lo citan como **RNF-03**. Hay que unificar antes de la matriz final del informe; este plan cita el requisito, no un número que pueda estar mal |
| Seguridad y no filtración de secretos | T4, T7, T8, T9, T13 (D8) |
| Contrato frontend-agnóstico | T7, T9, T12: el backend generado y su documento OpenAPI no saben que existe el editor |
| CU-18, CU-19 y CU-20 | Es el destino de Ciclo 3. T1–T9 cubren la parte de generación y los checks que este incremento ejecuta; los checks restantes quedan `NOT_RUN` y así se declaran en T8 y en el acta de T14 |
| CU-21 (consumir el backend desde un cliente) | Fuera de este incremento: T13 deja el backend desplegable con su base, que es la precondición, y la app móvil es el Incremento 7 |

## 11. Qué se aprovecha de lo ya hecho

| Artefacto previo | Uso en este incremento |
| --- | --- |
| `spikes/uml2spring/` | Reglas de emisión, regla del lado propietario, procedimiento de hash y las tres trampas de ejecución (JVM huérfano, build con red, `EAGER` serializado fuera de transacción). **No es código a copiar:** se reimplementa en Java desde los contratos |
| `docs/puds/generacion/01-diseno-generador.md` | Contrato de snapshot, IR, perfil/plantilla, destinos independientes, ZIP, hashes y verificación |
| `docs/puds/contratos/01-contratos-transversales.md` §8 | Forma de los recursos, ciclo de vida `PENDING → RUNNING → SUCCEEDED|FAILED` y la separación entre generar y verificar |
| `docs/puds/datos/02-esquema-fisico.sql` | **Autoridad del esquema** (D5 del Incremento 1); el subconjunto de generación se copia a `V4` |
| `ModelStateHasher` y `state_hash` del Incremento 1 | Base del determinismo declarado en el acta de cierre |
| `tools/acceptance/` | Patrón de arnés con limpieza verificada de procesos y contenedores, `--keep-running` y `--pull=never` |
