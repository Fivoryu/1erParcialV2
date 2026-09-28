# PUDS — Diseño

> Documento índice del flujo de Diseño para el diagramador colaborativo de clases UML 2.5. Esta primera versión prepara la entrada D0 y organiza los artefactos que se completarán por incrementos; todavía no fija la arquitectura física ni las tecnologías concretas.

[Índice del PUDS](../esqueleto-proceso-desarrollo-parcial-2.md) · [Captura de requisitos](01-captura-de-requisitos.md) · [Análisis](02-analisis.md) · [Diseño del generador](generacion/01-diseno-generador.md)

## Estado y propósito

**Estado:** D0–D6 — línea base inicial de Diseño **cerrada** (umbral C1) en [acta de cierre](cierre/01-cierre-diseno.md). La captura, el análisis y el diseño base son la entrada; los umbrales C2 y C3 revalidan el diseño con las evidencias de Q3 y Q9.

Este documento define:

- qué información entra a Diseño;
- qué artefactos exige la estructura de los modelos PUDS consultados;
- en qué orden se elaborarán;
- cómo se relacionan con los ciclos C1, C2 y C3;
- qué debe verificarse antes de pasar a implementación.

Este documento no repite RF, RNF ni las fichas de los 23 casos de uso. Esos elementos permanecen bajo la autoridad de [Captura de requisitos](01-captura-de-requisitos.md). Las responsabilidades conceptuales y los invariantes permanecen bajo la autoridad de [Análisis](02-analisis.md).

## Ruta rápida

