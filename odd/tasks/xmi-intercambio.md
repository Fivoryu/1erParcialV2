# Intercambio XMI — exportación e importación (RF-09, CU-16, CU-17)

> **Estado:** plan creado, ejecución por etapas. Autorizado por el usuario en esta sesión.
> **Producto:** diagramador colaborativo de clases UML 2.5 (PRIMER PARCIAL, Ingeniería de Software I).
> **Rama:** `feat/xmi-intercambio`, en el worktree `../1erParcialV2-xmi`, apilada sobre
> `feat/incremento-3-colaboracion` en `27c3a69`.
> **Autoridad del alcance:** pedido del usuario de esta sesión — «exportación e importación de XMI; que se vean
> bien las figuras en EA; poder exportar de EA y poder ver los diagramas en la app también».
> **Autoridad del dialecto:** [`docs/puds/contratos/04-dialecto-xmi-ea.md`](../../docs/puds/contratos/04-dialecto-xmi-ea.md)
> **más la medición propia de §3**, que corrige dos afirmaciones de ese documento.
> **Contrato congelado con la sesión de colaboración:** `odd/tasks/incremento-3-colaboracion.md` §S6b.

## 0. Frontera con la sesión que continúa el Incremento 3

Esta unidad nace de un handover explícito, no de una suposición. La sesión `01a0f72e-5721`, dueña del plan del
Incremento 3, cedió C22/C23/C24 después de verificar que **no existía ningún archivo ni endpoint de XMI** (así el
handover no duplica trabajo), bajó esas tres tareas de su todo, y congeló el contrato de integración en §S6b de
su plan. Lo que queda atado:

| Punto | Acuerdo |
| --- | --- |
| Migraciones | Esta sesión toma **`V10`**; la otra toma `V8` (invitaciones) y `V9` (chat). Dos archivos con la misma versión no se pueden fusionar y Flyway los rechaza |
| Concesión de edición | `com.diagramador.collaboration.EditingLeaseService#requireExportAllowed(UUID modelId)`, que lanza `ApiException(409, "EXPORT_BLOCKED_EDITING")`. El principal de operador queda exento |
| Forma del bloqueo | Código `EXPORT_BLOCKED_EDITING`; en endpoints de archivo HTTP **409** con el sobre `ApiErrorResource`; en `POST /projects/{id}/generations` el mismo código dentro del cuerpo `outcome` con HTTP 200 (convención D7 del Incremento 2). Claves de `details[0]`: `editorDisplayName`, `targetType`, `targetId`, `reclaimable`, `retryAfterSeconds` |
| Archivos compartidos | `backend/src/test/java/com/diagramador/SchemaOwnershipTest.java` (compuerta dura: hoy declara que `xmi_exchange` y `xmi_loss_item` **no existen**; al consolidar la unión es versiones 1–10 y las cuatro tablas, con `invitation` de la otra sesión), `docs/puds/datos/02-esquema-fisico.sql`, `web/src/pages/ProjectEditorPage.tsx` |
| **No compartido** | `backend/src/main/java/com/diagramador/command/CommandService.java` es de la otra sesión (su tarea C13). **Esta unidad no lo toca**: verificado que el servicio resuelve handlers por `Collectors.toUnmodifiableMap(CommandHandler::type, …)` sobre una `List<CommandHandler>` inyectada por constructor, así que un `@Component` nuevo se auto-registra |
| **Pendiente declarado** | El endpoint de XMI nace **sin** la guarda de la concesión, porque `C19` no está en esta base. Queda como integración de una línea al consolidar, y **así se declara**: hasta entonces la exportación XMI no está protegida contra una edición en curso. **La línea ya tiene nombre y forma, medidos en §13.7**: `requireExportAllowed(UUID modelId)`, que lanza `ApiException(409, "EXPORT_BLOCKED_EDITING")`. **Cerrada en §14.2**: la línea se conectó en `XmiExportService.java:61`, con su prueba primero en rojo y la compuerta completa medida. El comportamiento de la concesión **en vivo** —quién la toma, cuándo vence, qué pasa con el operador— sigue siendo de la otra unidad |

## Estado de ejecución

Se actualiza en cada transición. Una etapa solo dice **cerrada** cuando sus pruebas pasan.

| Etapa | Estado | Evidencia |
| --- | --- | --- |
| X1 — Oráculo del dialecto | **Cerrada** | `python -m pytest tools -q` = **46 pruebas, 0 fallos** (escritor y padre). Verificador independiente: **6 HOLDS**, con **cuatro mutaciones** probando que las aserciones rechazan datos falsos. El arnés además **refutó dos afirmaciones mías** (§3.4) |
| X2 — Esquema y persistencia | **Cerrada**, con un límite declarado | `mvn -o -B -f backend/pom.xml verify` = **232 pruebas, 0 fallos** (escritor y padre). Verificador independiente: **5 HOLDS**, 1 UNPROVEN que cerré inline con `git show`, y **1 FAILS que es el borde de la compuerta** —detecta deriva unilateral, no una omisión coordinada—, declarado en §5.2 |
| X3 — Emisión del dialecto | **Cerrada**, reconciliada en §13.1 | §5.2: X3.1+X3.1b → `mvn verify` = **237 pruebas, 0 fallos**, y un verificador que no ejecutó nada encontró **dos defectos reales**, corregidos en `bc9a825`; X3.2a → **239 pruebas, 0 fallos, BUILD SUCCESS**, con los cinco bloques, las claves del registro por elemento y los **26** tipos de pérdida. Determinismo (X3.3): `XmiSemanticEmitterTest.outputIsByteDeterministicAcrossEquivalentCollectionOrders` |
| X4 — Servicio y endpoint de exportación | **Cerrada**, reconciliada en §13.1 | §5.2: **257 pruebas, 0 fallos** (escritor y padre) y `XmiExportApiTest` 3 pruebas; el padre además movió dos pruebas de API que vivían en `xmi/` declarando `package com.diagramador` |
| X5 — Lectura del dialecto | **Cerrada**, reconciliada en §13.1 | §5.2: **244 pruebas, 0 fallos** (escritor y padre), `XmiReaderTest` 4 pruebas y `XmiSemanticEmitterTest` 8, incluida la ida y vuelta `emittedCanonicalModelReadsBackWithSemanticsAndDiagramAnchors` |
| X6 — Importación: comando de lote y endpoints | **Cerrada**, reconciliada en §13.1 | §5.2: X6a → **249 pruebas, 0 fallos** con cinco escenarios contra PostgreSQL; X6b → **254 pruebas, 0 fallos**, más una causa probable nueva para la flake: cuatro contenedores ajenos compitiéndole memoria a Testcontainers |
| X7 — Web | **Cerrada**, reconciliada en §13.1 | §5.2: el escritor declaró `partial` porque el worktree no tenía `node_modules`; el padre corrió las tres compuertas: `npm test` = **93 pasan, 4 omitidas (97)**, lint limpio y build ok. Y una aserción **imposible de satisfacer** —React escapa los apóstrofes— se normalizó sin ablandar la redacción |
| X8 — Arnés y cierre | **En curso; se cierra en §13** | El arnés existe, corre **sin Docker** sobre dos archivos y hoy da **15 pasadas y 3 fallas** (`2026-10-03`). Las tres están **medidas como defectos del oráculo**, no del emisor (§13.2) |
| Verificación en EA (§7) | **Cerrada en §10 y §11** | Sentido app → EA: GAP-07 con causa raíz medida, el arquero de dialecto en `xmi:Documentation`. `t_diagram` 90 → 91, ids preservados y 2 figuras ancladas sobre una copia de `sw1_p2.eapx`. Sentido EA → app: medido en §11 con exportaciones reales de EA |
| E — El sentido EA → app | **Cerrada en §11** | 5 artefactos de EA pinchados por sha256 y byte-exactos; el lector medido sobre la matriz de exportación; `mvn verify` = **260 pruebas, 0 fallos, 0 salteadas**; `python -m pytest tools -q` = **46 pruebas, 0 fallos**; revisión nativa `review-79d56d80554b3cd0` = **approved**, autoridad quemada |
| Consolidación y publicación | **Cerrada en §12** | Fusión de `feat/incremento-3-colaboracion` (`61bf2d2`) en `feat/xmi-intercambio` como unión de los dos lados, commit `f3f8e33` de dos padres; `mvn verify` = **307 pruebas, 0 fallos, 0 salteadas**; `pytest` = **46**; `npm run lint` y `npm run build` limpios; remoto privado creado y `main` movida a la punta por decisión del usuario |
| F — Lo que el oráculo no medía | **Cerrada en §13** | Las seis filas X3–X8 reconciliadas contra §5.2; el control positivo con el artefacto de EA en los dos casilleros **refutó dos de los tres «defectos» de §7.5**; el diagnóstico del dialecto mira las declaraciones de namespace, no el prefijo; arnés **18/0** en los dos corpus y las suites verdes (`mvn verify` **308 pruebas, 0 fallos**; `pytest` **54**); revisión nativa `review-5fceca1cdf2aad8c` = **approved**, autoridad quemada, tres avisos no bloqueantes en §13.6 |
| G — La guarda de exportación, conectada | **Cerrada en §14** | Fusión de `feat/incremento-3-colaboracion` (`38882b4`) en `f2fd947`, sin conflictos y con auditoría semántica contra el diff del par; la guarda declarada quedó conectada en `XmiExportService.java:61` con su prueba **primero en rojo** (5/1 → 5/0) y su control positivo; la fusión trajo **2 fallas reales** en la web, reparadas en `2b47c66`; `mvn verify` = **332 pruebas, 0 fallos, 0 salteadas, BUILD SUCCESS**; `pytest tools` = **54**; web **158 pasan, 4 omitidas, 0 fallan** con lint, tipos y build limpios; arnés XMI **18/0** |

## 1. Corte visible que define la unidad

El intercambio cierra cuando se puede hacer esto, de punta a punta y en los dos sentidos:

> **Sentido EA → app.** Se importa un archivo exportado por Enterprise Architect (`docs/fixtures/xmi/Prueba3.xml`,
> el fixture canónico) eligiendo proyecto y modo. La app aplica el modelo —clases, interfaz, enumeración con sus
> constantes, atributos, operaciones, herencia, asociación, agregación y multiplicidades— **y su diagrama**, con
> cada figura en la posición y el tamaño que traía el archivo, y el lienzo lo muestra sin recargar.
>
> **Sentido app → EA.** Se exporta el modelo y su diagrama a un documento del dialecto de EA. Ese documento, al
> importarse en Enterprise Architect, muestra las mismas figuras con la misma disposición y las mismas etiquetas
> de rol y multiplicidad en los conectores. Toda pérdida se declara en el reporte; no se afirma conservación total.

**Corte que sí se puede afirmar hoy**, y **corte que no**: el round-trip *dentro de la app* (exportar → importar →
comparar identificadores, semántica y geometría) se puede medir y afirmar. El round-trip *contra EA en vivo* no:
GAP-07 sigue abierto y §7 lo declara. Nadie firma «se ve bien en EA» sin la verificación de §7.

## 2. Alcance

| Pieza | Qué entra |
| --- | --- |
| Emisión | Documento XMI 2.1 / UML 2.1 con `xmi:Extension extender="Enterprise Architect"`: subárbol semántico, registros de elemento y de conector, y **bloque `diagrams` con la geometría de cada figura** |
| Lectura | Adaptador del dialecto de EA que separa `Model` de `Extension`, reconstruye el inventario con sus trampas medidas y **cuenta** lo que no puede representar |
| Reporte de pérdidas | `xmi_exchange` + `xmi_loss_item` persistidos en los dos sentidos, y **el reporte viaja en la respuesta**, no solo en la base |
| Modos de importación | **Reemplazar** (borra el contenido del modelo destino y deja solo lo importado) y **Agregar al lado** (conserva lo existente y suma el diagrama importado), elegidos por el usuario y confirmados |
| Vista previa | Analizar antes de aplicar: qué se reconoció, qué se pierde, qué colisiona. **La vista previa no escribe nada** |
| Guardas | Modelo válido y sin colisiones abiertas para exportar; archivo válido y proyecto con rol suficiente para importar |
| Web | Panel de importación (archivo → vista previa → modo → confirmar) y botón de exportación con el reporte de pérdidas visible |

### 2.1 Fuera de alcance, y por qué

| Fuera | Motivo |
| --- | --- |
| Round-trip contra EA en vivo | **GAP-07**. El add-in del MCP no contesta desde este entorno (medido, §7). Se mide la pérdida contra el fixture; contra EA en vivo, no |
| Preservar el ruteo de conectores del archivo de EA | **Medido** (§3.4): en el fixture el `Path=` de los conectores está **vacío**, así que EA no guarda el ruteo sino que lo recalcula. Inventar un formato de `Path` no medido sería fingir una capacidad. Se exporta el `Path=` vacío y se declara |
| Preservar el layout por atributo de EA | **Medido** (§3.5): 21 bloques `coords`/`bounds`/`containment` cuelgan de los `<attribute>` de la extensión. El modelo canónico no tiene geometría por atributo. Se cuentan como pérdida declarada |
| Esteroides y perfiles de EA | El fixture trae un `<profiles>` de un hijo y 32 bloques `stereotype` vacíos (§3.6). El perfil UML-BL-1 no los usa. Se reconocen y se apartan |
| Offline, conflictos y panel de colisiones | Es unidad propia del Incremento 3. La importación rechaza o reporta, no abre un panel nuevo |
| Varios diagramas por proyecto | `uml_model.project_id` sigue `UNIQUE`. Se exporta la vista por defecto; se importa **una** vista por archivo |

## 3. Mediciones propias sobre `Prueba3.xml`

Todo lo de esta sección se midió con script propio sobre el fixture, y **corrige o precisa** al contrato del dialecto. Es la base de
de las decisiones de §4: el contrato se lee, la medición manda.

> **Esta sección se corrigió a sí misma.** Una primera versión afirmó que el `Path` de los conectores estaba
> vacío **en todos** los casos. Era falso, y el error no lo encontré yo: lo encontró el escritor de X1 al medir
> las 8 colocaciones una por una en vez de muestrear, y la corrección está en §3.4. Desde entonces las
> mediciones del dialecto no son prosa: son **aserciones ejecutables** con recuentos exactos, que es la única
> forma de que un error así falle en vez de propagarse.

**Fixture pinneado.** `docs/fixtures/xmi/Prueba3.xml`, 91.440 bytes, sha256
`336d13b1ae87da5643dc74903e9787899aaeb3896eb2ca30d5c5a74a2cd0dab2`, declarado `-text` en `.gitattributes`
(byte-exacto) y **verificado**: el hash del blob en `HEAD` coincide con el del disco. Ese hash es guarda de prueba.

### 3.1 Estructura de la extensión

```text
xmi:XMI
├─ xmi:Documentation      exporter / exporterVersion
├─ uml:Model              (name="EA_Model", SIN xmi:id)   -> el árbol semántico
└─ xmi:Extension extender="Enterprise Architect"
   ├─ elements       (11)   registro por elemento del modelo, con <properties documentation=...>
   ├─ connectors      (8)   registro por conector, con <source>/<target>
   ├─ primitivetypes  (1)
   ├─ profiles        (1)
   └─ diagrams        (1) -> <diagram> -> <elements> (18 colocaciones)
```

**Corrección 1 al contrato.** El contrato (§4.4) ubica los conectores en `connectors/connector/...` sin decir
dónde cuelgan. Medición: en el fixture **`<connectors>` es hijo directo de `Extension`**, hermano de
`<diagrams>`; la colocación del conector en el lienzo es otra cosa y vive en `<diagram>/<elements>`. Un lector
que busque `connectors` dentro de `<diagram>` —como aparece en los archivos de referencia de `docs/xmi-backup/`—
no encuentra nada. Es exactamente la trampa de «clasificar por contexto, no por etiqueta» que el contrato §2.3
advierte, cometida por el propio contrato.

### 3.2 El registro de un elemento de la extensión

```xml
<element xmi:idref="EAID_6E23CBF8_..." xmi:type="uml:Class" name="Agente" scope="public">
  <model package="EAPK_..." tpos="0" ea_localid="191" ea_eleType="element"/>
  <properties documentation="Agente inmobiliario que publica inmuebles." sType="Class"
              isRoot="false" isLeaf="false" isAbstract="false" isActive="false"/>
  <project author="HP" created="..." modified="..." status="Proposed"/>
  <code gentype="Java"/>
  <style appearance="BackColor=16777215;BorderColor=5325111;..."/>
  <tags/> <xrefs/> <extendedProperties/>
  <attributes> ... </attributes>
  <operations> ... </operations>
  <links> ... </links>
</element>
```

Notas: el `xmi:idref` de la extensión **es el mismo identificador** que el `xmi:id` del `packagedElement` en
`Model` (confirmado: la intersección de identificadores entre ambos subárboles es lo que hace que el diagrama
resuelva). `documentation` es el canal real de las notas (§4.6 del contrato, confirmado por medición: los textos
«Agente inmobiliario que publica inmuebles.» y «Cliente que realiza reservas…» existen).

### 3.3 El registro de un conector, y de dónde salen las etiquetas

```xml
<connector xmi:idref="EAID_E84130F9_..." name="realiza">
  <source xmi:idref="EAID_C63E7789_...">
    <model ea_localid="183" type="Class" name="Cliente"/>
    <role name="cliente" visibility="Public"/>
    <type multiplicity="1" aggregation="none"/>
    <modifiers isOrdered="false" isNavigable="false"/>
    <style value="Navigable=Unspecified;Owned=0;"/>
  </source>
  <target xmi:idref="EAID_4684DADD_..."> ... <role name="reservas"/><type multiplicity="0..*"/> ... </target>
  <properties ea_type="Association" subtype="&lt;none&gt;" direction="Unspecified"/>
  <appearance linemode="3" linecolor="-1" linewidth="0" headStyle="0" lineStyle="0"/>
  <labels lb="1" lt="+cliente" mt="realiza" rb="0..*" rt="+reservas"/>
  <tags><tag name="_lineStyle" value="orthogonalR"/></tags>
</connector>
```

**Lo que esto habilita.** `role@name` da el **nombre de rol** de cada extremo y `type@multiplicity` su
multiplicidad — la forma `0..*`, no el enum del canónico. `labels` da el texto que EA dibuja en el lienzo
(`lt`/`rt` = roles con el prefijo de visibilidad, `lb`/`rb` = multiplicidades, `mt` = nombre del conector).
`<role>` aparece 8 veces bajo `source` y 8 bajo `target`: 16, coincidente con el contrato. `properties@ea_type`
distingue `Generalization` de `Association`, que es la única señal de la extensión para la herencia.

### 3.4 La geometría del diagrama y el canal de ruteo

Las colocaciones de figura llevan geometría rectangular; las de conector, otra cosa:

```xml
<!-- figura -->
<element geometry="Left=380;Top=100;Right=610;Bottom=235;" seqno="2"
         subject="EAID_6E23CBF8_..." style="DUID=2B72FD4C;"/>
<!-- 7 de los 8 conectores -->
<element geometry="SX=0;SY=0;EX=0;EY=0;Path=;" subject="EAID_E84130F9_..."
         style=";Hidden=0;"/>
<!-- el octavo -->
<element geometry="SX=0;SY=0;EX=0;EY=0;Path=622:-260$;"
         subject="EAID_D1614536_..." style=";Hidden=0;"/>
```

**Corrección de una afirmación mía.** La primera versión de esta sección decía que el `Path` estaba **vacío en
todas** las colocaciones de conector y titulaba «por qué el ruteo no se puede preservar». **Era falso.** Medido
exhaustivamente —las 8 colocaciones, una por una, y las 8 apariciones de `Path=` en todo el documento—:
**7 están vacías y 1 no**, con el valor `622:-260$` en el conector
`EAID_D1614536_5F17_45c9_8786_75858AA78AF0`. La afirmación salió de mirar una muestra truncada de las
apariciones, que es exactamente el error que el contrato §1 advierte: **aparición de una etiqueta no es
evidencia de la capacidad, y su ausencia en una muestra tampoco lo es de su inexistencia**.

Qué se sostiene y qué no, después de corregir:

| Hecho medido | Consecuencia |
| --- | --- |
| Existen 18 colocaciones: 10 figuras con `Left/Top/Right/Bottom` y `seqno`, 8 conectores con `SX/SY/EX/EY` y sin `seqno` | La distinción figura/conector es real y se mide, no se supone |
| **El canal de ruteo existe**: 1 de 8 conectores trae `Path` con contenido | No se puede afirmar que el dialecto no transporte ruteo. Se puede afirmar que **casi nunca** lo hace |
| Hay **una sola** muestra de `Path` con contenido, y su valor es `622:-260$` | Con un punto de datos **no se establece la gramática**. El `$` parece separar puntos y `:` separar coordenadas, pero **no se sabe si `622:-260` es una coordenada absoluta del diagrama o un desplazamiento relativo**: las 10 figuras viven en coordenadas positivas (`Left` 80–1310, `Top` 80–840) y ese valor tiene un `y` negativo |
| El conector con más datos trae `Mode`, `EOID` y `SOID` — y viven en su atributo **`style`**, no en `geometry` | El anclaje se hace con `SOID`/`EOID` contra el `DUID` de cada figura. Verificado que coinciden: `SOID=4C5579B1` es `Usuario` y `EOID=C0CFAA2E` es `Cliente` |
| Ese mismo conector trae en su `geometry` bloques `$LLB=`, `LLT=`, `LMT=`, `LRT=`, `LRB=` con `CX`/`CY`/`OX`/`OY` | Es la geometría de las **etiquetas** del conector, un canal aparte del ruteo |

**Los atributos no se mezclan, y conviene decirlo junto porque me equivoqué en dos pasos distintos:** el canal de
ruteo vive en **`geometry`** —las 8 apariciones de `Path=` están ahí, las 8—, y el de anclaje vive en **`style`**
—`Mode`, `EOID` y `SOID` aparecen una vez cada uno, las tres en `style` y **ninguna** en `geometry`—. Son dos
atributos con dos contenidos distintos de la misma colocación, y confundirlos es un error de lectura que este
documento cometió y ahora deja escrito.

Consecuencias, explícitas y sin adornos:

1. **La disposición de las figuras es transportable** y es lo que hace que «se vean bien»: es el canal que se
   emite y se lee.
