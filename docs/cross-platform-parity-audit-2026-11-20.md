# Cross-platform feature parity and legacy-retirement audit — 2026-11-20

## Scope and conclusion

Source audit of the active Android, Compose desktop (Windows/macOS/Linux), and iOS UIKit/KMP
hosts, on the branch based on master d946ff4e. Includes this branch's common status/insets changes.
**Shared presentation is broadly wired, but identical feature coverage is not established.**
This audit is not an Android/iOS build, device test, screenshot comparison or field-by-field
round-trip proof. Older reports such as `cross-build-feature-audit.md` are historical: their claims
that Android still mounts native About tabs or iOS still mounts SwiftUI vault pages are obsolete.

Classification: **wired** = active host dispatch and common presentation found; **difference** =
platform-specific capability/semantics; **gap** = no equivalent active route found in inspected
hosts; **unverified** = requires deeper semantic fixtures or native execution. Wiring does not
imply that a feature has been tested or that all options/defaults are equal.

## Active host evidence

- Android: `Activities/MainActivity.kt` (`publishComposeVault`, action callbacks),
  `View/AndroidVaultController.kt`, `Activities/{Settings,Backup,Authenticate,About,HardwareKey}Activity.kt`;
  `ui/.../host/ComposeDialogViews.kt` and `ui/.../vault/VaultComposeView.kt`.
- Desktop: `desktop-compose/.../ComposeMain.kt` (`VaultContent` and presenter callbacks),
  `ui-model/.../vault/VaultPresenter.kt`, `desktop/.../control/Desktop*` operation controllers.
  All desktop OS builds use this host; filesystem/clipboard/PCSC integration is OS-dependent.
- iOS: `ios/App/Sources/{AppRootViewController,ComposeVaultHost,ComposeUnlockHost,VaultStore}.swift`;
  `ui-foundation/src/iosMain/.../PortableVaultViewController.kt` dispatches surface keys to common
  forms. `ios/project.yml` requires OfflineOTPPresentation.xcframework. There are no old
  SettingsView/BackupView SwiftUI files in App/Sources to delete in this checkout.

## Feature matrix

