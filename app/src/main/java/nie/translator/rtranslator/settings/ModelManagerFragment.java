package nie.translator.rtranslator.settings;

import static nie.translator.rtranslator.tools.DownloaderTools.checkMozillaModelsPresence;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.icu.text.DecimalFormat;
import android.os.Bundle;
import android.net.Uri;
import android.widget.Toast;
import androidx.lifecycle.ViewModelProvider;
import java.io.File;
import nie.translator.rtranslator.models.FeatureReadiness;
import nie.translator.rtranslator.models.ModelFiles;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen.QwenModelStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.HashMap;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.LoadingActivity;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.access.AccessActivity;
import nie.translator.rtranslator.downloader2.DownloadGroupInfo;
import nie.translator.rtranslator.downloader2.DownloadInfo;
import nie.translator.rtranslator.downloader2.DownloadManager;
import nie.translator.rtranslator.tools.DownloaderTools;
import nie.translator.rtranslator.tools.ErrorCodes;
import nie.translator.rtranslator.tools.Tools;
import nie.translator.rtranslator.tools.gui.ResourceManagerView;
import nie.translator.rtranslator.tools.gui.SegmentProgressBar;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator;
import worker8.com.github.radiogroupplus.RadioGroupPlus;

public class ModelManagerFragment extends Fragment {
    private static int WHISPER_RAM_CONSUMPTION_MB = 900;
    private static int WHISPER_RAM_CONSUMPTION_REDUCED_MB = 500;
    private static int MOZILLA_RAM_CONSUMPTION_MB = 100;  //todo: measure it better
    private static int HY_RAM_CONSUMPTION_MB = 1900;
    private static int MADLAD_RAM_CONSUMPTION_MB = 1800;
    private static int TATOEBA_RAM_CONSUMPTION_MB = 5;    //todo: measure it better
    private static int DICT_RAM_CONSUMPTION_MB = 140;
    private Activity activity;
    private Global global;
    private DownloadManager downloadManager;
    private HashMap<String, ResourceManager> resourceManagers = new HashMap<>();
    private DownloadManager.Callback downloadManagerCallback;
    // gui
    private RadioGroupPlus radioGroup;
    private ResourceManagerView hyManagerView;
    private ResourceManagerView madladManagerView;
    private ResourceManagerView tatoebaManagerView;
    private Button applyButton;
    private SegmentProgressBar barRam;
    private SwitchMaterial switchMozillaForVoiceModes;
    private SwitchMaterial switchWhisperReducedRam;
    private SwitchMaterial switchTatoeba;
    private SwitchMaterial switchTranslationDict;
    private TextView textRamUsage;
    private TextView textRamUsage2;
    private ImageView arrowMozilla;
    private boolean guiStateRestored = false;
    private RadioGroupPlus speechRadios;
    private SwitchMaterial acceleration;
    private ResourceManagerView qwenOnnx, qwenGguf;
    private ModelOperations operations;
    private String pendingImport;
    private boolean closingModels;
    private static final int IMPORT_MODEL = 710;



    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_models_manager, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        radioGroup = view.findViewById(R.id.model_radios);
        hyManagerView = view.findViewById(R.id.modelHy);
        madladManagerView = view.findViewById(R.id.modelMadlad);
        tatoebaManagerView = view.findViewById(R.id.resourceTatoeba);
        applyButton = view.findViewById(R.id.buttonApply);
        barRam = view.findViewById(R.id.barRam);
        switchMozillaForVoiceModes = view.findViewById(R.id.switchMozillaForVoiceModes);
        switchWhisperReducedRam = view.findViewById(R.id.switchWhisperReducedRam);
        switchTatoeba = view.findViewById(R.id.switchTatoeba);
        switchTranslationDict = view.findViewById(R.id.switchTranslationDict);
        textRamUsage = view.findViewById(R.id.textRamUsage);
        textRamUsage2 = view.findViewById(R.id.textRamUsage2);
        arrowMozilla = view.findViewById(R.id.arrowMozilla);
        speechRadios = view.findViewById(R.id.speech_model_radios);
        acceleration = view.findViewById(R.id.switchQwenAcceleration);
        qwenOnnx = view.findViewById(R.id.modelQwenOnnx);
        qwenGguf = view.findViewById(R.id.modelQwenGguf);
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        activity = requireActivity();
        global = (Global) activity.getApplication();
        operations = new ViewModelProvider(requireActivity()).get(ModelOperations.class);
        if (savedInstanceState != null) pendingImport = savedInstanceState.getString("pendingModelImport");

