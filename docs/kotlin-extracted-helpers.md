# Kotlin extraction record

The Android application sources were converted from Java to Kotlin in incremental
batches. Original Java implementations were removed rather than retained as a
second engine. The detailed batch journal and then-current test counts are retained
in Git history; they are not current build instructions.

## Responsibilities that remain relevant

| Area | Boundary |
| --- | --- |
| Timer and input helpers | Scheduling/tap policy separate from Android View and session authority |
| Bounded IO and format headers | Reject oversized or malformed input before expensive work |
| OTP generation and codecs | Headless engine behavior with deterministic vectors |
| Task contracts | Explicit outcomes, cancellation and owned-result disposal |
| Vault sessions/storage | Generation checks and atomic/recoverable persistence sequencing |
| Native adapters | Android UI/service, permission and lifecycle execution |

Portable consolidation now moves applicable rules to `shared-domain/commonMain`.
JVM-specific engine-to-presentation adapters live in `ui-model/jvmHostMain`; shared
Compose rendering lives in `ui-foundation/commonMain`. Older references to Java
owners, Swing renderers, conversion-only interop tests or `security-test-client`
describe earlier revisions and should not guide new code.

## Validation

```sh
bash scripts/run-jvm-tests.sh
bash scripts/kotlin-sandbox/compile-core.sh
bash scripts/kotlin-sandbox/compile-desktop.sh
bash scripts/kotlin-sandbox/compile-ui.sh
bash scripts/kotlin-sandbox/compile-android-tests.sh
```

These local checks use substitute dependencies where documented. Instrumentation
source compilation is not an Android test run, and Compose stubs render no pixels.
See [sandbox limitations](../scripts/kotlin-sandbox/README.md),
[current migration status](shared-ui-migration-status.md) and
[shared-code plan](shared-code-migration.md).
