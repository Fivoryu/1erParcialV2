# Despliegue propio de un backend generado y preparación AWS/Floci

> Incremento 2, tarea T13. Este documento registra un despliegue **ejecutado**, los pasos de emulador que
> **se corrieron**, y los límites que **no** se verificaron. Nada de lo que sigue se proyecta desde la
> intención: cada cifra es un valor observado.

[Diseño](01-diseno-generador.md) · [Ejecución local](02-ejecucion-local.md) · [Plan de tareas](../../../odd/tasks/incremento-2-generacion-y-exportacion.md)

## 1. Qué cubre

El Incremento 2 entrega un backend Spring Boot generado como ZIP. Este documento cubre el paso siguiente: el ZIP
se descomprime en una máquina que no es la del diagramador, se compila y se levanta con **su propia** PostgreSQL,
y dos destinos del mismo modelo corren **a la vez sin interferirse**. También registra la preparación AWS
ejecutada contra el emulador local.

Dos hechos definen la forma del artefacto:

- Un destino es independiente. Su proyecto Compose, sus contenedores, su base y su volumen llevan identidad
  propia, así que dos destinos nunca comparten estado aunque su contenido generado sea idéntico.
- El proyecto generado **no lleva ningún secreto**. Toda credencial entra por el entorno de la máquina que lo
  despliega.

## 2. Los artefactos de despliegue dentro del ZIP

El emisor escribe tres artefactos de despliegue junto al código del proyecto:

| Artefacto | Rol |
| --- | --- |
| `docker-compose.yml` | El stack propio del destino: el servicio de la aplicación, su PostgreSQL, el cableado del datasource y los dos healthchecks. |
| `.env.example` | Todos los valores que el destino necesita, documentados, sin ningún secreto usable. El operador lo copia a `.env`. |
| `Dockerfile` | Ya existía desde T6: la imagen base del runtime desde la línea base del classpath, y el jar empaquetado. |

Reglas que el emisor impone y que cada prueba hace fallar si se rompen:

1. **La identidad viene de la clave del destino.** El proyecto Compose es `generated-<slug>`, y el mismo slug
   nombra el servicio de la aplicación, el servicio de la base y el volumen de la base. `<slug>` es la forma
   kebab-case de la clave del destino, la misma identidad que ya usa el artefacto Maven. Por construcción, dos
   destinos no pueden chocar en un nombre de servicio ni de volumen.
2. **La imagen de la base y la imagen base del runtime vienen de la línea base del classpath**
   (`backend/src/main/resources/baseline.properties`), así que un cambio de línea base llega al destino.
3. **Los puertos son configuración del destino, no una adivinanza.** El Compose lee
   `GENERATED_HTTP_HOST_PORT` y `GENERATED_POSTGRES_HOST_PORT`, con el primer par del convenio de
   [02-ejecucion-local.md](02-ejecucion-local.md) como default. El par vive en el `.env` del destino, así que
   nada inventa un puerto a partir de un identificador.
4. **No se emite ningún secreto.** El archivo de ejemplo documenta las variables y lleva un valor que
   claramente no es usable. El `.gitignore` emitido excluye `.env`, así que un valor real nunca llega al control
   de versiones.
5. **El archivo Compose es YAML válido.** La suite lo **parsea con un parser YAML real**, no lo compara contra
   un string. La sección 6 explica por qué ese guardián existe.

El orden documentado es deliberado: el `Dockerfile` copia `target/*.jar`, así que primero se construye el jar.

```sh
mvn -o -B package                     # desde la caché local de Maven, sin red
cp .env.example .env                  # y luego se completa cada valor
docker compose up -d --build
curl http://localhost:19001/actuator/health
```

## 3. La corrida ejecutada

Entorno: Windows 11, Docker Desktop 29.8.0, Java 21, Maven 3.9.15, imágenes de PostgreSQL ya locales,
`eclipse-temurin:21-jre-alpine` descargada una vez para esta corrida (la re-descarga declarada). El diagramador
se levantó con `tools/acceptance/acceptance.py --keep-running` sobre `postgres:16`, que es la variante Debian.

El modelo se creó por la API pública de comandos: dos clases, tres atributos (`String`, `String` con default,
`BigDecimal`), una operación que devuelve `String`, y una asociación cuyo extremo muchos es navegable, de modo
que el `Order` generado es dueño de la clave foránea a `Customer`.

### 3.1 Generación

| Destino | Clave | Base | `artifactSetHash` | `artifactZipHash` | Bytes del ZIP |
| --- | --- | --- | --- | --- | --- |
| Reporting service | `reporting-service` | `reporting_db` | `de111ac47713e1ae6c29a6facf4c125adb86501bcd418eb5933215418a42b054` | `35d6a7a9f2ae306ef8a399f8107e50a13f11eaef986bf588215cdcdfb2e5e8cb` | 42086 |
| Billing service | `billing-service` | `billing_db` | `87119262b6eec2abd123cc686762360d67e874cdb41d3eab3be5b3f0ce2405d4` | `93e95e7579117385598843ee97da5fe19291c45c04183dc6cb5d746f483f70be` | 41900 |

