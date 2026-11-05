import SwiftUI
import UniformTypeIdentifiers
import OfflineOTPKit

/// Backups, restores and links: the M4 surface.
///
/// The sheet only collects a password, a file and one switch. Every byte-level decision — the backup
/// framing, the merge rule, the plain-text decoder, the URI parser — lives in `OfflineOTPKit`, which
/// mirrors `:core`, so a file written here opens on the phone and a file written there opens here.
///
/// The wording is the other clients' wording: the **Replace existing entries** switch starts off
/// (merging), the plain-text warning is Android's sentence verbatim, and a merge reports only what it
/// added.
struct BackupView: View {
    @ObservedObject var store: VaultStore
    @Environment(\.dismiss) private var dismiss

    @State private var status: String?
    @State private var exportPassword = ""
    @State private var exportConfirmation = ""
    @State private var exportFile: BackupFile?
    @State private var exportName = ""
    @State private var showingExporter = false

    @State private var pickingEncrypted = false
    @State private var pickingPlainText = false
    @State private var pickingImage = false
    @State private var pending: PendingRestore?
    @State private var warningPlainText = false
    @State private var link = ""
    @State private var importRequest: ImportRequest?
    @State private var duplicateIssuer: String?

    /// An account waiting to be added: its text and whether it came from an image. Kept so the
    /// **Add anyway** answer can re-run the very same import.
    private struct ImportRequest {
        let text: String
        let fromImage: Bool
    }

