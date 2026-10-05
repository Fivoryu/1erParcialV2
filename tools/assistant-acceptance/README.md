# Arnés de aceptación integrado del asistente (IA4)

Ejecutalo desde la raíz del repositorio:

```sh
python tools/assistant-acceptance/assistant_acceptance.py
```

El arnés usa solo la biblioteca estándar de Python y combina dos piezas reales: la pila Compose de la raíz
(PostgreSQL y el backend construido desde `backend/Dockerfile`) y un **llama-server local** que el backend
contacta como proveedor del asistente. Los puertos que **este arnés posee por defecto** son HTTP **18092**,
PostgreSQL **25434** y llama-server **18099**. Elegí otros si ya están ocupados. Si ejecutás desde WSL y Docker
o las rutas `D:\ai-eval\...` no están disponibles en esa distro, podés usar el Python de Windows desde la raíz
(sin comillas anidadas):

```sh
cmd.exe /c python tools/assistant-acceptance/assistant_acceptance.py
```

## Prerrequisitos

- Docker Desktop corriendo, igual que el arnés de colaboración.
- El binario `llama-server.exe` en `D:\ai-eval\runtime\` y el modelo `Qwen3.5-2B-Q4_K_M.gguf` en
  `D:\ai-eval\models\` (el contrato vigente del prompt es `ia4-01-prompt-v3`, la segunda y última
  iteración declarada, que agrega el esquema explícito de `args`; medido en
  `docs/puds/verificacion/03-evaluacion-modelos-ia4-01.md`; el 2B es el modelo elegido para el asistente).
- Alrededor de **2,5 GB de RAM libres** para cargar el modelo en el contexto de 2048 tokens.
- Los tres puertos por defecto libres.

## Opciones

```text
--db-name NAME             DIAGRAMMER_DB_NAME (default: assistant_acceptance)
--db-user USER             DIAGRAMMER_DB_USER (default: harness)
--db-password PASSWORD     DIAGRAMMER_DB_PASSWORD (default: harness)
--http-port PORT           DIAGRAMMER_HTTP_HOST_PORT (default: 18092)
--postgres-port PORT       DIAGRAMMER_POSTGRES_HOST_PORT (default: 25434)
--request-timeout SECONDS  límite por petición no-asistente (default: 15)
--assistant-timeout SECONDS límite por petición a /assistant/plan y /assistant/apply (default: 240;
                           el proveedor del backend espera hasta 120 s a llama-server)
--compose-timeout SECONDS  límite para cada up/down (default: 1200)
--llama-binary PATH        binario llama-server (default: D:\ai-eval\runtime\llama-server.exe)
--llama-model PATH         archivo GGUF del modelo (default: D:\ai-eval\models\Qwen3.5-2B-Q4_K_M.gguf)
--llama-port PORT          puerto local de llama-server (default: 18099)
--llama-start-timeout SECONDS  espera por /health de llama-server (default: 180)
```

## Qué levanta y cómo limpia

La disciplina de Compose es la misma del arnés de colaboración (`tools/collaboration-acceptance`): proyecto
fijo `assistant-acceptance`, conteo por etiqueta `com.docker.compose.project=assistant-acceptance` **antes**
de cualquier comando Compose (si hay contenedores etiquetados, se niega a arrancar o borrar nada), conteo
**durante** (dos contenedores: base y backend), `down -v` en el `finally` aunque una comprobación previa falle,
y conteo final que debe quedar en **cero**.

El asistente se habilita con `DIAGRAMMER_ASSISTANT_ENABLED=true` y
`DIAGRAMMER_ASSISTANT_URL=http://host.docker.internal:18099`. Como `docker-compose.yml` no interpola esas
variables, el arnés escribe un archivo de override Compose en el directorio temporal del sistema (nunca dentro
del repositorio) que las inyecta en el servicio `diagrammer`, y lo borra al terminar. El backend las lee vía
`@Value("${diagrammer.assistant.enabled}")` y `@Value("${diagrammer.assistant.url}")`
(`LlamaCppAssistantProvider.java`), y el binding relajado de Spring mapea esos nombres de variable de
entorno sobre esas propiedades.

Después de subir la pila, el arnés arranca **llama-server como proceso local** (`--port 18099`, contexto 2048),
espera su `/health` y lo mata en el `finally`. Si la pila, la salud del backend o el modelo local fallan,
informa qué no se ejecutó y limpia; nunca sustituye el backend ni convierte la corrida en un parcial silencioso.

## Recorrido medido

Preparación: registra e inicia sesión A y B; A crea el proyecto, siembra un clasificador `Cliente` con
`POST /projects/{id}/commands` (misma forma de comando manual que el arnés de colaboración) e invita a B como
`EDITOR`; B acepta. Cada `POST /projects/{id}/assistant/plan` se hace con el `baseRevision` fresco que el
endpoint exige; cada `POST /projects/{id}/assistant/apply` reenvía las operaciones **exactamente como el plan
las devolvió** (`type`, `targetId`, `args`), como espera `AssistantApplyRequest`.

**Contrato de argumentos (prompt v3 + defaults declarados en apply):** `attribute.add`/`attribute.update`
deben llevar `args {name, type}` (si falta `type`, `AssistantApplyService` aplica el default declarado
`"String"`); `classifier.rename` debe llevar `args {newName}` (con tolerancia a `args.name`; `args` vacío se
rechaza con `MISSING_ARGUMENT`, sin adivinar); `classifier.create` lleva `args {name}`;
`relationship.create` lleva `args {kind}` (default declarado `"ASSOCIATION"`) más `source`/`target`; la
migración **V13** arregla el re-borrado de tombstones (`UNIQUE (cascade_id, target_type, target_id)`), de
modo que deshacer un borrado y volver a borrar el elemento recreado ya no termina en `MODEL_INVALID`.

