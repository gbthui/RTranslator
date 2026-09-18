# Qwen3-ASR and the optional model library

The application opens without downloading weights or initializing inference sessions. Use the existing **Settings → Manage models** page to choose translation and speech models, import local files, or explicitly start downloads. Saving a model selection does not require the model to be installed. The corresponding feature explains missing or invalid dependencies and keeps settings accessible.

The previous standalone Qwen settings activity now redirects to that page for old shortcuts. Model management uses the existing RadioGroupPlus, ResourceManagerView, Material switches and dialogs rather than a separate UI. Opening model management closes active conversations and retires loaded models before allowing changes to mapped files. Return to the desired mode to resume.

## Installation and availability

Translation and speech selections are independent. Text translation needs the selected translation package. Voice modes additionally need the selected ASR package and Mozilla resources when the existing Mozilla-for-voice override is enabled. The current Translator implementation still initializes its selected primary translation model before that override. Optional Tatoeba and dictionary packages do not block primary translation.

Availability checks inspect actual files, including nonempty required files; download-history flags alone do not establish readiness. Native initialization is deferred until a feature is used. An initialization failure gives an actionable model-management entry rather than downloading or replacing a model automatically.

Use **Import translation model**, **Import speech model**, or **Import optional resources** for extracted folders. Importing copies files into application storage; it does not modify or remove the source. Supported layouts:

- **Whisper Small:** the folder containing the six RTranslator ONNX files: `Whisper_initializer.onnx`, `Whisper_encoder.onnx`, `Whisper_cache_initializer.onnx`, `Whisper_cache_initializer_batch.onnx`, `Whisper_decoder.onnx`, and `Whisper_detokenizer.onnx`.
- **HY-MT:** its RTranslator folder containing `model_int8_final.onnx`, `tokenizer.json`, and associated ONNX external data when present.
- **MADLAD:** its RTranslator folder containing the `Int4Acc4` encoder/decoder/cache files, `madlad_embed_8bit.onnx`, and `spiece.model`.
- **Mozilla:** the Mozilla root or a language folder containing the expected translation-direction model, vocabulary and lexicon files. Both English directions must be present for an installed language pair.
- **Optional resources:** the Tatoeba or translation-dictionary folder containing the corresponding database.
- **Qwen ONNX:** the 0.6B package `sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25`, containing `conv_frontend.onnx`, `encoder.int8.onnx`, `decoder.int8.onnx` and `tokenizer/{vocab.json,merges.txt,tokenizer_config.json}`. Associated external data files are copied too.
- **Qwen GGUF:** select a single complete transcribe.cpp-compatible `qwen3_asr` GGUF document, such as `handy-computer/Qwen3-ASR-0.6B-gguf` Q4_K_M. llama.cpp's decoder/mmproj pair is a different layout and is not accepted.

There is no runtime code download. Qwen weights use private no-backup storage and native CPU validation before atomic publication. Failed Qwen imports retain the old model. An operation already published completes successfully instead of reporting a late cancellation as rollback. Qwen SAF imports are bounded to 3 GiB and 64 files. Legacy imports have separate bounded staging, ONNX integrity checks and publication rollback; an existing legacy package must be explicitly deleted before replacement. Only trusted model packages should be imported.

Downloads start only on request. Qwen downloads use pinned public artifacts, resume partial transfers where the server supports ranges, and verify SHA-256 before installation. Archive extraction rejects traversal and links and bounds expanded size and file count. Leaving the model page does not implicitly resume paused downloads. Active Qwen work can be cancelled when leaving; downloaded partial data is retained for a later explicit retry. Native validation/loading may finish its current call before observing cancellation.

Optional-resource selection without files is shown as unavailable, not as a mandatory setup failure. Speech weights are no longer bundled with the dictionary download. Model selections take effect on the next feature initialization without restarting the application.

## Inference and language routing

The fork retains Whisper and adds:

- **ONNX:** sherpa-onnx `1.13.2`, commit `13d0ae6c539d2809d32f5eaa3ef1db0c459d0b24`, with a scoped cancellation/language-result patch.
- **GGUF:** transcribe.cpp `0.2.3`, commit `63a44d9239d610b3908e8a66b384924cd4a77217`, with separate CPU and Vulkan JNI libraries.

Audio capture, Silero VAD, translation and Android system TTS are retained. Qwen accepts 16 kHz mono float PCM and processes at most 20 seconds per native call. Longer submitted segments are split near a quiet frame without intentionally dropping samples. This is utterance/segment recognition, not native streaming ASR or token-streamed subtitles.

Manual language buttons pass a language hint. Automatic two-language mode performs one transcription, reads its language label and routes only to one of the selected languages. Mixed/unknown labels are rejected rather than inventing confidence scores or silently choosing a direction. Whisper retains its own two-hypothesis scoring.

Qwen uses its documented language set, separate from Whisper, including Mandarin, Cantonese and English. **Urdu is not included.** Use another supported backend for unsupported languages. Importing weights does not expand the model's language coverage. ASR and translation models may be loaded together, so combined working memory matters; the UI does not reuse Whisper's RAM estimate for Qwen.

## Cancellation and resource lifetime

`SerialRecognitionQueue` owns one backend and bounds waiting audio jobs to four. Each job has a fresh `CancellationToken`. Stopping increments a generation, removes pending work and signals active inference without waiting on the UI thread. Delivery checks its generation even for results already posted to the main thread.

