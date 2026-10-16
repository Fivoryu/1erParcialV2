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
| T3 | S1, S2, S6 | Panel de ayuda: botón flotante, panel propio, endpoint backend del guía con corpus en contexto, respuestas con navegación propuesta | pendiente |
| T4 | S3 | Acciones de navegación: propuesta en la respuesta, botón de confirmación, dispatch de apertura de sección, prueba de que nunca muta el modelo | pendiente |
| T5 | S1–S6 | Cierre: suites verdes, docs de uso, acta | pendiente |

## Log

- **L1** — Pedido del usuario (verbatim): «Podríamos hacer un chatbot para que guie al usuario a través de la app web. Que te parece?». Respuesta del orquestador con diseño en dos capas (tour determinista + guía IA) y riesgos (modelo 2B, alucinación de UI, separación de preocupaciones). Usuario eligió en el cuestionario: panel de ayuda separado; «Explica, navega, todo completo»; «Sí, con tour + IA». Alcance autorizado: crear el rastreo y ejecutar por etapas.
