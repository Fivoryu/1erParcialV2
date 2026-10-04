# Arnés de evaluación de modelos — Incremento 4 (IA4-01)

**Estado:** protocolo y corpus versionados **antes** de correr cualquier modelo, como exige
[`docs/guia-implementacion-incremento-4-ia.md`](../../docs/guia-implementacion-incremento-4-ia.md) §8 (IA4-01).
Este arnés todavía **no mide nada**: mide cuando exista la corrida.

## Qué hay acá

| Archivo | Contenido |
| --- | --- |
| [`corpus.json`](corpus.json) | Corpus fijo en español: 15 casos sobre un fixture declarado (`ecommerce-base`), cada uno con intención esperada (`plan` / `explain` / `clarify` / `reject`) y operaciones esperadas a nivel `{type, targetName}`. |
| [`eval_run.py`](eval_run.py) | Corredor + juez, solo stdlib. Tres modos: `--judge-selftest` (27 casos sintéticos contra el juez, **sin modelo y sin RAM**), `--judge RAW.json` (re-juzgar una corrida guardada) y `--run --model PATH` (medición completa: levanta `llama-server`, mide carga fría, latencia por caso, muestrea RAM del proceso y del sistema, juzga y emite `results.json` + `report.md` + `raw_answers.json` bajo `results/`). |

## Reglas del protocolo (fijadas antes de medir)

1. **Ningún caso cambia su enunciado después de la primera corrida.** Si un caso resultara
   defectuoso, se declara en el informe y se agrega uno nuevo; el corpus no se retoca para favorecer
   a un modelo.
2. **Igualdad esperada a nivel de intención, no de prosa.** El juicio por caso es:
   - `plan`: la salida contiene las operaciones esperadas del caso (por `type` y nombre de objetivo),
     en un orden válido con sus dependencias (E09 exige que las clases existan antes de la relación).
   - `explain`: sin operaciones mutadoras en el plan.
   - `clarify`: sin mutación y con una pregunta identificable.
   - `reject`: sin mutación y con rechazo explícito.
3. **El contexto enviado al modelo es el fixture, no un screenshot.** El contenido del diagrama viaja
   como datos no confiables, nunca como instrucciones (guía §3.1).
4. **Cada corrida registra, con su comando productor:**
   - artefacto exacto (repo, archivo, cuantización, sha256), runtime y versión, hardware;
   - carga fría y caliente (s por token hasta primer y último token del caso E01);
   - RAM pico del proceso y del sistema durante la corrida completa;
   - latencia p50/p95 por caso;
   - veredicto por caso (4 resultados posibles arriba) y total `X/15`.
5. **Lo no medido se declara.** Ningún número entra al informe sin el comando que lo produjo.

## Qué NO es este arnés

- No es la integración de producto: la guía mantiene al **proveedor determinista como predeterminado**
  hasta que la evaluación cierre DEC-I4-01/02 con autorización del usuario.
- No fija el umbral de calidad/latencia: eso es DEC-I4-06 y se decide con el informe en la mano.
- No descarga pesos: toda descarga es una decisión explícita del usuario
  (0.8B Q4_0 ≈ 563 MB de `ggml-org`; 2B Q4_K_M ≈ 1,28 GB de Unsloth, conversión no oficial declarada).

## Próximo paso

El corredor existe y está verificado sin modelo: `python3 tools/ai-eval/eval_run.py --judge-selftest`
(27/27, cubre los 15 casos). La corrida real espera RAM libre del host: cuando la haya,

```
python3 tools/ai-eval/eval_run.py --run --model D:\ai-eval\models\Qwen3.5-0.8B-Q4_0.gguf
python3 tools/ai-eval/eval_run.py --run --model D:\ai-eval\models\Qwen3.5-2B-Q4_K_M.gguf
```

cada corrida escribe su informe en `tools/ai-eval/results/<modelo>/`. El juez rechaza además
operaciones destructivas que nadie pidió dentro de un plan y mutaciones fuera de él (E05/E15).
