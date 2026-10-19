# Structured agent operation receipts

The additive API accepts a validated **structured intent**, not arbitrary SQL,
URLs, executable code, or proof of natural-language authorization. The mobile
agent must separately bind this intent to the user's clear request. Platform
authentication, current project permissions and the frozen generated contract
remain mandatory. No generated backend configuration is changed by this API.

## Submit and reconcile

`POST /projects/{projectId}/mobile/backends/{deploymentId}/agent/operations`

```json
{
  "operationId": "123e4567-e89b-12d3-a456-426614174099",
  "contractHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "method": "POST",
  "path": "/items",
  "body": {"name": "Synthetic example"}
}
```

Methods are `GET`, `POST`, `PUT`, `DELETE`, constrained to the selected frozen
OpenAPI contract. Member permission suffices for reads; all other operations
require current editor permission. The complete JSON request envelope is capped
at 65,536 bytes before parsing. Both upstream JSON results and the complete
serialized receipt (including its bound intent) are capped at 1 MiB.

`GET /projects/{projectId}/mobile/backends/{deploymentId}/agent/operations/{operationId}?contractHash={hash}`
performs fresh account, membership, deployment and contract checks. Reading a
write receipt also requires current editor permission. Foreign operation IDs
return `OPERATION_NOT_FOUND` (404) without exposing their payload.

Both routes return an envelope with `operationId`, `contractHash`, `method`,
`path`, `body`, `state`, nullable `confirmationToken`, nullable upstream `status`,
nullable upstream JSON `result`, and nullable sanitized `error`.

## Deletion preview and explicit confirmation

A DELETE must address a concrete UUID item, never an entire collection. First
submit the exact intent **without** `confirmationToken`. The returned
`awaiting_confirmation` receipt previews the bound method/path/body/hash and
returns its `confirmationToken`. Display that exact target to the user. Only
after the user explicitly confirms, resubmit the same operation ID and intent
with that token. A first request containing a token cannot bypass the persisted
preview. A changed target/body/hash conflicts instead of dispatching. The token
binds the preview; it is not an assertion that the server saw a human click.
Tokens on non-DELETE intents are rejected with `CONFIRMATION_NOT_APPLICABLE`.

## Durable dispatch semantics

An operation UUID is globally immutable. Canonical payload identity includes
contract hash, method, path and recursively sorted JSON object fields; array
order is retained. Confirmation tokens are excluded from payload identity.
Changed same-scope payloads return `OPERATION_CONFLICT` (409).

One short PostgreSQL transaction inserts/locks the receipt and claims
`dispatching`. It commits **before** the single upstream send. No database lock
or transaction is held across HTTP. Concurrent duplicates observe the same
receipt and never send again. Responses (including known upstream 4xx/5xx)
become `completed`; a transport failure, timeout or invalid/oversized response
becomes `unknown` with a sanitized error. A crash after claiming or failure to
persist the response leaves `dispatching`, which is also non-retryable.

There is deliberately no exactly-once claim across the upstream crash window.
Repeated POST and GET only reconcile an existing receipt; they do not authorize
redispatch. Do not mint a fresh operation UUID automatically after an unknown
result. Keep the pending operation and explain that its effect requires manual
inspection. Cancellation after a send does not undo the generated operation.

The legacy `.../operations` endpoint still requires explicit `confirmed: true`
for **every** write; the new policy does not relax its confirmation contract.
Migration V15 creates only the platform `diagrammer.agent_operation` receipt
table and its scope index, never modifying existing or generated-domain data.
