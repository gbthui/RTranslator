#include "jni_common.h"
#include "qwen_cancel.h"
#include "sherpa-onnx/csrc/offline-recognizer.h"
#include "sherpa-onnx/csrc/offline-stream.h"

#define METHOD(name) Java_nie_translator_rtranslator_voice_1translation_neural_1networks_voice_qwen_QwenNative_00024Sherpa_##name
namespace {
struct Model {
    std::mutex mutex;
    std::unique_ptr<sherpa_onnx::OfflineRecognizer> recognizer;
};
rq::Handles<Model> models;
rq::Handles<rtranslator_qwen::Cancellation> signals;
}
extern "C" JNIEXPORT jlong JNICALL METHOD(open)(JNIEnv *env, jobject, jstring path, jint threads, jboolean accelerator) {
    try {
        if (threads < 1 || threads > 4) throw std::invalid_argument("Invalid thread count");
        const auto root = rq::String(env, path);
        sherpa_onnx::OfflineRecognizerConfig config;
        auto &q = config.model_config.qwen3_asr;
        q.conv_frontend = root + "/conv_frontend.onnx";
        q.encoder = root + "/encoder.int8.onnx";
        q.decoder = root + "/decoder.int8.onnx";
        q.tokenizer = root + "/tokenizer";
        q.max_total_len = 2048;
        q.max_new_tokens = 512;
        q.temperature = 0.0f;
        config.model_config.num_threads = threads;
        config.model_config.provider = accelerator ? "nnapi" : "cpu";
        if (!config.Validate()) throw std::invalid_argument("Invalid Qwen ONNX package");
        auto model = std::make_shared<Model>();
        model->recognizer = std::make_unique<sherpa_onnx::OfflineRecognizer>(config);
        return models.Add(std::move(model));
    } catch (const std::exception &) { rq::Fail(env, "java/io/IOException", "Could not load Qwen ONNX model"); }
    catch (...) { rq::Fail(env, "java/io/IOException", "Unexpected Qwen ONNX initialization failure"); }
    return 0;
}
extern "C" JNIEXPORT jlong JNICALL METHOD(newCancellation)(JNIEnv *env, jobject) {
    try { return signals.Add(std::make_shared<rtranslator_qwen::Cancellation>()); }
    catch (...) { rq::Fail(env, "java/lang/IllegalStateException", "Cannot create cancellation signal"); return 0; }
}
extern "C" JNIEXPORT void JNICALL METHOD(cancel)(JNIEnv *, jobject, jlong id) {
    if (auto signal = signals.Find(id)) signal->Cancel();
}
extern "C" JNIEXPORT void JNICALL METHOD(freeCancellation)(JNIEnv *, jobject, jlong id) { signals.Remove(id); }
extern "C" JNIEXPORT void JNICALL METHOD(release)(JNIEnv *, jobject, jlong id) { models.Remove(id); }
extern "C" JNIEXPORT jobjectArray JNICALL METHOD(decode)(JNIEnv *env, jobject, jlong modelId,
        jfloatArray audio, jstring language, jlong signalId) {
    std::shared_ptr<rtranslator_qwen::Cancellation> signal;
    try {
        auto model = models.Get(modelId);
        signal = signals.Get(signalId);
        rtranslator_qwen::Scope scope(signal.get());
        rtranslator_qwen::CheckCancelled();
        auto pcm = rq::Audio(env, audio);
        const auto hint = rq::String(env, language);
        std::lock_guard<std::mutex> lock(model->mutex);
        rtranslator_qwen::CheckCancelled();
        auto stream = model->recognizer->CreateStream();
        if (!hint.empty()) stream->SetOption("language", hint);
        stream->AcceptWaveform(16000, pcm.data(), static_cast<int32_t>(pcm.size()));
        rtranslator_qwen::CheckCancelled();
        model->recognizer->DecodeStream(stream.get());
        rtranslator_qwen::CheckCancelled();
        const auto &result = stream->GetResult();
        return rq::Result(env, result.text, hint.empty() ? stream->GetOption("rtranslator_language") : hint);
    } catch (const std::length_error &) {
        if (signal && signal->cancelled.load()) rq::Fail(env, "java/util/concurrent/CancellationException", "Speech recognition cancelled");
        else rq::Fail(env, "java/io/EOFException", "Qwen ONNX transcript exceeds configured limits");
    } catch (const std::exception &) {
        if (signal && signal->cancelled.load()) rq::Fail(env, "java/util/concurrent/CancellationException", "Speech recognition cancelled");
        else rq::Fail(env, "java/io/IOException", "Qwen ONNX did not produce a complete transcript");
    } catch (...) { rq::Fail(env, "java/io/IOException", "Unexpected Qwen ONNX failure"); }
    return nullptr;
}
