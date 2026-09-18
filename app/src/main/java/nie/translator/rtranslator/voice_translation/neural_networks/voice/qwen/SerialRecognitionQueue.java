package nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Bounds queued audio and serializes model use and destruction. stop() never waits. */
public final class SerialRecognitionQueue {
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(4), r -> new Thread(r, "qwen-asr"));
    private final Object lock = new Object();
    private final Set<CancellationToken> jobs = new HashSet<>();
    private boolean closed;
    private long generation;
    private CompletableFuture<Void> closing;

    public CancellationToken submit(Consumer<CancellationToken> work) {
        synchronized (lock) {
            CancellationToken token = new CancellationToken(generation);
            if (closed) throw new IllegalStateException("Recognizer is closed");
            jobs.add(token);
            try {
                worker.execute(() -> {
                    try { if (!token.isCancelled()) work.accept(token); }
                    finally { synchronized (lock) { jobs.remove(token); } }
                });
            } catch (RuntimeException e) {
                jobs.remove(token);
                token.cancel();
                throw e;
            }
            return token;
        }
    }

    public boolean mayDeliver(CancellationToken token) {
        synchronized (lock) { return !closed && token.generation == generation && !token.isCancelled(); }
    }

    public long generation() { synchronized (lock) { return generation; } }
    public boolean mayDeliver(long expectedGeneration) {
        synchronized (lock) { return !closed && generation == expectedGeneration; }
    }

    public void stop() {
        CancellationToken[] cancelled;
        synchronized (lock) {
            if (closed) return; // Do not remove the queued release operation.
            ++generation;
            cancelled = jobs.toArray(new CancellationToken[0]);
            worker.getQueue().clear();
            jobs.clear();
        }
        for (CancellationToken token : cancelled) token.cancel();
    }

    public CompletableFuture<Void> close(Runnable releaseModel) {
        CancellationToken[] cancelled;
        synchronized (lock) {
            if (closing != null) return closing;
            closed = true;
            closing = new CompletableFuture<>();
            ++generation;
            cancelled = jobs.toArray(new CancellationToken[0]);
            jobs.clear();
            worker.getQueue().clear();
            worker.execute(() -> {
                try { releaseModel.run(); closing.complete(null); }
                catch (Throwable e) { closing.completeExceptionally(e); }
            });
            worker.shutdown();
        }
        for (CancellationToken token : cancelled) token.cancel();
        return closing;
    }
}
