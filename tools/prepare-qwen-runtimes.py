#!/usr/bin/env python3
"""Build pinned Qwen3-ASR JNI backends, without downloading model weights.

Android: Python 3, Git, CMake, Ninja, NDK r28c, glslc, libvulkan-dev,
spirv-headers. Host builds only CPU libraries for Linux/JVM smoke tests.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
NATIVE = ROOT / 'app/src/main/cpp/qwen'
SHERPA = '13d0ae6c539d2809d32f5eaa3ef1db0c459d0b24'
TRANSCRIBE = '63a44d9239d610b3908e8a66b384924cd4a77217'
ORT_VERSION = '1.24.3'
API = 28
RUNTIMES = ('onnx', 'gguf_cpu', 'gguf_vulkan')


def run(*args, **kwargs):
    subprocess.run([str(a) for a in args], check=True, **kwargs)


def digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as source:
        for data in iter(lambda: source.read(1024 * 1024), b''):
            h.update(data)
    return h.hexdigest()


def recipe():
    paths = [Path(__file__), ROOT / 'tools/patch-qwen-sherpa.py'] + sorted(p for p in NATIVE.rglob('*') if p.is_file())
    return hashlib.sha256('\n'.join(f'{p.relative_to(ROOT)}:{digest(p)}' for p in paths).encode()).hexdigest()


def verify(output, host=False):
    try:
        data = json.loads((output / 'runtime.json').read_text())
        expected = ('onnx', 'gguf_cpu') if host else RUNTIMES
        abi = 'host' if host else 'arm64-v8a'
        required = {f'jni/{abi}/librtranslator_qwen_{name}.so' for name in expected}
        if data['recipe'] != recipe() or data['sherpa'] != SHERPA or data['transcribe'] != TRANSCRIBE or data['ort'] != ORT_VERSION:
            return False
        if data['host'] != host or not required <= data['files'].keys():
            return False
        return all((output / p).is_file() and digest(output / p) == h for p, h in data['files'].items())
    except (OSError, KeyError, ValueError, TypeError):
        return False


def request(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'rtranslator-qwen-build'}), timeout=180)


def asset(repository, tag, name, destination):
    with request(f'https://api.github.com/repos/{repository}/releases/tags/{tag}') as response:
        entry = next(a for a in json.load(response)['assets'] if a['name'] == name)
    expected = entry.get('digest', '')
    if not expected.startswith('sha256:'):
        raise RuntimeError(f'Release asset has no SHA-256 digest: {name}')
    with request(entry['browser_download_url']) as response, destination.open('wb') as out:
        shutil.copyfileobj(response, out)
    if digest(destination) != expected[7:]:
        raise RuntimeError(f'Asset checksum mismatch: {name}')
    return expected


def checkout(work, repository, commit):
    path = work / repository.rsplit('/', 1)[-1]
    run('git', 'init', path)
    run('git', '-C', path, 'remote', 'add', 'origin', f'https://github.com/{repository}.git')
    run('git', '-C', path, 'fetch', '--depth=1', 'origin', commit)
    run('git', '-C', path, 'checkout', '--detach', commit)
    if subprocess.check_output(['git', '-C', str(path), 'rev-parse', 'HEAD'], text=True).strip() != commit:
        raise RuntimeError('Source revision mismatch')
    return path


def extract_zip(path, output):
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            dest = (output / entry.filename).resolve()
            if not dest.is_relative_to(output.resolve()) or ((entry.external_attr >> 16) & 0o170000) == 0o120000:
                raise RuntimeError('Unsafe runtime archive')
        archive.extractall(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path)
    parser.add_argument('--host', action='store_true')
    parser.add_argument('--verify-only', action='store_true')
    parser.add_argument('--output', type=Path, default=ROOT / '.gradle/qwen-runtimes')
    args = parser.parse_args()
    output = args.output.resolve()
    if verify(output, args.host):
        print('Verified Qwen runtimes:', output)
        return
    if args.verify_only:
        raise RuntimeError('Qwen runtimes missing or stale; run tools/prepare-qwen-runtimes.py --ndk <NDK r28c>')
    for tool in ('git', 'cmake', 'ninja'):
        if not shutil.which(tool):
            parser.error(f'{tool} is required')
    ndk = args.ndk.resolve() if args.ndk else None
    if not args.host and (not ndk or not (ndk / 'build/cmake/android.toolchain.cmake').is_file()):
        parser.error('--ndk must point to Android NDK r28c')
    with tempfile.TemporaryDirectory(prefix='rtranslator-qwen-') as temporary:
        work = Path(temporary)
        sherpa = checkout(work, 'k2-fsa/sherpa-onnx', SHERPA)
        transcribe = checkout(work, 'handy-computer/transcribe.cpp', TRANSCRIBE)
        run('python3', ROOT / 'tools/patch-qwen-sherpa.py', sherpa, NATIVE)
        ort = work / 'ort'
        if args.host:
            archive = work / 'ort.tgz'
            ort_digest = asset('microsoft/onnxruntime', f'v{ORT_VERSION}', f'onnxruntime-linux-x64-{ORT_VERSION}.tgz', archive)
            with tarfile.open(archive) as data:
                data.extractall(ort, filter='data')
            package = next(ort.iterdir())
            headers, libraries = package / 'include', package / 'lib'
        else:
            archive = work / 'ort.zip'
            ort_digest = asset('csukuangfj/onnxruntime-libs', f'v{ORT_VERSION}', f'onnxruntime-android-{ORT_VERSION}.zip', archive)
            extract_zip(archive, ort)
            headers, libraries = ort / 'headers', ort / 'jni/arm64-v8a'
        if not (headers / 'onnxruntime_cxx_api.h').is_file() or not (libraries / 'libonnxruntime.so').is_file():
            raise RuntimeError('ORT headers or shared library missing')
        environment = dict(os.environ, SHERPA_ONNXRUNTIME_INCLUDE_DIR=str(headers), SHERPA_ONNXRUNTIME_LIB_DIR=str(libraries))
        stage = work / 'stage'
        abi = 'host' if args.host else 'arm64-v8a'
        target = stage / 'jni' / abi
        target.mkdir(parents=True)
        if args.host:
            readelf = shutil.which('readelf')
            strip = shutil.which('strip')
            # Keep a matching ORT beside the host JNI libraries for smoke tests.
            for file in libraries.glob('libonnxruntime.so*'):
                shutil.copy2(file, target / file.name, follow_symlinks=True)
        else:
            readelf = next((ndk / 'toolchains/llvm/prebuilt').glob('*/bin/llvm-readelf'))
            strip = readelf.with_name('llvm-strip')
        for runtime in (('onnx', 'gguf_cpu') if args.host else RUNTIMES):
            build = work / f'build-{runtime}'
            flags = [f'-DQWEN_RUNTIME={"onnx" if runtime == "onnx" else "gguf"}',
                     f'-DRUNTIME_SOURCE={sherpa if runtime == "onnx" else transcribe}',
                     f'-DQWEN_GGUF_VULKAN={"ON" if runtime == "gguf_vulkan" else "OFF"}', '-DCMAKE_BUILD_TYPE=Release']
            if not args.host:
                flags += [f'-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake',
                          '-DANDROID_ABI=arm64-v8a', f'-DANDROID_PLATFORM=android-{API}', '-DANDROID_STL=c++_static']
            if runtime == 'gguf_vulkan':
                glslc = shutil.which('glslc')
                if not glslc:
                    raise RuntimeError('Install glslc, libvulkan-dev and spirv-headers')
                includes = work / 'vulkan-headers'
                for name in ('vulkan', 'vk_video', 'spirv'):
                    shutil.copytree(Path('/usr/include') / name, includes / 'include' / name, dirs_exist_ok=True)
                shutil.copytree('/usr/share/cmake/SPIRV-Headers', includes / 'share/cmake/SPIRV-Headers', dirs_exist_ok=True)
                stub = readelf.parent.parent / f'sysroot/usr/lib/aarch64-linux-android/{API}/libvulkan.so'
                flags += [f'-DVulkan_INCLUDE_DIR={includes}/include', f'-DVulkan_LIBRARY={stub}',
                          f'-DVulkan_GLSLC_EXECUTABLE={glslc}', f'-DSPIRV-Headers_DIR={includes}/share/cmake/SPIRV-Headers']
            run('cmake', '-S', NATIVE, '-B', build, '-G', 'Ninja', *flags, env=environment)
            run('cmake', '--build', build, '--target', f'rtranslator_qwen_{runtime}', '--parallel', str(min(os.cpu_count() or 2, 4)))
            binary = build / 'out' / f'librtranslator_qwen_{runtime}.so'
            run(strip, '--strip-unneeded', binary)
            dynamic = subprocess.check_output([str(readelf), '-dW', str(binary)], text=True)
            if any(name in dynamic for name in ('libggml', 'libtranscribe', 'libsherpa-onnx', 'libc++_shared')):
                raise RuntimeError(f'Unisolated native dependency: {runtime}')
            if runtime != 'gguf_vulkan' and 'libvulkan' in dynamic:
                raise RuntimeError('CPU runtime depends on Vulkan')
            segments = subprocess.check_output([str(readelf), '-lW', str(binary)], text=True)
            if any(int(line.split()[-1], 16) < 16384 for line in segments.splitlines() if line.strip().startswith('LOAD ')):
                raise RuntimeError('Runtime is not compatible with 16 KiB pages')
            shutil.copy2(binary, target / binary.name)
            shutil.rmtree(build)
        assets = stage / 'assets'
        assets.mkdir()
        (assets / 'qwen-runtime.properties').write_text(f'sherpa={SHERPA}\ntranscribe={TRANSCRIBE}\nonnxruntime={ORT_VERSION}\nabi={abi}\n', encoding='utf-8')
        for name, source in (('sherpa', sherpa), ('transcribe', transcribe)):
            for license_file in source.rglob('LICENSE*'):
                if license_file.is_file() and '.git' not in license_file.parts:
                    dest = assets / 'licenses/qwen' / name / license_file.relative_to(source)
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(license_file, dest)
        manifest = {'recipe': recipe(), 'sherpa': SHERPA, 'transcribe': TRANSCRIBE,
                    'ort': ORT_VERSION, 'ort_asset_sha256': ort_digest, 'host': args.host,
                    'files': {str(file.relative_to(stage)): digest(file) for file in stage.rglob('*') if file.is_file()}}
        output.mkdir(parents=True, exist_ok=True)
        (output / 'runtime.json').unlink(missing_ok=True)
        shutil.copytree(stage, output, dirs_exist_ok=True)
        (output / 'runtime.json').write_text(json.dumps(manifest, indent=2) + '\n')
        if not verify(output, args.host):
            raise RuntimeError('Runtime verification failed')
    print('Prepared Qwen runtimes:', output)


if __name__ == '__main__':
    main()
