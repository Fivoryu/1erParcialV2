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