        //initialize download manager
        downloadManager = new DownloadManager(global);
        resourceManagers.put("hyManager", new ResourceManager(activity, global.getHyMtDownloadInfo(), hyManagerView, downloadManager));
        resourceManagers.put("madladManager", new ResourceManager(activity, global.getMadladDownloadInfo(), madladManagerView, downloadManager));
        resourceManagers.put("tatoebaManager", new ResourceManager(activity, global.getTatoebaDownloadInfo(), tatoebaManagerView, downloadManager));

        resourceManagers.put("whisperManager", new ResourceManager(activity, global.getWhisperDownloadInfo(), getView().findViewById(R.id.modelWhisper), downloadManager));
        resourceManagers.put("dictionaryManager", new ResourceManager(activity, global.getDictionariesDownloadInfo(), getView().findViewById(R.id.resourceDictionaries), downloadManager));
        // initialize GUI based on shared preferences
        restoreGuiPreferenceState();
        Bundle selection = savedInstanceState != null ? savedInstanceState : getArguments();
        if (selection != null && selection.containsKey("draftTranslation")) {
            radioGroup.check(selection.getInt("draftTranslation"));
            speechRadios.check(selection.getInt("draftSpeech"));
            switchMozillaForVoiceModes.setChecked(selection.getBoolean("draftMozilla"));
            switchWhisperReducedRam.setChecked(selection.getBoolean("draftReducedRam"));
            switchTatoeba.setChecked(selection.getBoolean("draftTatoeba"));
            switchTranslationDict.setChecked(selection.getBoolean("draftDictionary"));
            acceleration.setChecked(selection.getBoolean("draftAcceleration"));
        }

