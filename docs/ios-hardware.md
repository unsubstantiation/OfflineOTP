# iOS hardware-key support: NFC and USB-C

## Active source route

Settings / Hardware / Security-key OATH accounts opens shared Compose portable surfaces. Explicit
NFC and USB-C actions use Yubico's official `yubikit-swift` v1.5.0 SDK pinned to commit
`03fe66d15b6e863e87a44c7a263b7bffedda9d79`, never an emulated key or software fallback. NFC
requires ISO7816 support; USB-C requires smart-card (CCID / CryptoTokenKit) capability. A FIDO-only
key is not an OATH key. Lightning accessory connections are not started.

The workflow supports account enumeration, an optional OATH password entered for each operation,
explicit single-account calculation, confirmed deletion and supported account renaming. Names are bounded/control-stripped for display; matching
uses the raw identity, not the displayed name/index. Reconnecting to calculate verifies the OATH
device ID before authentication and uniquely re-matches credential label/account/issuer/type/period.

HOTP requires explicit **one-operation counter-advance consent**, reset before the request starts.
Duplicate connection callbacks cannot issue another calculation. No automatic calculation, bulk
calculation or retry is performed. A lost/failed HOTP response warns that the counter may already
have advanced. TOTP codes expire at the SDK interval end; HOTP display expires 30 seconds after
request. Expired results are rejected and copied codes expire no later than their display deadline.

The OATH adapter does not export seeds from the key or change/reset key passwords or keys.
Deletion and rename are explicit single-account operations with separate confirmation. The separate explicit copy-to-key writer below provisions a vault seed. Hardware-backed unlock uses the separate FIDO route below. The OATH password is not a FIDO PIN. It is not
stored in preferences or Keychain. Swift strings cannot guarantee physical memory wiping; ownership
is released on handoff/cancellation. Names/codes/passwords/raw diagnostics are not logged or persisted.

## Lifecycle and safety

Each operation gets a UUID-bound async task and admission check on the main queue. A 30-second
timeout, explicit close, lock, background or coordinator destruction cancels transport ownership.
Native callbacks cannot publish across cancellation, dialog replacement or vault generation changes.
If a calculation already reached the physical key, cancellation cannot reverse its counter change.
Use the same physical key when reconnecting; a reset OATH application intentionally changes identity.

Toolkit-free `HardwareOathPolicy` centralizes unique credential matching, HOTP consent and numeric
code/expiry validation. Portable Swift tests cover mismatched/ambiguous identity, counter consent,
non-ASCII/invalid codes and expiry. App tests cover actions without an account or current session.
These tests are added but **not compiled/executed in the Linux sandbox**.

## Build/signing and native acceptance

XcodeGen resolves the pinned YubiKit SPM dependency. Project configuration declares the NFC TAG
entitlement, NFC purpose text and both OATH and FIDO AIDs. Regenerate the project. Physical NFC requires a
properly provisioned app with NFC capability; unsigned simulator builds cannot certify it. USB-C
requires a compatible physical device, OS and CCID key. The SDK API was inspected from its public
Swift transport/OATH/WebAuthn sources and tests; that does not replace Swift type-checking.

Run the Xcode build and test NFC and USB-C independently: empty/multiple accounts, FIDO-only key,
protected OATH, wrong password/no automatic retry, touch, HOTP consent, key swap/reset/rename,
duplicate callbacks, timeout/disconnect, cancel/background/lock/reconnect, expiry and clipboard.

## Explicit vault-to-key seed copy

Settings / Hardware / Copy vault OTP account to security key uses the shared portable Compose form.
Select a compatible TOTP/HOTP entry, review the seed-export warning, and authorize one write before
choosing NFC or USB-C. The OATH password is entered per operation; requiring touch defaults on.
The vault entry is not deleted. HOTP copies have independent counters after the initial copy; using
both copies can desynchronize authentication. This is **vault-to-key**, not extraction of a key's seed.

The writer rejects existing raw hardware IDs before calling the official SDK's add-credential API.
The naming policy preserves JVM 64-byte UTF-8 limits, period-prefix disambiguation, unsupported
name/control-character rejection, 6–8 digits and signed-32-bit HOTP counter interoperability.
A shared synthetic naming/admission corpus is consumed by JVM and Swift tests. The source entry's
fields and vault generation must still match selection. Consent is consumed before connecting;
close/background/lock/replacement cancels work and clears the selection/password. Lost responses
are never retried: a seed already written to the physical key cannot be rolled back by cancellation.
SDK template internals and Swift strings cannot promise physical zeroization of all copies.

