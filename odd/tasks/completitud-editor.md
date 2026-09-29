# Completitud del editor — auditoría y cierre de brechas

> **Estado:** auditoría hecha; T5, T6 y advertencia de bundle cerradas; T3 y captura de T4 pendientes.
> **Origen:** al mirar el incremento cerrado, el usuario señaló que «le faltan más cosas para que sea
> completo, como la multiplicidad o la asociación de clases».
> **Producto:** diagramador colaborativo de clases UML 2.5 (PRIMER PARCIAL, Ingeniería de Software I).
> **Rama:** `feat/incremento-1-modelo-y-editor`.

## 1. Por qué existe este documento

El usuario miró el producto terminado y dijo que le falta multiplicidad y forma de asociar clases. **Las dos
cosas existen en el modelo, en la API y en el contrato.** Entonces la pregunta correcta no era «¿qué falta
programar?» sino:

> **De los 23 constructos que la línea base declara soportados, ¿cuáles puede una persona ver y usar de verdad
> en el editor?**

Desde el asiento del usuario, **la presentación es la funcionalidad**: un constructo que está en la base de datos
pero no se puede ver ni alcanzar desde la pantalla, para quien lo usa, no existe. Esta auditoría recorre los 23
constructos y para cada uno responde tres preguntas con evidencia: ¿está en el modelo y la API?, ¿es alcanzable
en la interfaz?, ¿se ve dibujado?

Esto cierra el punto **C1** que el [acta del Incremento 1](../../docs/puds/cierre/03-cierre-incremento-1.md) §2.2
dejó pendiente.

## 2. Veredicto de la auditoría

### 2.1 Lo que está completo

Doce constructos tienen las tres columnas en verde, con evidencia en el código: `BL-CLS-01` (clase), `BL-CLS-02`
(clase abstracta), `BL-CLS-03` (interfaz), `BL-FEA-01` (atributo), `BL-FEA-02` (operación), `BL-FEA-04` (valor
predeterminado), `BL-VIS-01` y `BL-VIS-02` (visibilidad pública y privada), `BL-REL-01` a `BL-REL-06` (las seis
relaciones con su marcador), `BL-CMP-05` (disposición separada de la semántica).

### 2.2 Las brechas, ordenadas por cuánto duelen

| # | Constructo | Qué falta exactamente | Evidencia |
| --- | --- | --- | --- |
| **B1** | `BL-FEA-03` — Parámetro | **No se pueden crear ni editar parámetros desde la interfaz.** El panel de operación expone retorno, visibilidad y banderas, pero ningún control de parámetros, y la selección no tiene un tipo `parameter`. Una firma existente se dibuja; no se puede escribir una nueva | `PropertiesPanel.tsx:405-471`, `selection.ts:1-6`, `ModelResource.java:89-103` |
| **B2** | `BL-REL-08`, `BL-REL-10` — Rol y multiplicidad de extremo | **El rótulo se calcula y después se descarta si no encuentra una ubicación segura.** La arista queda visible pero el extremo pierde su información: en el diagrama de demostración se lee `customer` y **no** su multiplicidad ni el nombre de la asociación | `UmlRelationshipEdge.tsx:166-189,518-533` |
| **B3** | `BL-REL-01` (y las demás) — Descubribilidad | **Los ocho anclajes tienen `opacity: 0`** y solo aparecen con hover, selección o foco. No hay herramienta de relación en la barra: la única forma de asociar dos clases es descubrir por accidente que hay anclajes invisibles | `styles.css:883-895`, `UmlClassNode.tsx:231-249`, `UmlCanvas.tsx:326-340` |
| **B4** | `BL-CLS-06` — Paquete | **Implementado en T4; captura visual live pendiente:** la paleta crea paquetes, el panel permite asignar o quitar su espacio de nombres con validación contra ciclos, y el lienzo muestra notación de carpeta | `ClassifierSetNamespaceHandler.java`, `UmlCanvas.tsx`, `PropertiesPanel.tsx`, `UmlClassNode.tsx`; pruebas backend y web; script extendido |
| **B5** | `BL-CMP-02` — Restricción | **Cerrada en T5:** restricciones de clasificador y relación se pueden crear, leer y editar; expresión y severidad persisten. El lienzo dibuja `{expresión}` y solo agrega `⚠` o `ⓘ` para severidad no predeterminada; `ERROR` no agrega ruido | `ConstraintAddHandler.java`, `ConstraintUpdateHandler.java`, `ProjectApiService.java`, `PropertiesPanel.tsx`, `UmlClassNode.tsx`; pruebas HTTP y capturas en `docs/capturas/completitud-editor/` |
| **B6** | `BL-REL-09` — Navegabilidad | **Cerrada en T5:** asociación, agregación, composición y dependencia dibujan flechas en los extremos navegables; generalización y realización conservan su triángulo fijo hacia el padre y no dibujan navegabilidad | `UmlRelationshipEdge.tsx`, `connectors.test.tsx`; captura en `docs/capturas/completitud-editor/01-member-wrapping.png` |
| **B7** | `BL-FEA-01`…`BL-FEA-04` — Filas de miembro | **Cerrada en T6:** las filas envuelven texto dentro del ancho redimensionable; la tarjeta permite scroll si el contenido supera su alto. No hay `ellipsis` sobre los datos | `styles.css`, `UmlClassNode.tsx`, `uml-node.test.ts`; captura en `docs/capturas/completitud-editor/01-member-wrapping.png` |

