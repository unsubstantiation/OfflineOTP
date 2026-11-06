# Signed release APKs

Release signing is **opt-in**. The Android workflow always builds and uploads the F-Droid debug
APK. The separate `sign-release` job builds signed F-Droid and Play release APKs **only when the
release keystore is configured**; otherwise it is skipped with a notice and the workflow stays
green. This is why an unconfigured repository no longer shows a red Android build.

The credentials live as **repository secrets** (previously the `android-release` environment):

| Where | Name | Required | Contents |
| --- | --- | --- | --- |
| Repository secret | `ANDROID_KEYSTORE_BASE64` | yes | Base64 of the `.jks` keystore (single line or wrapped) |
| Repository secret | `ANDROID_KEYSTORE_PASSWORD` | yes | Keystore password; also used as the key password |
| Repository secret | `ANDROID_RELEASE_KEY_ALIAS` | no | Names the key when the keystore holds more than one; otherwise read from the keystore |
| Repository secret | `ANDROID_RELEASE_KEY_PASSWORD` | no | Only when the key password differs from the store password |

`ANDROID_RELEASE_KEYSTORE`, `ANDROID_RELEASE_STORE_PASSWORD` and `ANDROID_RELEASE_KEY_PASSWORD` are
still read as fallbacks, so a setup made with the older names keeps working. Two secrets are enough
for the common case, because the key alias is not a secret: the job reads it out of the keystore
with `keytool -list`. The workflow no longer creates GitHub Deployments — artifacts are downloaded
from **Actions** (see below).

There are two independent things to keep apart:

- `app/build.gradle` signs `release` only when `-PRELEASE_STORE_FILE` **and**
  `-PRELEASE_KEY_ALIAS` are present. Without them, `assemble*Release` still produces *unsigned*
  APKs, which is what local runs and `scripts/verify_reproducible_build.sh` rely on.
- The workflow decides whether to pass those properties at all, based on the environment secrets.

## "The release was not signed / the action was skipped"

If a run on `master` shows `sign-release` as **skipped** (or the log says
`ANDROID_KEYSTORE_BASE64 is not configured`), the keystore secret is not visible to the job. The
workflow deliberately does not fall back to a debug or unsigned APK for a release artifact, and the
advisory is only a notice, so the run stays green.

Check, in order:

1. Both secrets are set as repository secrets (Settings → Secrets and variables → Actions).
2. The run is on `master` (or a `workflow_dispatch` of `master`).
3. `ANDROID_KEYSTORE_BASE64` holds the base64 of the whole `.jks` file. A base64 of the `.b64` text
   or of a truncated file decodes to something `keytool` cannot open.

Diagnostics the job prints before failing:

| Message | Meaning |
| --- | --- |
| `::error::ANDROID_KEYSTORE_BASE64 is not configured` | The secret is empty or invisible to the job |
| `::error::ANDROID_KEYSTORE_BASE64 is not valid base64` | The payload is not base64 |
| `::error::The decoded release keystore is empty` | The payload decoded to zero bytes |
| `::error::ANDROID_KEYSTORE_PASSWORD is not configured` | Keystore set, password missing |
| `::error::keytool could not open the release keystore …` | Wrong password, or the base64 is not a keystore |
| `::error::The release keystore holds no private key …` | The keystore opens but contains no signing key |
| `::error::The release keystore holds N private keys; set ANDROID_RELEASE_KEY_ALIAS to name one` | Several keys, so the alias cannot be inferred |

Every published release so far carries **debug** builds — the uploaded `OfflineOTP-android-debug`
artifact and the `.apk` on `v2026-10-21`, for example. They are signed with the runner's ephemeral
key and use the `.dev` application ID, so they cannot be installed over a release install
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) and different runs have different keys.

## Creating a keystore (once)

```sh
keytool -genkeypair -v \
  -keystore OfflineOTP-release-keystore.jks \
  -alias offlineotp \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$STORE_PASSWORD" -keypass "$STORE_PASSWORD" \
  -dname "CN=OfflineOTP"
```

Base64-encode it for the secret (GNU coreutils; on macOS use `base64 -i`):

```sh
base64 -w0 OfflineOTP-release-keystore.jks > OfflineOTP-release-keystore.jks.b64
```

Keep the `.jks` file and the password in an offline password manager. GitHub cannot return a secret,
and losing the key means no future update can be installed over the same app.

