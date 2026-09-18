package nie.translator.rtranslator.settings;

import android.app.Application;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.models.LegacyModelImports;
import nie.translator.rtranslator.models.QwenDownloads;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen.CancellationToken;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen.QwenModelStore;

/** Activity-scoped work survives rotation; observers are tied to each fragment's view. */
public final class ModelOperations extends AndroidViewModel {
    public static final class State {
        public final boolean busy; public final String engine, message; public final int progress;
        State(boolean busy, String engine, String message, int progress) { this.busy=busy; this.engine=engine; this.message=message; this.progress=progress; }
    }
    private interface Work { void run(CancellationToken token) throws Exception; }
    private final Global global;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private CancellationToken token;
    private boolean cleared;
    public ModelOperations(@NonNull Application application) {
        super(application); global=(Global)application;
        state.setValue(new State(false, "", "", 0));
    }
    public LiveData<State> state() { return state; }
    public boolean busy() { return token != null; }
    public void cancel() { if (token != null) token.cancel(); }
    public void importUri(Uri uri, String kind) {
        run(kind, t -> {
            if (QwenModelStore.ONNX.equals(kind) || QwenModelStore.GGUF.equals(kind)) QwenModelStore.importModel(global, uri, kind, t);
            else LegacyModelImports.install(global, uri, kind, t);
        });
    }
    public void download(String engine) {
        run(engine, t -> QwenDownloads.install(global, engine, t,
            p -> main.post(() -> { if (!cleared && token == t) state.setValue(new State(true, engine, global.getString(p >= 91 ? R.string.model_validating : R.string.model_downloading), p)); })));
    }
    public void remove(String engine) { run(engine, t -> { t.check(); QwenModelStore.removeModels(global, engine); }); }
    private void run(String kind, Work work) {
        if (busy() || cleared) return;
        for (nie.translator.rtranslator.downloader2.DownloadGroupInfo group : nie.translator.rtranslator.downloader2.DownloadManager.getSavedDownloadStatus(global)) {
            if (!group.isAllDownloadCompleted() && group.getRunningDownloadIndex() >= 0) {
                state.setValue(new State(false, kind, global.getString(R.string.model_download_busy), 0)); return;
            }
        }
        final CancellationToken current = new CancellationToken(); token = current;
        state.setValue(new State(true, kind, global.getString(R.string.model_closing), 0));
        global.models().beginLibraryOperation(() -> {
            if (cleared) { global.models().endLibraryOperation(); return; }
            state.setValue(new State(true, kind, global.getString(R.string.model_validating), 0));
            io.execute(() -> {
                String message = global.getString(R.string.model_operation_complete);
                try { current.check(); work.run(current); }
                catch (CancellationException e) { message=global.getString(R.string.qwen_cancelled); }
                catch (Exception | LinkageError e) { message=current.isCancelled() ? global.getString(R.string.qwen_cancelled) : global.getString(R.string.qwen_failed) + " " + String.valueOf(e.getMessage()); }
                final String result=message;
                main.post(() -> {
                    token=null; global.updateLanguages(); global.models().endLibraryOperation();
                    if (!cleared) state.setValue(new State(false, kind, result, 0));
                });
            });
        });
    }
    @Override protected void onCleared() { cleared=true; cancel(); io.shutdown(); }
}
