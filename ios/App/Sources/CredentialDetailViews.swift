import SwiftUI
import OfflineOTPKit

/// Tag editor sheet for all nine entry kinds (including `PASSWORD`, `PASSWORD_ARCHIVE`, and `EMAIL`).
struct TagEditorSheet: View {
    @ObservedObject var store: VaultStore
    let index: Int
    @Environment(\.dismiss) private var dismiss

    @State private var assigned: [String] = []
    @State private var draftTag = ""
    @State private var errorText: String?
    @State private var loaded = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 8) {
                        TextField("Add a tag", text: $draftTag)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        Button("Add") { addDraft() }
                            .disabled(draftTag.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                } header: {
                    Text("New tag")
                }

                if !store.allTags.isEmpty {
                    Section {
                        ForEach(store.allTags, id: \.self) { tag in
                            let selected = assigned.contains(tag)
                            Button {
                                toggle(tag)
                            } label: {
                                HStack {
                                    Text(tag)
                                        .foregroundColor(ForestPalette.textPrimary)
                                    Spacer()
                                    if selected {
                                        Image(systemName: "checkmark")
                                            .foregroundColor(ForestPalette.accent)
                                    }
                                }
                            }
                        }
                    } header: {
                        Text("Known tags")
                    }
                }

                Section {
                    if assigned.isEmpty {
                        Text("No tags assigned")
                            .foregroundColor(ForestPalette.textSecondary)
                    } else {
                        ForEach(assigned, id: \.self) { tag in
                            HStack {
                                Text(tag)
                                Spacer()
                                Button("Remove", role: .destructive) {
                                    assigned.removeAll { $0 == tag }
                                }
                                .buttonStyle(.borderless)
                            }
                        }
                    }
                } header: {
                    Text("Assigned tags (\(assigned.count)/\(InputPolicy.maxTags))")
                }

                if let errorText {
                    Section {
                        Text(errorText)
                            .foregroundColor(ForestPalette.warning)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(ForestPalette.background)
            .navigationTitle("Edit tags")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                }
            }
            .onAppear {
                guard !loaded else { return }
                loaded = true
                if store.entries.indices.contains(index) {
                    assigned = store.entries[index].tags
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    private func toggle(_ tag: String) {
        errorText = nil
        if let pos = assigned.firstIndex(of: tag) {
            assigned.remove(at: pos)
        } else {
            guard assigned.count < InputPolicy.maxTags else {
                errorText = "An entry may have at most \(InputPolicy.maxTags) tags."
                return
            }
            assigned.append(tag)
        }
    }

    private func addDraft() {
        errorText = nil
        let trimmed = draftTag.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        guard trimmed.count <= InputPolicy.maxTagChars else {
            errorText = "Tags may contain at most \(InputPolicy.maxTagChars) characters."
            return
        }
        if !assigned.contains(trimmed) {
            guard assigned.count < InputPolicy.maxTags else {
                errorText = "An entry may have at most \(InputPolicy.maxTags) tags."
                return
            }
            assigned.append(trimmed)
        }
        draftTag = ""
    }

    private func save() {
        addDraft()
        guard errorText == nil else { return }
        if store.setTags(at: index, tags: assigned) {
            dismiss()
        } else {
            errorText = store.message ?? "Tags could not be saved."
        }
    }
}

/// `PASSWORD` credential detail sheet: every stored column with secret masking and one-tap copy,
/// plus the edit form for the three columns every browser shares (arrow into `PasswordCsvExport`).
struct PasswordDetailSheet: View {
    let entry: Entry
    /// Position of the entry in the vault, so an edit can be written back where it was.
    let index: Int
    @ObservedObject var store: VaultStore
    let onCopy: (String, String) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var revealSecrets = false
    @State private var editing = false

    private var decodedFields: PasswordMaterial.Fields? {
        try? entry.passwordFields()
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Show passwords", isOn: $revealSecrets)
                        .tint(ForestPalette.primary)
                }

                if let fields = decodedFields {
                    Section {
                        ForEach(Array(fields.items.enumerated()), id: \.offset) { _, field in
                            if field.name != LinScpVault.sessionJsonColumn {
                                let isSecret = isSecretColumn(field.name)
                                let shown = (isSecret && !revealSecrets && !field.value.isEmpty)
                                    ? EmailMaterial.secretMask
                                    : field.value
                                HStack(alignment: .top, spacing: 12) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(field.name)
                                            .font(.caption)
                                            .foregroundColor(ForestPalette.textSecondary)
                                        Text(shown.isEmpty ? "—" : shown)
                                            .font(isSecret ? .body.monospaced() : .body)
                                            .foregroundColor(ForestPalette.textPrimary)
                                    }
                                    Spacer()
                                    if !field.value.isEmpty {
                                        Button("Copy") {
                                            onCopy(field.value, field.name)
                                        }
                                        .buttonStyle(.bordered)
                                        .tint(ForestPalette.primary)
                                    }
                                }
                            }
                        }
                    } header: {
                        Text(VaultStore.displayName(entry))
                    }
                } else {
                    Section {
                        Text("This password entry's fields could not be decoded.")
                            .foregroundColor(ForestPalette.warning)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(ForestPalette.background)
            .navigationTitle("Password details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if decodedFields != nil {
                        Button("Edit") { editing = true }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .sheet(isPresented: $editing) {
            PasswordEditSheet(entry: entry, index: index, store: store) { dismiss() }
        }
        .preferredColorScheme(.dark)
    }

    private func isSecretColumn(_ name: String) -> Bool {
        let low = name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return low.contains("password") || low.contains("passphrase") || low.contains("secret")
    }
}

/// Read-only `EMAIL` credential detail sheet backed by `EmailMaterial.details(account:revealSecrets:)`.
struct EmailDetailSheet: View {
    let entry: Entry
    let onCopy: (String, String) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var revealSecrets = false

    private var account: EmailMaterial.Account? {
        try? entry.emailAccount()
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Show passwords", isOn: $revealSecrets)
                        .tint(ForestPalette.primary)
                }

                if let account {
                    let rows = EmailMaterial.details(account: account, revealSecrets: revealSecrets)
                    let rawRows = EmailMaterial.details(account: account, revealSecrets: true)
                    Section {
                        ForEach(Array(rows.enumerated()), id: \.offset) { idx, row in
                            let rawVal = rawRows.indices.contains(idx) ? rawRows[idx].value : row.value
                            HStack(alignment: .top, spacing: 12) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(row.label)
                                        .font(.caption)
                                        .foregroundColor(ForestPalette.textSecondary)
                                    Text(row.value.isEmpty ? "—" : row.value)
                                        .font(row.secret ? .body.monospaced() : .body)
                                        .foregroundColor(ForestPalette.textPrimary)
                                }
                                Spacer()
                                if !rawVal.isEmpty {
                                    Button("Copy") {
                                        onCopy(rawVal, row.label)
                                    }
                                    .buttonStyle(.bordered)
                                    .tint(ForestPalette.primary)
                                }
                            }
                        }
                    } header: {
                        Text(account.name)
                    }
                } else {
                    Section {
                        Text("This email account's settings could not be decoded.")
                            .foregroundColor(ForestPalette.warning)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(ForestPalette.background)
            .navigationTitle("Email details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .preferredColorScheme(.dark)
    }
}

/// Manual FairEmail-compatible email account creation sheet.
struct EmailAccountEditorSheet: View {
    @ObservedObject var store: VaultStore
    @Environment(\.dismiss) private var dismiss

    @State private var accountName = ""
    @State private var emailAddress = ""
    @State private var incomingProtocol = 0 // 0 = IMAP, 1 = POP
    @State private var incomingHost = ""
    @State private var incomingPort = "993"
    @State private var incomingEncryption = 0 // 0 = SSL, 1 = STARTTLS, 2 = None
    @State private var incomingUser = ""
    @State private var incomingPassword = ""

    @State private var outgoingHost = ""
    @State private var outgoingPort = "465"
    @State private var outgoingEncryption = 0
    @State private var outgoingUser = ""
    @State private var outgoingPassword = ""
    @State private var tagsText = ""
    @State private var errorText: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Account name", text: $accountName)
                    TextField("Email address (user@example.com)", text: $emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.emailAddress)
                } header: {
                    Text("Identity")
                }

                Section {
                    Picker("Protocol", selection: $incomingProtocol) {
                        Text("IMAP").tag(0)
                        Text("POP3").tag(1)
                    }
                    TextField("Incoming server (imap.example.com)", text: $incomingHost)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("Incoming port", text: $incomingPort)
                        .keyboardType(.numberPad)
                    Picker("Encryption", selection: $incomingEncryption) {
                        Text("SSL/TLS").tag(0)
                        Text("STARTTLS").tag(1)
                        Text("None").tag(2)
                    }
                    TextField("Incoming user name", text: $incomingUser)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("Incoming password", text: $incomingPassword)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("Incoming mail")
                }

                Section {
                    TextField("Outgoing SMTP server (smtp.example.com)", text: $outgoingHost)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("Outgoing port", text: $outgoingPort)
                        .keyboardType(.numberPad)
                    Picker("Encryption", selection: $outgoingEncryption) {
                        Text("SSL/TLS").tag(0)
                        Text("STARTTLS").tag(1)
                        Text("None").tag(2)
                    }
                    TextField("Outgoing user name", text: $outgoingUser)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("Outgoing password", text: $outgoingPassword)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("Outgoing mail (SMTP)")
                }

                Section {
                    TextField("Tags, separated by commas", text: $tagsText)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("Tags")
                }

                if let errorText {
                    Section {
                        Text(errorText)
                            .foregroundColor(ForestPalette.warning)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(ForestPalette.background)
            .navigationTitle("Add email account")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    private func save() {
        errorText = nil
        guard let inPort = Int(incomingPort.trimmingCharacters(in: .whitespacesAndNewlines)),
              (1...65_535).contains(inPort),
              let outPort = Int(outgoingPort.trimmingCharacters(in: .whitespacesAndNewlines)),
              (1...65_535).contains(outPort) else {
            errorText = "Enter valid server ports between 1 and 65535."
            return
        }
        let effectiveInUser = incomingUser.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? emailAddress : incomingUser
        let effectiveOutUser = outgoingUser.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? effectiveInUser : outgoingUser
        let effectiveOutPass = outgoingPassword.isEmpty ? incomingPassword : outgoingPassword
        let effectiveName = accountName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? emailAddress : accountName

        do {
            let account = try EmailMaterial.create(
                name: effectiveName,
                address: emailAddress,
                incomingHost: incomingHost,
                incomingPort: inPort,
                incomingEncryption: incomingEncryption,
                incomingProtocol: incomingProtocol,
                incomingUser: effectiveInUser,
                incomingPassword: incomingPassword,
                outgoingHost: outgoingHost,
                outgoingPort: outPort,
                outgoingEncryption: outgoingEncryption,
                outgoingUser: effectiveOutUser,
                outgoingPassword: effectiveOutPass
            )
            let tags = tagsText.split(separator: ",").map(String.init)
            if store.addEmailAccount(account, tags: tags) {
                dismiss()
            } else {
                errorText = store.message ?? "The email account could not be saved."
            }
        } catch {
            errorText = "Provide a valid email address and incoming/outgoing mail servers."
        }
    }
}

/// Edits the URL, username and password of a stored browser-imported password.
///
/// The password box starts empty and keeps the stored value when left blank, so opening the form
/// never puts a secret on screen; the other stored columns are carried over by `PasswordCsvExport`.
struct PasswordEditSheet: View {
    let entry: Entry
    let index: Int
    @ObservedObject var store: VaultStore
    /// Called after the write succeeds, so the caller can dismiss this sheet and its parent.
    let onSaved: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var url: String
    @State private var username: String
    @State private var password = ""
    @State private var revealPassword = false
    @State private var errorText: String?

    private let maxFieldChars = 64 * 1024

    init(entry: Entry, index: Int, store: VaultStore, onSaved: @escaping () -> Void) {
        self.entry = entry
        self.index = index
        self.store = store
        self.onSaved = onSaved
        let fields = (try? entry.passwordFields()) ?? PasswordMaterial.Fields(items: [])
        _url = State(initialValue: fields.value(PasswordCsvExport.fieldURL))
        _username = State(initialValue: fields.value(PasswordCsvExport.fieldUsername))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("URL", text: $url)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    TextField("Username", text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    if revealPassword {
                        TextField("New password (blank keeps the stored one)", text: $password)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    } else {
                        SecureField("New password (blank keeps the stored one)", text: $password)
                    }
                    Toggle("Show password", isOn: $revealPassword)
                } footer: {
                    Text("Notes, GUIDs, timestamps and other stored columns are kept as they are.")
                }
                if let errorText {
                    Section {
                        Text(errorText).foregroundColor(ForestPalette.warning)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(ForestPalette.background)
            .navigationTitle("Edit password")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { password = ""; dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: save)
                }
            }
        }
        .preferredColorScheme(.dark)
        .onDisappear { password = "" }
    }

    private func save() {
        guard url.count <= maxFieldChars, username.count <= maxFieldChars, password.count <= maxFieldChars else {
            errorText = "Every value must fit the entry limits."
            return
        }
        let replacement: Entry
        do {
            replacement = try PasswordCsvExport.withEditedFields(
                entry,
                url: url,
                username: username,
                password: password.isEmpty ? nil : password
            )
        } catch {
            errorText = "The password entry could not be updated. A password cannot be empty."
            password = ""
            return
        }
        password = ""
        // The store replaces the entry in place, so the vault order and every other entry survive.
        if store.update(at: index, with: replacement) {
            onSaved()
            dismiss()
        } else {
            errorText = store.message ?? "The password entry could not be saved to the vault."
        }
    }
}
