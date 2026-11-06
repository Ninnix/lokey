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
The runner saves JSON results and logs in `artifacts/`; screenshots are kept
locally. Keep meaningful JSON fixtures and summaries, not private device dumps.

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
