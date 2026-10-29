# Huecos detectados al documentar el 2do parcial

Objetivo: cerrar las diferencias entre el software y la documentación del 2do parcial, que deben reflejarse mutuamente.
Rama: `fix/doc-code-gaps`. Estrategia de entrega: ask-on-risk. Pruebas: Vitest (web), JUnit 5 (backend).

## Specs

- **S1. Renombrar un proyecto desde la interfaz.** El backend ya lo soporta con el comando `project.rename` (`backend/src/main/java/com/diagramador/command/handler/ProjectRenameHandler.java`), pero no hay ningún control en la interfaz: no aparece en `web/src/pages/ProjectsPage.tsx` ni en `web/src/pages/ProjectEditorPage.tsx`. Al implementarlo, agregar el paso al flujo del CU-02 "Gestionar proyectos" en el documento.
- **S2. Mensajes en inglés dentro de una interfaz en español.** La interfaz se tradujo al español el 2026-10-25, pero el usuario todavía ve textos en inglés:
  - Backend, mensajes de error que la web muestra tal cual:
    - "Username or password is incorrect." (`identity/IdentityService.java:132-135`)
    - "You do not have access to this project." (`identity/ProjectAuthorizationService.java:17`)
    - "Project was not found." (`api/ProjectApiService.java:349-351`)
    - "Model was not found." (`vcs/VcsController.java:225`)
  - Backend, motivos del almacenamiento que muestra el editor: "Set manually.", "Has list attributes, which a document stores natively.", "No document signal, so it stays relational." (`storage/PersistenceStoreClassifier.java:26-28`, mostrados por `web/src/diagram/StoreBadge.tsx`).
  - Backend, resultado al rechazar una propuesta: "Rejected without changes." (`collaboration/ChatProposalService.java:157-162`).
  - Web: "Enter a project name to continue." (`web/src/pages/ProjectsPage.tsx:89-92`).
  - Web: los errores del dictado por voz no tienen texto localizado (`web/src/assistant/useSpeechRecognition.ts:38-46`).
  - Al corregirlos, actualizar las excepciones de los CU-01, CU-02, CU-13, CU-19 y CU-25 en el documento, que hoy citan los textos en inglés.
- **S3. Resto de los mensajes de error en inglés.** Pedido: "Contina papu"; decisión del usuario (2026-10-29): "Mapa por código en web (Recommended)". La web traduce por código de error en un solo lugar; si el servidor ya respondió en español, se conserva su texto.
- **S4.** "No se puede rechazar una invitación; solo aceptarla, o que el propietario la revoque".
- **S5.** "No se puede restaurar un commit puntual; solo cambiar de rama".
- **S6.** "Las solicitudes de fusión no tienen aprobación de revisores; solo el propietario fusiona".
- **S7.** "La verificación de una generación (`POST /projects/{projectId}/generations/{runId}/verification`, `api/GenerationController.java:103-107`) no tiene botón en `web/src/generation/GenerationPanel.tsx`; solo se invoca por API."
- **S8.** "La pasarela móvil está apagada por defecto (`diagrammer.mobile-gateway.enabled`, `backend/src/main/resources/application.yml:30`); los CU-28 y CU-29 no funcionan hasta habilitarla."
- **S9.** "El teléfono usa Qwen3.5-0.8B, pero la evaluación recomendó Qwen3.5-2B (`docs/puds/verificacion/03-evaluacion-modelos-ia4-01.md:51`)."

## Tasks

| ID | Specs | Ruta | Estado | Commit |
| --- | --- | --- | --- | --- |
| T1 | S1 | inline | hecho (Vitest verde) | fa90eb2 |
| T2 | S2 | inline | hecho (Vitest y JUnit verdes) | 91144fa |
| T3 | S3 | inline | hecho (Vitest verde) | 064fa94 |
| T4 | S4 | por decidir | pendiente | — |
| T5 | S5 | por decidir | pendiente | — |
| T6 | S6 | por decidir | pendiente | — |
| T7 | S7 | worker + verificador | verificado; commit pendiente | — |
| T8 | S8 | por decidir | pendiente | — |
| T9 | S9 | por decidir | pendiente | — |

## Log

- **L1 (2026-10-29, pedido del usuario):** "Anota esos huecos para poder impelemntarlos."
- **L2 (2026-10-29):** Los huecos salieron de revisar los 30 casos de uso del documento contra el código, con un explorador por ciclo. Documento: Google Doc `1OI5p8d1x-NvM_Bel-efUocawG9NziuAbqpMjlnru6RA`, sección 2.1.4.
- **L3 (2026-10-29, otras diferencias observadas, no pedidas; decidir si se implementan o se dejan documentadas así):**
  - No se puede rechazar una invitación; solo aceptarla, o que el propietario la revoque (`api/InvitationController.java`, `web/src/members/InvitationsSection.tsx`).
  - No se puede restaurar un commit puntual; solo cambiar de rama (`vcs/VcsController.java`).
  - Las solicitudes de fusión no tienen aprobación de revisores; solo el propietario fusiona (`vcs/VcsMergeRequestService.java`).
  - La verificación de una generación (`POST /projects/{projectId}/generations/{runId}/verification`, `api/GenerationController.java:103-107`) no tiene botón en `web/src/generation/GenerationPanel.tsx`; solo se invoca por API.
  - La pasarela móvil está apagada por defecto (`diagrammer.mobile-gateway.enabled`, `backend/src/main/resources/application.yml:30`); los CU-28 y CU-29 no funcionan hasta habilitarla.
  - El teléfono usa Qwen3.5-0.8B, pero la evaluación recomendó Qwen3.5-2B (`docs/puds/verificacion/03-evaluacion-modelos-ia4-01.md:51`).
