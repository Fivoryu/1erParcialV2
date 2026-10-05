# Vista compacta del editor (barra de opciones + modales + IA flotante)

**Estado:** plan congelado el 2026-10-05 sobre `feat/vista-compacta-editor` (desde `feat/incremento-4-ia-textual` = `8197e01`).
**Origen:** pedido del usuario con evidencia visual (`a5b24e5c-...-Nest Hub Max.png`, 2560×5504 px de tabla).
**Rama:** `feat/vista-compacta-editor`, creada desde HEAD y no desde `main`, porque `AssistantPanel`/IA textual solo existe en `feat/incremento-4-ia-textual` (`git ls-tree main` → 0 coincidencias; `main..HEAD` = 28 commits). Se mantiene aislada de la rama de incremento 4 para no ensuciar su revisión.

## 0. Diagnóstico medido antes de congelar

El editor apila **8 bloques a ancho completo** en una sola columna vertical:

| # | Bloque | Montaje |
| --- | --- | --- |
| 1 | Encabezado + Undo/Redo + chip de revisión | `web/src/pages/ProjectEditorPage.tsx:452` (`section.editor-heading`) |
| 2 | Lienzo + panel de propiedades | `ProjectEditorPage.tsx:500` (`div.editor-workspace`, grid `1fr / 300px`, `web/src/styles.css:766`) |
| 3 | Intercambio XMI | `ProjectEditorPage.tsx:519` → `section.generation-panel` (`web/src/xmi/XmiImportPanel.tsx:137`) |
| 4 | Presencia ("In this diagram") | `ProjectEditorPage.tsx:520` → `section.surface.presence-panel` (`web/src/collaboration/PresencePanel.tsx:10`) |
| 5 | Asistente IA | `ProjectEditorPage.tsx:521` → `section.surface.chat-panel.assistant-panel` (`web/src/assistant/AssistantPanel.tsx:85`) |
| 6 | Chat del proyecto | `ProjectEditorPage.tsx:522` → `section.surface.chat-panel` (`web/src/collaboration/ChatPanel.tsx:41`) |
| 7 | Miembros | `ProjectEditorPage.tsx:528` → `section.surface.members-panel` (`web/src/members/MembersPanel.tsx:81`) |
| 8 | Generación de backend | `ProjectEditorPage.tsx:529` → `section.generation-panel` (`web/src/generation/GenerationPanel.tsx:124`) |

Ancho de página gobernado por `.content-wrap` (`styles.css:128`, `min(1120px, 100% - 48px)`) y `.editor-wrap` (`styles.css:498`, `min(1160px, 100% - 48px)`, `padding-top: 50px`). Padding de tarjeta entre 22 y 26 px (`styles.css:1458`, `:1681-1698`). Los ocho bloques están **siempre montados**: no existe estado de visibilidad a nivel página; el único disclosure del repo es `formOpen` en `GenerationPanel.tsx:100,133-134`.

**Conclusión:** el costo no es la cantidad de información sino la superficie ocupada para información casi vacía (XMI sin archivo, chat sin mensajes, invitaciones pendientes vacías, generación sin destino). No existe ningún patrón de solapas, modal o acordeón previo en este repositorio.

## 1. Decisiones tomadas antes de congelar

| Decisión | Valor | Quién/cuándo |
| --- | --- | --- |
| VCE-01 Modelo de vista | **Barra de opciones en la parte superior** con botones; cada botón abre **un modal** con esa sección. El lienzo y Propiedades quedan siempre visibles. | Usuario, 2026-10-05 (respuesta a la pregunta de alcance) |
| VCE-02 Ubicación de la IA | La IA **no** es un botón de la barra: es un **botón circular flotante** que se puede mover por la pantalla para abrir la sección. | Usuario, 2026-10-05 |
| VCE-03 Alcance visual | **Solo densidad y jerarquía.** No se cambia la paleta ni los tokens de color (`styles.css:1-25`). | Usuario, 2026-10-05 |
| VCE-04 Agrupación | 4 botones: `Intercambio XMI` \| `Colaboración` (presencia **+** chat juntos) \| `Miembros` \| `Generación`. La IA queda fuera de la barra (VCE-02). | Derivada del pedido; agrupar presencia+chat evita dos modales casi vacíos |
| VCE-05 Sin `createPortal` | El modal se renderiza **en línea** (overlay `position: fixed`), no por portal. | Derivada de la infraestructura de tests: ver §2 |
| VCE-06 Sin dependencias nuevas | No se agrega jsdom, testing-library ni librería de diálogos. | Convención medida del repo: `package.json` no las tiene (ver §2) |