| Feature family | Android | Desktop | iOS | Audit finding / remaining acceptance |
|---|---|---|---|---|
| Vault categories, search, sort, tag filters, reveal/copy, move | Main/controller + common vault | VaultContent/presenter + common vault | ComposeVaultHost selectTab/search/card + PortableVaultPresentation | Wired; runtime keyboard/accessibility/order persistence unverified. |
| OTP, note, SSH and password editing | Shared manual/password forms | Shared creation/editing forms | Portable surface editor routing + Swift document builders | Wired. Field/algorithm coverage and unknown metadata preservation require semantic fixtures, not renderer equality. |
| Email accounts, identities/settings/password editing | Shared email forms | Shared email forms/controllers | Common forms through surface routing; Swift validation/builders | Wired. Equal validation/defaults for every identity/server option are unverified. |
| Browser CSV and stored password archive import/export | MainActivity transfer routes | DesktopPasswordImport/Export and archive controllers | backupSurface password/csv/archive routes | Wired. CSV only edits common columns; extra/foreign columns may be preserved rather than fully editable. Do not call this universal field-edit parity. |
| FairEmail, Thunderbird, LinSCP transfers | MainActivity + common dialogs | Desktop*Import/Export + common dialogs | backupSurface + common forms + native readers/writers | Wired; encrypted/master-key/cancel/failure/retry round trips require native checks. |
| Encrypted backup restore/export and merge/replace | BackupActivity | Desktop backup controllers | backupSurface/restore surface + VaultStore | Wired. Mobile/background storage authority and key handling differ by adapter. |
| OpenPGP key-file backups | In-app engine and optional external provider | In-app key-file engine | openPgpSection explicitly checks build availability | Difference: engine availability is build-dependent. Android provider service/signing UI is Android-only, not a missing Compose widget. Desktop OpenPGP hardware/agent integration is not implemented by this host. |
| QR URI/image and Google multipart review | MainActivity + common progress/confirmation | URI/image controllers + common dialogs | uriImport/qrImageFromTools + common forms/native readers | Wired; decoder and scanner adapters differ. |
| Camera QR capture | Android camera adapter | Common capture dialog + optional local FFmpeg one-frame adapter | qrCamera route + QrCameraScanner.swift | Desktop source route added after audit; **conditional on FFmpeg/device/permission**, no live preview or native test evidence. See desktop-camera.md. iOS camera exists; older image-only claim is obsolete. |
| Card layout, search fields, tag rules, sort preferences | Shared Settings routes | Presenter configureLayout/configureSearchFields | settingsSurface/cardLayout/searchIncludes/tagRule | Wired subsets. Setting-by-setting defaults/export catalogs still need a dedicated semantic comparison. |
| Auto-lock, password change, storage location, portable preferences | Settings/Main routes | Presenter/controller routes | settingsSurface routes | Wired. Native folder grants, background policies and preference key catalogs are intentionally not interchangeable. |
| Automatic backup configuration/credentials/retention | Explicit Settings routes and native workers | Common session-backup form + worker/timer/retention | Compose portable settings + scoped Files folder/worker/timer/retention | Source routes added. **Still different:** vault-encrypted local credentials/options; OpenPGP encryption and session-only signing added; no signer-identity verification on restore or OS background scheduling; see automatic-backups.md. |
| Quick unlock | Android biometric/device credential + hardware | Enrolled PCSC FIDO PRF hardware | Native biometry/Keychain and enrolled NFC/USB-C PRF source routes | Difference: platform capabilities, not identical mechanisms. Never show unsupported device auth/USB features as working. |
| Hardware management | HardwareKeyActivity common page + USB/NFC | HardwareCopy/Oath/enrol/remove common forms + PCSC | Common portable OATH list/calculation/delete/conditional rename, vault-to-key copy and PRF enrollment/unlock/removal with explicit NFC/USB-C | Source routes added; iOS noncanonical-ID rename refusal, password/reset management and native verification remain. See ios-hardware.md. |
| About/version/catalog/libraries | Common AboutContent + generated Android libraries + flavor metadata | Common AboutDialog + build/dependency metadata | Common about surface + native build metadata | Wired shared catalog; dependency inventories legitimately differ. Compare generated notices per artifact. Old Android fragment UI is removed by this audit. |
| Recoverable status/errors | Shared page/form feedback | Common dialogs/status and host feedback | Common dialogs/status and host feedback | Common status renderer consolidated. Fatal closing-task and detached background feedback retain native adapters. Announcement timing still unverified. |
| Navigation, insets and lifecycle | View/Compose hybrid; host-level bar/cutout/IME insets | Window/keyboard lifecycle adapters | UIKit/KMP lifecycle and scanner adapters | Difference. Actual Android overlap fix and iOS/Desktop safe-area/focus behavior require native verification. |

## Legacy deletion performed

Removed Android `BaseAboutFragment`, F-Droid/Play `AboutFragment`, `fragment_about.xml`,
`component_about.xml`, F-Droid `part_author_extra.xml`, and their private `about_libraries_*`
attributes/style assignments. Repository reference search found no active inflater, manifest or
navigation consumer of those layouts; AboutActivity already mounts AboutScreenComposeView directly.
The sole production fragment reference supplied flavor links, now plain `Utilities/AboutFlavorLinks`
metadata in each flavor. F-Droid retains both donation URLs; Play retains an empty extras list.

Removed fragment-only sandbox fixtures and checks that required these dead views/API shapes.
Coverage now targets the active Compose AboutActivity, generated metadata, flavor links, tap-count
feedback and destruction. Python guards check shared catalog routing and ensure deleted UI does
not return. This does not remove dependency-license generation or shared About catalog content.

## Explicitly retained legacy/adapters and why

- SettingsFragment and preference resources: still participate in preference listeners/summaries,
  settings callbacks and external provider compatibility. Hidden is not the same as unused.
- Android main legacy controls/controller and intro pager: active interaction/state restoration
  adapters remain. Delete only after extracting their authority and running restoration tests.
