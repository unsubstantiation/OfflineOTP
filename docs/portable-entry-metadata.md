# Chunk 2a — portable entry metadata

Historical description of chunk 2a, the first vertical slice. The remainder is now implemented in
[the full portable entry contract](portable-entry-documents.md); native validation remains pending.

## Shared code

`:shared-domain` is a Kotlin Multiplatform module for Android, desktop JVM, iOS device and
simulators. It depends on neither the JVM engine nor Compose/JSON libraries. Its first value is
`EntryMetadata`: issuer, label, hidden label, ordered tags, last-used timestamp, usage frequency
and details visibility. Collections are detached on input/output and diagnostic text is redacted.
A hidden label can contain sensitive information; this is not a public/loggable display DTO.
Immutable strings/serialized JSON are not guaranteed physically zeroized.

The model owns metadata size validation (UTF-16 text/tag limits) and metadata-only JSON encoding.
Its bounded encoder escapes JSON strings without trimming, sorting/deduplicating tags, normalizing
Unicode, or converting signed 64-bit values through floating point. Decoding remains with the
existing validated engine adapters for this slice. Numeric coercion/default behavior on malformed
legacy usage fields is deliberately not changed in this structural migration.

## Production adapters

- JVM `EntryJson` validates portable metadata and uses its JSON patch to serialize issuer, label,
  tags and usage values. Existing field ordering and kind-specific writing remain intact. Public
  `InputPolicy` metadata constants delegate to the portable contract.
- `:ui-foundation` exports the domain API through `OfflineOTPPresentation`, without making the
  domain depend on UI. This is a transitional framework packaging choice, not a domain/UI edge.
- iOS `SharedEntryMetadataAdapter` converts a validated Swift `Entry` to the same Kotlin model,
  checks validation before invoking its serializer, overlays that metadata on the original Swift
  field bag, preserves explicit optional defaults, then revalidates the whole entry. The shared
  manual editor's add/edit save paths use this adapter. It does not make standalone SwiftPM tests
  depend on a UIKit/Compose binary.

The metadata model is intentionally **partial**. Never create a full entry from its JSON output.
Seed/key/password material, kind-specific parameters, explicit defaults and opaque nested/null
extension values must survive in the original record. This slice does not replace entry identity,
secret ownership, OTP rules, crypto, storage, or the vault format. Other iOS creation/import paths
still use the Swift engine and migrate in subsequent entry slices.

## Verification

- `PortableEntryMetadataTest` exercises collection ownership, bounds, Unicode/escaping, optional
  fields, exact Long extrema and redacted diagnostic text.
- `portable-entry-metadata.json` is a synthetic shared corpus with TOTP/HOTP, nested/null extensions,
  duplicate ordered tags, explicit defaults, control/Unicode text and integers above 2^53.
- Desktop `PortableEntryMetadataContractTest` round-trips the actual JVM entry/vault adapters.
- iOS app `SharedEntryMetadataAdapterTests` exercises the actual exported Kotlin model/serializer
  through the Swift adapter. XcodeGen bundles the same corpus into the **test** target; tests do
  not depend on a repository path existing on an iPhone or simulator.
- Architecture guards include the new module and intentional core/foundation dependency edges.
  The shared UI gate additionally compiles domain sources in isolation without platform stubs.

Linux JVM/source/stub checks do not compile the Kotlin/Native framework or execute Swift/XCTest.
Before removing the Swift metadata implementation, build the XCFramework and app with Xcode and
run the bundled adapter tests on a simulator/device. Existing golden vault/backup fixtures must
continue passing unchanged.

## Follow-through

The remaining entry model/document work is implemented in
[portable entry documents](portable-entry-documents.md): all-kind parameters, complete lossless
field bags, bounded decoding, JVM/iOS production adapters, native-copy preservation and all-kind
fixtures/tests. Native Apple and Android runtime validation is still an explicit release gate.
