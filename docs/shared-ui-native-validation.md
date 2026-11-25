# Shared Compose migration: native validation handoff

The source/JVM checks are not a release sign-off. The current workspace has no Android SDK,
emulator or Xcode, and GitHub workflow dispatch returned `403 Resource not accessible by integration`.
No native workflow was started by that rejected request. Do not weaken workflow permissions or
introduce credentials into the repository to bypass the connection's Actions permissions.

## Run the existing workflows

With an authorized GitHub connection/account, select branch `arena/22c4b5b2-offlineotp` in Actions:

- **Android build** (`android.yml`): includes emulator execution of `ComposeVaultDeviceTest` and
  `IssuerArtworkDeviceTest` for the configured flavors.
- **iOS build** (`ios.yml`): use the default macOS runner unless runner capacity requires a different
  supported image. This must compile the new scanner controls KMP/Swift interface.
- **Compose desktop validation** (`compose-desktop-validation.yml`): real Gradle/Compose artifacts
  across the configured Windows/macOS/Linux matrix; JVM sandbox stand-ins do not replace this gate.

Equivalent CLI dispatch commands (run outside the sandbox with Actions permission):

```sh
gh workflow run android.yml --ref arena/22c4b5b2-offlineotp
gh workflow run ios.yml --ref arena/22c4b5b2-offlineotp -f runner=macos-15
gh workflow run compose-desktop-validation.yml --ref arena/22c4b5b2-offlineotp
```

Retain run links, exact tested commit and sanitized build/test logs. Do not attach vault files,
real provisioning payloads, passwords, derived keys, preference exports or recovery material.

## Added instrumentation coverage

`ComposeVaultDeviceTest` now includes tests for shared Settings busy/closed navigation, setup-shell
native content attachment/release, About dependency version/full-license rendering and credential
shell dismissal/input clearing/replay rejection. These run with fixture values only. The test source
compiles under the sandbox gate, but **has not executed on a device or emulator**. A component-host
attachment test does not prove full material-intro fragment restoration/reparenting is correct.

## Required integration checks beyond component tests

Use a disposable test vault and backups, never the user's live vault.

1. First run: welcome → encryption → credentials → platform-backup information → completion.
   Rotate/recreate at each step, including empty/invalid credential confirmation. Test create and
   encrypted-backup restore, picker/password cancellation, failed restore/retry and single completion.
   Verify native pager chrome does not reappear, Back is blocked during work and no credential is
   restored into a saved hierarchy or Bundle. Confirm the one-use key handoff still opens the vault.
2. Vault chrome: small phone, landscape, large text, keyboard and TalkBack. Test all five categories,
   tags (including untagged and SINGLE rule), sort, search, clear filters and creation. Ensure hidden
   drawer/FAB/menu views cannot take focus or intercept gestures and every former action is reachable.
3. Settings: busy Back blocking, canceled/stale dialogs, lock/reunlock while prompts are open and
   Activity recreation. Test authentication/encryption transitions using disposable data; ensure
   invalid combinations are rejected before any write and a failed transaction leaves a usable vault.
4. Protected input: password visibility toggle, confirmation, IME, accessibility/autofill policies,
   empty separate-backup credential clear, cancellation and process death. Existing backup passwords
   must still open existing backups after source changes; plaintext removal requires explicit consent.
5. Biometric quick unlock: unavailable/not-enrolled/cancel/lockout/success, rotation and lock while the
   OS prompt is open. A revoked prompt must never persist a shortcut. Test native KeyStore invalidation
   and recovery separately. Authorized destructive reset is intentionally absent from automated tests.
6. Provider OpenPGP: discovery, absent provider, installation/uninstallation, provider switch, signing
   key selection/cancellation, lock/recreation while provider UI is open, and service disconnection.
   Late or replayed results must not change settings; services must be unbound after completion.
7. About: both flavors, real generated license catalog/version metadata, license text/URLs, external
   browser absent/clipboard fallback, donation links and canceled special-feature opt-in.
8. Google multipart import: camera/image routes, duplicate/mixed batches, cancel, lock/reunlock and
   stale confirmation; import once only and wipe every canceled buffered account.
9. iOS: build exported scanner controls interface, camera permission denied/unavailable/cancel/success,
   VoiceOver, modal dismissal and backgrounding. VisionKit must stop scanning when its host disappears.
10. Desktop: real Compose window/keyboard/scroll/render tests; verify unchanged supported imports,
    exports, editors and settings on each release OS. Account for capability-specific native adapters,
    not just equal menu labels. Device-only hardware/biometric tests require appropriate hardware.

The migration remains open until these gates provide evidence. Keep hidden compatibility engines
until native restoration/default/persistence tests justify removing them; source removal alone is
not proof of feature parity.

### Busy-state follow-up

Settings/Backup/hardware grouped actions now expose disabled semantics during work rather than
merely ignoring clicks in the host. The Settings component test asserts that both Back and the
sample action are disabled. Verify this on real Compose, including detailed action rows and TalkBack;
no emulator execution is implied by the local test-source compilation.
