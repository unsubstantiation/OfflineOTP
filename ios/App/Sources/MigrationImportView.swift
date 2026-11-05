import SwiftUI
import OfflineOTPKit

/// The Google Authenticator export sheet: where the export has got to, and — once every code has
/// arrived — the accounts it holds, awaiting one confirmation.
///
/// The sheet decides nothing. Whether a code belongs to the export, whether it was already scanned,
/// whether the export is complete and what an identical account means are all
/// `MigrationBatchCollector`'s and `VaultStore`'s, which mirror Android's; this view shows the
/// progress sentence, the preview names and two buttons.
struct MigrationImportView: View {
    /// How many of the export's codes have been read, and how many it has.
    let received: Int
    let expected: Int
    /// The accounts, once the export is complete; nil while codes are still missing.
    let entries: [Entry]?

    let onNextCode: () -> Void
    let onImport: () -> Void
    let onCancel: () -> Void

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text(MigrationWording.title)
                    .font(.headline)
                    .foregroundColor(ForestPalette.textPrimary)

                if let entries {
                    ScrollView {
                        Text(MigrationWording.confirm(count: entries.count))
                            .font(.subheadline)
                            .foregroundColor(ForestPalette.textPrimary)
                        ForEach(Array(entries.enumerated()), id: \.offset) { _, entry in
                            Text(name(of: entry))
                                .font(.footnote)
                                .foregroundColor(ForestPalette.textSecondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        Text(MigrationWording.duplicateNotice)
                            .font(.footnote)
                            .foregroundColor(ForestPalette.textSecondary)
                            .padding(.top, 8)
                    }
                } else {
                    Text(MigrationWording.progress(received: received, expected: expected))
                        .font(.subheadline)
                        .foregroundColor(ForestPalette.textPrimary)
                    Text(MigrationWording.expectedQR)
                        .font(.footnote)
                        .foregroundColor(ForestPalette.textSecondary)
                }

                Spacer(minLength: 0)

                if entries == nil {
                    Button("Choose the next QR code…", action: onNextCode)
                        .buttonStyle(.borderedProminent)
                        .tint(ForestPalette.accent)
                } else {
                    Button("Import accounts", action: onImport)
                        .buttonStyle(.borderedProminent)
                        .tint(ForestPalette.accent)
                }
                Button("Cancel", role: .cancel, action: onCancel)
            }
            .padding(20)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(ForestPalette.background)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", action: onCancel)
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    /// The name a preview line shows: the issuer and the label, never a seed.
    private func name(of entry: Entry) -> String {
        let parts = [entry.issuer, entry.label].filter {
            !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        return parts.isEmpty ? MigrationWording.unnamed : parts.joined(separator: " — ")
    }
}
