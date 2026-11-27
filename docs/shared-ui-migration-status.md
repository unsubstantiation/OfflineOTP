# Shared UI completion status

**Migration remains in progress; no release/native-runtime completion is claimed.**
This is the current source-level status, superseding older incremental entries in the ownership
log and the historical cross-build audit. Stub/JVM checks do not verify actual Android/iOS layout,
window attachment, accessibility, native service binding or secure hardware behavior.

## Portable vault-copy work (in progress)

See [the storage/recovery contract](portable-vault-container.md). New password vaults are
self-contained containers; JVM/Swift engines and Android password transaction staging understand
the format. Manual backup forms offer an exact authenticated saved-file copy separately from
password/OpenPGP exports. New Android setup requires password recovery; existing device-only
vaults remain unchanged until an explicit password/encryption migration. Existing containers
cannot be downgraded through Android's encryption-change transaction.

Independent Argon2/PBKDF2 vectors pass on JVM and are added to Swift/Android restore suites.
Latest available gates: core 355/355; desktop 463 passed, 2 skipped; Python 706/706; Android
sandbox checks passed; shared UI/headless seams passed; 28 Android instrumentation source files
stub-compiled. Swift and native/device execution remain unperformed.

Backup forms now expose explicit legacy password-vault migration. Automatic full-file copies
are separate from encrypted exports: desktop/iOS support saved configuration, save-coalescing,
periodic/manual runs and mode-scoped retention; Android reuses its save-triggered durable retry
path with an explicit source choice. It does not gain a periodic/locked-vault/background timer.
Existing export modes and paused signing requirements are preserved rather than silently changed
to file copies. Portable Android vaults cannot select device-only encryption in the shared form.
See the format document for migration/cancellation semantics and native validation still required.

## Source changes implemented

- Android main vault uses shared header, search, category tabs, tag filters, sort, add, tools and
  account cards. Toolbar, speed-dial, tag drawer and category controls are hidden compatibility
  engines; the drawer is locked closed. Settings and hardware capabilities are available from
  the shared tools. Every published action is guarded against session replacement.
- Google Authenticator multipart collection/progress and final preview use shared selection and
  confirmation forms. Buffered secrets remain host-owned and are destroyed on cancel/lock.
- Settings overview has shared routes for all active preference groups including authentication,
  encryption, biometric quick unlock, backup credentials/source/retention, special features and
  guarded destructive reset. No visible advanced native-preference destination is offered;
  saved legacy destinations reopen the shared overview. The old preference Fragment remains
  hidden for compatibility/listener behavior, not as a visible feature implementation.
- Android OpenPGP provider selection uses a shared selector over discovered services. Signing-key
  selection is provider-owned UI reached through a guarded native service adapter, with ownership,
  session, mode and provider checks at result time, and service release on completion/lock/destroy.
- AboutActivity mounts common AboutContent, including generated dependency versions, license URLs
  and full license text. Flavor donation actions and the version-tap opt-in confirmation remain.
  Old About fragments/layouts remain unused compatibility code, not visible pages.
- Android onboarding welcome, step bodies and navigation chrome are shared. The material-intro
  pager is retained only as the fragment restoration/validation/result adapter; its native buttons,
  indicator and welcome layout are not mounted in the shared shell. Real pager reparenting and
  recreation behavior have not been verified on Android.
- iOS scanner's app-owned Cancel action uses Compose. VisionKit retains camera feed, recognition,
  guidance and native permission UI. The new Kotlin/Swift bridge has not been built with Xcode.
- Standalone authentication and credential confirmation keep protected Android inputs where native
  autofill/accessibility/IME/security behavior is required. No key is put in shared presentation.

## Still to finish / verify

- Secondary Android container toolbars (authentication, backup, Settings) are now hidden. Settings
  and Backup use common Back navigation with host task-state enforcement; authentication retains
  its existing no-Up behavior. Verify system insets, keyboard, Back and blocked-task behavior on Android.
- Retire hidden legacy preference/drawer/intro compatibility code after native restoration/parity
  tests prove that moving its state behavior does not break existing installations.
