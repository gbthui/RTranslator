#!/usr/bin/env python3
"""Apply narrowly-scoped cancellation/LID patches to pinned sherpa-onnx sources."""
from pathlib import Path
import shutil
import sys


def replace(text, old, new, count=1):
    actual = text.count(old)
    if actual != count:
        raise RuntimeError(f"Pinned sherpa source changed: {old[:80]!r}: expected {count}, got {actual}")
    return text.replace(old, new)


def patch(source: Path, native: Path):
    target = source / 'sherpa-onnx/csrc'
    shutil.copy2(native / 'qwen_cancel.h', target / 'rtranslator-qwen-cancel.h')
    path = target / 'offline-qwen3-asr-model.cc'
    text = path.read_text()
    text = '#include "sherpa-onnx/csrc/rtranslator-qwen-cancel.h"\n' + text
    text = replace(text, 'Ort::RunOptions{nullptr}', 'rtranslator_qwen::RunOptions()', 3)
    for before in ('{}, conv_input_names_ptr_', '{}, encoder_input_names_ptr_', '{}, input_names_ptr.'):
        text = replace(text, before, before.replace('{}', 'rtranslator_qwen::RunOptions()'))
    path.write_text(text)

    path = target / 'offline-recognizer-qwen3-asr-impl.cc'
    text = '#include "sherpa-onnx/csrc/rtranslator-qwen-cancel.h"\n' + path.read_text()
    text = replace(text, 'void OfflineRecognizerQwen3ASRImpl::Decode(OfflineStream *stream) const {',
        'void OfflineRecognizerQwen3ASRImpl::Decode(OfflineStream *stream) const {\n  rtranslator_qwen::CheckCancelled();')
    text = replace(text, '  for (int32_t step = 1; step < max_new_tokens; ++step) {',
        '  bool reached_eos = false;\n  for (int32_t step = 1; step < max_new_tokens; ++step) {\n    rtranslator_qwen::CheckCancelled();')
    text = replace(text, '    if (next_id == eos_id) {\n      break;\n    }',
        '    if (next_id == eos_id) {\n      reached_eos = true;\n      break;\n    }')
    text = replace(text, '  std::vector<int64_t> cleaned_ids = generated_ids;',
        '  rtranslator_qwen::CheckCancelled();\n  if (!reached_eos) throw std::length_error("Qwen transcript truncated");\n  std::vector<int64_t> cleaned_ids = generated_ids;')
    text = replace(text, '        cleaned_ids.assign(std::next(asr_text_it), generated_ids.end());',
        '        stream->SetOption("rtranslator_language", prefix_text.substr(9, prefix_text.size() - 19));\n        cleaned_ids.assign(std::next(asr_text_it), generated_ids.end());')
    # Do not silently omit audio when the model context limit is exceeded.
    begin = text.index('  if (context_len > max_seq_len) {')
    end = text.index('\n  std::vector<int64_t> input_ids = source_ids;', begin)
    text = text[:begin] + '  if (context_len > max_seq_len) throw std::length_error("Qwen audio exceeds context limit");\n' + text[end:]
    path.write_text(text)

    # The embedded library must report a bad user-imported model, not kill Android.
    path = target / 'macros.h'
    text = replace(path.read_text(), '#include <utility>', '#include <utility>\n#include <stdexcept>')
    text = replace(text, '_Exit(code);', 'throw std::runtime_error("Invalid sherpa model or tensor metadata");')
    path.write_text(text)
    path = target / 'session.cc'
    text = replace(path.read_text(), '// nnapi_flags |= NNAPI_FLAG_CPU_DISABLED;',
                   'nnapi_flags |= NNAPI_FLAG_CPU_DISABLED;')
    path.write_text(text)


if __name__ == '__main__':
    patch(Path(sys.argv[1]), Path(sys.argv[2]))
