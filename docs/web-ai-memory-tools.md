# Web AI memory operations API

These additive APIs read, persist, revise, and search diagram memory under server-side membership and role checks. Existing memory and forgetting routes remain available. Shared-chat agent integration follows in the next work unit.

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
