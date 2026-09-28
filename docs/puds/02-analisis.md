# Análisis

> Documento canónico del análisis lógico del PUDS. Organiza las responsabilidades y relaciones derivadas de la captura de requisitos; no reemplaza sus especificaciones ni adelanta el diseño físico.

[Índice del PUDS](../esqueleto-proceso-desarrollo-parcial-2.md) · **Anterior:** [Captura de requisitos](01-captura-de-requisitos.md) · **Siguiente:** [Diseño](03-diseno.md)

## Ruta rápida

| Destino | Contenido |
| --- | --- |
| [Entrada y frontera](#1-entrada-y-frontera-del-análisis) | Baseline y decisiones que gobiernan este análisis. |
| [Invariantes](#2-invariantes-de-análisis) | Condiciones que deben conservarse en todas las realizaciones. |
| [Paquetes lógicos](#3-paquetes-lógicos-propuestos) | P01–P09 y responsabilidades conceptuales. |
| [Relación CU–paquetes](#4-relación-entre-casos-de-uso-y-paquetes) | Realizaciones conceptuales de CU-01–CU-23. |
| [Análisis conceptual](#5-análisis-conceptual-de-casos-de-uso-críticos) | Boundaries, controles, entidades y mensajes mínimos. |
| [Riesgos y decisiones](#6-responsabilidades-riesgos-y-registro-de-decisiones) | Riesgos residuales, decisiones cerradas y Q9. |
| [Cierre](#7-cierre-del-análisis) | Checklist y frontera explícita hacia Diseño. |

## 1. Entrada y frontera del análisis

**Entrada canónica:** este documento toma como entrada el catálogo de requisitos, los actores, las reglas transversales y las 23 especificaciones detalladas de casos de uso definidos en [Captura de requisitos](01-captura-de-requisitos.md).

La captura ya cerró la línea base UML acordada, el perfil primario de Enterprise Architect/XMI, el alcance de backend completo, las cuatro capas mínimas, el comportamiento de colaboración, los dos runtimes locales independientes de IA, la propuesta fotográfica editable y parcial con cobertura explícita, y la frontera Docker/D:/AWS/Floci. Q3 es una tarea de prueba obligatoria; Q9 es la única pregunta de producto abierta.

**Frontera:** el análisis organiza responsabilidades, relaciones y colaboraciones conceptuales. No fija todavía clases de implementación, endpoints definitivos, tablas físicas, protocolos concretos, librerías, plantillas ni la topología detallada de despliegue. Diseño deberá concretar esas decisiones sin reabrir el alcance confirmado. El objetivo real de despliegue es AWS, preparado antes de la defensa; Floci cubre desarrollo o emulación local.

**Frontera de extracción y canon:** una entrada textual, de voz o fotográfica puede producir hechos extraídos, disposición aproximada y una propuesta parcial. En voz, el límite es `audio → transcripción ASR local → interpretación textual con Qwen → propuesta estructurada`. Estos resultados no son comandos ni mutan el modelo canónico. Los elementos inciertos, ilegibles o faltantes permanecen fuera del canon hasta que la persona los revise, complete o descarte, y los comandos resultantes pasen por validación UML y confirmación.

## 2. Invariantes de análisis

| Invariante | Consecuencia para el análisis |
| --- | --- |
| Modelo canónico válido | Todas las entradas terminan en comandos o transformaciones validadas del mismo modelo semántico. Las alternativas incompatibles nunca entran al canon: se conservan en un panel lateral de conflictos con su historial. |
| Sobre de operación trazable | Cada operación contiene `operationId`, autor, objetivos estables, revisión base/causal y precondiciones. El historial es inmutable y permite reconstruir el resultado y su procedencia. |
| Autorización autoritativa | El servidor decide autenticación, autorización y revocación en cada operación; una autorización local no publica ni confirma por sí sola un cambio. |
| Semántica y viewport separados | Posiciones, tamaños, rutas de conectores y agrupaciones son disposición compartida persistente. Zoom, pan, selección, paneles, tema, filtros y grilla son estado personal del viewport. |
| Validación antes de efectos | Toda operación manual, asistida, offline o importada pasa por validación de precondiciones y validación semántica UML antes de modificar el canon, publicar, exportar o generar. |
| Merge seguro | Solo se fusionan automáticamente campos independientes cuando las precondiciones se cumplen y la validación del modelo completo pasa. Los conflictos semánticos permanecen en el panel lateral. |
| Delete-wins atómico | Una eliminación gana mediante tombstone; sus cascadas se aplican como una única operación atómica y una restauración válida invierte el conjunto completo. |
| Colisión de nombres explícita | IDs duplicados se conservan para no perder trabajo y se alertan a todos. La colisión bloquea validación final, publicación, XMI definitivo, generación y despliegue hasta resolverla por renombrado, movimiento o merge explícito. |
| Undo/redo selectivo | Deshacer y rehacer solo afectan acciones reversibles del autor correspondiente. Se expresan como comandos inversos nuevos y se vuelven a validar contra el estado actual. |
| Revocación offline observable | Una operación verificablemente pre-revocación puede aceptarse; una verificablemente post-revocación se rechaza; una operación ambigua queda fuera del canon para revisión administrativa auditada. |
| SLO de colaboración | La respuesta local es ≤100 ms; presencia, cursor y movimiento remoto tienen p95 ≤200 ms; un comando UML remoto es visible en p95 ≤300 ms; la confirmación durable alcanza p95 ≤1 s; el arrastre se coalescea a 10–30 actualizaciones/s; sobre 500 ms se muestra “sincronizando” y sobre 1 s sostenido se declara servicio degradado. Se registran p50, p95, p99 y jitter por métrica. |
| Contrato independiente del cliente | El backend generado y su contrato se pueden consumir desde Flutter u otro cliente sin trasladar reglas de negocio a la interfaz; la generación no depende de ninguno de los runtimes de IA. |
| Propuesta revisable | Texto, voz y fotografía producen propuestas identificables mediante dos runtimes independientes; una propuesta ambigua, destructiva o parcial requiere revisión antes de crear comandos canónicos. La cobertura, confianza por elemento, faltantes, ambigüedades y procedencia (`runtime`, `modelId`, `artifactId`, `executionMode`) se conservan explícitamente. |

## 3. Paquetes lógicos propuestos

Los paquetes siguientes son una propuesta de análisis, no nombres definitivos de módulos ni una decisión sobre la arquitectura física.

| ID | Paquete lógico propuesto | Responsabilidad | Entradas y salidas principales | Dependencias conceptuales |
| --- | --- | --- | --- | --- |
| P01 | Gestión de proyectos y acceso | Crear, abrir, guardar, recuperar y compartir proyectos; persistir modelo, disposición y versiones; administrar integrantes y permisos. | Solicitudes de proyecto y acceso; proyecto recuperable, membresía y estado autorizado. | P02 |
| P02 | Núcleo UML y validación | Representar el subconjunto UML 2.5 confirmado, identificadores estables, referencias, colisiones y reglas de validez. | Modelo o comando candidato; modelo válido, diagnóstico o advertencia. | Ninguna de dominio |
| P03 | Comandos, historial y versionado | Recibir el sobre de operación, validar/aplicar comandos atómicos, mantener historial inmutable, tombstones, cascadas y undo/redo inverso por autor. | Operación con base causal; nueva revisión, rechazo o evidencia de resolución. | P02, P01 |
| P04 | Colaboración y sincronización offline | Propagar operaciones autorizadas, presencia y estado; mantener cola offline; clasificar revocación; resolver merges seguros y conflictos en panel lateral conforme a RNF-11. | Operaciones locales/remotas y revisiones; cambios aceptados, pendientes, rechazados o conflictos explicados. | P01, P02, P03 |
| P05 | Entradas multimodales e IA | Interpretar lenguaje natural, voz y fotografía y producir propuestas estructuradas sin escribir directamente en el modelo. El texto se interpreta con Qwen 3.5 0.8B en el runtime local seleccionado, sin exigir una sintaxis rígida. La fotografía también debe ser interpretada por Qwen 3.5 0.8B y puede producir una propuesta parcial con clases, atributos/tipos, operaciones, relaciones/multiplicidades, cobertura, confianza por elemento, faltantes, ambigüedades, advertencias y disposición aproximada. La interacción concreta del asistente de la demostración se mantiene provisional y se fija únicamente mediante Q9. | Texto, audio o imagen; hechos extraídos, transcripción, disposición aproximada, propuesta, cobertura, confianza, faltantes, advertencias o comandos candidatos. | P02, P03 |
| P06 | Interoperabilidad XMI | Importar y exportar el subconjunto soportado del perfil primario EA 15.0.1514 build 1514, con XMI 2.1/UML 2.1, extensiones EA 6.5 e información de diagrama; conservar IDs, relaciones y geometría representable y reportar pérdidas. | `Prueba3.xml` u otro archivo y perfil; modelo canónico, XMI y reporte de compatibilidad. | P02, P03 |
| P07 | Generación y verificación de backend | Validar, mapear y generar el backend completo, organizado en las cuatro capas mínimas de dominio/entidades, repositorios/persistencia, servicios/aplicación y controladores/API; incluye entidades/relaciones JPA, operaciones de dominio, DTO/mapeadores, validación, errores, PostgreSQL, migraciones, OpenAPI, pruebas, Docker Compose, ejecución reproducible, health/contrato, autenticación, autorización, auditoría y artefactos de despliegue. | Modelo y perfil válidos; proyecto generado, evidencia de verificación o diagnóstico. | P02, P03, P08 |
| P08 | Contratos y servicios generados | Definir el contrato frontend-agnóstico y las reglas de interacción que consumen los clientes; acompañar servicios generados sin trasladar la lógica de negocio a la interfaz. | Modelo de API y configuración; OpenAPI, DTO, errores y respuestas del backend. | P02 |
| P09 | Clientes y demostración | Consumir el contrato desde Flutter, ejecutar el flujo del dominio nuevo y demostrar los dos runtimes locales de IA sin acoplarlos. El runtime del backend corre en Docker y puede desplegarse como contenedor en AWS; Floci es el emulador local de AWS. El runtime móvil corre localmente/en dispositivo en Flutter y no comparte proceso ni memoria mutable. En el Ciclo 3 se reúne evidencia para resolver Q9: evaluación de eficiencia, interacción exacta del asistente y secuencia de aproximadamente tres minutos. | Contrato, eventos, estado de red y entradas; interacción observable, evidencia de eficiencia y límites. | P04, P05, P07, P08 |

### 3.1 Dirección tecnológica abierta, sin mandato de producto

El contrato de colaboración exige comportamiento observable: operaciones trazables, servidor autoritativo, historial inmutable, cola offline, validación UML, merges seguros, conflictos explicables y cumplimiento de RNF-11. No exige CRDT ni otra biblioteca o algoritmo.

Como dirección de análisis para Diseño se evaluará una combinación híbrida de CRDT/merge semántico, registro de comandos, servidor autoritativo y validador UML. La decisión debe comparar complejidad, verificabilidad, operación offline y alcance de la demostración. Esta dirección no convierte CRDT en requisito funcional, RNF ni criterio de aceptación.

## 4. Relación entre casos de uso y paquetes

| Caso de uso | Paquete principal | Paquetes de apoyo | Realización o condición analizada |
| --- | --- | --- | --- |
| CU-01 | P01 | P03 | Crea proyecto, modelo canónico vacío, disposición inicial, identidad y primera versión. |
| CU-02 | P01 | P03, P02 | Recupera modelo, disposición persistente e historial sin alterar IDs ni validez. |
| CU-03 | P01 | P04, P03 | Administra membresías; el servidor aplica autenticación, autorización y auditoría. |
| CU-04 | P02 | P01, P03 | Usa la línea base UML cerrada; conserva IDs en colisiones y aplica cascadas delete-wins atómicas. |
| CU-05 | P02 | P03 | Valida características, firmas, tipos, multiplicidades y modificadores como operaciones trazables. |
| CU-06 | P02 | P03 | Valida relaciones y extremos; sus eliminaciones respetan tombstones, cascadas e historial. |
| CU-07 | P03 | P02, P04, P09 | Persiste geometría compartida y mantiene viewport personal separado de la semántica. |
| CU-08 | P02 | P03 | Valida la línea base UML cerrada y bloquea efectos posteriores ante errores. |
| CU-09 | P05 | P02, P03 | Produce una propuesta estructurada sobre el modelo existente, sin mutación directa. |
| CU-10 | P03 | P05, P02 | Revisa, corrige, confirma o descarta propuestas antes de aplicar comandos validados. |
| CU-11 | P05 | P02, P03 | Lleva voz a texto y al mismo flujo de propuesta, validación y aplicación. |
| CU-12 | P05 | P02, P03 | Produce una propuesta fotográfica editable y posiblemente parcial, con cobertura, confianza por elemento, faltantes, advertencias y disposición aproximada visibles; la extracción permanece fuera del canon hasta revisión. |
| CU-13 | P01 | P04, P03 | Gestiona participantes y permisos; toda operación posterior queda bajo autorización del servidor. |
| CU-14 | P04 | P01, P02, P03 | Sincroniza sobres de operación, presencia y disposición conforme a RNF-11. |
| CU-15 | P04 | P01, P02, P03 | Aplica merge seguro, conflictos laterales, revocación offline, tombstones y undo/redo selectivo. |
| CU-16 | P06 | P02, P03 | Importa el perfil primario EA/XMI cerrado y usa `Prueba3.xml` como fixture canónico. |
| CU-17 | P06 | P02, P03 | Exporta el perfil primario y ejecuta la prueba round-trip obligatoria de Q3. |
| CU-18 | P07 | P02, P08 | Selecciona un perfil para el backend completo y las cuatro capas mínimas confirmadas. |
| CU-19 | P07 | P02, P03, P08 | Genera de forma determinista desde versión, perfil y plantillas identificados. |
| CU-20 | P07 | P02, P08 | Verifica estructura, seguridad, auditoría, compilación, inicio, health, contrato, persistencia, pruebas y reproducibilidad. |
| CU-21 | P09 | P07, P08 | Consume el backend generado desde Flutter mediante el contrato frontend-agnóstico. |
| CU-22 | P09 | P04, P05, P08 | Ejecuta el runtime móvil local/en dispositivo de Flutter sin conexión, separado del runtime local del backend, y conserva estado/cola recuperable. |
| CU-23 | P09 | P01, P02, P03, P04, P05, P06, P07, P08 | Integra dominio nuevo, modelado, asistencia, XMI, generación, cliente, AWS preparado, Floci local/emulado y la secuencia provisional de Q9. |

La relación no introduce requisitos nuevos: traduce los 23 casos de uso a responsabilidades asignables. Diseño podrá cambiar la distribución física, pero debe conservar estas responsabilidades y sus invariantes.

## 5. Análisis conceptual de casos de uso críticos

La vista identifica responsabilidades `boundary`, `control` y `entity` para orientar diagramas de comunicación. No constituye todavía un diseño de clases, componentes o endpoints.

| Caso | Boundary / entrada | Control conceptual | Entidades o información | Colaboración principal |
| --- | --- | --- | --- | --- |
| CU-04 a CU-08 Edición manual | Lienzo, propiedades, toolbar, atajos y validador | Constructor/aplicador de comandos y validador UML | Proyecto, modelo canónico, clasificador, característica, relación, disposición, versión, colisión | Usuario → interfaz → sobre de operación → autorización → validación → modelo/historial → vista; una colisión queda en panel lateral y una cascada es atómica. |
| CU-09/CU-10 Asistencia textual | Panel de asistente y previsualización | Intérprete, revisor y aplicador de propuestas | Instrucción, modelo base, comando, confianza, advertencia, autor, revisión | Texto → IA → propuesta → revisión/confirmación → validación → comando/historial → modelo. |
| CU-11/CU-12 Multimodalidad | Captura de voz/fotografía y reporte de detección | ASR local, runtimes Qwen independientes, extractor de hechos/disposición y productor de propuesta | Audio, transcripción, imagen, clases, atributos/tipos, operaciones, relaciones/multiplicidades, hechos detectados, cobertura, faltantes, ambigüedades, geometría aproximada, confianza por elemento, procedencia (`runtime`, `modelId`, `artifactId`, `executionMode`), advertencias y comandos candidatos | Audio → ASR local → texto → Qwen; fotografía → Qwen con preprocesamiento compatible → propuesta parcial revisable → CU-10 → comandos confirmados → validación UML → modelo canónico; lo incierto queda fuera y nunca se sobrescribe automáticamente. |
| CU-13 a CU-15 Colaboración | Invitación, sesión, estado de conexión y panel lateral de conflictos | Gestor de acceso, sincronizador, clasificador de revocación y reconciliador | Participante, permiso, operación, autor, target estable, base causal, precondición, revisión, cola, tombstone, conflicto, vista | Acceso servidor → operación local → cola/canal → autorización servidor → merge seguro y validación → canon o panel/historial → sesiones. |
| CU-16/CU-17 XMI | Selector de archivo, perfil y reporte | Adaptador, normalizador y comparador de round-trip | Modelo, ID, perfil EA/XMI, fixture, geometría, pérdida, advertencia, archivo | Archivo ↔ adaptador ↔ modelo canónico validado; `Prueba3.xml` alimenta Q3 y toda pérdida se registra. |
| CU-18 a CU-20 Generación | Perfil, solicitud e informe de verificación | Validador, mapeador, generador y verificador | Modelo, perfil, plantilla, artefacto, cuatro capas, contrato, seguridad, auditoría, evidencia | Versión válida → perfil → modelo intermedio → plantillas → backend completo → compilación/inicio/health/contrato/pruebas. |
| CU-21/CU-22 Cliente e IA local | Aplicación Flutter, cliente API y estado de red | Cliente de contrato, runtime móvil local y sincronizador | OpenAPI, solicitud, respuesta, modelo Qwen, procedencia, resultado, operación pendiente, evidencia | Flutter → contrato → backend → resultado; sin red: Flutter → runtime móvil local independiente → resultado/cola recuperable. El runtime del backend sigue separado. |
| CU-23 Demostración integral | Interfaz de presentación, AWS preparado, Floci y evidencias | Orquestador del recorrido | Dominio nuevo, proyecto, modelo, XMI, backend, cliente, IA local, informe, Q9 | Evaluador → modelado → entradas asistidas → XMI → generación → cliente → IA offline → evidencias y límites. |

### 5.1 Mensajes conceptuales mínimos

1. **Edición manual:** usuario → interfaz → operación con `operationId`, autor, targets estables, base causal y precondiciones → autorización del servidor → validación UML → modelo/historial → interfaz.
2. **IA textual y multimodal:** entrada → interpretación o extracción de bajo consumo → hechos/layout y propuesta parcial o estructurada → revisión/confirmación → comando validado → modelo/historial. Los hechos extraídos y la disposición aproximada no son comandos canónicos; lo incierto permanece fuera del canon.
3. **Colaboración:** acceso autorizado → operación local → cola o canal compartido → autorización y orden causal → merge por campo seguro o panel de conflicto → validación → modelo canónico/versionado → sesiones.
4. **Revocación offline:** operación con evidencia temporal suficiente → aceptación pre-revocación, rechazo post-revocación o revisión administrativa si es ambigua; cada resultado queda auditado.
5. **XMI:** archivo → adaptador del perfil primario → validación del modelo canónico o archivo de salida → reporte de compatibilidad; Q3 compara ida y vuelta con el fixture.
6. **Generación:** usuario → perfil → validación del modelo → mapeo → plantillas → backend completo → verificación y evidencia.
7. **Cliente y offline:** usuario → Flutter → contrato/backend o IA local → resultado → cola/sincronización cuando corresponda.
8. **Demostración:** evaluador → dominio nuevo → modelo → XMI/generación verificada → cliente → IA offline → resultados, límites y Q9.

## 6. Responsabilidades, riesgos y registro de decisiones

### 6.1 Riesgos residuales

| Área | Responsabilidad cubierta | Riesgo residual y tratamiento |
| --- | --- | --- |
| Línea base UML | El subconjunto UML 2.5 está cerrado con clasificadores, características, relaciones, restricciones, IDs y disposición separada. | Su superficie sigue siendo amplia para el parcial; se mitiga verificando solo el subconjunto declarado. |
| Canonicalización y fotografía | Las entradas convergen en comandos solo después de revisión y validación; la fotografía produce una propuesta editable, posiblemente parcial, con cobertura, hechos/layout, confianza, faltantes y advertencias visibles. | La visión/OCR puede tener baja confianza y el procesamiento de bajo consumo puede omitir partes; el panel, la edición manual y la confirmación humana impiden inferencias o mutaciones silenciosas. El presupuesto de memoria y el rendimiento deben validarse en el hardware objetivo. |
| Colaboración | Contrato observable de servidor autoritativo, historial, cola, merges seguros, conflictos y revocación. | La combinación híbrida CRDT/merge puede exceder la capacidad de implementación; se mantiene como dirección de análisis, no mandato, y se valida contra la demo. |
| XMI | Perfil EA 15.0.1514 build 1514 y fixture `Prueba3.xml` están fijados. | La equivalencia real requiere ejecutar Q3 y registrar pérdidas, no inferir compatibilidad desde metadatos. |
| Generación | Backend completo, autenticación, autorización, auditoría y cuatro capas mínimas están incluidos en el baseline. | El volumen de artefactos puede afectar el tiempo; CU-20 exige verificación incremental y diagnósticos explícitos. |
| IA dual y cliente | El backend usa un runtime local Qwen 3.5 0.8B en Docker y Flutter usa un runtime local/en dispositivo independiente con la misma identidad de modelo; el contrato de propuesta es compartido y frontend-agnóstico. | La compatibilidad exacta de artefacto, runtime, cuantización y preprocesador, además de recursos del dispositivo, debe verificarse sin reabrir la decisión de Qwen ni acoplar los runtimes. |
| Infraestructura | El runtime local del backend se sirve en Docker, con pesos/caché configurables en D:, y puede desplegarse como contenedor en AWS; Floci cubre desarrollo/emulación local de AWS. | Verificar capacidad de almacenamiento del host y el uso de Docker Desktop WSL/VHDX, que puede consumir espacio en C:, sin inventar recursos AWS ni generar costos innecesarios. |
| Demostración y eficiencia | El dominio se proporciona en el examen y Q9 permanece abierta hasta el Ciclo 3, cuando se disponga de evidencia de la aplicación completa. | Todavía deben acordarse los criterios de eficiencia, la secuencia y la interacción exacta; Q9 no habilita hardcodear un dominio, reducir requisitos ni inventar métricas antes de medir. |

### 6.2 Registro canónico de decisiones

| ID | Estado | Registro |
| --- | --- | --- |
| Q1 | Cerrada — decisión confirmada | El modelo soporta el subconjunto UML 2.5 explícito; no afirma cobertura completa. |
| Q2 | Cerrada — decisión confirmada | EA 15.0.1514 build 1514; perfil primario observado en `Prueba3.xml`: XMI 2.1/UML 2.1, extensiones EA 6.5 e información de diagrama. |
| Q3 | Cerrada como tarea de prueba | Ejecutar EA → aplicación → XMI → EA con el fixture canónico, comparar IDs, semántica soportada, relaciones y geometría, y registrar toda pérdida. No es pregunta abierta. |
| Q4 | Cerrada — decisión confirmada | Backend completo con JPA, persistencia, aplicación/servicios, REST, DTO/mapeadores, validación/errores, PostgreSQL/migraciones, OpenAPI, pruebas, Docker Compose, verificación, seguridad, auditoría y despliegue preparado. |
| Q5 | Cerrada — decisión confirmada | Cuatro capas mínimas: dominio/entidades, repositorios/persistencia, servicios/aplicación y controladores/API; DTO, mapeadores, seguridad, migraciones e infraestructura son apoyo según Diseño. |
| Q6 | Cerrada — contrato de comportamiento | Modelo canónico válido, panel lateral para alternativas, operación trazable, autoridad del servidor, merge seguro, delete-wins, revocación offline, undo/redo selectivo, colisiones de nombres, separación de vista y RNF-11; CRDT no es requisito. |
| Q7 | Cerrada — decisión confirmada | Existen dos runtimes independientes con Qwen 3.5 0.8B: backend-local en Docker y móvil-local/en dispositivo para Flutter. No comparten proceso ni memoria mutable; las comprobaciones exactas de artefacto, runtime, cuantización y preprocesador son gates de verificación técnica. |
| Q8 | Cerrada — decisión confirmada | La fotografía produce una propuesta editable, posiblemente parcial, con cobertura explícita, confianza por elemento, faltantes, incertidumbres, advertencias y disposición aproximada cuando esté disponible. El usuario puede completarla y reposicionarla manualmente; solo la parte revisada, confirmada y validada puede mutar el modelo canónico. |
| Q9 | Abierta — diferida al Ciclo 3 | Con evidencia obtenida en el último ciclo, ¿qué criterios permiten evaluar la eficiencia integral de la aplicación, cuál es la secuencia exacta de aproximadamente tres minutos y cuál es la interacción exacta del asistente Flutter? |
| Q10 | Cerrada — decisión confirmada | AWS es el objetivo real primario preparado antes de la defensa; Floci es desarrollo/emulación local; no se crean recursos facturables en esta tarea. |

## 7. Cierre del análisis

- [x] Entrada canónica y frontera del análisis.
- [x] Invariantes de modelo válido, trazabilidad, autoridad, vistas, merges, cascadas, colisiones, undo/redo, revocación y SLO.
- [x] Paquetes lógicos P01–P09 y responsabilidades completas.
- [x] Relación consistente entre CU-01–CU-23 y paquetes.
- [x] Análisis conceptual de los casos críticos y mensajes mínimos.
- [x] Riesgos residuales y registro de decisiones.
- [x] Q1, Q2, Q4, Q5, Q6, Q7, Q8 y Q10 cerradas; Q3 registrada como tarea de prueba.
- [ ] Ejecutar y documentar la prueba round-trip de Q3.
- [ ] Resolver Q9 en el Ciclo 3 con evidencia de eficiencia: criterios de evaluación, secuencia de tres minutos e interacción exacta del asistente.
- [ ] Desarrollar Diseño: arquitectura física, componentes, datos, contratos finales, plantillas, protocolos y despliegue detallado.

El análisis queda coherente como entrada para Diseño. La implementación de Diseño y la evidencia de Q3 siguen fuera de este documento; ninguna de ellas reabre las decisiones cerradas. Q9 es la única pregunta de producto pendiente y queda diferida explícitamente al Ciclo 3.

[Anterior: Captura de requisitos](01-captura-de-requisitos.md) · [Siguiente: Diseño](03-diseno.md) · [Volver al índice](../esqueleto-proceso-desarrollo-parcial-2.md)
