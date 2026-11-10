# Diagrammer Mobile

Android Flutter client for authenticated generated-backend access. App-authored screens, accessibility labels, confirmations, and status/error messages use Spanish. API identifiers, model protocols, and existing conversation content are unchanged.

## Local AI seam

The app defines an injectable `MnnService` backed by `MethodChannel('diagrammer/mnn')` with read-only `discoverModel`, `loadModel`, `generate`, and `release`. The Kotlin/JNI/CMake bridge exists, and the default APK deliberately exposes an unavailable-runtime adapter until MNN is supplied. It never falls back to remote inference or claims that local inference works when the runtime/model is unavailable. See [native-mnn.md](native-mnn.md) for `DIAGRAMMER_MNN_ROOT` and model setup.

The local generator receives a constrained prompt and returns a bounded JSON object with only `{ "method", "path", "body" }`. One complete outer `json` code fence is accepted as presentation only; surrounding prose, multiple blocks and malformed JSON are rejected without repair. The app validates that proposal against the selected OpenAPI contract before saving it locally or allowing explicit confirmation. It retries malformed local output once only; it never retries HTTP writes automatically.

## Development

Provide a HTTPS platform URL with `--dart-define=PLATFORM_URL=https://host`. HTTP is rejected unless the explicit development-only `--dart-define=ALLOW_INSECURE_HTTP=true` is also set. Real-device MNN load and inference have been verified using an arm64 release AOT probe without INTERNET permission. The isolated probe also parsed and OpenAPI-validated its proposal with zero gateway calls. End-to-end login/backend selection/gateway CRUD remain pending.

## Merge requests

After login, select a project and choose **Solicitudes de fusión** to view its diagrammer merge requests. The list is read-only and does not require a generated backend. It shows the title, state, and source and target branches. Open a request for its semantic comparison of changed fields (before and after), and partial activity from its opener, commits, approvals and reviews. Eligible reviewers can request changes; users with merge permission can close without merging. This is not a complete timeline or a full UML snapshot. The app uses the current authenticated session and the project's merge-request list endpoint. Access errors do not sign out the user except for HTTP 401. Returning to project selection does not change the selected backend.

The backend also exposes `POST /projects/{projectId}/vcs/merge-requests/{mergeRequestId}/request-changes` with JSON `{"message":"..."}` (1–2000 trimmed characters). Active editors and owners other than the opener can record a review on an open request; reviews appear in the detail's `reviews` array. Reviews do not alter approval or merge policy. This endpoint does not send notifications; push delivery remains future work. Flutter displays that notification delivery remains pending.

The backend also provides authenticated `POST /push/devices` with JSON `{"token":"..."}` to register a device token for the signed-in user. It returns 204 without the token; repeat registration by the same user is idempotent, and registration of a token belonging to another user returns 409. `DELETE /push/devices` with the same body removes the caller's own registration (204, no-op for unknown tokens). A background processor delivers queued review events through Firebase Cloud Messaging with exponential backoff (abandoned after 10 attempts) and drops tokens FCM reports as unregistered; payloads carry only identifiers. Set `diagrammer.push.fcm.project-id` plus `diagrammer.push.fcm.service-account-file` (a path outside the repository; access tokens are renewed automatically) or a static `diagrammer.push.fcm.access-token` for quick tests; without them events stay pending. The Android app registers its FCM token after sign-in, follows token rotation and unregisters on sign-out. Tapping a notification opens the merge request it refers to (selecting its project first). A newly opened merge request notifies the reviewers chosen by its author and, by default, the project creator and the project's `OWNER` members, never the person who opened it. Place your Firebase `google-services.json` in `mobile/android/app/` (it is not committed).

## Backend switching

Only one generated backend is selected at a time. **Desconectar backend** clears its active contract, proposal, result and error, while keeping the platform login, project list and loaded local model. Selecting another backend loads its own frozen contract and pending proposal.

Pending proposal JSON is unchanged. A separate secure-storage inspection marker is scoped to the user, project, deployment and frozen contract hash. An unknown or still-running operation on backend A cannot be retried blindly by switching to B and back. Leaving does not roll back an operation that has already been dispatched; inspect that backend before a manual retry. Stale responses cannot populate the newly selected backend.
## Navigation and model setup

After login, **Backends** lists project/deployment choices. **Abrir agente** opens only the selected backend's agent. **Configuración** contains local AI and account controls. Child screens have a **Volver** button; Android Back also returns through the route stack. Going back preserves the session, active backend, request draft, model and proposal. It does not send an operation. **Desconectar backend** and **Cerrar sesión** are separate actions.

Settings detects the existing Qwen3.5-0.8B package in this app's internal or app-specific external `files/models/qwen3.5-0.8b` directory. Tap **Cargar IA** once; no path entry is needed. Detection checks package presence; loading still verifies all six pinned hashes and CPU-only configuration. It does not download files or request shared-storage permissions. A missing package shows an explicit unavailable state and **Volver a comprobar**. Loading is serialized with release and late completion after logout cannot restore loaded status. Native load latency is not eliminated by automatic discovery.
