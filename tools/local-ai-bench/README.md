# Arnes de medicion de IA local

Mide lo unico que decide si la IA local es usable en este producto: **cuanto tarda una respuesta simple y de
que depende esa latencia**.

No es una suite de pruebas: mide un servicio externo (Docker Model Runner) y por eso no corre en el `pytest`
habitual. Si el servicio no esta, informa el motivo en vez de fallar.

## Como se ejecuta

```bash
docker desktop enable model-runner --tcp=12434   # una vez
python tools/local-ai-bench/bench.py
```

Requiere un modelo ya descargado en Docker Model Runner. El arnes **no descarga modelos y no cambia su
configuracion**: solo mide lo que encuentra.

## Que mide

| Caso | Para que |
| --- | --- |
| Piso: salida minima | Cuanto cuesta una peticion sin generar casi nada. Es el piso fijo que no se puede bajar generando menos |
| Respuesta simple | El caso real: una instruccion corta a un JSON chico |
| Respuesta simple **con razonamiento** | Cuanto cuesta dejar el razonamiento encendido. Fue el mayor hallazgo |
| Salida larga | La tasa de decodificacion real, para poder proyectar |

Y de ahi deriva el **modelo de latencia** del producto, que es lo que permite decidir sin volver a medir:

```text
latencia ≈ piso fijo + tokens_de_salida / tasa_de_decode
```

## Lo que el arnes no hace, a proposito

- **No fuerza la descarga del modelo** de memoria, asi que el numero de "primera peticion" no mide por si solo
  el tiempo de carga. Lo dice en pantalla.
- **No cambia la configuracion del modelo.** En la medicion original, intentar resetear el contexto con
  `docker model configure --context-size -1` dejo el runner en un estado invalido
  (`GGML_ASSERT(n_outputs >= 1)`). Se corrige fijando un contexto explicito, por ejemplo `4096`.
- **No mide ASR ni vision.** Son otra medicion y otro consumo de memoria.

## Por que existe

Un numero de latencia medido una vez y escrito en un documento se convierte en folclore. El arnes existe para
que cualquiera pueda volver a medirlo cuando cambie el modelo, el runtime o la maquina, y para que el numero
de la documentacion tenga de donde salir.
