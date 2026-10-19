# Private mobile chat synchronization

Chat history belongs to the authenticated account, selected project and deployment. It is separate from project collaboration chat. Stored assistant/tool text and metadata have no execution authority.

## API

Base: `/projects/{projectId}/mobile/backends/{deploymentId}/chats`. Use the existing platform bearer session.

| Request | Body / query | Response |
|---|---|---|
| POST base | `{id: UUID, title: string, contractHash: string}` | Conversation |
| GET base | `cursor: UUID?`, `limit: 1..100` (default 50) | `{items: Conversation[], nextCursor: UUID|null}` |
| GET `/{id}/messages` | `afterSequence: integer >= 0` (default 0), `limit: 1..100` | `{items: Message[], nextCursor: string|null}` |
| POST `/{id}/messages` | `{id: UUID, role: user|assistant|tool, content: string, createdAt: ISO offset datetime?, metadata: object?}` | Message |

Conversation: `{id, title, contractHash, createdAt, updatedAt}`.
Message: `{id, role, content, createdAt, sequence, metadata}`.

All successful requests return 200. Title is 1..200 characters; content is 1..16000 characters. Metadata is an object up to 4096 serialized characters. The entire inbound JSON body is limited to 64 KiB, including streamed requests. Timestamps use ISO offset datetime; omitted message timestamps are assigned by the server.

## Retry and pagination

Generate and durably retain UUIDs before synchronization. Retry the exact same body, including an omitted or supplied `createdAt`. A duplicate UUID in the same scope with equal payload returns the original resource without another insert; a different payload returns 409 `CHAT_ID_CONFLICT`. IDs cannot move to another account, conversation or backend. Conversation creation with a foreign-owned ID returns indistinguishable 404.

Conversation pages use ascending immutable UUID keyset order, not most-recent order. Pass `nextCursor` as `cursor`. Messages use ascending server-assigned sequence; pass the numeric `nextCursor` as `afterSequence`. Sequence is the ordering authority, not client timestamps. The client timestamp identity is stored as its lossless ISO offset string, separate from the PostgreSQL-normalized display timestamp. Exact retries therefore retain offsets and nanosecond precision across database roundtrips. A null cursor means the page was exhausted at read time; later appended messages can be polled using the last observed sequence.

## Authorization and durability

Every request requires a current user, current project membership and a deployment that belongs to that project. Another project collaborator cannot read or append an account's conversation. Creation validates the exact frozen artifact hash and available frozen OpenAPI artifact. A stopped deployment does not prevent history viewing or synchronization: no upstream/generated-data request happens here.

V14 creates only new conversation/message tables and indexes. It changes no existing rows. PostgreSQL transaction-scoped advisory locking serializes absent conversation creation; pessimistic conversation row locking serializes append/retry and sequence allocation. Message-ID/sequence constraints enforce uniqueness even across concurrent transactions. Transactions roll back on rejected requests.

## Verification

Run `mvn -f backend/pom.xml -Dtest=MobileAgentChat*Test test`. MVC tests exercise identity, collaborator isolation, exact retry, conflicting IDs, bounded history and payload rejection. Persistence tests use a newly created disposable PostgreSQL container to validate migration mapping, keyset queries and lock SQL. No existing platform database is used.

## Mobile synchronization foundation

ChatRepository synchronizes an existing scoped conversation's local messages and paginated remote history. It retains original append payloads across retries and compares remote metadata structurally. Transport failures remain observable and preserve local history. History synchronization never dispatches generated-data operations. UI integration, remote conversation discovery and reconnect automation are not implemented by this foundation.
