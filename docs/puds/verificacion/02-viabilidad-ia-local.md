# Viabilidad de la IA local, la voz y la visión en el hardware objetivo

> **Veredicto: la IA local es viable, con condiciones declaradas.** Un modelo de la clase del 0,8B cuantizado
> corre en CPU sin GPU y produce una respuesta simple en **~3 s**. Lo que hace lenta la respuesta no es el
> tamaño del modelo ni el largo del prompt: es **el razonamiento encendido** y **la cantidad de tokens de
> salida**. Las dos se controlan.

Medición del 2026-09-28. Reproducible con [`tools/local-ai-bench/`](../../../tools/local-ai-bench/).

## 1. El hardware medido

| Recurso | Valor | Consecuencia |
| --- | --- | --- |
| CPU | Intel Core 5 120U, 10 núcleos, 1,4 GHz base | Sin AVX-512. La inferencia va a CPU |
| RAM | 15,6 GB | Con el modelo cargado quedaron **0,17 GB libres** |
| GPU | Intel integrada, **sin dedicada** | `nvidia-smi` ausente: no hay CUDA. Nada se acelera por GPU |
| Disco | C: ~35 GB libres, D: ~26 GB libres | Alcanza para los pesos; poco margen en C: |

## 2. El runtime y el modelo

| Pieza | Valor observado |
| --- | --- |
| Runtime | **Docker Model Runner 1.2.6**, motor `llama.cpp` |
| Endpoint | `http://localhost:12434/engines/v1/chat/completions` (compatible con OpenAI) |
| Modelo | **`huggingface.co/ggml-org/qwen3.5-0.8b-gguf:Q4_0`** |
| Tamaño | **526,50 MiB**, 752,39M parámetros, cuantización Q4_0 |
| Licencia | **Apache-2.0** (`Qwen3.5 0.8B Base`) |
| Estado | Ya estaba descargado en la máquina: no hizo falta bajar nada |

El contrato fija "Qwen 3.5 0.8B". **El modelo existe, está en esta máquina y coincide con lo que el contrato
declara.** El runtime se descarga el modelo de memoria tras ~4 minutos de inactividad, que es el
comportamiento correcto para un producto que no puede tener medio giga residente todo el día.

## 3. Latencia medida

Salida medida con `chat_template_kwargs: {"enable_thinking": false}` y `response_format: {"type": "json_object"}`,
modelo cargado, 5 repeticiones por caso salvo donde se indica.

| Caso | Mediana | Mín | Máx | Tokens de salida | Razonamiento | ¿JSON válido? |
| --- | ---: | ---: | ---: | ---: | ---: | :---: |
| Piso: salida mínima | **2,18 s** | 2,17 | 2,20 | 2 | 0 | sí |
| **Respuesta simple** | **3,04 s** | 2,89 | 3,28 | 28 | 0 | **no** |
| Respuesta simple **con razonamiento** | **5,87 s** | 5,84 | 5,95 | 120 | 442 car. | no |
| Salida larga | 11,69 s | 11,61 | 11,78 | 300 | 0 | no |

De ahí sale el modelo de latencia del producto:

```text
latencia ≈ 2,2 s + tokens_de_salida / 32
```

El **decode es de ~31,5 tok/s**. El **piso fijo por petición es de ~2,2 s**: pedir dos tokens tarda casi lo
mismo que pedir veinte. Ese piso no depende del largo del prompt (31 tokens de entrada) ni de generar más.

Proyección honesta con ese modelo: una propuesta con ~150 tokens de salida tarda **~6,9 s**; con 50 tokens,
**~3,8 s**. No es una medición, es la proyección del modelo medido.

## 4. Las palancas, ordenadas por impacto medido

### 4.1 Apagar el razonamiento: 5,87 s → 3,04 s

El modelo es de razonamiento y, con el razonamiento encendido, **gasta más tokens pensando que respondiendo**
(120 contra 28) y **casi duplica la latencia**. Peor: en una de las corridas se quedó en un bucle
—*"I should create a class… Let me create a class…"*— y **nunca emitió el JSON**: gastó el presupuesto entero
en `reasoning_content` y devolvió `content` vacío con `finish_reason: length`.

La palanca que funciona:

```json
"chat_template_kwargs": {"enable_thinking": false}
```

La que **no** funciona, aunque se repita por ahí: `/no_think` en el prompt. Medido: 120 tokens y 422 caracteres
de razonamiento, igual que sin la marca. La palanca es la del **template**, no la del prompt.

### 4.2 Salida corta: cada token cuesta ~1/32 de segundo

