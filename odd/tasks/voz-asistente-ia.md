# Voz para el asistente de IA (dictado por micrófono)

> **Estado:** incremento ejecutado y verificado (2026-10-06). V1-V2 cerradas; evidencia final: lint+tsc limpios, suite 438 passed / 4 skipped, build verde. Rama `feat/context-menu-multiplicidad`, worktree `1erParcialV2-uxmenu`.
> **Decisión del usuario (2026-10-05):** Web Speech API del navegador (cero backend; botón oculto en navegadores sin soporte).

## 1. Corte visible

En el panel del asistente de IA (`web/src/assistant/AssistantPanel.tsx`), junto al campo de texto,
hay un botón de micrófono. Al activarlo, el usuario dicta y la transcripción aparece en vivo en el
campo; al detener, el texto queda listo para enviar al asistente como cualquier orden escrita.
En navegadores sin Web Speech API el botón no se muestra (degradación honesta, sin error).

## 2. Base verificada

- `web/src/assistant/AssistantPanel.tsx` — inputs de texto en ~:96 y ~:123; controlador
  `assistantController.ts`, estado `assistantState.ts`, hook `useAssistantPanel.ts`.
- Web Speech API: `SpeechRecognition` / `webkitSpeechRecognition`, eventos `result` (interim +
  final), `error`, `end`; soportado en Chrome/Edge, ausente en Firefox.
- Convención de tests del repo: lógica pura + markup (sin jsdom/eventos DOM reales).

## 3. Alcance

- **V1 — `useSpeechRecognition`:** hook que encapsula Web Speech API: estados
  `idle/listening/error`, acumulación de transcript final, interim opcional, idioma `es-AR`
  (constante exportada), feature-detect (`isSpeechRecognitionSupported`), limpieza al desmontar,
  tolerancia a `no-speech` y `aborted` sin estado de error persistente.
- **V2 — Botón en el panel:** mic en `AssistantPanel` junto al input; toggle click para
  iniciar/parar; indicador visual de escucha (CSS, respeta tokens del archivo); transcript final
  se anexa al texto del input; botón oculto si no hay soporte; manejo de permiso denegado con
  mensaje accesible.

### Fuera de alcance

- Comandos por voz directos (interpretación semántica): la voz solo **dicta texto**; el pipeline
  IA→plan→aplicar existente hace el resto.
- Push-to-talk global, selección de idioma en UI, STT en backend.

## 4. Unidades

| Unidad | Contenido | Verificación |
| --- | --- | --- |
| V1 | Hook `useSpeechRecognition` + máquina de estados pura testeable | tests de lógica pura (transiciones, acumulación, feature-detect); lint |
| V2 | Botón + wiring en `AssistantPanel` + CSS | tests de markup y de anexado de transcript; suite completa + build |

## 5. Estado de ejecución

| Unidad | Estado | Evidencia |
| --- | --- | --- |
| V1 | **cerrado** | commit `feat(assistant)` (ver evidencia abajo); RED observado (módulos inexistentes), GREEN 41/41, suite 428 passed / 4 skipped (+41), lint y tsc limpios. API pública: `useSpeechRecognition()` → `{ supported, listening, transcript, interim, error, start, stop, reset }` + `createSpeechRecognitionController`, `mapSpeechRecognitionError`, `transitionSpeechRecognitionState`, `isSpeechRecognitionSupported()`, `SPEECH_RECOGNITION_LANG = 'es-AR'`. Nota UX para V2: `reset()` es no-op mientras escucha (hay que parar primero) |
| V2 | **cerrado** | commit `feat(assistant)` (ver evidencia abajo); RED observado (8 fallos), GREEN 25/25 en el archivo, suite 438 passed / 4 skipped (+10), lint y `npm run build` verdes. Botón solo si `supported` (oculto en Firefox, sin placeholder); estados idle/listening/error con `aria-pressed` y copy en español; interim como hint `aria-live=polite` bajo el formulario; transcript anexado al detener (elección deliberada documentada: `continuous=false` hace que onend siga al resultado final, un único append sin dobles inserciones) |
