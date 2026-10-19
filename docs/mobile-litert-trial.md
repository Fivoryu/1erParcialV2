# Isolated on-device Gemma trial

This opt-in diagnostic evaluates Gemma with LiteRT-LM inside the Android app. It does not replace production MNN, enable database writes, or demonstrate production agent readiness.

## Isolation and identity

- Normal builds exclude the trial native source and LiteRT dependency. Production Flutter main, MNN channel, gateway guards and SQLite data remain unchanged.
- Trial builds enable `-PlitertTrial=true` and use the separate Flutter entrypoint `tool/litert_trial.dart` with channel `diagrammer/litert_trial`.
- Native dependency: `com.google.ai.edge.litertlm:litertlm-android:0.18.0`. The resolved AAR requires Android API 24; this app retains API 28.
- Model: `litert-community/gemma-4-E2B-it-litert-lm`, revision `b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1`, file `gemma-4-E2B-it.litertlm`.
- Required bytes: `2588147712`. SHA-256: `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`.
- Fixed app-private location: `files/litert-trial/gemma-4-E2B-it.litertlm`. Caller-provided paths and symlink escapes are rejected. Verify the artifact before loading; never commit model files.

## Safety boundary

CPU text inputs are the initial baseline. Vision/audio backends are not initialized. Automatic tool calling is explicitly disabled and registered tool execution bodies throw. A proposed tool call is diagnostic output, never executable authorization. No gateway, account session, downloader or chat store is instantiated by the trial entrypoint.

Each independent synthetic case receives a fresh model conversation. One serialized native worker owns engine creation and teardown. Release must not close an engine during native work. A 120-second reply fence is not proof that synchronous initialization stops at that deadline; subsequent work remains guarded while cleanup finishes.

## What the trial measures

Use natural Spanish requests across unrelated synthetic schemas. Each request carries a bounded entity-to-field/type descriptor, so field names such as `altitudeKm` are visible to the model without disclosing the evaluator's expected action. Keep expected tools and arguments in the evaluator, not in the user request. Report syntactic validity separately from correct tool/arguments and successful real application tasks. No trial tool changes a record.

Metrics are optional and explicitly named: load duration, inference duration, sampled own-process peak PSS and RSS. PSS and RSS are different accounting views, not values to add. Missing TTFT, energy or GPU measurements are unavailable, not zero. Debug APK process memory includes development overhead; it is not release-app performance proof.

The roughly 0.84 GB published active text-weight estimate is not this artifact's download size or a promise of total application RAM. The verified universal Android file is approximately 2.59 GB and loads modalities conditionally.

## Verification status

Native API compilation against the pinned AAR, default activity compilation with the trial class absent, and initial channel tests have passed. The first independent snapshot ran 94 repository tests plus two independent probes successfully, but found a missing synthetic field-schema context and 19 analyzer informational findings; one bounded correction batch addresses both before final verification. The single correction recheck passed 10 repository trial tests plus two independent boundary probes, 20 native input/schema-forwarding checks, and the exact configured analyzer with zero issues. The earlier complete snapshot passed 94 repository tests plus two independent probes; it is not represented as a full-suite rerun of the correction. Parent repeated the final 10 repository trial tests successfully. Existing Qwen failures remain unresolved production evidence, not overwritten by a different runtime trial.

## References

