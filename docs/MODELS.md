# Lokey models

Commands and repository paths below are relative to the repository root.

## Local models

Models offers LFM2.5-350M Instruct (230 MB) and Qwen3.5-0.8B Instruct (533 MB), both in Q4_K_M. Prediction, autocorrection and translation use the same resolved text model. LFM handles all three when it is the only installed text model. Qwen is not a separate translation requirement. Download the runtime-compatible Parakeet TDT 0.6B v3 GGUF for dictation. Keep the matching architecture for each slot; a GGUF extension alone does not imply compatibility.

Automatic selects both models from the language chosen in Lokey setup, defaulting to the device language. On devices with a supported Hexagon DSP and its bundled kernel, it prefers Qwen text and Qwen ASR. Bulgarian, Croatian, Estonian, Latvian, Lithuanian, Maltese, Slovak, Slovenian and Ukrainian retain Parakeet because Qwen ASR does not list them. CPU devices prefer LFM where listed and Parakeet where listed; Chinese uses both Qwen models. Installed-model fallback stays within the configured language's support. Selecting either model manually preserves the other resolved choice and leaves Automatic. The selected text and speech cards are both marked Selected automatically while Automatic is active.

Prediction still follows the language of recent text without changing the selected model; a new or ambiguous message may need several words to identify its language. The spacebar has no language label. Capability detection runs in the background; inference initialization retains CPU fallback.

Bundled frequency vocabularies cover 21 supported Latin-script languages: English, Italian, Spanish, French, Portuguese, German, Dutch, Swedish, Danish, Finnish, Polish, Czech, Slovak, Romanian, Hungarian, Turkish, Croatian, Slovenian, Estonian, Lithuanian and Latvian. This is not support for every European language or script. Vocabulary comes from Hermit Dave's [FrequencyWords](https://github.com/hermitdave/FrequencyWords), derived from OpenSubtitles, under CC BY-SA 4.0; attribution and regeneration instructions are in `assets/languages/CREDITS.txt` and `tools/prepare-languages.py`.

By default, a low-priority worker produces frequency/phrase suggestions after a 35 ms debounce, then ranks complete words from local model probabilities. A bounded search filters token pieces against the typed prefix before selection, adds their normalized log-probabilities, and scores the probability of ending each word. Up to three distinct completed words are displayed; weak word endings are excluded. The dictionary is a fallback rather than a whitelist: names, places, and technical terms can be generated without appearing in it. Native decoding starts at the current word boundary and constrains token sequences to the typed prefix, avoiding partial-token artifacts. Leading apostrophe fragments are rejected, while complete contractions remain valid. Short English contractions such as `Im` and `Ive` are handled without weakening the general protection for capitalized names and acronyms.

A RAM candidate pool retains up to 24 generated words plus the bounded, model-scored dictionary candidates at a word boundary. Prefix extensions select a precomputed state immediately on the UI thread; a thin pool is refilled in the background, and obsolete results are discarded. Up to three RAM checkpoints retain attention/recurrent model memory, exact token IDs, and matching output logits. Appending text decodes only the new tokens when the IDs match. Backspaces and cursor changes restore a matching checkpoint or rebuild the bounded context; speculative completions are restored separately. Editor-session changes clear both caches. No partial recurrent rewinds or disk prompt caches are used. Obsolete jobs cannot replace newer suggestions. If no completion is available yet, the typed word remains available to accept. Once the current word is recognized as complete, the entire text including that word is used for next-word suggestions; accepting one appends it. Partial completions remain available. Punctuation (`?`, `.`, `!`) is scored at its actual tokenizer boundary. Accepting punctuation preserves the final word and removes an intervening automatic space. Neural context uses the last 192 characters before the word boundary, leaving room inside the editor’s 240-character window for the typed prefix. Prediction context is not saved to disk. Private fields disable prediction and voice.