    var body: some View {
        NavigationStack {
            Form {
                exportSection
                restoreSection
                linkSection
                imageSection

                if let status {
                    Section {
                        Text(status)
                            .font(.footnote)
                            .foregroundColor(ForestPalette.accent)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(ForestPalette.background)
            .navigationTitle("Backups and links")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .fileExporter(isPresented: $showingExporter, document: exportFile,
                          contentType: .data, defaultFilename: exportName) { result in
                switch result {
                case .success(let url):
                    status = "Encrypted backup written to \(url.lastPathComponent)"
                case .failure:
                    status = "The backup was not written."
                }
                exportFile = nil
            }
            .fileImporter(isPresented: $pickingEncrypted, allowedContentTypes: [.data],
                          allowsMultipleSelection: true) { result in
                handlePicked(result, kind: .encrypted)
            }
            .fileImporter(isPresented: $pickingPlainText, allowedContentTypes: [.json, .plainText],
                          allowsMultipleSelection: true) { result in
                handlePicked(result, kind: .plainText)
            }
            .fileImporter(isPresented: $pickingImage, allowedContentTypes: [.image],
                          allowsMultipleSelection: true) { result in
                handlePickedImage(result)
            }
            .sheet(item: $pending) { target in
                RestorePromptView(store: store, kind: target.kind, data: target.data) { summary in
                    status = summary
                    pending = nil
                } onCancel: {
                    pending = nil
                }
            }
            .alert("Restore plain-text backup?", isPresented: $warningPlainText) {
                Button("Choose a file") { pickingPlainText = true }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text(BackupWording.plainTextWarning)
            }
            .alert(VaultStore.ImportOutcome.duplicateIssuerTitle,
                   isPresented: Binding(get: { duplicateIssuer != nil },
                                        set: { if !$0 { duplicateIssuer = nil } })) {
                Button(VaultStore.ImportOutcome.addAnyway) { confirmDuplicateIssuer() }
                Button("Cancel", role: .cancel) { importRequest = nil }
            } message: {
                Text(VaultStore.ImportOutcome.duplicateIssuerMessage(duplicateIssuer ?? ""))
            }
        }
        .preferredColorScheme(.dark)
    }

    // MARK: - Sections

    private var exportSection: some View {
        Section {
            SecureField("Backup password", text: $exportPassword)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            SecureField("Confirm the password", text: $exportConfirmation)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("Create encrypted backup") { createBackup() }
                .disabled(exportPassword.isEmpty)
        } header: {
            Text("Export an encrypted backup")
        } footer: {
            // Both other clients ask for a separate backup password rather than reusing the vault's,
            // because a leaked backup must not open the vault.
            Text("The backup password protects this file and is not stored anywhere. It is not the "
                 + "vault password, and it cannot be recovered.")
        }
    }

    private var restoreSection: some View {
        Section {
            Button("Restore an encrypted backup…") { pickingEncrypted = true }
            Button("Restore a plain-text backup…") {
                // The warning comes before the file choice, as it does on the other clients.
                warningPlainText = true
            }
        } header: {
            Text("Restore")
        } footer: {
            Text("A restore merges by default: entries the vault already holds are kept exactly as "
                 + "they are, and new ones are added.")
        }
    }

    private var linkSection: some View {
        Section {
            TextField("otpauth://totp/…", text: $link)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(.system(.footnote, design: .monospaced))
            Button("Add an entry from the link") { addLink() }
                .disabled(link.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        } header: {
            Text("Add from a link")
        } footer: {
            Text("Paste an otpauth:// link from another authenticator. Nothing is fetched: the link "
                 + "is read here, offline.")
        }
    }

    private var imageSection: some View {
        Section {
            Button("Add an entry from a QR image…") { pickingImage = true }
        } header: {
            Text("Add from a picture")
        } footer: {
            Text("Choose a screenshot or photo of a QR code. Camera scanning arrives with the next "
                 + "milestone; this reads the image without asking for camera access.")
        }
    }

    // MARK: - Actions

    private func createBackup() {
        guard exportPassword == exportConfirmation else {
            status = "The two passwords do not match."
            return
        }
        guard let exported = store.exportBackup(password: exportPassword) else {
            status = store.message ?? "The backup could not be written."
            return
        }
        // The typed password is not kept in this view once the file exists.
        exportPassword = ""
        exportConfirmation = ""
        exportName = exported.name
        exportFile = BackupFile(data: exported.data)
        showingExporter = true
    }

    private func addLink() {
        let text = link.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        run(ImportRequest(text: text, fromImage: false))
    }

    /// Adds an account and reports what happened. A duplicate issuer stops here and asks; every other
    /// outcome is final.
    private func run(_ request: ImportRequest, allowDuplicateIssuer: Bool = false) {
        let outcome = request.fromImage
            ? store.importPayload(request.text, allowDuplicateIssuer: allowDuplicateIssuer)
            : store.importUri(request.text, allowDuplicateIssuer: allowDuplicateIssuer)
        switch outcome {
        case .added:
            status = request.fromImage ? "The code was added to the vault."
                : "The link was added to the vault."
            if !request.fromImage { link = "" }
            importRequest = nil
        case .duplicateIssuer(let issuer):
            importRequest = request
            duplicateIssuer = issuer
        case .duplicateEntry, .rejected:
            status = store.message ?? "That account could not be added."
            importRequest = nil
        }
    }

    private func confirmDuplicateIssuer() {
        duplicateIssuer = nil
        guard let request = importRequest else { return }
        run(request, allowDuplicateIssuer: true)
    }

    private func handlePicked(_ result: Result<[URL], Error>, kind: RestoreKind) {
        do {
            guard let url = try result.get().first else {
                status = "No file was chosen."
                return
            }
            // A picked file may live outside the app's sandbox; the scope has to be opened to read it.
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let data = try Data(contentsOf: url)
            pending = PendingRestore(kind: kind, data: data)
        } catch {
            status = "The file could not be read."
        }
    }

    private func handlePickedImage(_ result: Result<[URL], Error>) {
        do {
            guard let url = try result.get().first else {
                status = "No image was chosen."
                return
            }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let payload = QrImageReader.payload(in: url) else {
                status = "No QR code was found in that image."
                return
            }
            run(ImportRequest(text: payload, fromImage: true))
        } catch {
            status = "The image could not be read."
        }
    }
}

/// A file that has been read but not yet applied: the password and the merge choice come next.
struct PendingRestore: Identifiable {
    let id = UUID()
    let kind: RestoreKind
    let data: Data
}

/// Which parser a picked file goes through.
enum RestoreKind: Equatable {
    case encrypted
    case plainText
}

/// A file handed to the system's exporter. The bytes are already encrypted; nothing here decrypts.
struct BackupFile: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }
    static var writableContentTypes: [UTType] { [.data] }

    var data: Data

    init(data: Data) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

/// The password and the merge choice a restore needs, asked in one place.
///
/// The switch is the **Replace existing entries** switch from Android's backup screen, off by
/// default, with the same explanation underneath.
struct RestorePromptView: View {
    @ObservedObject var store: VaultStore
    let kind: RestoreKind
    let data: Data
    let onFinished: (String) -> Void
    let onCancel: () -> Void

    @State private var password = ""
    @State private var replace = false
    @State private var errorText: String?

    var body: some View {
        NavigationStack {
            Form {
                if kind == .encrypted {
                    Section {
                        SecureField("Backup password", text: $password)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    } footer: {
                        Text("The password this backup was created with.")
                    }
                }
                Section {
                    Toggle(BackupWording.replaceSwitch, isOn: $replace)
                } footer: {
                    Text(BackupWording.replaceSwitchDetail)
                }
                if let errorText {
                    Section {
                        Text(errorText).foregroundColor(ForestPalette.warning)
                    }
                }
                Section {
                    Text(BackupWording.restoreQuestion)
                        .font(.footnote)
                        .foregroundColor(ForestPalette.textSecondary)
                }
            }
            .scrollContentBackground(.hidden)
            .background(ForestPalette.background)
            .navigationTitle(kind == .encrypted ? "Restore encrypted backup"
                                                : "Restore plain-text backup")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Restore", action: restore)
                        .disabled(kind == .encrypted && password.isEmpty)
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    private func restore() {
        let plan: RestorePlan?
        switch kind {
        case .encrypted:
            plan = store.restore(encrypted: data, password: password, replace: replace)
        case .plainText:
            plan = store.restore(plainText: data, replace: replace)
        }
        guard let plan else {
            errorText = store.message ?? BackupWording.unreadableBackup
            return
        }
        password = ""
        onFinished(BackupWording.restoreSummary(plan, replace: replace))
    }
}
