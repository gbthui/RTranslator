#ifndef RTRANSLATOR_QWEN_CANCEL_H
#define RTRANSLATOR_QWEN_CANCEL_H
#include <atomic>
#include <stdexcept>
#include "onnxruntime_cxx_api.h"

namespace rtranslator_qwen {
struct Cancelled : std::runtime_error { Cancelled() : std::runtime_error("Recognition cancelled") {} };
struct Cancellation {
    std::atomic<bool> cancelled{false};
    Ort::RunOptions options;
    void Cancel() noexcept {
        cancelled.store(true, std::memory_order_release);
        // ORT allows SetTerminate from another thread. Some accelerator drivers
        // only observe termination at a kernel boundary; no hard latency promise.
        try { options.SetTerminate(); } catch (...) {}
    }
};
inline thread_local Cancellation *active = nullptr;
inline void CheckCancelled() {
    if (active && active->cancelled.load(std::memory_order_acquire)) throw Cancelled();
}
inline const Ort::RunOptions &RunOptions() {
    CheckCancelled();
    if (!active) throw std::logic_error("Qwen inference requires a cancellation scope");
    return active->options;
}
struct Scope {
    Cancellation *previous;
    explicit Scope(Cancellation *current) : previous(active) { active = current; }
    ~Scope() { active = previous; }
};
}
#endif