Autocorrection proposes nearby spellings from both the model and the fallback lexicon. It scores every token of a candidate and the probability that the word ends there, comparing the result against the original spelling in the same sentence. Candidates must beat the original by a confidence margin; unfamiliar terms can remain unchanged rather than being forced into a familiar word. Damerau edit distance limits automatic replacements to small spelling changes. Space and punctuation trigger correction; when inference is still running, the boundary is inserted immediately and a late correction is applied only if the editor text, cursor, and session still match. Other input is preserved. Existing words, capitalized unknown names, selected text, mid-word edits, addresses, numeric fields, no-suggestions fields, and private fields are protected. Known contraction forms are the exception to the short-word/name guards. Backspace immediately restores the original spelling, without immediately correcting it again. Enable autocorrection in Feel. Swipe typing, adaptive letters and autocorrection default to off; keyboard height defaults to 100%. Saved preferences take precedence.

Adaptive letter sizes use current on-device letter probabilities to
move shared keycap boundaries by at most 0.12 key pitch. Letter labels and their
original center targets stay fixed; active touches freeze geometry. Letter probabilities aggregate the current model-scored word likelihoods,
including generated and dictionary candidates when a text model is installed.
Without a text model, estimates come from the public dictionary/context counts.
Validated neural candidates/scores are
reused only in the active inference context in memory. Prefix candidates and
letter distributions are prepared on the worker, so a cached tap selects its
next state synchronously. Cache misses start immediately while adaptive letters
are enabled; fresh model inference still takes time.
No touch history, calibration, learned user profile or usage information is saved. Numeric, symbol, composition and private fields bypass adaptation.
Unsupported native alphabets keep fixed keys rather than reuse ASCII priors.

Swipe uses the original geometry decoder and selected text-model context scorer.
Public FrequencyWords dictionaries contain up to 50,000 swipe words for each of
21 Latin-script languages. They load lazily and are indexed by first/last letter
to avoid scanning every word. They do not change the installed text model's
vocabulary or its known-word autocorrection guards.

Without downloaded neural models, a small Kotlin engine reuses those bundled
vocabularies for completion, conservative correction and adaptive-letter priors
in the 21 Latin-script languages. A bounded pass from the resident small
dictionary returns first suggestions while the worker prepares the larger index.
Both passes start without the neural debounce.
A compact public word-pair/triple table adds short context for English, Italian,
Spanish, French, German, Portuguese and Turkish. These are fixed statistics from
Universal Dependencies 2.18, with source pins in `assets/context/sources.json`
and offline attribution in `context/CREDITS.txt`; they are not trained on user
input. Public vocabulary caches are bounded. Correction still needs the user
setting enabled, protects known words/names and uncertain candidates, and keeps
unfamiliar word endpoints unchanged. This helps common spelling errors but does
not provide LLM-level semantics. Native-script prediction coverage is unchanged;
bundled input-method conversion still works. Dictation and translation require
their optional neural models.

Audio is recorded as 16 kHz mono PCM in memory, with a 60-second limit, then transcribed locally. The microphone is released on stop, cancellation, editor changes, and keyboard dismissal. Transcripts are inserted only into the original editor session. Recording and inference never run on the UI thread. Internet access is used only for explicit model downloads; there are no analytics or remote inference endpoints.

Prediction, dictation and translation each retain their loaded model for two
minutes after their last use. A background task scheduled for the next idle
deadline releases idle weights, contexts, checkpoints and model-owned
CPU/Hexagon buffers.
It then asks Android's allocator to return unused heap pages to the OS, rather
than retaining large freed allocations in its caches.
Short pauses and repeated requests reuse the loaded model. Cached prediction
requests also count as use; an unused speech or translation model can expire
while prediction remains active. Speech starts warming when recording starts,
rather than every time the keyboard opens.
Loading and release run on background workers without a loading screen or
blocking typing. Dictionary suggestions remain available while text weights load.
Vocabulary initialization, editor cleanup and dictionary-only requests do not
renew the neural model's idle timestamp.
Repeated cleanup clears native state once until prediction work resumes, and
hidden keyboard callbacks do not refresh prediction. The memory task stops
entirely when no models are resident and is rearmed by a load or model-use job.
Busy inference can briefly postpone reclamation. These changes retain the same
models, scoring/search budgets, prediction delays and CPU/NPU power settings.