2. **El ruteo del canónico se declara pérdida, pero por un motivo distinto del que escribí antes.** No porque el
   dialecto no lo transporte, sino porque **el fixture no alcanza para deducir la gramática**: una muestra no
   fija si las coordenadas son absolutas o relativas. Emitir un `Path` inventado sería afirmar una capacidad que
   no se midió, y el propio contrato lo prohíbe. Se emite `Path=` vacío —como EA hace en 7 de 8 casos— y el
   canal queda registrado como **candidato a una iteración verificada contra EA**, con la muestra ya pinneada.
3. Al importar, un `Path` vacío **no** se convierte en cero waypoints silenciosos: se registra como «ruteo
   recalculado por EA». Y un `Path` con contenido **se cuenta y se reporta** como no transportado, en vez de
   descartarse sin decir nada.
4. Las etiquetas del conector y su geometría (`$LLB`, `LMT`, …) son un tercer canal, tampoco transportado, y
   también se cuentan.

### 3.5 El layout por atributo es un canal aparte

Los 21 bloques `coords`, 21 `bounds` y 21 `containment` **no** son ruteo de conectores: son hijos de los
`<attribute>` de la extensión, o sea posición de cada atributo dentro de su compartimento. El canónico no tiene
ese concepto. Se cuentan como pérdida declarada.

**Verificado exhaustivo, no por muestra.** Esta afirmación la escribí primero mirando los primeros 3 bloques —el
mismo vicio que produjo el error de §3.4—, así que la conté otra vez sobre el árbol entero, con el padre de cada
nodo construido por recorrido completo: las 21 apariciones de cada uno de los tres bloques tienen `<attribute>`
de padre y `<attributes>` de abuelo, sobre los **21 objetos `<attribute>` distintos**, sin una sola excepción.
Esta vez se sostiene.

Dos datos que solo aparecen cuando se cuenta bien, y que le sirven al lector:

- Entre esos 21 atributos están las cuatro constantes de la enumeración —`PENDIENTE`, `CONFIRMADA`, `CANCELADA`,
  `COMPLETADA`—, lo que confirma **también en la extensión** que las constantes viajan como atributo y no como
  literal UML. No es una rareza del subárbol semántico: es el dialecto.
- `containment` trae `position` con valores `0` (9 veces), `1` (6), `2` (5) y `3` (1): es la **posición del atributo
  dentro de su compartimento**, que se corresponde con la noción de `ordinal` que el canónico ya tiene. Este canal
  es entonces **parcialmente representable**, a diferencia del ruteo. Se registra igual como pérdida, porque el
  canónico no guarda la geometría del compartimento, pero queda anotado como candidato más barato que el `Path`.

### 3.6 Andamiaje vacío y el prefijo de datos propietarios

Recuento sobre el documento completo, para no volver a confundir «existe la etiqueta» con «hay contenido»:
`style` 78, `tags` 78, `xrefs` 78, `documentation` 67, `properties` 63, `styleex` 43, `stereotype` 32,
`ownedAttribute` 25, `modifiers` 22, `initial` 21, `options` 21, `coords` 21, `bounds` 21, `containment` 21,
`role` 16, `constraints` 16. Nada de esto se importa: se cuenta lo que lleva contenido y se declara lo que no.

### 3.7 El `Model` está sucio y el identificador del modelo falta

`uml:Model` viene con `name="EA_Model"` y **sin `xmi:id`**: un lector que exija identificador en la raíz rechaza
el fixture canónico. Dentro de `Model` conviven `uml:Class` 8, `uml:Property` 33, `uml:Package` 1,
`uml:Interface` 1, `uml:Enumeration` 1, `uml:PrimitiveType` 4, `uml:Association` 6, `uml:Generalization` 1,
`uml:Realization` 1, más `LiteralInteger` 62 y `LiteralUnlimitedNatural` 4 (las multiplicidades).

### 3.8 Codificación

El fixture declara `encoding="windows-1252"` y el texto trae acentos reales («Prueba didáctica»). El lector
**honra la codificación declarada**; asumir UTF-8 corrompe las notas. La emisión sale en UTF-8.

### 3.9 Las formas exactas de serialización, y una tercera corrección mía

Un escritor me corrigió dos veces antes de que yo midiera esto, y esta tercera corrección también es mía: le
ordené emitir `value="*"` para las multiplicidades sin cota, y **el carácter `*` no aparece ni una vez en todo el
documento**. Medido sobre los **66** valores, uno por uno:

| Elemento | `xmi:type` | `value` | Veces |
| --- | --- | --- | ---: |
| `lowerValue` | `uml:LiteralInteger` | `1` | 29 |
| `lowerValue` | `uml:LiteralInteger` | `0` | 4 |
| `upperValue` | `uml:LiteralInteger` | `1` | 29 |
| `upperValue` | `uml:LiteralUnlimitedNatural` | `-1` | 4 |

Reglas que se derivan y que la emisión tiene que respetar:

1. Una cota **finita** es `uml:LiteralInteger`, **incluida la superior**. No es `LiteralUnlimitedNatural`.
2. Una cota **sin límite** es `uml:LiteralUnlimitedNatural` con **`value="-1"`**. Nunca `*`.
3. `lowerValue` y `upperValue` llevan su propio `xmi:id`.

Y las formas de los demás constructos, todas del mismo barrido exhaustivo:

| Constructo | Forma medida |
| --- | --- |
| `ownedAttribute` | `xmi:type="uml:Property"`, hijos en orden **`lowerValue`, `upperValue`, `type`**; el tipo es un **elemento** `<type xmi:idref>`, no un atributo. 19 de los 23 lo llevan; los 4 restantes no llevan ninguno, que es el caso de referencia sin destino |
| `ownedOperation` | `xmi:id`, `name`, `visibility`, `concurrency="sequential"`, y **sin `xmi:type`** |
| `ownedParameter` | Los 11 del subárbol semántico son de retorno. **10 de 11 llevan el tipo como atributo** (`type="EAJava_Inmueble"`) y sin hijos; **1 de 11** lleva un `<type>` sin `idref`, que es el caso sin resolver. La forma conforme es el **atributo**, no el elemento |
| `ownedEnd` | `xmi:type="uml:Property"`, con **`association` apuntando al id de la asociación**, `aggregation`, y hijos `type`, `lowerValue`, `upperValue` |
| `generalization` | **Anidada** en el `packagedElement` de la subclase: `xmi:type="uml:Generalization"`, `xmi:id`, `general="<id del padre>"` |
| `realization` | **De nivel superior**: `packagedElement xmi:type="uml:Realization"` con `name`, `visibility`, **`supplier`** y **`client`**. No es anidada, y las dos no se parecen entre sí |
| `memberEnd` | Dos por asociación, y solo `xmi:idref` |
| Tipos primitivos | `packagedElement xmi:type="uml:PrimitiveType"` agrupados en `EA_PrimitiveTypes_Package`, `EA_Java_Types_Package` y `EA_none_Types_Package`, con ids `EAJava_*` y `EAnone_*` |
| `uml:Model` | `xmi:type="uml:Model"`, `name="EA_Model"`, `visibility="public"` y **sin `xmi:id`** |

Dos números que parecen contradecirse y no lo hacen, porque conviene dejarlo escrito: el documento trae **25**
`ownedAttribute` y el subárbol semántico **23**, porque 2 viven en la extensión. Y los **33** `uml:Property` se
descomponen en esos 23 `ownedAttribute` más **10** `ownedEnd`. Mi consigna al escritor de X5 decía «33
`ownedAttribute`»; **él lo midió, lo refutó y reportó la descomposición correcta**. Es la **cuarta** vez en esta
sesión que alguien más corrige un número mío, y las cuatro veces el error estuvo en una medición que yo había
tratado como firme.

**Tercera lección, y la más incómoda.** Tres veces escribí una afirmación sobre el dialecto a partir de una muestra
o de memoria, y tres veces la medición la refutó: el `Path` «vacío en todas», el ancla «en `geometry`», y ahora la
multiplicidad «`*`». Las tres las encontró otro —dos veces un escritor, una vez un verificador—, nunca yo. Lo que
sí funcionó fue la regla de §6: la medición no vive en la prosa, vive como **aserción ejecutable con recuento
exacto**. Estas formas entran como aserciones en las pruebas de la emisión, no como párrafos que alguien tenga que
recordar.

### 3.10 Segundo archivo de EA: corroboración independiente, la geometría, y la puerta que faltaba

Apareció en los recientes de EA un segundo archivo escrito por EA, `C:\Users\HP\Downloads\ea-ref-with-diagram.xmi`
(27.503 bytes, `encoding="windows-1252"`, `xmi:version="2.1"`, `exporter="Enterprise Architect"`). **Tiene diagrama**,
que es justo el canal que X3.2 debe emitir. Sirve para dos cosas: corrobora lo medido sobre una sola muestra, y
responde la geometría, que era el hueco de X3.2.

**Corroboraciones**, todas contra un archivo distinto del fixture:

1. Las multiplicidades repiten la regla **exacta**: `LiteralInteger` para lo finito (`0` ×5 y `1` ×7 en la cota
   inferior, `1` ×7 en la superior) y `LiteralUnlimitedNatural` con `-1` (×5) para lo ilimitado. **Cero `*`**, otra
   vez. La corrección de §3.9 deja de apoyarse en una muestra.
2. `<connectors>` es hijo directo de `Extension`, hermano de `<diagrams>`.
3. `uml:Model` **no lleva `xmi:id`**.
4. La codificación declarada es `windows-1252`.

**La geometría.** El `<diagram>` lleva `id` (no `xmi:id`) y cuelga `elements`, que mezcla **figuras y conectores en
una sola lista**, distinguidos por las claves de `geometry`:

| | `geometry` | otros atributos |
| --- | --- | --- |
| **Figura** | `Left=50;Top=50;Right=250;Bottom=140;` | `subject="EAID_…"`, `seqno="1"`, `style="DUID=31201C34;"` |
| **Conector** | `SX=0;SY=0;EX=0;EY=0;Path=;` | `subject="EAID_…"`, `style=";Hidden=0;"`, **sin `seqno`** |

Dos cosas que no sabía y que importan:

- `geometry` es un **atributo con pares separados por `;`**, no un elemento con hijos. Confirma la corrección de
  §3.2 y explica por qué `Mode`/`EOID`/`SOID` viven en `style`.
- El conector **no lleva `seqno`** en este archivo, y su `Path=` sale vacío porque EA recalcula el ruteo. Es
  evidencia a favor de X-4 por una razón mejor que la mía: EA **tampoco** escribe waypoints en su propio archivo.

Y el bloque `Extension/connectors/connector`, cada uno con `idref` a la relación, con estos hijos: `source` y `target`
(con `xmi:idref`), un bloque de rol por extremo con `model` (`ea_localid`, `type`, `name`), `role` (`name`,
`visibility`), `type` (`multiplicity='0..*'`, `aggregation`), `modifiers` (`isOrdered`, `isNavigable`) y `style`
(`value='Owned=0;Navigable=Unspecified;'`); después `model`, `properties` (`ea_type`, `direction`), `modifiers`
(`isRoot`, `isLeaf`), `appearance` (`linemode`, `linecolor`, `linewidth`, `seqno`, `headStyle`, `lineStyle`),
`labels` (`lb`, `lt`, `rb`, `rt`) y `style`. Confirmado: la multiplicidad en el canal de la extensión se escribe como
**texto** `0..*`, y el rol lleva el prefijo `+` de visibilidad.

**Y la puerta que faltaba, que es lo que cierra GAP-07.** El add-in MCP de EA responde pero es frágil —respondió una
vez y después se colgó dos veces— y **no expone importar ni exportar XMI**. Pero `CreateObject("EA.Repository")`
**sí** funciona desde el PowerShell de **32 bits** (EA es de 32 bits; desde 64 bits la instancia viva no aparece en
la tabla de objetos activos), y expone `OpenFile`, `CreateModel`, `ExportPackageXMI` e **`ImportPackageXMI`**. O sea:
puedo importar lo que emitimos dentro de una **copia** de un proyecto real y ver si EA lo acepta, sin tocar el
original. `ExportPackageXMI` es **lentísimo** (24 exportaciones no terminaron en 10 minutos), así que se usa con
cuentagotas; la importación es lo que faltaba.

Dos cautelas que corresponden. Primero, EA es una repack «EA Trial / Cracked Edition from Brains»; eso **no**
invalida el dialecto —el archivo lo escribe el mismo motor— pero es un dato que hay que declarar en cualquier
afirmación de interoperabilidad. Segundo, que EA **escriba** `-1` sigue sin probar que **rechace** `*`; con
`ImportPackageXMI` eso deja de ser una pregunta retórica, y hay que contestarla con un experimento antes de dar
X-4 y la emisión de multiplicidades por cerradas.

## 4. Decisiones cerradas

| # | Decisión | Alternativa descartada | Motivo |
| --- | --- | --- | --- |
| X-1 | **Emitir `EAID_`/`EAPK_` derivados del UUID canónico**, con biyección reversible | Emitir el UUID crudo como `xmi:id` | El dialecto medido usa `EAID_`/`EAPK_` en las 139 referencias del fixture y el diagrama resuelve `subject` contra ese espacio. La derivación es reversible (mayúsculas + `_` por `-`), así que el round-trip conserva identidad y a la vez se habla el dialecto del destino. Al leer se aceptan **las dos** formas |
| X-2 | El **bloque `diagrams`** con la geometría es obligatorio en la emisión | Exportar solo el árbol UML | Sin `diagrams` el archivo importa el modelo pero **no dibuja nada**, que es justo el pedido del usuario |
| X-3 | Los conectores se emiten con **`source`/`target` en el registro** *y* `SOID`/`EOID` contra el `DUID` de cada figura | Confiar solo en `source`/`target` | El fixture ejerce **los dos** mecanismos a la vez. Emitir los dos hace que el conector caiga en las figuras correctas aunque EA use uno u otro |
| X-4 | `Path=` **vacío** y el ruteo del canónico como **pérdida declarada** | Inventar puntos intermedios | Medido en §3.4, **y corregido**: el dialecto **sí** transporta ruteo —1 de 8 conectores trae `Path=622:-260$`—, pero **una sola muestra no fija la gramática**: no se sabe si esas coordenadas son absolutas del diagrama o desplazamientos relativos, y el `y` negativo frente a figuras en coordenadas positivas deja la duda abierta. Emitir un formato deducido de un punto de datos sería afirmar una capacidad no medida. Se emite vacío, como EA en 7 de 8, y el canal queda pinneado como candidato para una iteración verificada contra EA |
| X-5 | **Revertida en §10.2 por medición.** El `exporter`/`exporterVersion` de `xmi:Documentation` son el **arquero de dialecto** que EA lee, no un campo de procedencia. Se emite `exporter="Enterprise Architect" exporterVersion="6.5"`, y la procedencia real viaja en un **comentario XML** | Declarar la app en el `exporter` | La decisión original era por honestidad de procedencia y **declaró el riesgo textualmente**: «si EA exigiera su propio nombre en `exporter`, es un cambio de una línea, y solo la verificación de §7 lo decide». La verificación lo decidió: con un `exporter` ajeno **0 de 11 ids sobreviven** (EA asigna GUID nuevos) y `t_diagram` queda en 90 → 90; con el arquero de EA los **11 sobreviven exactos** y `t_diagram` 90 → **91**, con las figuras ancladas. La honestidad se conserva por otro canal, que es el comentario |
| X-6 | **Vista previa sin escrituras**, y confirmación que reenvía los bytes con `checksum` | Guardar el archivo en el servidor entre vista previa y confirmación | La vista previa está obligada por CU-16 y no puede tocar el proyecto. Guardar el temporal introduce estado que hay que expirar y limpiar; comparar el `checksum` del reenvío es determinista y no deja basura |
| X-7 | Los dos modos, **REEMPLAZAR** y **AGREGAR**, elegidos por el usuario | Un solo modo | Decisión del usuario en esta sesión, textual: «borrar todo» o «poner el diagrama junto a lo que estaba antes». Son dos intenciones distintas y el sistema no puede adivinar cuál |
| X-8 | En **AGREGAR**, una colisión de nombre con lo existente **bloquea la confirmación** y se reporta | Renombrar con sufijo, o dejar pasar | El canónico tiene como invariante «sin colisiones de nombre abiertas» (contrato §1.2.2). Renombrar en silencio rompería la trazabilidad del archivo importado; bloquear y reportar deja la decisión en el usuario |
| X-9 | Una **sola** vista por archivo importado, y la vista por defecto al exportar | Varias vistas | `uml_model.project_id` sigue `UNIQUE` y el proyecto tiene un modelo. Declarar la frontera es más honesto que fingir multipágina |
| X-10 | El reporte de pérdidas **viaja en la respuesta** además de persistirse | Solo persistir | El requisito dice «comunicando toda pérdida». Un reporte que solo está en una tabla no comunica nada al usuario que exporta |
| X-11 | La confirmación entra por el **camino de comandos**, como un comando de lote nuevo `xmi.import` con su handler | Escribir los repositorios directo, o mandar los comandos uno por uno | **Medido** (X5 del scout): no existe entry point de lote, `ModelVersion` guarda revisión y hash y no un snapshot, y cada comando aceptado abre su propia transacción y sube la revisión. Escribir directo **saltearía** propiedad de proyecto/modelo, membresía, idempotencia, precondiciones de revisión, historial, cascadas, tombstones y el hash de estado. Mandar un comando por elemento deja **progreso parcial** y veinte revisiones. Un handler de lote aplica todo el grafo en **una** transacción, con **una** revisión y **una** fila de historial, y hereda las guardas sin escribirlas |
| X-12 | Los endpoints son **de proyecto**, no de modelo: `/projects/{projectId}/xmi/…` | Meter el `modelId` en la URL | **Medido** (X1 del scout): el modelo se resuelve por proyecto —`GET /projects/{id}/model`— y hoy no hay ninguna URL que lleve `modelId`. Inventar un `modelId` en la ruta contradiría el enrutado vigente |
| X-13 | El archivo viaja en **JSON como base64 de los bytes crudos**, y el `checksum` es sobre esos bytes | Mandar el XML como texto en el cuerpo, o multipart | El fixture **declara `windows-1252`** con acentos reales. Si el navegador lo lee como texto UTF-8, los bytes se corrompen **antes** de llegar al servidor y el `checksum` deja de significar algo. Base64 preserva los bytes exactos y deja el `checksum` verificable; además **medido** (X1 del scout): no hay ninguna convención de multipart ni de subida de archivos en este backend, así que el formato se elige, no se hereda |

## 5. Plan de trabajo

Cada tarea cierra con **al menos un commit de unidad de trabajo**, con sus pruebas y su documentación al lado del
comportamiento. El padre verifica el árbol y el diff de cada tarea.

**Regla de verificación para lanzar escritores** (heredada del plan del Incremento 3): el encargo debe decir de
forma explícita que `mvn -o -B compile` y `mvn -o -B verify` están **permitidos**, incluido el PostgreSQL que
Testcontainers levanta por su cuenta. La prohibición de contenedores alcanza a los arneses de `tools/`, no al
build. Un encargo que prohíbe contenedores y exige `verify` a la vez se contradice y detiene al escritor.

### X1 — El dialecto como oráculo ejecutable

| # | Tarea | Evidencia que la cierra |
| --- | --- | --- |
| X1.1 | Extender `tools/xmi-inventory/` para emitir el **inventario de intercambio** de `Prueba3.xml`: elementos, características, relaciones con extremos y etiquetas, colocaciones con geometría, `Path` de conectores, y el recuento de lo leído pero no representable | `python -m pytest tools -q` verde, incluido el oráculo nuevo; el inventario queda como artefacto commiteado |
| X1.2 | Pinchar el fixture por hash en la prueba | Prueba que falla si el sha256 de `Prueba3.xml` deja de ser `336d13b1…dab2` |

### X2 — Esquema, dominio y persistencia del intercambio

| # | Tarea | Evidencia que la cierra |
| --- | --- | --- |
| X2.1 | Migración `V10__xmi_exchange.sql`: tipos `exchange_direction`, `exchange_status` y tablas `xmi_exchange`, `xmi_loss_item` con sus índices, según `docs/puds/datos/02-esquema-fisico.sql` | Migración aplicada contra PostgreSQL real; prueba de esquema verde |
| X2.2 | `SchemaOwnershipTest`: sacar `xmi_exchange` y `xmi_loss_item` de `TABLES_RESERVED_FOR_LATER_TASKS` (hoy afirma que **no existen**), declararlas en `MAPPED_COLUMNS` con sus columnas exactas, agregarlas a `SUPPORT_INDEXES` (`xmi_exchange` → `{idx_exchange_model}`; `xmi_loss_item` → **vacío**), agregar los tipos de columna nuevos a `nativeEnumColumnsUseTheirPostgresType` y las etiquetas a `javaEnumsAndPostgresEnumsHaveTheSameLabels`, y sumar `"10"` a la aserción de versiones | Suite verde |
| X2.3 | Dominio y repositorios `XmiExchange`, `XmiLossItem` | Prueba de ida y vuelta de persistencia |

**Las seis compuertas reales de ese archivo, verificadas una por una** (no todas las líneas son compuerta, y creer que sí cuesta una vuelta):

| Prueba | Semántica | Qué la rompe |
| --- | --- | --- |
| `schemaOwnsExactlyTheTablesOfThisIncrement` | **exacta** (`containsExactlyInAnyOrderElementsOf` sobre `MAPPED_COLUMNS.keySet()` + `flyway_schema_history`) | crear una tabla y no declararla, o declarar una que no existe |
| la misma prueba, segunda aserción | **prohibición** (`doesNotContainAnyElementsOf(TABLES_RESERVED_FOR_LATER_TASKS)`) | migrar las tablas y dejar los nombres en la lista de reservadas |
| `everyMappedColumnExistsInItsTable` | **exacta** por tabla | una columna de más o de menos |
| `nativeEnumColumnsUseTheirPostgresType` | por columna | una columna enum sin declarar acá |
| `javaEnumsAndPostgresEnumsHaveTheSameLabels` | etiquetas + cantidad | un enum de Java y uno de PostgreSQL que no coinciden |
| `supportIndexesExactlyMatchTheAuthority` | **exacta** por tabla, y excluye los índices de restricciones primarias y únicas | inventar un índice de soporte. **PostgreSQL no crea índice para una clave foránea**, así que `xmi_loss_item` queda legítimamente en `Set.of()` |

