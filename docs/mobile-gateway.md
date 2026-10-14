# Authenticated mobile gateway

The mobile client reaches generated data only through the platform's authenticated gateway. It never receives an upstream URL, and the platform bearer token is never forwarded to a generated service.

## Quick path

1. Use the platform Bearer authentication normally.
2. Select an `UP` deployment through `GET /projects/{projectId}/mobile/backends`.
3. Read its frozen contract and send only a contract-hash-matching operation. Set `confirmed: true` for POST, PUT, or DELETE.

## Security boundary

| Boundary | Decision |
|---|---|
| Authorization | Reads require a project member; writes require an editor. Operator identities cannot use this surface. |
| Contract | The gateway reconstructs `openapi.json` from the frozen deployment run and validates the method, route, UUID route IDs, and JSON object schema before forwarding. |
| Network | Only a configured trusted host and generated HTTP ports 19001–19010 are reachable. Redirects, arbitrary URLs, authentication, cookies, and caller headers are never forwarded. |
| Writes | A write is forwarded at most once, only after explicit confirmation. Rejections and failures are never retried automatically. |
| Audit | Structured application logs record user, project, deployment, route, method, and status—never record values or credentials. Persistent audit storage is intentionally deferred because it requires a schema migration. |

## Deployment prerequisite

**The gateway is disabled by default.** Existing generated HTTP ports bind all interfaces, so enable `DIAGRAMMER_MOBILE_GATEWAY_ENABLED=true` only after the generated-service network is isolated from untrusted clients. Configure the trusted local/isolated host with `DIAGRAMMER_MOBILE_GATEWAY_UPSTREAM_HOST`; do not expose those generated ports to mobile clients.
