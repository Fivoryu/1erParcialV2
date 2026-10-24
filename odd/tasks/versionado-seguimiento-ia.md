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
