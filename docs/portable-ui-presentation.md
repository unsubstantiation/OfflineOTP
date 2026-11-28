# One portable UI renderer — chunk 9

Implemented 2026-11-20. Native Gradle/Xcode builds and visual/device verification remain outstanding.

## Production boundary

The portable production stack is `ui-foundation -> ui-contract` (plus the existing shared-domain API).
`ui-foundation/commonMain` owns Compose forms, page scaffolding/navigation, bars, typography/artwork,
feedback, vault cards/code/actions and the reorderable list. It has no JVM engine dependency.

The remaining `ui-model` and `ui` sources were moved from misleading `commonMain` roots into
**`jvmHostMain`**, shared only by the Android and desktop targets. Their Gradle dependencies on
`:core` and the JVM projection are confined to that source set; portable commonMain re-exports only
`:ui-contract` / `:ui-foundation`. These modules remain intentionally separate adapters:

- `ui-model/jvmHostMain`: reads JVM entries, core enums/settings, produces frames, and maps them to
  portable render values. Non-Compose native controllers can still consume it.
- `ui/jvmHostMain` and `ui/androidMain`: thin Compose/Android host conversion seams. They retain
  localized strings and callbacks with native/core argument types, not a second card/list renderer.
- Apple `ComposeVaultHost` and the Kotlin UIKit bridge: produce portable values/intents and supply
  native service adapters. They do not draw a SwiftUI/UIKit replica of vault cards or forms.

Merging modules would erase these still-distinct responsibilities and recreate a JVM barrier in
portable UI, so this chunk does not merge them or pretend their entry APIs are Apple-compatible.
Existing native engine conversion/oracle layers remain for chunk 10's reviewed cleanup/native gates.

## Removed renderer duplication

`VaultCardContent` / `VaultCardCommand` in `ui-contract` are the engine-free render contract. Both
JVM `vaultCardContent` and portable/Apple `portableCardContent` adapters feed the **same**
`SharedVaultRenderedCard`. There is now one production call site assembling `SharedVaultEntryCard`
and `SharedVaultCodeArea`, including action buttons, artwork, accessibility, masked/revealed tokens,
previous tokens, counters, countdowns, custom periods, notes and connection details.

The contract masks concealed token values and drops concealed previous tokens even when an adapter
mistakenly supplies raw strings. It redacts diagnostics and is not serializable/saveable. Frames
can still contain authorized revealed strings; immutable strings and runtime copies cannot be
physically zeroized. Native hosts must clear/drop their snapshots on teardown.

The two independent long-press/auto-scroll/reorder loops were replaced by `SharedVaultList` and
`VaultDragSession`. One gesture issues one neighboring move, waits for the exact host-adopted order,
and retains the same opaque entry identity—not the neighboring array position. Filter changes,
unexpected orders, deletion, duplicate identities and cancellation stop it. Failed/non-adopted moves
do not loop native writes. Desktop/Android stable list IDs remain unchanged. Apple supplies opaque
monotonic IDs reused across unchanged-entry reorders; full native entry snapshots used to match IDs
remain native-owned and are cleared on session/background teardown.

Localized JVM labels and existing controls/layout/privacy settings are preserved in adapters.
Apple's existing toolbar/filter/empty-state data and page styling are preserved. Header adapters
still compose shared bars using host-specific labels/counts and callbacks; they are not another
list/card implementation. No feature is claimed supported merely because its shared form exists.
Native hardware/capability/permission availability and reason messages remain native inputs; NFC
and USB-C are not collapsed into one hardware capability, and no OS-name branching was added to UI.

## Frame, callback and draft lifetime

Apple's index-addressed bridge now uses `VaultFrameDelivery`: replacing a frame rejects old callbacks;
background/session invalidation revokes delivery without destroying the reusable composition, and
close is terminal. Native card actions additionally compare the displayed native snapshot at an
index with the current entry, so a stale index cannot address a different account after reorder.

Dialog callbacks capture their surface epoch/key. Replacing or closing a dialog rejects retained
callbacks rather than redirecting their draft mutations into a different form. Session/background
suspension uses the native close-surface cleanup, drops edit snapshots and secret drafts, clears the
portable frame, and revokes queued/native sensitive operations. Foreground resume publishes a fresh
frame. Android/desktop keep their existing generation/live-ID checks and terminal view/presenter
cleanup; display tokens are not vault authority. Cancellation is not rollback of a provider write
or hardware operation already in progress.

This slice does not change file pickers, SAF/Files grants, clipboard/camera APIs, biometrics, NFC/USB
connections, storage/crypto engines, OS packaging, minimum versions or Android navigation-bar inset
handling. Actual layout/inset behavior still requires devices.

## Verification

Local gates:

| Gate | Result |
| --- | --- |
| Core | 422 passed |
| App JVM | 443 passed |
| Desktop | 479 passed, 2 skipped (481 total) |
| Presentation/model tests | 178 passed |
| Compose host tests | 3 passed |
| Sandbox | All checks passed |
| Android instrumentation stub compile | 29 files compiled, not device execution |
| Python | 765 passed |
| Shared boundaries / whitespace | Passed |

Additional evidence:


- UI gate compiles the portable contract and renderer **without core or ui-model on the classpath**,
  then type-checks the Kotlin UIKit seam with stubs, JVM adapters, desktop Compose host and Android
  Compose seam. Presentation tests include renderer privacy/capability-action mappings, command
  availability, custom period/counter behavior, identity-preserving drag adoption and stale/closed
  frame delivery.
- Source ratchets require one production list/card/code assembly, explicit `jvmHostMain` edges,
  engine-free common sources and guarded Apple frame/surface dispatch.
- Core/JVM/desktop/sandbox/Android-stub/Python regression gates are retained; source tests now follow
  the adapter-to-renderer chain rather than requiring code in retired renderer files.
- Added Apple native stale-index/reorder regression tests; these are **not executed here**.

Required equipped-runner gates: real Gradle metadata/Android/desktop builds (including the new
intermediate source-set wiring), both Android variants' instrumentation, Kotlin iOS framework build,
SwiftPM and Xcode app/test builds. Device/package smoke tests must cover all entry kinds and tools,
translations/font scaling/accessibility, theme/layout settings, long-list drag/autoscroll, filter/sort
changes mid-gesture, failed persistence, relock/rekey/background while forms are open, copy/reveal,
Files/SAF/native picker callbacks, and Android navigation insets on requested systems. Stub compilation
does not prove Gradle resolution, Kotlin/Native ABI, rendering, VoiceOver/TalkBack, native durability
or requested-OS feature parity. Final release/removal work remains chunk 10.
