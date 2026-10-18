# Run Qwen locally on Android

The Flutter agent uses a real Kotlin/JNI bridge to MNN, not the platform assistant or a remote inference API. **Offline device inference and isolated valid-proposal acceptance are verified.** The default APK contains an explicit unavailable-runtime adapter until MNN is included.

## Build and model prerequisites

- Android API 28+, arm64-v8a, NDK selected by Flutter, CMake 3.22.1. The app clears Flutter's prepopulated ABI filters before selecting arm64 only; see the [Flutter ABI migration](https://docs.flutter.dev/release/breaking-changes/default-abi-filters-android).
- An operator-provided source checkout of [MNN 3.6.1](https://github.com/alibaba/MNN/releases/tag/3.6.1). Set `DIAGRAMMER_MNN_ROOT` to that directory before `flutter build apk --debug --target-platform android-arm64`. No source/model downloads happen at build time.
- The complete [Qwen3.5-0.8B-MNN package](https://huggingface.co/taobao-mnn/Qwen3.5-0.8B-MNN/tree/main), approximately 548 MB. Place its files inside the application's private or app-specific external `files/models/qwen3.5-0.8b` directory and enter that directory in the model setup screen.
- Required files: `llm_config.json`, `tokenizer.txt`, `llm.mnn`, `llm.mnn.weight`, `visual.mnn`, `visual.mnn.weight`. The UI currently accepts text only; the official runtime metadata also loads the vision encoder.
- All six required files are SHA-256 pinned to immutable model revision `3208b55b2e3c948016c9d380a697be881a1f12f8` and verified before load. The directory cannot reference arbitrary shared files.

## Runtime boundary

The bridge uses [MNN's public Llm lifecycle](https://github.com/alibaba/MNN/blob/3.6.1/transformers/llm/engine/include/llm/llm.hpp): load, independent reset per proposal, bounded response and release. JNI accesses are serialized on one worker. UTF-8 conversion preserves supplementary Unicode characters.

CPU execution uses two threads, a 512-token generation bound and a 120-second runtime timeout. Thinking is disabled through MNN's Jinja context **after** metadata load. HTTP resource support is disabled in the native build. Proposals still require strict app/gateway validation and human confirmation. No local proposal writes data itself.

## Required acceptance proof

1. Compile an APK with the pinned MNN source and install it on an authorized device.
2. Load the official package; record hardware, model hashes, runtime version and latency.
3. Select a backend while connected, then disable networking and generate a proposal.
4. Reject malformed output without sending any gateway operation.
5. Reconnect, inspect the proposal, confirm exactly once and verify the generated database.

On the authorized Samsung SM-S711B (Android 16, arm64), an INTERNET-free release AOT probe loaded Qwen3.5-0.8B/MNN 3.6.1 in 4431 ms and generated in 2248 ms in one acceptance run. All six app-private package hashes matched the pins. Its exact POST `/api/notes` proposal passed the strict parser and isolated OpenAPI contract validation, with zero gateway calls; the model was released afterward. The initial model response used a JSON fence, which is now supported only as a complete presentation wrapper without repairing JSON. These timings are single-run observations, not benchmarks. End-to-end login/backend selection/CRUD remains unverified. An APK compiled without MNN proves channel/linkage integration only.