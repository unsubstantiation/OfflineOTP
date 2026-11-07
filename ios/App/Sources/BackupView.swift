import SwiftUI
import UniformTypeIdentifiers
import OfflineOTPKit

/// Backups, restores and links: the M4 surface.
///
/// The sheet only collects a password, a file and one switch. Every byte-level decision — the backup
/// framing, the merge rule, the URI parser — lives in `OfflineOTPKit`, which
/// mirrors `:core`, so a file written here opens on the phone and a file written there opens here.
///
/// The wording is the other clients' wording: the **Replace existing entries** switch starts off
/// (merging), a merge reports only what it
/// added.
struct BackupView: View {
    @ObservedObject var store: VaultStore
    @Environment(\.dismiss) private var dismiss

    @State private var status: String?
    @State private var exportPassword = ""
    @State private var exportConfirmation = ""
    @State private var linScpExportPassword = ""
    @State private var linScpExportConfirmation = ""
    @State private var exportFile: BackupFile?
    @State private var exportName = ""
    @State private var showingExporter = false

    @State private var pickingEncrypted = false
    @State private var pickingLinScp = false
    @State private var pickingOpenPgpRecipient = false
    @State private var pickingOpenPgpMessage = false
    @State private var pickingOpenPgpSecretKey = false
    @State private var pickingPasswordsIndividual = false
    @State private var pickingPasswordsArchive = false
    @State private var pickingFairEmail = false
    @State private var pickingThunderbird = false
    @State private var pickingImage = false
    @State private var stagedOpenPgpMessage: Data?
    @State private var pending: PendingRestore?
    @State private var link = ""
    @State private var importRequest: ImportRequest?
    @State private var duplicateIssuer: String?
    @State private var migration: MigrationPrompt?

    /// An account waiting to be added: its text and whether it came from an image. Kept so the
    /// **Add anyway** answer can re-run the very same import.
    private struct ImportRequest {
        let text: String
        let fromImage: Bool
    }

    /// A Google Authenticator export in progress, or complete and awaiting confirmation.
    private struct MigrationPrompt: Identifiable {
        let id = UUID()
        let received: Int
        let expected: Int
        let entries: [Entry]?
    }

