import SwiftUI
import OfflineOTPKit

/// The settings sheet: the one preference this build has, plus where the vault lives.
///
/// **Unlock with Face ID** is off until it is turned on, and turning it on asks for the vault password
/// even though the vault is already open. That is the rule this screen exists to enforce: an unlocked
/// phone must not be enough to add a face that keeps access after the vault is locked again. Turning it
/// off asks nothing — it only takes a shortcut away.
struct SettingsView: View {
    @ObservedObject var store: VaultStore
    @Environment(\.dismiss) private var dismiss

    @State private var askingPassword = false
    @State private var password = ""
    @FocusState private var passwordFocused: Bool

    private var switchTitle: String { "Unlock with \\(store.biometryTitle)" }

    var body: some View {
        NavigationStack {
            Form {
                unlockSection
                vaultSection
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
                            // The toggle only moves once the password has confirmed the choice.
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
                    Button("Turn on \\(store.biometryTitle)") { confirm() }
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

    private var vaultSection: some View {
        Section {
            LabeledContent("Entries", value: "\\(store.entries.count)")
            Text(store.folderPath)
                .font(.caption2.monospaced())
                .foregroundColor(ForestPalette.textSecondary)
                .lineLimit(1)
                .truncationMode(.middle)
        } header: {
            Text("Vault")
        } footer: {
            Text("The folder holding \\(VaultStore.vaultFilename) and its settings.")
        }
    }

    private func confirm() {
        // The key is only filed when the password opens *this* vault; a wrong one leaves the switch
        // off and says so.
        if store.enableBiometricUnlock(password: password) {
            askingPassword = false
            password = ""
        }
    }
}
