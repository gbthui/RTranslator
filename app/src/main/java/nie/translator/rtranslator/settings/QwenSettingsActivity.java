package nie.translator.rtranslator.settings;

import android.content.Intent;
import android.os.Bundle;
import nie.translator.rtranslator.GeneralActivity;

/** Compatibility redirect for old shortcuts; model UI lives in the existing model manager. */
public final class QwenSettingsActivity extends GeneralActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(new Intent(this, SettingsActivity.class).putExtra("startWithModelManager", true));
        finish();
    }
}
