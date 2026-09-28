# Spike — UML a Spring Boot de cuatro capas

Prueba de riesgo del **Incremento 0**. No es código de producto: existe para responder una sola pregunta
antes de comprometer el generador real.

> **Pregunta:** ¿se puede emitir, de forma **determinista**, un proyecto Spring Boot de cuatro capas que
> **compile**, **arranque** y **atienda un CRUD** contra PostgreSQL, partiendo de un modelo UML validado?

Si la respuesta es no, el requisito más pesado del parcial (RF-10, RF-11, RNF-04) cambia de forma **antes** de
escribir el generador definitivo. Si es sí, queda el conjunto de reglas de emisión probado.

## Resultado

**Sí.** La corrida de extremo a extremo pasa los 13 pasos: emisión determinista, compilación sin red, arranque
con salud `UP`, migración aplicada por Flyway y CRUD con la clave foránea resuelta. La evidencia completa está
en [`REPORT.md`](REPORT.md), y salió de correr el arnés, no de escribirlo.

```bash
python -m pytest spikes/uml2spring -q     # reglas, determinismo y falla cerrada
python spikes/uml2spring/run_spike.py    # los 13 pasos de extremo a extremo
```

El spike encontró tres defectos reales que la revisión de escritorio no ve, y están documentados en el reporte
con su causa y su corrección.

## Frontera

- La entrada es el **modelo canónico**, no un archivo XMI. El dialecto de EA se resuelve al importar, en el
  adaptador; este spike consume el modelo ya normalizado.
- El generador definitivo vivirá en el backend. **Este spike está escrito en Python a propósito**: lo que se
  prueba aquí son las *reglas de emisión* y el determinismo del artefacto, no el runtime del generador.
  Portar reglas probadas a Java es un riesgo aparte y mucho menor.
- Fuera de alcance: plantillas definitivas, perfiles de generación, targets, empaquetado de artefactos,
  descarga del ZIP y verificación (CU-18…CU-20).

## Contrato de entrada

`model/biblioteca.json` — dos clases con atributos y **una asociación** con nombre, multiplicidad,
navegabilidad e identificadores estables. Es el mínimo que ejercita las decisiones que importan: qué capa
posee la relación y cómo se traduce una multiplicidad a nulabilidad y colección.

Forma:

| Campo | Uso |
| --- | --- |
| `model.name` | Nombre del proyecto generado |
| `model.basePackage` | Paquete Java raíz |
| `model.classes[].id` | Identificador estable del clasificador |
| `model.classes[].name` | Nombre de la clase y de la tabla |
| `model.classes[].attributes[]` | `name`, `type`, `visibility`, `required` |
| `model.associations[].ends[]` | `classId`, `role`, `multiplicity`, `navigable` |

Multiplicidades admitidas: `EXACTLY_ONE`, `ZERO_OR_ONE`, `ONE_OR_MANY`, `ZERO_OR_MANY`. Son las cuatro
categorías que el modelo de datos puede guardar hoy; el resto queda fuera (GAP-05 de la línea base).

## Contrato de salida

El emisor escribe un proyecto Maven completo:

```text
pom.xml
src/main/java/<pkg>/<Nombre>Application.java
src/main/java/<pkg>/domain/<Clase>.java          entidades JPA
src/main/java/<pkg>/repository/<Clase>Repository.java
src/main/java/<pkg>/service/<Clase>Service.java
src/main/java/<pkg>/web/<Clase>Controller.java
src/main/resources/application.yml               PostgreSQL por variables de entorno
src/main/resources/db/migration/V1__init.sql     tablas, columnas y claves foráneas
```

Reglas de mapeo (de `docs/puds/contratos/01-contratos-transversales.md` §8.3):

- cada clase se convierte en entidad JPA con tabla en `snake_case`, más repositorio, servicio y controlador;
- cada atributo se convierte en columna, con `nullable` derivado de `required` y validación cuando el tipo la
  admita;
- una asociación se convierte en una relación JPA **con el lado propietario elegido por la navegabilidad y la
  multiplicidad**, no por el orden en que aparecen los extremos;
- la capa de migración declara las tablas y las claves foráneas explícitamente, sin depender del
  auto-generado de JPA.

## Reglas de determinismo

RNF-04 exige que el mismo modelo y el mismo perfil produzcan el mismo artefacto. El spike lo hace
**verificable**, no declarativo:

1. Los archivos se emiten en orden de ruta POSIX, siempre.
2. Todo archivo termina en `\n` y usa LF.
3. Ningún archivo contiene fecha, hora, ruta absoluta, nombre de usuario ni identificador aleatorio.
4. La huella del conjunto es `sha256` sobre la concatenación de, por cada archivo en orden:
   `ruta_relativa + "\n" + sha256(contenido) + "\n"`.
5. Dos corridas con el mismo modelo deben imprimir **la misma huella**, byte a byte.

## Evidencia que este spike debe producir

| Comprobación | Criterio |
| --- | --- |
| Determinismo | Dos corridas con el mismo modelo, misma huella |
| Compilación | `mvn -B -o package` en verde sobre el proyecto generado |
| Arranque | Aplicación arriba contra PostgreSQL en contenedor, `GET /actuator/health` = `UP` |
| CRUD | `POST` y `GET` sobre los dos recursos generados, con la clave foránea resuelta |
| Migración | Las tablas existen por Flyway, no por auto-generado |

## Decisiones y límites declarados

| Decisión | Por qué |
| --- | --- |
| **Spring Boot 3.4.5**, no 3.5.0 | 3.4.5 gestiona Flyway 10.20.1, Hibernate 6.6.13 y el driver 42.7.5, que ya están en la caché local. Con 3.5.0 el build pide Flyway 11.7.2 y necesita red. Un spike que depende de la red no es reproducible |
| Extremo a uno **EAGER** | Con `open-in-view: false`, uno perezoso serializado fuera de la transacción da HTTP 500. Se midió. El costo declarado es N+1 al listar; un generador definitivo devuelve DTO dentro de la transacción |
| El modelo se **valida y falla cerrado** | Ante una asociación que no es uno-a-muchos, un tipo desconocido o un extremo colgando de una clase inexistente, el emisor levanta un error claro en vez de elegir en silencio un mapeo equivocado |
| Rutas REST en **singular** (`/api/libro`) | Evita un pluralizador. No es una decisión de producto |
| Sin clases abstractas, enumeraciones ni herencia | El emisor las rechaza explícitamente: no se probaron, así que no se declaran soportadas |

## Cómo se ejecuta

```bash
python spikes/uml2spring/emit.py --model spikes/uml2spring/model/biblioteca.json --out spikes/uml2spring/build/generated
```

Los comandos exactos, el puerto y el contenedor de PostgreSQL los fija el arnés de este directorio, porque el
puerto 8080 de la máquina está ocupado por otro proceso.
