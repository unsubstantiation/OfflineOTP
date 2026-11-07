import SwiftUI
import UniformTypeIdentifiers
import OfflineOTPKit

/// The exported preferences file, handed to the system's exporter. Plain JSON by design — this is
/// the one document the app writes unencrypted, which is why the backup password is opt-in.
struct PreferencesDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    static var writableContentTypes: [UTType] { [.json] }

    var data: Data

    init(data: Data) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

/// Settings sheet: biometric quick unlock, vault password change, inactivity auto-lock, vault
/// diagnostics, and offline About information.
struct SettingsView: View {
    @ObservedObject var store: VaultStore
    @Environment(\.dismiss) private var dismiss

    @State private var askingPassword = false
    @State private var password = ""
    @FocusState private var passwordFocused: Bool

    @State private var currentVaultPassword = ""
    @State private var newVaultPassword = ""
    @State private var confirmVaultPassword = ""

    @State private var preferencesExport: PreferencesDocument?
    @State private var exportingPreferences = false
    @State private var importingPreferences = false
    @State private var preferencesMessage: String?

    private var switchTitle: String { "Unlock with \(store.biometryTitle)" }

    private static let autoLockChoices: [(seconds: Int, label: String)] = [
        (0, "Disabled"),
        (30, "30 seconds"),
        (60, "1 minute"),
        (120, "2 minutes"),
        (300, "5 minutes"),
        (900, "15 minutes"),
    ]

