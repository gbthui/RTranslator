#include "jni_common.h"
#include "transcribe.h"
#include "gguf.h"
#include <atomic>
#include <cstring>

#if QWEN_GGUF_VULKAN
#define METHOD(name) Java_nie_translator_rtranslator_voice_1translation_neural_1networks_voice_qwen_QwenNative_00024GgufVulkan_##name
#else
#define METHOD(name) Java_nie_translator_rtranslator_voice_1translation_neural_1networks_voice_qwen_QwenNative_00024GgufCpu_##name
#endif
namespace {
struct Model {
    std::mutex mutex;
    transcribe_session *session = nullptr;
    ~Model() { if (session) transcribe_session_free(session); }
};
struct Signal { std::atomic<bool> cancelled{false}; };
rq::Handles<Model> models;
rq::Handles<Signal> signals;
bool Abort(void *opaque) { return static_cast<Signal *>(opaque)->cancelled.load(std::memory_order_acquire); }
struct ClearAbort {
    transcribe_session *session;
    ~ClearAbort() { transcribe_set_abort_callback(session, nullptr, nullptr); }
};
}
extern "C" JNIEXPORT jlong JNICALL METHOD(open)(JNIEnv *env, jobject, jstring path, jint threads, jboolean) {
    try {
        if (threads < 1 || threads > 4) throw std::invalid_argument("Invalid thread count");
        auto filename = rq::String(env, path);
        gguf_init_params inspect{};
        inspect.no_alloc = true;
        std::unique_ptr<gguf_context, decltype(&gguf_free)> metadata(gguf_init_from_file(filename.c_str(), inspect), gguf_free);
        if (!metadata) throw std::invalid_argument("Invalid GGUF");
        auto key = gguf_find_key(metadata.get(), "general.architecture");
        if (key < 0 || gguf_get_kv_type(metadata.get(), key) != GGUF_TYPE_STRING ||
                std::strcmp(gguf_get_val_str(metadata.get(), key), "qwen3_asr") != 0) {
            throw std::invalid_argument("GGUF architecture must be qwen3_asr");
        }
        metadata.reset();
        transcribe_model_load_params load;
        transcribe_model_load_params_init(&load);
        load.backend = QWEN_GGUF_VULKAN ? TRANSCRIBE_BACKEND_VULKAN : TRANSCRIBE_BACKEND_CPU;
        transcribe_session_params params;
        transcribe_session_params_init(&params);
        params.n_threads = threads;
        params.n_ctx = 2048;
        auto model = std::make_shared<Model>();
        auto status = transcribe_open(filename.c_str(), &load, &params, &model->session);
        if (status != TRANSCRIBE_OK) throw std::runtime_error("GGUF initialization failed");
        if (!transcribe_model_supports(transcribe_get_model(model->session), TRANSCRIBE_FEATURE_CANCELLATION)) {
            throw std::runtime_error("GGUF runtime lacks cancellation");
        }
        return models.Add(std::move(model));
    } catch (const std::exception &) { rq::Fail(env, "java/io/IOException", "Could not load compatible Qwen GGUF model"); }
    catch (...) { rq::Fail(env, "java/io/IOException", "Unexpected GGUF initialization failure"); }
    return 0;
}
extern "C" JNIEXPORT jlong JNICALL METHOD(newCancellation)(JNIEnv *env, jobject) {
    try { return signals.Add(std::make_shared<Signal>()); }
    catch (...) { rq::Fail(env, "java/lang/IllegalStateException", "Cannot create cancellation signal"); return 0; }
}
extern "C" JNIEXPORT void JNICALL METHOD(cancel)(JNIEnv *, jobject, jlong id) {
    if (auto signal = signals.Find(id)) signal->cancelled.store(true, std::memory_order_release);
}
extern "C" JNIEXPORT void JNICALL METHOD(freeCancellation)(JNIEnv *, jobject, jlong id) { signals.Remove(id); }
extern "C" JNIEXPORT void JNICALL METHOD(release)(JNIEnv *, jobject, jlong id) { models.Remove(id); }
extern "C" JNIEXPORT jobjectArray JNICALL METHOD(decode)(JNIEnv *env, jobject, jlong modelId,
        jfloatArray audio, jstring language, jlong signalId) {
    std::shared_ptr<Signal> signal;
    try {
        auto model = models.Get(modelId);
        signal = signals.Get(signalId);
        if (signal->cancelled.load()) throw std::runtime_error("Cancelled");
        auto pcm = rq::Audio(env, audio);
        auto hint = rq::String(env, language);
        std::lock_guard<std::mutex> lock(model->mutex);
        if (signal->cancelled.load()) throw std::runtime_error("Cancelled");
        transcribe_run_params params;
        transcribe_run_params_init(&params);
        params.timestamps = TRANSCRIBE_TIMESTAMPS_NONE;
        params.language = hint.empty() ? nullptr : hint.c_str();
        transcribe_set_abort_callback(model->session, Abort, signal.get());
        ClearAbort clear{model->session};
        auto status = transcribe_run(model->session, pcm.data(), static_cast<int>(pcm.size()), &params);
        if (signal->cancelled.load() || status == TRANSCRIBE_ERR_ABORTED) {
            rq::Fail(env, "java/util/concurrent/CancellationException", "Speech recognition cancelled");
            return nullptr;
        }
        if (status == TRANSCRIBE_ERR_INPUT_TOO_LONG || status == TRANSCRIBE_ERR_OUTPUT_TRUNCATED) {
            rq::Fail(env, "java/io/EOFException", "Qwen GGUF transcript exceeds configured limits");
            return nullptr;
        }
        if (status != TRANSCRIBE_OK) throw std::runtime_error("Incomplete transcript");
        return rq::Result(env, transcribe_full_text(model->session),
                hint.empty() ? transcribe_detected_language(model->session) : hint);
    } catch (const std::exception &) {
        if (signal && signal->cancelled.load()) rq::Fail(env, "java/util/concurrent/CancellationException", "Speech recognition cancelled");
        else rq::Fail(env, "java/io/IOException", "Qwen GGUF did not produce a complete transcript");
    } catch (...) { rq::Fail(env, "java/io/IOException", "Unexpected Qwen GGUF failure"); }
    return nullptr;
}
