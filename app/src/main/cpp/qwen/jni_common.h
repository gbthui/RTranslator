#ifndef RTRANSLATOR_QWEN_JNI_COMMON_H
#define RTRANSLATOR_QWEN_JNI_COMMON_H
#include <jni.h>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace rq {
template<class T> class Handles {
    std::mutex mutex_;
    std::unordered_map<jlong, std::shared_ptr<T>> values_;
    jlong next_ = 1;
public:
    jlong Add(std::shared_ptr<T> value) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (next_ == INT64_MAX) throw std::overflow_error("Native handle limit");
        const auto id = next_++;
        values_.emplace(id, std::move(value));
        return id;
    }
    std::shared_ptr<T> Find(jlong id) {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = values_.find(id);
        return it == values_.end() ? nullptr : it->second;
    }
    std::shared_ptr<T> Get(jlong id) {
        auto value = Find(id);
        if (!value) throw std::invalid_argument("Released native handle");
        return value;
    }
    void Remove(jlong id) {
        std::shared_ptr<T> removed;
        { std::lock_guard<std::mutex> lock(mutex_);
          auto it = values_.find(id);
          if (it != values_.end()) { removed = std::move(it->second); values_.erase(it); }
        }
        // Destroy outside the registry lock. In-flight calls retain shared ownership.
    }
};
inline void Fail(JNIEnv *env, const char *type, const char *message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass(type);
    if (cls) { env->ThrowNew(cls, message); env->DeleteLocalRef(cls); }
}
inline std::string String(JNIEnv *env, jstring value) {
    if (!value) throw std::invalid_argument("Missing native argument");
    // These strings are app-private ASCII paths or validated language identifiers.
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::bad_alloc();
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
inline std::vector<float> Audio(JNIEnv *env, jfloatArray value) {
    if (!value) throw std::invalid_argument("Missing audio");
    jsize size = env->GetArrayLength(value);
    if (size <= 0 || size > 20 * 16000) throw std::invalid_argument("Audio exceeds 20 seconds");
    std::vector<float> result(size);
    env->GetFloatArrayRegion(value, 0, size, result.data());
    if (env->ExceptionCheck()) throw std::runtime_error("Cannot copy audio");
    for (float sample : result) if (!std::isfinite(sample)) throw std::invalid_argument("Non-finite audio");
    return result;
}
inline jobjectArray Result(JNIEnv *env, const std::string &text, const std::string &language) {
    if (text.size() > 1024 * 1024 || language.size() > 64) throw std::runtime_error("Oversized ASR result");
    jclass bytesClass = env->FindClass("[B");
    if (!bytesClass) return nullptr;
    jobjectArray result = env->NewObjectArray(2, bytesClass, nullptr);
    env->DeleteLocalRef(bytesClass);
    if (!result) return nullptr;
    const std::string *values[] = {&text, &language};
    for (int i = 0; i < 2; ++i) {
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(values[i]->size()));
        if (!bytes) return nullptr;
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(values[i]->size()),
                               reinterpret_cast<const jbyte *>(values[i]->data()));
        env->SetObjectArrayElement(result, i, bytes);
        env->DeleteLocalRef(bytes);
        if (env->ExceptionCheck()) return nullptr;
    }
    return result;
}
}
#endif