- Audit cross-platform capability/default/route parity against real Android, desktop and iOS builds;
  platform-only Android device authentication/KeyStore/provider settings must remain explicit rather
  than being silently advertised as supported on desktop or iOS.
- Actual Android rendering, scrolling/keyboard/large-text, protected input, lock/recreation, first-run
  create/restore and generated-license loading; real biometric/KeyStore/reset/recovery and provider
  service/intent/cancellation tests. Sandbox tests never authorize destructive KeyStore reset.
- Real macOS/Xcode KMP+Swift build, simulator/device lifecycle and VoiceOver/camera tests; real
  desktop Compose rendering and keyboard/window-size testing. No native build/test results yet.

## Platform/feedback ownership

OS pickers, permission sheets, biometric/device-credential prompts, camera feed/recognition and
provider-owned signing/security-key UI remain native capability adapters. App-owned modal forms,
choices, progress and confirmations should use shared Compose. Most active Android page feedback now uses the common feedback surface described below.
Closing-task/detached-callback notices and some compatibility adapters still use native delivery;
shared source alone is not evidence of cross-platform visual/accessibility parity. Legacy AlertDialog helpers remain in
unused compatibility editors/fragments; live main-vault migration dialogs no longer use them.

## Environment blocker

This workspace is Linux with no Android SDK/adb or Xcode. Available Kotlin sandbox checks compile
against stubs, not real Android or UIKit. Completing the native integration gate requires an Android
SDK/device/emulator and a macOS/Xcode runner (plus device-only biometric/hardware validation).
Do not treat passing source/JVM checks as permission to mark the migration complete.

## Native test handoff

`shared-ui-native-validation.md` records workflow dispatch commands, the permission blocker,
new Android component tests and the remaining real integration matrix. The added tests are in
`ComposeVaultDeviceTest`, already included by the Android workflow's instrumentation class filter.
They compile locally; no emulator execution or native build pass is claimed.

## Current shared-feedback status

The common `SharedFeedbackBanner`/`SharedFeedbackPage` now renders transient page feedback for
Android Settings, Backup, vault and About hosts, with polite live-region semantics, tap dismissal,
revision-based replacement and cleanup on host close. These are shared presentation primitives;
Android still owns localized messages, expiry, session checks and native capability operations.

- **Settings:** no direct Toast/Snackbar calls remain in SettingsActivity or its compatibility
  Fragment. Import/export, policy, encryption and biometric result notices use common page/form
  presentation. Import success crosses recreation using only counts, a boolean and session
  generation, not imported values. Export buffers are generation-bound and wiped on completion,
  cancellation, lock and destruction. Destructive compatibility callbacks reuse guarded routes.
- **Vault:** non-closing transfer/import/export/read/CSV/timestamp/QR errors and results use common
  feedback. Captured operation generations prevent late results crossing sessions. Google batch
  warnings stay in the visible common progress dialog; image-decoder errors are reported to the
  host rather than emitted behind that dialog. Email retries and Thunderbird/export selections
  check prompt/buffer/preview ownership. The old static image decoder retains a native-feedback
  compatibility wrapper, not used by the active vault route.
- **Backup:** picker and non-closing task/provider failures use common feedback. Successful
  operations return only an allowlisted success kind and generation to the vault, which waits for
  a matching frame/key before consuming the notice. No task payload or filename is forwarded.
- **About:** active clipboard fallback, version-tap and feature opt-in notices use common feedback;
  old unused fragments remain compatibility code.
- **Authentication:** recoverable biometric errors use common in-page feedback. Shared buttons,
  native password/IME/autofill submission and native hardware/biometric retry paths clear old
  status. Background/close drops it; protected credential inputs and native biometric checks remain.

### Remaining feedback work

- Fatal authentication/missing-credential/migration failures that immediately close the task or
  Activity still use native delivery; do not expose a vault frame merely to announce them.
- The detached post-restore auto-backup persistence callback retains native delivery because it
  can run after the owning Activity has closed. It is a background-operation adapter, not a page.
  Closing Backup picker failures now use the same allowlisted result handoff as success.
- Audit remaining Activities, utility adapters and unused compatibility surfaces for live app-owned
  feedback. Do not infer whole-app or cross-platform parity from the hosts above.
