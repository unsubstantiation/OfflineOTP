# Shared Compose device-verification checklist

Use a disposable vault and backups. Do not test destructive key-store reset against your only copy.
The user reports non-iOS builds/platform checks verified. This checklist has no per-scenario
result log; iOS remains build-blocked. Use it for candidate-specific evidence, not to negate that report.
Record OS/build/flavor, device, steps and observed result for failures.

## Build and presentation
- [ ] Real Android Gradle build and instrumentation run; real macOS/Xcode KMP + Swift build.
- [ ] Android, desktop and iOS: common screens/dialogs render with small/large windows, landscape,
      keyboard open, large text, dark/light modes and safe/system insets.
- [ ] Scroll through long import previews and settings; focused controls remain reachable.
- [ ] TalkBack/VoiceOver announce changed error/status text politely, without duplicate Toasts.
      Empty status messages create no empty announcement. Verify desktop keyboard/screen-reader access.

## Security and lifecycle
- [ ] Password/IME/autofill, visibility toggle, accessibility/autofill blocking and screenshot policy
      behave as configured. No credential field restores a secret after recreation.
- [ ] Lock/background/rotate/destroy while a dialog, picker, biometric or USB/NFC operation is pending.
      Late callbacks must neither mutate a newer session nor show old-session feedback.
- [ ] Retry/cancel and rapid repeated identical notices: old dismissals/timers do not clear new notices.
- [ ] Setup create/restore, Back, busy/completed navigation and Activity recreation preserve authority.

## Vault and common forms
- [ ] OTP/password/email/SSH/note add/edit/delete, reveal/copy, tags, sort, filtering and reorder.
- [ ] Invalid input/storage failures in entry editors, tags, card layout, auto-lock, preferences,
      password change and confirmation dialogs show common in-form status and allow safe retry.
- [ ] Email/password/Thunderbird/LinSCP/CSV import/export: success, corrupt/read/write failure,
      canceled picker, empty data, duplicate confirmation and failure retry; check buffer cleanup.
- [ ] Google multipart QR: duplicate/wrong-batch/ordinary OTP/invalid input, scan cancel/failure,
      image picker failure, complete preview/import, duplicate counts and lock while staged.
- [ ] Image QR: oversized/unreadable/no-QR/checksum/format failure uses the visible dialog/page.

## Settings, backup, About
- [ ] Settings policy/credentials/encryption/provider/signing/biometric/backup preferences preserve
      existing behavior. Native permission, biometric, device-credential and provider sheets stay native.
- [ ] Settings import: same-session success after recreation, skipped counts and secrets-present notice;
      rotate again or replace the session and verify no unintended replay of the import or notice.
- [ ] Backup encrypted/OpenPGP export/restore success and closing picker failures return one allowlisted
      result to the matching vault frame; notices contain no document contents, labels or filenames.
- [ ] Detached post-restore auto-backup failures remain visible through their native background adapter.
- [ ] About generated licenses, URL/clipboard fallback, version-tap count/reset and feature opt-in.

## Portable vault copies and migration
- [ ] Create a new password vault on each platform; reject device-only setup/no recovery password.
- [ ] Open/save a legacy password or device-only vault: original format stays unchanged until explicit migration.
- [ ] Migrate with wrong/correct password, cancel before promotion, interrupt during backup/promotion,
      simulate full/read-only storage; verify original rollback bytes and independent copy recovery.
- [ ] Android device-only migration first requires explicit password authentication/encryption;
      existing portable vaults cannot downgrade through the encryption settings or stale callbacks.
- [ ] Full manual/automatic copy matches saved vault bytes exactly; restore on another platform without
      original preferences/device credentials. A separately encrypted export still uses its own password.
- [ ] Automatic full-copy mode resumes after unlock, works after biometric/hardware quick unlock,
      stops on lock/disable, ignores stale completions and never falls back to encrypted exports.
- [ ] Desktop/iOS: coalesced saves, periodic/manual scheduling, separate `.offlineotp` retention,
      exclusive creation, private permissions, iOS Files grant lifetime; verify provider failures.