### 2.3 Lo que **no** es una brecha, y conviene decir por qué

- **`BL-CMP-04` — identificador estable: no se muestra, y está bien.** La regla de presentación del incremento
  dice que la pantalla no muestra UUID. Que el lienzo no exponga el id es el requisito cumplido, no una falta.
  La auditoría lo listó como brecha y **se rechaza ese punto**.
- **La multiplicidad de las cuatro categorías existe.** `EXACTLY_ONE`, `ZERO_OR_ONE`, `ONE_OR_MANY` y
  `ZERO_OR_MANY` se ofrecen en el panel, el parser las acepta y el lienzo las formatea como `1`, `0..1`, `1..*` y
  `0..*`. Lo que falta es **verlas**, que es B2. La queja «no hay multiplicidad» es cierta para **rangos
  arbitrarios**, y eso es `BL-REL-07`, declarado no soportado por la línea base y rechazado nombrando el
  constructor y `GAP-05`.
- **Los constructos no soportados se declaran, no se descubren.** Enumeraciones y literales (`BL-CLS-04`,
  `BL-CLS-05`), visibilidad protegida y de paquete (`BL-VIS-03`, `BL-VIS-04`), estático y abstracto en atributo
  (`BL-FEA-05`, `BL-FEA-06`), notas (`BL-CMP-01`) y estereotipos (`BL-CMP-03`) se rechazan con motivo.

### 2.4 Comandos que faltan en el modelo, no solo en la pantalla

| Falta | Por qué importa |
| --- | --- |
| `parameter.update` | **Resuelto en T3a** en el modelo; la edición de la firma desde la interfaz sigue dentro de T3 |
| `constraint.update` | **Resuelto en T5:** actualiza expresión y/o severidad; las pruebas releen ambos campos desde el recurso del modelo |
| Cambiar el `namespaceId` de un clasificador ya creado | **Resuelto en T4:** `classifier.setNamespace` valida paquetes del mismo modelo, auto-namespace y ciclos; la paleta permite asignar o limpiar el paquete |
| Reasignar el clasificador de un extremo | `relationship.update` acepta rol, multiplicidad y navegabilidad, pero no `classifierId` |
| Restricción con dueño relación | **Resuelto en T5:** alta, lectura, edición y limpieza al borrar la relación; otros dueños polimórficos siguen rechazados nombrando el tipo |

## 3. Tareas

Ordenadas por impacto sobre alguien que quiere dibujar un diagrama de clases.

### T1 — Que la información del extremo no se pierda (B2)

- **Objetivo:** que rol y multiplicidad se vean siempre, sin tapar ninguna caja.
- **Entregable:** reemplazar la omisión por un **desplazamiento perpendicular** al trazado cuando el hueco no
  alcanza para la etiqueta centrada. La regla geométrica actual se conserva como primer intento.
- **Evidencia:** prueba que, para un hueco corto, la etiqueta **existe** y su caja estimada no intersecta
  ninguno de los dos rectángulos; captura del diagrama de demostración donde se lean las dos multiplicidades y
  el nombre de la asociación.
- **Superficie:** `web/src/diagram/UmlRelationshipEdge.tsx`, `web/src/diagram/connectors.test.tsx`.

### T2 — Que los anclajes se descubran (B3)

- **Objetivo:** que se vea que dos clases se pueden conectar.
- **Entregable:** anclajes visibles en reposo con un indicador tenue y completo al hover o al seleccionar el
  nodo; y una entrada en la barra que explique la conexión o entre en modo conectar. Sin agregar una librería.
- **Evidencia:** captura donde los anclajes se vean sin interacción previa; prueba de que siguen siendo
  alcanzables por teclado.
- **Superficie:** `web/src/styles.css`, `web/src/diagram/UmlClassNode.tsx`, `web/src/diagram/UmlCanvas.tsx`.

### T3 — Parámetros de operación en la interfaz (B1)

- **Objetivo:** poder escribir una firma completa desde el editor.
- **Entregable:** lista de parámetros en el panel de la operación, con alta, edición de nombre y tipo,
  dirección, orden y baja; y `parameter.update` en el backend, porque hoy solo hay alta y baja.
- **Evidencia:** recorrido contra el backend real: crear una operación con dos parámetros, cambiarlos de orden,
  renombrar uno y ver la firma actualizada en el lienzo; pruebas de aceptación y de rechazo de `parameter.update`.
