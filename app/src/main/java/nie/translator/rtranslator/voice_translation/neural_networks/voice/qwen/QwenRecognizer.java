package nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.tools.ErrorCodes;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recognizer;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.RecognizerListener;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.RecognizerMultiListener;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.RecognizerAutoListener;

/** One serialized native model, per-utterance cancellation and guarded main-thread delivery. */
public final class QwenRecognizer extends Recognizer {
    private final SerialRecognitionQueue queue = new SerialRecognitionQueue();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String engine;
    private final File directory;
    private final CopyOnWriteArrayList<RecognizerListener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RecognizerAutoListener> autoListeners = new CopyOnWriteArrayList<>();
    private QwenBackend backend; // Worker thread only.
    private boolean accelerated;

    public QwenRecognizer(Global global, String engine, InitListener listener) {
        this.global = global;
        this.engine = engine;
        this.directory = QwenModelStore.current(global, engine);
        queue.submit(token -> {
            try {
                if (directory == null) throw new IOException("Import a Qwen model first");
                openBackend(token);
                post(token, listener::onInitializationFinished);
            } catch (CancellationException ignored) {
            } catch (Exception e) {
                releaseBackend();
                post(token, () -> listener.onError(new int[]{ErrorCodes.ERROR_LOADING_MODEL}, 0));
            }
        });
    }
    private void openBackend(CancellationToken token) throws IOException {
        token.check();
        if (backend != null) return;
        String key = QwenModelStore.failureKey(engine, directory);
        accelerated = QwenModelStore.wantsAcceleration(global) &&
                !global.getSharedPreferences("qwen-runtime", Context.MODE_PRIVATE).getBoolean(key, false);
        long start = SystemClock.elapsedRealtime();
        try { backend = QwenModelStore.open(directory, engine, accelerated); }
        catch (IOException e) {
            token.check();
            if (!accelerated) throw e;
            markAcceleratorFailure();
            accelerated = false;
            backend = QwenModelStore.open(directory, engine, false);
        }
        token.check();
        Log.i("Qwen3Asr", "engine=" + engine + " requestedProvider=" + provider() + " loadMs=" + (SystemClock.elapsedRealtime() - start));
    }
    private String provider() { return accelerated ? (QwenModelStore.ONNX.equals(engine) ? "nnapi" : "vulkan") : "cpu"; }
    private void markAcceleratorFailure() {
        global.getSharedPreferences("qwen-runtime", Context.MODE_PRIVATE).edit()
                .putBoolean(QwenModelStore.failureKey(engine, directory), true).apply();
        Log.w("Qwen3Asr", "Hardware backend failed; retrying on CPU. This is not a speed comparison.");
    }
    private void releaseBackend() {
        if (backend != null) { backend.close(); backend = null; }
    }
    private void post(CancellationToken token, Runnable callback) {
        main.post(() -> { if (queue.mayDeliver(token)) callback.run(); });
    }
    @Override public void recognize(float[] audio, int beamSize, String language) { submit(audio, language, null); }
    @Override public void recognize(float[] audio, int beamSize, String first, String second) { submit(audio, first, second); }