Whole worker jobs reserve their model across load/inference calls. Reclamation
skips busy workers and locked native contexts, and never cancels inference to
free memory. Native generations invalidate Kotlin load identities and candidate
caches after eviction, so the next request reloads normally. Android memory
pressure callbacks can request earlier reclamation of models without active
jobs. The deadline task remains necessary on Android versions that no longer
deliver older memory-pressure levels. Timers do not wake a sleeping phone;
expired models can be reclaimed when execution resumes. Shared runtime/device
initialization stays available for subsequent loads; model files and user
settings remain on disk.

Models → Speak offers both Parakeet v3 and Qwen3-ASR-0.6B Q8_0. Qwen requires both `Qwen3-ASR-0.6B-Q8_0.gguf` (805 MB) and `mmproj-Qwen3-ASR-0.6B-Q8_0.gguf` (214 MB); Download fetches both from the official ggml-org repository. The C11 bridge uses llama.cpp and its mtmd public C APIs for the Qwen decoder and audio encoder. Speech jobs share one lock, can be cancelled, and preserve the active editor guard. The runtime revisions are pinned in `runtimes.json`; Parakeet carries a cropped relative-position view compatible with the newer shared GGML bounds checks.

On the OnePlus 15, the clean JFK fixture took about 1.85 seconds with Parakeet and 3.25–3.28 seconds with Qwen Q8. Qwen also transcribed its official 4.20-second Chinese sample in 1.44 seconds and its 15.05-second English sample in 4.43 seconds. Both silence checks passed. These are limited timing and transcription fixtures, not a broad quality comparison; see `artifacts/oneplus-speech.json`.

## Model files and licenses

The two text models are the curated Instruct versions. Both use Q4_K_M weights. Qwen3 ASR uses its matching Q8_0 decoder and audio encoder. These format names are implementation details and are omitted from the Models cards. Paths and download URLs are defined in `app/src/main/java/org/lokey/app/Models.kt`; changing only a displayed name does not change a model.

LFM weights use the LFM Open License v1.0, Qwen weights use Apache 2.0, and Parakeet weights use CC BY 4.0. Runtime libraries and model weights have separate licenses. Models → Models & inference credits links the original models, conversions, pinned runtime sources and offline license texts.

Downloads use Hugging Face and may expose network metadata to the host. See [PRIVACY.md](PRIVACY.md). Lokey sends no prediction, translation or audio content in a model download.

## One text model, one speech model

All text functions resolve through `Models.selectedText`: prediction, correction and translation use the same installed model file. An installed manual selection is respected. If the preferred model is missing, the available text model is used across these functions. With no text model installed, ordinary typing remains available and translation asks for a text-model download.

Translation uses the model’s instruction template. Qwen keeps its non-thinking response prefix; LFM uses its own template without Qwen thought markers. Translation output caches include model identity, so changing models cannot return a cached result from the previous model. Translation and prediction have separate runtime contexts to keep their working state isolated.

LFM is smaller and its published language list is narrower. Qwen is generally the stronger translation option in the recorded fixtures; enabling LFM does not imply equal quality or dependable results for every language in the picker. The active speech model supplies dictation both in normal fields and in the translation source editor.

The small LFM model can produce a fluent but wrong translation, including a wrong time-of-day greeting. The model-switch smoke tests check execution, language, cache reuse and a few short phrases; they are not a translation-quality guarantee. Review important output, or choose Qwen for the broader and stronger translation path.