- Execute real Android result/recreation/process-death, camera/provider, accessibility and protected
  input tests, plus iOS/macOS builds and desktop rendering. All native gates remain open.

### Latest local checks

- Python source/regression suite: **675 passed**.
- Kotlin sandbox: **all checks passed**, including feedback revision/close rules, result handoff,
  foreground/retry cleanup, decoder callback-only reporting and batch-warning collector retention.
- Shared UI tests: **168/168 passed**; scheduler: **3/3 passed**.
- Android instrumentation source compilation: **28 files compile** against sandbox stubs.

These checks are not native rendering, Android instrumentation execution, secure hardware testing
or an Xcode build. See `shared-ui-native-validation.md` for the outstanding native scenarios.

Hardware-key management operation results now use its existing shared status area, with current-
session checks and persistent uncertain-outcome text. Native USB/NFC commands and envelope storage
remain platform-owned; transport/cancellation/accessibility tests have not run on hardware.

## Common persistent-status follow-up

Grouped tools/hardware status, authentication, biometric enrolment and hardware unlock now reuse
`SharedStatusMessage` for typography, warning color and polite live-region semantics. The component
is state-free; hosts retain session checks and message lifetime. Hardware unlock's recoverable
failure now uses its existing common form error without a duplicate native Toast. A new Android
component test covers authentication visibility/retry/background/close cleanup; it compiles but has
not executed on a device. Refreshed local gates: Python 673, sandbox all, UI 168/168, scheduler 3/3,
and 28 Android test sources compile. All native runtime/accessibility/build gates remain open.

## Full common-form error sweep

Import/export (email/password/LinSCP/OpenPGP/encrypted/preferences), entry editors, tags, card layout,
auto-lock, password changes, confirmation failures, first-run restore and shared unlock now render
persistent errors through the same common status component. This applies to all builds mounting
these forms. Empty messages do not create a status node. Host authority and secret cleanup remain
unchanged. Backup closing failures now return an allowlisted failure kind/generation to the vault;
no arbitrary message, filename or payload is forwarded.

Latest sweep: Python **675 passed**, sandbox **all passed**, UI **168/168**, scheduler **3/3**,
desktop engine **405 passed / 2 skipped**, Android test-source compilation **28 files**. Native
Android and Xcode build/runtime gates remain open. Device verification is deferred to the user;
`shared-ui-device-checklist.md` is the focused acceptance checklist. This is not a claim of completed
native integration or audited whole-app cross-platform capability parity.

## Current parity audit and legacy removal

See [the 2026-11-20 source audit](cross-platform-parity-audit-2026-11-20.md) for a platform matrix,
actual active-host evidence, remaining camera/automatic-backup/hardware capability gaps, and a
retirement decision for each retained adapter. Android About's unused fragments, XML pages and
private legacy style attributes are removed. Flavor-specific donation metadata is preserved in
plain AboutFlavorLinks providers; generated notices and common catalog remain. Settings/intro/main
adapters are retained because they still own behavior/restoration. None of these source findings
substitutes for a native build, runtime feature-parity pass or field-by-field semantic audit.

## Desktop camera capability follow-up

Desktop URI import now offers an explicit shared capture-consent form and optional local FFmpeg
single-frame capture. It reuses the hidden QR review, validation and multipart batch flow, with
bounded reads, deadline and cancellation/session ownership. No live preview, bundled FFmpeg or
camera/device execution is claimed. See `desktop-camera.md`. Local gates: Python 677, desktop
408 passed/2 skipped, shared UI 168/168 and scheduler 3/3. Automatic-backup configuration outside
Android and iOS hardware management remain open gaps, not completed by this change.

## iOS NFC / USB-C hardware increment

Both transports are now represented by explicit actions in shared Compose, backed by pinned native
YubiKit read-only OATH account enumeration. Lifecycle cancellation, timeout, callback epoch and vault
generation checks are present. No raw protocol UI, fake hardware or automatic fallback was added.
This is **not full parity**: password-protected OATH/code calculation and hardware vault unlock remain
open, as do automatic-backup workflows outside Android. See `ios-hardware.md`. Python source/config
gates pass (678); Swift compilation, SDK resolution and device execution have not run in this Linux
environment. Native verification remains required, separately for NFC and USB-C.

