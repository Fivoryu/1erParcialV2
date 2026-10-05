# Corrida de evaluación — Qwen3.5-0.8B-Q4_0.gguf

- Fecha: 2026-10-04T08:52:49
- Artefacto: `D:\ai-eval\models\Qwen3.5-0.8B-Q4_0.gguf`
- Runtime: `llama-server` (D:\ai-eval\runtime), ctx=2048, temperatura 0.0, max_tokens 512, thinking deshabilitado (chat_template_kwargs)
- Contrato de prompt: `ia4-01-prompt-v2`
- Hardware: host Windows, CPU (sin offload); RAM pico del proceso (commit privado): 1007 MiB (working set pico: 1124 MiB); RAM libre mínima del sistema: 706 MiB
- Carga fría: 2.4 s; E01 frío: 10.3 s; E01 tibio: 5.3 s
- Latencia por caso p50: 3.9 s; p95: 10.3 s

## Veredicto: **6/15**

| Caso | Esperado | Observado | OK | Detalle |
| --- | --- | --- | --- | --- |
| E01 | plan | plan | ❌ | missing expected ops: ['attribute.add:Pedido.fecha'] |
| E02 | explain | explain | ✅ | explanation without mutation |
| E03 | clarify | reject | ❌ | expected clarify with a question, got reject |
| E04 | reject | reject | ✅ | rejected without mutation |
| E05 | plan | clarify | ❌ | missing expected ops: ['classifier.rename:Producto'] |
| E06 | plan | clarify | ❌ | missing expected ops: ['attribute.update:Cliente.email'] |
| E07 | plan | plan | ✅ | all expected ops present |
| E08 | plan | reject | ❌ | missing expected ops: ['classifier.delete:Pedido'] |
| E09 | plan | plan | ❌ | missing expected ops: ['attribute.add:LineaDeFactura.cantidad'] |
| E10 | plan | invalid_json | ❌ | no parseable JSON object in the answer |
| E11 | plan | invalid_json | ❌ | no parseable JSON object in the answer |
| E12 | clarify | clarify | ✅ | asked instead of mutating |
| E13 | reject | reject | ✅ | rejected without mutation |
| E14 | clarify | plan | ❌ | mutation outside a plan: 3 op(s) |
| E15 | reject | reject | ✅ | rejected without mutation |

## Comandos

```
python3 tools/ai-eval/eval_run.py --run --model D:\ai-eval\models\Qwen3.5-0.8B-Q4_0.gguf --runtime D:\ai-eval\runtime --port 8099 --ctx 2048
```
