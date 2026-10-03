# Dialecto XMI de Enterprise Architect — caracterización y política de intercambio

> **Estado: el roundtrip contra EA en vivo está medido en los dos sentidos.** La exportación de la app se importó
> en Enterprise Architect (el diagrama con sus 2 figuras y 3 conectores presentes, y los ids nuestros
> preservados) y una exportación real de EA se leyó de vuelta en la app. Las mediciones están en
> `odd/tasks/xmi-intercambio.md` §10 y §11. Lo que sigue acotado es la **cobertura**: ningún artefacto prueba una
> capacidad que el archivo no ejerza.

**Artefactos:**

| Artefacto | Rol |
| --- | --- |
| [`tools/xmi-inventory/xmi_inventory.py`](../../../tools/xmi-inventory/xmi_inventory.py) | Arnés que deriva el inventario del dialecto desde el propio archivo |
| [`tools/xmi-inventory/test_xmi_inventory.py`](../../../tools/xmi-inventory/test_xmi_inventory.py) | Oráculo propio: particiona la línea base y refuta la evidencia declarada |
| [`docs/fixtures/xmi/Prueba3.xml`](../../fixtures/xmi/Prueba3.xml) | Evidencia medida: export real de EA 16 |
| [`docs/fixtures/xmi/ea-roundtrip-uml-2.1.xml`](../../fixtures/xmi/ea-roundtrip-uml-2.1.xml) | Nuestro modelo, importado en EA y reexportado por EA: el roundtrip cerrado, con los ids nuestros |
| [`docs/fixtures/xmi/ea-export-default-1.1.xml`](../../fixtures/xmi/ea-export-default-1.1.xml) | La exportación **por defecto** de EA: dialecto XMI 1.1, que el lector rechaza nombrando el dialecto |
| [`docs/fixtures/xmi/ea-export-uml-2.2.xml`](../../fixtures/xmi/ea-export-uml-2.2.xml) | La exportación «XMI 2.2» de EA: declara el namespace UML 2.2, que el lector rechaza nombrando el namespace |
| [`docs/fixtures/xmi/ea-independent-with-diagram.xmi`](../../fixtures/xmi/ea-independent-with-diagram.xmi) | Export de EA de otro repositorio: ids ajenos a nuestro dialecto y conectores sin anclas |
| [`03-linea-base-uml.v1.json`](03-linea-base-uml.v1.json) | Línea base UML que este dialecto debe poder transportar |

```bash
python tools/xmi-inventory/xmi_inventory.py
python -m pytest tools/xmi-inventory -q
```

## 1. Qué se midió y qué no

**Se midió** un único archivo en las secciones §1 a §5: `Prueba3.xml`, 91.440 bytes, exportado por **Enterprise
Architect 6.5**
(`extenderID`) en **XMI 2.1 / UML 2.1**, codificado en **windows-1252**, con un modelo de 8 clases, 6
asociaciones, 1 interfaz, 1 enumeración, 1 generalización, 1 realización y 1 diagrama de 18 elementos. La
§6.1 amplía la medición a las opciones de exportación de EA, con archivos escritos por EA a partir de nuestro
propio modelo.

**No se puede concluir** de este archivo:

- cómo se comporta el roundtrip contra EA en vivo, que se midió aparte y en los dos sentidos (§6.1, acta §10 y §11);
- ninguna capacidad que el fixture **no ejerza**. Que una etiqueta aparezca no prueba la capacidad.

> **Regla de lectura de este documento:** aparición de una etiqueta **no** es evidencia de la capacidad.
> `isStatic` aparece 33 veces en el modelo y las 33 valen `false`. Un importador que confunda presencia con
> capacidad se lleva puesto el problema.

## 2. Mapa del documento

El archivo tiene dos mitades bien separadas, colgando de la raíz `XMI`:

```text
XMI
├─ Documentation   (dialecto declarado: exporter / exporterVersion)
├─ Model           (uml:Model)  ->  el árbol UML semántico
└─ Extension       (extender / extenderID)  ->  todo lo propietario de EA
```

> **Medido, y no deducible del formato.** `xmi:Documentation exporter` **no** es un campo libre de procedencia: EA 16
> lo lee para decidir si el documento habla su dialecto. Con un valor ajeno, EA **descarta todos los `xmi:id`** del
> archivo y **no procesa el bloque `diagrams`**: el modelo entra sin identidad y ninguna figura puede anclarse. Un
> documento de intercambio declara `exporter="Enterprise Architect"` y `exporterVersion="6.5"`; la procedencia real
> viaja en un comentario XML, que ningún lector de dialecto interpreta. Medición y comandos:
> [`odd/tasks/xmi-intercambio.md`](../../../odd/tasks/xmi-intercambio.md) §10.2.