**Interpretación declarada (no cerrada por el usuario):** "un botón que se moverá en la pantalla de forma circular" (VCE-02) se implementa como **botón circular flotante reposicionable por arrastre**, con posición inicial abajo a la derecha y posición recordada. Se descarta la órbita automática por ser una animación permanente que contradice VCE-03 (densidad y jerarquía). Si el usuario quería órbita, es una corrección acotada sobre VC-04.

## 2. Base técnica verificada (path:line)

- **Tests sin DOM.** `web/package.json` no declara `jsdom`, `happy-dom`, `@testing-library/*` ni `react-test-renderer`. `web/vite.config.ts` define `test.include` y ningún `environment`/`setupFiles`. Todos los tests de componente usan `renderToStaticMarkup` (`web/src/collaboration/ChatPanel.test.tsx:1-3`) y la lógica se extrae a módulos puros (`web/src/collaboration/messageTime.ts`, `web/src/diagram/selection.ts`).
  - **Consecuencia 1 (VCE-05):** un modal con `createPortal` rompe el render de servidor, así que los tests SSR no podrían cubrir lo que se envía. Se implementa sin portal.
  - **Consecuencia 2:** la lógica interactiva (Esc, trampa de foco, límites de arrastre) va a módulos puros testables, y el componente se prueba por marcado estático.
  - **Consecuencia 3:** los tests SSR **no ven layout**. La verificación real de geometría exige navegador (VC-06).
- **Herramienta de captura disponible en el repo.** `web/scripts/capture-editor.mjs`, `capture-extras.mjs`, `capture-generation-panel.mjs` usan `puppeteer-core` contra Chrome instalado (`C:/Program Files/Google/Chrome/Application/chrome.exe`) y fallan ruidosamente si un estado no es alcanzable. Son la base de la verificación de VC-06.
- **Node fuera del PATH de bash.** `node` no resuelve en WSL; existe en `$HOME/.nvm/versions/node/v24.21.0/bin/node`.
- **INCIDENTE DE TOOLCHAIN (medido).** El `node_modules` de `web/` fue instalado en **Windows**, así que bajo el node de WSL vitest **no arranca**: falla con `Cannot find module @rollup/rollup-linux-x64-gnu` antes de ejecutar un solo test. Hay que correr los tests por la cadena de Windows:
  ```
  cd /mnt/d/Universidad/Proyectos/2doSemestre2026/sw1/1erParcialV2/web && cmd.exe /c "npx vitest run src/ui" 2>&1 | tail -40
  ```
  `npm run lint` (eslint + `tsc --noEmit`) **sí** funciona bajo WSL, porque no necesita binario nativo. No reinstalar `node_modules` ni tocar `package-lock.json`: no está autorizado y es innecesario.
- **Baseline autoritativo medido el 2026-10-05 en `8197e01`** (excluyendo `src/ui`, que aún no existía en ese commit):
  - `npx vitest run` sobre `src/api src/assistant src/collaboration src/diagram src/generation src/members src/pages src/xmi` → **30 archivos passed + 1 skipped (31); 222 tests passed + 4 skipped (226); 0 fallos** en 4,46 s.
  - `npm run lint` → **limpio**, exit 0.
  - Riesgo de regresión permisible: **0 fallos** al corte. Cualquier fallo posterior es atribuible a esta feature.
- **Docker no disponible en esta distro WSL.** Un backend real por `docker compose` no es alcanzable sin integración de Docker Desktop; la verificación de VC-06 se diseña para no depender de eso.
- **Riesgo de regresión acotado.** Ningún test monta `ProjectEditorPage` (0 coincidencias). Los tests que podrían romperse son los de los paneles movidos, que se montan directos y no cambian de contrato: `web/src/xmi/XmiImportPanel.test.tsx`, `web/src/collaboration/PresencePanel.test.tsx`, `web/src/collaboration/ChatPanel.test.tsx`, `web/src/members/MembersPanel.test.tsx`, `web/src/generation/GenerationPanel.test.tsx`.

## 3. Unidades

