package nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.*;

/** Real CPU JNI smoke test; timings are host evidence, never phone benchmarks. */
public final class QwenNativeSmoke {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static float[] wav(String path) throws Exception {
        try (AudioInputStream input = AudioSystem.getAudioInputStream(new File(path))) {
            AudioFormat format = input.getFormat();
            boolean pcm16 = AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding()) &&
                    format.getSampleSizeInBits() == 16;
            boolean float32 = AudioFormat.Encoding.PCM_FLOAT.equals(format.getEncoding()) &&
                    format.getSampleSizeInBits() == 32;
            int frameSize = pcm16 ? 2 : 4;
            check(format.getChannels() == 1 && format.getSampleRate() == 16000 &&
                    !format.isBigEndian() && (pcm16 || float32) && format.getFrameSize() == frameSize,
                    "Expected mono PCM16 or float32 16kHz fixture: " + format);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) >= 0) out.write(buffer, 0, n);
            byte[] data = out.toByteArray();
            check(data.length > 0 && data.length % frameSize == 0, "Incomplete WAV frames");
            ByteBuffer bytes = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            float[] samples = new float[data.length / frameSize];
            for (int i = 0; i < samples.length; i++) {
                // Preserve the publisher's float waveform; do not quantize it to PCM16.
                samples[i] = pcm16 ? bytes.getShort() / 32768f : bytes.getFloat();
                check(Float.isFinite(samples[i]) && Math.abs(samples[i]) <= 1.0f,
                        "Fixture contains invalid normalized waveform samples");
            }
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
        if (args.length > 0 && args[0].equals("--fixtures")) {
            check(args.length > 1, "At least one WAV fixture is required");
            for (int i = 1; i < args.length; i++) {
                float[] samples = wav(args[i]);
                check(samples.length <= 20 * 16000, "Fixture exceeds native audio bounds");
                System.out.println("PASS WAV fixture " + args[i] + " samples=" + samples.length);
            }
            return;
        }
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
