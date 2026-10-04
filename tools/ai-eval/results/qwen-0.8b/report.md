# Corrida de evaluación — Qwen3.5-0.8B-Q4_0.gguf

- Fecha: 2026-10-04T08:33:37
- Artefacto: `D:\ai-eval\models\Qwen3.5-0.8B-Q4_0.gguf`
- Runtime: `llama-server` (D:\ai-eval\runtime), ctx=2048, temperatura 0.0, max_tokens 512, thinking deshabilitado (chat_template_kwargs)
- Contrato de prompt: `ia4-01-prompt-v1`
- Hardware: host Windows, CPU (sin offload); RAM pico del proceso (commit privado): 72 MiB (working set pico: 90 MiB); RAM libre mínima del sistema: 413 MiB
- Carga fría: 2.6 s; E01 frío: 4.4 s; E01 tibio: 3.5 s
- Latencia por caso p50: 2.9 s; p95: 5.1 s

## Veredicto: **2/15**

| Caso | Esperado | Observado | OK | Detalle |
| --- | --- | --- | --- | --- |
| E01 | plan | plan | ❌ | missing expected ops: ['attribute.add:Pedido.fecha', 'relationship.create:Pedido->Cliente'] |
| E02 | explain | plan | ❌ | mutation outside a plan: 1 op(s) |
| E03 | clarify | plan | ❌ | mutation outside a plan: 1 op(s) (includes destructive ['classifier.delete']) |
| E04 | reject | reject | ✅ | rejected without mutation |
| E05 | plan | plan | ✅ | all expected ops present |
| E06 | plan | plan | ❌ | missing expected ops: ['attribute.update:Cliente.email'] |
| E07 | plan | plan | ❌ | missing expected ops: ['classifier.setAbstract:Cliente'] |
| E08 | plan | reject | ❌ | missing expected ops: ['classifier.delete:Pedido'] |
| E09 | plan | plan | ❌ | missing expected ops: ['relationship.create:Factura->LineaDeFactura', 'attribute.add:LineaDeFactura.cantidad'] |
| E10 | plan | plan | ❌ | missing expected ops: ['operation.add:Cliente.calcularTotal'] |
| E11 | plan | plan | ❌ | missing expected ops: ['relationship.update:Cliente->Pedido'] |
| E12 | clarify | plan | ❌ | mutation outside a plan: 1 op(s) |
| E13 | reject | plan | ❌ | mutation outside a plan: 6 op(s) |
| E14 | clarify | plan | ❌ | mutation outside a plan: 1 op(s) |
| E15 | reject | plan | ❌ | mutation outside a plan: 1 op(s) (includes destructive ['attribute.remove']) |

## Comandos

```
python3 tools/ai-eval/eval_run.py --run --model D:\ai-eval\models\Qwen3.5-0.8B-Q4_0.gguf --runtime D:\ai-eval\runtime --port 8099 --ctx 2048
```
