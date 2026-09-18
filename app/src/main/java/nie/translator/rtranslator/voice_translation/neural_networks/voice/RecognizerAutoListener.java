package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import nie.translator.rtranslator.voice_translation.neural_networks.NeuralNetworkApiListener;

/** A single auto-detected transcript, without an invented confidence score. */
public interface RecognizerAutoListener extends NeuralNetworkApiListener {
    void onSpeechRecognizedAutoResult(String text, String languageCode);
}
