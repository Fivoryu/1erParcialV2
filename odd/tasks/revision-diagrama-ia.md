# Revisión de diagrama con validación UML y IA

> Feature: botón/sección **Review** que corre la validación determinista, muestra si el diagrama está listo para generar el backend, explica los hallazgos con la IA y permite proponer un plan de corrección con preview/confirmación. Fecha: 2026-10-18. Rama: `feat/guia-asistente-app`.

## Specs

### S1 — Validación UML determinista, visible y en lenguaje natural
El panel **Review** consume `GET /projects/{id}/model/validation` y muestra errores y warnings agrupados, con código, severidad, elemento (nombre de clase/atributo/operación/relación) y mensaje. Nunca muestra UUIDs ni identificadores técnicos. Cubre, entre otros, ciclos de generalización, auto-generalización, roles duplicados, multiplicidades inválidas/incompletas, miembros duplicados y tipos no resueltos.

### S2 — Readiness de generación sin crear una corrida
El panel muestra si el diagrama está listo para generar un backend **antes** de generar. Como `POST /generations` persiste una corrida (incluso fallida), se agrega un endpoint read-only de preflight: `GET /projects/{id}/generation-readiness?versionId=<uuid>` devuelve `{versionId, ready, diagnostics}` con los diagnósticos del mapper y del subconjunto V1. Usa la configuración V1 estática: **no** persiste perfil/template, **no** crea `generation_run` ni `verification_result` y **no** escribe configuración. Los chequeos específicos del destino (database engine, etc.) quedan en el panel Generation.

### S3 — Explicación y corrección con IA, explícitas
El panel ofrece **Explain with AI** y **Propose a fix** solo a `EDITOR`/`OWNER` (el plan/apply exige editor). Ambos abren el asistente existente con una consigna pre-cargada que contiene los hallazgos con nombres humanos; el usuario debe presionar **Consultar** y, para mutar, **Aplicar cambios**. Nunca se auto-envía, auto-aplica ni se muta desde el chat. Un `VIEWER` ve la validación y la readiness, pero no las acciones de IA; se le explica por qué.

### S6 — Sub-listas reales en markdown
Los ítems de lista indentados (2 o 4 espacios) se renderizan como sub-listas anidadas (`<li>` con `<ul>`/`<ol>` interno), no como ítems planos. Se preservan las listas planas, ordenadas y desordenadas, la seguridad (sin HTML crudo) y la ausencia de marcadores literales.

### S5 — Pulido visual del tour, la consigna y el chat
El tour de onboarding no tapa el botón flotante ni los paneles: se muestra por encima del FAB y se oculta mientras hay una sección o el asistente abiertos, conservando su progreso. La consigna del asistente usa un textarea para que los hallazgos largos se puedan leer y editar. El modal del chat usa el ancho disponible en dos columnas (conversación + contexto/memoria), con el medidor de contexto compacto, un empty state compuesto y sin el eyebrow duplicado de “Live collaboration”.

### S4 — Regresión y evidencia
Tests backend del preflight (listo, bloqueado, sin corrida persistida, permisos), tests web de lógica/markup (nombres, sin UUIDs, severidades, prompt, prefill, roles) y E2E real con un diagrama que tenga un ciclo de generalización y un bloqueante de generación. Capturas antes/después en `docs/capturas/ai-browser/`. Sin cambios de contrato no pedidos; sin commit salvo pedido.

## Tasks

| ID | Tarea | Specs | Ruta | Estado |
| --- | --- | --- | --- | --- |
| R1 | Endpoint read-only de readiness de generación + tests | S2 | backend worker | completada: 533 tests, 0 fallos, 1 skipped; sin corridas ni escrituras |
| R2 | Cliente API + sección Review + panel (validación y readiness) + tests | S1, S2 | web worker | completada: 613 tests, 4 skipped; nombres, sin UUIDs |
| R3 | Prefill del asistente para explicar/corregir + wiring de roles + tests | S3 | web worker | completada: prefill sin auto-envío; acciones solo editor/owner |
| R4 | E2E con ciclo/bloqueante, capturas y cierre | S4 | orquestador | completada: 10/10 con A→B→C→A, Not ready y explicación real |
| R5 | Pulido visual: tour, textarea de consigna y layout del chat | S5 | web worker | completada: 617 tests, 4 skipped; review E2E 14/14; AI E2E 25/25 |
| R6 | Sub-listas reales en markdown + tests + E2E | S6 | web worker | completada: AST recursivo; focused 21/21; web 621/4; AI E2E 26/26 |

## Log

### L1 — Pedido del usuario (2026-10-18)
> Hazlo, ya que quizás uno este haciendo un diagrama con relaciones circulares o que el diagrama no esta listo para generar el backend

### L2 — Cierre: Review con validación, readiness y explicación de IA (2026-10-18)

**R1 — preflight read-only.** `GET /projects/{id}/generation-readiness?versionId=<uuid>` devuelve `{versionId, profile, ready, diagnostics}` usando la configuración V1 estática. No crea `generation_run` ni `verification_result`, no aprovisiona perfil/template y no toca el modelo. Acceso member (VIEWER incluido); versión ajena/desconocida 404, `versionId` ausente/malformado 400. Backend: **533 tests, 0 fallos, 1 skipped**.

