# Diagramador colaborativo de clases UML 2.5

Proyecto del **Primer Parcial** de Ingeniería de Software I. El producto es una herramienta CASE
colaborativa que permite crear y modificar diagramas de clases UML 2.5 de forma manual y asistida,
mantener un modelo canónico versionado, interoperar con Enterprise Architect mediante XMI y generar un
backend Spring Boot conectado a PostgreSQL.

## Estado actual

El proyecto se está construyendo **desde cero**. Hoy el repositorio contiene documentación y planificación;
todavía **no contiene código de producto**.

| Fase | Estado |
| --- | --- |
| Documentación de requisitos, análisis y diseño | Heredada y disponible en `docs/` |
| Incremento 0 — contrato y pruebas de riesgo | En curso (`odd/tasks/incremento-0-contrato-y-riesgos.md`) |
| Implementación por ciclos C1 → C2 → C3 | Pendiente |

> **Importante:** `SOFTWARE.md` describe el **intento anterior** del mismo producto: sus números medidos, su
> inventario de API y sus límites conocidos. Se conserva como evidencia y como referencia de requisitos, pero
> **no describe el código de este repositorio**, porque ese código todavía no existe. Cualquier afirmación de
> `SOFTWARE.md` debe volver a medirse sobre la implementación nueva antes de darla por buena.

## Estructura del repositorio

Este es un **monorepo**. Todo el código fuente se versiona dentro de este mismo repositorio.

```text
backend/     Servicio Spring Boot (Java 21) — modelo canónico, comandos, persistencia, XMI, generación
web/         Editor web (React + TypeScript + Vite)
mobile/      Cliente móvil (Flutter + Dart)
tools/       Arnés de verificación y evidencia (inventarios, E2E, roundtrips)
spikes/      Pruebas de riesgo desechables del Incremento 0; no es código de producto
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
[Incremento 0 — contrato y pruebas de riesgo](odd/tasks/incremento-0-contrato-y-riesgos.md), que cierra los
cuatro riesgos tempranos antes de comprometer arquitectura: línea base UML, dialecto XMI de Enterprise
Architect, transformación UML→Spring Boot y viabilidad de IA local.

## Entorno medido

Java 21.0.10 · Maven 3.9.15 · Node 22.23.0 · npm 10.9.8 · Dart 3.11.5 con Flutter · Docker 29.8.0 con
Compose v5.5.1 · Python 3.14.6 · git 2.54.0. No hay cliente `psql` instalado: PostgreSQL se ejecuta por
contenedor.
