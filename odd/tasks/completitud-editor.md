# Completitud del editor — auditoría y cierre de brechas

> **Estado:** auditoría hecha, tareas por delante.
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
| **B5** | `BL-CMP-02` — Restricción | Solo se admiten restricciones **de clasificador**; la severidad queda fija en `ERROR`, no se muestra en el lienzo y una restricción existente no se puede editar | `ConstraintAddHandler.java:41-46`, `PropertiesPanel.tsx:113-127,166-195`, `UmlClassNode.tsx:195-212` |
| **B6** | `BL-REL-09` — Navegabilidad | Existe el control, pero en las relaciones que no son asociación el marcador se elige **por tipo** y no refleja el valor editado: cambiar la navegabilidad de una dependencia no cambia nada | `PropertiesPanel.tsx:269-292`, `UmlRelationshipEdge.tsx:321-337` |
| **B7** | `BL-FEA-01`…`BL-FEA-04` — Filas de miembro | El texto de miembro usa `ellipsis`, así que tipo, parámetros, retorno o valor predeterminado pueden quedar cortados. El valor predeterminado va al final de la fila, justo donde corta | `styles.css:833-837`, `uml-format.ts:64-82` |

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
| `parameter.update` | Hay `parameter.add` y `parameter.remove`; sin `update`, corregir el tipo de un parámetro obliga a borrarlo y recrearlo |
| `constraint.update` | Expresión y severidad solo se envían al crear |
| Cambiar el `namespaceId` de un clasificador ya creado | **Resuelto en T4:** `classifier.setNamespace` valida paquetes del mismo modelo, auto-namespace y ciclos; la paleta permite asignar o limpiar el paquete |
| Reasignar el clasificador de un extremo | `relationship.update` acepta rol, multiplicidad y navegabilidad, pero no `classifierId` |
| Restricción con dueño relación | La línea base admite ese dueño y el modelo no tiene camino para él |

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
- **Entregable:** `constraint.update`, severidad visible y editable, restricciones de relación si el modelo lo
  admite; y que el marcador refleje la navegabilidad en todos los tipos, no solo en asociación.

### T6 — Filas de miembro sin truncar (B7)

- **Objetivo:** que un tipo, un retorno o un valor predeterminado no desaparezcan por el ancho.
- **Entregable:** ajuste del ancho de la tarjeta al contenido, o ajuste de línea, en vez de `ellipsis`.

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
| T5 — Restricciones y navegabilidad | pendiente | — |
| T6 — Filas sin truncar | pendiente | — |