    var body: some View {
        NavigationStack {
            Form {
                unlockSection
                autoLockSection
                passwordSection
                vaultSection
                preferencesSection
                aboutSection
                if let message = store.message {
                    Section {
                        Text(message).font(.footnote).foregroundColor(ForestPalette.accent)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(ForestPalette.background)
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    private var unlockSection: some View {
        Section {
            if store.biometry.isAvailable {
                Toggle(switchTitle, isOn: Binding(
                    get: { store.biometricUnlockEnabled },
                    set: { on in
                        store.clearMessage()
                        if on {
                            askingPassword = true
                            passwordFocused = true
                        } else {
                            askingPassword = false
                            password = ""
                            store.disableBiometricUnlock()
                        }
                    }
                ))
                .tint(ForestPalette.primary)
                .disabled(askingPassword)

                if askingPassword {
                    SecureField("Vault password", text: $password)
                        .textContentType(.password)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .focused($passwordFocused)
                    Button("Turn on \(store.biometryTitle)") { confirm() }
                        .disabled(password.isEmpty)
                    Button("Cancel", role: .cancel) {
                        askingPassword = false
                        password = ""
                        store.clearMessage()
                    }
                }
            } else {
                Text(store.biometry.refusal)
                    .font(.footnote)
                    .foregroundColor(ForestPalette.textSecondary)
            }
        } header: {
            Text("Quick unlock")
        } footer: {
            if store.biometry.isAvailable {
                Text(askingPassword ? BiometricWording.enableQuestion
                                    : BiometricWording.switchDetail)
            }
        }
    }

    private var autoLockSection: some View {
        Section {
            Picker("Auto-lock after inactivity", selection: Binding(
                get: { store.inactivityLockSeconds },
                set: { store.setInactivityLockSeconds($0) }
            )) {
                ForEach(SettingsView.autoLockChoices, id: \.seconds) { option in
                    Text(option.label).tag(option.seconds)
                }
            }
        } header: {
            Text("Security")
        } footer: {
            Text("Automatically locks the vault when no interaction occurs for the chosen interval.")
        }
    }

    private var passwordSection: some View {
        Section {
            SecureField("Current vault password", text: $currentVaultPassword)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            SecureField("New vault password", text: $newVaultPassword)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            SecureField("Confirm new password", text: $confirmVaultPassword)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("Change vault password") { changeVaultPassword() }
                .disabled(currentVaultPassword.isEmpty || newVaultPassword.isEmpty || confirmVaultPassword.isEmpty)
        } header: {
            Text("Vault Password")
        } footer: {
            Text("Re-encrypts the vault under Argon2id + AES-256-GCM with a fresh random salt.")
        }
    }

    private var vaultSection: some View {
        Section {
            LabeledContent("Entries", value: "\(store.entries.count)")
            Text(store.folderPath)
                .font(.caption2.monospaced())
                .foregroundColor(ForestPalette.textSecondary)
                .lineLimit(1)
                .truncationMode(.middle)
        } header: {
            Text("Vault")
        } footer: {
            Text("The folder holding \(VaultStore.vaultFilename) and its settings.")
        }
    }

    private var preferencesSection: some View {
        Section {
            Button("Export preferences…") { exportPreferences() }
            Button("Import preferences…") { importingPreferences = true }
            if let preferencesMessage {
                Text(preferencesMessage)
                    .font(.footnote)
                    .foregroundColor(ForestPalette.textSecondary)
            }
        } header: {
            Text("Preferences")
        } footer: {
            Text("Moves the app settings this client can carry — the inactivity delay — between iOS "
                 + "installs. The vault, its password and its accounts are never included, and a file "
                 + "written by another client is refused rather than half-applied.")
        }
        .fileExporter(isPresented: $exportingPreferences, document: preferencesExport,
                      contentType: .json, defaultFilename: "offlineotp-preferences.json") { result in
            switch result {
            case .success(let url):
                preferencesMessage = "Preferences exported to \(url.lastPathComponent)."
            case .failure:
                preferencesMessage = "The preferences could not be exported."
            }
        }
        .fileImporter(isPresented: $importingPreferences, allowedContentTypes: [.json]) { result in
            switch result {
            case .success(let url):
                importPreferences(from: url)
            case .failure:
                preferencesMessage = "The preferences could not be imported."
            }
        }
    }

    private func exportPreferences() {
        do {
            let export = try PreferencesExport.export(store.preferencesPort)
            preferencesExport = PreferencesDocument(data: export.bytes)
            exportingPreferences = true
        } catch {
            preferencesMessage = "The preferences could not be exported."
        }
    }

    private func importPreferences(from url: URL) {
        // A file picked outside the app's container needs an explicit security scope.
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let data = try Data(contentsOf: url)
            let result = try PreferencesExport.apply(data, into: store.preferencesPort)
            let skipped = result.unknownKeys.isEmpty ? ""
                : " \(result.unknownKeys.count) settings are not known to this version and were skipped."
            preferencesMessage = "\(result.applied) settings imported.\(skipped)"
            // The auto-lock picker must reflect a value that just changed underneath it.
            store.reloadInactivityLock()
        } catch PreferencesExport.PreferencesError.wrongPlatform(let source, _) {
            preferencesMessage = "That file holds \(source) preferences; this client can only import iOS ones."
        } catch {
            preferencesMessage = "That file is not an OfflineOTP preferences file this version can read."
        }
    }

    private var aboutSection: some View {
        Section {
            LabeledContent("Application", value: "OfflineOTP")
            LabeledContent("Cryptography", value: "Argon2id + AES-256-GCM")
            LabeledContent("Network access", value: "None (100% offline)")
            Text("Compatible with OfflineOTP on Android and Desktop, FairEmail, Thunderbird, and LinSCP. "
                 + "Originally derived from andOTP under the MIT License.")
                .font(.footnote)
                .foregroundColor(ForestPalette.textSecondary)
        } header: {
            Text("About")
        }
    }

    private func confirm() {
        if store.enableBiometricUnlock(password: password) {
            askingPassword = false
            password = ""
        }
    }

    private func changeVaultPassword() {
        if store.changePassword(
            currentPassword: currentVaultPassword,
            newPassword: newVaultPassword,
            confirmation: confirmVaultPassword
        ) {
            currentVaultPassword = ""
            newVaultPassword = ""
            confirmVaultPassword = ""
        }
    }
}
