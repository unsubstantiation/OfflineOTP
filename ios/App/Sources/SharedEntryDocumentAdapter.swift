import Foundation
import OfflineOTPKit
import OfflineOTPPresentation

/// Full production document boundary. Kotlin owns JSON syntax, bounds, field bags, kinds and
/// parameters. Swift material codecs still validate the decoded secret payloads, not vice versa.
/// No process-global mutable codec or silent fallback to the standalone Swift oracle.
struct SharedEntryDocumentAdapter: EntryDocumentAdapter {
    private func shared(_ text: String) throws -> String {
        guard let result = EntryDocumentCodec().roundTrip(text: text) else {
            throw VaultDocumentError.invalidEntry
        }
        return result
    }
    func decode(_ text: String) throws -> [Entry] {
        guard let document = EntryDocumentCodec().decode(text: text).document else {
            throw VaultDocumentError.invalidEntry
        }
        defer { document.destroy() }
        return try document.entries.map { record in
            guard CredentialMaterialCodec().validateEncoded(kind: record.parameters.kind.name, base32: record.encodedPayload) else {
                throw VaultDocumentError.invalidEntry
            }
            guard let known = record.materialJson(),
                  var entry = try VaultDocument.decode("[" + known + "]").first else {
                throw VaultDocumentError.invalidEntry
            }
            try entry.attachValidatedExtensionFields(record.extensionFieldsJson())
            return entry
        }
    }
    func encode(_ entries: [Entry]) throws -> String {
        // Native material/entry validation remains mandatory on writes as well as reads.
        let text = try VaultDocument.encode(entries)
        _ = try decode(text)
        return try shared(text)
    }
}
