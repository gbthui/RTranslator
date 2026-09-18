"""Static integration contracts. Does not replace instrumented UI or real-device tests."""
from pathlib import Path
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[2]
j = root / 'app/src/main/java/nie/translator/rtranslator'
for relative in ['LoadingActivity.java', 'GeneralActivity.java']:
    text = (j / relative).read_text()
    assert 'AccessActivity.class' not in text and 'startAllDownloads(' not in text, relative
launcher = (j / 'LoadingActivity.java').read_text()
assert 'VoiceTranslationActivity.class' in launcher
prefs = (root/'app/src/main/res/xml/preferences.xml').read_text()
assert 'qwenAsrSettings' not in prefs and 'model' in prefs.lower()
manager = ET.parse(root/'app/src/main/res/layout/fragment_models_manager.xml')
ns = '{http://schemas.android.com/apk/res/android}'
nodes = {node.get(ns+'id'):node.tag for node in manager.iter()}
for key in ['modelQwenOnnx','modelQwenGguf','modelWhisper','resourceDictionaries']:
    assert nodes['@+id/'+key].endswith('ResourceManagerView'), key
for key in ['importSpeechModel','importTranslationModel','importOptionalResource']:
    assert '@+id/'+key in nodes, key
for path in (root/'app/src/main/res').glob('*/model_library.xml'): ET.parse(path)
ET.parse(root/'app/src/main/res/layout/fragment_translation.xml')
recorder = (j/'voice_translation/neural_networks/voice/Recorder.java').read_text()
assert 'emitVoice(false)' in recorder
branch = recorder[recorder.index('private void emitVoice('):recorder.index('private void executeDismiss(')]
assert branch.index('if (endOfSpeech)') < branch.index('mCallback.onVoiceEnd()')
service = (j/'voice_translation/_walkie_talkie_mode/_walkie_talkie/WalkieTalkieService.java').read_text()
assert 'captureRouting.current()' in service
print('PASS launch, project-style model controls, imports and non-terminal recording-window integration')

voice_service = (j/'voice_translation/VoiceTranslationService.java').read_text()
assert 'if (serviceDestroyed || !((Global) getApplication()).models().ready(true)) return;' in voice_service
assert 'if (!serviceDestroyed && ((Global) getApplication()).models().ready(true) && shouldDeactivateMicDuringTTS())' in voice_service
destroy = voice_service[voice_service.index('public synchronized void onDestroy()'):voice_service.index('// communication')]
assert destroy.index('serviceDestroyed = true') < destroy.index('ttsEngine.stop()')
assert 'mainHandler.removeCallbacksAndMessages(null)' in destroy
print('PASS stopped or unconfigured voice service cannot be restarted by delayed TTS callbacks')
