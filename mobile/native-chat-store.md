# Durable, isolated local agent storage

`MethodChannelChatStore` stores chat history, consulted-data cache and offline POST additions in Android application-private SQLite. It never sends network requests. The controller must choose the authenticated scope and enforce contract permission and coverage before calling it.

## Integration seam

- Channel: `diagrammer/chat_store`; new database: `agent_chat_v1.db`.
- Import `domain/chat_models.dart` and `data/chat_store.dart`. Construct one `MethodChannelChatStore` for the application lifetime; `close()` is terminal, not a logout action.
- Every call requires the literal `ChatScope(platform, userId, projectId, deploymentId, contractHash)`. The namespace is an escaped fixed-order JSON array, not delimiter concatenation. No implicit current account or fallback scope exists.
- `saveConversation` precedes `appendMessage` or `enqueue`. Conversation/message/operation IDs are supplied stable strings (UUIDs recommended). Messages return insertion order. Optional server sequence and metadata are immutable payload; synchronization must preserve them on duplicate replay.
- `enqueue(operation, message: message)` inserts both in one native transaction. Only POST with initial `queued` state is accepted. Exact duplicate IDs are idempotent; changed operation or coupled-message payload is refused. Canonical object-key order does not change identity.
- `cache(scope, path: ...)`, `getCache` and `putCache` preserve the exact relative path, decoded JSON body, UTC fetchedAt and explicit completeness. Storage does not infer full coverage. Cache never includes queued additions implicitly.

## Pending-state safety

| Current state | Allowed next states |
| --- | --- |
| queued | sending, rejected, conflict |
| sending | completed, rejected, unknown, conflict |
| completed, rejected, unknown, conflict | same state only |

Same-state updates may attach an explanation. Unknown/conflict/rejected rows remain visible through `outbox`; nothing is silently removed or requeued. A crash-left `sending` row remains stored and must be reconciled explicitly by the synchronization owner, normally as unknown. Replaying enqueue never resets its stored state/error. Already-dispatched operations are not rolled back by close or logout.

## Bounds and lifecycle

One executor owns SQLite reads/writes and close. Foreign keys bind messages and outbox rows to their namespace-local conversation. Bound values and fixed table/method names prevent arbitrary SQL. SQLite transactions protect coupled writes and rejection rollback. Each JSON request/record is limited to 1 MiB; native aggregate payloads to 64 MiB and 10,000 rows, with JSON nesting capped at 64. These payload bounds are not a disk-size or RAM performance claim. No schema upgrade rewrites prior data and no existing database is opened. No storage permissions or Dart SQLite dependency are added.

The scope is an isolation key, not independent authentication: the application controller supplies only its authenticated account/selected backend. Switching/logout must fence work before publishing results; storage does not auto-open another account. `dispose` rejects late replies and closes behind queued work. `MemoryChatStore` is only a deterministic test seam, not durable storage or proof of Android SQLite execution.

## Verification

Focused Dart tests cover account/platform/project/deployment/contract isolation, message membership, atomic fake enqueue, idempotency/conflict, state retention, cache coverage, size refusal, UTC and channel marshalling. Native source compiles using cached Kotlin, Android and Flutter jars in a fresh temporary directory. Real Android SQLite durability/restart/transaction execution and the parent integration build remain separate required checks; mocked channels do not establish those outcomes.