**Trampa de esa misma consulta, que conviene tener escrita:** el `NOT EXISTS` filtra `contype IN ('p','u')`, o sea que descarta los índices que **respaldan una restricción** primaria o única. Un `CREATE UNIQUE INDEX` suelto **no** tiene fila en `pg_constraint`, así que **sí** entra en el conjunto exacto y hay que declararlo. En esta unidad no aplica —ninguna de las dos tablas lleva índice único, porque un intercambio es un registro de eventos y dos exportaciones simultáneas son legítimas—, pero el día que se agregue uno hay que declararlo como índice de soporte y no como restricción, o la prueba falla.

**Y una que no es compuerta:** `flywayAppliedBothMigrations` usa `contains("1" … "7")` de AssertJ, que es **subconjunto**: pasa igual con `"10"` de más y con `"10"` de menos. Sumarlo es higiene —que la lista diga la verdad— y no un riesgo de fusión. Nadie tiene que coordinar ahí.

### X3 — Emisión del dialecto

**Partida en dos mitades por tamaño, no por capricho.** El subárbol semántico y la extensión con el diagrama son
dos canales distintos, cada uno con evidencia propia y probable por separado; juntos exceden cómodamente una
unidad de trabajo revisable.

| # | Tarea | Evidencia que la cierra |
| --- | --- | --- |
| X3.1 | **Subárbol semántico**: `xmi:XMI` + `xmi:Documentation` + `uml:Model` con paquetes, clases, clases abstractas, interfaces y enumeraciones —cuyas constantes viajan como `ownedAttribute`, que es la forma real del dialecto y no la estándar—, atributos, operaciones, parámetros, restricciones, y asociaciones, generalizaciones y realizaciones con sus extremos, roles y multiplicidades. Identificadores `EAID_`/`EAPK_` derivados del UUID canónico por biyección reversible | Prueba por constructo; el documento parsea y cada elemento canónico tiene su contraparte; una prueba pinnea la forma de las constantes de enumeración; otra comprueba la ida y vuelta del identificador |
| X3.2 | **Extensión y diagrama**: `xmi:Extension` con `elements`, `connectors`, `primitivetypes`, `profiles` y `diagrams`, con la geometría de cada figura (`Left/Top/Right/Bottom`, `seqno`, `DUID`), el anclaje de cada conector (`Mode`, `SOID`, `EOID` contra los `DUID`) y sus `labels`; `Path=` vacío por X-4 | Prueba por constructo contra las mediciones de §3; las 10 figuras y los 8 conectores del fixture de ida y vuelta aparecen con geometría y anclaje correctos |
| X3.3 | Determinismo y reporte de pérdidas de la emisión | Dos emisiones del mismo estado dan el **mismo sha256**; el reporte enumera cada pérdida —ruteo, layout por atributo, etiquetas de conector, andamiaje— con su nombre y su recuento |

### X4 — Servicio y endpoint de exportación

| # | Tarea | Evidencia que la cierra |
| --- | --- | --- |
| X4.1 | `XmiExportService`: persistir `xmi_exchange` (dirección, perfil, revisión, `checksum`) y sus `xmi_loss_item`, y devolver documento + reporte | Integración: la fila y sus pérdidas quedan persistidas y el `checksum` corresponde al documento devuelto |
| X4.2 | Endpoint de exportación con guardas — modelo válido, sin colisiones abiertas, y el gancho de la concesión de edición declarado como pendiente (§0) | Prueba de API de **cada** guarda por separado |

### X5 — Lectura del dialecto

| # | Tarea | Evidencia que la cierra |
| --- | --- | --- |
| X5.1 | `XmiReader`: separar `Model` de `Extension` antes de contar, y reconstruir el inventario | Prueba contra `Prueba3.xml` con los **números medidos** de §3: 8 clases, 1 interfaz, 1 enumeración, 1 paquete, 6 asociaciones, 1 generalización, 1 realización, 18 colocaciones |
| X5.2 | Las trampas, una por una: constantes de enumeración desde `ownedAttribute`; navegabilidad desde `modifiers` (ausente = **no especificada**, nunca `false`); visibilidad normalizada a minúsculas; referencia de tipo sin `idref` **contada como no resuelta**, no convertida en tipo vacío; notas desde el atributo `documentation` | Prueba por trampa, y una prueba de mutación por cada una que demuestre que el oráculo la refuta |
| X5.3 | Codificación declarada y rechazo de XML inválido | Prueba con `windows-1252` real (los acentos sobreviven) y prueba de archivo corrupto |

### X6 — Importación: comando de lote, servicio y endpoints

| # | Tarea | Evidencia que la cierra |
| --- | --- | --- |
| X6.1 | `XmiImportService#preview`: inventario + pérdidas + colisiones de nombre, **sin escribir nada** | Integración: tras la vista previa el modelo y sus revisiones quedan **idénticos**; la prueba compara el estado antes y después |
| X6.2 | Comando de lote `xmi.import` con su handler (X-11): aplica el grafo importado, con la vista y su geometría, en **una** transacción. El handler se auto-registra como `@Component`, sin tocar `CommandService`; `createsProjectAndModel()` queda en `false` para que el servicio exija modelo existente y membresía | Integración: un solo comando aceptado deja **una** fila de `model_command`, **una** revisión nueva y **una** fila de `model_version`; el hash de estado corresponde al modelo resultante; un VIEWER y un no-miembro son rechazados por el servicio |
| X6.3 | Los dos modos de X-7 sobre el comando de lote, con la política de colisión de X-8. En REEMPLAZAR, cada objetivo borrado se declara en `Outcome.deletedTargets` en vez de borrarse en silencio | Integración de los dos modos; en AGREGAR una colisión **bloquea** con diagnóstico; REEMPLAZAR deja exactamente lo importado **y** persiste las cascadas y los tombstones de lo borrado |
| X6.4 | Endpoints de vista previa y de confirmación, con el archivo en base64 y el `checksum` de X-13, y la autorización que ya ejerce `CommandService` | Prueba de API: XML inválido no modifica el proyecto; sin rol suficiente no entra (VIEWER rechazado, EDITOR y OWNER aceptados); un `checksum` que no corresponde se rechaza |
| X6.5 | El bloqueo por concesión de edición, declarado como gancho pendiente (§0) con el código y la forma congelados | Prueba de que el gancho existe y está **desactivado**, para que el pendiente sea visible y no una ausencia silenciosa |

### X7 — Web: importar y exportar desde el editor

Convenciones medidas que esta etapa sigue (X1 del scout): el cliente `web/src/api/client.ts` ya sabe distinguir un ZIP de un rechazo JSON, así que la descarga de XMI reusa ese patrón; la descarga se dispara con objeto URL y un ancla temporal, como `GenerationPanel`; y las pruebas de componente usan `renderToStaticMarkup` con un `render` local, no un DOM de pruebas. **Dependencia declarada**: el manejo del `Authorization: Bearer` en el cliente es la tarea C7 de la otra sesión. Hasta que consolide, estas pantallas se prueban contra el cliente que exista en esta rama y se anota el punto de fusión.

| # | Tarea | Evidencia que la cierra |
| --- | --- | --- |
| X7.1 | Panel de importación: elegir archivo, ver la vista previa con pérdidas y colisiones, elegir modo, confirmar. El archivo se lee como **bytes** y se manda en base64 (X-13) | Pruebas de componente de los tres estados (sin archivo, con vista previa limpia, con colisión) y `npm run lint` verde |
| X7.2 | Botón de exportación con el reporte de pérdidas visible | Prueba de componente; ninguna pérdida se muestra como cero si no se midió |

### X8 — Arnés de aceptación y cierre de la unidad

Plantilla: `tools/acceptance/acceptance.py`, que es la más cercana (X6 del scout) —levantó su propio PostgreSQL con `--pull=never`, arrancó el jar empaquetado en un puerto libre, esperó `health`, autenticó con `POST /auth/register` + `POST /auth/login`, asertó con `[OK]`/`[FAIL]` y verificó que no quedara ningún contenedor propio—. El arnés de XMI sigue ese molde, no el de generación.

| # | Tarea | Evidencia que la cierra |
| --- | --- | --- |
| X8.1 | `tools/xmi-acceptance/`: exportar desde la app, reimportar lo exportado y comparar **identificadores, semántica soportada y geometría**, con teardown verificado | Corrida completa sin fallos y con el recuento de contenedores restaurado |
| X8.2 | Documento de evidencia con la matriz de pérdidas, más las capturas del lienzo antes y después de importar | Documento y capturas |
| X8.3 | Los números medidos (aserciones del arnés, pruebas de cada suite) se pasan a la sesión del Incremento 3 para que su acta de cierre no los invente | Mensaje por intercom con los números |

**Orden:** X1 → X2 → X3 → X4 → X5 → X6 → X7 → X8. X3 y X5 son independientes entre sí una vez que X1 y X2 están;
todo lo demás depende de ellas.

### 5.1 Ruta por tarea y disparador

Cada tarea declara por dónde se ejecuta y **qué disparador** la mandó por ahí, para que saltear una delegación sea
observable en vez de silencioso. Los disparadores son los del contrato de delegación del arnés: *mapeo* (entender
la tarea pide 4 o más archivos), *escritor* (la implementación toca 2 o más archivos no triviales), *incidente*
(algo se torció), *sesión larga* (unas 20 llamadas de herramienta sin delegar) y *verificación* (correr o delegar
comandos de verificación).

| Tarea | Ruta | Disparador |
| --- | --- | --- |
| X1 | Delegada a `gentle-ai-worker` | escritor (dos archivos no triviales) + sesión larga |
| X2 | Delegada | escritor (migración, dos entidades, dos repositorios y la compuerta dura de `SchemaOwnershipTest`) |
| X3 | Delegada | escritor (escritor XMI y pruebas) |
| X4 | Delegada | escritor (servicio, persistencia y endpoint) |
| X5 | Delegada | escritor (lector del dialecto y sus trampas) |
| X6 | Delegada | escritor (servicio de importación y endpoints) |
| X7 | Delegada | escritor (panel, botón y pruebas de componente) |
| X8 | Arnés delegado; capturas y documento en el padre | verificación (correr el arnés) y mapeo |
| Verificación en EA (§7) | Padre | es una acción sobre la máquina del usuario, no código del repositorio |

**Verificación de cada tarea.** El escritor corre en primer plano los comandos exactos que el padre autoriza bajo
`## Verification` y reporta cada uno como `<comando>: <resultado observado>`. Después el padre vuelve a correr un
comando reportado como spot check antes de dar la tarea por cerrada.

### 5.2 Qué verificación cerró cada etapa

Se escribe al cerrar, no al planificar. Un casillero nunca se marca sobre una afirmación, solo sobre una corrida.

| Etapa | Autoverificación del escritor | Spot check del padre | Evaluación nativa | Verificador independiente |
| --- | --- | --- | --- | --- |
| **X2** | `mvn -o -B -f backend/pom.xml verify` → **232 pruebas, 0 fallos**, con Flyway aplicando V10 contra PostgreSQL 16. El escritor además corrigió mi encargo: yo había dicho «desde la raíz del worktree», y la raíz no tiene `pom.xml` | El mismo comando, re-corrido por el padre → **232 pruebas, 0 fallos, BUILD SUCCESS** en 3:32 | **No evaluable**, y por un motivo **distinto** del de X1: ahora `schema-incompatible`, «no sanitized stderr diagnostic is available». O sea que el evaluador no falla por mi estado de trabajo: **no puede correr en este repositorio** | **Sí, por la vía de riesgo alto**, `gentle-ai-verify` sobre el esquema, con derivación **independiente** de las columnas desde la autoridad y prueba de mutación sobre la compuerta |
| **X3.1 + X3.1b** | `mvn -o -B -f backend/pom.xml verify` → **237 pruebas, 0 fallos**. El escritor además **corrigió mi encargo en cuatro lugares**, siguiendo al fixture en vez de a mi prosa: la `realization` es de nivel superior con `client`/`supplier` y no anidada, el `uml:Model` no lleva `xmi:id`, las cotas finitas no son `LiteralUnlimitedNatural`, y las operaciones no llevan `xmi:type` | Confirmado en la corrida de X3.2a: **239 pruebas, 0 fallos** | No evaluable, sin cambio: el evaluador **no puede correr en este repositorio** | **INCOMPLETO, y por culpa mía.** Le pedí verificar y a la vez lo declaré «read-only», y `mvn verify` escribe en `backend/target/`. Eligió la lectura estricta y **no ejecutó ni un comando**: todo su veredicto es análisis estático. Aun así encontró **dos defectos reales**, los dos confirmados y corregidos |
| **X3.2a** | **No hubo reporte.** El escritor terminó su trabajo pero devolvió un mensaje sobre un objetivo de revisión ajeno a la tarea, así que nadie supo qué hizo ni qué midió | El padre, en su lugar: `mvn -o -B -f backend/pom.xml verify` → **239 pruebas, 0 fallos, 0 errores, BUILD SUCCESS**, más comprobación estructural de los cinco bloques, las claves del registro por elemento y los **26** tipos de pérdida | — | Diferida, junto con X3.2b y X3.3, a **una sola verificación de la emisión completa** |
| **X5** | `mvn -o -B -f backend/pom.xml verify` → **244 pruebas, 0 fallos**. El escritor reportó con conteos medidos, declaró sus límites —que la ida y vuelta prueba el acuerdo lector/escritor y **no** la aceptación en EA— y de paso **refutó un número mío** (§3.9) | El padre, el mismo comando → **244 pruebas, 0 fallos, 0 errores, BUILD SUCCESS**; `XmiReaderTest` 4 pruebas y `XmiSemanticEmitterTest` 8, incluida la ida y vuelta `emittedCanonicalModelReadsBackWithSemanticsAndDiagramAnchors`. Las cuatro pruebas del lector cubren el fixture ajeno, la discrepancia de multiplicidad entre mitades, **el segundo archivo de EA sin asumir nuestro dialecto de UUID**, y XML malformado | — | Pendiente, y es el candidato natural: verificar lector y emisión **juntos**, que es la costura real |
| **X6a** | `mvn -o -B -f backend/pom.xml verify` → **249 pruebas, 0 fallos**, con cinco escenarios de integración contra PostgreSQL: reemplazo con cada borrado reportado, agregar, colisión de nombres diagnosticada **sin aplicación parcial**, checksum rechazado **antes** de escribir nada, y las filas de intercambio y pérdidas leídas de vuelta | El padre, el mismo comando → **249 pruebas, 0 fallos, 0 errores, BUILD SUCCESS**; `XmiImportServiceTest` 5 pruebas. Y una corrección del padre: el escritor había dejado un `verify.log` de **93 KB dentro de `src/test/java`** —un archivo de build en el árbol de fuentes— y se sacó antes de commitear | — | Pendiente: verificar la costura completa lector + importación |
| **X6b** | `mvn -o -B -f backend/pom.xml verify` → **254 pruebas, 0 fallos** en el reintento. El primer intento murió por **timeout de arranque de Testcontainers** en `DomainProjectEmitterTest`, la flake conocida | El padre, el mismo comando → **254 pruebas, 0 fallos, 0 errores, BUILD SUCCESS**, con `XmiImportApiTest` 5 pruebas. Y una **causa probable nueva para la flake**, más precisa que «colisión de puertos»: hay **cuatro contenedores de otro proyecto** corriendo desde hace 8-9 horas —`backend-main-integration-{web,backend,floci,db}`— que le disputan memoria a Testcontainers. No es solo puerto: es **contención de recursos**, y por eso aparece como timeout de arranque y no como puerto ocupado | — | Pendiente: la costura completa |
| **X7** | **No pudo validar nada**, y lo reportó como `partial` en vez de inventar un verde: este es un *worktree* de git y `node_modules` no está versionado, así que el `web/` del worktree **nunca tuvo dependencias** y `vitest`, `eslint` y `tsc` no existían | El padre resolvió el entorno y corrió las tres compuertas: `npm test` → **93 pasan, 4 omitidas (97)**; `npm run lint` → limpio; `npm run build` → ok. Y encontró que **la prueba del diagnóstico de colisión era imposible de satisfacer**: comparaba el marcado renderizado contra un apóstrofe literal, y React escapa los apóstrofes al renderizar marcado estático, así que **ninguna implementación correcta podía pasarla**. El componente estaba bien; la aserción estaba mal. Se normalizó el escapado **conservando la redacción exacta** de la aserción | — | Pendiente |
| **X4** | `mvn -o -B -f backend/pom.xml verify` → **257 pruebas, 0 fallos**. Reutilizó `ProjectApiService.getModel` para el snapshot **en vez de inventar una proyección nueva**, y dejó la nota de la guarda ausente en `exportXmi` **inmediatamente después de `requireEditor`**, que es exactamente donde va la línea al consolidar | El padre, el mismo comando → **257 pruebas, 0 fallos, 0 errores, BUILD SUCCESS**; `XmiExportApiTest` 3 pruebas y `XmiImportApiTest` 5. Y una corrección del padre: las dos pruebas de API **vivían en la carpeta `xmi/` declarando `package com.diagramador;`** —desajuste entre directorio y paquete, que compila porque el compilador no lo exige, pero desorienta a cualquiera que busque la clase— y se movieron a donde su paquete manda, junto a `DeploymentApiTest` y `GenerationApiTest`. Tras el movimiento: **26 pruebas XMI verdes** | — | Pendiente |

#### El worktree no tiene dependencias de web, y eso hay que saberlo

`node_modules` no está versionado, así que **cualquier worktree de este repositorio tiene el `web/` sin
dependencias**: `vitest`, `eslint` y `tsc` no existen, y toda validación de web falla ahí **aunque el código esté
bien**. Eso es exactamente lo que le pasó al escritor de X7, y por eso declaró `partial` con honestidad en lugar de
inventar un resultado.

Se resolvió con un **enlace directo** (`junction`) al `node_modules` del repositorio principal, después de comprobar
que `package.json` y `package-lock.json` son **idénticos** entre los dos, que es lo que hace seguro compartirlo. Queda
declarado porque es una dependencia del **entorno**, no del código: si el repositorio principal cambia sus
dependencias, la validación del worktree cambia con él sin que nadie lo pida.

#### Dos huecos que X6a declaró en vez de esconder

Corresponden al acta porque afectan lo que el usuario **ve**:

- **Las notas de los clasificadores se pierden.** El lector las toma del atributo `documentation`, y el modelo de la
  aplicación **no tiene dónde guardarlas**. Va como pérdida declarada, no como descarte silencioso, pero significa que
  un diagrama traído de EA **no muestra sus notas**.
- **`external_path` queda nulo** en las pérdidas, porque el lector entrega categorías agregadas y no rutas por
  elemento. La columna existe y admite el dato; hoy no hay quién lo produzca.

Los dos son la conducta correcta: declarar en vez de callar. Pero ninguno es «resuelto», y así se registran.

#### Una lección técnica que vale conservar

Al aplicar la importación aparecieron **colisiones de identificadores entre proyectos**: los UUID canónicos viajan en
el archivo, pero las claves de la base son **globales**, así que importar el mismo clasificador en dos proyectos
choca. La solución fue **acotar los identificadores al modelo destino**. Es un hallazgo que no estaba en ninguna
medición previa y que solo aparece cuando se aplica de verdad en vez de describirse.

#### Un verificador bloqueado igual sirvió

Vale la pena registrarlo, porque contradice la intuición de que una verificación que no ejecuta nada no aporta. El de X3.1 no corrió un solo comando y **igual encontró el defecto más grave de la unidad**:

> `XmiIdCodec.classifierId` produce siempre el prefijo `EAID_`, mientras un paquete se declara con `packageId`, que produce `EAPK_`. La generalización escribía `general` y la relación dirigida escribía `client` y `supplier` con el prefijo fijo, y la precondición de alcanzabilidad solo probaba pertenencia a `emittableClassifierIds` — donde un paquete **sí** está. Resultado: una relación con extremo en un paquete emitía una referencia a un id que el documento **nunca declara**.

Es exactamente la propiedad que decide si el archivo carga o no, y la encontró leyendo, no ejecutando. El segundo defecto fue del mismo tipo de silencio: `RelationshipEndResource.navigability` no se leía ni se reportaba, así que se perdía sin decir nada. Los dos quedaron corregidos en `bc9a825`, con `classifierXmiId` como la única vía para elegir prefijo y con `unsupported_relationship_navigability` como pérdida declarada.

**Las dos lecciones de orquestación, juntas.** La primera es que una notificación de cierre puede perderse (X1). La segunda es que **una instrucción mía puede hacer que el verificador no verifique nada**: pedir «verificar» y «read-only» a la vez no es una instrucción estricta, es una contradicción, y el costo lo pagó el usuario. La tercera, que apareció con X3.2a, es que **un escritor puede terminar bien y no reportar nada**, y entonces la evidencia tiene que producirla el padre o no existe.

#### Veredictos de los verificadores independientes

**X1 — el oráculo del dialecto.** Seis puntos. El verificador contrastó los hechos contra un probe propio sobre los bytes del fixture, y después **sometió las aserciones a mutaciones** en vez de creerles:

| # | Punto | Veredicto |
| --- | --- | --- |
| 1 | Identidad del fixture: 91.440 bytes, `windows-1252`, sha256 pinneado | **HOLDS** |
| 2 | Enumeración **exhaustiva** de los `Path=`: 7 vacíos, 1 con `622:-260$`, y exactamente 8 apariciones en todo el documento | **HOLDS** |
| 3 | `Mode`/`SOID`/`EOID` en **`style`** y ausentes de **`geometry`**; los anclajes resuelven a `Usuario` y `Cliente` | **HOLDS** |
| 4 | Estructura y recuentos: hijos de `Extension` 11/8/1/1/1, `<connectors>` hijo directo y no dentro de `diagram`, 18 colocaciones = 10 figuras + 8 conectores, 16 roles = 8 source + 8 target, los 18 subjects resueltos, `uml:Model` sin `xmi:id` | **HOLDS** |
| 5 | Que las aserciones **rechacen datos falsos** | **HOLDS** — cuatro mutaciones, cuatro rechazos |
| 6 | Geometría agregada (18) y particionada (10 + 8) | **HOLDS** |

