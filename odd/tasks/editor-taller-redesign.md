# Editor "taller" redesign (web + mobile)

Objective: apply the approved mockups `design/diagramador-web-movil/01-web-taller.html` (web editor) and `02-movil-taller.html` (mobile agent screen) with the purple-pink palette of `design/system.md`, wiring every mockup control to the real model instead of the mockup's simulated data.
Branch: `feat/editor-taller-redesign` · Delivery: `ask-on-risk` · Runners: `cd web && npx vitest run` + `npx tsc --noEmit` + `npx eslint`; `cd mobile && flutter test` + `flutter analyze`; browser check with ego-lite.

## Specs

- **S1 Palette and type.** Web tokens and the Flutter theme take the purple-pink proposal of `design/system.md` § "Propuesta morado-rosa para las maquetas": `--ink #2d2032`, `--muted #6d6070`, `--line/--grid #e8dce8`, `--paper #ffffff`, `--canvas #f9f5f9`, `--accent #8b3c86`, `--dark #68286f` (primary actions), `--wash #f6e8f4`, `--rose #a73d74`, `--rose-wash #fae9f1`; errors keep `#9b3e37` / `#fbefed`.
- **S2 Editor header.** Brand "Diagramador / Espacio de modelado", breadcrumb "Proyectos / <project>", project name as h1; actions: Deshacer `Ctrl + Z`, Rehacer `Ctrl + Y`, Colaboración, primary "Revisar diagrama". User comment: "Tanto en deshacer y deshacer se muestre se muestre las teclas rapidas como ctrl + z, ctrl + y."
- **S3 Section bar.** A second row with Intercambio XMI, Integrantes, Generación, Versionado, opening the existing panels. User comment: "También con ello quiero ver el flujo".
- **S4 Tool rail.** Left rail "HERRAMIENTAS" with Seleccionar, Clase, Interfaz (plus the existing Clase abstracta, Enumeración, Paquete) and Relación. User comments: "Sobre relaciones me gustaría que al hacer click pueda ver los diferentes tipos de relaciones que hay incluidas la asocición de clases en vez de solo ver la predeterminada. Y además el modal no me gusta para la opcion de relación"; "El modal no quiero, quiero que se despliegue por la parte inferior y ocultarlo"; "Me gusta los símbolos, pero no me gusta que ocupe la pantalla del diagrama, la idea es que el icono tenga una flecha hacia abajo para poder abrir los demás tipos de relaciones, si selecciona la relación se cambia y se puede dar click al tipo de relación sin necesidad de abrir hacia abajo, el modal actual no lo quiero para eso."; "Bien me gusta, pero la flecha ocupa mucho y no es simétrico con las demás herramientas." The Relación tool shows the current kind's symbol and name; a small ▾ opens the seven kinds (Asociación, Agregación, Composición, Generalización, Realización de interfaz, Dependencia, Clase de asociación) below it inside the rail; picking one makes it current. Activating the tool opens a relation setup in the inspector (Origen, Destino, and Clase de asociación for that kind) that creates the real relationship.
- **S5 Live validation.** User comments: "Me gustaría que se pueda ver el validador a tiempo real, que aparezca los advertencias o errores que hay, como referencias circulares, redundantes, etc."; "Mejora la parte visual, y que si se hay varios detalles no tenga que mostrar todo y ocupar toda la pantalla". A strip above the canvas shows Errores N / Advertencias N from the model validation API, refreshed after each model revision, with "Ver detalles" revealing a scroll-capped list.
- **S6 Collapsible attributes.** User comment: "Por donde están los atributos me gustaría poder ocultar o mostrar, ya que es posible que una clase pueda tener varios atributos y pueda saturarlo". The attributes section of the properties panel can be hidden and shown.
- **S7 Copy/paste hint.** User comment: "añadir la opción de poder copiar y pegar, no en esto mismo sino en la pantalla para que el usuario sepa que puede copiar y pegar". The canvas shows Copiar `Ctrl + C` / Pegar `Ctrl + V` buttons that run the clipboard.
- **S8 Versionado like a repo.** User comment: "Mejora el modal, que se más cercano a lo que es un repo de github". Repo header (project / modelo-uml), tabs Historial, Ramas, Revisiones (merge requests) with counts, and Permisos, over the existing VCS panels.
- **S9 Mobile agent screen.** Header with back, "Agente", Configuración; a "BACKEND SELECCIONADO" context card with Cambiar; conversation heading with Historial; user bubble "Tú" and agent card "Agente local"; collapsible "Revisar operaciones pendientes"; composer "Mensaje" + primary "Enviar".
- Assumption: the mockup's "Referencia obligatoria … ejemplo" checkbox only simulated cycles ("no configura multiplicidades reales"), so it is not carried over; multiplicities stay in the properties panel.

