package nie.translator.rtranslator.models;

import android.content.Context;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.UUID;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen.CancellationToken;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen.QwenModelStore;

/** Explicit, resumable downloads of the exact weights used by the real-model tests. */
public final class QwenDownloads {
    public interface Progress { void update(int percent); }
    private static final long LIMIT = 3L * 1024 * 1024 * 1024;
    private static final String PACKAGE = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25";
    private QwenDownloads() {}
    public static void install(Context context, String engine, CancellationToken token, Progress progress) throws Exception {
        boolean onnx = QwenModelStore.ONNX.equals(engine);
        if (!onnx && !QwenModelStore.GGUF.equals(engine)) throw new IOException("Unknown model");
        String url = onnx ? "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" + PACKAGE + ".tar.bz2"
            : "https://huggingface.co/handy-computer/Qwen3-ASR-0.6B-gguf/resolve/28c66fba077f1359a42b52c55b618a24cdb6e0e7/Qwen3-ASR-0.6B-Q4_K_M.gguf";
        String digest = onnx ? "393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96"
            : "5b58f32a58ffa2c8783e0b0963485623e286e6272d953dfc9e28bc3447dee0c0";
        File cache = new File(context.getNoBackupFilesDir(), "model-downloads");
        if (!cache.mkdirs() && !cache.isDirectory()) throw new IOException("Cannot create download directory");
        File part = new File(cache, engine + ".part");
        download(url, part, token, progress);
        token.check(); progress.update(91);
        if (!sha256(part, token).equals(digest)) { part.delete(); throw new IOException("Model checksum mismatch; retry the download"); }
        File staged = new File(cache, "prepared-" + UUID.randomUUID());
        if (!staged.mkdir()) throw new IOException("Cannot create staging directory");
        try {
            if (onnx) extract(part, staged, token);
            else copy(new FileInputStream(part), new File(staged, "model.gguf"), token, LIMIT);
            progress.update(96); token.check();
            QwenModelStore.installPrepared(context, staged, engine, token);
            part.delete(); progress.update(100);
        } finally { removeTree(staged); }
    }
    private static void download(String source, File part, CancellationToken token, Progress progress) throws Exception {
        long offset = part.isFile() ? part.length() : 0;
        URL url = new URL(source);
        HttpURLConnection c = null;
        for (int redirects = 0; redirects < 8; redirects++) {
            token.check();
            if (!"https".equals(url.getProtocol())) throw new IOException("Refusing an insecure download redirect");
            c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(15000); c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "RTranslator-model-manager");
            if (offset > 0) c.setRequestProperty("Range", "bytes=" + offset + "-");
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = c.getHeaderField("Location"); c.disconnect();
                if (location == null) throw new IOException("Missing redirect target");
                url = new URL(url, location); c = null; continue;
            }
            break;
        }
        if (c == null) throw new IOException("Too many download redirects");
        final HttpURLConnection connection = c;
        try (CancellationToken.Registration registration = token.onCancel(connection::disconnect)) {
            token.check(); int code = connection.getResponseCode();
            if (code == 416 && offset > 0) return; // Full partial file: checksum validation follows.
            if (code != 200 && code != 206) throw new IOException("Download server returned HTTP " + code);
            if (code == 206) {
                String range = connection.getHeaderField("Content-Range");
                if (range == null || !range.startsWith("bytes " + offset + "-")) throw new IOException("Invalid resume response");
            } else offset = 0;
            long length = connection.getContentLengthLong();
            long total = length > 0 ? offset + length : -1;
            if (offset > LIMIT || total > LIMIT) throw new IOException("Model download exceeds size limit");
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(part, offset > 0)) {
                byte[] buffer = new byte[128 * 1024]; long received = offset; int n, last = -1;
                while ((n = in.read(buffer)) != -1) {
                    token.check(); received += n;
                    if (received > LIMIT) throw new IOException("Model download exceeds size limit");
                    out.write(buffer, 0, n);
                    int percent = total > 0 ? (int)(90 * received / total) : 0;
                    if (percent != last) { last = percent; progress.update(percent); }
                }
                out.getFD().sync();
                if (total > 0 && received != total) throw new EOFException("Incomplete model download; retry to resume");
            }
        } finally { connection.disconnect(); }
    }
    private static String sha256(File file, CancellationToken token) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[128 * 1024]; int n;
            while ((n = in.read(b)) != -1) { token.check(); md.update(b, 0, n); }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
    private static void extract(File archive, File root, CancellationToken token) throws IOException {
        try (InputStream file = new BufferedInputStream(new FileInputStream(archive));
             BZip2CompressorInputStream bz = new BZip2CompressorInputStream(file);
             TarArchiveInputStream tar = new TarArchiveInputStream(bz)) {
            TarArchiveEntry entry; long size = 0; int count = 0;
            while ((entry = tar.getNextTarEntry()) != null) {
                token.check();
                if (++count > 512 || entry.isSymbolicLink() || entry.isLink()) throw new IOException("Unsafe model archive");
                if (entry.isDirectory()) continue;
                String prefix = PACKAGE + "/";
                if (!entry.getName().startsWith(prefix)) throw new IOException("Unexpected archive root");
                String name = entry.getName().substring(prefix.length());
                File target = new File(root, name);
                if (!target.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) throw new IOException("Unsafe archive path");
                if (entry.getSize() < 0 || (size += entry.getSize()) > LIMIT) throw new IOException("Expanded model exceeds size limit");
                // Test recordings, executable code and other unrelated assets are never installed.
                if (!(name.startsWith("tokenizer/") || name.endsWith(".onnx") || name.endsWith(".onnx.data"))) continue;
                if (!target.getParentFile().mkdirs() && !target.getParentFile().isDirectory()) throw new IOException("Cannot create model directory");
                try (OutputStream out = new FileOutputStream(target)) {
                    byte[] b = new byte[128 * 1024]; int n; long copied = 0;
                    while ((n = tar.read(b)) != -1) { token.check(); copied += n; if (copied > entry.getSize()) throw new IOException("Invalid archive length"); out.write(b,0,n); }
                    if (copied != entry.getSize()) throw new EOFException("Truncated model archive");
                }
            }
        }
    }
    static long copy(InputStream input, File output, CancellationToken token, long remaining) throws IOException {
        long total = 0;
        try (InputStream in = input; FileOutputStream out = new FileOutputStream(output)) {
            if (in == null) throw new IOException("Cannot open selected document");
            byte[] b = new byte[128 * 1024]; int n;
            while ((n = in.read(b)) != -1) { token.check(); total += n; if(total > remaining) throw new IOException("Model package exceeds size limit"); out.write(b,0,n); }
            out.getFD().sync();
        }
        return total;
    }
    public static void removeTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) removeTree(child);
        file.delete();
    }
}