        // initialize GUI listeners
        downloadManagerCallback = new DownloadManager.Callback() {
            @Override
            public void onServiceConnected() {
                if(!guiStateRestored) {
                    ArrayList<DownloadGroupInfo> downloadsStatus = downloadManager.getDownloadsStatus();
                    // we change the GUI based on current download status
                    restoreGuiDownloadState(downloadsStatus);
                }
            }

            @Override
            public void onProgress(DownloadGroupInfo downloadGroup, DownloadInfo download, int totalProgress, int progress, boolean unzipping, boolean testingIntegrity) {
                for(ResourceManager manager: resourceManagers.values()) {
                    if (downloadGroup.equals(manager.getDownloadInfo())) {
                        manager.setProgress(totalProgress, unzipping, testingIntegrity);
                    }
                }
            }

            @Override
            public void onCompleted(DownloadGroupInfo downloadGroup, DownloadInfo download) {

            }

            @Override
            public void onAllCompleted(DownloadGroupInfo downloadGroup) {
                global.updateLanguages();
                for(ResourceManager manager: resourceManagers.values()) {
                    if (downloadGroup.equals(manager.getDownloadInfo())) {
                        manager.setState(ResourceManagerView.State.DOWNLOADED);
                    }
                }
            }

            @Override
            public void onError(DownloadGroupInfo downloadGroup, DownloadInfo download, int reason) {
                for(ResourceManager manager: resourceManagers.values()) {
                    if (downloadGroup.equals(manager.getDownloadInfo())) {
                        manager.setError(reason);
                    }
                }
            }
        };
        arrowMozilla.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(activity instanceof SettingsActivity) {
                    Bundle draft = new Bundle(); saveSelection(draft);
                    draft.putBundle("modelSelection", new Bundle(draft));
                    ((SettingsActivity) activity).startFragment(SettingsActivity.MOZILLA_MANAGER, draft);
                }else if(activity instanceof AccessActivity){
                    ((AccessActivity) activity).startFragment(AccessActivity.MOZILLA_MANAGER, null);
                }
            }
        });
        radioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if(checkedId == R.id.radioMozilla){  //eventual deactivation of switchMozillaForVoiceModes if Mozilla is the model selected
                switchMozillaForVoiceModes.setChecked(false);
                switchMozillaForVoiceModes.setEnabled(false);
            }else{
                switchMozillaForVoiceModes.setEnabled(true);
            }
            updateRamUsageTranslation();
        });
        switchMozillaForVoiceModes.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(@NonNull CompoundButton buttonView, boolean isChecked) {
                //update the ram bar based on the new switch value
                updateRamUsageTranslation();
            }
        });
        switchWhisperReducedRam.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(@NonNull CompoundButton buttonView, boolean isChecked) {
                //update the ram bar based on the new switch value
                updateRamUsageSpeechRecognition();
            }
        });
        switchTatoeba.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(@NonNull CompoundButton buttonView, boolean isChecked) {
                updateRamUsageTranslationEnhancements();
            }
        });
        switchTranslationDict.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(@NonNull CompoundButton buttonView, boolean isChecked) {
                updateRamUsageTranslationEnhancements();
            }
        });
        speechRadios.setOnCheckedChangeListener((group, id) -> updateSpeechControls());
        getView().findViewById(R.id.importTranslationModel).setOnClickListener(v -> chooseFolder(selectedTranslationKind()));
        getView().findViewById(R.id.importSpeechModel).setOnClickListener(v -> {
            String kind = selectedEngine();
            if (QwenModelStore.GGUF.equals(kind)) chooseDocument(kind); else chooseFolder(kind);
        });
        getView().findViewById(R.id.importOptionalResource).setOnClickListener(v ->
            new MaterialAlertDialogBuilder(activity, R.style.MyThemeOverlay_MaterialComponents_MaterialAlertDialog)
                .setTitle(R.string.model_import_optional)
                .setItems(new String[]{"Tatoeba", getString(R.string.translation_dictionaries)},
                    (d, item) -> chooseFolder(item == 0 ? "Tatoeba" : "TranslationDictionaries")).show());
        getView().findViewById(R.id.cancelModelOperation).setOnClickListener(v -> operations.cancel());
        getView().findViewById(R.id.retryQwenAcceleration).setOnClickListener(v -> {
            File model = QwenModelStore.WHISPER.equals(selectedEngine()) ? null : QwenModelStore.current(global, selectedEngine());
            if (model != null) global.getSharedPreferences("default", Context.MODE_PRIVATE).edit()
                .remove(QwenModelStore.failureKey(selectedEngine(), model)).apply();
            Toast.makeText(activity, R.string.model_retry_saved, Toast.LENGTH_SHORT).show();
        });
        bindQwen(qwenOnnx, QwenModelStore.ONNX);
        bindQwen(qwenGguf, QwenModelStore.GGUF);
        operations.state().observe(getViewLifecycleOwner(), state -> {
            if (getView() == null) return;
            TextView status = getView().findViewById(R.id.modelOperationStatus);
            status.setText(state.message); status.setVisibility(state.message.isEmpty() ? View.GONE : View.VISIBLE);
            getView().findViewById(R.id.cancelModelOperation).setVisibility(state.busy ? View.VISIBLE : View.GONE);
            renderLibrary();
            if (state.busy && QwenModelStore.ONNX.equals(state.engine)) {
                qwenOnnx.setState(ResourceManagerView.State.DOWNLOADING, false); qwenOnnx.setDownloadProgress(state.progress, false, state.progress >= 91);
            } else if (state.busy && QwenModelStore.GGUF.equals(state.engine)) {
                qwenGguf.setState(ResourceManagerView.State.DOWNLOADING, false); qwenGguf.setDownloadProgress(state.progress, false, state.progress >= 91);
            }
        });
        // Unload only when opening model management, not when opening the application.
        closingModels = true; renderLibrary();
        global.models().closeForConfiguration(() -> {
            closingModels = false;
            if (isAdded() && getView() != null) renderLibrary();
        });
        applyButton.setOnClickListener((v) -> {
            applySettings();
        });
        barRam.setSegmentProgressChangeListener(new SegmentProgressBar.SegmentChangeListener() {
            @Override
            public void onSegmentProgressChanged() {
                //update the RAM text
                updateTextRamUsage();
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        boolean serviceStarted = downloadManager.subscribe(downloadManagerCallback);
        if(!serviceStarted){
            ArrayList<DownloadGroupInfo> downloadStatus = downloadManager.getSavedDownloadStatus();
            // we change the GUI based on current saved download status
            // normally we do this when the service starts, but if it won't start (paused download or other reasons)
            // we restore the GUI state based on the saved download state instead.
            restoreGuiDownloadState(downloadStatus);
        }
        renderLibrary();
    }

    @Override
    public void onStop() {
        super.onStop();
        guiStateRestored = false;
        downloadManager.unsubscribe();
    }

    public void restoreGuiPreferenceState(){
        // model selection initialization
        SharedPreferences sharedPreferences = global.getSharedPreferences("default", Context.MODE_PRIVATE);
        int mode = sharedPreferences.getInt("selectedTranslationModel", Translator.MOZILLA);
        switch (mode) {
            case Translator.MOZILLA:
                radioGroup.check(R.id.radioMozilla);
                switchMozillaForVoiceModes.setActivated(false);
                break;
            case Translator.MADLAD:
            case Translator.MADLAD_CACHE:
                radioGroup.check(R.id.radioMadlad);
                break;
            case Translator.HY_MT:
                radioGroup.check(R.id.radioHY);
                break;
        }
        // switches initialization
        switchMozillaForVoiceModes.setChecked(global.isUseMozillaForVoiceTranslation());
        switchWhisperReducedRam.setChecked(global.isWhisperReducedRam());
        switchTatoeba.setChecked(global.isUseTatoeba());
        switchTranslationDict.setChecked(global.isUseTranslationDictionaries());
        String engine = QwenModelStore.selectedEngine(global);
        speechRadios.check(QwenModelStore.ONNX.equals(engine) ? R.id.radioQwenOnnx : QwenModelStore.GGUF.equals(engine) ? R.id.radioQwenGguf : R.id.radioWhisper);
        acceleration.setChecked(QwenModelStore.wantsAcceleration(global));
        if(mode == Translator.MOZILLA){  //eventual deactivation of switchMozillaForVoiceModes if Mozilla is the model selected
            switchMozillaForVoiceModes.setChecked(false);
            switchMozillaForVoiceModes.setEnabled(false);
        }else{
            switchMozillaForVoiceModes.setEnabled(true);
        }

        // ram consumption bar redline and orangeLine initialization
        barRam.setRedLine(100F - (float) (global.getRamThreshold() * 100) / global.getTotalRamSize());
        barRam.setOrangeLine(80F);

        // ram consumption bar initialization
        setRamUsageSystem(global.getTotalRamSize() - global.getMaxAllocatableRAM());
        updateRamUsageSpeechRecognition();
        updateRamUsageTranslation();
        updateRamUsageTranslationEnhancements();
        updateTextRamUsage();
    }

    private void restoreGuiDownloadState(ArrayList<DownloadGroupInfo> downloadStatus){
        // we change the GUI based on current download status
        if(downloadStatus != null){
            guiStateRestored = true;
            for(DownloadGroupInfo download: downloadStatus){
                for(ResourceManager manager: resourceManagers.values()) {
                    if (download.equals(manager.getDownloadInfo())) {
                        ResourceManagerView.State state = ResourceManagerView.State.EMPTY;
                        int index = DownloaderTools.findFirstIncompletedDownload(download);
                        //todo: improve detection methods of status
                        if(download.isAllDownloadCompleted()){
                            state = ResourceManagerView.State.DOWNLOADED;
                        } else if (download.getRunningDownloadIndex() == -1 && download.getCurrentProgress() <= 0) {
                            state = ResourceManagerView.State.EMPTY;
                        } else if (
                                (download.getRunningDownloadIndex() == -1 && download.getCurrentProgress() > 0) ||
                                (download.getRunningDownloadIndex() != -1 && download.getRunningDownload().getCurrentError() != -1)  //in case of an error the status will be PAUSED
                        ) {
                            state = ResourceManagerView.State.PAUSED;
                        } else if (download.getRunningDownloadIndex() != -1) {
                            state = ResourceManagerView.State.DOWNLOADING;
                        }
                        if(index < download.downloadsInfo.length) {
                            manager.setStatus(state, download.getCurrentProgress(), download.downloadsInfo[index].isUnzipping(), download.downloadsInfo[index].isTestingIntegrity());
                            manager.setError(download.downloadsInfo[index].getCurrentError());
                        }else{
                            manager.setStatus(state, download.getCurrentProgress(), false, false);
                        }
                    }
                }
            }
        }
    }

    public boolean checkSettingsChanged(){
        if (!selectedEngine().equals(QwenModelStore.selectedEngine(global)) || acceleration.isChecked() != QwenModelStore.wantsAcceleration(global)) return true;
        // check translation models
        int checkRadioId = radioGroup.getCheckedRadioButtonId();
        if(checkRadioId == R.id.radioMozilla && global.getTranslationMode() != Translator.MOZILLA) {
            return true;
        } else if(checkRadioId == R.id.radioHY && global.getTranslationMode() != Translator.HY_MT) {
            return true;
        } else if(checkRadioId == R.id.radioMadlad && global.getTranslationMode() != Translator.MADLAD_CACHE) {
            return true;
        }
        // check Mozilla for voice modes
        if(global.isUseMozillaForVoiceTranslation() != switchMozillaForVoiceModes.isChecked()){
            return true;
        }
        // check Whisper RAM reduction
        if(global.isWhisperReducedRam() != switchWhisperReducedRam.isChecked()){
            return true;
        }
        // check Tatoeba
        if(global.isUseTatoeba() != switchTatoeba.isChecked()){
            return true;
        }
        // check translation dictionaries
        if(global.isUseTranslationDictionaries() != switchTranslationDict.isChecked()){
            return true;
        }
        return false;
    }

    public void applySettings() {
        if (operations.busy() || closingModels) return;
        int mode = radioGroup.getCheckedRadioButtonId() == R.id.radioHY ? Translator.HY_MT
            : radioGroup.getCheckedRadioButtonId() == R.id.radioMadlad ? Translator.MADLAD_CACHE : Translator.MOZILLA;
        global.getSharedPreferences("default", Context.MODE_PRIVATE).edit()
            .putString(QwenModelStore.ENGINE_KEY, selectedEngine())
            .putString(QwenModelStore.ACCELERATION_KEY, acceleration.isChecked() ? "auto" : "cpu").apply();
        global.saveModelPreferences(mode, switchMozillaForVoiceModes.isChecked(), switchTatoeba.isChecked(),
            switchTranslationDict.isChecked(), switchWhisperReducedRam.isChecked());
        if (activity instanceof SettingsActivity) ((SettingsActivity) activity).startFragment(SettingsActivity.SETTINGS_FRAGMENT, null);
        else if (activity instanceof AccessActivity) startRTranslator();
    }








    private void updateRamUsageSpeechRecognition() {
        if (speechRadios != null && !QwenModelStore.WHISPER.equals(selectedEngine())) {
            setRamUsageSpeechRecognition(0); return; // No measured Qwen peak RSS: do not invent a RAM estimate.
        }
        if(switchWhisperReducedRam.isChecked()) {
            setRamUsageSpeechRecognition(WHISPER_RAM_CONSUMPTION_REDUCED_MB);
        }else{
            setRamUsageSpeechRecognition(WHISPER_RAM_CONSUMPTION_MB);
        }
    }

    private void updateRamUsageTranslation(){
        int checkRadioId = radioGroup.getCheckedRadioButtonId();
        if(checkRadioId == R.id.radioMozilla) {
            setRamUsageTranslation(MOZILLA_RAM_CONSUMPTION_MB);
        } else if(checkRadioId == R.id.radioHY) {
            int ramUsed = HY_RAM_CONSUMPTION_MB;
            if (switchMozillaForVoiceModes.isChecked()) {
                ramUsed += MOZILLA_RAM_CONSUMPTION_MB;
            }
            setRamUsageTranslation(ramUsed);
        } else if(checkRadioId == R.id.radioMadlad) {
            int ramUsed = MADLAD_RAM_CONSUMPTION_MB;
            if (switchMozillaForVoiceModes.isChecked()) {
                ramUsed += MOZILLA_RAM_CONSUMPTION_MB;
            }
            setRamUsageTranslation(ramUsed);
        }
    }

    private void updateRamUsageTranslationEnhancements(){
        int ramConsumptionTranslationEnhancements = 0;
        if(switchTatoeba.isChecked()){
            ramConsumptionTranslationEnhancements += TATOEBA_RAM_CONSUMPTION_MB;
        }
        if(switchTranslationDict.isChecked()){
            ramConsumptionTranslationEnhancements += DICT_RAM_CONSUMPTION_MB;
        }
        setRamUsageTranslationEnhancements(ramConsumptionTranslationEnhancements);
    }

    private void setRamUsageSystem(long ramUsedMb){
        long totalRam = global.getTotalRamSize();
        long percentageUsed = (ramUsedMb * 100L)/totalRam;
        if(percentageUsed <= 0 && ramUsedMb > 0) percentageUsed = 1;
        barRam.setSegmentProgress(0, percentageUsed);
    }

    private void setRamUsageSpeechRecognition(long ramUsedMb){
        long totalRam = global.getTotalRamSize();
        long percentageUsed = (ramUsedMb * 100L)/totalRam;
        if(percentageUsed <= 0 && ramUsedMb > 0) percentageUsed = 1;
        barRam.setSegmentProgress(1, percentageUsed);
    }

    private void setRamUsageTranslation(long ramUsedMb){
        long totalRam = global.getTotalRamSize();
        long percentageUsed = (ramUsedMb * 100L)/totalRam;
        if(percentageUsed <= 0 && ramUsedMb > 0) percentageUsed = 1;
        barRam.setSegmentProgress(2, percentageUsed);
    }

    private void setRamUsageTranslationEnhancements(long ramUsedMb){
        long totalRam = global.getTotalRamSize();
        long percentageUsed = (ramUsedMb * 100L)/totalRam;
        if(percentageUsed <= 0 && ramUsedMb > 0) percentageUsed = 1;
        barRam.setSegmentProgress(3, percentageUsed);
    }

    private void updateTextRamUsage() {
        int totalRamGB = Math.round(((float) global.getTotalRamSize()) / 1000);
        float usedRAMPercentage = 0;
        for(SegmentProgressBar.Segment segment : barRam.getSegments()){
            usedRAMPercentage += segment.progress;
        }
        float usedRamGB = (usedRAMPercentage * totalRamGB) / 100;
        DecimalFormat df = new DecimalFormat("0.#");
        textRamUsage.setText(df.format(usedRamGB));
        textRamUsage2.setText("/" + totalRamGB + " GB");
        if(barRam.getRedLineValue() >= 0 && usedRAMPercentage > barRam.getRedLineValue()){
            textRamUsage.setTextColor(getResources().getColor(R.color.red));
        }else if(barRam.getOrangeLineValue() >= 0 && usedRAMPercentage > barRam.getOrangeLineValue()){
            textRamUsage.setTextColor(getResources().getColor(R.color.orange));
        }else{
            textRamUsage.setTextColor(textRamUsage2.getTextColors().getDefaultColor());
        }
    }

    private String selectedEngine() {
        int id = speechRadios.getCheckedRadioButtonId();
        return id == R.id.radioQwenOnnx ? QwenModelStore.ONNX : id == R.id.radioQwenGguf ? QwenModelStore.GGUF : QwenModelStore.WHISPER;
    }
    private String selectedTranslationKind() {
        int id = radioGroup.getCheckedRadioButtonId();
        return id == R.id.radioHY ? "HY-MT" : id == R.id.radioMadlad ? "Madlad" : "Mozilla";
    }
    private void updateSpeechControls() {
        boolean whisper = QwenModelStore.WHISPER.equals(selectedEngine());
        boolean enabled = !closingModels && !operations.busy();
        switchWhisperReducedRam.setEnabled(enabled && whisper);
        acceleration.setEnabled(enabled && !whisper);
        getView().findViewById(R.id.retryQwenAcceleration).setEnabled(enabled && !whisper);
        getView().findViewById(R.id.qwenRamNote).setVisibility(whisper ? View.GONE : View.VISIBLE);
        barRam.setVisibility(whisper ? View.VISIBLE : View.GONE);
        textRamUsage.setVisibility(whisper ? View.VISIBLE : View.GONE);
        textRamUsage2.setVisibility(whisper ? View.VISIBLE : View.GONE);
        updateRamUsageSpeechRecognition();
    }
    private void renderLibrary() {
        if (getView() == null) return;
        boolean enabled = !closingModels && !operations.busy();

        if (!operations.busy()) {
            restoreGuiDownloadState(downloadManager.getSavedDownloadStatus());
            qwenOnnx.setState(FeatureReadiness.speech(global, QwenModelStore.ONNX) ? ResourceManagerView.State.DOWNLOADED : ResourceManagerView.State.EMPTY, false);
            qwenGguf.setState(FeatureReadiness.speech(global, QwenModelStore.GGUF) ? ResourceManagerView.State.DOWNLOADED : ResourceManagerView.State.EMPTY, false);
            syncResource("whisperManager", FeatureReadiness.speech(global, QwenModelStore.WHISPER));
            syncResource("hyManager", FeatureReadiness.translation(global, Translator.HY_MT));
            syncResource("madladManager", FeatureReadiness.translation(global, Translator.MADLAD_CACHE));
            syncResource("tatoebaManager", FeatureReadiness.tatoeba(global));
            syncResource("dictionaryManager", FeatureReadiness.dictionaries(global));
        }
        TextView summary = getView().findViewById(R.id.modelLibraryStatus);
        summary.setText(closingModels ? getString(R.string.model_closing) : getString(R.string.model_library_intro));
        enableTree(getView(), enabled);
        getView().findViewById(R.id.cancelModelOperation).setEnabled(operations.busy());
        switchMozillaForVoiceModes.setEnabled(enabled && radioGroup.getCheckedRadioButtonId() != R.id.radioMozilla);
        updateSpeechControls();
    }
    private void syncResource(String key, boolean present) {
        ResourceManager manager = resourceManagers.get(key);
        if (manager == null) return;
        if (manager.getState() == ResourceManagerView.State.DOWNLOADING || manager.getState() == ResourceManagerView.State.PAUSED) return;
        manager.setState(present ? ResourceManagerView.State.DOWNLOADED : ResourceManagerView.State.EMPTY, false);
    }
    private static void enableTree(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup)view).getChildCount(); i++) enableTree(((ViewGroup)view).getChildAt(i), enabled);
    }
    private void bindQwen(ResourceManagerView view, String engine) {
        view.setListener(new ResourceManagerView.Listener() {
            public void onDownloadClicked() { confirmDownload(engine); }
            public void onResumeClicked() { confirmDownload(engine); }
            public void onPauseClicked() { operations.cancel(); }
            public void onDeletePressed() {
                new MaterialAlertDialogBuilder(activity, R.style.MyThemeOverlay_MaterialComponents_MaterialAlertDialog)
                    .setMessage(R.string.model_delete_qwen_confirmation)
                    .setPositiveButton(android.R.string.ok, (d,w) -> operations.remove(engine))
                    .setNegativeButton(android.R.string.cancel, null).show();
            }
            public void onErrorPressed() { renderLibrary(); }
        });
    }
    private void confirmDownload(String engine) {
        new MaterialAlertDialogBuilder(activity, R.style.MyThemeOverlay_MaterialComponents_MaterialAlertDialog)
            .setTitle(QwenModelStore.ONNX.equals(engine) ? R.string.qwen_onnx : R.string.qwen_gguf)
            .setMessage(R.string.model_download_confirmation)
            .setPositiveButton(R.string.model_download_action, (d,w) -> operations.download(engine))
            .setNegativeButton(android.R.string.cancel, null).show();
    }
    private void chooseFolder(String kind) {
        if (operations.busy() || closingModels) return;
        pendingImport=kind;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), IMPORT_MODEL);
    }
    private void chooseDocument(String kind) {
        if (operations.busy() || closingModels) return;
        pendingImport=kind;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), IMPORT_MODEL);
    }
    @Override public void onActivityResult(int request, int result, @Nullable Intent data) {
        super.onActivityResult(request, result, data);
        if (request == IMPORT_MODEL && result == Activity.RESULT_OK && data != null && data.getData() != null && pendingImport != null) {
            operations.importUri(data.getData(), pendingImport); pendingImport=null;
        }
    }
    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        state.putString("pendingModelImport", pendingImport);
        saveSelection(state);
        super.onSaveInstanceState(state);
    }
    private void saveSelection(Bundle state) {
        if (radioGroup == null) return;
        state.putInt("draftTranslation", radioGroup.getCheckedRadioButtonId());
        state.putInt("draftSpeech", speechRadios.getCheckedRadioButtonId());
        state.putBoolean("draftMozilla", switchMozillaForVoiceModes.isChecked());
        state.putBoolean("draftReducedRam", switchWhisperReducedRam.isChecked());
        state.putBoolean("draftTatoeba", switchTatoeba.isChecked());
        state.putBoolean("draftDictionary", switchTranslationDict.isChecked());
        state.putBoolean("draftAcceleration", acceleration.isChecked());
    }
    public boolean isClosingModels() { return closingModels; }
    public boolean hasActiveOperation() { return operations != null && operations.busy(); }
    public void cancelOperation() { if (operations != null) operations.cancel(); }

    private void startRTranslator(){
        if (activity != null) {
            //modification of the firstStart
            global.setFirstStart(false);
            //start activity
            Intent intent = new Intent(activity, LoadingActivity.class);
            intent.putExtra("activity", "download");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            activity.finish();
        }
    }
}
