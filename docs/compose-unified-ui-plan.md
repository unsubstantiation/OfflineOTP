# Unified Compose Multiplatform UI

> **Current status:** Android `MainActivity` now displays `VaultComposeView`/the shared Compose
> cards, backed by `AndroidVaultController`. The existing toolbar, tabs, tag drawer and Android
> dialogs remain. Manual order is exposed through Move up/Move down controls, not drag gestures.
> The legacy adapter/holder/layout files are retained for the old regression fixtures and still
> need retirement. Sizing and label preferences are wired, and both Compose hosts now load bundled
> issuer artwork; runtime visual/accessibility parity still needs validation. The desktop shipping UI remains Swing.
>
> Sandbox gates type-check the actual Activity-to-Compose integration and exercise its publication
> lifecycle with recording stubs. **No real Gradle Android build or device rendering validation has
> been performed.** This is the display wiring cutover, not completion of all stage-2 exit criteria.

## Why unify

The vault screen exists twice. Android has `EntriesCardAdapter` + `EntryViewHolder` + the
RecyclerView layouts (≈1.6k Kotlin lines plus `item_entry_card.xml` and its drawables), and the
desktop client has `MainScreen` + `EntryCards` + `MainWindow` (≈2k Kotlin lines of Swing). Both
render the same data and re-derive the same presentation rules — digits split into halves, the
expiry colour, seconds left, the tag and category counts, the empty-state wording — and the two
copies have already drifted enough to need a parity review
([`client-parity-2026-11-02.md`](client-parity-2026-11-02.md)). iOS is a third implementation of the
same list in SwiftUI.

A Compose Multiplatform screen removes one implementation of every screen it covers: the composables
are written once and each client supplies only what is genuinely platform-specific (its vault
session, its clipboard, its language resources, its window chrome).

## Layering

```
:core        (Android-free engine: entries, OTP, parsers, vault, backups)
  ↑
:ui-model    (toolkit-free frame layer: cards, filters, strings — no Compose, no Android)
  ↑
:ui          (Compose Multiplatform: theme, screens, widgets)
  ↑                                ↑
:app  (Android host, stage 2)      :desktop-compose  (Compose desktop host)
                                   :desktop          (Swing client, still shipping until stage 4)

:desktop also depends on :ui-model — labels and the frame model only, never :ui.
```

The split between `:ui-model` and `:ui` is what makes the boundary structural instead of textual: the
frame layer is compiled with **no Compose artifact on the classpath at all**, so a widget or a
card-state file physically cannot reach for one, and a host that does not use Compose (the Swing
client today) can still share the model and the wording.

`:desktop` stays Compose-free so its Swing-only sandbox gate and its Swing tests keep working while
both desktop clients share one engine; the Compose desktop host lives in its own module instead of
growing a second UI inside `:desktop`.

## What stage 1 contains

| Area | Files | Notes |
| --- | --- | --- |
| Design system | `ui/src/commonMain/.../ui/theme/ForestPalette.kt`, `ForestTheme.kt` | The Dark Forest tokens and a named type scale, replacing the duplicated `colors.xml`/`ForestTheme` values and the Material 3 colour scheme. |
| Frame model | `ui-model/src/commonMain/.../ui/vault/{TokenDisplay,VaultCardState,VaultUiState,VaultStrings,VaultActions,VaultProjection,VaultCards}.kt` | A module with no Compose dependency at all. Everything a row shows — and the tab/sort wording the Swing client also uses — is computed here and unit-tested on a host JVM. |
| Screen | `ui/src/commonMain/.../ui/vault/{VaultScreen,VaultComponents,Countdown}.kt` | Stateless: one immutable frame in, callbacks out. |
| Unlock | `ui/src/commonMain/.../ui/unlock/UnlockScreen.kt` | Password field only; the host owns the unlock path. |
| Android seam | `ui/src/androidMain/.../ui/vault/VaultComposeView.kt` | `AbstractComposeView` subclass with `render(frame)`/`clear()`, so the Activity can push frames from its own session without rebuilding the composition. Type-checked by the gate against the app's Android stubs. |
| Desktop host | `desktop-compose/src/main/kotlin/.../compose/ComposeMain.kt` | Unlock, list, copy, reveal, HOTP advance, search/tag/sort, lock — over the existing `VaultController`. |

Deliberately absent from stage 1: add/edit/delete dialogs, tags/thumbnail editing, backup and
restore, OpenPGP, hardware keys, preferences, idle auto-lock, issuer artwork rasterization, and the
Android host. The screen hides an action when the host passes no callback, so the preview host never
shows a button that does nothing.

## Decisions and their reasons

1. **The projection is a separate module from the composables.** `VaultProjection` turns borrowed
   entries into a `VaultUiState` inside `:ui-model`, which is compiled without Compose on the
   classpath. That makes the behaviour (grouping, expiry window, empty-state choice, tag counts,
   masking) testable with the same JUnit runner the rest of the repository uses, instead of requiring
   a device, and it lets a non-Compose host — the Swing client — share the model without pulling a
   UI toolkit it does not use.
2. **Strings are passed in, and their English defaults are the Android resources' words.** Android
   ships thirty translated `values-*` lists; a Compose resource bundle would orphan them. Hosts pass
   a `VaultStrings` value (Android from its resources, the desktops from `VaultStrings.English`), so
   localisation stays a host concern. The English defaults are checked against
   `app/src/main/res/values/strings_main.xml` by the guard test, and the Swing client now renders its
   tab and sort labels from the same table, so `OTP`/`SSH Keys` cannot be spelled three ways.
3. **The host supplies time.** There is no `expect fun currentTimeMillis()`: the host injects
   a wall-clock reader through `VaultClock`. `rememberNowMillis` samples it on every tick, so
   time corrections and resume affect tokens and countdowns together. Coroutine delays only pace
   refreshes; elapsed time from unlock is not a substitute for current wall time. The host rebuilds
   the frame once per second; the screen recomposes, the vault does not re-filter.
4. **Codes start masked.** A card draws digits only when its id is in the revealed set; copying is
   still allowed because the user asked for that row. Reveal state is keyed by the entry's stable
   list id, never a row position, and the host clears the set on lock.
5. **Filter policy is a value, not a client's habit.** Android's search temporarily overrides the tag
   filter; the desktop list applies both. `VaultFilterCombination` carries that difference, so the
   projection can serve either without silently changing one client's results.
6. **No session authority in the UI.** A frame contains strings and ids only. Every action returns to
   the host, which re-checks its session before copying a token, revealing a note, advancing a
   counter or saving.
7. **Secret-bearing state never enters saved state.** The unlock field uses `remember`, not
   `rememberSaveable`; `VaultCardState`/`VaultUiState` redact themselves in `toString`, so a log line
   or a failing assertion cannot print a code, a note body or a stored password.
8. **`:desktop` stays Compose-free.** Its Swing gate compiles every desktop source without Compose on
   the classpath; keeping the Compose host in `:desktop-compose` is what preserves that check.
9. **Versions are pinned and the compiler matches Kotlin.** Compose Multiplatform `1.10.3` (newest
   stable 1.10 line) with `compose-compiler-gradle-plugin:2.4.21`, the same version as the Kotlin
   compiler. Dependencies use the plugin's `compose.*` aliases because the Material 3 artifact is
   versioned separately from the rest of the 1.10 line.

## What was verified, and what was not

Verified in the checkout that produced this stage (no Maven Central, no Android SDK):

```sh
bash scripts/kotlin-sandbox/bootstrap-toolchain.sh
bash scripts/kotlin-sandbox/compile-ui.sh          # six phases, 40 frame-layer tests
python3 -m unittest scripts.tests.test_compose_ui_module -v
```

`compile-ui.sh` runs six phases: it compiles `:core`; compiles `:ui-model` **with no Compose
artifact on the classpath**; compiles `ui/src/commonMain` against the hand-written Compose stubs in
[`scripts/kotlin-sandbox/stubs-compose`](../scripts/kotlin-sandbox/stubs-compose/README.md); runs the
frame-layer tests with the JUnit shim; type-checks `desktop-compose` against the compiled `:desktop`
gate output and the Compose Desktop window stubs; and type-checks the Android seam
(`ui/src/androidMain`) against the app's own Android stubs plus a Compose platform stub. A green run means the shared sources type-check against
`:core` with no Android/desktop/storage dependency, and that the frame builder behaves as asserted.
It does **not** prove layout, rendering, accessibility, composition, resource resolution or that the
stub signatures match the published artifacts.

Not verified here, and required before anything is called accepted:

- a real Gradle build of `:ui` and `:desktop-compose` (so the Compose artifacts, the KMP Android
  target and the Material 3 alpha resolution are exercised for the first time);
- `bash scripts/packaging/verify-desktop-build.sh` with the Skiko natives in the distribution;
- TalkBack/screen-reader behaviour of the Compose list, keyboard navigation on desktop, and RTL
  layouts for the right-to-left locales the app ships;
- a running preview window on a machine with a display.

## Migration stages

### Stage 2 — Android vault list (the first deletion)

1. Add `implementation project(':ui')` to `:app`.
2. Host the screen through the seam that already exists — `VaultComposeView` in `androidMain` — so
   the Activity keeps session authority and only pushes frames:

   ```kotlin
   val view = VaultComposeView(context, strings = AndroidVaultStrings.of(context), actions = actions)
   view.render(projection.snapshot(filter, nowMillis, revealedIds, appearance))   // once per tick
   // on lock: view.clear() (or drop the screen entirely)
   ```

   `VaultFilter` transitions come from the shared helpers (`withQuery`, `withCategory`,
   `withTagToggled`, `withUntaggedToggled`, `clearedFilters`) rather than the adapter's own copies,
   and `AndroidVaultStrings.of(context)` maps this module's `VaultStrings` fields to the existing
   `R.string` resources so the thirty translations carry over unchanged.
3. Build `VaultFilter` from the settings store and the adapter's current selection, call
   `VaultProjection.snapshot`, and route `VaultActions` back into the adapter's existing entry
   commands (dialog creation stays in `EntryActionDialogs`).
4. Delete `EntryViewHolder`, `item_entry_card.xml` and the card-only drawables/dimension resources,
   and drop `me.zhanghai.android.materialprogressbar` once nothing else uses it.
5. Port `TokenCardDeviceTest`/`EntrySearchDeviceTest` to `androidx.compose.ui:ui-test-junit4` (new
   test-only dependency) before deleting the old assertions.

Exit criteria: both flavors build and pass lint, the existing API 28/36 instrumentation suite passes
with the Compose list, the adapter's search/tag/sort parity tests still pass, no View resource is
left unreferenced, and the marked columns/tags still render in every locale.

### Stage 3 — entry actions and dialogs