- ONNX JNI sets an atomic flag and calls `Ort::RunOptions::SetTerminate()` for that job. The pinned sherpa patch passes those options to all six Qwen session-run call sites and checks cancellation between decoder steps. This is not a feature claimed for an unmodified upstream AAR.
- GGUF JNI installs an atomic abort callback and clears it after `transcribe_run()` returns. The pinned runtime checks between decoder steps; it does not guarantee interruption inside the audio encoder.
- Native cancellation/model handles are registry IDs with retained `shared_ptr` ownership, not Java-exposed pointers. Late cancellation after deregistration is a no-op. Model destruction happens after active inference returns.
- Cancelled or over-limit transcripts are not delivered as successful partial text. The next job can reuse the model with a fresh signal.

Cancellation is cooperative. A driver or operator can delay termination. No fixed latency, thread kill or safe recovery from a hung driver is promised. A previous host test took about 80 seconds to leave the GGUF encoder after cancellation; the model-library changes do not resolve that limitation. Settings stay navigable while native work returns, but mapped files cannot safely be replaced until then.

Leaving voice services or changing input languages cancels obsolete work. Finishing a recording still finalizes/transcribes it and is not cancellation. Whisper gains stale-result fencing and deferred release, not Qwen's native abort API.

## Long recordings and the gray microphone

The recorder has a roughly 29-second bounded capture window. Previously reaching that boundary used the genuine end-of-speech callback. Walkie-talkie mode could then resume TTS and deactivate the microphone during playback. Its first recognition callback also cleared manual-language flags, so later windows could lose their routing and be skipped.

A capture rollover now emits a non-terminal segment, keeps recording active and advances the buffer boundary without reapplying pre-roll. It does not issue end-of-speech or resume TTS. The manual direction is latched for the recording rather than inferred from a delayed ASR result. Only a genuine silence endpoint or user stop ends that recording and permits TTS to resume. Releasing the button flushes the last segment. The headset TTS-start microphone gate now matches the existing TTS-end gate.

A gray microphone during actual speaker playback can still be intentional, to avoid recording the translation itself. This is not merely cosmetic: a stopped recorder does not capture new speech. Backend throughput and the bounded queue remain limits; this change is not unlimited continuous dictation. Source-level regression tests do not replace microphone/touch/headset testing on a phone.

## CapsWriter comparison

Inspected `gbthui/CapsWriter-Offline` at `7a975c3536e860c27f570eb279e3406f0034b3b8`, specifically `core/server/engines/qwen_asr_gguf/inference/asr.py` and `encoder.py`.

That implementation combines previous audio embeddings and stable text in a bounded history window, uses token rollback and exposes prefix-prefill reuse. It also has a Windows DirectML-specific padding/mask path. Its runtime is a hybrid ONNX encoder plus llama.cpp decoder, not the transcribe.cpp single-file path used here.

Those mechanisms are not ported as an unmeasured Android optimization. Passing earlier turns into a two-person translator can mix speakers/languages; longer history adds memory and prefill work. Changing an attention mask is not equivalent to fixing the capture-window lifecycle. This revision retains the pretrained runtime's attention behavior and Qwen chunk bound. History/prefix-cache changes need matching multi-turn accuracy, memory, latency and cancellation measurements before enabling them.

## Binary compatibility and acceleration

CPU is the default. Optional hardware mode tries NNAPI for ONNX and Vulkan for GGUF. Recoverable failures fall back to CPU and are remembered per runtime/model/device fingerprint; the settings page has an explicit retry action. Hardware mode is not an automatic speed benchmark, and a requested provider does not prove that every operator was accelerated.

ONNX Runtime Android is aligned to `1.24.3` for both Java bindings and sherpa JNI. Only the Maven dependency packages `libonnxruntime.so`; custom JNI does not add another copy. New JNI/ggml symbols are hidden and new libraries are checked for 16 KiB ELF alignment. The app remains arm64-only.

Existing upstream tokenizer, ORT Extensions and SQLite binaries can still have 4 KiB alignment, so the new libraries do not prove whole-APK compatibility with 16 KiB-page devices. The ORT upgrade also affects Whisper/HY-MT/MADLAD; those, Bluetooth and NNAPI/Vulkan still need phone regressions.

## Build and verification

Requires Python 3.12+, Git, CMake, Ninja, JDK 17, Android SDK 36 and NDK r28c (`28.2.13676358`). On Ubuntu install `glslc libvulkan-dev spirv-headers` for Vulkan.

```sh
python3 tools/prepare-qwen-runtimes.py --ndk "$ANDROID_HOME/ndk/28.2.13676358"
bash tools/test-qwen-core.sh
bash tools/test-model-library.sh
./gradlew :app:assembleDebug
```

Native source pins, ORT archive digests, patches and binary hashes are recorded in `.gradle/qwen-runtimes/runtime.json`. Gradle rejects absent or stale prepared libraries. Building an APK does not require model weights.

`test-qwen-core.sh` contains 13 cancellation/lifecycle checks. `test-model-library.sh` adds 21 filesystem/capture-policy checks and static launch/UI integration contracts. These are host tests, not Android touch, rotation, accessibility, microphone or visual tests.

Host real-model smoke tests use production JNI and independent public recordings:

```sh
python3 tools/prepare-qwen-runtimes.py --host --output .gradle/qwen-host
python3 tools/download-qwen-test-models.py .gradle/qwen-smoke-models
bash tools/test-qwen-native.sh gguf .gradle/qwen-smoke-models/Qwen3-ASR-0.6B-Q4_K_M.gguf .gradle/qwen-smoke-models/jfk.wav .gradle/qwen-smoke-models/zh.wav
bash tools/test-qwen-native.sh onnx .gradle/qwen-smoke-models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25 .gradle/qwen-smoke-models/jfk.wav .gradle/qwen-smoke-models/zh.wav
```

CI separates core regressions, Android compilation and real-model smoke tests. Check each job's actual outcome. None establishes phone battery use, peak RAM, driver performance, field accuracy or completed device interaction testing.
