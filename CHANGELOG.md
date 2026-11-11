# Changelog

#### Unreleased

 * Compose desktop: Export allowlisted portable preferences on a worker without vault credentials or authentication material. Refuse existing destinations and reuse the restricted exclusive-file writer.

 * Compose desktop: Add explicit replace-all confirmation for OpenPGP restore while retaining merge as the default. Save before replacing live entries and preserve the original vault on failed writes.

 * Compose desktop: Export full OpenPGP backups to a selected recipient public-key file with explicit scope confirmation, worker encryption and exclusive destination creation. Wipe provider plaintext inputs after encryption. Native crypto/UI validation remains pending.

 * Compose desktop: Merge OpenPGP backups using a selected secret-key file, masked optional passphrase, bounded worker decoding and explicit confirmation. Bound and wipe decoded provider byte buffers in the shared codec. Native crypto/UI validation remains pending.

 * Compose desktop: Export SSH keys and password credentials to an encrypted LinSCP vault using a detached snapshot, worker encryption and exclusive file creation. Confirm scope and password; existing files are never overwritten.

 * Compose vault: Group import, encrypted-backup and settings actions in a scrollable Tools dialog instead of stacking them above the entry list. Keep Add entry directly accessible and omit unsupported tools.

 * Compose desktop: Import LinSCP encrypted vaults, backup bundles and session files with optional sessions.key selection, bounded worker decoding and explicit merge confirmation. Source/key files remain unchanged.

 * Compose desktop: Persist Default, Compact or Full card layout, apply only after successful save without resetting filters/reveal state, and include the setting in desktop portable preferences.

 * Compose desktop: View email connection and SMTP identity details with passwords masked by default and guarded explicit reveal. Also fix the missing Edit button for supported imported password layouts.

 * Compose desktop: Import FairEmail JSON, gzip and encrypted backups with masked password input, bounded worker decoding and explicit merge confirmation. Share cancellable file-import ownership with browser-password import.

 * Compose desktop: Import browser password files as individual credentials or preserved archives using bounded worker parsing, explicit confirmation and session-owned cleanup. The source plaintext file remains unchanged; final persistence is synchronous.

 * Compose desktop: Edit imported password URL, username and password with blank-preserves semantics, retained extra columns and live-session/reveal checks. Unsupported or conflicting column layouts remain excluded.

 * Compose desktop: Read QR image files through a native picker with bounded worker decoding and explicit import confirmation. Discard canceled/locked results and keep decoded payloads hidden; camera capture remains pending.

 * Compose desktop: Import pasted OTP URIs and multi-part Google Authenticator migration payloads through the shared coordinator, with masked drafts, explicit duplicate-issuer policy and session-bound batch cleanup. Image/camera QR import remains pending.

 * Compose entry editing: Explicit, undoable removal choices for stored mOTP PINs, SSH connection passwords and protected labels. Blank fields still preserve values; removal only applies on Save and resets after a failed submission.

 * Compose desktop: Restore password-encrypted backups with worker decoding and a separate merge/replace confirmation. Default to merge, discard canceled results, and save before changing live entries. Final persistence remains synchronous; native UI validation is pending.

 * Compose desktop: Export full password-encrypted backups through a native save picker and shared confirmation form. Encrypt a detached snapshot on a worker, refuse existing destinations, and cancel pending work on lock without adopting stale results. Restore and runtime validation remain pending.

 * Compose desktop: Add session-bound auto-lock settings with immediate live updates after successful persistence. Reject stale/expired requests and roll back the desktop settings cache after failed writes.

 * Compose desktop: Add persisted up/down ordering for unsorted, unfiltered rows. Bind moves to the original entry and neighbor so stale controls cannot reorder replacements; reject failures without changing live order.

 * Compose desktop: Run password unlock/create on a worker, disable duplicate attempts, clear the password field before handoff, and discard unadopted keys/entries after screen disposal. Other persistence workflows still need asynchronous integration.

 * Compose desktop: Copy live, revealed entries only after persisting usage, with explicit SSH-field selection and 30-second clipboard expiry. Reject stale targets, roll back usage after failed writes, and best-effort clear matching clipboard content on lock.

 * Compose desktop: Add searchable bundled-artwork selection to the shared add/edit form. Preview choices lazily and persist the draft selection only on successful Save; failed saves retain the original artwork.

 * Compose desktop: Edit revealed OTP, note and SSH entries with fixed types and identity-bound save requests. Blank secret fields retain existing values; edits preserve artwork, privacy, usage metadata and row identity. Real Compose validation and remaining desktop workflows are still pending.

 * Compose desktop: Add manual OTP, note and SSH-key creation through the shared form and EntryDraft validation. Persist before publication, reject stale/locked dialog submissions, and discard unadopted drafts. Existing-entry editing and shipping launcher cutover remain pending.

 * Compose desktop: Honor the saved inactivity timeout, reject activity after expiry, and clear the vault on close/disposal. Add a Swing-free event-thread scheduler with headless lifecycle/timer tests. Shipping desktop launcher remains Swing pending parity and runtime validation.

 * Android CI: Add manual-only Compose/artwork emulator validation to the existing workflow, covering F-Droid/API 28 and Play/API 36. Require passing results for all selected tests and retain diagnostic logs without publishing debug APKs.

 * Android tests: Add real Compose-host device tests for frame/action replacement, terminal clearing, concealed text, localized period labels and artwork loading. These are type-checked in the sandbox; device execution remains required.

 * Compose: Restore non-default OTP period labels, including mOTP’s actual ten-second step. Android uses its existing localized period string and strips the label from concealed frames.

 * Android: Remove the unused RecyclerView card renderer and drag helpers from production sources; retain them only as androidTest regression fixtures. Scope the explicit RecyclerView dependency to tests and compile sandbox production independently of those fixtures.

 * Compose: Render bundled issuer artwork on Android and desktop, reusing their existing loaders. Keep letter-tile fallback and privacy gating, fit images without cropping, and cache Android assets with a bounded LRU rather than reloading every tick.

 * Android Compose: Apply label/thumbnail sizes, ten-line multiline labels and marquee scrolling. Full stacks the code below identity; Compact reduces padding. Use one appearance mapping for frames and views, and show complete secure notes. Real layout/device validation remains pending.

 * Android Compose: Honor token group size, Hide issuer, previous-code and individual-countdown preferences. Reuse the existing right-aligned grouping rule from shared core; keep custom-step countdowns and masked-token behavior.

 * Android: Switch MainActivity’s list display to the shared Compose cards over AndroidVaultController. Retain the existing toolbar, tabs, tag drawer and action dialogs; manual ordering uses up/down controls rather than dragging. Legacy renderer retirement, display-preference parity and real Android build/device validation remain pending.

 * Android: Bridge the extracted controller to shared vault frames with generation- and identity-checked card commands. Preserve asynchronous search publication and existing Android action handlers; Activity display cutover remains pending.

 * Android: Extract vault/session/search/action ownership into AndroidVaultController. The Activity uses the controller for vault operations; RecyclerView remains a temporary renderer until the Compose cutover.

 * Compose desktop: Resample wall time on every token tick rather than extrapolating from unlock time, so clock corrections reach OTPs and countdowns on the next refresh.

 * Desktop: Share Swing and Compose HOTP/deletion persistence handlers. Roll back Swing counters on failed saves, and bind deletion confirmation to the selected entry rather than a row that may change while the dialog is open.

 * Compose Android seam: Make clearing the view terminal for its session. Reject late frames and retained actions after clear, and share callback liveness guarding with the desktop presenter. A new unlock requires a new view.

 * Compose Android seam: Extend AbstractComposeView rather than the final ComposeView class. Correct the sandbox stub so it rejects the unsupported inheritance; Android cutover remains pending.

 * Compose: Make shared presenter locking terminal. Retained callbacks cannot copy tokens, change selections or request dialogs after lock; closed dialog sessions reject late requests.

 * Compose: Consolidate PIN, counter and deletion dialogs into a shared lifecycle model and renderer. Ignore callbacks from dismissed or replaced requests, and keep dialog wording localizable outside the desktop host.

 * Compose desktop: Add confirmed deletion using the shared entry dialog. Save the remaining vault before discarding an entry, retain it on write failure, and preserve the current filters after deletion.

 * Compose desktop: Add masked mOTP PIN entry through a shared Compose dialog. Save by stable entry id, restore the previous PIN on write failure, and discard pending dialog state on lock. Android dialog migration remains pending.

 * Desktop: Give the vault one selection value instead of four fields and a second copy of the filter rules. `DesktopVaultState` kept `query`, `selectedTags`, `includeUntagged` and `tagMode` separately and re-ran the search-then-tag composition itself — a duplicate of what `:ui-model`'s `VaultProjection` now owns, and the copy whose semantics differ from Android's (here a search and a tag choice narrow together). It now holds a single `VaultFilter(combination = SEARCH_AND_TAGS)` and applies it through the projection, so the `apply*` methods only replace a field and every refresh runs the one shared implementation; `lock()` clears the selection by replacing it with a fresh filter. The public API is unchanged, but the presentation is no longer a place to mutate behind the state's back: the main window's restore path used to call `presentation.setCategory`/`setSortMode` directly (the next refresh would have reset the user's tab), and the three tests that did the same now call `applyCategory`/`applySortMode`. A new test pins the combination, that a refresh keeps the selection, and that locking forgets tab and filters; `scripts/tests/test_shared_client_logic.py` now asserts the tag rule lives in the projection and that the state holds no `EntryTagFilter` of its own. Gates: `compile-desktop.sh` 192 tests (180 passed, 12 skipped), python guards 496 OK.

 * Desktop: Make the Swing cards paint the shared row model instead of re-deriving it. What a vault row shows — the code, its grouping, whether the step is running out, the tags, the thumbnail, the accessibility line — is now built by `VaultCards.of(entry, nowMillis, revealed)` in `:ui-model` (`VaultProjection` delegates to it), and `EntryCards` only draws the result, so the Compose screen and the Swing window cannot answer the same question differently. The extraction removed the desktop's private `EXPIRING_SECONDS` and its own `step - now % step` arithmetic (the clock is now passed in, `CardClock.nowMillis`, so a test pins the second), and it surfaced two real drifts: the desktop printed `123456` where the phone prints `123 456`, and it printed the mOTP placeholder constant `PINREQ` as if it were a code (now `PIN required`, the shared wording). `EntryTableModel` still owns row selection, sorting and the redacted placeholder text for stored passwords, keys and mail accounts, and `EntryCards` still owns the drawing, the kind label, the hidden label and the wrapped note geometry — those are host chrome, not derivation, and the plan records them as stage-3b residue rather than stage-4 blockers. Behaviour tests changed with it: two assertions that compared a rendered code against `getCurrentOTP()` now compare against `TokenDisplay.group(...)`, so grouping is asserted rather than incidental, and a new `VaultCardsTest` (9 tests, 49 in the frame layer) covers the one-row path the table takes, which no frame-level test did before.

 * UI: Make the shared frame layer a module of its own, and stop the desktop client keeping a second copy of its labels. The pure part of the Compose UI — card/state model, token formatting, filter transitions, strings — moved from `:ui` into `:ui-model`, a Kotlin Multiplatform module with **no Compose dependency**: `scripts/kotlin-sandbox/compile-ui.sh` compiles it with no Compose artifact on the classpath at all, so the boundary that used to be a Python source check is now enforced by the compiler, and a non-Compose host can share the model. The Swing client takes its tab and sort wording from that table (`VaultStrings.English.categoryTitle`/`sortTitle`) instead of `MainScreen`'s own `categoryTitle()`/`SortChoice.toString()`, which are gone; `desktop/build.gradle` depends on `:ui-model` and still on nothing that reaches Compose, and `compile-desktop.sh` compiles the module in its phase 1 to keep proving it. The shared English defaults are now the Android resources' own words — `OTP`, `SSH Keys` (`tab_*`) and `Unsorted`/`Issuer`/`Label`/`Last used`/`Most used` (`menu_sort_*`) — with `scripts/tests/test_compose_ui_module.py` parsing `strings_main.xml` and failing on any drift, so the Compose screen, the Swing window and the phone cannot spell one tab three ways; `test_desktop_email_support.py` now follows the label mapping to its new home while still requiring all five categories. The split also surfaced a real cross-module compile error (Compose could not smart-cast `VaultCardState.token` from another module) that a single-module build would have hidden until Gradle. Still not device-verified and still not deleted: the RecyclerView list and the Swing screen remain the shipping UIs, and the remaining Swing adoption (rows rendering from `VaultCardState`) is stage 3b in `docs/compose-unified-ui-plan.md`.

 * UI: Give the shared Compose UI its Android seam and one implementation of the filter rules. The new `ui/src/androidMain` `VaultComposeView` is the `ComposeView` the Android Activity will render through: composition is created once, `render(frame)` publishes each `VaultUiState` from the session the host already owns, `clear()` empties it on lock, and the view holds no session, key, storage or settings reference of its own. The tag-tap rules moved out of both hosts into pure `VaultFilter` transitions — `withQuery`, `withCategory`, `withSortMode`, `withTagToggled`, `withUntaggedToggled`, `clearedFilters` — so `OR`/`AND` accumulate, `SINGLE` replaces the selection, a second tap on the only `SINGLE` chip clears it, the untagged pseudo chip stays a separate switch, and *Clear* drops the narrowing without resetting the user's tab or order; the desktop preview host now calls those helpers instead of its own copy. `scripts/kotlin-sandbox/compile-ui.sh` gained a fifth phase that type-checks `androidMain` against the app's human-written Android stubs and a Compose platform stub, and `scripts/tests/test_compose_ui_module.py` pins the seam's shape (render/clear, composition strategy, no session types, no widget of its own) and the filter tests. Still not deleted and still not device-verified: the RecyclerView list, `EntryViewHolder` and the Swing window remain the shipping UIs, and the `AndroidVaultStrings` resource mapping is stage 2 work — see `docs/compose-unified-ui-plan.md`.

 * UI: Start the unified Compose Multiplatform UI, so the vault screen stops existing twice. The new `:ui` module holds one implementation of the Dark Forest design tokens, the vault list (kind tabs, search, tag chips, issuer tiles, codes with grouping and a countdown ring, per-row reveal/copy/counter actions, empty and filtered-empty states), the password unlock screen and the frame projection that feed them; `:desktop-compose` runs it over the existing headless desktop engine as a preview client (`./gradlew :desktop-compose:run`) with unlock, copy, reveal, HOTP advance, search/tag/sort and lock. Nothing is deleted yet and nothing is claimed as device-verified: the Android View list and the Swing window remain the shipping UIs, and no Compose artifact could be resolved in the environment that produced this change, so the Gradle build and every on-device layout/accessibility check are still outstanding — the staged cutover, its deletion targets and its exit criteria are in `docs/compose-unified-ui-plan.md`. The behaviour that used to be re-derived per client now lives in a Compose-free projection (`TokenDisplay`, `VaultCardState`, `VaultProjection`) and is covered by 31 host-JVM tests: code grouping for 6/7/8-digit and Steam/mOTP alphabets, second-boundary countdown arithmetic including a backwards clock, the 8-second expiry window, HOTP counters without a countdown, mOTP's PIN prompt, codes masked until revealed, revealed content gated per stable list id, tab/tag counts, the three different empty messages, and the deliberate filter difference — Android's search overriding the tag filter versus the desktop's search-and-tags — carried as `VaultFilterCombination`. Secrets stay out of diagnostics (`VaultCardState`/`VaultUiState` redact `toString`), and the unlock field deliberately avoids `rememberSaveable`. Boundaries are enforced rather than documented: `scripts/tests/test_compose_ui_module.py` rejects any android/androidx/Swing/storage/crypto import in `commonMain`, any host-package import, any Compose import in the projection layer, any wall-clock read or `expect` declaration, any Compose import in the Swing `:desktop` module, any dynamic Compose version, and a Compose compiler that does not match Kotlin 2.4.21; `scripts/kotlin-sandbox/compile-ui.sh` compiles `:core`, then `commonMain` against hand-written Compose stubs (which the module's README describes as compile-only, not a runtime), then runs the projection tests.

 * iOS: Ship an app icon, and prove what is inside the `.ipa` instead of asking anyone to trust its size. The 2.5 MB artifact read as an incomplete build; it was the right size for one arm64 Release slice with no embedded frameworks (iOS carries the Swift runtime, where the desktop zip embeds a JVM) and no bundled images or fonts — but it genuinely **had no app icon**: the target compiled no asset catalog at all, so iOS drew the blank placeholder while Android and the desktop shipped the shared mark. `App/Resources/Assets.xcassets` now declares a single 1024×1024 icon that `scripts/ios/generate-app-icon.py` resamples from the 1254×1254 PNG embedded in `assets/logo_legacy.svg` — generated at build time like the macOS `.icns`, never committed — and the target sets `ASSETCATALOG_COMPILER_APPICON_NAME`. The packaging step also runs `scripts/ios/report-ipa-contents.py` on the archive it is about to upload and publishes one **iOS bundle** annotation (compressed/installed size, file count, executable architecture and size, the Rust OpenPGP share of the linked image read from the new `LD_GENERATE_MAP_FILE=YES` link map, the compiled icon, bundle id and minimum OS), so the contents are answerable from the run page without a Mac or a download. It **fails the build** on the two ways this app can be silently incomplete: no compiled icon, and an OpenPGP port compiled out — `ios/Package.swift` drops `OFFLINEOTP_OPENPGP_NATIVE` when the XCFramework is missing, and that app still builds green with the cryptography disabled. `scripts/tests/test_ios_bundle_report.py` drives the checker against synthetic bundles (complete, icon-less, Rust-less, Intel/fat, simulator, stray files) so the gate is known to catch what it claims to.

 * CI: Publish only the iOS `.ipa`, and stop reporting Rust errors that were not errors. The iOS job uploaded four artifacts — the unsigned device `.ipa`, a simulator `.app` bundle, a source archive of the kit, and a build log — beside a `SHA256SUMS.txt`, none of which anyone needed: the `.ipa` is the deliverable. `ios.yml` now uploads exactly one artifact containing only `OfflineOTP-ios-unsigned.ipa`; the simulator build step, the kit-source packaging and the checksum manifest are gone, and the `.ipa` is unpacked and checked in the job (arm64 `IOS` Mach-O, `DTPlatformName = iphoneos`) instead of shipping a sidecar file. The green run also carried **ten `error` annotations on the Rust job that had passed**: `cargo --verbose` echoes `Running ... rustc ... --error-format=json ...` for every crate, and the diagnostics step grepped for the bare word `error`, so command lines were reported as failures (truncated at 221 characters, which hid the very flag that caused the match), while `grep ... | head -20` made grep itself die with *write error: Broken pipe*. Cargo now runs without `--verbose` and with `CARGO_TERM_COLOR=never`; both diagnostics steps run only `if: ${{ !success() }}`, match real diagnostics (`^error:`, `^error[E0308]:`, `^test result: FAILED`), and collect matches before limiting them rather than piping into `head`. A behavioural guard in `scripts/tests/test_openpgp_rust_crate.py` extracts the workflow's own pattern and fails if it matches a verbose `rustc` line or misses a real one.

 * CI: Un-break the iOS app target's SwiftUI type-check timeout. `EntryListView.body` was one expression carrying the tab/search/tag column, the toolbar, nine sheets, the archive exporter and the delete alert, and Xcode 16.4 stopped with *the compiler is unable to type-check this expression in reasonable time* at `var body: some View` (simulator build, exit 65). The screen is now composed from separately type-checked members — `baseScreen`, `tooledScreen`, `editorSheets`, `credentialSheets`, `presentedSheets`, `vaultScreen`, a `@ToolbarContentBuilder` toolbar, and small sheet/export/alert helpers — with the same modifier order and behaviour. `scripts/tests/test_ios_app.py` pins the thin `body` and the split so the monolithic expression cannot return.

 * Security: Triage the FairEmail backup `java/weak-cryptographic-algorithm` finding without breaking upstream compatibility. FairEmail's current password-protected writer still emits headerless v0 AES-CBC/PKCS5Padding, so the legacy reader is kept read-only and isolated behind a local CodeQL compatibility exception; v1 imports remain AES-GCM. Added an independent v0 known-answer fixture, v1 wrong-password/tamper/framing rejection tests, and documented the residual unauthenticated-v0 risk and retirement condition.

 * CI: Un-break the Android OpenPGP jniLibs build. `build-android-jnilibs.sh` invoked `cargo ndk … build --manifest-path rust/Cargo.toml` from the repository root, but cargo-ndk (4.1.2) loads the manifest with `cargo metadata` **in the current directory** before it parses anything else: it only honours its own `--manifest-path`, which has to precede the cargo subcommand, so the trailing one was passed straight through to `cargo build` after metadata had already failed. The step died with *Failed to load Cargo.toml in current directory* / *could not find `Cargo.toml` in … or any parent directory* before a single line of Rust was compiled. The script now runs `cargo ndk` inside `rust/`, where the workspace manifest is the default and the target directory is `rust/target` — the same paths the other workflows build — and writes through an absolute `-o "$ROOT/app/src/main/jniLibs"`, with the staged-cdylib check pointed at the same variable. `scripts/tests/test_openpgp_rust_crate.py` fails if a `--manifest-path` reappears in the script's executable lines or the `cd "$ROOT/rust"` and absolute output path go away.

 * CI: Un-break the iOS build's overlapping access. `EmailCredentials.aesCbcDecrypt` passed `out.count` as `CCCrypt`'s output capacity from *inside* `out.withUnsafeMutableBytes { outBuf in … }`, so the closure read `out` while `out` was mutably borrowed: Swift refuses that with *overlapping accesses to 'out', but modification requires exclusive access* and the whole `OfflineOTPKit` module stops compiling — the one error repeated for every file still in the batch, which is why the log looked like eleven failures. The capacity now comes from `outBuf.count`, the same number, and `scripts/tests/test_ios_package.py` walks every `withUnsafeMutableBytes`/`withUnsafeMutableBufferPointer` closure in the kit and the app sources and fails if it reaches back for the borrowed array's own `count`, `capacity`, `indices`, `startIndex`, `endIndex`, `isEmpty` or `underestimatedCount`; restoring the old line reproduces the compiler's message.

 * Settings: Export and import your preferences as a portable JSON file, on all three clients. A new device meant re-ticking twenty settings by hand (#648), and the gap was a format decision rather than missing plumbing. **Export preferences…** writes `offlineotp.preferences` version 1 — `{"format","version","platform","includesSecrets","settings","skipped"}` — bounded at 512 KiB, 512 keys, 64 KiB per string and 256 values per set, typed as boolean / integer / string / string set / secret / device-bound. It is re-importable, not inspection-only, and it may carry the **backup password** — the only portable secret — but only when the user asks for it: the Android export asks *with / without* and the file records `includesSecrets`. Everything device-bound stays on the device and is **not even named** in the file (the wrapped backup password, the vault salt and KDF parameters, OpenPGP key-file grants, the hardware-unlock envelope), because a salt in a shared file is an offline guessing target. A document belongs to one client: the three apps' key sets do not correspond (Android `pref_*`, desktop `ui_sort_mode`/`ui_inactivity_lock_seconds`), so a mismatch throws `WrongPlatformException` **before any write** and Android and desktop say which client the file came from — silently applying the one or two colliding names would be worse than refusing. Android imports through a no-write probe port first, so a document that would fail halfway is rejected with the vault untouched, and re-wraps a restored backup password through the Keystore (`Settings.setBackupPassword`) instead of ever storing it as plaintext; the serialised bytes are zeroed on cancel, after the write, and on lock. Desktop puts both behind **Tools**; iOS gains a **Preferences** section and re-reads the auto-lock delay after an import. `:core` `PreferencesExport` (13 tests) is the single format; `AndroidPreferencesPort`, `DesktopPreferencesPort` and OfflineOTPKit's `PreferencesExport.swift` are the three adapters.

 * iOS: Move the CSV table writer to the type its callers name. `PasswordCsvExport.exportCsv` called `EmailMaterial.exportCsv(columns:rows:)`, but the RFC 4180 writer is referenced as `ThunderbirdCsv.exportCsv(columns:rows:)` from the Thunderbird export and **no such member existed on either type** — the iOS build failed with *type 'EmailMaterial' has no member 'exportCsv'*, which is the error that surfaces first while the rest of the module is unresolved. The writer now lives on `ThunderbirdCsv`, next to the parser it shares escape rules with, and both the Thunderbird export and the browser-CSV exporter call it. A new guard in `scripts/tests/test_ios_package.py` parses the Swift and asserts that every `Type.member(...)` call resolves to a member (or nested type, or enum case) that type declares — string and comment literals are blanked first, `extension` bodies count, and reintroducing the bug reproduces the compiler's message.

 * CI: Un-break both red platform builds. **iOS** failed in `Argon2id.loadLe64`: an eight-term `|` chain of `UInt64(x) << literal` makes the type checker enumerate a literal type for every operand, and the compiler gives up with *unable to type-check this expression in reasonable time* rather than building the module. The reads are now a loop over the eight bytes — bit-identical, verified against the old expression — and the deprecated `assign(repeating:count:)` calls are `update(repeating:count:)`. **Android** failed in "Build Android OpenPGP jniLibs": `cargo ndk` cross-compiles with the toolchain rustup already has and does **not** install `std` for the Android ABIs, and unlike the iOS script the Android script never ran `rustup target add` — so a clean runner fails with `can't find crate for \`std\``. The script now installs its three targets, resolves the NDK from `ANDROID_NDK_HOME`, `ANDROID_NDK_ROOT` or the newest `$ANDROID_HOME/ndk/*` instead of trusting one hard-coded path, prints the paths it tried, and checks each staged cdylib. The workflow no longer relies on a bare `test -d` as its only diagnostic, and resolves the NDK from the runtime environment — a step-level `env:` cannot read `env.ANDROID_HOME` set by the setup step, so a pinned `${{ env.ANDROID_HOME }}/ndk/...` silently became `/ndk/...`. The script reports a missing NDK and a missing cdylib as `::error` annotations, so a failure is readable without downloading the log. Both regressions are pinned by `scripts/tests` (`test_ios_package`, `test_openpgp_rust_crate`).

 * iOS: Fix the settings store so the kit compiles. `KeyValueSettings` declared `setString`/`setInteger` but both `PasswordVault.changePassword`'s rollback and `VaultStore.setInactivityLockSeconds(0)` call `remove(_:)`, which existed nowhere — a missing protocol requirement that fails the whole module. `remove` is now declared (defaulted to a no-op so an adapter that cannot delete keeps compiling) and `PropertyListSettings` implements it, deleting the key and persisting. `VaultStore` also gains `reloadInactivityLock()`, so a preferences import that replaces the stored delay is reflected by the picker above it instead of leaving the old value on screen.

 * Docs: Record the remaining decisions instead of leaving them as silent gaps. Paper backups (#178) are **declined** — the encrypted vault file is already the offline copy, per-entry QR sheets are a factor anyone who finds them can use, and a printable recovery format needs print/scan validation that cannot happen here — and automatic OpenPGP backups (#531) have their prompt question **answered**: when built they run silently from the saved recipients and key file, mirroring password auto-backups, with the existing retention rule pruning `.json.gpg` snapshots. `docs/andotp-open-issues-triage.md`, `docs/incomplete-feature-audit-2026-11-06-fourth-pass.md` (new preferences-export section) and `README.md` (a *Moving your preferences* section) say so.

 * Backups: Prune old automatic backups — **Keep automatic backups** (Settings, default *Keep all*). Every automatic export wrote a new `otp_accounts_<uuid>.json.aes` and left the previous ones behind, so the folder grew by one file per password save forever (#925/#531). The choice keeps the newest 5, 10, 20 or 50 snapshots and deletes older ones **after** a successful automatic export, so the fresh document is always part of the kept set and a failed export can never be the reason an older copy disappears. The policy is Android-free and unit-tested (`:core` `BackupRetention`: only names this app generates for an automatic export are candidates, a dated manual export and `otp_accounts.json.aes` never are, an unknown `lastModified` counts as newest, ties order by name); the app applies it through `AutomaticBackupPruner`, which deletes nothing when the tree or its listing cannot be read, counts each provider refusal instead of retrying or reporting success, and is best-effort throughout so housekeeping cannot fail a backup. Only `Mode.USER` exports are exempt from pruning on Android: `EncryptedBackupTask.isAutomatic()` covers the settings-driven and broadcast retries that created the files. Default `0` keeps every existing vault's current behaviour.

 * Passwords: Edit and re-export browser-imported passwords. Individual imports kept every source column but nothing could write them back out, so changing a password meant deleting the entry and re-importing the whole file. **Edit password…** changes the URL, username and password — the three columns every browser shares — and leaves notes, GUIDs, timestamps and Safari OTPAuth untouched; **Export as `<browser>` CSV…** writes an entry (or, on Android, all of them through **+ → Export passwords…**, on desktop **Tools → Export passwords as browser CSV…**) in the dialect it came from, keeping its own header and column order. `:core` `PasswordCsvExport` decides this from the stored columns alone: a Chrome import stays a Chrome CSV after an edit, mixed selections fall back to the dialect's canonical header and write absent columns empty, and a column set no browser writes — a LinSCP session, a legacy Thunderbird row — is **refused** rather than exported with its columns dropped. Blank passwords are refused, and the edit forms start with the password box empty so opening one never reveals a stored secret. Mirrored on all three clients: `OfflineOTPKit/PasswordCsvExport.swift` with the same rules, an Android `PasswordEntryEditDialog` plus menu items, desktop `PasswordImportDialogs.edit`/`exportCsv` behind the row menu, and an iOS `PasswordEditSheet` plus a per-entry export action that writes in place through `VaultStore.update(at:with:)`.

 * Android: The removal prompt no longer erases the entry's name when its label is blank. `EntriesCardAdapter.removeItem` passed `entry.getLabel()` to `dialog_msg_confirm_delete`, so a scanned account — whose identity is its issuer — asked *Are you sure you want to remove the account ""?*. The dialog now uses the shared `Entry.privacyTitle()` rule (the label, else the issuer) that the desktop's `removalConfirmationMessage` and iOS's `VaultStore.displayName` already applied, so all three clients name the same entry the same way.

 * iOS: Show the per-tag entry counts the store already computed. `VaultStore.tagCounts(for:)` existed but only fed the ordering of `tags(for:)`, so the tag chips read `work` where Android's drawer rows and the desktop filter panel read `work (3)`. The chip title is now built from those counts through one helper, which keeps a tag with no count yet at its bare name — exactly `TagsAdapter`'s rule on Android. `scripts/tests/test_incomplete_feature_completion.py` pins the chip text and the count-once-per-entry rule.

 * Android: Make the biometric wording translatable, and remove the dead resources. Every sentence the biometric screens show was a hard-coded `BiometricWording` constant, while `biometric_prompt_title`, `biometric_prompt_subtitle` and `settings_toast_biometric_stale` sat unused in `res/values`. The wording now resolves through `BiometricWording.localized(context, text)` with the constants as the no-`Context` fallback, the unlock prompt uses its own title again (it had been showing the settings switch's title), and the sentences that had no resource at all — wrong password, cancelled, locked out, and the *Enter the vault password to turn this on* prompt — are added to `strings_settings.xml`. `AuthenticateActivity` also lost a `when` whose `else` branch reported a stale shortcut for every unnamed failure: a failed biometric error now reads as the stale sentence once and the button stays for a retry on cancel or lockout. The settings description keeps the second-account sentence the code claimed, and the `settings_key_cat_security`/`settings_toast_storage_not_accessible` leftovers are removed.

 * Android: Complete the in-app OpenPGP engine — the rpgp adapter, JNA bindings and CI-built per-ABI `jniLibs` shipped with **no production caller**, so the backup screen could only ever reach an external OpenKeychain provider. **Settings → OpenPGP engine** now chooses `Separate OpenPGP app` (still the default, so existing installs keep their provider and their old files) or `In this app (key files)`, with **Recipient key files** and **Secret key file** picked through the Storage Access Framework (read-only persisted grants, bounded `content:` URIs only — a raw path is never read). Export seals the vault document through the shared `OpenPgpBackupCodec` in `OpenPgpKeyFileBackupTask`, which owns and destroys a validated entry snapshot like `EncryptedBackupTask`; restore asks whether the secret key is passphrase-protected (an unprotected key is an explicit answer, not a forced empty password) and `OpenPgpKeyFileRestoreTask` clears the passphrase `CharArray` and zeroes the key bytes on every exit path, storing neither. Both buttons are gated by `OpenPgpKeyFilePolicy.availability` with a specific reason — engine missing, no recipients, no secret key — matching the provider mode's disabled-buttons-plus-warning behaviour, and the native library is probed once rather than per redraw. Both modes emit standard OpenPGP messages, so a backup written in one restores in the other.

 * Desktop: Make the vault's at-rest storage owner-only, matching what Android gets from the platform. `DesktopStorage.open` used plain `mkdirs()` and both atomic writers used `FileOutputStream`, so under the usual `022` umask a portable install left a `0755` data directory holding `0644` `secrets.dat`, `secrets.dat.bck`, `vault.properties` and `hardware-unlock.b64` — readable by every local account, which hands an attacker an offline password-guessing target and leaks the KDF parameters. The new `PrivateFiles` creates the directory `0700` and staging files `0600` **before the first byte is written**, restricts the promoted target (a non-atomic move can keep the destination's older mode), and tightens the directory and known files on open so an older install is repaired. Tightening only ever *removes* access: a directory the user made read-only stays read-only and the vault reports it cannot write, rather than chmod-ing past that decision or a read-only mount. On non-POSIX file systems the JDK owner-only flags are the closest equivalent and do not rewrite an inherited ACL, so the inherited permissions remain the real boundary there.

 * Desktop: Retire the dead `RsaKeyWrapPort` and the core `KeyWrapPort` interface. They were complete, tested and guard-pinned but never constructed. The port wraps the **vault** key (Android's `otp.key`), so wiring it would have meant quick unlock via a software RSA key stored beside the vault — any process running as the user could read both and unlock without the password, strictly weaker than Android's hardware KeyStore. The useful part (owner-only writes) is promoted into shared storage instead, and a guard now asserts the port does not return.

 * iOS: Gate the OpenPGP buttons on real availability. `RustOpenPgpPort.isBackendAvailable` and `OpenPgpAvailability` had no callers while `BackupView` always enabled both buttons, so a build without the XCFramework let the user pick files and only then fail. `OpenPgpPort` gains `isAvailable` (defaulted so injected in-process ports keep working), `VaultStore` exposes `openPgpAvailability`, and both buttons are disabled with an explanatory footer when the engine is absent — parity with Android.

 * Docs: Add `docs/incomplete-feature-audit-2026-11-06-fourth-pass.md` — backup retention, browser-password editing/re-export and preferences export (#648) were closed; paper backups (#178) are declined, automatic OpenPGP backups (#531) have their prompt decision recorded, and Nitrokey native OTP (#921) needs hardware this environment does not have — each with the concrete reason rather than a stub.

 * Docs: Correct a stale parity claim — the desktop does have multi-tag filtering (`TagDialog.promptFilter`, an `OR`/`AND`/`SINGLE` multi-select panel wired through `MainWindow` to `DesktopVaultState.applyTagFilter`); what remains Android-only is the search-includes-tag preference. Add `docs/scaffold-audit-2026-11-06-second-pass.md` recording the three scaffolds, the at-rest fix, and the CI-only verification limits.

 * Refactor: Migrate all Kotlin/Java packages, directory trees (`com/fairuniversal/offlineotp`), Android `namespace`, desktop `mainClass`, manifest intent/broadcast actions, iOS Keychain service name, and sandbox/test scripts to `com.fairuniversal.offlineotp`.

 * CI: Remove Android debug build — the `build` job in `.github/workflows/android.yml` that produced `OfflineOTP-android-debug` is deleted; the workflow now only runs `signing-preflight`/`sign-release` on `master` with secrets. No debug artifact is uploaded — use `./gradlew :app:assembleFdroidDebug` locally. `scripts/tests/test_no_github_automation.py`, `README.md`, `docs/release-signing.md` and `scripts/packaging/README.md` are updated accordingly.

 * CI: Remove GitHub Deployments — the Android workflow no longer creates `android-release` deployments; release APKs are downloaded from **Actions → Android build → Artifacts** (`OfflineOTP-android-release`). Secrets are repository secrets, not environment secrets (see `docs/release-signing.md`). Existing deployments remain in the UI until deleted manually.

 * iOS: Fix mOTP tokens — `TokenCalculator.motp` was changed to SHA-256 by the CodeQL autofix for `swift/weak-sensitive-data-hashing`; mOTP is defined as `MD5(step + seed + PIN)` and SHA-256 broke every mOTP code (`693ae2` → `dc1b3a`, `0ebf99` → `44242f`, `20bba6` → `9a7dbb` in `token-vectors.json`). Restored `Insecure.MD5` with CodeQL suppressions (`codeql`/`lgtm`) and a spec comment; the Kotlin `TokenCalculator.MOTP` (MD5) is the reference. `TokenVectorTests` and `EntryDraftTests` now pass.

 * Android: Move to new application ID `com.fairuniversal.offlineotp` (`com.fairuniversal.offlineotp.dev` for debug) so the app installs **alongside** the upstream andOTP app instead of being detected as its update. Release verification checks `aapt2 dump badging` for the new ID and `testOnly`, and `docs/release-signing.md`/`README.md` are updated. Existing installs under the previous application ID must be backed up → uninstalled → restored to move to `com.fairuniversal.offlineotp`.

 * OTP cards: Hide the **Type** (TOTP/HOTP/Steam/mOTP) on the desktop card when revealed — it is shown only while editing — and add an editable **Hidden label** that behaves like **Label** but is only shown when the entry is revealed (hidden otherwise). Stored as `hidden_label` in the vault JSON, in the field order after `tags`, preserved through save/restore/QR, searchable via **Label**, and covered on desktop (`EntryCards` title + `EntryDialog` row), Android (`dialog_manual_entry` row + `component_card_*` italic line + `EntryViewHolder`, `EntryList` search) and iOS (`Entry.hiddenLabel`, `canonicalFieldOrder`, `EntryDraft`, `EntryListProjection`).

 * About: Show the required attribution on a user-visible screen on both clients — `Copyright (c) 2026 unsubstantiation — http://fairuniversal.com and https://github.com/unsubstantiation/OfflineOTP/releases` in the header card (`strings_about.xml:about_copyright`), with tappable **Website** (`http://fairuniversal.com`) and **Releases** (`https://github.com/unsubstantiation/OfflineOTP/releases`) rows on Android (`fragment_about.xml` + `BaseAboutFragment.WEBSITE_URI/RELEASES_URI`, tinted icons) and desktop (`Help → About OfflineOTP` via `AboutDialog` with `Desktop.browse` + clipboard fallback). Documented in README **Credits and license**.

 * Thunderbird: Import and export Thunderbird email login credentials as a Thunderbird-compatible CSV (Firefox/Thunderbird **Export Logins** format: `url`/`hostname`, `username`, `password` plus `httpRealm`, `formActionOrigin`, `guid` and timestamps, or the legacy `hostname` alias). On Android **+ → Import/Export Thunderbird credentials**, on desktop **Tools/Add entry → Import/Export Thunderbird credentials…**; Thunderbird and FairEmail now share the same **Emails** vault type (`EmailMaterial.Account` → `ENTRY_EMAIL`): Thunderbird CSV rows are grouped by username into one account per address (incoming `imap`/`pop` + per-identity `smtp` rows, `mailbox://` handled, default ports/encryption, 8 MiB/MAX_ENTRIES bounds), stored as `EMAIL` entries indistinguishable from FairEmail imports, and either source can be exported via `EmailMaterial.exportBackup` (FairEmail JSON) or `EmailMaterial.exportThunderbirdCsv` / `ThunderbirdImport.exportCsv` (Thunderbird CSV) — legacy PASSWORD-stored Thunderbird vaults remain exportable via the compatibility path. Core adds `ThunderbirdCsv`/`ThunderbirdImport` (now `EmailMaterial` interchange) with headless unit tests, the Fab menu gains the two thunderbird actions, and the desktop shell adds `ThunderbirdDialogs` with selection and warning dialogs.

 * Backups: Remove plain-text backup import/export — the **Restore plain-text backup…** button, `PlainTextBackupDecoder`, `PlainTextRestoreTask`, the desktop `restorePlainText` path, and the iOS `PlainTextBackupDecoder`/`restore(plainText:)` are deleted, the layout `buttonRestorePlainText` and strings `backup_button_restore_plaintext`/`backup_plaintext_warning_*`/`backup_toast_plaintext_invalid` are removed, `Constants.INTENT_BACKUP_OPEN_DOCUMENT_PLAIN` is deleted, and `BackupRestoreDeviceTest`/`EntryEditorAndBackupTest` no longer exercise plain-text. Rename the password-encrypted backup type from **Encrypted** to **Full Encrypted Backup** in `strings_backup.xml` (`backup_list_type_names`, `backup_label_crypt`), the desktop `BackupDialogs`/`MainWindow` menus/dialogs, and the translated `strings_backup.xml` files.

 * Desktop: Remove the persistent **New vault** / **Open vault** header buttons and **Vault** menu to match Android — the desktop shell now auto-prompts like the phone: first launch shows the **Create a new vault** password dialog, later launches auto-show **Open vault** while the vault is locked, reusing the same `VaultController.createVault` / `openVault` paths. `MainWindow` exposes the two prompts as `offlineotp.promptCreateVault` / `offlineotp.promptOpenVault` runnables for headless tests, `MainScreen` keeps only the app bar brand (no `vaultActions` row), the empty-state copy no longer assumes buttons, and `MainWindowDisplayTest` asserts the buttons/menus are gone and drives the auto-prompt instead of clicking them.

 * Android: Add an optional **biometric** quick unlock (fingerprint or face) as an alternative to password login, matching the iOS Face ID shortcut. It is off until the person turns it on in **Settings → Unlock with fingerprint or face**, and turning it on asks for the vault password again — an unlocked phone must not be enough to add a biometric that keeps access after the vault is locked. What the app keeps is the *derived vault key* (the 16-byte AES key the password derives), encrypted with an Android KeyStore key that has `setUserAuthenticationRequired(true)` and `setInvalidatedByBiometricEnrollment(true)` and `BIOMETRIC_STRONG` only (fingerprint or strong face; weak face is not used): the KeyStore key never leaves the TEE/StrongBox, the preference holds only `IV‖ciphertext` (Base64, ≤4 KiB), syncing and backup are off, and enrolling a new finger or face invalidates it, so someone who can add a biometric to an unlocked phone still cannot use the key they added. The vault file is untouched — it stays PBKDF2 + AES-GCM over the password, the released key is the one the password derives, and the GCM tag is what decides whether it opens the file — so a lost, cancelled or invalidated key costs only the shortcut and the password still opens everything. A key that no longer fits (the item was dropped, the biometric set changed, the vault was re-created, the password was changed) is removed rather than left to fail again, with a sentence saying so; too many failed attempts fall back to the password; turning it off deletes the item. The switch and its encrypted key live in the app's `SharedPreferences` beside `ui_sort_mode`, never inside `secrets.dat`, and `AndroidManifest.xml` gains the only permission the app declares for this, `USE_BIOMETRIC`. The unlock screen shows an **Unlock with biometrics** button when the shortcut is available and auto-prompts the system biometric dialog (fingerprint or face, whichever is enrolled) on open (a cancel stays on the password field); `BiometricKeyStore` drives every path through an injectable `BiometricManager`/`KeyStore` seam, and `scripts/tests/test_manifest_source_policy.py` pins the new permission. `AuthenticateActivity` and `SettingsActivity` together cover the enable (password-confirmed, then biometric prompt), use (prompt, then `VaultSession.offerDerivedKey`), stale/invalidated, cancelled, locked-out and disabled paths.

 * Vault: Entry edits now reliably autosave. `EntriesCardAdapter.replaceEntry` validates a draft,
checks for duplicates, writes the vault atomically under `MutationLock` and only then publishes the
replacement, matching `EntryEditor.replace` and `VaultStore.update`. `ManualEntryDialog` no
longer mutates the live entry outside the lock (which could leave a dirty in-memory entry when the
session changed before the save). A failing write keeps the previous entry and the file unchanged.
`EntryEditorAndBackupTest.editsPersistViaReplace` and `VaultStoreTests.testDeletingAndEditingPersist`
pin the round-trip.

 * OpenPGP: Complete the unified Rust backend (`rust/offlineotp-openpgp`, rpgp 0.21 + UniFFI) for all
five distributions (Android, Debian, Windows, macOS, iOS). The crate now performs real rpgp
cryptography: binary SEIPDv1 + AES-256 exports, binary/ASCII-armored imports, protected secret keys,
recipient binding/revocation/expiration checks, bounded streaming decompression, and explicit
rejection of plaintext, tampered, wrong-key, blank and oversized messages. The UDL contract
(`src/offlineotp_openpgp.udl`) generates committed Kotlin/JNA and Swift bindings; `NativeApi.kt`
adapts UniFFI's `List<UByte>` surface to the JVM `ByteArray` ports. Desktop defaults to
`RpgpOpenPgpPort` (PGPainless only when the native library is absent) and packages the host cdylib
with `-Djna.library.path=$APPDIR/native`. Android CI builds per-ABI `jniLibs` via `cargo ndk` while
retaining the complete OpenKeychain provider flow. iOS CI builds
`ios/Frameworks/offlineotp_openpgp.xcframework`; when present, Package.swift defines
`OFFLINEOTP_OPENPGP_NATIVE` and `RustOpenPgpPort` calls the generated Swift bindings, otherwise it
fails explicitly with `backendUnavailable`. Every per-platform workflow runs `cargo test` and
`cargo build --release`; `scripts/openpgp/` documents regeneration and packaging. The crate's unit
tests cover single/multi-recipient, armored, compressed, protected-key, wrong-key, tamper, plaintext,
blank-output and document-bound cases. Native compilation and cross-implementation interop remain
CI/device gates; the no-Gradle sandbox still exercises the explicit fallback/unavailable paths.

 * Android: Remove the PIN login. Authentication is now none, password or device credentials: the
fourth entry is gone from Settings → Authentication and from the first-run wizard, the unlock screen
always asks for a password, and the credential dialog, the setup slide, the deprecated `pref_auth_pin`
/ `pref_auth_pin_hash` keys and the PIN minimum length went with it. A PIN is a four-digit secret
protecting an encrypted vault and, on a device that is already unlocked, the weakest way into one;
anything left under the old keys is deleted on first start rather than migrated into credentials. The
backup-password dialog loses its unused PIN mode too, so no credential of any kind can be a
four-digit number any more — password-derived automatic backups are unchanged and still refuse a
PIN. A vault that an older release set up with the PIN login still opens: the stored method is read
as *password*, the file is untouched, and the same PIN typed into the password field derives the
same key, so nobody is locked out and **Settings → Authentication** can give that vault a real
password. Treating the stored value as unknown would have thrown on launch. mOTP PINs and
hardware-key PINs are entry and key data, not logins, and are untouched. PIN-only strings are
deleted from all 31 translations, and the eight translated sentences whose meaning changed fall
back to English rather than keep describing a login that no longer exists. `AuthMethodTest`
pins the stored-token rules, including the removed token and an unreadable one

 * CI: Move the two Apple workflow jobs to `macos-15`, because GitHub retired the `macos-14` arm64 image. Every run on that label started being cancelled before its first step with *The configuration 'macos-14-arm64-us-default' is not supported* — a probe across the labels confirmed `macos-14`, `macos-14-large` and `macos-14-xlarge` all cancel while `macos-15` and `macos-15-intel` start, and reported what the new image is: `macos-15` is arm64, macOS 15.8.2, Xcode 16.4. The macOS artifact is unchanged (`OfflineOTP-macos-arm64.zip`, still Apple silicon only), the Xcode project keeps its `xcode15_3` format pin so it still opens on Xcode 15, and `xcodeVersion` moves to the runner's 16.4. With the iOS job running for the first time, its compiler found two name errors in `BiometricUnlockTests` — the fake key store's account property was called for by the wrong name, and one assertion read that account after the failed unlock had cleared it — so the quick-unlock tests now compile and drive the paths they were written for. `scripts/tests/test_ios_app.py`, `test_ios_package.py` and `test_no_github_automation.py` fail if a workflow moves back to `macos-14`

 * iOS: Move data in and out of the vault the way the other two clients do. **Backups** writes an encrypted backup under a dated name (`otp_accounts_<yyyy-MM-dd_HH-mm-ss>.json.aes`), from a password entered twice and never stored, using the same header and the same name template as the phone and the desktop, so a file from any client opens on any other. **Restore** opens an encrypted backup, or a legacy plain-text one after Android's warning sentence, and merges it by default — imported entries first, entries the vault already holds left exactly as they are — with a **Replace existing entries** switch, off by default, that takes the backup verbatim. An empty backup, an unreadable one and one whose entries would duplicate the vault are all refused with a sentence instead of a half-applied vault, and a failed write leaves the current entries untouched

 * iOS: Make the list readable by VoiceOver. A code is now announced **one character at a time** — six digits read as a number are useless to anyone typing them into another client, and Steam and mOTP codes mix letters and digits — followed by how long the step has left, so the countdown ring can stay a decoration it does not have to describe. The reveal eye is a full 44-point target with a hint saying what it does, the name, label and kind of a row are one stop instead of three fragments, the code button says *Copy code* or *Next code* (with the step described in its hint) rather than folding the value into the label, every icon-only toolbar button keeps its label, and the tab strip still reports which tab is selected. `CodeFormatTests` pins the spoken form and the singular/plural of the seconds, and `scripts/tests/test_ios_app.py` fails if a label, the 44-point target or the spoken value is dropped

 * iOS: Add an optional **Face ID** unlock (Touch ID on a phone with a fingerprint sensor). It is off until the person turns it on, and turning it on asks for the vault password again — an unlocked phone must not be enough to add a face that keeps access after the vault is locked. What the app keeps is the *derived vault key*, in a Keychain item whose access control is `kSecAccessControlBiometryCurrentSet` on `WhenUnlockedThisDeviceOnly`: it never leaves the device, syncing and migration are off, and enrolling a new face or finger invalidates it, so someone who can add a face to an unlocked phone still cannot use the key they added. The vault file is untouched — it stays PBKDF2 + AES-GCM over the password, the released key is the one the password derives, and the GCM tag is what decides whether it opens the file — so a lost, cancelled or invalidated key costs only the shortcut and the password still opens everything. A key that no longer fits (the item was dropped, the face set changed, the vault was re-created) is removed rather than left to fail again, with a sentence saying so; too many failed attempts fall back to the password; turning it off deletes the item. The switch and its state live in the settings file beside `ui_sort_mode`, never inside `secrets.dat`, and `Info.plist` gains the only permission the app declares, `NSFaceIDUsageDescription`. A new **Settings** sheet holds the switch and the vault folder; `BiometricUnlockTests` drives every path through an injected key store, and `scripts/tests/test_ios_app.py` pins the Keychain properties, the password-confirmed enrolment and the sentences

 * iOS: Import a Google Authenticator export (`otpauth-migration://`) with all of its accounts. The protobuf reader is a port of `GoogleAuthenticatorMigration` down to its refusals: the 16 KiB ceiling, the fixed `offline` authority, duplicate and unknown fields (`INVALID_DATA`/`UNSUPPORTED`), the version 0–2 window, the signed batch id, the seven-value digit enum, the four hash algorithms, the `Issuer:label` split that only happens when the issuer field is empty, and a Base32 seed of the same bounds. A multi-code export is held until every code has arrived — a repeat or a foreign code is refused without ending the export, an export that would not fit in the vault ends instead of importing part of it, and a code that arrives after the vault locked belongs to no running import — so a half-scanned export is never partly imported. Confirming writes the batch as one change: an account the vault already holds is skipped and counted, the rest are stamped as just used, and the result is Android's sentence (*Imported N accounts; skipped M identical accounts.*). The confirmation lists the account names and never a seed. Every sentence is Android's `google_import_*` string, and `scripts/tests/test_ios_app.py` compares them, the error names, the batch statuses and the import rule with `:core`

 * iOS: Decide whether two entries are the same account the way the phone does. `Entry` compared its whole field bag, so the same account carrying a different last-used stamp, usage count, tag list or reveal state counted as a second entry — a re-imported account would have been duplicated, and a restore would have kept a near-duplicate instead of collapsing it. It now compares what `Entry.equals` compares (kind, period, counter, digits, algorithm, seed, label, issuer and the SSH target), hashes the same fields so a `Set` collapses the same entries Android's `HashSet` would, and still stores, reveals and writes every piece of metadata. For users: adding, importing or restoring an account the vault already holds is refused with *This vault already holds that entry* instead of leaving two rows that differ only in a stamp

 * iOS: Import a single account from an `otpauth://` link or from a QR image, and show any entry as a QR code. The parser is a port of `OtpUriParser` down to its bounds and its refusals (16 KiB ceiling, the four kinds, a canonical Base32 seed, mOTP's hexadecimal seed, HOTP's required counter, the 1–86400 period, 1–9 digits, Steam's fixed five, repeatable `tags` and refused duplicate parameters, `+` meaning space only in the query), and the serializer is `Entry.toUriString`'s — same field order, same `%20`, same omission of defaults — so a code shown on the phone scans on the desktop and the other way round. The error names are `OtpParseError`'s, and the messages never repeat the link back, because a link carries a secret. A second account from an issuer already in the vault asks Android's **Issuer already exists** question before it is added

 * iOS: Store the same thumbnail name the other clients would for an entry added or imported here. The iOS client draws no logos, but the name is vault data: the phone and the desktop resolve it to artwork, so writing a generic name would replace a brand mark with the default tile the moment an account crossed over. `ThumbnailCatalog` is ported from `:core` — the exact match ignoring case and punctuation, the alias table, the first whole-word match in declaration order, then `Default` — and its 468 names are *generated* from the Kotlin catalog by `scripts/ios/generate-thumbnail-catalog.py`, which CI runs in verify mode, so the two lists cannot drift

 * iOS: Add `OtpUri`, `QrPayload`, `BackupFileNames`, `PlainTextBackupDecoder` and `VaultRestoreMerge` to `OfflineOTPKit`, with `OtpUriTests` and `BackupRestoreTests` pinning every refusal and every round trip, plus app tests for a backup written by one vault and restored into another, the wrong password, the replace switch, the plain-text import, the duplicate-issuer question and the dated file name. `scripts/tests/test_ios_app.py` now compares the parser's error names and bounds, the URI field order, the backup name template, the plain-text warning, the duplicate-issuer wording and the restore summary with `:core` and with the other clients' sources

 * iOS: Give the app the daily list the other two clients have — the five tabs (OTP, Notes, SSH Keys, Passwords, Email) with a count on each, a live search over labels, issuers and tags that survives tab, tag and sort changes, tag chips with any/all/one and a tagless switch, and the same five orders (Unsorted, Issuer, Label, Last used, Most used) remembered in the client settings file under `ui_sort_mode` rather than inside `secrets.dat`. The rules are ports of the shared ones (`EntryListPresentation`, `EntryList` sorting, `EntryTagFilter`), so the same vault shows the same rows in the same order on all three clients, and `scripts/tests/test_ios_app.py` compares the tab set, the titles, the five order names and the tag rule with `:core`

 * iOS: Add and edit entries on iOS through a form backed by a port of the shared `EntryDraft` — the same kinds offered (no password archives or email accounts, which only an import can produce), the same whitespace rule for a pasted secret, the same "Digits must be a number" wording, the same usage stamp for a new entry and the same metadata preserved on an edit. SSH keys are the one deliberate exception: their seed is `SshKeyMaterial`-framed, which this build does not implement, so writing one would produce an entry the other clients cannot decode. Such entries are still listed, revealed and written back unchanged

 * iOS: Match the other clients' reveal and usage behaviour — `details_visible` gates the code and the copy action (a concealed entry shows no code and offers no copy, and a failed write leaves it concealed), copying records a use (last-used stamp plus the Most used counter), an HOTP entry gains a one-step counter action with the shared `MAX_COUNTER` ceiling, and deleting asks for confirmation by name. `EntryUsageTests` and the app tests pin the stamps, the counter and the ceiling against `EntryUsage` in `:core`

 * iOS: Add `EntryListProjection`, `EntryUsage` and `EntryDraft` to `OfflineOTPKit` with tests for the tab mapping (every kind lands in a tab, unsupported kinds included), the search fields and their locale-independent match, the AND/OR/single tag rule, collator-primary ordering with stable ties, the counter ceiling, and a hand-typed entry's exact stored bytes

 * CI: Add a macOS build workflow, one per-platform action like the Android, Windows and Debian ones — it builds the desktop app image with `jpackage` on Apple silicon, generates the `.icns` from the shared artwork instead of committing a binary, ad-hoc signs and verifies the bundle, and ships `OfflineOTP-macos-arm64.zip` with a `SHA256SUMS.txt` (built with `ditto`, since a plain zip flattens the bundle's symlinks and executable bits)

 * iOS: Start the native iOS client as a Swift package (`ios/`) that reproduces the shared formats — PBKDF2-HMAC-SHA1 through CommonCrypto with the vault's lower/upper half split, AES-GCM through CryptoKit as `nonce ‖ ciphertext ‖ tag`, the encrypted-backup header, the entry document in the stored field order, and TOTP/HOTP/Steam/mOTP — with the vault engine behind the same `AtomicByteStore`/`KeyValueSettings` ports the JVM clients use, so a save, a backup and a restore behave identically. Its tests read `desktop/src/test/resources/golden/` in place: the golden vault unlocks, `expected-vault.json` is reproduced byte for byte, the golden backup re-seals to the same bytes, and the three real desktop exports (Unicode note, >5 KiB SSH key, hidden and revealed entries) load; token vectors come from `scripts/ios/generate-token-vectors.py`, which checks itself against the published RFC 4226/6238 vectors first. Entries of a kind this build cannot display are preserved verbatim rather than dropped

 * CI: Add the fifth per-platform workflow, `ios.yml` — `swift build`/`swift test` on the Apple silicon macOS image, the vector generator, an iOS-simulator compile of the package, and a checksummed source archive; no signing identity, no secrets and no publishing, because the app target and its certificate story belong to whoever ships it. `scripts/tests/test_ios_package.py` fails the build if any restated constant, the stored field order or the token alphabet drifts from `:core`

 * Docs: Add the native iOS client plan (`docs/ios-client-plan.md`) — the vault and backup crypto a SwiftUI client must reproduce byte for byte (PBKDF2-HMAC-SHA1 split in halves, nonce-prefixed AES-GCM, the backup header, the entry JSON and its input bounds), the fixed-vector corpus in `desktop/src/test/resources/golden` it is verified against instead of by review, the milestones up to TestFlight, and why `:core` cannot simply be compiled for iOS. `scripts/tests/test_ios_plan.py` fails when the document's numbers drift from `:core`, when a named fixture disappears, or when an `ios.yml` workflow is claimed before there is an Xcode project to build

 * Core: The two manual-entry dialogs now assemble their entry through one shared builder (`EntryDraft`), including the list of kinds a form offers, so a value one form accepts is accepted by the other — including the period/counter/digits wording, whitespace in a pasted secret, and the usage stamp a new entry gets (the desktop form now stamps a hand-typed entry as used, so it leads a "last used" list as it does on Android)

 * Core: Give both clients one implementation of the rules they share — usage counting and the last-used stamp (`EntryUsage`), the one-step HOTP advance and its ceiling, the tag selection rule and tag ordering (`EntryTagFilter`), the entry-to-tab mapping (`EntryListPresentation.categoryOf`), whether an entry has an `otpauth://` QR form (`Entry.hasOtpUri`), and the merge-or-replace plan a restore applies (`VaultRestoreMerge`) — so a phone and a desktop copy of the same vault cannot follow different rules and a fix lands once

 * Desktop: Restore asks the same question as Android's backup screen — a **Replace existing entries** switch, off by default, so a restore merges the backup into the vault (new entries first, existing ones keeping their counters and metadata) instead of silently discarding the current entries; and **Tools → Restore plain-text backup…** imports a legacy unencrypted JSON backup through the same shared decoder and warning Android uses

 * Desktop: Close two more Android differences — entries can be reordered in the unsorted view with **Move up**/**Move down** or Alt+↑/↓ (Android's drag handle, gated by the same rule and saved in the vault), and **Show QR code…** renders an entry's `otpauth://` URI like Android's popup item, for the same entry kinds

 * Desktop: Match the Android client on four points where the two had drifted — the entry list can now be sorted (**Unsorted**, **Issuer**, **Label**, **Last used**, **Most used**) and the choice is remembered in the client settings file, importing entries stamps them as just used so they lead a **Last used** list, adding or importing entries stops at the same 2000-entry ceiling instead of writing a vault Android refuses, and **Delete** asks for confirmation by name before removing the entry

 * Email accounts: Keep FairEmail-compatible IMAP/POP and SMTP credentials in one encrypted vault entry on both clients, with a fifth **Email** tab; add an account by hand, view its connection details, and import or export FairEmail backups (plain JSON, the gzip agent export, or a password-protected file), with passwords masked in the desktop details dialog until you ask for them

 * Desktop: Complete the email support the Android client and `:core` already had — previously an `EMAIL` entry in a shared vault was filtered into a category that the desktop tab strip, a fixed list of four labels, could not select, so it was invisible; the strip is now derived from the shared category list and the same form validation guards both clients

 * Email accounts: Describe encryption and protocol by name (SSL, STARTTLS, none; IMAP, POP) in the connection-details rows, and build those rows once in `:core` so Android and desktop cannot show different text for the same stored account

 * Desktop: Keep the vault, its `.bck` copy, the settings and the hardware-unlock record in a `data` folder inside the program folder instead of `~/.offlineotp`, so the Debian and Windows builds are fully portable: copy the folder and everything travels with it. `-Dofflineotp.home=<folder>` or `OFFLINEOTP_HOME` still selects another folder, and an unwritable program folder is reported instead of falling back into the user's profile

 * SSH keys: Store an optional host name, port, user name and password beside the keys, inside the encrypted vault and its encrypted backups; a revealed card shows `user@host:port` and the copy menu offers the password. Blank fields are omitted, so existing entries, vaults and backups keep their exact bytes and older builds still read them

 * CI: Skip the signed release build when the release keystore secret is not configured, instead of failing the Android workflow at 0s; release signing stays opt-in and is documented in `docs/release-signing.md`

 * CI: Read the signing credentials from the `android-release` environment (`ANDROID_KEYSTORE_BASE64` and `ANDROID_KEYSTORE_PASSWORD`, with the older `ANDROID_RELEASE_*` names still accepted), take the key alias from the keystore itself instead of asking for a third secret, and report a clear skip on any branch the environment does not allow

 * CI: Make the signed release build usable once the keystore *is* configured — the store password, key alias and key password now reach Gradle as environment properties instead of `-P` arguments, so a password containing a quote, backslash, `$`, backtick or space cannot break the command or be read from `ps` output; a keystore configured without its password now names the missing secret instead of failing inside Gradle with `keystore password was incorrect`; and the decoded keystore is deleted in an `always()` step so it does not outlive the job

 * Android: Make the app switcher blackout a setting (**Hide in the app switcher**), off by default, so the last screen stays visible in the switcher unless you opt in; screenshots remain blocked while the app is open and the QR scanner still always hides its preview

 * UI: Show a revealed secure note in full, wrapped over as many lines as it needs, on both Android and desktop instead of a "note hidden" placeholder

 * UI: Show the bundled issuer logos on desktop entry cards (Debian and Windows rasterize the same Android vector marks) instead of generated letter tiles

 * UI: Use a warm amber for OTP digits on both clients, with a warning color only while a code is about to expire

 * Desktop UI: Give entry cards a top-lit gradient, a hover lift and a soft shadow; the token still turns amber-to-red as its step runs out, but the extra ring drawn around the issuer logo is gone

 * Desktop: Suggest dated backup file names (`otp_accounts_<timestamp>.json.aes` / `.json.gpg`) like Android instead of the fixed `otp_accounts.json.aes`, so exporting twice cannot overwrite an earlier backup

 * Desktop: Ask for a new backup password twice, with a **Show password** toggle and the Android minimum-length rule, so a mistyped password cannot make the backup unreadable

 * Desktop UI: Drop the bottom footer row (unlocked-entry count and the OFFLINE badge); the entry count next to **Your entries** stays

 * Desktop: Add tag assignment for all entry types and a tag filter that works with search and tabs; preserve tags in the encrypted vault and backups.

 * Passwords: Import Chrome, Firefox and Safari CSV credentials individually or preserve the original file for later plaintext export; separate OTP, notes, SSH keys and passwords into tabs. New password entry types require an updated app to read vaults and backups.

 * Desktop: Use the OfflineOTP forest-lock logo for the Windows and Linux app images and Swing window

 * UI: Show both issuer and label on hidden Android and desktop entries while keeping their protected details concealed; Android's Hide issuer preference still applies

 * Desktop UI: Match Android's Dark Forest palette with entry cards, themed controls and dialogs, direct note/SSH/QR add actions, and keyboard-accessible visibility and row menus

 * Desktop: Keep the selected card across token refreshes and preserve an active search after entry changes or imports

 * UI: Apply the Dark Forest palette to Android dialogs, dropdowns and add-menu labels, and improve setup and text contrast

 * UI: Add direct Android **+** menu actions for secure notes and SSH keys, with a scrollable menu on smaller screens

 * Removal: Drop restoring legacy headerless andOTP backups on Android and desktop; files created before andOTP 0.6.3 must be opened and re-exported with an older release

 * Build: Update artifact uploads to v7.0.2 and Android setup to v4.0.4 (Node 24), retaining full commit-SHA pins and the existing Android command-line tools version

 * Build: Add separate Android debug, Windows and Debian app-image GitHub workflows with downloadable artifacts and checksums

 * Build: Make those workflows build end to end: hold the YubiKit Android transport at 3.1.0 (`compileSdk 36`), build the desktop client for JVM 11 (PGPainless 2.x), use YubiKit's javax-free `PcscManager()` constructor (`java.smartcardio` is not a `java.se` module), and install `binutils` so jpackage can link the Debian app image

 * Feature: Add 46 brand logos (Oracle, Veeqo, cloud, banking, telecom, SaaS, gaming, password-manager and VPN services), and reach three logos that shipped without an enum entry

 * Bugfix: Match a brand logo for issuer spellings that differ only in punctuation or spacing, so "Login.gov", "login.gov", "GOV.UK", "Battle.net", "digital ocean" and similar resolve to their icon instead of the letter tile; add a small alias table for unambiguous abbreviations (AWS, Azure, GCP, 1Password, Tuta)

 * Bugfix: Give Netcup, Netlify and phpMyAdmin capitals in their thumbnail names; the lowercase declarations never matched the persisted catalog names, so no entry could select those logos

 * Feature: Remember per-entry hide/reveal choices for OTPs, notes and SSH keys on Android and desktop; entries default to hidden, and encrypted backups retain the choice

 * Feature: Store SSH public and/or private key text in one encrypted Android or desktop vault entry, with separate copy choices and encrypted-backup support; older releases cannot read these entries

 * Feature: Add standalone encrypted notes (up to 200 Unicode characters) on Android and desktop, with copy/edit and portable encrypted backups; older releases cannot read vaults or backups containing notes

 * Feature: Add a pure-JVM Swing desktop client (`:desktop`) over the shared Android-free `:core` engine, using the same vault and backup file formats with byte-identical golden fixtures; vault data lives in `~/.offlineotp`
 * Desktop: Create/open password vaults, add/edit/delete entries, search, and show refreshing TOTP/HOTP/Steam/mOTP tokens; copy a token via toolbar button, row double-click or context menu (copy records last-used and usage frequency), with explicit HOTP counter advancement and a guard at the counter ceiling
 * Desktop: Import QR image files, including multi-part Google Authenticator exports with duplicate-issuer confirmation, bounded file reads and rollback on failed writes; no camera or screen capture
 * Desktop: Save and restore password-encrypted and OpenPGP (PGPainless 2.x) backups
 * Desktop: Experimental PC/SC-only USB security-key support (no raw HID or NFC): OATH account list/calculate/copy-to-key and FIDO2 PRF enroll/unlock through cancellable background jobs, with typed no-key/cancel/failure results, PIN zeroization on every path and no provider error text surfaced
 * Internal: Extract vault, parser, backup and hardware-key protocol layers into the shared `:core` module behind ports, with headless desktop tests, reproducible `:desktop:distZip` packaging and an optional jpackage app-image smoke test
 * Experimental: Add USB/NFC OATH account access and FIDO2 PRF key-or-password vault unlock; Android and physical-key verification is still required
 * Bugfix: Preserve TOTP periods when copying hardware accounts with numeric name prefixes, and reject ambiguous OATH IDs
 * Bugfix: Discard stale hardware HOTP displays after an interrupted request, and recheck hardware TOTP validity after clock changes
 * Bugfix: Preserve pending automatic-backup retries when overlapping snapshots and reverted edits race
 * Feature: Import Google Authenticator export QR codes or images, including multi-code batches, with confirmation and duplicate skipping
 * Security: Add password-login-derived encrypted backups without storing the app password or requiring Android KeyStore; preserve legacy separate-password and KeyStore-vault compatibility
 * Improvement: Snapshot eligible entry changes, including imports, without exporting no-op edits; retain completed backups and retry interrupted changes after unlock when possible
 * Improvement: Prompt for the actual password when restoring encrypted files, including snapshots made before a password change
 * UI: Fall back to the updated English backup-setting explanations until their translations are revised
 * Bugfix: Keep an active search applied after account, tag, or sort changes; clearing it restores the selected tags
 * Bugfix: Show mOTP's 10-second countdown and actual import parameters, and highlight only genuinely expiring time-based codes
 * Security: Dismiss and wipe pending URI/QR confirmations on lock or activity destruction; reject confirmations from an earlier vault session
 * Internal: Remove unreferenced external-storage probes
 * Internal: Use the OfflineOTP name for the Gradle project and test fixtures while preserving legacy Android identifiers for compatibility
 * Internal: Remove GitHub Actions workflows; run build and release checks separately
 * Feature: Import supported `otpauth://` URIs from the manual-entry dialog, including type, algorithm, timing, counter and tags (andOTP #990)
 * Improvement: Show per-tag entry counts in the manual-entry tag picker as well as the main tag drawer (andOTP #994)
 * Improvement: Warn before importing a scanned or file-based QR account from an issuer already in the vault, while allowing additional accounts (andOTP #918)
 * Bugfix: Keep the first-run finish button enabled after rotating on the last slide, and attach the pager's restored slides instead of a second set of fragments
 * Bugfix: Keep the backup-password dialog open when the KeyStore cannot store the password, instead of closing it as if the password had been saved
 * Bugfix: Apply the screenshot block to Settings, Backup, About and the first-run wizard, not only the entry list and the unlock screen
 * Bugfix: Mark copied tokens as sensitive for supported system clipboard previews
 * Bugfix: Refresh entry cards when individual timeout bars are turned on or off, and stop overlapping progress animations from fighting each other
 * Bugfix: Open the mOTP PIN dialog for the entry that was actually long-pressed after the list has resorted, and report when no app can receive keystrokes
 * Bugfix: Do not show an authentication-failed message when the encryption-change prompt is cancelled
 * Bugfix: Let the first-run wizard follow the device orientation and keep a completed setup (and the derived vault key) across recreation (andOTP #591)
 * Bugfix: Make the inactivity re-lock count screen-off and background time instead of only foreground Handler time, and default the delay to the preference's 30 seconds (andOTP #712)
 * Bugfix: Keep the localized "Hidden" token placeholder on one auto-sized line on the compact, default and full cards (andOTP #880)
 * Bugfix: Resize the manual-entry and password dialogs with the keyboard so Save/Cancel stay reachable (andOTP #805)
 * Internal: Add device reachability tests for the first-run, About and Settings screens, and Python guards for the carry-over fixes (OfflineOTP #39)
 * Bugfix: Regenerate the sandbox `R` stand-in after the thumbnail batch (the local compile gate was broken) and add a freshness check
 * Bugfix: Restore the missing `%1$s` format argument in the Arabic confirm-delete dialog
 * Internal: Add the JVM unit-test suite (RFC vectors, parser/document matrices, timer policy, entry-list semantics) with a no-Gradle harness (`scripts/run-jvm-tests.sh`)
 * Internal: Add the instrumentation suite (on-device crypto/database/URI vectors, environment and first-run smoke checks) and a compile check for no-SDK environments
 * Internal: Add Python guards in `scripts/tests` for resources, conversion surfaces, secret state and build-input mutations
 * UI: Rewrite the in-app English text for accuracy and concision, point the About links at this repository, display the required license attribution, and drop unused or stale strings
 * Docs: Refresh the READMEs and issue templates, and correct stale test-suite and environment references in the design records

#### v0.9.0.1

 * Bugfix: Fix crash when using the tags drawer (Issue #856)

#### v0.9.0

 * New feature: Option to focus search on start (Issue #654,PR #658 by @mchllngr)
 * New feature: Option to disable Android sync (Google Drive backup) during initial setup (Issue #620, PR #622 by @RichyHBM)
 * New feature: Option to automatically unlock the app after the credentials have been auto-filled (PR #657 by @mchllngr)
 * New feature: Option to globally hide the issuer (Issue #678, PR #758 by @Ullas-Aithal)
 * New feature: Option for multi-line labels (Issue #699)
 * New feature: Automatic theme selection for Android 10+ (Issue #394, PR #418 by @Ullas-Aithal)
 * New feature: Allow editing digits, period and counter for existing entries (Issue #694)
 * New feature: **mOTP support** (Issue #142, PR #617 by @mantinan)
 * New feature: Show a warning if the automatic time setting is disabled on the phone (Issue #285 and #777)
 * New feature: Share OTP token as keystrokes (PR #693 by @DanielWeigl)
 * New feature: Option to show previous token (Issue #506)
 * Improvement: Perform authentication in the background to improve responsiveness (Issue #635, PR #738 by @jsoberg)
 * Improvement: Backups are now performed in background tasks to improve responsiveness and work around problems with some storage providers (e.g. Nextcloud)
 * Improvement: Show simple instructions and hide the timeout bar if there are no accounts (Issue #745 and #746, PR #747 by @Ullas-Aithal)
 * Improvement: Pressing ENTER on a physical keyboard now finishes certain tasks (Issue #590, PR #661 by @mchllngr)
 * Improvement: Use a font with slashed zero for the account secrets (Issue #665, PR #772 by @Ullas-Aithal)
 * Improvement: Auto-select the thumbnail if the name appears as part of the issuer (Issue #685, PR #687 by @mavit)
 * Improvement: Force a minimum password length for backups (Issue #770, PR #771 by @Ullas-Aithal)
 * Improvement: Automatically update the thumbnail when changing the issuer of an entry (Issue #553)
 * Improvement: Try harder to decode QR codes from images (Issue #790, PR #792 by @ziegenberg)
 * Improvement: Perform a short benchmark to determine the iterations to use for unlocking the database (Issue #778)
 * Bugfix: Switch keyboard when changing auth type during setup (Issue #740, PR #741 by @jsoberg)
 * Bugfix: Show correct message on password mismatch during setup (PR #774 by @brainynai)
 * Bugfix: Validate password before saving settings during setup to avoid empty passwords (Issue #593)
 * Bugfix: Fix scanning QR codes on Android 11+ (Issue #806, PR #807 by @ziegenberg)
 * Bugfix: Fix OpenKeychain detection on Android 11+ (Issue #825)
 * Bugfix: Don't require authentication after attaching a physical keyboard (Issue #592)
 * Bugfix: Fix crash during thumbnail selection with empty issuer
 * Internal: Performance and stability improvements for the main token list (fixes #346 as well)
 * Internal: Upgrade Gradle, build tools and dependencies
 * Internal: Perform key-generation during setup in a background task
 * Internal: Perform credentials change in the settings in a background task
 * Internal: Perform encryption change in the settings in a background task
 * Internal: Use our own NumberPickerPreference class
 * Thumbnails: LOTS of new thumbnails (thanks to all contributors)
 * Translations: Indonesian, Serbian and Slovak (thanks to all contributors)

#### v0.8.0

 * New feature: **Storage Access Framework** (remove all manual storage permissions and use the SAF everywhere)
 * New feature: New setting to block autofill access to important fields (Issue #493)
 * Improvement: Improve some English strings (Issue #149, PR #571 and #576 by @jsoberg)
 * Improvement: Don't automatically backup when updating last used or when moving cards
 * Improvement: Enable the "append date to backups" option by default
 * Improvement: Strip issuer from the label when adding via URL/QR code (Issue #551, PR #560 by @shivasheeshyadav)
 * Improvement: Remove the "new backup format warning" (has been there long enough)
 * Improvement: Show requirements for auto backups in the settings (Issue #492)
 * Improvement: Fix some accessibility issues (Issue #498)
 * Improvement: Toggle show/hide of a password confirmation field together with the password field (Issue #638, PR #641 by @jsoberg)
 * Improvement: Decoding of secrets with an invalid Base32 encoding (Issue #600)
 * Bug fix: Make the automatic thumbnail selection case insensitive (Issue #564)
 * Bug fix: Validate Integer input for counter, digits and period (Issue #569, PR #570 by @jsoberg)
 * Bug fix: Visibility of the password confirmation field (Issue #580, PR #582 by @jsoberg)
 * Bug fix: Crash when clicking on an entry multiple times (Issue #631, PR #634 by @jsoberg)
 * Bug fix: Crash during settings changes (Issue #639, PR #640 by @jsoberg)
 * Bug fix: Don't use regionalized language codes (Issue #567)
 * Bug fix: Report backup failure correctly (Issue #671)
 * Internal: Update Gradle, build tools and dependencies
 * Internal: Min API Level set to 22 (Lollipop	5.1)
 * Internal: Refactoring and reducing build warnings
 * Thumbnails: Lots of new ones (thanks to all contributors)

#### v0.7.1.1

 * Bug fix: Fix migration of old tap-to-reveal setting
 * Bug fix: Cache encryption key when recreating main activity

#### v0.7.1

 * Deprecation notice: This will be the last version to support Android versions below 5.1
 * New feature: Show QR codes of stored accounts (PR #501 by @tilosp)
 * New feature: Support Steam URIs (Issue #510)
 * New feature: Move Steam out of the Special features
 * New feature: Unify the edit dialog for entries (Issue #241)
 * New feature: Add an option to hide the global timeout bar (Issue #166)
 * New feature: Add an option to show individual timeout bars for all cards (Issue #166)
 * New feature: Add options to configure single and double taps on entries (Issue #489)
 * Improvement: Increase the iterations for the password generation to 150000
 * Improvement: Show cards as transparent while dragging (Issue #487, PR #488 by @Ullas-Aithal)
 * Improvement: Rewording of the last used dialog (Issue #485)
 * Improvement: Handle the back key to close open drawers or the FAB overlay (Issue #499)
 * Improvement: Validate secrets during manual entry (Issue #500)
 * Bug fix: Fix some remaining issues with the intro dialog (Issue #486)
 * Bug fix: Fix images containing gradients on API versions below 24 (Issue #539)
 * Thumbnails: Lots of new ones (thanks to all contributors)
 * Translations: Bulgarian (thanks to all contributors)

#### v0.7.0

 * New feature: Generate a new HOTP token when revealing (Issue #334, PR #366 by @moritzgloeckl)
 * New feature: Split issuer and label (Issue #258, PR #372 by @lucavallerini)
 * New feature: Automatic thumbnail selection based on the issuer (Issue #388, PR #389 by @schwedenmut)
 * New feature: Allow searching the tags and issuers in addition to the label (Issue #327)
 * New feature: Turn tokens red if they are about to expire (Issue #311, PR #410 by @Ullas-Aithal)
 * New feature: Handle otpauth:// intents from other apps (Issue #324, PR #393 by @schwedenmut)
 * New feature: Create an encrypted backup every time the entries are changed (PR #397 and PR #421 by @RichyHBM)
 * New feature: Different layouts for the entry cards (compact, default and full)
 * New feature: New thumbnail size "Tiny"
 * New feature: Block accessibility services from seeing sensitive input fields via a new settings item
 * New feature: Import QR codes from image files (Issue #377, PR #425 by @Ullas-Aithal)
 * New feature: Move the app to the background after copying a token (Issue #373, PR #392 by @Ullas-Aithal)
 * New feature: Re-lock the app after a certain time of inactivity (Issue #28, PR #390 by @LizardWithHat)
 * New feature: Re-lock when being send to the background (Issue #216)
 * New feature: Sort tokens by "most used" (Issue #443, PR #467 by @Ullas-Aithal)
 * Improvement: Hide the token list on screen off (Issue #264, PR #390 by @LizardWithHat)
 * Improvement: Scale the font of the default thumbnail with its size
 * Improvement: Do not use auto-completion for the account secret (PR #430 by @duchampdev)
 * Improvement: Enable Android backup by default (Issue #341, PR #342 by @RichyHBM)
 * Improvement: Remove whitespaces from manually entered secrets (Issue #253, PR #426 by @Ullas-Aithal)
 * Improvement: Fallback method for opening backup files (based on PR #358 by @theobch)
 * Improvement: Allow the backup directory to be set independent of the "ask for filename" setting
 * Bug fix: Fix black navigation bar on OxygenOS devices (PR #417 by @Ullas-Aithal)
 * Internal: Migrate to AndroidX
 * Internal: Update Gradle and a lot of dependencies
 * Thumbnails: Lots of new ones (thanks to all contributors)
 * Translations: Greek, Hindi (thanks to all contributors)

#### v0.6.3.1

 * Introduce build flavors:
   - fdroid: Shows donation links in the About section
   - play: Doesn't show donation links in the About section

#### v0.6.3

 * Security: Improved password derivation for the password protected backups
 * New feature: Prevent screencapture in the Authentication and QR scanner screen (Issue #378, PR #386 by @schwedenmut)
 * New feature: Color navbar according to the theme (Issue #284, PR #371 by @HarryJohnso)
 * Bug fix: Fix "all tags" only selecting visible tags (Issue #333, PR #350 by @RichyHBM)
 * Bug fix: Focus the password/PIN input field on start (Issue #356, PR #357 by @schwedenmut)
 * Bug fix: Fix spelling of "QR code" (PR #368 by @yegortimoshenko)
 * Bug fix: Always use arabic numerals for the tokens (Issue #359)
 * Bug fix: Refactor storage access code to allow importing and exporting from cloud storage directly
 * Bug fix: Hardcode the black background color to avoid strange behaviour on some custom ROMs
 * Bug fix: Force English locales for saving AuthMethod
 * Misc: Update donation links (PR #351)
 * Thumbnails: Lots of new thumbnails
 * Translations: Hungarian

#### v0.6.2

 * Bug fix: Proper handling of RTL layouts by forcing LTR for the tokens (PR #280 by @ahangarha)
 * Internal: Image compression (thanks to @Peppernrino)
 * Thumbnail: Add a LOT of new thumbnails (thanks to everybody that contributed)
 * Translation: New Arabic, Traditional Chinese, Japanese, Persian and Swedish translations (thanks to all contributors)

#### v0.6.1

 * New feature: Enable Android Backup by default if available and using the password encryption (PR #252)
 * Bug fix: Fix crash in the manual entry dialog on KitKat
 * Bug fix: Fix thumbnail generation on KitKat
 * Thumbnail: Add MediaWiki (PR #246 by @MeLlamoPablo)

#### v0.6.0

 * New feature: **HOTP support**
 * New feature: Settings item to activate Broadcast backups
 * New feature: Re-locking of the app on screen off is now optional (Issue #28)
 * New feature: Allow PGP backups with only a public key (Issue #31)
 * New feature: Show individual timeout bars on the cards for non-default periods
 * New feature: App shortcuts to add new entries (Issue #185)
 * New feature: Ask for the backup password if it's not available (Issue #182)
 * New feature: Allow installation on external storage (PR #206 by @leggewie)
 * Bug fix: Avoid crash on empty PIN/Password an API 23 (Issue #159, PR #160 by magnus anderssen)
 * Bug fix: Honor the system accessibility settings for the font size (Issue #71, PR #192 by @mbertram)
 * Bug fix: Make the new entry dialog scrollable (Issue #196)
 * Bug fix: Fix autofill of the password fields (Issue #215, PR #218 by @z3ntu)
 * Bug fix: Extend thumbnail generation to non-latin letters and digits (PR #234 by @jeandeaual)
 * Bug fix: Show new entries at the top of the list when using last used sorting (Issue #211)
 * Bug fix: Fix a crash on the settings page (Issue #197)
 * Internal: Replace custon FAB menu with Floating Action Button Speed Dial library (Issue #155 and #186)
 * Style/UI: Use AboutLibraries instead of LicenseDialog and rework the About section (Issue #155)
 * Style/UI: Show a disclaimer about the included thumbnails in the About screen
 * Update: ZXing Android Embedded (3.6.0), Constraint Layout (1.1.2) and all support libraries (27.1.1)
 * F-Droid: Add the feature graphic and some screenshots (PR #117 by @jaller94)
 * Thumbnails: lots of new thumbnails (see the wiki)

#### v0.5.1 (Google Play only)

 * Like v0.5.0 but with Password-based encryption offered by default during setup

#### v0.5.0

 * New feature: **Intro screen when staring the app for the first time to setup encryption and authentication**
 * New feature: **Broadcast receivers to trigger backups from Tasker** (PR #115)
 * New feature: **Add support for using Android Backup** (Issue #109, PR #111)
 * New feature: Optionally append date to backups (PR #124)
 * New feature: Check if entries are valid when entering manually (Issue #135, PR #136 by Björn Richter)
 * New feature: Offer different options when using the tag selection (Issue #133, PR #134)
 * New feature: Show a warning before changing the encryption
 * Bug fix: Fix crash when saving an empty label (Issue #138, PR #139 by Björn Richter)
 * Bug fix: Fix visibility of thumbnails in dark themes (Issue #88, PR #90)
 * Bug fix: Don't require credentials again after screen rotation (Issue #152)
 * Thumbnails: new thumbnails (see the wiki)

#### v0.4.0

 * New feature: **Password-based encryption** (a big thanks to all the testers)
 * New feature: Enforce a minimum password / PIN length (Issue #107)
 * New feature: Add an additional unlock button to the authentication (Issue #87)
 * New feature: The thumbnail toggle is now in the size selector (Issue #98, PR #102)
 * New feature: Split the tokens into blocks (Issue #83, PR #83 by DanielWeigl)
 * New feature: Account name is now shown in the removal confirmation (Issue #84)
 * New feature: Advanced options are now hidden in the manual entry dialog (Issue #85)
 * New special feature: Clear the KeyStore (use with caution)
 * Bug fix: Change the format used to store and set the language (Issue #112)
 * Bug fix: Add some extra padding to the RecyclerView (Issue #95)
 * Bug fix: Remove gradients from vector thumbnails (Issue #103, PR #97)
 * Thumbnails: a lot of new thumbnails (check the wiki for details)
 * Translation: Catalan (ca-rES) thanks to isard

#### v0.3.1

 * Move: the Github repository was moved from flocke/andOTP to andOTP/andOTP for better organization of collaborators
 * New feature: assign (predefined) images to entries (Issue #14, PR #75, again thanks to @RichyHBM for the implementation)
 * New feature: sort labels locale-sensitive (PR #74 by carmebar)
 * New feature: re-hide the revealed entries after a configurable timeout (Issue #77)
 * New feature: add sorting by last usage (Issue #67)
 * New feature: improved error messages during the import of backups
 * New feature: make the replace switch default to false (Issue #80)
 * New special feature: disable Special features again
 * New special feature: enable screenshots in the main Activity
 * Bug fix: use sp for font sizes (to make them scalable)
 * Bug fix: disable the save button in a manual entry until label and secret are not empty (Issue #82)
 * Style/UI: better description of the replace switch
 * Update: Android SDK 27 (Issue #76)
 * Update: Android Gradle plugin 3.0.1
 * Translation: Chinese Simplified (zh-rCN) thanks to Cp0204

#### v0.3.0

 * New feature: tagging support (Issue #37, PR #64, big thanks to @RichyHBM for actually implementing this)
 * New feature: settings option to scroll overlong labels instead of just truncating them
 * New feature: option to append entries during import instead of just replacing everything
 * New feature: in-app language switcher (Issue #53)
 * Bug fix: convert secrets to upper case when importing from JSON (Issue #55)
 * Bug fix: some layout fixes for certain translations (Issue #58)
 * Style/UI: new adaptive icon for Android 8+ (Issue #65)
 * Style/UI: remove card elevation
 * Update: Android Studio 3
 * Update: Gradle 4.1 / Android Gradle Plugin 3.0

#### v0.2.8

 * New feature: store authentication credentials hashed (Issue #49)
 * New feature: store backup password encrypted (Issue #49)
 * New feature: set a static backup dir to disable the file selector (Issue #52)
 * New feature: special features (see wiki)
 * New special feature: SteamGuard tokens (Issue #38)
 * Style/UI: black theme (Issue #47)
 * Bug fix: keep authentication settings when receiving a Panic Trigger (Issue #50)
 * Bug fix: progress bar animation with default duration scale
 * Translation: Czech (cs-rCZ) thanks to Picard0147

#### v0.2.7

 * New feature: require authentication again after screen lock (Issue #28)
 * New feature: make response to Panic Trigger configurable (Issue #35)
 * Bug fix: prevent adding duplicate entries (Issue #41)
 * Update: Android SDK 26 (Oreo)
 * Update: Apache Commons Codec 1.10
 * Code: lot of internal changes (mostly due to the Android 26 update)
 * Translation: French (fr-rFR) thanks to Johan Fleury
 * Translation: Durch (nl-rNL) thanks to T-v-Gerwen and rain2reign
 * Translation: Galician (gl-rES) thanks to Triskel
 * Translation: Russian (ru-rRU) thanks to Victor Nidens, Ilia Drogaitsev and Dmitry

#### v0.2.6

 * New feature: custom password preference with confirmation (Issue #26)
 * New feature: use an individual password or PIN to lock the app (Issue #23)
 * New feature: support for Panic Trigger (PR #27 by carmebar)
 * New feature: support for variable digits lenths (PR #30 by SuperVirus)
 * Bug fix: OpenPGP with security token (Issue #20, PR #25 by carmebar)
 * Style/UI: add Contributors, Translators and Translate to About
 * Code: internal refactoring
 * Translation: German (de-rDE) thanks to SuperVirus

#### v0.2.5

 * New feature: sort the entries by label (Issue #12)
 * New feature: add support for SHA256 and SHA512 (Issue #24)
 * Bug fix: show current theme in the settings
 * Bug fix: don't show FloatingActionMenu when scrolling while searching
 * Code: lots of internal refactoring
 * Translation: Polish (pl-rPL) thanks to Daniel Pustuła
 * Translation: Spanish (es-rES) thanks to Carlos Melero

#### v0.2.4

 * New feature: make the font size of the labels configurable (Issue #18)
 * Style/UI: Dark theme (Issue #3)
 * Bug fix: make the backup activity scrollable (Issue #15)
 * Bug fix: remove swipe-to-dismiss to avoide accidental deletions (Issue #13)
 * Bug fix: use the whole card for tap-to-reveal, not just the token (Issue #10)
 * Code: internal changes (as always)

#### v0.2.3

 * New feature: encrypted backups with password
 * New feature: show a warning about backups on the first launch
 * Style/UI: rename Export and Import to Backup and Restore
 * Bug fix: don't require device authentication again after screen rotation (Issue #7)
 * Bug fix: hide the FloatingActionMenu on scroll (Issue #8)
 * Bug fix: rename the apps launcher icon to "andOTP" (Issue #6)
 * Bug fix: restrict the label size so they don't overlap with the buttons (Issue #9)
 * Code: lots of internal refactoring

#### v0.2.2

 * Bug fix: resume import and export after permission request
 * Bug fix: implement a working hashCode function for the Entry class
 * Code: add missing copyright headers
 * Code: fix some tests
 * Code: remove outdated tests

#### v0.2.1

 * New feature: encrypted backups using OpenPGP
 * Style: new about screen
 * Style: new backup screen
 * Code: a lot of refactoring

#### v0.2.0

 * New feature: copy token to clipboard
 * New feature: device credentials to unlock app
 * New feature: manually enter account details
 * New feature: search
 * New feature: settings activity
 * New feature: tap to reveal
 * Style: replace FAB with a custom FloatingActionMenu
 * Style: replace all Snackbars with Toasts
 * Update: ZXing Android Embedded v3.5.0
 * Code: a lot of internal fixes and refactoring
 * Code: initial groundwork to support different types of OTP tokens (e.g. HOTP)

#### v0.1.0

 * Initial release (beta)