| Unidad | Título | Prerrequisitos | Estado |
| --- | --- | --- | --- |
| VC-01 | Primitiva de modal accesible sin portal (`Modal.tsx` + `modalBehavior.ts` puros + tests) | VCE-05, VCE-06 | **cerrada** (19/19) |
| VC-02 | Registro de secciones + barra de opciones presentacional (`editorSections.ts`, `EditorOptionsBar.tsx` + tests) | VCE-01, VCE-04 | pendiente |
| VC-03 | Montar XMI, Colaboración, Miembros y Generación dentro de modales y eliminar el apilado vertical | VC-01, VC-02 | pendiente |
| VC-04 | Botón flotante circular de IA, arrastrable con límites de viewport y posición recordada (`floatingPosition.ts` + tests) | VC-01, VCE-02 | **cerrada** (282/0) |
| VC-05 | Pasada de densidad y jerarquía en `web/src/styles.css` (paleta y tokens intactos) | VC-03, VC-04 | **cerrada** (282/0) |
| VC-06 | Verificación: suite + lint + captura real de geometría; actualizar documentación de uso | VC-05 | **cerrada** (22/22 en navegador) |

Regla test-first: en VC-01, VC-02 y VC-04 existe escenario determinista (helpers puros y marcado estático) → RED observado antes de GREEN. En VC-03 y VC-05 el cambio es composición y CSS: la verificación es proporcional (suite existente + captura de geometría en navegador), y se declara que los tests SSR no pueden probar layout.

### 3.1 Evidencia de cierre por unidad

**VC-01 — cerrada.** Archivos: `web/src/ui/Modal.tsx`, `web/src/ui/modalBehavior.ts`, `web/src/ui/modalBehavior.test.ts`, `web/src/ui/Modal.test.tsx`, bloque `.editor-modal*` en `web/src/styles.css`.

- RED observado dos veces: `modalBehavior.test.ts` falló con `Cannot find module './modal'`; `Modal.test.tsx` falló con 8× `Element type is invalid … got: undefined`.
- GREEN: `src/ui` → **2 archivos, 19/19 tests** en 791 ms. Suite completa: **241 passed / 4 skipped / 0 failed** sobre 32 archivos + 1 skipped (baseline 222 + 19 nuevos). `lint` limpio.
- **Corrección obligada por revisión del padre** (el worker no la detectó; los tests SSR no pueden verla):
  1. **Colisión de mayúsculas.** `src/ui` contenía `modal.ts` y `Modal.tsx`. Con `core.ignorecase = true` el import extensionless `'./Modal'` resuelve a `.ts` antes que a `.tsx`, así que devolvía el helper puro y no el componente. El worker la esquivó importando `'./Modal.tsx'` explícitamente, lo que dejaba la trampa armada para el próximo import. Se renombró el helper a `modalBehavior.ts` y se volvió al import extensionless.
  2. **Restauración de foco destruida por re-render.** El único `useEffect` dependía de `[open, dismissible, onClose]`. Con un `onClose` inline en el padre, cualquier re-render con el modal abierto (tipear en un panel adentro) recapturaba `document.activeElement` — ya dentro del diálogo — de modo que el disparador original se perdía y el foco no volvía al cerrar. Se separó en tres efectos: foco `[open]`, bloqueo de scroll `[open]`, y teclado `[open, dismissible, onClose]`.
  3. **El foco no entraba al diálogo.** `aria-modal="true"` exige que el foco esté adentro. Se agregó `tabIndex={-1}` al contenedor y foco inicial al primer elemento focalizable en la apertura.

**VC-02 — cerrada.** Archivos: `web/src/pages/editorSections.ts`, `editorSections.test.ts`, `EditorOptionsBar.tsx`, `EditorOptionsBar.test.tsx`, bloque `.editor-options-bar*` en `web/src/styles.css`.

- RED observado dos veces: `Cannot find module './editorSections'` y `Failed to load url ./EditorOptionsBar`.
- GREEN: `src/pages` → **3 archivos, 16/16** (LoginPage 3 + registry 7 + barra 6). Suite completa: **254 passed / 4 skipped / 0 failed** sobre 35 archivos + 1 skipped (222 baseline + 19 VC-01 + 13 VC-02). `lint` limpio.
- Diseño respetado: la barra es **puramente presentacional**, no tiene semántica de toggle; solo reporta qué botón se pulsó y el padre decide con `nextActiveSection`. El toggle vive en el módulo puro y por eso sí está testeado, ya que `renderToStaticMarkup` no puede disparar eventos.
- Sin correcciones del padre. `EditorOptionsBar.tsx` usa `createElement` en vez de JSX: se verificó que **`GenerationPanel.tsx` ya lo hace en producción**, así que es precedente del repo y no se tocó.

