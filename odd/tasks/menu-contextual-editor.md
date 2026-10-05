# Menú contextual del editor + multiplicidad por extremo (estilo EA)

> **Estado:** plan congelado tras exploración con evidencia (`gentle-ai-explore`, task `muvp38xw-7-l0vp`). Rama `feat/context-menu-multiplicidad` desde `main` = `e65fbb4`, worktree `1erParcialV2-uxmenu`.
> **Decisión del usuario (2026-10-05):** menú completo estilo EA; submenú de multiplicidad con las 4 opciones mapeables.

## 1. Corte visible que define el incremento

Click derecho sobre una clase o relación del lienzo abre un menú contextual estilo Enterprise
Architect. Sobre una relación, el menú incluye **Multiplicidad ▸** con `1`, `0..1`, `1..*`, `0..*`;
el extremo que se edita se elige por **posición**: click derecho cerca del extremo A edita A, cerca
de B edita B (con las etiquetas de multiplicidad ya renderizadas como puntos de anclaje). Todo
cambio pasa por el pipeline de comandos existente (undo/redo gratis) y queda verificado con tests.

## 2. Base verificada en el código (exploración, con path:line)

- React Flow v12; edge custom `umlRelationship` (`web/src/diagram/UmlCanvas.tsx:36`); callbacks
  `onNodeClick/onEdgeClick/onPaneClick` en `CanvasFrame` (`UmlCanvas.tsx:562-565`);
  **`onNodeContextMenu`/`onEdgeContextMenu`/`onPaneContextMenu` existen y no se usan** (0 matches de
  `onContextMenu` en `web/src`).
- Etiquetas de multiplicidad ya renderizadas por extremo: `placeRelationshipLabels` +
  `<EdgeLabelRenderer>` con `RelationshipEndLabel` (`UmlRelationshipEdge.tsx:498-507, 653-669`),
  divs `.uml-edge-label-position` hoy con `pointer-events: none` (`styles.css:1389-1392`); coords
  disponibles en `labelPlacements.source/target`.
- Update de relación: `buildRelationshipUpdateRequest` (`commands.ts:185-199`) — payload
  **posicional** `source/target` (por ordinal, no por id); el panel envía ambos extremos completos
  (`PropertiesPanel.tsx:233+, save() ~310`). Aplicación optimista: `applyOptimisticCommand` caso
  `relationship.update` (`commands.ts:496-511`).
- Multiplicidades: `MULTIPLICITY_NOTATION` (`uml-format.ts:7-20`): `EXACTLY_ONE:'1'`,
  `ZERO_OR_ONE:'0..1'`, `ONE_OR_MANY:'1..*'`, `ZERO_OR_MANY:'0..*'`. `RANGE` queda fuera del menú
  (decisión del usuario: 4 opciones mapeables).
- Overlays existentes para apoyarse: waypoint buttons dentro de `EdgeLabelRenderer`
  (`UmlRelationshipEdge.tsx:677-707`), Escape en `handleKeyDown` (`UmlCanvas.tsx:435-445`),
  clamp de viewport en `web/src/ui/floatingPosition.ts`.
- Undo/redo: endpoints de backend, sin comandos inversos en el cliente; todo `relationship.update`
  despachado por el menú es deshacible sin trabajo extra.

## 3. Alcance

### 3.1 Menú contextual (items activos, respaldados por comandos existentes)

| Item | Clase | Relación | Respaldo |
| --- | --- | --- | --- |
| Editar (abre panel de propiedades) | ✅ | ✅ | `onSelect` + `PropertiesPanel` |
| Duplicar | ✅ si existe comando de creación con payload replicable | ❌ | `classifier.create` (verificar en `commands.ts` durante T1) |
| Borrar | ✅ | ✅ | comandos delete existentes (verificar nombre exacto en T1) |
| Copiar como SVG / Imagen | ✅ si el lienzo es serializable | — | serialización client-side; si no es robusta, queda deshabilitado |
| Multiplicidad ▸ (A/B) | — | ✅ | `relationship.update` con payload completo |

Items sin soporte en el modelo/backend (cortar, copiar, z-order, bloquear, puntos de conexión,
notas, estilos predeterminados): se renderizan **deshabilitados** con tooltip "no soportado por el
modelo" — el menú se ve completo pero no miente. Lista registrada en esta acta.

### 3.2 Selección de extremo (A vs B)

- Click derecho **sobre la etiqueta** de un extremo ⇒ ese extremo queda preseleccionado (las
  etiquetas pasan a ser interactivas: se quita `pointer-events: none` solo para ellas).
- Click derecho **sobre la línea** ⇒ se preselecciona el extremo cuyo ancla (`sourceX/Y` o
  `targetX/Y`) esté más cerca del cursor (test de distancia sobre `points[0]` / último punto).
- El submenú muestra encabezado con el rol/extremo elegido ("Extremo A — Desarrollo") para que el
  usuario vea a quién aplica.

### 3.3 Fuera de alcance

- Backend (payloads parciales de `relationship.update`: se envía el payload completo, igual que el
  panel — el backend no se toca).
- RANGE con bounds (modelo sin bounds; incremento posterior).
- La sesión paralela trabaja backend/xmi en el worktree principal: ningún archivo de
  `backend/` entra en esta rama.

## 4. Unidades

| Unidad | Contenido | Verificación |
| --- | --- | --- |
| T1 | `ContextMenu` (componente + estado en `CanvasFrame`, anclaje con clamp a viewport, Escape/click-away) y wiring de `onNodeContextMenu`/`onEdgeContextMenu`/`onPaneContextMenu`; items activos/deshabilitados según §3.1 | tests de componente (apertura, cierre, dispatch por item); `npm run lint` + `npm test` |
| T2 | Submenú Multiplicidad con las 4 opciones; dispatch `relationship.update` con payload completo preservando el otro extremo | tests del payload (extremo cambiado + extremo preservado); suite completa |
| T3 | Extremos interactivos: etiquetas clickeables (preselección por etiqueta) y preselección por distancia al click derecho sobre la línea | tests de preselección A/B; suite completa + build |
| T4 | Cierre: acta, README, commit final | `npm run lint`, `npm test`, `npm run build` |

## 5. Convenciones de ejecución

- Test-first donde haya RED significativo (dispatch, preselección, cierre de menú); para CSS puro,
  verificación estructural.
- Artefactos técnicos en inglés; acta en español (convención del repo).
- Commits por unidad (Conventional Commits); evidencia en este documento.
- Trabajo exclusivamente en `1erParcialV2-uxmenu`; jamás tocar `backend/` ni el worktree principal.

## 6. Estado de ejecución

| Unidad | Estado | Evidencia |
| --- | --- | --- |
| T1 | **cerrado** | commit `feat(editor)` (ver evidencia abajo); RED observado (imports no resueltos), GREEN 24/24 nuevos, suite completa 339 passed / 4 skipped (base 315/4), lint y tsc limpios. Desviaciones: sin entorno DOM en el repo, tests como lógica pura + markup (convención modalBehavior); Duplicar deshabilitado (classifier.create no copia atributos/operaciones); Copiar como SVG/Imagen deshabilitado (canvas HTML+SVG no serializable client-side); módulo puro renombrado a contextMenuBehavior.ts por colisión case-insensitive en Windows con ContextMenu.tsx |
| T2 | pendiente | — |
| T3 | pendiente | — |
| T4 | pendiente | — |