| Familia | Elementos | Proporción | Qué es |
| --- | ---: | ---: | --- |
| `ea-proprietary` | 953 | 81,1 % | Bloques de Sparx Systems |
| `uml-element` | 153 | 13,0 % | Elementos con `xmi:type` UML |
| `uml-structure` | 66 | 5,6 % | Serialización UML sin tipo propio (`packagedElement`, `memberEnd`, `general`) |
| `plumbing` | 3 | 0,3 % | `XMI`, `Documentation`, `Extension` |

**Más del 80 % del documento es carga propietaria.** Cualquier consumidor que lea solo el árbol UML ve menos
de una quinta parte del archivo.

### 2.1 Definición y duplicado

Los elementos UML se declaran en `Model` y **vuelven a aparecer como duplicados en `Extension`**:

| Tipo | En `Model` (definición) | En `Extension` (duplicado) |
| --- | ---: | ---: |
| `uml:Class` | 8 | 8 |
| `uml:Package` | 1 | 4 |
| `uml:PrimitiveType` | 4 | 10 |
| `uml:Enumeration` | 1 | 1 |
| `uml:Interface` | 1 | 1 |
| `uml:Property` | 33 | 2 |
| `uml:Comment` | 0 | 1 |
| `uml:Stereotype` | 0 | 1 |

Un importador que recorra todo el documento **sin separar subárboles** cuenta cada clase dos veces. Es la
primera trampa del dialecto.

### 2.2 Fuga de EA dentro del árbol UML

`Model` no está limpio: contiene **8 bloques `_lineStyle`**, un estereotipo propio de EA declarado en el
perfil y aplicado dentro del subárbol semántico. No es la extensión: está dentro del modelo.

### 2.3 La misma etiqueta significa cosas distintas según el contexto

| Etiqueta | Familias | Por qué |
| --- | ---: | --- |
| `type` | 3 | `Property.type` en el modelo; tipo de retorno de EA dentro de sus bloques de operación |
| `element` | 2 | Contenedor de la extensión y entrada de elemento con `xmi:type` |
| `ownedEnd` | 2 | Extremo de asociación en UML y extremo del perfil dentro de la extensión |

Clasificar por etiqueta y no por contexto produce conclusiones falsas en los tres casos.

## 3. Qué lleva realmente el modelo

23 de los 32 constructos de UML-BL-1 quedan **evidenciados por medición**, con el valor exacto. Los valores
se miden sobre el subárbol semántico `Model` y excluyen los duplicados de la extensión:

| Constructo | Hecho medido | Valor |
| --- | --- | ---: |
| BL-CLS-01 Clase | `uml:Class` en `Model` | 8 |
| BL-CLS-03 Interfaz | `uml:Interface` | 1 |
| BL-CLS-04 Enumeración | `uml:Enumeration` | 1 |
| BL-CLS-05 Literal de enumeración | Literales de `EstadoReserva` | 4 |
| BL-CLS-06 Paquete | `uml:Package` | 1 |
| BL-CLS-07 Tipo primitivo o de dominio | Referencias con prefijo `EAJava_` | 12 |
| BL-FEA-01 Atributo | `ownedAttribute` en `Model` | 23 |
| BL-FEA-02 Operación | `ownedOperation` | 11 |
| BL-FEA-03 Parámetro | `ownedParameter` | 11 |
| BL-VIS-01 Visibilidad pública | `visibility="public"` en `Model` (70 en todo el documento) | 46 |
| BL-VIS-02 Visibilidad privada | `visibility="private"` en `Model` (17 en todo el documento) | 17 |
| BL-REL-01 Asociación | `uml:Association` | 6 |
| BL-REL-02 Agregación | `aggregation="shared"` | 1 |
| BL-REL-03 Composición | `aggregation="composite"` | 1 |
| BL-REL-04 Generalización | `uml:Generalization` | 1 |
| BL-REL-05 Realización de interfaz | `uml:Realization` | 1 |
| BL-REL-07 Multiplicidad | `upperValue` (con 4 `LiteralUnlimitedNatural` para `*`) | 33 |
| BL-REL-08 Nombre de rol | Extremos de asociación con nombre | 10 de 10 |
| BL-REL-10 Extremo de relación | Extremos en total | 10 |
| BL-CMP-01 Nota de modelo | Atributos `documentation` en bloques de elemento y de diagrama | 12 |
| BL-CMP-03 Estereotipo | Bloques `stereotype` en la extensión | 32 |
| BL-CMP-04 Identificador estable | Identificadores `EAID_` en `Model` | 139 |
| BL-CMP-05 Disposición visual separada | Elementos colocados en el diagrama con geometría | 18 de 18 |

