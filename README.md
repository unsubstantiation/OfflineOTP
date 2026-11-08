![OfflineOTP](./assets/logo.png)

# OfflineOTP

OfflineOTP is a source-available Android authenticator for time-based (TOTP), counter-based
(HOTP), Steam Guard, and mOTP one-time passwords. It supports Android 9 (API 28) and later. It
is a Kotlin rewrite of the upstream andOTP project with hardened vault, backup, and IPC handling.
The current build is `versionName 0.9.0.1` (versionCode 39).

A pure-JVM **desktop client** (Swing) lives in the `:desktop` Gradle module and shares the
Android-free `:core` engine and password-backup codec. Use exported backups to transfer accounts
between distributions; copying an Android app-data vault is not a substitute for a backup. See
[Desktop client](#desktop-client) for its scope and build instructions.

The user interface is moving to a shared **Compose Multiplatform** screen. The `:ui-model` module holds
the toolkit-free frame layer — what a row shows, the shared filter transitions and the tab/sort
wording the Swing client already renders from; `:ui` holds the Compose screen, its Dark Forest design
tokens, the unlock screen and the `VaultComposeView` used by Android MainActivity, and
`:desktop-compose` runs that screen over the existing desktop engine as a preview
(`./gradlew :desktop-compose:run`). That host now honors the saved inactivity timeout and clears
its session on window close/disposal, and supports manual entry creation/editing through the shared
validated form; full desktop feature parity is still pending. The Swing client already paints each row from that frame model
(`VaultCards.of`), so the desktop and the Compose screen group codes, warn about expiry and pick tags
the same way. Android MainActivity now renders the shared Compose cards while retaining its
existing toolbar, tabs, tag drawer and action dialogs. Manual ordering uses up/down controls.
Legacy renderer cleanup, display-preference parity and real Android build/device validation remain
open in the [Compose UI plan](docs/compose-unified-ui-plan.md); the shipping desktop UI is still Swing.

The Android application ID, namespace, and Kotlin/Java packages use `com.fairuniversal.offlineotp` (debug `com.fairuniversal.offlineotp.dev`), so OfflineOTP installs **alongside** the upstream andOTP app instead of being detected as its update. Updating an existing OfflineOTP installation still requires the same signing key, and it does not collide with andOTP.

## Features

- Add accounts by scanning a QR code, importing a QR image or `otpauth://` URI, or entering details manually. Google Authenticator export QR codes are supported, including multi-code exports.
- Keep the vault encrypted with **AES-256-GCM**, backed by either Android KeyStore protection or a password (Argon2id v1.3: 64 MiB memory, 3 iterations, 4 lanes; existing legacy PBKDF2 / AES-128-GCM vaults continue to open).
- Create password-encrypted (`.json.aes`, Argon2id v1.3 + AES-256-GCM; existing PBKDF2 backups continue to restore) or OpenPGP-encrypted backups, including automatic encrypted backups.
- Generate TOTP (RFC 6238), HOTP (RFC 4226), Steam Guard, and mOTP codes; copy them or share them as keystrokes.
- Store standalone secure notes, SSH keys, browser-exported passwords, LinSCP SSH keys & saved sessions, and FairEmail/Thunderbird mail accounts in the encrypted vault; browse them in separate OTP, Notes, SSH Keys, Passwords and Email tabs.
- Experimentally access compatible hardware-key OATH accounts and use a compatible FIDO2 key as an alternative vault unlock; device testing is still required.
- Search within the selected tab or filter by tags, and see per-tag entry counts on all three clients (Android's drawer rows, the desktop **Tag** dropdown and filter panel, the iOS tag chips). On Android, search temporarily overrides the tag filter; on desktop, search and tag filters work together. The UI uses the Dark Forest theme on both clients, with the one-time codes shown in amber.
- Stay offline: the app declares no `INTERNET` permission.
- Show a bundled brand logo per account, resolved from the issuer name (editable per entry on Android); unknown issuers get a letter tile. Debian and Windows use the same artwork as the phone.

## Brand logos

Each account shows a logo chosen from its issuer name when the app creates or imports it. A match
ignores capitalisation, punctuation and spacing, so `Login.gov`, `login.gov`, `GOV.UK` and
`digital ocean` reach the same marks as `LoginGov`, `GovUK` and `DigitalOcean`, and a few
unambiguous abbreviations are recognised (`AWS`, `Azure`, `GCP`, `1Password`, `Tuta`). Other
issuers show a letter tile until you pick a logo from the entry's **Edit** menu. Saving an edit
re-resolves the logo from the issuer name, so a hand-picked logo for an unknown brand returns to
the letter tile.

Android and desktop share one artwork set: the desktop build copies the same
`thumb_*.xml` vectors from `app/src/main/res/drawable` onto its classpath and rasterizes them with
Java2D, so a card on Debian or Windows shows the same mark as the phone. The name-to-file table in
`:core` is generated by `scripts/branding/generate_thumbnail_assets.py`; rerun it after adding a
mark to the Android enum.

The marks are bundled from Simple Icons and similar CC0/public-domain collections, with the source
noted in each `app/src/main/res/drawable/thumb_*.xml` file. Every brand remains the trademark of
its owner; the logos identify the service an account belongs to and imply no endorsement.

## Hardware keys (experimental)

Unlock the app and open **Hardware keys** from the main menu. The feature uses YubiKit 3.2.1, with
the USB/NFC transport held at 3.1.0 (the newest release that accepts the pinned `compileSdk 36`),
for USB and NFC. The phone must support the chosen connection; grant USB access when requested, or hold
the key in the NFC field throughout the operation. If a system permission screen interrupts an
operation, retry after granting access. Enter the key's OATH password or FIDO PIN in the field when
required; these are separate from the OfflineOTP password.

- **Key-held OTPs:** Show OATH accounts already on the key. Tap a code to copy it; touch-protected
  accounts and HOTP require an explicit calculation. Generating HOTP advances the key's counter.
  To provision a key, choose **Copy an app account to key**, select an existing TOTP/HOTP account,
  and optionally require touch if the key supports it. The app copy remains. The operation refuses
  an existing matching hardware ID rather than overwriting it. The picker omits unsupported digits
  and counters, and names that cannot be represented safely in a 64-byte YKOATH ID; edit a copy if
  needed. If a connection ends during a write, check the key before retrying; the result can be
  uncertain. Steam and mOTP are not OATH accounts.
- **Vault unlock:** For a vault using password authentication and password encryption, set up a
  FIDO2 key that supports the PRF/hmac-secret extension and user verification (PIN or built-in
  verification). One key is registered at a time; setting up another replaces it. On the login
  screen choose the key or enter the app password. Changing the app password or vault encryption
  removes the shortcut; an older non-exportable Android Keystore vault cannot use it. Removing the
  shortcut removes the local wrapper, not the credential from the key.

The key is an **alternative**, not a mandatory second factor. Keep the app password and a tested
backup: key unlock stores a wrapped vault key locally and is lost with the app's private data, while
backups remain password-protected. App-password automatic backups made after a key-only unlock
remain pending until a password unlock supplies the backup key. Hardware-held accounts are not
part of app backups. Copying an account sends its secret over USB or NFC to the chosen key; use a
trusted device and a private connection. HOTP counters on the app and key advance independently.

Compatibility depends on the key's actual protocols and firmware, not its brand. OATH accounts
require a compatible OATH application; FIDO2 alone does not provide OATH. A Nitrokey with a suitable
FIDO2 PRF implementation may work for vault unlock, but the separate Nitrokey Pro native OTP
protocol is not implemented. Other FIDO2 keys may lack PRF, user verification or an applicable
transport. No Android build, emulator or physical-key interoperability test was available for this
implementation; verify on your devices before relying on it.

## Desktop client

The `:desktop` module is a Swing application for desktop JDKs (JDK 17+). It reuses the shared
`:core` module for the vault, OTP calculation, parsers, backups and hardware-key protocols, and
all portable logic stays headless and unit-tested; Swing is only a thin shell. Desktop builds are
fully portable: the vault (`secrets.dat`), its `.bck` copy, the client settings and the
security-key unlock record live in a `data` folder **inside the program folder**, never in the
user's profile. Move or copy the OfflineOTP folder and the vault comes along, so a USB stick or a
second machine works without a migration step — keep the folder private, since the vault is only
as safe as the key you unlock it with. The program folder is found from the jpackage launcher,
then from the running jar, then from the directory you launched in; pass
`-Dofflineotp.home=<folder>` (or set `OFFLINEOTP_HOME`) to keep the data somewhere else, for
example when the program folder is read-only. An unwritable program folder is reported at startup
rather than silently falling back into your home directory. Windows and Debian share Android's Dark
Forest palette, with an
app bar, the same bundled issuer logos, entry cards that lift on hover, dark controls and dialogs.
Codes are drawn in amber and turn red as the code is about to change; native window decorations
may still differ by platform. The rules the two clients share — usage counting and last-used stamps,
the HOTP step, tag matching and tag order, which tab an entry belongs to, whether an entry can be
shown as a QR code, how a restore merges or replaces, and how a manual-entry form becomes an entry
(including which values it accepts and what it does with a pasted secret) — are single `:core`
implementations used by both, not two copies.

Features:

- Create and open a local password-encrypted vault; use **Add entry** to add an account, secure note, SSH key, email account or QR image; edit and delete entries, search the vault, and watch tokens refresh each second.
- Choose the list order with the **Sort** dropdown: **Unsorted**, **Issuer**, **Label**, **Last used** or **Most used** — the five modes of Android's sort menu. The choice is remembered in the client settings file and survives a restart; an imported entry is stamped as just used, so it leads a **Last used** list. Sorting combines with search and the tag filter inside each tab.
- Reorder entries by hand in the **Unsorted** view: select a row and use **Move up**/**Move down** in its menu, or Alt+↑/↓. The order is saved in the vault, and choosing any sort order disables moving, as Android's drag handle does.
- **Show QR code…** in a row's menu renders that entry's `otpauth://` URI for another device to scan. Notes, SSH keys, password entries and email accounts have no QR form, so the item is hidden for them, exactly as on Android.
- **Delete** asks for confirmation, naming the entry, before anything is removed — as Android does — and both clients stop adding entries at the same 2000-entry ceiling. An active search keeps applying when you change tab, tag or sort order on both clients.
- Assign tags to any revealed entry using **Tags…**, or choose tags when adding or editing an account, note, or SSH key. Use the **Tag** dropdown to show all, untagged, or one tag; it works alongside search within each tab. Counts include entries across all tabs. Tag names in the dropdown are visible even when their entries are hidden. Tags are saved in the encrypted vault and included in backups.
- Copy a token with the **Copy selected** button, a card double-click, or the row context menu. Copying records last-used time and usage frequency and re-persists the vault; the mOTP no-PIN placeholder is never copied. HOTP counters advance through an explicit context-menu action, matching the Android counter chip.
- Manage FairEmail-compatible mail accounts in the **Email** tab: add one by hand, view its connection details with the passwords masked until you ask for them, and import or export FairEmail backups. The same entries are created by Android's **Add email account** action.
- Import accounts from QR **image files** only — there is no camera or screen capture. Google Authenticator multi-image exports are buffered and only committed once the whole batch is present, with duplicate-issuer confirmation.
- Plain-text (unencrypted JSON) backup import has been removed on all clients; only **Full Encrypted Backup** and **OpenPGP** backups are offered, matching Android's retained restore-only path removal.
- Save and restore Full Encrypted and OpenPGP backups — the unified Rust backend (`rust/offlineotp-openpgp`, rpgp 0.21 + UniFFI, 5/5) is the default desktop engine and is packaged for Android/iOS by CI. PGPainless (`org.pgpainless:pgpainless-core:2.0.4`) remains only as a desktop fallback when the native library is absent, and Android's external OpenKeychain provider path remains supported. Every per-platform workflow runs `cargo test` and builds/packages the native library. New exports are suggested with the same dated name Android uses (`otp_accounts_<timestamp>.json.aes`), so a second export never overwrites the first, and a new backup password is typed twice behind a **Show password** toggle so a typo cannot lock you out of your own file. The headerless format written before andOTP 0.6.3 is no longer restorable. All file reads are bounded.
- Experimental USB security-key support over **PC/SC only** (no raw HID, no NFC): list and calculate OATH accounts, copy a vault entry to a key, and enroll/use a FIDO2 PRF envelope as an alternative vault unlock. Key operations run on background threads with cancellation; provider error text is never surfaced.
- Deliberately out of desktop v1 scope: NFC, camera, screen capture, settings editor, inactivity lock, and password change.

### Secure notes

On Android, tap **+** → **Add secure note**. On desktop, choose **Add entry** → **Add secure note**.
On smaller Android screens, scroll the **+** menu to see all actions. Android's **Enter details** and
desktop's **Enter account details** also offer the type selector. Use the label as the note title and
enter 1–200 Unicode characters in
the secret field; spaces and newlines are preserved. Notes stay concealed until you reveal one, and
then the card shows the note in full, wrapped over as many lines as it needs. Open **Edit** to change
one, or use the copy action to place it on the clipboard. Clipboard contents may be visible to other
applications or clipboard history. Notes are not OTPs, cannot be exported as OTP
QR codes, and are not sent to hardware keys. They are included in encrypted vaults and backups.

Both distributions must be updated to a version that supports notes before transferring a backup
containing them. Older versions cannot read the new `NOTE` entry type and reject such a vault or
backup; keep an independently tested backup before upgrading or transferring. Existing OTP-only
files retain their previous format when entries are hidden.

### Browser password imports

Export passwords from Chrome, Firefox or Safari as a **UTF-8 CSV** file, then choose **+ → Import
passwords** on Android or **Add entry → Import passwords** on desktop. Choose either:

- **Individual passwords:** the CSV is parsed into separate vault entries. Source columns, including
  notes and Safari OTPAuth fields, are retained in each entry; reveal a card to copy the password or
  use its menu to view its fields. Identical entries are skipped. Every entry keeps its file's own
  dialect (Chrome, Firefox or Safari), so it can be edited and written back out: **Edit password…**
  changes the URL, username and password and leaves notes, GUIDs, timestamps and OTPAuth untouched,
  and **Export as … CSV…** (Android: *Export as `<browser>` CSV…* in the entry menu, plus
  **+ → Export passwords…** for all of them; desktop: the same entry-menu item and **Tools → Export
  passwords as browser CSV…**; iOS: **Export as `<browser>` CSV…** in the entry menu) writes the
  selection as a CSV the same browser imports. Entries that did not come from a browser — a LinSCP
  session, for example — have no browser CSV form and are refused rather than written with their
  columns dropped; use the LinSCP or Thunderbird export for those.
- **Keep original file:** the file is stored byte-for-byte as a single encrypted vault entry without
  parsing or converting it. Reveal it and use **Export password file** in its menu to save the
  original bytes for import into another program. This option accepts any file up to the limit.

Files are limited to 4 MiB; individual imports are limited to 2,000 rows and an 8 MiB combined decoded payload, and the whole vault is
limited to 2,000 entries and its existing document-size bound. Individual imports reject malformed
CSV; both modes reject oversized files or failed vault writes without committing a partial batch.
CSV support requires the browser's standard
headers, including URL, username and password; files with unsupported headers are rejected.

### Thunderbird email login credentials

Thunderbird keeps each saved email login as a hostname (e.g. `mailbox://`, `imap://imap.gmail.com`, `smtp://smtp.gmail.com`) plus a username (the email address) and a password, exportable from Thunderbird/Firefox as a CSV with `url` (or `hostname`), `username` and `password` plus optional `httpRealm`, `formActionOrigin`, `guid` and timestamps — the same format Firefox's **Export Logins** writes. OfflineOTP imports and exports that CSV on both clients: on Android **+ → Import Thunderbird credentials** and **+ → Export Thunderbird credentials**, on desktop **Tools → Import Thunderbird credentials…** and **Tools → Export Thunderbird credentials…** (the **Add entry** menu also has the import). Thunderbird and FairEmail now share the same **Emails** vault type (`EmailMaterial.Account`): import reads either `url` or `hostname`/`host`, parses the scheme/`mailbox://` host, default ports and encryption, groups rows by username into one account per address (first `imap`/`pop` as incoming, `smtp` rows as outgoing identities), and stores them as `EMAIL` entries indistinguishable from a FairEmail import — so an account imported from FairEmail can be exported for Thunderbird and vice versa without loss beyond inferred defaults. Export writes the selected **Emails** entries as a Thunderbird-compatible CSV with the canonical header `url,username,password,httpRealm,formActionOrigin,guid,timeCreated,timeLastUsed,timePasswordChanged` (incoming `imap|pop://host:port` plus one `smtp://host:port` row per identity, with GUIDs). Old vaults with PASSWORD-stored Thunderbird logins remain exportable via the legacy path. The 4 MiB / 2,000-row limits, per-import 8 MiB payload bound and plaintext warning match the browser import.

**Browser exports and exported archive files are plaintext.** Anyone who can read the file can read
its passwords. Transfer them securely and remove unneeded plaintext copies. Passwords and archives
are encrypted in the vault and included in encrypted backups; revealing details or copying a password
can expose it to observers, other applications or clipboard history. These new entry types are not
readable by older app versions: update both devices before transferring backups, and keep an
independently tested backup. Browser OTPAuth values are retained as password metadata, not imported
as OTP accounts.

### Email accounts (FairEmail)

OfflineOTP keeps mail-server credentials as a vault entry type of its own, in the style of the
FairEmail account backup. One entry holds a single IMAP or POP account plus its SMTP identities:
for each connection, the host, port, encryption (SSL, STARTTLS or none), FairEmail authentication
type, user name and password. Add one on Android with **+ → Add email account**, or on desktop with
**Add entry → Add email account…**; both use the same fields and the same validation, so an address
without `@`, a port outside 1–65535, a protocol other than 0 (IMAP) or 1 (POP), a non-numeric field
or a blank host is refused instead of stored. Entries appear in the **Email** tab on both clients.
Tags work like every other entry, and encrypted backups carry the account.

**Connection details.** Select an email card and open its menu → **View connection details**
(Android) or **View email details…** (desktop) to read the account back. Both clients render the
same rows. The desktop dialog masks the stored passwords behind a **Show passwords** toggle; the
Android dialog shows the values directly, like the other details dialogs there. Concealed cards show
only the issuer and label.

**Import and export.** FairEmail's own file backup can be imported on either client: Android **+
→ Import FairEmail backup**, desktop **Tools → Import FairEmail backup…**. The reader accepts the
plain JSON restore file, the gzip-wrapped file FairEmail's backup agent writes, and the
password-protected exports (both the current format and the older one); a password prompt appears
only when the file does not parse without one. Oversized files (over 8 MiB), more than 2,000
accounts, missing identities, impossible connection values or missing credentials reject the whole
file — nothing is imported from a partial batch, and an identical existing account is skipped.
The older FairEmail v0 export is still written by upstream and is read through a narrow,
documented [compatibility exception](docs/vault-security-hardening.md#fairemail-backup-compatibility-exception);
it is unauthenticated, so import it only from a trusted source. FairEmail v1 imports use AES-GCM.

**Export FairEmail backup** (Android **+**, desktop **Tools**) writes the selected accounts as
FairEmail's unencrypted JSON restore file. Pick the accounts, then save: the file is plain text and
contains the account passwords, which is why both clients warn before writing it. Exported files are
compatible with FairEmail's passwordless file restore; OfflineOTP always writes them unencrypted.

Email entries are structured credentials, not OTPs: they have no token, QR export, keystroke
sharing or hardware-key copy, and they cannot be edited in the entry editor on either client —
delete an outdated account and add or import it again. Revealing one never exposes it to the
clipboard. As with the other newer entry kinds, an older release that does not know the `EMAIL`
type rejects a vault or backup containing one: update both clients before transferring, and keep an
independently tested backup.

### Hiding entry details

Each Android card has an eye button; the desktop card has a **Show** checkbox. On desktop, select
a card and press Space to toggle it; right-click or press Shift+F10 for the card menu. New entries and
older entries without a saved choice start hidden. A hidden row still displays its issuer and label,
but not its token, tags, counter, note contents or SSH keys — and on desktop the OTP **Type** (TOTP etc) is not shown even when revealed, only while editing; reveal an entry before copying or editing. OTP entries can store an optional **Hidden label** that behaves like **Label** but is shown only when the entry is revealed (and hidden otherwise, including from search when concealed); add or edit it in **Enter details** / **Enter account details** (OTP types only, up to 1024 characters). On Android, the separate **Hide issuer** setting also applies to hidden rows (an entry with no label will then have no identity text). Each choice is saved in the encrypted vault and included in portable backups, so a revealed entry remains revealed after a restart or restore. Older releases ignore this choice and may show details; do not rely on it when opening a backup in an older app. If a visibility change cannot be saved, the entry stays hidden for the current session, but its previously saved state may return after restart.

This is visual concealment, not screenshot protection: revealed entries, dialogs, copied data,
titles, searches and other app screens may still expose information. Android blocks screenshots
and screen recording by default, which is the additional protection here; the Swing desktop has no
equivalent portable screenshot-blocking guarantee.

On Android the app switcher normally keeps a preview of the last screen, like any other app. Turn
on **Hide in the app switcher** in Settings to replace that preview with a blank card. It is off by
default, and screenshots stay blocked while the app is open either way. The setting covers the
vault, settings, backup and unlock screens; the QR scanner always blanks its own preview because
it shows the camera. Allow screenshots only if you accept that anything on screen can be captured.
The desktop client does not control the operating system's window thumbnails.

### SSH key entries

On Android, tap **+** → **Add SSH key**. On desktop, choose **Add entry** → **Add SSH key**.
Give the entry a label and paste a public key, a private key, or both in their separate multiline
fields. Each field is limited to 32 KiB of UTF-8 text, independently of the note limit.
Whitespace and line breaks entered in the fields are preserved. Open **Edit** to view or change
keys; the list hides their contents. The copy action asks which key to copy, even if only one is
present. Clipboard contents may be visible to other applications or clipboard history.

Each SSH entry can also store the connection it belongs to: **Host name**, **Port**, **User name**
and **Password**. The port is optional and blank means the SSH default 22; anything else must be a
whole number from 1 to 65535, and a typo is rejected rather than quietly replaced by the default.
These details live beside the keys inside the encrypted vault and its encrypted backups, and a
revealed card shows the target as `user@host:port`. The password is treated as a secret just like
the keys: it never appears on a concealed card, the copy menu offers it as **Password** only when
the entry is revealed and actually has one, and it is not exported to OTP QR codes. Leave the four
fields empty and the entry is stored exactly as before, so older builds still read it; a backup
that contains them needs updated clients on both sides.

The app stores the text as entered: it does **not** validate keys, check that the two keys match,
generate keys, or provide an SSH agent. Encrypted private-key files remain encrypted with their
original passphrase; the passphrase is not managed by this entry. SSH keys are included in vaults
and encrypted backups but cannot be exported as OTP QR codes or copied to an OATH hardware key.
Both Android and desktop must be updated to a version that understands `SSH_KEY` before restoring
such backups. Older versions reject them; keep a separately tested backup before transferring.

### LinSCP SSH keys and saved sessions

OfflineOTP supports bidirectional encrypted credential interchange with [LinSCP](https://github.com/unsubstantiation/LinSCP) across Android, desktop, and iOS:

- **Import from LinSCP**:
  - **LinSCP `LSCPVLT2` encrypted credential vault (`.json.aes`)**: exported from LinSCP via **Options → Credential vault → Export** (Argon2id v1.3 `64 MiB, t = 3, p = 1` + AES-256-GCM with `"LSCPVLT2"` AAD). Imports both `SSH_KEY` entries (main session keys and jump-host keys, automatically enriched with their linked session/jump-host connection target and login password) and saved `PASSWORD` session entries (`linscp_session_json`, `linscp_ssh_key_label`, `linscp_tunnel_key_label`). Standard encrypted backup restore also opens `LSCPVLT2` `.json.aes` files directly.
  - **LinSCP `BackupBundle` JSON (`.json`)**: imports saved SFTP/SCP/SSH/FTP/FTPS/WebDAV/S3/RDP/VNC/SPICE/X2Go/X11 sessions and decrypts `lscp1$` saved-session passwords using `sessions_key_b64`.
  - **LinSCP `sessions.ini`**: imports saved `[SiteName]` sections directly (with `sessions.key` decryption when `lscp1$` encrypted passwords are present).
- **Export to LinSCP**:
  - **Android** (**+ → Export to LinSCP vault**), **desktop** (**Add entry** / **Tools → Export to LinSCP vault…**), and **iOS** (**Backups and links → Export to LinSCP encrypted vault**) write selected `SSH_KEY` and `PASSWORD` entries into a `LSCPVLT2` Argon2id + AES-256-GCM `.json.aes` file ready for **Options → Credential vault → Import** in LinSCP.

### Transferring backups

Password-encrypted `.json.aes` backups use the same framing, password derivation and entry JSON on
Android (both flavors) and desktop. Export on one device, transfer the file securely, and restore
on the other using the original backup password. Restoring a backup created before andOTP 0.6.3
(the headerless SHA-256 format) is no longer supported on either platform: open such a file with an
older release that still reads it, then export a new backup. Both clients ask the same question
before restoring: with **Replace existing entries** off (the default) the backup is merged into the
current vault, its new entries first, while entries the vault already holds keep their counters and
metadata; turn it on to replace the vault with the backup contents.
Keep a separate, tested backup before restoring. Desktop rejects backups containing duplicate
account identities (even when their tags or usage metadata differ) rather than silently dropping
one. HOTP counters in different copies do not sync. Hardware-key-held accounts are not included, and
a backup carries no device settings — use **Export preferences…** below for those.

OpenPGP backups use the same entry JSON and standard OpenPGP encryption, but provider and key
compatibility must be tested on the actual devices. Desktop exports are encrypted but **unsigned**;
Android's optional signature verification will reject them when enabled. Desktop currently does not
verify OpenPGP signatures, including those on Android exports. Do not treat an OpenPGP restore on
desktop as proof of who created it. The unified Rust backend (`rust/offlineotp-openpgp`, rpgp 0.21 +
UniFFI) closes the previous provider gap (Android external vs desktop embedded vs iOS none) with one
implementation for all five distributions: desktop defaults to it, Android CI stages per-ABI
`jniLibs` while retaining OpenKeychain, and iOS builds an XCFramework. Exports remain unsigned and
no client verifies OpenPGP signatures on restore. Password backups are the simpler interchange path.

Verification limits: the offline sandbox compiles every desktop source against narrow stubs and
runs the headless suite under `java.awt.headless=true`, including lightweight Swing theme and
privacy checks. Display tests are opt-in on a host with a real or virtual display:
`./gradlew :desktop:test -Dofflineotp.uiTests=true --tests '*MainWindowDisplayTest'`.
Gradle builds, real PC/SC keys, jpackage packaging and lint require a networked host with a full
JDK. See [Desktop packaging and release](scripts/packaging/README.md).

### Moving your preferences to a new device

The vault is the accounts; the settings are everything else — theme, language, auto-lock delay,
screenshot and autofill policy, the backup password. **Export preferences…** writes them as a small
JSON document (`offlineotp.preferences`, version 1) that the same client can re-import, so a new
phone starts from your configuration instead of the defaults where that is meaningful:

| | where | what it asks |
|---|---|---|
| Android | Settings → **Export preferences…** / **Import preferences…** | whether to include the backup password |
| Desktop | **Tools → Export preferences…** / **Import preferences…** | nothing — the desktop stores no backup password |
| iOS | Settings → **Preferences** → **Export preferences…** / **Import preferences…** | nothing — the delay is the only portable setting |

The backup password is the one secret a file can carry, and only when you ask for it: the export
dialog says plainly when the file will hold a password, and importing one sets your backup password
from the file. Everything bound to a device stays on it and is **not written, nor even named** in the
file — the wrapped backup password, the vault's salt and KDF parameters, OpenPGP key-file grants and
the hardware-unlock envelope. Keying an import with a file that lacks your salt cannot weaken
anything, because a file that names the salt is not one this app writes.

A preferences file belongs to one client. The three apps do not share a key set (Android's
`pref_*` names, the desktop's `ui_*`), so importing a desktop file on Android — or the reverse — is
refused outright with a message saying which client it came from, before anything is written. Moving
settings between platforms needs a real mapping of each setting's meaning, which does not exist yet;
silently applying the one or two names that happen to collide would be worse than a refusal.

Settings this version does not know are reported as skipped, never guessed at. On Android the file
is picked through the Storage Access Framework and the import is validated against a no-write trial
first, so a corrupt document leaves the vault untouched; the serialised bytes are zeroed once the
file is written or the picker is cancelled.

## Importing from Google Authenticator

1. In Google Authenticator, choose **Transfer accounts** → **Export accounts**, then select the
   accounts to export.
2. Unlock OfflineOTP, tap **+** → **Scan QR code**, and scan the export. You can also choose
   **QR code from image**. If Google shows multiple codes, scan or select each code from the same
   export when prompted; they can be scanned in any order.
3. Review the account names and confirm the import. Entries with matching OTP settings, names and
   issuers are skipped; other accounts with matching issuers can still be added. Check that the
   imported codes work before removing anything from Google Authenticator. HOTP counters advance
   independently on each device, so avoid using both copies interchangeably.

The importer supports TOTP and HOTP, SHA1/SHA256/SHA512, six, seven or eight digits, and the exported
HOTP counter. TOTP uses the format's 30-second default. Unsupported fields or parameters reject
the QR rather than silently changing the codes. If the vault locks or the activity is destroyed before
confirmation, start the export import again; unfinished batches are not saved.

On iOS, open **Backups and links** and choose **Add an entry from a QR image…**, then pick a screenshot
or photo of each code from the same export; the sheet says how many codes have been read and imports
the accounts together once the last one is in. The desktop client reads an export from a QR **image
file** the same way, buffering the codes of one export until it is complete.

**Export QR codes and their images contain unencrypted account secrets.** Do not share them or
upload them to a QR-decoding website; delete any temporary images you no longer need. Matching
batch IDs help avoid mixing exports but do not authenticate the source. The format is not an
officially published Google API and may change.

## Protecting and restoring your vault

Use the in-app backup and restore functions. Android cloud backup and device transfer are
disabled, and copying app data with third-party backup tools does not preserve the separate
KeyStore key. Export a portable backup and verify that it restores before changing devices,
reinstalling, clearing app data, or switching to a build signed with a different key. A signing
key change requires uninstalling the old build and can erase its data.

Paper will not be added as a backup medium: the encrypted file is already the offline copy, and a
printed sheet of per-entry QR codes is a factor anyone who finds it can use. See
[the fourth-pass audit](docs/incomplete-feature-audit-2026-11-06-fourth-pass.md) for the reasoning.

For automatic backups, choose a backup folder and enable **Automatic backup** in Settings. With
password login, **Use app password for encrypted backups** uses that password for automatic and
manual password-encrypted backups without storing the password. If the session key is unavailable,
manual export asks for a password. A password-encrypted vault and this backup path do not require
Android KeyStore; existing KeyStore-backed vaults still do. Choose **New entries** or **Any edits**;
no-op edits do not create snapshots. Changed imports trigger backups in either mode; HOTP counter
changes trigger them in **Any edits** mode. Usage statistics and manual reordering do not trigger
backups. Automatic backup is off by default.

Existing separate backup passwords remain selected for compatibility until explicitly changed.
Device authentication does not supply a login password for this mode; use a strong separate
backup password or choose password login. OpenPGP backups use their selected recipients instead.
Previously created backups keep their original passwords. **Keep automatic backups** in Settings
(default *Keep all*) can prune old automatic snapshots, keeping the newest 5, 10, 20 or 50 and
deleting the rest after each successful automatic export. Only files this app names
`otp_accounts_<uuid>.json.aes` / `.json.gpg` are candidates — a dated manual export, the
`otp_accounts.json.aes` file and anything else in the folder are never touched, nothing is deleted
for a backup the user asked for, and a provider that refuses a delete leaves the file in place. Old
snapshots can still contain accounts you later delete. Always test restoring backups and keep track
of their passwords. Overlapping exports may finish out of order, so the
provider's last-modified order does not always identify the newest vault state.

A stolen backup permits offline password guessing. Use a strong, unique passphrase; no design can
protect secrets completely on a compromised device or guarantee erasure of every memory copy.
Interrupted automatic backups are retried after unlocking when possible; storage/provider failures
and process crashes can prevent or occasionally duplicate an export. Optional same-signature backup
broadcasts only retry pending legacy backups, not unchanged vaults. See
[Vault security hardening](docs/vault-security-hardening.md) for details and limitations.

## Build

The project uses Gradle, the Android Gradle Plugin, Kotlin, and the Android SDK (JDK 17,
compile SDK 36, build-tools 36.0.0). Build the fdroid and play APKs in debug and release:

```sh
./gradlew --no-daemon :app:assembleFdroidDebug :app:assembleFdroidRelease \
  :app:assemblePlayDebug :app:assemblePlayRelease
```

Build the desktop distribution (platform-independent zip with launcher scripts and dependency
jars) or a native jpackage app image, on a full JDK 17+ with network access:

```sh
./gradlew --no-daemon :desktop:distZip
./gradlew --no-daemon packageDesktop
```

## Testing

Run the source guards and Gradle wrapper checks:

```sh
python3 -m unittest discover -s scripts/tests -v
python3 scripts/check_build_inputs.py
```

With the Android SDK and JDK configured, run the Gradle unit tests and lint:

```sh
./gradlew --no-daemon :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest \
  :app:lintFdroidDebug :app:lintFdroidRelease :app:lintPlayDebug :app:lintPlayRelease
```

`ComposeVaultDeviceTest` attaches the shipping `VaultComposeView` to a real ComponentActivity
and checks Compose semantics, frame/action replacement, terminal clear, privacy and artwork
loading. It does not use the historical XML renderer. The no-SDK instrumentation gate only
type-checks this suite; its UI assertions require Android execution.

For a focused CI run, manually dispatch the existing Android workflow on the branch under review
(`gh workflow run android.yml --ref <branch>`). Its Compose validation job builds real debug APKs
and runs the Compose-host/artwork tests on F-Droid/API 28 and Play/API 36 emulators. It requires
passing test reports, not merely successful compilation, and uploads logs/reports without APKs.
This focused matrix does not replace the full connected suite below.

For instrumentation tests, use a disposable connected emulator or device:

```sh
./gradlew --no-daemon :app:connectedFdroidDebugAndroidTest :app:connectedPlayDebugAndroidTest
```

The [Kotlin sandbox](scripts/kotlin-sandbox/README.md) provides partial host checks without an
Android SDK; it does not replace Gradle builds, lint, or device tests. The desktop module has its
own headless gate, which compiles every desktop source against compile-only stubs and runs the
desktop suite under `java.awt.headless=true`:

```sh
bash scripts/kotlin-sandbox/bootstrap-toolchain.sh   # one-time, needs pypi and npm access
bash scripts/kotlin-sandbox/compile-desktop.sh
```

The shared UI has its own boundary gate: it compiles `:core`, then `:ui-model` with no Compose
artifact on the classpath, then `ui/src/commonMain` against the compile-only Compose stubs in
`scripts/kotlin-sandbox/stubs-compose`, then runs the frame-layer tests, then type-checks both hosts.
It proves the shared sources need no Android, Swing, storage or session API; it does not render a
single pixel.

```sh
bash scripts/kotlin-sandbox/compile-ui.sh
```

With Gradle, `./gradlew --no-daemon :ui:desktopTest` runs the same projection tests against the real
Compose artifacts, and `./gradlew --no-daemon :desktop-compose:run` opens the Compose preview client.

The deterministic vault/backup fixtures can be checked with
`bash scripts/packaging/generate-golden-fixtures.sh`; the desktop suite also pins a real desktop
export for the Android instrumentation test. The reproducible desktop packaging check is
`bash scripts/packaging/verify-desktop-build.sh` on a networked full-JDK host.

## Build verification

Five GitHub Actions workflows build on pushes to `master` or manual dispatch — one
per platform, so a macOS regression cannot hide behind a green Linux job. Download their artifacts
from the workflow run: signed Android release APKs (F-Droid and Play, only on `master` when
signing secrets are configured), a Windows x64 app-image zip, a Debian 12 x64 app-image
tarball, a macOS arm64 app-bundle zip, and — for iOS — a single unsigned physical-device Release IPA
(`OfflineOTP-ios-unsigned`, one file: `OfflineOTP-ios-unsigned.ipa`). Android, Windows, Debian and
macOS include SHA-256 checksums; Android also includes the signing certificate details. iOS ships no
extra files — the `.ipa` is unpacked and verified inside the job instead of carrying a checksum
manifest. The workflows do **not** publish GitHub releases or run device tests.

The macOS bundle is ad-hoc signed, not Developer ID signed, so the first launch after download needs
**right-click → Open** (or `xattr -d com.apple.quarantine`) until a signing identity is configured.
The iOS client is a working daily-use app short of Face ID: `ios/` holds a Swift package that matches
the other two clients byte for byte — the encrypted vault, the encrypted backup, the entry document and
the token maths, verified against the same fixed vectors in
`desktop/src/test/resources/golden/` — plus a SwiftUI app with the same five tabs, a live search over
labels, issuers and tags, the same tag rules and the same per-tag entry counts (`tag (n)`) on the tag
chips, the same five orders (remembered beside the vault, never
inside it), per-entry reveal, a 30-second pasteboard expiry, an HOTP step, adding, editing and deleting
entries, dated Full Encrypted Backups, restores that merge by default or replace,
`otpauth://` and QR-image import, Google Authenticator export import (every code of a multi-code
export, committed together once the last one is in), optional Face ID unlock, and an entry shown as a
QR code. A concealed entry shows no code and offers no copy, exactly as on the phone and the desktop
client, an entry kind this build cannot display is still listed in its tab and explained rather than
hidden, and a second account from an issuer that is already there asks the phone's *Issuer already
exists* question first. The list speaks its codes one character at a time to VoiceOver and every control is a 44-point
target. **Face ID** is off until it is turned on in **Settings**, and turning it on
asks for the vault password: the app keeps the derived vault key in a Keychain item that is released
only after a successful check of the *currently enrolled* face and never leaves the device, so the
vault file stays encrypted with the password and a forgotten face costs only the shortcut. OpenPGP
backups are not there yet; that list, with milestones, is in
[the iOS client plan](docs/ios-client-plan.md). Its workflow tests both parts on a simulator and
builds an unsigned `iphoneos` Release `.ipa` (`OfflineOTP-ios-unsigned.ipa`) alongside the simulator
bundle with no signing identity: sideloaders (such as AltStore, SideStore, or Sideloadly) or local
Mac signing tools sign the `.ipa` on your own machine, while Apple Developer distribution and
TestFlight stay manual, as releases do everywhere else here. On a Mac,
`cd ios && swift test` runs the format tests and `xcodegen generate && xcodebuild … test` the app's.

The Android workflow additionally builds **signed** F-Droid and Play release APKs, but only when
the release keystore is configured as repository secrets (see [release signing](docs/release-signing.md)).
Without `ANDROID_KEYSTORE_BASE64` the `sign-release` job is skipped with a notice instead of
failing, so an unconfigured fork or a fresh clone still gets a green Android build with the debug
APK — but that also means **no signed release APK is produced**: the uploaded artifact and every
asset on a current release are debug builds, made with an ephemeral runner key under the `.dev`
application ID, which cannot update a release install. To enable signing, follow
[release signing](docs/release-signing.md) — the keystore, the two secrets, the key alias that is
read from the keystore itself, what to re-publish afterwards, and the verification steps; unsigned
release APKs are still built by `scripts/verify_reproducible_build.sh`. The workflow only uploads
artifacts; it does not create GitHub Deployments or releases — download the signed APKs from the
**Actions** run.

Two dependency pins are deliberate and should not be "upgraded" casually: the Windows and Debian
clients build for JVM 11 because the embedded OpenPGP engine declares a JVM 11 requirement —
currently `org.pgpainless:pgpainless-core:2.0.4` (JVM 11), retained only as a fallback.
The unified Rust backend (`rust/offlineotp-openpgp`, rpgp 0.21 + UniFFI, 5/5) is the default
OpenPGP engine when its native library is packaged; generated Kotlin/Swift bindings are committed
and CI builds the per-ABI artifacts (`cargo ndk` for Android, `XCFramework` for iOS, `cdylib` for
desktop) —
and the Android USB/NFC transport stays on YubiKit 3.1.0 because 3.2.x targets `compileSdk 37`
while AGP 8.13.2 supports 36; the JVM YubiKit modules remain on 3.2.1.

The APK uses the runner's ephemeral debug key and the `.dev` application ID. Different runs can
have different signing keys, so updating may require uninstalling the previous debug build, which
erases its vault. Export and verify a portable backup first. Desktop app images are unsigned,
require a compatible system with a graphical environment, and are not native installers.

For lint, device tests and release publishing, use the separate checks above. The dependency
inventory can be generated locally:

```sh
./gradlew --no-daemon :writeDependencyInventory
```

The report is written to `build/reports/dependency-inventory.txt`. For independent builds and
byte-for-byte comparisons of both unsigned release APKs and dependency inventories, set
`ANDROID_HOME`, put `apkanalyzer` on `PATH`, then run:

```sh
bash scripts/verify_reproducible_build.sh
```

## Documentation

- [Changelog](CHANGELOG.md) — release history.
- [Release signing](docs/release-signing.md) — opt-in signed APKs, keystore secrets and CI behavior.
- [Desktop packaging and release](scripts/packaging/README.md) — desktop distributions, jpackage, reproducible builds and sandbox verification.
- [Vault security hardening](docs/vault-security-hardening.md) — storage, session, backup, and IPC behavior.
- [Kotlin conversion record](docs/kotlin-extracted-helpers.md) — conversion scope and verification limits.
- [OTP URI parser](docs/kotlin-otp-parser-pilot.md) and [vault/session/task contracts](docs/kotlin-vault-session-task-contracts.md) — parser and contract boundaries.
- [UI decomposition plan](docs/ui-decomposition-plan.md) — completed slices and remaining extraction work.
- [Unified Compose UI plan](docs/compose-unified-ui-plan.md) — the shared `:ui-model`/`:ui` modules, what stage 1 covers, the cutover stages and their exit criteria.
- [Android/desktop parity review](docs/client-parity-2026-11-02.md) — the four client behaviours that differed and how they were aligned.
- [iOS client plan](docs/ios-client-plan.md) — the vault/backup format contract a native SwiftUI client must implement, the fixed-vector corpus it is verified against, and the milestones up to TestFlight.
- [Existing-feature audit](docs/feature-audit-2026-10-26.md) — completed fixes, remaining enhancement requests and device-test limits.
- [Incomplete-feature audits](docs/incomplete-feature-audit-2026-11-06-third-pass.md) — the third pass (naming the removed entry, iOS tag counts, translatable biometric wording); it links the [first](docs/incomplete-feature-audit-2026-11-06.md) and [second](docs/scaffold-audit-2026-11-06-second-pass.md) 2026-11-06 passes and the [2026-11-01](docs/incomplete-feature-audit-2026-11-01.md) pass, which closed the OpenPGP backend, three unreachable scaffolds and the desktop's at-rest permissions.
- [Language security assessment](docs/language-security-assessment.md) — historical baseline review.
- [Latest bug-hunt follow-up](docs/bughunt-2026-10-25-tag-drawer.md), [the earlier 2026-10-25 pass](docs/bughunt-follow-up-2026-10-25.md), [the previous pass](docs/bughunt-follow-up-2026-10-18.md) and [upstream andOTP issue triage](docs/andotp-open-issues-triage.md) — audit and upstream-issue records.

The [Fastlane seven-inch screenshots](fastlane/metadata/android/en-US/images/sevenInchScreenshots/)
are inherited from the upstream app and do not reflect the current OfflineOTP UI. Replace them with
current device screenshots before publishing store metadata.

## Contributing

Report bugs and request features through the [GitHub issue tracker](https://github.com/unsubstantiation/OfflineOTP/issues).
Search existing issues first. For bug reports, include the app version, Android version,
reproduction steps, and relevant logs; remove secrets and account data before sharing logs.

## Credits and license

Copyright (c) 2026 unsubstantiation — [http://fairuniversal.com](http://fairuniversal.com) and [https://github.com/unsubstantiation/OfflineOTP/releases](https://github.com/unsubstantiation/OfflineOTP/releases)

In-app **About** shows the same notice on both clients: Android **⋮ → About → About** displays
`Copyright (c) 2026 unsubstantiation — http://fairuniversal.com and https://github.com/unsubstantiation/OfflineOTP/releases`
with tappable **Website** (`http://fairuniversal.com`) and **Releases** (`https://github.com/unsubstantiation/OfflineOTP/releases`)
rows (plus Source, Changelog, License and Issues); desktop **Help → About OfflineOTP** shows
the identical copyright line with clickable **Website**, **Releases** and **Source** links that
open in the system browser or copy to the clipboard.

This project is licensed under the **OfflineOTP Custom Noncommercial License with Attribution
and Artificial Intelligence Restrictions, Version 1.0**. The complete, binding terms are in
[LICENSE.txt](LICENSE.txt). It is **not** an OSI-approved open-source license. In summary:

- **Noncommercial use only.** Commercial use requires a separate written license.
- **Attribution is mandatory.** Every copy, modification, and distribution must credit
  "unsubstantiation" and carry [http://fairuniversal.com](http://fairuniversal.com) in source
  headers, license/notice files, and a user-visible About-equivalent screen for binaries.
- **No AI copying or modification.** Only natural persons may copy or modify this code; doing so
  by, through, or with the assistance of any artificial intelligence system is prohibited.
- **No AI training.** The code may not be used as training, fine-tuning, or evaluation data, or
  in embeddings, retrieval corpora, synthetic-data generation, or model distillation.
- **Keep the notices.** The copyright notice, attribution, license text, and website link may
  not be removed or obscured. The software is provided "as is", without warranty of any kind.

### Attribution (prior work)

This project is a Kotlin update of andOTP for Android 9+, originally copyright (C) 2017-2020
Jakob Nixdorf and (C) 2015 Bruno Bierbaumer.
