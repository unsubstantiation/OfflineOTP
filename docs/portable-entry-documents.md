# Chunk 2 — portable entry contract and document adapters

**Shared entry slice implemented; the broader iOS Kotlin cutover remains incomplete.**
See [current verification status](shared-ui-migration-status.md).

This completes the metadata slice described in [portable entry metadata](portable-entry-metadata.md)
with a full portable record, kind/algorithm/parameter model, bounded JSON codec and production
JVM/Apple document adapters. The remaining native DTOs/material validators are compatibility
adapters, not an alternative production document-policy path.

## Shared domain

`shared-domain/src/commonMain/kotlin/com/fairuniversal/offlineotp/domain/` contains:

- `EntryMetadata`: names, ordered tags, visibility and exact signed 64-bit usage metadata.
- `EntryRecordKind` / `EntryRecordAlgorithm`: the nine supported kinds and three hash algorithms.
  JVM `OtpKind` / `OtpHashAlgorithm` now alias these enums instead of declaring separate copies.
- `EntryParameters`: validated period, digits and counter with the existing Steam/mOTP/HOTP rules.
- `EntryRecord`: complete field bag with typed projections, encoded payload access, validated
  field replacement, opaque-extension separation and explicit reference release.
- `EntryValue`: immutable text/number/boolean/null/array/object values. JSON numbers retain their
  decimal text without passing through `Double`, including future values beyond Int64 range.
- `EntryDocumentCodec`: strict, all-or-nothing JSON decoding and bounded serialization with a
  Swift-safe result/null error boundary. Diagnostic strings never include input or secrets.

The codec uses no Java, Android, Foundation, Compose, third-party JSON, crypto or filesystem API.
Its parser rejects duplicate object names (including escaped aliases), trailing documents,
comments/single quotes, trailing commas, malformed number grammar, unescaped controls and unpaired
Unicode surrogates. Bounds are 16 MiB UTF-8, depth 32, 2,000 entries and 200,000 JSON value nodes.
The value-node ceiling explicitly bounds broad extension graphs; oversized documents are rejected
in full, never truncated or partially installed. Encoding checks the resulting UTF-8 size too.

### Compatibility interpretation

- Missing historical type/algorithm/issuer/tags/usage fields keep their documented defaults.
- Historical integral numeric strings in known parameter fields are accepted and transferred to
  native engines as exact integer tokens. Floating-point parameters, overflow and unknown kinds or
  algorithms are rejected. mOTP's stored period never controls its timing.
- The lossless `json()` view retains original field values/number lexemes. `transferJson()` uses the
  shared typed interpretation for present known numeric/visibility fields; it does not invent
  omitted defaults or reinterpret extension names as typed data.
- Absent/invalid usage metadata projects to zero. Valid signed 64-bit usage values are exact,
  including values above 2^53. Fractional/malformed usage data is not an OTP counter and cannot
  bypass strict parameter validation.
- `materialJson()` exposes only known fields to a native engine. `extensionFieldsJson()` retains
  unknown field values as validated raw JSON fragments. This split is essential: Foundation and
  Android's `org.json` cannot losslessly represent every future JSON number.

Seed/key material remains encoded in the record and is never included in diagnostic text. Native
NOTE/SSH/PWD1/email material codecs still validate the actual bytes **after** the shared structural
and parameter checks. Syntax validation is not sufficient to install a password/key/email entry.
Porting those material/import/export codecs is chunk 7, not a hidden crypto rewrite in chunk 2.
Immutable strings and retained snapshots cannot be promised physically zeroized; `destroy()`
releases owned record references, not copies a caller has deliberately retained.

## Production integration

### Android and all desktop distributions

`VaultJson` reads and writes through the shared codec. On read, each native `Entry` receives only
validated known fields plus a detached lossless extension map. On write, opaque fragments are
merged with the native material serializer and checked by the shared codec before returning bytes.
Errors remain mapped to the existing all-or-nothing core exception contract.

`Entry.detachedCopy()` preserves opaque fragments without converting them through `JSONObject`.
Production detached-save, transaction, first-run, hardware-copy and password/email editing paths
use it. Desktop transaction fingerprints hash the complete lossless document rather than an
org.json projection. Replacement forms continue to call `preserveExtensionFieldsFrom` when preserving metadata.
The legacy public `toJSON()` surface remains a compatibility projection for existing callers;
it is **not** the lossless vault-document transport for future numeric fields.

