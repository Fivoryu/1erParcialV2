# Guía de implementación — Incremento 4: IA textual para el editor UML

**Fecha:** 2026-10-03 (America/La_Paz)  
**Estado:** guía futura; no implementa ni declara entregada esta funcionalidad.  
**Propósito:** desglosar el alcance en trabajo seguro sin reemplazar requisitos ni hoja de ruta.

En esta guía, **confirmado** señala el alcance vigente; **base observada**, lo verificado en el
código/documentación; **propuesta**, una recomendación no aprobada; y **pendiente**, una decisión
que debe resolverse antes de implementar la parte afectada.

## 1. Resultado y límites

El [plan incremental obligatorio](analisis-enunciado-parcial-1.md#L337) define Incremento 4 como IA
textual, referencias a elementos, previsualización, confirmación, deshacer y manejo de ambigüedad.
El pedido amplía el asistente a crear, editar y eliminar entidades UML soportadas; procesar
solicitudes de varios pasos; explicar y validar; y añadir herramientas manuales y presentación de
relaciones configurable.

**El asistente propone; la persona conserva el control.** Todo cambio requiere una vista previa
explícita y confirmación. Una referencia ambigua se aclara antes de mutar el diagrama. El alcance
queda limitado al catálogo UML habilitado; no promete ejecutar cualquier instrucción.

Voz, interpretación de fotos, inferencia móvil offline y sincronización móvil quedan en los
[incrementos 6 y 7](analisis-enunciado-parcial-1.md#L382). Esta guía solo fija esa frontera futura.

## 2. Base actual verificada

- **Stack:** [`web/package.json`](../web/package.json#L14) declara React 19 y React Flow 12;
  [`backend/pom.xml`](../backend/pom.xml#L8) declara Spring Boot 3.4.5, JPA y PostgreSQL
  (`:31–32`, `:51–57`).
- **Lienzo:** [`UmlCanvas.tsx`](../web/src/diagram/UmlCanvas.tsx#L334) maneja Delete/Backspace
  sobre selección e ignora controles de texto. La barra actual crea clasificadores y ofrece Fit
  to view (`:352–418`); el punto de entrada revisado no confirma Escape ni undo/redo.
- **Geometría:** `UmlCanvas.tsx` convierte posición/tamaño en `geometry.set` y persiste al
  terminar el movimiento (`:84–99`, `:245–280`, `:305–312`).
  [`GeometrySetHandler.java`](../backend/src/main/java/com/diagramador/command/handler/GeometrySetHandler.java#L19)
  guarda rectángulos y waypoints por vista (`:45–62`, `:78–139`), no campos de estilo visual.
- **Relaciones:** [`UmlRelationshipEdge.tsx`](../web/src/diagram/UmlRelationshipEdge.tsx#L515)
  dibuja marcadores/etiquetas y permite agregar o arrastrar quiebres (`:535–579`, `:614–675`).
  El recorrido actual es personalizado.
- **Comandos:** [`CommandService.java`](../backend/src/main/java/com/diagramador/command/CommandService.java#L82)
  valida cada sobre dentro de una transacción, comprueba rol y `baseRevision`, registra
  `ModelCommand`/`ModelVersion`, avanza revisión y publica `operation.applied` (`:82–125`,
  `:136–255`). Reutilizar esta autoridad; no escribir directamente desde el modelo.
- **Colaboración:** el [cierre de Incremento 3](puds/cierre/05-cierre-incremento-3.md#L10)
  confirma edición compartida, cursores/presencia, cambios en vivo, chat y concesiones.
- **Undo/redo:** la inspección puntual de lienzo y servicio no confirmó una API visible al usuario;
  sí comprobó comandos versionados y colaboración. No se afirma que no exista en todo el producto:
  inventariar antes de extenderlo y acordar semántica compartida. No restaurar snapshots locales.

La [captura de requisitos](puds/01-captura-de-requisitos.md#L598) fija Qwen 3.5 0.8B para runtimes
independientes de backend y móvil. La [viabilidad local](puds/verificacion/02-viabilidad-ia-local.md#L121)
mantiene el proveedor determinista como predeterminado: una prueba de modelo no demuestra integración
de producto.

## 3. Flujo propuesto con límites de seguridad

**Texto + diagrama canónico + selección + revisión → modelo local → plan tipado → validación
determinista → vista previa → confirmación → ejecutor normal de comandos → estado actualizado.**

1. Construir contexto mínimo del diagrama, selección, revisión y catálogo permitido. Tratar nombres,
   atributos, notas y demás contenido del diagrama como datos no confiables, nunca como instrucciones.
2. Pedir un plan acotado con intención, operaciones ordenadas, tipos/campos permitidos, IDs presentes
   en el contexto, argumentos tipados, revisión base, explicación y preguntas pendientes.
3. Validar esquema y semántica: operación, tipo UML, campos, existencia/pertenencia de IDs, permiso,
   efectos de borrado, precondiciones y `baseRevision`. JSON válido no implica intención correcta.
   Si el destino no es inequívoco, preguntar; nunca aproximar un objetivo destructivo.
4. Presentar cada operación y efecto lateral antes de habilitar Confirmar. Distinguir explicación o
   validación sin mutación de un plan aplicable.
5. Al confirmar, revalidar estado y permisos del servidor; enviar por el mismo ejecutor manual.
   El modelo no recibe credenciales ni ejecuta SQL, código arbitrario o escrituras directas.
6. Si cambió la revisión desde la vista previa, rechazar el plan obsoleto y pedir revisar el contexto
   actualizado. No reconstruir y aplicar silenciosamente sobre datos nuevos.
7. Definir antes de programar si un plan de varios comandos es todo-o-nada o admite aplicación
   parcial. `CommandService.execute` demuestra transacción por comando, no por secuencia. Informar
   pasos aceptados/rechazados; no llamar atómica a varias peticiones ni simular rollback.
8. Cancelar antes del envío no produce mutación. Si la petición ya está en curso, reconciliar el
   resultado del servidor antes de repetirla; cancelar no equivale a deshacer una escritura.

## 4. Modelos, evaluación y recursos

**Base vigente:** no se reemplaza Qwen3.5-0.8B. **Propuesta:** evaluar primero Qwen3.5-2B para el
agente de diagramas, mantener 0.8B como referencia y considerar 4B solo si 2B no alcanza un umbral
acordado y el hardware lo permite. LFM2.5-1.2B-Instruct queda como candidato de texto móvil futuro,
no como reemplazo aprobado para la web. Las tarjetas oficiales de [Qwen 0.8B](https://huggingface.co/Qwen/Qwen3.5-0.8B),
[2B](https://huggingface.co/Qwen/Qwen3.5-2B) y [4B](https://huggingface.co/Qwen/Qwen3.5-4B)
declaran Apache-2.0.

Las tarjetas del autor informan BFCL-V4 Thinking: 2B 43,6 frente a 0.8B 25,3; IFEval
Non-Thinking: 2B 61,2 frente a 0.8B 52,1. Son resultados del autor, no exactitud, latencia ni
seguridad medidas en este editor.

| Archivo candidato | Cuantización / tamaño listado | Estado |
| --- | --- | --- |
| [ggml-org Qwen 0.8B](https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF/tree/main) | Q4_0, 563 MB | Listado actual; no igualar al artefacto histórico de 526,50 MiB del proyecto. |
| [Unsloth Qwen 2B](https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/tree/main) | Q4_K_M, 1,28 GB | Conversión de Unsloth, no GGUF oficial de Qwen. |
| [Unsloth Qwen 4B](https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/tree/main) | Q4_K_M, 2,74 GB | Conversión de Unsloth, no GGUF oficial de Qwen. |
| [Liquid LFM 1.2B](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF/tree/main) | Q4_0, 696 MB | Candidato futuro de texto móvil, no decisión. |

Son tamaños de archivos de pesos, no RAM. No hay medidas fiables de RAM pico por proceso Qwen en
los dispositivos objetivo. La viabilidad previa registra 0,17 GB libres del sistema tras cargar
un artefacto en un equipo concreto: no es consumo atribuible al proceso ni garantía de objetivo.
Medir por hardware/runtime carga fría y caliente, RAM pico del proceso y del sistema, latencia
p50/p95, contexto y calidad sobre tareas UML. El umbral queda **pendiente de acuerdo**.

El listado 2B incluye `mmproj-F16` de 668 MB; junto con Q4_K_M, suma aprox. 1,95 GB en pesos y
proyector solamente. `llama.cpp` requiere un archivo de proyector separado para multimodalidad;
runtime, caché y datos suman más. Es costo futuro de incrementos 6/7, no de este incremento.

**Despliegue confirmado:** todos los componentes de aplicación del servidor, incluido runtime y
servicio de IA, se autogestionan en contenedores. El servicio IA es interno, accesible solo por el
backend, y usa volumen persistente para pesos. No usar equivalentes gestionados ni inferencia paga;
no agregar fallback pago silencioso. `llama.cpp` es candidato; Docker Model Runner fue un ensayo,
no decisión final. Fijar runtime/imagen tras evaluación y cargar un candidato por entorno. AWS y
sus costos de host/red/almacenamiento requieren despliegue posterior y autorización separada; aquí
no se crean recursos.

LFM no debe llamarse irrestricto: su licencia `lfm1.0` limita uso comercial para entidades que
alcancen el umbral de USD 10 millones de ingresos anuales. Revisar términos y uso previsto antes de
seleccionar/redistribuirlo ([tarjeta](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct),
[licencia](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF/blob/main/LICENSE)).

## 5. Experiencia de edición

### Asistente contextual

- Panel junto al lienzo con selección visible y editable antes de confirmar.
- Estados observables: listo, analizando, aclaración, vista previa, aplicando, completado,
  cancelado y error recuperable; mostrar progreso y ofrecer editar/reintentar/descartar.
- Previsualización interactiva sin inferencia por frame. La viabilidad observó latencia de segundos
  en un equipo de prueba: operar asincrónicamente, sin prometer respuesta instantánea.
- Explicar/validar se separa de proponer cambios. Confirmar solo aparece para un plan válido,
  no ambiguo y revisado.

### Herramientas manuales e historial

- Añadir herramientas contextuales, selección, alineación/distribución y ajuste a grilla; ofrecer
  alternativa de teclado para cada interacción de arrastre.
- Incorporar Ctrl+Z, Ctrl+Y o Ctrl+Shift+Z según convención elegida, además de Delete/Backspace y
  Escape. Limitar atajos al lienzo, no interceptar campos de texto ni combinaciones reservadas.
- Undo/redo debe expresar intención sobre el estado compartido, nunca restaurar un snapshot que
  sobrescriba cambios remotos. Definir precedencia, agrupación de planes, conflictos concurrentes
  y alcance de redo antes de implementarlo.

### Rutas y presentación UML

- Ofrecer rutas rectas, libres y ortogonales; editar puntos, segmentos, extremos y etiquetas;
  permitir restablecer recorrido y usar esquinas redondeadas opcionales.
- Persistir apariencia por vista separada de semántica. `geometry.set` acepta waypoints pero no
  estilo: acordar comando/campos versionados y migración en vez de enviar campos que se rechazan.
- Preservar tipo UML, extremos, marcadores, navegabilidad, multiplicidad y línea sólida/discontinua.
  Evitar colisiones requiere algoritmo explícito; `getSmoothStepPath` genera un trayecto escalonado
  con radio configurable, no editor de segmentos ni garantía de evitar nodos ([React Flow](https://reactflow.dev/api-reference/utils/get-smooth-step-path)).

## 6. Frontera móvil y dominio generado

En el futuro, el runtime del teléfono será independiente: modelo local en almacenamiento privado,
sin Docker y sin servidor/red para responder offline. SQLite guardará dominio y outbox; sincronización,
deduplicación y conflictos pertenecen a su incremento.

El backend generado es un dominio variable y necesita metadatos, esquema, acciones y reglas por
backend/versión/aislamiento. Los comandos UML canónicos no equivalen al CRUD de ese dominio. No
anticipar esa arquitectura dentro de Incremento 4.

## 7. Decisiones pendientes

| ID | Decisión | Evidencia requerida |
| --- | --- | --- |
| DEC-I4-01 | Modelo/artefacto final. | Evaluación 0.8B y 2B; autorización expresa para cambiar la base. |
| DEC-I4-02 | Runtime e imagen IA del servidor. | Soporte de formato, aislamiento, consumo y operación por contenedores. |
| DEC-I4-03 | Catálogo UML, referencias y efectos de borrado. | Contrato de operaciones; ambigüedad sin efectos hasta aclarar. |
| DEC-I4-04 | Atomicidad y fallos parciales del plan. | Diseño transaccional y respuesta a fallos/reintentos. |
| DEC-I4-05 | Undo/redo colaborativo. | Inventario actual y pruebas con cambios remotos intercalados. |
| DEC-I4-06 | Límites y umbral de calidad/latencia. | RAM/p50/p95 por objetivo y aprobación del responsable. |
| DEC-I4-07 | Versión de persistencia de estilo/ruta. | Compatibilidad y migración de vistas existentes. |

## 8. Lista de implementación propuesta

Las tareas están sin iniciar. Usar test-first donde exista un escenario determinista: observar la
falla, implementar y refactorizar manteniendo verde. Esta guía no afirma pruebas ejecutadas.

- [ ] **IA4-01 — Evaluar modelos locales.**  
  **Salida:** comparación 0.8B/2B; 4B si hace falta; LFM solo como candidato móvil futuro.  
  **Prerrequisito:** fijar hardware objetivo y revisar licencias; no descargar ni sustituir sin permiso.  
  **Aceptación:** corpus fijo en español mide exactitud, ambigüedad, esquema, revisión, cancelación,
  carga fría/caliente, RAM pico y latencia p50/p95; registrar hardware/runtime/artefacto. No inventar SLA.
- [ ] **IA4-02 — Definir plan tipado y validador.**  
  **Salida:** contrato estricto de explicación o plan mutador y catálogo cerrado.  
  **Prerrequisito:** IA4-01 y DEC-I4-03.  
  **Aceptación:** ID inexistente, permiso/tipo/campo inválido, borrado no aceptado o revisión obsoleta
  se rechazan sin mutación; destino ambiguo produce una pregunta. Validar semántica, no solo JSON.
- [ ] **IA4-03 — Aplicar planes por el ejecutor normal.**  
  **Salida:** preview/confirm/cancel ligados a revisión y autorización vigentes.  
  **Prerrequisito:** IA4-02 y DEC-I4-04.  
  **Aceptación:** sin escritura previa a confirmar; revalidación servidora; conflicto solicita revisar;
  resultado por paso según política y reintento sin duplicados.
- [ ] **IA4-04 — Resolver undo/redo colaborativo.**  
  **Salida:** compensación compatible con comandos actuales, no snapshot compartido.  
  **Prerrequisito:** inventario actual, IA4-03 y DEC-I4-05.  
  **Aceptación:** cambios posteriores de otra persona no se pierden; conflicto conserva estados;
  recarga/eventos mantienen revisión coherente.
- [ ] **IA4-05 — Construir el panel del asistente.**  
  **Salida:** entrada, aclaración, explicación/validación, preview, confirmación, progreso y errores.  
  **Prerrequisito:** IA4-02/03 y DEC-I4-06.  
  **Aceptación:** planes inválidos/ambiguos no confirmables; selección o refresco invalida preview
  obsoleto; el lienzo sigue operable durante inferencia.
- [ ] **IA4-06 — Completar herramientas y atajos manuales.**  
  **Salida:** acciones contextuales, selección, alineación/distribución, grilla y atajos.  
  **Prerrequisito:** inventario del lienzo e IA4-04.  
  **Aceptación:** selección correcta, alternativa a arrastre, foco visible, no interceptar escritura
  ni atajos reservados.
- [ ] **IA4-07 — Añadir rutas ortogonales y estilo por vista.**  
  **Salida:** modos recto/libre/ortogonal, edición de segmentos/puntos/extremos/etiquetas y reset.  
  **Prerrequisito:** DEC-I4-07.  
  **Aceptación:** guardar/recargar conserva estilo/ruta sin alterar kind, marcadores, navegabilidad,
  multiplicidad o trazos UML; no prometer avoidance sin algoritmo medido.
- [ ] **IA4-08 — Cerrar aceptación integrada.**  
  **Salida:** pruebas de asistente, manual, persistencia y colaboración.  
  **Prerrequisito:** IA4-01 a IA4-07.  
  **Aceptación:** “Cree una clase Pedido con el atributo fecha: Date y asóciela con Cliente” genera
  preview; “Explique la relación seleccionada” no muta; “Elimine la clase” con varias candidatas
  pregunta; “Elimine la clase Inexistente” rechaza; un cambio remoto entre preview y confirm exige
  revalidación. Repetir recarga y dos colaboradores; registrar RAM/latencia reales y límites.

## 9. Handoff al ejecutor futuro

Leer esta guía junto con requisitos y plan autoritativos; reconciliarla con el código actual,
especialmente colaboración, undo y persistencia. Implementar tarea por tarea con evidencia real.
Esta guía no autoriza despliegues, instalaciones, servicios cloud, inferencia paga ni borrados no
revisados.

## Referencias principales

- Proyecto: [incrementos](analisis-enunciado-parcial-1.md#L337),
  [requisitos IA local](puds/01-captura-de-requisitos.md#L598),
  [viabilidad](puds/verificacion/02-viabilidad-ia-local.md#L121),
  [cierre de Incremento 3](puds/cierre/05-cierre-incremento-3.md#L10).
- Código: [`UmlCanvas.tsx`](../web/src/diagram/UmlCanvas.tsx),
  [`UmlRelationshipEdge.tsx`](../web/src/diagram/UmlRelationshipEdge.tsx),
  [`CommandService.java`](../backend/src/main/java/com/diagramador/command/CommandService.java),
  [`GeometrySetHandler.java`](../backend/src/main/java/com/diagramador/command/handler/GeometrySetHandler.java).
- Modelos: [Qwen 0.8B](https://huggingface.co/Qwen/Qwen3.5-0.8B),
  [Qwen 2B](https://huggingface.co/Qwen/Qwen3.5-2B), [Qwen 4B](https://huggingface.co/Qwen/Qwen3.5-4B),
  [GGUF 0.8B](https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF/tree/main),
  [GGUF 2B](https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/tree/main),
  [GGUF 4B](https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/tree/main),
  [LFM](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct),
  [licencia LFM](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF/blob/main/LICENSE),
  [archivos LFM](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF/tree/main).
- Geometría: [React Flow `getSmoothStepPath`](https://reactflow.dev/api-reference/utils/get-smooth-step-path);
  [llama.cpp multimodal](https://github.com/ggml-org/llama.cpp/blob/master/tools/mtmd/README.md).
