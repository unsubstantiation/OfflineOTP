import SwiftUI

/// The lock screen: one password field, and the same minimum length and confirmation the other
/// clients apply when a vault is created.
struct UnlockView: View {
    @ObservedObject var store: VaultStore

    @State private var password = ""
    @State private var confirmation = ""
    @State private var creating = false
    @FocusState private var passwordFocused: Bool

    private var isCreating: Bool { creating || !store.hasVault }

    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            Image(systemName: "lock.shield.fill")
                .font(.system(size: 52, weight: .light))
                .foregroundColor(ForestPalette.accent)

            Text("OfflineOTP")
                .font(.largeTitle.weight(.semibold))
                .foregroundColor(ForestPalette.textPrimary)

            Text(isCreating
                 ? "Choose a password for a new vault. It cannot be recovered if you forget it."
                 : "Enter your vault password.")
                .font(.subheadline)
                .multilineTextAlignment(.center)
                .foregroundColor(ForestPalette.textSecondary)
                .padding(.horizontal, 32)

            SecureField("Password", text: $password)
                .textContentType(isCreating ? .newPassword : .password)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($passwordFocused)
                .padding(14)
                .background(ForestPalette.surface)
                .overlay(RoundedRectangle(cornerRadius: 10)
                    .stroke(ForestPalette.divider, lineWidth: 1))
                .cornerRadius(10)
                .foregroundColor(ForestPalette.textPrimary)

            if isCreating {
                SecureField("Confirm password", text: $confirmation)
                    .textContentType(.newPassword)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(14)
                    .background(ForestPalette.surface)
                    .overlay(RoundedRectangle(cornerRadius: 10)
                        .stroke(ForestPalette.divider, lineWidth: 1))
                    .cornerRadius(10)
                    .foregroundColor(ForestPalette.textPrimary)
            }

            if let message = store.message {
                Text(message)
                    .font(.footnote)
                    .foregroundColor(ForestPalette.warning)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
            }

            Button(action: submit) {
                Text(isCreating ? "Create vault" : "Unlock")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(ForestPalette.primary)
                    .foregroundColor(ForestPalette.onPrimary)
                    .cornerRadius(10)
            }
            .disabled(password.isEmpty)
            .padding(.horizontal, 32)

            Text(store.folderPath)
                .font(.caption2.monospaced())
                .foregroundColor(ForestPalette.textHint)
                .lineLimit(1)
                .truncationMode(.middle)
                .padding(.horizontal, 24)
            Spacer()
        }
        .padding(.horizontal, 24)
        .onAppear {
            creating = !store.hasVault
            passwordFocused = true
        }
    }

    private func submit() {
        store.unlock(password: password, confirmation: confirmation, creating: isCreating)
        if store.isUnlocked {
            password = ""
            confirmation = ""
            creating = false
        }
    }
}
