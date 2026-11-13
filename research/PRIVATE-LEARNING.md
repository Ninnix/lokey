# Private learning with and without downloaded models

Reviewed 13 November 2026. Learning is an optional switch in Feel, off on a
new installation. Default typing saves no personal profile; enabling learning
adds encrypted word statistics.

## Decision and evidence

Use a bounded, encrypted, on-device word-frequency cache and explicitly
confirmed typo pairs. Augment both the public dictionary and neural scoring;
derive next-letter priors from learned completions and reuse constrained
resizing with fixed letter centers. Do not save sentences or touch traces,
import messages, retrain weights, or send training events to a server.

| Primary source | Evidence and decision |
| --- | --- |
| [Sakkos et al., Anima, 2015](https://arxiv.org/abs/1501.05696v2) | Personal dictionaries and forgetting support small adaptive keyboards. Keep frequency and aging; exclude its history import and temporal profiling. Its interface and evaluation do not establish benefit for Lokey. |
| [Grave et al., continuous cache, 2016](https://arxiv.org/abs/1612.04426v1) | External caches can adapt pretrained language models without parameter updates. The paper evaluates hidden-state caches. Lokey uses ordinary counts compatible with a dictionary; it does not reproduce that neural cache or its gains. |
| [Sivek and Riley, Gboard spatial personalization, 2022](https://arxiv.org/abs/2209.11311) | Personal offsets/covariances can improve decoding, with overfitting controlled through data quantity, weighting and grouping. Defer spatial learning: personal word probabilities already influence letter sizes without retaining touches. |
| [Abouelenin et al., SwiftKey DP transformer, 2025](https://arxiv.org/abs/2505.05648v1) | Studies small transformers and differentially private population training on typing data. Local adaptation is a different problem. This feature uploads nothing and makes no differential-privacy claim. |

The papers motivate components, not Lokey's exact thresholds. On-device
fine-tuning would add optimizer state, replay data, checkpoint lifecycle and
battery/RAM costs. Federated learning adds a server/training pipeline that
this local feature does not need. Personal bigrams might improve context but
retain word relationships and increase phrase-reconstruction risk. Start with
unigrams; context-sensitive benefits are consequently limited. There is no
evidence here of a universally best personalized keyboard.

## Implementation

* Learn words from Lokey's own key events and explicit suggestion selections.
  Do not scan an existing editor to build a profile. Pasting, transcripts,
  translations, swipe output and the practice test are excluded. Discard the
  current draft on editor/tool/session changes or cursor discontinuities.
  Counts finalize at whitespace, stripping punctuation suffixes. Automatic
  correction output does not train the cache.
* Accept 2–24 Unicode letters/apostrophes. Reject digits, internal punctuation,
  URLs, email syntax and whitespace. This is minimization, not secret
  detection: an ordinary word or name can still be sensitive.
* Separate by layout and prediction language. Suggest after two observations,
  including words absent from the bundled vocabulary. This path needs no
  GGUF, tokenizer or native inference.
* Learn a typo only from an explicit suggestion selection when the typed token
  came from this keyboard and edit distance is one. A prefix completion is
  never a typo. Require three confirmations, a decayed count of two, and a
  threefold margin over competing replacements. Undo removes corresponding
  correction evidence. Word frequency alone cannot authorize autocorrection.
* Cap the whole profile at 1,024 aggregate entries. Decay with a 30-day
  half-life, discard entries unseen for over 90 days when accessed, and evict
  low-weight entries at capacity. Keep day buckets, without event timestamps
  or ordered observations.
* Restrict frequency boosts to the current word prefix. Leave next-word
  prediction to the existing contextual engine. Build a conservative set by
  placing a missing personal completion in the third slot, keeping two context
  choices. For a partial word, permit that completion to move first only when
  its existing model score plus `min(3, 1.5 * ln(decayed_count))` reaches the
  current first score. Missing/invalid scores, append-mode results, near-typo
  alternatives and active automatic corrections keep the conservative order.
  The cap prevents arbitrarily frequent words from overriding strong model
  evidence. These rank scores approximate first-token support, not calibrated
  full-word likelihood. The model-free path may rank personal completions
  first. Explicitly confirmed typo pairs retain priority. A completion
  extending a complete word replaces it rather than appending another word.
* Add personal prefix candidates to the LLM pool before scoring when installed;
  do not concatenate a profile into its prompt or score a word extension as a
  next word. Blend prefix-specific next-letter probabilities at 25% when a base
  prior exists, otherwise use the personal prior. Resizing and automatic
  replacement still require their own separate switches.

Immediate and worker results from the public dictionary both use this cache,
so the model-free path benefits directly. The LLM continues to process the
actual current context. Frequency boosts are limited because a frequent word
is not necessarily appropriate to that context.

## Privacy boundary

Honor [Android's no-learning flag](https://developer.android.com/reference/android/view/inputmethod/EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING)
for collection and personal suggestions. Exclude private/password, visible/web
password, numeric/phone, email, URL, filter, person-name and postal-address
fields and fields disabling suggestions. Exclude tools and practice tests.
Unspaced/composition layouts are excluded from collection in this revision.
Receiving apps supply the flags; secrets in ordinary chat cannot reliably be
recognized. The feature explanation and privacy policy disclose this limit.

Persist only words, typo pairs, counts, day buckets, layout and language in
`noBackupFilesDir/usage-learning.bin`. Use AES-256-GCM with a fresh
provider-generated IV and authenticated format identity, and a non-exportable
[Android Keystore](https://developer.android.com/privacy-and-security/keystore)
key restricted to an unlocked device. Hardware protection depends on the
device; StrongBox is not required. There is no plaintext fallback. Discard
malformed, oversized or unauthenticated files with their key.
If initialization happens while the device is locked, defer decryption until
unlock; a temporarily unavailable key must not erase a valid profile.

Coalesce writes on one background executor and commit with AtomicFile. Erase
clears memory, invalidates queued snapshots, deletes the file and deletes the
key under the same lock as writes. Turning learning off invokes erase; an
older write cannot restore a profile. Erasing while enabled permits fresh
observations afterward. Existing app rules exclude cloud/device-transfer
backup. No new network path, export, diagnostics containing words or dependency
is introduced.

Encryption protects a copied file, not a compromised app/OS, use of the
unlocked keyboard, or suggestions visible on screen. JVM strings and flash
storage cannot be promised immediate physical erasure. Key deletion removes
access to the persisted encrypted profile.

## Evaluation

The local replay compares model-free, LFM 350M and Qwen 0.8B prediction, with
learning off/on. The final build completes 2,160 trials: three fictional English
sets, three paths, two learning conditions and 120 probes per configuration.
Each set has 48 completions, 24 next words, 24 typos and 24 clean-word controls.
Profiles are frozen during evaluation and training/evaluation sentences differ.

First-choice completion hits out of 48, conservative order → bounded promotion:

| Path | Original set | Second set | Fresh holdout |
| --- | --- | --- | --- |
| Model-free | 17 → 17 | 21 → 21 | 17 → 17 |
| LFM 350M | 24 → 26 | 19 → 24 | 18 → 22 |
| Qwen 0.8B | 28 → 29 | 21 → 24 | 22 → 27 |

The original and second sets are development data. A 3×3 grid tests frequency
scales 0.5/1/1.5 and caps 1/2/3. Selection recovers each model/set's previous best
first-choice total, then minimizes paired losses. The selected 1.5/3 point has
one fewer aggregate development hit and more individual losses than 1/2, but
recovers both old second-set totals of 24. It is not a universal optimum.
The third set is frozen before tuning and scored after fixing the rule.

Top-three hit counts, next-word hit counts and every boundary-correction string
remain unchanged across all nine set/path comparisons. The two development
sets gain 16 and lose five first-choice hits; the holdout gains nine and loses
none across the neural paths. Some useful personal names remain blocked by
strong approximate model scores. Existing false corrections remain. These
small self-authored English pilots do not establish population accuracy,
multilingual quality, human typing speed, touch accuracy or energy savings.
Weights are warm and prediction/KV caches are cleared between probes. The
normal installed app and its real learned data are untouched. Detailed replay
fixtures, instrumentation and raw measurements remain local research artifacts.

Run instrumentation mode `typingfeatures`. `TypingFeatureChecks` uses synthetic
words, isolated preferences/files and a separate Keystore alias. It checks the
model-free Prediction API before/after learning, unknown-word completion,
confirmed correction, letter priors, language/layout isolation, capacity,
aging, excluded flags, encrypted restart, tamper rejection and deletion with a
pending write. Results: [device report](results/private-learning-device.json).

These are functional checks, not a human typing study. Before increasing
weight or adding n-grams, evaluate held-out consented typing: top-3 hit rate,
next-letter log loss, false corrections, undo rate, p50/p95 latency and energy.
Ablate counts, correction pairs and both, with and without neural models.
Run `typingfeatureslive` in the isolated application to exercise actual key
events, the suggestion strip and no-learning/email field transitions. The
previous input method is restored afterward.

Build the separate test installation with
`./gradlew -I research/benchmarks/private-learning.gradle :app:assembleRelease :app:assembleDebugAndroidTest`
(plus the NDK/runtime/Hexagon arguments used for the normal build). Install
both APKs and run
`adb -s SERIAL shell am instrument -w -e mode typingfeatures org.lokey.app.features.test/org.lokey.app.KeyboardTest`,
then the same command with `-e mode typingfeatureslive`. The init script changes
only the test application IDs and signing, and does not replace the normal app.

Audit every learning path across private-field transitions. Do not publish
users' learned dictionaries. Thresholds and weights remain engineering choices
pending those measurements.

New feature strings cover all 40 app locales. Native-speaker review is still
needed, as for the existing translations.