- **Superficie:** `backend/src/main/java/com/diagramador/command/**`, `web/src/diagram/PropertiesPanel.tsx`,
  `web/src/diagram/selection.ts`, `web/src/diagram/commands.ts`, `backend/src/test/**`.

### T4 — Paquetes (B4)

- **Objetivo:** que el paquete sea un constructo usable, no una fila de la base.
- **Entregable:** alta de paquete desde la paleta, asignación de `namespaceId` a un clasificador, y notación de
  paquete en el lienzo.
- **Decisión cerrada:** el paquete se dibuja como un nodo con notación de carpeta. La contención geométrica no está implementada: los miembros conservan posiciones independientes en el lienzo y el namespace es semántico.

### T5 — Restricciones y navegabilidad (B5, B6)

- **Objetivo:** cerrar las dos brechas de fidelidad que quedan.
- **Entregable:** `constraint.update` acepta `expression` y/o `severity`; las severidades permitidas son `INFO`, `WARNING` y `ERROR`, y el rechazo nombra el valor inválido. Constraints de classifier y relationship se crean, serializan dentro de su dueño y editan en el mismo panel. El diagrama deja `ERROR` sin marcador y usa `⚠`/`ⓘ` para severidades no predeterminadas. Asociación, agregación, composición y dependencia dibujan flechas según la navegabilidad; generalización y realización conservan su triángulo hacia el padre.

### T6 — Filas de miembro sin truncar (B7)

- **Objetivo:** que un tipo, un retorno o un valor predeterminado no desaparezcan por el ancho.
- **Entregable:** ajuste de línea dentro del ancho del nodo y scroll de la tarjeta si el contenido supera su alto; la tarjeta conserva su redimensionador. Se elige envolver en vez de crecer el ancho para no alterar posiciones vecinas ni deshacer el tamaño elegido por la persona.

## 4. Definición de terminado

- [ ] Rol y multiplicidad visibles en el diagrama de demostración, sin ninguna caja tapada.
- [ ] Los anclajes se descubren sin pasar el mouse.
- [ ] Una operación con parámetros se puede escribir de punta a punta desde el editor.
- [ ] Cada brecha cerrada tiene su prueba y su captura.
- [ ] Las brechas que se decidan **no** cerrar quedan escritas con su motivo, no en silencio.

## 5. Registro de progreso

| Tarea | Estado | Evidencia |
| --- | --- | --- |
| Auditoría de los 23 constructos | **hecha** | §2, con `file:line` por constructo; cierra C1 del acta |
| T1 — Rol y multiplicidad siempre visibles | **hecho** | La colocación en el trazado sigue siendo el primer intento; si no hay punto seguro, la etiqueta se **desplaza perpendicularmente** en pasos de 8 px, probando ambos sentidos, hasta que su caja estimada libre los dos rectángulos y las etiquetas ya colocadas. Verificado en el diagrama: se leen `places`, `customer` y **`orders [0..*]`**. **Intercambio declarado:** esa última queda debajo de la caja, visible pero menos pegada a su extremo. Visible y algo ambiguo es mejor que invisible |
| T2 — Anclajes descubribles | **hecho** | En reposo los ocho anclajes se ven como puntos de 8 px a `opacity: 0.62`, y pasan a 11 px y opacidad plena con hover, selección o foco; además son alcanzables por teclado. La barra dice **«Drag from one class dot to another to connect them.»** Verificado en la captura |
| T3 — Parámetros en la interfaz | pendiente | — |
| T4 — Paquetes | **implementación hecha; captura live pendiente** | `classifier.setNamespace` con 5 pruebas backend; selector/paleta/notación con pruebas web; `capture-editor.mjs` exige estados reales; falta ejecutar con un editor live que contenga Customer, una relación, una operación y un paquete |
| T5 — Restricciones y navegabilidad | **hecho** | `constraint.update` cubre `expression` y `severity`; `ERROR`, `WARNING` e `INFO` releídos vía HTTP; rechazo de severidad `TRACE` y constraint desconocida que nombra el UUID; constraint de relación creada, leída, editada y borrada con su dueño; rechazo de constraint desconocida; sin migración. Marcadores probados por tipo. Capturas reales en `docs/capturas/completitud-editor/02-class-constraints.png` y `03-relationship-constraints.png` |
| T6 — Filas sin truncar | **hecho** | Prueba con tipo de miembro largo comprueba contenido intacto, `overflow-wrap:anywhere`, `white-space:normal`, ausencia de ellipsis y scroll del nodo; captura `docs/capturas/completitud-editor/01-member-wrapping.png` |
| Bundle — advertencia de chunk | **hecho** | Antes: `npm run build` emitía un chunk JS de **503.18 kB** (gzip 158.34 kB), sobre 500 kB. Después de cargar el editor por ruta dinámica: `index` **268.48 kB** (gzip 85.52 kB) y `ProjectEditorPage` **238.36 kB** (gzip 74.12 kB); ningún chunk supera el límite y Vite no advierte. CSS: **33.42 kB** (gzip 6.63 kB) |
