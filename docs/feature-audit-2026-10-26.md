# Existing-feature audit — 2026-10-26

The audit traced the documented OTP, import, search, tag and settings paths through their UI and
implementation. It focused on incomplete behavior in existing features, not a promise that every
optional request or device-specific issue has been resolved.

| Finding | Completion and coverage |
| --- | --- |
| An active search disappeared after account, tag or sort changes while the query remained visible. | The active whole-vault search is reapplied; clearing it restores the selected tags. JVM projection tests and sandbox adapter checks cover refresh and stale results. `EntrySearchDeviceTest` is written to exercise the Android filter worker. |
| mOTP uses a 10-second step but its card showed only the 30-second global countdown. Its external-import preview also displayed the stored SHA1 metadata instead of mOTP's actual MD5 operation. | Cards and previews use the actual token step, and the preview describes six hexadecimal characters and MD5. JVM and sandbox checks cover timing; `TokenCardDeviceTest` is written to cover all three real card layouts. |
| HOTP and the mOTP PIN placeholder could be colored as expiring even though neither is a timed code; the last-eight-seconds boundary was one second late. | The highlight applies only to time-based codes, uses the actual step and reverts when the clock moves back. Explicit-time JVM tests cover the boundary, HOTP and the mOTP placeholder. |
| Duplicate-issuer QR confirmation could outlive a lock and insert into a later session; external-import confirmation was not explicitly cleaned up on activity destruction. | Both confirmation paths own their parsed entry, wipe it on dismissal, lock or destruction, and recheck the original session under the mutation lock. JVM ownership and sandbox dialog checks cover stale and cancelled confirmations. |

The historical [feature and UX list](andotp-open-issues-triage.md) also contains optional additions.
Google Authenticator migration-QR import was implemented after this audit. Experimental OATH and
FIDO2 PRF hardware-key paths were added later; protocol/model limits and Android/device verification
remain open (see the README). Configurable pruning of automatic backups is not implemented:
completed backups are retained. In-app network clock
synchronization would also conflict with the current offline contract.

Host JVM and sandbox checks use Android stand-ins. The new device tests must still run on real
API 28 and API 36 environments, alongside both-flavor Gradle builds, lint and provider tests;
stand-in compilation is not Android runtime acceptance.
