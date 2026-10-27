# Vault security hardening

The [Kotlin OTP parser](kotlin-otp-parser-pilot.md) introduced URI validation with explicit success/failure results and encoding checks. The app main sources have since been migrated to Kotlin; this document describes behavior, not source-language boundaries.

This documents the current implementation and its compatibility changes, **not a completed security audit or a claim that Android tests have passed**. Minimum Android remains API 28, target API 35; both existing flavors remain supported by the build configuration.

## IPC authorization

The panic activity and encrypted-backup receiver require the app-owned
`${applicationId}.permission.CONTROL_VAULT` permission, declared with **signature** protection. Android checks it before delivering an activity launch or broadcast. Exported-component allowlisting alone is not authorization.

A companion application must use the same signing identity and request this permission. An explicit package/component or a correct action string does not grant access. Independently signed Tasker/PanicKit installations and ordinary `adb shell am` commands are intentionally no longer authorized. There is no caller-name, Intent-extra, or user-toggle bypass. Introducing independently signed automation again requires a separately designed, revocable authorization mechanism.

The launcher/import activity remains public for normal account import. An external `ACTION_VIEW` OTP import requires confirmation after vault authentication. Scanned or file-imported QR codes are added directly, except that an existing issuer triggers a confirmation; invalid input does not silently add entries. Internal credential/settings/backup activities remain unexported. Obscured touch events are rejected by the base vault activities (partially obscured events additionally on API 29+).

## Bounded input and fail-closed results

| Input | Policy |
| --- | --- |
| Documents/provider output | 16 MiB; byte counts enforced independently of provider metadata |
| JSON nesting | At most 32 levels, checked before recursive platform parsing; single-quoted/comment JSON extensions rejected |
| Accounts | At most 2,000 per vault/import |
| OTP URI | At most 16,384 characters; duplicate singleton parameters rejected |
| Label/issuer/password | At most 1,024 characters |
| Tags | At most 32, each at most 128 characters |
| Seed | At most 2,048 decoded bytes, validated encoding |
| Period/digits/counter | Period 1–86,400 seconds; digits 1–9 (Steam fixed to its format); nonnegative counter below `Long.MAX_VALUE` |
| Decoded QR image | Positive dimensions, each at most 8,192, at most 4,000,000 pixels; validated before allocating pixel buffers |
| Untrusted encrypted-backup KDF | 1–1,000,000 iterations, validated before derivation |
| Stored login KDF | 1–20,000,000 iterations accepted; newly benchmarked credentials capped at 2,000,000 |

These limits deliberately reject formerly accepted oversized or malformed data. The existing backup writer's 140,000–160,000 iteration range is unchanged. Normal historical backup framing and custom 1–9 digit OTPs remain supported within the limits. Database payloads leave 64 bytes of headroom for the staged transaction framing.

JSON imports are all-or-nothing: malformed entries cannot produce a successful prefix. Missing files, missing keys, authentication failures, corrupt data and I/O failures have distinct load outcomes; none is treated as a legitimate empty database. Ordinary saves must authenticate the existing vault first. Only explicit setup/reset can initialize a missing vault. Crypto failures no longer manufacture a zero OTP.

Storage Access Framework output uses `content:` URIs; arbitrary external `file:` reads are rejected (private cache fixture files remain supported). Display-name metadata is optional, length-limited and sanitized. Null streams, provider exceptions and oversized PGP output fail rather than becoming successful partial exports. Byte limits do **not** impose a wall-clock timeout on a blocking provider.

## Session and credentials

Vault keys are process-memory state, not Activity Intent/Bundle extras. Authentication/setup key handoffs are one-use and generation-bound. Process death requires reopening/authentication; it does not restore a serialized key.

Locking advances the generation, clears the handoff, invalidates asynchronous results and clears adapter entry/token state. Background/screen-off locking follows the existing relock settings. The device-credential challenge is exempted from background relocking while pending because no vault key has been installed yet. UI task delivery and vault mutations are serialized against revocation. Password and seed widgets explicitly disable saved view state in XML and at runtime where views are bound; password-entry dialogs and the manual-entry dialog clear their secret fields on dismissal. The encrypted-password preference keeps the encrypted value in its field and decrypts only while binding the dialog rather than retaining a plaintext password field. Byte-array cleanup is best-effort: Java Strings, framework copies, and provider buffers cannot be reliably erased.

`MainActivity` now uses normal Android configuration recreation instead of claiming orientation/screen/keyboard changes itself. Its saved bundle contains only the search filter; an existing process-owned `VaultSession` is reused after configuration recreation, while process death leaves the session locked and still requires the regular authentication/unlock path. The first-run wizard follows the same rule and is no longer portrait-locked: its saved state carries only the setup outcome, and a key derived before a recreation is handed over through the one-use, generation-bound session handoff rather than a bundle. The inactivity re-lock deadline is kept in elapsed-realtime terms across background/screen-off periods, so time in which the foreground timer could not run still counts. The real rotation, process-death and accessibility behavior remains a device-acceptance gate.

KeyStore lookup never silently invents a replacement key for an existing vault. Key creation is confined to explicit setup/reset or an authenticated encryption migration. An invalidated or missing key needs a portable recovery backup; generating another key cannot decrypt the old database.

