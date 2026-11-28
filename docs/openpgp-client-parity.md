# OpenPGP engine contract across builds

## Baseline

All clients support in-app OpenPGP encryption with recipient public-key files and decryption
with a secret/private-key file (and its passphrase when protected). No external OpenPGP program
is required for this path. Key files are supplied by the user; this is not key generation or an
automatic migration of a provider-managed private key.

| Release | Default in-app implementation | Optional external integration | Unavailable engine |
| --- | --- | --- | --- |
| Android | `RpgpOpenPgpPort`, native Rust library/JNA | OpenKeychain-compatible provider mode, explicitly selectable; existing configured providers retained | Key-file buttons disabled with a backend warning; no silent provider switch |
| Windows | `RpgpOpenPgpPort` via the shared desktop controllers | No external provider/agent integration currently | Embedded PGPainless fallback when the native library cannot load |
| macOS | Same desktop implementation | Same as Windows | Same as Windows |
| Debian/Linux | Same desktop implementation | Same as Windows | Same as Windows |
| iOS | `RustOpenPgpPort`, linked Rust XCFramework | No Android-style provider integration | Buttons disabled; explicit missing-engine explanation |

PGPainless is another **in-process library**, not an external program. Desktop does not retry a
failed native cryptographic operation with that library; the fallback applies only when the
native backend cannot load. These availability differences are intentional, not a requirement
for a separate app on any platform.

## Selection and messaging

Android's Settings selector offers **In this app (key files)** and **Separate OpenPGP app**.
New installations without provider configuration default to key files; explicit choices and
legacy configured providers remain honored. The provider-required warning applies only to the
selected external-provider mode and explains how to choose the built-in alternative.

Desktop import/export forms and the iOS backup screen explicitly state that the key-file path
runs in-app. Android's key-file description uses the same notice. A source-level regression test
pins that notice and the actual controller/adapter defaults for all release families.

## Scope and validation

This establishes engine selection, not identical capability sets. Native signed exports
and session-only signing credentials are implemented where exposed by the host. Android
external-provider signing/verification controls remain provider-specific; do not infer
signature verification on restore merely from successful decryption. Recipient-selection UX/limits and native pickers also differ; those remain separate parity
work. Standard OpenPGP format compatibility depends on keys and supported algorithms and must
be tested with real files, not inferred from labels.

Source gates: `scripts/tests/test_openpgp_cross_build.py`, existing key-file/native-packaging tests.
Desktop import/export behavior is covered in `DesktopOpenPgpImportTest`,
`DesktopOpenPgpExportTest` and codec tests. Swift availability tests live in
`ios/Tests/OfflineOTPKitTests/OpenPgpAvailabilityTests.swift`.

The user reports non-iOS builds/platforms verified; the iOS build remains blocked.
For subsequent release candidates, validate the actual packaged native libraries. Exercise encrypted backup/restore and incorrect-key/passphrase failures on
each; on Android also exercise explicit provider selection. Sandbox tests are not evidence
that any particular distributed artifact contains a working native library.
