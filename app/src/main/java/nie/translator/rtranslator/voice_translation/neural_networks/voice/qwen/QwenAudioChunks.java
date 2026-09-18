package nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen;

public final class QwenAudioChunks {
    public static final int SAMPLE_RATE = 16000;
    public static final int MAX_SAMPLES = 20 * SAMPLE_RATE;
    private QwenAudioChunks() {}
    /** Prefer the quietest 20 ms frame in the last two seconds. Never drop samples. */
    public static int nextEnd(float[] samples, int start) {
        if (start < 0 || start >= samples.length) throw new IllegalArgumentException("Invalid audio offset");
        int end = Math.min(samples.length, start + MAX_SAMPLES);
        if (end == samples.length) return end;
        int best = end;
        double bestEnergy = Double.POSITIVE_INFINITY;
        for (int candidate = end - 2 * SAMPLE_RATE; candidate < end; candidate += 320) {
            double energy = 0;
            for (int i = candidate; i < Math.min(candidate + 320, end); ++i) energy += (double) samples[i] * samples[i];
            if (energy < bestEnergy) { bestEnergy = energy; best = candidate + 160; }
        }
        return Math.max(start + 1, Math.min(end, best));
    }
}
