# Diagramador colaborativo de clases UML 2.5

Proyecto del **Primer Parcial** de Ingeniería de Software I. El producto es una herramienta CASE
colaborativa que permite crear y modificar diagramas de clases UML 2.5 de forma manual y asistida,
mantener un modelo canónico versionado, interoperar con Enterprise Architect mediante XMI y generar un
backend Spring Boot conectado a PostgreSQL.

## Idioma de la interfaz

Las interfaces web y móvil muestran en español sus pantallas, formularios, ayudas, etiquetas de accesibilidad y mensajes propios. Los nombres de usuario, el contenido de los proyectos y conversaciones, los identificadores de API y los protocolos de los modelos no se traducen.

## Estado actual

El repositorio **ya contiene código de producto**. Los Incrementos 1 y 2 están implementados y verificados: se
puede crear un proyecto, dibujar un diagrama de clases en un lienzo, editarlo y recargar sin perder ni el modelo
ni su disposición visual, y **generar desde el editor un backend Spring Boot** que se descarga como ZIP, compila
sin red y se levanta con su propia base PostgreSQL.

| Fase | Estado |
| --- | --- |
| Documentación de requisitos, análisis y diseño | Heredada y disponible en `docs/` |
| Incremento 0 — contrato y pruebas de riesgo | **Cerrado** (`odd/tasks/incremento-0-contrato-y-riesgos.md`) |
| Incremento 1 — modelo y editor visual | **Cerrado** — [acta](docs/puds/cierre/03-cierre-incremento-1.md). Los tres defectos de presentación que su §3 nombra están **corregidos** |
| Incremento 2 — generación, exportación y despliegue | **Cerrado, con límites declarados** — [acta](docs/puds/cierre/04-cierre-incremento-2.md) |
| Unidad de despliegue desde el editor | **Cerrada** — [documento](docs/puds/generacion/04-despliegue-desde-el-editor.md): botón, operador en la máquina y dirección para el teléfono |
| Incremento 3 — colaboración en vivo e intercambio XMI con EA | **Cerrado** — [acta](docs/puds/cierre/05-cierre-incremento-3.md) |
| Incremento 4 — IA textual | **Cerrado**, con una quema de revisión pendiente por infraestructura — [acta](docs/puds/cierre/06-cierre-incremento-4.md) |
| Guía de la app — tour y chat de ayuda | **Cerrada** — [acta](docs/puds/cierre/07-cierre-guia-asistente-app.md) |
| IA colaborativa, foto a diagrama y XMI asistido | **Integrada en `main`** (PR #35): la IA dice a quién atiende, propone cambios desde una foto adjunta y ayuda a resolver los problemas de una importación XMI antes de importar |
| Notación UML 2.5 completa del editor | **Integrada en `main`** (PR #36): visibilidad `+ - # ~`, multiplicidad con rango, estereotipos de lista fija, notas, atributos estáticos y derivados, `{ordered}` y `{unique}`; ver el manual de uso (`docs/3`, secciones 3 y 4) |

### Qué está verificado hoy

| Superficie | Evidencia |
| --- | --- |
| Backend | Suite completa del 2026-11-11 sobre la rama de la notación: **882 pruebas**, 2 omitidas, 0 fallos tras corregir la única falla que apareció (el lector XMI contaba la palabra clave de EA de una enumeración como estereotipo perdido). Las correcciones posteriores se verificaron solo con clases focalizadas. Antes: `mvn -o -B verify` = 387 pruebas, sin red |
| Validación semántica UML 2.5 | `GET /projects/{id}/model/validation` = 6 reglas a nivel modelo (ciclos de generalización/realización, auto-generalización, validez de ends, multiplicidad RANGE incompleta, nombres de miembros únicos, tipos resolvibles); 4 pruebas de integración con Postgres; suite completa 387/0 en `feat/validacion-uml-semantica` |
| Arnés del corte visible | `python tools/acceptance/acceptance.py` = **33 pasos OK**, 0 fallos, con teardown verificado |
| Arnés de generación | `python tools/generation-acceptance/acceptance_generation.py` = **69 aserciones**, 0 fallos, con teardown verificado |
| Arnés de despliegue | `python tools/generation-acceptance/acceptance_deployment.py` = **32 aserciones**, 0 fallos: ordena por la API, deja que el operador despliegue, comprueba `health` **desde la dirección de red**, hace el CRUD contra ese backend, ordena detener y verifica que no quedó nada |
| Editor web | Suite completa del 2026-11-11 (`npx vitest run`): **917 pruebas** pasando, 4 omitidas, 88 archivos; `tsc --noEmit` limpio. Antes: `npm test` = 371 pruebas pasando, `npm run build` en verde |
| Menú contextual + multiplicidad por extremo | Click derecho en clase/relación/lienzo abre menú estilo EA (items sin soporte deshabilitados con razón); submenú Multiplicidad con `1`, `0..1`, `1..*`, `0..*` y dispatch `relationship.update` con payload completo byte-preservado; el extremo (A/B) se preselecciona por etiqueta clickeable o por cercanía al click en la línea. Cobertura: 73 tests de lógica pura + markup (convención sin jsdom) |
| Voz para el asistente de IA | Botón mic en el panel del asistente (Web Speech API, `es-AR`): dictado en vivo con interim como hint `aria-live`, transcript final anexado al texto sin auto-envío, botón oculto en navegadores sin soporte. Cobertura: máquina de estados pura + hook + markup; `npm test` = **438 passed / 4 skipped**, lint, tsc y `npm run build` en verde |
| Corte visible del Incremento 1 | Crear proyecto, dos clases, atributo, operación, relación, mover, recargar y encontrar lo mismo **con la misma disposición** |
| Corte visible del Incremento 2 | Elegir destino, generar, ver el estado y los diagnósticos, descargar el ZIP, compilarlo offline, levantarlo con su base, `health` `UP` y CRUD con la foránea resuelta; **dos destinos a la vez sin pisarse** |
| Determinismo | Dos generaciones del mismo estado dan el mismo `artifactSetHash`; dos destinos dan dos archivos distintos |
| Regla de presentación | El HTML renderizado no contiene ningún UUID ni identificador técnico; comprobado sobre `web/src/**` y sobre el render vivo |
| Corte visible del despliegue | Apretar **Deploy backend** en el editor deja el backend corriendo con **su propio PostgreSQL**, y el panel muestra **la dirección que otro dispositivo de la misma red puede usar** |

**Evidencia visual:** las capturas del lienzo están en `docs/capturas/incremento-1/` y las del Incremento 2 en
`docs/capturas/incremento-2/`, junto con las transcripciones de la corrida de Postman y del arnés de generación.
La vista compacta del editor (barra de opciones superior con las secciones secundarias en diálogos y el asistente
de IA como botón flotante) está medida y capturada en `docs/capturas/vista-compacta/`; el plan y las mediciones
están en [`odd/tasks/vista-compacta-editor.md`](odd/tasks/vista-compacta-editor.md), y el script que las produce
es `web/scripts/verify-vista-compacta.mjs`.

**Ojo con los límites:** el Incremento 2 cierra con **siete puntos abiertos** y con **lo que no se verificó**
declarado en el [acta](docs/puds/cierre/04-cierre-incremento-2.md) §5 y §7. Los tres que más importan: la subida
de la imagen no la completa el **emulador** de AWS, aunque contra un **registro real** está verificada de ida y
vuelta; no se verificó ningún despliegue fuera de esta máquina; y el backend generado **no tiene capa de
seguridad** porque `spring-boot-starter-security` no está disponible para la versión 3.4.5 en la caché local.

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

### Generación y despliegue, sin interfaz

```bash
cd backend && mvn -o -B package                      # jar del diagramador
python tools/acceptance/acceptance.py --keep-running # imprime la URL base del diagramador
python tools/generation-acceptance/acceptance_generation.py --base-url <esa URL>
```

El segundo arnés crea un modelo por HTTP, genera **dos destinos independientes**, baja cada ZIP, comprueba que su
SHA-256 sea el `artifactZipHash` que la corrida persistió, compila cada proyecto **sin red**, despliega cada
destino con su propio proyecto Compose y su propia PostgreSQL, espera `health` `UP`, hace el CRUD de los dos
recursos con la clave foránea resuelta, y limpia **verificando** que el conteo de contenedores volvió a su valor
inicial. Con `--keep-running` deja los destinos arriba e imprime su comando `--stop`; así la colección de Postman
de `tools/postman/` corre contra un backend ya desplegado.

### Generar y levantar un backend desde el editor

```bash
python tools/acceptance/acceptance.py --keep-running   # URL base del diagramador
cd web && npm install && npm run dev                   # http://localhost:5173
```

En el editor, el panel **Generation** lista los destinos del modelo, permite crear uno nuevo y lanza la
generación con el perfil publicado. Cuando la corrida termina, el botón de descarga baja el ZIP. El ZIP trae su
propio `docker-compose.yml` y un `.env.example` sin secretos, así que el backend generado se levanta solo:

```bash
unzip generated-<destino>.zip -d mi-backend && cd mi-backend
mvn -o -B package            # desde la caché local, sin red; deja el jar en target/
cp .env.example .env         # y completar los valores: base, usuario, clave y el par de puertos
docker compose up -d --build # el Dockerfile copia target/*.jar
curl http://localhost:19001/actuator/health
```

Cada destino es su propio proyecto Compose con su propio volumen, así que **dos destinos del mismo modelo corren
a la vez** mientras su par de puertos difiera. El par sale del convenio `1900N`/`2543N`: `19001`/`25433` para el
primero, `19002`/`25434` para el segundo.

### Desplegar desde el editor, y la dirección para el teléfono

```bash
python tools/acceptance/acceptance.py --keep-running          # URL base del diagramador
python tools/deploy-operator/deploy_operator.py --api <esa URL>   # el operador, en esta máquina
cd web && npm run dev                                          # el editor
```

El operador es un proceso que corre **en tu máquina** y es el que hace el trabajo: el diagramador le ordena y él
despliega. Existe para que el diagramador **no** necesite acceso a Docker. Dejalo en su modo servicio, o corrélo
con `--once` para que atienda lo que haya pendiente y salga.

En el editor, con una corrida terminada, el botón **Deploy backend** ordena el despliegue. Cuando el operador
termina, el panel muestra **Running** y **la dirección que otro dispositivo de la misma red puede usar**, por
ejemplo:

```text
http://192.168.0.11:19001
```

Esa dirección sirve para el teléfono: el backend generado queda publicado en todas las interfaces y **su base de
datos sólo en `127.0.0.1`**. Desde una terminal del teléfono se comprueba con
`curl http://192.168.0.11:19001/actuator/health`.

**Abrí el editor por la dirección de red de tu máquina y no por `localhost`:** la dirección se arma con el host
que ya usó tu navegador, así que si abrís el editor en `localhost` el panel te va a mostrar una dirección que sólo
funciona en esa máquina y te lo va a decir.

Dos cosas que conviene saber antes de usarlo:

- **El backend generado no tiene autenticación.** Cualquiera en la misma red que sepa la dirección puede leer y
escribir en su base. El panel lo avisa.
- **Cortar el operador baja el stack antes de salir** y reporta el despliegue como fallado. No queda nada huérfano.

Si preferís desplegar sin el operador —por ejemplo en otra máquina—, el ZIP sigue levantándose a mano como en la
sección de arriba: las dos vías son válidas y la segunda no necesita nada de esto.

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
backend/     Servicio Spring Boot (Java 21) — modelo canónico, comandos, persistencia, API REST y generación
web/         Editor web (React + TypeScript + Vite) con lienzo UML sobre React Flow y panel de generación
mobile/      Cliente móvil (Flutter + Dart) — todavía no creado: es un incremento posterior
spikes/      Pruebas de riesgo desechables del Incremento 0; no es código de producto
tools/       Arneses y evidencia: aceptación del corte visible, generación y despliegue, Postman,
             pruebas de contrato, inventario XMI y bench de IA
docs/        Requisitos, análisis, diseño, contratos, generación, cierre, fixtures, diagramas y capturas
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
[Incremento 2 — generación, exportación y despliegue](odd/tasks/incremento-2-generacion-y-exportacion.md), ya
implementado y cerrado por su [acta](docs/puds/cierre/04-cierre-incremento-2.md). El anterior,
[Incremento 1 — modelo y editor visual](odd/tasks/incremento-1-modelo-y-editor.md), reemplazó la etapa de
«interfaz mínima» del plan original por un lienzo tipo draw.io y dejó registradas catorce decisiones de diseño
con la alternativa que cada una descarta. El siguiente es el de colaboración.

## Entorno medido

Java 21.0.10 · Maven 3.9.15 · Node 22.23.0 · npm 10.9.8 · Dart 3.11.5 con Flutter · Docker 29.8.0 con
Compose v5.5.1 · Python 3.14.6 · git 2.54.0. No hay cliente `psql` instalado: PostgreSQL se ejecuta por
contenedor.