- Protected native credential fields: autofill, IME, accessibility and security policies; not dead UI.
- System camera/file pickers, Keychain/Keystore, biometric/device-auth/provider sheets and hardware
  transports: native capabilities by design.
- Resource strings/drawables not proven unused across flavors/localizations: no speculative purge.

## Follow-up work, in order

1. Complete remaining persistent/background/OpenPGP backup workflows and iOS FIDO PRF hardware
   unlock/credential-management support; validate new camera, session-backup and OATH routes natively.
   Do not remove Android features to manufacture parity.
2. Build a per-setting/default/codec and per-entry-field round-trip matrix with shared fixtures;
   common forms alone do not prove semantic parity of Swift and JVM builders/storage policies.
3. Run the native acceptance checklist (`shared-ui-device-checklist.md`), including Android insets,
   recreation/process death, provider/camera, protected inputs, and desktop/iOS keyboard/accessibility.
4. Extract Settings/intro/controller adapter state before removing further compatibility trees.

All current local gates are documented in shared-ui-migration-status.md. Runtime capability parity
and the native deletion/restoration gates remain unverified; this audit deliberately does not
certify a fully equivalent product on every build.

## Follow-up: desktop camera implementation

The desktop URI import form now opens common CameraCaptureDialog and captures one frame through a
bounded local FFmpeg adapter (V4L2/AVFoundation/DirectShow). Existing worker ownership and hidden QR
review/OTP/migration submission are reused. Cancellation terminates capture; raw images are not
written to disk. See `desktop-camera.md` for the explicit runtime dependency and limitations. This
changes desktop camera from a missing source route to a conditional, native-unverified capability;
it does not close automatic-backup or iOS hardware-management gaps.

## Follow-up: iOS hardware and automatic backups

User selected **both NFC and USB-C**. Native YubiKit OATH enumeration/password/calculation now uses
shared Compose surfaces, per-operation delegate epochs, same-key/raw-credential checks, explicit
one-use HOTP consent, timeout/cancellation and code/clipboard expiry. No fake FIDO unlock is exposed.
Portable Swift policy/app tests are added but have not run here. See `ios-hardware.md`.

Desktop and iOS now persist automatic-backup credentials/options in a vault-encrypted local record,
resume scheduling only after unlock, and surface reconfiguration after key change/recovery.
Detached snapshots, interval/manual runs, retention and failure status remain native-owned. iOS
adds security-scoped encrypted bookmarks and coordinated Files writes. These are not OS background
jobs or automatic OpenPGP flows. See `automatic-backups.md`.

The user selected migration to the official PRF-capable successor SDK. iOS now pins
`yubikit-swift` v1.5.0 and has source routes for PRF enrollment/removal and unlock over both NFC and
USB-C, preserving the desktop/Android envelope and fixed offline RP. Required UV/UP, credential
allowlisting, cancellation epochs and SDK-log suppression are implemented. Swift tools 6.1/Xcode
build and native transport verification are still outstanding; see `ios-hardware.md`.

Executed current checks: Python 680 passed; desktop 418 passed / 2 skipped; common UI 168 passed
and scheduler 3 passed, with desktop/Android host stub type-checking. Native Swift and physical
behavior are not certified by these checks. OATH credential-management and the broader settings/entry semantics matrix remain open; this is not full cross-platform parity.

## Follow-up: vault-to-key copy and lifecycle review

Added explicit iOS vault-to-key TOTP/HOTP seed copy with per-write consent, default touch, fresh
entry-field/session checks, duplicate raw-ID rejection and NFC/USB-C selection. Shared naming and
admission vectors execute on the JVM and have Swift consumers. Copy is not hardware seed extraction.
The native owner now permits injected connections and disposes late PRF results on cancellation,
timeout or replacement; Swift fake-native tests are added, not executed. A queued initial/foreground
backup restore is now fenced by foreground state, including when background relocking is disabled.
Latest executed checks: Python **682 passed**; desktop **419 passed / 2 skipped**. No common UI
source changed in this follow-up. See `ios-hardware.md` for native acceptance still required.

Native unlock actions also reject deferred background delivery. Enrollment now keeps its queued
vault-key snapshot in a revocable one-use owner: cancellation/timeout clears it before a late
connection can retrieve it. Added Swift one-use/revocation tests; they still require Xcode.

