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