1. [Entrada y reglas](#1-entrada-y-reglas-de-diseño): límites que Diseño no puede romper.
2. [Artefactos esperados](#2-artefactos-del-flujo-de-diseño): arquitectura, datos y comportamiento.
3. [Cortes D0–D6](#3-flujo-incremental-de-diseño): secuencia de elaboración y revisiones.
4. [Diseño por ciclos](#4-relación-con-los-ciclos): detalle progresivo para C1, C2 y C3.
5. [Trazabilidad](#5-plantilla-de-trazabilidad): forma de comprobar cada decisión.
6. [Generic backend generation](#233-generic-backend-generation): source, targets, and design contract.
7. [Cierre](#7-condiciones-de-cierre): condiciones para habilitar implementación.

## 1. Entrada y reglas de Diseño

### 1.1 Entradas canónicas

| Entrada | Uso en Diseño |
| --- | --- |
| `docs/puds/01-captura-de-requisitos.md` | RF-01–RF-16, RNF-01–RNF-11, reglas transversales, actores, ciclos, casos de uso, criterios de aceptación y estado de Q1–Q10. |
| `docs/puds/02-analisis.md` | Invariantes, paquetes P01–P09, responsabilidades, relaciones CU–paquetes, límites conceptuales, riesgos y mensajes mínimos. |
| `docs/fixtures/xmi/Prueba3.xml` | Fixture del perfil primario EA 15.0.1514 para el diseño del adaptador XMI y la prueba de round-trip de Q3. |
| `docs/modelos_puds/2do Parcial.pdf` | Referencia estructural del flujo de Diseño, especialmente su §2.3. No aporta requisitos del producto actual. |
| `docs/modelos_puds/documentacion.docx` | Modelo complementario para la organización de arquitectura, datos, mapeo, normalización, script y secuencias. No aporta requisitos ni tecnologías obligatorias. |

### 1.2 Reglas que Diseño debe conservar

1. **El modelo canónico es semántico.** La disposición compartida y la vista personal no deben alterar la semántica UML ni la generación.
2. **Las responsabilidades se conservan.** Diseño puede cambiar la distribución física de P01–P09, pero no puede eliminar responsabilidades ni romper los invariantes del Análisis.
3. **La validez precede a los efectos.** No se diseña una ruta que publique, exporte o genere desde un modelo inválido.
4. **Las operaciones son trazables.** El sobre de operación conserva `operationId`, autor, objetivos estables, revisión causal y precondiciones.
5. **La colaboración es tecnológica-abierta.** CRDT, merge por campos, registro de comandos u otra combinación son alternativas a comparar; CRDT no es mandato del producto.
6. **La seguridad y la auditoría son parte del baseline.** Autenticación, autorización y auditoría deben aparecer en los contratos y flujos que correspondan.
7. **Q9 sigue abierta.** La eficiencia integral, el recorrido exacto de aproximadamente tres minutos y la interacción final del asistente se decidirán en C3 con evidencia.
8. **Q3 es una prueba.** El round-trip EA → aplicación → XMI → EA se planifica y evidencia, pero no reabre la decisión del perfil primario.
9. **La fuente de generación es el dominio creado por el usuario.** El diagrama de clases UML que el usuario crea en un proyecto es la fuente del generador; el diagrama actual de metadata/arquitectura del diagramador no es entrada de dominio generado. La generación se implementa en el repositorio `backend-main-integration`, que no se modifica en este corte de diseño.
10. **La IA tiene dos runtimes independientes.** El backend usa Qwen 3.5 0.8B en un servicio local dentro de Docker; Flutter usa un runtime local/en dispositivo separado con la misma identidad de modelo. No comparten proceso ni memoria mutable. Qwen es obligatorio para interpretar texto y fotografías de diagramas de clase; la compatibilidad exacta de artefacto, runtime, cuantización y preprocesador es un gate técnico de verificación.

### 1.3 Clasificación de decisiones

Cada sección de Diseño debe marcar el estado de sus afirmaciones:

| Marca | Significado |
| --- | --- |
| **Confirmado** | Surge de Captura/Análisis y no se vuelve a decidir en Diseño. |
| **Derivado** | Se deduce de una responsabilidad o invariante; debe conservar trazabilidad. |
| **Propuesto** | Alternativa técnica que todavía debe compararse o aprobarse. |
| **GAP** | Información que no está disponible; no debe completarse por inferencia. |
| **Pendiente Q9** | Elemento deliberadamente reservado para el Ciclo 3. |

## 2. Artefactos del flujo de Diseño

La estructura de los modelos consultados organiza Diseño en arquitectura, datos y comportamiento. Para este proyecto se conserva esa estructura y se agregan contratos y verificación como cortes transversales.

### 2.1 Diseño de arquitectura — D1

| Artefacto | Propósito | Estado |
| --- | --- | --- |
| Diagrama de despliegue | Mostrar nodos, entornos, artefactos y límites entre AWS, desarrollo y emulación local con Floci. | Borrador EA creado |
| Vista C4 de contexto | Mostrar actores, clientes, sistema y dependencias externas sin detalles de implementación. | Pendiente |
| Vista C4 de contenedores | Separar editor, API, colaboración, persistencia, los dos runtimes de IA, XMI, generación y demás contenedores que se justifiquen. | Pendiente |
| Vista C4 de componentes | Detallar componentes relevantes dentro de cada contenedor y sus dependencias. | Pendiente |
| Diseño lógico de capas | Ubicar las cuatro capas mínimas: dominio/entidades, repositorios/persistencia, servicios/aplicación y controladores/API; ubicar componentes de apoyo. | Borrador EA creado |
| Registro de decisiones de arquitectura | Explicar alternativas, criterios, decisión, consecuencias y riesgos. | Pendiente |

Las vistas no deben convertirse en una lista de tecnologías sin responsabilidades. Cada componente debe responder qué responsabilidad cubre, qué contrato expone y qué requisito o caso de uso habilita.

#### 2.1.1 Artefacto EA inicial

Se creó un modelo EA independiente para reproducir la composición visual de las referencias de arquitectura y despliegue:

| Campo | Valor |
| --- | --- |
| Archivo | [`docs/diagramas/arquitectura-despliegue.eapx`](../diagramas/arquitectura-despliegue.eapx) |
| Paquete | `PUDS - Diseño / Arquitectura` |
| Diagrama 1 | `Despliegue del Sistema` — tipo EA `Deployment` |
| Diagrama 2 | `Arquitectura lógica organizada en capas` — tipo EA `Package`, con dependencias `<<use>>` |
| Referencias visuales | `image82.png` — despliegue; `image83.png` — capas, extraídas del modelo DOCX. |
| Estado | Borrador inicial de D1; debe revisarse y ampliarse con C4, decisiones y trazabilidad. |

El archivo adapta la distribución de las referencias al producto actual: cliente Flutter, editor web, backend generado Spring Boot, núcleo UML, colaboración/offline, los dos runtimes locales de IA, XMI, generación, PostgreSQL, almacenamiento de artefactos, AWS y Floci. La vista lógica usa paquetes y dependencias `<<use>>` para conservar la semántica visual del modelo. No copia el dominio ni las tecnologías específicas del sistema de ejemplo.

#### 2.1.2 Topología confirmada de IA y despliegue

- **Desarrollo local:** el servicio de IA del backend ejecuta Qwen 3.5 0.8B dentro de Docker. Sus pesos y caché se configuran mediante un volumen o ruta del host en `D:`; la ruta concreta queda como configuración operativa.
- **AWS:** el mismo runtime de IA del backend debe ser desplegable como contenedor. No se fija un servicio, nombre de recurso, región ni otro detalle facturable no elegido por el equipo.
- **Floci:** se usa como emulador local de AWS para desarrollo y comprobaciones de integración; no representa por sí solo el despliegue real.
- **Flutter:** el runtime local/en dispositivo de Qwen es independiente del proceso del backend y no comparte memoria mutable, caché de ejecución ni estado interno con él.
- **Comprobación operativa:** Docker Desktop con WSL puede almacenar su disco virtual VHDX en `C:`. Antes de descargar pesos o ampliar cachés se debe comprobar espacio disponible, ubicación efectiva del VHDX y capacidad en `D:`; esta es una verificación operativa, no una elección de proveedor.

### 2.2 Diseño de datos — D2

| Artefacto | Propósito | Estado |
| --- | --- | --- |
| Modelo lógico de clases | Representar el modelo semántico UML, proyectos, versiones, operaciones, integrantes, permisos, conflictos y artefactos relacionados. | Borrador EA creado |
| Mapeo | Relacionar conceptos y atributos con tablas, claves, relaciones y persistencia PostgreSQL. | Borrador creado |
| Normalización e integridad | Explicar claves, restricciones, invariantes, unicidad, cascadas, tombstones y separación entre semántica y disposición. | Incluido en el mapeo |
| Diseño físico | Definir esquema, tablas, tipos, índices, auditoría, historial, conflictos y migraciones. | Borrador creado |
| Script o migraciones | Producir la representación ejecutable y reproducible del esquema diseñado. | DDL inicial creado |

#### 2.2.1 Artefactos D2

| Artefacto | Ubicación | Contenido |
| --- | --- | --- |
| Diagrama de clases EA | [`docs/diagramas/arquitectura-despliegue.eapx`](../diagramas/arquitectura-despliegue.eapx), paquete `PUDS - Diseño / Datos / Modelo lógico` | Diagrama `Diagrama de Clases del Sistema` tipo `Logical` con 32 clases, atributos y 29 asociaciones. |
| Mapeo relacional | [`docs/puds/datos/01-mapeo-base-de-datos.md`](datos/01-mapeo-base-de-datos.md) | Alcance, clase → tabla, separación semántica/disposición/viewport, normalización, integridad, historial, conflictos, offline, auditoría y gaps. |
| Diseño físico SQL | [`docs/puds/datos/02-esquema-fisico.sql`](datos/02-esquema-fisico.sql) | Esquema PostgreSQL `diagrammer` con 33 tablas, enums, UUID, FKs, CHECK, índices y JSONB acotado. |

La vista lógica modela únicamente la metadata del diagramador; no modela el dominio del examen, que será creado por los usuarios en un proyecto. Las decisiones de ORM, protocolo de autenticación, CRDT y detalles del proveedor cloud siguen abiertas; la separación de los runtimes de IA, Qwen 3.5 0.8B y la frontera Docker/D:/AWS/Floci están confirmadas. La compatibilidad exacta del artefacto, runtime, cuantización y preprocesador permanece como gate técnico.

El modelo conceptual previo de `docs/analisis-enunciado-parcial-1.md` funciona únicamente como punto de partida. El diseño final debe derivarse de los requisitos y del Análisis actuales.

### 2.3 Contratos transversales — D3

| Contrato | Contenido mínimo | Estado |
| --- | --- | --- |
| Comandos | `operationId`, autor, objetivos estables, base causal, precondiciones, resultado y diagnóstico. | Borrador creado |
| API | Recursos, operaciones, validaciones, errores, autorización y respuestas frontend-agnósticas. | Borrador creado |
| Tiempo real/offline | Eventos, cola local, sincronización, orden causal, confirmación durable, revocación y conflictos. | Borrador creado |
| Conflictos | Criterios para merge seguro, alternativas incompatibles, panel lateral, resolución y nuevo comando. | Borrador creado |
| XMI | Perfil EA/XMI primario, preservación, pérdidas, advertencias y evidencia de round-trip. | Borrador creado |
| IA | Dos runtimes independientes con Qwen 3.5 0.8B; interpretación de texto y fotografías, ASR local para voz, propuesta compartida, procedencia, cobertura, confianza por elemento, faltantes, ambigüedades, revisión y confirmación. | Borrador creado; compatibilidad de artefacto/runtime/cuanti­zación/preprocesador y recursos pendientes de verificación |
| Generación | Fuente de dominio, `GenerationTarget`, perfiles/plantillas versionados, snapshot, reglas UML, artefactos ZIP, determinismo y verificación de backends independientes. | Borrador creado |

#### 2.3.1 Artefactos D3

| Artefacto | Ubicación | Contenido |
| --- | --- | --- |
| Contratos transversales | [`docs/puds/contratos/01-contratos-transversales.md`](contratos/01-contratos-transversales.md) | Sobre de operación, respuestas y errores, recursos API, eventos/offline/revocación, conflictos, XMI, IA y generación; decisiones abiertas y verificación prevista D5. |
| Diseño del generador | [`docs/puds/generacion/01-diseno-generador.md`](generacion/01-diseno-generador.md) | Contrato de fuente, target, snapshot, subconjunto UML, modelo intermedio, mapeos, diagnósticos, ZIP, hashes y ciclo de verificación. |

#### 2.3.2 Flujo confirmado de IA y gate técnico

La arquitectura del producto está confirmada; la compatibilidad concreta del artefacto y del runtime se valida técnicamente:

1. **Texto:** el runtime local seleccionado usa Qwen 3.5 0.8B para interpretar lenguaje natural sin exigir una sintaxis rígida.
2. **Fotografía:** Qwen 3.5 0.8B interpreta fotos de diagramas de clase, incluidos texto y estructura observable. El preprocesamiento puede preparar regiones, pero no sustituye la interpretación obligatoria de Qwen.
3. **Voz:** `audio → ASR local → transcripción → interpretación textual con Qwen → propuesta estructurada`. La transcripción se puede corregir antes de continuar.
4. **Propuesta común:** ambos runtimes emiten el mismo contrato frontend-agnóstico con clases, atributos/tipos, operaciones, relaciones/multiplicidades, confianza, cobertura, faltantes, ambigüedades y disposición aproximada cuando sea observable, además de `runtime`, `modelId`, `artifactId` y `executionMode`.
5. **Revisión y canon:** la propuesta no es comando ni fuente de verdad. La persona revisa, completa, reposiciona, confirma o descarta; después P02 valida UML y P03 aplica comandos. Ningún runtime muta directamente el modelo canónico.

El artefacto Qwen, el runtime de ejecución, la cuantización, el preprocesador para visión, la compatibilidad entre ellos, el consumo de memoria/CPU y la latencia son **gates de verificación técnica**. Estos gates no reabren el requisito confirmado de que Qwen interprete texto y fotografías ni permiten sustituirlo por un parser, OCR o modelo diferente.

#### 2.3.3 Generic backend generation

The user-created domain class diagram is the generator source; the current diagrammer metadata/architecture diagram is not generated-domain input. `GenerationTarget` (also called `BackendTarget` at the API/domain boundary) identifies one independent backend/database destination, separate from the versioned profile and template identities. One model/version can therefore produce multiple independent backend projects and databases. The snapshot, supported UML subset, intermediate representation, mapping rules, target-safe external database configuration, deterministic ZIP/hash rules, lifecycle, and CU-18–CU-20 verification contract are defined in [`docs/puds/generacion/01-diseno-generador.md`](generacion/01-diseno-generador.md).

The persistence/API alignment is defined in [`docs/puds/contratos/01-contratos-transversales.md`](contratos/01-contratos-transversales.md) §8, [`docs/puds/datos/01-mapeo-base-de-datos.md`](datos/01-mapeo-base-de-datos.md) §2.2, and [`docs/puds/datos/02-esquema-fisico.sql`](datos/02-esquema-fisico.sql). The current implementation prerequisite is recorded there: the backend snapshot contract still lacks full attributes, operations, and multiplicities. This is design-only; `backend-main-integration` source is unchanged.

Pendiente antes de cerrar D4: comparar explícitamente las alternativas de colaboración (CRDT vs merge por campos vs registro de comandos) con criterios de complejidad, verificabilidad, offline y alcance de la demostración, y definir los esquemas JSON versionados de cada `payload` en D5.

### 2.4 Diseño de comportamiento — D4

Los diagramas de secuencia se derivan de los grupos de casos de uso del Análisis. No se exige repetir un diagrama por cada CU cuando varios comparten el mismo flujo, pero cada CU debe quedar cubierto en la matriz de trazabilidad.

#### 2.4.1 Diagramas de secuencia EA

Se crearon 6 diagramas de secuencia en `PUDS - Diseño / Comportamiento / Escenario NN - <nombre>` del [EAPX](../diagramas/arquitectura-despliegue.eapx), basados en los contratos D3:

| Diagrama | Escenario | Mensajes | CUs asociadas |
| --- | --- | --- | --- |
| `Crear y validar clasificador` | Edición manual con sobre de operación, precondiciones y validación completa. | 7 | CU-04, CU-05, CU-08 |
| `Revisar y confirmar propuesta IA` | Texto/fotografía → Qwen en runtime local → propuesta parcial revisable → confirmación → comando validado. | 8 | CU-09, CU-10, CU-12 |
| `Conflicto y delete-wins` | Cambio concurrente, alternativa en panel, eliminación con cascada atómica. | 8 | CU-15, CU-16 E |
| `Offline y revocación` | Outbox local, reenvío causal y clasificación pre/post/ambigua. | 6 | CU-14, CU-15, CU-22 |
| `Importar XMI` | Fixture `Prueba3.xml`, normalización, validación y reporte de pérdidas. | 6 | CU-16, CU-17 |
| `Generación y verificación` | Target, perfil/plantilla versionados, snapshot de versión válida, modelo intermedio, backend independiente y verificación. | 9 | CU-18, CU-19, CU-20 |

Cada diagrama usa lifelines `rol: Clase` y mensajes numerados; quedan como borrador D4 revisable (no cierra la línea base).

| Grupo de comportamiento | Escenarios mínimos a diseñar | CUs relacionadas |
| --- | --- | --- |
| Edición y validación | Crear, modificar, validar y rechazar una operación UML; conservar disposición sin contaminar la semántica. | CU-01–CU-08 |
| Propuestas asistidas | ASR local para voz; interpretar con Qwen, revisar, corregir, completar, reposicionar, confirmar o descartar una propuesta de texto, voz o fotografía, incluida la cobertura parcial. | CU-09–CU-12 |
| Colaboración | Aplicar cambio remoto, merge seguro, conflicto semántico, borrado ganador, cascada y undo/redo selectivo. | CU-13–CU-15 |
| Offline y revocación | Encolar, reconectar, aceptar lo anterior a la revocación, rechazar lo posterior y enviar ambigüedades a revisión administrativa. | CU-14–CU-15 |
| Interoperabilidad | Importar, normalizar, exportar y comparar el fixture `Prueba3.xml`. | CU-16–CU-17 |
| Generación | Validar, seleccionar target y perfil/plantilla, generar backends independientes, compilar, iniciar y verificar cada resultado. | CU-18–CU-20 |
| Cliente e IA local | Consumir el contrato desde Flutter y ejecutar la interacción local sin conectividad. | CU-21–CU-22 |
| Demostración | Encadenar el recorrido integral sin cerrar todavía la interacción definitiva de Q9. | CU-23 |

### 2.5 Verificación del Diseño — D5

| Artefacto | Propósito | Estado |
| --- | --- | --- |
| Matriz de verificación | Cómo comprobar arquitectura, datos, contratos, secuencias y generación con evidencia reproducible. | Borrador creado |
| Esquemas JSON versionados | `payload` por familia con `schemaVersion` y política de evolución. | Borrador creado |
| Entorno y fixtures | `Prueba3.xml`, dataset de concurrencia y banco de métricas RNF-11. | Definido en el plan |

#### 2.5.1 Artefactos D5

| Artefacto | Ubicación | Contenido |
| --- | --- | --- |
| Plan de verificación | [`docs/puds/verificacion/01-verificacion.md`](verificacion/01-verificacion.md) | Matriz D1–D4, esquemas JSON v1 de payloads, entorno/fixtures y criterios de cierre hacia D6. |

#### 2.5.2 Decisión de mecanismo de colaboración

La comparación explícita quedó resuelta en [`docs/puds/contratos/02-comparacion-colaboracion.md`](contratos/02-comparacion-colaboracion.md): se adopta **registro de comandos + servidor autoritativo + merge por campos independientes** (A+B) como línea base; **CRDT queda descartado para V1** como mecanismo de convergencia, sin prohibirlo para evolución. Cierra el pendiente del checklist D3 (antes de D4).

## 3. Flujo incremental de Diseño

| Corte | Objetivo | Salida verificable | Habilita |
| --- | --- | --- | --- |
| **D0 — Entrada** | Confirmar alcance, autoridad, trazabilidad, pendientes y plantilla de Diseño. | Este índice, matriz inicial, inventario de artefactos y lista de gaps. | Elaboración de D1. |
| **D1 — Arquitectura** | Definir contexto, despliegue, contenedores, componentes, capas y decisiones estructurales. | Vistas de arquitectura y registro de decisiones. | Diseño de datos y contratos. |
| **D2 — Datos** | Convertir el modelo conceptual en un modelo lógico y físico persistible. | Clases, mapeo, normalización, esquema y migraciones/scripts. | Secuencias y generación. |
| **D3 — Contratos** | Precisar API, comandos, eventos, XMI, IA, generación y errores. | Contratos versionados y alternativas descartadas justificadas. | Diseño de comportamiento. |
| **D4 — Secuencias** | Mostrar colaboraciones normales y excepcionales de los escenarios críticos. | Diagramas de secuencia con trazabilidad y precondiciones. | Diseño de verificación. |
| **D5 — Verificación** | Convertir cada decisión en una comprobación reproducible. | Matriz de evidencias, pruebas de diseño y riesgos actualizados. | Revisión de línea base. |
| **D6 — Cierre** | Revisar consistencia global y preparar la transición a implementación. | Checklist aprobado; gaps explícitos; Q9 permanece en C3. | Implementación del ciclo correspondiente. |

El cierre inicial de D6 (umbral C1) quedó registrado en [cierre/01-cierre-diseno.md](cierre/01-cierre-diseno.md); los umbrales C2 y C3 revalidan según sus evidencias.

### 3.1 Regla de trabajo por corte

Cada corte debe seguir esta secuencia:

1. Leer la entrada canónica y declarar el alcance del corte.
2. Elaborar únicamente los artefactos necesarios para ese corte.
3. Registrar decisiones, alternativas, consecuencias y gaps.
4. Actualizar la trazabilidad.
5. Verificar contra los invariantes y criterios de aceptación.
6. Dejar explícito qué queda habilitado y qué sigue bloqueado.

No se debe avanzar al siguiente corte solo porque exista un diagrama. El artefacto debe tener propósito, entradas, salidas y evidencia asociada.

## 4. Relación con los ciclos

El diseño general establece la línea base común. Luego cada ciclo agrega detalle sin duplicar ni contradecir los artefactos anteriores.

| Ciclo | Casos | Foco de Diseño | Evidencia asociada |
| --- | --- | --- | --- |
| **C1** | CU-01–CU-08 | Núcleo UML, proyectos, acceso, edición manual, validación, disposición, toolbar, historial y undo/redo básico. | Modelo lógico inicial, arquitectura del núcleo, secuencias de edición y validación. |
| **C2** | CU-09–CU-17 | Propuestas de IA, voz/fotografía, colaboración, offline, conflictos, revocación, XMI y round-trip. | Contrato de comandos/eventos, secuencias de conflicto y offline, fixture y plan Q3. |
| **C3** | CU-18–CU-23 | Generación genérica para un dominio nuevo, targets de backend/base de datos independientes, cliente Flutter, runtime móvil local Qwen, despliegue del runtime backend, demostración y eficiencia. | Verificación de generación, ejecución de ambos runtimes, gates de compatibilidad, mediciones disponibles y resolución de Q9. |

La existencia de C1, C2 y C3 ordena la entrega. No reduce el alcance de los requisitos obligatorios.

## 5. Plantilla de trazabilidad

Esta tabla se completará por cada artefacto y por cada decisión relevante:

| ID de Diseño | Artefacto / decisión | RF/RNF | CU | Paquete | Entrada / precondición | Evidencia | Estado |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D-XXX | Pendiente de completar | RF/RNF-XX | CU-XX | PXX | Pendiente | Pendiente | GAP |

### 5.1 Regla de cobertura

Un elemento de Diseño está cubierto cuando:

- referencia al menos un requisito, regla o invariante;
- indica qué caso de uso lo utiliza;
- identifica el paquete o responsabilidad que implementa;
- tiene una prueba, inspección o evidencia prevista;
- no contradice una decisión confirmada;
- declara explícitamente cualquier gap o decisión propuesta.

## 6. Pendientes que no se deben ocultar

| Pendiente | Tratamiento |
| --- | --- |
| Q9: eficiencia, recorrido e interacción del asistente | Mantener abierta hasta C3; diseñar puntos de observación sin fijar todavía el criterio global. |
| Q3: round-trip XMI | Ejecutar como prueba obligatoria y registrar preservaciones, pérdidas y advertencias. |
| Diagramas gráficos de casos de uso | Completar como pendiente documental de Captura/Análisis antes del cierre general de la línea base. |
| Mecanismo concreto de colaboración | **Resuelto en D3/D4**: registro de comandos + merge por campos (A+B); CRDT descartado para V1 en [comparación](contratos/02-comparacion-colaboracion.md). |
| Plataforma concreta del editor principal | Resolver en Diseño con criterios de alcance, interoperabilidad y demostración; Flutter queda confirmado para el cliente y su runtime móvil local de IA. |
| Compatibilidad técnica de IA | La separación backend-local/móvil-local y el uso de Qwen 3.5 0.8B están confirmados. Verificar artefacto, runtime, cuantización, preprocesador de visión, ASR local, presupuesto de RAM/CPU, latencia y dispositivo objetivo; no sustituir Qwen ni acoplar los runtimes. |
| Topología detallada de AWS | Resolver en Diseño manteniendo Floci para desarrollo/emulación y evitando recursos facturables sin aprobación. |

## 7. Condiciones de cierre

### 7.1 Checklist D0

- [x] Captura de requisitos disponible como entrada canónica.
- [x] Análisis disponible como entrada canónica.
- [x] Q9 identificada como única pregunta de producto abierta y diferida a C3.
- [x] Q3 identificada como tarea de prueba, no como pregunta abierta.
- [x] Estructura de Diseño de los modelos consultados incorporada al plan.
- [x] Artefactos de arquitectura, datos, contratos, comportamiento y verificación identificados.
- [x] Relación inicial con C1, C2 y C3 definida.
- [ ] Inventario final de diagramas gráficos de Captura/Análisis revisado.
- [ ] Matriz inicial de trazabilidad completada con los artefactos reales.

### 7.2 Cierre de la línea base de Diseño

Antes de implementar un ciclo, debe estar completo el diseño suficiente para sus casos y deben estar disponibles:

- arquitectura y dependencias del incremento;
- modelo de datos y reglas de integridad aplicables;
- contratos de entrada, salida y error;
- secuencias de escenarios normales y críticos;
- plan de pruebas y evidencias;
- riesgos, decisiones y gaps actualizados;
- trazabilidad desde requisitos hasta verificación.

El cierre de Diseño no implica que Q9 se resuelva antes de C3. Implica que cada incremento puede construirse y comprobarse sin inventar decisiones faltantes.

### 7.3 Acta D6 (umbral inicial C1)

La línea base inicial quedó cerrada en [`docs/puds/cierre/01-cierre-diseno.md`](cierre/01-cierre-diseno.md): artefactos D1–D5 aprobados, checklist completado, pendientes no bloqueantes explícitos (C4/ADR, diagramas CU, evidencia Q3) y umbrales de revalidación C2/C3 definidos.

## 8. Próximo paso

El siguiente corte es **D1 — Diseño de arquitectura**. Debe comenzar con el contexto del sistema y avanzar hacia despliegue, contenedores, componentes y capas, manteniendo separadas las decisiones confirmadas de las propuestas técnicas.
