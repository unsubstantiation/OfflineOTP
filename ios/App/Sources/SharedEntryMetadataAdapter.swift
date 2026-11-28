import Foundation
import OfflineOTPKit
import OfflineOTPPresentation

/// Temporary bridge for chunk 2. Kotlin owns the portable metadata value/serializer;
/// Swift still owns kind-specific validation, seeds and opaque extension fields.
/// Never construct a complete Entry from the metadata-only JSON patch.
enum SharedEntryMetadataAdapter {
    static func snapshot(_ entry: Entry) -> EntryMetadata {
        EntryMetadata(issuer: entry.issuer, label: entry.label, hiddenLabel: entry.hiddenLabel,
            tags: entry.tags, lastUsed: entry.lastUsed, usedFrequency: entry.usedFrequency,
            detailsVisible: entry.detailsVisible)
    }

    static func roundTrip(_ entry: Entry) throws -> Entry {
        let metadata = snapshot(entry)
        // Kotlin exceptions must not escape across the ObjC boundary. Check before toJson().
        guard metadata.validationError() == nil,
              let patch = try JSONValue.decode(metadata.toJson()).objectValue else {
            throw VaultDocumentError.invalidEntry
        }
        var fields = entry.fields
        for (name, value) in patch { fields[name] = value }
        // Preserve explicit defaults when present, rather than dropping fields during migration.
        if fields["hidden_label"] != nil { fields["hidden_label"] = .string(metadata.hiddenLabel) }
        if fields["details_visible"] != nil { fields["details_visible"] = .bool(metadata.detailsVisible) }
        var result = try Entry.parse(.object(fields))
        try result.attachValidatedExtensionFields(entry.validatedExtensionFields)
        return result
    }
}
