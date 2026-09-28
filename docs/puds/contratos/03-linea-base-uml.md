# Línea base UML 2.5 — UML-BL-1

> **Estado: propuesta.** Requiere ratificación humana antes de darse por cerrada. El artefacto declara
> `status: proposed` y `ratification: PENDING`, y una prueba impide que se declare ratificada por sí sola.

**Artefactos verificables de esta línea base:**

| Artefacto | Rol |
| --- | --- |
| [`03-linea-base-uml.v1.json`](03-linea-base-uml.v1.json) | Inventario machine-checkable: constructos, mapeos, exclusiones y huecos |
| [`03-linea-base-uml.schema.json`](03-linea-base-uml.schema.json) | Esquema JSON que el inventario debe satisfacer |
| [`tools/contract-tests/`](../../../tools/contract-tests/) | Suites que verifican el inventario contra su esquema y contra la autoridad |
| [`tools/contract-tests/baseline_summary.py`](../../../tools/contract-tests/baseline_summary.py) | Deriva las cifras de esta página desde el inventario |

Este documento explica y resume; **el inventario es la fuente de verdad**. Si los dos se contradicen, manda el
inventario y este documento está mal.

## 1. Por qué existe

El enunciado exige un diagramador "compatible con UML 2.5", y el análisis del enunciado advierte que esa frase
puede abarcar una superficie mucho mayor que la necesaria para el parcial. Una línea base que solo exista
como afirmación en prosa no se puede auditar: nadie puede distinguir "lo soportamos" de "no lo probamos".

Esta línea base existe para que la declaración sea **falsable**. Nombra el subconjunto y, para cada elemento,
declara qué ocurre en los tres destinos que importan: la persistencia, la generación de código y el
intercambio XMI.

## 2. Qué declara y qué no

**Declara** 32 constructos: 23 soportados y 9 que quedan nombrados sin soporte declarado, cada uno con su
motivo. Declara además 5 exclusiones explícitas de la superficie de UML 2.5 y 8 huecos abiertos con
responsable.

**No declara** compatibilidad con UML 2.5 completo. Nada que no aparezca en `constructs` está soportado,
aunque el formato XMI de origen pudiera contenerlo.

## 3. Los tres invariantes que el esquema hace cumplir

1. **Un mapeo por destino, y ningún silencio.** Cada constructo declara exactamente un mapeo para
   persistencia, uno para generación y uno para XMI. Cuando un destino no aplica, el valor es `NONE` y el
   esquema **exige un motivo**. Un mapeo ausente sin motivo es precisamente el hueco invisible que esta línea
   base existe para impedir.
2. **Nada se declara soportado sin materialización.** Un constructo con `supported: true` debe tener al menos
   un mapeo concreto. No se puede declarar soporte de algo que no se materializa en ningún destino.
3. **Nada se declara ratificado por sí solo.** Mientras `status` sea `proposed`, la ratificación debe estar
   `PENDING` y sin evidencia.

## 4. Cómo se verifica

```bash
python -m pytest tools/contract-tests -q
python tools/contract-tests/baseline_summary.py
```

**Evidencia medida (2026-09-28):** `15 passed` sobre tres archivos de contrato. El oráculo de la suite es
independiente: transcribe a mano las cláusulas obligatorias del análisis y **no** las lee del inventario, de
modo que la suite falla si alguien elimina una cláusula obligatoria.

**Prueba de mutación:** se eliminó del inventario el constructo que cubre `NOTES` y la suite falló en
`test_every_mandatory_clause_is_covered` nombrando exactamente `['NOTES']`; el resto siguió en verde y el
artefacto se restauró con el mismo hash. O sea: la suite no pasa por casualidad.

## 5. Reconciliación con la autoridad

### 5.1 El análisis del enunciado

Las 25 cláusulas obligatorias de `analisis-enunciado-parcial-1.md` §6 están cubiertas por al menos un
constructo. Hoy:

