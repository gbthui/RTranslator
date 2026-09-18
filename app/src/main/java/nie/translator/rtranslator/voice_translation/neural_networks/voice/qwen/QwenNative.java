package nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen;

import java.io.IOException;

/** Separate libraries keep the CPU backends usable without a Vulkan loader. */
public interface QwenNative {
    long open(String path, int threads, boolean accelerator) throws IOException;
    long newCancellation();
    void cancel(long cancellation);
    byte[][] decode(long model, float[] samples, String language, long cancellation) throws IOException;
    void freeCancellation(long cancellation);
    void release(long model);

    final class Sherpa implements QwenNative {
        static { System.loadLibrary("rtranslator_qwen_onnx"); }
        public native long open(String path, int threads, boolean accelerator) throws IOException;
        public native long newCancellation();
        public native void cancel(long cancellation);
        public native byte[][] decode(long model, float[] samples, String language, long cancellation) throws IOException;
        public native void freeCancellation(long cancellation);
        public native void release(long model);
    }
    final class GgufCpu implements QwenNative {
        static { System.loadLibrary("rtranslator_qwen_gguf_cpu"); }
        public native long open(String path, int threads, boolean accelerator) throws IOException;
        public native long newCancellation();
        public native void cancel(long cancellation);
        public native byte[][] decode(long model, float[] samples, String language, long cancellation) throws IOException;
        public native void freeCancellation(long cancellation);
        public native void release(long model);
    }
    final class GgufVulkan implements QwenNative {
        static { System.loadLibrary("rtranslator_qwen_gguf_vulkan"); }
        public native long open(String path, int threads, boolean accelerator) throws IOException;
        public native long newCancellation();
        public native void cancel(long cancellation);
        public native byte[][] decode(long model, float[] samples, String language, long cancellation) throws IOException;
        public native void freeCancellation(long cancellation);
        public native void release(long model);
    }
}
