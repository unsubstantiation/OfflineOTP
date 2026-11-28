# Documentation

## Current status

The user reports all non-iOS builds/workflows and platform checks verified. The iOS
build is confirmed failing; subsequent fixes await a successful native run. Shared
Compose UI is in place, but the iOS Kotlin engine/host cutover is not complete.
Only a minimal Swift YubiKit SDK bridge is approved to remain after that cutover.

This index and the status/ownership pages describe current intent and evidence.
Per-chunk handoffs retain implementation-time test snapshots. Historical audits
below describe their reviewed revisions, not current shipping behavior. Use Git
history for removed batch journals rather than treating them as active instructions.

## Start here

- [Project guide](../README.md)
- [iOS build guide](../ios/README.md)
- [Desktop packaging](../scripts/packaging/README.md)
- [Sandbox checks and limitations](../scripts/kotlin-sandbox/README.md)
- [OpenPGP packaging](../scripts/openpgp/README.md)

## Status, architecture and acceptance

- [Current migration and verification status](shared-ui-migration-status.md)
- [Shared UI ownership](shared-ui-ownership.md)
- [Shared-code consolidation plan](shared-code-migration.md)
- [iOS Kotlin cutover — in progress](ios-kotlin-cutover.md)
- [Native validation handoff](shared-ui-native-validation.md)
- [Shared Compose device-verification checklist](shared-ui-device-checklist.md)
- [Migration cleanup and release handoff](shared-migration-release.md)

## Contracts and focused guides

- [CodeQL setup and recovery](codeql.md)

- [About content and navigation](about-parity.md)
- [Automatic vault backups and encrypted exports](automatic-backups.md)
- [Compose desktop cutover](compose-desktop-cutover.md)
- [Shared Compose UI plan](compose-unified-ui-plan.md)
- [Desktop camera QR capture](desktop-camera.md)
- [Full backup restore](full-backup-restore.md)
- [iOS architecture and compatibility contract](ios-client-plan.md)
- [iOS hardware-key support: NFC and USB-C](ios-hardware.md)
- [Kotlin pilot: a validated OTP URI boundary](kotlin-otp-parser-pilot.md)
- [Kotlin vault, session and task contracts](kotlin-vault-session-task-contracts.md)
- [LinSCP Key/Sessions interoperability](linscp-key-session-interchange.md)
- [OpenPGP engine contract across builds](openpgp-client-parity.md)
- [Shared automatic-backup decisions — chunk 8](portable-automatic-backup.md)
- [Chunk 2 — portable entry contract and document adapters](portable-entry-documents.md)
- [Chunk 2a — portable entry metadata](portable-entry-metadata.md)
- [Portable import/export — chunk 7](portable-interchange-workflow.md)
- [Portable persistence/migration sequencing (chunk 6)](portable-persistence-workflow.md)
- [Shared restore workflow — chunk 4](portable-restore-workflow.md)
- [Portable session/unlock authority (chunk 5)](portable-session-authority.md)
- [One portable UI renderer — chunk 9](portable-ui-presentation.md)
- [Portable encrypted vault container (version 1)](portable-vault-container.md)
- [Portable vault and backup wire policy — chunk 3](portable-vault-protocol.md)
- [Signed release APKs](release-signing.md)
- [Vault security hardening](vault-security-hardening.md)

## Historical reports

Findings and paths may have been superseded. Security findings in an old audit are
neither proof of a current vulnerability nor proof that later code is safe.

- [OfflineOTP upstream-issue carry-over pass — issue #39 (2026-10-19)](andotp-carryover-issue-pass-2026-10-19.md)
- [OfflineOTP triage of upstream andOTP open issues](andotp-open-issues-triage.md)
- [Bug hunt pass, 2026-10-25 (tag drawer and per-tag counts)](bughunt-2026-10-25-tag-drawer.md)
- [OfflineOTP bug hunt — historical findings](bughunt-findings.md)
- [Comprehensive bug and unfinished-work audit follow-up](bughunt-follow-up-2026-10-17.md)
- [Bug hunt and unfinished-feature follow-up, 2026-10-18](bughunt-follow-up-2026-10-18.md)
- [Bug hunt and fix pass, 2026-10-25](bughunt-follow-up-2026-10-25.md)
- [Cross-build consolidation — 2026-11-07](build-unification-audit.md)
- [Android / desktop parity review — 2026-11-02](client-parity-2026-11-02.md)
- [Cross-build feature and UI audit — 2026-11-07](cross-build-feature-audit.md)
- [Cross-platform feature parity and legacy-retirement audit — 2026-11-20](cross-platform-parity-audit-2026-11-20.md)
- [Email editor parity audit](email-parity-audit.md)
- [Existing-feature audit — 2026-10-26](feature-audit-2026-10-26.md)
- [Incomplete-feature audit — 2026-11-01](incomplete-feature-audit-2026-11-01.md)
- [Incomplete-feature audit — 2026-11-06 (fourth pass)](incomplete-feature-audit-2026-11-06-fourth-pass.md)
- [Incomplete-feature audit — 2026-11-06 (third pass)](incomplete-feature-audit-2026-11-06-third-pass.md)
- [Incomplete-feature audit — 2026-11-06](incomplete-feature-audit-2026-11-06.md)
- [Kotlin extraction record](kotlin-extracted-helpers.md)
- [Language conversion assessment: security and Android compatibility](language-security-assessment.md)
- [OTP and note parity audit](otp-note-parity-audit.md)
- [Scaffold audit — 2026-11-06 (second pass)](scaffold-audit-2026-11-06-second-pass.md)
- [UI decomposition plan](ui-decomposition-plan.md)

## Notices and maintenance

The [project license](../LICENSE.txt), [font license](../assets/thumbnails/DEJAVU-LICENSE.md)
and [artwork sources](../assets/thumbnails/README.md) retain their original legal text.
The [changelog](../CHANGELOG.md) is chronological, not a current feature matrix.
Generated About/theme content comes from `shared/`; edit its sources, then regenerate.
Do not include vaults, passwords, token seeds or revealed-secret screenshots in evidence.