Los dos archivos se bajaron con `content-type: application/zip`, empezando con la firma `PK`, y su SHA-256 es
**igual** al `artifactZipHash` que la corrida persistió. **Dos destinos de un modelo producen dos conjuntos y
dos archivos distintos**: eso es D14 observado, no afirmado. Cada archivo trae 24 artefactos y 37 874 bytes de
contenido.

### 3.2 Despliegue, destino por destino

Cada destino se descomprimió en su propio directorio, se empaquetó sin red, recibió su propio `.env` y se
levantó con su propio proyecto Compose:

| Verificación | Reporting service | Billing service |
| --- | --- | --- |
| `mvn -o -B package` | exit 0, `generated-reporting-service-0.1.0.jar` | exit 0, `generated-billing-service-0.1.0.jar` |
| `docker compose config` | exit 0, 1627 bytes renderizados | exit 0, 1593 bytes renderizados |
| `docker compose up -d --build` | exit 0, los dos contenedores sanos | exit 0, los dos contenedores sanos |
| `GET /actuator/health` | `{"status":"UP"}` | `{"status":"UP"}` |
| Base propia | `reporting_db`, 3 tablas, 1 customer, 1 order | `billing_db`, 3 tablas, 1 customer, 1 order |
| Volumen propio | `generated-reporting-service_reporting-service-postgres-data` | `generated-billing-service_billing-service-postgres-data` |

Flyway aplicó la migración generada contra la base propia del destino dentro del contenedor
(`Migrating schema "public" to version "1 - init"`). El CRUD de los dos recursos generados respondió `201`/`200`,
y la resolución de la clave foránea generada se sostuvo:

```text
POST /api/customer            -> 201 {"id":"bbd92548-…","email":"borges@example.org","displayName":"Borges"}
POST /api/order               -> 201 {"id":"827d8300-…","total":42.5,"customerId":"bbd92548-…"}
GET  /api/order/827d8300-…    -> 200 {"id":"827d8300-…","total":42.50,"customerId":"bbd92548-…"}
POST /api/order (padre inexistente) -> 400 {"code":"INVALID_REFERENCE", …}
GET  /api/order/22222222-…    -> 404 {"code":"NOT_FOUND", …}
```

Los dos destinos estuvieron **arriba al mismo tiempo**, cada uno contestando en su puerto, cada uno con su base y
su volumen, y las dos filas de las dos bases tienen identidades distintas. Ese es el criterio «dos destinos
distintos del mismo modelo producen dos backends independientes, aun si su contenido es igual».

## 4. La preparación AWS/Floci que sí se ejecutó

**Ejecutada sólo contra el emulador local.** Floci v2.1.0 corre en `127.0.0.1:4566` y lo usa otro proyecto: esta
corrida lo **consumió** y no administró nada más allá de los recursos que creó para sí misma. No se usó ninguna
credencial real de AWS, no se creó nada fuera del emulador y no hubo ningún costo.

| Paso | Forma del comando | Observado |
| --- | --- | --- |
| Identidad | `aws --endpoint-url http://127.0.0.1:4566 sts get-caller-identity` | `Account 000000000000`, `arn:aws:iam::000000000000:root` |
| Almacén de artefactos, sólo lectura | `aws … s3api list-buckets` | Un bucket preexistente, `generation-artifacts`, de otro proyecto. Listado y **no tocado**. |
| Configuración | `aws … ssm put-parameter /generated-<slug>/deployment` | Escrito y releído idéntico: 267 y 261 bytes, **sin ningún campo secreto**. |
| Registro | `aws … ecr create-repository --repository-name generated-<slug>` | Los dos repositorios creados; `repositoryUri` `000000000000.dkr.ecr.us-east-1.localhost:4566/generated-<slug>`. |
| Imagen | `docker tag` de la imagen construida contra el URI del repositorio | Las dos imágenes etiquetadas localmente bajo el URI del emulador. |
| Subida de la imagen | `docker push <uri>:0.1.0` | **Rechazada.** Ver el límite declarado. |

Los nombres de recurso se derivan, nunca se inventan: el repositorio del registro y la ruta del parámetro SSM
usan el nombre del artefacto Maven emitido, `generated-<slug>`, que el POM ya lleva.

Contra AWS real, cada paso de arriba difiere en **un solo flag**: desaparece
`--endpoint-url http://127.0.0.1:4566`. Esa sustitución es toda la distancia entre esta preparación y un
despliegue real.

### 4.1 Límites declarados del camino AWS

- **La subida de la imagen no se completó.** El emulador contesta la API de ECR, pero el camino de registro
  Docker falla: con el host del URI devuelve `no such host`, y contra `127.0.0.1:4566` responde
  `400 Bad Request` en la subida de blobs. La imagen se construye y se etiqueta localmente; la subida queda
  **documentada y no se reclama como ejecutada**. Un registro real es donde se completa ese paso.
- **No se aprovisionó ningún recurso de ECS, EC2, RDS ni IAM.** Esta corrida preparó la configuración y el
  camino del registro, nada más. La base gestionada y la definición de tarea quedan fuera del incremento.