Y **9 constructos que este fixture no puede evidenciar**. No es una lista de pendientes: es el límite de la
medición, y un fixture que no ejerce algo no prueba nada sobre ese algo, ni a favor ni en contra. La partición
está definida y fijada en el oráculo de `test_xmi_inventory.py`, no en esta tabla:

| Constructo | Por qué el fixture no lo evidencia |
| --- | --- |
| BL-CLS-02 Clase abstracta | No hay ninguna `uml:Class` con `isAbstract="true"`. El único clasificador abstracto es la interfaz `ProcesadorPago` |
| BL-FEA-04 Valor predeterminado | No existe ningún tag `defaultValue`, ni bloque de EA equiparable |
| BL-FEA-05 Miembro estático | `isStatic` aparece 33 veces y las 33 valen `false` |
| BL-FEA-06 Miembro abstracto | `isAbstract` vale `true` dos veces en todo el documento, sobre una interfaz y sobre un bloque `properties`; ningún miembro lo ejerce |
| BL-VIS-03 Visibilidad protegida | El modelo solo lleva `public` y `private` |
| BL-VIS-04 Visibilidad de paquete | El modelo solo lleva `public` y `private` |
| BL-REL-06 Dependencia | No hay ningún `uml:Dependency` en `Model` |
| BL-REL-09 Navegabilidad | `isNavigable` **no aparece en el árbol UML**; solo en bloques `modifiers`, y en 16 casos vale `false` |
| BL-CMP-02 Restricción | Los 16 bloques `constraints` cuelgan de la extensión del conector y están vacíos |

## 4. Los cinco hallazgos que cambian la política

### 4.1 Las constantes de una enumeración no se serializan como literales UML

`EstadoReserva` trae sus cuatro constantes —PENDIENTE, CONFIRMADA, CANCELADA, COMPLETADA— como
**`ownedAttribute`**, que es la forma de una propiedad, y **no** como `ownedLiteral`, que es lo que UML 2.1
define para las constantes de una enumeración. **`ownedLiteral` no aparece ni una vez en todo el archivo.**

Un importador que busque la forma estándar no encuentra nada y **pierde las cuatro constantes en silencio**.
Y del otro lado, la persistencia tampoco tiene dónde guardarlas (GAP-02): hoy la pérdida sería doble.

### 4.2 La navegabilidad vive solo en los bloques propietarios

`isNavigable` **no aparece nunca en el árbol UML**. Las 16 apariciones están en bloques `modifiers` de EA, y
las 16 valen `false`.

Esto importa más de lo que parece: BL-REL-09 alimenta la **selección del lado propietario** de una relación
generada. Si el importador descarta la extensión, la generación elige el lado equivocado y nadie se enterará
hasta ver el código emitido.

### 4.3 Una parte grande de la extensión es andamiaje vacío

Vacío significa sin atributos, sin texto **y sin hijos**. Medir solo atributos y texto fue un error de la
primera versión de este contrato: hacía parecer vacío a `tags`, que lleva 8 hijos dentro.

| Bloque | Bloques | Con atributos | Con hijos | Vacíos |
| --- | ---: | ---: | ---: | ---: |
| `styleex` | 43 | 0 | 0 | 43 |
| `stereotype` | 32 | 0 | 0 | 32 |
| `options` | 21 | 0 | 0 | 21 |
| `initial` | 21 | 0 | 0 | 21 |
| `constraints` | 16 | 0 | 0 | 16 |
| `behaviour` | 11 | 0 | 0 | 11 |
| `paths` | 1 | 0 | 0 | 1 |
| `documentation` | 67 | 1 | 0 | 66 |
| `tags` | 78 | 0 | 8 | 70 |
| `style` | 78 | 27 | 0 | 51 |
| `xrefs` | 78 | 21 | 0 | 57 |

**414 de los 953 elementos propietarios (43,4 %) no llevan nada.** No es "la mayoría": la mayoría lleva algo.
Y los contenedores `links`, `parameters`, `attributes` y `operations` **no** están vacíos, están llenos de
hijos; llamarlos andamiaje era otro error.

