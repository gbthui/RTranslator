package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import android.content.Context;
import java.util.ArrayList;
import ai.onnxruntime.OrtException;
import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.voice_translation.neural_networks.NeuralNetworkApi;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen.QwenModelStore;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen.QwenLanguages;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen.QwenRecognizer;

/** Shared ASR boundary. A model format is not a speech recognition algorithm. */
public abstract class Recognizer extends NeuralNetworkApi {
    public static final String UNDEFINED_TEXT = "[(und)]";

    public static Recognizer create(Global global, InitListener listener) {
        String engine = QwenModelStore.selectedEngine(global);
        if (QwenModelStore.WHISPER.equals(engine)) return new WhisperRecognizer(global, true, listener);
        return new QwenRecognizer(global, engine, listener);
    }

    public static ArrayList<CustomLocale> getSupportedLanguages(Context context) {
        if (QwenModelStore.WHISPER.equals(QwenModelStore.selectedEngine(context))) {
            return WhisperRecognizer.getSupportedLanguages(context);
        }
        ArrayList<CustomLocale> result = new ArrayList<>();
        for (String code : QwenLanguages.codes()) result.add(CustomLocale.getInstance(code));
        return result;
    }

    public abstract void recognize(float[] audio, int beamSize, String language);
    public abstract void recognize(float[] audio, int beamSize, String firstLanguage, String secondLanguage);
    public abstract void addCallback(RecognizerListener listener);
    public abstract void removeCallback(RecognizerListener listener);
    public abstract void addMultiCallback(RecognizerMultiListener listener);
    public abstract void removeMultiCallback(RecognizerMultiListener listener);
    public void addAutoCallback(RecognizerAutoListener listener) {}
    public void removeAutoCallback(RecognizerAutoListener listener) {}
    public void initializeEncoderSession() throws OrtException {}
    public abstract void stop();
    public abstract void destroy();
    public abstract void closeAsync(Runnable afterClose);
}
