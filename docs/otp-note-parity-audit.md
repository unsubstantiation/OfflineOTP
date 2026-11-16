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
persistence remain platform-specific. Android creation still uses its native dialog
with the shared core builder; migrating creation to the same Compose form is a
remaining consolidation task. iOS uses SwiftUI and its Swift `EntryDraft` port;
it cannot directly reuse the Kotlin widget without a larger architectural change.
Its catalog is generated from Kotlin and its formats have shared golden fixtures.

## Remaining inconsistencies found (not fixed by this editor increment)

1. iOS list visibility still uses persisted `detailsVisible`, unlike the new
   Android/desktop session-only Hide/Reveal. Its context-menu OTP/note copy actions
   still require visibility, and its OTP copy wording is still "Copy code".
2. iOS does not display the previous OTP beside the current token. Android respects
   its saved previous-token preference (default on); desktop defaults on.
3. iOS Remove remains visible-but-disabled while concealed; Android/desktop hide
   the Delete button. iOS also uses a context menu rather than the shared inline
   actions. These should be changed together with visibility/copy authority checks.
4. Android copy/usage persistence and desktop copy/usage persistence have different
   failure ordering. A broader clipboard/session audit is needed before claiming
   identical behavior under write failures. iOS list commands also rely on indices;
   this increment only adds stale-target protection to the editor.
5. Android and desktop edit persistence is still synchronous. Moving it to guarded
   background transactions is separate from form parity.

Usage timestamps/counts, list identity and concealment metadata are preserved on
edit, not exposed as user-configurable credentials. No vault/backup format changes
are made. Device/desktop tests must verify keyboard/touch edits, cancellation, lock
while editing, PIN removal, type conversion, save/reopen and clipboard masking.
