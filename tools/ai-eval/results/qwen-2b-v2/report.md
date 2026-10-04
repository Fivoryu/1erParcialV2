# Corrida de evaluación — Qwen3.5-2B-Q4_K_M.gguf

- Fecha: 2026-10-04T08:56:17
- Artefacto: `D:\ai-eval\models\Qwen3.5-2B-Q4_K_M.gguf`
- Runtime: `llama-server` (D:\ai-eval\runtime), ctx=2048, temperatura 0.0, max_tokens 512, thinking deshabilitado (chat_template_kwargs)
- Contrato de prompt: `ia4-01-prompt-v2`
- Hardware: host Windows, CPU (sin offload); RAM pico del proceso (commit privado): 1244 MiB (working set pico: 2041 MiB); RAM libre mínima del sistema: 145 MiB
- Carga fría: 2.9 s; E01 frío: 18.5 s; E01 tibio: 10.4 s
- Latencia por caso p50: 11.2 s; p95: 18.5 s

## Veredicto: **11/15**

| Caso | Esperado | Observado | OK | Detalle |
| --- | --- | --- | --- | --- |
| E01 | plan | plan | ✅ | all expected ops present |
| E02 | explain | explain | ✅ | explanation without mutation |
| E03 | clarify | reject | ❌ | expected clarify with a question, got reject |
| E04 | reject | reject | ✅ | rejected without mutation |
| E05 | plan | plan | ✅ | all expected ops present |
| E06 | plan | plan | ✅ | all expected ops present |
| E07 | plan | plan | ✅ | all expected ops present |
| E08 | plan | plan | ✅ | all expected ops present |
| E09 | plan | plan | ✅ | all expected ops present |
| E10 | plan | plan | ✅ | all expected ops present |
| E11 | plan | clarify | ❌ | missing expected ops: ['relationship.update:Cliente->Pedido'] |
| E12 | clarify | clarify | ✅ | asked instead of mutating |
| E13 | reject | reject | ✅ | rejected without mutation |
| E14 | clarify | plan | ❌ | mutation outside a plan: 2 op(s) |
| E15 | reject | plan | ❌ | mutation outside a plan: 1 op(s) (includes destructive ['attribute.remove']) |

## Comandos

```
python3 tools/ai-eval/eval_run.py --run --model D:\ai-eval\models\Qwen3.5-2B-Q4_K_M.gguf --runtime D:\ai-eval\runtime --port 8099 --ctx 2048
```
