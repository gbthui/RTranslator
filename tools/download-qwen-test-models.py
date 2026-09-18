#!/usr/bin/env python3
"""Download public test weights only for CI, verify publisher hashes and record provenance."""
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tarfile

spec = importlib.util.spec_from_file_location('runtimes', Path(__file__).with_name('prepare-qwen-runtimes.py'))
runtimes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtimes)


def main():
    destination = Path(sys.argv[1]).resolve()
    destination.mkdir(parents=True, exist_ok=True)
    repo = 'handy-computer/Qwen3-ASR-0.6B-gguf'
    name = 'Qwen3-ASR-0.6B-Q4_K_M.gguf'
    with runtimes.request(f'https://huggingface.co/api/models/{repo}?blobs=true') as response:
        info = json.load(response)
    entry = next(x for x in info['siblings'] if x['rfilename'] == name)
    sha = entry['lfs']['sha256']
    with runtimes.request(f'https://huggingface.co/{repo}/resolve/{info["sha"]}/{name}') as response, (destination / name).open('wb') as output:
        shutil.copyfileobj(response, output)
    if runtimes.digest(destination / name) != sha:
        raise RuntimeError('GGUF model checksum mismatch')
    archive = destination / 'onnx.tar.bz2'
    onnx_name = 'sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25'
    digest = runtimes.asset('k2-fsa/sherpa-onnx', 'asr-models', onnx_name + '.tar.bz2', archive)
    with tarfile.open(archive) as data:
        data.extractall(destination, filter='data')
    archive.unlink()
    fixtures = {}
    for name in ('jfk.wav', 'zh.wav'):
        url = f'https://raw.githubusercontent.com/handy-computer/transcribe.cpp/{runtimes.TRANSCRIBE}/samples/{name}'
        with runtimes.request(url) as response:
            data = response.read(4 * 1024 * 1024)
        (destination / name).write_bytes(data)
        fixtures[name] = {'source': url, 'sha256': hashlib.sha256(data).hexdigest()}
    (destination / 'provenance.json').write_text(json.dumps({'gguf': {'repository': repo,
        'revision': info['sha'], 'filename': entry['rfilename'], 'sha256': sha},
        'onnx': {'package': onnx_name, 'sha256': digest}, 'fixtures': fixtures}, indent=2) + '\n')


if __name__ == '__main__':
    main()
