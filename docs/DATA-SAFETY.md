# Google Play Data Safety

Prepared November 13, 2026 for Lokey (`org.lokey.app`), version 0.1.4.
Developer: Nicolo' D'Evangelista (Ninnix), nicolo.deva@gmail.com.

These are the answers prepared for the Play Console form. They have not been
submitted. The form must describe every app version still distributed on Play;
recheck it when adding an SDK, network destination, upload or account feature.

## Form answers

The app processes typing and audio locally, but its optional model downloads
contact an independent third-party host. The answers below conservatively
include the host's disclosed processing of request metadata. They do not
claim that local keyboard content is uploaded.

| Question | Prepared answer |
| --- | --- |
| Does the app collect or share any of the listed user data types? | Yes: optional model-download request metadata, described below. |
| Is data encrypted in transit? | Yes. Downloads use HTTPS; cleartext traffic is disabled. |
| Can users create an account in the app? | No. There is no Lokey account or sign-in. |
| Can users request deletion? | Requests can be sent to nicolo.deva@gmail.com. Local data can be removed in the app or Android settings. Download-host logs are held by the host; its privacy request process applies to those logs. |
| Account-deletion URL | Not applicable: no account creation. |
| Privacy-policy URL | Use the public, published URL of [PRIVACY.md](PRIVACY.md). Do not enter a local file path or a URL that has not been published. |
| Independent security review / certification | Do not claim a certification. |

The policy URL is https://github.com/Ninnix/lokey/blob/main/docs/PRIVACY.md. Use this public URL in Play Console after confirming that it is accessible without signing in.

## Data types for model downloads

Hugging Face's policy describes automatically recorded IP, usage, location and
device information. Its policy does not give Lokey a guarantee that download
logs are anonymous or immediately erased. The classification here is a
conservative interpretation of that policy, not a packet-level verification
of the host's internal logging.

| Data type | Collected | Shared | Optional | Purposes | Ephemeral |
| --- | --- | --- | --- | --- | --- |
| Device or other IDs: IP address accompanying a model request | Yes, by the download host | Yes, to the independent host and its delivery providers | Yes: users can skip model downloads | App functionality; the host also describes analytics and security uses | No claim of ephemeral processing |
| App activity → App interactions: requested model/file and download request timing | Yes, by the download host | Yes | Yes | App functionality; host analytics and security | No claim of ephemeral processing |
| Location → Approximate location: location associated with a download session, if derived from its IP | Included conservatively because the host discloses session location | Yes | Yes | Host analytics and security; not used by Lokey to choose suggestions | No claim of ephemeral processing |

Do not declare precise location. Lokey requests no location permission, does
not derive a location from IP, and does not use location for prediction.
If the host provides a narrower, download-specific statement, update the
metadata answers to match that evidence. Do not assume that a user's tap on
Download proves all later host processing is exempt from disclosure.

## Data kept on the device

| Data | Access and purpose | Off-device collection by Lokey |
| --- | --- | --- |
| Nearby editor text, typed input, translation drafts and results | Suggestions, optional correction, translation, and insertion into the chosen editor | No model-service or developer upload. Inserting text into the receiving app is the keyboard action the user requested. |
| Audio | Microphone recording and local transcription | No audio upload or saved recording file. |
| Clipboard text | Local recent/pinned history and user-directed paste | No server upload. |
| Images and custom designs | Local theme editing and saved designs | No upload. |
| Settings, language choices and custom layouts | Local app behavior | No upload. |
| Optional learned words and confirmed typo pairs | Encrypted, bounded local counts for suggestions and correction, including without downloaded models; off by default | No upload, synchronization or remote training. |
| Speed-test records | Local score history | No automatic upload. A score image can be shared through Android's user-directed share sheet. |
| App search text | Filtering languages and layouts | No upload. |
| Crash logs and performance telemetry | No embedded reporting service | No automatic developer upload. Android or the app store may have separate diagnostics. |
| Email sent to the developer | Support/privacy correspondence sent by the user's email app | Not an automatic in-app upload; the privacy policy covers correspondence. |

Google's guidance excludes solely on-device processing from collection and
provides an exception for reasonably expected user-directed transfers. That
supports the local-content and explicit paste/share treatment above. Network
metadata still needs its own assessment.

## Retention and deletion

Unpinned clipboard entries are pruned on history access/update after one hour;
up to 40 are retained. Up to 100 pinned entries remain until deleted. Settings,
models, custom layouts/designs and score records persist in private app
storage. Clear app storage or uninstall to remove these files. Shared copies
in other apps are outside Lokey's control.

The optional learning profile is capped at 1,024 aggregate entries and encrypted
with an Android Keystore key in storage excluded from backup. Entries unseen
for over 90 days are pruned on access. Turning learning off or choosing Erase
learned data deletes the profile and its key. Private and no-learning editor
flags are honored; ordinary words entered in normal fields can still be learned.

The developer does not run the model file host and cannot inspect or erase
its logs directly. Use the host's privacy request channel for those logs;
contact the developer for help routing a request. Do not promise immediate
or unconditional deletion of third-party security logs.

## Source review

* `app/src/main/java/org/lokey/app/Downloads.kt`: HTTPS model file requests,
  byte-range resume headers, and persistent download queue.
* `Models.kt`: the curated Hugging Face download URLs and matching encoders.
* `Voice.kt`, `Translation.kt`, `Prediction.kt`, and `app/src/main/c/lokey.c`:
  local model execution and working state.
* `ClipboardHistory.kt` and `LokeyService.kt`: clipboard limits, sensitive-clip
  filtering, private-field handling and insertion into the selected editor.
* `Prefs.kt`, `DesignPresets.kt`, `CustomLayouts.kt`, `SpeedRun.kt` and
  `ScoreCard.kt`: local storage and explicit score-image sharing.
* `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/data_extraction.xml`,
  and `app/build.gradle.kts`: permissions, backup exclusion and dependencies.

Policy references: [Google Play Data Safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469),
[Google Play User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311),
and [Hugging Face's privacy policy](https://huggingface.co/privacy).