## Opaque entry metadata preservation follow-up

Found and fixed a concrete field round-trip difference: the JVM entry model dropped unrecognized
keys while Swift retained them. JVM load/save now preserves bounded opaque extension fields in a
detached representation, with depth checks before serializing direct object graphs. Typed known
fields remain authoritative. Android replacement, common manual edit and desktop replacement
transaction paths retain the original extensions. A shared fixture covers nested/null/Unicode
metadata; executed JVM tests cover edits, reload, alias isolation, destruction and cyclic/deep
rejection. Swift typed-edit round-trip tests are added, not executed. This closes that data-loss
path, not the entire per-setting/entry-field semantic matrix.

Latest local validation: Python **684 passed**, desktop **424 passed / 2 skipped**, shared UI
**168 passed**, scheduler **3 passed**, Android **28 test files compile against stubs**, sandbox
checks pass. Whole-vault output bounds now reject overlarge/deep extension combinations before
writing an unreopenable vault. NFC/USB-C transport capability reporting is separate in shared
unlock and iOS portable forms. Swift/native acceptance remains unexecuted, not implicitly passed.

## Continued save-triggered backups, editor and native-file boundary work

- Added coalesced successful-save backup triggers on desktop/iOS, catch-up after an in-flight run,
  generation/foreground fences and per-schedule timer identity. Periodic/manual runs remain; no
  locked-vault or OS background backup authority was added.
- Queued backup inputs are revocable rather than captured directly by worker closures. Cancellation
  drops queued document/password references and wipes the JVM password buffer before the worker runs.
- Backup forms now display/restored destination, interval and retention without preloading passwords.
- Closed additional opaque-field loss paths: synchronous desktop replacement, Swift manual-draft
  metadata preservation and central iOS replacement. Added actual replacement/disk-round-trip tests.
- Native Files results are bound to picker identity, origin coordinator/session/surface and foreground
  epoch. Old delegate callbacks cannot claim newer handlers. Private UUID export directories and
  protected files are removed on cancellation/background/session replacement. Bounded streaming
  reads replace size-preflight-only reads in the picker, vault byte store and backup credential store.

Latest executed checks: Python **687 passed**; desktop **430 passed / 2 skipped**; common UI
**168 passed**; scheduler **3 passed**; sandbox checks passed; Android **28 test files compile against
stubs**. Swift file/picker/queue/replacement tests were added but are not executed in this Linux
environment. Native compilation/device/provider acceptance still remains separate from these gates.

### Settings file validation/persistence follow-up

Kotlin numeric settings/version parsing no longer coerces strings, rounds fractions or wraps longs;
Swift uses the same Int32 range with exact string conversion rather than lossy NSNumber conversion.
Both apply UTF-16 scalar/set-element text bounds. iOS imports now use a single throwing atomic
plist merge and publish cache only after disk success, with native failure/retry tests added.
This fixes concrete import-integrity differences without pretending platform-addressed catalogs
are interchangeable. Latest core gate: 349 passed; Python source gates: 688 passed. Swift remains
uncompiled/unexecuted here.

### Atomic display/security-form persistence follow-up

Production desktop/iOS common card-display and idle/background-lock forms now save validated batches
atomically before changing the live view/policy. Added desktop disk/cache rollback, reopen, retry,
request identity and elapsed-deadline tests; equivalent native Swift tests are added but unrun.
Android display/search/group/tap readers now recover safely from unsupported stored tokens without
rewriting them. Known search tokens remain usable when mixed with future ones; unknown tap actions
are inert. Security/provider settings deliberately do not inherit display fallback logic.

Latest executed gates: desktop 436 passed / 2 skipped; shared UI 168 + scheduler 3 passed; Python
689 passed; Android 28 stub-compiled test files; sandbox all passed. Remaining semantic cross-client
settings transfer, automatic OpenPGP modes and native acceptance are not closed by this change.

### Automatic OpenPGP encryption follow-up

