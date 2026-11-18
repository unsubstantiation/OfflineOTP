# Shared UI ownership and migration boundary

The desired architecture is **one production implementation per portable screen**, with hosts
providing file pickers, key access, clipboard, lifecycle, camera and persistence adapters. Matching
screens implemented independently in Kotlin and Swift are not shared UI. Golden tests and generated
colors help, but do not solve that duplication.

## Current production boundary

- `ui/commonMain`: Compose forms, cards, actions, theme and dialog layout, consumed by Android
  and all desktop distributions. Portable changes belong here rather than in Activity builders.
- `ui-model/commonMain`: toolkit-free state, input and presentation logic, but currently depends
  on the JVM `core` module. The Multiplatform source-set name does not mean iOS can consume it.
- Android `VaultComposeView`: hosts shared forms, tracks request ownership and disposes staged data.
  MainActivity/controller still own session checks and native IO. No decoded entries or file bytes
  are added to reusable Compose forms.
- Desktop Compose host: uses the same form components with desktop IO/controller adapters.
- iOS: SwiftUI screens and Swift engine. Shared generated assets/contracts are present; Compose
  screen sharing is **not implemented**. `ui/build.gradle` has only Android/JVM targets and depends
  on `core`, so adding an `iosArm64()` declaration alone would not make this graph native-compatible.

## This increment

Removed the separate Android LinSCP `AlertDialog`/`EditText` read prompt. Both its encrypted-vault
password and Base64 session-master-key input now use the desktop `LinScpImportDialog`. The form has
host-supplied secret-label/key-picker capabilities instead of a copied Android layout. Desktop keeps
its sessions.key file picker; Android keeps its existing text-key entry. Read and selection stages
share the same dialog implementation and clear secret drafts on submit/cancel.

The host owns the byte buffer until successful decoding, cancellation, replacement or lock. A
failed read keeps only source bytes for explicit retry, not the entered password. Successful
transition to selection revokes and disposes the read request without dismissing the new request.
Tests prevent reintroducing the native Android prompt and exercise callback/cleanup behavior.

## Next work in dependency order

1. Move remaining Android ordinary import/backup/settings dialogs onto existing shared forms,
   with request-ownership/disposal adapters. Keep platform authentication prompts native.
2. Extract presentation DTOs, UI capabilities, validation results, labels and bounded numeric
   rules from JVM `Entry`/utility dependencies into a truly platform-neutral module. Keep crypto,
   filesystem, Java APIs and session authority outside its exported API. Migrate one screen and
   compile Android/JVM/iOS targets before expanding the boundary.
3. Pilot a shared Compose screen hosted by iOS, bridged to the existing Swift store through that
   neutral API. Validate native navigation, keyboard, accessibility, privacy and lifecycle before
   deleting the SwiftUI counterpart. Do not retain two selectable production versions indefinitely.
4. Expand shared navigation/settings once the pilot works. Shared screens must be the release
   path, not an unused alternative. Keep capability-driven adapters for OS-specific features.

Each migrated screen must have host wiring, failure/cancellation/session tests, and deletion or
explicit retirement of its old implementation. Do not claim all-build UI sharing until native
iOS compilation and device behavior have passed; Android/JVM stubs cannot prove that boundary.

## First native-compatible extraction

`:ui-foundation` now owns the existing Compose theme and unlock screen, moved rather than copied.
It has Android/JVM and iOS device/simulator targets and **no engine or model project dependency**.
`:ui` exports it transitively; the existing JVM unlock API is now a small wording adapter. Desktop
continues rendering the same screen. Android's shared Compose theme now also comes from this
module; its native authentication Activity has not yet been replaced.

The iOS source set exports `UnlockPresentation.viewController` using `ComposeUIViewController`.
Its main-thread adapter accepts busy/error updates and revokes submissions on close. It does not
implement authentication, biometrics, vault creation, foreground/background privacy covering or
native file access. Those remain host responsibilities.

The authentication integration now requires that framework in `ios/project.yml`. Build it on a
Mac with `scripts/ios/build-compose-framework.sh` before XcodeGen. `UnlockView.swift` hosts
Compose through a lifecycle adapter, including create/confirmation and biometric actions; its
SwiftUI field/button layout was removed. RootView's inactive-scene cover and VaultStore's key/
biometric logic remain in place. The **rest of the release UI is still SwiftUI** and must be
migrated separately. No native framework/Xcode build was run in the Linux sandbox, so this wiring
is not yet native-validated. Gate it on password unlock, first-run creation, biometric access,
lock/close during authentication, rotation, keyboard, VoiceOver and privacy snapshots.

The sandbox independently compiles foundation common sources **without core/model classes** before
compiling the JVM UI hosts, and source tests reject engine/Java imports in this module. That checks
the new dependency boundary, not Kotlin/Native ABI correctness or actual Compose rendering.

## About migration