Credential changes derive new credentials without changing the active verifier first. The transaction is:

1. Authenticate/load the existing ciphertext and retain an encrypted recovery copy.
2. Write staged ciphertext with a unique transaction UUID.
3. Synchronously commit the matching encryption/auth settings and UUID.
4. Promote the ciphertext through `AtomicFile` and verify a bounded read-back.

Startup performs journal recovery **before selecting keys**. Uncommitted staged data is discarded in favor of the original vault; committed staged data is promoted. A failed preferences commit blocks further process-local vault writes, because Android's in-memory preferences may already have changed. Restart or an explicit destructive wipe is needed. Cancellation before metadata commit leaves the old credentials authoritative. Cancellation after a completed commit cannot undo that commit; the next authentication must use the newly committed credentials.

The recovery copy contains old ciphertext, not a plaintext export or a raw key. It can require the previous password/key; it is not a portable backup or an automatic credential rollback. AtomicFile/read-back is not a guarantee against every filesystem, power-loss or hardware failure. Legacy SHA-256 verifier upgrades are auth-only, restricted to a successfully authenticated KeyStore-mode vault; they cannot rewrite a password vault's credentials without re-encryption.

## Backup lifecycle

The source manifest opts out of platform cloud backup and device transfer with `allowBackup=false`, no backup agent registration, and explicit cloud/device-transfer/full-backup exclusions. The retained unregistered legacy agent registers no helpers and refuses restore callbacks; it cannot receive platform callbacks without being registered. Old platform restores are intentionally unsupported. Verify the merged, packaged manifest and behavior on target devices as release checks. Maintain and test portable password-encrypted or OpenPGP backups before upgrading or changing devices/signing identities.

Automatic backups require a configured location, explicit opt-in, and a nonempty encrypted backup password. Broadcast backups additionally require KeyStore mode and the signature permission. Duplicate broadcasts are coalesced through unique WorkManager work and a one-minute enqueue throttle. Workers recheck configuration at execution time; export checks also cover cancellation, changed location/password/opt-in, and session revocation during an in-flight operation.

Automatic exports request new UUID-named documents, even if the old date/time naming option is off, to avoid truncating a previous backup. Failed new documents are deleted best-effort. Storage providers are not atomic or inherently trustworthy: a failed user-selected export may leave partial data, and a hostile provider can ignore delete requests or retain bytes already received. Panic disables automatic backups, cancels pending unique work, revokes the session and performs the requested wipe under mutation locks; it cannot recall bytes already exported.

## Regression coverage and remaining verification

The Python suite checks the signature-bound manifest policy, backup-disable policy, source
exclusion rules, build-input provenance, and independent-build comparison logic. The host JVM
suite covers input/header limits, parser/document rules, crypto derivation and wrapping, portable
AES-GCM (NIST vector), and current/legacy encrypted-backup round trips and tamper rejection. The
sandbox checks process-session handoffs against stand-ins. Instrumentation sources cover platform
paths: app context and private storage, `IntroScreenActivity` launch, RFC TOTP/HOTP vectors, entry URI/document round
trips, Android AES-GCM tamper rejection, a real generated QR image for the reported CCP URI, an
AndroidKeyStore wrap/read/delete cycle, and the database round trip. The sandbox's fake SAF resolver
also exercises the actual encrypted backup writer and content-URI read path; it is not a substitute
for Drive, Work Profile, or OEM document-provider testing.

The independent-UID `security-test-client` module and the larger interop suites described in
earlier revisions are not part of this checkout. IPC authorization is pinned by the
signature-permission manifest policy checks; the remaining instrumentation suite still needs
device execution.

Local validation on 2026-10-22: the Python suite passed 113 tests (9 PyYAML-gated tests skipped),
`scripts/run-jvm-tests.sh` passed 138 JVM tests, `scripts/kotlin-sandbox/run.sh` passed 102 checks,
and `compile-android-tests.sh` type-checked all seven instrumentation sources against stand-ins.
This is not validation against AGP, real Android APIs or a device. The Gradle wrapper was attempted
but could not download Gradle from `services.gradle.org` under this sandbox's outbound-host policy;
no Android SDK/emulator was installed in that environment. At that point the last green API 28/36
CI run was on the base commit; see the [later issue #39 record](andotp-carryover-issue-pass-2026-10-19.md)
for subsequent verification. Both-flavor Gradle builds, lint, packaged manifest/resource inspection,
API 28/36 emulator runs, and reproducibility comparisons remain release checks. GitHub Actions
workflows are no longer configured. OEM restore
behavior, actual TalkBack traversal, cloud/work-profile provider grants, external OpenPGP behavior,
and UI/process-death/rotation flows need device or provider testing.

This work does not defend against a rooted device, compromised signing key, malicious user-selected OpenPGP/storage provider, privileged accessibility tooling, clipboard/token sharing chosen by the user, or arbitrary same-signer code. The production app must continue to have no `INTERNET` permission; external providers can have their own network access. Reviewed dependency locks/checksums and an archived/digest-pinned release toolchain remain separate supply-chain follow-up work.
