# Vista previa del tipo de relación en el panel

**Estado:** pedida por el usuario el 2026-10-06: «en las relaciones, sobre todo el tipo de relación, en las propiedades quiero añadir una vista tipo svg para ver el dibujo del tipo de relación».
**Rama:** `feat/rutas-ortogonales-ajustables` (misma que las dos unidades anteriores en curso).
**Dependencia de orden:** la unidad de anclajes tiene un arreglo de trazado en vuelo (`T5` de `anclajes-de-relacion.md`). La escritura es de a un solo escritor por vez, así que esta unidad **arranca cuando ese arreglo cierre**.

## 0. Diagnóstico medido

| Medición | Resultado |
| --- | --- |
| ¿Existe ya la semántica del dibujo por tipo? | **Sí**: `relationshipMarkerPresentation(kind, ends)` es pura y exportada (`UmlRelationshipEdge.tsx:403`) y devuelve `{lineStyle, sourceMarker, targetMarker, realizationLabelAtTarget}` |
| ¿Está probada? | Sí, con las tablas `markerCases` y `navigationMarkerCases` en `connectors.test.tsx` |
| ¿Existe la geometría de cada marcador? | Sí, pero **suelta dentro del `<defs>`** del componente de arista; sólo `HOLLOW_TRIANGLE_MARKER` y `hollowTriangleGeometry` están exportados (`:336`, `:355`) |
| Marcadores que hay que dibujar | `open-arrow`, `hollow-diamond`, `filled-diamond`, `hollow-diamond-open-arrow`, `filled-diamond-open-arrow`, `hollow-triangle` |
| Dónde va la vista previa | En el formulario de relación, debajo del `<select id="relationship-kind-property">` |
| Qué estado refleja | El formulario (`kind`, extremos, navegabilidad), no lo guardado: si el usuario cambia el tipo, el dibujo tiene que seguirlo **antes** de guardar |

## 1. Decisiones

| Decisión | Valor | Por qué |
| --- | --- | --- |
| PRE-01 | La geometría de los marcadores se extrae a un módulo compartido (`relationship-markers.ts`) y el `<defs>` de la arista se genera **desde esa tabla** | Es la única forma de que la vista previa no se desincronice del lienzo con el tiempo. La arista no puede cambiar su dibujo: sus pruebas ya fijadas tienen que quedar verdes |
| PRE-02 | La vista previa **no inventa semántica**: se deriva de `relationshipMarkerPresentation` + la tabla de dibujos | La semántica por tipo ya está decidida y probada; duplicarla sería tener dos verdades |
| PRE-03 | Refleja el **formulario**, no el modelo guardado, incluida la navegabilidad de los dos extremos | Un extremo navegable cambia el dibujo (una asociación gana flecha). Si la vista previa no sigue al formulario, miente justo cuando el usuario está por guardar |
| PRE-04 | Es un `<svg>` chico con `role="img"` y `<title>` con el nombre del tipo | La información no puede depender del color ni del trazo: tiene que ser legible por lector de pantalla |
| PRE-05 | Fuera de alcance: la ruta (quiebres y anclajes) y el estilo por vista (grosor, radio, guiones) | Lo pedido es «el dibujo del **tipo** de relación»: eso es la línea y sus extremos. La ruta ya se ve en el lienzo |

## 2. Plan de trabajo

- [ ] **T1 (RED/GREEN, extracción + descriptor puro)** `web/src/diagram/relationship-markers.ts`: tabla con los seis dibujos (`viewBox`, `markerWidth/Height`, `refX/refY`, `path` o `paths`, `fill`, `stroke`, sufijo de id) y un descriptor puro `relationshipPreview(kind, ends)` que devuelve `{ lineStyle, start, end }` reutilizando `relationshipMarkerPresentation`. `UmlRelationshipEdge.tsx` pasa a emitir su `<defs>` desde la tabla. Pruebas: toda variante que `relationshipMarkerPresentation` puede devolver **tiene** dibujo en la tabla (si mañana alguien agrega un marcador y se olvida del dibujo, la suite lo dice); los seis tipos mapean a lo esperado; y las pruebas ya fijadas de la arista siguen verdes sin tocarlas. **El dibujo del lienzo no cambia**: la prueba es que las existentes no se editan.
- [ ] **T2 (RED/GREEN, componente y lugar)** `RelationshipKindPreview.tsx` (SVG de muestra, ~140×44: línea recta, marcador de origen a la izquierda, marcador de destino a la derecha, guiones cuando corresponde) colocado debajo del selector de tipo en `PropertiesPanel.tsx`, alimentado por el estado del formulario (`kind` + los dos extremos). Pruebas por `renderToStaticMarkup` con los seis tipos y con un extremo navegable, fijando que el marcador correcto aparece en el extremo correcto y que la línea es sólida o discontinua según el tipo.
- [ ] **T3 (compuertas y cierre)** `npm test`, `npm run lint`; registrar el conteo y el commit. (El backend no se toca: no hace falta `mvn verify`.)

## 3. Fuera de alcance, y por qué

| Ítem | Motivo |
| --- | --- |
| Previsualizar la ruta real (quiebres, anclajes, «Reset route») | La vista previa es de la **semántica del tipo**; la ruta se ve en el lienzo y no aporta a esa decisión |
| Previsualizar el estilo por vista (grosor, radio de esquina, guiones manuales) | Es apariencia por vista, no el dibujo del tipo; además el panel ya lo edita con sus propios controles |
| Vista previa en otros lugares (menú contextual, lista de relaciones) | No lo pidió el usuario; un lugar, bien hecho |

## 4. Evidencia

Se completa al cerrar cada tarea, con el conteo medido y el commit de la unidad.