**VC-03 — cerrada.** Archivos: `web/src/pages/ProjectEditorPage.tsx`, `web/src/ui/Modal.tsx`, `web/src/ui/Modal.test.tsx`, bloque de aplanado en `web/src/styles.css`.

- RED observado: 4 tests nuevos fallaron (uno corrigió su propia aserción durante GREEN, porque el botón de cierre también lleva `aria-label`).
- GREEN: `src/ui` + `src/pages` → **5 archivos, 39/39**. Suite completa: **258 passed / 4 skipped / 0 failed** (254 + 4 nuevos, cero regresiones). `lint` limpio. Diff de la unidad: **+254 / −22** en dos archivos.
- Los seis paneles salieron del apilado: el `grep` muestra cada nombre solo en imports y dentro de un `<Modal>` (líneas 675-731).
- **El worker corrigió un razonamiento del padre.** Yo afirmé que el separador de Colaboración ganaba por orden en el archivo. Falso: `.editor-modal-body .presence-panel + .chat-panel` es (0,3,0) contra (0,2,0) del reset, así que gana por **especificidad** y ganaría aun colocado antes. El orden es inocuo y legible, pero no es lo que sostiene la regla.
- **Defecto potencial detectado en revisión del padre, trasladado a VC-04:** `openSection` y `assistantOpen` son estados independientes, así que nada impide dos diálogos abiertos a la vez (dos backdrops, dos trampas de foco). Se cierra en VC-04 por dos vías: `z-index: 30` del FAB, menor que los `40` del backdrop, de modo que con un diálogo abierto el botón queda detrás del velo y no se puede pulsar; y `setOpenSection(null)` al abrir el asistente.
- **Ajuste anotado para VC-05:** con `titleVisible={false}` el `.editor-modal-header` conserva `padding: 20px 24px 0` y contiene solo el botón de cierre, así que suma ~36 px de aire muerto arriba del panel. Es candidato directo de la pasada de densidad.

> **Deuda declarada (no es un defecto, es una decisión consciente):** `titleVisible` es hoy `false` en los cinco llamadores, así que el bloque de título visible de `Modal` (`.editor-modal-title`, `.modal-description`, `eyebrow`) no tiene ningún usuario en producción. Se conserva porque es una capacidad genuina de una primitiva compartida, está testeada en ambas ramas y borrarla invalidaría tests y CSS correctos. Si la revisión lo objeta, la simplificación es eliminar el bloque visible y dejar `title` como único `aria-label`.

**VC-04 — cerrada con corrección obligada por revisión.** Archivos: `web/src/ui/floatingPosition.ts`, `floatingPosition.test.ts`, `FloatingAssistantButton.tsx`, `FloatingAssistantButton.test.tsx`, wiring en `ProjectEditorPage.tsx`, bloque `.floating-assistant` en `styles.css`.

- RED observado dos veces (`Failed to load url ./floatingPosition`, y `./FloatingAssistantButton` inexistente).
- GREEN: `src/ui` + `src/pages` → **7 archivos, 58/58**. Suite completa: **277 passed / 4 skipped / 0 failed** (258 + 19 nuevos). `lint` limpio.
- **Defecto funcional encontrado por el padre leyendo el código, no por tests:** `handlePointerDown` inicializaba `startPosition` con las **coordenadas del puntero** en lugar de la posición renderizada del botón. Como `handlePointerMove` calcula `startPosition + (currentPointer − startPointer)`, con ambos valores idénticos la cuenta colapsaba a `next = currentPointer`: el botón **se teletransportaba** bajo el cursor en el primer arrastre en vez de moverse relativo a sí mismo. `clampToViewport` disimulaba el resultado manteniéndolo en pantalla.
- **Corrección, y lo importante: cómo se corrige.** No alcanzaba con arreglar la línea. Se extrajo la aritmética a la función pura `dragPosition(startPosition, startPointer, currentPointer)` y se testea, con la regresión exacta como primer caso (mover el puntero 10 px debe mover el botón 10 px, no llevarlo a la coordenada absoluta) más una **propiedad de invariancia por offset** que vuelve el defecto imposible de reescribir sin romper la suite. El origen ahora sale de `getBoundingClientRect()`, que además es correcto antes de la hidratación.
- **Segundo defecto en la misma revisión:** `event.preventDefault()` en `pointerdown` suprimía el `mousedown` de compatibilidad, así que pulsar con el mouse **nunca enfocaba el botón**. Eso rompía la restauración de foco del modal, que captura `document.activeElement` al abrir: `previouslyFocused` quedaba en `<body>` y al cerrar el asistente el foco no volvía. Era la misma falla de VC-01 reintroducida desde el otro lado. `touch-action: none` y `user-select: none` ya cubrían lo que ese `preventDefault` pretendía evitar.
- **Tercero, menor:** se escribía en `localStorage` dentro de un updater de `setPosition`, que React puede invocar más de una vez (StrictMode). Se movió a un `positionRef` fuera de los updaters.
- Suite tras la corrección: **282 passed / 4 skipped / 0 failed** (277 + 5 tests de `dragPosition`). `lint` limpio.

