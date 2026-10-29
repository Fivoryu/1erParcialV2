# Captura de requisitos

> Documento canónico de requisitos del diagramador colaborativo de clases UML. Aquí se definen la evidencia, el alcance, las decisiones confirmadas, los catálogos, los casos de uso y la única pregunta de producto que permanece abierta.

[Índice del PUDS](../esqueleto-proceso-desarrollo-parcial-2.md) · **Anterior:** [Índice](../esqueleto-proceso-desarrollo-parcial-2.md) · **Siguiente:** [Análisis](02-analisis.md)

## Ruta rápida

| Destino | Contenido |
| --- | --- |
| [Evidencia y autoridad](#1-evidencia-y-autoridad) | Cómo interpretar fuentes y decisiones del proyecto. |
| [Objetivo y alcance](#2-objetivo-y-alcance) | Producto, límites, decisiones confirmadas y supuestos. |
| [Captura de requisitos](#3-captura-de-requisitos) | Actores, reglas y catálogos canónicos. |
| [Ciclo 1](#ciclo-1--fundación-del-proyecto-y-edición-manual-uml) | CU-01–CU-08: fundación y edición manual UML. |
| [Ciclo 2](#ciclo-2--edición-asistida-colaboración-e-interoperabilidad) | CU-09–CU-17: asistencia, colaboración e XMI. |
| [Ciclo 3](#ciclo-3--generación-determinista-clientes-y-demostración-integral) | CU-18–CU-23: generación, clientes y demostración. |
| [Decisiones y pendiente](#6-registro-de-decisiones-y-pendiente-restante) | Q1–Q8 y Q10 cerradas; Q3 como prueba y Q9 como único pendiente de producto. |
| [Cierre](#7-cierre-de-captura-de-requisitos) | Checklist para cerrar la captura antes de diseño. |

## 1. Evidencia y autoridad

### 1.1 Clasificación de la evidencia

| Etiqueta | Uso en este documento |
| --- | --- |
| **Hecho de fuente** | Información explícita en el enunciado o en un modelo de PUDS consultado. |
| **Decisión confirmada** | Decisión de alcance o de tecnología adoptada por el equipo para este proyecto. Las respuestas del equipo se registran aquí como decisiones del proyecto, no como exigencias textuales del docente. |
| **Análisis derivado** | Organización, relación o propuesta técnica deducida para hacer verificables los requisitos. Debe validarse antes de diseño. |
| **Supuesto operativo** | Condición necesaria para avanzar provisionalmente, todavía no confirmada. |
| **Pendiente** | Pregunta cuya respuesta cambia el alcance, la aceptación o la solución. No se resuelve por inferencia. |

### 1.2 Fuentes y autoridad

| ID | Fuente | Uso y límite |
| --- | --- | --- |
| S1 | `Enunciado Parcial I.md` | Fuente principal para el problema, el producto, las funcionalidades mínimas, la demostración, XMI, IA, cliente móvil y stack base. |
| S2 | `docs/modelos_puds/2do Parcial.pdf`, contenido impreso 38–100 | Modelo de estructura PUDS: actores, casos de uso, ciclos, especificaciones, paquetes y análisis de casos. Sus requisitos corresponden a otro sistema y no se trasladan al producto actual. |
| S3 | `docs/modelos_puds/documentacion.docx` | Ejemplo de organización PUDS de una plataforma de emergencias vehiculares. Se usa solo como referencia de forma; no es fuente de requisitos del diagramador. |
| S4 | `docs/analisis-enunciado-parcial-1.md` | Síntesis previa del alcance y de los riesgos. Se conserva como apoyo, pero las decisiones actuales se distinguen de los hechos del enunciado. |

**Anclas de S1 utilizadas en la trazabilidad:**

| ID | Contenido resumido de la fuente |
| --- | --- |
| S1-01 | Herramienta colaborativa para apoyar el trabajo de desarrollo y el diseño de datos. |
| S1-02 | Modelo conceptual de clases UML y mención contextual del modelo entidad-relación. |
| S1-03 | Uso de una herramienta como Enterprise Architect y trabajo colaborativo de diseñadores. |
| S1-04 | IA para crear, mover, editar, borrar y relacionar elementos mediante comandos o texto. |
| S1-05 | Coexistencia de edición manual y edición mediante IA sobre el diagrama. |
| S1-06 | Generación del backend completo a partir del diagrama, con configuraciones. |
| S1-07 | Presentación con un dominio proporcionado por el docente y desarrollo de una aplicación pequeña. |
| S1-08 | Envío de comandos de voz. |
| S1-09 | Edición manual del modelo. |
| S1-10 | Entrada mediante una fotografía. |
| S1-11 | Importación y exportación mediante XMI con Enterprise Architect. |
| S1-12 | Cliente Flutter o React Native, uso de IA en la aplicación y operación sin internet mediante IA local. |
| S1-13 | Stack base: Spring Boot con cuatro capas mínimas y PostgreSQL. |

> **Corrección de trazabilidad:** los actores, casos de uso y paquetes de salud, workflow, documentos y emergencias que aparecen en S2 o S3 no describen el producto de este proyecto. Se reemplazan aquí por el producto definido en S1 y por las decisiones confirmadas.

## 2. Objetivo y alcance

### 2.1 Objetivo del proceso

Transformar el enunciado en un conjunto de requisitos identificables, verificables y trazables, y en un análisis lógico que permita comenzar el diseño sin mezclar decisiones de implementación con obligaciones del producto.

Al cerrar estos dos flujos, otra persona debe poder responder:

- qué producto se construye y qué problema del contexto queda fuera;
- quién usa el diagramador y qué sistemas colaboran con él;
- qué funcionalidades son obligatorias y cómo se comprueban;
- cómo se relacionan los requisitos con casos de uso y paquetes lógicos;
- qué decisiones son propuestas técnicas y cuáles siguen abiertas.

### 2.2 Producto y límites

**Hecho de fuente (S1):** el problema presenta una empresa distribuida que debe organizar el diseño de un sistema grande en un plazo reducido. La respuesta propuesta es una herramienta colaborativa para apoyar el trabajo de desarrollo, especialmente el diseño de datos y modelos.

**Decisión confirmada:** el producto de este parcial es una plataforma colaborativa de modelado centrada en diagramas de clases UML 2.5. No se construye la plataforma nacional de salud descrita como contexto.

**Límites confirmados:**

- El editor central es el diagrama de clases UML 2.5.
- El modelo entidad-relación no se implementa como un segundo editor independiente en esta línea base; se considera contexto de diseño de datos.
- El modelo semántico es la fuente de verdad para la edición manual, la IA, la colaboración, XMI y la generación de código.
- La disposición visual del lienzo se conserva separada de la semántica UML.
- El producto no reemplaza todas las capacidades de Enterprise Architect ni cubre todos los diagramas UML.
- El backend generado debe ser independiente del framework del cliente.

### 2.3 Decisiones confirmadas del proyecto

Las respuestas consolidadas del equipo son decisiones confirmadas del proyecto. Se distinguen de los hechos del enunciado y de las propuestas que todavía deberán detallarse en diseño.

| Tema | Decisión adoptada | Clasificación |
| --- | --- | --- |
| Alcance funcional | Todas las funcionalidades mínimas de S1 son obligatorias; los ciclos solo ordenan la entrega. | Decisión confirmada del proyecto |
| Línea base UML | El modelo interno soporta el subconjunto UML 2.5 acordado: clase, clase abstracta, interfaz, enumeración, paquete; atributos, operaciones, parámetros/tipos, valores predeterminados, visibilidad y miembros estáticos/abstractos; asociación, agregación, composición, generalización, realización de interfaz y dependencia; multiplicidades, roles, navegabilidad, notas/restricciones, estereotipos requeridos e identificadores estables. La disposición visual queda separada de la semántica. No se afirma cobertura completa de UML 2.5. | Decisión confirmada del proyecto |
| Enterprise Architect/XMI | El objetivo validado es Enterprise Architect 15.0.1514, build 1514. El contrato primario es el dialecto observado en `Prueba3.xml`: XMI 2.1/UML 2.1, extensiones EA 6.5 e información de diagrama. `Prueba1` (XMI 1.1/UML 1.3) y `Prueba2` (EA Native XML) son referencias secundarias, no perfiles obligatorios de V1. | Decisión confirmada del proyecto |
| Prueba de interoperabilidad | Q3 queda cerrada como tarea de prueba obligatoria: ejecutar EA → aplicación → XMI → EA con el fixture copiado, preservando identificadores estables, semántica soportada, relaciones y geometría, y reportando toda pérdida. | Tarea de prueba confirmada |
| Backend completo | La generación incluye entidades y relaciones JPA; repositorios; aplicación/servicios y operaciones de dominio generadas que el modelo/perfil soporte; controladores REST; DTO y mapeadores; validación y manejo consistente de errores; PostgreSQL y migraciones; OpenAPI; pruebas unitarias e integración; Docker Compose y ejecución local reproducible; compilación, inicio, health check y verificación del contrato; autenticación, autorización y auditoría; y artefactos/configuración necesarios para un despliegue real. | Decisión confirmada del proyecto |
| Cuatro capas mínimas | La línea base lógica tiene: (1) dominio/entidades, (2) repositorios/persistencia, (3) servicios/aplicación y (4) controladores/API. DTO, mapeadores, seguridad, migraciones e infraestructura son componentes de apoyo, salvo que diseño los ubique de otra manera. | Decisión confirmada del proyecto |
| Colaboración | El contrato es tecnológico-abierto. La dirección de análisis puede evaluar comandos locales optimistas con cola offline, canal en tiempo real conectado, registro inmutable de operaciones, autoridad del servidor, validación UML y resolución semántica. CRDT no es un requisito textual ni una decisión de biblioteca o algoritmo. | Decisión confirmada de comportamiento; dirección técnica para análisis/diseño |
| Arquitectura de IA local | Existen dos runtimes independientes que usan Qwen 3.5 0.8B: un runtime local del backend, servido en Docker, y un runtime local/en dispositivo para Flutter. El backend puede desplegarse como contenedor en AWS; Floci es el emulador local de AWS. No comparten proceso ni memoria mutable. Qwen es obligatorio para interpretar texto y fotografías de diagramas de clase; la compatibilidad exacta de artefacto, runtime, cuantización y preprocesador es una compuerta técnica de validación. | Decisión confirmada del proyecto |
| Fotografía | Una fotografía produce una propuesta editable del contenido detectado, que puede ser parcial y declara su cobertura. Incluye clases, atributos, relaciones y disposición aproximada cuando sean observables, con confianza por elemento; los faltantes, la información ilegible y las ambigüedades se hacen explícitos. La propuesta siempre se revisa y valida antes de entrar al modelo canónico; el usuario puede completar y reposicionar el diagrama manualmente. No hay inferencia ni sobrescritura silenciosa. | Decisión confirmada del proyecto |
| Demostración y despliegue | El dominio lo proporciona el examen y la aplicación debe mostrar importación/exportación desde la propia aplicación. El despliegue real se prepara antes de la defensa: AWS es el objetivo primario y Floci se usa para desarrollo/emulación local. El runtime local del backend se ejecuta en Docker y sus pesos/caché deben poder configurarse en D:. No se crean recursos facturables en esta tarea ni la defensa de aproximadamente tres minutos implica desplegar durante el recorrido. | Decisión confirmada del proyecto |
| Asistente, eficiencia y recorrido de tres minutos | Es provisional que Flutter actúe como asistente capaz de poblar o proponer valores a partir del diagrama/modelo generado. Q9 permanece abierta y se resolverá en el Ciclo 3, cuando exista evidencia para evaluar la eficiencia de la aplicación y fijar la secuencia exacta de tres minutos y la interacción del asistente. Hasta entonces no se agregan métricas globales distintas de las ya definidas en RNF-11. | Pregunta abierta diferida al Ciclo 3 |

### 2.4 Fixture canónico de interoperabilidad

El archivo utilizado para la prueba de Q3 se conserva sin re-encoding en el repositorio actual.

| Campo | Valor |
| --- | --- |
| Origen | `D:/Universidad/Proyectos/2doSemestre2026/sw1/proyecto_final/docs/diagramas/Prueba3.xml` |
| Copia canónica | [`docs/fixtures/xmi/Prueba3.xml`](../fixtures/xmi/Prueba3.xml) |
| Tamaño | 91.440 bytes |
| SHA-256 | `336d13b1ae87da5643dc74903e9787899aaeb3896eb2ca30d5c5a74a2cd0dab2` |
| Uso | Fixture primario para importación, exportación y round-trip EA → aplicación → XMI → EA. |

### 2.5 Supuestos operativos no bloqueantes

La arquitectura local de IA, la separación de runtimes y la frontera Docker/D:/AWS/Floci son decisiones confirmadas. Permanecen abiertos para diseño de implementación el proveedor concreto de ASR, los nombres de recursos AWS, los protocolos de autenticación, el mecanismo de conflictos y la forma exacta de empaquetar/ejecutar Qwen. La compatibilidad entre artefacto, runtime, cuantización y preprocesador se valida técnicamente; no es una pregunta de producto.

## 3. Captura de requisitos

### 3.1 Propósito

Identificar actores, requisitos funcionales y de calidad, casos de uso, dependencias, criterios de aceptación iniciales y preguntas abiertas. El catálogo se descompone en unidades asignables y cada especificación mantiene un formato uniforme para revisar comportamiento observable sin adelantar el diseño físico.

### 3.2 Actores y sistemas externos

| ID | Actor | Responsabilidad o interacción |
| --- | --- | --- |
| A1 | Diseñador | Crea y modifica el modelo de clases, manualmente o mediante entradas asistidas. |
| A2 | Colaborador | Trabaja sobre el mismo proyecto desde otra sesión o ubicación y recibe o produce cambios. |
| A3 | Administrador del proyecto | Administra proyectos, integrantes y permisos básicos. |
| A4 | Asistente de IA | Interpreta instrucciones, voz o imágenes y propone operaciones sobre el modelo. Puede ejecutarse localmente cuando el requisito lo exige. |
| A5 | Enterprise Architect | Sistema externo con el que se intercambian modelos mediante el dialecto XMI acordado. |
| A6 | Cliente web o móvil | Consume el backend generado para demostrar un flujo del dominio modelado. |
| A7 | Generador de código | Subsistema que transforma un modelo validado en un backend y sus artefactos de apoyo. |
| A8 | Docente/evaluador | Proporciona o selecciona el dominio de la demostración y verifica el flujo integral. No es un actor operativo permanente del producto. |

> **Análisis derivado:** A4, A5 y A7 pueden representarse como actores de apoyo en los diagramas de casos de uso, aunque algunos se implementen como subsistemas internos o adaptadores. La clasificación final debe conservar la diferencia entre usuario, sistema externo y componente interno.

### 3.3 Reglas transversales del producto

**Clasificación:** decisiones confirmadas y análisis derivado. Estas reglas atraviesan varios casos de uso y se usan para detectar contradicciones; no son requisitos adicionales independientes del enunciado:

1. Una modificación válida se expresa como una operación sobre el modelo canónico; ningún adaptador modifica directamente la base de datos o el lienzo.
2. Toda operación pasa por validación UML y por las reglas de permisos y consistencia correspondientes.
3. Toda propuesta de IA, voz o fotografía es revisable antes de aplicarse; su cobertura, información faltante, incertidumbre y posibles efectos se muestran explícitamente.
4. El historial identifica el autor, la operación y la versión del modelo cuando la colaboración o la recuperación lo requieran.
5. El generador solo trabaja sobre una versión de modelo validada y permanece desacoplado de ambos runtimes de IA.
6. La semántica UML y la disposición visual no se sustituyen entre sí.
7. La generación de código y los contratos API no incluyen dependencias obligatorias de un cliente específico.
8. Los dos runtimes de IA son independientes: el local del backend corre en Docker y el local/en dispositivo de Flutter no comparte proceso ni memoria mutable.
9. La voz sigue explícitamente el límite `audio → transcripción ASR local → interpretación textual con Qwen → propuesta estructurada`.
10. Pesos, cachés, secretos y estado de runtime no entran en imágenes destinadas a exportación, volúmenes destinados a exportación, propuestas, logs, hashes ni ZIPs.

### 3.4 Requisitos funcionales obligatorios

Los criterios de la última columna son criterios iniciales de aceptación. Las decisiones confirmadas del proyecto ya están incorporadas; Q9 es la única pregunta de producto que puede cambiar el recorrido final de demostración.

| ID | Requisito | Criterio de aceptación inicial | Fuente / trazabilidad |
| --- | --- | --- | --- |
| RF-01 | Gestionar proyectos de modelado: crear, abrir, guardar y recuperar. | Se crea un proyecto, se guarda, se cierra y se recupera sin perder el modelo semántico ni su disposición visual. | S1-01; análisis derivado; CU-01, CU-02, CU-23 |
| RF-02 | Crear y validar el subconjunto UML 2.5 acordado. | El sistema representa clases, clases abstractas, interfaces, enumeraciones, paquetes, características, relaciones, multiplicidades, roles, navegabilidad, notas/restricciones, estereotipos requeridos e identificadores estables; rechaza operaciones fuera de la línea base. No se afirma cobertura completa de UML 2.5. | S1-02, S1-03; decisión confirmada; CU-04, CU-05, CU-06, CU-08, CU-16, CU-23 |
| RF-03 | Editar el diagrama manualmente. | El diseñador puede crear, mover, editar, duplicar y eliminar elementos desde la interfaz; cada cambio actualiza el modelo canónico o la disposición visual correspondiente. | S1-04, S1-09; CU-04, CU-05, CU-06, CU-07, CU-23 |
| RF-04 | Trabajar colaborativamente sobre el mismo proyecto. | Dos o más sesiones autorizadas observan operaciones válidas dentro de los SLO de colaboración; los conflictos, rechazos y operaciones offline no se pierden silenciosamente. | S1-01, S1-03; CU-03, CU-13, CU-14, CU-15, CU-23 |
| RF-05 | Editar mediante IA conversacional o texto. | Instrucciones de lenguaje natural, sin exigir una sintaxis rígida, como crear, mover, editar, eliminar o relacionar elementos se interpretan con Qwen 3.5 0.8B en el runtime local seleccionado y producen una propuesta sobre el modelo existente; solo se modifican después de validarse y, cuando corresponde, confirmarse. | S1-04, S1-05; decisión confirmada; CU-09, CU-10, CU-23 |
| RF-06 | Mantener un único modelo para edición manual y asistida. | Una modificación manual puede continuar mediante IA y una modificación asistida puede continuar manualmente sin crear representaciones divergentes. | S1-04, S1-05; CU-04, CU-05, CU-06, CU-07, CU-08, CU-09, CU-10, CU-11, CU-12, CU-14, CU-15, CU-16, CU-17, CU-23 |
| RF-07 | Recibir comandos de voz. | El flujo es explícito: audio → transcripción ASR local → interpretación textual con Qwen → propuesta estructurada. La propuesta atraviesa el mismo flujo de revisión, validación y aplicación que una instrucción textual. | S1-08; decisión confirmada; CU-11, CU-23 |
| RF-08 | Interpretar una fotografía de un modelo. | Qwen 3.5 0.8B interpreta la fotografía mediante el runtime local seleccionado y produce una propuesta editable y revisable con clases, atributos y tipos, operaciones, relaciones y multiplicidades, confianza por elemento, elementos faltantes o ambiguos, cobertura explícita y disposición aproximada cuando sea observable. El usuario puede completar y reposicionar el diagrama; ningún elemento pasa al modelo canónico sin revisión humana, validación UML y confirmación. La compatibilidad exacta del artefacto visual, runtime, cuantización y preprocesador es una compuerta técnica de verificación. | S1-10; decisión confirmada; CU-12, CU-23 |
| RF-09 | Importar y exportar XMI para Enterprise Architect. | El sistema intercambia el contrato primario de EA 15.0.1514 build 1514 observado en `Prueba3.xml`, preservando el subconjunto soportado, identificadores, relaciones y geometría representable, y comunicando toda pérdida. | S1-03, S1-11; decisión confirmada; CU-16, CU-17, CU-23 |
| RF-10 | Generar un backend a partir del diagrama. | Con un modelo y perfil válidos, se genera un backend completo según la línea base del proyecto, que compila, inicia y expone las operaciones soportadas para el dominio modelado. | S1-06; decisión confirmada; CU-19, CU-20, CU-23 |
| RF-11 | Generar configuración y estructura completa de backend. | El resultado incluye JPA, repositorios, servicios y operaciones de dominio soportadas, REST, DTO/mapeadores, validación, errores, PostgreSQL, migraciones, OpenAPI, pruebas unitarias e integración, Docker Compose, ejecución reproducible, health/contrato, autenticación, autorización, auditoría y artefactos de despliegue, organizados en las cuatro capas mínimas y componentes de apoyo. | S1-13; decisión confirmada; CU-18, CU-19, CU-20, CU-23 |
| RF-12 | Permitir una demostración con un dominio proporcionado por el examen. | Ante el dominio proporcionado durante el examen, el equipo puede modelar una primera versión, generar su backend y mostrar un flujo sin depender de clases de dominio preparadas de antemano. | S1-07; decisión confirmada; CU-01, CU-04, CU-08, CU-16, CU-18, CU-19, CU-20, CU-21, CU-23 |
| RF-13 | Consumir el backend desde una aplicación cliente. | El cliente inicial en Flutter ejecuta al menos un flujo completo contra el backend generado sin modificar su lógica. | S1-12; decisión confirmada; CU-21, CU-23 |
| RF-14 | Incorporar IA en la aplicación demostrativa. | La aplicación cliente usa IA en una interacción relevante para el dominio y el resultado es observable durante el recorrido definido en Q9. | S1-12; CU-22, CU-23 |
| RF-15 | Mantener disponible la IA requerida sin conexión. | El cliente Flutter ejecuta la interacción offline definida con un recurso local, sin servicio remoto durante la desconexión. | S1-12; decisión confirmada; CU-22, CU-23 |
| RF-16 | Proporcionar una barra de herramientas y atajos de teclado para el trabajo del diseñador. | La interfaz ofrece acciones descubribles para selección/pan, elementos y relaciones UML soportados, eliminar/duplicar, deshacer/rehacer, zoom/ajuste, importar/exportar, entradas de IA y estado de colaboración/conflicto. Como mínimo, documenta Ctrl+Z, Ctrl+Y o Ctrl+Shift+Z, Delete, Ctrl+C/Ctrl+V/Ctrl+D para copiar/pegar/duplicar, Ctrl+S y un atajo o control equivalente de zoom; muestra el estado de guardado/sincronización. Las remapeaciones de macOS y la personalización quedan para diseño. | Decisión confirmada del proyecto; S1-04, S1-09, S1-11; CU-04, CU-05, CU-06, CU-07, CU-09, CU-10, CU-14, CU-15, CU-16, CU-17, CU-23 |

### 3.5 Requisitos de calidad y restricciones

| ID | Atributo | Criterio inicial | Fuente / clasificación |
| --- | --- | --- | --- |
| RNF-01 | Consistencia | Todos los clientes y adaptadores operan sobre una versión válida del modelo canónico; las alternativas incompatibles permanecen fuera de él. | Decisión confirmada; análisis derivado |
| RNF-02 | Trazabilidad y auditoría | Cada operación colaborativa, asistida, offline o de resolución conserva operación, autor, identificadores objetivo, revisión base/causal y resultado suficiente para auditarla. | Decisión confirmada; análisis derivado |
| RNF-03 | Determinismo | El mismo modelo, perfil y versión de plantillas generan el mismo conjunto de artefactos, salvo metadatos explícitamente excluidos. | Decisión confirmada |
| RNF-04 | Interoperabilidad | El intercambio declara EA 15.0.1514 build 1514, XMI 2.1/UML 2.1, extensiones EA 6.5, información de diagrama y elementos soportados; las pérdidas no se ocultan. | Decisión confirmada; análisis derivado |
| RNF-05 | Seguridad | Un usuario solo lee, modifica, publica, exporta o genera sobre proyectos y operaciones para los que tiene autorización; los rechazos se auditan. | Decisión confirmada; análisis derivado |
| RNF-06 | Validación de entradas | Las operaciones manuales, textuales, de voz, fotográficas y XMI se validan antes de modificar el modelo canónico; un modelo inválido no se publica ni genera. | Decisión confirmada |
| RNF-07 | Independencia del cliente | El contrato OpenAPI y el backend generado no dependen de React, TypeScript o Flutter. | Decisión confirmada |
| RNF-08 | Disponibilidad local | La interacción de IA definida para modo offline funciona en el cliente Flutter sin servicio remoto durante la desconexión y conserva entradas/resultados recuperables. | S1-12; decisión confirmada |
| RNF-09 | Demostrabilidad | El flujo integral puede ensayarse en aproximadamente tres minutos; la restricción organiza la presentación y no reduce las capacidades obligatorias. | Restricción confirmada; Q9 abierta solo para la secuencia exacta |
| RNF-10 | Despliegue reproducible | AWS es el objetivo real primario preparado antes de la defensa; Floci permite desarrollo/emulación local. Esta tarea no crea recursos facturables. | Decisión confirmada del proyecto |
| RNF-11 | Colaboración en tiempo real medible | La respuesta local es ≤100 ms; presencia, cursor y movimiento remoto alcanzan p95 ≤200 ms; un comando UML remoto es visible en p95 ≤300 ms; la confirmación durable alcanza p95 ≤1 s; el arrastre se coalescea a 10–30 actualizaciones/s; sobre 500 ms se muestra “sincronizando” y sobre 1 s sostenido se declara servicio degradado. Se registran p50, p95, p99 y jitter por métrica. | Decisión confirmada de comportamiento; análisis derivado |

### 3.6 Catálogo incremental de casos de uso

El catálogo supersede explícitamente el catálogo anterior: conserva el alcance funcional, pero separa responsabilidades que antes estaban mezcladas para que puedan asignarse, demostrarse y revisarse de manera independiente. Se definen **23 casos de uso en tres ciclos**. La prioridad ordena el trabajo; no convierte ningún requisito obligatorio en opcional.

#### Ciclo 1 — Fundación del proyecto y edición manual UML

| Campo | Definición |
| --- | --- |
| Objetivo | Entregar un proyecto recuperable, un modelo canónico y una edición manual verificable sobre el subconjunto UML 2.5 confirmado. |
| Alcance incluido | Creación y recuperación de proyectos, permisos básicos, clasificadores, características, relaciones, disposición visual y validación semántica. |
| Resultado demostrable | Una persona crea un proyecto, modela un dominio nuevo con clases y relaciones, reorganiza el lienzo, guarda, cierra, recupera y recibe errores de validación explicables. |
| Dependencias de entrada | S1-01, S1-02 y S1-09; la línea base UML confirmada y la persistencia conceptual del proyecto. |
| Criterios de salida y aceptación | [ ] Los 8 casos del ciclo tienen flujo y resultado observable. [ ] La semántica y la disposición se recuperan por separado. [ ] Las operaciones inválidas no alteran el modelo. [ ] El modelo recuperado conserva identificadores estables y una versión válida o un diagnóstico. |

| Orden | ID | Caso de uso | Actor iniciador | Dependencias | Prioridad |
| --- | --- | --- | --- | --- | --- |
| 1 | CU-01 | Crear proyecto de modelado | A1 o A3 | — | P0 habilitante |
| 2 | CU-02 | Abrir, guardar y recuperar proyecto | A1 o A3 | CU-01 | P0 habilitante |
| 3 | CU-03 | Configurar integrantes y permisos básicos | A3 | CU-01 | P0 habilitante |
| 4 | CU-04 | Gestionar clasificadores UML | A1 | CU-01 | P0 habilitante |
| 5 | CU-05 | Gestionar características UML | A1 | CU-04 | P0 habilitante |
| 6 | CU-06 | Gestionar relaciones UML | A1 | CU-04, CU-05 | P0 habilitante |
| 7 | CU-07 | Organizar la disposición visual | A1 | CU-04, CU-06 | P0 habilitante |
| 8 | CU-08 | Validar el modelo UML | A1 | CU-04, CU-05, CU-06, CU-07 | P0 habilitante |

#### Ciclo 2 — Edición asistida, colaboración e interoperabilidad

| Campo | Definición |
| --- | --- |
| Objetivo | Extender el mismo modelo canónico con entradas textuales, de voz, fotográficas, sesiones colaborativas y archivos XMI. |
| Alcance incluido | Interpretación y aplicación revisable de propuestas, invitaciones, sincronización, reconciliación offline, importación y exportación XMI. El contrato de comportamiento es tecnológico-abierto; CRDT solo puede evaluarse como dirección técnica. |
| Resultado demostrable | Dos participantes autorizados editan el proyecto; una instrucción textual, una voz y una fotografía producen propuestas revisables —la fotográfica puede ser parcial y declara faltantes y confianza—; el usuario puede completarla y reposicionarla manualmente; un cambio offline se reconcilia; el modelo se importa o exporta con un reporte de compatibilidad. |
| Dependencias de entrada | Ciclo 1 completo; línea base UML, perfil XMI primario, capacidad de voz/visión y política de colaboración confirmados. |
| Criterios de salida y aceptación | [ ] Todas las entradas convergen en comandos sobre el modelo único. [ ] La propuesta fotográfica declara cobertura, confianza, faltantes y advertencias. [ ] El usuario puede completar y reposicionar manualmente una propuesta parcial. [ ] La pérdida de conexión no elimina cambios silenciosamente. [ ] Las invitaciones y permisos se verifican. [ ] Importación y exportación declaran versión, dialecto, soporte y pérdidas. |

| Orden | ID | Caso de uso | Actor iniciador | Dependencias | Prioridad |
| --- | --- | --- | --- | --- | --- |
| 9 | CU-09 | Interpretar instrucciones textuales con IA | A1 o A2 | CU-08 | P1 extensión obligatoria |
| 10 | CU-10 | Revisar y aplicar una propuesta asistida | A1 o A2 | CU-08, CU-09 | P1 extensión obligatoria |
| 11 | CU-11 | Procesar comandos de voz | A1 o A2 | CU-09, CU-10 | P1 extensión obligatoria |
| 12 | CU-12 | Interpretar fotografía de un modelo | A1 o A2 | CU-08, CU-10 | P1 extensión obligatoria |
| 13 | CU-13 | Invitar y administrar participantes | A3 | CU-01, CU-03 | P1 extensión obligatoria |
| 14 | CU-14 | Sincronizar cambios en colaboración | A1 o A2 | CU-08, CU-13 | P1 extensión obligatoria |
| 15 | CU-15 | Reconciliar cambios offline y conflictos | A1 o A2 | CU-08, CU-14 | P1 extensión obligatoria |
| 16 | CU-16 | Importar modelo XMI | A1 | CU-01, CU-08 | P1 extensión obligatoria |
| 17 | CU-17 | Exportar modelo XMI | A1 | CU-08 | P1 extensión obligatoria |

#### Ciclo 3 — Generación determinista, clientes y demostración integral

| Campo | Definición |
| --- | --- |
| Objetivo | Transformar una versión validada en un backend verificable, consumirlo desde un cliente y demostrar IA local y el flujo completo sobre un dominio nuevo. |
| Alcance incluido | Perfil de generación, generación determinista, backend completo, verificación de compilación/inicio/health/contrato, cliente Flutter, IA offline en Flutter, despliegue preparado, evaluación de la eficiencia integral de la aplicación y demostración. |
| Resultado demostrable | Con el dominio elegido por el evaluador, el equipo selecciona un perfil, genera dos veces resultados equivalentes, verifica compilación/inicio/contrato, ejecuta un flujo desde el cliente, muestra la interacción local sin conexión y conserva evidencia para evaluar la eficiencia de la aplicación. |
| Dependencias de entrada | Ciclos 1 y 2 completos para el recorrido integral; CU-08 habilita la generación y CU-10, CU-15, CU-16 y CU-17 aportan variantes demostrables. Q9 se revisa en este ciclo con la evidencia de eficiencia disponible para definir la secuencia exacta y la interacción del asistente. |
| Criterios de salida y aceptación | [ ] El mismo modelo, perfil y versión de plantillas producen artefactos equivalentes. [ ] El backend generado compila, inicia y expone el contrato acordado. [ ] El cliente no requiere modificar la lógica generada. [ ] La IA definida para offline funciona sin servicio remoto. [ ] Se obtiene evidencia suficiente para acordar en Q9 cómo evaluar la eficiencia integral. [ ] El recorrido integral es ensayable en aproximadamente tres minutos. |

| Orden | ID | Caso de uso | Actor iniciador | Dependencias | Prioridad |
| --- | --- | --- | --- | --- | --- |
| 18 | CU-18 | Definir y seleccionar perfil de generación | A1 | CU-08 | P2 cierre obligatorio |
| 19 | CU-19 | Generar backend determinista | A1 | CU-08, CU-18 | P2 cierre obligatorio |
| 20 | CU-20 | Verificar backend generado | A1 | CU-19 | P2 cierre obligatorio |
| 21 | CU-21 | Consumir backend desde cliente | A6 | CU-20 | P2 cierre obligatorio |
| 22 | CU-22 | Usar IA en cliente sin conexión | A6 | CU-15, CU-21 | P2 cierre obligatorio |
| 23 | CU-23 | Ejecutar demostración integral con dominio nuevo | A8 | CU-02, CU-08, CU-10, CU-11, CU-12, CU-14, CU-16, CU-17, CU-18, CU-19, CU-20, CU-21, CU-22 | P2 cierre obligatorio |

> **Regla de dependencia:** una dependencia es una capacidad habilitante, no una asignación exclusiva. Los casos se ordenan sin ciclos: las dependencias apuntan a un caso anterior o, cuando se indique expresamente, a un caso de un ciclo anterior. Las decisiones abiertas no se convierten en dependencias resueltas por inferencia.

### 3.7 Especificaciones detalladas de casos de uso

Las fichas siguen siempre el mismo orden para facilitar revisión y asignación: identidad, propósito, actores, disparador, precondiciones, flujo principal, alternativas/excepciones, postcondiciones, reglas, aceptación y trazabilidad. `A` identifica un flujo alternativo y `E` una excepción; las etiquetas son estables para futuras revisiones.

#### Ciclo 1 — Fundación del proyecto y edición manual UML

#### CU-01 — Crear proyecto de modelado

- **Ciclo y prioridad:** C1 · P0 habilitante.
- **Propósito:** iniciar un espacio de trabajo con identidad propia para un modelo y su disposición visual.
- **Actor iniciador y actores de apoyo:** inicia A1 Diseñador o A3 Administrador del proyecto; apoyan P01 Gestión de proyectos y P03 Comandos, historial y versionado.
- **Disparador:** el usuario solicita crear un proyecto y proporciona un nombre o identificador.
- **Precondiciones:** el usuario puede acceder al diagramador; el identificador no está ocupado en el ámbito visible.
- **Flujo principal:** 1. El usuario solicita crear el proyecto. 2. El sistema valida nombre y permisos. 3. El sistema crea un modelo semántico vacío, una vista inicial y una primera versión. 4. El sistema asigna identificadores estables y muestra el proyecto listo para editar. 5. El sistema deja registrado al creador como participante inicial.
- **Flujos alternativos y excepciones:** **A1.** Si el usuario elige una plantilla permitida, el sistema inicializa solo los elementos declarados por esa plantilla. **E1.** Si el nombre ya existe, el sistema rechaza la creación y conserva los proyectos existentes. **E2.** Si falla la persistencia, el sistema informa el fallo y no presenta el proyecto como creado.
- **Postcondición de éxito:** existe un proyecto identificable, recuperable y editable con modelo y disposición inicial.
- **Garantía mínima ante falla:** no se crea un proyecto parcialmente accesible ni se pierde otro proyecto.
- **Reglas de negocio y semánticas:** cada proyecto tiene una identidad estable; la vista inicial no sustituye al modelo semántico; crear el proyecto no implica que el usuario pueda invitar a terceros.
- **Resultado de aceptación observable:** al crear un proyecto nuevo, este aparece en la lista del usuario, abre un lienzo vacío y puede pasar a CU-02.
- **Trazabilidad:** RF-01, RF-12; RNF-01, RNF-05, RNF-06; P01, P03; S1-01, S1-03; análisis derivado.

#### CU-02 — Abrir, guardar y recuperar proyecto

- **Ciclo y prioridad:** C1 · P0 habilitante.
- **Propósito:** conservar y restaurar el modelo semántico, la disposición visual y la versión de un proyecto existente.
- **Actor iniciador y actores de apoyo:** inicia A1 o A3; apoyan P01 y P03.
- **Disparador:** el usuario selecciona un proyecto, guarda cambios, cierra la sesión o solicita recuperar una versión guardada.
- **Precondiciones:** el proyecto existe; el usuario tiene permiso de lectura y, para guardar, permiso de escritura.
- **Flujo principal:** 1. El usuario selecciona el proyecto. 2. El sistema carga modelo, vista, configuración y versión. 3. El usuario edita o solicita guardar. 4. El sistema valida el estado y persiste una versión recuperable. 5. Al cerrar y abrir nuevamente, el sistema restaura el mismo estado observable.
- **Flujos alternativos y excepciones:** **A1.** Si hay varias versiones recuperables, el usuario selecciona una y el sistema la presenta como estado de trabajo. **E1.** Si el formato persistido es inválido, el sistema informa el diagnóstico y no lo aplica. **E2.** Si existe un conflicto de guardado, el sistema conserva la versión previa y solicita una decisión explícita.
- **Postcondición de éxito:** el proyecto abierto refleja la versión seleccionada, con semántica y disposición consistentes.
- **Garantía mínima ante falla:** la última versión válida permanece recuperable; ningún guardado fallido se presenta como exitoso.
- **Reglas de negocio y semánticas:** recuperar una versión no debe cambiar sus identificadores estables; guardar una vista no puede modificar relaciones UML; el historial de versiones es parte del estado revisable.
- **Resultado de aceptación observable:** después de cerrar y volver a abrir, una clase, una relación, su posición y la versión elegida se conservan.
- **Trazabilidad:** RF-01; RNF-01, RNF-02, RNF-05, RNF-06; P01, P03; S1-01, S1-03; análisis derivado.

#### CU-03 — Configurar integrantes y permisos básicos

- **Ciclo y prioridad:** C1 · P0 habilitante.
- **Propósito:** establecer quién puede consultar o modificar un proyecto antes de compartirlo.
- **Actor iniciador y actores de apoyo:** inicia A3 Administrador del proyecto; apoya A1 y P01 Gestión de proyectos.
- **Disparador:** el administrador abre la gestión de integrantes o permisos del proyecto.
- **Precondiciones:** el proyecto existe y el iniciador posee el permiso administrativo correspondiente.
- **Flujo principal:** 1. El administrador consulta integrantes y roles. 2. El sistema muestra permisos actuales. 3. El administrador agrega, modifica o quita un permiso básico. 4. El sistema valida la autorización y registra el cambio. 5. Las sesiones posteriores aplican la nueva autorización.
- **Flujos alternativos y excepciones:** **A1.** El administrador puede conservar acceso propio aunque quite a otro participante. **E1.** Un usuario no autorizado no puede consultar ni modificar la lista. **E2.** Una invitación o cambio con identidad inválida se rechaza sin alterar permisos existentes.
- **Postcondición de éxito:** la lista de participantes y sus permisos queda explícita y auditable.
- **Garantía mínima ante falla:** no se eleva ni revoca accidentalmente un permiso; la configuración anterior permanece vigente.
- **Reglas de negocio y semánticas:** leer y modificar son permisos distintos; el administrador no sustituye la validación de cada operación; la pertenencia no implica que una edición concurrente ya esté sincronizada.
- **Resultado de aceptación observable:** un usuario autorizado puede ser reconocido por CU-13 y uno no autorizado recibe una negativa al intentar abrir o modificar.
- **Trazabilidad:** RF-04; RNF-02, RNF-05, RNF-06; P01; S1-01, S1-03; análisis derivado.

#### CU-04 — Gestionar clasificadores UML

- **Ciclo y prioridad:** C1 · P0 habilitante.
- **Propósito:** crear, consultar, modificar, duplicar y eliminar clases, clases abstractas, interfaces, enumeraciones y paquetes admitidos por la línea base UML.
- **Actor iniciador y actores de apoyo:** inicia A1 Diseñador; apoyan P02 Núcleo UML y P03.
- **Disparador:** el usuario selecciona una acción de la barra de herramientas o un atajo sobre un clasificador.
- **Precondiciones:** existe un proyecto abierto con permiso de escritura; la línea base UML confirmada está disponible.
- **Flujo principal:** 1. El usuario elige el tipo desde la barra de herramientas o la acción de teclado. 2. Proporciona nombre, identificador y propiedades admitidas. 3. El sistema verifica el tipo, namespace y referencias. 4. El sistema crea, modifica o duplica el comando; una duplicación recibe un identificador estable nuevo. 5. Para eliminar, calcula el impacto y solicita confirmación solo si la cascada afecta elementos adicionales. 6. El sistema aplica el comando al modelo canónico y actualiza la vista.
- **Flujos alternativos y excepciones:** **A1.** `Ctrl+D` duplica el elemento con identidad nueva. **A2.** `Ctrl+Z` y `Ctrl+Y`/`Ctrl+Shift+Z` deshacen o rehacen la acción propia cuando la validación actual lo permite. **E1.** Los nombres duplicados dentro de un namespace se conservan como IDs distintos, se alertan a todos los usuarios y se registran en el panel de conflictos; editar sigue permitido, pero validación final, publicación, exportación XMI definitiva, generación y despliegue quedan bloqueados hasta resolver por renombrado, movimiento o merge explícito. **E2.** Eliminar una clase elimina atómicamente sus relaciones incidentes y cualquier clase de asociación que materialice una relación muchos-a-muchos, junto con sus relaciones dependientes; permanecen las demás clases principales. Se muestra el conteo exacto de elementos adicionales y una única acción de undo restaura toda la cascada cuando es válida. **E3.** Un tipo fuera de la línea base se rechaza con diagnóstico.
- **Postcondición de éxito:** el clasificador válido existe con identidad estable y puede recibir características o relaciones.
- **Garantía mínima ante falla:** el modelo previo permanece válido y no quedan referencias colgantes ni cascadas parciales.
- **Reglas de negocio y semánticas:** la identidad estable es distinta del nombre visible; la semántica no depende de la posición; eliminar es un comando atómico; el conflicto de nombres no destruye ni fusiona automáticamente elementos.
- **Resultado de aceptación observable:** la barra de herramientas permite crear una clase y una interfaz, `Ctrl+D` crea una copia con ID nuevo, `Delete` ejecuta la política de cascada y un nombre duplicado queda alertado y bloquea las salidas definitivas.
- **Trazabilidad:** RF-02, RF-03, RF-06, RF-12, RF-16; RNF-01, RNF-02, RNF-05, RNF-06; P02, P03; S1-02, S1-09; decisiones confirmadas y análisis derivado.

#### CU-05 — Gestionar características UML

- **Ciclo y prioridad:** C1 · P0 habilitante.
- **Propósito:** administrar atributos, operaciones, parámetros, tipos, valores predeterminados, visibilidad y miembros estáticos/abstractos de los clasificadores admitidos.
- **Actor iniciador y actores de apoyo:** inicia A1; apoyan P02 y P03.
- **Disparador:** el usuario abre un clasificador y usa el panel o la barra de herramientas para agregar, duplicar, modificar o eliminar un atributo, operación o parámetro.
- **Precondiciones:** existe un clasificador válido; el usuario tiene permiso de escritura; el tipo de característica pertenece a la línea base confirmada.
- **Flujo principal:** 1. El usuario selecciona la característica. 2. Ingresa nombre, tipo, visibilidad, multiplicidad y, si corresponde, valor predeterminado o parámetros. 3. El sistema valida la combinación. 4. El sistema registra un comando atómico. 5. El usuario confirma y el sistema actualiza el clasificador y su representación visual.
- **Flujos alternativos y excepciones:** **A1.** `Ctrl+C`, `Ctrl+V` o `Ctrl+D` copia, pega o duplica una característica con identidad apropiada sin alterar la original. **A2.** `Delete`, `Ctrl+Z` y `Ctrl+Y`/`Ctrl+Shift+Z` aplican la eliminación o la reversión selectiva si la validación actual lo permite. **E1.** Un tipo desconocido, multiplicidad inválida o firma incompatible se rechaza. **E2.** Si se elimina una característica referenciada por una regla de generación, el sistema informa el impacto antes de confirmar.
- **Postcondición de éxito:** el clasificador contiene características válidas y trazables a una versión del modelo.
- **Garantía mínima ante falla:** la firma anterior se conserva; no se aplica una característica parcialmente validada.
- **Reglas de negocio y semánticas:** visibilidad, tipo, multiplicidad, firma, valores predeterminados y modificadores son datos semánticos; la barra de herramientas no reemplaza la validación.
- **Resultado de aceptación observable:** el usuario agrega un atributo tipado y una operación con parámetro, los duplica o deshace desde los controles definidos y observa el rechazo de una multiplicidad inválida.
- **Trazabilidad:** RF-02, RF-03, RF-06, RF-16; RNF-01, RNF-06; P02, P03; S1-02, S1-09; decisiones confirmadas y análisis derivado.

#### CU-06 — Gestionar relaciones UML

- **Ciclo y prioridad:** C1 · P0 habilitante.
- **Propósito:** crear, modificar, duplicar y eliminar asociaciones, agregaciones, composiciones, generalizaciones, realizaciones de interfaz y dependencias con sus extremos y restricciones admitidos.
- **Actor iniciador y actores de apoyo:** inicia A1; apoyan P02 y P03.
- **Disparador:** el usuario selecciona una relación desde la barra de herramientas o conecta dos clasificadores.
- **Precondiciones:** existen los clasificadores extremos; el usuario tiene permiso de escritura; el tipo de relación pertenece a la línea base confirmada.
- **Flujo principal:** 1. El usuario selecciona tipo, extremos, roles, navegabilidad y multiplicidades. 2. El sistema verifica que los extremos existan. 3. El sistema valida compatibilidad semántica. 4. El usuario confirma. 5. El sistema persiste la relación como comando y actualiza la vista.
- **Flujos alternativos y excepciones:** **A1.** `Ctrl+D` duplica una relación con identidad nueva cuando sus extremos siguen siendo válidos. **A2.** `Delete` elimina la relación como una operación propia; `Ctrl+Z` y `Ctrl+Y`/`Ctrl+Shift+Z` deshacen o rehacen selectivamente cuando la validación actual lo permite. **E1.** Una relación sin extremos, con extremos iguales no permitidos o multiplicidad inválida se rechaza. **E2.** Si una relación importada no tiene equivalencia, se conserva como advertencia o se omite según el perfil, nunca silenciosamente.
- **Postcondición de éxito:** la relación válida queda vinculada a identificadores estables y a una versión del modelo.
- **Garantía mínima ante falla:** no se crean relaciones huérfanas ni se eliminan relaciones previas por un intento inválido.
- **Reglas de negocio y semánticas:** el trazado visual no define el tipo; multiplicidad, roles y navegabilidad influyen en validación y generación; la eliminación de un clasificador se especifica en CU-04.
- **Resultado de aceptación observable:** la barra de herramientas crea una asociación y una generalización válidas, los atajos permiten duplicar/eliminar/revertir la relación y un extremo inexistente genera un diagnóstico.
- **Trazabilidad:** RF-02, RF-03, RF-06, RF-16; RNF-01, RNF-06; P02, P03; S1-02, S1-09; decisiones confirmadas y análisis derivado.

#### CU-07 — Organizar la disposición visual

- **Ciclo y prioridad:** C1 · P0 habilitante.
- **Propósito:** seleccionar, desplazar, redimensionar, panear y ajustar el lienzo sin modificar la semántica UML.
- **Actor iniciador y actores de apoyo:** inicia A1; apoyan P03, P04 y P09 Clientes y demostración.
- **Disparador:** el usuario usa selección/pan de la barra de herramientas, arrastra un elemento o activa zoom/ajuste.
- **Precondiciones:** existe un proyecto abierto y una vista cargada; el usuario tiene permiso para modificar la vista.
- **Flujo principal:** 1. El usuario selecciona el modo de selección o pan. 2. Cambia posición, tamaño o disposición y usa zoom/fit cuando corresponda. 3. Durante el arrastre, el sistema muestra presencia y un bloqueo blando breve para esa geometría. 4. Al soltar, el sistema coalescea actualizaciones y confirma la geometría compartida. 5. El lienzo se actualiza sin modificar atributos, relaciones ni identificadores.
- **Flujos alternativos y excepciones:** **A1.** Zoom, ajuste y pan modifican el estado personal de la sesión; posiciones, tamaños, rutas de conectores y agrupaciones son estado compartido. **A2.** `Ctrl+Z` y `Ctrl+Y`/`Ctrl+Shift+Z` deshacen o rehacen solo la acción propia si la versión actual lo permite. **E1.** Una vista corrupta o elemento visual inexistente se repara desde la última disposición válida. **E2.** Una operación sin permiso se rechaza.
- **Postcondición de éxito:** la disposición visual cambia y la versión semántica permanece equivalente.
- **Garantía mínima ante falla:** el modelo semántico no se altera; se conserva la última vista válida y se informa una sincronización fallida.
- **Reglas de negocio y semánticas:** mover no es editar UML; la disposición puede variar por vista; la generación y XMI se basan en semántica; la coalescencia de arrastre respeta RNF-11.
- **Resultado de aceptación observable:** el toolbar permite selección, pan, zoom y fit; dos sesiones observan una posición confirmada al soltar; zoom/pan/selección no alteran el estado compartido; los atajos de undo/redo actúan selectivamente.
- **Trazabilidad:** RF-03, RF-06, RF-16; RNF-01, RNF-06, RNF-11; P03, P04, P09; S1-09; decisiones confirmadas y análisis derivado.

#### CU-08 — Validar el modelo UML

- **Ciclo y prioridad:** C1 · P0 habilitante.
- **Propósito:** determinar si el modelo y los comandos cumplen la línea base UML y las invariantes necesarias para continuar.
- **Actor iniciador y actores de apoyo:** inicia A1; apoyan P02 y P03.
- **Disparador:** el usuario solicita validar, confirma una operación o el sistema necesita una versión apta para otro caso de uso.
- **Precondiciones:** existe un modelo o comando candidato; las reglas de la línea base UML confirmada están disponibles.
- **Flujo principal:** 1. El sistema recibe el modelo candidato. 2. Comprueba identificadores, clasificadores, características, relaciones y disposición separada. 3. Evalúa restricciones UML y consistencia entre referencias. 4. Clasifica errores y advertencias. 5. Devuelve una versión válida o un diagnóstico accionable.
- **Flujos alternativos y excepciones:** **A1.** Una advertencia no bloqueante puede acompañar una versión válida si el perfil lo permite. **E1.** Un error semántico bloquea la aplicación, exportación o generación que dependa de esa versión. **E2.** Un elemento fuera del subconjunto confirmado se marca como no soportado y no se presenta como compatible.
- **Postcondición de éxito:** queda identificada una versión válida o un resultado de validación reproducible.
- **Garantía mínima ante falla:** no se publica como válida una versión que no pudo validarse; el modelo anterior sigue disponible.
- **Reglas de negocio y semánticas:** toda entrada pasa por validación antes de efectos; UML 2.5 significa solo el subconjunto acordado; errores y advertencias no se ocultan.
- **Resultado de aceptación observable:** una versión con relación inválida es rechazada con regla y ubicación; una versión válida puede continuar a colaboración, XMI o generación.
- **Trazabilidad:** RF-02, RF-06, RF-12; RNF-01, RNF-04, RNF-06; P02, P03; S1-02, S1-03; decisiones confirmadas y análisis derivado.

#### Ciclo 2 — Edición asistida, colaboración e interoperabilidad

#### CU-09 — Interpretar instrucciones textuales con IA

- **Ciclo y prioridad:** C2 · P1 extensión obligatoria.
- **Propósito:** transformar una instrucción textual contextualizada en una propuesta de comando UML, sin modificar directamente el modelo.
- **Actor iniciador y actores de apoyo:** inicia A1 o A2; apoyan A4 Asistente de IA, P05 Entradas multimodales e IA y P02.
- **Disparador:** el usuario envía una instrucción como crear, mover, editar, eliminar o relacionar elementos.
- **Precondiciones:** existe un proyecto abierto y una versión de modelo consultable; la entrada textual está disponible; uno de los dos runtimes locales independientes está disponible para la ejecución seleccionada.
- **Flujo principal:** 1. El usuario escribe la instrucción. 2. El sistema incorpora contexto mínimo del modelo y permisos. 3. El runtime local seleccionado usa Qwen 3.5 0.8B para interpretar la intención. 4. El sistema convierte la salida a una propuesta estructurada. 5. El sistema valida referencias y devuelve propuesta, confianza y advertencias sin aplicarla.
- **Flujos alternativos y excepciones:** **A1.** Si la instrucción admite varias interpretaciones, se presentan alternativas para CU-10. **E1.** Una referencia inexistente o comando no soportado produce diagnóstico sin mutación. **E2.** Si el proveedor no responde, el usuario conserva la edición manual y el modelo intacto.
- **Postcondición de éxito:** existe una propuesta estructurada vinculada a una versión del modelo.
- **Garantía mínima ante falla:** ninguna salida textual o de IA modifica el modelo sin pasar por CU-10 y CU-08.
- **Reglas de negocio y semánticas:** la IA no escribe en base de datos ni lienzo; toda propuesta debe ser un comando validable; la IA es un adaptador y no una fuente de verdad.
- **Resultado de aceptación observable:** “crear una clase X” devuelve una propuesta identificable; una orden ambigua solicita aclaración y deja el modelo igual.
- **Trazabilidad:** RF-05, RF-06, RF-16; RNF-01, RNF-06, RNF-09; P05, P02, P03; S1-04, S1-05; decisiones confirmadas y análisis derivado.

#### CU-10 — Revisar y aplicar una propuesta asistida

- **Ciclo y prioridad:** C2 · P1 extensión obligatoria.
- **Propósito:** permitir que una persona inspeccione, corrija, confirme o descarte una propuesta producida por texto, voz o fotografía.
- **Actor iniciador y actores de apoyo:** inicia A1 o A2; apoyan A4 y P03 Comandos, historial y versionado.
- **Disparador:** CU-09, CU-11 o CU-12 entrega una propuesta estructurada.
- **Precondiciones:** la propuesta identifica su modelo de origen, comandos y advertencias; el usuario tiene permiso de escritura.
- **Flujo principal:** 1. El sistema muestra la propuesta y sus diferencias. 2. El usuario revisa advertencias y puede editar campos permitidos. 3. El sistema revalida el comando contra la versión actual. 4. El usuario confirma o descarta. 5. Al confirmar, el sistema aplica el comando atómico, crea una versión y registra la procedencia.
- **Flujos alternativos y excepciones:** **A1.** El usuario aplica solo una parte de una propuesta si los comandos son separables y válidos. **E1.** Una operación destructiva exige confirmación explícita. **E2.** Si el modelo cambió mientras se revisaba, se recalcula la propuesta o se solicita revisión; no se sobreescribe silenciosamente. **E3.** Una propuesta inválida se descarta sin mutación.
- **Postcondición de éxito:** el modelo contiene exactamente los cambios confirmados y el historial identifica su origen.
- **Garantía mínima ante falla:** el modelo anterior permanece disponible y una propuesta descartada no deja efectos parciales.
- **Reglas de negocio y semánticas:** la revisión humana es obligatoria cuando la propuesta es ambigua, destructiva o incompleta; aplicar y descartar son resultados distintos; el comando se valida igual que uno manual.
- **Resultado de aceptación observable:** una propuesta puede corregirse antes de aplicarse, y el historial distingue el comando asistido confirmado.
- **Trazabilidad:** RF-05, RF-06, RF-16; RNF-01, RNF-02, RNF-06; P03, P05, P02; S1-04, S1-05; decisiones confirmadas y análisis derivado.

#### CU-11 — Procesar comandos de voz

- **Ciclo y prioridad:** C2 · P1 extensión obligatoria.
- **Propósito:** convertir una orden hablada en una propuesta que use el mismo flujo textual, de validación y de aplicación.
- **Actor iniciador y actores de apoyo:** inicia A1 o A2; apoyan A4, reconocimiento de voz y CU-09/CU-10.
- **Disparador:** el usuario graba o emite una orden de voz.
- **Precondiciones:** proyecto abierto y permiso de edición; micrófono o archivo de audio disponible; la capacidad de reconocimiento aplicable está configurada para el flujo de voz.
- **Flujo principal:** 1. El usuario inicia la captura. 2. El sistema ejecuta ASR local y obtiene una transcripción. 3. El usuario puede corregirla. 4. La transcripción pasa a Qwen 3.5 0.8B para interpretación textual en el runtime local seleccionado. 5. Qwen produce una propuesta estructurada que pasa a CU-10 y, si corresponde, se confirma.
- **Flujos alternativos y excepciones:** **A1.** El usuario corrige palabras o nombres antes de interpretar. **E1.** Audio ilegible, idioma no soportado o transcripción ambigua detiene el flujo sin cambiar el modelo. **E2.** Si el reconocimiento remoto no está disponible y no existe capacidad local acordada, se informa la limitación.
- **Postcondición de éxito:** la orden comprensible produce un comando validado con procedencia de voz.
- **Garantía mínima ante falla:** el audio y el modelo previo no se presentan como operación aplicada; el usuario puede continuar manualmente.
- **Reglas de negocio y semánticas:** voz es un adaptador hacia texto; no se crea un segundo lenguaje de comandos; la confianza de transcripción debe ser visible cuando afecte la interpretación.
- **Resultado de aceptación observable:** una orden de voz equivalente a una orden textual llega a la misma revisión y modifica el modelo solo después de confirmar.
- **Trazabilidad:** RF-06, RF-07; RNF-01, RNF-06, RNF-09; P05, P03, P02; S1-05, S1-08; análisis derivado.

#### CU-12 — Interpretar fotografía de un modelo

- **Ciclo y prioridad:** C2 · P1 extensión obligatoria.
- **Propósito:** convertir una fotografía en una propuesta editable y posiblemente parcial de los elementos y relaciones UML detectados.
- **Actor iniciador y actores de apoyo:** inicia A1 o A2; apoyan A4 y P05.
- **Disparador:** el usuario carga o captura una fotografía de un diagrama.
- **Precondiciones:** proyecto abierto y permiso de edición; imagen accesible; el runtime local seleccionado dispone de Qwen 3.5 0.8B y del preprocesamiento compatible que la verificación técnica confirme.
- **Flujo principal:** 1. El usuario selecciona la imagen. 2. El runtime prepara la fotografía y Qwen interpreta texto y estructura UML observables. 3. El sistema construye una propuesta con clases, atributos/tipos, operaciones, relaciones/multiplicidades, cobertura explícita, confianza por elemento, faltantes, ambigüedades y disposición aproximada cuando sea observable, sin convertir todavía la extracción en comandos canónicos. 4. El usuario revisa y corrige la propuesta mediante CU-10, puede completar y reposicionar manualmente el diagrama, y confirma qué elementos conservar. 5. Solo los comandos confirmados y válidos se incorporan al modelo canónico.
- **Flujos alternativos y excepciones:** **A1.** Las partes faltantes, ilegibles o de baja confianza se presentan explícitamente para que el usuario las complete o corrija. **E1.** Una imagen ilegible o sin elementos reconocibles produce un diagnóstico y una propuesta sin cobertura útil, nunca una afirmación de completitud. **E2.** Una coincidencia dudosa con un elemento existente se presenta como advertencia y permanece fuera del modelo canónico hasta la revisión. **E3.** Una falla del procesamiento local no altera el modelo.
- **Postcondición de éxito:** la propuesta revisada conserva su cobertura y procedencia, y el modelo incorpora únicamente la parte confirmada por el usuario y validada como UML.
- **Garantía mínima ante falla:** la fotografía nunca sobrescribe automáticamente el modelo ni elimina elementos existentes.
- **Reglas de negocio y semánticas:** la fotografía produce una propuesta, no una fuente de verdad; la revisión humana, la validación UML y la confirmación son obligatorias; las pérdidas, partes faltantes, incertidumbres y ambigüedades son observables; la disposición detectada no inventa semántica ausente.
- **Resultado de aceptación observable:** una fotografía genera una propuesta editable visible con cobertura, confianza, faltantes, advertencias y disposición aproximada cuando exista; puede completarse, reposicionarse, corregirse, descartarse o confirmarse antes de entrar al modelo canónico.
- **Trazabilidad:** RF-06, RF-08; RNF-01, RNF-06; P05, P03, P02; S1-05, S1-10; análisis derivado.

#### CU-13 — Invitar y administrar participantes

- **Ciclo y prioridad:** C2 · P1 extensión obligatoria.
- **Propósito:** incorporar participantes autorizados a un proyecto y administrar su acceso para colaboración.
- **Actor iniciador y actores de apoyo:** inicia A3; apoyan A1 y P01.
- **Disparador:** el administrador solicita invitar, aceptar, cambiar o revocar la participación de una identidad.
- **Precondiciones:** existe proyecto; el iniciador es administrador; la identidad invitada puede identificarse.
- **Flujo principal:** 1. El administrador selecciona invitar. 2. El sistema registra la invitación y el permiso propuesto. 3. La persona invitada acepta por el mecanismo disponible. 4. El sistema verifica identidad y autorización. 5. El participante aparece con su rol y puede abrir el proyecto conforme al permiso.
- **Flujos alternativos y excepciones:** **A1.** El administrador puede revocar una invitación pendiente. **A2.** La persona invitada puede rechazar una invitación pendiente: se conserva como rechazada, no se crea membresía y el administrador puede invitarla de nuevo. **E1.** Una invitación duplicada se informa sin crear membresías duplicadas. **E2.** Una identidad no verificable o sin permiso se rechaza. Solo la persona invitada puede rechazar su invitación, y solo mientras esté pendiente; los intentos inválidos no cambian permisos ni estado. **E3.** Revocar acceso no borra el historial de operaciones ya registradas.
- **Postcondición de éxito:** el participante autorizado puede iniciar CU-14 con una identidad y un rol explícitos.
- **Garantía mínima ante falla:** no se expone el proyecto a una identidad no autorizada y la membresía anterior permanece si el cambio falla.
- **Reglas de negocio y semánticas:** invitar no equivale a sincronizar; los permisos se comprueban en cada operación; el historial conserva autoría aunque cambie la membresía.
- **Resultado de aceptación observable:** el administrador invita a un colaborador, este obtiene acceso según su rol y un tercero no autorizado no puede abrir el proyecto.
- **Trazabilidad:** RF-04; RNF-02, RNF-05, RNF-06; P01, P04; S1-01, S1-03; análisis derivado.

#### CU-14 — Sincronizar cambios en colaboración

- **Ciclo y prioridad:** C2 · P1 extensión obligatoria.
- **Propósito:** propagar operaciones válidas entre sesiones autorizadas que trabajan sobre el mismo proyecto.
- **Actor iniciador y actores de apoyo:** inicia A1 o A2; apoyan A3, P04 Colaboración y P03.
- **Disparador:** una sesión autorizada aplica una operación mientras otra sesión está abierta o se conecta.
- **Precondiciones:** proyecto compartido mediante CU-13; sesiones autenticadas; cada sesión conoce una versión local.
- **Flujo principal:** 1. Una sesión produce un comando local optimista con operation ID, autor, objetivos estables, revisión base/causal y precondiciones. 2. El sistema valida permisos y semántica contra el modelo local. 3. Si hay conexión, publica por el canal en tiempo real; si no, lo conserva en la cola offline. 4. El servidor mantiene autoridad sobre permisos y estado canónico válido. 5. Las sesiones receptoras verifican la base, aplican una operación compatible y muestran el estado actualizado. 6. Si la operación no es compatible, se deriva a CU-15 sin mutar silenciosamente el canon. 7. El sistema registra autoría, resultado, latencias y estado de sincronización.
- **Flujos alternativos y excepciones:** **A1.** Una sesión recién conectada solicita operaciones posteriores a su revisión. **A2.** Propiedades independientes se fusionan automáticamente solo si las precondiciones se cumplen y la validación del modelo completo pasa. **A3.** Durante un drag se muestra presencia y un bloqueo blando breve; la geometría se confirma al soltar. **E1.** Una sesión no autorizada no recibe ni publica operaciones. **E2.** Una base atrasada, comando incompatible o permiso revocado se separa para CU-15. **E3.** Una desconexión detiene la propagación remota sin borrar la operación local.
- **Postcondición de éxito:** las sesiones autorizadas convergen en los cambios aceptados o muestran el conflicto que impide converger.
- **Garantía mínima ante falla:** toda operación aceptada queda registrada o marcada como pendiente; ninguna modificación desaparece sin señal.
- **Reglas de negocio y semánticas:** la sincronización opera sobre comandos y versiones, no sobre píxeles; autoría e historial son obligatorios; el canal y algoritmo de merge son decisiones de diseño, no requisitos tecnológicos; estado compartido son posiciones, tamaños, rutas y agrupaciones, mientras zoom, pan, selección, paneles, tema, filtros y grilla son personales.
- **Resultado de aceptación observable:** dos sesiones autorizadas observan un cambio dentro de RNF-11; una operación con base incompatible aparece en el panel de conflictos y una operación offline permanece recuperable.
- **Trazabilidad:** RF-04, RF-06, RF-16; RNF-01, RNF-02, RNF-05, RNF-06, RNF-11; P04, P03, P01; S1-01, S1-03; decisiones confirmadas y análisis derivado.

#### CU-15 — Reconciliar cambios offline y conflictos

- **Ciclo y prioridad:** C2 · P1 extensión obligatoria.
- **Propósito:** conservar, analizar y reconciliar operaciones hechas sin conexión cuando una sesión vuelve a comunicarse.
- **Actor iniciador y actores de apoyo:** inicia A1 o A2 al reconectar; apoyan P04, P03 y P02.
- **Disparador:** una sesión recupera conectividad o el usuario solicita resolver una operación pendiente.
- **Precondiciones:** existe una cola local identificada; la sesión y el proyecto tienen identidad; el modelo local fue obtenido desde una versión conocida.
- **Flujo principal:** 1. El sistema detecta la reconexión. 2. Ordena operaciones locales y cambios remotos por causalidad, revisión y autorización. 3. Acepta operaciones verificablemente pre-revocación, rechaza las verificablemente post-revocación y deja fuera del canon las temporalmente ambiguas para revisión administrativa auditada. 4. Comprueba precondiciones y valida el modelo completo. 5. Fusiona automáticamente solo propiedades independientes seguras. 6. Aplica los niveles de resolución: rechazo de autorización/seguridad; delete-wins mediante tombstone; conflicto semántico al panel; merge independiente seguro; resolución visual determinista con historial. 7. El usuario o administrador elige una resolución; esta crea un comando canónico nuevo y conserva la autoría original. 8. El sistema registra aceptación, rechazo, estado local/exportable y evidencia.
- **Flujos alternativos y excepciones:** **A1.** Una operación compatible se reintenta de manera idempotente. **A2.** Eliminar una clase usa la cascada atómica de CU-04 y undo restaura el conjunto completo cuando es válido. **A3.** Nombres duplicados en un namespace permanecen como IDs distintos, se alertan a todos y bloquean validación final, publicación, XMI definitivo, generación y despliegue hasta renombrar, mover o fusionar explícitamente. **A4.** Ctrl+Z emite un inverse command de la última acción reversible propia; Ctrl+Y/Ctrl+Shift+Z reaplica solo si la validación actual lo permite. **A5.** Una aprobación administrativa de una operación ambigua crea un comando autorizado nuevo y conserva la autoría original; un rechazo conserva el trabajo local/exportable. **E1.** Dos cambios sobre la misma propiedad o propiedades acopladas pasan al panel, nunca se elige por inferencia. **E2.** Si no hay conectividad suficiente, la cola permanece intacta. **E3.** Un conflicto desconocido se escala para decisión humana y no altera el canon.
- **Postcondición de éxito:** los cambios reconciliados forman parte de una historia común y los no resueltos quedan identificados.
- **Garantía mínima ante falla:** no se pierden operaciones locales, no se duplican al reintentar y el estado anterior sigue recuperable.
- **Reglas de negocio y semánticas:** el modelo canónico conserva el último estado válido; alternativas incompatibles viven en conflicto/historial; el contrato no exige CRDT ni otra biblioteca; la resolución humana siempre produce un comando nuevo. Las métricas y estados de sincronización cumplen RNF-11.
- **Resultado de aceptación observable:** después de trabajar sin red y reconectar, una operación compatible aparece una vez y un conflicto incompatible queda explicado y pendiente.
- **Trazabilidad:** RF-04, RF-06, RF-16; RNF-01, RNF-02, RNF-05, RNF-06, RNF-08, RNF-11; P04, P03, P02, P01; S1-01, S1-03; decisiones confirmadas y análisis derivado.

#### CU-16 — Importar modelo XMI

- **Ciclo y prioridad:** C2 · P1 extensión obligatoria.
- **Propósito:** transformar un archivo del perfil XMI primario de Enterprise Architect al modelo canónico sin ocultar incompatibilidades.
- **Actor iniciador y actores de apoyo:** inicia A1; apoyan A5 Enterprise Architect, P06 Interoperabilidad XMI y P02.
- **Disparador:** el usuario selecciona el fixture o un archivo XMI para crear o incorporar un modelo.
- **Precondiciones:** existe un proyecto destino; el archivo está disponible; el perfil primario está fijado a EA 15.0.1514 build 1514 con XMI 2.1/UML 2.1, extensiones EA 6.5 e información de diagrama.
- **Flujo principal:** 1. El usuario selecciona archivo y perfil. 2. El adaptador identifica metadatos y elementos. 3. Convierte al modelo canónico los clasificadores, características, relaciones, IDs y geometría soportados. 4. Valida semántica y referencias. 5. Presenta elementos soportados, advertencias y pérdidas. 6. El usuario confirma la incorporación. 7. El sistema crea una versión importada trazable.
- **Flujos alternativos y excepciones:** **A1.** `Prueba1` y `Prueba2` se usan como referencias secundarias de diagnóstico, no como perfiles V1 obligatorios. **E1.** XML/XMI inválido se rechaza sin modificar el proyecto. **E2.** Un dialecto no compatible genera un reporte. **E3.** IDs duplicados o pérdida semántica bloquean o solicitan revisión según el perfil; nunca se ocultan.
- **Postcondición de éxito:** el subconjunto importado queda editable y sus pérdidas son trazables.
- **Garantía mínima ante falla:** el proyecto destino conserva su versión previa y el archivo original no se presenta como importado.
- **Reglas de negocio y semánticas:** XMI no es un dialecto universal; las extensiones EA de diagrama no sustituyen la semántica UML; los IDs estables se preservan cuando el perfil los representa.
- **Resultado de aceptación observable:** el fixture canónico produce un modelo editable y un reporte que enumera perfil, elementos, relaciones, geometría representable y pérdidas.
- **Trazabilidad:** RF-02, RF-06, RF-09, RF-12, RF-16; RNF-01, RNF-04, RNF-06; P06, P02, P03; S1-02, S1-03, S1-11; decisiones confirmadas y análisis derivado.

#### CU-17 — Exportar modelo XMI

- **Ciclo y prioridad:** C2 · P1 extensión obligatoria.
- **Propósito:** generar un archivo XMI interoperable con EA 15.0.1514 build 1514 para el subconjunto soportado y comprobar el round-trip exigido por Q3.
- **Actor iniciador y actores de apoyo:** inicia A1; apoya A5 y P06.
- **Disparador:** el usuario solicita exportar una versión válida o ejecutar la prueba de ida y vuelta.
- **Precondiciones:** existe una versión UML válida y el perfil primario EA 15.0.1514 build 1514 está disponible; el fixture `docs/fixtures/xmi/Prueba3.xml` fue preservado byte a byte.
- **Flujo principal:** 1. El usuario selecciona versión y perfil. 2. El sistema valida el modelo. 3. El adaptador convierte clasificadores, características, relaciones, IDs y geometría soportados. 4. Genera metadatos de XMI 2.1/UML 2.1 y extensiones EA 6.5 representables. 5. Informa pérdidas o extensiones no representables. 6. Para Q3, ejecuta EA → aplicación → XMI → EA con el fixture y compara IDs, semántica soportada, relaciones y geometría, registrando cualquier pérdida. 7. El usuario descarga o conserva el archivo e informe.
- **Flujos alternativos y excepciones:** **A1.** El usuario puede exportar una copia de solo los elementos soportados. **E1.** Un modelo inválido bloquea la exportación. **E2.** Un elemento sin mapeo queda en el reporte y no se afirma conservación total. **E3.** Un fallo de escritura conserva la versión válida y permite reintentar.
- **Postcondición de éxito:** existe un XMI identificado por perfil y asociado a la versión de origen.
- **Garantía mínima ante falla:** no se sustituye la versión del modelo ni se entrega un archivo incompleto como exportación válida.
- **Reglas de negocio y semánticas:** `Prueba1` y `Prueba2` son referencias secundarias; el perfil primario observado en `Prueba3.xml` es la obligación V1; la disposición visual solo se conserva donde el dialecto la representa.
- **Resultado de aceptación observable:** EA abre el archivo exportado del perfil primario o el sistema reporta la incompatibilidad, y la prueba Q3 conserva la matriz de pérdidas.
- **Trazabilidad:** RF-06, RF-09, RF-16; RNF-01, RNF-04, RNF-06; P06, P02, P03; S1-03, S1-11; decisiones confirmadas y tarea de prueba Q3.

#### Ciclo 3 — Generación determinista, clientes y demostración integral

#### CU-18 — Definir y seleccionar perfil de generación

- **Ciclo y prioridad:** C3 · P2 cierre obligatorio.
- **Propósito:** declarar las reglas y opciones con las que una versión UML se transformará en backend y artefactos de apoyo.
- **Actor iniciador y actores de apoyo:** inicia A1; apoya A7 Generador de código y P07 Generación de backend.
- **Disparador:** el usuario crea, modifica o selecciona un perfil de generación.
- **Precondiciones:** existe un modelo validado; el perfil conoce el subconjunto UML y la línea base completa de backend; las cuatro capas mínimas y los componentes de apoyo están definidas en las decisiones confirmadas.
- **Flujo principal:** 1. El usuario selecciona opciones de persistencia, API, migraciones, validación, errores, pruebas y capas. 2. El sistema valida que sean compatibles. 3. El sistema versiona el perfil y registra plantillas o reglas aplicables. 4. El usuario confirma el perfil para una generación.
- **Flujos alternativos y excepciones:** **A1.** Un perfil puede derivarse de uno base sin mutarlo. **E1.** Una opción incompatible con el modelo se marca y bloquea la selección. **E2.** Si una opción no puede mapearse al modelo o a la línea base completa, el sistema la marca, bloquea la selección y no afirma cumplimiento.
- **Postcondición de éxito:** queda un perfil versionado, reproducible y asociado a una generación posible.
- **Garantía mínima ante falla:** el perfil anterior y el modelo no se modifican por una configuración inválida.
- **Reglas de negocio y semánticas:** el mismo modelo y perfil deben ser entradas explícitas de la generación; la línea base incluye JPA, repositorios, servicios y operaciones de dominio soportadas, REST, DTO/mapeadores, validación, errores, PostgreSQL, migraciones, OpenAPI, pruebas, Docker Compose, ejecución reproducible, health/contrato, autenticación, autorización, auditoría y despliegue, dentro de las cuatro capas mínimas y componentes de apoyo.
- **Resultado de aceptación observable:** el usuario puede identificar qué perfil se usará y el sistema rechaza una combinación sin mapeo o sin capas definidas.
- **Trazabilidad:** RF-11, RF-12; RNF-03, RNF-06, RNF-07; P07, P08; S1-06, S1-13; decisión confirmada y análisis derivado.

#### CU-19 — Generar backend determinista

- **Ciclo y prioridad:** C3 · P2 cierre obligatorio.
- **Propósito:** transformar una versión UML validada y un perfil versionado en un proyecto backend reproducible.
- **Actor iniciador y actores de apoyo:** inicia A1; apoyan A7 y P07, P08.
- **Disparador:** el usuario solicita generar el backend para una versión y perfil.
- **Precondiciones:** CU-08 aprobó el modelo; CU-18 aprobó el perfil; las plantillas y reglas están disponibles.
- **Flujo principal:** 1. El sistema fija la versión del modelo, perfil y plantillas. 2. Valida nuevamente la entrada. 3. Construye un modelo intermedio. 4. Mapea entidades, atributos, relaciones y reglas soportadas. 5. Genera código, configuración, contrato OpenAPI, migraciones, pruebas y documentación acordada. 6. Registra artefactos, diagnósticos e identidad de origen.
- **Flujos alternativos y excepciones:** **A1.** El usuario puede generar una vista previa o un conjunto acotado de artefactos si el perfil lo permite. **E1.** Un elemento sin mapeo bloquea o queda explícitamente reportado según el perfil. **E2.** Una plantilla defectuosa termina con diagnóstico y sin marcar el backend como listo. **E3.** Repetir la generación con las mismas entradas produce artefactos equivalentes.
- **Postcondición de éxito:** existe un backend generado asociado a modelo, perfil y plantillas concretos.
- **Garantía mínima ante falla:** no se publica una generación parcial como completa; el modelo y las generaciones anteriores permanecen disponibles.
- **Reglas de negocio y semánticas:** solo se genera desde una versión válida; la semántica UML gobierna el resultado; el backend y OpenAPI son independientes del cliente; determinismo excluye solo metadatos declarados.
- **Resultado de aceptación observable:** dos ejecuciones con las mismas entradas entregan el mismo conjunto de artefactos y el resultado contiene la estructura mínima acordada.
- **Trazabilidad:** RF-10, RF-11, RF-12; RNF-01, RNF-03, RNF-06, RNF-07; P07, P08, P02, P03; S1-06, S1-13; decisión confirmada y análisis derivado.

#### CU-20 — Verificar backend generado

- **Ciclo y prioridad:** C3 · P2 cierre obligatorio.
- **Propósito:** comprobar que el backend generado es estructuralmente completo según el perfil y puede compilar, iniciar y responder al contrato acordado.
- **Actor iniciador y actores de apoyo:** inicia A1; apoyan A7 y P07/P08.
- **Disparador:** CU-19 entrega un conjunto de artefactos generado o el usuario solicita verificarlo.
- **Precondiciones:** existe una generación identificada; están disponibles las herramientas de compilación, ejecución, health, contrato, base de datos y pruebas requeridas por la línea base confirmada.
- **Flujo principal:** 1. El sistema verifica estructura, dependencias y cuatro capas. 2. Comprueba entidades/relaciones JPA, repositorios, servicios, operaciones de dominio, REST, DTO/mapeadores, seguridad, auditoría, migraciones y configuración. 3. Compila el proyecto. 4. Inicia el backend y ejecuta health check. 5. Comprueba PostgreSQL, OpenAPI, validaciones, errores y pruebas unitarias/integración. 6. Comprueba Docker Compose y ejecución reproducible. 7. Repite la generación o compara artefactos para verificar determinismo. 8. Emite un informe de aprobado, advertencias o fallos.
- **Flujos alternativos y excepciones:** **A1.** Si PostgreSQL real no está disponible, se ejecuta la alternativa local o simulada permitida por el alcance, dejando constancia. **E1.** Un error de compilación, inicio o health impide declarar el backend verificable. **E2.** Una diferencia no explicada entre generaciones impide declarar determinismo. **E3.** Un artefacto ausente se reporta sin inventar cumplimiento.
- **Postcondición de éxito:** la generación tiene evidencia de compilación, inicio, contrato, estructura y determinismo dentro del perfil.
- **Garantía mínima ante falla:** el informe identifica el punto de fallo y no confunde un diagnóstico parcial con backend aprobado.
- **Reglas de negocio y semánticas:** verificar no corrige silenciosamente el código; la configuración debe respetar las cuatro capas mínimas y el backend completo confirmados; la evidencia debe referir la generación concreta.
- **Resultado de aceptación observable:** el backend compila, inicia, pasa health check, responde a una operación del contrato, evidencia seguridad/auditoría y el informe muestra cualquier incumplimiento restante.
- **Trazabilidad:** RF-10, RF-11, RF-12; RNF-01, RNF-03, RNF-06, RNF-07; P07, P08; S1-06, S1-13; decisión confirmada y análisis derivado.

#### CU-21 — Consumir backend desde cliente

- **Ciclo y prioridad:** C3 · P2 cierre obligatorio.
- **Propósito:** ejecutar desde Flutter, o desde otro cliente compatible, un flujo del dominio usando el contrato del backend generado.
- **Actor iniciador y actores de apoyo:** inicia A6 Cliente web o móvil; apoyan A1, P08 Contratos y servicios generados y P09.
- **Disparador:** el usuario abre la aplicación y solicita una operación del dominio.
- **Precondiciones:** CU-20 verificó el backend; el contrato OpenAPI está disponible; el cliente está configurado sin alterar la lógica del backend; el dominio será proporcionado por el examen y la secuencia exacta se definirá en Q9.
- **Flujo principal:** 1. El cliente configura el endpoint y contrato. 2. El usuario inicia un flujo funcional. 3. El cliente envía una solicitud conforme a OpenAPI. 4. El backend valida, ejecuta reglas y persiste. 5. El cliente interpreta la respuesta o error. 6. El usuario observa el resultado del dominio.
- **Flujos alternativos y excepciones:** **A1.** El mismo contrato puede consumirse desde React/TypeScript u otro cliente compatible. **E1.** Un contrato desactualizado o respuesta incompatible se informa sin adaptar la lógica del backend a mano. **E2.** Una validación o fallo de infraestructura se muestra como error observable y no como éxito.
- **Postcondición de éxito:** el cliente completa al menos un flujo contra el backend generado sin modificar sus reglas.
- **Garantía mínima ante falla:** no se corrompe el backend ni se afirma que el cliente consumió una operación no confirmada.
- **Reglas de negocio y semánticas:** el contrato es frontend-agnóstico; Flutter es cliente inicial confirmado, no dependencia del backend; el dominio utilizado no debe estar codificado previamente para la demostración.
- **Resultado de aceptación observable:** una aplicación Flutter ejecuta un flujo completo y muestra la respuesta del backend generado mediante el contrato.
- **Trazabilidad:** RF-12, RF-13; RNF-07, RNF-09; P09, P08, P07; S1-07, S1-12; decisión confirmada y análisis derivado.

#### CU-22 — Usar IA en cliente sin conexión

- **Ciclo y prioridad:** C3 · P2 cierre obligatorio.
- **Propósito:** mantener una interacción de IA relevante para el cliente cuando el dispositivo no tiene conectividad.
- **Actor iniciador y actores de apoyo:** inicia A6; apoyan A4 Asistente de IA local, P09, P05 y P04.
- **Disparador:** el usuario ejecuta la interacción de IA definida y luego se desconecta la red, o inicia directamente el flujo offline.
- **Precondiciones:** CU-21 está disponible; el runtime local/en dispositivo de Flutter está separado del runtime local del backend; ambos usan la identidad Qwen 3.5 0.8B, pero no comparten proceso ni memoria mutable; la compatibilidad exacta del artefacto móvil y sus recursos está pendiente de verificación técnica.
- **Flujo principal:** 1. El usuario verifica o activa la desconexión. 2. La aplicación carga el recurso local. 3. El usuario realiza la interacción acordada. 4. La IA procesa localmente y muestra el resultado. 5. La aplicación conserva el estado y operaciones pendientes. 6. Al volver la red, sincroniza sin duplicar ni perder resultados, si esa sincronización forma parte del flujo elegido.
- **Flujos alternativos y excepciones:** **A1.** Si el modelo local no está disponible, la aplicación informa la limitación y ofrece el estado offline permitido. **E1.** Memoria, almacenamiento o latencia insuficientes bloquean la interacción con diagnóstico. **E2.** Un resultado ambiguo requiere revisión. **E3.** La reconexión fallida conserva la cola local sin simular sincronización.
- **Postcondición de éxito:** la interacción definida produce un resultado observable sin servicio remoto y queda lista para sincronizarse conforme al alcance.
- **Garantía mínima ante falla:** no se pierden entradas ni se presentan resultados remotos como locales; el estado local es recuperable.
- **Reglas de negocio y semánticas:** la IA móvil local se ejecuta en Flutter y no depende de red; es independiente del runtime local del backend y no comparte memoria mutable; el modelo Qwen, artefacto, runtime, cuantización, preprocesador y hardware objetivo se validan técnicamente; no se presenta un resultado remoto como local.
- **Resultado de aceptación observable:** con la red desconectada, el cliente ejecuta la interacción acordada y muestra su resultado; el criterio de latencia y modelo usado queda registrado.
- **Trazabilidad:** RF-14, RF-15; RNF-08, RNF-09; P09, P05, P04; S1-12; decisión confirmada y análisis derivado.

#### CU-23 — Ejecutar demostración integral con dominio nuevo

- **Ciclo y prioridad:** C3 · P2 cierre obligatorio.
- **Propósito:** demostrar en un único recorrido que el producto integra modelado, asistencia, colaboración, interoperabilidad, generación, cliente e IA local sobre un dominio no preparado previamente.
- **Actor iniciador y actores de apoyo:** inicia A8 Docente/evaluador; apoyan A1, A2, A3, A4, A5, A6 y A7.
- **Disparador:** el evaluador proporciona o selecciona el dominio durante la presentación.
- **Precondiciones:** los casos habilitantes de los ciclos anteriores fueron verificados; existe un entorno de demostración preparado; el dominio será proporcionado por el examen, AWS es el objetivo real preparado antes de la defensa y Floci es la alternativa local/emulada; Q9 fija la secuencia exacta.
- **Flujo principal:** 1. El evaluador propone un dominio no codificado. 2. El equipo crea y abre un proyecto. 3. Modela clases, características y relaciones manualmente. 4. Incorpora una instrucción textual, una voz y una fotografía mediante propuestas revisables; la propuesta fotográfica puede ser parcial y el equipo puede completarla y reposicionarla manualmente antes de confirmar. 5. Muestra una colaboración o reconciliación definida. 6. Importa o exporta XMI según el recorrido acordado. 7. Selecciona perfil y genera el backend. 8. Verifica compilación, inicio y contrato. 9. Ejecuta un flujo desde el cliente. 10. Muestra la interacción de IA y repite la parte offline definida. 11. Presenta resultados, advertencias y límites sin ocultar pendientes.
- **Flujos alternativos y excepciones:** **A1.** Si el recorrido completo excede aproximadamente tres minutos, se usa una secuencia ensayada que conserva evidencia de cada capacidad obligatoria. **E1.** Un fallo de una capacidad no se reemplaza por una afirmación; se registra el punto de fallo y la evidencia disponible. **E2.** Si el evaluador cambia el dominio, no se reutilizan clases preparadas para ocultar la falta de generalidad. **E3.** Si AWS no está disponible durante la defensa, Floci puede utilizarse únicamente como respaldo diagnóstico; debe informarse explícitamente que esa ejecución local no demuestra el despliegue real obligatorio.
- **Postcondición de éxito:** el evaluador observa un flujo vertical reproducible y puede distinguir capacidades demostradas, límites y preguntas pendientes.
- **Garantía mínima ante falla:** el equipo conserva el modelo, generación, informe y diagnóstico; una demostración incompleta no se presenta como cumplimiento integral.
- **Reglas de negocio y semánticas:** todas las funcionalidades mínimas siguen siendo obligatorias; el dominio del examen es el que se proporciona durante la defensa; el recorrido no puede depender de clases de dominio codificadas previamente; AWS debe estar preparado antes de la defensa; Q9 es el único pendiente de producto y la restricción temporal ordena la presentación, no reduce requisitos.
- **Resultado de aceptación observable:** con un dominio nuevo, se muestra el camino desde modelo hasta cliente y la interacción offline, con evidencia verificable de cada tramo que se declare cumplido.
- **Trazabilidad:** RF-01, RF-02, RF-03, RF-04, RF-05, RF-06, RF-07, RF-08, RF-09, RF-10, RF-11, RF-12, RF-13, RF-14, RF-15, RF-16; RNF-01, RNF-02, RNF-03, RNF-04, RNF-05, RNF-06, RNF-07, RNF-08, RNF-09, RNF-10, RNF-11; P01, P02, P03, P04, P05, P06, P07, P08, P09; S1-01, S1-02, S1-03, S1-04, S1-05, S1-06, S1-07, S1-08, S1-09, S1-10, S1-11, S1-12, S1-13 según capacidad; decisiones confirmadas, análisis derivado y Q9 abierta.

### 3.8 Matriz de trazabilidad de requisitos a casos de uso

La matriz es simétrica con los campos `Trazabilidad` de las 23 fichas: cada relación RF declarada en una ficha aparece aquí y cada RF de la matriz aparece en al menos una ficha. Los RNF se mantienen como condiciones transversales dentro de las fichas y del catálogo de calidad.

| Requisito | Casos de uso que lo realizan | Resultado verificable |
| --- | --- | --- |
| RF-01 | CU-01, CU-02, CU-23 | Proyecto nuevo, guardado, cierre y recuperación sin perder modelo ni disposición. |
| RF-02 | CU-04, CU-05, CU-06, CU-08, CU-16, CU-23 | Clasificadores, características y relaciones dentro de la línea base UML acordada y validados. |
| RF-03 | CU-04, CU-05, CU-06, CU-07, CU-23 | Edición manual semántica y visual convertida en operaciones verificables. |
| RF-04 | CU-03, CU-13, CU-14, CU-15, CU-23 | Participantes autorizados, cambios sincronizados y conflictos o colas offline señalizados. |
| RF-05 | CU-09, CU-10, CU-23 | Instrucción textual convertida en propuesta y aplicada solo después de validar y confirmar. |
| RF-06 | CU-04, CU-05, CU-06, CU-07, CU-08, CU-09, CU-10, CU-11, CU-12, CU-14, CU-15, CU-16, CU-17, CU-23 | Entradas manuales, asistidas, colaborativas y XMI convergen en un único modelo canónico. |
| RF-07 | CU-11, CU-23 | Voz transcrita y convertida en comando por el mismo flujo textual. |
| RF-08 | CU-12, CU-23 | Fotografía convertida en propuesta editable y revisable con cobertura explícita, confianza, faltantes y disposición aproximada cuando esté disponible. |
| RF-09 | CU-16, CU-17, CU-23 | Perfil EA/XMI declarado, intercambio probado y pérdidas reportadas. |
| RF-10 | CU-19, CU-20, CU-23 | Backend generado, determinista, compilable, iniciable y verificable. |
| RF-11 | CU-18, CU-19, CU-20, CU-23 | Backend completo con cuatro capas, componentes de apoyo y artefactos de ejecución/despliegue. |
| RF-12 | CU-01, CU-04, CU-08, CU-16, CU-18, CU-19, CU-20, CU-21, CU-23 | Dominio nuevo recorrido desde proyecto y modelo hasta backend, cliente y demostración. |
| RF-13 | CU-21, CU-23 | Cliente Flutter o compatible consume un flujo completo sin modificar la lógica generada. |
| RF-14 | CU-22, CU-23 | IA observable en una interacción del cliente durante el cierre. |
| RF-15 | CU-22, CU-23 | IA del cliente Flutter continúa funcionando sin conectividad con recurso local. |
| RF-16 | CU-04, CU-05, CU-06, CU-07, CU-09, CU-10, CU-14, CU-15, CU-16, CU-17, CU-23 | Toolbar, atajos, estado de guardado/sincronización y entradas de colaboración/IA son descubribles y verificables. |

> **Cobertura:** RF-01 a RF-16 aparecen exactamente en la matriz y cada uno tiene al menos un caso asignado. No se agregan casos de uso artificiales para cubrir RF-16: sus acciones se ejercitan en los casos existentes.

## 6. Registro de decisiones y pendiente restante

Las respuestas consolidadas se clasifican aquí para que no vuelvan a aparecer como preguntas sin resolver. Q3 es una tarea de prueba, no una pregunta de producto. Q9 es la única pregunta de producto abierta.

| ID | Estado | Registro canónico |
| --- | --- | --- |
| Q1 | Cerrada — decisión confirmada | Subconjunto UML 2.5 explícito; no se afirma cobertura completa. |
| Q2 | Cerrada — decisión confirmada | EA 15.0.1514 build 1514; perfil primario observado de `Prueba3.xml` (XMI 2.1/UML 2.1, EA 6.5 y diagrama). |
| Q3 | Cerrada como tarea de prueba | Ejecutar round-trip EA → aplicación → XMI → EA con el fixture copiado y registrar preservación/pérdidas. |
| Q4 | Cerrada — decisión confirmada | Backend completo con JPA, persistencia, aplicación/servicios, REST, DTO/mapeadores, validación/errores, PostgreSQL/migraciones, OpenAPI, pruebas, Docker Compose, verificación, seguridad, auditoría y despliegue preparado. |
| Q5 | Cerrada — decisión confirmada | Cuatro capas mínimas: dominio/entidades, repositorios/persistencia, servicios/aplicación y controladores/API; el resto es apoyo. |
| Q6 | Cerrada — contrato de comportamiento | Política observable de colaboración, conflictos, offline, revocación, undo/redo, cascadas, nombres duplicados, estado visual y SLO de RNF-11; CRDT no es requisito textual. |
| Q7 | Cerrada — decisión confirmada | Existen dos runtimes independientes con Qwen 3.5 0.8B: backend-local en Docker y móvil-local/en dispositivo para Flutter. No comparten proceso ni memoria mutable; la compatibilidad exacta de artefacto, runtime, cuantización y preprocesador es una compuerta de verificación. |
| Q8 | Cerrada — decisión confirmada | La fotografía produce una propuesta editable, posiblemente parcial, con cobertura explícita, confianza por elemento, faltantes, incertidumbres y disposición aproximada cuando esté disponible. El usuario puede completarla y reposicionarla manualmente; la revisión humana y la validación UML preceden cualquier mutación canónica. |
| Q9 | Abierta — diferida al Ciclo 3 | Con evidencia obtenida en el último ciclo, ¿qué criterios permiten evaluar la eficiencia integral de la aplicación, cuál es la secuencia exacta de aproximadamente tres minutos y cuál es la interacción exacta del asistente Flutter? |
| Q10 | Cerrada — decisión confirmada | AWS es el objetivo real primario preparado antes de la defensa; Floci es desarrollo/emulación local; no se crean recursos facturables en esta tarea. |

### 6.1 Evidencia requerida para Q3

- [ ] Importar [`docs/fixtures/xmi/Prueba3.xml`](../fixtures/xmi/Prueba3.xml) con el perfil EA primario.
- [ ] Exportar desde la aplicación al mismo perfil XMI.
- [ ] Abrir/verificar el resultado en EA 15.0.1514 build 1514.
- [ ] Comparar IDs estables, elementos soportados, relaciones y geometría.
- [ ] Registrar toda pérdida, advertencia o incompatibilidad; no declarar conservación total por inferencia.

### 6.2 Único pendiente de producto: Q9, diferida al Ciclo 3

Q9 permanece abierta hasta el último ciclo. Allí se reunirá evidencia para acordar cómo evaluar la eficiencia integral de la aplicación y, a partir de esos resultados, fijar la secuencia exacta de aproximadamente tres minutos y la interacción precisa del asistente Flutter. No se anticipan métricas globales nuevas: RNF-11 conserva sus objetivos colaborativos y cualquier criterio adicional deberá registrarse al resolver Q9.

## 7. Cierre de captura de requisitos

- [x] Producto y límites separados del contexto narrativo.
- [x] Decisiones del proyecto separadas de hechos de fuente, análisis y diseño pendiente.
- [x] Catálogo de actores del diagramador.
- [x] Catálogo de requisitos funcionales RF-01 a RF-16.
- [x] Catálogo de requisitos de calidad RNF-01 a RNF-11.
- [x] Catálogo de casos de uso y dependencias: 23 CUs distribuidos 8/9/6, sin ciclos.
- [x] Especificación detallada y trazable de cada caso de uso.
- [x] Matriz RF↔CU simétrica, incluida RF-16.
- [x] Q1, Q2, Q4, Q5, Q6, Q7, Q8 y Q10 cerradas; Q3 registrada como tarea de prueba.
- [ ] Resolver Q9 en el Ciclo 3 con evidencia de eficiencia: criterios de evaluación, secuencia de tres minutos e interacción exacta del asistente.
- [ ] Diagramas gráficos de casos de uso del Ciclo 1, Ciclo 2, Ciclo 3 y vista general.

[Volver al índice](../esqueleto-proceso-desarrollo-parcial-2.md) · [Siguiente: Análisis](02-analisis.md)