Lo que sí se sostiene, y es lo que importa: que exista un bloque `constraints` **no** significa que haya
restricciones (los 16 están vacíos), y que exista un bloque `stereotype` **no** significa que haya
estereotipos aplicados (los 32 están vacíos).

### 4.4 Hay datos que solo existen en la extensión

| Dato | Dónde vive | Consecuencia de descartar la extensión |
| --- | --- | --- |
| Navegabilidad del extremo | `modifiers/isNavigable` | Se pierde: el árbol UML no la lleva |
| Nombre de rol del conector | `connectors/connector/source|target/role` | Se pierde la etiqueta del extremo |
| Orden y hoja/raíz | `modifiers/isOrdered`, `isLeaf`, `isRoot` | Se pierde (y en UML solo `isOrdered` está duplicado) |
| Tipo de retorno de la operación | bloques `operation/type` | Se pierde si no se resolvió antes por el árbol UML |
| Dirección del parámetro | `properties/direction` | Se pierde |
| **Notas de modelo** | Atributo `documentation` de los bloques de elemento | Se pierden las 12 notas descritas |

Y al revés: `isOrdered` aparece **dos veces** (33 en `Model`, 16 en `Extension`). Un importador tiene que
decidir cuál manda, y esa decisión debe ser explícita, no la que salga primero.

### 4.5 Higiene de datos ausente en el dialecto

- **Cuatro referencias de tipo sin destino** (`<type>` sin `idref`) en el modelo. Convertirlas en un tipo
  vacío es inventar; corresponde registrarlas como no resueltas.
- Los tipos apuntan a tres espacios distintos: `EAJava_` (12), `EAID_` (13, nuestras propias clases) y
  `EAnone_` (2). `EAJava_DateTime` y `EAnone_DateTime` son el mismo concepto escrito dos veces.
- **`visibility` se escribe con dos criterios de mayúsculas**: `public` (70), `private` (17) y **`Public`
  (16)**. Una comparación sensible a mayúsculas pierde 16 valores sin decir nada. De los 70 `public`, 46 están
  en el modelo y 24 en la extensión.

### 4.6 Las notas de modelo existen, en un canal propietario

La primera versión de este contrato afirmó que el fixture no traía ninguna nota de modelo. **Era falso.** EA
describe los elementos del modelo con un **atributo `documentation`** dentro de sus propios bloques:

| Canal | Apariciones | Dónde |
| --- | ---: | --- |
| Atributo `documentation` | 12 | 11 en bloques de elemento, 1 en el del diagrama |
| Elemento `documentation` con `value` | 1 | Bloque `documentation` de la extensión |
| `uml:Comment` en el modelo | 0 | El único `uml:Comment` del archivo pertenece al perfil |

Textos reales medidos: «Agente inmobiliario que publica inmuebles.», «Cliente que realiza reservas de
inmuebles.». Describen elementos del modelo, no la herramienta.

El error fue buscar solo elementos llamados `documentation` e ignorar que la descripción viaja como
**atributo**. La consecuencia importa: el canal estándar (`uml:Comment`) **no aparece en el modelo**, así que
un consumidor que solo lea UML pierde las 12 notas sin enterarse.

Y los 8 `tags` con hijos guardan `_lineStyle="orthogonalR"`: el estilo de ruteo del conector. Eso es
presentación, no un estereotipo de usuario, y pertenece al layout.

## 5. Política de intercambio

### 5.1 Preservar

| Qué | Bloques | Motivo |
| --- | --- | --- |
| Estructura UML semántica | `packagedElement`, `ownedAttribute`, `ownedOperation`, `ownedParameter`, `ownedEnd`, `generalization`, `general`, `memberEnd`, `lowerValue`, `upperValue`, `type` | Es el modelo canónico |
| Identificadores | `xmi:id` con prefijo `EAID_` / `EAPK_` | Trazabilidad y correlación entre importaciones |
| Geometría del diagrama | `diagrams/diagram/elements/element` con `geometry`, más `coords`, `bounds`, `containment` | Alimenta el layout compartido, que BL-CMP-05 declara soportado |
| Notas de modelo | Atributo `documentation` de los bloques de elemento y de diagrama | Es el único canal con contenido de nota que el fixture ejerce |
| Estilo representable | `appearance`, `labels`, `style` con valor | Presentación del diagrama compartido |

### 5.2 Leer y normalizar