**VC-05 — cerrada.** Solo `web/src/styles.css`. Pasada de densidad **inline del padre**, no delegada: un archivo, CSS puro, y sin test posible (la suite no lee `styles.css`, verificado).

- Alcance aplicado: `.editor-wrap` `padding-top` 50→32, `.editor-heading` `margin-bottom` 30→20, `.editor-heading h1` `clamp(28px,3.5vw,38px)` → `clamp(24px,3vw,32px)`, `.editor-modal-header` `padding` 20px→14px, `.editor-modal-body` `padding` 16/24/24→10/20/20, y step-down tipográfico dentro del diálogo (`.eyebrow` margen 5px, `h2` 18px→15px, `.section-heading` margen 22px→16px).
- **Hallazgo que evitó una regla dañina:** `.generation-heading` **no** tiene `margin-bottom`; su espaciado lo aportan los `margin-top` de los elementos siguientes (`.generation-notice { margin: 14px 0 0 }`). Agregarle un margen inferior habría duplicado la separación. No se agregó.
- **Trampa de cascada evitada:** una regla agregada al final con igual especificidad **le gana a un `@media` anterior**, esté o no envuelta la otra. Como `@media (max-width: 560px)` fija `.editor-wrap { padding-top: 40px }`, la reducción se envolvió en `@media (min-width: 561px)`. Se descartó deliberadamente agrandar `.diagram-canvas`: su altura ya está sobrescrita en `@media (max-width: 640px)`, y además agrandar el lienzo **aumenta** el scroll, que es lo contrario del objetivo.
- Nota de método: un `awk` mal escrito atribuyó varios bloques a los `@media` equivocados y casi produce dos reglas con guardas incorrectas. Se corrigió leyendo las fronteras reales de los bloques.
- Suite tras el CSS: **282 passed / 4 skipped / 0 failed**. Ningún test lee `styles.css`, así que esto no es evidencia de densidad: lo es la captura de VC-06.

**VC-06 — cerrada: 22/22 mediciones en navegador real, cero defectos.**

- Entorno: `VITE_API_PROXY_TARGET=http://localhost:18090` **funcionó** (probado porque `GET :5173/projects` devolvió el 401 JSON del backend a través del proxy, no un 404 de Vite). Sin `.env` en `web/`. Vite respondió solo en `[::1]:5173` de Windows, así que la navegación tuvo que usar el navegador del lado Windows.
- Suite: **282 passed / 4 skipped / 0 failed**. `lint` limpio.
- Artefactos autorizados: `web/scripts/verify-vista-compacta.mjs` y `docs/capturas/vista-compacta/` (7 capturas, 344 KB).
- **Los tres defectos encontrados leyendo código quedaron confirmados como arreglados, con número crudo, no con opinión:**
  - Arrastre: rect antes `1360/820` → después `1240/740`, **delta exactamente (−120, −80)**. El teletransporte no volvió.
  - Foco: tras `Escape`, `document.activeElement` es el FAB (`Asistente de modelo (IA)`).
  - Dos diálogos: con un diálogo de sección abierto, el backdrop (z 40) está por encima del FAB (z 30) y `elementFromPoint` en el centro del botón devuelve `editor-modal-backdrop`, no el botón.
