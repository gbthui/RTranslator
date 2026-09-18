package nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Common cancellation/lifetime semantics for ONNX and GGUF. */
public final class NativeQwenBackend implements QwenBackend {
    private final QwenNative nativeApi;
    private final boolean onnx;
    private long model;

    public NativeQwenBackend(QwenNative nativeApi, String path, boolean onnx, boolean accelerator) throws IOException {
        this.nativeApi = nativeApi;
        this.onnx = onnx;
        model = nativeApi.open(path, Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())), accelerator);
        if (model == 0) throw new IOException("Could not load Qwen3-ASR");
    }

    @Override public Result decode(float[] samples, String language, CancellationToken token) throws IOException {
        token.check();
        if (model == 0) throw new IOException("Recognizer has been released");
        if (samples.length == 0) return new Result("", language);
        if (samples.length > QwenAudioChunks.MAX_SAMPLES) throw new IOException("Audio chunk exceeds 20 seconds");
        long signal = nativeApi.newCancellation();
        if (signal == 0) throw new IOException("Could not allocate cancellation signal");
        try (CancellationToken.Registration registration = token.onCancel(() -> nativeApi.cancel(signal))) {
            token.check();
            String hint = language == null || language.isEmpty() ? "" :
                    (onnx ? QwenLanguages.name(language) : QwenLanguages.code(language));
            byte[][] values = nativeApi.decode(model, samples, hint, signal);
            token.check();
            if (values == null || values.length != 2 || values[0] == null || values[1] == null) {
                throw new IOException("Invalid recognition result");
            }
            String detected = new String(values[1], StandardCharsets.UTF_8);
            return new Result(new String(values[0], StandardCharsets.UTF_8),
                    hint.isEmpty() ? detected : language);
        } finally {
            // decode retains a shared native reference until it has returned.
            nativeApi.freeCancellation(signal);
        }
    }

    @Override public void close() {
        long previous = model;
        model = 0;
        if (previous != 0) nativeApi.release(previous);
    }
}
