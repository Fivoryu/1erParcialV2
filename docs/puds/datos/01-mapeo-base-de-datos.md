# Mapeo de base de datos del diagramador

Este documento define el mapeo relacional propuesto para **la metadata del diagramador colaborativo de clases UML**. No modela el dominio que el docente entregue durante el examen: ese dominio será creado posteriormente por los usuarios dentro de un proyecto. The detailed generation contract is in [`docs/puds/generacion/01-diseno-generador.md`](../generacion/01-diseno-generador.md).

> **Estado:** D2 — borrador creado. El mapeo es revisable y no cierra todavía la línea base completa de Diseño.

## Ruta rápida

| Necesidad de revisión | Sección |
| --- | --- |
| Alcance y autoridad | [1. Alcance, autoridad y decisiones](#1-alcance-autoridad-y-decisiones) |
| Clase → tabla | [2. Mapeo relacional](#2-mapeo-relacional-clase--tabla) |
| Semántica, disposición y viewport | [3. Separación de estados](#3-separación-de-estados) |
| Normalización e integridad | [4. Normalización e integridad](#4-normalización-e-integridad) |
| Historial, conflictos y offline | [5. Historial, colaboración y auditoría](#5-historial-colaboración-y-auditoría) |
| Decisiones abiertas | [6. Propuestas y gaps](#6-decisiones-propuestas-y-gaps) |
| Artefactos relacionados | [7. Trazabilidad y próximos pasos](#7-trazabilidad-y-próximos-pasos) |

## 1. Alcance, autoridad y decisiones

### 1.1 Autoridad

La fuente funcional es [Captura de requisitos](../01-captura-de-requisitos.md); las invariantes y responsabilidades provienen de [Análisis](../02-analisis.md). El modelo lógico fue agregado al paquete `PUDS - Diseño / Datos / Modelo lógico` del archivo [EA de arquitectura, datos y despliegue](../../diagramas/arquitectura-despliegue.eapx), en el diagrama `Diagrama de Clases del Sistema`.

Este documento y el [esquema físico PostgreSQL](02-esquema-fisico.sql) son una **propuesta D2**. No seleccionan ORM, protocolo de autenticación, proveedor de identidad, algoritmo CRDT ni nombres de recursos cloud. La arquitectura de IA sí está confirmada: runtime backend-local Qwen 3.5 0.8B en Docker, con pesos/caché configurables en D: y desplegable como contenedor en AWS, y runtime móvil-local/en dispositivo independiente para Flutter. Floci es el emulador local de AWS; la compatibilidad exacta de artefacto, runtime, cuantización y preprocesador es un gate técnico.

### 1.2 Alcance persistente

Se persisten:

- identidad referencial de usuarios, proyectos, integrantes e invitaciones;
- modelo semántico UML: clasificadores, características, tipos, relaciones, extremos y restricciones;
- disposición compartida: vistas, geometrías y rutas de conectores;
- versiones, comandos, cascadas, tombstones, conflictos y colisiones de nombres;
- propuestas asistidas y elementos detectados antes de su confirmación, incluida la procedencia (`runtime`, `modelId`, `artifactId`, `executionMode`), cobertura, confianza, faltantes y ambigüedades;
- transcripciones ASR locales asociadas a propuestas de voz, antes de su interpretación textual con Qwen;
- intercambios XMI y pérdidas informadas;
- generation targets, versioned profiles/templates, generation runs, artifacts, and verification results;
- auditoría suficiente para reconstruir quién intentó qué operación y con qué resultado.

No se persiste aquí ninguna clase del dominio desconocido de la demostración. `Classifier` representa los elementos UML que el usuario modela, no entidades predefinidas del examen.

### 1.3 Decisiones de mapeo

| Tema | Decisión D2 | Estado |
| --- | --- | --- |
| Motor | PostgreSQL, con esquema dedicado `diagrammer`. | Propuesto |
| Identidad | UUID estable generado por aplicación o por PostgreSQL; el nombre visible nunca es la identidad. | Derivado |
| Forma | Tablas normalizadas para relaciones estables; JSONB solo para payloads variables, diagnósticos y configuraciones. | Propuesto |
| Historial | Versiones y comandos son registros append-only lógicos; las correcciones se expresan como nuevos comandos. | Derivado |
| Eliminación | El flujo normal usa tombstones y cascadas lógicas atómicas; no depende de borrar filas semánticas. | Derivado |
| Acceso | `external_subject` es una referencia opaca al sistema de identidad. No fija protocolo ni proveedor. | GAP controlado |
| Estado personal | Zoom, pan, selección, paneles, tema, filtros y grilla permanecen en el cliente V1. | Confirmado por Análisis |

## 2. Mapeo relacional Clase → tabla

Las claves primarias son UUID. Las relaciones de composición se expresan mediante FK y, cuando es seguro para la limpieza técnica, `ON DELETE CASCADE`; la eliminación funcional del modelo se realiza mediante comandos, tombstones y validación de servidor.

| Clase UML | Tabla PostgreSQL | Clave y columnas principales | Relaciones persistentes |
| --- | --- | --- | --- |
| `User` | `diagrammer.user_account` | `user_id` PK; `external_subject`, `display_name`, `email`, `revoked_at` | 1:N con `project`, `project_member`, `invitation`, `model_command`, propuestas y auditoría |
| `Project` | `diagrammer.project` | `project_id` PK; `owner_user_id` FK; `name`, fechas de archivo | 1:1 con `uml_model`; 1:N con miembros, invitaciones, perfiles, targets y auditoría |
| `ProjectMember` | `diagrammer.project_member` | PK compuesta (`project_id`, `user_id`); `role`, `status`, fechas | N:1 con proyecto y usuario; permisos básicos por proyecto |
| `Invitation` | `diagrammer.invitation` | `invitation_id` PK; `project_id`, `invited_by_user_id`, `invitee_user_id` | N:1 con proyecto; usuario invitado puede todavía no existir |
| `UMLModel` | `diagrammer.uml_model` | `model_id` PK; `project_id` UNIQUE FK; `name`, `current_revision` | 1:N con clasificadores, relaciones, vistas, versiones, comandos, conflictos y entradas de intercambio |
| `Classifier` | `diagrammer.classifier` | `classifier_id` PK; `model_id`, `namespace_classifier_id`, `kind`, `name`, `is_abstract` | 1:N con atributos, operaciones, restricciones; se referencia desde extremos y tipos |
| `Attribute` | `diagrammer.uml_attribute` | `attribute_id` PK; `classifier_id`, `type_reference_id`, firma y orden | N:1 con clasificador y tipo; no se impone unicidad por nombre |
| `Operation` | `diagrammer.uml_operation` | `operation_id` PK; `classifier_id`, retorno, firma y orden | N:1 con clasificador; 1:N con parámetros |
| `Parameter` | `diagrammer.operation_parameter` | `parameter_id` PK; `operation_id`, `type_reference_id`, dirección y orden | N:1 con operación y tipo |
| `TypeReference` | `diagrammer.type_reference` | `type_reference_id` PK; `model_id`; referencia a clasificador **o** nombre primitivo | Puede ser usado por atributos, operaciones y parámetros |
| `Relationship` | `diagrammer.uml_relationship` | `relationship_id` PK; `model_id`, `kind`, `name` | 1:N con exactamente dos extremos según la invariante de aplicación; puede tener restricciones |
| `RelationshipEnd` | `diagrammer.relationship_end` | `relationship_end_id` PK; `relationship_id`, `classifier_id`, ordinal 1–2, rol y multiplicidad | N:1 con relación y clasificador |
| `Constraint` | `diagrammer.uml_constraint` | `constraint_id` PK; dueño opcional de clasificador o relación; expresión y severidad | N:1 con modelo y, opcionalmente, un dueño UML |
| `DiagramView` | `diagrammer.diagram_view` | `view_id` PK; `model_id`, `name`, `view_kind`, `is_default` | 1:N con geometrías y rutas de conectores; solo representa disposición compartida |
| `ElementGeometry` | `diagrammer.element_geometry` | `geometry_id` PK; vista y un `classifier_id` o `relationship_id`; coordenadas | N:1 con vista y con el elemento visual estable |
| `ConnectorPath` | `diagrammer.connector_path` | `connector_path_id` PK; vista, relación, índice de punto, `x`, `y` | N:1 con vista y relación; la ruta no cambia la semántica |
| `ModelVersion` | `diagrammer.model_version` | `version_id` PK; `model_id`, revisión, padre, hash, estado de validación | N:1 con modelo; comandos apuntan a versión base y resultante |
| `ModelCommand` | `diagrammer.model_command` | `command_id` PK; autor, modelo, base/resultante, operación, payload y precondiciones | N:1 con modelo/autor/versiones; puede invertir otro comando y ser raíz de cascada |
| `CascadeGroup` | `diagrammer.cascade_group` | `cascade_group_id` PK; `model_id`, comando raíz, cantidad | 1:N con tombstones; identifica una eliminación lógica atómica |
| `Tombstone` | `diagrammer.tombstone` | `tombstone_id` PK; modelo, tipo/ID objetivo, comando, grupo, fechas | N:1 con grupo y comando; target polimórfico validado por aplicación |
| `Conflict` | `diagrammer.model_conflict` | `conflict_id` PK; target, tipo, estado, payloads base/izquierda/derecha | N:1 con modelo y comandos de detección/resolución |
| `NameCollision` | `diagrammer.name_collision` | `collision_id` PK; modelo, namespace, `name`, estado | Registra colisiones sin índice UNIQUE sobre `classifier.name` |
| `AIProposal` | `diagrammer.ai_proposal` | `proposal_id` PK; modelo, autor, versión base, fuente, confianza y payload con contrato común/procedencia | 1:N con detecciones; conserva runtime, modelId, artifactId, executionMode, ASR/propuesta y campos UML antes de confirmación; puede referenciar comandos al aplicarse mediante auditoría/historial |
| `DetectedElement` | `diagrammer.detected_element` | `detected_element_id` PK; propuesta, clave externa, tipo, nombre, confianza | N:1 con propuesta; `accepted_classifier_id` es nullable hasta confirmación |
| `XMIExchange` | `diagrammer.xmi_exchange` | `exchange_id` PK; modelo, dirección, perfil, versiones, checksum y estado | 1:N con pérdidas; importación/exportación siempre deja diagnóstico |
| `XMILossItem` | `diagrammer.xmi_loss_item` | `loss_item_id` PK; intercambio, severidad, ruta externa y mensaje | N:1 con intercambio; no se ocultan pérdidas no representables |
| `GenerationTarget` | `diagrammer.generation_target` | `target_id` PK; modelo, `target_key`, nombre, metadata de base de datos sin secretos, referencia de configuración externa y revisión | 1:N con ejecuciones; identidad estable de un backend/base de datos independiente |
| `GenerationProfile` | `diagrammer.generation_profile` | `profile_id` PK; proyecto opcional, `profile_key`, revisión, hash y configuración sin secretos | 1:N con versiones de plantilla y ejecuciones |
| `TemplateVersion` | `diagrammer.template_version` | `template_id` PK; perfil, etiqueta, revisión y hash | N:1 con perfil; identidad inmutable por versión |
| `GenerationRun` | `diagrammer.generation_run` | `run_id` PK; modelo, versión, target, perfil, plantilla, snapshot/hashes, ZIP y estado | 1:N con artefactos y resultados de verificación |
| `GeneratedArtifact` | `diagrammer.generated_artifact` | `artifact_id` PK; ejecución, ruta, tipo, hash, URI y tamaño | N:1 con ejecución; la URI no presupone un almacenamiento concreto |
| `VerificationResult` | `diagrammer.verification_result` | `verification_id` PK; ejecución, nombre de check, estado, evidencia/hash, herramienta y tiempos | N:1 con ejecución; `tool_name` permanece tecnológico-abierto y la evidencia no contiene secretos |
| `AuditLog` | `diagrammer.audit_log` | `audit_id` PK; proyecto, actor, comando, target, acción, resultado y metadata | N:1 opcional con proyecto, usuario y comando; append-only lógico |

### 2.1 Claves, nombres e identificadores

- `classifier_id`, `attribute_id`, `operation_id`, `relationship_id` y demás UUID son identificadores estables y no se regeneran al recuperar una versión.
- `classifier.name` no tiene `UNIQUE`: dos clasificadores pueden compartir nombre en un namespace. `name_collision` registra y comunica la condición; la validación final, publicación, XMI definitivo y generación pueden bloquearse hasta resolverla.
- Las restricciones de unicidad de orden (`relationship_id, ordinal`, por ejemplo) evitan duplicar posiciones estructurales, no nombres UML.
- Los targets de tombstones y conflictos son pares `target_type` + UUID porque el modelo UML tiene varios tipos de elemento; la existencia y el tipo real se verifican en la capa de aplicación.

### 2.2 Identidad de generación y regla de unicidad

`generation_target` es la identidad estable del destino: un backend y su base de datos externa. `generation_profile` y `template_version` son identidades inmutables de estrategia/versión. Ninguna se reemplaza por un nombre visible o una configuración de conexión.

La regla canónica anterior de `generation_run` era `UNIQUE (model_id, version_id, profile_id, template_id)`. Se reemplaza explícitamente por `UNIQUE (target_id, target_config_revision, version_id, profile_id, template_id)`: el target y su revisión congelada de configuración forman parte de la identidad determinista, por lo que una versión puede tener varias ejecuciones independientes sin permitir dos resultados para la misma combinación target/configuración/versión/perfil/plantilla. `model_id` permanece para trazabilidad y debe coincidir con la versión y el target; el servicio/transacción valida esta consistencia.

`generation_profile.revision` + `configuration_hash` y `template_version.revision` + `content_hash` fijan las versiones exactas. `generation_run` almacena `target_config_revision`, `input_snapshot_hash`, `artifact_set_hash`, `artifact_zip_hash` y la URI del ZIP. Los hashes cubren solo entradas/contenido canónicos sin secretos; se excluyen timestamps, UUID aleatorios, referencias de secretos, credenciales, pesos, cachés y estado de runtime.

## 3. Separación de estados

| Estado | Persistencia D2 | Qué contiene | Qué no debe hacer |
| --- | --- | --- | --- |
| Modelo semántico | `uml_model`, `classifier`, `uml_attribute`, `uml_operation`, `operation_parameter`, `type_reference`, `uml_relationship`, `relationship_end`, `uml_constraint` | Identidad, nombres, firmas, tipos, multiplicidades, relaciones, restricciones y reglas UML | No depende de coordenadas, zoom ni del dispositivo del usuario |
| Disposición compartida | `diagram_view`, `element_geometry`, `connector_path` | Vistas, posiciones, tamaños, orden y puntos de conectores observables por colaboradores | No modifica el tipo UML ni crea/elimina semántica por mover un elemento |
| Viewport personal | Cliente V1; sin tabla en este esquema | Zoom, pan, selección, paneles, tema, filtros, grilla y preferencias de sesión | No se publica como cambio semántico ni como layout compartido |
| Estado offline/transitorio | Outbox local del cliente y presencia efímera | Operaciones pendientes, conexión, cursor y presencia | No se trata como verdad durable del servidor antes de autorización y clasificación |

Una geometría compartida puede apuntar a un clasificador o a una relación, pero no a ambos. La ruta de un conector usa puntos ordenados y tampoco cambia los extremos.

## 4. Normalización e integridad

### 4.1 Forma normal propuesta

El diseño separa atributos atómicos y relaciones N:M en filas propias:

1. **1FN:** nombres, roles, multiplicidades, coordenadas, hashes y estados ocupan columnas atómicas; las listas de targets y payloads variables se conservan como JSONB porque son sobres de comandos, no relaciones de dominio.
2. **2FN:** las tablas con identidad propia usan UUID; `project_member` usa su clave compuesta y no repite atributos del usuario o proyecto.
3. **3FN:** tipos, plantillas, ejecuciones, intercambios, conflictos y auditoría tienen tablas independientes; no se duplica la descripción del usuario, perfil o modelo en cada fila hija.
4. **Dependencias identificables:** las FKs expresan pertenencia y las claves compuestas/índices expresan orden o idempotencia sin convertir nombres visibles en claves.

### 4.2 Reglas de integridad

- Todas las referencias principales usan FK y `NOT NULL` cuando la pertenencia es obligatoria.
- Los `CHECK` limitan clasificadores y relaciones al subconjunto UML acordado, validan multiplicidades, coordenadas, hashes, confianzas, estados y severidades.
- Una `TypeReference` referencia un clasificador existente o un nombre de tipo primitivo, pero no ambos.
- Una geometría pertenece a un único clasificador o relación; una relación tiene ordinales 1 y 2 como máximo.
- Una vista solo contiene layout compartido. La equivalencia de `model_id` entre todas las filas relacionadas se valida en aplicación o transacción de servicio.
- La unicidad se reserva para identidad técnica, orden dentro de un padre, idempotencia y rutas dentro de una ejecución. Nunca se usa para prohibir nombres UML duplicados.
- Índices por modelo, versión, estado y target favorecen recuperación, validación y paneles de conflicto.
- `generation_target` separa la identidad del destino de las identidades de perfil y plantilla. Su configuración de base de datos contiene solo datos sin secretos y una referencia externa opaca; nunca credenciales.

### 4.3 Invariantes que requieren servicio o transacción

El SQL no puede garantizar por sí solo, sin triggers específicos que no forman parte de este borrador:

- que una relación tenga **exactamente dos** extremos válidos y que ambos pertenezcan al mismo modelo;
- que una eliminación delete-wins cree todos los tombstones y dependencias en una única operación atómica y que un undo restaure el conjunto completo;
- que cada comando esté autorizado para ese proyecto y que una versión sea resultado de un modelo completamente validado;
- que las referencias polimórficas de `target_type` + `target_id` existan con el tipo correcto;
- que no haya colisiones de nombres en un snapshot válido ni referencias cruzadas entre modelos;
- que la revocación offline se clasifique comparando evidencia temporal, autoridad del servidor y versión de permisos;
- que una generación consuma exclusivamente una versión validada, sea independiente de ambos runtimes de IA y produzca todos sus artefactos de forma determinista;
- que `generation_target`, `generation_profile` y `template_version` pertenezcan al mismo proyecto/modelo y que el target seleccionado no se confunda con un perfil o plantilla;
- que ningún secreto llegue a configuración persistida, imágenes o volúmenes destinados a exportación, propuestas, código generado, ZIPs, logs, diagnósticos o hashes;
- que las propuestas no contengan pesos, cachés ni estado mutable de ejecución de ninguno de los runtimes de IA.

## 5. Historial, colaboración y auditoría

### 5.1 Comandos, versiones y undo/redo

`model_command` conserva el autor, la revisión base/causal, targets, precondiciones, payload variable, origen y clasificación de autorización. `model_version` conserva revisión, padre, hash y estado de validación. Deshacer y rehacer no reescriben el pasado: crean un comando inverso nuevo con `inverse_of_command_id` y vuelven a validar el estado actual. El SQL deja comentarios de append-only; la API debe rechazar mutaciones retroactivas salvo la transición de estado explícitamente definida.

### 5.2 Tombstones y cascadas

Eliminar un clasificador o relación se registra como comando delete-wins. `cascade_group` agrupa el root y sus `tombstone`; la aplicación calcula y valida la cascada antes de confirmarla, muestra el conteo y permite una única restauración válida del conjunto. La limpieza física de filas históricas es una operación administrativa fuera del flujo normal y no está definida por D2.

### 5.3 Conflictos y colisiones

Los merges seguros pueden aceptar campos independientes; una incompatibilidad conserva payloads base/izquierda/derecha en `model_conflict`. `name_collision` conserva ambos clasificadores y su contexto en vez de fusionarlos o borrarlos. Resolver cualquiera de los dos casos produce una acción trazable y una nueva versión/comando.

### 5.4 Offline y revocación

El outbox es del cliente. Cuando el servidor recibe una operación offline, el sobre se guarda como comando pendiente o procesado y `authorization_classification` distingue:

- `verified_pre_revocation`: puede continuar si la evidencia es suficiente;
- `verified_post_revocation`: se rechaza;
- `ambiguous`: queda fuera del canon y requiere revisión administrativa auditada;
- `authorized_online` y `not_applicable`: cubren operaciones conectadas o no offline.

El servidor es la autoridad: una autorización local, un cursor o una presencia no publican una modificación por sí solos. La revocación no se infiere solo desde el cliente.

### 5.5 Auditoría

`audit_log` registra intentos y resultados de operaciones manuales, asistidas, importadas, offline, administrativas y de generación, incluyendo target, actor cuando se conoce, razón y metadata variable. Es append-only lógico. El esquema no fija retención, firma, proveedor ni almacenamiento externo; son gaps de seguridad/operación a resolver antes de producción.

## 6. Decisiones propuestas y gaps

| Tema | Tratamiento en D2 | Estado / próximo corte |
| --- | --- | --- |
| Identidad y autenticación | Se guarda solo `external_subject` opaco y estado de revocación. | GAP; D3 debe definir contrato, no proveedor necesariamente. |
| Autorización fina | Roles básicos en `project_member`; la autorización de cada comando es de servicio. | Propuesto; D3 debe detallar permisos y respuestas. |
| Colaboración | Comandos, versiones, conflictos y clasificación offline; no se selecciona CRDT. | Propuesto; comparar alternativas en D3. |
| Retención y purga | No se borra historial durante el uso normal. | GAP operativo; definir política y permisos de administración. |
| JSONB | Permitido para sobres variables, diagnósticos, propuestas y configuración; no reemplaza las relaciones estables. | Decisión D2 |
| Generación | `GenerationTarget` identifica un backend/base de datos independiente; perfil y plantilla son versiones inmutables de reglas; snapshot, artefactos ZIP, hashes y verificaciones son metadata del generador. La configuración de base de datos es externa y sin secretos; `target_language`, URI y `tool_name` son descriptivos. La generación es independiente de los dos runtimes de IA. | Propuesto; no fija framework, ORM ni cloud. |
| Viewport personal | Se mantiene local/efímero en V1. | Confirmado provisional; reevaluar solo si un requisito exige sincronización. |
| RLS, triggers y particionado | No se agregan sin decisión de seguridad/operación y evidencia de necesidad. | GAP; revisar antes de implementación productiva. |
| Migraciones | El SQL está ordenado por dependencias y usa `IF NOT EXISTS` en esquema/extensión; los cambios posteriores deben ser migraciones versionadas. | Propuesto |

## 7. Trazabilidad y próximos pasos

| Elemento D2 | Evidencia | Habilita |
| --- | --- | --- |
| Modelo lógico | `../../diagramas/arquitectura-despliegue.eapx`, paquete `Datos / Modelo lógico`, diagrama `Diagrama de Clases del Sistema` | Revisar clases, asociaciones, composiciones y separación de estados |
| Mapeo | Este documento | Revisar cobertura de clases, claves, FKs y reglas |
| Diseño físico | [`02-esquema-fisico.sql`](02-esquema-fisico.sql) | Ejecutar un parser/instalación controlada y preparar migraciones |
| Trazabilidad funcional | RF-01–RF-16, RNF-01–RNF-11; CU-01–CU-23; P01–P09 | Completar D3: comandos, API, conflictos, XMI, IA y generación |
| Diseño de generación | [`../generacion/01-diseno-generador.md`](../generacion/01-diseno-generador.md), [`../contratos/01-contratos-transversales.md`](../contratos/01-contratos-transversales.md) §8 | Revisar snapshot, targets independientes, perfiles/plantillas, reglas, ZIP, hashes, seguridad y verificación sin dependencia de IA |
| Verificación D2 | Lectura estructural del EA, validación del diagrama, comprobación de DDL y revisión de enlaces | No cerrar todavía la línea base general de Diseño |

### Checklist de revisión D2

- [x] El esquema modela metadata del diagramador y no el dominio desconocido.
- [x] Cada clase del modelo lógico tiene una tabla o una regla explícita de persistencia.
- [x] Los nombres UML duplicados no están prohibidos por una restricción UNIQUE.
- [x] Semántica, layout compartido y viewport personal están separados.
- [x] JSONB está acotado a payloads variables.
- [x] Tombstones, cascadas, conflictos, undo/redo, offline, revocación y auditoría tienen tratamiento explícito.
- [ ] Revisar y aprobar decisiones propuestas de D2.
- [ ] Completar contratos D3 y el plan de verificación D5.
- [ ] Mantener Q9 abierta y ejecutar Q3 como prueba de round-trip, sin presentar D2 como baseline final cerrada.

[Volver a Diseño](../03-diseno.md) · [Volver al Análisis](../02-analisis.md)