The existing desktop `AboutDialog`, `AboutContent` and `AboutNavigation` now live in
`:ui-foundation`, with their generated `AboutInfo` catalogue. Desktop uses the same declarations
through `:ui`'s transitive API; Android's native About/library integration still reads the common
catalogue. iOS Settings opens `AboutPresentation` through a UIKit adapter. The SwiftUI content list
and generated Swift catalogue were removed, rather than left as a fallback. Version lookup,
explicit external-link opening and sheet dismissal are native adapters. Cryptography/network
information previously shown only in iOS is retained in the common layout.

This requires rebuilding the Compose XCFramework before XcodeGen/Xcode. Native linking, VoiceOver,
sheet dismissal and external-link behavior still need Mac/simulator/device validation. The
remaining settings sections and vault screens are still SwiftUI; this is not the full cutover.

## Auto-lock settings migration

The iOS timeout picker now opens the exact `AutoLockDialog` used by desktop, moved into
`:ui-foundation`. Its common action/scroll components moved with it and remain used by existing
JVM forms. The Swift preset/label implementation was removed. `:ui-contract` is the new pure
Kotlin multiplatform dependency for presentation rules: it has neither Compose nor engine
project dependencies and holds the single `AutoLockOptions` definition. Both `:ui-model` and
`:ui-foundation` consume it, avoiding an inverted dependency on JVM code or Compose in the model.

The iOS adapter accepts saves only while its originating session is unlocked/current and its
expected setting value remains unchanged. Cancel/close revokes callbacks. iOS now stages selection
until Save, matching desktop; opening or cancelling does not mutate the timeout. Custom values
remain visible. Persistence uses the existing VaultStore setter and its existing storage semantics;
this migration does not claim new disk-failure detection in that setter.

The Swift duplicate-rule tests are retired with the duplicate implementation; the existing golden
JVM contract still tests the single shared Kotlin rule, and native wiring is pinned by source gates.
The independent contract compilation deliberately has no Compose or engine classpath. Native
framework linking, simulator behavior, settings dismissal on lock and accessibility still need
Xcode/device validation. Android's native numeric setting is not migrated in this increment.

## Vault password change migration

The iOS three-password SwiftUI form was removed in favor of the existing desktop Compose
`PasswordChangeDialog`, now in `:ui-foundation`. Field bounds are supplied by each engine's
InputPolicy rather than importing a JVM utility into the native-compatible module. The shared
form scrolls, masks all fields and clears drafts before submission/dismissal; new and current
passwords must differ. The native adapter rechecks field bounds, confirmation and session identity.

Rekey semantics have **not** been silently unified: desktop locks before starting its asynchronous
transaction and blocks normal close; iOS's existing synchronous `VaultStore.changePassword` keeps
the session open and updates biometric key material. The common form has explicit host behavior
wording rather than falsely promising desktop's lock/close guarantees on iOS. Native error and
success messages (including biometric recovery instructions) are preserved. This engine/lifecycle
difference remains separate parity work; UI sharing does not prove cryptographic transaction parity.

Swift app tests cover adapter rejection after close/session replacement, wrong-password retry and
entry preservation. They are added but not executed in this environment. Shared Compose/stub
compilation, Python wiring guards and Android sandbox checks do not replace Xcode validation.
Rebuild the required Compose XCFramework before native testing.

## Preferences import confirmation migration

The desktop `PreferencesImportDialog` moved into `:ui-foundation` and iOS now presents it after a
file pick instead of applying immediately. Platform wording is a host parameter (desktop default,
iOS override); the confirm button is disabled while a plan failed, which desktop never produced
but the shared form now guards for every host.

iOS computes the plan by applying the picked document into a throwaway in-memory settings port.
`PreferencesExport.apply` validates the whole document before writing, so the scratch run yields
identical applied/unknown/refused counts without touching real settings. Only the shared dialog's
Apply writes through `VaultStore.preferencesPort`, then reloads the inactivity delay so the
auto-lock sheet reflects the imported value. Foreign-platform and unreadable files surface their
existing specific messages in the dialog's failure state rather than a settings footnote.

Swift adapter tests cover plan-without-apply, confirm-applies, foreign-file refusal and stale
session rejection; they are added but not executed in this environment. Shared Compose/stub
compilation, Python wiring guards and Android sandbox checks do not replace Xcode validation.
Rebuild the required Compose XCFramework before native testing.

## Delete confirmation migration

The iOS SwiftUI "Remove entry?" alert was replaced with the desktop `EntryConfirmationDialog`,
now in `:ui-foundation`. The title follows the desktop ("Delete account?"); the message keeps the
entry name, matching Android and iOS's previous prompt. A failed removal now keeps the dialog open
with the error appended — the desktop failed-request behavior — instead of dismissing into a toast.

The native adapter revalidates the pending removal on confirm: same session generation, same index,
unchanged entry fields and still revealed. A stale or dismissed request reports an error and
removes nothing. Swift adapter tests cover successful removal, stale-session and closed-request
rejection; they are added but not executed in this environment. Shared Compose/stub compilation,
Python wiring guards and Android sandbox checks do not replace Xcode validation. Rebuild the
required Compose XCFramework before native testing.
