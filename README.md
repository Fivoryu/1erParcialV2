# Diagramador colaborativo de clases UML 2.5

Proyecto del **Primer Parcial** de Ingeniería de Software I. El producto es una herramienta CASE
colaborativa que permite crear y modificar diagramas de clases UML 2.5 de forma manual y asistida,
mantener un modelo canónico versionado, interoperar con Enterprise Architect mediante XMI y generar un
backend Spring Boot conectado a PostgreSQL.

## Estado actual

El repositorio **ya contiene código de producto**. El Incremento 1 está implementado y verificado: se puede
crear un proyecto, dibujar un diagrama de clases en un lienzo, editarlo y recargar sin perder ni el modelo ni
su disposición visual.

| Fase | Estado |
| --- | --- |
| Documentación de requisitos, análisis y diseño | Heredada y disponible en `docs/` |
| Incremento 0 — contrato y pruebas de riesgo | **Cerrado** (`odd/tasks/incremento-0-contrato-y-riesgos.md`) |
| Incremento 1 — modelo y editor visual | **Cerrado** — [acta](docs/puds/cierre/03-cierre-incremento-1.md), con dos defectos visuales abiertos |
| Incrementos 2 y siguientes (generación, colaboración, XMI, IA, móvil) | Pendientes |

### Qué está verificado hoy

| Superficie | Evidencia |
| --- | --- |
| Backend | `mvn -o -B verify` = **82 pruebas**, 0 fallos, sin red |
| Arnés de aceptación | `python tools/acceptance/acceptance.py` = **34 pasos OK**, 0 fallos, con teardown verificado |
| Editor web | `npm run build` y `npm test` = **24 pruebas**, 0 saltadas, incluidos los dos tests vivos contra el backend real |
| Corte visible | Crear proyecto, dos clases, atributo, operación, relación, mover, recargar y encontrar lo mismo **con la misma disposición** |
| Regla de presentación | El HTML renderizado no contiene ningún UUID ni identificador técnico; probado sobre datos vivos |

**Evidencia visual:** la captura del lienzo está en `docs/capturas/incremento-1/` y se reproduce con
`python tools/acceptance/seed_demo_diagram.py` más un `npm run build` apuntando al backend. La regla de no-IDs se
confirma ahí a ojo: no hay un solo UUID en pantalla.

**Dos defectos de presentación quedan abiertos y nombrados:** las etiquetas de los extremos de una asociación se
superponen a las cajas cuando el tramo es corto, y el triángulo de `INTERFACE_REALIZATION` se dibuja despegado de
su línea. Están en el [acta de cierre](docs/puds/cierre/03-cierre-incremento-1.md) §3 con su causa, y son lo
primero a arreglar antes de mostrar el producto.

> **Importante:** `SOFTWARE.md` describe el **intento anterior** del mismo producto. Se conserva como evidencia y
> como referencia de requisitos, pero **no describe el código de este repositorio**. Cualquier afirmación de
> `SOFTWARE.md` debe volver a medirse sobre esta implementación antes de darla por buena.

## Cómo ejecutarlo

### El recorrido completo, sin interfaz

```bash
cd backend && mvn -o -B package     # construye el jar, sin red
python tools/acceptance/acceptance.py
```

El arnés levanta PostgreSQL y el backend, ejecuta el corte visible por HTTP, compara el modelo releído campo a
campo y termina verificando que no quedó ni un contenedor ni un proceso vivo. Sale con código distinto de cero
en el primer fallo.

### Desarrollo del editor

```bash
python tools/acceptance/acceptance.py --keep-running   # imprime la URL base del backend
cd web && npm install && npm run dev                   # http://localhost:5173
```

Cuando termines, el propio arnés imprime el comando `--stop` con el identificador de esa corrida. Usalo: dejarlo
vivo consume contenedores y memoria de forma silenciosa.

### Restricciones de construcción sin red

