import java.io.*;
import java.nio.file.*;
import nie.translator.rtranslator.models.ModelFiles;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.CaptureWindow;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.CaptureRouting;

/** Dependency and capture-policy tests, deliberately runnable without Android or model downloads. */
public final class ModelLibraryTest {
    private static int passed;
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        passed++; System.out.println("PASS " + message);
    }
    private static void file(File root, String name) throws Exception {
        File target = new File(root, name); target.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(target)) { out.write(1); }
    }
    private static void remove(File f) {
        File[] children = f.listFiles(); if (children != null) for (File child: children) remove(child);
        f.delete();
    }
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("model-library-tests-").toFile();
        try {
            check(!ModelFiles.complete(root, ModelFiles.WHISPER), "empty library has no speech model");
            check(!ModelFiles.complete(root, ModelFiles.HY_MT), "empty library has no translation model");
            check(ModelFiles.mozillaLanguages(root).isEmpty(), "empty Mozilla library does not invent English support");
            file(root, ModelFiles.HY_MT[0]);
            check(!ModelFiles.complete(root, ModelFiles.HY_MT), "missing tokenizer disables translation");
            new File(root, ModelFiles.HY_MT[1]).createNewFile();
            check(!ModelFiles.complete(root, ModelFiles.HY_MT), "zero-byte tokenizer is not ready");
            file(root, ModelFiles.HY_MT[1]);
            check(ModelFiles.complete(root, ModelFiles.HY_MT) && !ModelFiles.complete(root, ModelFiles.WHISPER), "text model is ready without ASR or optional databases");
            for (String name : ModelFiles.WHISPER) file(root, name);
            check(ModelFiles.complete(root, ModelFiles.WHISPER), "Whisper package needs only its six inference files");
            new File(root, ModelFiles.WHISPER[2]).delete();
            check(!ModelFiles.complete(root, ModelFiles.WHISPER), "removed model is immediately unavailable");
            for (String dir : new String[]{"zh/zhen", "zh/enzh"}) {
                file(root, dir + "/model.intgemm.alphas.bin"); file(root, dir + "/vocab.spm");
            }
            check(!ModelFiles.mozillaLanguage(root, "zh"), "Mozilla requires shortlists as well as weights and vocabulary");
            file(root, "zh/zhen/lex.50.bin");
            check(!ModelFiles.mozillaLanguage(root, "zh"), "one translation direction is insufficient");
            file(root, "zh/enzh/lex.50.bin");
            check(ModelFiles.mozillaLanguages(root).size() == 1, "complete imported language is discovered without download metadata");
            check(!ModelFiles.mozillaLanguage(root, "../zh"), "invalid language-directory code is rejected");
            check(!CaptureWindow.shouldRoll(16000 * 29 - 1, 16000, 29000), "no premature recording rollover");
            check(CaptureWindow.shouldRoll(16000 * 29, 16000, 29000), "recording limit is sample-count based");
            check(!CaptureWindow.shouldEnd(true, true, false, 50000, 1000), "long continuous speech is not a speech-end event");
            check(!CaptureWindow.shouldEnd(true, false, true, 50000, 1000), "manual capture retains its direction across pauses");
            check(CaptureWindow.shouldEnd(true, false, false, 1500, 1000), "automatic speech ends after a real silence timeout");
            CaptureRouting routing = new CaptureRouting();
            routing.request(CaptureRouting.Side.FIRST); routing.begin();
            for (int i = 0; i < 100; i++) {
                if (routing.current() != CaptureRouting.Side.FIRST) throw new AssertionError("window lost direction");
            }
            check(routing.current() == CaptureRouting.Side.FIRST, "100 model windows keep the same recording direction");
            routing.request(CaptureRouting.Side.SECOND);
            check(routing.current() == CaptureRouting.Side.FIRST, "next recording request cannot reroute pending final audio");
            routing.end(); routing.begin();
            check(routing.current() == CaptureRouting.Side.SECOND, "end of old recording cannot clear next recording request");
            routing.reset(); check(routing.current() == CaptureRouting.Side.NONE, "mode switch clears capture routing");
            System.out.println("Model library / capture tests: " + passed + " passed");
        } finally { remove(root); }
    }
}
