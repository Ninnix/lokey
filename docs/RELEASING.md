# Releasing Lokey

Commands below run from the repository root. The package is `org.lokey.app`.
The current release is version 0.1.4, version code 5. Increase the version code
before uploading a later release to Play.

For the 0.1.4 Play update, upload the signed AAB to the existing Lokey listing.
It retains `org.lokey.app`, raises version code 4 to 5, and uses the existing
upload certificate. Play App Signing continues to sign delivered updates with
the app-signing key registered for that listing.

## Signing key

Create an upload key once:

```sh
python3 tools/setup-signing.py
```

The helper uses the JDK's `keytool`, RSA 4096 and a PKCS12 keystore. It generates
a random password without printing it or passing it in a command-line argument.
The files are kept in the ignored directory `.local/signing/`:

* `upload.p12`: private upload key.
* `release.properties`: keystore location, alias and passwords.
* `upload-certificate.pem`: public certificate, for registration in Play Console.

**Back up the entire directory securely before distributing an APK or uploading
an AAB.** Losing a key used for direct APK distribution can prevent updates to
those installations. The helper refuses to overwrite an existing key. Do not
attach the private files to an issue, put them in Git, or share them as build
artifacts. File and directory permissions restrict access to their owner.

For a separately managed keystore, create a private properties file containing
`storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. `storeFile` is an
absolute path or a path relative to the repository root. The keystore must be
PKCS12. Select the properties file with
`-PlokeySigningProperties=/absolute/path/release.properties`. CI should restore
these files from its secret store; do not create a new key on each build.

## Build

```sh
./gradlew :app:assembleRelease :app:bundleRelease
```

Release builds fail if signing is not configured. Debug builds do not require
a release key. The outputs are:

* `app/build/outputs/apk/release/app-release.apk`
* `app/build/outputs/bundle/release/app-release.aab`

Use the runtime and optional Hexagon settings described in [BUILD.md](BUILD.md).
Do not distribute a phone-specific DSP kernel as support for all Hexagon
versions. Unsupported devices must retain CPU fallback.

## Verify

With Android SDK Build Tools on your path:

```sh
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
jarsigner -verify app/build/outputs/bundle/release/app-release.aab
```

The signer must match the certificate you retain in `.local/signing/`. A
self-signed certificate warning is expected for an Android upload certificate;
it is not a public TLS certificate. Also check native library alignment and
exercise the release on a 16 KB page-size device before claiming that runtime
configuration is tested.

A release APK has a different signature from the development APK. Android will
not install it as an update over a debug-signed copy. Keep the existing phone's
data intact; use a separate test device/profile for that installation check.

## Google Play

Use Play App Signing for store distribution. The AAB is signed locally with
the upload key; Play registers that public upload certificate and signs the
APKs it delivers with the app-signing key. For direct APK distribution, keep
using the same local signing key for updates. A Play-generated app-signing key
will generally differ from the direct APK key.

The repository prepares the binary and disclosures. Play Console enrollment,
key registration, policy-URL entry and form submission still happen in the
publisher's account.

* Publish [PRIVACY.md](PRIVACY.md) at a public HTTPS URL before entering it in
  Play Console. The app also includes the same policy offline, reachable from
  setup and Models. Do not use an unpublished branch or local path as a policy URL.
* Transfer the answers in [DATA-SAFETY.md](DATA-SAFETY.md) to the Data Safety form,
  checking all versions still active on Play. Include the optional download
  host metadata; local inference alone does not describe those connections.
* Confirm the store developer identity matches the policy contact, complete the
  listing/content declarations, and use a testing track before production.

The README links the policy source. Moving this file through Git does not
publish the policy or submit a Play form by itself.

References: [Android app signing](https://developer.android.com/studio/publish/app-signing),
[Play Data Safety](https://support.google.com/googleplay/android-developer/answer/10787469),
and [privacy-policy requirements](https://support.google.com/googleplay/android-developer/answer/10144311).
