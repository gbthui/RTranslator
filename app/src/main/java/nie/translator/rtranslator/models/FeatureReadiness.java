package nie.translator.rtranslator.models;

import android.os.Environment;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.downloader2.DownloadGroupInfo;
import nie.translator.rtranslator.downloader2.DownloadInfo;
import nie.translator.rtranslator.downloader2.DownloadManager;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen.QwenModelStore;

/** An empty or partially configured model library is a normal application state. */
public final class FeatureReadiness {
    private FeatureReadiness() {}
    public static File base(Global g) {
        return Global.USE_EXTERNAL_MEMORY_FOR_RESOURCES
            ? new File(Environment.getExternalStorageDirectory(), "models") : g.getFilesDir();
    }
    public static File translation(Global g) { return new File(base(g), "Translation"); }
    public static boolean mozilla(Global g) {
        return !ModelFiles.mozillaLanguages(new File(translation(g), "Mozilla")).isEmpty();
    }
    public static boolean dictionaries(Global g) {
        return ModelFiles.complete(translation(g), "TranslationDictionaries/translation_dict_ordered.db");
    }
    public static boolean tatoeba(Global g) {
        return ModelFiles.complete(translation(g), "Tatoeba/tatoeba.db");
    }
    public static boolean translation(Global g, int mode) {
        if (mode == Translator.MOZILLA) return mozilla(g);
        if (mode == Translator.HY_MT) return ModelFiles.complete(new File(translation(g), "HY-MT"), ModelFiles.HY_MT);
        if (mode == Translator.MADLAD || mode == Translator.MADLAD_CACHE)
            return ModelFiles.complete(new File(translation(g), "Madlad"), ModelFiles.MADLAD);
        return false;
    }
    public static boolean speech(Global g, String engine) {
        return QwenModelStore.WHISPER.equals(engine)
            ? ModelFiles.complete(g.getFilesDir(), ModelFiles.WHISPER)
            : QwenModelStore.current(g, engine) != null;
    }
    public static String missing(Global g, boolean voice) {
        List<String> missing = new ArrayList<>();
        int mode = g.getTranslationMode();
        if (!translation(g, mode)) missing.add(g.getString(R.string.model_translation_missing));
        if (voice && g.isUseMozillaForVoiceTranslation() && !mozilla(g))
            missing.add(g.getString(R.string.model_voice_translation_missing));
        if (voice && !speech(g, QwenModelStore.selectedEngine(g)))
            missing.add(g.getString(R.string.model_speech_missing));
        // Never treat partially overwritten legacy packages as ready during a download.
        for (DownloadGroupInfo group : DownloadManager.getSavedDownloadStatus(g)) {
            if (group.isAllDownloadCompleted() || group.getRunningDownloadIndex() < 0) continue;
            for (DownloadInfo info : group.downloadsInfo) {
                String path = info.getDestinationCompletePath();
                boolean selected = mode == Translator.HY_MT && path.contains("HY-MT")
                    || (mode == Translator.MADLAD || mode == Translator.MADLAD_CACHE) && path.contains("Madlad")
                    || (mode == Translator.MOZILLA || voice && g.isUseMozillaForVoiceTranslation()) && path.contains("Mozilla")
                    || voice && QwenModelStore.WHISPER.equals(QwenModelStore.selectedEngine(g)) && path.contains("Whisper_");
                if (selected && missing.isEmpty()) missing.add(g.getString(R.string.model_download_in_progress));
            }
        }
        return android.text.TextUtils.join("\n", missing);
    }
}
