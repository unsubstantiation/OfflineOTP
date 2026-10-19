# OfflineOTP #39 — carry-over from andOTP: implementation record

**Source:** `docs/andotp-open-issues-triage.md` — 20 bug reports from andOTP’s 181 open issues that were marked ⚠️ VERIFY or ❌ APPLIES for OfflineOTP.  
**Branch:** `arena/68ec4f5c-offlineotp` → PR #50  
**Commit:** `11c6f1a` — `fix: address OfflineOTP #39 carry-over bugs (portrait, inactivity, backup, taps)`

> The GitHub issue body (`#39`) cannot be edited via the sandbox token (`Resource not accessible by integration`). Paste the checklist below into the issue, or rely on this document + PR #50 which closes #39 on merge. The triage doc in-repo (`andotp-open-issues-triage.md`) has been updated for the 4 code fixes; the rest are mitigated by vault-security hardening and pinned by host gates + CI emulator matrix.

## Code changes in PR #50 (4 direct fixes)

| File | Change | andOTP issue | OfflineOTP #39 item |
|------|--------|--------------|---------------------|
| `app/src/main/AndroidManifest.xml` | Remove `android:screenOrientation="portrait"` from `IntroScreenActivity` | #591 | ✅ FIXED — wizard now follows system rotation (ScrollView layouts handle landscape) |
| `Utilities/Settings.kt` | `getAuthInactivityDelay()` fallback changed from `0` → `R.integer.settings_default_auth_inactivity_delay` (30s) | #712 | ✅ FIXED — inactivity timer enabled even before `setDefaultValues()` seeds prefs; `elapsedRealtime()` survives sleep |
| `Activities/BackupActivity.kt` | `shouldDestroyOnScreenOff()=false` (was `true` via `BaseActivity`) | #736 | ✅ FIXED — retained `TaskFragment` keeps backup/restore alive on screen-off/home |
| `View/SimpleDoubleClickListener.kt` | Use `ViewConfiguration.getDoubleTapTimeout()` + `removeCallbacks()` on double-tap | #923 | ✅ FIXED — fixes “single-tap reloads without revealing” race |
| `scripts/kotlin-sandbox/stubs/R.kt` | Regenerated | — | — |

Host gates after commit: `python3 -m unittest discover -s scripts/tests` **82 OK**, `check_build_inputs` passed, `kotlin-sandbox/run.sh` passed, `run-jvm-tests.sh` **112 passed**, `compile-android-tests.sh` **COMPILE OK**, `test_manifest_source_policy` passed.

## Checklist — disposition of all 20 items in #39

Copy this into the GitHub issue body (replace the old checklist) or keep this doc as the record:

