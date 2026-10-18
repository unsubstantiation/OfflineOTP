# Kotlin sandbox check (non-authoritative)

`run.sh` compiles the app's Kotlin main sources against hand-written Android/AndroidX stubs and
runs `Checks.kt` on a desktop JVM. It exists for environments that have a JDK and a Kotlin
compiler but no Android SDK, AndroidX artifacts, or Gradle dependency graph. It is not the
app's real build.

**It is not a substitute for the CI gate**: the Gradle unit tests, lint, APK builds, packaged-
manifest policy, and API 28/36 instrumentation runs described in the root [README](../../README.md).
A green run means the covered sources compile under `-jvm-target 1.8` and the checked logic
behaves against the stubs. It says nothing about Android runtime behavior, resources, the
manifest, packaging, signing, or reproducibility.

## Run it

On a machine without a JDK or Kotlin compiler, `bootstrap-toolchain.sh` fetches both from package
mirrors, verifies pinned SHA-256 digests, unpacks them outside the repository, and prints the
environment to use:

```sh
bash scripts/kotlin-sandbox/bootstrap-toolchain.sh
eval "$(bash scripts/kotlin-sandbox/bootstrap-toolchain.sh | grep ^export)"
bash scripts/kotlin-sandbox/run.sh
```

The digests are trust-on-first-use against the mirrors over TLS, not independent provenance
verification. Prefer a package manager or a verified archive where available.

With an existing toolchain:

```sh
KOTLINC=/path/to/kotlinc-jvm JAVA_BIN=/path/to/java bash scripts/kotlin-sandbox/run.sh
```

Set `KOTLIN_STDLIB` to use a specific `kotlin-stdlib.jar`. Output defaults to the ignored
`reports/kotlin-sandbox/` directory.

The related host gates read the same `APP_SOURCES` and `STUBS` lists from `run.sh`:

- `scripts/run-jvm-tests.sh` runs the JVM unit tests in `app/src/test/kotlin`.
- `scripts/kotlin-sandbox/compile-android-tests.sh` compiles the instrumentation sources in
  `app/src/androidTest/kotlin` against test stubs.

Last verified on 2026-10-18 with Kotlin 2.4.21 and Temurin 25.0.2: 100 sandbox checks passed,
110 JVM tests passed, and all four instrumentation sources compiled.

## What it checks

- **OTP generation:** RFC 4226/6238 vectors for SHA-1/SHA-256/SHA-512, Steam and mOTP mapping,
  token formatting, previous-token selection, and refresh after a backwards clock change.
- **Parsing and input bounds:** `InputPolicy`, `OtpUriParser`, `EntryJson`/`EntryJsonResult`,
  bounded document reads and provider output, encrypted-backup framing, and the replicated
  `org.json`, Commons Codec Base32, and `android.net.Uri` subsets.
- **Cryptography that a desktop JVM can run:** PBKDF2 against an independent derivation and the
  RFC 6070 vector, legacy SHA-256 key derivation, and RSA wrap/unwrap with a real generated key
  pair, including tampered blobs, wrong keys, and short frames.
- **Vault, session, and tasks:** typed vault outcomes and recovery decisions, one-use secrets and
  generation-bound handoffs, task cancellation/ownership, settings and credential staging,
  backup receivers/worker/agent, the panic responder, and screen-off/obscured-touch handling.
- **UI and Java-visible shapes:** dialog and preference wiring, tags, about rows, entry-card
  binding, and the retained task fragment. Names, descriptors, annotations, enum order, and
  other ABI details are reflected from compiled classes where a class cannot be loaded.

Some sources are fully executed; others are only compiled and shape-checked. The `PASS` lines
name what each check actually exercises.

## Limits

- The stubs are approximations, not the Android platform. There is no real window, theme,
  resource resolution, IME, `Looper`/`Handler` timing, notification channel, camera, KeyStore,
  or Storage Access Framework provider.
- The app's AES-GCM path passes an `IvParameterSpec`, which Android accepts but the desktop JDK
  rejects. Only the framing checks that run before the provider are covered here.
- `AtomicFile` refuses every operation, and `Context.getFilesDir()` is the working directory.
  The real file transaction is deliberately never executed.
- Some stub behavior is itself replicated (for example `TypedValue.applyDimension`), so checks
  that depend on it are only as accurate as the replica.
- No Gradle invocation, lint, resource/manifest processing, APK packaging, signing, or
  reproducible-build comparison happens here.
