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
python3 tools/bench-typing.py --serial SERIAL --label typing-bursts --intervals 120 40 24 12
python3 tools/bench-typing.py --serial SERIAL --label typing-terminal --terminal
python3 tools/bench-typing.py --serial SERIAL --label typing-slow --read-delay-ms 4
```

The default intervals offer 150, 300 and 500 WPM using five characters per word;
120 and 12 ms extend the replay to 100 and 1,000 offered WPM. Each
round types the same pangram and presses Backspace eight times. One warmup round
precedes three measured rounds. The runner waits three seconds after each replay
for queued edits to finish. Results include the actual and expected text,
matched edit states, latency percentiles, editor-read counts and injection
lateness. `latency_*` runs from the scheduled finger release to the editor's text
change; `delivery_*` starts when the release is actually submitted. Changes
received before release count as zero. These measure text delivery rather than
display scanout or prediction quality. A late injector inflates scheduled latency;
inspect both metrics and injection lateness. Compare correctness
as well as latency. The report's `passed` flag means the harness completed;
per-round `correct` records the text and deletion result, ignoring case.

The default `--injector direct` requires Android 12 or later. Its shell helper
submits touchscreen events to Android's input manager without waiting for window
transactions after each tap. `--injector uiautomation` preserves the older mode
and supports earlier Android versions. Android's two-argument UiAutomation
injection method synchronizes window transactions before Down and after Up even
with `sync=false`; that wait can limit the replay rate. Each report records the
injector and the p95 duration of release submission (`injection_up_p95_ms`).
Compare builds using the same injector and do not interpret a large scheduled
latency as entirely time spent processing an already submitted key.

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
adaptive geometry freezing and flushing after cancelled touches,
and 300 overlapping thumb pairs with reverse
release order, including spaces and punctuation. It also checks that disabling
adaptation restores the original geometry. `fasttypingprofile` enables typing
features only in isolated `org.lokey.app.fasttyping*` test packages, leaving the
installed app's profile alone.
The terminal check uses the actual IME with a raw editor to verify zero ordinary
typing reads, Unicode backspace, Enter/arrows, Hangul and dead-key composition,
and cancellation of pending composition without deleting committed terminal text.

Ordinary letter bursts coalesce optional editor/suggestion refreshes over a
32 ms budget. Word boundaries, deletion, selections and composition bypass that
budget; text commits and pressed-key rendering keep their immediate paths.

The 2026-11-26 OnePlus 15 rerun is saved in `artifacts/throughput-summary.json`.
With direct injection, median per-round delivery p95 changed from 7 to 6 ms at
100/300 WPM, 10.5 to 11 ms at 500 WPM, and 27 to 22.5 ms at 1,000 WPM.
The 500 WPM latency result is effectively flat; editor reads fell from 109.5 to
94 per 52-action round. Gboard measured 7/6/8/13 ms and SwiftKey 18/10/10/16 ms
at those four speeds. LoKey's 500/1,000 WPM checks use six measured rounds;
other cells use three, with warmups excluded. All scored rounds were correct.
LoKey used an isolated QWERTY/English profile with swipe, adaptive keys,
autocorrect, learning, sound and haptics enabled, and no downloaded models.
Competitor settings stayed as installed. These short, one-phone English replays
do not establish prediction quality or a universal speed ranking; small timing
differences can be noise. Darchy's supplied results use a different editor and
input route and were not rerun as part of this comparison.

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

### Polish dictation

The `speechcorpus` mode reads `files/speech-corpus/fixtures.json` with a `file` field for each mono 16 kHz PCM WAV beside it. The optional `language` field defaults to Polish. The mode compares Parakeet where supported with Qwen language detection and Qwen given the fixture language. The report in `files/speech-corpus/results.json` includes text, audio duration, decode time, and the backend. Pass `-e speech_accelerator cpu` or `-e speech_accelerator hexagon` to select the backend.

On the OnePlus 15, the same 24 Polish FLEURS test recordings contained 392 reference words. The sample used distinct reference sentences, random seed 181, and four recordings per speaker gender in each duration range from 0–6, 6–9, and 9–15 seconds. Word error rate ignores punctuation and casing and retains diacritics. Times measure decoding after model load. [FLEURS](https://huggingface.co/datasets/google/fleurs/tree/70bb2e84b976b7e960aa89f1c648e09c59f894dd)

| Model and language mode | Hexagon WER | Hexagon median | CPU WER | CPU median |
| --- | ---: | ---: | ---: | ---: |
| Parakeet v3 | 10.2% | 209 ms | 10.2% | 1,503 ms |
| Qwen3 ASR, automatic | 33.4% | 1,391 ms | 35.5% | 3,015 ms |
| Qwen3 ASR, Polish | 32.9% | 1,318 ms | 35.5% | 2,938 ms |

### Cross-language autocorrect

Stage [language-corrections.json](../tools/typing-benchmark/language-corrections.json) as
`files/correction-corpus.json` in an isolated test app. Run
`correctioncorpus-local`, `correctioncorpus-lfm`, or `correctioncorpus-qwen`.
Use empty model storage for the local run and install the named text model
for each model run. The report records each expected and actual edit.
For each proposed edit, the check exercises a space commit, undo, and a
deferred punctuation commit in an editor.

In the initial OnePlus 15 runs, all three configurations preserved all ten valid forms per language.
Across 120 checks of valid forms and 72 checks of deliberately mistyped
words, there were no substitutions to an infinitive or other incorrect
form. Every mismatch was a missed correction.

| Language | Bundled dictionary | LFM 2.5 | Qwen 3.5 |
| --- | ---: | ---: | ---: |
| English | 4/6 | 6/6 | 6/6 |
| Spanish | 0/6 | 2/6 | 4/6 |
| French | 0/6 | 3/6 | 3/6 |
| Portuguese | 0/6 | 0/6 | 0/6 |

The table counts expected corrections applied in the curated cases. For
example, Qwen restored `saldré` and `hablábamos` in Spanish, while
`viajaria` and `ella termino` stayed as typed. Portuguese `estavamos`
stayed as typed in all three runs. These cases target verb-form preservation
and common typos or missing accents. The `layoutui` suite covers native
Hindi input and Mandarin Pinyin composition.

The follow-up adds accent restoration and narrow context checks for
conjugated forms. Its results below use the original six typo cases per
language, followed by the expanded fixture totals.

| Language | Original cases, bundled dictionary | Original cases, either model | Expanded cases, all configurations |
| --- | ---: | ---: | ---: |
| English | 6/6 | 6/6 | 6/6 |
| Spanish | 6/6 | 6/6 | 7/7 |
| French | 6/6 | 6/6 | 7/7 |
| Portuguese | 3/6 | 3/6 | 5/8 |

All three configurations preserved the 50 valid input examples in the
expanded fixture. Spanish now restores `viajaría` and uses the past-tense
context in `Ayer ella terminó`. French uses the preceding words to recognize
`Il est allé` even when the unaccented final word appears in another
language. Portuguese restores forms such as `estávamos` and `tínhamos`.
The remaining Portuguese misses involve ambiguous forms of `vira`,
`e`, and `de` that can be valid in other contexts.

### Multilingual dictation

To reproduce the first multilingual sample, put the FLEURS test TSV and audio
archive in each locale folder as `test.tsv` and `test.tar.gz`. Use
[FLEURS revision 70bb2e84](https://huggingface.co/datasets/google/fleurs/tree/70bb2e84b976b7e960aa89f1c648e09c59f894dd/data).

```sh
python3 tools/bench-language-quality.py prepare .local/multilingual-quality \
  English=en_us Chinese=cmn_hans_cn Hindi=hi_in Spanish=es_419