- **L4 (2026-10-29, pedido del usuario):** "Hagamos los huecos de doc vs cod"
- **L5 (2026-10-29, T1):** El nombre del proyecto en el encabezado del editor ahora tiene el botón "Renombrar"; envía `project.rename` por la misma cola de comandos y luego relee el proyecto (`web/src/pages/ProjectNameEditor.tsx`, `ProjectEditorPage.tsx`). La validación "Escribe un nombre de proyecto para continuar." se comparte con la creación.
- **L6 (2026-10-29, T2):** Traducidos en el backend "El usuario o la contraseña son incorrectos.", "No tienes acceso a este proyecto." (y las variantes de historial y permisos), "No se encontró el proyecto.", "No se encontró el modelo." en todos sus usos, y los textos de propuestas ("Rechazada sin cambios.", "N de M cambios aplicados", "Cambio sugerido: …", "… rechazó/revisó la sugerencia: …"). En la web: "Todos los proyectos", "Sin descripción", y los errores del dictado ahora se muestran en el chat. Los motivos de almacenamiento ya se traducían en `web/src/diagram/StoreBadge.tsx`, así que no se cambiaron (también alimentan el README del backend generado). Quedan otros mensajes del backend en inglés fuera de esta lista (p. ej. "Resource was not found.", "Generation run was not found.", los de invitaciones).
- **L7 (2026-10-29, pedido del usuario):** "Contina papu". Quedaban unos 100 mensajes del backend en inglés; el usuario eligió "Mapa por código en web (Recommended)".
- **L8 (2026-10-29, T3):** `web/src/api/errorMessages.ts` traduce en el constructor de `ApiTransportError` (el texto original queda en `serverMessage`) y en los rechazos de comandos del editor. `NO_PROPOSAL` pasa intacto porque es la pregunta de aclaración de la IA. El login decide con el texto original para conservar sus mensajes propios. Google Doc: actualizadas las excepciones de actualización sin campos y de adjuntos inválidos.
- **L9 (pedido del usuario):** "Vamos con los 6 papu". "Busca otra vez, estabamos haciendo lo que le falto a claude, ya que faltaba que el codigo reflejara la documentacion". "Review the delivered subagent output and continue."
- **L10 (revisión):** La comparación con los documentos locales no confirma que los seis puntos de L3 sean incumplimientos. Las invitaciones y la configuración predeterminada de la pasarela móvil corresponden a lo documentado localmente; las aprobaciones no están especificadas allí; el operador también puede fusionar. CU-20 sí exige verificaciones de ejecución que el servicio registra como `NOT_RUN`. El documento de Google de L2 redirige al inicio de sesión y no se pudo cotejar en esta sesión.
- **L11 (pedido del usuario):** "No pasa nada continua sin verificar el documento".
- **L12 (T7):** El worker quedó bloqueado antes de leer el proyecto, editar código o ejecutar pruebas: la guía requerida de React está fuera del repositorio y falta autorización para leerla.
- **L13 (decisión del usuario):** "Sí, autorizar la lectura" del único archivo `C:\Users\HP\.pi\agent\skills\vercel-react-best-practices\SKILL.md`.
- **L14 (T7, revisión del primer resultado):** El worker informó 52 pruebas enfocadas aprobadas y una omitida, pero la prueba del panel solo renderiza HTML estático y no demuestra el recorrido del botón a la API. La revisión también encontró que el estado `busy` muestra «Generando…» al verificar y que una respuesta rechazada muestra directamente el mensaje del servidor. Se solicitan correcciones y evidencia del recorrido público antes de cerrar la tarea.
- **L15 (T7, corrección):** El worker corrigió la indicación de espera y la localización de respuestas rechazadas; informó RED para ambos casos y GREEN con 54 pruebas enfocadas aprobadas y una omitida. La comprobación conjunta de la suite web, ESLint, TypeScript y build terminó con código 0; Vitest informó 799 pruebas aprobadas y 4 omitidas. La interacción botón → petición → resultado sigue sin probarse.
- **L16 (T7, autorización y bloqueo):** El usuario eligió "Yes, allow this read" para leer únicamente `C:\Users\HP\.pi\agent\skills\ego-browser\SKILL.md`. La lectura se realizó, pero `ego-browser skill` terminó con código 127 (`command not found`); no hubo comprobación de navegador. La evaluación nativa no pudo determinar el candidato por archivos no rastreados que requieren selección explícita; no se declara una revisión nativa aprobada.
- **L17 (decisión del usuario):** "I'll make ego-browser available"; T7 espera que el usuario habilite el navegador antes de continuar.
- **L18 (corrección del usuario):** "Pero si esta disponible ego-browser". Confirmado: `ego-browser.exe` está en el `PATH` y `ego-browser.exe skill` terminó correctamente. El diagnóstico anterior era incorrecto: se había probado `ego-browser` sin la extensión de Windows.
- **L19 (T7, verificación independiente):** S7 PASS. En el panel real, con `ego-browser.exe` y respuestas simuladas en estado aislado, la persona verificadora pulsó los botones y comprobó los `POST` a la generación correcta, los resultados `NOT_RUN` y `FAILED` y la localización del rechazo `RUN_NOT_FOUND`. Ejecutó además `npm --prefix web test -- src/generation/GenerationPanel.test.tsx`: 19 pruebas aprobadas. No se usó un backend real; la compatibilidad de consumidores externos del componente exportado no se comprobó. Riesgo: ítem 3 (propiedades exportadas y estado de espera compartido). La evaluación nativa fue inasignable por archivos no rastreados; no se declara aprobación nativa.

