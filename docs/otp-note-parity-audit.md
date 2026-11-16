# OTP and note parity audit

Scope: Android, the shared Windows/macOS/Debian desktop client, and iOS. This is a
source/test audit, not evidence of native UI or device execution.

## Corrected in this increment

- Android's OTP/note Edit button and popup Edit now open the same `ManualEntryDialog`
  Compose form as desktop. Both use `ManualEntryInput.forEntryEdit` and `build`.
  This retires the legacy Android editor from these edit paths: it made OTP seeds
  read-only, disabled algorithms on edit and omitted mOTP PIN controls.
- The shared form exposes issuer, label, Hidden Note/OTP seed, Hidden Label,
  supported OTP type, algorithm, digits, period/counter, mOTP PIN, tags and artwork.
  Changing OTP type clears secret/PIN and resets timing; cross-category conversion
  remains forbidden. mOTP's fixed six-digit/ten-second/MD5 algorithm is described,
  not presented as configurable. Existing credentials are visible only in the
  explicitly opened editor and are not saved in UI restoration state.
- Android callbacks recheck exact entry/session/editor identity and current reveal
  state. Lock, reload, dismissal and replacing an editor revoke its authority.
  Failed saves retain the original. Shared mOTP PIN deletion no longer falls through
  the legacy Android replacement rule that automatically restored the old PIN.
- Existing unknown imported artwork names can be retained without failing an edit.
- iOS editor now offers OTP-only type changes, Hidden Note/Hidden Label naming and
  artwork selection; unchanged artwork is retained. Tags now use one-per-line input
  so comma-containing tags are preserved, matching the shared Kotlin form. Edits reject a changed/reordered
  target instead of writing blindly to a stale index. Creation retains automatic
  issuer-based artwork unless an explicit choice is made.

## Code sharing

Android and all desktop builds now share the actual OTP/note edit UI, input model,
validation and core `EntryDraft` construction. Their session/lifecycle adapters and
persistence remain platform-specific. Android creation now also uses the shared form and
`ManualEntryInput.build`, handing validated owned drafts to the existing guarded import/save
transaction. QR/URI import keeps its separate existing import path. iOS uses SwiftUI and its
Swift `EntryDraft` port; it cannot directly reuse Kotlin widgets. Its catalog is generated from
Kotlin and format behavior is checked against shared golden fixtures.

## Follow-up corrections

- iOS Hide/Reveal is now session-only; projected display entries carry visibility without
  changing vault bytes. Session visibility survives usage saves and is cleared on lock.
- Hidden OTPs and notes offer Copy Token/Copy without revealing their contents. iOS uses fresh
  token calculation at copy time, displays the previous TOTP/Steam token when revealed, and
  offers inline Hide/Edit/Delete controls. HOTP copying no longer advances its counter;
  advancement remains an explicit action. Delete is absent while concealed.
- iOS OTP/note callbacks check the original entry contents and session generation; deletion
  confirmation rechecks these as well. Editor saves also recheck opening generation and all
  stored fields, not merely duplicate-equivalence. Optional PIN/Hidden Label removal is explicit.
- Android and iOS now require successful usage persistence before publishing copied OTP/note
  contents, matching desktop. Android saves cloned candidates and only then adopts usage
  metadata, rejecting stale sessions and recovery-required writes without copying.

## Validation boundary and intentional platform differences

Android/desktop edit saves remain synchronous on both platforms; moving these to background
transactions is an outstanding performance task, not completed by this parity work. iOS uses
native SwiftUI controls instead of Compose. Android retains an explicit user preference for
previous-token display; all clients show it by default where applicable. mOTP and HOTP have no
previous time-step token. Device accessibility, gesture behavior, actual clipboard integrations,
and Xcode compilation still require native validation. The added Swift XCTest is not executed
by the sandbox; Python guards only check wiring.

Usage timestamps/counts, list identity and concealment metadata are preserved on
edit, not exposed as user-configurable credentials. No vault/backup format changes
are made. Device/desktop tests must verify keyboard/touch edits, cancellation, lock
while editing, PIN removal, type conversion, save/reopen and clipboard masking.
