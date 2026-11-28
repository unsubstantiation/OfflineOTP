# Portable import/export — chunk 7

Implemented 2026-11-20. Native build/device validation remains outstanding; this is not an OS parity certificate.

## Shared rules and production adapters

`shared-domain/commonMain` now owns provisioning URI parsing/export, Google migration protobuf and batch identity/count rules, strict UTF-8/percent/base encodings, browser and Thunderbird CSV syntax, browser dialect/edit/export policy, password/SSH/email material validation, credential document shapes, LinSCP session INI parsing, preferences document validation and one-shot generation-bound interchange authorization.

JVM `core` utility APIs delegate to these codecs; the duplicate JVM URI/protobuf/CSV tokenizers were removed. Native entry adapters retain material ownership and convert common results into existing entries. Production Apple hosts inject `SharedInterchangeAdapter`; shared parse failures do not retry a native parser. `SharedEntryDocumentAdapter` also uses common material validation. LinSCP JSON/bundle/INI import forwards the adapter, including the encrypted-backup compatibility route. Standalone SwiftPM defaults deliberately retain native compatibility oracles until Apple framework/device gates pass; deleting those is chunk 10, not evidence obtained here.

Preferences catalogs and writes remain native. Common validation checks the whole document, platform, typed known values and device-bound exclusions before any native mutation. Foreign-platform settings are refused rather than partly applied. LinSCP framing/crypto, file pickers/providers, exclusive writes and OpenPGP encryption/signing remain native. The existing Rust OpenPGP backend is unchanged. Full vault snapshots remain exact file copies, not entry reserialization.

## Lifecycle and secret ownership

`InterchangeWorkflow` authorizes one preview consumption/export delivery in the unlocked generation that prepared it. Native owners retain the exact payload; a count is not identity. Pending JVM imports transfer entries once and destroy rejected/canceled payloads. Apple migration confirmation requires the exact pending entries and a one-shot token; a new code invalidates the previous confirmation, and lock/session reset cancels it. Batch metadata rules reject stale, duplicate, oversized or foreign exports while native collectors own and release account seeds.

Desktop queued password/archive/email/Thunderbird/preferences/encrypted/OpenPGP exports consume shared generation authorization before worker execution. Encrypted/OpenPGP paths additionally recheck before writing. Session generation is published to detached workers; key replacement invalidates queued snapshots. This is not a rollback promise for a native write already in progress. Native document access and cleanup remain adapter responsibilities.

Portable OTP results own copied seeds and expose explicit destruction; failed material decodes wipe mutable buffers. Metadata policies never hold seeds. Bridge byte arrays are cleared after conversion, native entries retain their normal destruction contract, and diagnostic wrappers are redacted. Immutable strings and Swift copy-on-write values cannot promise physical zeroization of every runtime copy.

## Compatibility and bounds

Wire vault/backup formats and KDF routing are unchanged. CSV retains quoted multiline/Unicode cells, unknown columns and order; provisioning export preserves repeated tags, counter and percent encoding. Inputs are bounded before adoption; malformed UTF-8, duplicate recognized URI parameters, malformed CSV, oversized seeds/material/documents and trailing material bytes are refused. Common material/document validation is deliberately stricter than permissive historical Foundation/org.json coercion: required unique fields must have the expected types and fit shared limits. No fallback decoder is used to bypass rejection. Unknown entry extension preservation remains governed by the entry-document contract.

## Evidence and remaining gates

Local gates for this slice:

- Core: **413/413** tests.
- App JVM: **434/434** tests.
- Desktop: **477 passed, 2 skipped**, 479 total.
- Shared UI stub gate: **3/3** tests; sandbox checks passed.
- Android instrumentation: **29 files compile** against stubs, not device execution.
- Python: **754** tests (final rerun recorded in session); architecture boundary and whitespace checks.

Coverage includes malformed/bounded encodings/material, CSV/provisioning round trips, migration batch ordering and lifecycle, preferences/LinSCP validation, pending consumption and stale queued exports. Source ratchets ensure production delegation and preserved native boundaries. Added Android `InterchangeCodecDeviceTest`, Apple `SharedInterchangeAdapterTests` and migration confirmation tests await real native execution.

Required equipped-runner gates: real Gradle common/JVM/Android builds and both Android variants' instrumentation; Kotlin iOS framework compilation, SwiftPM tests and Xcode app/test builds; packaged desktop smoke tests. Exercise providers, cancel/relock/rekey during queued work, Unicode/large imports and OpenPGP on requested operating systems. Stub compilation/source tests are not ABI, runtime, secure-erasure or device evidence. Automatic-backup consolidation (8), full presentation consolidation (9), and final removal/release (10) are separate chunks.
