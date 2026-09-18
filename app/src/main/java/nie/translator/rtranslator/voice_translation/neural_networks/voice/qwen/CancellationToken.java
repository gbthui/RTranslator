package nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** One token per utterance. Never reset or reuse a cancelled token. */
public final class CancellationToken {
    final long generation;
    public CancellationToken() { this(0); }
    CancellationToken(long generation) { this.generation = generation; }
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<Runnable> listeners = new ArrayList<>();

    public boolean isCancelled() { return cancelled.get(); }

    public void check() {
        if (isCancelled()) throw new CancellationException("Speech recognition cancelled");
    }

    public void cancel() {
        List<Runnable> callbacks;
        synchronized (listeners) {
            if (!cancelled.compareAndSet(false, true)) return;
            callbacks = new ArrayList<>(listeners);
            listeners.clear();
        }
        // Do not hold a Java lock across JNI. Native handles are registry IDs, so
        // a concurrent unregister/release makes a late cancel harmless.
        for (Runnable callback : callbacks) callback.run();
    }

    public Registration onCancel(Runnable callback) {
        boolean callNow;
        synchronized (listeners) {
            callNow = cancelled.get();
            if (!callNow) listeners.add(callback);
        }
        if (callNow) callback.run();
        return () -> { synchronized (listeners) { listeners.remove(callback); } };
    }

    public interface Registration extends AutoCloseable {
        @Override void close();
    }
}
