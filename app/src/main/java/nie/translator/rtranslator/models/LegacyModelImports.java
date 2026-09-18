package nie.translator.rtranslator.models;

import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.downloader2.DownloadGroupInfo;
import nie.translator.rtranslator.downloader2.DownloadInfo;
import nie.translator.rtranslator.tools.DownloaderTools;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen.CancellationToken;

/** SAF copies of existing packages, with staging and validation before publication. */
public final class LegacyModelImports {
    private static final long LIMIT = 8L * 1024 * 1024 * 1024;
    private LegacyModelImports() {}
    public static void install(Global g, Uri uri, String kind, CancellationToken token) throws IOException {
        DocumentFile source = DocumentFile.fromTreeUri(g, uri);
        if (source == null || !source.isDirectory()) throw new IOException("Select an extracted model folder");
        File staged = new File(g.getFilesDir(), ".model-import-" + UUID.randomUUID());
        if (!staged.mkdir()) throw new IOException("Cannot stage model package");
        try {
            if ("whisper".equals(kind)) {
                long total = 0;
                for (String name : ModelFiles.WHISPER) {
                    DocumentFile doc = source.findFile(name);
                    if (doc == null || !doc.isFile()) throw new IOException("Missing " + name);
                    total += QwenDownloads.copy(g.getContentResolver().openInputStream(doc.getUri()), new File(staged, name), token, LIMIT-total);
                }
                validateOnnx(staged, token);
                List<File> published = new ArrayList<>();
                // Never overwrite a user's current package with a partially imported one.
                for (String name : ModelFiles.WHISPER) if (new File(g.getFilesDir(), name).exists())
                    throw new IOException("Whisper files already exist. Delete them in model management before importing a replacement.");
                try {
                    for (String name : ModelFiles.WHISPER) {
                        token.check(); File target = new File(g.getFilesDir(), name);
                        if (!new File(staged, name).renameTo(target)) throw new IOException("Could not install Whisper file");
                        published.add(target);
                    }
                } catch (IOException | RuntimeException e) { for (File f : published) f.delete(); throw e; }
                mark(g, g.getWhisperDownloadInfo());
            } else {
                if (!("HY-MT".equals(kind) || "Madlad".equals(kind) || "Mozilla".equals(kind)
                        || "Tatoeba".equals(kind) || "TranslationDictionaries".equals(kind))) throw new IOException("Unknown model package");
                // Select either the package directory itself or its containing Translation directory.
                DocumentFile child = source.findFile(kind);
                if (child != null && child.isDirectory()) source = child;
                copyTree(g, source, staged, token, new long[]{0,0}, 0);
                validateOnnx(staged, token);
                File target = new File(FeatureReadiness.translation(g), kind);
                if ("HY-MT".equals(kind) && !ModelFiles.complete(staged, ModelFiles.HY_MT)) throw new IOException("Missing HY-MT ONNX model or tokenizer");
                if ("Madlad".equals(kind) && !ModelFiles.complete(staged, ModelFiles.MADLAD)) throw new IOException("Select the Madlad folder containing Int4Acc4, embedding and spiece.model");
                if ("Tatoeba".equals(kind) && !ModelFiles.complete(staged, "tatoeba.db")) throw new IOException("Missing tatoeba.db");
                if ("TranslationDictionaries".equals(kind) && !ModelFiles.complete(staged, "translation_dict_ordered.db")) throw new IOException("Missing translation_dict_ordered.db");
                List<String> languages = new ArrayList<>();
                if ("Mozilla".equals(kind)) {
                    // One language folder (e.g. zh/{zhen,enzh}) or a whole Mozilla folder.
                    String folderName = source.getName();
                    if (folderName != null && folderName.matches("[a-z]{2,3}")) {
                        File wrapper = new File(staged.getParentFile(), ".mozilla-import-" + UUID.randomUUID());
                        if (!wrapper.mkdir() || !staged.renameTo(new File(wrapper, folderName))) throw new IOException("Cannot stage language model");
                        QwenDownloads.removeTree(staged); staged = wrapper;
                    }
                    languages = ModelFiles.mozillaLanguages(staged);
                    if (languages.isEmpty()) throw new IOException("Mozilla needs both translation directions, vocabulary and shortlist files");
                    if (!target.mkdirs() && !target.isDirectory()) throw new IOException("Cannot create Mozilla directory");
                    for (String code : languages) if (new File(target, code).exists()) throw new IOException("Language already installed: " + code);
                    List<File> published = new ArrayList<>();
                    try {
                        for (String code : languages) {
                            token.check(); File installed = new File(target, code);
                            if (!new File(staged, code).renameTo(installed)) throw new IOException("Cannot install Mozilla language");
                            published.add(installed);
                        }
                    } catch (IOException | RuntimeException e) { for (File f : published) QwenDownloads.removeTree(f); throw e; }
                    for (Global.MozillaLanguageDownloadInfo info : g.getMozillaLanguagesDownloadInfo(true))
                        if (languages.contains(info.lang.getLanguage())) mark(g, info.downloadGroupInfo);
                } else {
                    if (target.exists()) throw new IOException("Package already exists. Delete it in model management before importing a replacement.");
                    if (!target.getParentFile().mkdirs() && !target.getParentFile().isDirectory()) throw new IOException("Cannot create package directory");
                    token.check(); if (!staged.renameTo(target)) throw new IOException("Cannot publish imported package");
                    if ("HY-MT".equals(kind)) mark(g, g.getHyMtDownloadInfo());
                    else if ("Madlad".equals(kind)) mark(g, g.getMadladDownloadInfo());
                    else if ("Tatoeba".equals(kind)) mark(g, g.getTatoebaDownloadInfo());
                    else mark(g, g.getDictionariesDownloadInfo());
                }
            }
        } finally { QwenDownloads.removeTree(staged); }
    }
    private static void copyTree(Global g, DocumentFile source, File target, CancellationToken token, long[] budget, int depth) throws IOException {
        if (depth > 8) throw new IOException("Model directory is too deeply nested");
        for (DocumentFile doc : source.listFiles()) {
            token.check(); if (++budget[1] > 4096) throw new IOException("Too many model assets");
            String name = doc.getName();
            if (name == null || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) throw new IOException("Invalid asset name");
            File output = new File(target, name);
            if (doc.isDirectory()) {
                if (!output.mkdir()) throw new IOException("Cannot create asset directory");
                copyTree(g, doc, output, token, budget, depth+1);
            } else if (doc.isFile()) {
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (lower.endsWith(".so") || lower.endsWith(".dll") || lower.endsWith(".dex") || lower.endsWith(".apk") || lower.endsWith(".exe") || lower.endsWith(".jar"))
                    throw new IOException("Model packages must not contain executable code");
                budget[0] += QwenDownloads.copy(g.getContentResolver().openInputStream(doc.getUri()), output, token, LIMIT-budget[0]);
            }
        }
    }
    private static void validateOnnx(File folder, CancellationToken token) throws IOException {
        File[] children = folder.listFiles();
        if (children == null) throw new IOException("Cannot inspect model folder");
        for (File file : children) {
            token.check();
            if (file.isDirectory()) validateOnnx(file, token);
            else if (file.getName().endsWith(".onnx")) {
                final boolean[] valid = {false};
                nie.translator.rtranslator.voice_translation.neural_networks.NeuralNetworkApi.testModelIntegrity(file.getPath(),
                    new nie.translator.rtranslator.voice_translation.neural_networks.NeuralNetworkApi.InitListener() {
                        public void onInitializationFinished() { valid[0] = true; }
                        public void onError(int[] reasons, long value) { valid[0] = false; }
                    });
                token.check();
                if (!valid[0]) throw new IOException("Invalid or incompatible ONNX file: " + file.getName());
            }
        }
    }
    public static void mark(Global g, DownloadGroupInfo group) {
        for (DownloadInfo info : group.downloadsInfo) {
            info.setDownloadCompleted(true); if (info.shouldUnzip()) info.setUnzipped(true);
            // ONNX sessions have been validated before publication; other packages passed structural checks.
            if (info.shouldTestIntegrity()) info.setIntegrityTested(true);
        }
        group.setAllDownloadCompleted(true); group.setRunningDownloadIndex(-1); group.setCurrentProgress(100);
        DownloaderTools.addDownloadGroupInfoPreference(g, group);
        DownloaderTools.updateDownloadGroupInfoPreference(g, group);
    }
}
