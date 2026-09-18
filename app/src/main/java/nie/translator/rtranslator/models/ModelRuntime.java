package nie.translator.rtranslator.models;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.tools.ErrorCodes;
import nie.translator.rtranslator.voice_translation.neural_networks.NeuralNetworkApi;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator;
import nie.translator.rtranslator.voice_translation._conversation_mode._conversation.ConversationService;
import nie.translator.rtranslator.voice_translation._walkie_talkie_mode._walkie_talkie.WalkieTalkieService;

/** Main-thread coordinator: availability, loading and native readiness are distinct states. */
public final class ModelRuntime {
    public interface Listener { void onReady(); void onUnavailable(String message); }
    private static final class Request {
        final boolean voice; final Listener listener;
        Request(boolean voice, Listener listener) { this.voice = voice; this.listener = listener; }
    }
    private final Global global;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Request> pending = new ArrayList<>();
    private final List<Runnable> afterClose = new ArrayList<>();
    private boolean translatorReady, speechReady, loading, closing, libraryBusy;
    private long generation;
    public ModelRuntime(Global global) { this.global = global; }
    public boolean ready(boolean voice) { return !closing && !libraryBusy && translatorReady && (!voice || speechReady); }
    private final List<Runnable> observers = new ArrayList<>();
    public void addObserver(Runnable observer) { if (!observers.contains(observer)) observers.add(observer); }
    public void removeObserver(Runnable observer) { observers.remove(observer); }
    private void changed() { for (Runnable observer : new ArrayList<>(observers)) observer.run(); }
    public void resourcesChanged() { main.post(() -> { global.updateLanguages(); changed(); }); }
    public boolean isLoading() { return loading; }
    public boolean isClosing() { return closing || libraryBusy; }
    public void beginLibraryOperation(Runnable ready) { libraryBusy = true; closeForConfiguration(ready); }
    public void endLibraryOperation() { libraryBusy = false; changed(); }

    public void prepare(boolean voice, Listener listener) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> prepare(voice, listener)); return; }
        String missing = FeatureReadiness.missing(global, voice);
        if (isClosing() || !missing.isEmpty()) {
            listener.onUnavailable(isClosing() ? global.getString(R.string.model_closing) : missing); return;
        }
        if (ready(voice)) { listener.onReady(); return; }
        pending.add(new Request(voice, listener));
        pump();
    }
    private void pump() {
        if (loading || closing || pending.isEmpty()) return;
        loading = true;
        final long ticket = generation;
        if (!translatorReady) {
            try {
                global.initializeTranslator(new Translator.GeneralListener() {
                    @Override public void onSuccess() {
                        main.post(() -> { if (ticket != generation) return; translatorReady = true; loading = false; deliver(); pump(); });
                    }
                    @Override public void onFailure(int[] reasons, long value) { fail(ticket); }
                });
            } catch (Exception | LinkageError e) { fail(ticket); }
        } else {
            try {
                global.initializeSpeechRecognizer(new NeuralNetworkApi.InitListener() {
                    @Override public void onInitializationFinished() {
                        main.post(() -> { if (ticket != generation) return; speechReady = true; loading = false; deliver(); pump(); });
                    }
                    @Override public void onError(int[] reasons, long value) { fail(ticket); }
                });
            } catch (Exception | LinkageError e) { fail(ticket); }
        }
    }
    private void deliver() {
        List<Request> completed = new ArrayList<>();
        for (Request request : pending) if (ready(request.voice)) completed.add(request);
        pending.removeAll(completed);
        for (Request request : completed) request.listener.onReady();
    }
    private void fail(long ticket) {
        main.post(() -> {
            if (ticket != generation) return;
            List<Request> failed = new ArrayList<>(pending); pending.clear();
            closeForConfiguration(() -> {
                for (Request request : failed) request.listener.onUnavailable(global.getString(R.string.model_load_failed));
            });
        });
    }
    /** Do not replace/delete mapped files, or block the UI waiting for native cancellation. */
    public void closeForConfiguration(Runnable done) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> closeForConfiguration(done)); return; }
        afterClose.add(done);
        if (closing) return;
        closing = true; loading = false; translatorReady = false; speechReady = false; generation++;
        global.setModelsLoaded(false);
        List<Request> abandoned = new ArrayList<>(pending); pending.clear();
        for (Request request : abandoned) request.listener.onUnavailable(global.getString(R.string.model_closing));
        global.stopService(new Intent(global, ConversationService.class));
        global.stopService(new Intent(global, WalkieTalkieService.class));
        global.closeSpeechRecognizer(() -> main.post(() -> global.closeTranslator(() -> main.post(() -> {
            closing = false;
            List<Runnable> callbacks = new ArrayList<>(afterClose); afterClose.clear();
            for (Runnable callback : callbacks) callback.run();
            changed();
        }))));
    }
}
