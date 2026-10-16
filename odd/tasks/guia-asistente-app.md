# Guía de la app: tour determinista + chat de ayuda con navegación

> **Estado:** plan creado; ejecución por etapas. Autorización del usuario en esta sesión.
> **Producto:** diagramador colaborativo de clases UML 2.5 (PRIMER PARCIAL, Ingeniería de Software I).
> **Rama:** por crear desde `main` (`eb5d9d7`), propuesta: `feat/guia-asistente-app`.
> **Base verificada:** el chat IA colaborativo está mergeado en `main` (PR #8): sesiones, SSE, memoria por diagrama, medidor, adjuntos.

## Specs

### S1 — Chatbot guía de la app web

> «Podríamos hacer un chatbot para que guie al usuario a través de la app web.»

**Criterio:** un asistente conversacional que responde preguntas sobre cómo usar la aplicación (qué hace cada panel, cómo exportar XMI, cómo generar el backend, cómo invitar, cómo colaborar), basado en un corpus cerrado y versionado de la UI real. Nunca inventa pasos ni describe superficies que no existen. No muta el modelo UML ni toca la memoria del chat del diagrama.

### S2 — Panel de ayuda separado

> Elección del usuario: «Panel de ayuda separado» (frente a «Integrado al chat actual»).

**Criterio:** botón flotante de ayuda que abre un panel propio, distinto del ChatPanel del diagrama. No comparte sesiones ni memoria con el chat del diagrama. Visible en el editor (y evaluado en las otras páginas).

### S3 — Explica y navega (alcance completo)

> Elección del usuario: «Explica, navega, todo completo» (frente a «Solo explica»).

**Criterio:** además de explicar, el guía propone **acciones de navegación** (por ejemplo «ir al panel de Generación», «abrir XMI interchange») que se renderizan como botones en la respuesta y que el usuario confirma para ejecutarlas. La navegación cambia la UI del editor (abre la sección), nunca el modelo. Toda acción es propuesta del modelo y decisión del usuario: sin navegación automática.

### S4 — Tour determinista de onboarding

> Elección del usuario: «Sí, con tour + IA».

**Criterio:** capa de onboarding sin IA: pasos resaltados con explicaciones fijas la primera vez que un usuario entra al editor (por proyecto, persistido localmente), con la posibilidad de reabrirlo. Determinista: los pasos y sus textos no dependen del modelo.

### S5 — Corpus cerrado de la app

**Criterio:** el conocimiento del guía proviene de un módulo de datos versionado (`web/src`), derivado del inventario real de la UI (secciones del editor, panels, flujos de generación/despliegue/XMI/colaboración), con prueba que impide describir secciones inexistentes. Los cambios de UI que el corpus describe actualizan el corpus en la misma unidad de trabajo.

### S6 — Límites del modelo, declarados

**Criterio:** el guía corre sobre el runtime local existente (llama.cpp, Qwen 2B). Respuestas cortas; el corpus va en contexto y el modelo selecciona/redacta, no inventa. Si la pregunta cae fuera del corpus, lo declara en vez de alucinar. Sin fallback a nube.

## Tasks

| ID | Specs | Descripción | Estado |
| --- | --- | --- | --- |
| T1 | S5 | Corpus de ayuda de la app: inventario de secciones/panels/flujos como módulo de datos puro + pruebas de cobertura contra `editorSections.ts` | **cerrada** — `0ea918e` (corpus + 5 pruebas, GREEN tras RED); `f8f02c0` (arreglo preexistente de lint: disable de regla sin plugin en `ChatPanel.tsx`); compuertas: lint 0, suite completa 550 passed / 4 skipped |
| T2 | S4 | Tour determinista de onboarding (datos de pasos, overlay con resaltado, persistencia local, reabrir) | **cerrada** — `782aa18` (escritor delegado; RED→GREEN, 14 pruebas nuevas de ayuda; compuertas del orquestador: suite 559 passed / 4 skipped, lint 0, build verde); tarjeta de paso con navegación, progreso por proyecto en localStorage (`editor-onboarding-tour:v1:<projectId>`), apertura automática al cargar el modelo la primera vez, botón «Show editor tour» para reabrir; pasos derivados del corpus (fuente única) con apertura de la sección asociada al navegar |
| T3 | S1, S2, S6 | Panel de ayuda: botón flotante, panel propio, endpoint backend del guía con corpus en contexto, respuestas con navegación propuesta | **cerrada** — `1f26f08` (escritor delegado; RED→GREEN en web y backend; `assistant/**` intacto). Endpoint `POST /help/guide` stateless autenticado por el filtro bearer; el corpus viaja como dato desde `HELP_TOPICS` (fuente única, sin duplicar en backend); doble restricción de slugs (cliente y servidor); errores explícitos `PROVIDER_DISABLED` / `GUIDE_PROVIDER_FAILED` / `GUIDE_RESPONSE_INVALID`; panel con estado propio, independiente del chat del diagrama. Compuertas medidas por el orquestador: vitest **568 passed / 4 skipped**, lint 0, build verde, `mvn -o -B verify` **518 pruebas, 0 fallos** (vía `cmd.exe`: sin `JAVA_HOME` en WSL, `mvn.cmd` no ejecutable desde bash) |
| T4 | S3 | Acciones de navegación: propuesta en la respuesta, botón de confirmación, dispatch de apertura de sección, prueba de que nunca muta el modelo | **cerrada** — `86529c8` (escritor delegado; RED→GREEN, 8/8 enfocadas). `helpNavigation.ts` mapea slug→sección solo para temas con `section`, deduplica y omite la sección ya abierta; botones accesibles «Open …»; la navegación solo actualiza `openSection`, nunca el modelo. Compuertas medidas por el orquestador: vitest **573 passed / 4 skipped**, lint 0, build verde |
| T5 | S1–S6 | Cierre: suites verdes, docs de uso, acta | **cerrada** — `408f9de` (acta [`docs/puds/cierre/07-cierre-guia-asistente-app.md`](../../docs/puds/cierre/07-cierre-guia-asistente-app.md) + sección «La guía de la app» en `docs/MANUAL-DE-USO-Y-VERIFICACION.md`). Estado final medido: backend **518 pruebas / 0 fallos**, web **573 passed / 4 skipped**, lint 0, build verde; dos revisiones nativas **approved** con autoridad quemada |

## Log

- **L6 — Revisión nativa del candidato completo T1–T5 (2026-10-16).** Linaje `review-189aea00bad900dc`, objetivo `sha256:343712bc…` (30 archivos, 1742 líneas, base `eb5d9d7`, committed-only), riesgo medium, lenses readability+reliability. **Veredicto: approved, autoridad quemada** (`gentle-ai.review-acknowledged/v1`, revisión consumida `sha256:0abba6aa…`). Cuatro hallazgos **informativos, no bloqueantes** (trabajo posterior): `R2-TourStepCount` WARNING en el acta `07-cierre-guia-asistente-app.md:8`; `R3-001` WARNING `ProjectEditorPage.tsx:372`; `R3-002` WARNING `HelpGuideRequest.java:11`; `R3-003` WARNING `HelpGuideService.java:50`. Mantenimiento: limpieza de vista candidata diferida (`candidate-view-cleanup-failed`), igual que en L2/L5. Con esta revisión, toda la feature quedó cubierta por receipts nativos: T1+T2 (L4), T1–T3 (L5) y el candidato completo (L6).

- **L5 — Revisión nativa del candidato T1+T2+T3 (2026-10-16).** Linaje `review-271d53270ad5453d`, objetivo `sha256:f2bd0e7b…` (26 archivos, 1535 líneas, base `eb5d9d7`, committed-only), riesgo medium, tres lentes materializadas (resilience, readability, reliability). **Veredicto: approved, autoridad quemada** (`gentle-ai.review-acknowledged/v1`, revisión consumida `sha256:2c32454c…`). Cinco hallazgos **informativos, no bloqueantes** (trabajo posterior separado): `R2-001` SUGGESTION `TourOverlay.tsx:14`; `R3-001` WARNING `helpGuide.ts:20`; `R3-002` WARNING `ProjectEditorPage.tsx:372-374`; `R4-NullTopic` WARNING `HelpGuideRequest.java:11`; `R4-StorageUnavailable` WARNING `ProjectEditorPage.tsx:373`. Mantenimiento: limpieza de vista candidata diferida (`candidate-view-cleanup-failed`), igual que en L2.
- **L4 — Revisión nativa del candidato T1+T2 (2026-10-16).** Linaje `review-212619c7a21deb1c`, objetivo `sha256:1aecd3f0…` (11 archivos, 666 líneas, base `eb5d9d7`, committed-only), riesgo medium, lentes readability+reliability. Dos revisores materializados por relé del host y admitidos; **veredicto: approved, autoridad quemada** (`gentle-ai.review-acknowledged/v1`, revisión consumida `sha256:6af20076…`). Un hallazgo **no bloqueante**: `R3-001` (WARNING informativo, `web/src/help/tourSteps.ts:3-8`, lente reliability) — trabajo posterior separado, nunca re-ejecución de esta revisión. Nota de mantenimiento: la limpieza de la vista candidata quedó diferida con diagnóstico `candidate-view-cleanup-failed`; reintentar o remover la vista fuera de banda.
- **L1** — Pedido del usuario (verbatim): «Podríamos hacer un chatbot para que guie al usuario a través de la app web. Que te parece?». Respuesta del orquestador con diseño en dos capas (tour determinista + guía IA) y riesgos (modelo 2B, alucinación de UI, separación de preocupaciones). Usuario eligió en el cuestionario: panel de ayuda separado; «Explica, navega, todo completo»; «Sí, con tour + IA». Alcance autorizado: crear el rastreo y ejecutar por etapas.