| Qué | De dónde | Regla |
| --- | --- | --- |
| Literales de enumeración | `ownedAttribute` bajo `uml:Enumeration` | Es la forma real del dialecto, no la estándar |
| Navegabilidad | `modifiers/isNavigable` | Ausente significa no especificada, **nunca** `false` por defecto silencioso |
| Nombre de rol | `role/name` del conector, y el `name` del extremo | Se prefieren ambos y se declara el conflicto si difieren |
| Tipos primitivos | `primitivetypes` de la extensión | Resolver `EAJava_*` y `EAnone_*` contra los `uml:PrimitiveType` declarados |
| Visibilidad | `visibility` | Normalizar a minúsculas antes de comparar |
| Estereotipos propios de EA | `_lineStyle` dentro de `Model` | Se reconocen y se apartan: no son estereotipos del usuario |

### 5.3 Descartar, declarando la pérdida

| Qué | Motivo |
| --- | --- |
| Andamiaje vacío (`styleex` 43, `stereotype` 32, `options` 21, `initial` 21, `constraints` 16, `behaviour` 11, `paths` 1) | No lleva contenido. 414 de los 953 elementos propietarios están vacíos. Se cuentan, para que "cero pérdida" sea medible y no una afirmación |
| `style` y `xrefs` cuando están vacíos | 51 de 78 y 57 de 78 no llevan nada. Se descartan esos, no el bloque entero: los que llevan valor se preservan |
| Metadatos de EA (`project`, `times`, `flags`, `code`, `model.ea_guid`, `ea_localid`, `packageproperties`, `matrixitems`, `swimlanes`, `style1`, `style2`) | Autor, fechas y configuración de la herramienta, sin equivalencia en el modelo |

**Regla de honestidad:** descartar un bloque vacío no es una pérdida y no se reporta como tal; descartar un
bloque con contenido **sí** se reporta, con su nombre y su recuento. La pérdida se mide, no se supone.

## 6. Reglas de importación que se derivan

1. **Separar subárboles antes de contar.** `Model` define, `Extension` duplica. Recorrer todo el documento
   duplica cada clase. *(§2.1)*
2. **Clasificar por contexto, no por etiqueta.** `type`, `element` y `ownedEnd` cambian de significado según
   dónde estén. *(§2.3)*
3. **Los literales de una enumeración se leen de `ownedAttribute`** y, si además apareciera `ownedLiteral`, se
   prefiere el estándar. Nunca se importa una enumeración sin sus constantes. *(§4.1)*
4. **La navegabilidad se lee de `modifiers`.** Ausente es no especificada; no se asume `false`. *(§4.2)*
5. **La visibilidad se normaliza a minúsculas** antes de comparar, y un valor fuera de `public`, `private`,
   `protected` y `package` se registra como no reconocido en vez de descartarse. *(§4.5)*
6. **Una referencia de tipo sin `idref` no se convierte en un tipo vacío.** Se registra como no resuelta y se
   cuenta. *(§4.5)*
7. **Las notas se leen del atributo `documentation`** de los bloques de elemento y de diagrama, y del elemento
   `documentation` con `value`. El canal estándar, `uml:Comment`, **no aparece en el modelo**: un consumidor que
   solo lea UML pierde las 12 notas sin enterarse. *(§4.6)*

## 6.1 Qué dialectos de exportación acepta el lector (medido)

Enterprise Architect ofrece el dialecto como una opción del diálogo de exportación, y **la opción por defecto no
es la que el lector implementa**. Se exportó el mismo paquete con cada opción y se leyó cada archivo con el lector
real (`jshell --class-path backend/target/classes`, acta §11.2):

| Opción de EA | `EnumXMIType` / bandera | Raíz y namespace | Resultado del lector |
| --- | --- | --- | --- |
| XMI 2.1 | `xmiEA21 = 11` | `xmi:XMI xmi:version="2.1"`, `xmlns:uml="…/spec/UML/2.1"` | **Se lee**: 9 clasificadores, 6 relaciones, 1 vista, 1 diagrama de 2 figuras y 3 conectores **con las anclas resueltas** |
| XMI 2.2 | `xmiEA22 = 14` | `xmi:XMI xmi:version="2.1"`, `xmlns:uml="…/spec/UML/2.2"` | Rechazado; el mensaje nombra el namespace UML 2.2 encontrado |
| Por defecto | `xmiEADefault = 0` | `XMI xmi.version="1.1"`, `xmlns:UML="omg.org/UML1.3"` | Rechazado; el mensaje nombra el dialecto XMI 1.1 |
| XMI 1.1 | `xmiEA10` / `xmiEA11` | ídem anterior | Rechazado igual: mismo dialecto, distinto rótulo |
| XMI 2.1 sin extensiones | `epExcludeEAExtensions` | igual que XMI 2.1 | Se lee, pero **pierde un extremo** de la agregación y de la composición, y las anclas de los conectores: son datos que viven solo en la extensión (§4.2) |
| XMI 2.1 sin diagramas | `DiagramXML = 0` | igual que XMI 2.1 | Se lee sin diagramas: es una elección explícita de quien exporta, no una falla |