**La quinta es la que valía el costo**, porque era el modo de falla declarado: un arnés que pasa y no puede fallar. Las cuatro mutaciones se hicieron sobre **copias en un directorio temporal, fuera del repositorio**, y las cuatro fueron rechazadas por una prueba distinta:

| Mutación | Qué pasó |
| --- | --- |
| `Path=622:-260$` → `Path=623:-260$` | Falló la prueba de partición de colocaciones y resolución de subjects |
| `SOID=4C5579B1` → `SOID=4C5579B2` | Falló la prueba de anclajes: dejó de coincidir con el `DUID` de una figura |
| Agregar `Mode`/`EOID`/`SOID` **también** a `geometry` | Falló la aserción que exige su **ausencia** en ese atributo |
| Quitar un registro `<connector>` de `Extension/connectors` | Falló la prueba de estructura, que observó 7 en vez de 8 |

**Lo que el verificador declaró NO verificado**, y coincido: el significado de la gramática de `Path` —una muestra no dice si las coordenadas son absolutas o relativas, que es justo por qué X-4 emite vacío— y la interoperabilidad con EA en vivo, que es GAP-07.

**Y una falla de orquestación que me costó tiempo muerto.** El resultado de este verificador **nunca llegó como notificación de cierre**, aunque el proceso había terminado: lo tuve que traer a mano cuando el usuario me preguntó por qué tardaba. Los otros cuatro cierres sí llegaron. Consecuencia declarada: **una notificación de cierre puede perderse**, así que antes de cerrar un turno esperando trabajo ajeno corresponde **una** consulta de estado por tarea en vuelo, que no es sondear en bucle sino mirar una vez en el borde.

**X2 — esquema y persistencia.** Seis puntos. El verificador **derivó las columnas por su cuenta desde la autoridad** en vez de leer la prueba y darle la razón, que era el único modo de cazar el riesgo de omisión compartida:

| # | Punto | Veredicto |
| --- | --- | --- |
| 1 | V10 refleja la autoridad física: columnas, tipos, precisión, nulabilidad, defaults, claves y `ON DELETE CASCADE` | **HOLDS** — sin diferencias. Incluye que `checksum`, `artifact_ref` y `model_revision` sean nullable: es lo que dice la autoridad |
| 2 | `MAPPED_COLUMNS` y `SUPPORT_INDEXES` declaran lo que migró V10, y ninguna columna de la autoridad falta de **ambos** | **HOLDS** |
| 3 | V10 reutiliza `severity_kind` y define los dos enums en el orden de los enums Java | **HOLDS** |
| 4 | V10 es aditiva, no hay V8 ni V9, y Flyway aplicó V7→V10 | **HOLDS**, con la parte histórica **UNPROVEN** en el reporte |
| 5 | Que la compuerta sirva de oráculo **independiente** de la autoridad | **FAILS** |
| 6 | `XmiPersistenceTest` afirma que no quedan pérdidas tras borrar el intercambio | **HOLDS** — la aserción es `assertThat(lossItemRepository.findByExchangeId(exchangeId)).isEmpty()` sobre un estado previo de dos pérdidas, no un «no lanzó excepción» |

**El UNPROVEN del punto 4 lo cerré inline**, porque es un comando y no una opinión: `git show --name-only 245f093` toca **9 archivos** y el único de migraciones es `V10__xmi_exchange.sql`, **agregado** (`--diff-filter=A`). Ninguna migración previa fue modificada. Pasa a HOLDS.

**El FAILS del punto 5 no es un defecto del código: es el borde de la compuerta, y se declara.** Las cuatro pruebas comparan el esquema real contra lo declarado **en el propio archivo de prueba**. Detectan **deriva unilateral** —una columna o un índice que está en uno y no en el otro— y **no una omisión coordinada**: si la autoridad pide una columna y esa columna falta tanto en la migración como en la declaración, ninguna fuente independiente lo nota. El verificador buscó esa omisión en el esquema actual y **no la encontró**; lo que encontró es que la compuerta **no podría** encontrarla.

No corresponde «arreglarlo» con una prueba que parsee `02-esquema-fisico.sql`: sería un oráculo frágil que este repositorio nunca quiso, y para dos tablas introduciría una regla que las otras 27 no siguen. **El control compensatorio es la derivación independiente desde la autoridad, que es exactamente lo que este verificador hizo**, y por eso el límite se escribe en lugar de taparse: quien agregue columnas a estas dos tablas tiene que cotejarlas a mano contra la autoridad, porque la suite no lo va a hacer por él.

**Consecuencia del evaluador roto, que conviene decir entera:** si `assess` no puede producir un riesgo en este repositorio, entonces **todo** candidato se trata como alto y **toda** unidad de trabajo paga un verificador independiente. No es gratis y no es lo que el flujo quería; lo que el flujo quiere es que el riesgo se mida. Queda declarado en lugar de disimulado, y las dos corridas del evaluador están registradas con su mensaje exacto.

**El modo de falla que estos verificadores están cazando.** En X1, un arnés que pasa y no puede fallar. En X2, algo más profundo: la migración y la compuerta las escribió **el mismo escritor**, así que si las dos salieron de la misma mala lectura de la autoridad, la suite pasa y el esquema está mal. Re-correr `verify` no lo detecta nunca. Por eso el verificador deriva las columnas por su cuenta desde `docs/puds/datos/02-esquema-fisico.sql` y las compara, en vez de leer la prueba y darle la razón.

**El defecto del evaluador, reportado.** Búsqueda definitiva en `Gentleman-Programming/gentle-ai` sobre issues abiertos y cerrados, por cuatro términos distintos: apareció el tracker canónico **#4791** —«assess over a committed range returns schema-incompatible native response (risk unassessable)»—, **abierto, sin labels, con tres ocurrencias ya registradas**, todas sobre la línea publicada y Linux. No se encontró fix publicado: ningún issue cerrado lo referencia y el único PR que menciona ese número trata otra cosa. Corresponde entonces **una** ocurrencia y **ninguna** etiqueta, y eso fue lo que se hizo: [comentario 5937540519](https://github.com/Gentleman-Programming/gentle-ai/issues/4791#issuecomment-5937540519).

Lo que esa ocurrencia aporta y que las tres anteriores no tenían: reproduce en **Windows x86_64**, y el **código y el texto cambiaron** —`native-assess-unavailable` con «native response is schema incompatible» pasó a ser `schema-incompatible` con «no sanitized stderr diagnostic is available»—. El segundo detalle importa más de lo que parece: el mensaje dejó de decir **qué** está mal y ahora solo dice que no hay nada que decir, que es un modo de falla peor para quien reporta.

**Dos honestidades sobre ese reporte.** Primera: el binario instalado es una **build local** resuelta por un override de `dev-binary`, no una publicada, así que la ocurrencia **no** confirma el defecto en la línea publicada y así quedó escrito. Segunda: la ruta hasta reproducirlo pasó por un rechazo distinto —`untracked files require an explicit declaration`— que es un **requisito documentado** del contrato y no este defecto; solo con el árbol limpio la evaluación llegó al camino nativo y falló al decodificar. Mezclarlos habría sido inflar un defecto con el otro.

**Lectura honesta de esa fila.** El escritor auto-verificó y el padre repitió el comando: eso prueba que el arnés
funciona, no que mida bien. Lo que prueba que **mida bien** es la verificación de mutación en curso —que las
aserciones fallen cuando el hecho cambia—, y por eso esa etapa no se marca cerrada hasta que llegue. Un arnés que
pasa y no puede fallar es exactamente lo que este repositorio ya se comió una vez.

## 6. Riesgos declarados

| Riesgo | Cómo se trata |
| --- | --- |
| **EA no acepta un `exporter` ajeno** | X-5. Es un cambio de una línea y lo decide la verificación de §7; hasta entonces el archivo no está probado contra EA |
| **EA no acepta identificadores fuera de su convención** | X-1 deriva `EAID_`/`EAPK_` de la convención medida precisamente para no apostar a que EA tolere un UUID crudo |
| **El ruteo de conectores se pierde** | Medido (§3.4) y declarado como pérdida, no oculto. La disposición de las figuras —que es lo que se ve— sí se conserva. **La justificación original era falsa**: el dialecto sí tiene el canal, y lo que falta no es la capacidad sino la gramática, porque el fixture trae un solo `Path` con contenido |
| **Una medición propia puede estar mal y contaminar el diseño** | Pasó: afirmé «`Path` vacío en todas» a partir de una muestra truncada y eso entró a una decisión de diseño (X-4) y al contrato con la otra sesión. Lo detectó el escritor de X1 al medir exhaustivamente, no yo. Mitigación que queda: las mediciones del dialecto viven como **aserciones ejecutables** en `tools/xmi-inventory/`, con recuentos —«7 vacías, 1 con contenido»— en vez de universales, y el fixture pinneado por hash |
| **El dialecto está caracterizado con un solo archivo** | Límite heredado del contrato: un diagrama de paquetes anidados puede traer formas que el fixture no tiene. Se declara y el lector **cuenta** lo que no reconoce en vez de descartarlo callado |
| **Guardas ausentes al nacer** | El gancho de la concesión de edición no está en esta base (§0). Se declara explícitamente en el acta: hasta consolidar, la exportación XMI no está protegida contra una edición en curso |
| **El `type()` del comando de lote colisiona con otro handler** | `Collectors.toUnmodifiableMap` **lanza al arrancar** si dos handlers declaran el mismo `type()`, así que una colisión no degrada: tumba la aplicación. Se verifica que `xmi.import` sea único y la prueba de arranque del contexto lo cubre |
| **Dos ramas divergiendo sobre los mismos archivos** | Está congelado en §0 qué toca cada sesión. La compuerta dura es el **conjunto exacto de tablas** y su declaración de columnas e índices, y se resuelve tomando la unión: las cuatro tablas declaradas y ninguna en la lista de reservadas. La lista de versiones de migración **no** es punto de conflicto: su aserción es de subconjunto |
| **El fixture se corrompe** | Hash pinneado en X1.2 y byte-exacto por `-text`; verificado que el blob del repo coincide con el disco |

## 7. Verificación en EA — GAP-07

Estado medido en esta sesión, para que el acta no lo invente:

| Hecho | Evidencia |
| --- | --- |
| EA corre, versión `15,0,0,1514` (coincide con el perfil fijado) | `EA.exe` PID 31164, `Responding: True`, `FileVersion 15,0,0,1514` |
| **Es una build parcheada**, no la oficial | Título de ventana: `No current project - EA - Cracked Edition from Brains`; el instalador sale de un repack con carpeta `crack` |
| **No hay repositorio abierto** | El mismo título lo dice: `No current project` |
| El add-in del MCP **está cargado** | `HKLM\SOFTWARE\WOW6432Node\Sparx Systems\EAAddins\MCPAddin` → ProgID `MCP_EA.Main`, que resuelve; `MCP3.exe` arranca limpio como servidor stdio |
| **Pero toda llamada da timeout** | `get_root_packages` y `get_packages_information` → `Failed to connect to Enterprise Architect: The operation has timed out`, reproducible |
| Había un `MCP3.exe` huérfano | PID 26544, de una sesión anterior; se terminó. El pipe `mcp-e3b549be-…sock` sobrevive, así que pertenece a la gateway de Pi, no al add-in |

### 7.1 Lo que se midió después, y que parte el gap en dos

El add-in del MCP quedó **descartado como vía**: respondió una sola vez y después se colgó dos veces, y su superficie **no incluye** importar ni exportar XMI. La vía es la automatización COM de EA, y ahora está **ejecutada**, no solo enumerada.

| Hecho | Evidencia |
| --- | --- |
| La automatización de EA es alcanzable desde el PowerShell de **32 bits** | `New-Object -ComObject EA.Repository` devuelve un objeto usable. Desde 64 bits, `GetActiveObject` falla con `MK_E_UNAVAILABLE`, porque EA es de 32 bits |
| Su superficie incluye importar y exportar XMI | `OpenFile`, `CreateModel`, `ExportPackageXMI`, `ExportPackageXMIEx`, **`ImportPackageXMI`**, `ImportFile`, enumerados por reflexión COM |
| **`ImportPackageXMI` funciona, y es rápido** | Sobre una **copia** de `sw1_p2.eapx`, importando `docs/fixtures/xmi/Prueba3.xml` en el paquete `Implementacion`: llamada OK en **1,5 s** |
| **EA recuperó semántica y dibujo del fixture** | Paquetes 36→37, elementos **397→407 (+10)**, diagramas **90→91 (+1)**. Los +10 son exactamente los 10 clasificadores del fixture (8 clases + 1 interfaz + 1 enumeración), y el +1 es su diagrama |
| Aparece el paquete del fixture en el destino | Subpaquete nuevo: `Prueba - Diagrama de Clases Completo` |
| `ExportPackageXMI` es **lentísimo** en cambio | 24 exportaciones sobre paquetes reales no terminaron en 10 minutos; se abortó |

**Entonces GAP-07 se parte en dos, y la partición es el avance.** Queda **cerrado** que el camino de importación existe, funciona y es rápido, y que EA acepta un XMI 2.1 de su propio dialecto recuperando **10 elementos y 1 diagrama** — ese es el criterio de aceptación, calibrado contra un archivo real y no conjeturado. Queda **abierto** que EA acepte **un archivo emitido por nosotros**: eso lo mide X8 con la emisión completa, y el número a mirar es si EA recupera **esos mismos 10 elementos y ese mismo diagrama**.

Dos cautelas que siguen en pie: la instalación es una repack, que no invalida el dialecto medido —lo escribe el mismo motor— pero condiciona cualquier lectura de interoperabilidad; y todo esto corrió sobre una **copia**, nunca sobre el proyecto del usuario. La copia vive en `C:\Users\HP\AppData\Local\Temp\ea-scratch\`.

### 7.2 La aceptación medida de nuestro propio archivo

Con `ImportPackageXMI` funcionando, se importó **nuestro** documento emitido —el que la prueba de emisión vuelca en
`backend/target/xmi-sample.xml`, 19.196 bytes— sobre una copia de `sw1_p2.eapx`. Resultado medido:

| Archivo importado | Paquetes | Elementos | **Diagramas** |
| --- | ---: | ---: | ---: |
| El fixture de EA (`Prueba3.xml`) | +1 | **+10** | **+1** |
| El nuestro (`xmi-sample.xml`) | +3 | **+8** | **+0** |

Reproducido dos veces, con los mismos números las dos veces. Y lo que aterrizó del nuestro es correcto:
`AbstractBase [Class]`, `Base [Class]`, `Feature [Class]`, `Status [Enumeration]`, `Contract [Interface]`,
`Boolean` y `String [PrimitiveType]`. **EA acepta nuestra semántica, con los tipos bien resueltos, y descarta
nuestro diagrama.**

Entonces el requisito «que se vean bien las figuras en EA» queda **mitad verificado y mitad localizado**, que es
mejor que «no se sabe»: hay una diferencia concreta entre el bloque que EA sí importa y el nuestro.

**Una hipótesis que había que descartar, y que descarté mal.** Vi que nuestro archivo sale con **CRLF** y que mis
variantes de prueba salían con **LF**, y con LF EA no importaba nada. Concluí que EA exigía CRLF. **Era falso**: al
reproducir la línea base, un archivo **byte-idéntico** al original importaba las mismas +3/+8. Las variantes daban
cero porque **el paso de la ruta por parámetro desde Git Bash no llegaba al script** —el mismo problema de
conversión de rutas que ya me había mordido con `reg query`—. No cambiaba el archivo: cambiaba mi forma de
invocarlo. Queda registrado porque es la **cuarta** vez en esta sesión que una medición mía resultó ser un
artefacto del instrumento y no del objeto.

**Dónde está la diferencia.** Los bloques de diagrama, comparados:

| Hijo de `<diagram>` | El fixture (EA lo importa) | El nuestro (EA lo descarta) |
| --- | --- | --- |
| `<model>` | `package`, `localID`, `owner`, `tpos` | solo `package` |
| `<properties>` | `name`, **`type="Logical"`**, `documentation` | solo `name`, **sin `type`** |
| `<project>` | `author`, `version`, `created`, `modified` | **vacío** |
| `<style1>`, `<style2>` | `value` extenso | **vacíos** |
| `<swimlanes>`, `<matrixitems>` | `value` | **vacíos** |
| `<extendedProperties>` | vacío | vacío |
| `<elements>` | 18 colocaciones (10 figuras + 8 conectores) | 5 (2 figuras + 3 conectores) |

El sospechoso principal es **`<properties>` sin `type`**: es el único campo que en el fixture declara **qué clase de
diagrama es**, y sin él EA no tiene con qué crear la vista. Los demás son metadatos de presentación.

**Y una cautela sobre el instrumento, que ahora es parte del resultado.** Las variantes que había preparado para
aislar el campo culpable —agregar `type="Logical"`, luego `<project>`, luego los atributos de `<model>`— **no se
pudieron medir**: EA se colgó más de 25 minutos en una y, tras terminarlo, volvió a colgarse en la siguiente.
Lanzar `EA.Repository` por COM repetidamente sobre copias del mismo proyecto lo deja en un estado del que no sale.
La receta es reproducible —copiar el `.eapx`, `OpenFile`, `ImportPackageXMI(guid, archivo, ImportDiagrams=1,
StripGUID=0)`, contar antes y después por delta, y cerrar con `Exit()`—, pero **la identificación del campo exacto
queda pendiente de una corrida con EA en estado limpio y NO se declara como conocida**.

### 7.3 Tres hipótesis refutadas, una puerta cerrada, y el defecto declarado

Se probaron hipótesis de a una, cada una con **una sola invocación** de importación sobre una copia:

| Hipótesis | Cómo se probó | Resultado |
| --- | --- | --- |
| **Faltaban los campos de la cabecera**: `type="Logical"` en `<properties>`, `localID`/`owner`/`tpos` en `<model>`, `version` en `<project>` | Se implementaron en la emisión y se volvió a importar | **REFUTADA.** Sigue `DIAGRAMAS=0`, con los mismos +3 paquetes y +8 elementos |
| **El dueño del diagrama apunta al `uml:Model`**, que en el dialecto medido no lleva `xmi:id` ni es un paquete | Variante de **un solo** cambio: `package` y `owner` del diagrama apuntando al paquete real `Outer` | **REFUTADA.** Sigue `DIAGRAMAS=0` |
| **El orden de los hijos** de `Extension` o de `<diagram>` difiere del de EA | Comparación directa de los dos archivos | **REFUTADA de antemano.** El orden es **idéntico** en ambos: `elements`, `connectors`, `primitivetypes`, `profiles`, `diagrams`, y dentro del diagrama `model`, `properties`, `project`, `style1`, `style2`, `swimlanes`, `matrixitems`, `extendedProperties`, `elements` |

Y una puerta que se cerró: **hacer que EA escriba la respuesta no es posible en esta build.** La idea era importar el
fixture —que sí crea su diagrama— y pedirle a EA que **exporte** ese mismo paquete de 10 elementos, para leer el
dialecto exacto que EA produce. `ExportPackageXMI` **no terminó en 900 segundos** sobre ese paquete chico, igual que
antes no había terminado en 600 segundos sobre paquetes grandes. El camino de exportación de EA no sirve como oráculo
en esta máquina.

La cáscara de cabecera que se implementó **se conserva**, porque cada campo está medido contra el fixture y no cuesta
nada, pero **no arregla el diagrama**, y así queda declarado, no como «arreglado».

**Lo que queda como candidato, sin probar**, y corresponde decirlo con esa palabra:

- Los bloques `<style1>`, `<style2>`, `<swimlanes>` y `<matrixitems>` vacíos: el fixture trae `value` extensos.
- `<project>` sin `author`, `created` ni `modified`.
- **El más plausible de los que quedan**: en el fixture, las diez figuras del diagrama son miembros del **paquete que
  posee el diagrama**. En el nuestro, las figuras referencian clases que están en la raíz del modelo y dentro de
  paquetes anidados. Si EA exige que los sujetos de las figuras pertenezcan al paquete dueño, **ningún cambio de
  cabecera lo iba a arreglar** — y eso encaja con que las dos hipótesis refutadas lo fueran.

**El estado de GAP-07, que es lo que va al acta.** La importación existe, es rápida y está ejecutada. EA acepta
**nuestra semántica** con los tipos resueltos: +8 elementos sobre una copia de un proyecto real. EA **no crea nuestro
diagrama**: +0, contra +1 del archivo de EA. La causa **no está identificada**, con tres hipótesis refutadas por
experimentos de una sola variable. **El requisito «que se vean bien las figuras en EA» NO está cumplido** y no se
declara cumplido.

### 7.4 Decisión de alcance del usuario, y sus consecuencias

Ante el estado de §7.3 —EA acepta la semántica, **no** crea el diagrama, la causa no está identificada, y la mitad de
importación del pedido está entera sin construir— se le presentó la disyuntiva y **eligió completar la importación y
dejar el diagrama como defecto declarado**, en lugar de seguir cazándolo sin techo de tiempo conocido.

Consecuencias, escritas para que no se lean como un olvido:

- **X5, X6 y X7 se ejecutan**: el lector del dialecto, el servicio de vista previa y confirmación con su comando de
  lote y sus endpoints, y el panel web.
- **El diagrama NO se declara resuelto.** Queda abierto con las tres hipótesis refutadas y con el candidato más fuerte
  **sin probar**: que los sujetos de las figuras deban pertenecer al paquete dueño del diagrama. Ese candidato queda
  escrito acá para que la próxima sesión no vuelva a empezar de cero.
- **El requisito «que se vean bien las figuras en EA» queda parcialmente cumplido y se declara así**: el **modelo**
  se ve en EA —+8 elementos con los tipos resueltos—, el **dibujo** no.

Y una nota de método que esta decisión obliga a sostener: el lector se mide contra un archivo **ajeno** (el fixture, y
el segundo archivo de EA) además de contra nuestra propia emisión, porque un lector que solo entiende lo que nosotros
escribimos no prueba interoperabilidad, prueba simetría con nuestros propios errores.

### 7.5 El control positivo encontró tres defectos en nuestra propia emisión

El arnés de X8 (`tools/xmi-acceptance/`) corre sus comprobaciones **reusando el oráculo de `tools/xmi-inventory/`** contra
dos documentos, y los dos son obligatorios: el fixture que **escribió EA** —control positivo— y el que produce nuestro
emisor. El control positivo pasa las nueve comprobaciones. **El nuestro falla tres.**

Ese es exactamente el resultado que justifica el diseño: un conjunto de aserciones que solo corriera contra nuestra
salida no podría distinguir «nuestra salida está bien» de «las aserciones codifican nuestras manías». El escrito del
control positivo es lo que convierte al arnés en oráculo en vez de espejo.

```
extensión:  6 registros de conector   vs   5 relaciones contadas en el modelo
            unmatchedRelationIds: ["EAID_00000000_0000_0000_0000_000000000050"]
            connectorsWithMissingOrUnresolvedEndpoints: 1
            etiquetas mt: 5 para 6 conectores
            vocabulario no declarado: defaultValue, specification
