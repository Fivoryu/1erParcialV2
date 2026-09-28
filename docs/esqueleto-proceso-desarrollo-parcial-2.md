# PUDS — Segundo parcial

> **Estado de esta versión:** se mantienen consolidados los flujos de **captura de requisitos** y **análisis**, y se incorpora el esqueleto D0 del flujo de **Diseño**. El producto documentado es el diagramador colaborativo de clases UML 2.5; la plataforma nacional de salud pertenece únicamente al contexto del enunciado.
>
> **Criterio de alcance:** todas las funcionalidades mínimas enumeradas por el docente son obligatorias. Los ciclos propuestos ordenan la entrega, pero no convierten ningún requisito en opcional.

## Propósito y estado actual

Este archivo es el punto de entrada breve. La información canónica está separada para que cada flujo pueda leerse y revisarse sin recorrer un único documento largo.

**Estado:** captura, análisis y línea base inicial de Diseño (D0–D6) documentados y cerrados para el umbral C1; la implementación por ciclos C1→C2→C3 y los flujos PUDS posteriores quedan pendientes explícitos.

## Orden recomendado de lectura

1. [Captura de requisitos](puds/01-captura-de-requisitos.md): evidencia, alcance, RF/RNF, actores, ciclos, 23 casos de uso y trazabilidad.
2. [Análisis](puds/02-analisis.md): entrada, invariantes, paquetes, realizaciones, modelo conceptual, responsabilidades y riesgos.
3. [Diseño](puds/03-diseno.md): D0–D6 — línea base inicial cerrada para C1; [acta de cierre](puds/cierre/01-cierre-diseno.md).
4. Mantener Q9 abierta hasta el Ciclo 3, cuando se evaluará con evidencia la eficiencia de la aplicación y se cerrarán el recorrido de demostración y la interacción del asistente; revisar también la tarea de prueba Q3.

## Documentos canónicos

| Documento | Uso | Propietario de contenido |
| --- | --- | --- |
| [01 — Captura de requisitos](puds/01-captura-de-requisitos.md) | Define qué debe hacer el producto y cómo se comprueba. | Evidencia y autoridad; objetivo, alcance, decisiones y supuestos; A1–A8; RF-01–RF-16; RNF-01–RNF-11; reglas transversales; C1–C3 y CU-01–CU-23; trazabilidad; Q1–Q10, con Q9 como único pendiente y Q3 como tarea de prueba. |
| [02 — Análisis](puds/02-analisis.md) | Define cómo se organizan conceptualmente esas responsabilidades. | Entrada y frontera; invariantes; P01–P09; relaciones CU–paquetes; análisis conceptual; responsabilidades, riesgos y registro de decisiones; Q9 como único pendiente y Q3 como tarea de prueba. |
| [03 — Diseño](puds/03-diseno.md) | Organiza cómo se transformarán las responsabilidades en una solución técnica verificable. | D0–D6; arquitectura, datos, contratos, secuencias, verificación y acta de cierre D6 (umbral C1); relación con C1–C3. |

## Mapa compacto de propiedad

- **Requisitos:** solo `01-captura-de-requisitos.md` define RF/RNF, actores, fuentes, reglas transversales y las 23 fichas detalladas de CU.
- **Análisis:** solo `02-analisis.md` define paquetes y realizaciones conceptuales; allí se referencian los CU sin repetir sus fichas.
- **Preguntas y tareas:** la captura registra Q1–Q10; Q9 es la única pregunta de producto abierta y queda diferida al Ciclo 3 para evaluar la eficiencia de la aplicación, mientras Q3 es una tarea de prueba. Este índice solo indica la ubicación.
- **Diseño:** `03-diseno.md` define el flujo y el inventario de artefactos; todavía no fija arquitectura física, tecnologías concretas, contratos finales ni despliegue detallado.

## Diseño: línea base inicial cerrada

La línea base de Diseño quedó aprobada para el Ciclo 1 en [03 — Diseño](puds/03-diseno.md) con su [acta de cierre D6](puds/cierre/01-cierre-diseno.md). Pendientes explícitos:

- vista C4 de contexto/contenedores/componentes y ADR ampliado (refinamiento D1, no bloqueante);
- diagramas gráficos de casos de uso de Captura/Análisis (documental);
- ejecutar Q3 para validar el diseño del adaptador XMI en el umbral C2;
- Q9 abierta hasta el Ciclo 3.

Pendientes del diseño detallado de C2/C3 y de los cortes D6 finales:

- refinamiento de colaboración/offline/XMI/IA con evidencia Q3 (antes de C2);
- generación, cliente, despliegue y resolución de Q9 con mediciones (antes de C3);

Q9 continúa abierta hasta el Ciclo 3; Q3 continúa como tarea de prueba.

## Flujos posteriores: pendientes explícitos

La implementación, integración, pruebas completas, despliegue, documentación de usuario y cierre de PUDS no se completan en estos documentos. Solo podrán iniciarse después de cerrar la línea base y aprobar los resultados de captura y análisis.

> **Compatibilidad:** este archivo conserva la ruta original como índice para que cualquier referencia existente siga llegando a una página de navegación; los documentos canónicos enlazan entre sí para la navegación detallada.