Card menus, manual entry, tag and thumbnail pickers and confirmation dialogs move into `:ui` behind
`VaultActions.onEntryActions`. Each dialog migrates with its own characterization coverage; the
security rules in [`vault-security-hardening.md`](vault-security-hardening.md) (delayed confirmation,
redaction, screen privacy) apply unchanged.

### Stage 3b — Swing adopts the frame model (done)

The Swing client depends on `:ui-model` only, takes its tab and sort labels from `VaultStrings`, and
now paints each row from `VaultCards.of(entry, nowMillis, revealed)` instead of re-deriving the row:
the code, its grouping, the expiring threshold (`VaultAppearance.expiringSeconds`, not a private
`EXPIRING_SECONDS`), the tags, the thumbnail name and the accessibility line all come from the card
the Compose screen paints. `CardClock` is the only clock, and it is passed in, so a test pins the
second instead of waiting for a rollover.

Two consequences worth recording. The desktop had drifted on grouping — it printed `123456` where
the phone prints `123 456` — and it printed the mOTP placeholder constant `PINREQ` as if it were a
code; both now follow the shared card (`PIN required` is the shared wording). The tests that checked
the old strings now compare against `TokenDisplay.group(...)`, so the grouping is an asserted
behaviour rather than a coincidence.

What remains Swing's own is drawing and row chrome the Compose card leaves to its host: the redacted
placeholder text for stored passwords, keys and mail accounts; the kind label; the hidden label; and
the wrapped note geometry. None of it blocks stage 4 — the remaining work there is features
(dialogs, backup, hardware keys), not derivation.

### Stage 3c — the desktop selection is one `VaultFilter`

`DesktopVaultState` used to keep four fields (`query`, `selectedTags`, `includeUntagged`, `tagMode`)
plus its own search-then-`removeAll`-tag-mismatch composition. That was the desktop's second copy of
the filter rules — and the one that differed from Android's, which is why the difference had to be
documented. It now holds a single `VaultFilter(combination = SEARCH_AND_TAGS)` and applies it through
`VaultProjection`, so `applySearch`/`applyCategory`/`applyTagFilter`/`applySortMode` only replace a
field of that value and every refresh runs one shared implementation. The public API is unchanged;
what changed is that the presentation is no longer mutated behind the state's back, which the desktop
tests now respect (they call `applyCategory`/`applySortMode` instead of reaching into
`presentation`).

The main window's restore path was the one place that did reach in — it called
`presentation.setCategory(...)`/`setSortMode(...)` directly when binding a vault — so it now goes
through the state; otherwise the next refresh would have reset the user's tab.

### Stage 4 — desktop parity, then delete Swing

The Compose desktop host gains the remaining features (add/edit, backup/restore, OpenPGP,
hardware keys, preferences, clipboard expiry, portable-location handling), then `desktop/src/main/.../ui`
and the Swing entry point are deleted and `:desktop-compose` takes over the `:desktop` name. The
engine (`control/`, `storage/`, `openpgp/`, `hardware/`) remains shared; tested host-facing operations are added as needed.

### Stage 5 — iOS (out of scope)

A Kotlin/Native framework exposing `:ui` would let the SwiftUI client reuse the same screen; the
[iOS client plan](ios-client-plan.md) and its vault/backup contract are unaffected either way.

## What must not change

Vault and backup formats (already portable and pinned by
[the golden fixtures](../desktop/src/test/resources/golden/README.md)), the session-generation rules,
input bounds, the offline manifest policy, and the per-platform filter semantics. The Compose module
consumes `EntryList`/`EntryListPresentation` exactly as the two clients do today; it does not
re-implement search, sorting or tag matching.

## Risks

- **Artifact compatibility.** Compose Multiplatform 1.10's Material 3 artifact is an alpha line and
  the module applies `com.android.library` inside a KMP build against AGP 8.13.2 / `compileSdk 36`.
  The first network-enabled Gradle build is the real test; if it fails, pinning an older Compose line
  or holding the Android target until AGP 9 are the two fallbacks.
- **Accessibility.** The View UI's `contentDescription`s and focus order have device coverage; the
  Compose list needs equivalent semantics and its own instrumentation assertions before cutover.
- **Distribution size.** Compose Desktop adds Skiko natives to the desktop archive; the reproducible
  packaging check and the size notes in
  [`scripts/packaging`](../scripts/packaging/README.md) must be updated with the measured numbers.
- **Localisation drift.** Every new `VaultStrings` field needs an Android resource mapping in
  `AndroidVaultStrings`; a field without one would silently fall back to English on the phone. The
  mapping belongs to stage 2 together with a guard that every field has a resource, so a new field
  cannot ship half-translated.

### Shared interaction presenter (preparatory increment)

The Compose desktop host delegates filter transitions, reveal state, digits-only copy and
clear-before-lock ordering to `:ui-model`'s `VaultPresenter`. Clipboard, counter persistence,
sort preferences and session locking remain host callbacks; unsupported entry actions remain hidden.
The host observes a revision signal and still supplies the clock for frames. This synchronous
presenter is not wired into Android's asynchronous search path. Dialog migration and Android
cutover remain pending. Tests cover transitions, masking, copy eligibility and lock ordering.

### First shared entry-action dialog

`EntryConfirmationDialog` in `:ui` provides caller-localized confirmation/dismissal chrome.
The Compose desktop HOTP action now asks before advancing, retaining only a stable id,
and drops the dialog on dismissal or lock. Confirmation resolves the id in the live vault.
`DesktopVaultState.advanceCounter` uses the shared `EntryUsage` rule and restores the
in-memory counter if saving fails; the dialog reports failure and permits retry. Tests cover
success, failed/throwing writes, retries, removed entries, locking, non-HOTP entries and exhaustion.
Android and Swing dialog migration remains pending. Compose dialog rendering still requires
a real Gradle build and desktop/device testing; sandbox stubs only check signatures.

### Shared mOTP PIN dialog

Compose cards with a missing mOTP PIN now offer an optional `onEnterPin` action. The desktop
host supplies it using `EntryPinDialog` from `:ui`, with a masked input bounded by `InputPolicy`.
Input is keyed to the target id, never saveable, and cleared before submission or dismissal.
The host stores only the pending id and a generic error, drops pending dialogs on lock, and
resolves the live entry again before saving. Failed writes restore the old PIN; a retry requires
re-entry rather than retaining the submitted secret in the dialog. Setting a PIN does not verify
it against the remote service. Android's existing PIN dialog is unchanged; adopting the shared
one remains part of the Compose cutover. Runtime masking, focus and dismissal need real Compose
testing; the sandbox checks signatures and the headless action/persistence rules only.

### Confirmed deletion in the Compose host

Compose cards expose an optional `onDelete` action; hosts without it show no Delete control.
The desktop host reuses `EntryConfirmationDialog`, keeps only the pending stable id, and clears
it on cancellation, another dialog request or locking. Confirmation saves the remaining entries
before removing and destroying the live target. A failed/throwing write leaves the entry intact
and displays an error. The presenter forgets the removed row's reveal state and reapplies its
current selection after successful deletion. Tests cover ordering, failure/retry, filtered/sorted
views, repeated confirmation and locked sessions. This does not migrate Android or Swing deletion;
real Compose layout and dialog interaction still require runtime validation.

### Shared dialog lifecycle and renderer

`VaultDialogs` now owns a single pending request (kind, entry id and failure flag), replacing
the desktop host's six independent pending-id/error fields. `VaultEntryDialog` in `:ui` selects
the PIN, counter or delete widget and takes `VaultDialogStrings` for localized wording. The host
retains only persistence dispatch and live-entry validation. Request identity rejects stale
callbacks after dismissal, replacement, completion or lock, even if the next request targets
the same account. Failed operations create a fresh retry request, resetting PIN input. No PIN,
card, entry or arbitrary exception text is stored in the dialog model. Five headless lifecycle
tests and source guards cover the new ownership. Android integration and real Compose runtime
validation remain outstanding.

### Terminal session boundary for shared interactions

`VaultPresenter.lock()` now makes the presenter terminal before invoking host cleanup: frames
are empty, filter/reveal state is cleared, and all retained action callbacks become inert.
Cleanup failure still notifies the view and does not re-enable interactions. Hosts create a new
presenter after unlocking rather than reusing a locked one. `VaultDialogs.close()` likewise
invalidates pending requests and refuses future opens, unlike ordinary dialog dismissal.
Regression tests cover old and newly requested callbacks after lock, throwing cleanup, and late
dialog requests. Hosts remain responsible for clearing storage/session references and validating
live entry ids; already-issued immutable frames cannot be zeroized by this layer.

The UI sandbox gate now puts its freshly compiled model/core first on the host classpath, so
older desktop gate output cannot shadow a new shared API.

### Android interop API correction

`VaultComposeView` extends `AbstractComposeView` and implements its composable `Content()`
hook. AndroidX `ComposeView` is final, so the previous subclass compiled only because the
handwritten stub incorrectly marked it `open`. The corrected stub models the abstract base
and final concrete view separately; source guards pin the distinction. A negative compilation
probe verified that extending the stub's concrete view now fails with “this type is final”.