## Continued parity implementation (supersedes the read-only hardware increment above)

- iOS NFC/USB-C OATH now has optional per-operation password unlock, explicit single-account
  calculation, same-device/raw-credential checks after reconnect, one-use HOTP counter consent,
  per-operation native delegate epochs, cancellation/background handling, code/clipboard expiry.
- Desktop and iOS now expose automatic password-encrypted backups: immediate/interval/manual runs,
  detached worker snapshots, managed retention, cancellation and persistent main-vault failure
  notices. iOS adds native Files-folder selection, session checks, security-scoped access and
  coordinated provider writes. Passwords/options remain session-only; background/credential/OpenPGP
  parity is not claimed. See `automatic-backups.md`.
- Added shared session-backup boundary/default fixtures and Swift OATH safety-policy tests. Swift
  PRF-envelope format support and independent Python/JVM/Swift golden vectors prepare the existing
  hardware-unlock format, but do not expose an unsupported native PRF unlock option.
- The pinned iOS SDK public FIDO methods do not accept PRF extension inputs; no OATH/signature/PIN
  substitute was invented. See `ios-hardware.md` for the concrete remaining transport requirement.

Native Swift compilation, physical hardware/camera/Files-provider behavior and full settings/entry
semantics remain unverified; these source additions are not a native parity certification.

Validation after the continued implementation: **Python 680 passed; desktop 415 passed / 2 skipped;
shared UI 168/168; scheduler 3/3; Android 28 test files compile against stubs; sandbox all passed**.
The sandbox pass precedes the final standalone portable policies/Swift format additions; subsequent
UI/desktop gates compile their JVM/common consumers. Swift policy/envelope/app tests are added but
not run. Synthetic hardware-envelope regeneration is byte-identical and its JVM contract passes.
No native/device or Xcode result is implied; no commit/push was performed.

Remaining security-policy decisions before persistent backup/hardware-unlock work: whether to
replace the native SDK with the PRF-capable Yubico Swift successor, and whether scheduled backups
may remember a recoverable backup password across sessions (and under which protection boundary).


## Successor SDK and persistent backup credentials (latest source update)

Supersedes the session-only and old-SDK limitations above: iOS now uses official
`yubikit-swift` v1.5.0, with separate NFC/USB-C PRF enrollment/removal/unlock routes and required
UV/UP. Desktop/iOS automatic-backup configuration is persisted under the vault key, resumes only
after unlock, and visibly requires reconfiguration after key change/recovery. See
`ios-hardware.md` and `automatic-backups.md` for format, lifecycle and acceptance boundaries.
Current executed checks: Python 680; desktop 418 passed / 2 skipped; shared UI 168; scheduler 3.
Swift tools 6.1/Xcode compilation, native transport and Files-provider checks remain outstanding.


### Continued hardware/lifecycle implementation

Added the iOS explicit vault-to-key seed-copy source route with duplicate-ID protection, shared
JVM/Swift naming fixtures, per-operation export consent and stale-entry/session checks. Extracted
native connection injection and late-result disposal; added Swift timeout/cancellation/replacement
and app admission tests. Foreground state now fences queued backup restore and deferred actions
when background relocking is disabled. Executed Python **682 passed**, desktop **419 passed /
2 skipped**. Swift additions still await Xcode compilation/execution; not a native parity claim.

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

Latest local validation after extension preservation and writer bounds: **Python 684 passed;
desktop 424 passed / 2 skipped; shared UI 168 passed; scheduler 3 passed; Android 28 test files
compile against stubs; sandbox checks pass**. UI/sandbox checks precede the final writer-bound
addition, which is covered by the later desktop/Android compilation. `git diff --check` is clean.
The shared About catalog now includes the pinned iOS YubiKit Swift/SwiftLog notices. NFC and USB-C
transport availability are reported separately and unavailable actions disabled; this is not a
claim that every attached key supports OATH/PRF/UV. Native Swift compilation/execution is pending.

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

