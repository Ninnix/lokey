# Lokey supported languages

Lokey supports the **39 languages listed by Parakeet v3 or Qwen3 ASR**. Broader text-model coverage does not add languages to the app’s keyboard or translation pickers.

Parakeet lists 25 languages and Qwen ASR lists 30; their union is 39. Sources: [Parakeet v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3), [Qwen3 ASR](https://huggingface.co/Qwen/Qwen3-ASR-0.6B).

LFM lists 9 of these languages; Qwen text covers the complete supported set. Sources: [LFM2.5](https://huggingface.co/LiquidAI/LFM2.5-350M), [Qwen3.5](https://huggingface.co/Qwen/Qwen3.5-0.8B). On CPU devices, prefer LFM where listed and Parakeet where listed; Chinese uses both Qwen models. The suggested-text column below describes these CPU choices.

With supported NPU acceleration, Automatic prefers Qwen text for all 39 languages and Qwen ASR for its 30 listed languages. The nine Parakeet-only languages retain Parakeet: Bulgarian, Croatian, Estonian, Latvian, Lithuanian, Maltese, Slovak, Slovenian and Ukrainian. Automatic uses Lokey's setup language, defaulting to the device language, and can fall back to an installed model supporting that language.

## Complete supported list

| Language | Keyboard | Character coverage | Parakeet | Qwen ASR | Suggested text |
| --- | --- | --- | --- | --- | --- |
| Arabic | Arabic script | available | — | Yes | LFM |
| Bulgarian | Cyrillic | available | Yes | — | Qwen |
| Cantonese | Jyutping / Cangjie / Sucheng | available | — | Yes | Qwen |
| Chinese | Chinese Pinyin | available | — | Yes | Qwen |
| Croatian | QWERTZ | available | Yes | — | Qwen |
| Czech | QWERTZ | available | Yes | Yes | Qwen |
| Danish | QWERTY Accent | available | Yes | Yes | Qwen |
| Dutch | QWERTY Accent | available | Yes | Yes | Qwen |
| English | QWERTY Intl | available | Yes | Yes | LFM |
| Estonian | QWERTY Accent | available | Yes | — | Qwen |
| Filipino | QWERTY Accent | available | — | Yes | Qwen |
| Finnish | QWERTY Accent | available | Yes | Yes | Qwen |
| French | AZERTY | available | Yes | Yes | LFM |
| German | QWERTZ | available | Yes | Yes | LFM |
| Greek | Greek | available | Yes | Yes | Qwen |
| Hindi | InScript / Roman / Bolnagri | available | — | Yes | Qwen |
| Hungarian | QWERTZ | available | Yes | Yes | Qwen |
| Indonesian | QWERTY Intl | available | — | Yes | Qwen |
| Italian | QWERTY Accent | available | Yes | Yes | Qwen |
| Japanese | Japanese Romaji | available | — | Yes | LFM |
| Korean | Korean Hangul | available | — | Yes | LFM |
| Latvian | QWERTY Accent | available | Yes | — | Qwen |
| Lithuanian | QWERTY Accent | available | Yes | — | Qwen |
| Macedonian | Cyrillic | available | — | Yes | Qwen |
| Malay | QWERTY Intl | available | — | Yes | Qwen |
| Maltese | QWERTY Accent | available | Yes | — | Qwen |
| Persian | Arabic script | available | — | Yes | Qwen |
| Polish | QWERTY Accent | available | Yes | Yes | Qwen |
| Portuguese | Portuguese QWERTY | available | Yes | Yes | LFM |
| Romanian | QWERTY Accent | available | Yes | Yes | Qwen |
| Russian | Cyrillic | available | Yes | Yes | Qwen |
| Slovak | QWERTZ | available | Yes | — | Qwen |
| Slovenian | QWERTZ | available | Yes | — | Qwen |
| Spanish | Spanish QWERTY | available | Yes | Yes | LFM |
| Swedish | QWERTY Accent | available | Yes | Yes | Qwen |
| Thai | Thai Kedmanee | available | — | Yes | Qwen |
| Turkish | Turkish QWERTY | available | — | Yes | Qwen |
| Ukrainian | Ukrainian Cyrillic | available | Yes | — | Qwen |
| Vietnamese | Vietnamese Telex | available | — | Yes | Qwen |

## Keyboard coverage and familiarity

All 39 mappings now have an implemented input method and pass the main-character audit. This checks character access and conversion, not equal prediction or transcription quality. The picker groups 23 variants into eleven families.

- Hindi has InScript, Roman transliteration, and Bolnagri. InScript keeps its familiar primary rows; flick र upward for ृ and hold for ऋ. The omitted vowel forms are available on related keys. Roman input uses a bundled, ranked [Dakshina](https://github.com/google-research-datasets/dakshina) lexicon with deterministic Devanagari composition for other words. Space accepts a word and inserts a space.
- Thai includes ๎ on the held ื key, alongside existing shifted marks.
- Ukrainian includes the modifier apostrophe ʼ on held ь.
- Cantonese has [Jyutping](https://github.com/rime/rime-cantonese), [Cangjie 5](https://github.com/rime/rime-cangjie), and Sucheng (first/last Cangjie code). Jyutping accepts optional tones 1–6: `neihou` or `nei5hou2` → 你好. Conversion runs locally without an LLM. Space accepts; the prediction arrow pages through candidates. The shape layouts show radical hints and can also be used for Chinese.

Some users will still need to adapt: Nordic letters use held/flicked alternatives, several Central European languages share a general QWERTZ, Bulgarian/Macedonian share Russian primary positions, Japanese uses Romaji rather than kana flick, and Korean uses Dubeolsik rather than a phone keypad. Adding the Hindi and Cantonese variants addresses those input-method preferences without claiming every regional layout is present.

Chinese currently uses Simplified Pinyin. The [full mapping and accent audit](LANGUAGE-LAYOUTS.md) documents the source data and every retained common character.

## QWERTY accents

QWERTY Intl appears first and uses dead keys. QWERTY Accent shares 75 useful characters across the supported languages, with the active language’s characters prioritized. Eleven old common-menu characters are outside the audited main alphabets: ō ŷ ŵ ẁ ẃ ẅ ð ĝ ĥ ĵ þ.

The visible layout families stay grouped. Changing model or keyboard preferences remains possible after setup.

## Interface language

The app bundles translations for all 39 languages above, plus a Traditional Chinese variant. Android chooses the interface language from the system setting, or its per-app override on Android 13 and later. Unsupported locales use English. This does not change the chosen keyboard or model language.

Interface text, accessibility labels and keyboard hints use Android resources. Clipboard contents, custom names and text you enter are preserved. The English privacy policy is available offline from Models and setup. Native-speaker review of the interface translations remains pending.
