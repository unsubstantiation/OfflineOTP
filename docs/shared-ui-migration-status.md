# Current migration and verification status

This is the current status summary. Dated audits and earlier per-chunk test counts
are historical snapshots, not competing descriptions of the shipping architecture.

## Verification

| Area | Recorded evidence |
| --- | --- |
| Android, Debian, Windows and macOS | User reports all non-iOS builds/workflows and platform checks verified |
| iOS | Confirmed failed build; Kotlin cutover and workflow fixes await a successful native run |
| Local follow-up checks | Shared presentation/host JVM tests, Python regressions and architecture/shell checks pass; these do not replace native execution |

The non-iOS report does not supply a per-scenario artifact/device log. Preserve it
as user-reported verification, not an independent security audit or a guarantee for
later changes. Do not describe those platforms as wholly untested.

## Source architecture

- `shared-domain` owns portable document/protocol/interchange rules and workflow decisions.
- `ui-contract` owns engine-free render/frame/drag/presentation contracts.
- `ui-foundation` owns shared Compose rendering and Apple Compose bridges.
- `ui-model/jvmHostMain` and `ui/jvmHostMain` adapt JVM engine values and host services.
- Android and desktop retain native session, storage, lifecycle and capability adapters.
- iOS still uses substantial Swift production logic. Only five leaf implementations
  have been replaced in the follow-up; the engine/host cutover is not complete.

## Outstanding iOS work

See the [cutover inventory](ios-kotlin-cutover.md): models/crypto/storage, `VaultStore`,
automatic-backup orchestration, large vault/unlock coordinators, native lifecycle,
pickers/camera and package retirement. The sole approved Swift exception is a
minimal bridge to the official YubiKit SDK, not the current hardware orchestration.

The workflow no longer runs retired standalone SwiftPM tests. It runs shared Kotlin
presentation tests, builds both native frameworks, then compiles the actual Xcode
app dependency graph and checks the unsigned device IPA. Compilation still includes
remaining production Swift until callers migrate.

## Safety and release handoff

- Full backups copy the saved portable encrypted vault. Separate password/OpenPGP
  exports and preferences exports are different operations.
- Lock/background/close must invalidate stale requests and cancel secret-bearing work.
- Signing credentials remain session-only; no locked-vault automatic backup.
- Do not run concurrent clients against one writable vault or discard recoverable
  candidates after an interrupted rekey/migration.

Related: [ownership](shared-ui-ownership.md), [target matrix](shared-code-migration.md),
[release checklist](shared-migration-release.md),
[native validation](shared-ui-native-validation.md),
[device checklist](shared-ui-device-checklist.md).