- [Official Android integration](https://developers.google.com/edge/litert-lm/android)
- [Pinned model artifact](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1)
- [Google memory planning](https://ai.google.dev/gemma/docs/core)


## Verified preparation

The pinned model download has been verified against the exact size and SHA above. The opt-in development APK contains the trial class in DEX and LiteRT JNI, targets ARM64/API 28, passes 16 KB ZIP alignment checks, adds no permissions and matches the normal APK signing certificate. It uses the existing verified MNN prebuilts rather than rebuilding MNN.

Set `litertTrial=true` explicitly in the isolated scratch Android `gradle.properties` when building this diagnostic. The environment-only Gradle-property attempt produced an APK without the trial; that APK was rejected. Never infer trial activation from a successful build alone: inspect DEX and native library contents.

Actual model execution, memory and task success remain pending. Device testing installs the matching-signed development APK without wiping app data, streams the verified model directly into its own private directory, runs synthetic cases without tools, and restores the exact normal APK. The isolated model file may remain in its separate private directory; Qwen files and chat data are not removed.

The harness stops immediately after a structural native failure; it does not dispatch later cases after initialization, availability or deadline errors. Invalid model-output syntax remains a measured case failure rather than a runtime-availability verdict. A widget regression verifies exactly one native dispatch after a structural failure.


## First device observation (2026-10-16)

The verified model reached app-private storage through an explicitly approved temporary native-ADB-sync delivery followed by an Android-local copy. The first greeting was syntactically valid and correct: total 43.786 s, including load 17.604 s and inference 25.853 s. Android reported sampled peak process PSS 3,146,814 KiB; this development-build measurement is not a release or model-only RAM figure. RSS is a separate counter and must not be added to PSS.

The process disappeared before a second result or final summary. The cause is unknown; this does not establish an out-of-memory event. Seven cases remain unproven and the next request may already have dispatched. No inference retry was performed.

The explicitly approved external temporary model file was removed after identity verification; the verified private model remains isolated. Normal APK restoration stalled, and a subsequent own-focus guard failed after the exact stalled host installer was stopped. Restoration is **not proven**; the last verified installed APK was the trial. Human foreground/unlock or native-consent resolution is required before continuing restoration. Do not present this partial result as production readiness.

A subsequent read-only installed-artifact check resolved restoration: the exact normal APK SHA matched, signer/version matched, only user 0 had this package installed, and normal launch plus fresh own-focus/unlocked checks passed. No new installation or retry was needed. This supersedes the earlier restoration warning, not the partial benchmark outcome.


## Short instrumented stability probe

The next authorized probe limits inference to two synthetic natural-language cases. It is not a backend action test. Startup inspects a previous diagnostic marker once and never starts inference automatically; an inspector-only relaunch must remain model-free.

Before model work, the native bridge durably records its own run ID, UID/PID, absolute wall time and monotonic elapsed time. Phase updates distinguish hashing, initialization, inference and conversation teardown. Baseline own-process PSS/RSS and available/total system RAM are separate scalar measurements; available system RAM is not an inventory of other apps.

On a subsequent launch, Android API 30+ may return one historical exit record for the stored own PID. The ordinary own-user Context provides server-side user/UID filtering. Returned identity, bounded wall-clock interval and monotonic consistency must match before any result is emitted. Only reason/status/timestamp/last-sampled PSS/RSS are returned; descriptions, traces, other profiles and unrelated history are never exposed. Missing, stale, clock-inconsistent or mismatched records remain unavailable. A query is consumed durably before invoking the API, with no widening or retry.

A structural native failure stops the remaining cases. Completion is marked only after both cases finish without such a failure. Native initialization is still not assumed forcibly interruptible. Build type, source hashes, artifact identity and restoration proof must accompany any reported device result; do not extrapolate the prior debug measurement to a performance build.

Opt-in release builds apply `litert-trial-proguard.pro` to preserve the reflectively constructed trial bridge and its public constructor. This rule is not active for normal builds. A successful release compilation alone is insufficient: the final DEX must contain that exact class and constructor before installation.

## Instrumented release observation (2026-10-16)

One explicit run completed zero of two synthetic cases. Pinned model verification passed and initialization took 13.695 seconds. The last durable marker was `before_conversation`; configuration conversion and native conversation creation remain inside that boundary. Android classified the original process exit as `REASON_CRASH_NATIVE` (5), status `SIGABRT` (6). This is not proof of low-memory killing; native allocation failure is still possible.

Maximum phase-sampled process PSS was 2,959,231 KiB and RSS was 1,063,308 KiB. Minimum sampled available system RAM was 1,024,240 KiB of 7,469,728 KiB total. These counters are separate snapshots, not additive or continuous peak measurements. External during-run samples were unavailable. Exit-history memory values were unavailable, not zero usage.

The exact normal APK was restored and its installed SHA-256 and own-app launch verified without wiping data. No backend actions ran. Next diagnosis is offline inspection of release JNI getter preservation; collecting an own-process abort message or native stack requires additional explicit consent.

## JNI getter correction and bounded repeat

The exact failed release DEX lacked six SDK configuration getters accessed by JNI. Narrow trial-only keep rules now preserve the four SamplerConfig getters and two ThinkingConfig getters. Actual old/new DEX checks reproduced failure then passed all eight checks including bridge constructor/close. Independent packaging verification and parent replay passed; native libraries and permissions stayed unchanged.

One corrected release run produced a valid, correct greeting in 46.086 seconds (load 23.391 s; inference 21.925 s). Calculation reached `after_send` but produced no parsed result before process death. The own-process exit record now reports `REASON_CRASH` (4), status 0, indicating a separate Java crash—not successful completion. The specific exception is not yet authorized for collection. This does not prove the earlier native-abort cause was the only defect.

Native greeting peak PSS was 3,020,343 KiB and RSS 2,019,708 KiB; minimum observed available system RAM was 975,380 KiB. Separate external samples are lower bounds, not exact peaks. RAM causality remains unknown. The exact normal app was restored and verified without wiping data. No backend actions ran. Native review remains pending untracked candidate selection; no approval or mobile commit is claimed.

## Codec correction: successful short phone probe

Pinned SDK numbers can be Gson-specific Number objects unsupported by Flutter channels. The trial now recursively normalizes outbound arguments into supported JSON primitives/containers, with bounded depth/items/strings/numbers, and preflights actual envelope encoding before posting success. Forty actual-codec regression checks passed, including independent verification. The retained standalone checks live at `mobile/tool/native_checks/TrialChannelValuesChecks.kt`, outside default Gradle test sources.

One corrected release phone run completed both synthetic cases without a crash: greeting 36.512 seconds (load 17.559; inference 18.108), calculation 11.518 seconds (inference 10.594) with correct `calculate/add/[12,7]` selection. No tool or backend operation executed. Durable finish and release callback were observed; normal APK restoration was verified without a wipe.

Greeting sampled PSS peak was 3,195,692 KiB and RSS 2,391,184 KiB, separate counters; minimum observed available system RAM was 934,472 KiB. These are whole-process measurements, not model-only RAM. One successful two-case probe does not prove sustained stability, all operations, or production integration. Production remains MNN by default pending explicit optional-Gemma adoption.

## Optional production chat adapter

The optional native and Dart Gemma adapters now have a visible Settings selector. Qwen remains the initial default. Three actual-widget selector checks and the full 116-test Flutter suite passed; integrated phone behavior still requires separate evidence. Builds with the existing explicit `litertTrial=true` Gradle property include both the diagnostic and the separate `diagrammer/litert_agent` production adapter. Normal default-off builds report Gemma unavailable; they do not silently fall back.

The adapter receives the complete bounded runner envelope and returns a strict action decision. Native tools cannot execute; existing Dart authorization, frozen contract checks, operation receipts and exact deletion confirmation remain authoritative. A fresh native conversation is created for each decision while the app supplies bounded chat history and observations. Runtime switching preserves the backend and draft and releases the actual resource owner before another load; failed cleanup must retain ownership.

Native generation accepts `prompt` (up to 32,768 characters) and `maxTokens` (1–512), with bounded replies and cancellation/late-result fencing. Synchronous engine initialization is not proven hard-cancellable. Tool selection and codec/lifecycle tests are not evidence of real model comprehension or persisted backend effects; integrated phone verification remains required.

### Integrated phone observation: ready, conversation still unproven

The integrated main APK reached Gemma's actual AI-ready state through the visible Settings selector and connected to the fresh Note fixture with the correct contract. Opening Agent exposed no usable composer controls to the guarded automation, so zero prompts were submitted. This verifies model loading and backend selection, not conversation quality or actions.

The exact normal APK was restored and its installed SHA-256 verified without wiping data. Two stalled streaming installation clients were resolved before one successful non-streaming restoration; restored-app foreground focus remains unverified. Further chat testing requires the app visible and unlocked. No CRUD, chat-history, cancellation or integrated RAM measurement is claimed from this run.

### Latest full-chat retest: greeting passed, arithmetic failed

The real integrated chat sent one greeting and displayed an assistant reply in 29 seconds. A separate arithmetic request repeatedly selected calculation steps without a final answer; Stop was requested after 170 seconds. This is a failed arithmetic test, not successful agent completion or proof of hard cancellation. Read/CRUD/history-isolation/RAM tests were not reached.

USB then disappeared before restoration. The last verified installed APK is the DDD34B3C test candidate; the exact normal FEDF68F3 artifact is available on the host but is **not restored in this latest run**. Earlier successful restoration receipts describe earlier runs only. Device reconnection is required for restoration and further phone verification.

A later source-only exact-repeat calculation guard passed an independent 119-test Flutter suite and analyzer. It reports actual partial observations, not an invented final model answer. That correction has not yet been built or verified on the disconnected phone.
