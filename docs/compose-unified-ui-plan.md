# Unified Compose Multiplatform UI

> **Status:** stage 1 is in the checkout — a shared `:ui-model` frame layer (toolkit-free), a `:ui`
> Compose Multiplatform widget module plus a desktop preview host (`:desktop-compose`); the Swing
> desktop client already takes its tab and sort wording from the shared table. The Android app still renders the View/RecyclerView UI and the
> shipping desktop client is still Swing, so **no client has been deleted yet**: this stage adds the
> shared screen and the gates that keep it shareable. Stage 2 (Android cutover) and stage 4 (Swing
> deletion) are the changes that actually shrink the codebase; their exit criteria are below.
> Nothing in this document is a claim that the Compose screen has been run on a device: no Compose
> artifact could be resolved in the environment that produced it, so Gradle and device checks are
> still outstanding.

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
3. **The host supplies time.** There is no `expect fun currentTimeMillis()`: the host anchors the
   countdown with its own wall clock and `rememberNowMillis` counts elapsed time from
   `TimeSource.Monotonic`. Fewer platform actuals, and a clock change cannot make the ring run
   backwards. The host rebuilds the frame once per second; the screen recomposes, the vault does not
   re-filter.
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

The Compose desktop host gains the remaining features (add/edit/delete, backup/restore, OpenPGP,
hardware keys, preferences, auto-lock, portable-location handling), then `desktop/src/main/.../ui`
and the Swing entry point are deleted and `:desktop-compose` takes over the `:desktop` name. The
engine (`control/`, `storage/`, `openpgp/`, `hardware/`) is untouched throughout.

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