| Resultado | Cantidad | Cláusulas |
| --- | ---: | --- |
| Soportadas | 18 | `CLASS`, `ABSTRACT_CLASS`, `INTERFACE`, `PACKAGE`, `ATTRIBUTE`, `OPERATION`, `PARAMETER_AND_TYPE`, `DEFAULT_VALUE`, `ASSOCIATION`, `AGGREGATION`, `COMPOSITION`, `GENERALIZATION`, `INTERFACE_REALIZATION`, `DEPENDENCY`, `ROLES_AND_NAVIGABILITY`, `CONSTRAINTS`, `STABLE_IDS`, `LAYOUT_SEPARATION` |
| Con soporte parcial | 1 | `VISIBILITY` |
| Sin ningún soporte | 6 | `ENUMERATION`, `STATIC_MEMBER`, `ABSTRACT_MEMBER`, `MULTIPLICITY`, `NOTES`, `STEREOTYPES` |

### 5.2 Los contratos transversales

`01-contratos-transversales.md` §8.3 enumera en prosa el subconjunto soportado por el generador. Contrastado
con el modelo de datos, **§8.3 sobredeclara**. Este es el hallazgo central de este incremento:

| Declarado en §8.3 | Constructo | Veredicto |
| --- | --- | --- |
| classes | BL-CLS-01 | Soportado |
| abstract classes | BL-CLS-02 | Soportado |
| interfaces | BL-CLS-03 | Soportado |
| enumerations | BL-CLS-04, BL-CLS-05 | **No soportado** — no hay dónde guardar los literales |
| packages | BL-CLS-06 | Soportado |
| attributes | BL-FEA-01 | Soportado |
| operations | BL-FEA-02 | Soportado |
| parameters | BL-FEA-03 | Soportado (ver GAP-08) |
| primitive/domain types | BL-CLS-07 | Soportado |
| visibility | BL-VIS-01…04 | **Parcial** — 2 de 4 valores; columna sin restricción de dominio |
| static/abstract modifiers | BL-FEA-05, BL-FEA-06 | **No soportado** — modelado asimétrico |
| default values | BL-FEA-04 | Soportado |
| associations | BL-REL-01 | Soportado |
| aggregations | BL-REL-02 | Soportado |
| compositions | BL-REL-03 | Soportado |
| generalizations | BL-REL-04 | Soportado |
| interface realizations | BL-REL-05 | Soportado |
| dependencies | BL-REL-06 | Soportado |
| roles | BL-REL-08 | Soportado |
| navigability | BL-REL-09 | Soportado |
| multiplicities | BL-REL-07 | **No soportado** — categoría sin cotas numéricas |
| stable IDs | BL-CMP-04 | Soportado |
| supported constraints/stereotypes | BL-CMP-02, BL-CMP-03 | **Parcial** — restricciones sí, estereotipos no |

En números: de los 23 elementos que §8.3 declara, **18 se sostienen, 2 se sostienen a medias y 3 no**. Y hay
una cláusula que el análisis exige y §8.3 nunca menciona: **las notas UML**.

La diferencia no es cosmética. §8.3 dice que las enumeraciones se convierten en `enum` de Java; el modelo de
datos no tiene dónde guardar las constantes, así que hoy no puede cumplirlo. Declararlo soportado habría
significado descubrirlo durante la generación, no antes.

## 6. Inventario y triple mapeo

### Clasificadores

| id | Constructo | ¿Soporta? | Persistencia | Generación | XMI |
| --- | --- | :---: | --- | --- | --- |
| BL-CLS-01 | Clase | sí | `classifier` con `kind='CLASS'` | Entidad JPA | `uml:Class` con `EAID_` |
| BL-CLS-02 | Clase abstracta | sí | `is_abstract` y literal `ABSTRACT_CLASS` | Entidad JPA con herencia | `isAbstract='true'` |
| BL-CLS-03 | Interfaz | sí | `classifier.kind='INTERFACE'` | Interfaz Java | `uml:Interface` |
| BL-CLS-04 | Enumeración | **no** | Solo el clasificador, sin literales | `NONE` | `uml:Enumeration` |
| BL-CLS-05 | Literal de enumeración | **no** | `NONE` | `NONE` | `ownedLiteral` |
| BL-CLS-06 | Paquete | sí | `kind='PACKAGE'` y `namespace_id` | Paquete Java | `uml:Package` |
| BL-CLS-07 | Tipo primitivo o de dominio | sí | `type_reference` con XOR | Resolución en el IR | `type` con prefijo `EAJava_` |

