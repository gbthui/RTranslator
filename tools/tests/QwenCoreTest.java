package nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Host tests for the production Java core, without Android or model downloads. */
public final class QwenCoreTest {
    private static int passed;
    private interface Test { void run() throws Exception; }
    private static void test(String name, Test body) throws Exception {
        body.run(); ++passed; System.out.println("PASS " + name);
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void await(CountDownLatch latch) throws InterruptedException {
        check(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for worker");
    }
    private static void cancelled(Test work) throws Exception {
        try { work.run(); throw new AssertionError("Expected cancellation"); }
        catch (CancellationException expected) {}
    }
    private static class Native implements QwenNative {
        final AtomicInteger opened = new AtomicInteger(), decoded = new AtomicInteger();
        final AtomicInteger released = new AtomicInteger(), freed = new AtomicInteger(), cancelled = new AtomicInteger();
        final AtomicBoolean signal = new AtomicBoolean();
        final CountDownLatch entered = new CountDownLatch(1), proceed = new CountDownLatch(1);
        volatile boolean block, ignoreCancellation;
        String hint;
        public long open(String path, int threads, boolean accelerator) {
            check(threads >= 1 && threads <= 4, "Thread limit"); opened.incrementAndGet(); return 7;
        }
        public long newCancellation() { signal.set(false); return 10; }
        public void cancel(long id) { check(id == 10, "Signal ID"); cancelled.incrementAndGet(); signal.set(true); proceed.countDown(); }
        public byte[][] decode(long model, float[] samples, String language, long id) throws IOException {
            check(model == 7 && id == 10, "Native IDs"); decoded.incrementAndGet(); hint = language; entered.countDown();
            if (block) try { await(proceed); } catch (InterruptedException e) { throw new IOException(e); }
            if (signal.get() && !ignoreCancellation) throw new CancellationException();
            return new byte[][] {"广州𠮷野 Qwen".getBytes(StandardCharsets.UTF_8), "Chinese".getBytes(StandardCharsets.UTF_8)};
        }
        public void freeCancellation(long id) { check(id == 10, "Signal ID"); freed.incrementAndGet(); }
        public void release(long model) { check(model == 7, "Model ID"); released.incrementAndGet(); }
    }
    public static void main(String[] args) throws Exception {
        test("cancellation registration before and after cancel", () -> {
            CancellationToken token = new CancellationToken(); AtomicInteger hits = new AtomicInteger();
            token.onCancel(hits::incrementAndGet); token.cancel(); token.cancel();
            token.onCancel(hits::incrementAndGet);
            check(hits.get() == 2, "Callbacks called exactly once"); cancelled(token::check);
        });
        test("unregistered cancellation callback is not retained", () -> {
            CancellationToken token = new CancellationToken(); AtomicInteger hits = new AtomicInteger();
            token.onCancel(hits::incrementAndGet).close(); token.cancel(); check(hits.get() == 0, "Unregistered callback");
        });
        test("cancel before native entry does not allocate or decode", () -> {
            Native fake = new Native(); NativeQwenBackend backend = new NativeQwenBackend(fake, "model", true, false);
            CancellationToken token = new CancellationToken(); token.cancel();
            cancelled(() -> backend.decode(new float[]{0.1f}, "zh", token));
            check(fake.decoded.get() == 0 && fake.freed.get() == 0, "No native decode or signal"); backend.close();
        });
        test("Unicode bytes and language hint mapping", () -> {
            Native fake = new Native(); NativeQwenBackend backend = new NativeQwenBackend(fake, "model", true, false);
            QwenBackend.Result result = backend.decode(new float[]{0.1f}, "zh-CN", new CancellationToken());
            check(result.text.equals("广州𠮷野 Qwen") && result.language.equals("zh"), "UTF-8 preserved");
            check(fake.hint.equals("Chinese"), "ONNX hint uses model language name"); backend.close(); backend.close();
            check(fake.released.get() == 1 && fake.freed.get() == 1, "Exactly one release");
        });
        test("GGUF uses ISO hint and normalizes detected language", () -> {
            Native fake = new Native(); NativeQwenBackend backend = new NativeQwenBackend(fake, "model", false, false);
            check(backend.decode(new float[]{1}, "zh-CN", new CancellationToken()).language.equals("zh"), "Explicit language");
            check(fake.hint.equals("zh"), "GGUF language code");
            check(backend.decode(new float[]{1}, "", new CancellationToken()).language.equals("zh"), "Auto language"); backend.close();
        });
        test("cancel in native decode then reuse model with fresh signal", () -> {
            Native fake = new Native(); fake.block = true;
            NativeQwenBackend backend = new NativeQwenBackend(fake, "model", false, false);
            ExecutorService worker = Executors.newSingleThreadExecutor(); CancellationToken token = new CancellationToken();
            try {
                Future<?> running = worker.submit(() -> {
                    try { cancelled(() -> backend.decode(new float[]{1}, "", token)); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
                await(fake.entered); token.cancel(); running.get(5, TimeUnit.SECONDS);
                fake.block = false;
                check(!backend.decode(new float[]{1}, "en", new CancellationToken()).text.isEmpty(), "Next decode succeeds");
                check(fake.opened.get() == 1 && fake.freed.get() == 2, "Model reused; per-run signals released");
            } finally { worker.shutdownNow(); backend.close(); }
        });
        test("late native success after cancellation is discarded", () -> {
            Native fake = new Native(); fake.block = true; fake.ignoreCancellation = true;
            NativeQwenBackend backend = new NativeQwenBackend(fake, "model", true, false);
            ExecutorService worker = Executors.newSingleThreadExecutor(); CancellationToken token = new CancellationToken();
            try {
                Future<?> task = worker.submit(() -> {
                    try { cancelled(() -> backend.decode(new float[]{1}, "", token)); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
                await(fake.entered); token.cancel(); task.get(5, TimeUnit.SECONDS);
            } finally { worker.shutdownNow(); backend.close(); }
        });
        test("completed result queued for UI cannot survive stop", () -> {
            SerialRecognitionQueue queue = new SerialRecognitionQueue(); CountDownLatch done = new CountDownLatch(1);
            CancellationToken token = queue.submit(t -> done.countDown()); await(done);
            queue.stop(); check(!queue.mayDeliver(token), "Old UI callback fenced by generation");
            CountDownLatch next = new CountDownLatch(1); CancellationToken current = queue.submit(t -> next.countDown()); await(next);
            check(queue.mayDeliver(current), "Next generation not cancelled"); queue.close(() -> {}).get(5, TimeUnit.SECONDS);
        });
        test("pending audio is removed; active work receives cancellation", () -> {
            SerialRecognitionQueue queue = new SerialRecognitionQueue(); CountDownLatch started = new CountDownLatch(1);
            CountDownLatch exit = new CountDownLatch(1); AtomicInteger pending = new AtomicInteger();
            queue.submit(t -> { started.countDown(); while (!t.isCancelled()) Thread.yield(); exit.countDown(); });
            await(started); queue.submit(t -> pending.incrementAndGet()); queue.stop(); await(exit);
            queue.close(() -> {}).get(5, TimeUnit.SECONDS); check(pending.get() == 0, "Queued speech must not run");
        });
        test("close waits for native return; stop after close cannot remove release", () -> {
            SerialRecognitionQueue queue = new SerialRecognitionQueue(); CountDownLatch started = new CountDownLatch(1), exit = new CountDownLatch(1);
            AtomicBoolean running = new AtomicBoolean(), released = new AtomicBoolean();
            queue.submit(t -> {
                running.set(true); started.countDown();
                try { await(exit); } catch (InterruptedException e) { throw new RuntimeException(e); }
                running.set(false);
            });
            await(started);
            CompletableFuture<Void> closed = queue.close(() -> { check(!running.get(), "Release during decode"); released.set(true); });
            queue.stop(); check(!closed.isDone() && !released.get(), "Release must wait");
            check(queue.close(() -> { throw new AssertionError("Second release"); }) == closed, "Idempotent close");
            exit.countDown(); closed.get(5, TimeUnit.SECONDS); check(released.get(), "Eventually released");
        });
        test("queue bounds pending recordings", () -> {
            SerialRecognitionQueue queue = new SerialRecognitionQueue(); CountDownLatch start = new CountDownLatch(1), end = new CountDownLatch(1);
            queue.submit(t -> { start.countDown(); try { await(end); } catch (InterruptedException e) { throw new RuntimeException(e); } });
            await(start); for (int i=0;i<4;i++) queue.submit(t -> {});
            try { queue.submit(t -> {}); throw new AssertionError("Unbounded queue"); } catch (RejectedExecutionException expected) {}
            end.countDown(); queue.close(() -> {}).get(5, TimeUnit.SECONDS);
        });
        test("chunking covers every sample and bounds every native call", () -> {
            for (int size : new int[]{1, 320000, 320001, 16000*65}) {
                float[] data = new float[size]; Arrays.fill(data, 0.2f); int total=0;
                for (int start=0;start<data.length;) {
                    int end=QwenAudioChunks.nextEnd(data,start);
                    check(end>start && end-start<=320000, "Bounded progressing chunk"); total += end-start; start=end;
                }
                check(total==size, "No dropped samples");
            }
        });
        test("supported language mapping does not inherit Whisper coverage", () -> {
            check(QwenLanguages.codes().size()==30, "Thirty model languages");
            check(QwenLanguages.code("Urdu").isEmpty() && QwenLanguages.code("ur").isEmpty(), "Urdu is unsupported");
            check(QwenLanguages.code("Cantonese").equals("yue"), "Cantonese");
            check(QwenLanguages.code("tl").equals("fil") && QwenLanguages.code("in").equals("id"), "Locale aliases");
        });
        System.out.println("Qwen core tests: " + passed + " passed");
    }
}
