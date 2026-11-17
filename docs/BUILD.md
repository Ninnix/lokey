# Building Lokey

Commands and repository paths below are relative to the repository root.

## Get the source

From a parent directory, keep the app and its pinned runtime forks beside
each other:

```sh
git clone https://github.com/Ninnix/lokey.git
git clone --filter=blob:none https://github.com/Loina-AI/loina-core.git
git -C loina-core checkout b1c33435ccf9c04eca35a16d6c1920e398d8bf5d
git clone --filter=blob:none https://github.com/Loina-AI/llama.cpp.git
git -C llama.cpp checkout a59247d746f5519a73e9d7c9de81c9c7aba488f4
git clone --filter=blob:none https://github.com/Loina-AI/whisper.cpp.git
git -C whisper.cpp checkout 42e358ab1258f46530b5b8494a930a9927bf9ed2
cd lokey
```

Use the revisions in `runtimes.json` when updating this checkout. The remaining
commands run from the app repository root.

## Build

Use JDK 17+, Android SDK 37, NDK 28.2.13676358, and CMake 3.31.6. Gradle downloads the Android build tooling and Kotlin standard library. Runtime model files are optional and are not bundled into the APK. Application native sources are in `app/src/main/c/lokey.c`; the external inference libraries retain their own implementation languages and are accessed through C APIs.

Python 3 and curl prepare pinned public dictionaries and small word-context
tables during `preBuild`. Sources and checksums are in
`app/src/main/assets/swipe-words/sources.json` and `context/sources.json`; caches
are `.local/swipe-words` and `.local/context`. No extra inference runtime is used.
The larger dictionaries support swipe and typing without a downloaded text model.
The context tables are pruned public bigram/trigram counts, bundled in the APK;
they do not download or learn from user typing at runtime. Text/dictation neural
model downloads are unchanged.

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
