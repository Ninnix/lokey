<p align="center">
  <img src="app/src/main/res/drawable-nodpi/lokey_logo.png" alt="Lokey" width="220">
</p>

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=org.lokey.app">
    <img src="docs/google-play-badge.png" alt="Get it on Google Play" width="240">
  </a>
</p>

**Lokey** is an Android keyboard with local word prediction, dictation and
translation. The models run on your phone. Writing a message does not require
an account, a subscription or a connection to an inference service.

The keyboard is native Kotlin and Android Canvas, with a C11 bridge to the
inference runtimes. It supports **39 languages**, eleven keyboard families,
and a few small models chosen for work that belongs on a phone.

## Screenshots

| Word suggestions | Translation | Appearance |
| --- | --- | --- |
| <img src="docs/screenshots/local-word-suggestions.png" alt="Lokey keyboard suggesting words while typing" width="240"> | <img src="docs/screenshots/translate-as-you-type.png" alt="Lokey translating English text into Spanish" width="240"> | <img src="docs/screenshots/appearance.png" alt="Lokey keyboard themes and appearance settings" width="240"> |

## What you can do

* Get up to three complete-word suggestions, ranked from model probabilities.
* Hold Enter to dictate; slide up to keep recording or left to cancel.
* Translate into another app's text field with LFM or Qwen, using the same
  text model that supplies prediction and autocorrection.
* Use native script layouts, accent menus and bundled input converters.
* Change key colors, save designs, create layouts, and choose recorded switch
  sounds and haptic feedback.
* Keep copied text in a local clipboard, with pinning and deletion controls.

Swipe typing, adaptive letters and autocorrection are optional and start off.
Keyboard height starts at 100%. Settings you have already chosen are preserved.

## Start here

Lokey requires **Android 9 or later and an ARM64 device**. Install it from
[Google Play](https://play.google.com/store/apps/details?id=org.lokey.app) or download the signed
[APK from Releases](https://github.com/Ninnix/lokey/releases), open Lokey, and
follow setup to enable and select the keyboard. Choose your language and download the models you want. Ordinary
keyboard input works without downloading a model.

The interface follows Android's language setting. Android 13 and later also
provide a separate app-language setting. Keyboard layout and model language
remain separate choices. See [supported languages](docs/LANGUAGES.md).

## Models

| Model | Use | Approximate download |
| --- | --- | --- |
| LFM 2.5 · 350M | Prediction, autocorrection and translation | 230 MB |
| Qwen 3.5 · 0.8B | Prediction, autocorrection and translation | 533 MB |
| Parakeet v3 · 0.6B | Dictation in 25 languages | 418 MB |
| Qwen3 ASR · 0.6B | Dictation in 30 languages | 1.02 GB, including its encoder |

Lokey uses one active text model for prediction, autocorrection and translation,
and one speech model for dictation. An LFM-only installation uses LFM for all
three text functions; Qwen is not required. Automatic chooses this pair for
your language and hardware.
Supported Qualcomm Hexagon devices can use the NPU; CPU execution remains
available. Model files are downloaded separately from the app.

[MODELS.md](docs/MODELS.md) explains selection, formats, memory reuse and limits.

## Build

Use JDK 17 or later, Android SDK 37, NDK 28.2.13676358 and CMake 3.31.6.

```sh
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
The [build guide](docs/BUILD.md) covers runtime checkouts and optional Hexagon
support. The [release guide](docs/RELEASING.md) covers signing and distribution.

## Privacy

Prediction, translation and speech recognition run locally. The app has no
advertising or analytics SDK. Clipboard history, settings, custom designs and
speed-test records use Android's private app storage.

Model downloads contact Hugging Face and its delivery providers, which receive
network request information such as your IP address. Text and microphone audio
are not part of those requests. Opening an external link or sharing a result
uses the app you choose.

Read the [privacy policy](docs/PRIVACY.md) and the developer
[Data Safety disclosure](docs/DATA-SAFETY.md).

## Status and documentation

Lokey is beta software. Device checks cover the OnePlus 15 and POCO F3;
performance and model quality vary with the phone and language. Interface
translations still need full native-speaker review.

* [Keyboard use and customization](docs/KEYBOARD.md)
* [Supported languages](docs/LANGUAGES.md)
* [Language and keyboard mapping](docs/LANGUAGE-LAYOUTS.md)
* [Models and inference](docs/MODELS.md)
* [Building](docs/BUILD.md), [testing](docs/TESTING.md), and [releasing](docs/RELEASING.md)

## Credits

Lokey uses the [Loina core](https://github.com/Loina-AI/loina-core),
[llama.cpp](https://github.com/Loina-AI/llama.cpp), and
[whisper.cpp](https://github.com/Loina-AI/whisper.cpp) forks. Their exact revisions
are pinned in [runtimes.json](runtimes.json).

Models, dictionaries, recordings, icons and artwork have their own licenses.
Attribution and license texts are bundled in the app, under Models → Models &
inference credits and Feel → Input & design credits, and in
[the assets directory](app/src/main/assets).

## Contributing and license

See [CONTRIBUTING.md](CONTRIBUTING.md) for correctness checks, performance
comparisons and useful bug reports. Application code is released under the
[MIT license](LICENSE). Third-party runtimes, model weights and bundled assets
retain their own licenses and notices.
