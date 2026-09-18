package nie.translator.rtranslator.settings;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.LoadingActivity;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.voice_translation._conversation_mode._conversation.ConversationService;
import nie.translator.rtranslator.voice_translation._walkie_talkie_mode._walkie_talkie.WalkieTalkieService;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen.CancellationToken;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen.QwenModelStore;

/** Model import uses the Storage Access Framework; no storage permission or network. */
public final class QwenSettingsActivity extends AppCompatActivity {
    private static final int IMPORT_ONNX = 91, IMPORT_GGUF = 92;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final String[] engines = {QwenModelStore.WHISPER, QwenModelStore.ONNX, QwenModelStore.GGUF};
    private RadioGroup choices;
    private CheckBox hardware;
    private TextView status;
    private LinearLayout content;
    private Button cancel;
    private CancellationToken operation;
    private boolean busy;
    private volatile boolean destroyed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.qwen_settings_title);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content); setContentView(scroll);
        TextView description = new TextView(this);
        description.setText(R.string.qwen_details); content.addView(description);
        choices = new RadioGroup(this);
        int[] labels = {R.string.qwen_whisper, R.string.qwen_onnx, R.string.qwen_gguf};
        for (int i = 0; i < engines.length; i++) {
            RadioButton button = new RadioButton(this); button.setId(100 + i); button.setText(labels[i]); choices.addView(button);
            if (engines[i].equals(QwenModelStore.selectedEngine(this))) choices.check(button.getId());
        }
        content.addView(choices);
        hardware = new CheckBox(this); hardware.setText(R.string.qwen_hardware);
        hardware.setChecked(QwenModelStore.wantsAcceleration(this)); content.addView(hardware);
        button(R.string.qwen_import_onnx, () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), IMPORT_ONNX));
        button(R.string.qwen_import_gguf, () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT_GGUF));
        button(R.string.qwen_apply, this::applySelection);
        button(R.string.qwen_delete, this::deleteSelected);
        button(R.string.qwen_return, this::restart);
        status = new TextView(this); content.addView(status); refreshStatus();
        cancel = button(R.string.qwen_cancel, () -> { if (operation != null) operation.cancel(); });
        cancel.setVisibility(View.GONE);
    }
    private Button button(int label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(v -> action.run()); content.addView(button); return button;
    }
    private String selected() {
        int index = choices.getCheckedRadioButtonId() - 100;
        return engines[index >= 0 && index < engines.length ? index : 0];
    }
    private void refreshStatus() {
        status.setText(getString(R.string.qwen_installed,
                QwenModelStore.current(this, QwenModelStore.ONNX) != null ? "ONNX" : "-",
                QwenModelStore.current(this, QwenModelStore.GGUF) != null ? "GGUF" : "-"));
    }
    private void setBusy(boolean value) {
        busy = value;
        for (int i = 0; i < content.getChildCount(); ++i) content.getChildAt(i).setEnabled(!value);
        for (int i = 0; i < choices.getChildCount(); ++i) choices.getChildAt(i).setEnabled(!value);
        cancel.setEnabled(value); cancel.setVisibility(value ? View.VISIBLE : View.GONE);
    }
    private void runAfterClosing(Work work) {
        if (busy) return;
        operation = new CancellationToken();
        CancellationToken token = operation;
        setBusy(true); status.setText(R.string.qwen_working);
        stopService(new Intent(this, ConversationService.class));
        stopService(new Intent(this, WalkieTalkieService.class));
        Global global = (Global) getApplication();
        global.closeSpeechRecognizer(() -> {
            if (destroyed) return;
            try { io.execute(() -> {
                String error = null;
                try { token.check(); work.run(token); }
                catch (CancellationException e) { error = getString(R.string.qwen_cancelled); }
                catch (Exception | LinkageError e) { error = getString(R.string.qwen_failed) + " " + e.getMessage(); }
                String result = error;
                runOnUiThread(() -> {
                    if (destroyed) return;
                    setBusy(false); operation = null;
                    if (result == null) restart(); else status.setText(result);
                });
            }); } catch (java.util.concurrent.RejectedExecutionException ignored) {
                token.cancel(); // Activity was destroyed while ASR was closing.
            }
        });
    }
    private void save(String engine, boolean acceleration) {
        getSharedPreferences("default", Context.MODE_PRIVATE).edit()
                .putString(QwenModelStore.ENGINE_KEY, engine)
                .putString(QwenModelStore.ACCELERATION_KEY, acceleration ? "auto" : "cpu").apply();
        if (!QwenModelStore.WHISPER.equals(engine)) {
            File directory = QwenModelStore.current(this, engine);
            if (directory != null) getSharedPreferences("qwen-runtime", Context.MODE_PRIVATE).edit()
                    .remove(QwenModelStore.failureKey(engine, directory)).apply();
        }
    }
    private void applySelection() {
        String engine = selected(); boolean acceleration = hardware.isChecked();
        if (!QwenModelStore.WHISPER.equals(engine) && QwenModelStore.current(this, engine) == null) {
            status.setText(R.string.qwen_missing_model); return;
        }
        runAfterClosing(token -> { token.check(); save(engine, acceleration); });
    }
    private void deleteSelected() {
        String engine = selected();
        if (QwenModelStore.WHISPER.equals(engine)) return;
        new androidx.appcompat.app.AlertDialog.Builder(this).setMessage(R.string.qwen_delete_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> runAfterClosing(token -> {
                token.check(); save(QwenModelStore.WHISPER, false); QwenModelStore.removeModels(this, engine);
            })).show();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null || (request != IMPORT_ONNX && request != IMPORT_GGUF)) return;
        String engine = request == IMPORT_ONNX ? QwenModelStore.ONNX : QwenModelStore.GGUF;
        Uri uri = data.getData(); boolean acceleration = hardware.isChecked();
        runAfterClosing(token -> { QwenModelStore.importModel(this, uri, engine, token); save(engine, acceleration); });
    }
    private void restart() {
        if (busy || destroyed) return;
        startActivity(new Intent(this, LoadingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }
    @Override public void onBackPressed() { if (busy) { if (operation != null) operation.cancel(); } else restart(); }
    @Override protected void onDestroy() {
        destroyed = true;
        if (operation != null) operation.cancel();
        io.shutdown();
        super.onDestroy();
    }
    private interface Work { void run(CancellationToken token) throws IOException; }
}