**Regla:** el rechazo se nombra por dialecto, no por elemento faltante. Decir «falta `uml:Model`» manda a buscar un
elemento cuando la respuesta es otra opción de exportación; el lector nombra el dialecto encontrado y la opción
que corresponde. El valor `xmiEA21` es **`11`**: exportar con el `2` que documentan terceros escribe XMI 1.0 en
silencio.

**Lo que sigue sin medir:** si `<links>` es redundante en general (se midió en un archivo, donde sus 12 entradas ya
aparecían en el resto del documento); si EA preserva nuestros `xmi:id` en todas las opciones de exportación; y el
dialecto XMI 1.1 como **capacidad** — solo se mide su rechazo.

## 7. Huecos de esta caracterización

| id | Hueco | Qué bloquea |
| --- | --- | --- |
| GAP-07 | ~~El roundtrip contra EA en vivo no se puede verificar: el servidor de EA no responde~~ **Cerrado**: se verificó en los dos sentidos con EA en vivo sobre copias del repositorio (acta §10 y §11) | Q3, RF-09, RNF-05 |
| GAP-10 | Las notas de modelo viajan solo en un canal propietario (atributo `documentation`) y el canal estándar `uml:Comment` no aparece en el modelo | Un consumidor que solo lea UML pierde las 12 notas; el roundtrip de notas sigue sin verificar |
| — | 9 constructos sin evidencia (§3) | Cada uno necesita un fixture que los ejerza, o una decisión explícita de no soportarlos |
| — | El dialecto está caracterizado con **un solo** archivo | Un diagrama de despliegue o de paquetes anidados puede traer formas que este fixture no tiene |

## 8. Verificación

| Comprobación | Resultado | Fecha |
| --- | --- | --- |
| Suite del inventario y de contrato | Pasa entera, 0 fallos (`python -m pytest tools -q`) | 2026-09-28 |
| Tags sin clasificar | 0 | 2026-09-28 |
| Partición de la línea base | 23 evidenciados + 9 declarados sin evidencia = 32 | 2026-09-28 |
| Prueba de mutación: declarar `BL-FEA-05` evidenciado por `isStatic` | Falla por dos lados y solo por esos dos | 2026-09-28 |
| Verificación independiente | Falsificó tres afirmaciones: clase abstracta inexistente, `documentation`/`tags` mal medidos como vacíos, y «el modelo no lleva notas». Las tres se corrigieron en el arnés y en este documento | 2026-09-28 |
| Roundtrip contra Enterprise Architect en vivo | Pasa en los dos sentidos: app → EA (el diagrama con 2 figuras y 3 conectores, ids preservados) y EA → app (9 clasificadores, 6 relaciones, 1 diagrama con anclas resueltas) | 2026-10-03 |
| Matriz de dialectos de exportación de EA | 6 opciones exportadas y leídas: 1 aceptada, 2 rechazadas nombrando el dialecto, 1 con pérdida medida sin la extensión, 2 sin efecto sobre lo leído | 2026-10-03 |
| Artefactos de EA como fixtures | 5 archivos pinneados por sha256 y byte-exactos (`XmiReaderTest.everyCommittedEaArtifactIsByteExact`); ninguno se saltea | 2026-10-03 |

## 9. Trazabilidad

| Requisito | Cómo lo toca esta caracterización |
| --- | --- |
| RF-09 (importar y exportar XMI) | Define qué se preserva, qué se normaliza y qué se descarta con pérdida declarada |
| RNF-05 (interoperabilidad) | Nombra los elementos que deben sobrevivir al intercambio |
| Q3 (prueba de roundtrip) | Aporta la línea de base contra la que medir la pérdida |
| CU-16, CU-17 | Reglas de importación y exportación |
| BL-CLS-04, BL-REL-05, BL-REL-07, BL-REL-09, BL-CMP-03 | Evidencia del dialecto para cada constructo |
