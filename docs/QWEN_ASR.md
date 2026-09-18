# Qwen3-ASR backends

This fork retains Whisper and adds two optional Qwen3-ASR runtimes. Open Settings → Input → Speech recognition models, import a model, then apply the selection. A model import and switch stop the active conversation. Resume using the return button.

- **ONNX:** sherpa-onnx `1.13.2`, commit `13d0ae6c539d2809d32f5eaa3ef1db0c459d0b24`, with a scoped cancellation/language-result patch. Import the folder containing `conv_frontend.onnx`, `encoder.int8.onnx`, `decoder.int8.onnx` and `tokenizer/{vocab.json,merges.txt,tokenizer_config.json}`. Associated ONNX external data files are copied too. Use the 0.6B package `sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25`.
- **GGUF:** transcribe.cpp `0.2.3`, commit `63a44d9239d610b3908e8a66b384924cd4a77217`. Import one complete `qwen3_asr` GGUF, for example `handy-computer/Qwen3-ASR-0.6B-gguf` Q4_K_M. llama.cpp's separate decoder/mmproj files are a different layout and are not accepted.

There is no runtime code download. Weights are copied using Android's Storage Access Framework into private no-backup storage. CPU load validation precedes atomic publication. Failed imports retain the old model. Previous generations remain on disk until explicitly deleted after the recognizer closes. An operation that has already published completes successfully rather than reporting a late cancellation as a rollback. Imports are bounded to 3 GiB and 64 files.

The upstream first-run resource downloader still downloads its Whisper/translation resources. This change does not replace that setup flow. Whisper remains the default until a Qwen model is imported and selected. Qwen requires its own model in addition to the selected translation model, so allow for their combined working memory.

## Inference and language routing

Audio capture, Silero VAD, translation and Android system TTS are retained. Both Qwen backends receive 16 kHz mono float PCM and process at most 20 seconds per native call. Long utterances are split near a quiet frame with no dropped samples. The result is finalized after the utterance, not streamed token by token.

Manual language buttons pass a language hint. Automatic two-language mode performs **one transcription**, reads the model's language label and routes only to one of the selected languages. Mixed/unknown language results are rejected rather than inventing a confidence score or silently choosing a direction. Whisper's two-hypothesis scoring remains specific to Whisper.

Qwen's language set is separate from Whisper's. It supports the model's 30 documented languages, including Mandarin, Cantonese and English; **Urdu is not in that set**. Switch to Whisper for unsupported languages. The public model's weights determine recognition quality; importing a file does not add language coverage.

## Cancellation contract

`SerialRecognitionQueue` owns one backend and bounds queued audio to four waiting jobs. Each utterance gets a new `CancellationToken`. Stopping increments a generation, discards pending audio and signals active inference without waiting on the UI thread. Main-thread callbacks check their generation so a result already queued for delivery cannot leak into the next conversation.

- **ONNX:** custom JNI sets an atomic cancellation flag and calls `Ort::RunOptions::SetTerminate()` on that utterance's run options. The pinned sherpa patch passes those options to all six Qwen ONNX session-run call sites and checks cancellation between autoregressive decoder steps. This is not a feature claimed for the unmodified upstream AAR.
- **GGUF:** custom JNI installs an abort callback backed by an atomic flag. The callback is cleared only after `transcribe_run()` returns. The pinned runtime checks cancellation between decoder steps; it does **not** guarantee interruption in the middle of the audio encoder.
- Native cancellation and model handles are registry IDs with retained `shared_ptr` ownership, not Java-exposed raw pointers. A late cancel after deregistration is a no-op. Model destruction is serialized after decode has returned.
- Cancelled/over-limit transcripts are never delivered as successful partial text. A fresh utterance uses a fresh signal and can reuse the loaded model.

Cancellation is cooperative. ORT execution providers and GPU drivers may observe it only at a kernel boundary. No fixed latency, forced thread kill or safe recovery from a hung driver is promised. Model loading itself is checked before/after the load; it is not forcibly interrupted.

Leaving a voice service, replacing typed text or changing input languages cancels obsolete work. Finishing a recording still finalizes/transcribes it; it is not equivalent to discarding the recording. Legacy Whisper gains stale-result fencing and deferred release, not Qwen's native abort API.

## Acceleration and binary compatibility

CPU is the default. Hardware mode tries NNAPI for ONNX and Vulkan for GGUF. Recoverable hardware failures fall back to CPU and are remembered per engine/model/device fingerprint; Apply retries hardware. Auto mode is **not** a speed benchmark, and `requestedProvider` does not prove every operator ran on an accelerator.

ONNX Runtime Android is aligned to **1.24.3** for both the app's Java bindings and the custom sherpa JNI. Only the Maven dependency packages `libonnxruntime.so`; the custom runtime does not package a competing copy. The JNI wrappers and GGUF CPU/Vulkan libraries hide their C++/ggml symbols. Builds check native dependencies and 16 KiB ELF load alignment. The app keeps its existing arm64-only target.

This ORT change also affects upstream Whisper/HY-MT/MADLAD. Those models, Bluetooth, service lifecycle, and hardware providers require Android device regression testing; a host smoke test is not that validation.

## Build and verification

Requires Python 3.12+, Git, CMake, Ninja, JDK 17, Android SDK 36 and NDK r28c (`28.2.13676358`). On Ubuntu also install `glslc libvulkan-dev spirv-headers` for Vulkan.

```sh
python3 tools/prepare-qwen-runtimes.py --ndk "$ANDROID_HOME/ndk/28.2.13676358"
bash tools/test-qwen-core.sh
./gradlew :app:assembleDebug
```

Native source commits, downloaded ORT archive digests, local patches and output binary hashes are recorded in `.gradle/qwen-runtimes/runtime.json`. Gradle rejects missing or stale prepared libraries. Model weights are never needed for an APK build.

Host CPU smoke tests use the same JNI source and independent public fixtures:

```sh
python3 tools/prepare-qwen-runtimes.py --host --output .gradle/qwen-host
python3 tools/download-qwen-test-models.py .gradle/qwen-smoke-models
bash tools/test-qwen-native.sh gguf .gradle/qwen-smoke-models/Qwen3-ASR-0.6B-Q4_K_M.gguf .gradle/qwen-smoke-models/jfk.wav .gradle/qwen-smoke-models/zh.wav
bash tools/test-qwen-native.sh onnx .gradle/qwen-smoke-models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25 .gradle/qwen-smoke-models/jfk.wav .gradle/qwen-smoke-models/zh.wav
```

CI separates Java lifecycle tests, Android APK compilation and real-model host smoke tests. Check each job's actual result. None measures phone power use, peak Android memory, Vulkan/NNAPI speed or field accuracy. Test those on the target phone with the selected translation model loaded before relying on the app for travel.