### iOS

`SharedEntryDocumentAdapter` uses the exported Kotlin codec, then invokes the native material
validators with known fields only. Unknown fragments are attached to Swift `Entry` separately;
`compact()`, metadata edits and replacement edits retain them without NSNumber conversion.
A compatibility `fields` projection is available for native callers, but serialization uses the
validated fragments, including values Foundation cannot project at all. Edit/hardware-selection
staleness checks compare complete `compact()` snapshots, not that lossy projection. Password/email
material-edit helpers and metadata/form replacements explicitly preserve the original fragments.

`VaultStore` injects this adapter into `PasswordVault` and uses it for every app-level document
read/write, including first-run restore, import/restore previews, commits, exports and automatic
export snapshots. Selected encrypted-backup decoding receives the same adapter for its preliminary
entry validation and duplicate check, rather than applying a different Foundation-only reader. There is no process-global mutable codec or production silent fallback.
Individual import/factory adapters may still construct native entries; publication/persistence
passes through the shared document boundary and native material validation.

`NativeEntryDocumentAdapter` remains the default for standalone SwiftPM engine tests and their
compatibility oracle, which cannot depend on a UIKit/Compose framework. It is **not** selected by
the production iOS app. Retain this adapter and native DTOs until native comparison gates pass;
cleanup is an explicit later step, not deletion of unverified code.

The vault container format, password/KDF policy, atomic-write sequencing, entry identity and
session state machine are unchanged. This chunk shares entry documents, not encrypted framing or
restore/unlock orchestration (chunks 3–6).

## Tests and reproducibility

- `PortableEntryDocumentTest`: defaults/discriminants, parameter bounds, signed integers, lossless
  unknown numbers, Unicode/JSON grammar, all-or-nothing failure, collection/depth/byte/node bounds,
  base32 shape, safe edits and a deterministic 250-record nested-extension corpus.
- `PortableEntryDocumentContractTest`: every kind through actual JVM decode/edit/save/reload,
  invalid documents, native material rejection and huge/exponent/fractional opaque values through
  detached copies and persistence.
- Android `BackupRestoreDeviceTest`: the same all-kind corpus and Android-number-coercion regression.
- iOS app `SharedEntryDocumentAdapterTests`: actual Kotlin/Swift bridge, all kinds, malformed input,
  material rejection, opaque future numbers through metadata edits, and full VaultStore save/reopen.
- SwiftPM `PortableEntryDocumentOracleTests`: native compatibility corpus and injected adapter
  read/write/failure behavior. These complement, not replace, the app's actual shared-adapter tests.
- Python source/dependency guards pin adapter injection, domain exports, lossless-copy paths and
  bundled XCTest resources. They are not native runtime evidence.

`desktop/src/test/resources/golden/portable-entry-documents.json` contains independently framed,
synthetic payloads for all nine kinds. Reproduce/check it with:

```sh
python3 scripts/fixtures/generate-entry-documents.py
python3 scripts/fixtures/generate-entry-documents.py --check
```

The iOS app tests bundle this corpus through XcodeGen. No device test tries to read a repository
path from the host. Old vault/backup golden files are not regenerated or version-bumped.

## Local validation record (2026-11-20)

- Core: 367 passed.
- App/core JVM unit gate: 388 passed (overlaps the core suite; do not sum these as unique tests).
- Desktop: 471 passed, 2 skipped, 0 failed (473 discovered).
- Shared UI: 168 passed; desktop headless host: 3 passed; platform seams type-checked with stubs.
- Android sandbox integration checks: passed.
- Android instrumentation sources: all 28 files compile against stubs, not executed on a device.
- Python source/tooling guards: 725 passed; fixture regeneration check and architecture guard pass.
- Native Swift/Kotlin-Native compilation, Swift test execution, real Gradle/platform builds and
  Android device instrumentation: **not run in this Linux environment**.

## Handoff

The chunk-2 source changes are complete: all production builds consume the shared entry-document
contract, adapters preserve opaque metadata, and the all-kind compatibility corpus is in place.
Before release, build the Kotlin iOS framework and Xcode app, run both Swift test suites and execute
the Android instrumentation cases. Do not claim those gates passed from a source/stub check.
After review, chunk 3 can consolidate vault/backup framing above vetted crypto implementations.
