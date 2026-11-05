# Signed release APKs

Release signing is **opt-in**. The Android workflow always builds and uploads the F-Droid debug
APK. The separate `sign-release` job builds signed F-Droid and Play release APKs **only when the
release keystore is configured**; otherwise it is skipped with a notice and the workflow stays
green. This is why an unconfigured repository no longer shows a red Android build.

## "The release was not signed / the action was skipped"

If a run on `master` shows `sign-release` as **skipped** (or the log says
`ANDROID_RELEASE_KEYSTORE is not configured`), nothing is broken: this repository has no release
keystore configured yet, and the workflow deliberately does not fall back to a debug or unsigned
APK for a release artifact. The advisory is only a notice, so the run stays green.

That also means no release asset has ever been signed: the uploaded `OfflineOTP-android-debug`
artifact and every asset on the published releases are debug builds, signed with the runner's
ephemeral key and carrying the `.dev` application ID. They are fine for trying the app, but they
cannot be installed over a release install
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and different runs have different keys.

To enable signing:

1. Create the keystore once, on a machine you control — [Creating a keystore](#creating-a-keystore-once)
   below — and keep the `.jks` file and both passwords in an offline password manager. They cannot
   be recovered from GitHub, and losing them means no future build can update the same install.
2. Base64-encode it and set the four repository secrets — [Setting the secrets](#setting-the-secrets).
3. Push to `master`, or run the **Android build** workflow manually (`workflow_dispatch`), and check
   that `sign-release` ran instead of being skipped. The job prints the certificate it verified.
4. Re-publish the release assets from that run, not from an earlier debug build. The workflows stop
   at uploading artifacts and never create a GitHub release themselves.

The preflight only tests `ANDROID_RELEASE_KEYSTORE`, so a keystore with a missing password fails
inside the signing job, where it names the secret that is absent
(`::error::ANDROID_RELEASE_STORE_PASSWORD is not configured …`) instead of failing inside Gradle with
`keystore password was incorrect`.

There are two independent things to keep apart:

- `app/build.gradle` signs `release` only when `-PRELEASE_STORE_FILE` **and**
  `-PRELEASE_KEY_ALIAS` are present. Without them, `assemble*Release` still produces *unsigned*
  APKs, which is what local runs and `scripts/verify_reproducible_build.sh` rely on.
- The workflow decides whether to pass those properties at all, based on the repository secrets.

## Required repository secrets

| Secret | Contents |
| --- | --- |
| `ANDROID_RELEASE_KEYSTORE` | Base64 of the `.jks` keystore file (single line or wrapped) |
| `ANDROID_RELEASE_STORE_PASSWORD` | Keystore password |
| `ANDROID_RELEASE_KEY_ALIAS` | Key alias inside the keystore |
| `ANDROID_RELEASE_KEY_PASSWORD` | Key password |

`ANDROID_RELEASE_KEYSTORE` drives the preflight check: when it is empty the `sign-release` job is
skipped. The other three are consumed only by that job, but a build will fail at signing time if
they are missing once the keystore is present.

The three credentials reach Gradle as `ORG_GRADLE_PROJECT_RELEASE_*` environment variables, so they
never appear on the command line (`ps` output, process titles) and never pass through shell quoting:
a password containing `"`, `\`, `$`, a backtick or a space works as-is. Only the keystore path is an
ordinary `-P` argument, because it is not a secret. Every value is masked in the log by the runner
and the workflow never echoes any of them.

## Creating a keystore (once)

```sh
keytool -genkeypair -v \
  -keystore OfflineOTP-release-keystore.jks \
  -alias offlineotp \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$STORE_PASSWORD" -keypass "$KEY_PASSWORD" \
  -dname "CN=OfflineOTP"
```

Base64-encode it for the secret (GNU coreutils; on macOS use `base64 -i`):

```sh
base64 -w0 OfflineOTP-release-keystore.jks > OfflineOTP-release-keystore.jks.b64
```

## Setting the secrets

```sh
gh secret set ANDROID_RELEASE_KEYSTORE --repo <owner>/<repo> < OfflineOTP-release-keystore.jks.b64
gh secret set ANDROID_RELEASE_STORE_PASSWORD --repo <owner>/<repo>
gh secret set ANDROID_RELEASE_KEY_ALIAS --repo <owner>/<repo>
gh secret set ANDROID_RELEASE_KEY_PASSWORD --repo <owner>/<repo>
```

Or add them under **Settings → Secrets and variables → Actions**. Secrets are not readable back, so
keep the keystore file and both passwords in an offline password manager; losing them means no
future update can be signed with the same identity. The workflow re-checks that the payload is
valid base64 and non-empty, fails closed on a malformed secret, and never echoes the keystore or
the passwords into the log.

Never commit the keystore, the `.b64` file, or the passwords. `/signing/`, `*.jks` and `*.keystore`
are ignored by Git for that reason.

## What the job produces

With the secrets configured, a push to `master` (or a manual `workflow_dispatch`) produces:

- `OfflineOTP-fdroid-release.apk` and `OfflineOTP-play-release.apk`, both signed with the release
  key;
- `SHA256SUMS.txt`.

Before uploading, the job verifies each APK with `apksigner verify --print-certs` and
`zipalign -c -v 4`. Pull requests never run the job, so fork PRs cannot trigger a signing attempt
and secrets are never exposed to untrusted code. The workflow only uploads artifacts; it does not
create GitHub releases or store listings. The decoded keystore is deleted in an `always()` step, so
it does not outlive the job even when a later step fails and the workspace is kept for debugging.

## Verifying a downloaded APK

```sh
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs OfflineOTP-fdroid-release.apk
sha256sum -c SHA256SUMS.txt
```

The printed certificate DN (`CN=OfflineOTP` above) must match the key you set as
`ANDROID_RELEASE_KEY_ALIAS`. Installing a build signed with a different key over an existing
install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; users must export a portable backup,
uninstall, and restore.