```

| # | Defecto | Medición | Interpretación |
| --- | --- | --- | --- |
| 1 | **Vocabulario no declarado**: `defaultValue` (1) y `specification` (2) | **El fixture tiene CERO de los dos.** Los emitimos porque el modelo canónico los tiene, con la grafía **estándar de UML 2.1** que EA simplemente no ejercita en su muestra | No es un error de código: es vocabulario **no medido**. Corresponde declararlo como desvío explícito y razonado; ni borrar la funcionalidad ni ablandar la comprobación |
| 2 | **Un conector de extensión sin relación que le corresponda**: 6 registros contra 5 relaciones contadas, y `EAID_…050` —que es la dependencia `uses`— sin emparejar | El id sin emparejar es el de la **dependencia** | Falta determinar cuál de las dos cosas es: que emitamos un registro de más, o que el oráculo no cuente las dependencias como relaciones. **No se supone; se mide** |
| 3 | **Etiquetas `mt` incompletas**: 5 para 6 conectores | Coincide con el defecto 2 | Probablemente el mismo origen: el conector sin relación emparejada tampoco recibe etiquetas |

**Y un hallazgo colateral de los mismos datos:** el fixture trae un bloque `<links>` **dentro de cada `<element>` de la
extensión** —con `<Generalization>` y `<Realisation>` adentro, con `start` y `end`— y **nosotros no emitimos ninguno**.
Es un canal entero del dialecto que no estábamos cubriendo, y aparece en las diez figuras del fixture.

**Lo que NO corresponde hacer con esto.** El escritor tenía prohibido ablandar las aserciones para que pasara lo
nuestro, y **no lo hizo**: dejó las tres fallas visibles y el arnés saliendo con código 1. Eso es lo correcto. La
resolución no es relajar la comprobación sino **distinguir un desvío declarado de una falla**, con una lista de desvíos
explícita, corta y razonada, de modo que cualquier vocabulario **nuevo** que nadie midió siga fallando. Un arnés que
siempre falla no sirve de compuerta; uno que pasa por ablandarse no sirve de nada.

### 7.6 El diagnóstico del defecto 2: no era un defecto de emisión

Se midió relación por relación contra registro por registro, y **nuestra emisión es internamente consistente**:

```
NUESTRO:  6 relaciones en el modelo  ↔  6 registros de conector
ORÁCULO:  cuenta 5 relaciones
```

El `5` sale de que **el oráculo no cuenta las dependencias** como relaciones, y el `EAID_…050` «sin emparejar» es
la dependencia `uses`. O sea que el defecto 2 **no es que emitamos un registro de más**: es que la comprobación y la
emisión **cuentan cosas distintas**, y el arnés lo reportó como falla en vez de decirlo con esas palabras.

Y queda en pie la verdad de fondo, que es la misma del defecto 1: **el fixture no tiene ninguna dependencia**, así que
la forma de un conector de dependencia **nunca se midió**. Emitimos una forma que nadie observó.

La comparación cruda de un conector contra otro destapó además diferencias que no estaban en la lista:

| | Fixture (EA) | Nuestro |
| --- | --- | --- |
| `name` en el conector | `hereda`, `realiza` | **ausente** |
| `<model>` | `ea_localid="253"` | **vacío** (declarado como pérdida `extension_connector_vendor_metadata`) |
| `properties` | `subtype="<none>"` | **sin `subtype`** |
| `direction` en una agregación | `Unspecified` | `Destination -> Source` |

**Entonces el defecto 2 se descompone en dos cosas distintas que no hay que confundir:** una **corrección del arnés**
—su conjunto de relaciones tiene que incluir las dependencias, o decir explícitamente que las excluye— y una
**decisión de emisión** sobre una forma no medida. Y el defecto 3 —un conector sin etiquetas `mt`— es el mismo
conector de dependencia: es consecuencia, no causa independiente.

**Lo que este episodio enseña, y que vale más que los tres defectos:** el arnés encontró una discrepancia **real y
útil** (vocabulario no medido) y la reportó junto a una **falsa** (un conteo distinto entre dos piezas correctas).
Distinguirlas exige abrir el documento, no leer el veredicto. Un arnés que no se audita a sí mismo termina
gobernando por sus propias definiciones en vez de por los hechos.

### 7.7 El método correcto para el diagrama, y el experimento que quedó listo

La captura del navegador de EA que trajo el usuario muestra que la importación semántica es **perfecta**: el
anidamiento `Outer → Inner → Nested`, `AbstractBase` y `Base` como clases, `Feature` con sus dos atributos **y el
método `transform(String, Boolean)` con la firma completa**, `Contract` con icono de interfaz, `Status` de
enumeración, y `Boolean` y `String` como primitivos. Es exactamente lo que emitimos. **Falta solo el dibujo.**

Eso obliga a cambiar el método, y el cambio es importante. Hasta acá probé **agregándole campos** a nuestro archivo
para ver si el diagrama aparecía: tres hipótesis refutadas así. Lo correcto es lo inverso: **partir del archivo de EA,
que sí dibuja, e ir quitándole o sustituyéndole piezas** hasta que deje de dibujar. Se busca desde el caso que
funciona hacia el que no, no al revés.

El primer experimento de esa serie quedó **construido y listo**: `%TEMP%\ea-scratch\hybrid.xml` (87.648 bytes), que
es el **archivo de EA con nuestro bloque `<diagrams>` completo adentro** —5.149 bytes del fixture reemplazados por
nuestros 1.357—. Interpretación, escrita de antemano para no torcerla después:

| Resultado | Qué significa |
| --- | --- |
| EA **crea** el diagrama | Nuestro bloque está bien y el problema es la **interacción con nuestro modelo**. La búsqueda pasa a los sujetos de las figuras y el paquete dueño |
| EA **no** lo crea | El problema está **dentro de nuestro bloque**, y se biseca ahí: estilos, `<project>`, el id del diagrama |

No se pudo medir: **EA se colgó más de 900 segundos** en esa importación, la misma fragilidad ya registrada que
obliga a matar los procesos huérfanos entre intentos. El comando queda listo para correr en una corrida limpia:

```sh
XMI_NAME=hybrid.xml TAG=hybrid powershell.exe -File %TEMP%\imp3ps.ps1
```

**Y una lección de método que vale más que el experimento:** tres hipótesis refutadas construyendo desde el caso roto,
y la primera construida desde el caso que funciona quedó lista para decidir en un solo intento. Cuando se depura
contra un oráculo que funciona, **se parte del oráculo**.

### 7.8 El control de expectativas, que es parte del entregable

El usuario preguntó dónde ver los diagramas generados en EA, y la respuesta correcta es incómoda: **no hay ninguno
que ver**. Corresponde decirlo con estas palabras y no con rodeos:

- **Su proyecto `sw1_p2` nunca fue tocado.** Su archivo conserva la fecha del 11 de junio; todas las importaciones
  corrieron sobre **copias** en `%TEMP%\ea-scratch\`. Si abrió su proyecto buscando algo nuestro, no había nada, por
  diseño.
- **El artefacto es un archivo, no una imagen**: `backend/target/xmi-sample.xml`. Importarlo a mano da el mismo
  resultado medido: el modelo, y ningún diagrama.
- **Advertencia para el usuario**: no importar ese archivo en su proyecto de trabajo. Agrega un paquete con el modelo
  sintético de la prueba y ningún dibujo.

**Decisión del usuario:** reiniciar EA y reintentar. **Pendiente de su acción**: abrir EA con un proyecto y avisar.

| Desenlace | Qué se declara |
| --- | --- |
| El add-in contesta con repositorio abierto | Se importa el XMI exportado en EA y se captura el diagrama: el corte «se ve bien en EA» queda **verificado** |
| Sigue el timeout | La build parcheada rompió el COM del add-in. Cae a verificación manual del usuario con captura, o a oráculo estructural, y **GAP-07 se declara abierto** |

En cualquier desenlace, **el round-trip contra EA en vivo no se declara verificado sin evidencia**.

## 8. Trazabilidad

| Requisito | Cómo lo toca esta unidad |
| --- | --- |
| RF-09 (importar y exportar XMI) | Es la unidad completa: emisión, lectura, reporte de pérdidas y los dos modos de importación |
| RNF-05 (interoperabilidad) | La disposición de figuras y las etiquetas de rol y multiplicidad sobreviven al intercambio; el ruteo de conectores se declara perdido |
| CU-16 (importar modelo XMI) | Vista previa, confirmación, rechazo sin modificar el proyecto, pérdidas trazables |
| CU-17 (exportar modelo XMI) | Modelo válido como precondición, pérdidas reportadas, archivo identificado por perfil |
| Q3 (prueba de round-trip) | Aporta el round-trip **dentro de la app**; contra EA en vivo queda como GAP-07 |
| RNF-01, RNF-04, RNF-06 | Determinismo de la emisión, rechazo de entrada inválida, y el reporte como salida observable |

### 8.1 El traspaso al acta, en dos listas

Cuando cierre X8, el traspaso a la sesión de Incremento 3 son **dos listas**, y la segunda es obligatoria. El par la
pidió textualmente, y el motivo es bueno: «si no me lo decís vos lo voy a tener que inferir yo, que es peor».

1. **Lo medido**, cada número con el comando que lo produjo.
2. **Lo no medido**, explícito.

Ya se saben al menos tres elementos de la segunda lista, y hay que ampliarla con lo que vaya apareciendo:

- EA **no aceptó ni una vez** un archivo emitido por nosotros. GAP-07 sigue abierto.
- Nunca se probó empíricamente si EA **rechaza** `value="*"`. Que EA **escriba** `-1` no lo implica, y la decisión de
  seguirlo es basada en evidencia, no una demostración.
- El **significado** de la gramática de `Path=` en el canal de conectores sigue sin establecerse, que es por qué X-4
  emite vacío. Una y dos muestras dicen cómo se **escribe**, no qué **acepta** el lector.

Y la frase del bloque de interoperabilidad va con su límite pegado, no en nota al pie: la automatización de EA es
alcanzable y expone importación y exportación de XMI, y queda como **vía disponible, no como round-trip verificado**;
además, la instalación de EA de esta máquina es un repack, que no invalida el dialecto medido —lo escribe el mismo
motor— pero condiciona cualquier lectura de interoperabilidad.

## 9. Integración con la sesión de Incremento 3

La sesión hermana (`01a0f8fe`, rama `feat/incremento-3-colaboracion`, worktree principal, mismo clon) avisó al cerrar
su C10. Tres hechos que afectan la consolidación, tomados de su aviso y no supuestos:

1. **`web/src/pages/ProjectEditorPage.tsx`** — su cambio es un `import` más una línea de JSX
   (`<MembersPanel projectId={project.id} />`, entre el contenedor del lienzo y `<GenerationPanel>`). Cuando yo
   agregue el panel de XMI, la fusión es **de los dos**, y ninguno de los dos toca nada más de ese archivo.
2. **`EditorCorsConfig`** — ahora permite `GET, POST, PATCH, DELETE, OPTIONS` (antes solo `GET, POST, OPTIONS`). Mis
   endpoints son GET y POST, así que no me cambia nada; su C10 fue el primero en usar PATCH y DELETE.
3. **`web/vite.config.ts`** — cada prefijo de primer nivel necesita su entrada, y `/projects` ya está, que es el mío
   (`/projects/{projectId}/xmi/...`). **No hay que tocarlo.** Vale la pena recordar por qué: un prefijo faltante da
   **404 silencioso en desarrollo con el build en verde**, que ya les pasó con `/deployments` y `/invitations`.

Y una frontera de propiedad que hay que respetar, con una corrección que el par me hizo y que cambia la delegación de X4: el
paquete `com.diagramador.collaboration` es **suyo**, pero `EditingLeaseService` **no nace en C11** —C11 es solo el bus de
eventos y `GET /projects/{id}/events`—. La concesión de edición es **C19, de la etapa S5**. La consecuencia práctica es
dura:

> **X4 no debe referenciar `EditingLeaseService`.** La interfaz no existe en esta base y cualquier llamada no compilaría.
> La guarda de exportación nace ausente, como ya declara §2, y se cablea en la consolidación con una línea.

La firma está congelada en el contrato §S6b de `odd/tasks/incremento-3-colaboracion.md` y es esta, textual:

```java
package com.diagramador.collaboration;

import java.util.UUID;

/** La concesión de edición de un modelo. Mientras otra sesión la tenga viva, exportar queda bloqueado. */
public interface EditingLeaseService {
    /**
     * Lanza {@code ApiException(409, "EXPORT_BLOCKED_EDITING")} cuando otra sesión tiene una concesión viva
     * sobre el modelo. El principal de operador queda exento: entrega un artefacto que un usuario ya ordenó.
     */
    void requireExportAllowed(UUID modelId);
}
```

La forma compartida del bloqueo: código `EXPORT_BLOCKED_EDITING`; en endpoints de archivo **HTTP 409** con el sobre
`ApiErrorResource`; en `POST /projects/{id}/generations` el mismo código dentro del cuerpo `outcome` con **HTTP 200**
(convención D7 del Incremento 2). Las claves de `details[0]` van **en este orden**: `editorDisplayName`,
`targetType`, `targetId`, `reclaimable`, `retryAfterSeconds`.

Y una nota de esquema que mueve el punto de fusión: `V8` (invitaciones y `invitation.role`) ya está en su rama desde
`568f25d`, y `V9` (chat, `diagram_message`) llega en su S4. Cuando eso pase, el conflicto real no es el SQL sino
**`SchemaOwnershipTest`**, y se resuelve por **unión de listas**: sus líneas de `invitation` y las mías de
`xmi_exchange` y `xmi_loss_item`. Pero el conteo de índices de soporte **no se suma**: es una compuerta exacta, y para
cada tabla se toma lo que la autoridad del esquema declara para esa tabla.

Dos reglas de proceso que su experiencia aporta, y que aplican a cualquier corrida de revisión nativa mía:

- El sobre de concesión del START **vence a los 10 minutos** sin respuesta, y al vencer **no crea linaje**. Hay que
  volver a correr START; nunca reenviar el binding viejo.
- Si la corrida del revisor muere por presupuesto agregado de tiempo, hay que consultar **STATUS fresco** antes de
  reintentar, no reintentar a ciegas.

Y un dato que puede ahorrarle un susto a cualquiera que corra `mvn verify` en este repositorio: la salida incluye
líneas con `T8_INCOMPLETE`, `FAILED` y `NOT_RUN` que son **telemetría preexistente** de
`GenerationIdentityServiceTest`, ajena a la unidad que se esté midiendo. El veredicto se lee en los totales de
pruebas, no en esas líneas.

## 10. Reparación verificada en vivo: el diagrama ausente en EA (GAP-07)

Sección agregada con la sesión de reparación. **GAP-07 queda cerrado con causa identificada y medida**, no con una
hipótesis plausible.

### 10.1 El instrumento que faltaba, y por qué los tres veredictos de §7.3 eran inválidos

La medición se hizo **leyendo la base de EA directamente**, sobre una **copia** de `sw1_p2.eapx`, con
`Microsoft.ACE.OLEDB.12.0` vía PowerShell. Ese instrumento no depende del add-in MCP ni de COM para verificar, y
sobrevive a los cuelgues que arruinaron §7.2 y §7.3.

Y al medir con él apareció el defecto de método que invalida los tres veredictos de §7.3:

> **Las tres hipótesis que §7.3 declaró «REFUTADAS» se probaron mientras EA descartaba todos nuestros identificadores
> y les asignaba GUID nuevos.** Con los ids regenerándose en cada importación, ninguna corrección de cabecera del
> diagrama *podía* funcionar: los `subject` de las figuras y el `package` del diagrama apuntaban a ids que la
> importación ya había tirado. Eran falsos negativos, no refutaciones.

Y el add-in MCP mintió en el otro sentido: reportó la operación `transform` con `parameters: []`, y la base de EA
demuestra que los parámetros **entraron completos** (`input: String`, `return: Boolean`, `Pos 0/1`). El motivo está en
`%APPDATA%\Sparx Systems\EA\DBError.txt`: su consulta es
`SELECT ... Pos AS POS FROM t_operationparams WHERE OperationID = 37 ORDER BY Pos` y el motor la rechaza por
*«Circular reference caused by alias 'POS'»*. **Un veredicto del add-in no es evidencia de importación.**

### 10.2 Causa raíz, con el experimento que la aísla

El emisor declaraba procedencia propia en la cabecera del documento:

```xml
<xmi:Documentation exporter="UML Class Diagrammer" exporterVersion="0.1.0"/>
```

EA lee esa cabecera para decidir **si el archivo habla su dialecto**. Con un `exporter` desconocido:

1. **Regenera todos los identificadores.** Medido: con `exporter="UML Class Diagrammer"`, los 11 objetos importados
   recibieron GUID nuevos y **0 ids propios sobrevivieron**; con `exporter="Enterprise Architect"`, EA devolvió
   `{00000000-0000-0000-0000-000000000001}` —nuestro propio id de modelo— y los 11 sobrevivieron exactos.
2. **No procesa el bloque `<diagrams>`.** Medido: `t_diagram` 90 → 90 con nuestro archivo, y 90 → 91 con el archivo
   de EA. Y con **solo** ese cambio de cabecera, 90 → **91** con nuestro archivo.

Las dos partes son independientes y quedó probado con una sola variable cada una:

| Test | Cambio único sobre nuestro archivo | Ids preservados | Diagramas |
| --- | --- | --- | --- |
| T4 | `exporter="Enterprise Architect"` (versión `0.1.0`) | **sí** | 90 (sin diagrama) |
| T5 | + `extenderID="6.5"` | sí | 90 |
| **T16** | `exporter="Enterprise Architect" exporterVersion="6.5"` | sí | **91** |
| Control | `docs/fixtures/xmi/Prueba3.xml` (archivo de EA) | sí | **91** |

O sea que **el arreglo mínimo son dos atributos**, y `extenderID` **no** es necesario: T16 funciona sin él. La forma
final del arquero es emitirlo igual, porque está medido en el dialecto de §3.1 y no cuesta nada.

Y **el arreglo mínimo bastó**: con T16, EA creó el diagrama (`t_diagram` 341, nombre `Default`, GUID
`{00000000-0000-0000-0000-00000000012C}` = nuestro id) y ancló las **2 figuras** en `t_diagramobjects`, además de
conservar `label → String/Private/guest` y `nickname → String/Package`.

Consecuencia directa sobre la decisión **X-5**, que se revierte: el plan la había tomado por honestidad de procedencia
y había declarado el riesgo textualmente —«si EA exigiera su propio nombre en `exporter`, es un cambio de una línea, y
solo la verificación de §7 lo decide»—. La verificación lo decidió, y la honestidad se conserva por otro canal: la
procedencia real viaja en un **comentario XML**, que ningún lector de dialecto interpreta.

### 10.3 Refutado, con instrumento válido

Todo lo siguiente se midió una variable por vez contra la base, y **no** cambia el resultado: `StripGUID` (con
`StripGUID=0` explícito los ids igual se regeneraban, porque el problema era la cabecera), identificadores
degenerados (`EAID_00000000_…` reemplazados por `A1B2C3D4_…`: igual), los bloques `<style1>`, `<style2>`,
`<swimlanes>` y `<matrixitems>` vacíos (reemplazados por los `value` medidos de EA: igual), `<project>` sin
`author`/`created`/`modified` (igual), `<properties>` sin `documentation` (igual), el contenido de los registros de
`<elements>` y `<connectors>` (vaciados: igual), `<profiles/>` vacío (reemplazado por el de EA: igual),
`<primitivetypes>` (reemplazado por el de EA: igual), el vocabulario no medido `defaultValue`/`specification`
(borrado: igual), que el dueño del diagrama sea un `uml:Package` real (igual) y que las figuras sean miembros
directos del dueño (igual).

**El bloque `<diagrams>` de nuestro emisor estaba bien desde el principio.** Probado dos veces: metido en el documento
de EA produce el diagrama (`Diagram_ID 341`, `ea_guid` = nuestro id de diagrama, 2 figuras ancladas), y el documento
de EA con **nuestro árbol semántico** más **nuestro bloque `<diagrams>`** también lo produce (90 → 91).

### 10.4 Lo que queda sin medir, y se declara

- Los tipos primitivos: los declaramos en el árbol semántico como `uml:PrimitiveType` con ids `EAID_…` generados, y
  dejamos `<primitivetypes>` sin declaraciones. EA escribe `EAJava_String`, `EAJava_Boolean`, `EAJava_void`,
  `EAnone_*` **dentro** de `<primitivetypes>` y los referencia por nombre. **Medido que no rompe nada** —los tipos
  resolvieron—, pero **no medido** si otra build o otro camino de EA lo tolera. Es un desvío declarado, no un defecto
  probado.
- El sentido **EA → app** no se midió en esta sección. Es una unidad aparte.
- Los tres defectos del control positivo de X8 (§7.5) siguen como estaban: el arnés comprueba contra su propio
  conjunto de relaciones y el defecto 2 ya fue diagnosticado en §7.6 como desacuerdo de conteo, no como emisión.

### 10.5 Plan de reparación, con su evidencia de cierre

**Commit de la unidad:** `c561376` — `fix(xmi): declare the Enterprise Architect dialect marker on export`, sobre `feat/xmi-intercambio`.

| # | Tarea | Estado y evidencia |
| --- | --- | --- |
| R1 | Emitir el arquero de dialecto medido en `xmi:Documentation` (`exporter="Enterprise Architect"`, `exporterVersion="6.5"`) y conservar la procedencia real en un comentario XML; invertir la aserción de `XmiSemanticEmitterTest` que codificaba X-5, con la medición citada | **Cerrada.** `mvn -o -B -f backend/pom.xml -Dtest=XmiSemanticEmitterTest test` = **8 pruebas, 0 fallos**; la aserción vieja ya no existe y la nueva exige el arquero y el comentario |
| R2 | Emitir `extenderID="6.5"` en `xmi:Extension`, medido en §3.1 | **Cerrada.** La prueba afirma `extenderID`; sin él la importación también funciona (T16), así que es fidelidad de dialecto, no requisito |
| R3 | Verificación de punta a punta contra EA, sobre copia | **Cerrada.** `mvn -o -B -f backend/pom.xml verify` = **257 pruebas, 0 fallos**; `python -m pytest tools -q` = **46 pruebas, 0 fallos**; importación de `backend/target/xmi-sample.xml` (sha256 `32bec0aa…`) sobre una copia de `sw1_p2.eapx` con `ImportPackageXMI(…, ImportDiagrams=1, StripGUID=0)`: EA devolvió `{00000000-0000-0000-0000-000000000001}` —el id propio—, `t_diagram` **90 → 91** con `Diagram_ID 341`, nombre `Default` y `ea_guid {00000000-0000-0000-0000-00000000012C}`, y **2 filas** en `t_diagramobjects` |
| R4 | Revertir X-5 en §4 y actualizar el contrato `docs/puds/contratos/04-dialecto-xmi-ea.md` | **Cerrada.** La fila X-5 de §4 declara la reversión con la medición citada, y el contrato declara el arquero como requisito medido |
| R5 | Registrar los límites de §10.3 y §10.4 en el cierre de la unidad | **Cerrada** con esta sección; los límites viven en §10.3 y §10.4 y el traspaso al acta es §8.1 |

### 10.6 El traspaso al acta, en las dos listas que §8.1 exige

**Lo medido**, cada número con el comando que lo produjo:

| Medición | Comando |
| --- | --- |
| 257 pruebas, 0 fallos | `mvn -o -B -f backend/pom.xml verify` |
| 46 pruebas, 0 fallos | `python -m pytest tools -q` |
| 19.470 bytes, sha256 `32bec0aa…` | `sha256sum backend/target/xmi-sample.xml` |
| `t_diagram` 90 → 91, ids preservados, 2 figuras | `import.ps1` + `query.ps1` sobre `backend/target/xmi-lab/import-final.eapx` (evidencia y muestras en `backend/target/xmi-lab/`) |

**Lo no medido**, explícito:

- Si otra build de EA, u otro camino de importación, tolera nuestra declaración de primitivos (ids `EAID_` generados
  en el árbol semántico y `<primitivetypes>` sin declaraciones) como la toleró ésta.
- El sentido **EA → app** completo.
- Si EA acepta un comentario XML **fuera** de la raíz en todas sus versiones. Se midió que lo acepta en ésta: el
  artefacto verificado es el que lo lleva.
- El significado de la gramática `Path=` sigue sin establecerse, que es por qué X-4 sigue emitiendo vacío.

## 11. Unidad E — el sentido EA → app, medido con exportaciones reales de EA

La unidad de §10 cerró **app → EA**. Ésta mide el sentido inverso: qué pasa cuando el archivo lo escribe Enterprise
Architect. §7 ya tenía dos lecturas del dialecto ajeno —el fixture `Prueba3.xml` y un archivo suelto
`ea-ref-with-diagram.xmi`—, pero las dos son lecturas **del documento**, y una de ellas ni siquiera corre.

### 11.1 El instrumento de exportación, y una corrección a §3.10

§3.10 declaró `ExportPackageXMI` como «lentísimo» —24 exportaciones sin terminar en 10 minutos— y por eso la vía de
exportar quedó abandonada. **Medido ahora: es rápido.** Una sesión completa de exportación —`OpenFile`, seis
exportaciones y `Exit`— tarda **18,8 s**, y la exportación completa de 2.1 tarda **1.067 ms**. La lentitud que §3.10
midió era la misma fragilidad ya registrada en §7.2: **instancias sucias de EA**. Con una instancia limpia aparece el
instrumento que faltaba, y es el mismo `import.ps1` que ya funcionaba más un `export.ps1` hermano
(`backend/target/xmi-lab/`, artefactos de build).

Dos mediciones hubo que hacer **antes** de escribir una línea, porque la documentación de la API no coincide con esta
build. Las dos salen de `Interop.EA.dll`, leído por reflexión (`probe3.ps1`):

```text
ExportPackageXMI  (string PackageGUID, EnumXMIType XMIType, int DiagramXML, int DiagramImage, int FormatXML, int UseDTD, string FileName)
ExportPackageXMIEx(string PackageGUID, EnumXMIType XMIType, int DiagramXML, int DiagramImage, int FormatXML, int UseDTD, string FileName, int Flags)