- [ ] Existing v1 password/v2 OpenPGP configurations retain their mode. Session signing remains
      paused until reloaded, never silently replaced by unsigned output or a full-file copy.
- [ ] Copies made before a password change still require the old recovery password. Verify restored
      automatic configuration either resumes correctly or visibly requires reconfiguration.

## Hardware and authentication
- [ ] Standalone password/biometric/hardware unlock, wrong PIN/password, cancellation/lockout and retry.
      Recoverable error stays in the shared form; fatal closing-path feedback remains native.
- [ ] Hardware discovery/list/copy/enrol/remove with USB/NFC: status, touch/PIN, cancellation and lock.
      Uncertain outcomes remain readable; stale callbacks cannot store credentials or replace status.
- [ ] Retest platform-only capabilities explicitly; unsupported desktop/iOS capabilities must not be
      silently presented as operational. Check all supported Android flavors.

See `shared-ui-native-validation.md` for detailed historical scenarios and workflow limitations.

## Newly added capabilities (source-wired, native verification pending)
- [ ] Desktop one-frame camera: FFmpeg installed/missing, each OS device selector and permissions,
      timeout/cancel/lock, invalid/no-QR frame, hidden review and Google multipart continuation.
- [ ] Desktop/iOS automatic backup: valid/invalid folder, password mismatch/length, interval/retention
      bounds, fresh encrypted backup restore on another client, no overlapping jobs, manual run,
      disable/lock/close during queued/encrypting/writing work and stale timer/UI delivery.
- [ ] Retention after successful writes only: preserve unrelated/manual files and symlinks; verify
      newest matching files kept, failures disable visibly, old backups survive failure/disable.
- [ ] iOS Files folder selection: cancel/denial/read-only/offline provider, coordinator/session swap
      while picker is open, coordinated writes, security-scoped grant release, background stops work.
- [ ] NFC and USB-C independently: OATH list/protected password/touch/calculation, FIDO-only key,
      wrong/reset/different key, changed account identity, duplicate callbacks, disconnect/timeout.
- [ ] HOTP: explicit one-use counter consent, no calculation on list, no automatic retry after an
      uncertain response, no stale callback revealing a code, disclosure/clipboard expiry on time.
- [ ] iOS PRF enrollment/unlock/removal independently over NFC and USB-C: UV/UP, PIN/no-PIN built-in UV,
      wrong key, unsupported PRF, cancelled registration possibly leaving a credential, password fallback,
      local-record protection and cross-client envelope compatibility. OATH password is not a FIDO PIN.
- [ ] iOS vault-to-key copy: explicit one-use seed-export consent, default touch, correct initial HOTP
      counter, duplicate-ID rejection without overwrite, UTF-8/period-prefixed names, changed source
      entry, cancel/background/lock during connect/list/write, uncertain response with no automatic retry.
- [ ] Backup credentials persist encrypted across restart and resume only after unlock/foreground;
      wrong/recovered/new vault key visibly requires reconfiguration; forget removes local settings.
      Background with relock disabled must not restart from queued initialization or stale picker delivery.

## Additional save/picker lifecycle acceptance
- [ ] Successful saves trigger one coalesced backup; UI-only changes and failed writes do not.
      Save during encryption/writing queues one fresh catch-up snapshot without overlapping jobs.
- [ ] After relaunch/unlock, verify the displayed backup destination/interval/retention match the saved
      encrypted configuration, with password fields empty. Retired timers cannot run a new session.
- [ ] Cancel/replace/background during queued backup work before the worker takes input; no export starts.
- [ ] Native Files/QR/camera result after surface replacement, lock/re-unlock or background is ignored;
      a stale picker delegate cannot invoke/cancel a new request's callback.
- [ ] Private export scratch directory is cleaned on completion/cancel/background/session replacement;
      document provider may already have copied a started export (cancellation cannot revoke that copy).
- [ ] Import files with short reads/unknown sizes/changing size: enforce limits while reading, never
      silently accept a truncated prefix. Recheck provider availability and safe retry behavior.
- [ ] Edit an entry with opaque newer-client fields on every host, then reopen on another client;
      fields survive manual-form and structured email/password replacement, not only no-op saves.

