# Panel de exportación XMI (RF-09, la mitad que faltaba en la UI)

**Estado:** plan congelado el 2026-10-05 sobre `feat/vista-compacta-editor` (punta `93880ae`, ya empujada a `origin`).
**Origen:** el usuario reportó "no veo el botón para exportar". La investigación descartó una regresión propia y encontró un hueco real de producto.
**Autorización:** el usuario eligió explícitamente "Sí, implementá el panel de export XMI" el 2026-10-05.

## 0. Diagnóstico medido antes de congelar

| Hecho | Evidencia |
| --- | --- |
| El backend **ya exporta** XMI en esta rama | `POST /projects/{projectId}/xmi/export` en `backend/src/main/java/com/diagramador/api/XmiExportController.java:32`, con `requireEditor` en `:37`. No lleva cuerpo. |
| Devuelve documento + verificabilidad | `XmiExportResource(String fileBase64, String checksum, UUID exchangeId, List<XmiEmissionResult.Loss> losses)` (`XmiExportController.java:44-45`); `Loss(String name, int count)` (`backend/src/main/java/com/diagramador/xmi/XmiEmissionResult.java:15`). |
| **La web nunca lo llama** | Cero coincidencias de `xmi/export` o `exportXmi` en `web/src`. El cliente solo expone `previewXmi` (`web/src/api/client.ts:340`) e `importXmi` (`:346`). |
| El enunciado exige **las dos** mitades | `docs/analisis-enunciado-parcial-1.md:108` — *"RF-09 \| Importar y exportar XMI. \| Un modelo puede intercambiarse con la versión acordada de Enterprise Architect conservando los elementos soportados."* |
| El manual manda a una UI inexistente | `docs/3-documentacion-uso-campos-completos.md:315` — *"**Exportar:** menú **File → Export model file…**. El panel **Export XMI**…"*, con captura `12-exportar-xmi.png` en `:330`. **Esta app no tiene menú File ni panel Export XMI**: esa documentación describe otro proyecto. |
| El único export real de la UI es otro | `Download ZIP` del backend generado, dentro del diálogo **Generation**, y **solo se renderiza** si una corrida terminó en `SUCCEEDED` sin diagnósticos bloqueantes (`web/src/generation/GenerationPanel.tsx:239-241`, predicado en `web/src/generation/presentation.ts:122-128`). |
| No hubo regresión | El commit `93880ae` solo reubicó montajes de paneles; `git show HEAD -- web/src/pages/ProjectEditorPage.tsx` no borra ningún botón. |

**Conclusión:** la mitad de RF-09 está implementada en el backend y es **inalcanzable desde la interfaz**. No es un defecto del rediseño: es una capacidad sin superficie.

## 1. Decisión de diseño (VCE-12)

**El panel va DENTRO del diálogo `XMI interchange` existente, junto al `XmiImportPanel`, no como un quinto botón de la barra.**

Razones, ambas medidas:

1. Importar y exportar son las dos mitades de la misma tarea del enunciado, así que compartir diálogo refleja el dominio. Es el mismo criterio que ya agrupa presencia + chat en `collaboration`.
2. El registro `EDITOR_SECTIONS` está congelado con un test que afirma **exactamente cuatro** ids en orden (`web/src/pages/editorSections.test.ts`, y el propio plan de la vista compacta lo declaró contrato). Agregar un quinto botón obligaría a romper un contrato congelado por una razón de acomodo, no de producto.

El diálogo `xmi` pasa a contener dos paneles apilados, separados por el mismo patrón de separador que ya usa `collaboration` (`.editor-modal-body .presence-panel + .chat-panel` en `web/src/styles.css`).

## 2. Unidades

| Unidad | Título | Prerrequisitos | Estado |
| --- | --- | --- | --- |
| XE-01 | Cliente y tipos: `exportXmi(projectId)` en `ApiClient` + `XmiExportResource` reusando `XmiLossResource` | — | pendiente |
| XE-02 | Panel `XmiExportPanel`: exportar, descargar el `.xmi`, informar checksum y pérdidas | XE-01 | pendiente |
| XE-03 | Montaje dentro del diálogo `XMI interchange` junto al import + separador CSS | XE-02 | pendiente |
| XE-04 | Verificación: suite + lint + exportación real contra el backend + corrección del manual | XE-03 | pendiente |

Regla test-first: XE-01 y XE-02 tienen escenario determinista (cliente con transporte inyectable y marcado estático) → RED observado antes de GREEN. XE-03 y la mitad visual de XE-04 son composición y CSS: la verificación es proporcional y **la geometría exige navegador real**, porque este repo no tiene jsdom ni testing-library y `renderToStaticMarkup` no ejecuta efectos.

## 3. Riesgos declarados

1. **Duplicar el informe de pérdidas.** `LossReport` es una función local de `web/src/xmi/XmiImportPanel.tsx` (`:61`) y no se exporta. Se prefiere **duplicar un listado mínimo** en el panel nuevo antes que refactorizar el panel de import, que tiene su propio archivo de test (`XmiImportPanel.test.tsx`) y no debe cambiar de contrato. Deuda aceptada y declarada.
2. **Nombre del archivo.** El backend no devuelve filename; solo `fileBase64`. El nombre se deriva del nombre del proyecto en la página y se sanea. Debe declararse, no inventarse como si viniera del servidor.
3. **El base64 no debe ir por `innerHTML` ni por un `href` con datos.** Se decodifica con `atob` a `Uint8Array` y se crea un `Blob` con `URL.createObjectURL`, siguiendo el camino ya probado de `GenerationPanel.tsx:577-582`. El objeto URL se revoca.
4. **La guarda de edición es del servidor.** `XmiExportService` aplica la guarda de exportación declarada; el cliente debe mostrar su conflicto con el mapeo existente y no duplicar la regla.
5. **El manual quedará mintiendo si no se corrige.** XE-04 incluye corregir `docs/3-documentacion-uso-campos-completos.md:307-332` para que describa el diálogo real y no un menú File que no existe. Es parte de la unidad, no un extra.