**R2 — sección Review.** Nueva sección `[data-section="review"]` con panel que corre en paralelo la validación UML y la readiness. Muestra errores/warnings/bloqueantes con nombres del modelo (nunca UUIDs), badge `Ready`/`Not ready`, perfil V1 y la nota de que los chequeos del destino quedan en Generation. Web: **613 tests, 4 skipped**; lint y build verdes.

**R3 — IA explícita.** `Explain with AI` y `Propose a fix` solo para `OWNER`/`EDITOR`, habilitados cuando hay hallazgos. Abren el asistente con una consigna en español pre-cargada (nombres, sin UUIDs), sin auto-enviar ni auto-aplicar; un `VIEWER` ve la nota de que las acciones requieren edición. El plan sigue con preview/confirmación.

**R4 — E2E real.** Modelo sembrado con clases A/B/C y generalizaciones A→B→C→A: **10/10 checks, 0 fallos**. El panel mostró 3 errores `GENERALIZATION_CYCLE`, `Not ready` y 3 bloqueantes `UNSUPPORTED_UML_FEATURE`; el draft de IA quedó pre-cargado sin UUIDs y sin enviarse. Al consultar, el 2B respondió: «El modelo actual presenta un ciclo de generalización circular entre los elementos A, B y C, lo cual es inválido en UML estándar… no puede ser generado correctamente por el backend sin modificaciones estructurales.»

**Carrera encontrada:** al abrir el editor se persisten las geometrías por defecto y la revisión pasó de 7 a 13; el primer ask usó 7 y recibió `REVIEW_REQUIRED`. El E2E ahora espera una revisión persistida estable antes de abrir Review y reintenta una vez; el asistente ya ofrece `Reintentar`. Es una carrera de UI, no un error del review determinista.

**Pendientes visuales:** el tour de onboarding sigue tapando la esquina inferior derecha en las capturas y el input de una línea del asistente trunca la consigna larga; ambos son polish, no bloquean el flujo.

**Alcance:** backend + web + tests + arnés; sin commit. Capturas: `docs/capturas/review/review-01-findings.png`, `review-02-ai-prefill.png`, `review-03-ai-outcome.png`.

### L3 — Cierre del pulido visual (2026-10-18)

**Tour:** la tarjeta se movió por encima del FAB (`bottom: 96px`, `maxHeight: calc(100vh - 120px)`) y se oculta cuando hay una sección o el asistente abiertos, sin marcar el tour como visto. El FAB queda visible y clickeable; el E2E comprueba que el centro del FAB no está interceptado. Al cerrar el panel, el tour reaparece si no se terminó.

**Asistente:** la consigna principal pasó de `<input>` a `<textarea rows={3}>`; la consigna larga del Review se puede leer y editar. El test de markup cubre textarea y prefill sin auto-envío.

**Chat:** layout de dos columnas (`.chat-layout` / `.chat-main` / `.chat-side`) con la conversación a la izquierda y contexto/memoria a la derecha; eyebrow “Conversation” en lugar del duplicado “Live collaboration”; medidor de contexto compacto con `<details>` “Details”; empty state compuesto (“No messages yet” + sugerencia). En pantallas menores de 980px vuelve a una columna.

**Markdown:** las viñetas anidadas con 4 espacios dejaban un `*` literal; el parser ahora acepta indentación inicial y las renderiza como ítems. Limitación declarada: los ítems anidados se aplanan (sin sub-lista), pero no se ven asteriscos.

**Evidencia:** web **617 tests, 4 skipped** (focused 17/17 para markdown); lint y build verdes. Review E2E **14/14** (tour visible, FAB no interceptado, tour oculto con el panel, cycle/readiness/prefill/explicación). AI E2E **25/25**, 0 findings, con la respuesta markdown y el layout nuevo. Capturas: `docs/capturas/review/` y `docs/capturas/ai-browser/06/07`.

**Alcance:** frontend, tests y arnés; sin commit. Servicios apagados y puertos `18092/25434/5173/18099` libres.

### L4 — Sub-listas reales en markdown (2026-10-18)

**R6:** el AST de listas pasó a `MarkdownListItem { children, sublists? }`; `listItem` devuelve la indentación; `parseList` es recursivo y arma sublistas para 2/4 espacios (unordered y ordered, hasta dos niveles y más); el renderer recursa y produce `<li>parent<ul><li>child</li></ul></li>`. Se mantienen listas planas, cambios de tipo al mismo nivel, blank lines, inline formatting y el escape de HTML. CSS ajusta márgenes/padding de `li > ul/ol`.

**Evidencia:** focused markdown **21/21**; suite web **621 tests, 4 skipped**; lint y build verdes. El AI E2E ahora detecta sintaxis de lista indentada en la respuesta cruda y exige `li > ul`/`li > ol` en el DOM: **26/26, 0 fallos**, con una captura `07-chat-ai-answer.png` que muestra “Características” con Proveedor/Producto anidados (marcador cuadrado) y sin `*` literales. El primer intento del E2E falló por un `frame got detached` al cargar el editor (flake del navegador, sin aserción fallida); el reintento pasó.

**Alcance:** frontend, tests y arnés; sin commit. Servicios apagados y puertos libres.

