# Full backup restore

Existing-vault restore and first-run restore use the same explicit source choices on Android, Desktop and iOS:

1. **Vault file copy (current)**: the saved portable vault container, opened with the recovery password in use when the copy was made. Local biometric/security-key enrollment is not a substitute for that password.
2. **Previous OfflineOTP portable backup**: historical password exports with either the OOTP Argon2id header or the older PBKDF2-HMAC-SHA1 header.
3. **andOTP portable backup**: choose password-encrypted (0.6.3 and later), unencrypted JSON, or explicitly opt into the pre-0.6.3 encrypted format. OpenPGP backups remain a separate restore action.

Selection is not trial decryption: a password or authentication failure never retries another format. Older OfflineOTP PBKDF2 exports and modern andOTP encrypted backups share the same wire format, so these two cannot be distinguished cryptographically. The andOTP selection does not accept OfflineOTP's Argon2id header or a vault container.

Modern andOTP framing is BE32 iteration count, 12-byte salt, 12-byte AES-GCM nonce, ciphertext and 16-byte tag, with PBKDF2-HMAC-SHA1 deriving a 256-bit AES key. The old headerless format uses SHA-256 of the UTF-8 password as the AES key and is read-only, explicitly selected compatibility support. No writer emits this weak legacy format.

All selected decoders apply document-size and KDF bounds, strict UTF-8 and complete entry-list validation before installation; duplicate accounts inside the imported document are rejected. Existing-vault restore previews the entry count and requires explicit merge/replace confirmation. An empty backup cannot replace an existing vault. First-run restore installs the entries under a newly chosen vault password (Android retains the setup authentication choice), without altering the source file. Plaintext JSON contains secrets and must be handled accordingly.

## Verification

`desktop/src/test/resources/golden/full-backup-restore.json` contains synthetic independent Python hashlib/cryptography vectors for all three andOTP encodings, checked by JVM and Swift contract tests. Existing golden vault containers and historical OfflineOTP exports cover the other selections. Wrong passwords, tampering, wrong selections and malformed documents are tested. Swift tests read the same corpus directly from the repository.

Linux sandbox gates type-check shared Compose and platform seams, but do not constitute native Android/iOS builds or device validation. Run shared Kotlin/target integration tests and verify platform file pickers, first-run restore, merge/replace, lock/cancel and error presentation for changes under review. The standalone legacy Swift suite is not an iOS CI gate; see [current verification](shared-ui-migration-status.md).