- **El scroll apilado desapareció, medido:** `body.scrollHeight` en reposo = **1019 px** a 1440×900 (y 1078 a 720×800), con **0** hijos directos de panel en `.editor-wrap`. Los paneles ya no son hijos de la página.
- Contención de diálogos verificada en ambos viewports, incluido el de 720×800 donde todos quedan dentro de 0–800 con margen lateral 24–696.
- Captura en reposo: editor compacto, barra de 4 botones, una sola tarjeta `Customer`, diálogo de generación aplanado (sin tarjeta dentro de tarjeta) y FAB abajo a la derecha.

**Límites honestos que la verificación misma declaró:**
1. `max-height: calc(100dvh - 48px)` **nunca fue exigido**: en todos los casos medidos `scrollHeight == clientHeight`, así que el camino de scroll interno del diálogo (el riesgo 4 de este plan) quedó **sin ejercitar**. Queda como riesgo residual declarado, no como verificado.
2. La comparación "~5500 px apilados" proviene de la captura que aportó el usuario, **no** de una medición de esta build. El número duro medido es 1019 px.
3. El badge con valor `1` depende de que llegue el evento de presencia/SSE dentro de una ventana de 2,5 s.
4. El borrador `.chat-panel` conserva `border-top: 1px solid var(--line)` dentro del diálogo: es el separador Presencia→Chat documentado en VCE-10 (fondo transparente, radio 0, sombra `none`), no una cáscara de tarjeta. Se acepta como PASS con esa justificación.

**Compuerta nativa (ASSESS).** `assess` devolvió `risk: unassessable` porque la revisión nativa exige declaración explícita de archivos no rastreados, con `changedPaths: 0`. Su plan indica `independentVerifier: true`; un candidato `unassessable` se trata como riesgo alto y exige verificador independiente, que **ya corrió** (esta misma unidad, 22 mediciones). No se inventó ninguna evaluación de riesgo propia.

**Hallazgo preexistente, ajeno a esta feature, que la verificación destapó.** En desarrollo, una navegación dura o recarga a `/projects/<id>` la intercepta el proxy `/projects` de `web/vite.config.ts` y devuelve el JSON del backend (401) en vez del SPA. Es preexistente y de desarrollo: el manual ya documenta que el despliegue sirve solo cuatro caminos exactos y que un enlace profundo no lo sirve el editor. **No se tocó** (fuera de alcance), pero se registra porque afecta a cualquier recarga profunda del editor y obligó a verificar la persistencia de posición por reentrada del SPA en vez de por recarga real.

**Datos de prueba dejados en la base de desarrollo.** El verificador creó usuarios y proyectos descartables vía API (`vce-verify-<sufijo>`, proyectos `VCE verify <sufijo>`). **No se pueden limpiar por API**: los únicos `DELETE` del cliente son `removeProjectMember` y `releaseEditingLease` (`web/src/api/client.ts:330, 451`); no existe borrado de proyecto. Se reporta en vez de mutar la base del usuario a mano.

## 4. Decisiones de implementación tomadas durante la ejecución

Se registran porque desvían del diseño inicial y deben sobrevivir a una interrupción.

| Decisión | Valor | Evidencia que la fuerza |
| --- | --- | --- |
| VCE-07 Cabecera del modal sin título visible | Los modales de sección llevan **solo el botón de cierre**; el nombre accesible del diálogo sale del título del registro vía `aria-label`, no de un `<h2>` visible. La prop `titleVisible` (por defecto `true`) se agrega de forma **aditiva** en VC-03. | Los seis paneles ya son `<section aria-labelledby="…-title">`: `XmiImportPanel.tsx:137`, `PresencePanel.tsx:10`, `ChatPanel.tsx:41`, `MembersPanel.tsx:81`, `GenerationPanel.tsx:124`, `AssistantPanel.tsx:85`. Ocultar esos `<h2>` por CSS dejaría al `<section>` sin nombre accesible. Además el modal combinado de Colaboración **necesita** los dos títulos internos para distinguir "In this diagram" de "Project chat". |
| VCE-08 Idioma de las etiquetas | Barra en **inglés** (`XMI interchange`, `Collaboration`, `Members`, `Generation`), porque es el idioma de la copy de esas secciones. El botón flotante de IA en **español** (`Asistente de modelo`), porque `AssistantPanel.tsx:88-89` es la única sección en español. | `styles.css` y los paneles: copy mixta pero con esas cuatro secciones en inglés. No se reescribe copy ajena (VCE-03 prohíbe cambios de alcance visual). |
| VCE-09 z-index del backdrop | Debe superar **8**. | Máximo existente en `web/src/styles.css:830` (`.diagram-toolbar`/cursors, `z-index: 8`); luego `7` en `:1429`, `5` en `:1391`, `1` en `:608`. |
| VCE-10 Aplanado sin tocar marcado | El cuerpo del modal neutraliza la cáscara del panel por CSS (`.editor-modal-body .surface`, `.editor-modal-body .generation-panel`), sin modificar el contrato ni el marcado de ningún panel. | Cinco archivos de test montan los paneles directos: `XmiImportPanel.test.tsx`, `PresencePanel.test.tsx`, `ChatPanel.test.tsx`, `MembersPanel.test.tsx`, `GenerationPanel.test.tsx`. |
| VCE-11 Régimen de commits | **Un único commit al final**, cuando VC-06 cierre y verifique. No se commitea por unidad. | Decisión explícita del usuario, 2026-10-05, ante el choque entre la regla de work-unit commits del flujo ODD y la regla de seguridad "nunca commitear sin pedido explícito". |

