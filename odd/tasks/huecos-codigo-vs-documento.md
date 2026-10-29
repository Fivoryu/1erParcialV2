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

## Tasks

| ID | Specs | Ruta | Estado | Commit |
| --- | --- | --- | --- | --- |
| T1 | S1 | inline | hecho (Vitest verde) | fa90eb2 |
| T2 | S2 | inline | hecho (Vitest y JUnit verdes) | 91144fa |
| T3 | S3 | inline | hecho (Vitest verde) | |

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