    private void submit(float[] audio, String first, String second) {
        if (audio == null || audio.length == 0) return;
        // The recorder is bounded too; reject an accidental unbounded caller before copying.
        if (audio.length > 5 * 60 * 16000) { notifyError(ErrorCodes.ERROR_EXECUTING_MODEL); return; }
        final float[] owned = Arrays.copyOf(audio, audio.length);
        final String a = QwenLanguages.code(first);
        final String b = second == null ? null : QwenLanguages.code(second);
        try {
            queue.submit(token -> {
                try {
                    if (a.isEmpty() || (b != null && b.isEmpty())) {
                        post(token, () -> notifyError(ErrorCodes.ERROR_LANGUAGE_NOT_SUPPORTED)); return;
                    }
                    openBackend(token);
                    long start = SystemClock.elapsedRealtime();
                    QwenBackend.Result result;
                    try { result = decodeAll(owned, a, b, token); }
                    catch (CancellationException e) { throw e; }
                    catch (SpeechResultException | java.io.EOFException e) { throw e; }
                    catch (IOException e) {
                        token.check();
                        if (!accelerated) throw e;
                        markAcceleratorFailure(); releaseBackend(); accelerated = false;
                        backend = QwenModelStore.open(directory, engine, false);
                        result = decodeAll(owned, a, b, token);
                    }
                    token.check();
                    QwenBackend.Result finalResult = result;
                    Log.i("Qwen3Asr", "engine=" + engine + " requestedProvider=" + provider() +
                            " audioMs=" + owned.length / 16 + " decodeMs=" + (SystemClock.elapsedRealtime() - start));
                    post(token, () -> {
                        if (b == null) {
                            for (RecognizerListener listener : listeners) listener.onSpeechRecognizedResult(finalResult.text, a, Double.NaN, true);
                        } else {
                            for (RecognizerAutoListener listener : autoListeners) listener.onSpeechRecognizedAutoResult(finalResult.text, finalResult.language);
                        }
                    });
                } catch (CancellationException ignored) {
                    // Never report cancellation as a hardware failure or deliver a partial result.
                } catch (SpeechResultException e) {
                    post(token, () -> notifyError(ErrorCodes.LANGUAGE_UNKNOWN));
                } catch (Exception e) {
                    releaseBackend();
                    post(token, () -> notifyError(ErrorCodes.ERROR_EXECUTING_MODEL));
                }
            });
        } catch (RuntimeException e) { notifyError(ErrorCodes.ERROR_EXECUTING_MODEL); }
    }
    private QwenBackend.Result decodeAll(float[] audio, String first, String second, CancellationToken token) throws IOException {
        StringBuilder text = new StringBuilder();
        String detected = "";
        for (int start = 0; start < audio.length;) {
            token.check();
            int end = QwenAudioChunks.nextEnd(audio, start);
            QwenBackend.Result result = backend.decode(Arrays.copyOfRange(audio, start, end), second == null ? first : "", token);
            token.check();
            if (second != null && !result.text.isEmpty()) {
                if ((!first.equals(result.language) && !second.equals(result.language)) ||
                        (!detected.isEmpty() && !detected.equals(result.language))) {
                    throw new SpeechResultException("Ambiguous speech language; use a manual language button");
                }
                detected = result.language;
            }
            if (!result.text.isEmpty()) {
                if (text.length() > 0 && text.charAt(text.length() - 1) < 128 && result.text.charAt(0) < 128) text.append(' ');
                text.append(result.text);
            }
            start = end;
        }
        if (text.length() == 0) throw new SpeechResultException("No speech recognized");
        return new QwenBackend.Result(text.toString(), second == null ? first : detected);
    }
    private static final class SpeechResultException extends IOException {
        SpeechResultException(String message) { super(message); }
    }
    private void notifyError(int code) {
        long generation = queue.generation();
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> { if (queue.mayDeliver(generation)) notifyError(code); });
            return;
        }
        if (!queue.mayDeliver(generation)) return;
        for (RecognizerListener listener : listeners) listener.onError(new int[]{code}, 0);
        for (RecognizerAutoListener listener : autoListeners) listener.onError(new int[]{code}, 0);
    }
    @Override public void stop() { queue.stop(); }
    @Override public void destroy() { closeAsync(() -> {}); }
    @Override public void closeAsync(Runnable afterClose) {
        listeners.clear(); autoListeners.clear();
        queue.close(this::releaseBackend).whenComplete((unused, error) -> afterClose.run());
    }
    @Override public void addCallback(RecognizerListener listener) { listeners.addIfAbsent(listener); }
    @Override public void removeCallback(RecognizerListener listener) { listeners.remove(listener); }
    @Override public void addMultiCallback(RecognizerMultiListener listener) { /* Whisper-only scoring interface. */ }
    @Override public void removeMultiCallback(RecognizerMultiListener listener) {}
    @Override public void addAutoCallback(RecognizerAutoListener listener) { autoListeners.addIfAbsent(listener); }
    @Override public void removeAutoCallback(RecognizerAutoListener listener) { autoListeners.remove(listener); }
}
