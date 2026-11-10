# Testing Lokey

Run commands from the repository root. Use a dedicated test editor and synthetic
text; keyboard injection tests must not target a private conversation.

## Build and device runner

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
python3 tools/test-device.py --serial SERIAL --label check --install-tests \
  interaction customizationui modelui
```

Install the app APK separately before running the tests. The runner installs
only its instrumentation APK when `--install-tests` is given; it does not
replace models or clear app storage. Inference checks are serialized per phone.
The original keyboard layout is restored after the run.

See [CONTRIBUTING.md](../CONTRIBUTING.md) for choosing checks by failure mode.
The `adaptive` mode checks bounded visible resizing, stationary letter anchors,
frozen geometry during touches, private-field gates, absence of learning/storage,
and all built-in layouts at two widths. It can also replay synthetic taps with
saved research model probabilities; these are not human typing results.
The `swipe` mode compares the original and expanded dictionaries and validates
the endpoint index on recorded QWERTY traces and synthetic paths on other layouts.
The `labels` mode records actual text drawing bounds across built-in layouts
and their custom clones, portrait/landscape, keyboard heights, Shift, both symbol pages,
numeric input, suggestions, and adaptive keys; it checks for overlap/overflow.
The `local` mode evaluates prediction, correction, dictionary coverage and
public-corpus next-word cases with isolated empty model storage. Its public
fixture is prepared/staged by `research/tools/research-local-fixtures.py` and
`research-local-phone.py`; full results are pulled with the latter
`--results` option to avoid Android's instrumentation message limit. Installed
model files/preferences remain in place.
`adaptiveenable` enables adaptive letters and swipe for hands-on testing.

The `memory` mode checks both text and both dictation models for warm reuse,
release, unmapped GGUF files and successful inference after reload. It also
checks worker reservations and prediction/speech/translation cache invalidation.
Use `--accelerator cpu` or `--accelerator hexagon` to require the actual backend;
translation retains its CPU path. The public speech fixture is
`../whisper.cpp/samples/jfk.wav`, staged as `files/jfk.wav` in the test app.
The `memoryidle` mode waits for the real two-minute production sweep, records
PSS before/after eviction and checks reloading. Leave the phone idle for these
checks so the active IME cannot warm models in the same process.
Repeated editor cleanup runs during the timeout check and must not postpone
model eviction. These checks run without opening a test or loading screen.
Keep the device awake when measuring the timer separately from deep sleep or
Android's earlier background reclamation.
The headless `battery` mode checks that no memory sweeps run with an empty model
cache, repeated cleanups do not queue native resets, new work still receives one
cleanup, and hidden refreshes do not query an editor. These are work-avoidance
checks, not a measurement of battery percentage or hours saved.

```sh
python3 tools/test-device.py --serial SERIAL --label memory-cpu --accelerator cpu memory memoryidle
python3 tools/test-device.py --serial SERIAL --label memory-npu --accelerator hexagon memory memoryidle
```

The runner saves JSON results and logs in `artifacts/`; screenshots are kept
locally and ignored by Git. Keep source fixtures with tests or in `research/`.

## Localization and privacy

```sh
python3 tools/check-locales.py
python3 tools/test-device.py --serial SERIAL --label locales localization privacy
python3 tools/test-app-locales.py --serial SERIAL
```

Resource checks cover all 39 languages and the Traditional Chinese variant,
formatting arguments and nonempty strings. Android checks also draw the
keyboard, validate accessibility text and preserve user-provided names and
clipboard content. The visible-screen runner changes Android's per-app locale,
checks three pages in representative languages, and restores the original
locale and screen-awake setting. Keep the phone unlocked for these UI checks.

The privacy check opens the policy through the Models screen, checks the
packaged contact and content, and verifies that setup links to it as well.

## Model correctness

`probability` checks token-matched RAM checkpoints against fresh prompt
execution, speculative-state isolation, immediate prefix filtering, bounded
editor windows and stale-result rejection. `probabilitylanguages` uses 20
hand-picked fixtures across nine languages. Those reference hits are fixture
results, not a language-quality benchmark.

`translationmodels` isolates model availability in a test-only directory, exercises LFM-only translation, and switches between LFM and Qwen to check cache identity. It does not remove or replace the user’s installed model files.

`correction` checks real replacements and undo as well as words that should
remain unchanged. `translation` and `translationui` cover generated output,
cancellation and selection/cursor guards. Speech and model checks require
the corresponding weights and fixtures; do not redownload large files just
to rerun a UI-only change.

The C11 boundary check in `tools/native-word-test.c` can be built with address
and undefined-behavior sanitizers without loading a model.

## Recorded devices

OnePlus 15 and POCO F3 reports are in `artifacts/`. Measurements depend on the
recorded model, runtime, backend, prompt and power/thermal state. Old model
selection experiments are historical evidence and do not add models to the
current four-card catalog.

The localization and color-reset checks are summarized in
[publication-readiness.json](../artifacts/publication-readiness.json). Release
signing checks are recorded separately when a signed release is built. An
ARM64 library with 16 KB ELF alignment still needs runtime testing on a 16 KB
page-size Android device.
