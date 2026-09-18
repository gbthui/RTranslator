#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash tools/test-qwen-core.sh
javac --release 8 -encoding UTF-8 -cp build/qwen-core-tests -d build/qwen-core-tests tools/tests/QwenNativeSmoke.java
runtime="$(realpath "${QWEN_HOST_RUNTIME:-.gradle/qwen-host}/jni/host")"
export LD_LIBRARY_PATH="$runtime:${LD_LIBRARY_PATH:-}"
java -Xcheck:jni -ea -Djava.library.path="$runtime" -cp build/qwen-core-tests \
  nie.translator.rtranslator.voice_translation.neural_networks.voice.qwen.QwenNativeSmoke "$@"