    var body: some View {
        NavigationStack {
            Form {
                exportSection
                restoreSection
                openPgpSection
                passwordsSection
                fairEmailSection
                thunderbirdSection
                linScpSection
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
                    status = "Export written to \(url.lastPathComponent)"
                case .failure:
                    status = "The export was not written."
                }
                exportFile = nil
            }
            .fileImporter(isPresented: $pickingEncrypted, allowedContentTypes: [.data],
                          allowsMultipleSelection: true) { result in
                handlePicked(result, kind: .encrypted)
            }
            .fileImporter(isPresented: $pickingLinScp, allowedContentTypes: [.data],
                          allowsMultipleSelection: true) { result in
                handlePicked(result, kind: .linScp)
            }
            .fileImporter(isPresented: $pickingOpenPgpRecipient, allowedContentTypes: [.data],
                          allowsMultipleSelection: true) { result in
                handlePickedOpenPgpRecipients(result)
            }
            .fileImporter(isPresented: $pickingOpenPgpMessage, allowedContentTypes: [.data],
                          allowsMultipleSelection: false) { result in
                handlePickedOpenPgpMessage(result)
            }
            .fileImporter(isPresented: $pickingOpenPgpSecretKey, allowedContentTypes: [.data],
                          allowsMultipleSelection: false) { result in
                handlePickedOpenPgpSecretKey(result)
            }
            .fileImporter(isPresented: $pickingPasswordsIndividual, allowedContentTypes: [.data],
                          allowsMultipleSelection: false) { result in
                handlePickedPasswords(result, mode: .individual)
            }
            .fileImporter(isPresented: $pickingPasswordsArchive, allowedContentTypes: [.data],
                          allowsMultipleSelection: false) { result in
                handlePickedPasswords(result, mode: .archive)
            }
            .fileImporter(isPresented: $pickingFairEmail, allowedContentTypes: [.data],
                          allowsMultipleSelection: false) { result in
                handlePicked(result, kind: .fairEmail)
            }
            .fileImporter(isPresented: $pickingThunderbird, allowedContentTypes: [.data],
                          allowsMultipleSelection: false) { result in
                handlePicked(result, kind: .thunderbird)
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
            .sheet(item: $migration) { prompt in
                MigrationImportView(received: prompt.received, expected: prompt.expected,
                                    entries: prompt.entries) {
                    // The next code of the export comes from the same image picker: the export is
                    // still open in the store, so the code is added to it rather than starting over.
                    migration = nil
                    pickingImage = true
                } onImport: {
                    importMigration(prompt.entries ?? [])
                } onCancel: {
                    store.cancelMigrationImport()
                    migration = nil
                }
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
            Button("Create Full Encrypted Backup") { createBackup() }
                .disabled(exportPassword.isEmpty)
        } header: {
            Text("Export a Full Encrypted Backup")
        } footer: {
            // Both other clients ask for a separate backup password rather than reusing the vault's,
            // because a leaked backup must not open the vault.
            Text("The backup password protects this file and is not stored anywhere. It is not the "
                 + "vault password, and it cannot be recovered.")
        }
    }

    private var restoreSection: some View {
        Section {
            Button("Restore a Full Encrypted Backup…") { pickingEncrypted = true }
        } header: {
            Text("Restore")
        } footer: {
            Text("A restore merges by default: entries the vault already holds are kept exactly as "
                 + "they are, and new ones are added.")
        }
    }

    private var openPgpSection: some View {
        let availability = store.openPgpAvailability
        return Section {
            Button("Export OpenPGP backup (.json.gpg)…") { pickingOpenPgpRecipient = true }
                .disabled(!availability.canEncrypt)
            Button("Restore OpenPGP backup…") { pickingOpenPgpMessage = true }
                .disabled(!availability.canRestore)
        } header: {
            Text("OpenPGP Backup")
        } footer: {
            // Android disables both buttons and explains why rather than offering an action that
            // cannot succeed; this is the same rule on iOS.
            if availability.canEncrypt || availability.canRestore {
                Text("Encrypts the vault to one or more OpenPGP recipient public keys, or restores an "
                     + "encrypted .json.gpg message with your OpenPGP secret key.")
            } else {
                Text("OpenPGP backups are not available in this build because it does not include the "
                     + "OpenPGP engine. Use a Full Encrypted Backup instead, or install a build that "
                     + "includes it.")
            }
        }
    }

    private var passwordsSection: some View {
        Section {
            Button("Import browser passwords CSV (individual)…") { pickingPasswordsIndividual = true }
            Button("Export browser passwords CSV…", action: exportBrowserPasswords)
            Button("Archive password file into vault…") { pickingPasswordsArchive = true }
        } header: {
            Text("Browser Passwords")
        } footer: {
            Text("Imports Chrome, Firefox, or Safari CSV rows as individual searchable password "
                 + "entries, exports revealed browser-imported passwords back to CSV, or stores a "
                 + "password file byte-for-byte inside the encrypted vault. Exported CSV files "
                 + "contain unencrypted passwords.")
        }
    }

    private var fairEmailSection: some View {
        Section {
            Button("Import FairEmail backup…") { pickingFairEmail = true }
            Button("Export FairEmail backup…") { createFairEmailExport() }
        } header: {
            Text("FairEmail Accounts")
        } footer: {
            Text("Imports FairEmail .json, gzipped JSON, or encrypted .efb backups, and exports "
                 + "email accounts in FairEmail's portable restore format.")
        }
    }

    private var thunderbirdSection: some View {
        Section {
            Button("Import Thunderbird credentials (.csv)…") { pickingThunderbird = true }
            Button("Export Thunderbird credentials (.csv)…") { createThunderbirdExport() }
        } header: {
            Text("Thunderbird Mail")
        } footer: {
            Text("Bidirectional CSV interchange with Thunderbird: email accounts round-trip "
                 + "between FairEmail and Thunderbird through the same vault entries.")
        }
    }

    private var linScpSection: some View {
        Section {
            Button("Import LinSCP SSH & sessions…") { pickingLinScp = true }
            SecureField("LinSCP vault password", text: $linScpExportPassword)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            SecureField("Confirm LinSCP password", text: $linScpExportConfirmation)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("Export to LinSCP encrypted vault") { createLinScpExport() }
                .disabled(linScpExportPassword.isEmpty)
        } header: {
            Text("LinSCP SSH & Sessions")
        } footer: {
            Text("Imports LinSCP encrypted credential vaults (.json.aes), BackupBundle JSON, or "
                 + "sessions.ini, and exports SSH keys and login sessions in LinSCP's Argon2id + "
                 + "AES-256-GCM vault format.")
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
            .accessibilityAddTraits(.isHeader)
        } footer: {
            Text("Choose a screenshot or photo of a QR code. A Google Authenticator export is read "
                 + "one code at a time: choose each code from the same export and the accounts are "
                 + "imported together once the last one is in. This reads the image without asking "
                 + "for camera access.")
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

    private func createLinScpExport() {
        guard linScpExportPassword == linScpExportConfirmation else {
            status = "The two passwords do not match."
            return
        }
        guard let exported = store.exportLinScpVault(password: linScpExportPassword) else {
            status = store.message ?? "The LinSCP vault could not be written."
            return
        }
        linScpExportPassword = ""
        linScpExportConfirmation = ""
        exportName = exported.name
        exportFile = BackupFile(data: exported.data)
        showingExporter = true
    }

    private func createFairEmailExport() {
        guard let exported = store.exportFairEmailBackup() else {
            status = store.message ?? "The FairEmail backup could not be written."
            return
        }
        exportName = exported.name
        exportFile = BackupFile(data: exported.data)
        showingExporter = true
    }

    private func createThunderbirdExport() {
        guard let exported = store.exportThunderbirdBackup() else {
            status = store.message ?? "The Thunderbird export could not be written."
            return
        }
        exportName = exported.name
        exportFile = BackupFile(data: exported.data)
        showingExporter = true
    }

    private func handlePickedOpenPgpRecipients(_ result: Result<[URL], Error>) {
        do {
            let urls = try result.get()
            guard !urls.isEmpty else {
                status = "No recipient key file was chosen."
                return
            }
            var blocks: [Data] = []
            for url in urls {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                blocks.append(try Data(contentsOf: url))
            }
            guard let exported = store.exportOpenPgpBackup(recipientKeyBlocks: blocks) else {
                status = store.message ?? "The OpenPGP backup could not be created."
                return
            }
            exportName = exported.name
            exportFile = BackupFile(data: exported.data)
            showingExporter = true
        } catch {
            status = "The recipient key file could not be read."
        }
    }

    private func handlePickedOpenPgpMessage(_ result: Result<[URL], Error>) {
        do {
            guard let url = try result.get().first else {
                status = "No OpenPGP backup file was chosen."
                return
            }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            stagedOpenPgpMessage = try Data(contentsOf: url)
            pickingOpenPgpSecretKey = true
        } catch {
            status = "The OpenPGP backup file could not be read."
        }
    }

    private func handlePickedOpenPgpSecretKey(_ result: Result<[URL], Error>) {
        guard let messageData = stagedOpenPgpMessage else { return }
        stagedOpenPgpMessage = nil
        do {
            guard let url = try result.get().first else {
                status = "No OpenPGP secret key file was chosen."
                return
            }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let secretKey = try Data(contentsOf: url)
            pending = PendingRestore(kind: .openPgp(secretKey: secretKey), data: messageData)
        } catch {
            status = "The OpenPGP secret key file could not be read."
        }
    }

    private func handlePickedPasswords(_ result: Result<[URL], Error>, mode: PasswordImport.Mode) {
        do {
            guard let url = try result.get().first else {
                status = "No password file was chosen."
                return
            }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let data = try Data(contentsOf: url)
            if let plan = store.importBrowserPasswords(filename: url.lastPathComponent, data: data, mode: mode) {
                status = BackupWording.restoreSummary(plan, replace: false)
            } else {
                status = store.message ?? "That password file could not be imported."
            }
        } catch {
            status = "The password file could not be read."
        }
    }

    private func exportBrowserPasswords() {
        status = nil
        store.clearMessage()
        guard let backup = store.exportBrowserPasswordsCsv() else {
            status = store.message
            return
        }
        exportName = backup.name
        exportFile = BackupFile(data: backup.data)
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
        case .migration(let step):
            importRequest = nil
            show(step)
        case .duplicateEntry, .rejected:
            status = store.message ?? "That account could not be added."
            importRequest = nil
        }
    }

    /// Shows where an export has got to: the confirmation once every code has arrived, the progress
    /// sentence while codes are still missing, or a sentence when a code was refused. A named refusal
    /// keeps the export open, exactly as Android's toast does, so the next code can still complete it.
    private func show(_ step: VaultStore.MigrationImportStep) {
        if let entries = step.entries {
            migration = MigrationPrompt(received: step.received, expected: step.expected,
                                        entries: entries)
        } else if step.expected > 1 {
            migration = MigrationPrompt(received: step.received, expected: step.expected,
                                        entries: nil)
        } else {
            migration = nil
            status = step.message ?? store.message
        }
    }

    private func importMigration(_ entries: [Entry]) {
        migration = nil
        let result = store.confirmMigrationImport(entries)
        status = result.message
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
            if kind == .linScp, !store.isLinScpEncryptedVault(data),
               !store.needsLinScpMasterKey(data) {
                if let plan = store.importLinScp(data: data, replace: false) {
                    status = BackupWording.restoreSummary(plan, replace: false)
                } else {
                    status = store.message ?? BackupWording.unreadableBackup
                }
                return
            }
            if kind == .fairEmail, !store.needsFairEmailPassword(data) {
                if let plan = store.importFairEmailBackup(data: data, replace: false) {
                    status = BackupWording.restoreSummary(plan, replace: false)
                } else {
                    status = store.message ?? "That file could not be read as a FairEmail backup."
                }
                return
            }
            if kind == .thunderbird {
                if let plan = store.importThunderbirdBackup(data: data, replace: false) {
                    status = BackupWording.restoreSummary(plan, replace: false)
                } else {
                    status = store.message ?? "That file is not a valid Thunderbird CSV export."
                }
                return
            }
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
    case linScp
    case fairEmail
    case thunderbird
    case openPgp(secretKey: Data)
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

    private var requiresPassword: Bool {
        switch kind {
        case .openPgp, .thunderbird: return false
        case .encrypted, .linScp, .fairEmail: return true
        }
    }

    private var promptTitle: String {
        switch kind {
        case .encrypted: return "Restore Full Encrypted Backup"
        case .linScp: return "Import LinSCP Credentials"
        case .fairEmail: return "Import FairEmail Backup"
        case .thunderbird: return "Import Thunderbird CSV"
        case .openPgp: return "Restore OpenPGP Backup"
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                switch kind {
                case .encrypted:
                    Section {
                        SecureField("Backup password", text: $password)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    } footer: {
                        Text("The password this backup was created with.")
                    }
                case .linScp:
                    Section {
                        SecureField(store.isLinScpEncryptedVault(data)
                                    ? "LinSCP vault password"
                                    : "Base64 LinSCP sessions.key",
                                    text: $password)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    } footer: {
                        Text(store.isLinScpEncryptedVault(data)
                             ? "The password this LinSCP credential vault was exported with."
                             : "The Base64 sessions.key needed to decrypt lscp1$ saved passwords.")
                    }
                case .fairEmail:
                    Section {
                        SecureField("FairEmail backup password", text: $password)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    } footer: {
                        Text("The password this FairEmail .efb backup was encrypted with.")
                    }
                case .openPgp:
                    Section {
                        SecureField("Secret key passphrase (leave empty if unencrypted)", text: $password)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    } footer: {
                        Text("Optional passphrase for the selected OpenPGP secret key.")
                    }
                case .thunderbird:
                    EmptyView()
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
            .navigationTitle(promptTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(kind == .encrypted ? "Restore" : "Import", action: restore)
                        .disabled(requiresPassword && password.isEmpty)
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
        case .linScp:
            if store.isLinScpEncryptedVault(data) {
                plan = store.importLinScp(data: data, password: password, replace: replace)
            } else {
                plan = store.importLinScp(data: data, masterKeyText: password, replace: replace)
            }
        case .fairEmail:
            plan = store.importFairEmailBackup(data: data, password: password, replace: replace)
        case .thunderbird:
            plan = store.importThunderbirdBackup(data: data, replace: replace)
        case .openPgp(let secretKey):
            plan = store.restoreOpenPgpBackup(
                message: data,
                secretKeyBlock: secretKey,
                passphrase: password.isEmpty ? nil : password,
                replace: replace
            )
        }
        guard let plan else {
            errorText = store.message ?? BackupWording.unreadableBackup
            return
        }
        password = ""
        onFinished(BackupWording.restoreSummary(plan, replace: replace))
    }
}
