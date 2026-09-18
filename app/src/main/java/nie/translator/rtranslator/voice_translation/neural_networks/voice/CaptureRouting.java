package nie.translator.rtranslator.voice_translation.neural_networks.voice;

/** Keep a recording's direction stable across model windows and delayed ASR callbacks. */
public final class CaptureRouting {
    public enum Side { NONE, FIRST, SECOND, AUTO }
    private Side requested = Side.NONE;
    private Side active = Side.NONE;
    public synchronized void request(Side side) { requested = side; }
    public synchronized void begin() { active = requested; }
    public synchronized Side current() { return active; }
    public synchronized void end() { active = Side.NONE; }
    public synchronized void reset() { requested = active = Side.NONE; }
}