**Consecuencia de VCE-11 (no negociable).** Sin commits por unidad no hay frontera revisable por unidad en git, así que **la evidencia por unidad de §3.1 es el único rastro** de qué entró en cada paso y con qué resultado. Cada unidad debe cerrar su entrada en §3.1 con: archivos, RED observado, GREEN, conteos de suite y lint, y toda corrección posterior a la entrega del writer. El diff final se revisa como un bloque, y su tamaño se declara al cierre para proteger la carga de revisión.

## 5. Entorno medido para VC-06 (captura real)

Verificado el 2026-10-05 antes de necesitarlo, porque una captura imposible se descubre tarde y caro:

| Recurso | Estado medido |
| --- | --- |
| Backend real | **ARRIBA y sano.** Contenedor `uml-diagrammer-diagrammer-1`, `Up 3 hours (healthy)`, `0.0.0.0:18090->8080/tcp`. `curl http://localhost:18090/actuator/health` → `{"status":"UP"}`. |
| Postgres | `uml-diagrammer-diagrammer-db-1`, `0.0.0.0:25432->5432/tcp`. |
| Chrome | `/mnt/c/Program Files/Google/Chrome/Application/chrome.exe` presente (ruta por defecto de los scripts de captura). |
| Docker desde WSL | NO está en el PATH de esta distro. **Sí** funciona el binario de Windows: `/mnt/c/Program Files/Docker/Docker/resources/bin/docker.exe`. |
| Servidor web | **No hay dev server corriendo**: 5173, 3000 y 4173 no responden. Hay que levantar Vite. |

**Comandos derivados** (`web/vite.config.ts` lee `VITE_API_PROXY_TARGET` o `VITE_API_BASE_URL`, con default `http://localhost:8080`, que NO es el puerto real del backend):

```
cd /mnt/d/Universidad/Proyectos/2doSemestre2026/sw1/1erParcialV2/web
cmd.exe /c "set VITE_API_PROXY_TARGET=http://localhost:18090 && npx vite --port 5173"
```

Y la captura con el script existente del repo:

```
cmd.exe /c "node scripts/capture-editor.mjs --editor-url http://localhost:5173 --out ../docs/capturas/vista-compacta"
```

**Riesgos declarados de VC-06, a resolver ahí y no antes:**
1. `capture-editor.mjs` arranca en la página de proyectos esperando `.project-row`, **sin ningún paso de login**. Si la app no auto-autentica, hay que sembrar sesión (el repo ya tiene `loadSession` en `web/src/api/session.ts` y los tests lo stubean con `MemoryStorage`).
2. El script exige que el diagrama tenga una clase llamada **`Customer`** y una relación seleccionable, y falla ruidosamente si no están. Hay que confirmar que el proyecto sembrado en la base los tiene.
3. El script fue escrito para la vista **vieja**. Los selectores de panel que usa (`.properties-panel`, `.react-flow__node`) siguen válidos, pero cualquier selector que asumiera el apilado vertical debe revisarse.
4. Las capturas deben medir geometría real (`getBoundingClientRect()` dentro del viewport y `scrollY === 0`), no confiar en `waitFor({state:'visible'})`, que auto-scrollea y ya ocultó un defecto en este ecosistema.

## 6. Riesgos declarados

