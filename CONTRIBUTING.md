# Contributing

Lokey changes should be checked against the behavior they can affect. A fix
for a keyboard gesture needs a real input check. A change to model scoring,
prompt rendering or cached state needs an inference correctness check. Include
the commands you ran, the phone, backend, model and any failures in the pull
request.

Read [AGENTS.md](AGENTS.md) before changing the code. Application code is Kotlin
or C11. Call the external inference libraries through their C APIs; changes to
those libraries belong in their own repositories. Keep new dependencies to the
minimum required for the change.

## Build and correctness

Start with the debug app and instrumentation runner:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

The [build guide](docs/BUILD.md) covers the SDK, NDK, runtime checkouts and
optional Hexagon backend. Release signing credentials are not needed for a
debug build.

Use an explicit device serial. Install the debug APK on a test device without
clearing its data, then install and run the relevant checks:

```sh
python3 tools/test-device.py --serial SERIAL --label my-change --install-tests \
  interaction customizationui modelui
```

Useful groups:

* `interaction`, `imeflow`: touch behavior and real editor commits.
* `customization`, `customizationui`, `themes`: saved colors, designs, layouts
  and the app controls that edit them.
* `probability`, `probabilitylanguages`: complete-word ranking, prefix reuse,
  token-matched checkpoints and multilingual fixtures.
* `correction`: accepted corrections, protected words and immediate undo.
* `translationmodels`: single-model availability, LFM translation and cache invalidation on model switches.
* `translation`, `translationui`: model output, cancellation and guarded
  insertion into the receiving app.
* `setup`, `automatic`, `modelui`: setup, model selection and model screens.
* `localization`, `privacy`: locale resources, user-text preservation and the
  bundled privacy policy.
* `typingfeatures`: model-free personal learning, encrypted storage and erasure,
  sensitive-field exclusions, one-tap recording controls and pause/resume.
* `typingfeatureslive`, `learningneural`, `speechoverlay`: real editor and
  microphone checks, personal neural candidates and draggable overlay controls
  in the isolated application described in [the learning note](research/PRIVATE-LEARNING.md).

The runner saves JSON reports in `artifacts/` and serializes inference runs on
the same phone. It restores the original keyboard layout. Keep test input
synthetic; do not run injection checks in a field containing personal text.
A passing short fixture is not evidence of equal quality in every language.

For locale changes, also run:

```sh
python3 tools/check-locales.py
python3 tools/test-app-locales.py --serial SERIAL
```

Use the same resource keys across locales and preserve formatting arguments.
Do not translate clipboard entries, custom names, typed text, model identifiers
or license bodies. Native-speaker corrections are welcome; machine-generated
translations are not a substitute for that review.

## Speed comparisons

Use the same phone, runtime revision, model file, backend, thread count,
prompt set and thermal state for a before/after comparison. Keep other
inference jobs off that phone. Separate model loading from warm latency and
report both useful responsiveness and correctness.

```sh
python3 tools/test-device.py --serial SERIAL --label before --accelerator cpu models
python3 tools/test-device.py --serial SERIAL --label after --accelerator cpu models
```

For Hexagon work, repeat the relevant checks with `--accelerator hexagon` on a
supported device and retain the CPU comparison. Do not report a CPU fallback
as accelerator performance. Explain any quality or latency tradeoff.

## Reporting a problem

Include the phone and Android version, app/runtime revision, selected language
and layout, model, active backend, steps to reproduce and expected behavior.
For editor bugs, include whether there was a selection, composing text or a
cursor move. For model bugs, use a short synthetic prompt that reproduces it.

Do not attach real messages, microphone recordings, clipboard dumps, keystores
or signing passwords. Review logs and screenshots before sharing them. Local
experiments and private signing material belong in the ignored `.local/`
directory. Keep the privacy policy and Data Safety answers aligned with any
change that adds storage, a permission or a network request.
