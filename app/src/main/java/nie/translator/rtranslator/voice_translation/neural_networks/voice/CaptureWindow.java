package nie.translator.rtranslator.voice_translation.neural_networks.voice;

/** Sample-count based rollover. A model window boundary is not an end-of-speech event. */
public final class CaptureWindow {
    private CaptureWindow() {}
    public static boolean shouldRoll(int bufferedSamples, int sampleRate, int limitMillis) {
        if (sampleRate <= 0 || limitMillis <= 0 || bufferedSamples < 0) throw new IllegalArgumentException();
        return bufferedSamples >= (long) sampleRate * limitMillis / 1000L;
    }
    public static boolean shouldEnd(boolean speechActive, boolean hearingVoice, boolean manual, long silenceMillis, int timeoutMillis) {
        return speechActive && !manual && !hearingVoice && silenceMillis > timeoutMillis;
    }
}