- **Escenario 1 — preview multioperación:** A pide "Creá una clase Factura con el atributo fecha: Date y
  asociala con Cliente" y el arnés exige, como aserción primaria, un único plan con intent `PLAN` y exactamente
  `classifier.create Factura` + `attribute.add Factura.fecha` (con `targetClassifierName`) +
  `relationship.create Factura->Cliente` (con los clasificadores resueltos por nombre); al aplicar, los tres
  pasos deben salir `ACCEPTED` y la lectura del modelo debe mostrar la clase, el atributo y la relación.
  **Nota histórica (desajuste resuelto):** antes del commit `10cd291`, `AssistantPlanValidator` resolvía
  `attribute.add` y `relationship.create` contra el modelo **actual**, donde Factura todavía no existía, y ese
  plan terminaba forzado a `REJECT` con `UNKNOWN_TARGET`; desde ese commit el validador soporta creates
  referenciados dentro del mismo plan (simulación hacia adelante) y `apply` resuelve los nombres al momento de
  cada paso. El arnés conserva el fallback determinista (un plan y un apply por operación, en orden de
  dependencia) únicamente como rama de resiliencia: corre solo si la aserción primaria del plan único falla,
  y ese fallo sigue quedando registrado.
- **Escenario 2 — explicar no muta:** "Explicá la relación entre Cliente y Factura" debe devolver `EXPLAIN`
  (o `CLARIFY`) con cero operaciones; la revisión del modelo no cambia. Por construcción, `apply` nunca se
  invoca en este escenario: el plan no trae operaciones.
- **Escenario 3 — ambigüedad pregunta:** A crea `ClienteInternacional` por comando manual; "Eliminá la clase
  ClienteIntern" debe devolver `CLARIFY` con una pregunta concreta y dejar la revisión intacta.
- **Escenario 4 — inexistente se rechaza:** "Eliminá la clase Inexistente" debe devolver `REJECT` sin
  operaciones y sin cambiar la revisión.
- **Escenario 5 — cambio remoto entre preview y confirmación:** A planifica "Creá la clase Marca"; B (EDITOR)
  crea `Pais` por comando manual y sube la revisión; A aplica el plan vencido y el primer paso debe salir
  `REJECTED` con diagnóstico `REVIEW_REQUIRED` (`AssistantApplyService` devuelve exactamente ese paso único).
  La lectura final debe coincidir con el estado de B (con `Pais`, sin `Marca`), sin mutación parcial.
- **Escenario 6 — undo/redo:** A planifica y aplica el renombrado de `Cliente` a `ClientePrincipal`; `undo`
  lo revierte y `redo` lo restaura (las lecturas del modelo lo confirman); un segundo `redo` responde
  `NOTHING_TO_REDO`. Como el log de undo es **por usuario y LIFO** (`UndoService`), quitar la `Factura` creada
  por el asistente exige deshacer primero las entradas posteriores de A (renombrado, `ClienteInternacional`,
  y los pasos de Factura); el arnés deshace en bucle hasta que la lectura del modelo ya no muestra `Factura`,
  deshace el `Cliente` sembrado y verifica `NOTHING_TO_UNDO` con el log vacío.
- **Coherencia de recarga:** relectura completa del modelo (estado final esperado: solo `Pais`, de B) y
  verificación de que la revisión fue estrictamente monótona en cada mutación **aceptada y verificada**. El
  arnés registra pares `(mutación, ¿aceptada?)` para cada mutación intentada y muestrea la revisión (leída
  del modelo justo después de la mutación) solo de las aceptadas; las ramas de comportamiento del modelo
  (fallbacks, rechazos esperados como `NOTHING_TO_REDO`) no generan muestra y no rompen la contabilidad.
  Las aserciones de tipo "la revisión no cambió después de X" comparan contra la revisión leída del modelo
  inmediatamente antes de X (nunca contra números hardcodeados).

La salida imprime una tabla `escenario -> chequeos pasados` y conserva los límites bajo
**What was NOT measured**. Cualquier aserción fallida, error de Docker/HTTP/llama o fallo de teardown termina
con estado distinto de cero.

## Lo que no mide

- La latencia del asistente es informativa: no se verifica ninguna duración de plan/apply.
- No se ejercita la entrega SSE en vivo (misma convención que el arnés de colaboración).
- La calidad del contrato de prompt `ia4-01-prompt-v3` pertenece a `tools/ai-eval`; este arnés mide el
  contrato plan/apply/undo del backend sobre esa pila real.
- No abre navegadores.

## Riesgos declarados

- Los escenarios 2, 3 y 6 dependen de que un modelo de 2B devuelva el intent y los argumentos esperados
  (por ejemplo `newName` en el renombrado). Si el modelo se desvía, la aserción correspondiente falla y el
  resumen lo muestra; no es un fallo del arnés.
- El fallback de resiliencia del escenario 1 (split en tres planes) corre solo si la aserción primaria del
  plan único falla; el chequeo fallido sigue registrado, así que la corrida igualmente termina con código
  distinto de cero hasta que se corrija la causa raíz.