## Tasks

| ID | Specs | Route | Status | Commit |
|---|---|---|---|---|
| T0 | clipboard (prior request) | inline | done, verified in browser | 76bd4f1 |
| T1 | S1 web tokens | inline | done | bad3237 |
| T2 | S2, S3 header + section bar | inline | done, browser-checked | bad3237 |
| T3 | S4 tool rail + relation picker + setup | inline | done, browser-checked (picker, click-to-fill, drag) | bad3237 |
| T4 | S5 live validation strip | inline | done, browser-checked | bad3237 |
| T5 | S6, S7 attributes + copy/paste hint | inline | done, browser-checked | bad3237 |
| T6 | S8 versioning repo layout | inline | done, browser-checked | 462833b |
| T7 | S1, S9 mobile theme + agent screen | inline | done; flutter test + analyze green; no device to view it | 5b22e57 |
| T8 | all | inline | done for web (1440px and 700px); mobile on device pending | — |

Route evidence: one writer (the parent) because units share `styles.css` and `ProjectEditorPage.tsx`; no writer trigger fires.

## Log

- L1 (2026-10-29, user): "Usa ego-lite para verificar, además haz el rediseño web y móvil. - Web: design/diagramador-web-movil/01-web-taller.html - Móvil: design/diagramador-web-movil/02-movil-taller.html - Paleta y sistema visual: design/system.md, sección “Propuesta morado-rosa para las maquetas”."
- L2 (2026-10-29, user, scope answer): "Completa" — the full mockup, including the relation picker, live validation, and the GitHub-like versioning modal.
- L3: clipboard verified with ego-lite; the browser check found that relationships between Ctrl+click-selected classes were dropped; fixed and pinned with tests before commit 76bd4f1.
- L4: web verified with ego-lite: header, rail, picker (7 kinds), click-to-fill relation setup (GENERALIZATION Cliente→Linea), drag with the rail kind (DEPENDENCY Cliente→PedidoCopy, no picker form), validation strip (1 error: 1–1 composition), versioning repo header with counters, 700px layout without horizontal overflow. The browser check also found the copy/paste hint covering classes; it now folds into a small button, remembered per browser.
- L5: decisions — product name kept as "Class Diagrammer" (mockup says "Diagramador"; renaming touches other pages and was not asked); redundant-relationship check runs in the editor only, so the generation-readiness validator is unchanged; the drag picker form was removed because the rail now chooses the kind.
- L6: pending — mobile agent screen not seen on a device (no phone or emulator attached); delivery is about 2,700 authored lines over 4 commits, above the ~400 slice heuristic: PR strategy (single PR or chained) is the user's call.
- L7 (2026-10-29, user): "Entregamos en un solor PR, ya lo conecte a mi cel" — one PR; phone connected.
- L8: on the phone (Samsung SM-S711B, dark mode) the app runs the dark theme; the chat bubbles and backend card used fixed light colors, unreadable in dark mode. Fixed with theme roles and pinned by a dark-mode widget test. Login and Proyectos verified on the phone against the local backend via adb reverse. The Agente screen needs a deployed generated backend, which the local stack does not have; it stays covered by widget tests.