EA.EnumXMIType:  xmiEADefault=0  xmiEA11=3  xmiEA21=11  xmiEA22=14
EA.ExportPackageXMIFlag:  epSaveToStub=1  epExcludeEAExtensions=2
```

El parámetro `WriteLog` que documenta Sparx **no existe** en esta build, y `xmiEA21` vale **11**, no el `2` que
sugiere la lista habitual —con `2` se exporta `xmiEA10`, en silencio—. Las dos cosas se habrían pagado como una
corrida fallida cada una.

### 11.2 La matriz de exportaciones, y el defecto del sentido inverso

Se exportó **el paquete que contiene nuestro propio modelo importado más el diagrama que EA creó** (la copia de
`import-final.eapx`), o sea un artefacto con verdad de referencia conocida **en los dos extremos**. Seis variantes,
medidas con `matrix.ps1`:

| Variante | `XmiType` | `DiagramXml` | `Flags` | Bytes | Dialecto medido | `Extension` | `diagrams` |
| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: |
| `ea21-full` | 11 | 1 | 0 | 38.208 | `xmi:version="2.1"`, `uml:Model` | 1 | 1 |
| `ea21-noext` | 11 | 1 | 2 | 12.948 | `2.1`, `uml:Model` | 1 | 1 |
| `ea21-nodiagram` | 11 | 0 | 0 | 35.137 | `2.1`, `uml:Model` | 1 | 0 |
| `ea22` | 14 | 1 | 0 | 37.102 | `2.1`, `uml:Model` | 1 | 1 |
| **`ea-default`** | **0** | 1 | 0 | 46.168 | **`XMI 1.1`**, `UML:Model` | **0** | **0** |
| `ea11` | 3 | 1 | 0 | 46.168 | `XMI 1.1`, `UML:Model` | 0 | 0 |

Lo que la matriz decide, y es el hallazgo de la unidad:

1. **La exportación por defecto de EA no es 2.1, es el dialecto 1.1.** Su cabecera es
   `<XMI xmi.version="1.1" xmlns:UML="omg.org/UML1.3" timestamp="…">`, con `XMI.exporter` como **elemento** y no como
   atributo, y **sin diagramas**. Nuestro lector exige `uml:Model` en el espacio de nombres 2.1 —`first()` usa
   `getElementsByTagNameNS`, o sea que es estricto— y por lo tanto **no puede leerla**: falla con
   «The XMI document must contain uml:Model». Si alguien exporta con la opción por defecto del diálogo de EA e
   importa en la app, ve exactamente el síntoma reportado, y la causa no es un elemento mal generado sino un
   **dialecto distinto**.
2. **`epExcludeEAExtensions` no quita el diagrama**: 38.208 → 12.948 bytes con `<diagrams>` intacto. Lo que cae es el
   bloque `<elements>` de la extensión. Es decir que excluir extensiones **no** explica un diagrama ausente.
3. `DiagramXml=0` sí quita `<diagrams>`, y es una opción explícita del usuario, no un defecto.
4. Una exportación de 2.1 conserva **nuestros propios identificadores** (`EAID_00000000_…`) al re-exportar. El viaje
   completo app → EA → app cierra con la misma identidad, que es lo que §10 habilitó.

### 11.3 Refutado en esta unidad, con instrumento válido

- **Que EA omita los anclajes de los conectores.** En la exportación 2.1 los tres conectores traen
  `style=Mode=3;EOID=34628065;SOID=23F57788;…`, y esos dos valores son exactamente los `DUID` de las dos figuras del
  diagrama: los anclajes están y **resuelven**. La hipótesis de que hubiera que reconstruirlos desde `<links>` era
  falsa, y se cae antes de escribir código.
- **Que `<links>` sea un canal perdido al leer.** Las 12 entradas de `<links>` de la exportación repiten `(id, start,
  end)` y **las 12 son redundantes**: cada identificador ya aparece en el árbol semántico o en los conectores de la
  extensión. Ignorar el canal no pierde nada *en este artefacto*. Queda declarado abajo que en general no está medido.
- **Que el archivo de EA con diagrama tenga que vivir fuera del repositorio.** Es un problema de higiene, no de
  dialecto, y se arregla en E1.

### 11.4 Lo que queda sin medir en esta unidad

- Si `<links>` es redundante en un documento de EA **en general**, o solo en éste. Se midió en un artefacto.
- Si EA respeta el `xmi:id` que emitimos en **todas** sus opciones de exportación, o solo en 2.1.
- El sentido 1.1 → app como funcionalidad. Acá se mide que **se rechaza**, no se propone soportarlo.
- El resto de §10.4 sigue vigente sin cambios.

### 11.5 Plan de la unidad, con su evidencia de cierre

| # | Tarea | Evidencia |
| --- | --- | --- |
| E1 | Llevar al repositorio, como fixtures pinchados por sha256 en `docs/fixtures/xmi/` (byte-exactos, `windows-1252`), los artefactos que EA escribió: la exportación 2.1 de ida y vuelta, la exportación por defecto 1.1, la exportación «2.2», y el archivo independiente que vivía en `C:/Users/HP/Downloads`. Sacar el `assumeTrue` que salteaba la única prueba del sentido EA → app **con diagrama** | **Cerrada**: `XmiReaderTest.everyCommittedEaArtifactIsByteExact` pincha los 5 sha256; la prueba del archivo independiente apunta al fixture y corre — `XmiReaderTest`: 7 pruebas, **0 salteadas** |
| E2 | Medir el lector sobre la matriz y fijar los números: la exportación 2.1 se lee completa —con anclajes resueltos—, y la 1.1 se rechaza | **Cerrada, y ampliada**: la medición (§11.6) mostró que **`ea22` también se rechaza**; hay una aserción por artefacto, con los números medidos |
| E3 | Que el rechazo del dialecto **diga qué pasó**: nombrar el dialecto encontrado y la opción de exportación que corresponde, en vez de solo el elemento ausente | **Cerrada**: `XmiReader.unsupportedDialect(root)`; probado con los dos fixtures reales |
| E4 | Declarar la matriz de exportación en `docs/puds/contratos/04-dialecto-xmi-ea.md`: qué opciones de exportación de EA acepta la app y cuáles no, con la medición | **Cerrada**: §6.1 del contrato, y su encabezado dejó de decir que el roundtrip en vivo era inverificable |

### 11.6 Lo que el lector devolvió sobre la matriz (medido)

Cada archivo se leyó con el lector real, sin escribir código: `jshell --class-path backend/target/classes
backend/target/xmi-lab/measure.jsh`, que llama a `XmiReader.read(bytes)` sobre los seis artefactos de la matriz más
los dos fixtures. Salida literal, resumida:

| Artefacto | Clasificadores | Relaciones | Vistas | Diagramas | Conectores | Pérdidas declaradas |
| --- | --- | --- | --- | --- | --- | --- |
| `ea21-full` | 9 | 6 | 1 | 1 | 3, anclas **resueltas** | `attribute_layout_bounds`=3, `attribute_layout_containment`=3, `attribute_layout_coords`=3, `constraint_severity_not_representable`=1, `unresolved_constraint_expression`=1, `unresolved_relationship_end`=2 |
| `ea21-noext` | 9 | 6 | 1 | 1 | 3, **sin anclas** | las tres de `attribute_layout` desaparecen; **`AGGREGATION` y `COMPOSITION` quedan con 1 extremo en vez de 2** |
| `ea21-nodiagram` | 9 | 6 | 0 | 0 | — | las seis de `ea21-full` |
| `ea22` | — | — | — | — | — | **Rechazado**: nombra el namespace UML 2.2 |
| `ea-default` (1.1) y `ea11` | — | — | — | — | — | **Rechazados**: nombran el dialecto XMI 1.1 |
| `ea-independent-with-diagram.xmi` | 6 | 4 | 1 | 1 | 4, **sin anclas** (`SOID`/`EOID` en `null`) | las tres de `attribute_layout`=4 y `diagram_connector_anchors_missing`=4 |

Lectura de la tabla, sin adornos:

- **El defecto del sentido EA → app no es «los elementos no se generan»: es el dialecto.** La opción por defecto del
  diálogo de exportación de EA escribe XMI 1.1, y el lector implementa 2.1. Hasta esta unidad eso se reportaba como
  «The XMI document must contain uml:Model», que manda a buscar un elemento ausente donde la causa es una opción de
  exportación.
- **El único fixture del sentido EA → app con diagrama se salteaba en silencio**, porque su entrada vivía en
  `C:/Users/HP/Downloads` y la prueba la protegía con `assumeTrue`: en CI no corría nunca.
- **`ea22` también se rechaza, y no estaba previsto.** EA rotula esa opción «XMI 2.2» pero escribe
  `xmi:version="2.1"` con `xmlns:uml="…/spec/UML/2.2"`. El lector es estricto por namespace, así que la 2.2 cae
  igual que la 1.1, por otra razón.
- **Sin la extensión se pierde semántica, no solo andamiaje.** `epExcludeEAExtensions` deja un solo extremo en la
  agregación y en la composición, y borra las anclas de los conectores: confirma §4.2 —los modificadores viven solo
  en los bloques propietarios— y agrega que los anclajes también.

**Lo que esto cambió en el producto.** El rechazo pasó a nombrar el dialecto: `XmiReader.unsupportedDialect(root)`
distingue XMI 1.1 («es lo que Enterprise Architect exporta por defecto; exportá como XMI 2.1») del namespace ajeno
(«declara `…/spec/UML/2.2`, y este lector implementa `…/spec/UML/2.1`»), y deja el mensaje original como último
recurso. Las dos ramas se prueban con los fixtures reales, no con cadenas inventadas.

### 11.7 El traspaso al acta, en las dos listas que §8.1 exige

| Medido | Comando o artefacto |
| --- | --- |
| 5 artefactos de EA byte-exactos, pinchados por sha256 | `XmiReaderTest.everyCommittedEaArtifactIsByteExact` |
| La exportación 2.1 se lee completa: 9 clasificadores, 6 relaciones, 1 diagrama de 2 figuras y 3 conectores con las anclas resueltas | `XmiReaderTest.readsTheEaRoundTripExportOfOurOwnModelWithItsAnchorsResolved` |
| El archivo independiente, de otro repositorio, con ids ajenos y sin anclas | `XmiReaderTest.readsTheIndependentEaFileWithoutAssumingOurUuidDialect`, ya sin `assumeTrue` |
| El rechazo nombra el dialecto encontrado y la opción que corresponde | `XmiReaderTest.namesTheDialectItCannotReadInsteadOfOnlyTheMissingElement` |
| La opción por defecto de EA es XMI 1.1; la 2.2 escribe el namespace UML 2.2 | `head -c 260` sobre cada archivo de `backend/target/xmi-lab/matrix/` (§11.2) |
| La matriz, declarada en el contrato | `docs/puds/contratos/04-dialecto-xmi-ea.md` §6.1; encabezado corregido |
| Toda la batería | `mvn -o -B -f backend/pom.xml verify` = **260 pruebas, 0 fallos, 0 salteadas**; `python -m pytest tools -q` = **46 pruebas, 0 fallos** |
| La revisión nativa del cambio de conducta | Linaje `review-79d56d80554b3cd0`, base `044d035b`, candidato `615e84ae2f`: riesgo **medio**, 1 lente, 4 archivos y 317 líneas, **0 correcciones**, veredicto **`approved`**; autoridad quemada (`gentle-ai.review-acknowledged/v1`). Los 4 archivos son el lector, sus pruebas y los dos documentos: los fixtures quedaron en la base, para que la revisión no gaste su presupuesto en miles de líneas de XML de EA |

| **No medido**, y así se declara | Por qué |
| --- | --- |
| Que `<links>` sea redundante en general, y que EA preserve nuestros `xmi:id` en todas las opciones | Se midió en un archivo cada cosa (§11.3, §11.4) |
| El dialecto XMI 1.1 o 2.2 como **capacidad** | Solo se mide su rechazo: soportarlos es una decisión de producto, no un defecto |
| La importación de un XMI de EA con notas, o con los 9 constructos de §3 sin evidencia | Ningún artefacto de esta unidad los ejerce |
| El texto del hallazgo no bloqueante `R3-001` (SUGGESTION, `XmiReader.java:91`, lente de fiabilidad) | El cierre de la revisión trae su ubicación y su severidad, no su texto. Queda como trabajo posterior; **no** habilita repetir la revisión de este candidato |

## 12. Consolidación con el Incremento 3, publicación y movimiento de `main`

Cierra el pedido del usuario: publicar la unidad XMI y llevar `main` al estado que reúne las dos ramas. Todo lo que
sigue está medido en este repositorio, salvo lo que §12.5 y §12.6 declaran como no reconciliado o no revisado.

| Paso | Comando | Resultado medido |
| --- | --- | --- |
| Estado inicial del remoto | `git remote -v` | **Vacío**: el repositorio no tenía ningún remoto |
| Remoto | `gh repo create Fivoryu/1erParcialV2 --private` | Repositorio privado nuevo en `https://github.com/Fivoryu/1erParcialV2` |
| Publicación de la rama | `git push -u origin feat/xmi-intercambio` con el git **de Windows** | `* [new branch] feat/xmi-intercambio -> feat/xmi-intercambio` |
| Fusión | `git merge --no-edit feat/incremento-3-colaboracion` | 3 archivos en conflicto, resueltos a mano; commit de fusión `f3f8e33`, **con dos padres** |
| Movimiento de `main` | `git branch -f main <punta fusionada>` | `main` estaba en `90f4f4a` y es **ancestro** de la punta (`git merge-base --is-ancestor` = sí): el movimiento es avance rápido, sin reescritura de historia |

**El git de WSL no sirve para empujar.** El intento desde WSL quedó colgado **900 s** y no publicó nada: esa
instalación no tiene ayudante de credenciales. La publicación se hizo con `git` 2.54.0.windows.1 desde PowerShell,
que sí trae `manager`. Queda anotado porque es un límite del entorno, no del repositorio.

### 12.1 Los tres conflictos, y por qué se resolvieron así

Todos los hunks eran **adiciones de los dos lados**; la resolución es la unión, con una excepción semántica en la
compuerta de esquema.

| Archivo | Conflicto | Resolución |
| --- | --- | --- |
| `SchemaOwnershipTest.java` | `TABLES_RESERVED_FOR_LATER_TASKS` | Quedó en **cinco** entradas: `model_conflict`, `name_collision`, `ai_proposal`, `detected_element`, `audit_log`. Mi base reservaba `invitation` —que la otra sesión migró en su C8 y corrigió en `c7e17fc`— y la punta de la otra sesión reservaba `xmi_exchange` y `xmi_loss_item`, que migro yo en `V10`. Después de fusionar **las tres existen** y están declaradas en `TABLES`, así que dejar reservada cualquiera de ellas **haría fallar la compuerta de propiedad exacta** |
| idem | Aserción de versiones de Flyway | `1..10`. Es un **subconjunto**, no la compuerta: la compuerta real son las tablas, las columnas mapeadas y los índices de soporte, que quedaron en `xmi_exchange → {idx_exchange_model}` y `xmi_loss_item → ∅` |
| `web/src/api/client.ts` | Interfaz y objeto | Los **cuatro** métodos de ambos lados: `previewXmi`, `importXmi`, `reportProjectPresenceTouch`, `reportProjectCursorPosition` |
| `web/src/pages/ProjectEditorPage.tsx` | Import y JSX | `XmiImportPanel` del lado XMI, `PresencePanel` y `MembersPanel` del lado colaboración |

Además se comprobó, **por eliminación contra cada padre** (`git diff HEAD` y `git diff MERGE_HEAD`, mirando solo las
supresiones), que ninguna otra línea se perdió: las únicas supresiones son las dos ediciones intencionales de la
compuerta, y el resto de las diferencias respecto de cada padre son cambios propios de ese lado fuera de los hunks
en conflicto.

### 12.2 Un defecto mío, y quién lo encontró

La primera resolución del hunk grande de `client.ts` **dejó caer `previewXmi`** del objeto de implementación,
mientras la interfaz seguía declarándolo. Ninguna suite de Java lo ve; el verificador independiente sí:

```
src/api/client.ts(255,3): error TS2741: Property 'previewXmi' is missing in type '{ register(...): ...;
  26 more ...; stopDeployment(deploymentId: string): Promise<...>; }' but required in type 'ApiClient'.
```

Lo devolvieron `npm run lint` y `npm run build` con el mismo error. Se restauró **antes** de commitear la fusión y se
volvió a verificar. Queda registrado porque es la lección que importa: **una fusión resuelta a mano necesita su
propia verificación aunque las dos ramas por separado estén verdes**, y esta vez la encontró el verificador.

### 12.3 Verificación medida sobre el árbol fusionado

| Comprobación | Comando | Resultado |
| --- | --- | --- |
| Backend | `mvn -o -B -f backend/pom.xml verify` | **307 pruebas, 0 fallos, 0 errores, 0 salteadas**, BUILD SUCCESS |
| Arnés de Python | `python -m pytest tools -q` | **46 pruebas, 0 fallos** |
| Web: tipos y estilo | `npm run lint` (`eslint . && tsc --noEmit`) | Sin diagnósticos |
| Web: construcción | `npm run build` (`tsc --noEmit && vite build`) | **226 módulos**, construido en **6,17 s**, `dist/` emitido |
| Conflictos | `grep -rn "^<<<<<<< \|^>>>>>>> "` y `git diff --name-only --diff-filter=U` | Sin coincidencias, sin rutas sin resolver |

