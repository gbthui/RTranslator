package nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen;

import java.io.IOException;

/** Owned by the recognizer worker. Cancellation may be signalled from any thread. */
public interface QwenBackend extends AutoCloseable {
    Result decode(float[] samples, String language, CancellationToken cancellation) throws IOException;
    @Override void close();

    final class Result {
        public final String text;
        public final String language;
        public Result(String text, String language) {
            this.text = text.trim();
            this.language = QwenLanguages.code(language);
        }
    }
}
