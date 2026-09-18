package nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.*;
import java.util.concurrent.*;

/** Real CPU JNI smoke test; timings are host evidence, never phone benchmarks. */
public final class QwenNativeSmoke {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static float[] wav(String path) throws Exception {
        try (AudioInputStream input = AudioSystem.getAudioInputStream(new File(path))) {
            check(input.getFormat().getChannels() == 1 && input.getFormat().getSampleRate() == 16000 &&
                  input.getFormat().getSampleSizeInBits() == 16 && !input.getFormat().isBigEndian(), "Expected mono PCM16 16kHz fixture");
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) >= 0) out.write(buffer, 0, n);
            byte[] data = out.toByteArray(); float[] samples = new float[data.length / 2];
            for (int i=0;i<samples.length;i++) samples[i] = (short)((data[2*i]&255) | data[2*i+1]<<8) / 32768f;
            return samples;
        }
    }
    private static final class Observed implements QwenNative {
        private final QwenNative delegate;
        volatile CountDownLatch entry;
        Observed(QwenNative delegate) { this.delegate = delegate; }
        public long open(String path, int threads, boolean accelerator) throws IOException { return delegate.open(path,threads,accelerator); }
        public long newCancellation() { return delegate.newCancellation(); }
        public void cancel(long id) { delegate.cancel(id); }
        public void freeCancellation(long id) { delegate.freeCancellation(id); }
        public void release(long id) { delegate.release(id); }
        public byte[][] decode(long model, float[] samples, String language, long id) throws IOException {
            if (entry != null) entry.countDown();
            return delegate.decode(model,samples,language,id);
        }
    }
    public static void main(String[] args) throws Exception {
        boolean onnx = args[0].equals("onnx");
        Observed nativeApi = new Observed(onnx ? new QwenNative.Sherpa() : new QwenNative.GgufCpu());
        // Late cancel after unregister must be a harmless no-op, not a stale pointer.
        for (int i=0;i<1000;i++) {
            long signal=nativeApi.newCancellation(); nativeApi.cancel(signal); nativeApi.freeCancellation(signal);
            nativeApi.cancel(signal); nativeApi.freeCancellation(signal);
        }
        System.out.println("PASS native cancellation registry lifetime");
        if (args.length == 1) return;
        long started=System.nanoTime();
        try (NativeQwenBackend backend = new NativeQwenBackend(nativeApi, args[1], onnx, false)) {
            System.out.println("loadMs=" + (System.nanoTime()-started)/1_000_000);
            float[] english=wav(args[2]);
            QwenBackend.Result baseline=backend.decode(english,"",new CancellationToken());
            check(baseline.language.equals("en") && baseline.text.toLowerCase().contains("country"), "English transcript/LID: " + baseline.text + " / " + baseline.language);
            System.out.println("PASS real English transcription and language detection: " + baseline.text);
            if (args.length > 3) {
                QwenBackend.Result chinese=backend.decode(wav(args[3]),"",new CancellationToken());
                check(chinese.language.equals("zh") && chinese.text.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff), "Mandarin transcript/LID");
                System.out.println("PASS real Mandarin transcription and language detection: " + chinese.text);
            }
            float[] longer=new float[20*16000];
            for (int i=0;i<longer.length;i++) longer[i]=english[i%english.length];
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                CancellationToken token=new CancellationToken(); nativeApi.entry=new CountDownLatch(1);
                Future<Boolean> task=worker.submit(() -> {
                    try { backend.decode(longer,"en",token); return false; }
                    catch (CancellationException expected) { return true; }
                });
                check(nativeApi.entry.await(30,TimeUnit.SECONDS), "Native entry timeout");
                Thread.sleep(100);
                started=System.nanoTime(); token.cancel();
                check(task.get(120,TimeUnit.SECONDS), "Cancelled native result was delivered");
                System.out.println("PASS in-flight native cancellation, cancelToReturnMs="+(System.nanoTime()-started)/1_000_000);
                nativeApi.entry=null;
            } finally { worker.shutdownNow(); }
            QwenBackend.Result next=backend.decode(english,"en",new CancellationToken());
            check(next.text.toLowerCase().contains("country"), "Post-cancel session not reusable: " + next.text);
            System.out.println("PASS same model reused after cancelled decode: " + next.text);
        }
        System.out.println("PASS model release after all native calls completed");
    }
}
