# Kotlin sandbox check (non-authoritative)

`run.sh` builds in two phases on a desktop JVM, mirroring the Gradle `:core`/`:app` dependency.
It first compiles the shared `:core` module (`core/src/main`, the `CORE_SOURCES` list) with **no**
Android/AndroidX stubs on the classpath, then compiles the app's Kotlin main sources
(`APP_SOURCES`) against that compiled core output and the hand-written Android/AndroidX stubs and
runs `Checks.kt`. The phase split fails if a core source reaches for an Android API, since such a
reference cannot resolve in phase 1. It exists for environments that have a JDK and a Kotlin
compiler but no Android SDK, AndroidX artifacts, or Gradle dependency graph. It is not the
app's real build.

**It is not a substitute for release checks**: the Gradle unit tests, lint, APK builds,
packaged-manifest policy, and API 28/36 instrumentation runs described in the root
[README](../../README.md).
A green run means the covered sources compile under `-jvm-target 1.8` and the checked logic
behaves against the stubs. It says nothing about Android runtime behavior, resources, the
manifest, packaging, signing, or reproducibility.

## Run it

On a machine without a JDK or Kotlin compiler, `bootstrap-toolchain.sh` fetches both from package
mirrors, verifies pinned SHA-256 digests, unpacks them outside the repository by default, and prints the
environment to use:

```sh
bash scripts/kotlin-sandbox/bootstrap-toolchain.sh
# Run the export commands printed by the script, then:
bash scripts/kotlin-sandbox/run.sh
```

The digests are trust-on-first-use against the mirrors over TLS, not independent provenance
verification. Prefer a package manager or a verified archive where available.

With an existing toolchain:

```sh
export JAVA_HOME=/path/to/jdk
export PATH="$JAVA_HOME/bin:$PATH"
KOTLINC=/path/to/kotlinc-jvm JAVA_BIN="$JAVA_HOME/bin/java" bash scripts/kotlin-sandbox/run.sh
```

Set `KOTLIN_STDLIB` to use a specific `kotlin-stdlib.jar`. Output defaults to the ignored
`reports/kotlin-sandbox/` directory.

The related host gates read the same `CORE_SOURCES`/`APP_SOURCES`/`STUBS` lists from `run.sh`:

- `scripts/kotlin-sandbox/compile-core.sh` is the strict core boundary gate: it compiles
  `core/src/main` together with `core/src/test` using no `android.*`/`androidx.*` stubs (only the
  third-party stubs that `core/build.gradle` declares) and runs the core JVM tests.
- `scripts/run-jvm-tests.sh` runs the JVM unit tests under both `app/src/test/kotlin` and
  `core/src/test/kotlin`.
- `scripts/kotlin-sandbox/compile-desktop.sh` is the desktop Swing gate: it compiles `:core` first
  with no Android stubs, then compiles `desktop/src/main` and `desktop/src/test` against core's
  output using only the JDK's `java.desktop` (Swing/AWT) module, and runs the desktop tests
  headless (`-Djava.awt.headless=true`). Lightweight Swing theme, card, and table-model tests run
  here; heavyweight `JFrame` display tests are skipped. On a host with a display and Gradle, run
  `./gradlew :desktop:test -Dofflineotp.uiTests=true --tests '*MainWindowDisplayTest'` for the
  opt-in window, dialog, menu, visibility, search and clipboard checks.
- `scripts/kotlin-sandbox/compile-android-tests.sh` compiles the instrumentation sources in
  `app/src/androidTest/kotlin` against test stubs.

On 2026-10-22, with Kotlin 2.4.21 and Temurin 25.0.2: 102 sandbox checks passed,
144 JVM tests passed, and all twelve instrumentation sources type-checked against stand-ins.

## What it checks

- **OTP generation:** RFC 4226/6238 vectors for SHA-1/SHA-256/SHA-512, Steam and mOTP mapping,
  token formatting, previous-token selection, and refresh after a backwards clock change.
- **Parsing and input bounds:** `InputPolicy`, `OtpUriParser`, Google Authenticator migration QR
  parsing and batch/session ownership, `EntryJson`/`EntryJsonResult`, bounded document reads and
  provider output, encrypted-backup framing, and the replicated
  `org.json`, Commons Codec Base32, and `android.net.Uri` subsets.
- **Cryptography that a desktop JVM can run:** PBKDF2 against an independent derivation and the
  RFC 6070 vector, AES-GCM against the NIST vector, current encrypted-backup round trips (including
  wrong-password/tamper rejection) and rejection of headerless backups, login-derived backup keys and
  their shared nonce budget, hardware-unlock AES-GCM envelope tampering and bounds, and RSA
  wrap/unwrap with a generated key pair.
- **Vault, session, and tasks:** typed vault outcomes, generation- and revision-bound key handoffs,
  task cancellation/ownership, backup retry markers, credential staging, backup receivers and the
  unregistered agent, the panic responder, and screen-off/obscured-touch handling. A fake document
  provider exercises login-password automatic/manual exports, failure cleanup and revocation.
  In-memory preferences and a scripted key API check hardware-enrollment invalidation, duplicate
  refusal and late-result cleanup, not actual device communication.
- **UI and Java-visible shapes:** dialog and preference wiring, tags, about rows, entry-card
  binding (including hidden issuer/label formatting, issuer preference and action redaction),
  active-search refresh and import-confirmation cleanup, and the retained task fragment.
  Names, descriptors, annotations, enum order, and other ABI details are reflected from compiled
  classes where a class cannot be loaded.

Some sources are fully executed; others are only compiled and shape-checked. The `PASS` lines
name what each check actually exercises.

## Limits

- The stubs are approximations, not the Android platform. There is no real window, theme,
  resource resolution, IME, `Looper`/`Handler` timing, notification channel, camera, KeyStore,
  or Storage Access Framework provider. The fake document resolver verifies backup bytes and
  permission gates, not Google Drive, Work Profile grants, or any real provider's behavior.
- `AtomicFile` refuses every operation, and `Context.getFilesDir()` is the working directory.
  The real file transaction is deliberately never executed.
- Some stub behavior is itself replicated (for example `TypedValue.applyDimension`), so checks
  that depend on it are only as accurate as the replica.
- No Gradle invocation, lint, resource/manifest processing, APK packaging, signing, or
  reproducible-build comparison happens here.
