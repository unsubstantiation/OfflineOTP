# Kotlin sandbox checks

These are **non-authoritative JVM checks**, intended for environments without the
Android SDK or published Compose/native dependencies. They compile selected
production sources against narrow substitutes and run available headless tests.
They do not replace Gradle, lint, package/signature checks or device tests.

## Setup

```sh
bash scripts/kotlin-sandbox/bootstrap-toolchain.sh
# Apply the export commands printed by the bootstrap script, then:
bash scripts/kotlin-sandbox/run.sh
```

Bootstrap downloads a pinned Kotlin compiler/JDK from npm/PyPI, checks recorded
SHA-256 values and unpacks outside the repository. Those hashes are not independent
provenance verification; prefer a trusted package manager/archive where available.

With your own toolchain, set `JAVA_HOME`, `JAVA_BIN`, `KOTLINC` and, if necessary,
`KOTLIN_STDLIB`. Outputs default to ignored `reports/kotlin-sandbox/` directories.

## Gates

| Command | Coverage |
| --- | --- |
| `run.sh` | Explicit core/app source lists, Android substitutes and `Checks.kt` behavior checks |
| `compile-core.sh` | Shared domain/core and core JVM tests without Android/Compose APIs |
| `scripts/run-jvm-tests.sh` | App/core JVM tests using the sandbox dependencies |
| `compile-desktop.sh` | JVM engine/model and desktop sources/tests, headless AWT and third-party stubs |
| `compile-ui.sh` | Separate domain/contract/foundation/JVM adapter boundaries, presentation tests and platform host seams |
| `compile-android-tests.sh` | Instrumentation source type-check only; no Android tests executed |

Invoke the scripts from the repository root as `bash scripts/kotlin-sandbox/<name>`;
use `bash scripts/run-jvm-tests.sh` for the JVM runner.

The UI gate compiles portable contracts without engine classes, foundation without
JVM model/core classes, and JVM engine-to-presentation adapters without Compose.
It runs shared presentation tests and headless scheduler tests, then checks host
seams with [compile-only Compose/platform stubs](stubs-compose/README.md).
No Swing UI fallback or obsolete table/display test suite is built.

`compile-desktop.sh` also runs `desktop-tests/HardwareKeyManagerTest` (nested in
its package directories), which requires scripted `OathSandbox`/`FidoSandbox`
substitutes. It is not part of Gradle's real-SDK test source set. The Compose
desktop validation workflow runs both the real Gradle matrix and this separate
scripted suite; moving it does not drop its cases from CI. Ordinary desktop tests
use an SDK-shaped credential fixture shared with the sandbox.

## What passing means

Checks cover known OTP/crypto vectors, document bounds, format routing, generation-
bound sessions/tasks, cancellation, backup ownership, privacy/redaction and shared
presentation policy. Each `PASS` line identifies a covered case. Some sources are
only compiled/shape-checked, and some third-party behavior is substituted.

No composable is executed or measured. No real Android window, resources, IME,
KeyStore, camera, file provider, permission prompt or UIKit service is exercised.
Stub APIs can accept calls that the real SDK rejects. Handwritten Foundation, imaging and
pasteboard signatures are not Kotlin/Native or Apple ABI verification.

These scripts perform no Gradle dependency resolution, Android manifest/resource
processing, native compilation, APK/IPA packaging, signing or independent-build
comparison. Native gates and current user-reported verification are documented in
the [project guide](../../README.md) and
[native handoff](../../docs/shared-ui-native-validation.md).
