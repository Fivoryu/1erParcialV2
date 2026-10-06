# Fix generated service annotation name collisions

Generated backends must compile when a valid model class is named Service or Transactional. Preserve the salon diagram and fix the emitter, not a downloaded output copy.

## Scope and authorization

The user authorized fixing the generator and retrying a new salon backend ZIP after the original failed compilation. Local-only work; no publishing, remote execution, new network downloads, existing-stack replacement, or existing-data mutation. The original ZIP and main checkout remain untouched. Work in the attached generator-service-collision worktree based on 5657d79a25c8bc5e076a72c149e3444bfce89fd3.

## Design

Emit fully qualified Spring Service and Transactional annotations in serviceSource and omit their imports. Keep domain names unchanged. Other generator naming sites are out of scope unless the exact salon retry demonstrates another blocker. Add a lightweight Java compiler regression independent of the Docker-based emitter integration class.

## Tasks

- [x] T1 — Fix service annotation emission and cover Service, Transactional, and an ordinary class with actual compilation. Route: delegated; preparation, multi-file behavior and test execution require delegation. Observe RED before emitter changes, GREEN afterward. Acceptance: complete emitted Java sources compile and ordinary emission remains deterministic. Checks: mvn -o -B -Dtest=DomainProjectEmitterNameCollisionTest test; mvn -o -B -DskipTests package; applicable generation tests. Commit pending.
- [ ] T2 — Regenerate salon artifacts from the persisted source snapshot, build offline, and run isolated Postman/Newman CRUD, relationship, validation and persistence checks. Route: delegated verification; builds/runtime tests require delegation. Preserve original ZIP, existing stack and data; use cached images only. If original frozen snapshot cannot be retrieved, disclose exact provenance of any fresh current-snapshot retry. Checks: generated mvn -o -B package; Newman exported JSON/JUnit results; unchanged original ZIP hash; explicit owned-process/container cleanup. Commit pending.

## Verification policy

Meaningful deterministic compiler regression exists: T1 must observe RED then GREEN. No test framework or source formatting changes. Source-mutating normalization precedes functional verification. Parent performs one reported-command spot check. Native RDD is on (global) as verified with elevated read-only mode status; assess each work-unit commit and follow exact native continuations when due, never change mode or invent approval.

## Delivery and boundaries

Strategy: ask-on-risk. Forecast: about 200 authored tracked changed lines, generated/runtime artifacts excluded. One coherent T1 code+regression commit, followed by a T2 evidence/documentation commit. No push, PR, merge, or replacement of the user's running application. Rollback T1 removes emitter annotation change and its focused test; T2 artifacts live outside the source checkout.

## Progress and evidence

- Original generation e94a1371-7d49-4889-9d46-6554259b2991 failed twice with five javac diagnostics in ServiceService.java.
- Original ZIP SHA256: D082587265AA5B0D295A44D3FCAB99C70B26F250F813DEEEBD3AAB6425EFB748.
- Snapshot identity: model ff38f7c2-812b-4cdf-a7e7-5f711d89fcee, revision 56, salon project 7daedb16-2839-46bb-8234-ad65950a7e14. ZIP contains provenance, not the source InputSnapshot.
- Cached postgres:16-alpine and postgres:16 images are available. Existing uml-diagrammer application/database containers are protected.
- Local Newman 6.2.2 installed previously; CLI operability still pending. No original live API tests executed.
- T1 partial: emitter change and new 99-line compiler regression implemented; no commit. Functional RED: 1/1 failure with 13 javac diagnostics. GREEN: 1/1 passed. Offline package passed. Combined generation checks: 30 tests, 29 passed, one stale text assertion failed at DomainProjectEmitterTest.java:135 (expects unqualified Transactional annotation).
- Parent spot check: mvn -o -B -Dtest=DomainProjectEmitterNameCollisionTest test passed 1/1; source diff and stale assertion independently inspected.
- Exact T2 source identity confirmed by read-only query: version a01b27b9-0cef-4255-ac61-b8c2dd32311f, revision56, VALID, snapshot hash 9833ab3112ac3f4fd55bfff9e6eb6a8717467a1b8b4f96e54d8ce25ec3256bcb. Child-row snapshot re-hash pending. No existing-data writes.
- Test-owned PostgreSQL and Ryuk container IDs absent after integration checks; existing user containers protected.
- User approved and worker updated only the obsolete annotation-spelling assertion in DomainProjectEmitterTest.java. Final combined generation checks: 30/30 passed; final offline package passed; git diff --check passed. Historical 29/30 result above was superseded by this successful final run. T1 functional work complete; commit/native assessment pending. T2 next, no new salon ZIP or Newman tests yet.