### Características

| id | Constructo | ¿Soporta? | Persistencia | Generación | XMI |
| --- | --- | :---: | --- | --- | --- |
| BL-FEA-01 | Atributo | sí | `uml_attribute` | Campo y columna | `ownedAttribute` |
| BL-FEA-02 | Operación | sí | `uml_operation` | Método y contrato | `ownedOperation` |
| BL-FEA-03 | Parámetro | sí | `operation_parameter` | Parámetro del método | `ownedParameter` |
| BL-FEA-04 | Valor predeterminado | sí | `default_value` | Valor por defecto | `defaultValue` |
| BL-FEA-05 | Miembro estático | **no** | `is_static` solo en operación | `static` solo donde se distingue | `isStatic` |
| BL-FEA-06 | Miembro abstracto | **no** | `is_abstract` solo en operación | Operación abstracta | `isAbstract` |
| BL-VIS-01 | Visibilidad pública | sí | `visibility`, por defecto `PUBLIC` | Miembro público | `visibility='public'` |
| BL-VIS-02 | Visibilidad privada | sí | `visibility` | Miembro privado | `visibility='private'` |
| BL-VIS-03 | Visibilidad protegida | **no** | `VARCHAR(16)` sin dominio | Miembro protegido | `visibility='protected'` |
| BL-VIS-04 | Visibilidad de paquete | **no** | `VARCHAR(16)` sin valor validado | `NONE` | `visibility='package'` |

### Relaciones

| id | Constructo | ¿Soporta? | Persistencia | Generación | XMI |
| --- | --- | :---: | --- | --- | --- |
| BL-REL-01 | Asociación | sí | `kind='ASSOCIATION'` | Regla JPA del perfil | `uml:Association` |
| BL-REL-02 | Agregación | sí | `kind` y `aggregation_kind='SHARED'` | Ciclo de vida compartido | `aggregation='shared'` |
| BL-REL-03 | Composición | sí | `kind` y `aggregation_kind='COMPOSITE'` | Ciclo de vida del compuesto | `aggregation='composite'` |
| BL-REL-04 | Generalización | sí | `kind='GENERALIZATION'` | Estrategia de herencia | `generalization` |
| BL-REL-05 | Realización de interfaz | sí | `kind='INTERFACE_REALIZATION'` | `implements` | `interfaceRealization` |
| BL-REL-06 | Dependencia | sí | `kind='DEPENDENCY'` | `NONE` | `uml:Dependency` |
| BL-REL-07 | Multiplicidad | **no** | `multiplicity_kind` sin cotas | Nulabilidad y colección | `lowerValue`/`upperValue` |
| BL-REL-08 | Nombre de rol | sí | `relationship_end.role` | Nombre del campo | `name` del extremo |
| BL-REL-09 | Navegabilidad | sí | `relationship_end.navigability` | Lado propietario | `isNavigable` |
| BL-REL-10 | Extremo de relación | sí | `relationship_end` con `ordinal` 1 o 2 | Extremo de la relación JPA | `memberEnd` |

### Complementarios

| id | Constructo | ¿Soporta? | Persistencia | Generación | XMI |
| --- | --- | :---: | --- | --- | --- |
| BL-CMP-01 | Nota UML | **no** | `NONE` | `NONE` | `ownedComment` |
| BL-CMP-02 | Restricción | sí | `uml_constraint` con dueño polimórfico | Validación si hay equivalencia | `ownedRule` y `constraints` |
| BL-CMP-03 | Estereotipo | **no** | `NONE` | `NONE` | `stereotype` y perfil |
| BL-CMP-04 | Identificador estable | sí | UUID por tabla y pares polimórficos | Id de origen en el IR y los hashes | Correlación con `EAID_` |
| BL-CMP-05 | Disposición visual separada | sí | `diagram_view`, `element_geometry`, `connector_path` | `NONE` (excluido del artefacto) | `element`, `geometry`, `style` |

