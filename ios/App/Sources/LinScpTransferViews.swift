import SwiftUI
import OfflineOTPKit

private let linScpPlainWarning = "Warning: Exported secrets will be accessible by software on your device."

struct LinScpImportSheet: View {
    @ObservedObject var store: VaultStore
    let data: Data
    let onFinished: (String) -> Void
    let onCancel: () -> Void
    @State private var password = ""
    @State private var candidates: [Entry]?
    @State private var selected = Set<Int>()
    @State private var epoch: Int?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                if let candidates {
                    Text("Choose keys and sessions. Select referenced keys too if the session needs them. Imports merge; existing entries are not replaced.")
                    ForEach(candidates.indices, id: \.self) { i in
                        Toggle("[\(candidates[i].kind == .sshKey ? "Key" : "Session")] \(candidates[i].label)",
                               isOn: Binding(get: { selected.contains(i) }, set: { if $0 { selected.insert(i) } else { selected.remove(i) } }))
                    }
                } else {
                    if LinScpVault.isEncryptedVault(data) || LinScpVault.needsSessionMasterKey(data) {
                        SecureField(LinScpVault.isEncryptedVault(data) ? "Backup password" : "Base64 sessions.key", text: $password)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                    }
                    Button("Read file", action: read)
                }
                if let error { Text(error).foregroundColor(.red) }
            }
            .navigationTitle("Import LinSCP Key/Sessions")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onCancel) }
                ToolbarItem(placement: .confirmationAction) { Button("Import", action: install).disabled(selected.isEmpty) }
            }
            .onAppear { if epoch == nil { epoch = store.generation } }
            .onChange(of: store.generation) { _, _ in candidates = nil; password = ""; onCancel() }
        }
    }
    private func read() {
        guard store.isUnlocked, store.generation == epoch else { onCancel(); return }
        do {
            candidates = try LinScpVault.importData(data, password: password.isEmpty ? nil : password,
                masterKeyData: LinScpVault.isEncryptedVault(data) || password.isEmpty ? nil : Data(password.utf8))
            selected = []; error = nil
        } catch { self.error = "Could not read the file. Check format, password or session master key." }
        password = ""
    }
    private func install() {
        guard let candidates, store.isUnlocked, store.generation == epoch, !selected.isEmpty,
              selected.allSatisfy({ candidates.indices.contains($0) }) else { return }
        let chosen = candidates.enumerated().filter { selected.contains($0.offset) }.map { $0.element }
        guard let plan = store.importLinScpSelection(chosen) else { error = store.message; return }
        self.candidates = nil
        onFinished(BackupWording.restoreSummary(plan, replace: false))
    }
}

struct LinScpExportSheet: View {
    @ObservedObject var store: VaultStore
    let onExport: (VaultStore.ExportedBackup) -> Void
    let onCancel: () -> Void
    @State private var candidates: [Entry] = []
    @State private var selected = Set<Int>()
    @State private var epoch: Int?
    @State private var plainText = false
    @State private var password = ""
    @State private var confirmation = ""
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Text("Choose keys and sessions. Select referenced keys too if the session needs them.")
                ForEach(candidates.indices, id: \.self) { i in
                    Toggle("[\(candidates[i].kind == .sshKey ? "Key" : "Session")] \(candidates[i].label)",
                           isOn: Binding(get: { selected.contains(i) }, set: { if $0 { selected.insert(i) } else { selected.remove(i) } }))
                }
                Toggle("Export without a password (plain text)", isOn: $plainText)
                    .onChange(of: plainText) { _, _ in password = ""; confirmation = "" }
                Text(linScpPlainWarning)
                if !plainText {
                    SecureField("Export password", text: $password)
                    SecureField("Confirm password", text: $confirmation)
                }
                if let error { Text(error).foregroundColor(.red) }
            }
            .navigationTitle("Export LinSCP Key/Sessions")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onCancel) }
                ToolbarItem(placement: .confirmationAction) { Button("Export", action: export)
                    .disabled(selected.isEmpty || (!plainText && (password.isEmpty || password != confirmation))) }
            }
            .onAppear {
                if epoch == nil { epoch = store.generation; candidates = store.entries.filter { $0.kind == .sshKey || $0.kind == .password } }
            }
            .onChange(of: store.generation) { _, _ in candidates = []; password = ""; confirmation = ""; onCancel() }
        }
    }
    private func export() {
        guard store.isUnlocked, store.generation == epoch else { onCancel(); return }
        let chosen = candidates.enumerated().filter { selected.contains($0.offset) }.map { $0.element }
        guard !chosen.isEmpty, chosen.allSatisfy({ candidate in store.entries.contains { $0.fields == candidate.fields } }) else {
            error = "The selection changed. Close and reopen the export."; return
        }
        guard let result = store.exportLinScpVault(password: password, selected: chosen, plainText: plainText) else {
            error = store.message; return
        }
        password = ""; confirmation = ""; candidates = []
        onExport(result)
    }
}
