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

## Fast typing across keyboards

The standalone benchmark runs an editor in a separate process from the keyboard
and injects touchscreen down/up events at the centers of accessibility keys.
It never uses `input text`, which bypasses keyboard processing. Install the
benchmark and leave the phone unlocked:

```sh
./gradlew :typing-benchmark:assembleDebug
adb install -r tools/typing-benchmark/build/outputs/apk/debug/typing-benchmark-debug.apk
python3 tools/bench-typing.py --serial SERIAL --label typing --intervals 80 40 24
python3 tools/bench-typing.py --serial SERIAL --label typing-terminal --terminal
python3 tools/bench-typing.py --serial SERIAL --label typing-slow --read-delay-ms 4
```

The intervals offer 150, 300 and 500 WPM using five characters per word. Each
round types the same pangram and presses Backspace eight times. One warmup round
precedes three measured rounds. The runner waits three seconds after each replay
for queued edits to finish. Results include the actual and expected text,
matched edit states, latency percentiles, editor-read counts and injection
lateness. Latency runs from the scheduled finger release to the editor's text
change, with changes received before release counted as zero. It measures text
delivery rather than display scanout or prediction quality. A late injector can
inflate the results; inspect its lateness alongside latency. Compare correctness
as well as latency. The report's `passed` flag means the harness completed;
per-round `correct` records the text and deletion result, ignoring case.

`--terminal` implements Termux's raw input contract: committed text is forwarded,
the local editable buffer stays empty, and deletion uses character deletion or
key events. `--read-delay-ms` adds a fixed delay to each editor read to expose
blocking work; this is a controlled stress test rather than a measurement of a
specific host app. Neither test opens an SSH session.

The runner recognizes LoKey, Gboard and SwiftKey Beta, reports their installed
versions, and restores the selected input method after each test. For another
package, use `--keyboards swiftkey --ime PACKAGE/SERVICE`. The benchmark stops
if its editor loses focus. JSON reports and raw logs go in `artifacts/`.
Rotate the phone and pass `--orientation landscape` to require a landscape
run. Choose the keyboard's full-width or split layout before each comparison.
Use `--output-dir` to save test artifacts to another directory.
Use the same device, settings, model availability and workloads for before/after
comparisons; the runner does not normalize keyboard preferences.

The `fasttyping` instrumentation mode checks shared editor reads, clipping,
selection and mutation invalidation, local prediction scheduling/cancellation,
and 300 overlapping thumb pairs with reverse
release order, including spaces and punctuation. It also checks that disabling
adaptation restores the original geometry. `fasttypingprofile` enables typing
features only in isolated `org.lokey.app.fasttyping*` test packages, leaving the
installed app's profile alone.
The terminal check uses the actual IME with a raw editor to verify zero ordinary
typing reads, Unicode backspace, Enter/arrows, Hangul and dead-key composition,
and cancellation of pending composition without deleting committed terminal text.

The 2026-11-17 OnePlus 15 comparison is recorded in
`artifacts/fast-typing-summary.json`. It uses optimized isolated before/after
builds with identical native libraries and bundled assets, QWERTY/English,
all four typing features enabled, and no downloaded neural model. Gboard and
SwiftKey Beta retain their installed settings. Results describe this workload
on one phone; they do not establish a universally fastest keyboard.

| Keyboard | 300 WPM p95 | 500 WPM p95 |
| --- | ---: | ---: |
| LoKey before | 18 ms | 341 ms |
| LoKey after | 16 ms | 20 ms |
| Gboard Beta | 19 ms | 33 ms |
| SwiftKey Beta | 21 ms | 21 ms |

Each cell is the median of three per-round p95 values. Small differences can be
run-to-run noise. Every normal round delivered the expected final text and
deletions. With 4 ms delayed editor reads at 300 WPM, LoKey's median p95 fell
from 976 to 23 ms and reads fell from 581 to 112 per round. The raw terminal
case changed from three deletion failures to three correct rounds, with zero
ordinary editor reads and a median p95 of 16 ms.

The real Termux smoke check types `lokeyxyz`, deletes three characters to leave
`lokey`, then clears the line without executing a shell command. SSH network
latency is outside these measurements. The existing `imeflow` suggestion check
for `?` after `Ciao come stai` fails in both isolated builds without a text model;
its earlier real-swipe assertions and the five targeted regression modes pass.

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