API reference: [AndroidX ComposeView.android.kt](https://github.com/androidx/androidx/blob/ca175c23e35c88a0a4517e7d1bc01764a60e3dbf/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/ComposeView.android.kt).
This source check is not a successful build against the project's pinned Compose artifacts.
The Activity still needs to supply lifecycle/saved-state owners, clear frames on lock, and
validate callbacks when the Android screen is cut over; real Gradle/device gates remain pending.

### Android frame lifecycle

`VaultComposeView` delegates frame acceptance and action liveness to the toolkit-free
`VaultFrameHost`. `clear()` now ends that view's session permanently: the frame is empty,
retained callbacks are inert, and delayed `render` calls cannot repopulate the screen. Construct
a new view after unlocking. Callbacks are also inert before the first frame is published.
The `VaultActions.guarded` wrapper is shared with the desktop presenter and preserves null
capabilities so unsupported actions remain hidden. Tests exercise every action, late frames,
closing before first publication, repeated close and isolation from a fresh session.
This does not replace Android's live-entry checks or same-session async search revision checks,
and does not zeroize immutable frames already held by callers. No Activity wiring is changed.

### Shared desktop mutation paths

Swing's `EntryEditor` now delegates HOTP advance and deletion to the same `DesktopVaultState`
handlers used by Compose, instead of maintaining a second persistence sequence. Swing retains
its reveal-before-advance requirement and checks object identity before resolving the stable id.
The removal confirmation carries the selected entry rather than re-resolving a displayed row
after the modal dialog returns. Failed HOTP saves now restore the old counter/token in Swing,
matching Compose; failed deletion leaves the entry intact. Encrypted-vault tests cover failure
and retry, hidden entries, detached objects with colliding ids, and changed sort/filter selection.
Swing rendering and dialogs are still present; this increment unifies their mutation backend.

### Wall-clock correction in the Compose token loop

The previous timer extrapolated wall time from an unlock anchor plus monotonic elapsed time.
That ignored later clock corrections, even though OTP generation requires current wall time.
`VaultClock` now samples the host clock once per tick and computes a strictly positive delay to
the next refresh boundary. Tests cover forward/backward clock changes, resume after a long gap,
negative/extreme timestamps and invalid intervals. The Compose effect is keyed to the injected
clock and remains cancelled when the screen leaves composition. Runtime coroutine scheduling
and actual suspend/resume behavior still need real Compose validation.

## Android cutover audit — not completed

The shipping Activity still instantiates `EntriesCardAdapter` and attaches it to `cardList`.
It does not yet instantiate `VaultComposeView`; the cutover must not be reported complete.

The first direct adapter dependency outside the Activity has been removed: `ManualEntryDialog`
now accepts `EntryEditorHost` (tags, tag counts, add and replacement). The existing adapter
implements this renderer-independent contract without changing validation or persistence.

Remaining work identified from the current code, in dependency order:

1. Extract `EntriesCardAdapter`'s session/persistence/search ownership into an Android controller.
   Preserve `VaultSession.MutationLock`, key identity/generation checks, revision-checked async
   `EntryFilter.publishResults`, recovery handling, automatic backups and hide-task cancellation.
2. Bridge stable-id Compose actions to the existing Android handlers: privacy persistence,
   per-kind copying (including SSH/password/email choices), PIN establishment, HOTP edits,
   QR export, tags/thumbnail editing, archive export and keyboard injection. Copying the
   frame's displayed token alone is not equivalent to Android's usage/clipboard behavior.
3. Preserve or explicitly replace tap/double-tap preferences, timed reveal, drag/manual order,
   card-layout/display settings, accessibility and localized strings. These are not all
   represented by the shared screen's current callback/appearance contract.
4. Replace the Activity's RecyclerView, item-touch wiring and data observer with the Compose
   host/controller publication. Reconcile existing tabs, search menu and tag drawer with the
   shared screen rather than displaying duplicate controls. Create a fresh view after unlock
   because `VaultComposeView.clear()` terminates its session.
5. Wire `:app` to `:ui`, extend the Android source gate to compile the actual integration,
   update device tests that construct/reflect on the adapter, and remove obsolete renderer
   classes/layouts only once their last users are gone. Update guards to the new rule owners.
6. Run a real Gradle Android build and device tests for unlock/lock, search races, each entry
   kind, imports/exports, reordering and failure paths. Sandbox type-checks are not substitutes.

The old rendered rows use `component_card_default`, `component_card_compact` and
`component_card_full` through `EntryViewHolder`; deletion should follow actual references,
not assume that removing a single `item_entry_card.xml` completes the UI migration.

### Android controller extracted

`AndroidVaultController` now owns the former adapter's vault entries, presentation, key,
persistence/backup handling, async search publication, privacy timers and Android action dialogs.
`EntryCardEvents` carries the existing Android card commands without a ViewHolder dependency.
A small `Changes` notification port lets any renderer observe mutations. The controller has no
RecyclerView, row inflation or holder imports; it still intentionally owns Android platform
services and dialogs, which need not be reimplemented just to change the list renderer.

`EntriesCardAdapter` is now a compatibility renderer that delegates to the controller. The
Activity's vault-operation field references that controller directly, while its initial list
wiring still constructs and displays RecyclerView rows. The async-search device test constructs
the controller without a renderer; it is type-checked here, not executed. Existing source guards
followed the moved rule implementations, and new guards enforce the renderer/controller split.

This completes the controller extraction prerequisite, not the Compose Activity cutover.
The remaining shared-screen capability mapping, Activity publication/wiring, obsolete renderer
removal and real Android build/device checks described above remain outstanding.

### Android shared frame/action bridge

The app now depends on `:ui-model`. `AndroidVaultController.composeFrame` snapshots its
already-published presentation through `VaultProjection.snapshot`, preserving Android's
revision-checked asynchronous search path rather than calling `applyFilter`. A detached
`EntryListPresentation.selection()` supplies filter metadata without changing the revision.

`AndroidVaultFrame` couples the shared state to commands bound to the frame's generation and
entry identities. Commands resolve the current displayed position under the session mutation
lock, reject removed/replaced/hidden entries, and dispatch to existing Android privacy, copy,
tap, counter, PIN, deletion and popup handlers. Privacy toggling is the only command allowed
on a concealed entry. Copy takes the current live token, never a token supplied by a stale frame.
The bridge carries an Android View only when anchoring an existing popup, not as row identity.

A core test pins selection snapshot isolation and search-revision preservation. The Android
search device test now requests frames during search and checks hidden-entry/locked-session
rejection; it has only been type-checked in the sandbox. The app source gate includes the real
ui-model sources, not stand-ins for the new bridge.

Activity display still uses the compatibility renderer: binding this bridge to the Compose
view, adding the remaining shared-screen capabilities and removing RecyclerView rows are next.

### Android Activity display wiring

`cardList` is now a FrameLayout containing `VaultComposeView`. `MainActivity` creates the
controller directly, publishes frames on controller notifications and token ticks, and recreates
the Compose view across session generations. Lock clears and removes the old view. Per-frame
callbacks capture their own `AndroidVaultFrame`, not a mutable latest-frame pointer. The Activity
retains its existing toolbar/search/category/tag controls; shared chrome is disabled for this host.

Shared cards now support separate persisted privacy and timed token reveal, configured single/
double taps, static-secret copying, HOTP long-press editing, popup entry actions and manual-order
up/down buttons. Concealed Android frames strip protected token/tag/counter/artwork fields. Hidden
labels/SSH connection summaries appear only in visible details. Existing Android dialogs, backups,
imports and session checks still execute in the controller. New labels use Android resources.

The app depends on `:ui`; its sandbox gate compiles real shared UI/platform seam sources alongside
the app against Compose stubs. The full app harness verifies view reuse on ticks and replacement
on lock/unlock, but does not execute compositions. Existing legacy card tests remain attached to
the compatibility renderer until equivalent real Compose UI tests are available. Device/Gradle
validation, legacy fixture/renderer deletion and display-preference parity remain open.

### Android token-display preference parity

The shared appearance value now carries Android's explicit group size, Hide issuer, previous-code
and individual-countdown preferences. Grouping is the existing right-aligned Android rule, moved
to `TokenFormat.groupFromRight` in core and used by both the legacy `Tools` facade and shared cards.
A null group size preserves the desktop's existing half-split convention. Current and previous
codes use the same selected policy; stored issuer/token values are not modified by display choices.

Standard-step countdowns respect the preference, non-default steps retain their indicator, and
Android's tap-to-reveal policy hides countdowns while tokens are masked. Countdown visibility does
not disable the underlying expiry calculation. Core/shared tests and the app sandbox harness
cover these preferences. Label/thumbnail sizing, artwork and legacy card-layout parity remain
separate work; real Android runtime validation and renderer retirement remain pending.

### Android label and layout preferences

`AndroidVaultController.composeAppearance()` is now the single mapping used by both shared frame
derivation and the Activity's Compose view. It carries label size in SP, thumbnail size converted
from Android pixels to DP, and the selected card layout. The view is recreated when this appearance
changes so layout preferences apply without reopening the vault.

Shared cards use the chosen size for title/account labels and the existing 75% tag-size ratio.
Multiline permits ten lines; Scroll requests a single-line Compose marquee instead of truncation.
Full places the code below identity, while Compact reduces vertical padding. These are shared
Compose arrangements, not pixel-identical copies of the retired XML designs. Secure notes are
no longer clipped to three lines. Artwork still uses the letter tile pending issuer-art rendering.

Headless tests pin layout policy and the app sandbox checks stored preferences and density
conversion. Actual marquee motion, font scaling, touch targets and layout need device validation.
Legacy renderer removal and real Gradle/device gates remain outstanding.

### Bundled issuer artwork in Compose

`VaultArtwork` is a host-provided lookup in `:ui`, not `:ui-model`: frame values still contain
only the persisted thumbnail name, never a bitmap or platform resource. The common card requests
artwork only when thumbnails and entry details are visible. Images are aspect-fitted without
cropping/tinting; missing providers, default/unknown selections and null loads use the letter tile.
Decorative images have no separate accessibility description because identity text is adjacent.

Android wires `AndroidIssuerArtwork` through `VaultComposeView`. It uses the existing resource
loader/thumbnail enumeration (including that loader's app-icon fallback on resource failure),
with a 64-entry LRU cache of named assets and failed lookups. It caches no account labels and
never recycles evicted bitmaps that a composed row might still reference. The view owns the cache
lifetime. Compose Desktop uses the same `IssuerIcons` vector rasterizer/cache as Swing. Shared
`remember` keys include provider and thumbnail name, so ticks do not reload a row's image and
changing the selected thumbnail invalidates its remembered lookup.

The app sandbox exercises cache hits, eviction, unknown/default handling and failed-load caching.
Source guards cover privacy gating and platform wiring. A new Android device test checks visible,
distinct bundled brand pixels and cache reuse; it is type-checked but not executed here. Existing
desktop artwork rasterization tests remain green. Actual Compose image layout/device validation
and legacy renderer retirement remain pending.

### Legacy renderer source-set retirement

The unused RecyclerView adapter, holder, double-click listener and three drag helpers now live
under `app/src/androidTest/kotlin`, with their original packages, solely as historical regression
fixtures. No production Kotlin source references them or RecyclerView; the explicit RecyclerView
dependency is test-only. `EntryCardEvents` remains a production controller command contract used
by shared-frame dispatch, not a renderer.

The app sandbox first compiles production sources without the legacy fixtures, then compiles the
fixtures/checks in a separate friend module. This prevents historical test support from hiding a
production dependency on the removed renderer. Instrumentation compilation discovers the fixtures
through androidTest normally. Source guards enforce this boundary and retain historical checks.

This retires legacy renderer **code from the shipping source set**, not all migration work.
The old card XML resources remain in main for existing instrumentation resource IDs; moving them
requires a test-resource namespace/context migration. Historical card tests still exercise their
old renderer, not Compose. Compose localization/accessibility and device rendering need validation,
and fixture/XML deletion should follow replacement coverage rather than silently losing it.

### Non-default token-period labels

Shared cards now carry the actual non-default rollover interval separately from countdown
seconds. Android formats the label through its existing localized `format_custom_period`
resource; the desktop Compose default is “45 s”, for example. This follows
`hasNonDefaultTokenStep()`, including mOTP's ten-second interval even before PIN entry,
rather than relying on the legacy stored period. HOTP and static entries have no period label.
Android concealed frames strip this metadata, and the renderer also checks detail visibility.
Model tests distinguish interval from remaining time and cover default/static/mOTP behavior;
the app harness verifies visible and concealed frames. Runtime layout validation is still pending.

### Compose device-test coverage (added, not executed in the sandbox)

`ComposeVaultDeviceTest` uses `createAndroidComposeRule<ComponentActivity>()` and attaches the
shipping `VaultComposeView` via `setContentView`, exercising its real lifecycle-owner integration.
It asserts current-token/custom-period semantics, current-frame copy callbacks, terminal clear
(including rejected late renders), concealed-frame text removal, thumbnail suppression and
artwork lookup reuse/invalidation. Period formatting calls Android's localized resource. No
legacy adapter/holder/XML is used. Inputs are synthetic frame values, so these tests do not
replace end-to-end Activity/controller/session coverage.

Compose's JUnit test dependency is instrumentation-only; its empty activity manifest is debug-only.
The app test sources invoke no composables directly and need no Compose compiler plugin of their
own. The sandbox has compile-only test APIs that throw if invoked; it cannot execute UI assertions.
Both market-flavor connected-test tasks in README remain required on a disposable device/emulator.
Legacy fixtures/XML are retained until replacement coverage has actually run and parity is reviewed.

### Manual real-Android validation gate

The existing Android workflow now includes a manual-dispatch-only `compose-validation` job:
F-Droid on API 28 and Play on API 36, using disposable x86_64 emulators. It first builds the real
app and instrumentation APKs, then runs `ComposeVaultDeviceTest` and `IssuerArtworkDeviceTest`.
A report checker requires all four selected cases to pass; absent, skipped, malformed or failed
results cannot be mistaken for runtime validation. Logs and reports are uploaded even on failure;
debug APKs are not published. This job uses no signing environment or secrets and does not run on
master pushes. The existing release jobs remain restricted by their original signing gates.

Dispatch against the branch under review with `gh workflow run android.yml --ref <branch>`.
Only a completed successful emulator run is evidence of device-test success. A checked-in workflow,
source guard pass or sandbox compile is not. This focused gate does not replace the full connected
suite, manual accessibility/visual review or release validation; both flavors on both API levels
are also outside this small matrix.

### Desktop replacement: session lifetime prerequisite

The Compose desktop host now honors `savedInactivityLockSeconds()` using a Swing-free AWT
scheduler and the shared `ScreenTimerController` through `DesktopSessionLifetime`. Input from
the host window and its owned dialogs resets a live deadline; input after expiry locks rather
than extending the session. Timeout closes the shared presenter/dialogs and destroys the owned
vault state. Disposal removes the global input listener, invalidates pending timers and locks
the presenter. Window-close explicitly clears the vault before exiting. Zero retains the existing
disabled-timeout preference. The Compose preference editor is not implemented yet.

Four headless policy tests cover expiry, rearming/stale timers, disabled/large timeouts and failing
cleanup. Three additional tests execute real JDK AWT scheduling (not Compose stubs), including EDT
delivery, cancellation and terminal close; `compile-ui.sh` now runs these after type-checking hosts.
Real Compose window lifecycle/input delivery and operating-system suspend/resume still need
display-backed validation. This does not switch the shipping launcher or remove Swing.

Next desktop migration gates: add/edit forms and tag/thumbnail editing; persistence-aware copy and
clipboard expiry; backup/restore/import/export including structured credentials; hardware-key and
OpenPGP workflows; preferences/portable paths; then packaging/launcher cutover and Swing retirement.
Unlock and persistence work also need asynchronous UI integration before shipping the Compose host.

### Desktop replacement: manual entry creation

The shared vault screen now exposes an optional guarded Add entry action; only the Compose desktop
host supplies it (Android keeps its existing add flow). `ManualEntryDialog` collects TOTP, HOTP,
Steam, mOTP, secure-note and SSH-key inputs, including tags, optional mOTP PIN and SSH connection
metadata. Password/email imports remain separate workflows and are not offered as manual types.
`ManualEntryInput` delegates construction/validation to `EntryDraft`, preserving Steam's fixed
parameters and free-text notes. It redacts diagnostic output. Input is not saveable; secret fields
are cleared before submission and after dismissal/type changes. Failed submissions require
secret re-entry. Immutable JVM strings cannot be zeroized.

`DesktopEntryCreation` binds submission to an exact dialog request and a live desktop state;
dismissed/replaced requests and independently locked states cannot build or save an entry. It
uses `EntryEditor.add` to persist before publication and destroys drafts that were not adopted.
Pending creation is closed alongside other dialogs when the presenter locks. `DesktopVaultState`
now exposes its terminal locked status for this boundary, without moving persistence into widgets.

Tests cover all offered types, field clearing/redaction, add-action liveness, persistence/reopen,
duplicates, failed/throwing saves and stale/locked requests. The form is type-checked with Compose
stubs; layout, keyboard/focus behavior and scrolling require real runtime validation. This is
add-only: existing-entry editing, tag/thumbnail editing, asynchronous persistence and the other
desktop migration gates remain pending. The shipping launcher is unchanged.

### Desktop replacement: editing existing manual-entry types

Revealed OTP/note/SSH rows now offer Edit through the shared action contract. The presenter checks
its current reveal set before opening, and the host checks it again on submission. Password/archive
and email entries remain outside this form. `DesktopEntryEditing` binds the dialog to the actual
entry object and exact request: removal, same-ID replacement, dismissal, type changes or terminal
lock invalidate the operation. It delegates persistence/publication to `EntryEditor.replace`,
destroys unadopted replacements, and leaves the original unchanged on rejected/failed writes.

The shared form locks the type while editing and preloads metadata only. Blank secret/note/key,
PIN, SSH password and protected-label fields mean keep the original; sensitive content is not
preloaded into form state. Editing such values replaces them in full; clearing an existing PIN,
SSH password or protected label is not yet offered. Metadata edits retain usage stamps, chosen
artwork and persistent privacy; the existing replacement path preserves row ID and manual order.
Successful replacement drops the old transient reveal state. Tags can be changed in the form;
a dedicated tag/thumbnail picker remains pending.

Tests cover secret/metadata preservation, fixed types, reveal guards, persist/reopen, failed and
throwing saves, stale requests and same-ID replacements. Real Compose editing, focus/scrolling,
async IO and the remaining desktop workflows still need validation/migration before launcher
cutover. No Swing renderer was removed in this increment.

### Desktop replacement: thumbnail selection

The shared manual-entry form now includes a searchable catalog picker, supplied with the host's
existing `VaultArtwork` loader. A bounded-height lazy list loads previews for composed choices,
with memoization keyed by provider/name. Search is case-insensitive and does not persist. The
picker offers explicit `Default` (letter tile), automatic issuer selection on creation, or keeping
the original artwork while editing. Returning from the picker without choosing leaves the draft
unchanged; canceling the form never writes a thumbnail. Save is disabled while browsing choices.

`ManualEntryInput.thumbnailName` is a nullable, validated catalog choice. Null preserves the
original on edit or leaves the constructor's automatic choice on creation. An explicit choice
overrides that behavior only on the detached candidate, using the existing add/replace persistence
path. Secret-field clearing and type switches retain the selected thumbnail. Tests cover the
three selection modes, invalid names, original-entry isolation, save/reopen and failed-write
preservation. The picker still needs real Compose focus, scrolling and visual/accessibility tests.

This completes the initial add/edit form's thumbnail selection, not the desktop cutover: structured
credentials, clipboard behavior, backup/import/export, preferences, hardware/OpenPGP integration,
async IO and packaging remain before the shipping Swing launcher can be retired.

### Desktop replacement: persisted copying and clipboard expiry

Compose now binds copy actions to exact frame-row and live-entry identities rather than copying
displayed strings. Requests recheck current reveal/session authority and refresh time-based OTPs
at copy time. Usage is persisted before any clipboard write; rejected writes roll back the usage
metadata and return no secret. Static notes/passwords use the same path; SSH copying always
explicitly chooses public key, private key or connection password, without showing values in the
chooser. Persistent privacy flags are not temporarily mutated to grant Compose reveal authority.

A session-owned, Swing-free clipboard lease expires after 30 seconds and clears on lock/disposal
only when the current text still matches what this session copied. Recopy cancels the old lease;
late callbacks cannot clear newer copies. Clipboard errors are surfaced without printing values.
Clearing is best effort: busy/unavailable native clipboards and same-value writes by another app
cannot be distinguished or atomically controlled through the desktop clipboard API.

Headless tests cover live code refresh, persistence/reopen, usage rollback, original frame identity,
same-ID replacement, hidden/locked/dismissed targets, SSH field selection, note whitespace and
clipboard expiry/cancellation/errors. The native clipboard and Compose chooser still require
display-backed validation; this is not the launcher cutover.

### Desktop replacement: asynchronous unlock/create

The Compose unlock screen now submits to a dedicated worker, with result adoption on the AWT
thread. Duplicate submissions are rejected while an attempt or its UI delivery is pending. The
password field is disabled while busy and cleared before handoff. Closing/disposal invalidates
attempts, drops queued passwords and destroys unadopted loaded entries/keys; stale successful
results cannot restore a session. Backend exceptions are reduced to an error enum, never shown
verbatim. Adopted results belong to the new desktop session.

Cancellation does not interrupt an in-flight atomic vault write: a canceled creation may finish
writing the vault, but its result will not enter a closed UI. Immutable password strings cannot
be zeroized while the backend is using them. Six deterministic worker/UI-queue tests cover success,
serialization, close-before-start, close-after-result, close-during-backend, failures/retry and
rejected dispatch. Real Compose busy/focus/close behavior still needs runtime validation. Unlock
is asynchronous now; other persistence/import/export operations are not all migrated off the UI
thread yet.

### Desktop replacement: persisted manual ordering

Unsorted/unfiltered Compose rows now expose up/down controls using the same `canDrag` policy and
`EntryEditor.move` persistence path as Swing. Each callback binds the exact row instance, live
entry and original adjacent entry. Filter/sort changes, replacement, lock or a changed neighbor
reject stale moves rather than moving whatever now occupies an old row index. Failed writes
leave the in-memory order unchanged. Tests cover persistence/reopen, stale neighbors, forged
row values, changed filters/sorts, replacement, lock and failed writes. These are button-based
controls, not touch dragging, and still need runtime interaction/accessibility validation.

The migration remains incomplete. In particular: native backup/restore and import/export UI,
structured password/email editing, preferences and portable-location controls, OpenPGP/hardware
workflows, QR/platform integrations, background persistence beyond unlock, packaging/launcher
switch and display-backed parity review remain before Swing removal is safe.

### Desktop replacement: auto-lock preference control

The Compose toolbar now offers an optional Auto-lock action. Its session-bound dialog uses the
existing desktop choices (disabled, 30 seconds, 1/2/5/15 minutes) and preserves a custom imported
value until the user changes it. Cancel never persists; stale/replaced/locked requests are rejected.
A successful write updates the existing lifetime controller rather than recreating or locking the
session. Overdue sessions cannot be revived by changing or disabling the timeout, even if a slow
settings write crosses the deadline. In that case the stored preference may change, but the current
session locks. Timers are started in the composition effect, not while a remembered owner is built.

The controller now exposes a checked preference write, and DesktopSettings restores its in-memory
cache after a failed write. This prevents a failed save from applying only until the next restart.
Tests cover dialog identity/cancel, successful updates, failed writes, existing/custom/disabled
values, expiry during writes, deferred/idempotent timer startup, reopen persistence and cache
rollback. The shared action is also guarded after presenter lock. Runtime keyboard/accessibility
and native window validation remain pending. This is the auto-lock preference, not the complete
preferences/import/export UI or desktop launcher cutover.

### Desktop replacement: password-encrypted backup export

The Compose toolbar now offers encrypted export through a native AWT save picker and a shared
masked password/confirmation dialog. Existing filenames are refused, including a file created
after the picker closes: the writer uses `CREATE_NEW`, never truncate/replace. A selected filename
is rechecked against session lifetime after the native modal event loop returns. No Swing dialog
is used. Android retains its existing export flow.

Submission serializes a detached full-vault snapshot on the UI thread, then uses the shared
`EncryptedBackupCodec` on a worker to produce the Android-compatible password-encrypted format.
The worker never borrows live entries or the vault key. UI password fields clear before handoff;
immutable password/JSON strings cannot be zeroized while encryption uses them. Queued input is
dropped on cancel/lock, ciphertext buffers are wiped after use, and stale UI completions are
ignored. Lock state is volatile for cancellation checks; all entry access stays on the UI thread.

The output is restricted before ciphertext is written (0600 on POSIX; inherited directory ACLs
remain relevant elsewhere) and force-synced before success. Writing is exclusive, not a transactional
replace: an interrupted/failed write can leave a partial encrypted file and the UI explicitly
reports that risk. A write already authorized by the worker may finish after cancellation/lock.
No plaintext backup is written and existing destinations are not deleted or overwritten.

Tests exercise shared-codec round-trip, detached snapshot isolation, exclusive-create behavior,
permissions, password validation, duplicate/stale requests, cancellation before and during
encryption, independent lock, late delivery and failure cleanup. Native picker focus, Compose
password/progress behavior and real Gradle/display validation remain pending. Restore/merge/replace
UI and OpenPGP export are not part of this increment; the shipping launcher is still Swing.

### Desktop replacement: password-encrypted restore

Compose now offers a native AWT open picker and a masked password dialog followed by a separate
confirmation step showing the decoded entry count. Merge is the default; replace is explicitly
labeled as deleting all current entries. Picker return, decoding delivery and final confirmation
check the current session/lifetime. Password drafts are cleared before worker submission. The
worker uses bounded file reads and shared backup/JSON decoding; empty or duplicate-account
backups are rejected. No worker accesses the live key or mutable live entries.

Decoded entries remain owned by a cancellable task while UI delivery is queued, then by the
request until confirmation. Cancel/close destroys pending entries immediately; late or rejected
delivery destroys its unadopted result. The existing coordinator now accepts this ownership
transfer and consumes entries on both success and failure. Merge plans against the current vault
at confirmation, retaining existing accounts and their metadata. Persistence succeeds before live
state is replaced; failed persistence preserves current entries. The final save remains synchronous
on the UI thread, serialized with other mutations; asynchronous transactional persistence is still
pending. A save crossing the inactivity deadline is not interruptible; the timer handles expiry
when the event thread resumes. Immutable decoded JSON/password strings cannot be zeroized.

Tests cover real encrypted backup decoding, confirmation, merge/replace and reopening, concurrent
pre-confirmation additions, cancellation before work/during decoding/while delivery is queued,
expired delivery, rejected dispatch, wrong passwords, malformed/empty/duplicate backups, and
failed persistence. Shared action guards and both platform UI seams pass sandbox type checks.
Real native picker, Compose rendering/accessibility and device validation remain outstanding;
Swing remains the shipping UI. OpenPGP, QR, structured credentials and remaining preferences
continue to be migration work.

### Desktop replacement: explicit optional-secret removal

The shared edit form now provides deliberate, undoable removal choices for mOTP PINs, SSH
connection passwords and OTP protected labels. Blank fields continue to preserve existing values.
Selecting removal clears/hides the corresponding draft text and displays the pending operation;
undo restores the preserve behavior, not previously typed text. Only Save applies the change.
Submission clears secret drafts and deletion intent, so a failed save requires selecting removal
again. Creation/type switching never carries deletion intent. The model rejects removal on
creation, inapplicable entry types and simultaneous replacement text plus removal.

Tests cover preservation of original fields/key material, explicit removal, invalid/conflicting
input, reset of retry intent, persistence/reopen, failed writes, and reveal/stale-request guards.
No new secret is preloaded into the form. These changes use the existing replacement persistence
path; native Compose visual/accessibility validation is still pending. This closes the optional
PIN/password/protected-label clearing limitation, not structured-credential migration.

### Desktop replacement: pasted OTP URI import

The Compose toolbar now opens a shared masked URI form for `otpauth://` and
`otpauth-migration://` payloads. It uses the existing coordinator/parser/collector and persistence
path, not a second URI implementation. Draft text is bounded and cleared before submission or
dismissal, never saveable, and parser messages/URIs are not echoed into result text. JVM immutable
strings still cannot be zeroized. Parsing and saving remain synchronous on the UI thread.

Additional accounts for existing issuers are excluded by default; an explicit option allows
them while exact duplicates remain skipped. That policy is fixed when a migration batch starts.
The dialog reports received/expected parts; only a completed batch can save. Duplicate parts,
other exports and non-migration payloads cannot silently join the active batch. Cancel, lock and
disposal discard buffered seeds; stale request identity and lifetime checks prevent delayed
submissions. Results report the actual count added (which can be zero after duplicate/policy
filtering). Failed persistence rolls back appended entries through the existing coordinator.

Tests cover persisted single imports, duplicate policy, invalid and oversized inputs, stale/expired/
locked requests, multi-part buffering, cancellation and frozen policy, and failed-write rollback.
Shared UI/action guards and platform seams pass sandbox gates, not real rendering/accessibility
validation. Native QR image decoding/camera capture and structured-credential imports remain
separate work. Swing remains the shipping launcher.

### Desktop replacement: QR image file import

The OTP import dialog now offers a native image picker. A worker reads at most the shared document
limit and delegates dimension/pixel limits and decoding to the existing `ZxingQrDecoder`. Decoded
text is bounded by the URI limit, kept hidden, and requires an explicit Import action through the
existing URI coordinator. Image selection alone never imports accounts. Multi-part migration
images use the same batch ownership and fixed issuer policy as pasted payloads.

A cancellable task owns the pending decoded string until guarded UI delivery; cancel, lock and
disposal discard it. A ready payload can be consumed once or explicitly discarded. Submission
clears the pasted draft, and the paste field is disabled while decoding or while an image result
is ready. File bytes are wiped after decoding; immutable decoded strings and image-library
buffers cannot be guaranteed zeroized. Dispatch rejection drops results but can leave the busy
indicator until dismissal. Parsing/final persistence remain on the UI thread.

Queue-based tests cover one-time consumption, duplicate submission, cancellation before/during/
after work, expired/stale results, rejected scheduling/delivery and invalid/oversized output.
A real ImageIO rejection test covers non-images; actual ZXing decoding remains stubbed in sandbox
gates and needs Gradle/runtime validation. Native picker behavior and Compose rendering still
need display-backed tests. No camera capture or launcher cutover is included.

### Desktop replacement: imported password editing

Compose now edits common imported password fields through the shared PasswordCsvExport builder.
The password field starts empty, stays masked and clears before submission; blank preserves the
stored password. URL and username may be changed or emptied. Notes, GUIDs, tags and other stored
columns are preserved along with artwork/color and usage/visibility metadata. The existing
identity-bound edit owner rejects stale/locked/hidden requests and destroys failed candidates.
Only layouts with one canonical URL/username/password column and no conflicting editable aliases
are offered; foreign layouts, archives and email account editing remain pending.

Tests cover persistence/reopen, extra-column preservation, blank/new passwords, failed writes,
reveal/lock/stale guards and excluded alias layouts. Form drafts are non-saveable and diagnostics
redacted. Immutable strings cannot be zeroized. This does not add password file import/export or
complete structured credential migration; real Compose runtime/accessibility validation and
asynchronous persistence remain pending. Swing remains shipping.

### Desktop replacement: browser password file import

Compose now offers a native password-file picker and two import modes: individual credentials
(default) or preservation of the original bytes as a password-archive entry. The dialog warns
that the source contains plaintext and is neither deleted nor protected by import. It reports
only a prepared item count, never file contents or credentials, and requires explicit confirmation
before merging into the vault. The original file is not modified.

Worker reads use the shared 4 MiB limit and PasswordImport parser/expansion bounds. Input bytes
are wiped after parsing. Prepared entries are owned by a cancellable task while delivery is queued,
then by the dialog request; cancellation, lock/disposal, stale delivery or rejected dispatch
destroys unadopted entries. Confirmation checks lifetime and transfers ownership to EntryEditor,
which deduplicates against current state, saves before publication and destroys failed/duplicate
candidates. Final save remains synchronous on the UI thread. Immutable parser strings cannot
be zeroized; rejected dispatch can leave the busy indicator until dismissal.

Tests cover both modes through persistence/reopen, source-file preservation, duplicate handling,
explicit confirmation, queued/ready cancellation, expired delivery, cancellation during decode,
rejected dispatch, malformed CSV and failed persistence. Actual Compose/native-picker validation
remains pending. Plaintext export of credentials/archives, foreign structured imports and email
workflows remain incomplete; Swing remains shipping.

### Desktop replacement: FairEmail backup import

Compose now imports FairEmail plaintext JSON, gzip agent backups and encrypted exports through
a native picker and a shared confirmation dialog. Blank password supports unencrypted input;
nonblank passwords stay masked and are cleared before worker submission. The existing EmailAccounts
parser and 8 MiB file limit remain authoritative. No credentials are rendered in the confirmation,
only an account count. Confirmation merges through EntryEditor into current state, skipping
identical entries, saving before publication and retaining existing accounts. Source files remain
unchanged. Email creation/editing/details/export remain separate migration work.

DesktopFileImport now owns the common browser-password/FairEmail lifecycle: queued inputs are
released on cancel, worker results have explicit ownership, queued/ready results are destroyed
on dismissal or close, and stale/expired delivery cannot install entries. Each format supplies
its bounded decoder; both use the same UI-thread commit path. Password/JSON strings cannot be
zeroized while in use. Final persistence is still synchronous and dispatch rejection may leave a
busy indicator until dismissal.

Tests cover plaintext, gzip and encrypted v0/v1 through vault reopen, incoming/SMTP credential
preservation, unchanged source bytes, wrong-password retry, stale requests and cancellation
before/after worker execution. Browser-password lifecycle regression tests remain green after
extraction. Sandbox UI checks are not real Compose/native-picker validation. Swing still ships;
remaining structured export, OpenPGP/hardware, preferences, async persistence and runtime/cutover
work is not complete.

### Desktop replacement: email connection details and password edit reachability

Revealed email cards now offer Connection details through an optional host action. The read-only
scrollable dialog uses EmailAccounts' shared incoming/SMTP identity rows, initially masking
passwords. Show passwords is a separate deliberate action; both row generation and toggling
check current presenter reveal, session liveness and exact Entry identity. Concealment, replacement,
lock and dismissal invalidate the request. No rendered credential strings are retained by the
owner or remembered by the form. Secrets displayed in immutable frame strings cannot be zeroized.
Tests cover mask/show/hide, unchanged live entries, stale requests, replacement retaining an id,
concealment and terminal close/lock. Native rendering and accessibility remain unvalidated.

During integration, the previous password-edit form was found unreachable from cards because the
shared button still allowed only OTP/note/SSH kinds. The desktop now supplies an explicit
canEditPassword flag for revealed supported layouts; the shared card offers Edit only with that
flag. Default-false behavior leaves other hosts unchanged, and archives/unsupported layouts stay
excluded. This is a reachability correction, not a claim of prior runtime validation.

Email editing/export, remaining migration features and background persistence remain incomplete.
Swing still ships; no launcher cutover or PR is included.

### Desktop replacement: persisted card layout

A session-owned Card layout dialog offers Default, Compact and Full. Save persists the enum name
under the desktop `ui_card_layout` preference before applying it to the existing presenter.
Opening/canceling does not write; failed persistence leaves the live layout unchanged. Request
identity, terminal session state and lifetime are checked before/after save; a slow save crossing
expiry can persist a preference for next time but cannot update the expired session. Unknown
stored names fall back to Default.

The presenter changes only its appearance layout, preserving other appearance options, filters
and reveal state; updates/actions become inert after lock. The new desktop-specific preference
is included in the existing portable settings catalog; Swing ignores the renderer-specific key.
Tests cover save/reopen, invalid values, settings-cache rollback on failed writes, cancel/stale/
expired/locked requests, presenter continuity and portable preference round-trip. Actual Compose
layout, focus and accessibility checks remain pending. Other appearance settings, preferences
import/export UI, async persistence and the wider migration/cutover are not complete.

### Desktop replacement: LinSCP credential import

Compose now imports LinSCP encrypted vaults, backup bundles and standalone sessions.ini files
through the existing LinScpCredentials codec and shared DesktopFileImport owner. Native source
and optional sessions.key pickers recheck lifetime after returning; paths are not displayed or
logged in result messages. Password drafts are masked, non-saveable and cleared before submission.
Worker reads enforce the shared 16 MiB source limit and a 4096-byte key-file limit. Encrypted vaults
ignore any selected external key; unencrypted session input may use an embedded or selected key.
Source/key bytes are wiped after decoding. Immutable password/decoded strings cannot be zeroized.

Prepared entries require explicit merge confirmation. EntryEditor handles duplicate/capacity rules
and save-before-publication; cancel, lock/disposal and stale/rejected delivery destroy unadopted
entries. The source/key files remain unchanged. Final persistence is still synchronous.

Tests cover vault/bundle/external-key decoding through persistence/reopen, unchanged files,
missing/wrong/oversized keys, wrong-password retry, ignored irrelevant key selection, stale
requests and queued-result cleanup. Shared lifecycle regression gates remain green. Native picker
and Compose runtime/accessibility validation, LinSCP export and remaining migration work are
still pending; Swing remains shipping.

### Shared Compose controls: grouped secondary tools

Import, encrypted-backup and display/security actions now live in a grouped Tools dialog instead
of a growing stack of full-height toolbar rows. Add entry stays directly accessible beside Tools
in a horizontally scrollable row. Tools and empty groups disappear when their optional callbacks
are absent, preserving Android's incremental host capabilities. The dialog content has a bounded
scrollable height, and invoking an action closes the menu before dispatch to a picker or dialog.
Only ephemeral menu visibility is remembered; tool IDs retain no session callbacks or secrets.
Labels come from host-supplied VaultStrings. Lock and search/filter controls remain in place.

Model tests cover stable order/grouping, unavailable actions, callback routing, terminal guards
and host wording. Source guards cover scroll bounds and close-before-dispatch. The sandbox gained
a type-check-only heightIn signature; no real renderer/layout/focus/accessibility test is claimed.
This addresses control growth during migration, not feature parity or launcher cutover.

### Desktop replacement: encrypted LinSCP export

Tools now offers encrypted LinSCP export through the native save picker and shared masked
password/confirmation form. Scope is explicit: all SSH keys and individual password credentials,
including concealed entries and regardless of filters. OTPs, notes, emails and password archives
are excluded. An empty compatible set fails before any worker/file write. This is not plaintext
export and does not require exposing card contents.

DesktopEncryptedExport now accepts an injected UI-thread snapshot provider, defaulting to the
unchanged full-vault behavior. The LinSCP provider serializes only compatible entries; worker
parsing owns detached entries and destroys them after the shared LinScpCredentials encoder runs.
Password drafts clear before submission; immutable passwords/JSON cannot be zeroized. Ciphertext
is written with the existing exclusive-create/restricted-file path and wiped after use. Cancel
or lock suppresses pending work and stale success delivery; a write already authorized may
finish, and an interrupted write may leave a partial encrypted file. Existing files are refused.

Tests cover compatible-only snapshots, concealed/filtered entries, real encrypted round-trip,
snapshot isolation, no-compatible/locked rejection, wrong-kind rejection, exclusive destination
protection and cancellation. Tool routing and terminal action guards pass. Native picker/Compose
validation remains pending. Plaintext structured exports, OpenPGP/hardware workflows and other
remaining migration work are not complete; Swing remains shipping.

### Desktop replacement: OpenPGP file-key import

Tools now offers OpenPGP backup import with a required secret-key file and an optional masked
passphrase (blank for unprotected keys). Message/key file reads each enforce the shared 16 MiB
limit. Source and key selections are native pickers with session/lifetime rechecks. Worker
decoding uses the existing RpgpOpenPgpPort and shared backup/JSON codecs; empty and duplicate
account documents are rejected. Confirmation merges into current state through EntryEditor,
without replace-all semantics. Sources remain unchanged.

Mutable message/key/passphrase buffers are wiped after decoding, and the shared OpenPGP codec
now bounds plaintext output before string conversion and wipes the provider's returned buffer
on success/failure. Immutable passphrase/JSON strings remain non-zeroizable. Shared file-import
ownership discards unadopted entries on cancel/lock/stale delivery. Final persistence remains
synchronous.

New tests use an injected fake OpenPGP port to verify confirmation, persistence/reopen, boundary
buffer cleanup, missing/wrong credentials, malformed/empty payloads and cancellation. They do not
prove real cryptographic interoperability or the native backend. Existing core codec tests pass.
Real key/native/Compose validation, OpenPGP export and replace-all restore, hardware workflows and
remaining migration work are pending. Swing remains shipping.

### Desktop replacement: OpenPGP recipient-file export

Tools now offers full-vault OpenPGP export to a selected recipient public-key file. The confirmation
explains that concealed entries and all categories are included regardless of filters, asks the
user to verify the recipient file, and warns that the matching secret key can decrypt the backup.
This step does not inspect/display a recipient fingerprint or offer hardware recipients.

The UI thread captures a detached JSON snapshot; a worker reads the recipient within the shared
16 MiB limit and delegates to the existing OpenPGP codec/desktop port. Input key bytes, provider
plaintext bytes and output ciphertext buffers are wiped when no longer needed. Immutable JSON
strings cannot be zeroized. Existing destination files are refused through exclusive creation,
and output uses the existing restricted-file path. Cancel/lock discard queued work and late
completion; an already-authorized write may finish, and failure can leave a partial encrypted file.

Injected-provider tests verify snapshot isolation, explicit submission, required/nonempty keys,
provider failure cleanup, existing-file protection, cancellation before/during encryption and
stale success suppression. Shared codec regression gates pass. These tests are orchestration
tests, not real OpenPGP interoperability or native crypto validation. Native picker, Compose
rendering/accessibility, hardware workflows and remaining migration/cutover work are pending.
Swing remains shipping.

### Desktop replacement: OpenPGP replace-all restore

OpenPGP confirmation now offers Merge (default) or Replace all current entries. Replacement
shows a deletion warning and a distinctly labeled confirm action; the choice resets when a new
decoded result or retry starts. It is not part of file selection or decryption. The existing
merge behavior stays available and preserves current accounts.

DesktopFileImport exposes a protected confirmation transaction hook while its public default
confirmation stays merge-only. DesktopOpenPgpImport requires an explicit host replacement
callback to use that hook. The desktop supplies BackupCoordinator.restoreEntries with replace
enabled: save first, install next, destroy removed entries only after successful persistence.
Session/request/lifetime checks still run before commit; canceled/expired/stale requests cannot
replace entries. Persistence remains synchronous and an already-running save is not interruptible.

Injected-provider tests cover explicit replacement through reopen, old-entry destruction after
success, save-failure cleanup with old entries intact, missing replacement authority, expiration
before confirmation, stale repeat confirmation and merge preservation. Native OpenPGP and real
Compose validation are still pending; this does not complete hardware or launcher migration.

### Desktop replacement: portable preferences export

Tools now offers Export preferences through a native save picker. The controller uses the shared
PreferencesExport codec and DesktopPreferencesPort with includeSecrets=false and desktop platform
selection. Only catalogued sort order, inactivity timeout and card layout are eligible; vault
entries, auth/KDF state, backup passwords and unknown keys are excluded. Success reports the
actual exported setting count, including zero for an untouched configuration.

The UI thread captures the settings document; a worker writes it through ExclusiveExportFile,
extracted from the existing ciphertext writer without changing its exclusive-create, permission
or force-sync behavior. EncryptedBackupFile remains a ciphertext-only facade. Existing files are
never overwritten. Snapshot bytes are wiped after write/failure/cancellation. Dismissing queued
work prevents its write; work already taken by the worker may finish, but cannot publish success
after cancellation/lock. A failure may leave a partial settings file. Rejected dispatch can leave
the progress notice until dismissal. Native picker return and result delivery check lifetime.

Tests verify catalog-only contents and portable decode, auth/secret/unknown-key exclusion,
existing-file preservation, canceled snapshots, late completion, failed writes and expiry.
Real native/Compose validation, preferences import UI and remaining migration work are pending.
Swing remains shipping.

### Preferences import prerequisite: one-write settings transaction

Before exposing Compose preferences import, DesktopPreferencesImportPlan now validates into an
isolated in-memory adapter, retaining only allowlisted encoded values and counts of accepted,
unknown and refused keys. Preview never touches live settings. Applying a plan requires the
DesktopSettings backend and merges the mentioned keys into its current state under the settings
monitor, preserving auth and unmentioned keys even if they changed after preview.

DesktopSettings now serializes a candidate map and performs one existing AtomicFiles write
before publishing its cache. Single-key writes use the same candidate-before-publication path.
Injected pre-promotion failures leave disk/cache unchanged; successful imports survive reopening.
As before, underlying AtomicFiles has a non-atomic fallback on filesystems without atomic moves;
this is not a promise of crash atomicity on every filesystem or recovery from post-promotion
permission errors. No new secret keys are made portable.

The shipping Swing preferences import now previews and applies the same plan, replacing its
per-key persistence loop. Tests cover one-write publication, preparation without mutation,
omitted-key preservation, full cache/disk rollback for injected write failure, retry, malformed
input, wrong platform/type, excluded keys and no-op empty plans. Compose import UI and live
timeout/layout refresh remain the next increment; Swing still ships.

### Desktop replacement: preferences import UI and live refresh

Tools now offers Import preferences through a native picker, bounded worker parsing and a
confirmation dialog reporting accepted/skipped counts. The dialog explains that absent settings
are retained, vault/auth data is untouched, and an imported timeout may disable auto-lock.
Only the validated DesktopPreferencesImportPlan reaches the single-write controller path.
Malformed/wrong-platform files cannot be confirmed; failed saves do not refresh the live view.

The session/request is checked before and after commit. If persistence crosses the old inactivity
deadline, that deadline locks the current session before an imported disabled/longer timeout can
be applied. The setting may still be persisted for a future session. Only keys present in the
plan update the existing presenter's sort/layout or the live timer; omitted timeout does not
restart inactivity. Other filters and reveal state are preserved. Disk persistence remains
synchronous; worker parsing and result adoption are cancellable, not in-flight settings writes.

Tests cover preview/no mutation, explicit confirmation and reopen, omitted-key preservation,
canceled/expired results, stale confirmation, failed commit and slow-write expiry. Tool availability
and terminal guards are tested. Rejected worker dispatch can leave the loading state until
dismissal, as with other import owners. Real Compose/native-picker validation and remaining
migration/cutover work are pending. Swing remains shipping.

### Async unlock lifecycle hardening: queued result ownership

A review found that DesktopUnlockOperation previously captured loaded entries/key directly in
the queued UI Runnable. Closing invalidated adoption, but cleanup waited for that callback to
run. A stalled/discarded event queue could therefore retain decrypted vault material longer
than the screen lifetime. The attempt now owns the result under a monitor; the Runnable retains
only the attempt. Close cancels and destroys an offered result immediately, while results
finishing after cancellation are destroyed at handoff. Successful adoption transfers ownership
once and close no longer touches the adopted session.

Completion also rechecks terminal state after onBusy(false), since a host callback can close
synchronously. Tests assert destruction before queued callback delivery, cancellation between
offer/enqueue, dispatch rejection after enqueue, reentrant close, and a real executor/latch
cancellation during backend execution. This does not interrupt an already-running create/write
or zero immutable password strings; no native-window test is claimed. Remaining feature parity,
background persistence and actual Compose/device validation are still pending. Swing remains
shipping.

### Compose manual email account creation

The existing Add entry form now offers Email account on desktop. Switching drops the prior
manual-entry form values; the email form uses ephemeral remembered state, masked incoming and
outgoing passwords, explicit IMAP/POP and SSL/STARTTLS/unencrypted choices, port and FairEmail
authentication-type inputs, plus bounded metadata/tags. Defaults are IMAP/SSL 993 and SMTP/SSL
465 with password authentication type 1; changing protocol/encryption deliberately does not
silently rewrite ports. The form states that it stores details offline without contacting servers.
Passwords are removed from form state before each submit and on dismissal; immutable strings
cannot be zeroized.

EmailEntryInput builds a fresh one-identity account using shared EmailMaterial validation.
DesktopEntryCreation routes it through the same identity/liveness gate, save-before-publication
and unadopted-entry cleanup as OTP/note/SSH creation. The host rechecks the idle deadline for
both creation paths. This is creation-only, never an imported-account reconstruction that could
drop additional identities or provider metadata. The email path is optional in the shared dialog
and not enabled by the Android host.

Tests cover separate credentials, FairEmail JSON roundtrip, invalid numeric/range/size/tag inputs,
redaction, save/reopen, failure cleanup and stale/dismissed/locked requests. Actual Compose layout,
accessibility and device/native-window execution remain unvalidated by the stub harness. Email
editing/export, other remaining parity, and asynchronous final persistence remain pending; Swing
continues shipping.

### Confirmed plaintext FairEmail export

Tools now offers an optional desktop-only FairEmail export action. After the native destination
picker returns and the current idle deadline is checked, an identity-bound request presents an
explicit warning: ALL email accounts, including concealed/filtered-out accounts, all their
identities, and plaintext passwords/tokens are exported. No snapshot or worker write occurs until
that request is confirmed. The export is not encrypted and the user must protect/remove the file.

The UI thread serializes email entries with EmailAccounts.exportBackup (the shared FairEmail
codec), preserving imported provider fields/extra identities without reconstructing the account.
Only detached bytes cross to the worker. ExclusiveExportFile uses CREATE_NEW and restricted POSIX
permissions where supported; existing files are never overwritten. Mutable bytes are wiped after
success/failure, rejection, or queued cancellation. Closing/locking invalidates confirmation and
late completion; it does not interrupt an already-started authorized write. Partial plaintext
files may remain on write failure and the UI warns about that. Serialization still runs on the
UI thread, immutable JSON strings cannot be zeroized, and a rejected dispatcher can leave the
progress notice until dismissal. No stronger cancellation or secure-deletion guarantee is made.

Tests cover explicit confirmation, empty/expired/locked/stale requests, filter-independent email
selection, detached snapshot, multiple identities/provider metadata, FairEmail JSON roundtrip,
exclusive destination handling, queued/in-flight cancellation, write/dispatch/executor failures,
buffer wiping and late-result suppression. Native picker, filesystem portability, Compose visual/
accessibility/device checks remain release gates. Email editing, other export types, hardware
workflows and remaining migration parity are still pending. Swing remains shipping.

### Email credential replacement (not full account editing)

Revealed desktop email connection details now offer Change passwords / tokens. This opens a
separate ephemeral masked form with incoming credentials and one outgoing field per stored SMTP
identity, in connection-details order. Stored secrets are never prefilled; blank means preserve.
The form clears entered secrets before submit and on dismissal. It explicitly does not change
provider passwords or refresh OAuth tokens, and authentication types/server settings stay intact.
There is no credential-removal operation in this increment.

EmailPasswordInput updates only nonblank password fields on the detached decoded account and
identities, validates/encodes through EmailMaterial, then replaces the cloned entry's secret.
All imported provider fields and additional identities are preserved, as are custom vault labels,
issuer, protected label, tags, artwork, visibility and usage metadata. Mutable encoded buffers
are wiped; immutable JSON/password strings cannot be zeroized. DesktopEntryEditing binds the
request to the exact live entry and checks current reveal authority before building/saving. The
Compose host checks the idle deadline and drops old reveal state after successful replacement.
Failed writes retain the original entry and destroy unadopted candidates.

Tests cover multiple-identity selective changes, blank retention, size/count rejection, diagnostic
redaction, all-envelope metadata preservation, original-account isolation, persistence/reopen,
concealed/stale/dismissed/removed/locked requests and failure cleanup. Broader email metadata and
identity editing remain pending. Final persistence is still synchronous. Actual Compose visual,
accessibility and native/device execution remain unvalidated; Swing remains shipping.

### Add/edit persistence deadline and reentrancy hardening

DesktopEntryCreation and DesktopEntryEditing accept a live session guard on submissions. The
Compose host supplies lifetime.checkActive for manual/email creation and manual/password/email
credential editing. Owners check the deadline and current request before construction, before
persistence and immediately after synchronous persistence, including failed/throwing saves.
Guard callbacks can themselves lock/dismiss the owner, so identity is rechecked after the guard.
Successful replacements no longer require the old entry to remain in state, but do require the
same pending request and live unlocked owner. Completion of an old save cannot clear a newer
dialog or mutate its failure state. Unadopted candidates are destroyed as before.

This closes the blocked-UI-thread window where a save crosses the idle deadline while the timer
callback is waiting. It does not interrupt or roll back an authorized disk write. A save that
finishes after the deadline can remain persisted; the session locks before reporting success,
and those changes are visible on the next unlock. Final entry persistence remains synchronous.

Tests use a deterministic monotonic scheduler and real save/reopen for all three edit kinds,
both creation paths, expiration before build/save, reentrant guard/dismissal, replacement dialog
completion, and expired failing/throwing saves with candidate cleanup. No real Compose/device
execution is claimed. Remaining feature parity and native/device validation are unchanged; Swing
continues shipping.

### Browser password export preparation (not yet exposed in Compose)

DesktopPasswordExportPlan prepares all recognized browser-password rows independent of filters
and reveal state. It groups by browser dialect and normalized ordered source headers, preserving
first-seen group order and row order. Unlike the existing cross-browser conversion API, it never
merges unlike headers into a canonical header, so provider-specific columns, notes, GUIDs and
OTPAuth are not silently dropped. It writes exact source cells through the shared CSV serializer,
not conversion aliases: an empty name/note remains empty even if title/notes also exists.

The plan retains mutable CSV bytes, not live entries. Each group transfers ownership once to a
future writer, whose responsibility is to wipe the bytes. Closing wipes all untransferred groups.
Diagnostic strings expose only counts/dialects. Foreign password records and password archives
are counted separately and not exported; other entry kinds are ignored. Preparation requires an
unlocked vault. This is only a tested data/ownership boundary: no file is written and no Compose
action is enabled. A future owner must enforce current plaintext confirmation, request/lifetime
guards, exclusive writes, and cancellation/late-result cleanup. Serialization remains synchronous
and immutable parser/CSV strings cannot be zeroized.

Tests cover mixed headers/dialects, unknown columns, quoting/newlines, source order, blank alias
cells, filter-independent detached snapshots, exclusions, one-time transfer, disposal, diagnostics
and locked-state rejection. Browser-password export UI and archive export remain pending, as do
real Compose/native/device validation and other migration parity. Swing remains shipping.

### Confirmed browser-password CSV export UI

The previous preparation groundwork is now exposed in desktop Tools. Opening Export browser
passwords prepares a filter-independent snapshot and shows numbered dialect/count groups, plus
excluded foreign-password and archive counts. Each group has matching original ordered columns.
The user selects one group, picks a native destination, and explicitly confirms plaintext export.
The warning includes passwords, notes, OTP seeds and provider-specific secrets; the chooser warns
not to execute CSV cells as spreadsheet formulas. Values remain unchanged for lossless roundtrip.
No passwords/columns enter the chooser. Reopen Export to save another group.

DesktopPasswordExport owns the plan and binds choice/confirmation to the current request and
idle deadline, including after native picker return. Confirmation transfers only the selected
CSV buffer to a worker and immediately disposes unselected groups. ExclusiveExportFile refuses
existing destinations. Dismissal/lock wipes queued bytes and invalidates late delivery; worker
output is wiped on success/failure. A write already started can finish after cancellation, partial
plaintext files can remain after failure, and a rejected dispatcher can leave progress until
dismissal. The UI explains these limits. Serialization remains on the UI thread and immutable
strings cannot be zeroized.

Tests exercise selection versus consent, exclusions/group preparation (existing plan suite),
exclusive writes, stale/expired/reentrant guards, lock before worker, rejection, mutable-buffer
cleanup and late delivery. App/instrumentation harness checks are not real Compose/native/device
validation. Password archive export, broader email settings, remaining parity and release
validation are still pending. Swing remains shipping.

### Exact password archive export

Tools now offers Export password archive (plaintext). A label-only chooser includes concealed
and filtered-out archives but excludes ordinary password rows and other entry types. It retains
exact entry identities, not copied archive secrets. After selecting an archive and native
destination, the user must confirm that the file may contain plaintext passwords, notes, OTP
seeds and other secrets. The chooser warns that contents are restored without sanitization and
should not be executed as formulas/scripts. A fixed password-archive.bin picker default avoids
using untrusted stored labels as paths; the user chooses the actual name/extension.

DesktopArchiveExport rechecks request/lifetime authority after the picker and before copying
bytes. If the selected entry has been replaced/removed, confirmation is dismissed. Only a
confirmed exact live archive is copied to a worker; no conversion, decoding, compression or
encryption is performed. ExclusiveExportFile refuses existing files. Queued cancellation and
lock suppress work, output buffers are wiped, and stale results cannot revive a dialog. Writes
already started may finish after cancellation; partial plaintext output may remain on failure.
Rejected UI dispatch can leave progress until dismissal. These are not secure-deletion guarantees.

Tests cover byte fidelity (including non-UTF8 data), filter-independent archive-only selection,
explicit confirmation, hostile stored filenames, replacement/dismissal/expiry, exclusive writes,
queued cancellation, failure/rejected delivery cleanup and late results. Native picker and real
Compose/device/accessibility validation remain pending. Other remaining migration work includes
password-change UI, broader preferences and email settings, hardware/camera integrations,
background persistence, packaging and cutover validation. Swing remains shipping.

### Lock-first password-change owner (UI wiring pending)

DesktopPasswordChange is a window-level owner for an asynchronous password rekey, not yet
exposed in Compose. It validates bounded nonempty current/new passwords, matching confirmation
and a changed password, checks live authority, then requires a synchronous lock callback that
actually destroys the old session before scheduling backend work. A failed/no-op lock callback
never authorizes the backend. One owner allows one authorized attempt; retries require a fresh
unlock. The existing VaultController.changePassword performs the storage rekey. Returned keys
and entries are destroyed immediately on the worker, before status delivery; none are adopted
or captured by a queued UI result.

Closing before worker start drops credential references and suppresses the backend. Closing
during rekey suppresses UI completion but does not interrupt the storage transaction; the new
password may take effect. Immutable password strings cannot be zeroized. Success, rejection and
backend failure all leave the original session locked. Rejected dispatch leaves WORKING until
the host closes the owner. No new UI/notifications or recovery claims are made in this increment.

Future Compose integration MUST retain the owner outside vault composition, disable unlock and
all other vault operations until backend execution has ended, and serialize credential operations
against an in-flight rekey even after dismissal. Locking the old state alone does not prevent a
separate new unlock/write racing the backend. These host lifecycle gates, status presentation,
explicit irreversible-operation messaging and fresh-unlock flow remain required before exposure.

Tests cover real save/rekey/reopen, wrong-current-password disk preservation, invalid/expired
input, lock enforcement, reentrant close, queued/in-flight cancellation, worker/dispatch/backend
failures and immediate key/entry destruction. Sandbox crypto/storage tests are not native UI or
device validation. Swing remains shipping and migration parity is still incomplete.

### Compose password-change UI and window-level interlock

Tools now exposes Change vault password. An ephemeral masked current/new/confirmation form
warns that submission locks the vault and requires fresh unlock, cannot be canceled once started,
and does not change existing backup passwords. Values are dropped before submit and dismissal;
immutable strings cannot be wiped. Invalid input permits retry while still unlocked. Idle lock
dismisses an unsubmitted form.

DesktopPasswordChangeFlow lives at the window level, outside VaultContent. It owns the operation
and blocks fresh unlock whenever a form/result exists. The owner enters WORKING before calling
presenter.lock, so the lock callback cannot accidentally dismiss the authorized operation. That
callback closes all vault authorities and destroys the old state synchronously, before the rekey
worker is scheduled. VaultContent disposal may run later but cannot authorize old-key writes.
While WORKING, form dismissal and normal window-close requests are refused. The unlock UI is
not composed until a completed result is explicitly dismissed. This prevents another unlock or
vault mutation racing backend rekey. The dedicated worker shuts down on window disposal.

Success tells the user to unlock with the new password. Failure gives conservative current/new
password and file-preservation guidance, not a claim that the transaction rolled back. Rejected UI
delivery fails closed: progress/close/unlock stay blocked. There is no force-quit/process-crash
guarantee, and no retry is authorized by a completed owner. These tradeoffs are intentional until
a more robust recovery/status transport exists.

Headless interlock tests cover deliberate lock versus idle lock, disabled dismissal/window exit/
unlock throughout queued work and queued delivery, stale submissions and rejected dispatch.
Existing rekey tests cover real controller roundtrip and wrong-current-password preservation.
Source guards/typechecks cover the Compose boundary, not real modal/window event behavior.
Native/Compose/accessibility/device validation, hardware integration and remaining migration
parity still block cutover. Swing remains shipping.

### Email connection settings editor

Revealed email details now offer Edit connection settings separately from password/token changes.
The ephemeral form edits incoming IMAP/POP, hosts, bounded ports, SSL/STARTTLS/None, and usernames
for incoming and each existing SMTP identity. It warns that no servers are contacted, ports do
not auto-adjust, and None disables transport encryption. The input contains no passwords/tokens.

The model modifies detached account/identity JSON, validates through EmailMaterial, and replaces
the cloned entry secret. Credentials, authentication types, identity addresses/UUIDs, provider
fields and vault metadata remain intact. Existing legacy pop/starttls flags are synchronized
with edited protocol/encryption fields. It does not add/remove/reorder identities or change auth
types, addresses or account display names. Those broader account-management features remain
out of scope. Encoded mutable buffers are wiped; immutable JSON strings cannot be zeroized.

Requests carry an explicit settings-versus-credential edit mode; the wrong submit path is
rejected. Exact-entry, live reveal and before/after save idle-deadline guards apply. Failed saves
retain originals and destroy unadopted candidates. Tests cover multi-identity preservation,
legacy flags, all envelope metadata, bounds, redaction, save/reopen, mode/reveal/expiry/lock
checks and failure cleanup. Native/Compose layout, accessibility and device validation remain
pending. Final persistence is still synchronous, and Swing remains shipping.

### Preference dialog save-boundary hardening

The individual layout and auto-lock owners now check the existing idle deadline immediately
after persistence whether the write succeeds, returns false or throws. A failed write that
blocks the UI past its deadline locks before any failure state is rendered. Previously the
failure branch returned before this check, leaving expiry to a queued timer.

Both owners recheck current request identity after guard callbacks and after persistence. A
reentrant write that replaces/dismisses/closes its dialog cannot clear a newer request, apply
a stale layout, or disable/extend the current timeout. Auto-lock changes the live timer only
after successful persistence AND current live authority. Already-persisted preferences are
not rolled back; they can apply on the next session. Persistence remains synchronous.

Deterministic scheduler tests cover failing/throwing writes crossing expiry, stale success and
failure after request replacement, reentrant layout guards before/after save, and owner closure
during a successful timeout write. Existing success/reopen/cache rollback tests remain in place.
This is lifecycle hardening, not additional preference UI or real Compose/native validation.
Remaining feature parity and device/release validation still block cutover; Swing remains shipping.

### Read-only portable storage location

Desktop Tools now includes Portable storage location. The host passes a DesktopStorageLocation
captured from the actual directory supplied to DesktopStorage.open at startup, rather than
re-reading environment/system properties later. The absolute path retains any lexical dot
segments rather than rewriting symlink-dependent paths, and the model neither creates files
nor inspects vault contents. Diagnostics redact the path.

The shared dialog renders the directory and managed vault/backup/settings/hardware-record
filenames as plain text. It makes clear that listed files need not exist and external exports
are not enumerated. Guidance explains closing all instances before relocation, keeping a verified
backup, copying the whole data folder, avoiding concurrent clients, the system-property/environment
program-folder overrides and automatic data subfolder. No links, clipboard writes, shell execution,
file moves or live switching are provided. The action is optional/guarded and lock removes the
dialog; Android does not advertise it.

Tests cover actual startup-path retention, no file creation, fixed filename provenance, redacted
diagnostics and changed overrides. Tools routing/terminal guards remain covered. Real Compose
layout/accessibility/native validation and broader portable migration/recovery workflows remain
pending. Swing remains shipping.

### Manual-order save deadline guard

DesktopEntryMove now checks live authority before resolving its exact-entry/adjacent-entry
frame and after save returns or throws. The Compose callback supplies the current lifetime
and only publishes filter/error updates if that lifetime and vault remain active. Reentrant
pre-save guards cannot bypass the existing identity/order/filter checks. Already-authorized
disk writes are not interrupted or rolled back; this change suppresses stale UI completion.

Tests cover expired/reentrant guards before persistence and successful/failed/throwing saves
that cross a deterministic idle deadline, alongside existing real persisted ordering/reopen
and failed-write order preservation. Persistence remains synchronous. Real Compose/device
validation and remaining hardware/camera/preferences/cutover work are still pending; Swing
remains shipping.
