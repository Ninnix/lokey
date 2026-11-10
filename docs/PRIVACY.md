# Lokey privacy policy

Effective date: November 10, 2026.

Lokey (`org.lokey.app`) is developed by Nicolo' D'Evangelista, also known as
Ninnix. For privacy questions or requests, contact nicolo.deva@gmail.com.
This policy describes the Android app. Websites and other apps you choose to
open have their own privacy policies.

## Typing, prediction and translation

As your selected keyboard, Lokey receives the text you enter and the nearby
editor context that Android and the receiving app make available. Lokey uses
that context to offer suggestions, apply optional autocorrection, and insert
text where you are writing. Translation processes the source text you choose
and inserts the result into the active app.

Prediction and translation run on your device. Lokey does not upload your
messages, editor context, translation source or translation output to the
developer, a model provider or an inference service. Prediction candidates,
model checkpoints and translation working state are held in memory rather
than saved as a typing history. Private/password fields identified through
Android's editor flags disable prediction, dictation and clipboard capture.

Adaptive letter sizes use only the current on-device model probabilities.
Lokey does not learn from or store touch positions, touch trails, calibration,
letter counts or typing history for this feature. Letter positions stay fixed
while likely keys can become wider. No personalization profile is saved.

Text you type, paste, dictate or translate is sent to the app in which you
are writing. That app decides how to store or transmit it. Lokey cannot
control the receiving app's data handling.

## Microphone

Dictation requires Android's microphone permission. Lokey records while you
use the recording controls, including a recording you explicitly lock on,
and processes the audio locally with the installed speech model. A recording
is limited to approximately one minute. You can stop or cancel it.

Audio is held in working memory. Lokey does not save microphone recordings
as audio files or upload them. The resulting text is inserted into the
active editor. You can revoke microphone access in Android's app settings
and continue using the keyboard without dictation.

## Clipboard

While Android permits clipboard access and Lokey is active outside a private
field, copied plain text can be added to Lokey's local clipboard history.
Entries marked sensitive by Android are excluded from automatic capture.
The clipboard does not leave the device through a Lokey server or model API.

Recent history holds up to 40 unpinned entries, each no longer than 8,000
characters. Unpinned entries older than one hour are pruned when history is
loaded, read or updated; this is not a scheduled secure-erasure guarantee.
Pinned entries, up to 100, remain until you delete them. You can delete
individual entries or clear recent entries from the Clipboard panel. Clearing
recent entries does not remove pins. Pasting sends the chosen text to the
receiving app.

## Settings, images and speed-test records

Lokey stores your preferences, selected languages and models, custom layouts,
saved designs and copies of images you choose in private app storage. Model
files, partial downloads and the download queue are also stored there.

The speed test stores scores and performance measures, such as speed,
accuracy and elapsed time, for its fixed practice passage. It does not store
an arbitrary transcript of your typing. If you choose Share result, Lokey
creates a score image in its cache and gives the app you select permission
to read that image. The selected app then handles the shared copy.

## Network connections and third parties

Downloading a model sends an HTTPS request to Hugging Face and its file
delivery providers. Requests identify the model file and may include standard
HTTP information such as your IP address, client software information, and
byte ranges needed to resume a download. These providers can process and
retain request information under their own policies. Hugging Face's policy
also describes automatically recorded usage, location, IP and device
information. See https://huggingface.co/privacy.

Model downloads do not include your typing, clipboard contents, images or
microphone recordings. You can use basic keyboard input without downloading
models. Downloads you start may resume after a network interruption or a
restart until you cancel them or they finish.

Opening a source, license-provider or other external website launches your
browser. Contacting the developer launches your email app if you choose to
use one. Those services receive the information you choose to send and
ordinary network metadata under their own policies.

Lokey includes no advertising SDK, advertising identifier access, analytics
SDK or automatic crash-upload service. It has no user accounts and does not
sell your typing or audio or use it to train a remote model. Android, Google
Play and other distribution services may separately process diagnostics or
installation information under their own settings and policies.

## Storage and security

Local files and preferences use Android's app storage protections. Lokey
opts out of Android cloud backup and automatic device-transfer backup.
The app does not add its own encryption layer to clipboard preferences or
custom designs; device security and access to your unlocked phone matter.
Model downloads use HTTPS and cleartext network traffic is disabled.

Working memory is released or reused as sessions and processes change. This
does not promise immediate overwriting of every memory buffer. Android may
retain local caches until it reclaims them or you clear the app's storage.

## Retention, deletion and your choices

Delete clipboard entries and saved designs or layouts using their controls.
To remove all of Lokey's local settings, clipboard history, images, models,
partial downloads and records, clear the app's storage in Android Settings
or uninstall Lokey. Android menu names vary by device. You will need to
configure the keyboard and download models again after clearing storage.

Deleting app data does not delete text already sent to another app, copies
of score images you shared, email you sent, or network logs held by download
providers. Contact the relevant recipient for those copies.

There is no Lokey account to delete and no developer-held database of your
typing or recordings. If you email the developer, the message, your email
address and any attachments are used to answer your request and retained
only as needed for that purpose and applicable legal obligations. You can
request deletion by emailing nicolo.deva@gmail.com. Do not include sensitive
text or recordings unless you intend to share them.

## Changes

Updates to this policy will be published with a new effective date and
bundled with app updates. Contact nicolo.deva@gmail.com with questions about
the policy or the app's handling of your data.
