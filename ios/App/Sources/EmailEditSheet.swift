import SwiftUI
import OfflineOTPKit

let emailEditWarning = "Warning: You should only make edits if you know what you are doing and that it will work. No changes you make here will be reflected on your server, If you make a mistake in edits you may disable your ability to connect, there is no edit history, edits are not recoverable"

private struct EmailStoredField: Identifiable {
    var id: String
    var text: String
    let original: JSONValue
    static func fields(_ json: [String: JSONValue]) -> [EmailStoredField] {
        json.keys.sorted().map { key in
            let value = json[key]!
            return EmailStoredField(id: key, text: value.stringValue ?? value.compact(), original: value)
        }
    }
    static func json(_ fields: [EmailStoredField]) throws -> [String: JSONValue] {
        var result: [String: JSONValue] = [:]
        for field in fields {
            if case .string = field.original { result[field.id] = .string(field.text) }
            else {
                let value = try JSONValue.decode(field.text)
                switch (field.original, value) {
                case (.int, .int), (.double, .int), (.double, .double), (.int, .double),
                     (.bool, .bool), (.object, .object), (.array, .array), (.null, .null): break
                default: throw EntryError.invalidSecret
                }
                result[field.id] = value
            }
        }
        return result
    }
}

/// Edit every stored account/identity value with the existing portable email encoder/validator.
struct EmailEditSheet: View {
    @ObservedObject var store: VaultStore
    let index: Int
    @Environment(\.dismiss) private var dismiss
    @State private var original: Entry?
    @State private var epoch: Int?
    @State private var account: [EmailStoredField] = []
    @State private var identities: [[EmailStoredField]] = []
    @State private var issuer = ""
    @State private var label = ""
    @State private var hiddenLabel = ""
    @State private var tags = ""
    @State private var thumbnail = "Default"
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Text(emailEditWarning)
                TextField("Issuer", text: $issuer)
                TextField("Label", text: $label)
                TextField("Hidden Label", text: $hiddenLabel)
                Text("Tags (one per line)")
                TextEditor(text: $tags).frame(minHeight: 64)
                Picker("Artwork", selection: $thumbnail) {
                    ForEach(Array(Set(ThumbnailCatalog.names + [thumbnail])).sorted(), id: \.self) { name in Text(name).tag(name) }
                }.pickerStyle(.menu)
                Section("Incoming account") { EmailRecordFields(fields: $account) }
                ForEach(identities.indices, id: \.self) { i in
                    Section("SMTP identity #\(i + 1)") { EmailRecordFields(fields: $identities[i]) }
                }
                if let error { Text(error).foregroundColor(.red) }
            }
            .navigationTitle("Edit email entry")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save", action: save).disabled(original == nil) }
            }
            .onChange(of: store.generation) { _, _ in
                account = []; identities = []; original = nil; dismiss()
            }
            .onAppear {
                guard original == nil, store.entries.indices.contains(index), store.isRevealed(store.entries[index]),
                      let email = try? store.entries[index].emailAccount() else { return }
                let entry = store.entries[index]
                original = entry; epoch = store.generation
                account = EmailStoredField.fields(email.account)
                identities = email.identities.map(EmailStoredField.fields)
                issuer = entry.issuer; label = entry.label; hiddenLabel = entry.hiddenLabel
                tags = entry.tags.joined(separator: "\n")
                thumbnail = entry.fields["thumbnail"]?.stringValue ?? "Default"
            }
        }
    }
    private func save() {
        guard let original, store.isUnlocked, store.generation == epoch, store.entries.indices.contains(index),
              store.entries[index].fields == original.fields, store.isRevealed(store.entries[index]) else {
            error = "The entry or session changed. Close and reopen the editor."; return
        }
        do {
            let bytes = try EmailMaterial.encode(account: EmailStoredField.json(account), identities: identities.map { try EmailStoredField.json($0) })
            var fields = original.fields
            fields["secret"] = .string(Base32.encode(bytes))
            fields["issuer"] = .string(issuer); fields["label"] = .string(label); fields["hidden_label"] = .string(hiddenLabel)
            fields["thumbnail"] = .string(thumbnail)
            fields["tags"] = .array(EntryDraft.cleanedTags(tags.components(separatedBy: "\n")).map { .string($0) })
            let replacement = try Entry.parse(.object(fields))
            guard store.update(at: index, with: replacement) else { error = "Could not save. Original retained."; return }
            dismiss()
        } catch { self.error = "Invalid email entry. Check field values and JSON structure." }
    }
}

private struct EmailRecordFields: View {
    @Binding var fields: [EmailStoredField]
    @State private var showPasswords = false
    var body: some View {
        Toggle("Show passwords / tokens", isOn: $showPasswords)
        ForEach(fields.indices, id: \.self) { i in
            let field = fields[i]
            let text = Binding<String>(get: { fields[i].text }, set: { value in
                fields[i].text = value
                for j in fields.indices {
                    switch (field.id, fields[j].id) {
                    case ("protocol", "pop"): fields[j].text = value == "1" ? "true" : "false"
                    case ("pop", "protocol"): fields[j].text = value == "true" ? "1" : "0"
                    case ("encryption", "starttls"): fields[j].text = value == "1" ? "true" : "false"
                    case ("starttls", "encryption"): fields[j].text = value == "true" ? "1" : "0"
                    default: break
                    }
                }
            })
            if field.id == "encryption" {
                Picker("Encryption", selection: text) {
                    Text("SSL").tag("0"); Text("STARTTLS").tag("1"); Text("None (unencrypted)").tag("2")
                }.pickerStyle(.menu)
            } else if field.id == "protocol" {
                Picker("Protocol", selection: text) { Text("IMAP").tag("0"); Text("POP3").tag("1") }.pickerStyle(.menu)
            } else if case .bool = field.original {
                Picker(field.id, selection: text) { Text("Yes").tag("true"); Text("No").tag("false") }.pickerStyle(.menu)
            } else if field.id == "password" && !showPasswords {
                SecureField("Password / token", text: text)
            } else {
                Text(field.id)
                TextEditor(text: text).frame(minHeight: 44)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
            }
        }
    }
}
