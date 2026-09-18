package nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Qwen3-ASR's declared 30 languages, not Whisper's much larger language list. */
public final class QwenLanguages {
    private static final Map<String, String> NAMES;
    static {
        String[] codes = {"zh","en","yue","ar","de","fr","es","pt","id","it","ko","ru","th","vi","ja","tr","hi","ms","nl","sv","da","fi","pl","cs","fil","fa","el","hu","mk","ro"};
        String[] names = {"Chinese","English","Cantonese","Arabic","German","French","Spanish","Portuguese","Indonesian","Italian","Korean","Russian","Thai","Vietnamese","Japanese","Turkish","Hindi","Malay","Dutch","Swedish","Danish","Finnish","Polish","Czech","Filipino","Persian","Greek","Hungarian","Macedonian","Romanian"};
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < codes.length; ++i) map.put(codes[i], names[i]);
        NAMES = Collections.unmodifiableMap(map);
    }
    private QwenLanguages() {}
    public static Set<String> codes() { return NAMES.keySet(); }
    public static String code(String value) {
        if (value == null) return "";
        String text = value.trim().toLowerCase(Locale.ROOT);
        if (text.equals("tl")) return "fil";
        if (text.equals("in")) return "id";
        if (NAMES.containsKey(text)) return text;
        for (Map.Entry<String, String> entry : NAMES.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(text)) return entry.getKey();
        }
        // Only normalize actual locale codes; do not turn an unknown model label into a language.
        int separator = Math.max(text.indexOf('-'), text.indexOf('_'));
        if (separator > 0 && NAMES.containsKey(text.substring(0, separator))) {
            return text.substring(0, separator);
        }
        return "";
    }
    public static String name(String code) {
        String normalized = code(code);
        if (normalized.isEmpty()) throw new IllegalArgumentException("Unsupported Qwen3-ASR language: " + code);
        return NAMES.get(normalized);
    }
}
