# Portable vault and backup wire policy — chunk 3

Status: **implemented; native release gates pending** (2026-11-20). This is protocol consolidation,
not a vault-format redesign, crypto-provider replacement, session migration or restore state machine.
See [migration plan](shared-code-migration.md) and [entry documents](portable-entry-documents.md).

## Shared authority and boundaries

`shared-domain/.../VaultProtocol.kt` now owns:

- Vault family recognition, version/cipher/flags/KDF checks, big-endian header encoding/decoding.
- Previous OfflineOTP Argon2id and historical PBKDF2 backup headers and read ceilings.
- Explicit restore-source routing plus the existing automatic reader's family routing.
- Algorithm-independent Argon2 resource bounds, including aggregate memory × passes.
- Immutable, defensively copied public recovery descriptors: salt, parameters, header bytes,
  encrypted payload offset, derived-output length and AES-key length. Descriptions are redacted.
- Typed nonthrowing parse/route failures (`SIZE`, `FORMAT`, `VERSION`, `PARAMETERS`), safe at the
  Kotlin/Native ObjC boundary. Wrong passwords/authentication failures remain provider errors.

There is no crypto implementation, password, key, native handle, storage operation or session
permission in this shared policy. The existing JCA/CommonCrypto/CryptoKit/Argon2 providers remain
responsible for the cryptographic operations; native validation remains defense in depth. Rust
OpenPGP is unchanged. LinSCP's format is only dispatched here; its codec remains chunk 7 work.

The API accepts a small header prefix and an **actual** full byte count. Production adapters must
obtain that count from the bounded byte buffer, not a length advertised by an untrusted header.
This is particularly useful on Apple: at most 40 public bytes cross into Kotlin rather than a
16 MiB encrypted file. It does not authorize reading or installing a file.

## Preserved wire contracts

| Family | Header / encryption | KDF material |
| --- | --- | --- |
| Current vault copy | `OTPVLT CR LF`, version 1, AES-GCM identifier 1, KDF identifier, zero flags; 40-byte header, nonce 12, ciphertext, tag 16. **All 40 header bytes are AAD.** | Argon2id derives 64 bytes, first 32 = AES-256 key. PBKDF2-HMAC-SHA1 compatibility derives 32, first 16 = AES-128 key. |
| Previous OfflineOTP portable backup | `OOTP`, version 1, BE32 memory KiB, BE32 passes, one-byte lanes, salt 16; nonce 12, ciphertext, tag 16. Header is **not AAD**, preserving existing exports. | Argon2id derives 32 bytes; all 32 = AES-256 key. |
| Historical PBKDF2 / andOTP modern backup | BE32 passes, **12-byte salt**; nonce 12, ciphertext, tag 16. No AAD. | PBKDF2-HMAC-SHA1 derives 32 bytes; all 32 = AES-256 key. |
| Explicit andOTP pre-0.6.3 | Headerless nonce 12, ciphertext, tag 16. No AAD. **Explicit selection only.** | SHA-256 of UTF-8 password, all 32 = AES-256 key. |

Vault header offsets: family 0..7, version 8, cipher 9, KDF 10, flags 11, BE32 memory 12,
BE32 passes 16, BE32 lanes 20, salt 24..39. PBKDF2 requires zero memory and lanes.
New writers still use the existing Argon2id settings (64 MiB, 3 passes, 4 lanes), fresh 16-byte salt
and fresh 12-byte nonce. No weaker fallback is introduced.

Limits remain 16 MiB total document bytes; vault plaintext limit is total minus 68 bytes. Argon2
limits: lanes 1..16, memory 8 × lanes through 262,144 KiB, passes 1..64, aggregate block-passes at
most 1,048,576. PBKDF2 vault/credential compatibility allows 1..20,000,000 iterations; backup
headers allow 1..1,000,000. These different ceilings and key-splitting rules are intentional.
Writers preflight backup size so they cannot emit a file their bounded reader would reject.

## Migrated callers

### JVM / Android / desktop

`VaultContainer` and `EncryptedBackupHeader` delegate parsing/construction to the shared policy;
their redundant ByteBuffer parsers are removed. Their existing native-facing API and JCA key types
remain adapters, keeping persistence/session callers unchanged. `EncryptedBackupCodec` delegates
both explicit source dispatch and automatic reader dispatch to the policy. JVM Argon2 validation
and PBKDF2 credential ceilings also use shared `KdfPolicy`.