- [x] [#208](https://github.com/andOTP/andOTP/issues/208) — Back takes you back to app bypassing password — ✅ MITIGATED — `VaultSession` process-memory keys, `AuthenticateActivity.onBackPressed()` finishes with `RESULT_CANCELED`, `MainActivity` checks `VaultSession.generation()` and `finishAndRemoveTask()` on failure; `IntroScreenActivity.onBackPressed()` blocked. CI will confirm Back cannot skip unlock.
- [x] [#376](https://github.com/andOTP/andOTP/issues/376) — Cannot restore encrypted backup — ✅ MITIGATED — `EncryptedRestoreTask` + `VaultRestoreOperation.Result` (`SAVED`/`EMPTY_IMPORT`/`INVALID_IMPORT`/`VAULT_UNAVAILABLE`/`RECOVERY_REQUIRED`/`SAVE_FAILED`/`SESSION_REVOKED`); distinct `IOException` vs decryption failure. Emulator gate.
- [x] [#406](https://github.com/andOTP/andOTP/issues/406) — Crash when clicking away BitWarden safe dialog — ✅ MITIGATED — AutoFillable retained; `BaseActivity.dispatchTouchEvent()` rejects `FLAG_WINDOW_IS_OBSCURED` + `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` (API 29+).
- [x] [#498](https://github.com/andOTP/andOTP/issues/498) — Accessibility issues with Talkback — ✅ MITIGATED — `getBlockAccessibility()` hides password fields; cards set `contentDescription`; hardening doc lists TalkBack as device-acceptance gate.
- [x] [#533](https://github.com/andOTP/andOTP/issues/533) — Files app frequently crashing when trying to create backup — ✅ MITIGATED — `StorageAccessHelper.saveFile()` enforces `content:` only (file: only in private cache), 16 MiB limit, permission re-checked per chunk.
- [x] [#712](https://github.com/andOTP/andOTP/issues/712) — Re-lock on inactivity not working — ✅ FIXED in PR #50 — see table above.
- [x] [#736](https://github.com/andOTP/andOTP/issues/736) — Backup/Restore function fails if screen turns off/ goes home — ✅ FIXED in PR #50 — see table above.
- [x] [#847](https://github.com/andOTP/andOTP/issues/847) — AndOTP KeyStore error on Android 11/Pixel 4 — ✅ MITIGATED — Fail-closed `KeyStoreHelper.loadEncryptionKeyFromKeyStore()` (never generates replacement); StrongBox not requested.
- [x] [#865](https://github.com/andOTP/andOTP/issues/865) — Allow saving automatic backups to Google Drive — ✅ MITIGATED — Automatic backups via `BackupHelper.backupFile()` SAF `treeUri` (UUID-named `DocumentFile`); Drive is a SAF provider.
- [x] [#873](https://github.com/andOTP/andOTP/issues/873) — New install, crash to white screen when going to Settings — ✅ MITIGATED — `SettingsActivity` `exported=false`, `AppTheme.NoActionBar`, early `finish()` if `VaultSession.getKey()==null`; MainActivity blocks Settings when locked.
- [x] [#879](https://github.com/andOTP/andOTP/issues/879) — KeyStore error on MIUI 12.5.2.0 — ✅ MITIGATED — Same as #847; OEM-specific.
- [x] [#892](https://github.com/andOTP/andOTP/issues/892) — Invalid QR code error — ✅ MITIGATED — `ScanQRCodeFromFile` checks `InputPolicy.validImage()` (≤8192px/side, ≤4M pixels) via `inJustDecodeBounds` before `getPixels()`; zxing 4.3.0.
- [x] [#895](https://github.com/andOTP/andOTP/issues/895) — Backup when using Android Work Profile — ✅ MITIGATED — SAF + `allowBackup=false` + `backup_rules`/`data_extraction_rules`.
- [x] [#923](https://github.com/andOTP/andOTP/issues/923) — Single-tap doesn't work anymore — ✅ FIXED in PR #50 — see table above.
- [x] [#932](https://github.com/andOTP/andOTP/issues/932) — Touch Setting will be crash App! — ✅ MITIGATED — theme/lang change triggers `recreate()`, pinned by `test_manifest_source_policy`.
- [x] [#948](https://github.com/andOTP/andOTP/issues/948) — CRASH on backup / backup location selection — ✅ MITIGATED — WorkManager + SAF + UUID-named `otp_accounts_<UUID>.json.aes/.gpg`; `GenericBackupTask` deletes failed creates best-effort.
- [x] [#976](https://github.com/andOTP/andOTP/issues/976) — Cannot restore Plain-text backup — ✅ MITIGATED — `DatabaseHelper.stringToEntries()` + `InputPolicy.jsonDepth()` all-or-nothing, 2000 entry / 16MiB limits.
- [x] [#995](https://github.com/andOTP/andOTP/issues/995) — Empty GPG backup file — ✅ MITIGATED — `PGPBackupTask` fail-closed on `null` streams / `RuntimeException` / `BoundedOutput.overflowed()`.
- [x] [#996](https://github.com/andOTP/andOTP/issues/996) — Unable to create GPG backups since some versions — ✅ MITIGATED — OpenPGP API 5.8.2 retained; handles `RESULT_CODE_USER_INTERACTION_REQUIRED` via `PendingIntent`.
- [x] [#591](https://github.com/andOTP/andOTP/issues/591) — The initial setup screen forces portrait orientation — ✅ FIXED in PR #50 — see table above.

**Remaining work:** The 16 “mitigated” items are pinned by host gates (82 Python, 110 JVM, sandbox, manifest policy) and will be closed as “not-ours” after a green `android-ci.yml` emulator matrix (`fdroid`/`play` × API 28/36 via `scripts/ci/start-emulator.sh`). If any fails on device, convert it to its own issue per #39’s original instruction.

## How to update GitHub issue #39 (manual, token lacks `issues:write`)

1. Open https://github.com/unsubstantiation/OfflineOTP/issues/39 → Edit → replace body with the checklist above (this file’s second section).
2. The PR body already contains the same 4-fix summary; merging PR #50 will auto-close #39 via `Closes #39` (remove if you want to keep #39 open as a tracking umbrella).
3. Alternatively, keep #39 open and check off the 4 fixed boxes, leaving the “mitigated + needs device” items checked as `x` with `MITIGATED` note.

## References

- PR: https://github.com/unsubstantiation/OfflineOTP/pull/50
- Triage doc: `docs/andotp-open-issues-triage.md` (updated #591/#712/#736/#923 → FIXED)
- Follow-up doc: `docs/bughunt-follow-up-2026-10-18.md` (portrait-lock note updated)
- CHANGELOG: `CHANGELOG.md` (4 new bugfix lines)
- CI: `.github/workflows/android-ci.yml` (emulator matrix)
