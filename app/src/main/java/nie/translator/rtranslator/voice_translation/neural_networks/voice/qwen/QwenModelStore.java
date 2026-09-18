package nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.AtomicFile;
import androidx.documentfile.provider.DocumentFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Imports weights only. Native code always comes from the APK, never a model package. */
public final class QwenModelStore {
    public static final String WHISPER = "whisper";
    public static final String ONNX = "qwen-onnx";
    public static final String GGUF = "qwen-gguf";
    public static final String ENGINE_KEY = "speechRecognitionEngine";
    public static final String ACCELERATION_KEY = "qwenAcceleration";
    private static final String[] WEIGHTS = {"conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx"};
    private static final String[] TOKENIZER = {"vocab.json", "merges.txt", "tokenizer_config.json"};
    private static final Object IMPORT_LOCK = new Object();
    private static final long LIMIT = 3L * 1024 * 1024 * 1024;
    private QwenModelStore() {}

    public static String selectedEngine(Context context) {
        String value = context.getSharedPreferences("default", Context.MODE_PRIVATE).getString(ENGINE_KEY, WHISPER);
        return ONNX.equals(value) || GGUF.equals(value) ? value : WHISPER;
    }
    public static boolean wantsAcceleration(Context context) {
        return "auto".equals(context.getSharedPreferences("default", Context.MODE_PRIVATE).getString(ACCELERATION_KEY, "cpu"));
    }
    public static File root(Context context, String engine) {
        if (!ONNX.equals(engine) && !GGUF.equals(engine)) throw new IllegalArgumentException("Invalid Qwen engine");
        return new File(context.getNoBackupFilesDir(), "qwen3/" + engine);
    }
    public static File current(Context context, String engine) {
        try {
            File root = root(context, engine);
            String marker = new String(new AtomicFile(new File(root, "current")).readFully(), StandardCharsets.UTF_8).trim();
            if (!marker.matches("model-[a-f0-9-]{36}")) return null;
            File model = new File(root, marker);
            validate(model, engine);
            return model;
        } catch (IOException | RuntimeException e) { return null; }
    }
    public static void validate(File directory, String engine) throws IOException {
        if (GGUF.equals(engine)) {
            File file = new File(directory, "model.gguf");
            if (!file.isFile() || file.length() < 24) throw new IOException("Incomplete GGUF model");
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                if (input.readInt() != 0x47475546) throw new IOException("Invalid GGUF header");
                int version = Integer.reverseBytes(input.readInt());
                if (version < 2 || version > 3 || Long.reverseBytes(input.readLong()) <= 0 || Long.reverseBytes(input.readLong()) <= 0) {
                    throw new IOException("Unsupported GGUF header");
                }
            }
        } else if (ONNX.equals(engine)) {
            for (String name : WEIGHTS) requireFile(new File(directory, name));
            for (String name : TOKENIZER) requireFile(new File(directory, "tokenizer/" + name));
        } else throw new IOException("Unsupported model format");
    }
    private static void requireFile(File file) throws IOException {
        if (!file.isFile() || file.length() == 0) throw new IOException("Missing model asset: " + file.getName());
    }
    public static QwenBackend open(File directory, String engine, boolean acceleration) throws IOException {
        validate(directory, engine);
        try {
            QwenNative api;
            String path;
            if (ONNX.equals(engine)) { api = new QwenNative.Sherpa(); path = directory.getAbsolutePath(); }
            else { api = acceleration ? new QwenNative.GgufVulkan() : new QwenNative.GgufCpu(); path = new File(directory, "model.gguf").getAbsolutePath(); }
            return new NativeQwenBackend(api, path, ONNX.equals(engine), acceleration);
        } catch (LinkageError e) { throw new IOException("The APK does not contain a compatible Qwen runtime", e); }
    }
    public static String failureKey(String engine, File directory) {
        return "qwen-native-v1:" + Build.FINGERPRINT + ":" + engine + ":" + directory.getName();
    }

    /** Caller closes active ASR first. A failed import never replaces the current marker. */
    public static File importModel(Context context, Uri uri, String engine, CancellationToken token) throws IOException {
        synchronized (IMPORT_LOCK) {
            File root = root(context, engine);
            if (!root.mkdirs() && !root.isDirectory()) throw new IOException("Cannot create model directory");
            File staged = new File(root, "model-" + UUID.randomUUID());
            if (!staged.mkdir()) throw new IOException("Cannot create staging directory");
            boolean published = false;
            try {
                List<DocumentFile> sources = new ArrayList<>();
                List<String> destinations = new ArrayList<>();
                if (GGUF.equals(engine)) {
                    DocumentFile document = DocumentFile.fromSingleUri(context, uri);
                    if (document == null || !document.isFile()) throw new IOException("Select a Qwen3-ASR GGUF file");
                    sources.add(document); destinations.add("model.gguf");
                } else {
                    DocumentFile folder = DocumentFile.fromTreeUri(context, uri);
                    if (folder == null || !folder.isDirectory()) throw new IOException("Select the ONNX model folder");
                    for (String name : WEIGHTS) {
                        DocumentFile document = folder.findFile(name);
                        if (document == null || !document.isFile()) throw new IOException("Missing " + name);
                        sources.add(document); destinations.add(name);
                    }
                    DocumentFile tokenizer = folder.findFile("tokenizer");
                    if (tokenizer == null || !tokenizer.isDirectory()) throw new IOException("Missing tokenizer folder");
                    for (DocumentFile document : tokenizer.listFiles()) {
                        String name = document.getName();
                        if (document.isFile() && safeName(name)) { sources.add(document); destinations.add("tokenizer/" + name); }
                    }
                    for (DocumentFile document : folder.listFiles()) {
                        String name = document.getName();
                        if (document.isFile() && safeName(name) && (name.endsWith(".onnx.data") || name.endsWith(".onnx_data"))) {
                            sources.add(document); destinations.add(name);
                        }
                    }
                }
                if (sources.size() > 64) throw new IOException("Too many model assets");
                byte[] buffer = new byte[128 * 1024];
                long total = 0;
                for (int i = 0; i < sources.size(); ++i) {
                    token.check();
                    File output = new File(staged, destinations.get(i));
                    if (!output.getCanonicalPath().startsWith(staged.getCanonicalPath() + File.separator)) throw new IOException("Invalid model path");
                    if (!output.getParentFile().mkdirs() && !output.getParentFile().isDirectory()) throw new IOException("Cannot create model directory");
                    try (InputStream in = context.getContentResolver().openInputStream(sources.get(i).getUri());
                         FileOutputStream out = new FileOutputStream(output)) {
                        if (in == null) throw new IOException("Cannot open model asset");
                        int count;
                        while ((count = in.read(buffer)) != -1) {
                            token.check(); total += count;
                            if (total > LIMIT) throw new IOException("Model package exceeds 3 GiB");
                            out.write(buffer, 0, count);
                        }
                        out.getFD().sync();
                    }
                }
                token.check(); validate(staged, engine);
                // The CPU loader also validates architecture, tensors and tokenizer.
                try (QwenBackend ignored = open(staged, engine, false)) { token.check(); }
                AtomicFile marker = new AtomicFile(new File(root, "current"));
                FileOutputStream output = null;
                try {
                    output = marker.startWrite();
                    output.write(staged.getName().getBytes(StandardCharsets.UTF_8));
                    token.check(); marker.finishWrite(output); output = null;
                    published = true;
                } finally { if (output != null) marker.failWrite(output); }
                // Keep previous generations: another activity may still hold a mapped model.
                // Removal is an explicit settings operation after all ASR has closed.
                return staged;
            } finally { if (!published) deleteTree(staged); }
        }
    }
    private static boolean safeName(String name) {
        return name != null && !name.equals(".") && !name.equals("..") && !name.contains("/") && !name.contains("\\");
    }
    static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
    public static void removeModels(Context context, String engine) {
        synchronized (IMPORT_LOCK) { deleteTree(root(context, engine)); }
    }
}