Every derivation from a file is still preceded by header bounds/version validation. A recognized
but unsupported vault family is not interpreted as a legacy backup. An authentication failure
never retries another format or KDF. Explicit OfflineOTP/andOTP paths reject LinSCP family input;
the existing automatic import reader retains its LinSCP route.

### Apple

`VaultProtocolAdapter` is the Swift engine's injected policy boundary. `SharedVaultProtocolAdapter`
in the app calls the exported Kotlin APIs and converts only public metadata. Production `VaultStore`
constructs its `PasswordVault` with this adapter; manual imports/exports, first-run backup reads and
session automatic encrypted exports also receive it. Header creation, key-based open, password open,
snapshot validation and explicit existing-vault conversion forward the same policy instance.

The standalone SwiftPM `NativeVaultProtocolAdapter` preserves an independent oracle, using the
old native parser/bounds. It is a default for standalone engine consumers/tests, **not a fallback
when shared validation fails**. Its parsers remain until actual Kotlin/Native/Xcode gates pass;
deleting the only independently executable Swift oracle now would reduce evidence, not duplication
safely. Native crypto code still validates its own inputs; it is not ported to hand-written common
cryptography. The deterministic legacy PBKDF2 sealer remains solely for historical fixture tests.

## Unchanged semantics / out of scope

- A full backup remains the exact encrypted vault-file bytes. No live-entry serialization, decrypt/
  re-encrypt, second container, preference bundle or new password is introduced for file copies.
- Existing copied-file unlock remains independent of stale local settings. Recovery metadata is in
  the authenticated header. Device conveniences do not become portable secrets.
- The shared entry codec and native kind-material validators remain the plaintext document gate.
- Restore preview/confirmation/install orchestration (chunk 4), session/unlock transitions (5),
  atomic storage/rekey sequencing (6), import codecs (7), backup scheduling (8) and UI consolidation
  (9) are not migrated here. Existing transactions and authority checks are preserved.
- Strings/COW/provider key objects cannot promise physical zeroization. Existing cleanup remains;
  shared protocol results contain only public recovery metadata, never passwords/plaintext/keys.

## Fixtures and validation

`python3 scripts/fixtures/generate-vault-protocol.py --check` verifies the deterministic
`desktop/src/test/resources/golden/portable-vault-protocol.json` corpus. It independently constructs
headers with Python's big-endian primitives and describes legal edge parameters, signed/overflow
values, reserved flags, unsupported versions, resource ceilings, truncated prefixes/files, oversize
buffers and explicit/automatic routes. Upper-bound rows only parse metadata; they do not spend the
requested KDF work. Existing independently encrypted golden vault/backup vectors remain unchanged.

Executed local gates:

- Core: **371 passed**, including defensive-copy, key-split, bounds, family/no-fallback and 5,000
  deterministic arbitrary-header cases. Existing header/body byte-tampering tests still pass.
- Desktop: **473 passed, 2 skipped** (475 discovered), including cross-corpus JVM adapters, dispatch
  and the existing real JCA/golden vault/backup contracts.
- App/core JVM: **392 passed** (overlaps core; do not sum as unique tests).
- Shared UI **168 passed**, desktop headless host **3 passed**; Android sandbox integration passed.
- All **28 Android instrumentation source files compile against stubs**. The new protocol corpus
  and real-provider vault/tamper test is authored, not device-executed.
- Python tooling/source guards: **730 passed**; architecture and fixture checks pass.

Authored but not executed here: `SharedVaultProtocolAdapterTests` runs the same corpus through
Kotlin/Native and the standalone Swift oracle, opens independent vault vectors with native crypto,
compares deterministic native/shared backup writers, and rejects wrong passwords/tampering.
It is bundled in the Xcode test target along with its fixtures.

**Native gates remain required:** actual Gradle/platform builds; both Android variants and device
instrumentation; standalone `swift test`; Kotlin iOS framework export; Xcode app/XCTest build and
execution. This Linux workspace has no Swift/Xcode/Android SDK. Stub/source checks are not proof
of native ABI/export correctness or requested-OS runtime parity. Do not delete native oracles on
the strength of these checks alone. Chunk 4 has not started.
