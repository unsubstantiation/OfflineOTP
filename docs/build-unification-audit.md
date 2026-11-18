# Cross-build consolidation — 2026-11-07

Scope: Android, Windows, macOS, Debian/Linux and iOS. This is an incremental audit,
not a declaration of identical clients or native/device validation.

## Architecture and remaining differences

| Area | Current implementation | Next consolidation boundary |
| --- | --- | --- |
| Vault cards and entry forms | Android and all desktop distributions use shared `:ui` Compose cards/models; iOS uses SwiftUI | Keep common behavior fixtures for Swift, plus native accessibility/visual checks. |
| Password Edit | Android now uses the desktop `PasswordEntryDialog` and `PasswordEntryInput`, instead of a separate native dialog | Expand editor scope deliberately for non-common fields; do not silently overwrite alias columns. |
| OTP, Notes, Keys, Email editing | Android shared host/controller routes Edit to shared manual/email forms; Keys inline Edit routing fixed | Audit remaining detail/menu actions and metadata coverage; this change does not claim every field has an editor. |
| Theme | `shared/theme/forest.json` generates Compose, SwiftUI and Android resource bindings | Typography, spacing, focus order and responsive layout still require native comparisons. |
| Navigation | Desktop shared Compose controls; Android retains Activity toolbar/navigation (`showControls = false`); iOS native navigation | Move ordinary Android controls into shared components incrementally, retaining native back/menu behavior. |
| Import/export | Shared JVM codecs/policies; Swift counterparts with golden corpus; Android native chooser/dialog wiring, desktop Compose transfer dialogs, iOS sheets | LinSCP export selection/password/warning now uses the shared Android/desktop form; import/master-key prompts remain a separate migration. |
| Preferences/unlock/backup | Native Android activities, desktop Compose surfaces, iOS SwiftUI | Inventory semantics and defaults before replacing surfaces; capability-dependent controls must remain platform-specific. |
| About | Shared content exists; Android native Activity/library integration, Compose desktop, iOS native view | Share presentation where practical without losing library notices. |
| Platform services | Android SAF, camera, hardware keys and lifecycle; desktop files/hardware integration; iOS Keychain/biometrics, document pickers | Keep native adapters. Similar appearance is not a reason to remove capabilities. |

## This increment

- Removed Android's redundant password editor and unused native email-creation dialog.
- Password form now scrolls, supports Show/Hide, uses the browser field limit (65,536 UTF-16
  code units), never preloads the stored password, and retains blank-as-preserve behavior.
- Android password callbacks require editor ownership and a current entry/session; persistence
  failure must not mutate the original. Key Edit now reaches the shared manual editor.
- JVM and Swift password replacement preserve the full stored entry metadata rather than
  rebuilding a partial record. JVM runtime tint is preserved separately from JSON.
- iOS password saves check the opening field snapshot, session generation, reveal state and
  common-form eligibility; menus use the same eligibility policy.
- `desktop/src/test/resources/golden/password-edit-contract.json` is consumed by JVM and Swift
  tests. Ineligible alias-only records may be rejected earlier by JVM decoding; neither client
  may offer the common editor for those records.
- Theme values and Android resource names are unchanged. Edit `shared/theme/forest.json`, run
  `python3 scripts/generate-theme.py`, and check in all generated bindings. The Python gate runs
  `--check`; builds need no generator runtime or network. Android night-mode library overrides
  remain aliases to generated resources.

## Gates and outstanding validation

Sandbox core: 346 passed. Desktop: 394 passed, 2 skipped. Shared UI-model: 141 passed.
Headless AWT: 3 passed. Android sandbox checks passed, including shared Key/Password opening,
failed persistence, dismissed callbacks and lock cleanup. Android instrumentation sources:
28 files compile against stubs. Python source/tooling tests: 560 passed.

These are **not** native Gradle builds, Android instrumentation execution, Swift/Xcode compilation,
Swift test execution, or device/simulator visual validation. Run those release gates before
claiming platform parity. Swift test additions are checked in but not executed in this environment.

Next increments should separately gate/commit transfer dialogs, ordinary navigation controls,
then preference semantics. Do not attempt a wholesale rewrite or remove native integrations.

## Follow-up: LinSCP export form

Android now hosts the same `LinScpExportDialog` as desktop, replacing native selection and
password dialogs. Default-empty selection, default-off plain text, warning text and password
confirmation therefore come from one component. Cancel clears both password drafts.
Android retains ACTION_CREATE_DOCUMENT, session-guarded storage writes and pending-byte cleanup.
The Activity validates selection bounds, matching nonempty encrypted-export passwords, request
ownership, session generation and selected-entry membership independently of button enablement.
Failed preparation stays in the form with an error; native chooser launch ends the form.
Import and its Android master-key input are unchanged. iOS continues using its native transfer
sheet and existing equivalent default-off plain-text policy.

Follow-up sandbox coverage exercises invalid/empty selections, mismatched/blank passwords,
replaced and dismissed forms, explicit plain export roundtrip, removed entries and locked sessions.
Shared UI-model (141) and headless AWT (3) tests pass; Python tooling/source tests (560) pass.
Native Gradle/device/Xcode gates remain outstanding.

## OpenPGP: in-app only

Android now always selects the bundled key-file engine, regardless of old or imported
`pref_openpgp_mode` values. External-provider, signing-key and provider-verification settings are
no longer exposed. Provider binding has been retired and old provider-result callbacks fail closed;
no external OpenPGP app is launched. Some legacy task/API compatibility code remains for a later
cleanup, but it is not an available backup engine.

Select recipient public-key files for backup and a secret-key file for restore in Settings.
Users with keys managed by another app must export the appropriate key files themselves; provider
key IDs cannot be converted into private keys automatically. Existing OpenPGP backups remain
subject to the bundled engine's supported algorithms/formats and available private keys. This is
not a promise of provider-specific signing/verification feature equivalence.

Missing key files no longer incorrectly report that the native engine is absent. A genuinely
missing engine requires a correctly packaged build, not installation of an external provider.
Native ABI packaging and actual encrypted backup/restore still need device validation.
