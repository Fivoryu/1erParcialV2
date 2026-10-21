# Web AI memory operations API

The web chat now extracts bounded pending knowledge automatically and can use validated memory actions while answering. Existing memory HTTP routes remain available; direct HTTP search is requester-scoped, while shared chat uses the stricter common-access boundary below.

## Quick path

| Operation | Endpoint | Purpose |
| --- | --- | --- |
| Read one memory | `GET /projects/{projectId}/views/{viewId}/memories/{memoryId}` | Return one memory in the requested diagram. |
| Search context | `POST /projects/{projectId}/views/{viewId}/memories/search` | Find matching memories and persisted project, diagram, and canonical UML facts. |
| Save | `POST /projects/{projectId}/views/{viewId}/memories` | Add a memory to the current diagram. |
| Revise | `PUT /projects/{projectId}/views/{viewId}/memories/{memoryId}` | Add a replacement and link the predecessor as `REPLACED`. |

Search accepts:

```json
{
  "query": "invoice status",
  "includeAccessibleProjects": true,
  "limit": 10
}
```

Omit `includeAccessibleProjects` to search only the requested diagram. For this direct HTTP endpoint, `true` searches the requester's active projects and rechecks membership while loading each canonical model. This requester-scoped behavior is intentionally separate from shared AI chat. Results carry server-derived project and diagram labels; the request cannot provide source names. The result limit defaults to 10 and is capped at 25, with each hit's content capped at 1,000 characters.

Each search result has a `type`, `content`, `source`, and `itemId`; memory hits also include `memoryId`, `kind`, and `status`. The source contains the persisted `projectId`/`projectName` and, for diagram-scoped hits, `viewId`/`viewName`. UML model facts use the persisted project label and `viewName: "UML model"`.

## Write and authorization rules

Save and revise accept `{ "kind": "DECISION|HYPOTHESIS", "content": "..." }`. Content must be nonblank and at most 2,000 characters. The server validates the view/project relationship and memory target before writing. Only active OWNER/EDITOR members may write directly; active project members may read. A revision is transactional, targets an active memory in the requested diagram, and preserves the prior memory through its `REPLACED` status and `replacedBy` link. It does not accept model-supplied source metadata.

The existing list and extraction routes are unchanged. Forgetting remains on the existing DELETE routes and remains OWNER-only. Search reads canonical UML/project/view records and persisted memories only; it does not search chat history or recalled claims.

## Shared-chat access boundary

AI answers are posted to a chat shared with the diagram's active collaborators. The server therefore searches outside the current diagram only when the user's request explicitly names other/different diagrams or projects, and only in projects where **every active collaborator** on the current project has active membership. It filters candidate projects before loading their memories or canonical UML and rechecks consulted source projects before publishing the answer. If the audience or access changes, the answer is not published. A requester's private project is never enough to authorize disclosure to the shared chat.

The direct HTTP search endpoint remains requester-scoped and is not subject to this shared-answer intersection because its response is returned only to that authenticated requester. Shared-chat writes also recheck this intersection at the write boundary: `saveForSharedChat(UUID projectId, UUID viewId, MemoryWriteRequest request, Set<UUID> sourceProjectIds)` and `reviseForSharedChat(UUID projectId, UUID viewId, UUID memoryId, MemoryWriteRequest request, Set<UUID> sourceProjectIds)` validate consulted sources before persistence. The existing requester-only `save` and `revise` operations remain unchanged.

## Chat behavior

For each queued AI ask, the existing bounded extraction service processes pending chat/model-change sources before the final context is built. Extraction failures leave sources pending and do not discard the question. The final context uses fresh canonical UML, active diagram memories, and that session's history; summaries remain session-specific.

The text provider may request up to four bounded `read`, `search`, `save`, or `revise` actions using complete JSON `memoryAction` objects; each search returns at most five hits. The server validates the exact shape and arguments, performs the existing authorized operation, and returns the real result for a natural-language answer. Cross-diagram search is opt-in and also requires an explicit cross-diagram/project request; writes and reads target the current diagram, and no delete or UML-mutation action is exposed. Chat writes/revisions revalidate any consulted external projects before persistence and preserve the ordinary editor role and revision-history rules. Repeated identical actions are not executed twice. The fixed chat and memory-agent instructions are counted exactly once in the meter's `instructions` component; each action/result turn is charged additionally against the existing prompt budget. Protocol JSON is not shown as a normal reply.

Save and revise still require active OWNER/EDITOR access. A revision preserves its predecessor as `REPLACED` with a `replacedBy` link; forgetting remains a separate OWNER-only operation.

## Web chat and visible memory

The chat panel keeps session history, diagram memory, and the context meter in the existing two-column layout. Useful pending knowledge is extracted automatically during an AI request; the existing **Extract memories** action remains available for a manual run. Once an AI request reaches a terminal state, the open editor refreshes memory for sessions in its current diagram view, including collaborator activity. Late responses from another project or view are ignored.

Ask naturally in chat to read, save, or revise a memory. Each AI request receives the latest canonical UML for the current diagram plus its active memories and selected session history. To search other diagrams, ask explicitly; shared answers name external sources and may use only projects every active collaborator can access. The browser does not expose protocol JSON or add a separate CRUD panel.

## Real browser/model acceptance

From `web/`, run the bounded local acceptance once:

```bash
node scripts/verify-ai-memory.mjs
```

Use `node scripts/verify-ai-memory.mjs --help` for options. The defaults use the local app at `http://localhost:5173`, backend at `http://localhost:18092`, screenshots under `docs/capturas/ai-memory`, and a 180-second timeout per AI request. Each preflight request (the app URL and backend `/auth/me`) has its own fixed five-second deadline. `--timeout-ms` applies only to later AI requests; it does not extend preflight. The report records each stage's URL, start/end timestamps, elapsed time, HTTP status, readiness, and error, so one failed request does not erase the other result. The run requires the existing local backend/database, enabled local assistant, and Chrome; it performs no cloud inference, downloads, or inference retries. It creates a synthetic account and two projects, seeds one external fixture through the public API, then exercises automatic extraction, recall in a new session, natural-language save/revision, fresh UML context, and explicitly requested common-access search through the browser. It clears only memories and chat messages in projects created by that run. The public project API has no deletion route, so the synthetic account and project shells (including the target UML fixture) remain for manual removal by the local operator.

## Instrumentation check and latest isolated result

The focused Windows Node regression command `node --test scripts/verify-ai-memory.test.mjs` passed 2/2 tests (exit 0) for two local HTTP cases: an app 503 with backend 401, and a successful app response followed by a backend transport failure. Both preserve independent stage evidence and retain the existing unavailable exit code 2. Before the correction, both cases failed against the prior CLI behavior (RED exit 1). The latest isolated stack attempt did **not** reach this browser/model acceptance: backend `/actuator/health` stayed unreachable until its 180-second readiness deadline, so the Node acceptance command was never launched. No account, project, browser session, screenshot, or model inference was created; S1–S5 therefore have no new live verdict from that attempt.

Before startup, the packaged backend artifact was checked against the current compiler input list (265 of 265 Java sources, including the memory/chat sources), and its timestamp was newer than the newest source. The retained offline Maven package run used `mvn -o -B -DskipTests package` and exited 0 with tests skipped. The isolated run's command/output/exit and teardown evidence remain in the authorized local scratch workspace; they are not checked into this repository. Treat the readiness failure as unavailable evidence, never as a PASS.
