# Seguimiento del versionado y QA de la IA local

> **Producto:** diagramador colaborativo UML 2.5. **Rama:** `feat/incremento-5-versionado`.
> **Estado:** ejecución. Pidió el usuario después del cierre del Incremento 5 (L1 abajo).

## Specs

- **S1 — No se puede editar la propia fila de permisos.** En la matriz de permisos, la fila del usuario
  conectado se muestra inhabilitada, con una pista que lo explica. El servidor ya rechaza el cambio con 403;
  la interfaz no debe ofrecer la acción para después fallar.
- **S2 — El tipo de prueba del arnés no vive en el mapa de acciones de producción.** Hallazgo `R3-001`
  (informativo) del revisor nativo: `test.noop` está declarado en `COMMAND_ACTIONS`. La acción se declara con el
  handler en su registro, o el arnés se separa del mapa real; la prueba de completitud sigue cerrada.
- **S3 — El error de la consulta por voz es comprensible y no pierde el dictado.** La causa del 503 es
  `diagrammer.assistant.enabled=false` (el despliegue no habilitó la IA local): `ChatAiService.ask` responde
  503 `PROVIDER_DISABLED` y la interfaz mostraba "revisá la conexión". El mensaje debe nombrar el motivo real
  (IA local no habilitada en este despliegue) y el texto dictado debe conservarse en el campo para reintentar.
- **S4 — Sesión de QA de la IA local sobre un diagrama de clínica.** Con el proveedor habilitado contra Docker
  Model Runner (`qwen3.5-0.8b`): crear el diagrama por IA; probar asociación, agregación, composición,
  generalización, realización, dependencia y multiplicidades; verificar que las relaciones se dibujan bien;
  crear varios chats y comprobar la memoria persistente del proyecto; y comprobar que la IA actúa como un
  miembro más: sugiere cambios, muestra el futuro cambio y lo aplica al aceptar el usuario.
- **S5 — Los defectos que la sesión de QA encuentre se registran con reproducción y se corrigen o se declaran.**

## Tasks

| # | Tarea | Specs | Commit |
| --- | --- | --- | --- |
| T1 | Matriz de permisos: fila propia inhabilitada con pista | S1 |  |
| T2 | Acción de `test.noop` fuera del mapa de producción | S2 |  |
| T3 | Mensaje real del 503 en el asistente/voz, sin perder el dictado | S3 |  |
| T4 | Sesión de QA de la clínica con la IA local y registro de defectos | S4, S5 |  |

## Log

- **L1 (pedido del usuario, verbatim):**

> Hay un hueco, al cambiar los permisos de uno mismo devuelve 403 (esta bien), debería estar inhabilitados ya que uno mismo no tendría que estar quitandose a si mismo los permisos.
> Además quiero que hagas un diagrama de una clínica a través de la IA local, y notes si hay errores, los diagramas si cumplen con uml 2.5, si se dibujan bien las relaciones, la multiplicidades, prueba todo tipo de relaciones, incluidas las de asociación de clases, comprueba la memoria persistente si funciona en todo el proyecto desde crear diferentes chats y la IA funcione como un miembro más del equipo, sugiriendo que añadir o pidiendo al usuario que si acepta los cambios y pueda ver el futuro cambio. Además arregla el erorr al intentar dar una consulta por voz. Failed to load resource: the server responded with a status of 503 (Service Unavailable).
> Y arregla lo que voto el revisor.

- **L2 (diagnóstico del 503, medido):** `LlamaCppAssistantProvider` se construye con
  `diagrammer.assistant.enabled:false` y `diagrammer.assistant.url:http://127.0.0.1:8099`. `ChatAiService.ask`
  responde 503 `PROVIDER_DISABLED` cuando está apagado. El runtime local real es **Docker Model Runner** en
  `http://localhost:12434/engines` (el proveedor arma `base + /v1/chat/completions`), con el modelo
  `huggingface.co/ggml-org/qwen3.5-0.8b-gguf:Q4_0` ya descargado y verificado vivo en esta máquina. Con el
  backend de prueba en 8099, la URL por defecto del proveedor apuntaba al propio backend: hay que fijarla
  explícitamente al runner.
- **L3 (ambiente de la sesión de QA):** Postgres en 25432 (contenedor `diagrammer-visual`), backend en 8099 con
  `diagrammer.assistant.enabled=true` y `url=http://localhost:12434/engines`, editor compilado servido en 5199
  por el proxy de `odd/evidence/vcs-visual-20261024/serve.mjs`. La conducción es con ego-browser.