## Setting the secrets

```sh
gh secret set ANDROID_KEYSTORE_BASE64 --repo <owner>/<repo> < OfflineOTP-release-keystore.jks.b64
gh secret set ANDROID_KEYSTORE_PASSWORD --repo <owner>/<repo>
```

The optional pieces, only if your keystore needs them:

```sh
gh secret set ANDROID_RELEASE_KEY_ALIAS --repo <owner>/<repo>
gh secret set ANDROID_RELEASE_KEY_PASSWORD --repo <owner>/<repo>
```

Never commit the keystore, the `.b64` file, or the passwords. `/signing/`, `*.jks` and `*.keystore`
are ignored by Git for that reason.

## What the job produces

With the secrets configured, a push to `master` (or a manual `workflow_dispatch` of `master`)
produces:

- `OfflineOTP-fdroid-release.apk` and `OfflineOTP-play-release.apk`, both signed with the release
  key;
- `SHA256SUMS.txt`.

Before uploading, the job verifies each APK with `apksigner verify --print-certs` and
`zipalign -c -v 4`. Pull requests never run the job, so fork PRs cannot trigger a signing attempt
and secrets are never exposed to untrusted code. The workflow only uploads artifacts; it does not
create GitHub releases or store listings — publishing a release is a manual step, and its assets
should come from the signed run, not from an earlier debug build.

## How the credentials reach Gradle

The store password, key alias and key password reach Gradle as `ORG_GRADLE_PROJECT_RELEASE_*`
environment variables, so they never appear on the command line (`ps` output, process titles) and
never pass through shell quoting: a password containing `"`, `\`, `$`, a backtick or a space works
as-is. Only the keystore path is an ordinary `-P` argument, because it is not a secret. Gradle
masks nothing itself, so the step writes the values through `$GITHUB_ENV` rather than
interpolating a `secrets` context into a script, and masks the alias before it is echoed. The alias
lookup passes the password as `-storepass:env`, never as an argument, so it does not appear in `ps`
either.

The decoded keystore is deleted in an `always()` step, so it does not outlive the job even when a
later step fails and the workspace is kept for debugging. A keystore supplied without its password
fails before any APK is built: the job names the missing secret, and `app/build.gradle` throws a
`GradleException` naming the property, so a local attempt fails the same way. The `release`
`signingConfig` now has `enableV1Signing`/`V2`/`V3`/`V4` all `true`, so the APK is verifiable on
every API level the `minSdk 28` allows.

## Verifying a downloaded APK

```sh
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs OfflineOTP-fdroid-release.apk
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging OfflineOTP-fdroid-release.apk | grep -E "package:|application:"
sha256sum -c SHA256SUMS.txt
```

The printed certificate DN (`CN=OfflineOTP` above) must match the key in the keystore. Installing a
build signed with a different key over an existing install fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`; users must export a portable backup, uninstall, and restore.

Release builds use `com.fairuniversal.offlineotp` — a new package that does **not** collide with the upstream andOTP app (`org.shadowice.flocke.andotp`) from F-Droid/Play. Debug builds use `com.fairuniversal.offlineotp.dev` and also do not collide. If a signed release still shows **Update** instead of **Install**, it is colliding with a previous OfflineOTP release install (same `com.fairuniversal.offlineotp` but different signing key) or a lingering install:

```sh
adb shell pm list packages --user all | grep offlineotp
adb shell pm list packages -u | grep offlineotp
adb shell dumpsys package com.fairuniversal.offlineotp | head -n 40
```

An entry there — often in a work profile, secondary user, or as a disabled system app — must be
removed first (`adb uninstall --user all com.fairuniversal.offlineotp` or `adb shell pm uninstall
--user 0 com.fairuniversal.offlineotp`), after exporting a backup. A leftover upstream andOTP install (`org.shadowice.flocke.andotp`) no longer collides, but installing a build signed with a different key over an existing OfflineOTP install still fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Installing with `adb install` prints the exact reason:

```sh
adb install OfflineOTP-fdroid-release.apk
adb logcat | grep -i -E "PackageManager|offlineotp"
```

The CI `Verify APK signatures` step now dumps `aapt2 dump badging` (package, `testOnly`, `uses-sdk`)
and `apksigner verify --verbose` (v1/v2/v3/v4) for every release APK, and fails the job if the
package is not `com.fairuniversal.offlineotp` or the APK is `testOnly`.
