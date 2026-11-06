# Language and keyboard mapping

Every one of the 39 languages listed by Parakeet v3 or Qwen3 ASR has an explicit code, default script, and an implemented input method. Broader text-model coverage does not expand Lokey’s supported-language list. Available means the audited main alphabet or a working script converter is present; it does not promise equal prediction quality or every regional typing convention.

Script defaults and main character inventories use [Unicode CLDR 49](https://github.com/unicode-org/cldr/tree/release-49). Language codes use ISO 639. Regional scripts can differ from the default shown here. A parent-language inventory is evidence for its shared script, not proof of a dialect's complete spelling system.

QWERTY Intl appears before QWERTY Accent. Plain Latin alphabets use Intl; accented Latin alphabets share Accent unless a regional layout or composing method is more appropriate. Common accent menus are retained only where a mapped language uses the character. Additional alphabet characters are offered for the selected language.

## Complete mapping

| Language | Code | Script | Target keyboard | Status | Notes |
| --- | --- | --- | --- | --- | --- |
| Arabic | ar | Arab | Arabic script | available |  |
| Bulgarian | bg | Cyrl | Cyrillic | available |  |
| Cantonese | yue | Hant | Jyutping / Cangjie / Sucheng | available | Traditional characters; optional Jyutping tones, or shape codes |
| Chinese | zh | Hans | Chinese Pinyin | available | Pinyin currently produces simplified characters |
| Croatian | hr | Latn | QWERTZ | available |  |
| Czech | cs | Latn | QWERTZ | available |  |
| Danish | da | Latn | QWERTY Accent | available |  |
| Dutch | nl | Latn | QWERTY Accent | available |  |
| English | en | Latn | QWERTY Intl | available |  |
| Estonian | et | Latn | QWERTY Accent | available |  |
| Filipino | fil | Latn | QWERTY Accent | available |  |
| Finnish | fi | Latn | QWERTY Accent | available |  |
| French | fr | Latn | AZERTY | available |  |
| German | de | Latn | QWERTZ | available |  |
| Greek | el | Grek | Greek | available |  |
| Hindi | hi | Deva | Devanagari InScript | available | InScript, Roman transliteration, or Bolnagri; ृ is a flick/hold on र in InScript |
| Hungarian | hu | Latn | QWERTZ | available |  |
| Indonesian | id | Latn | QWERTY Intl | available |  |
| Italian | it | Latn | QWERTY Accent | available |  |
| Japanese | ja | Jpan | Japanese Romaji | available |  |
| Korean | ko | Kore | Korean Hangul | available |  |
| Latvian | lv | Latn | QWERTY Accent | available |  |
| Lithuanian | lt | Latn | QWERTY Accent | available |  |
| Macedonian | mk | Cyrl | Cyrillic | available |  |
| Malay | ms | Latn | QWERTY Intl | available |  |
| Maltese | mt | Latn | QWERTY Accent | available |  |
| Persian | fa | Arab | Arabic script | available |  |
| Polish | pl | Latn | QWERTY Accent | available |  |
| Portuguese | pt | Latn | Portuguese QWERTY | available |  |
| Romanian | ro | Latn | QWERTY Accent | available |  |
| Russian | ru | Cyrl | Cyrillic | available |  |
| Slovak | sk | Latn | QWERTZ | available |  |
| Slovenian | sl | Latn | QWERTZ | available |  |
| Spanish | es | Latn | Spanish QWERTY | available |  |
| Swedish | sv | Latn | QWERTY Accent | available |  |
| Thai | th | Thai | Thai Kedmanee | available |  |
| Turkish | tr | Latn | Turkish QWERTY | available |  |
| Ukrainian | uk | Cyrl | Cyrillic | available |  |
| Vietnamese | vi | Latn | Vietnamese Telex | available |  |

## Common accent audit

Removed from common menus: ō ŷ ŵ ẁ ẃ ẅ ð ĝ ĥ ĵ þ. These have no main-alphabet witness in the audited catalog; this is not a claim about all languages outside the catalog.

| Key | Character | Mapped languages using it |
| --- | --- | --- |
| a | à | French, Italian, Maltese, Portuguese, Swedish, Vietnamese |
| a | á | Czech, Dutch, Hungarian, Portuguese, Slovak, Spanish, Vietnamese |
| a | â | French, Portuguese, Romanian, Vietnamese |
| a | ä | Dutch, Estonian, Finnish, German, Slovak, Swedish |
| a | ã | Portuguese, Vietnamese |
| a | å | Danish, Finnish, Swedish |
| a | æ | Danish, French |
| a | ā | Latvian |
| a | ą | Lithuanian, Polish |
| a | ă | Romanian, Vietnamese |
| e | è | French, Italian, Maltese, Vietnamese |
| e | é | Czech, Dutch, French, Hungarian, Italian, Portuguese, Slovak, Spanish, Swedish, Vietnamese |
| e | ê | French, Portuguese, Vietnamese |
| e | ë | Dutch, French |
| e | ē | Latvian |
| e | ė | Lithuanian |
| e | ę | Lithuanian, Polish |
| e | ě | Czech |
| i | ì | Italian, Maltese, Vietnamese |
| i | í | Czech, Dutch, Hungarian, Portuguese, Slovak, Spanish, Vietnamese |
| i | î | French, Romanian |
| i | ï | Dutch, French |
| i | ī | Latvian |
| i | į | Lithuanian |
| i | ı | Turkish |
| i | İ | Turkish |
| o | ò | Italian, Maltese, Vietnamese |
| o | ó | Czech, Dutch, Hungarian, Polish, Portuguese, Slovak, Spanish, Vietnamese |
| o | ô | French, Portuguese, Slovak, Vietnamese |
| o | ö | Dutch, Estonian, Finnish, German, Hungarian, Swedish, Turkish |
| o | õ | Estonian, Portuguese, Vietnamese |
| o | ø | Danish |
| o | œ | French |
| o | ő | Hungarian |
| u | ù | French, Italian, Maltese, Vietnamese |
| u | ú | Czech, Dutch, Hungarian, Portuguese, Slovak, Spanish, Vietnamese |
| u | û | French |
| u | ü | Dutch, Estonian, French, German, Hungarian, Spanish, Turkish |
| u | ū | Latvian, Lithuanian |
| u | ů | Czech |
| u | ű | Hungarian |
| u | ų | Lithuanian |
| c | ç | French, Portuguese, Turkish |
| c | ć | Croatian, Polish |
| c | č | Croatian, Czech, Latvian, Lithuanian, Slovak, Slovenian |
| c | ċ | Maltese |
| n | ñ | Filipino, Spanish |
| n | ń | Polish |
| n | ň | Czech, Slovak |
| n | ņ | Latvian |
| s | ß | German |
| s | ś | Polish |
| s | š | Croatian, Czech, Estonian, Finnish, Latvian, Lithuanian, Slovak, Slovenian |
| s | ș | Romanian |
| s | ş | Turkish |
| y | ý | Czech, Slovak, Vietnamese |
| y | ÿ | French |
| d | ď | Czech, Slovak |
| d | đ | Croatian, Vietnamese |
| g | ğ | Turkish |
| g | ģ | Latvian |
| g | ġ | Maltese |
| h | ħ | Maltese |
| k | ķ | Latvian |
| l | ł | Polish |
| l | ĺ | Slovak |
| l | ľ | Slovak |
| l | ļ | Latvian |
| r | ŕ | Slovak |
| r | ř | Czech |
| t | ť | Czech, Slovak |
| t | ț | Romanian |
| z | ž | Croatian, Czech, Estonian, Finnish, Latvian, Lithuanian, Slovak, Slovenian |
| z | ź | Polish |
| z | ż | Maltese, Polish |

The audit uses main alphabets, rather than importing entire auxiliary repertoires, and includes combining sequences such as Dutch j́. A mapping marked review has no verified CLDR inventory. Script-specific coverage is reported separately from text-model coverage.