## Atomic shared settings acceptance
- [ ] In card display, change several fields, then simulate unavailable/full settings storage before
      confirming. No selected field changes on disk/in the current frame; the form stays retryable.
- [ ] Repeat for inactivity timeout plus background lock. On failed save, the previous timeout and
      background policy remain active. A desktop save spanning the old deadline must still lock.
- [ ] Retry after restoring storage and reopen the app; the complete new selection/policy persists.
- [ ] On Android, load unknown card-layout/tap/tag/search tokens and malformed grouping values.
      The vault remains usable, unknown tap actions do not reveal/copy, supported search fields
      survive mixed unknown values, and reads do not overwrite the stored newer-client values.

## Unsigned automatic OpenPGP acceptance
- [ ] Configure verified public certificates; test a restore with each intended private key. Confirm
      the entire vault (including concealed entries) is encrypted and no plaintext file is produced.
- [ ] Relaunch/unlock and verify the mode, destination and schedule resume. Replacing/deleting the
      original public-key files must not change recipients or stop a configured snapshot from working.
- [ ] Invalid/expired/revoked certificates and an unavailable engine fail visibly without publishing
      a backup. No password or plaintext fallback. The UI must continue to say unsigned.
- [ ] Cancel/lock/background before queued encryption; no file starts. A write already underway may
      finish. Re-enabling cannot publish an old completion or re-arm an old timer.
- [ ] Switch password/OpenPGP modes; retention removes only matching current-mode files and leaves
      prior-mode/manual/symlink files intact. Verify 32 KiB combined and 64-recipient admission limits.

## Session-only signing acceptance
- [ ] Enable signing with a verified private signing key and passphrase; decrypt with each recipient,
      then verify the inner signature using an independent trusted OpenPGP tool and signing certificate.
      In-app restore must not claim that signer identity was verified.
- [ ] Incorrect passphrase, revoked/expired key, no data-signing capability, missing/old native library:
      fail without a file and without any unsigned retry. Rebuild native binaries with new bindings.
- [ ] Inspect encrypted configuration after unlocking it in a test harness: only `requiresSigning`
      and public certificates are present; no private signing key, passphrase or private-key path/grant.
- [ ] Lock/background/vault close revokes queued signing work; reopening shows a signing-credentials
      reload requirement, not an unsigned backup. Existing public-recipient snapshots remain intact.
- [ ] On iOS, verify signing key selection opens in place with a temporary security-scoped read grant,
      without an app-owned imported document copy or persisted signing-file bookmark.


## OATH mutations and retired Android compatibility trees
- [ ] On each host, delete only the selected hardware account after typed `DELETE`; wrong key,
      ambiguous/stale account, wrong password, cancel/disconnect/timeout must never trigger retries.
      Read the key after uncertainty; the vault remains unchanged, and no HOTP calculation occurs.
- [ ] Rename with typed `RENAME`: preserve seed/algorithm/digits/period/HOTP counter; reject destination
      collisions and unchanged IDs. Verify UTF-8 64-byte boundaries, nondefault periods and literal
      numeric-slash names. Verify unsupported firmware fails without overwriting any credential.
- [ ] On iOS, a noncanonical raw old ID disables rename because of the SDK's reconstructed-ID API;
      verify another account with that reconstructed ID cannot be renamed instead. Test NFC/USB-C
      independently. Safe refusal is not equivalent to unrestricted rename parity.
- [ ] Cancel a hardware mutation prompt, open its replacement, then deliver any old confirm/dismiss
      callback: it neither changes the key nor closes the replacement. Background/lock clears secrets.
- [ ] Android Hardware and Hardware Unlock contain only Compose UI, with no hidden EditText/Buttons.
      Verify password entry, HOTP consent/expiry/copy, cancellation, touch, reconnect and accessibility.
- [ ] Android Backup contains only Compose UI. Confirm OpenPGP/key-file/provider availability and
      warnings, busy admission, picker cancellation and vault relock retain their existing semantics.
      Choose replace/merge, recreate while the picker is open, then verify that exact mode resumes
      after authentication. No hidden Switch or Spinner supplies operation state anymore.