## FIDO PRF vault unlock

Settings / Hardware exposes local enrollment/removal, with separate explicit NFC and USB-C actions.
The shared unlock screen exposes Security key only when a local record is present, separately from
biometric unlock. `HardwareFidoReader` uses official WebAuthn registration and assertion PRF APIs,
required user verification/presence, the fixed offline `offlineotp.invalid` RP and allowlisted
credential. The HTTPS origin is a WebAuthn identifier, never a network request. No rejected-PIN
retry or software fallback is performed. Enrollment consists of registration followed by PRF
assertion; cancellation cannot undo a credential already created on the key.

`OfflineOTPKit.HardwareUnlockEnvelope` preserves the existing `OTP\x01` framing, bounded
credential ID/public salt, HMAC-SHA256 domain-separated wrapping key and authenticated AES-GCM
header. Independent synthetic vectors are consumed by JVM and Swift tests. The device-local
`hardware-unlock.b64` record is not exported as portable preferences. SDK logging is explicitly
silenced, including debug/trace output that could otherwise contain identifiers or secrets.

The former Objective-C YubiKit dependency is replaced, not loaded alongside the successor (both
use the same module name). SwiftLog is explicitly pinned for the no-op SDK logger. The successor
requires Swift tools 6.1; regenerate with XcodeGen and verify using a compatible Xcode installation.
**The native adapter and new Swift tests have not been compiled or executed in this Linux sandbox.**

## Remaining acceptance and parity

Test enrollment, password-independent unlock, removal, wrong key/PIN, required UV, unsupported PRF,
key swap/reset, cancellation/timeout/background/lock and stale completion independently over NFC
and USB-C. Also verify existing desktop/Android envelopes using the same physical key. A format
vector or shared Compose build is not native protocol verification. OATH password/reset
management remains unimplemented; seeds cannot be extracted from a hardware key into the vault.
Native-operation tests now inject SDK-compatible fake connections for unsupported transports,
connecting cancellation, operation cancellation, replacement and timeout, including late-result
disposal. App tests cover seed selection/consent/stale-entry/lock/background rejection. These Swift
tests are added but have not run here; full injected OATH/PRF protocol and real transport tests remain.

NFC and USB-C smart-card availability are reported independently in the shared unlock form and
portable hardware surfaces; unavailable transport actions are disabled and admission is rechecked
at operation time. Availability does not establish an attached key's PRF/UV/OATH capability.
Enrollment's queued vault-key snapshot is revocable and one-use; cancellation/timeout clears it,
including when a native connection completes late. New tests cover this ownership, but still
require Xcode execution.


## Account deletion source route

The portable OATH surface now offers per-account deletion with typed `DELETE`, selected-account
warning and password re-entry (selection clears any previously entered password). NFC and USB-C
use the existing independently gated native operation adapter. Before a single SDK deletion command,
the reader checks device ID, lists credentials without calculating codes, uniquely matches the
selected identity, and checks cancellation. HOTP consent is not needed for deletion because no
calculation occurs. The account list is cleared after success or uncertainty and no retry is automatic.
Cancellation after the command starts may still delete the account; the vault remains unchanged.
These Swift changes are source-guarded only, not natively compiled or device-verified here.


## Account rename source route

Android/Desktop use a common account-rename Compose dialog; iOS uses the portable Compose surface
with the same typed `RENAME`, fresh password and account-bound consent. The operation revalidates
the device/raw credential identity, refuses ambiguous matches, same-ID no-ops and destination-ID
collisions, checks cancellation immediately before one SDK rename command, and never calculates a
code. Account list and transient codes are retired after success or uncertainty. Vault, secret, HOTP
counter, algorithm, digits and period are unchanged. Unsupported firmware reports failure without
retry; rename requires the key's OATH rename feature (YubiKey 5.3 or later).

`HardwareOathRenamePolicy` (JVM/Swift) preserves the 64-byte UTF-8 ID limit and period-prefix
encoding, including literal numeric-slash names. Both test suites consume
`golden/hardware-oath-rename.json`. The Swift SDK reconstructs the **old** ID from parsed names:
this adapter requires its reconstructed bytes to equal the selected raw ID before exposing rename.
Noncanonical old IDs are therefore deliberately unavailable for rename on iOS, rather than risk
mutating another account. Deletion/calculation still match raw IDs. Native Swift tests and real
firmware/transport execution remain unrun here.