**Celdas `NONE`: 10 de 96** (persistencia 3, generación 7, XMI 0). Que XMI no tenga ninguna celda `NONE` no
significa que no pierda nada: significa que todo constructo de esta línea base tiene una forma declarada en el
dialecto. La pérdida real del intercambio es materia de T3 y de Q3, no de esta tabla.

## 7. Huecos abiertos

| id | Hueco | Bloquea | Responsable |
| --- | --- | --- | --- |
| GAP-01 | La línea base no está ratificada | Cierre del Incremento 0 y arranque del Ciclo 1 | Equipo y docente |
| GAP-02 | Sin literales de enumeración no hay `enum` de Java | BL-CLS-04, BL-CLS-05, RF-10, RF-11 | Equipo (diseño de datos) |
| GAP-03 | No existe entidad para notas UML | BL-CMP-01, RF-02, roundtrip de notas | Equipo (diseño de datos) |
| GAP-04 | No existe entidad de estereotipo aplicado | BL-CMP-03, RF-09, RF-10 | Equipo (diseño de datos) |
| GAP-05 | Multiplicidad sin cotas numéricas | BL-REL-07, fidelidad del roundtrip | Equipo (decisión explícita) |
| GAP-06 | Visibilidad sin restricción de dominio y sin cobertura | BL-VIS-03, BL-VIS-04, RF-02 | Equipo (diseño de datos) |
| GAP-07 | El roundtrip contra EA no se puede verificar: el servidor no responde | Q3, RF-09, RNF-05 | Equipo con EA abierto |
| GAP-08 | El mapeo no documenta dirección ni retorno del parámetro | BL-FEA-02, BL-FEA-03, contrato de operaciones | Equipo (revisión del mapeo) |

## 8. Exclusiones explícitas

| Fuera de alcance | Motivo |
| --- | --- |
| UML 2.5 completo | El análisis advierte que la frase abarca más que el parcial; se declara un subconjunto |
| Diagramas de comportamiento | El tipo central obligatorio es el diagrama de clases |
| Diagramas estructurales fuera del alcance | No son necesarios para generar un backend desde el modelo |
| Relaciones UML no enumeradas | Solo se declara lo que el análisis enumera |
| Entidad-relación como segundo editor | El análisis lo interpreta como contexto, no como editor paralelo |

## 9. Qué cambia si se ratifica

Si el docente ratifica la línea base tal como está, el Ciclo 1 puede arrancar con estos nueve constructos
nombrados como pendientes y no como olvidos: cierra GAP-02 a GAP-06 en el diseño de datos, o acepta la
restricción por escrito. Si la rechaza, el cambio se hace sobre el inventario y las pruebas vuelven a
decidir: la suite es el contrato, no la conversación.

## 10. Trazabilidad

| Requisito | Cómo lo toca esta línea base |
| --- | --- |
| RF-02 (diagramas de clases UML 2.5) | Define el subconjunto que puede considerarse conforme |
| RF-09 (importar y exportar XMI) | Aporta la columna XMI que T3 caracterizará contra el fixture real |
| RF-10, RF-11 (generar backend y su configuración) | Fija qué elementos son generables y cuáles quedan fuera |
| RNF-04 (determinismo) | Los constructos soportados son la entrada legible del generador |
| RNF-05 (interoperabilidad) | Declara qué debe sobrevivir al intercambio |
| CU-16, CU-17, CU-18…CU-20, Q3 | Entrada de los casos de uso de XMI y generación |

## 11. Registro de verificación

| Comprobación | Resultado | Fecha |
| --- | --- | --- |
| Esquema válido contra JSON Schema draft 2020-12 | Correcto | 2026-09-28 |
| Suite de contrato | 15 pruebas, 0 fallos | 2026-09-28 |
| Citas de origen existentes en disco | 32 constructos, ninguna cita rota | 2026-09-28 |
| Prueba de mutación sobre la cláusula `NOTES` | Falló la prueba correcta y solo esa | 2026-09-28 |
| Verificación independiente del mapeo de persistencia | Pendiente (delegada) | — |
| Ratificación con el docente | Pendiente | — |
