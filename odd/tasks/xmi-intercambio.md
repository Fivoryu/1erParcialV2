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
| **Pendiente declarado** | El endpoint de XMI nace **sin** la guarda de la concesión, porque `C19` no está en esta base. Queda como integración de una línea al consolidar, y **así se declara**: hasta entonces la exportación XMI no está protegida contra una edición en curso |

## Estado de ejecución

Se actualiza en cada transición. Una etapa solo dice **cerrada** cuando sus pruebas pasan.

| Etapa | Estado | Evidencia |
| --- | --- | --- |
| X1 — Oráculo del dialecto | **Cerrada** | `python -m pytest tools -q` = **46 pruebas, 0 fallos** (escritor y padre). Verificador independiente: **6 HOLDS**, con **cuatro mutaciones** probando que las aserciones rechazan datos falsos. El arnés además **refutó dos afirmaciones mías** (§3.4) |
| X2 — Esquema y persistencia | **Cerrada**, con un límite declarado | `mvn -o -B -f backend/pom.xml verify` = **232 pruebas, 0 fallos** (escritor y padre). Verificador independiente: **5 HOLDS**, 1 UNPROVEN que cerré inline con `git show`, y **1 FAILS que es el borde de la compuerta** —detecta deriva unilateral, no una omisión coordinada—, declarado en §5.2 |
| X3 — Emisión del dialecto | Pendiente | Depende de X1 (oráculo) y X2 (dominio) |
| X4 — Servicio y endpoint de exportación | Pendiente | Depende de X3 |
| X5 — Lectura del dialecto | Pendiente | Depende de X1 |
| X6 — Importación: comando de lote y endpoints | Pendiente | Depende de X2 y X5 |
| X7 — Web | Pendiente | Depende de X4 y X6 |
| X8 — Arnés y cierre | Pendiente | Depende de todo lo anterior |
| Verificación en EA (§7) | **Bloqueada por acción del usuario** | EA corriendo sin repositorio abierto; el add-in del MCP no contesta |

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

**Tercera lección, y la más incómoda.** Tres veces escribí una afirmación sobre el dialecto a partir de una muestra
o de memoria, y tres veces la medición la refutó: el `Path` «vacío en todas», el ancla «en `geometry`», y ahora la
multiplicidad «`*`». Las tres las encontró otro —dos veces un escritor, una vez un verificador—, nunca yo. Lo que
sí funcionó fue la regla de §6: la medición no vive en la prosa, vive como **aserción ejecutable con recuento
exacto**. Estas formas entran como aserciones en las pruebas de la emisión, no como párrafos que alguien tenga que
recordar.

## 4. Decisiones cerradas

| # | Decisión | Alternativa descartada | Motivo |
| --- | --- | --- | --- |
| X-1 | **Emitir `EAID_`/`EAPK_` derivados del UUID canónico**, con biyección reversible | Emitir el UUID crudo como `xmi:id` | El dialecto medido usa `EAID_`/`EAPK_` en las 139 referencias del fixture y el diagrama resuelve `subject` contra ese espacio. La derivación es reversible (mayúsculas + `_` por `-`), así que el round-trip conserva identidad y a la vez se habla el dialecto del destino. Al leer se aceptan **las dos** formas |
| X-2 | El **bloque `diagrams`** con la geometría es obligatorio en la emisión | Exportar solo el árbol UML | Sin `diagrams` el archivo importa el modelo pero **no dibuja nada**, que es justo el pedido del usuario |
| X-3 | Los conectores se emiten con **`source`/`target` en el registro** *y* `SOID`/`EOID` contra el `DUID` de cada figura | Confiar solo en `source`/`target` | El fixture ejerce **los dos** mecanismos a la vez. Emitir los dos hace que el conector caiga en las figuras correctas aunque EA use uno u otro |
| X-4 | `Path=` **vacío** y el ruteo del canónico como **pérdida declarada** | Inventar puntos intermedios | Medido en §3.4, **y corregido**: el dialecto **sí** transporta ruteo —1 de 8 conectores trae `Path=622:-260$`—, pero **una sola muestra no fija la gramática**: no se sabe si esas coordenadas son absolutas del diagrama o desplazamientos relativos, y el `y` negativo frente a figuras en coordenadas positivas deja la duda abierta. Emitir un formato deducido de un punto de datos sería afirmar una capacidad no medida. Se emite vacío, como EA en 7 de 8, y el canal queda pinneado como candidato para una iteración verificada contra EA |
| X-5 | `xmi:Documentation exporter` declara **la app**, no EA; el `extender` de la extensión sí dice `Enterprise Architect` | Firmar el archivo como `exporter="Enterprise Architect"` | El `xmi:Documentation` describe quién produjo el archivo y mentir ahí es falsear procedencia. El `extender` es lo que declara **a qué dialecto** pertenece el contenido, y ahí sí corresponde EA. **Riesgo declarado**: si EA exigiera su propio nombre en `exporter`, es un cambio de una línea, y solo la verificación de §7 lo decide |
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