Desktop/iOS now expose unsigned automatic OpenPGP encryption through their Compose workflows,
with vault-encrypted public-certificate snapshots, version-2 local configuration, 32 KiB/64-recipient
bounds and mode-scoped `.pgp` retention. The password route remains compatible. Both modes share
revocable inputs and successful-save/periodic/manual scheduling. Signing/private-key persistence
is not implemented. Latest checks: desktop **441 passed / 2 skipped**, shared UI **168 passed** plus
scheduler **3**, Python **690**, Android **28 test files stub-compiled**, sandbox all passed.
Swift sources/tests remain uncompiled; real OpenPGP-engine and Files-provider/device acceptance
remain separate. See automatic-backups.md for the exact limits and trust model.

## Session-only automatic-backup signing follow-up

The user explicitly chose session-only signing credentials. Added optional signing to desktop/iOS
OpenPGP backup routes, with revocable session-owned private-key/passphrase inputs and a persistent
`requiresSigning` boolean only. Signed configurations pause after unlock until credentials are
reloaded; no unsigned retry or silent downgrade. iOS opens private key documents in place instead
of requesting an app-owned imported copy. JVM inputs/snapshots are wipeable; immutable/COW copies
and started crypto cannot promise erasure/cancellation.

The Rust signed encoder and tests were added, and Kotlin/Swift bindings were regenerated using
UniFFI 0.28.3; `generate-bindings.sh --check` passed. Rust compilation/crypto tests and Swift/native
execution have **not run** in this environment. Native libraries must be rebuilt with the bindings.
In-app restore still does not verify signer identity; both forms warn to use a trusted OpenPGP tool
and independently verified certificate for signature verification.

Latest executed JVM gates: **351 core tests**, **443 desktop tests passed / 2 skipped**;
**168 shared UI + 3 scheduler tests**; **691 Python checks**. Signing native tests are authored,
not native acceptance evidence. Settings cross-client semantics and native device/provider checks
remain outstanding beyond this implementation.

## Backup reconfiguration and asynchronous file setup follow-up

Fixed implicit recipient replacement when adding desktop certificates to saved snapshots. New
worker setup validates the complete combined set off the UI thread, revokes queued private inputs,
wipes borrowed prepared data after apply, and fences retired UI deliveries. Added real-file tests
for merging, bounds/duplicates, input ownership, setup cancellation, replacement, failure and retry.
The shared form freezes configuration controls while setup is pending.

iOS form navigation now clears private drafts without discarding public snapshots required for
signer reload. Mode changes/clear/disable retire native picker authority. Resume is independent of
stale form choices. NativeFileReader moves provider reads off the main thread, transfers temporary
security-scoped access to the read, and cancels queued callbacks/owned results on replacement or
background/session teardown. Added native reader cancellation/bounds/deallocation tests; unrun here.

Latest executed: desktop **450 total / 448 passed / 2 skipped**; Python **693 passed**; shared UI
**168 passed** plus scheduler **3 passed**. Swift/Rust native execution remains unverified; these
checks do not close the full parity/native acceptance audit.


### OATH single-account deletion follow-up

Android and desktop now share `HardwareOathDeleteDialog`, with account-bound typed `DELETE`
confirmation and fresh OATH password entry. iOS renders the same confirmation semantics through
its shared portable Compose surface, for both NFC and USB-C adapters. Each native operation
reopens/unlocks the key, checks device identity and uniquely matches the raw credential ID and
account metadata before one deletion command. Deletion never calculates a code or advances HOTP.
Cancellation is checked immediately before the irreversible command; already-started effects
cannot be undone. No automatic retry is made. Success and uncertain failure retire the account
list; the vault is not mutated. Android also rejects stale list-revision confirmations.

Headless validation: desktop 454 total / 452 passed / 2 skipped; core 351 passed; shared UI
168 + 3 passed; Android sandbox checks passed (including deletion cancellation/stale confirmation).
Swift native compilation, NFC/USB-C execution, Rust execution and physical-key verification remain
unavailable here, not established by shared UI compilation. OATH rename/password/reset management
is still not implemented by the desktop/iOS hosts; deletion does not imply full key-management parity.