## Settings import persistence and typed-value hardening

- Shared Kotlin settings imports now reject fractional, string-coerced and out-of-Int32-range
  integers/versions instead of truncating or wrapping them. This includes inactivity timeouts.
  Swift uses the same integer range without NSNumber unsigned-to-signed wrapping.
- Text bounds now use UTF-16 units on both implementations, including individual string-set
  elements on both import and export (not merely a set-count or total-file bound).
- iOS persistent preferences imports now use a throwing atomic batch port. Validation completes
  first, the complete plist is written atomically once, then the cached values are published.
  Unmentioned keys and vault authentication material are preserved. A disk failure keeps the import
  pending for retry and shows a storage/save failure rather than claiming success or invalid input.
- Added JVM tests for numeric coercion/overflow, no partial writes and set-element bounds; Swift
  tests cover batch dispatch/error propagation, real plist failure/retry, preservation/reopen and
  UTF-16 limits. App tests also exercise confirmation after an actual settings-file write failure.

Executed after these changes: **349 core tests passed**, **430 desktop tests passed / 2 skipped**,
**28 Android test files stub-compiled**, and **688 Python checks passed**. The final set-element
bounds addition was covered by the 349-core run; Swift tests still require Xcode/macOS execution.
These changes do not make differently addressed platform settings files interchangeable: semantic
cross-platform mapping and automatic OpenPGP backup configuration remain separate unfinished work.

## Atomic shared settings forms and unknown-token recovery

The desktop Compose host now supplies single-batch writers for both card-display selection and
inactivity/background-lock policy. `VaultController` validates all card numeric fields before
calling `DesktopSettings.putAll`; persistence failure keeps the whole cached selection unchanged.
The session-owned dialogs still recheck their original lifetime/request after disk IO, so even a
successful write that outlasts the idle deadline cannot revive the current session. Existing narrow
controller methods remain for other callers; the production full-form routes no longer chain them.

The iOS card-display and auto-lock confirmation routes similarly validate the submitted values,
commit through the throwing atomic preferences port, then reload published settings. Invalid fields
no longer silently apply the valid half of a card form, and a disk failure no longer claims success
or replaces the active lock policy. Added native failure/retry, validation and disk-reopen tests;
these Swift tests have not run in this environment.

Android's display/grouping/search/tap readers now tolerate newer or malformed stored tokens without
crashing the shared vault. Known search fields survive unknown peers; all-unknown/empty search sets
fall back to the supported fields. Unknown tap actions do nothing, and reading never rewrites a
newer-client token. Authentication/encryption/provider token readers were not changed by this work:
a display fallback is not authority to weaken a security setting. Android's existing tap defaults
are preserved rather than changing them incidentally during migration.

Latest executed gates: desktop **438 total / 436 passed / 2 skipped**; shared UI **168 passed** and
scheduler **3 passed**; Python **689 passed**; Android **28 test files stub-compiled**; all sandbox
checks passed, including actual Settings reader vectors under a Turkish default locale and hidden
card binding with an invalid layout token. These are not native device/Xcode acceptance results.

## Automatic OpenPGP encryption added (unsigned)

Desktop and iOS automatic backup controls now offer public-certificate encryption instead of a
backup password, sharing the existing coalesced-save/periodic/manual scheduler and lock cancellation.
Certificates are copied into the vault-encrypted local record (version 2); no recipient paths/grants
are retained. Automatic mode allows 64 distinct byte blocks / 32 KiB combined, with explicit
all-recipients/any-private-key and no-signing warnings. `.pgp` and `.aes` retention are isolated.
Version-1 password configuration remains supported. The Swift persistence hook now also rechecks
originating session admission before enabling, preventing reactivation after a revoked hook.

Latest executed: desktop **443 total / 441 passed / 2 skipped**, shared UI **168** + scheduler **3**,
Python **690**, Android **28 files stub-compiled**, sandbox all passed. Added JVM schema/persistence/
queue/failure/ownership tests and analogous Swift tests. Synthetic/injected tests do not establish
real OpenPGP interoperability; Swift/native device acceptance and signing remain outstanding.

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
