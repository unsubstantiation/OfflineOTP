# UI decomposition plan

> **Status:** the five decomposition slices below are implemented and the large UI classes were
> converted to Kotlin in the twenty-first batch. The extractions listed under "Still to extract"
> remain open; this plan is not complete.

## Decision and current status

The original recommendation was to decompose large activities and adapters before converting them
to Kotlin. Five slices were extracted before the remaining UI was converted; the other roles below
remain inside their existing Kotlin owners. Do not replace those owners with a single catch-all
presenter or helper. See the [conversion record](kotlin-extracted-helpers.md) and
[vault/session/task contracts](kotlin-vault-session-task-contracts.md) for migration history and
verification limits.

Line counts below are a source-review snapshot on 2026-10-14, not conversion thresholds. A smaller file alone is not evidence of a sound boundary.

## Responsibility map and proposed extractions

This table records the original review and target boundaries. Concrete implementations completed so far are listed below; remaining roles are proposals. Reuse existing owners where possible rather than duplicating their policy.

| Owner at the review | Responsibilities observed | Proposed extractions | Keep in the Android boundary |
| --- | --- | --- | --- |
| `EntriesCardAdapter` (~880 lines) | Card binding, entry list ownership, filtering/sorting, mutation/save/automatic backup, token refresh/usage, drag handling, edit/thumbnail/PIN/delete dialogs, QR/token export | Separate display-query state from entry mutations and persistence; separate dialog construction and explicit export actions. Continue using `EntryList` for existing collection/sort logic and typed vault/task contracts for I/O. | RecyclerView binding/notifications, gesture dispatch and view lifecycle; translate actions to stable entry identities rather than carrying adapter positions through dialogs. |
| `MainActivity` (~1,044 lines) | Authentication and startup/unlock, inbound QR/import intents, navigation/search/tag drawer, update/inactivity timers, preferences and activity results | Separate startup/authentication orchestration, external-input routing, entry-list presentation and timer scheduling. Session authority stays in `VaultSession`, not in the screen coordinator. | Android lifecycle, intent/result entry points, system launches, window privacy, view wiring and navigation effects. |
| `BackupActivity` (~620 lines) | Backup/restore controls, file selection, task ownership, restore merge/save, OpenPGP connection/results and export | Extract restore merge/persistence into a vault operation; separate backup orchestration from document access and OpenPGP integration. Retain bounded input/output and final write-permission checks. | SAF launches/grant handling, service connection lifecycle, user confirmation and rendering of progress/outcomes. |
| `SettingsActivity` (~544 lines) | Preference binding/change reactions, authentication results, credential/encryption changes, key reset, backup location grants and nested preference fragment | Separate credential/encryption/reset orchestration from preference presentation and document-grant handling; keep transaction decisions in the existing vault boundary. | Preference widgets/listeners, system prompts, lifecycle and launch/result translation. |
| `IntroScreenActivity` (~694 lines, including nested slides) | Slide navigation, encryption/auth selection, credential entry, setup task, setup-key handoff and view rendering | Separate setup input validation and completion orchestration from slide views. Give task lifetime and one-use key ownership explicit owners before separating the nested slides. | Slide/view lifecycle, credential widgets, navigation and delivery of non-secret completion metadata. |

`AuthenticateActivity` and `BackgroundTaskActivity` are smaller but security-sensitive. Do not combine their conversion with replacing the retained-fragment runner or introducing coroutines. Any replacement needs its own lifecycle/cancellation review.

## Implemented decomposition slices

| Boundary | Current implementation and integration |
| --- | --- |
| Display/query state | `View/EntryListPresentation` owns the borrowed display projection, sort/tag selection, tag reconciliation, drag eligibility and search-result transfer/invalidation. `EntriesCardAdapter` delegates these operations and retains Android Filter/RecyclerView publication. Existing `EntryList` still defines search/sort semantics. |
| Entry I/O and automatic backup | `Utilities/EntryPersistence` holds application context only, checks the live session key under the mutation lock and delegates typed reads/writes to `DatabaseHelper`. Automatic backup is scheduled only after `Saved`, using the existing task-owned snapshot and final permission checks. The adapter handles notifications/toasts and ownership of its entry collection, not database calls or backup creation. |
| Entry-action dialogs | `View/EntryActionDialogs` renders counter/PIN/thumbnail/delete dialogs without entries, keys or persistence. `EntryActionTarget` (now Kotlin) resolves the original object/stable ID and generation, not a saved position; the adapter rechecks the live session under the mutation lock before commands. Lock/reload dismisses tracked dialogs, clears editable text and cancels old reveal callbacks. |
| Main-screen scheduling | `Utilities/ScreenTimerController` (now Kotlin) owns periodic updates and inactivity scheduling; `Utilities/HandlerTimerScheduler` (now Kotlin) is the Android main-loop/elapsed-realtime bridge and holds no policy. `MainActivity` supplies rendering and authentication effects, pauses on lock/pause and closes permanently on destruction. Repeated starts cannot create duplicate loops; obsolete callbacks cannot restart a stopped timer. |
| Restore merge/save | `Utilities/VaultRestoreOperation` owns parsed/current entries and destroys them on exit. It serializes the read/merge/write, checks the originating generation/current key and caller permission, and returns non-secret result enums. `BackupActivity` retains consent, progress/toasts and navigation rather than parsing/merging/saving. |

