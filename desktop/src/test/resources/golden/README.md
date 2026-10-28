# Golden interop blobs

Fixed-vector files that pin the on-disk formats shared by the Android app and the desktop client.
`GoldenInteropTest` hashes every file and opens each blob through the real desktop storage stack,
so a silent change to the codecs, the PBKDF2 split or the byte framing fails the build.

All blobs use the password `golden-interop-password` and the same two-entry vault document
(`expected-vault.json`, produced by the shared `VaultJson` encoder): a 6-digit SHA1 TOTP for
`Golden Issuer` and an 8-digit SHA256 HOTP (counter 9) for `Counter Service`.

| File | Format | Fixed parameters |
| --- | --- | --- |
| `secrets.dat` | nonce-prefixed AES-GCM over the vault JSON | PBKDF2 salt `00..0f`, 4096 iterations; GCM nonce `10..1b`; key = lower PBKDF2 half |
| `vault.properties` | desktop settings for the vault | Base64 URL-safe verifier (upper PBKDF2 half) and salt, iteration count 4096 |
| `otp_accounts.json.aes` | `4-byte BE iterations ‖ 12-byte salt ‖ 12-byte nonce ‖ GCM ct ‖ 16-byte tag` | 140000 iterations, salt `20..2b`, nonce `30..3b` |
| `legacy-otp_accounts.json.aes` | headerless nonce-prefixed AES-GCM, key = SHA-256(password) | nonce `40..4b` |
| `expected-vault.json` | compact `VaultJson` document | plaintext shared by all three encrypted blobs |

## Regeneration

The blobs are generated with a short Kotlin program compiled against the sandbox-built `:core`
output (`reports/kotlin-sandbox/desktop/core`) and the Kotlin stdlib; it calls only
`CryptoPrimitives`, `EncryptedBackupCodec`-equivalent framing and `VaultJson`, with the fixed
salts/nonces above instead of random bytes. `vault.properties` has its `Properties.store`
timestamp comments stripped so the file is deterministic. After regenerating, update the SHA-256
pins in `GoldenInteropTest.expectedHashes`.

These are test-only fixtures with a throwaway password and no real accounts.
