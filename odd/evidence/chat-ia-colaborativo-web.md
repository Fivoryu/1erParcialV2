# Evidencia histórica — chat IA colaborativo web
> Documento de apoyo al ODD, no una segunda fuente de requisitos o tareas. Traslado autorizado y documentado el 2026-10-14.
> Estado actual, Specs y Tasks: [ODD principal](../tasks/chat-ia-colaborativo-web.md). Topic de recuperación: `odd/chat-ia-colaborativo-web/evidence`.

## Alcance y conservación

Se trasladan L15, L20-L24 y L26-L27 para reducir el tamaño del espejo principal. El texto de cada entrada se conserva sin cambios de contenido. Las cifras, verificaciones y bloqueos que aparecen abajo son históricos; no sustituyen el estado vigente de L29 del ODD principal. No se ejecutaron de nuevo las pruebas históricas por trasladar sus notas.

## Manifest de integridad

SHA-256 del documento anterior, normalizando solo saltos finales: `fc1b9dc9c0b28a7fbb67717ba610e6088464659e82fd8102e51bc02251650d5e`. Las huellas siguientes corresponden a cada entrada original en UTF-8, ignorando únicamente saltos de línea finales; no se eliminaron párrafos, fuentes o fallos históricos.

| Entrada | SHA-256 del contenido original |
| --- | --- |
| L15 | `0531697c4cd57f6a82fdf889696cc76957c348f3fa916257736aa9936fe3f040` |
| L20 | `5042bfe92ee66e0f9b42e8f33f74032b0ac96bf4273e19997b37e974397506d2` |
| L21 | `a67f3d0b38db2f1e705d69f95b80837774d991b4254a3ba236b8e6a48df9a8c2` |
| L22 | `522b9d1197cebd1ea1e13cd64284744fa1e5dd55279976d0c11d693395221c59` |
| L23 | `26c3cd8e93f3a8b17c63cc8bb5dca336cdbe92821da72f0873a7216b799ae6bc` |
| L24 | `04c57b22b6ec22c95b43efba5dd141f8758cdfcf29003bb26a8afb7fb962d01d` |
| L26 | `6b4af873976b308ae5a198824a580451d83f3d2b6ab651f7c1752e6fbdf231ef` |
| L27 | `d99dd65e2a4db94ac133365ca66cf456f6eee7e8883b2c2fc33626ed91912625` |

## Entradas históricas

### L15 — Diseño base propuesto y aceptado después (documentado 2026-10-14)

- Backend y base de datos existentes como punto de partida; no se decidió incorporar Redis, pgvector, una segunda base o Engram de producto. El mecanismo de búsqueda se decide por evidencia, sin introducir una dependencia por analogía de nombre.
- Tres fuentes separadas: registro de cambios aceptados, conversaciones por sesión y memoria derivada por diagrama. Ninguna sustituye consultar el UML actual.
- Selección y redacción automática de recuerdos por IA; decisiones expresas se distinguen de hipótesis. Conservar evidencia y estado; no promover silenciosamente inferencias a acuerdos.
- Contradicciones entre sesiones se conservan como conflicto; reemplazos expresos conservan el antecedente. No aplicar una regla ciega de «última escritura gana» al conocimiento.
- Contexto compuesto por instrucciones, pregunta, fragmento UML actual, recuerdos pertinentes y últimos turnos necesarios. Las sesiones nuevas no heredan todo el historial.
- Invocación explícita de IA; mensajes humanos no disparan inferencia por defecto. Una inferencia activa inicial; mantenimiento en bloques con prioridad para consultas de usuarios, límites por medir. No prometer que la compactación es gratuita o instantánea.
- La actividad mostrada corresponde a trabajo real: consultando datos, recuperando fuentes, esperando, interpretando imagen o compactando; explicación breve del resultado. Streaming de tokens y activación de thinking son decisiones distintas, no requisitos aprobados.
- Mantener planes de edición revisables, revisión base y revalidación antes de aplicar. Una respuesta basada en una revisión anterior debe identificarse; cambios remotos no autorizan aplicar un plan vencido.
- Un resumen de conversación no reemplaza registros originales ni confirma reglas de negocio. Si faltan datos, señalar el alcance parcial y pedir aclaración.

### L20 — Mapa estático de reutilización y brechas (2026-10-14)