Current behavior includes enabled search fields and locale-independent case-insensitive matching; separate search versus tag projections (not a new combined-filter feature); AND/OR/tagless semantics, including AND with no selected tags; stable list IDs; sorting only the visible subset after usage updates; import ordering, duplicate handling and existing metadata precedence during merge; and empty-import rejection. Inactivity remains elapsed-realtime based, with one-second checks like the previous CountDownTimer. Delay multiplication uses long arithmetic to avoid integer overflow.

Lifecycle hardening accompanying these boundaries is explicit: a display change invalidates pending queries, rejected query results drop their borrowed references, query publication still requires current session authority, and timer cancellation invalidates already-dequeued callbacks as well as removing queued callbacks. Clearing a display projection does not destroy repository-owned entries; the adapter remains responsible for destruction on lock/reload.

The original decomposition introduced characterization and architecture tests. Some historical
suites, including `Kotlin*InteropTest`, are not in this checkout. See the [README](../README.md)
for current test commands and the [sandbox guide](../scripts/kotlin-sandbox/README.md) for the
limits of host-side checks. Android lifecycle and provider behavior still require device testing.

### Still to extract

Remaining adapter entry commands, manual-entry editing and QR/token-export actions; main startup/authentication/input routing; OpenPGP and document-provider orchestration; settings credential/reset orchestration; and intro setup/task/slide ownership remain in their existing owners — the five implemented slices above (display/query state, entry I/O and automatic backup, entry-action dialogs, main-screen scheduling and restore merge/save) are the parts that were extracted before the twenty-first batch converted the large classes. The conversion preserved those boundaries and did not add or move a responsibility, but it also did not extract the roles listed here: they are now Kotlin code inside the same owners, so the follow-up extractions below still apply to them. Dialog rotation/teardown, keyboard and privacy behavior still need device acceptance. This initial implementation does not mark the rest of the plan complete.

## Extraction order

1. **Verify the foundation.** Run both-flavor build, contract and API 28/36 instrumentation gates;
   add characterization coverage before changing a boundary.
2. **Preserve display-query behavior.** Keep search, sorting, tag filtering, stable IDs and drag
   eligibility covered; changes to search/tag semantics are separate features or fixes.
3. **Separate entry commands from rendering.** Test mutations, failed saves and automatic backup;
   make owned entries and borrowed display references explicit.
4. **Separate remaining dialogs and export actions.** Revalidate entry identity and session on
   delayed confirmation, and preserve screen privacy and recipient permissions.
5. **Extract one activity use case at a time.** Address input routing and startup/authentication,
   provider handling, settings and setup orchestration in separate reviewable changes.

Do not combine these steps with Compose, coroutine/task-runner replacement, new storage formats, algorithm/provider changes, or Gradle DSL conversion.

## Ownership and security requirements for each boundary

- **One authority:** session snapshots are diagnostics, not authorization leases. Delayed actions must recheck the captured generation/current key at the mutation or disclosure boundary. No extracted presenter keeps an independent unlocked flag capable of granting access.
- **One secret owner:** document who destroys loaded entries, task results and temporary key bytes. Query/display lists borrow entries; clearing a display projection must not destroy the repository's live data. Lock cleanup must clear every borrowed reference and invalidate in-flight queries.
- **No persistence of secrets:** seeds, credentials, keys, live tokens and secret-bearing screen state must not enter Bundles, saved state, logs, generated data-class diagnostics or serialized navigation arguments. Process death requires reauthentication.
- **Stable targets:** an adapter position is not an entry identity. A dialog/filter result arriving after reorder, deletion, replacement or lock must not act on whichever entry now occupies that position.
- **Explicit lifecycle:** give callbacks, timers, handlers, dialogs, service bindings and task cancellation a named owner and teardown path. Define rotation and permanent destruction separately. A helper must not retain a dead Activity through process/session listeners.
- **No hidden trust expansion:** intent routing, SAF providers, OpenPGP and token sharing remain external trust boundaries. Keep signature-protected IPC, the offline manifest policy, input limits and final authorization checks. Extraction does not make provider output trustworthy or cancellation transactional.
- **No ambiguous success:** unavailable vault data is not an empty list, task Value is not business success, and uncertain/committed metadata outcomes must not be reported as rollback or used to install a key.

## Completion gate for an individual component

Before accepting an individual extraction for release:

- Its responsibility, inputs/outputs, secret ownership and lifecycle teardown are documented and reflected in the caller; moved code is not duplicated or replaced by a catch-all coordinator.
- Characterization tests cover preserved behavior; domain logic can be tested without constructing an Activity/RecyclerView where practical.
- Integration tests cover lock during work, stale/replaced results, rotation/pause/resume, permanent destruction, process recreation and cancellation around metadata commitment as applicable.
- Adapter tests include filtering after lock, reorder/delete while a dialog is open, invalidation of pending searches, failed persistence and secret cleanup. Backup tests cover corrupt input, merge versus replace, provider failures and recovery without implicit unlock.
- Both flavors compile and pass host tests, focused lint and packaged-manifest policy; API 28/36 tests pass. Existing independent release/dependency comparisons remain required for release acceptance.
- Boundary changes are reviewable separately from unrelated behavior changes. No unexecuted test or source-only check is described as runtime acceptance.

**Current readiness:** the large UI classes are Kotlin, but the remaining extractions are not complete. Both-flavor builds and relevant device/provider checks remain release gates for changes to these boundaries.