```

Stage the generated `corpus/` as `files/speech-corpus/` alongside the
model files and run `speechcorpus`. Pull `results.json` from each run
to score them together.

```sh
python3 tools/bench-language-quality.py score .local/multilingual-quality \
  .local/multilingual-quality/speech-hexagon-decoded.json \
  .local/multilingual-quality/speech-cpu-decoded.json
```

Each language uses 24 distinct transcripts selected with seed 181, with
four recordings per speaker gender in each duration range from 0–7, 7–10,
and 10–15 seconds. Scoring folds case, ignores punctuation and preserves
diacritics and vowel marks. It uses character error rate for Mandarin and
word error rate for English, Hindi, and Spanish. Decode timing excludes
model load.

On the OnePlus 15, each backend completed 240 decodes over the same 96
recordings. Qwen3 ASR used its Q4_K decoder and Q8_0 audio encoder.

| Language | Model and language mode | Hexagon error | Hexagon median | CPU error | CPU median |
| --- | --- | ---: | ---: | ---: | ---: |
| English | Parakeet v3 | 4.11% | 280 ms | 4.31% | 1,613 ms |
| English | Qwen3 ASR, automatic | 4.93% | 1,485 ms | 4.93% | 2,940 ms |
| English | Qwen3 ASR, English | 4.93% | 1,406 ms | 4.93% | 2,884 ms |
| Mandarin | Qwen3 ASR, automatic | 7.40% | 1,333 ms | 6.86% | 4,400 ms |
| Mandarin | Qwen3 ASR, Chinese | 7.40% | 1,257 ms | 6.86% | 4,469 ms |
| Hindi | Qwen3 ASR, automatic | 30.23% | 2,587 ms | 32.56% | 6,438 ms |
| Hindi | Qwen3 ASR, Hindi | 29.30% | 2,437 ms | 30.23% | 6,643 ms |
| Spanish | Parakeet v3 | 3.57% | 319 ms | 3.81% | 1,602 ms |
| Spanish | Qwen3 ASR, automatic | 4.29% | 1,249 ms | 4.52% | 5,090 ms |
| Spanish | Qwen3 ASR, Spanish | 3.57% | 1,244 ms | 4.52% | 4,864 ms |

Parakeet covered English and Spanish in this batch. One Hindi clip
(`hi_in-1078154098564425938.wav`) ended after the first few words and
returned replacement characters in all four Qwen runs. Its errors are
included in the table.

### Dictation follow-up

The Qwen decoder now completes UTF-8 sequences across token boundaries
and reuses the single-token decode batch. The same Hindi recording
transcribed both sentences on the follow-up runs on CPU and Hexagon. Stage that recording
as `files/asr-hi-utf8.wav` to include it in the `speech` regression checks.

| Language | Hexagon result | Before error | After error | Before median | After median |
| --- | --- | ---: | ---: | ---: | ---: |
| Mandarin | Previous automatic vs new Chinese hint | 7.40% CER | 7.40% CER | 1,333 ms | 1,122 ms |
| Hindi | Previous automatic vs new Hindi hint | 30.23% WER | 26.74% WER | 2,587 ms | 2,191 ms |

With the same Hindi hint on both runs, word error rate fell from
29.30% to 26.74%. All 24 Mandarin transcripts matched the prior
outputs for their respective language modes. Mandarin Pinyin dictation
also joins the editor text without adding spaces.

The follow-up compared French and Brazilian Portuguese on Hexagon with
24 additional FLEURS recordings per language. French used 12 recordings
per speaker gender. The Brazilian Portuguese test split labels its
recordings as male, so the sample uses eight per duration range.

| Language | Model and language mode | WER | Median decode |
| --- | --- | ---: | ---: |
| French | Parakeet v3 | 4.58% | 271 ms |
| French | Qwen3 ASR, automatic | 10.07% | 1,386 ms |
| French | Qwen3 ASR, French | 10.44% | 1,397 ms |
| Brazilian Portuguese | Parakeet v3 | 1.94% | 281 ms |
| Brazilian Portuguese | Qwen3 ASR, automatic | 5.34% | 1,305 ms |
| Brazilian Portuguese | Qwen3 ASR, Portuguese | 5.34% | 1,294 ms |

The `modelpolicy` mode checks automatic model preferences for all
39 languages, layout-based dictation routing, and manual model overrides.

### Idle memory and battery

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