- **L4 (incidente del empaquetado, causa del padre):** `mvn verify` falló en `spring-boot:repackage` al renombrar
  el JAR a `.jar.original` **después de pasar los 671 tests**. Causa: el backend de la sesión de QA estaba
  ejecutando exactamente `target/uml-diagrammer-backend-0.1.0.jar`, y Windows bloquea el JAR en uso. No es un
  defecto del código: se mató el proceso (PID 17224) y se rerunó el empaquetado con el puerto libre.

- **S6 — El chat grupal puede pedirle a la IA que genere o sugiera.** En la sesión de chat compartida del
  editor, un miembro pide una sugerencia; la propuesta aparece en el chat como tarjeta visible para todos, con
  la vista previa del cambio futuro; un EDITOR o superior la aplica, y la aplicación queda registrada en el
  chat (el linaje `chat_ai_linkage` de V16 ya existe). La IA es un miembro más: ve el contexto del diagrama y
  el historial del chat, con los permisos de quien pregunta.
- **S7 — El consumo de RAM del runtime de IA se declara y se acota.** Medido (captura del usuario): el
  `com.docker.llama-server.exe` retiene **4.650-5.080 MB** para un modelo de 526 MB. `docker model configure
  --context-size 4096` fue aplicado pero la reducción no se observó aún: el comando disparó un pull de
  actualización del runner (`latest-cuda`) que quedó pendiente, y `docker model inspect` se bloquea en ese pull.
  La descarga y recarga del modelo (`docker model unload`) funcionó. Queda declarado: el consumo es propiedad
  del runtime de Docker Model Runner en esta máquina, no del producto; la palanca documentada (contexto
  explícito) pudo no aplicar hasta completar la actualización del runner.

## Tasks (continuación)

| # | Tarea | Specs | Commit |
| --- | --- | --- | --- |
| T5 | Fix del proveedor: campo `model`, `max_tokens` 2048, `TIMEOUT` 300 s, prompt v4 de lenguaje cotidiano | S3, L2 | `f19b46a` (base) + este commit |
| T6 | Chat grupal con sugerencias de IA: propuesta visible, vista previa, aceptación por EDITOR+ | S6 |  |

## QA de la clínica (S4) — hallazgos con evidencia

| # | Hallazgo | Evidencia | Estado |
| --- | --- | --- | --- |
| Q1 | 500 en todo ask: el proveedor no enviaba `model` y el runner exige `model is required` (HTTP 400) | `provider-resp.txt` | **corregido** con propiedad `diagrammer.assistant.model` + 2 pruebas |
| Q2 | Plan de clínica truncado a 512 tokens → "no devolvió un plan JSON válido" | `raw-draft.txt`, `completion_tokens: 512` | **corregido** (`max_tokens` 2048) |
| Q3 | Timeout del proveedor a los 120 s: el plan real genera 1735 tokens en **239 s** con prompt de 1720 | `backend-run.log` `HttpTimeoutException`, medición de ronda 25 | **corregido** (`TIMEOUT` 300 s) |
| Q4 | El modelo 0.8B degrada en planes largos: entradas con `"type"` duplicado, `targetName` sin convención `Dueño.atributo`, prosa y fence tras el JSON | `raw-draft-2048.txt` | **límite del modelo contratado**, no del código; el validador lo rechaza limpio y la UI ofrece Reintentar |
| Q5 | La latencia del modelo del doc (2,2 s + tokens/32, con prompt de 31 tokens) no escala: con prompt de 1720 tokens el decode cae a ~7,3 tok/s | medición de ronda 25 | documentado; el prompt v4 recorta ejemplos si hace falta |
| Q6 | El modelo sin `enable_thinking:false` gasta los tokens en `reasoning_content` y devuelve `content` vacío | sonda directa | ya manejado por el proveedor (se envía siempre) |

El camino feliz quedó demostrado de punta a punta a nivel runner (ronda 25: el plan JSON para la clínica se
genera con clases, atributos y relaciones), pero la **entrega en vivo** del plan de 5 clases sigue siendo
intermitente por Q4: el modelo contratado está por debajo de la tarea. Planes chicos (1-3 operaciones) son el
caso confiable hoy. La decisión de subir de modelo (más RAM) o limitar el tamaño de pedido es del usuario.