Nota honesta sobre el orden: el backend se midió **antes** de la corrección de una línea en el cliente web; esa
corrección no puede afectar a Java, y el lado web se volvió a medir **después**. Las dos corridas están declaradas.

### 12.4 La consecuencia declarada de mover `main`

`main` queda apuntando a un **Incremento 3 a medias**: la fusión se apoya en `61bf2d2`, así que lo que la otra sesión
cierre después —el servicio y los endpoints de chat (C17 en adelante), el lease de edición (S5) y el arnés de cierre
(S7)— **no está en `main`**. Que la rama por defecto quede atrás de `feat/incremento-3-colaboracion` es **permanente
hasta que alguien la mueva otra vez**, y ese movimiento lo vuelve a decidir el usuario.

La otra sesión planteó la objeción con este dato delante, el usuario decidió mover igual, y queda asentado acá como
**decisión suya registrada**, no como descuido de ninguna de las dos sesiones.

**Cuánto queda atrás, medido.** Al cerrar esta sección, `git rev-list --count main..feat/incremento-3-colaboracion`
= **2** commits: `d9d4d0a` («talk in the diagram») y su registro `384edd1`. Lo que la próxima unión arrastraría, según
`git diff --name-status 61bf2d2 384edd1`, son cinco archivos nuevos del chat (`ChatController`, `ChatResources`,
`ChatService`, `DiagramMessage`, `DiagramMessageRepository`), su prueba `ChatApiTest.java`, y dos documentos
compartidos modificados: la fila nueva de `message.posted` en §4.1 de `01-contratos-transversales.md` —`message`:
`id`, `authorUserId`, `authorDisplayName`, `body`, `createdAt` (ISO 8601)— y el plan de la otra unidad.

**Y medido otra vez, porque el desvío se movió.** Al cerrar §13, `git rev-list --count
main..feat/incremento-3-colaboracion` = **9** commits, con la punta en `38882b4`. El número de arriba —2 commits,
punta `384edd1`— era cierto cuando se midió, y `git merge-base --is-ancestor 384edd1
feat/incremento-3-colaboracion` confirma que la rama **avanzó**: no hubo reescritura de historia. Los cuatro commits
que la otra sesión nombró como «los que la rama fusionada no ve» (`14053ce`, `d9ebf54`, `39dd9cf`, `38882b4`) son los
que agregó **desde** esa medición, no el total del desvío.

### 12.5 Un defecto de este mismo documento, declarado

El cuadro **«Estado de ejecución»** (líneas 29–44) marca **«Pendiente»** las filas **X3 a X8**, pero **§5.2 «Qué
verificación cerró cada etapa» (líneas 506–619) registra la ejecución y la verificación de esas etapas**, con los
totales de `mvn verify` por unidad y los veredictos de sus verificadores. El cuadro resumen quedó sin actualizar
cuando el plan se reorganizó.

**No se reconcilia en esta sección**, y se declara con su ubicación exacta para que nadie lea ese cuadro como el
estado real de la unidad XMI: la decisión quedó fuera del alcance que el usuario eligió para este turno, y hacerlo
bien exige mapear la evidencia de §5.2 etapa por etapa, no pintar seis casilleros.

### 12.6 La revisión nativa del candidato de fusión, y por qué no se corrió

`gentle_review` con `operation: inspect` sobre este worktree devolvió `applicability: unrelated` con la proyección
de cambios **vacía** —el árbol quedó commiteado— y `next_transition.reason_code:
empty_candidate_base_ref_required`: un START tendría que llevar un `baseRef` explícito. Para un commit de fusión las
únicas bases posibles son sus dos padres, y ninguna de las dos aísla lo que esta fusión escribió a mano:

| `baseRef` | Alcance que revisaría |
| --- | --- |
| `7c2ad45` (mi punta previa) | **58 archivos, 7.052 líneas agregadas, 55 borradas**: el contenido completo del Incremento 3, ya commiteado por la otra sesión |
| `61bf2d2` (su punta) | **41 archivos, 11.048 líneas agregadas, 24 borradas**: el contenido completo de la unidad XMI |

O sea que la revisión nativa no puede apuntar a las **~10 líneas** que resolvieron los conflictos (§12.1):
apuntaría a trabajo ya revisado o ya commiteado por ambas ramas. **Y esas diez líneas son código nuevo**, no un
trámite: la resolución de un conflicto se escribe a mano y es justo donde se cuelan los defectos —§12.2 es la prueba,
con un método público del cliente que desapareció y ninguna suite de Java capaz de verlo—. Lo que hay que declarar,
entonces, no es «cubierto» sino **cubierto por evidencia de otra naturaleza**:

| Naturaleza | Qué la aporta | Qué no puede decir |
| --- | --- | --- |
| Revisión nativa | Nadie: no existe un candidato que aísle la resolución (tabla de arriba) | **Nada** sobre esta fusión; su ausencia no se compensa con las otras filas |
| Verificación independiente | Un verificador leyó el árbol fusionado, encontró el `TS2741` de `client.ts` (§12.2) y **volvió a verificar** después de la corrección: lint y build en verde | No es un veredicto de revisión, y no cubre lo que no se le pidió mirar |
| Compuertas automáticas | `mvn verify` = 307/0, `pytest` = 46, `npm run lint`, `npm run build` (§12.3) | Miden el árbol resultante, no la intención de la resolución |
| Autocrítica del autor | §12.2 declara el defecto, su causa y el orden de las corridas | No reemplaza a un tercero |

La precisión de este apartado —que la resolución **es código nuevo** y que su cobertura es de **otra naturaleza**,
no revisión— la pidió la sesión del Incremento 3 al leer el primer registro, y se aplica acá con su atribución.
Queda declarado como **no revisado nativamente** y como decisión pendiente del usuario, no como omisión silenciosa.

### 12.7 Lo que esta sección no mide

| **No medido** | Por qué |
| --- | --- |
| La reconciliación de las filas X3–X8 del cuadro de estado | Declarada en §12.5 |
| Que la fusión resuelta a mano tenga revisión nativa propia | Declarado en §12.6, con los dos alcances medidos y su costo |
| Lo que la otra sesión cierre después de `61bf2d2` | Depende de su trabajo y exigirá otra unión |

## 13. Unidad F — lo que el oráculo no medía, y el diagnóstico que no miraba el prefijo

Cierra los dos pendientes que §12 dejó abiertos: la reconciliación del cuadro de estado (§12.5) y los tres defectos
de emisión de §7.5 más el hallazgo no bloqueante `R3-001` de la revisión nativa.

### 13.1 La reconciliación del cuadro de estado

El cuadro decía «Pendiente» en X3–X8 mientras §5.2 registraba la verificación de esas etapas. Se reconcilió contra
§5.2 y contra los tests que existen hoy, **sin escribir una sola fila nueva**: cada fila cita ahora su corrida. La
regla que se aplicó es la de este repositorio —una fila no se marca cerrada por lo que la unidad hizo, sino por la
corrida que la cierra— y la única fila que **estaba y sigue abierta** es X8, porque su arnés es exactamente lo que
esta unidad arregla.

### 13.2 El control positivo refutó las tres aserciones: el defecto era del oráculo

§7.5 dejó tres fallas declaradas y una instrucción explícita: no suponer, medir. Medido, el resultado **invierte el
diagnóstico**. La corrida de hoy sobre nuestro documento reproduce exactamente las tres fallas:

```
=== XMI acceptance summary: 15 passed, 3 failed ===
[FAIL] Declared dialect vocabulary: backend/target/xmi-sample.xml
[FAIL] Measured connector references resolve to model relations and classifiers: backend/target/xmi-sample.xml
      observed: {"connectorsWithMissingOrUnresolvedEndpoints": 1, "extensionConnectors": 6,
                 "modelRelations": 5, "unmatchedRelationIds": ["EAID_00000000_0000_0000_0000_000000000050"]}
[FAIL] Measured connector labels include the complete observed form: backend/target/xmi-sample.xml
      observed: {"completeLabelSets": 3, "labelKeyCounts": {"lb": 6, "lt": 3, "mt": 5, "rb": 6, "rt": 3}}
```

Y la prueba que decide es poner **el archivo de Enterprise Architect** en los dos casilleros del arnés, que es
exactamente el control que el arnés existe para hacer:

| Corrida | Casillero «control positivo» | Casillero «emitido» | Resultado |
| --- | --- | --- | --- |
| A | `Prueba3.xml`, escrito por EA | `backend/target/xmi-sample.xml`, nuestro | **15 pasadas, 3 fallas** — las tres, del lado nuestro |
| B | `ea-export-uml-2.2.xml`, escrito por EA | el mismo archivo, escrito por EA | **12 pasadas, 6 fallas** — **las mismas tres fallas, dos veces** |

**Si el artefacto de EA falla las mismas tres aserciones, el defecto está en las aserciones.** La causa de cada una
está medida:

| Falla | Causa medida |
| --- | --- |
| Referencias de conector sin relación emparejada | El oráculo reconoce `uml:Association`, `uml:Generalization` y `uml:Realization`, **y no `uml:Dependency`**: la dependencia `EAID_…050` viaja en la extensión como registro de conector y no aparece del lado del modelo. Pero EA **también** emite un registro de conector con `ea_type="Dependency"` para una dependencia en su propio archivo. Nuestra emisión copia la forma medida; el oráculo es el que no la cuenta |
| Etiquetas `mt` incompletas | El emisor omite `mt` cuando la relación **no tiene nombre**, y el archivo de EA hace lo mismo: su generalización lleva `<labels/>`. «Un `mt` por conector» no es la forma del dialecto cuando la relación es anónima |
| Vocabulario no declarado | `defaultValue` y `specification` los emite nuestro emisor con la grafía **estándar de UML 2.1**; el fixture de EA tiene **cero** de los dos, y su archivo 2.2 **también falla esa aserción**. Es vocabulario no medido, y le corresponde una **lista de desvíos declarados**, no una aserción que lo trate como falla |

**Lo que esto significa para §7.5, sin adornos.** Dos de los tres «defectos» nunca fueron defectos de nuestra
emisión: eran el oráculo midiendo un dialecto más pobre que el que EA escribe. El tercero no es un error de código
sino vocabulario no medido, y su lugar es una lista de desvíos explícita. La resolución sigue siendo la que §7.5
prescribió —**distinguir un desvío declarado de una falla**, sin ablandar ninguna aserción— pero apunta al oráculo y
al arnés, no al emisor.

### 13.3 El hallazgo `R3-001`: reconstruido, y así se declara

El cierre de la revisión nativa trae **ubicación** (`XmiReader.java:91`), **severidad** (`SUGGESTION`) y **lente**
(`review-reliability`), y **no su texto**. El texto no está en ninguna parte del estado durable: el registro de
consumo del linaje `review-79d56d80554b3cd0` pesa **259 bytes** y solo lleva el linaje, y no queda ninguna
transacción `v2` de ese linaje. Se buscó antes de afirmarlo.

En el árbol de hoy la línea 91 es la lectura de `xmlns:uml` dentro de `unsupportedDialect`, el método que la unidad E
agregó para nombrar el dialecto. El defecto que hay ahí, y que esta unidad arregla, es de la familia que la lente
de fiabilidad mira: **el diagnóstico lee el prefijo literal `uml` en vez de mirar las declaraciones de namespace**,
así que un documento que declare el namespace de UML 2.2 bajo otro prefijo —`xmlns:u="…/spec/UML/2.2"`— cae en el
mensaje genérico y vuelve a esconder la causa, que es justo lo que la unidad E vino a reparar.

Queda declarado como **reconstrucción**: se conocen ubicación, severidad y lente, y se arregla el defecto que existe
en esa ubicación, no uno citado.

### 13.4 Plan de la unidad

| # | Tarea | Evidencia de cierre |
| --- | --- | --- |
| F1 | Reconciliar las filas X3–X8 del cuadro de estado contra §5.2 | Las seis filas citan su corrida; X8 queda abierta hasta F6 |
| F2 | El oráculo cuenta `uml:Dependency` como relación del modelo | Prueba nueva, primero en rojo; la aserción de referencias pasa con nuestro documento **y** con el de EA |
| F3 | La aserción de etiquetas codifica la regla medida: relación sin nombre ⇒ sin `mt`, y sigue exigiendo `mt` cuando la relación tiene nombre | Prueba que falla si se le quita el `mt` a una relación nombrada |
| F4 | Lista de **desvíos declarados** de vocabulario, derivada de los artefactos de EA medidos, con `defaultValue`/`specification` nombrados y su razón | Cualquier vocabulario nuevo y no medido **sigue fallando**, y una prueba lo demuestra |
| F5 | `unsupportedDialect` mira las declaraciones de namespace en vez del prefijo literal | Prueba con un documento que declare UML 2.2 bajo otro prefijo |
| F6 | Corrida final: arnés, `pytest`, `mvn verify` | Arnés sin fallas; suites verdes con sus totales |
| F7 | Registro y revisión nativa del candidato | §13.6: cuatro lentes, **aprobada**, autoridad quemada |

### 13.5 Resultado

Medido al cerrar, el `2026-10-03`. La columna «antes» es la corrida que abrió la unidad, no una estimación.

| Comprobación | Comando | Antes | Después |
| --- | --- | --- | --- |
| Arnés, corpus propio | `python tools/xmi-acceptance/xmi_acceptance.py --ea-fixture docs/fixtures/xmi/Prueba3.xml --emitted-sample backend/target/xmi-sample.xml` | **15 pasadas, 3 fallas** | **18 pasadas, 0 fallas** |
| Arnés, artefacto de EA en los dos casilleros | el mismo arnés con `ea-export-uml-2.2.xml` en ambos | **12 pasadas, 6 fallas** | **18 pasadas, 0 fallas** |
| Oráculo, pruebas propias | `python -m pytest tools/xmi-inventory/test_xmi_inventory.py -q` | 5 fallas, 32 pasadas | **39 pasadas** |
| Arnés de Python, completo | `python -m pytest tools -q` | 46 pasadas | **54 pasadas** |
| Backend | `mvn -o -B -f backend/pom.xml verify` | 307 pruebas, 0 fallos | **308 pruebas, 0 fallos, 0 errores, 0 salteadas, BUILD SUCCESS** |
| Diagnóstico del lector | `mvn -o -B -f backend/pom.xml -Dtest=XmiReaderTest test` | 8 pruebas, 1 falla | **8 pruebas, 0 fallas** |

**Y la parte que importa de una compuerta: que pueda fallar.** Cuatro mutaciones sobre **copias fuera del
repositorio** —quitarle el `mt` a una relación con nombre, inyectar una etiqueta no medida, borrar el registro de
conector de la dependencia y romper un anclaje— hicieron fallar **cada una su aserción** (`17 pasadas, 1 falla` en
las cuatro). Sin eso, el `18/0` sería un arnés que pasa, no un oráculo.

**Lo que cambió de diagnóstico.** Dos de los tres defectos que §7.5 declaró como nuestros nunca lo fueron: el oráculo
medía un dialecto **más pobre** que el que EA escribe. El tercero no era un error de código sino vocabulario no
medido, y ahora vive donde corresponde: una lista de **dos desvíos declarados** con su razón, que deja fallando a
cualquier vocabulario nuevo que nadie midió. La instrucción de §7.5 se cumplió al pie: **ninguna aserción se ablandó**,
y la prueba son las cuatro mutaciones.

**El cuadro de estado quedó reconciliado** en §13.1 contra §5.2 y contra los tests que existen: X3, X4, X5, X6 y X7
citan su corrida, y X8 se cierra acá. Las seis filas se escribieron mirando la evidencia, no el plan.

| **No medido**, y así se declara | Por qué |
| --- | --- |
| Que Enterprise Architect acepte el documento emitido en una importación viva | El arnés valida estructura de archivo y **no corre EA**; eso lo cubren §10 y §11 con el instrumento de EA |
| La gramática de `Path=` | Sin cambios desde §3: una muestra no dice si las coordenadas son absolutas o relativas, y por eso el emisor escribe vacío |
| El texto original del hallazgo `R3-001` | Irrecuperable del estado durable, y así se declara en §13.3 |

### 13.6 La revisión nativa de la unidad F

Corrida sobre el rango comprometido `0766066..efc67b2` —seis archivos, 525 líneas cambiadas, riesgo **alto**— y
quemada con el proveedor. La razón del riesgo viene medida: `process_boundary`, «code that starts other processes in
`tools/xmi-acceptance/xmi_acceptance.py`».

| Dato | Valor medido |
| --- | --- |
| Linaje | `review-5fceca1cdf2aad8c` |
| Lentes | Cuatro: riesgo, resiliencia, legibilidad, confiabilidad |
| Veredicto | `approved`, con **4 revisores preparados y 4 sometidos** |
| Correcciones | Ninguna: la tienda no abrió transición de corrección |
| Autoridad | `burned`, `burn_evidence: gentle-ai.review-acknowledged/v1` |

**Tres avisos, ninguno bloqueante**, y el proveedor lo dice con todas las letras: «ninguno abrió una corrección,
ninguno reabre esta revisión, y no se ofrece ninguna transición de corrección para este candidato. Tratalos como
trabajo posterior separado, nunca como razón para volver a correr la revisión sobre este candidato.»

| Hallazgo | Lente | Ubicación | Severidad | Disposición |
| --- | --- | --- | --- | --- |
| `R2-001` | legibilidad | `tools/xmi-acceptance/xmi_acceptance.py:275` | SUGGESTION | informativo |
| `R3-001` | confiabilidad | `backend/src/main/java/com/diagramador/xmi/XmiReader.java:91-99` | WARNING | informativo |
| `R4-001` | resiliencia | `backend/src/main/java/com/diagramador/xmi/XmiReader.java:94` | WARNING | informativo |

**Lo que este cierre no expone: la prosa de los hallazgos.** El cierre devuelve identificador, lente, ubicación,
severidad y disposición, no el texto. Las dos advertencias caen sobre el bloque que **esta** unidad escribió
(`unsupportedDialect`, líneas 91–99). Su texto **no se reconstruye**: reconstruir un hallazgo fue el error que §13.3
declaró, y no se repite acá. Quedan como trabajo posterior declarado, con su ubicación medida.