1. **Cobertura estructural falsa.** Un cambio de composición puede dejar la suite verde sin probar nada del layout (lección medida en este ecosistema). Mitigación: VC-06 exige captura real y comparación de geometría, no solo tests.
2. **Doble tarjeta dentro del modal.** Los paneles traen su propia cáscara (`.surface` / `.generation-panel`). Metidos en un modal con su propia cabecera producen borde y título duplicados. Mitigación: regla CSS de aplanado dentro de `.editor-modal-body`, sin tocar el marcado ni el contrato de los paneles.
3. **Regresión de accesibilidad.** Un modal mal hecho atrapa o pierde el foco. Mitigación: `role="dialog"`, `aria-modal`, `aria-labelledby`, Esc, devolución de foco al disparador, y el patrón `dismissible` para no cerrar durante una operación en curso.
4. **Desborde del modal.** Un modal centrado con contenido más alto que el viewport queda inalcanzable arriba y abajo. Mitigación: `max-height: calc(100dvh - 48px)` + `overflow: auto` en el cuerpo. **Corrección posterior a VC-06: la contención del diálogo SÍ se midió a dos alturas, pero `scrollHeight == clientHeight` en todos los casos, así que el camino de scroll interno nunca llegó a ejercitarse.** No debe leerse como verificado: es el riesgo residual principal de esta feature.

## 7. Cierre

**Commit único:** `feat(editor): open the secondary sections from an option bar instead of stacking them` — 24 archivos, **+2023 / −22** en su forma previa a esta sección de cierre. Árbol de trabajo limpio tras el commit. Nada empujado y nada mergeado: siguen siendo decisiones del usuario.

> Este documento viaja **dentro** del mismo commit que describe, así que su propio hash no puede figurar aquí sin dejar de ser cierto en el mismo instante en que se escribe. El hash se registra en el reporte de cierre y en Engram.

**Preflight de revisión nativa: corrido, no salteado.**

1. `inspect` → `status: blocked`, `applicability: **unrelated**`, `action: start`, `lineages: 0`, `candidates: []`, `projection.paths: []` (vacía porque el trabajo ya estaba commiteado), y `next_transition: { kind: collect, reason_code: empty_candidate_base_ref_required }`.
2. Como la proyección estaba vacía, correspondía declarar el rango exacto del candidato. El candidato es **la unidad de trabajo, no la rama acumulada** (regla del contrato), así que el rango correcto es el padre del commit: `start` con `{"baseRef":"<sha completo del padre 8197e01>", "committedOnly": true}` e `idempotencyKey` nueva.
3. `start` → **`Judgment Day graph-v1 START requires lineageId`**. Error previo a la autoridad: **no se creó ninguna linaje**.
4. Reconciliación prescrita: una llamada `status` a alcance de objetivo. Devuelve exactamente lo mismo que el `inspect`: `applicability: unrelated`, `lineages: 0`, `action: start`.

**Conclusión honesta: en este entorno no se puede abrir una revisión nativa para este candidato.** La propia integración declara el objetivo `unrelated`, no existe ningún linaje (`lineages: 0`, `events: 0`, `bytes: 0`) y el `start` de graph-v1 exige un `lineageId` que no existe. **No se inventó autoridad:** no se fabricó un linaje, no se llamó a `RESET` ni a `RECOVER` (que además exigen autorización explícita y son para los estados `blocked-legacy`/`blocked-mixed`, que no es este caso), y no se simuló ninguna revisión.

**Cómo queda cubierta la garantía de verificación independiente.** La compuerta `assess` devolvió `risk: unassessable` y su plan exige `independentVerifier: true` para un candidato no evaluable. Eso **sí se cumplió**: `gentle-ai-verify` corrió VC-06 completo sobre el navegador real, con 22 mediciones crudas y cero defectos, sobre el mismo contenido que hoy está en `60a70b8`.

**Límites abiertos, que no deben leerse como verificados:**
1. El scroll interno del diálogo nunca fue exigido por el contenido medido (riesgo residual principal).
2. La recarga profunda del editor está rota en desarrollo por el proxy `/projects` de `vite.config.ts` (preexistente, ajeno a esta feature; concentró la verificación de persistencia en una reentrada del SPA).
3. Usuarios y proyectos de prueba (`vce-verify-*`) quedaron en la base de desarrollo; no hay borrado de proyecto por API.
4. Deuda declarada: `titleVisible` sin usuario en producción (ver §3.1).
