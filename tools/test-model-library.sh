#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
output=build/model-library-tests
mkdir -p "$output"
base=app/src/main/java/nie/translator/rtranslator
javac --release 8 -encoding UTF-8 -d "$output" \
  "$base/models/ModelFiles.java" \
  "$base/voice_translation/neural_networks/voice/CaptureWindow.java" \
  "$base/voice_translation/neural_networks/voice/CaptureRouting.java" \
  tools/tests/ModelLibraryTest.java
java -ea -cp "$output" ModelLibraryTest
python3 tools/tests/check-model-ui.py