Evidencia obtenida por un explorador de solo lectura y contraste puntual del proveedor por el padre. No reemplaza la relectura al retomar; no es una corrida funcional.

| Fuente del repositorio | Hallazgo al diseñar | Consecuencia para la implementación futura |
| --- | --- | --- |
| `backend/src/main/java/com/diagramador/assistant/AssistantPlanService.java:48-60,91-140` | Construye contexto semántico del modelo del proyecto para una consulta | No existe todavía presupuesto/recuperación por conversación ni prueba de aislamiento por vista; contextualizar por diagrama |
| `backend/src/main/java/com/diagramador/assistant/LlamaCppAssistantProvider.java:69-135` | Una consulta de texto más contexto; `max_tokens=512`, `stream=false`, `enable_thinking=false` | Salida máxima no equivale a ventana; no hay visión/streaming por habilitar un botón |
| `backend/src/main/java/com/diagramador/collaboration/ChatService.java:52-103` | Chat humano persistente y publicación después del commit | Reutilización posible, pero ámbito actual proyecto/modelo y autor humano no satisfacen por sí solos el nuevo alcance |
| `backend/src/main/java/com/diagramador/domain/DiagramMessage.java:18-50` | Mensajes existentes ligados al modelo | Revisar identidad de diagrama/sesión y mensajes IA antes de migrar |
| `backend/src/main/java/com/diagramador/collaboration/ProjectEventBus.java:17-60` | Bus SSE en memoria, una instancia | Reutilizar sin asumir entrega durable; reconstruir desde persistencia al reconectar |
| `backend/src/main/java/com/diagramador/api/ProjectEventController.java:36-49` | Eventos por proyecto | No difundir contenido de un diagrama a espectadores no autorizados |
| `web/src/pages/ProjectEditorPage.tsx:249-291,373-418` | Carga de historial y deduplicación de mensajes/eventos existentes | Preservar comportamiento y ampliar sesiones/respuestas IA sin duplicación |
| `web/src/assistant/assistantController.ts:49-79,116-120` y `web/src/api/assistant.ts:45-51,129-141` | Consulta individual, sin historial enviado | Las sesiones conversacionales son capacidad nueva, no un cambio del máximo arquitectónico del modelo |
| `web/src/assistant/AssistantPanel.tsx:188-204,210-278,304-347` | Texto, estados y propuestas; dictado del navegador | No hay entrada visual en esta ruta; integrar medios y nuevos estados sin romper confirmación |
| `web/src/assistant/speechRecognition.ts:47,133-144` | Web Speech API, `es-AR`, voz a texto | Reconocimiento completamente local no establecido; respetar soporte/permiso y envío explícito |
| `backend/src/main/java/com/diagramador/assistant/AssistantApplyService.java:76-132` | Revalidación de revisión/objetivos antes de aplicar | Preservar barrera para planes obsoletos y permisos |

Antecedentes: `odd/tasks/incremento-4-ia-textual.md` y `odd/tasks/voz-asistente-ia.md`. Son historia de otras unidades; no copiar sus estados de completitud como evidencia de esta feature. `SOFTWARE.md` describe el intento anterior y no es autoridad del código actual.

### L21 — Ventana y ruta visual: observado frente a no probado (2026-10-14)

