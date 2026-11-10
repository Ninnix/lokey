# Building Lokey

Commands and repository paths below are relative to the repository root.

## Get the source

From a parent directory, keep the app and its pinned runtime forks beside
each other:

```sh
git clone https://github.com/Ninnix/lokey.git
git clone --filter=blob:none https://github.com/Loina-AI/loina-core.git
git -C loina-core checkout f11990801c78f6736ed9f8d672a8a7af7e4fc6c9
git clone --filter=blob:none https://github.com/Loina-AI/llama.cpp.git
git -C llama.cpp checkout fc75d063f0ac136f443eebe882c1a971a286673a
git clone --filter=blob:none https://github.com/Loina-AI/whisper.cpp.git
git -C whisper.cpp checkout 66c475eab12593a40770c06dccb50c7817ce0a6f
cd lokey
```

Use the revisions in `runtimes.json` when updating this checkout. The remaining
commands run from the app repository root.

## Build

Use JDK 17+, Android SDK 37, NDK 28.2.13676358, and CMake 3.31.6. Gradle downloads the Android build tooling and Kotlin standard library. Runtime model files are optional and are not bundled into the APK. Application native sources are in `app/src/main/c/lokey.c`; the external inference libraries retain their own implementation languages and are accessed through C APIs.

Python 3 and curl prepare pinned public swipe dictionaries during `preBuild`.
Sources and checksums are in `app/src/main/assets/swipe-words/sources.json`;
the download cache is `.local/swipe-words`. No extra inference runtime is used.
The larger dictionaries are swipe-only; prediction/correction vocabularies and
text/dictation model downloads are unchanged.

Inference dependencies:

- [loina-core](https://github.com/Loina-AI/loina-core)
- [llama.cpp](https://github.com/Loina-AI/llama.cpp)
- [whisper.cpp](https://github.com/Loina-AI/whisper.cpp)

Runtime revisions are pinned in [runtimes.json](../runtimes.json).

```
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

For another runtime location, add `-PlokeyRuntimeRoot=/path/to/parent`. For a separately installed NDK, add `-PlokeyNdkPath=/path/to/ndk`.

For a Hexagon-enabled APK, install Qualcomm Hexagon SDK 6.7.0.0 (tools 21.0.04) and add `-PlokeyHexagonSdk=/path/to/6.7.0.0`. The build compiles and packages DSP kernels for v73, v75, v79, v81, and v85. An optional `-PlokeyHexagonLibs=/path/to/prebuilt/kernels` reuses kernels built from the same pinned llama.cpp revision. CPU-only builds do not need the SDK. The OnePlus test APK packages v81.

In Hexagon-enabled builds, LFM2.5 and Qwen3.5 text prediction use Hexagon when available, in balanced power mode. Qwen ASR runs both its audio encoder and decoder on Hexagon; Parakeet explicitly selects Hexagon for supported operations, with CPU scheduling for the rest. Model or context initialization failure retries on CPU. CPU selection excludes accelerator backends. Translation remains on CPU. `Native.textBackend()` and `Native.speechBackend()` report the active paths. See `artifacts/oneplus15-npu-support-summary.json` for measured performance and parity limits; NPU floating-point results need not be bit-identical to CPU.

Install `app/build/outputs/apk/debug/app-debug.apk`. Open Lokey, enable it in Android settings, then choose it as your keyboard. Allow microphone access from Models. The Try it tab contains an editor.

## Release builds

See [RELEASING.md](RELEASING.md) for local upload-key setup and signed APK/AAB builds. Release builds require signing credentials; debug builds do not. The privacy policy asset is generated from `docs/PRIVACY.md` during every build.