**Y una nota de entorno, medida, porque bloqueó una compuerta — y no el código.** La captura del grupo falló una vez
con `Windows DACL validation failed: ace-type`: la DACL de `.git` traía dos ACEs de **denegar** para un SID
**inexistente en esta máquina** (`S-1-5-21-542072663-2363636745-3304252722-4107386395`; `Translate` falla), y el
validador del host admite **solo** ACEs de permitir. Medición: esos ACEs estaban **solo** en `.git` —no en `D:\`,
ni en `sw1`, ni en la raíz del repo, ni en el worktree, ni en `backend`— y el SID propio conservaba `FA`. El comando
que el usuario autorizó (`icacls …\.git /remove:d <SID>`) **procesó cero archivos**: entre la medición y el comando
los ACEs ya habían desaparecido solos, así que el estado sano **no es atribuible a ese comando**. La causa de la
aparición y de la desaparición **no está medida**, y el bloqueo **puede repetirse**.

### 13.7 El desvío medido de nuevo, y la guarda que faltaba con firma medida

La sesión vecina —dueña de `feat/incremento-3-colaboracion`— reportó que la punta de su rama es `38882b4` y no
`384edd1`, y pasó la firma de la guarda que a este sentido de la exportación le falta. **Ninguna de las dos cosas se
asienta acá como cita de la otra sesión**: se midieron, en solo lectura, sobre los objetos de git del repositorio
principal, sin tocar su árbol de trabajo.

| Lo que se midió | Comando | Resultado |
| --- | --- | --- |
| Punta de su rama | `git rev-parse feat/incremento-3-colaboracion` | **`38882b4`** |
| Cuánto queda `main` atrás | `git rev-list --count main..feat/incremento-3-colaboracion` | **9** commits |
| ¿Avanzó o se reescribió? | `git merge-base --is-ancestor 384edd1 <punta>` | Sí: **avanzó**, no hubo reescritura |
| La guarda, y con qué firma | `git grep -n requireExportAllowed 14053ce -- backend/src` | `EditingLeaseService.java:73` la declara, `InMemoryEditingLeaseService.java:118` la implementa y la línea 156 lanza `ApiException(CONFLICT, "EXPORT_BLOCKED_EDITING")` |

**La fila «Pendiente declarado» del cuadro de estado queda accionable.** Decía «integración de una línea al
consolidar» sin decir cuál; ahora la línea tiene nombre, forma y excepción, medidos. **No se conectó, y así se
declara**: conectar una guarda de una rama que todavía no está en `main` exige primero decidir esa unión, y esa
decisión es del usuario. **El usuario la tomó en esta sesión, y §14.2 mide la conexión.**

**Lo que esta sección no mide.** El comportamiento del lease en vivo —quién lo toma, cuándo vence, qué pasa con el
operador— está en su unidad y en su §S6b, no acá. De esa rama solo se midió lo que hace falta para nombrar la
integración pendiente, que es lo que la tabla de arriba tiene.

## 14. Consolidación con la guarda conectada

El usuario tomó dos decisiones en esta sesión: empujar y mover `main` (ejecutado en §12 y §13), y **consolidar la
rama del par conectando la guarda declarada**. Esta sección mide esa consolidación. Lo que la sesión vecina
reportó no se asienta acá como cita: se midió con instrumentos propios —los objetos de git, el árbol fusionado,
las suites— y lo que no se pudo medir se declara al final.

### 14.1 La fusión, y la auditoría que la acompaña

| Lo que se midió | Comando | Resultado |
| --- | --- | --- |
| Punta de la rama del par | `git rev-parse --short feat/incremento-3-colaboracion` | **`38882b4`** al fusionar; **`c39605a`** al re-medir después (§14.6) |
| Cuánto traía | `git diff --stat 61bf2d2..38882b4` | **21 archivos, +1921 inserciones, −6 borrados** |
| La fusión | `git merge --no-commit --no-ff feat/incremento-3-colaboracion` | **Sin conflictos**: auto-fusionó `client.ts`, `types.ts` y `ProjectEditorPage.tsx` |
| El commit | `f2fd947` | Dos padres: `4f59e50` (esta rama) y `38882b4` (la del par) |
| ¿Entró todo lo que traía? | lo aportado contra `git diff --stat 61bf2d2..38882b4` | Idéntico: 21 archivos, +1921, −6 |
| ¿Sobrevivieron las costuras de §12.1? | búsqueda de los símbolos resueltos a mano | **Cero borrados** en los cuatro archivos compartidos, y `previewXmi`, `importXmi`, `reportProjectPresenceTouch`, `reportProjectCursorPosition`, `XmiImportPanel`, `PresencePanel`, `MembersPanel` y `ChatPanel` siguen definidos |

Que la fusión fuera limpia no es suerte: el par partió de `61bf2d2`, donde las resoluciones de §12.1 todavía no
existían, así que su lado no volvió a disputar esas cuatro costuras.

### 14.2 La guarda, conectada con el rojo primero

La guarda quedó en **`XmiExportService.java:61`**, una línea después del snapshot que esa exportación ya lee:

| Dónde | Por qué ahí, y no en el controlador |
| --- | --- |
| `editingLeaseService.requireExportAllowed(snapshot.id())`, dentro de `exportProject` | recibe **exactamente** el `modelId` que la exportación va a leer. Hacerlo en el controlador obligaba a resolver el modelo otra vez —una lectura completa del agregado— y dejaba abierta la puerta de que los dos identificadores divergieran |
| `requireEditor` sigue primero, en el controlador | el orden congelado en §S6b: primero el permiso sobre el proyecto, después la concesión |
| El `TODO(consolidation)` se reemplazó por la nota que dice dónde vive la guarda | un TODO cumplido miente si queda escrito |

Con la prueba escrita **antes** que la conexión:

| Paso | Comando | Resultado |
| --- | --- | --- |
| Rojo | `mvn -o -B -f backend/pom.xml -Dtest=XmiExportApiTest test` | **5 pruebas, 1 falla**: `Status expected:<409> but was:<200>` |
| Verde | el mismo comando, con la guarda conectada | **5 pruebas, 0 fallas**, BUILD SUCCESS |

Lo que la prueba tiene de más, y por qué: además del rechazo trae un **control positivo** —que el titular de la
concesión sí pueda exportar—. Sin él, una guarda que bloqueara siempre pasaría la prueba roja. Ese control ya
estaba verde antes de conectar la guarda y siguió verde después. Lo que la prueba **no** ejerce: el exento del
operador (§14.6).

### 14.3 Un defecto que trajo la fusión, y quién lo encontró

La suite web del árbol fusionado quedó con **dos fallas reales que no existían en ninguno de los dos lados por
separado**: `web/src/api/xmi.test.ts` afirmaba `headers: { 'Content-Type': 'application/json' }` como objeto
plano, mientras que el helper de peticiones que trajo el par entrega a `fetch` una instancia de **`Headers`**.

| Lo que se midió | Resultado |
| --- | --- |
| Árbol fusionado, primer `npm test` | **1 archivo y 2 pruebas en falla**: `156 pasan, 4 omitidas` |
| Causa medida | `authorizedInit` fusionado devuelve `{ ...init, headers }` con `new Headers(init?.headers)` |
| Reparación | `2b47c66`: las dos aserciones leen el encabezado por `Headers`, que es el idioma que la propia suite fusionada ya usaba |
| No vacuidad de la aserción reparada | apuntando `postJson` a `text/plain`, las dos vuelven a fallar con `expected 'text/plain' to be 'application/json'`; el archivo se restauró **desde el índice** y `git diff` quedó vacío |
| Árbol fusionado, después | **158 pasan, 4 omitidas, 0 fallan**, en 24 archivos |

Se reparó **la prueba, no la implementación**: el contrato fusionado —el helper entrega `Headers`— es el que
queda, y la aserción se adaptó leyendo el encabezado, que comprueba lo mismo.

### 14.4 Verificación medida sobre el árbol consolidado

| Compuerta | Comando | Antes de esta unidad | Con la guarda conectada |
| --- | --- | --- | --- |
| Backend | `mvn -o -B -f backend/pom.xml verify` | 330 pruebas, 0 fallos, 0 salteadas (árbol fusionado, sin la guarda) | **332 pruebas, 0 fallos, 0 errores, 0 salteadas, BUILD SUCCESS** |
| Oráculo del dialecto | `python -m pytest tools -q` | 54 pasan | **54 pasan** |
| Web, pruebas | `npm test` | 156 pasan, 4 omitidas, **2 fallan** | **158 pasan, 4 omitidas, 0 fallan** |
| Web, tipos y estilo | `npm run lint` (`eslint . && tsc --noEmit`) | limpio | **limpio** |
| Web, build | `npm run build` | ok | **ok** |
| Arnés de aceptación XMI | `python tools/xmi-acceptance/xmi_acceptance.py --ea-fixture docs/fixtures/xmi/Prueba3.xml --emitted-sample backend/target/xmi-sample.xml` | no medido por separado | **18 pasan, 0 fallan**, con el control positivo de EA en el otro casillero |

Las 332 pruebas del backend son las 330 del árbol fusionado más **2**: la del rechazo y su control positivo. El
`mvn verify` regenera `backend/target/xmi-sample.xml`, que es lo que el arnés de aceptación consume.

### 14.5 Un comando mío corrió en el worktree del par (declaración)

Al revertir la mutación de prueba de §14.3 quise ejecutar `git checkout -- web/src/api/client.ts` **en mi
worktree**, y un `cd` con ruta de Windows falló en `bash`: el comando corrió entonces en el directorio de la
sesión, que es el worktree del par. **No hubo pérdida, y eso no es una suposición: lo midió el dueño del
árbol** — al lanzar su unidad siguiente el `git status --porcelain` de ese worktree estaba vacío, después el
`git diff --stat HEAD -- web/` quedó vacío, `git stash list` quedó vacío, y el último commit que tocó ese archivo
era suyo (`fc12692`). Como el archivo quedó igual a su `HEAD`, la restauración fue un **no-op**; su trabajo en
vuelo —9 rutas, ninguna bajo `web/`— quedó intacto. La mutación se revirtió después con `git -C <ruta absoluta>`,
y `git diff` sobre ese archivo quedó vacío. La lección se asienta como regla de este entorno: **nunca `cd` con
ruta de Windows; siempre `git -C` con ruta absoluta**.

### 14.6 Lo que esta sección no mide

- **El comportamiento de la concesión en vivo** —quién la toma, cuándo vence, qué pasa con el operador— es de la
  unidad del par y de su §S6b. Acá solo se mide que **el endpoint la consulta** y que el resultado llega como 409
  con `editorDisplayName` y `reclaimable` en `details[0]`.
- **El exento del operador no se ejerce acá**: se apoya en la prueba de la otra unidad, no en una medición propia.
- **La API de concesiones que el par tiene en vuelo** (`EditingLeaseController`, `EditingLeaseResources`,
  `EditingLeaseConflict` y sus pruebas) no entra en esta fusión. Su refactor **sí cambia el cuerpo** de
  `requireExportAllowed`: delega el bloqueo en un `blocker(UUID modelId)` nuevo y `blocked(EditingLeaseConflict)`
  conserva `CONFLICT` y el literal `EXPORT_BLOCKED_EDITING`. Un chequeo previo mío —`git diff -U0 | grep
  requireExportAllowed`— **no podía ver ese cambio**: filtra por el nombre del método, y un cambio de cuerpo sin
  cambio de firma no produce ninguna línea modificada que contenga el nombre. Queda como límite del método, no
  como dato. El compromiso, declarado: cuando ese commit aterrice, se vuelven a correr `XmiExportApiTest` y el
  `mvn verify` completo sobre el consolidado; si se pone rojo, el hallazgo es de la otra unidad.
- **El desvío de la rama del par sigue abierto**: su punta es `c39605a` —un commit de documentación, `docs-only`—
  y tiene su unidad siguiente sin commitear, así que esa rama queda 10 commits delante de `main`. Ese commit **no
  se fusionó**: es el diseño de un incremento ajeno todavía sin implementar. Se declara, no se cierra.
- **La revisión nativa de esta sección** se corrió sobre el rango posterior a la fusión: lineage `review-d8a85e5a4031c35f`, **approved** y autoridad quemada, en §14.7.
- **El cierre del arnés S7** sigue sin estar en ninguna de las dos ramas (§13.6).

### 14.7 La revisión nativa del consolidado

| Lo que se midió | Resultado |
| --- | --- |
| Alcance | Rango **committed** `f2fd947..bbd9d53`: **5 archivos, 168 líneas** cambiadas —la guarda, su controlador, su prueba de API, la reparación de la prueba web y esta sección— |
| Lineage | **`review-d8a85e5a4031c35f`**, de `reviewing` a `approved` |
| Riesgo y lentes | **`medium`**, con un motivo medido: «an executable change in `backend/src/main/java/com/diagramador/api/XmiExportController.java`». Una lente: `review-reliability` |
| Presupuesto | `correction_budget` **84** líneas lógicas, congelado por el proveedor |
| Corridas | Pronóstico `model_runs: 1` por relay del host; **1 revisor preparado y 1 enviado** (`prompt_bytes` 35470, `result_bytes` 752) |
| Veredicto | **`approved`** en la última admisión, sin corrección abierta |
| Autoridad | **`burned`**, con `burn_evidence` `gentle-ai.review-acknowledged/v1`; `delivery: ordinary-repository-policy` |
| Avisos | **Ninguno expuesto por el cierre.** No se reconstruye ninguno: un cierre que no los expone no autoriza a inventarlos |

**Qué quedó fuera, y se declara.** El **acto de fusión** no es candidato natural de esta revisión —con el árbol
ya commiteado la proyección del espacio de trabajo queda vacía y el proveedor exige un `baseRef` explícito
(§12.6)—, así que la revisión cubre lo **posterior** a la fusión, que es lo que esta unidad agregó. El código que
la fusión trajo del otro lado lleva su propia autoridad de revisión en su unidad. El desvío de `c39605a` y de
`73a2387` sigue declarado y sin fusionar (§14.6).

**La compuerta de DACL volvió a fallar, y volvió a ser un fantasma.** El primer `START` con el rango explícito
devolvió `candidate-owner-preparation-failed` («candidate view rejected before native START»). La medición
encontró, **otra vez y solo en `.git`**, los dos ACEs de **denegación** del SID ausente
`S-1-5-21-542072663-2363636745-3304252722-4107386395`, con los dos subdirectorios que el validador exige
—`.git/gentle-ai` y `.git/gentle-ai/candidate-views`— sanos. El segundo intento, **sin tocar ninguna ACL**, creó
la lineage. Conclusión declarada: el bloqueo es **intermitente**, la causa de la aparición y de la desaparición de
esos ACEs **sigue sin medir**, y el estado sano **no es atribuible a ningún comando** —igual que en §13.6—. La
revisión se hizo porque el segundo intento pasó, no porque el problema esté resuelto.

**Una observación, para el registro.** El `START` que sí funcionó volvió dentro de un sobre cuyo nombre de
operación era `answer-consent`, con `action: "created"` y sin binding de consentimiento pendiente; no llamé a
`answer-consent` porque no había nada que contestar. Queda asentado como observación, no como problema.

### 14.8 El segundo movimiento: la documentación del par, ya en `main`

El usuario pidió «coordinar con el otro orquestador para mover todo a `main`». La medición primero dice **qué es
«todo»**:

| Lo que se midió | Comando | Resultado |
| --- | --- | --- |
| Qué ramas no están en `main` | `git rev-list --count main..<rama>`, rama por rama | **Solo una**: `feat/incremento-3-colaboracion`, con **2** commits. `incremento-0`, `incremento-1`, `incremento-2` y `xmi-intercambio` ya son ancestros de `main` |
| Qué traen esos 2 commits | `git log --stat main..feat/incremento-3-colaboracion` | `c39605a` (+106) y `73a2387` (+22): **docs-only**, los dos solo sobre `odd/tasks/incremento-3-colaboracion.md` —congelar los endpoints de concesión antes de escribirlos, y registrar la guarda de XMI como conectada— |
| ¿Va a haber conflictos? | `git merge-tree --write-tree main feat/incremento-3-colaboracion` | **Una sola línea de salida** = el árbol resultante, o sea **cero conflictos**, medido **antes** de fusionar |
| La fusión | `git merge --no-commit --no-ff feat/incremento-3-colaboracion` | «Automatic merge went well»; el árbol, salvo ese archivo, **idéntico** a `efd7abd` |
| El commit | **`9b7862d`** | Dos padres: `efd7abd` y `73a2387` |
| Compuertas tras la fusión | las cuatro de §14.4 | `mvn verify` = **332 pruebas, 0 fallos, 0 salteadas, BUILD SUCCESS**; `pytest tools -q` = **54 pasan**; web = **158 pasan, 4 omitidas, 0 fallan**; lint, tipos y build limpios |

**La revisión nativa de este commit no se corrió, y el motivo se declara**: es una fusión cuyo único contenido es
**documentación pasiva** —un solo archivo de plan ajeno, +128/−1, con el resto del árbol medido **idéntico** a
`efd7abd`, que ya está revisado—, que es exactamente el caso que el contrato exceptúa. No es una omisión: es la
excepción declarada.

**Lo que falta para que `main` tenga todo, y de quién depende.** La unidad **C20** del par —la API de concesiones:
`EditingLeaseController`, `EditingLeaseResources`, `EditingLeaseConflict` y sus pruebas, más los cambios en
`EditingLeaseService`, `InMemoryEditingLeaseService` y `GenerationApiService`— sigue **sin commitear** en su
worktree: **9 rutas** medidas con `git status --porcelain` en solo lectura. No se puede fusionar lo que no es un
commit, y **no se toca su árbol de trabajo**. Se le pidió por intercom el `sha` cuando aterrice, junto con la
condición ya declarada: al tomarlo hay que **volver a correr `XmiExportApiTest`** y el `mvn verify` completo,
porque su refactor cambia el **cuerpo** de `requireExportAllowed` delegando en un `blocker(UUID modelId)` nuevo
(§14.6). El pedido quedó **entregado y sin responder a los 10 minutos**; el resto no depende de que conteste ahora,
sino de que su unidad se commitee.

**Un desvío más, declarado:** el ref remoto de su rama está en `41588c6`, tres commits atrás de su punta local
`73a2387`. Empujarla es decisión suya; para `main` no hace falta, porque la fusión ya arrastró esos commits.

### 14.9 La unidad C20 del par, y `main` con todo

El par entregó **`9211b09`** —*expose the editing lease and block exports while it is held*, 9 archivos de código—
**commiteada encima de `b488e5e`**, sobre mi `main`, más un commit de documentación `6c75b37`. Eso cambia la
forma del movimiento: no hubo nada que fusionar.

| Lo que se midió | Comando | Resultado |
| --- | --- | --- |
| Punta del par | `git rev-parse --short feat/incremento-3-colaboracion` | **`6c75b37`** |
| ¿Fusión o avance rápido? | `git merge-base --is-ancestor b488e5e feat/incremento-3-colaboracion` | Sí: **`b488e5e` es ancestro** → avance rápido, **sin commit de fusión** |
| Qué entró | `git diff --stat b488e5e..6c75b37` | **10 archivos, +808/−19**: los 9 de C20 (`EditingLeaseController`, `EditingLeaseResources`, `EditingLeaseConflict`, `EditingLeaseService`, `InMemoryEditingLeaseService`, `GenerationApiService` y tres pruebas) más su documento |
| El avance | `git merge --ff-only feat/incremento-3-colaboracion` | Historia **lineal**: `b488e5e` → `9211b09` → `6c75b37` |

**La condición pactada se cumplió, y no citando al otro.** Antes de dar el movimiento por bueno corrí **mi**
prueba sobre el árbol consolidado: `mvn -o -B -f backend/pom.xml -Dtest=XmiExportApiTest test` = **5 pruebas,
0 fallas, BUILD SUCCESS**. El par había medido lo mismo en su árbol; acá quedó **reproducido** con instrumento
propio.

| Compuerta | Comando | Resultado |
| --- | --- | --- |
| La guarda de XMI | `mvn … -Dtest=XmiExportApiTest test` | **5 pruebas, 0 fallas, BUILD SUCCESS** |
| Backend completo | `mvn -o -B -f backend/pom.xml verify` | **347 pruebas, 0 fallos, 0 errores, 0 salteadas, BUILD SUCCESS** (mis 332 + las 15 del par) |
| Oráculo del dialecto | `python -m pytest tools -q` | **54 pasan** |
| Web | `npm test`, `npm run lint`, `npm run build` | **158 pasan, 4 omitidas, 0 fallan**; tipos y estilo limpios; build ok |
| Arnés XMI | `xmi_acceptance.py` con EA en el otro casillero | **18 pasan, 0 fallan** |

**Dos autoridades sobre el mismo contenido, y no son la misma.** El par ya había revisado C20 de su lado. Corrí
además la mía porque **el candidato no coincide**: la suya fue sobre el árbol de trabajo (9 caminos de código,
sin el documento) y la mía sobre el rango commiteado `b488e5e..6c75b37` (**10 caminos, 827 líneas**, presupuesto
200), que incluye el commit de documentación y es exactamente lo que entra a `main`.

| | Autoridad del par | Autoridad de esta sesión |
| --- | --- | --- |
| Lineage | `review-9d023011615ed35e` | **`review-a54b05bff7b92b81`** |
| Candidato | árbol de trabajo, 9 caminos / 757 líneas | rango `b488e5e..6c75b37`, **10 caminos / 827 líneas** |
| Corridas | una lente | una lente: `prompt_bytes` 71863, `result_bytes` 1996 |
| Veredicto | aprobada al primer intento, autoridad quemada | **aprobada**, autoridad **quemada** (`gentle-ai.review-acknowledged/v1`) |
| Avisos | `R3-001`, informativo, `InMemoryEditingLeaseService.java:139`, `WARNING`, sin transición de corrección; su sobre no expuso el texto | **el sobre no expuso ninguno**, y no se reconstruye ninguno |
| Registro durable | `…/terminal-consumption/v1/21396e06….json`, 259 bytes | `…/terminal-consumption/v1/d33c21d1….json`, 259 bytes |

Que las dos existan como registro nativo se midió (`grep -rl <lineage> .git/gentle-ai/`): son dos autoridades
**independientes** sobre contenido idéntico más el documento, no una que reemplaza a la otra.

**Un dato de ruta del par, medido acá, con su consecuencia.** `AuthenticationInterceptor.isOperatorAllowed` es una
lista blanca por **forma** de ruta: `GET /projects`; `GET /projects/{uuid}/deployments` y `/generation-targets`;
`GET /projects/{uuid}/generations/{uuid}/artifacts`; `POST /projects/{uuid}/deployments/{claim|report|stop}`.
`POST /projects/{uuid}/xmi/export` tiene **cinco segmentos** y no encaja en ninguna forma, así que el interceptor
devuelve `403 FORBIDDEN` con el token de operador **antes** de llegar al controlador. Consecuencia declarada:
**la rama de exención del operador que vive en `requireExportAllowed` es inalcanzable por HTTP en esta ruta**;
sigue siendo alcanzable por código, y la ejercen las pruebas del par. No es un defecto: es una frontera, y queda
escrita como tal.

**Lo que esta sección no mide.** El comportamiento de la concesión en vivo sigue siendo de la unidad del par y de
su §S6b. El texto del aviso `R3-001` de la autoridad del par no se reconstruye: su sobre tampoco lo expuso. Y la
revisión del par, sobre el árbol de trabajo, **no** cubre el commit de documentación `6c75b37`: eso es lo que mi
corrida aporta de más.

### 14.10 El componente de conflicto del par: declarado pendiente, no trabajado

La unidad C21 del par trae un componente web que muestra el conflicto de concesión: nombre del editor, motivo y un
botón **Reclamar control** habilitado solo cuando `reclaimable` es verdadero. El par avisó que **la exportación XMI
se topa con el mismo 409** y ofreció el contrato para montarlo, en vez de que esta unidad escriba otro.

**El hueco, medido acá.** Ante el 409 de `/projects/{id}/xmi/export`, el panel muestra hoy `error.message` por
`messageFor` (`web/src/xmi/XmiImportPanel.tsx:285`), o sea el literal del backend —«Export is blocked by an editing
lease.»—: **sin** nombre del editor, **sin** `reclaimable` y **sin** forma de reclamar.

**La integración sería viable sin tocar archivos compartidos, y eso también está medido**: `ApiTransportError`
(`web/src/api/client.ts:34-48`) ya expone `code`, `message`, `details: Array<Record<string, unknown>>`,
`operationId` y `status`, así que el código `EXPORT_BLOCKED_EDITING` y las cinco claves de `details[0]` **ya llegan**
al panel; y el punto de montaje es `web/src/xmi/XmiImportPanel.tsx` (382 líneas), montado en
`ProjectEditorPage.tsx:612`, de modo que montar **dentro** del panel no obliga a tocar el archivo compartido.

**Decisión del usuario: queda declarado como pendiente, sin trabajarlo.** No se monta nada ahora y el panel queda
como está; la reutilización queda como **plan escrito** —no como rechazo— y la unidad se retoma cuando el usuario lo
pida. El motivo es de secuencia, no técnico: la unidad C21 **todavía no existe commiteada** del lado del par, y el
contrato llega con nombres finales recién cuando cierre su revisión nativa.

**Lo que el par anunció, asentado como anuncio suyo y no como contrato verificado**: el mapeador canónico quedará
exportado público en un módulo puro y aceptará el conflicto de **cualquiera de los dos portadores** —el
`details[0]` del 409 y el `result` del 200— devolviendo las cinco claves; el aviso será **puramente
presentacional**, con `onReclaim` opcional y un flag `busy`, sin llamar a la API por su cuenta; el botón se
habilitará con el `reclaimable` que decide el **servidor**, sin calcular vivacidad en el cliente; y los textos
irán en inglés, como el resto del panel. **Nombres y props no se asientan**: el par avisó que su revisión nativa
sigue pendiente y que va a mandar nombres finales, no intenciones.

**Lo que esta sección no mide.** Nada del componente: no existe todavía. Lo medido es el hueco de este panel, la
viabilidad de montarlo sin tocar los archivos compartidos, y que la decisión quedó declarada.

**Un desvío más, del mismo día:** `5d3ed83` —*freeze the web editing-lease design before writing it*, docs-only,
+47— es hoy el único commit fuera de `main`. Se asienta como desvío declarado, no como problema, y se tomará junto
con la unidad C21 cuando su código aterrice.
