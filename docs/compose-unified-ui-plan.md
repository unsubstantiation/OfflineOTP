# Unified Compose Multiplatform UI

> **Status:** stage 1 is in the checkout — a shared `:ui` Compose Multiplatform module plus a desktop
> preview host (`:desktop-compose`). The Android app still renders the View/RecyclerView UI and the
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
:core  (Android-free engine: entries, OTP, parsers, vault, backups)
  ↑
:ui    (Compose Multiplatform: theme, screens, projection)          ← this stage
  ↑                              ↑
:app   (Android host, stage 2)   :desktop-compose (desktop host, this stage)
                                  :desktop (Swing client, still shipping until stage 4)
```

`:desktop` stays Compose-free so its Swing-only sandbox gate and its Swing tests keep working while
both desktop clients share one engine; the Compose desktop host lives in its own module instead of
growing a second UI inside `:desktop`.

## What stage 1 contains

| Area | Files | Notes |
| --- | --- | --- |
| Design system | `ui/src/commonMain/.../ui/theme/ForestPalette.kt`, `ForestTheme.kt` | The Dark Forest tokens and a named type scale, replacing the duplicated `colors.xml`/`ForestTheme` values and the Material 3 colour scheme. |
| Frame model | `ui/src/commonMain/.../ui/vault/{TokenDisplay,VaultCardState,VaultUiState,VaultStrings,VaultActions,VaultProjection}.kt` | Compose-free. Everything a row shows is computed here, so it is unit-tested on a host JVM. |
| Screen | `ui/src/commonMain/.../ui/vault/{VaultScreen,VaultComponents,Countdown}.kt` | Stateless: one immutable frame in, callbacks out. |
| Unlock | `ui/src/commonMain/.../ui/unlock/UnlockScreen.kt` | Password field only; the host owns the unlock path. |
| Desktop host | `desktop-compose/src/main/kotlin/.../compose/ComposeMain.kt` | Unlock, list, copy, reveal, HOTP advance, search/tag/sort, lock — over the existing `VaultController`. |

Deliberately absent from stage 1: add/edit/delete dialogs, tags/thumbnail editing, backup and
restore, OpenPGP, hardware keys, preferences, idle auto-lock, issuer artwork rasterization, and the
Android host. The screen hides an action when the host passes no callback, so the preview host never
shows a button that does nothing.

## Decisions and their reasons

1. **The projection is separate from the composables.** `VaultProjection` turns borrowed entries into
   a `VaultUiState`; no file in that layer imports Compose. That is what makes the behaviour
   (grouping, expiry window, empty-state choice, tag counts, masking) testable with the same JUnit
   runner the rest of the repository uses, instead of requiring a device.
2. **Strings are passed in.** Android ships thirty translated `values-*` lists; a Compose resource
   bundle would orphan them. Hosts pass a `VaultStrings` value (Android from its resources, the
   desktop preview from `VaultStrings.English`), so localisation stays a host concern and the module
   keeps one default for previews and tests.
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
bash scripts/kotlin-sandbox/compile-ui.sh          # core, then :ui against Compose stubs, then 31 tests
python3 -m unittest scripts.tests.test_compose_ui_module -v
```

`compile-ui.sh` compiles `:core`, then `ui/src/commonMain` against the hand-written Compose stubs in
[`scripts/kotlin-sandbox/stubs-compose`](../scripts/kotlin-sandbox/stubs-compose/README.md), runs the
projection tests with the JUnit shim, and finally type-checks `desktop-compose` against the compiled
`:desktop` gate output and the Compose Desktop window stubs. A green run means the shared sources type-check against
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
2. Host `VaultScreen` in a `ComposeView` inside the existing Activity, keeping the adapter's
   filtering/session ownership: build `VaultFilter` from the settings store and the adapter's current
   selection, call `VaultProjection.snapshot`, and route `VaultActions` back into the adapter's
   existing entry commands.
3. Delete `EntryViewHolder`, `item_entry_card.xml` and the card-only drawables/dimension resources,
   and drop `me.zhanghai.android.materialprogressbar` once nothing else uses it.
4. Port `TokenCardDeviceTest`/`EntrySearchDeviceTest` to `androidx.compose.ui:ui-test-junit4` (new
   test-only dependency) before deleting the old assertions.

Exit criteria: both flavors build and pass lint, the existing API 28/36 instrumentation suite passes
with the Compose list, the adapter's search/tag/sort parity tests still pass, no View resource is
left unreferenced, and the marked columns/tags still render in every locale.

### Stage 3 — entry actions and dialogs

Card menus, manual entry, tag and thumbnail pickers and confirmation dialogs move into `:ui` behind
`VaultActions.onEntryActions`. Each dialog migrates with its own characterization coverage; the
security rules in [`vault-security-hardening.md`](vault-security-hardening.md) (delayed confirmation,
redaction, screen privacy) apply unchanged.

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
- **Localisation drift.** Every new `VaultStrings` field needs an Android resource mapping; a field
  without one would silently fall back to English on the phone.
