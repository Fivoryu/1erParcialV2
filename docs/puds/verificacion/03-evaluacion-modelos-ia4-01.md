# Verificación 03 — Evaluación de modelos locales para el asistente (IA4-01)

**Fecha:** 2026-10-04 · **Rama:** `feat/incremento-4-ia-textual` · **Estado:** medido, dos corridas por modelo con el corpus congelado.

## 1. Qué se evaluó

Los dos candidatos de `docs/guia-implementacion-incremento-4-ia.md` §4 sobre el corpus fijo de
15 casos en español (`tools/ai-eval/corpus.json`, fixture `ecommerce-base`), con el corredor
`tools/ai-eval/eval_run.py` (juez autoverificado 27/27 sin modelo). Todas las corridas con
ctx 2048, temperatura 0.0, max_tokens 512, CPU sin offload, runtime `llama.cpp` b11388
(`llama-b11388-bin-win-cpu-x64.zip`), en el host Windows de 16 GB.

## 2. Resultados

| Contrato | 0.8B Q4_0 | 2B Q4_K_M |
| --- | --- | --- |
| `ia4-01-prompt-v1` | 2/15 | 7/15 |
| `ia4-01-prompt-v2` (few-shot) | **6/15** | **11/15** |

| Métrica (v2, 2B) | Valor |
| --- | --- |
| Latencia por caso p50 / p95 | 11,2 s / 18,5 s |
| Carga fría | 2,9 s |
| RAM del proceso (medición en vivo, mitad de corrida) | 1.243 MB commit privado / 2.020 MB working set |

## 3. Enmiendas de instrumento declaradas (antes de confiar en números)

1. **Thinking vaciaba respuestas.** Qwen3.5 razona antes de responder: en la primera corrida
   6/15 respuestas eran cadenas vacías (el reasoning consumía el presupuesto). Corregido con
   `chat_template_kwargs {enable_thinking: false}`, igual para ambos modelos. La latencia bajó
   7× y el veredicto pasó a ser real.
2. **El muestreador de RAM se medía a sí mismo.** `$pid` es variable automática de solo lectura
   de PowerShell; la asignación falla en silencio y los "picos" de 76–96 MiB eran el propio
   PowerShell. Corregido (`$srvpid`). Los picos de RAM de las corridas quedan **invalidados**;
   el valor válido del 2B es la medición en vivo de la tabla.
3. **Una sola iteración de prompt declarada** (`v1` → `v2`): few-shot con elementos DISTINTOS de
   los del corpus, para no entrenar al test. El corpus no cambió ni una línea.

## 4. Lectura de los fallos restantes del 2B (4/15)

- **E03** (`ClienteIntern`, nombre parcial con dos candidatas): rechaza en vez de preguntar.
  Falla del lado **seguro**: se niega en vez de aproximar un objetivo. Limitación declarada.
- **E11** (cambiar el kind de una relación inequívoca): pregunta en vez de actuar — resto de la
  sobre-cautela.
- **E14** (`agregale algo`): muta en vez de preguntar (2 operaciones inventadas).
- **E15** (atributo inexistente): re-enruta en silencio al dueño real (1 operación destructiva
  que nadie pidió; el juez la caza).

## 5. Recomendación

- **DEC-I4-01: Qwen3.5-2B Q4_K_M (Unsloth, conversión no oficial declarada) como base**, con el
  contrato `ia4-01-prompt-v2`. El 0.8B queda descartado para el asistente (JSON degradado,
  targets rotos incluso con v2). El 4B queda como candidato solo si un umbral formal (DEC-I4-06)
  exigiera más que 11/15.
- **DEC-I4-02: llama.cpp es el runtime validado** para este host (CPU x64, sin CUDA). La
  contenedorización del servicio IA es trabajo de la unidad de integración, no de esta
  verificación; los artefactos ya están en `D:\ai-eval\models\`.
- El contrato `ia4-01-prompt-v1` queda como historia; el v2 es el vigente para IA4-02.

## 6. Qué NO se verificó

- RAM pico representativa por corrida (el sampler corregido no se volvió a correr con ambos
  modelos; el valor de la tabla es una observación en vivo puntual).
- Latencia/RAM en el hardware de presentación (no existe aún como decisión).
- Multimodalidad (mmproj) y LFM móvil: fuera de este incremento (guía §4).
- El comportamiento de los modelos en el producto real (IA4-02+ construye esa integración).

## 7. Comandos

```
python tools\ai-eval\eval_run.py --run --model D:\ai-eval\models\Qwen3.5-0.8B-Q4_0.gguf --ctx 2048 --out tools\ai-eval\results\qwen-0.8b-v2
python tools\ai-eval\eval_run.py --run --model D:\ai-eval\models\Qwen3.5-2B-Q4_K_M.gguf --ctx 2048 --out tools\ai-eval\results\qwen-2b-v2
python3 tools/ai-eval/eval_run.py --judge-selftest   # 27/27, sin modelo
```