El backend se construye **sin red** y eso es una restricción, no una comodidad: Spring Boot está fijado en
**3.4.5** y no 3.5.0 porque 3.4.5 resuelve Flyway 10.20.1, Hibernate 6.6.13 y el driver 42.7.5 desde la caché
local, mientras que 3.5.0 pide una versión de Flyway que no está. Flyway corre por autoconfiguración de Spring
y no por plugin, porque `flyway-maven-plugin` no está en la caché. Las pruebas de persistencia usan Testcontainers
contra una imagen de PostgreSQL presente localmente. H2 está descartado: el esquema usa `CREATE TYPE … AS ENUM`
y `gen_random_uuid()`.

## Estructura del repositorio

Este es un **monorepo**. Todo el código fuente se versiona dentro de este mismo repositorio.

```text
backend/     Servicio Spring Boot (Java 21) — modelo canónico, comandos, persistencia, API REST
web/         Editor web (React + TypeScript + Vite) con lienzo UML sobre React Flow
mobile/      Cliente móvil (Flutter + Dart) — Incremento posterior
spikes/      Pruebas de riesgo desechables del Incremento 0; no es código de producto
tools/       Arnés de verificación y evidencia (pruebas de contrato, inventario XMI, aceptación, bench de IA)
docs/        Requisitos, análisis, diseño, contratos, fixtures, diagramas y capturas
odd/         Planificación de tareas del repositorio
```

Las carpetas de código se crean cuando su tarea las necesita. El defecto del intento anterior —`backend/`,
`web/` y `mobile/` como repositorios git anidados— **no se repite**: si algún día un submódulo se justifica,
entra por `git submodule` y queda declarado en `.gitmodules`.

## Frontera de trabajo

La implementación se deriva de los requisitos documentados en `docs/`, no de código previo. El intento
anterior no forma parte de las fuentes de este repositorio:

- **Permitido:** todo lo que está dentro de este repositorio, incluida la documentación heredada.
- **Prohibido:** copiar, portar o leer la implementación anterior.

Los archivos de `docs/xmi-backup/` son salida de ese intento anterior y arrastran sus pérdidas conocidas: se
leen como síntoma, nunca como contrato.

## Convenciones

| Tema | Regla |
| --- | --- |
| Documentación y entregables | Español |
| Código, identificadores, comentarios y mensajes de commit | Inglés |
| Commits | Conventional Commits, sin atribución de IA ni co-autoría |
| Finales de línea | LF en el repositorio; los fixtures de EA quedan byte-exactos (ver `.gitattributes`) |

## Autoridad de requisitos

| Documento | Qué define |
| --- | --- |
| [`docs/analisis-enunciado-parcial-1.md`](docs/analisis-enunciado-parcial-1.md) | Alcance, actores, RF-01–RF-15, RNF-01–RNF-11, escenario de aceptación, riesgos |
| [`docs/puds/01-captura-de-requisitos.md`](docs/puds/01-captura-de-requisitos.md) | Casos de uso, ciclos C1–C3, trazabilidad, preguntas abiertas |
| [`docs/puds/02-analisis.md`](docs/puds/02-analisis.md) | Paquetes y realizaciones conceptuales |
| [`docs/puds/03-diseno.md`](docs/puds/03-diseno.md) | Línea base de diseño D0–D6 y su [acta de cierre](docs/puds/cierre/01-cierre-diseno.md) |
| [`docs/puds/contratos/`](docs/puds/contratos/) | Contratos transversales entre componentes |
| [`docs/Parcial_Software_I_Esqueleto_Completo.docx`](docs/) | Estructura obligatoria del documento entregable |

## Planificación

El trabajo del repositorio vive en `odd/tasks/`. El incremento vigente es
[Incremento 1 — modelo y editor visual](odd/tasks/incremento-1-modelo-y-editor.md), ya implementado, que reemplazó
la etapa de «interfaz mínima» del plan original por un lienzo tipo draw.io y dejó registradas catorce decisiones
de diseño con la alternativa que cada una descarta.

## Entorno medido

Java 21.0.10 · Maven 3.9.15 · Node 22.23.0 · npm 10.9.8 · Dart 3.11.5 con Flutter · Docker 29.8.0 con
Compose v5.5.1 · Python 3.14.6 · git 2.54.0. No hay cliente `psql` instalado: PostgreSQL se ejecuta por
contenedor.