Deletion follow-up validation also covers inactive desktop delivery: it clears busy/stale account
state and permits a fresh read, while replacement requests remain identity-fenced. Hardware labels
are bounded and stripped of directional/format/control separators before confirmation on all hosts.
Latest Python source/contract suite: 695 passed. Real native transport acceptance remains deferred.


### OATH rename and hidden-control retirement follow-up

- Added OATH single-account rename to Android/Desktop common Compose dialogs and the iOS portable
  Compose surface. Typed `RENAME`, fresh OATH password, exact device/raw identity, unique match,
  no-op/collision refusal, UTF-8/period-prefix validation, cancellation and no automatic retry are
  enforced. Hardware state is retired after mutation; vault/seed/counter/period remain unchanged.
- Added a shared JVM/Swift rename naming corpus. iOS explicitly disables rename for noncanonical
  old raw IDs because the SDK reconstructs that ID; this is a native API limitation, not parity.
- Removed the hidden Android hardware-management and hardware-unlock widget trees, secret-bearing
  `EditText` compatibility buffers, and native Button/proxy-click account dispatch. Native state now
  projects directly into common Compose, with revision-bound account keys and revocable CharArray
  ownership for HOTP confirmation. Stop/relock/replacement wipe pending credentials and clear codes.
- Removed Backup's hidden Spinner/Buttons/Switch/TextViews/progress tree. Availability, warning,
  busy and restore mode are explicit native adapter state; common Compose is the only page UI.
  Restore mode is now explicitly saved/restored across recreation. File pickers, OpenPGP provider
  service, crypto, relock/continuation and document-authority adapters remain native and retained.
- Updated sandbox checks to drive the actual shared hosts/native state rather than hidden widgets.
  They cover clock rollback, expired codes, HOTP consent/password cleanup, stale rename/delete,
  cancellation, disabled backup actions, real restore-mode consent/picker admission and saved mode.

Headless evidence: desktop 458 total / 456 passed / 2 skipped; core 351 passed; shared UI 168 + 3
passed; Android sandbox checks passed; Android 28 instrumentation-source files stub-compiled;
Python source/contract suite 699 passed. Native Android/iOS/device and Rust acceptance remains
unverified. OATH password/reset management and broader semantic/native acceptance are still open.

Hardware mutation confirmation follow-up: Android checks the exact current prompt before consuming
its password or starting work, not only the list revision. Common mutation forms also revoke their
own confirm callbacks after submit/cancel. Regression checks cover dismissed prompts and callbacks
arriving while a replacement prompt is open; pending HOTP consent remains separately wipeable.
Swift host tests additionally reject forged indices/typed confirmations without a listed account
and reject mutation after dismissal/lock; these Swift tests remain unexecuted here.

Removed Settings' unreachable native-page navigation flag, obsolete saved-state key and dead back/
visibility branch. The common overview is always the page. The hidden Settings fragment is still
retained as a live native credential/provider/listener adapter, not declared dead or removed without
extracting that authority. Old `advanced` actions remain inert in the sandbox regression check.

## Portable recovery and full-file backups follow-up

The versioned, header-authenticated portable vault container is wired through JVM/Swift password
engines and Android password setup/rekey journal staging. Manual backup forms expose a saved-file
copy and an explicit password-verified legacy migration, preserving the original rollback file.
Full copies remain distinct from password/OpenPGP exports and require portable password recovery.

Automatic full-copy mode is source-wired across hosts: desktop/iOS use foreground save-coalesced,
periodic/manual scheduling and vault-encrypted v3 mode configuration; Android uses its existing
save-triggered durable retry/destination/retention path. **Difference:** Android has no new periodic
snapshot timer. Existing encrypted-export modes and session-only signing policy remain unchanged;
a paused signing requirement does not default to file-copy/unsigned mode. Portable Android vaults
cannot select device-only encryption in the shared form, and writes independently reject downgrade.

Independent container vectors, JVM migration-failure/cancellation, exact-copy/retention/configuration
and stale-work tests supplement source/stub gates. Swift tests and Android interruption/runtime/
provider scenarios have been added but not executed. See portable-vault-container.md and the device
checklist; this is not evidence of full native parity or release readiness.
