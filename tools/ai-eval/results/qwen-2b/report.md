# Corrida de evaluación — Qwen3.5-2B-Q4_K_M.gguf

- Fecha: 2026-10-04T08:43:04
- Artefacto: `D:\ai-eval\models\Qwen3.5-2B-Q4_K_M.gguf`
- Runtime: `llama-server` (D:\ai-eval\runtime), ctx=2048, temperatura 0.0, max_tokens 512, thinking deshabilitado (chat_template_kwargs)
- Contrato de prompt: `ia4-01-prompt-v1`
- Hardware: host Windows, CPU (sin offload); RAM pico del proceso (commit privado): 74 MiB (working set pico: 92 MiB); RAM libre mínima del sistema: 54 MiB
- Carga fría: 2.9 s; E01 frío: 12.6 s; E01 tibio: 10.1 s
- Latencia por caso p50: 10.7 s; p95: 13.8 s

## Veredicto: **7/15**

| Caso | Esperado | Observado | OK | Detalle |
| --- | --- | --- | --- | --- |
| E01 | plan | plan | ❌ | missing expected ops: ['attribute.add:Pedido.fecha', 'relationship.create:Pedido->Cliente'] |
| E02 | explain | explain | ✅ | explanation without mutation |
| E03 | clarify | reject | ❌ | expected clarify with a question, got reject |
| E04 | reject | reject | ✅ | rejected without mutation |
| E05 | plan | plan | ✅ | all expected ops present |
| E06 | plan | invalid_json | ❌ | no parseable JSON object in the answer |
| E07 | plan | clarify | ❌ | missing expected ops: ['classifier.setAbstract:Cliente'] |
| E08 | plan | reject | ❌ | missing expected ops: ['classifier.delete:Pedido'] |
| E09 | plan | plan | ❌ | missing expected ops: ['relationship.create:Factura->LineaDeFactura', 'attribute.add:LineaDeFactura.cantidad'] |
| E10 | plan | plan | ✅ | all expected ops present |
| E11 | plan | clarify | ❌ | missing expected ops: ['relationship.update:Cliente->Pedido'] |
| E12 | clarify | reject | ❌ | expected clarify with a question, got reject |
| E13 | reject | reject | ✅ | rejected without mutation |
| E14 | clarify | clarify | ✅ | asked instead of mutating |
| E15 | reject | reject | ✅ | rejected without mutation |

## Comandos

```
python3 tools/ai-eval/eval_run.py --run --model D:\ai-eval\models\Qwen3.5-2B-Q4_K_M.gguf --runtime D:\ai-eval\runtime --port 8099 --ctx 2048
```
