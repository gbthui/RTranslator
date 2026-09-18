package nie.translator.rtranslator.models;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Cheap availability checks, not a substitute for the runtime's model validation. */
public final class ModelFiles {
    public static final String[] WHISPER = {"Whisper_cache_initializer.onnx",
        "Whisper_cache_initializer_batch.onnx", "Whisper_decoder.onnx", "Whisper_detokenizer.onnx",
        "Whisper_encoder.onnx", "Whisper_initializer.onnx"};
    public static final String[] HY_MT = {"model_int8_final.onnx", "tokenizer.json"};
    public static final String[] MADLAD = {"Int4Acc4/madlad_encoder_4bit.onnx",
        "Int4Acc4/madlad_decoder_4bit.onnx", "Int4Acc4/madlad_cache_initializer_4bit.onnx",
        "madlad_embed_8bit.onnx", "spiece.model"};
    private ModelFiles() {}
    public static boolean complete(File root, String... names) {
        for (String name : names) {
            File f = new File(root, name);
            if (!f.isFile() || f.length() <= 0) return false;
        }
        return names.length > 0;
    }
    public static boolean mozillaLanguage(File root, String code) {
        if (code == null || !code.matches("[a-z]{2,3}") || code.equals("en")) return false;
        return mozillaDirection(new File(root, code + "/" + code + "en"))
            && mozillaDirection(new File(root, code + "/en" + code));
    }
    private static boolean mozillaDirection(File folder) {
        boolean model = false, src = false, tgt = false, lex = false;
        File[] files = folder.listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (!file.isFile() || file.length() <= 0) continue;
            String n = file.getName();
            if (n.contains("model")) model = true;
            else if (n.contains("srcvocab")) src = true;
            else if (n.contains("trgvocab")) tgt = true;
            else if (n.contains("vocab")) { src = true; tgt = true; }
            else if (n.contains("lex.")) lex = true;
        }
        return model && src && tgt && lex;
    }
    public static List<String> mozillaLanguages(File root) {
        List<String> languages = new ArrayList<>();
        File[] dirs = root.listFiles();
        if (dirs != null) for (File dir : dirs) {
            if (dir.isDirectory() && mozillaLanguage(root, dir.getName())) languages.add(dir.getName());
        }
        return languages;
    }
}