- `tools/ai-eval/results/qwen-2b-v2/report.md:5,34` y `tools/assistant-acceptance/assistant_acceptance.py:1647`: Qwen3.5-2B Q4_K_M evaluado/arrancado por el arnés con contexto **2048**; no prueba la configuración de un servidor de producto activo.
- El proveedor fija **512 tokens máximos de salida**. Ejemplo matemático, no capacidad nueva medida: con ventana 2048 y reserva 512, entrada máxima 1536; el 80% es aproximadamente 1229. La cifra real debe incluir plantilla, tokens especiales y costo visual del runtime.
- [Configuración oficial del modelo base](https://huggingface.co/Qwen/Qwen3.5-2B/blob/main/config.json): `max_position_embeddings=262144` y configuración visual. No equivale a capacidad práctica garantizada del GGUF/runtime/hardware instalados.
- [Inventario público GGUF](https://huggingface.co/api/models/unsloth/Qwen3.5-2B-GGUF/tree/main): existen `Qwen3.5-2B-Q4_K_M.gguf` y proyectores; `mmproj-F16.gguf` tiene 668227264 bytes. Es tamaño del archivo, no RAM medida. No se descargaron pesos.
- [Documentación actual de llama.cpp](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md): ruta multimodal mediante proyector `--mmproj` y chat; documentada como experimental en la consulta. No confirma compatibilidad de la instalación registrada históricamente como build `b11388`.
- Consulta de procesos Windows mediante PowerShell: salida `NO_RUNNING_LLAMA_SERVER`, exit 0. No encontró `llama-server.exe`; no descarta contenedores o servidores remotos. No se verificó capacidad operativa actual ni se arrancó inferencia.
- Pendientes reales: calidad de comprensión/manuscritos/flechas, latencia, RAM, tokens visuales, calidad de compactación/extracción de recuerdos y origen del reconocimiento de voz. No hay umbrales de aceptación numéricos acordados: T1 los fija con evidencia y decisión humana cuando corresponda.

### L22 — Verificación futura y entrega (2026-10-14)

- T1 debe cerrar premisas que pueden invalidar la ruta visual o el presupuesto antes de construir sobre ellas. Si falla una capacidad, presentar alternativa y decisión; no cambiar de modelo, instalar OCR, subir datos a servicios externos ni descargar pesos silenciosamente.
- Para reglas deterministas nuevas: RED observado por regla solicitada, GREEN y regresión del comportamiento existente que comparta código. Verificar por interfaces públicas, incluyendo respuestas/errores y ausencia de mutación ante rechazo. Pruebas de persistencia/aceptación con estado aislado y limpieza comprobada.
- Casos integrados previstos: hasta cinco usuarios, dos sesiones del mismo diagrama y otro diagrama aislado; pregunta/respuesta compartida; usuario no autorizado; nueva sesión recupera memoria sin copiar chats; conflicto y reemplazo explícito; cambios mientras se espera/procesa; borrado de historial conserva memoria según acción; olvido no deja el recuerdo activo; compactación y reserva; reconexión, cancelación y fallo del runtime; dictado soportado/no soportado; fotos, dibujos y capturas reales con dudas y sin aplicación automática.
- Preservar el asistente anterior: explicar no muta; aclaración/rechazo no mutan; aplicar requiere permiso, confirmación y revisión vigente. Los rechazos no deben alterar datos ni contadores de revisión.
- Riesgos futuros: datos/migraciones/olvido (item 1), permisos/adjuntos (item 2), posibles contratos compartidos (item 3, solo si realmente se cambian), concurrencia (item 4), runtime/dependencias (item 5). Estos cambios requieren verificador independiente después de checks propios; una evaluación nativa indisponible no reduce el riesgo. No declarar «Risk: none» por tratarse de cinco usuarios.
- RDD solo según el switch del usuario y el contrato vigente al retomar. Ninguna aprobación de diseño concede consentimiento nativo, autoridad de revisión, commit, push o PR.
- Pronóstico cualitativo: feature multiunidad que probablemente supera ~400 líneas autorales; **no medido**. Mantener `ask-on-risk`; decidir cortes de entrega cuando haya implementación autorizada y estimación útil, no abrir PR ni elegir estrategia de cadena ahora. Registrar futuros commits por tarea sin inventarlos.

### L23 — Incidente de tooling y alcance de esta escritura (2026-10-14)

- `git status --short && git branch --show-current` agotó **20 s**, sin salida; se registra como fallo, no como estado limpio. No se reintentó ni migró a background.
- Diagnóstico acotado distinto de repetir el comando: `PWD=/mnt/d/Universidad/Proyectos/2doSemestre2026/sw1/1erParcialV2`; Git resuelve a `/usr/bin/git`; PowerShell a la instalación Windows; comprobación exit 0. No se estableció causa del timeout; no se aplicaron recetas de red ni se cambiaron procesos/configuración.
- `.git/HEAD` leído directamente apunta a `refs/heads/feat/rutas-ortogonales-ajustables`. No se creó rama y no hay inventario Git fiable para afirmar ausencia de cambios previos.
- Escritura autorizada: únicamente este archivo nuevo y su espejo Engram `odd/chat-ia-colaborativo-web/tasks`; no tocar planes previos, código, configuración ni modelos.

### L24 — Estado de documentación y reanudación (2026-10-14)

- Documento creado con 14 Specs y 10 tareas futuras pendientes. Ninguna funcionalidad nueva se declara implementada.
- Verificación estructural observada con Python, exit 0: `ODD_CHECK_OK specs=14 tasks=10 pending=10 logs=24 quotes=20 source_paths=16`. Comprobados orden de secciones, identificadores/dependencias, tareas pendientes sin commits inventados, 20 mensajes literales y existencia de 16 rutas citadas. RED/GREEN funcional no aplica a esta entrega documental; no se ejecutaron suites ni modelos.
- Espejo íntegro Engram guardado en la observación `4981`, topic `odd/chat-ia-colaborativo-web/tasks`, proyecto `1erparcialv2`. Lectura inicial cotejada con el archivo completo: contenido equivalente normalizando únicamente finales de línea y salto final; Engram omitió el último salto de línea. La comparación inicial del JSON crudo falló y no se consideró prueba de sincronización; se confirmó leyendo el campo `content` decodificado. Refrescar esta revisión final desde el archivo y comprobar su recuperación antes de entregar.
- Reanudar solo después de una nueva autorización de implementación. Leer `mem_context`, buscar `chat-ia-colaborativo-web`, recuperar el espejo completo, leer este archivo y reconciliar ambos con el código real. Iniciar por T1; no asumir que el runtime sigue igual ni repetir comprobaciones fallidas sin una razón y autorización nuevas.

### L26 — Formalización documental y estado de prueba (2026-10-14)

- Se conservan S1-S14, T1-T10 y L1-L24; solo S14 amplía el alcance de documentación. Catálogos derivados: 18 RF, 20 RN, 10 RNF y 42 CA; PD-01 a PD-08 hacen explícitos permisos/contratos/umbrales/políticas todavía no cerrados. No se inventan cifras de rendimiento ni se congela una API nueva.
- RF/RN/RNF enlazan fuentes y CA; cada CA enlaza tareas existentes, con precondición/acción/resultado observable. La evidencia funcional de todos los CA permanece pendiente hasta implementar y medir. El estado de 10 tareas sigue siendo pendiente, sin commits.
- Validación documental observada con Python, exit 0: `ODD_FORMAL_CHECK_OK specs=14 RF=18 RN=20 RNF=10 CA=42 PD=8 tasks=10 pending=10 logs=26 quotes=22 source_paths=16`. `TRACEABILITY_OK`: todos los requisitos tienen origen y CA, todos los CA enlazan tareas y las 10 tareas están cubiertas. Se comprobaron identificadores, dependencias, citas literales, ausencia de tareas cerradas/commits inventados y existencia de rutas citadas.
- La lectura paginada completa del archivo se cotejó con su contenido validado antes de actualizar el espejo. El espejo sigue en la observación `4981`, topic `odd/chat-ia-colaborativo-web/tasks`; la entrega requiere actualizar y recuperar el contenido íntegro de esta revisión, no un resumen.
- RED/GREEN funcional no aplica a documentación pasiva; no se ejecutaron tests de producto, modelos ni descargas. Los 42 CA de producto y las 10 tareas siguen pendientes por la indicación «Lo implementaremos despues papu :D».

### L27 — Limitación de recuperación del espejo (2026-10-14)

- La validación del archivo y su integridad final pasaron. Engram confirmó la revisión 3 al actualizar, pero el cotejo completo del espejo falló: la lectura devuelve 48700 caracteres frente a 55549 del archivo normalizado y termina en `... [truncated]`; el prefijo coincide hasta el carácter 48685.
- Esto demuestra truncamiento en la recuperación/proyección, no pérdida comprobada del almacenamiento. No declarar sincronización íntegra ni repetir validaciones para fingir disponibilidad de prueba. El archivo local completo es la fuente conservada; las 10 tareas de implementación permanecen pendientes.
- La tarea documental de sincronización queda bloqueada esperando una lectura íntegra de Engram o una decisión del usuario para separar evidencia histórica y reducir el documento principal. No cambiar el tamaño/el alcance del ODD ni mover su Log sin esa decisión. El contenido completo, incluida esta nota, se envía al espejo con advertencia de verificación pendiente; no se considera cotejado por ese envío.
