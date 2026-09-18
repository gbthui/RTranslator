#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
output="build/qwen-core-tests"
mkdir -p "$output"
package=app/src/main/java/nie/translator/rtranslator/voice_translation/neural_networks/voice/qwen
javac --release 8 -encoding UTF-8 -d "$output" \
  "$package"/{CancellationToken,SerialRecognitionQueue,QwenLanguages,QwenAudioChunks,QwenBackend,QwenNative,NativeQwenBackend}.java \
  tools/tests/QwenCoreTest.java
java -ea -cp "$output" nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen.QwenCoreTest