No hay forma de bajar el piso generando menos, pero sí de no gastar de más: **acotar el JSON al mínimo** y
fijar `max_tokens`. Un esquema con nombres de campo cortos y sin campos opcionales no es una preferencia
estética: es latencia.

### 4.3 `response_format: json_object` no garantiza JSON

En los tres casos con contenido real, la respuesta vino **envuelta en un cerco de markdown** (` ```json `) y
por eso no es JSON parseable. `json_object` garantiza *sintaxis JSON* en el motor, pero el modelo igual la
contamina.

**Consecuencia para el contrato:** la limpieza y la **validación de esquema del lado nuestro son
obligatorias**, no una precaución. El contrato §7.1 ya las exige; esta medición muestra que sin ellas el
producto no funciona.

### 4.4 Bajar el contexto: sin mejora medible, y no verificado

Se intentó reducir el contexto a 2048. La latencia **no mejoró** (2,95 s → 3,12 s, dentro del ruido) y **no se
pudo confirmar que el cambio se aplicara**, porque `docker model inspect` informa el máximo del modelo
(262144) y no la configuración activa, y `docker model logs` no devolvió nada útil.

**Queda declarado como no concluyente.** El piso de 2,2 s no se explica por el contexto en esta configuración.

## 5. Dos hallazgos de operación

### 5.1 Reiniciar el contexto con `-1` rompe el runner

La documentación dice que `docker model configure --context-size -1` resetea al valor por defecto. **En la
versión 1.2.6 no resetea: rompe el motor**, que muere con `GGML_ASSERT(n_outputs >= 1) failed` y deja el
endpoint devolviendo HTTP 500. Se corrige fijando un valor explícito (`--context-size 4096`). Quien opere el
runtime debe usar un valor explícito, nunca `-1`.

### 5.2 La API no expone restricción por esquema

La lista de parámetros soportados incluye `response_format: {"type": "json_object"}`, pero **no `json_schema`
ni `grammar`**. `llama.cpp` sí sabe restringir la salida contra un esquema JSON (`json_schema` en los endpoints
de completion, y dentro de `response_format` en `/chat/completions`), pero **Docker Model Runner no lo
reexpone**.

Eso importa para la latencia: con restricción por esquema, la primera respuesta ya es válida y **no hay
reintento**; sin ella, cada reintento cuesta otros ~2,2 s más su generación. Si el producto necesita esa
garantía, la decisión es hablar con `llama.cpp` directamente en lugar de con el runner de Docker. **No es una
decisión que corresponda tomar en este incremento**: queda registrada.

## 6. Consecuencias de arquitectura

1. **El proveedor determinista sigue siendo el default.** El contrato fija
   `AI_TEXT_PROPOSALS_PROVIDER=deterministic`, y esta medición lo respalda: el camino por reglas cuesta
   prácticamente cero y el camino por modelo cuesta **~3 s como mínimo**.
2. **La latencia es de segundos, no de milisegundos.** La interfaz tiene que ser **asíncrona con indicador**.
   Una operación que bloquea 3 s se siente rota.
3. **El piso de 2,2 s no se negocia generando menos.** Para una respuesta muy simple, el costo lo pone la
   infraestructura, no el modelo. Hay que aceptarlo o atacarlo con reutilización/constricción, no pidiendo
   JSONs más cortos.
4. **La validación del lado nuestro no es opcional.** El modelo puede envolver el JSON, y con razonamiento
   encendido puede no responder nada.
5. **Con el modelo cargado quedan 0,17 GB de RAM libre.** En la máquina de la demostración hay que **cerrar
   aplicaciones** antes de mostrar la IA local, o el rendimiento se degrada por paginación.

## 7. Lo que este documento NO mide

| No medido | Por qué |
| --- | --- |
| **ASR local** (voz) | Consume memoria sobre una máquina que ya quedó con 0,17 GB libres. Requiere su propia corrida con la máquina descargada |
| **Visión** (fotografía) | Igual que el ASR, y el modelo declara `image-text-to-text` pero la capacidad multimodal **no se verificó** |
| **Runtime móvil en dispositivo** | Es otra medición, en otro dispositivo, y pertenece al Ciclo 3 |
| **Tiempo real de carga del modelo** | Se observó **20,94 s** en la primera corrida con el modelo fuera de memoria, pero no se midió con un protocolo reproducible |
| **La máquina descargada** | Todas las cifras son con ~1 GB de RAM libre al empezar. Con más memoria libre el piso podría bajar: **no se midió** |

**Nada de lo no medido se declara viable.** En particular, RF-15 depende también del ASR si la demostración de
voz es parte del recorrido.