- **No se afirma ningún costo.** El emulador no tiene recurso facturable, y este documento no afirma nada sobre
  el costo de un despliegue real.

## 5. Vuelta a la línea base

Todo lo que esta corrida creó se eliminó y el resultado se verificó:

| Recurso | Antes | Después |
| --- | --- | --- |
| Contenedores | 36 | 36 |
| Volúmenes | 68 | 68 |
| Parámetros del emulador | ninguno | ninguno |
| Repositorios del emulador | ninguno | ninguno |
| Buckets del emulador | `generation-artifacts` | `generation-artifacts` (intacto) |

Los dos stacks de destino se eliminaron con `docker compose down -v`, los recursos del emulador con
`ecr delete-repository` y `ssm delete-parameter`, el diagramador con el `--stop` del propio arnés de aceptación,
y cuatro volúmenes anónimos huérfanos —dejados por los contenedores PostgreSQL del arnés y por un contenedor de
prueba de integración— se identificaron por fecha de creación y se borraron **después de comprobar que ningún
contenedor los referenciaba**.

## 6. Dos defectos que esta corrida encontró

Los dos aparecieron **ejecutando**, no leyendo, y los dos están corregidos. Cada corrección lleva una prueba que
falla sin ella, verificada reponiendo el defecto, observando el fallo y restaurando la corrección desde una copia
verificada por hash.

**`RUN_NOT_COMPLETE` para una corrida que había terminado bien.** La descarga respondía
`{"code":"RUN_NOT_COMPLETE","message":"… its persisted artifact manifest is incomplete."}` aunque la corrida
estaba en `SUCCEEDED` con los dos hashes persistidos. Causa raíz: el manifiesto se comparaba contra las filas
persistidas **por posición**, y las filas vuelven ordenadas por la base. La colación de una base no es el orden
canónico de paths del artefacto: bajo `en_US.utf8`, PostgreSQL devuelve `docker-compose.yml`, `Dockerfile`,
`.env.example`, `.gitignore`, …, mientras que el manifiesto del emisor está en el orden de paths de Java
(`.env.example`, `.gitignore`, `Dockerfile`, …). La suite de integración corre sobre `postgres:16-alpine`, cuya
colación se comporta como `C`, así que la comparación posicional coincidía ahí y el archivo era inalcanzable
**sólo en el camino de despliegue**. La comparación ahora es una igualdad de conjuntos por clave de path, que es
lo que el chequeo siempre quiso decir.

**Un archivo Compose que no era YAML.** El primer `docker-compose.yml` generado escribía
`test: ["CMD-SHELL", "pg_isready -U "$${POSTGRES_USER}" -d "$${POSTGRES_DB}""]`: comillas dobles internas sin
escapar, así que `docker compose config` lo rechazaba con `did not find expected ',' or ']'`. La suite ahora
parsea el Compose emitido con un parser YAML real y afirma los comandos de los healthchecks, así que esta clase
de defecto falla en la suite y no en un despliegue.

## 7. Lo que no se verificó

- **La subida de la imagen a un registro**, por lo de §4.1.
- **Cualquier despliegue fuera de esta máquina**: ni host en la nube, ni base gestionada, ni orquestador.
- **Que un tercero repita la corrida a mano.** La evidencia de T13 es una transcripción de comandos y su salida;
  el arnés que la hace repetible sin manos humanas es T10 y su transcripción está en
  `docs/capturas/incremento-2/14-arnes-aceptacion-generacion.txt`.
- **La postura de seguridad del backend generado.** `spring-boot-starter-security` no se emite para la versión
  3.4.5, así que la API generada no tiene autenticación ni autorización y no debe exponerse a redes no
  confiables. Es un límite heredado de T6, no un resultado de esta corrida.

## 8. Un hallazgo sobre el vocabulario de primitivos

El modelo de aceptación del Incremento 1 contiene una operación que devuelve `boolean`, y la generación lo
rechazó con un diagnóstico bloqueante que **nombra la característica**:

```text
code=UNRESOLVABLE_TYPE feature=returnType severity=ERROR blocking=True
message=Primitive type 'boolean' has no mapping in profile option 'primitiveTypeMappings.boolean'.
```

El vocabulario del perfil es PascalCase (`Boolean`, `String`, `Integer`, `Long`, `Double`, `BigDecimal`,
`LocalDate`, `LocalDateTime`, `UUID`), y un modelo puede llevar cualquier nombre de primitivo no vacío. Rechazar
uno sin mapeo **nombrando la característica** es el comportamiento diseñado; lo que esta corrida agrega es el
caso concreto, y el hecho de que el modelo de demostración del Incremento 1 no sea generable por esa razón. Por
eso la corrida de T13 usó un modelo escrito para el subconjunto declarado del incremento.

## 9. Numeración del documento

El plan de tareas nombra este documento `02-despliegue-propio.md`. `02-ejecucion-local.md` ya tiene ese número y
está referenciado desde el archivo Compose del repositorio, así que este documento es `03-despliegue-propio.md`
y la desviación se declara acá en lugar de resolverse renombrando un archivo que ya está referenciado.
