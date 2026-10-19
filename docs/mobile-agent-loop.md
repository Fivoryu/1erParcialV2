# Bounded local agent loop

The pure Dart runner accepts injected local model, gateway, scope guard, clock and progress callbacks. The production application must bind its authenticated backend/account context and receipt/unknown-write guards to those callbacks before enabling writes.

One response is a strict JSON tagged action: answer, clarify, read, create, update, delete_preview or calculate. Entity names and routes come from the selected frozen OpenAPI document; existing body validation is preserved. No executable code, SQL, arbitrary URL or model-provided authorization is accepted.

The initial independently verified intent grammar accepts `Crea Note con text = "hello"`, `create Note with {"text":"hello"}` and equivalent explicit edit verbs followed by an exact UUID. Values must be JSON literals and schema-valid. Anything outside this narrow grammar requires clarification or a trusted external immutable authorization. This is not unrestricted natural-language capability proof. One intent can dispatch at most one write. Deletes return an exact pending proposal and never call the gateway.

The runner has an absolute five-minute maximum budget, bounded model/tool/step/prompt/record counts, cancellation and scope fences before dispatch and after each await. Native generation is not assumed cancellable: the runner remains in flight until its future returns and ignores late output. Dispatched writes cannot be rolled back by cancellation. Progress exposes phases, tool names and elapsed durations, never private reasoning, invented percentage or fabricated energy readings.

Calculations execute a fixed finite-numeric arithmetic whitelist. Record aggregation rejects absent/non-numeric fields, oversized sets and empty inputs. Model-supplied numbers are explicitly unverified, not database totals. Tool results carry source, fetchedAt, completeness and committed status. Mock callback tests are not device model or production gateway proof. No offline synchronization or cache persistence is implemented by this pure engine.

The production prompt supplies concrete answer, clarification and calculation JSON examples, exact tool shapes, and supported arithmetic operators. Examples are format demonstrations, not authorized actions. The strict parser still rejects markdown fences, extra fields and malformed strings without gateway dispatch. This prompt clarification is a candidate remedy: device reproduction must establish whether the small local model follows it reliably.

## Repeated calculation protection

Each request tracks completed calculations by operation and ordered input values. If the local model asks for the same completed calculation again, the runner stops further model steps and returns the actual observed value as a **partial result**, explicitly identifying the inputs as model-proposed and not verified database totals. The result status is `stopped`, not a model-produced final answer or completed report. The public progress phase is `calculation_repeated`.

Distinct calculations, even with equal results, may continue normally. Tracking resets for each request. This guard does not change write authorization, deletion confirmation, gateway dispatch or the numeric calculator. It addresses exact repeated inputs; it does not prove that every observed phone loop used identical operands or that the model can finish arbitrary tasks.
