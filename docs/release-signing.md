# Signed release APKs

Release signing is **opt-in**. The Android workflow always builds and uploads the F-Droid debug
APK. The separate `sign-release` job builds signed F-Droid and Play release APKs **only when the
release keystore is configured**; otherwise it is skipped with a notice and the workflow stays
green. This is why an unconfigured repository no longer shows a red Android build.

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
create GitHub releases or store listings.

## Verifying a downloaded APK

```sh
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs OfflineOTP-fdroid-release.apk
sha256sum -c SHA256SUMS.txt
```

The printed certificate DN (`CN=OfflineOTP` above) must match the key you set as
`ANDROID_RELEASE_KEY_ALIAS`. Installing a build signed with a different key over an existing
install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; users must export a portable backup,
uninstall, and restore.
