# Email editor parity audit

> **Historical record:** findings, paths and test results below describe the reviewed revision,
> not current implementation or verification. See [current status](shared-ui-migration-status.md)
> and the [documentation index](README.md).

## Changes

- Android, Windows, macOS, Linux desktop and iOS now render the same shared Compose Multiplatform
  email creation, comprehensive multi-identity editing (`SharedEmailEditDialog`), masked
  credential-only rotation (`SharedEmailPasswordDialog`), and connection/sender settings +
  secondary SMTP identity removal (`SharedEmailSettingsDialog`) forms from `:ui-foundation` over
  toolkit-free DTOs in `:ui-contract`.
  Android creation also uses the shared creation input, with guarded
  save-before-adopt import persistence rather than the legacy numeric-field dialog.
- Edit includes issuer, label, Hidden Label, newline-separated tags, artwork, every stored
  incoming account field and every stored SMTP identity field. Imported extension objects
  and arrays are editable as JSON; unknown authentication IDs remain numeric, because the
  format does not define a closed enumeration for them.
- Core `EmailEditInput` owns typed JSON reconstruction and calls the existing email encoder
  and Entry validator. `ui-model` only exposes aliases. Entry cloning preserves internal
  usage/identity/privacy metadata. Empty passwords mean empty, not “keep the old value”.
- Protocol, encryption, boolean flags and artwork use compact dropdowns. Password/token
  fields are masked until explicitly revealed. Changing canonical transport settings updates
  matching legacy `pop`/`starttls` flags (and vice versa).
- iOS hosts the shared `:ui-foundation` Compose email dialogs (`SharedEmailAccountDialog`,
  `SharedEmailEditDialog`, `SharedEmailPasswordDialog`, `SharedEmailSettingsDialog`, and
  `SharedEmailDetailsDialog`) from `ComposeVaultHost.swift`, backed by `OfflineOTPKit`
  (`EmailEditInput`, `EmailPasswordInput`, `EmailSettingsInput`) so multi-identity edits,
  extension fields, Entry metadata (`issuer`, `label`, `hidden_label`, `thumbnail`, `tags`),
  masked credential rotation, and secondary SMTP identity removal match Android and desktop while
  re-checking exact opening entry fields, session generation, and live reveal status before saving.
- Existing details/copy views are retained. Email creation, editing and detail views display
  the requested warning; no operation here contacts or changes a mail server.

## Validation

Sandbox coverage: typed/lossless round trips, all-identity credential edits, unknown fields
and authentication IDs, invalid typed values/ports, legacy flag synchronization, unchanged
originals, and failed/dismissed/replaced desktop edit requests. Desktop, UI/model, app sandbox
and Android instrumentation compilation gates are run with stubs; these are not native UI tests.
Python checks validate source wiring and build conventions, not Swift runtime behavior.

## Boundaries and follow-up verification

- Native Gradle/Compose, Android device and Xcode/iOS builds still require validation. Check
  long forms/keyboard scrolling, dropdown placement, password reveal, cancellation, lock while
  editing, save failure and save/reopen with multi-identity imports on each platform.
- Android/desktop share code; iOS is a separate Swift implementation. There is no claim that
  source checks compile SwiftUI. Desktop creation still uses its existing background save
  flow; Android creation and existing-entry saves remain synchronous.
- The form edits existing stored account/identity fields; it is not an arbitrary JSON key or
  identity creation/deletion tool. Imported nested JSON is shown as JSON rather than a
  provider-specific form. Internal usage counters, list IDs and visibility are preserved,
  not presented as credential-edit fields. Existing PIN/privacy actions remain separate.
- Legacy-only transport records retain their boolean flags; they are not silently rewritten
  to a different schema on open. No vault or backup format change is introduced.
