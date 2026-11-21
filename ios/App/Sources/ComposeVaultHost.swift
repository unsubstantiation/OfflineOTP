import UIKit
import OfflineOTPKit
import OfflineOTPPresentation

/// Hosts the one Compose composition for the unlocked vault. All vault visuals, including dialogs,
/// are shared Compose declarations; this object only adapts native services and session checks.
final class VaultComposeViewController: UIViewController {
    let coordinator: VaultCoordinator

    init(store: VaultStore, onExport: @escaping (Data, String) -> Void,
         onImport: @escaping (String) -> Void, onOpenLink: @escaping (URL) -> Void) {
        coordinator = VaultCoordinator(store: store, onExport: onExport,
                                       onImport: onImport, onOpenLink: onOpenLink)
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("use init(store:onExport:onImport:onOpenLink:)") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = ForestPalette.background
        coordinator.refresh()
        let composed = coordinator.presentation.viewController(
            vaultActions: coordinator, surfaceActions: coordinator)
        addChild(composed)
        composed.view.frame = view.bounds
        composed.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(composed.view)
        composed.didMove(toParent: self)
    }

    func refresh() { coordinator.refresh() }
    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        coordinator.invalidate()
    }
}

final class VaultCoordinator: NSObject, PortableVaultActions, PortableSurfaceActions {
    let store: VaultStore
    let presentation = PortableVaultPresentation()
    let onExport: (Data, String) -> Void
    let onImport: (String) -> Void
    let onOpenLink: (URL) -> Void
    private var timer: Timer?
    private var toast: String = ""
    private var toastExpiry: Date = .distantPast
    private var surfaceKey: String = ""
    private var draft: [String: String] = [:]
    private var pendingImport = Data()
    private var lastImported = Data()
    private var openPgpRecipients: [Data] = []
    private var openPgpRecipientNames: [String] = []
    private var openPgpSecretKey = Data()
    private var pendingQrImagePayload: String?
    private var qrImageFailed = false
    private var passwordEditIndex: Int?
    private var openedFields: [String: JSONValue]?
    private var openedGeneration: Int?
    private var emailSecretsVisible = true
    private var linScpCandidates: [Entry] = []
    private var linScpSelected: Set<Int> = []
    private var linScpEpoch: Int?
    private var linScpPendingData = Data()
    private var editorIndex: Int?
    private var editorKind: EntryKind = .totp
    private var active = true
    private var surfaceEpoch: Int?

    init(store: VaultStore, onExport: @escaping (Data, String) -> Void,
         onImport: @escaping (String) -> Void, onOpenLink: @escaping (URL) -> Void) {
        self.store = store
        self.onExport = onExport
        self.onImport = onImport
        self.onOpenLink = onOpenLink
        super.init()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            DispatchQueue.main.async { self?.refresh() }
        }
    }

    deinit { timer?.invalidate() }

    // MARK: - Frame

    func refresh() {
        guard active else { return }
        guard store.isUnlocked else { return }
        if toastExpiry < Date() { toast = "" }
        presentation.updateVault(vaultState())
        if !surfaceKey.isEmpty {
            presentation.updateSurface(surfaceState(surfaceKey))
        }
    }

    private func vaultState() -> PortableVaultState {
        let now = Date()
        let indices = store.visibleIndices
        let cards: [PortableCard] = indices.compactMap { index in
            guard store.entries.indices.contains(index) else { return nil }
            let entry = store.entries[index]
            let revealed = store.isRevealed(entry)
            let kind = entry.kind?.rawValue ?? "ENTRY"
            let pinRequired = entry.kind == .motp && entry.pin.isEmpty
            let code = try? entry.code(unixTime: Int64(now.timeIntervalSince1970))
            let hasCode = entry.kind?.isOtp == true && code != nil && code != Entry.motpNoPinCode
            let previous: String
            if revealed, (entry.kind == .totp || entry.kind == .steam),
               let older = try? entry.code(unixTime: Int64(now.timeIntervalSince1970) - Int64(entry.period)) {
                previous = CodeFormat.grouped(older)
            } else {
                previous = ""
            }
            let seconds = CodeFormat.secondsRemaining(kind: kind, period: entry.period, now: now) ?? 0
            let title = entry.issuer.isEmpty ? (entry.label.isEmpty ? "Untitled entry" : entry.label) : entry.issuer
            var metadata = kind
            if entry.kind?.isOtp == true && seconds > 0 { metadata += " · \(seconds)s" }
            if revealed, entry.kind == .hotp { metadata += " · counter \(entry.counter)" }
            if revealed, !entry.tags.isEmpty { metadata += " · " + entry.tags.joined(separator: ", ") }
            return PortableCard(
                index: Int32(index),
                title: title,
                subtitle: revealed && !entry.label.isEmpty && !entry.issuer.isEmpty ? entry.label : "",
                concealedTitle: entry.label.isEmpty ? "Concealed" : entry.label,
                code: revealed ? CodeFormat.grouped(code ?? "") : "",
                previousCode: previous,
                metadata: metadata,
                secondsLeft: Int32(seconds),
                revealed: revealed,
                canStep: entry.kind == .hotp && revealed,
                hasCode: hasCode,
                mark: title,
                tags: entry.tags,
                canReorder: store.canReorder,
                pinRequired: pinRequired)
        }
        let chips = store.tags(for: store.category).map { tag in
            PortableChip(label: tag, count: Int32(store.tagCounts(for: store.category)[tag] ?? 0),
                         selected: store.selectedTags.contains(tag))
        }
        let untagged = PortableChip(label: "Untagged", count: -1, selected: store.includeUntagged)
        let rule: String
        switch store.tagFunctionality {
        case .or: rule = "Any tag"
        case .and: rule = "All tags"
        case .single: rule = "One tag"
        }
        return PortableVaultState(
            tabs: EntryCategory.allCases.map {
                PortableChip(label: $0.title, count: Int32(store.entryCount(for: $0)), selected: $0 == store.category)
            },
            query: store.query,
            tags: chips + [untagged],
            includeUntagged: store.includeUntagged,
            tagRule: rule,
            cards: cards,
            sortLabel: store.sortMode.title,
            toast: toast,
            hiddenTitleEnabled: false)
    }

    // MARK: - Vault actions

    func selectTab(position: Int32) {
        guard active, store.isUnlocked else { return }
        let categories = EntryCategory.allCases
        let resolved = Int(position)
        guard categories.indices.contains(resolved) else { return }
        store.category = categories[resolved]
        noteActivity()
        refresh()
    }

    func search(text: String) {
        guard active, store.isUnlocked else { return }
        store.query = text
        noteActivity()
        refresh()
    }

    func toggleTag(tag: String) {
        guard active, store.isUnlocked else { return }
        if tag == "Untagged" { store.includeUntagged.toggle() }
        else { store.toggleTag(tag) }
        refresh()
    }

    func clearTags() {
        guard active, store.isUnlocked else { return }
        store.clearFilters()
        refresh()
    }

    func toggleTagRule() {
        guard active, store.isUnlocked else { return }
        switch store.tagFunctionality {
        case .or: store.setTagFunctionality(.and)
        case .and: store.setTagFunctionality(.single)
        case .single: store.setTagFunctionality(.or)
        }
        refresh()
    }

    func tool(action: String) {
        guard active, store.isUnlocked else { return }
        noteActivity()
        switch action {
        case "lock": closeSurface(); store.lock()
        case "add":
            if store.category == .emails {
                openEmailEditor(index: nil)
            } else {
                openEditor(index: nil, kind: categoryKind())
            }
        case "sort": openSurface("sort")
        case "tagRule": toggleTagRule()
        case "backups": openSurface("backups")
        case "settings": openSurface("settings")
        case "dismissToast": toast = ""; refresh()
        default: break
        }
    }

    func card(index: Int32, action: String) {
        guard active, store.isUnlocked else { return }
        let position = Int(index)
        guard store.entries.indices.contains(position) else { return }
        let entry = store.entries[position]
        noteActivity()
        switch action {
        case "reveal":
            store.toggleReveal(at: position)
        case "copy":
            copyCode(at: position)
        case "pin":
            if entry.kind == .motp {
                editorIndex = position
                draft = ["pin": entry.pin]
                openSurface("pinEdit", keepDraft: true)
            }
        case "step":
            if store.advanceCounter(at: position) { message("Counter stepped. Copy the new code for this step.") }
            else { message(store.message ?? "This entry has no counter to step.") }
        case "details":
            if store.isRevealed(entry) { openDetails(at: position) }
        case "edit":
            if store.isRevealed(entry) { openEntryEdit(at: position) }
        case "more":
            if store.isRevealed(entry) { openSurface("more:\(position)") }
        case "moveUp", "moveDown":
            guard let origin = store.visibleIndices.firstIndex(of: position) else { return }
            let destination = origin + (action == "moveUp" ? -1 : 1)
            if store.moveDisplayed(from: origin, to: destination) {
                message("Entry moved.")
            } else {
                message(store.message ?? "This entry cannot be moved here.")
            }
        default: break
        }
        refresh()
    }

    func showQrUnavailable() {
        message("The camera is not available here. Import a QR image or paste a link instead.")
    }

    private func categoryKind() -> EntryKind {
        switch store.category {
        case .otp: return .totp
        case .notes: return .note
        case .sshKeys: return .sshKey
        default: return .totp
        }
    }

    private func noteActivity() { store.noteUserActivity() }

    private func message(_ text: String) {
        toast = text
        toastExpiry = Date().addingTimeInterval(2.5)
        refresh()
    }

    private func copyCode(at index: Int) {
        guard store.entries.indices.contains(index),
              let fresh = try? store.entries[index].code(unixTime: Int64(Date().timeIntervalSince1970)),
              fresh != Entry.motpNoPinCode else { return }
        guard store.recordUse(at: index), store.isUnlocked else {
            message(store.message ?? "Token not copied: the vault could not be saved.")
            return
        }
        Clipboard.copy(fresh)
        message("Token copied — the clipboard clears in \(Int(Clipboard.expirySeconds)) s")
    }

    private func copySecret(_ secret: String, label: String, at index: Int) {
        guard store.recordUse(at: index), store.isUnlocked else {
            message(store.message ?? "Not copied: the vault could not be saved.")
            return
        }
        Clipboard.copy(secret)
        message("\(label) copied — the clipboard clears in \(Int(Clipboard.expirySeconds)) s")
    }

    // MARK: - Shared dialogs

    private func openSurface(_ key: String, keepDraft: Bool = false) {
        surfaceEpoch = store.generation
        surfaceKey = key
        if !keepDraft {
            draft = [:]
            editorIndex = nil
        }
        presentation.updateSurface(surfaceState(key))
    }

    func closeSurface() {
        if surfaceKey == "uriImport" {
            store.cancelMigrationImport()
            pendingQrImagePayload = nil
            qrImageFailed = false
        }
        surfaceKey = ""
        surfaceEpoch = nil
        draft = [:]
        editorIndex = nil
        passwordEditIndex = nil
        openedFields = nil
        openedGeneration = nil
        presentation.updateSurface(nil)
    }

    private func openEntryEdit(at index: Int) {
        guard store.entries.indices.contains(index) else { return }
        let entry = store.entries[index]
        if entry.kind == .email {
            openEmailEditor(index: index)
        } else if entry.kind == .password, PasswordCsvExport.supportsCommonEdit(entry) {
            openPasswordEditor(index: index)
        } else {
            openEditor(index: index, kind: entry.kind ?? .totp)
        }
    }

    private func openPasswordEditor(index: Int) {
        guard store.entries.indices.contains(index) else { return }
        let entry = store.entries[index]
        passwordEditIndex = index
        openedFields = entry.fields
        openedGeneration = store.generation
        draft = [
            "url": (try? entry.passwordFields().value(PasswordCsvExport.fieldURL)) ?? "",
            "username": (try? entry.passwordFields().value(PasswordCsvExport.fieldUsername)) ?? "",
            "password": ""
        ]
        openSurface("passwordEdit:\(index)", keepDraft: true)
    }

    private func openEmailEditor(index: Int?) {
        editorIndex = index
        draft = [:]
        if let index, store.entries.indices.contains(index),
           let account = try? store.entries[index].emailAccount() {
            let entry = store.entries[index]
            let primary = account.identities.first ?? [:]
            draft["name"] = account.account["name"]?.stringValue ?? ""
            draft["address"] = account.address
            draft["inHost"] = account.account["host"]?.stringValue ?? ""
            draft["inPort"] = String(account.account["port"]?.intValue ?? 993)
            draft["inEnc"] = String(account.account["encryption"]?.intValue ?? 0)
            draft["inProto"] = String(account.account["protocol"]?.intValue ?? 0)
            draft["inUser"] = account.account["user"]?.stringValue ?? ""
            draft["inPass"] = ""
            draft["outHost"] = primary["host"]?.stringValue ?? ""
            draft["outPort"] = String(primary["port"]?.intValue ?? 465)
            draft["outEnc"] = String(primary["encryption"]?.intValue ?? 0)
            draft["outUser"] = primary["user"]?.stringValue ?? ""
            draft["outPass"] = ""
            draft["tags"] = entry.tags.joined(separator: ", ")
        } else {
            draft["name"] = ""
            draft["address"] = ""
            draft["inHost"] = ""
            draft["inPort"] = "993"
            draft["inEnc"] = "0"
            draft["inProto"] = "0"
            draft["inUser"] = ""
            draft["inPass"] = ""
            draft["outHost"] = ""
            draft["outPort"] = "465"
            draft["outEnc"] = "0"
            draft["outUser"] = ""
            draft["outPass"] = ""
            draft["tags"] = ""
        }
        openSurface("emailEditor", keepDraft: true)
    }

    private func openEditor(index: Int?, kind: EntryKind) {
        editorIndex = index
        editorKind = kind
        draft = [:]
        let entry = index.flatMap { store.entries.indices.contains($0) ? store.entries[$0] : nil }
        draft["type"] = kind.rawValue
        draft["issuer"] = entry?.issuer ?? ""
        draft["label"] = entry?.label ?? ""
        draft["hiddenLabel"] = entry?.hiddenLabel ?? ""
        switch kind {
        case .note:
            draft["secret"] = entry?.noteText ?? ""
        case .motp:
            draft["secret"] = entry?.rawSecretText ?? ""
        case .sshKey:
            let keys = try? entry?.sshKeys()
            draft["publicKey"] = keys?.publicKey ?? ""
            draft["privateKey"] = keys?.privateKey ?? ""
            draft["sshHost"] = entry?.sshHost ?? ""
            draft["sshPort"] = entry?.sshPort.map { $0 > 0 ? String($0) : "" } ?? ""
            draft["sshUsername"] = entry?.sshUsername ?? ""
            draft["sshPassword"] = entry?.sshPassword ?? ""
        default:
            draft["secret"] = entry?.secretBase32 ?? ""
        }
        draft["digits"] = entry.map { String($0.digits) } ?? "6"
        draft["period"] = entry.map { String($0.period) } ?? "30"
        draft["counter"] = entry.map { String($0.counter) } ?? "0"
        draft["pin"] = entry?.pin ?? ""
        draft["tags"] = entry?.tags.joined(separator: ", ") ?? ""
        openSurface("editor", keepDraft: true)
    }

    private func surfaceState(_ key: String) -> PortableSurfaceState {
        if key.hasPrefix("details:") {
            let position = Int(String(key.dropFirst("details:".count))) ?? -1
            return detailsSurface(at: position)
        }
        if key.hasPrefix("more:") {
            let position = Int(String(key.dropFirst("more:".count))) ?? -1
            return actionSurface(at: position)
        }
        if key == "linScpImport" { return linScpImportSurface() }
        if key == "linScpExport" { return linScpExportSurface() }
        if key.hasPrefix("passwordEdit:") {
            let position = Int(String(key.dropFirst("passwordEdit:".count))) ?? -1
            return passwordEditSurface(at: position)
        }
        if key == "emailEditor" {
            return emailEditorSurface()
        }
        if key == "passwordImportChoice" {
            return PortableSurfaceState(
                key: key, title: "Import passwords",
                sections: [PortableSection(title: "Choose how to store this file", rows: [
                    row("passwordImportMode:individual", "Individual credentials (browser CSV)"),
                    row("passwordImportMode:archive", "Keep original file as archive")])],
                confirmLabel: "Done", dismissLabel: "Cancel", showConfirm: false)
        }
        switch key {
        case "sort":
            let rows = SortMode.allCases.map { mode in
                PortableRow(key: "sort:\(mode.rawValue)", title: mode == store.sortMode ? "✓ \(mode.title)" : mode.title,
                            value: "", kind: "action", checked: false,
                            enabled: true, detail: "")
            }
            return PortableSurfaceState(key: key, title: "Sort entries",
                sections: [PortableSection(title: "Order", rows: rows)],
                confirmLabel: "Done", dismissLabel: "Close", showConfirm: false)
        case "settings": return settingsSurface()
        case "backups": return backupSurface()
        case "editor": return editorSurface()
        case "autoLock":
            return PortableSurfaceState(key: key, title: "Auto-lock after inactivity",
                sections: [PortableSection(title: "Seconds (0 disables auto-lock)", rows: [
                    row("seconds", "Inactivity delay",
                        value: draft["seconds"] ?? String(store.inactivityLockSeconds), kind: "text")])],
                confirmLabel: "Save", dismissLabel: "Cancel")
        case "passwordChange":
            return PortableSurfaceState(key: key, title: "Change vault password",
                sections: [PortableSection(title: "The new password is never used as a backup password", rows: [
                    row("current", "Current password", value: draft["current"] ?? "", kind: "secure"),
                    row("next", "New password", value: draft["next"] ?? "", kind: "secure"),
                    row("confirmation", "Confirm new password", value: draft["confirmation"] ?? "", kind: "secure")])],
                confirmLabel: "Change", dismissLabel: "Cancel")
        case "about":
            return PortableSurfaceState(key: key, title: "About OfflineOTP",
                sections: [PortableSection(title: "Offline authenticator and encrypted credential vault", rows: [
                    row("info", "Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "", kind: "info"),
                    row("build", "Build",
                        value: Bundle.main.object(forInfoDictionaryKey: "OfflineOTPBuildVersion") as? String
                            ?? "Build version unavailable", kind: "info"),
                    row("aboutLink:website", "Source and changelog", kind: "action"),
                    row("aboutLink:issues", "Report a problem", kind: "action")])],
                confirmLabel: "Done", dismissLabel: "Close", showConfirm: false)
        case "fairEmailImport":
            var rows = [row("password", "Backup password (optional)", value: draft["password"] ?? "", kind: "secure")]
            if let preview = draft["previewCount"] {
                rows.append(row("previewCount", "Accounts", value: preview, kind: "info"))
            }
            return PortableSurfaceState(key: key, title: "Import FairEmail backup",
                sections: [PortableSection(title: "", rows: rows)],
                confirmLabel: "Import", dismissLabel: "Cancel")
        case "uriImport":
            let migrationStep = store.openMigrationImport
            var rows = [
                row("uri", "Link", value: draft["uri"] ?? "", kind: "text"),
                row("collecting", "Collecting", value: migrationStep != nil ? "true" : "false", kind: "info"),
                row("imageReady", "Image ready", value: pendingQrImagePayload != nil ? "true" : "false", kind: "info"),
                row("imageFailed", "Image failed", value: qrImageFailed ? "true" : "false", kind: "info")
            ]
            if let notice = migrationStep?.message {
                rows.append(row("migrationNotice", "Migration", value: notice, kind: "info"))
            }
            return PortableSurfaceState(key: key, title: "Import otpauth:// link",
                sections: [PortableSection(title: "One link or QR payload", rows: rows)],
                confirmLabel: "Import", dismissLabel: "Cancel")
        case "prefsConfirm":
            return PortableSurfaceState(key: key, title: "Import preferences?",
                sections: [PortableSection(title: "Only sort order and auto-lock are carried between iOS installs.", rows: [
                    row("info", "The vault, its password and its accounts are never included.", kind: "info")])],
                confirmLabel: "Apply", dismissLabel: "Cancel")
        case "backupExportDialog":
            return PortableSurfaceState(key: key, title: "Export encrypted backup",
                sections: [PortableSection(title: "", rows: [
                    row("backupPassword", "Backup password", value: draft["backupPassword"] ?? "", kind: "secure"),
                    row("backupConfirm", "Confirm backup password", value: draft["backupConfirm"] ?? "", kind: "secure")])],
                confirmLabel: "Export", dismissLabel: "Cancel")
        case "restore":
            var rows = [row("password", "Backup password", value: draft["password"] ?? "", kind: "secure")]
            if let preview = draft["previewCount"] {
                rows.append(row("previewCount", "Entries", value: preview, kind: "info"))
            }
            return PortableSurfaceState(key: key, title: "Restore encrypted backup",
                sections: [PortableSection(title: "The backup password is not the vault password", rows: rows)],
                confirmLabel: "Restore", dismissLabel: "Cancel")
        case "biometricEnable":
            return PortableSurfaceState(key: key, title: "Enable quick unlock",
                sections: [PortableSection(title: "Enter the vault password to turn this on.", rows: [
                    row("password", "Vault password", value: draft["password"] ?? "", kind: "secure")])],
                confirmLabel: "Enable", dismissLabel: "Cancel")
        case "delete": return deleteSurface()
        case "pinEdit":
            return PortableSurfaceState(key: key, title: "Enter mOTP PIN",
                sections: [PortableSection(title: "", rows: [
                    row("entryId", "Entry", value: String(editorIndex ?? 0), kind: "info"),
                    row("pin", "PIN", value: draft["pin"] ?? "", kind: "secure")])],
                confirmLabel: "Save", dismissLabel: "Cancel")
        case "counterEdit":
            return PortableSurfaceState(key: key, title: "Set HOTP counter",
                sections: [PortableSection(title: "", rows: [
                    row("counter", "Counter", value: draft["counter"] ?? "0", kind: "text")])],
                confirmLabel: "Save", dismissLabel: "Cancel")
        case "tagEdit":
            let currentSet = Set((draft["tags"] ?? "").split(separator: ",").map {
                $0.trimmingCharacters(in: .whitespacesAndNewlines)
            }.filter { !$0.isEmpty })
            var counts: [String: Int] = [:]
            for entry in store.entries {
                for tag in entry.tags { counts[tag, default: 0] += 1 }
            }
            var tagRows: [PortableRow] = [row("tags", "Tags (comma separated)", value: draft["tags"] ?? "", kind: "text")]
            for tag in counts.keys.sorted() {
                tagRows.append(row("tag:\(tag)", tag, value: String(counts[tag] ?? 0),
                                   kind: "toggle", checked: currentSet.contains(tag)))
            }
            return PortableSurfaceState(key: key, title: "Tags",
                sections: [PortableSection(title: "", rows: tagRows)],
                confirmLabel: "Save", dismissLabel: "Cancel")
        case "qr": return qrSurface()
        case "fairEmailExportSelect":
            let rows = store.entries.enumerated().compactMap { idx, entry -> PortableRow? in
                guard entry.kind == .email else { return nil }
                let label = [entry.issuer, entry.label].filter { !$0.isEmpty }.joined(separator: " — ")
                return row("choice:\(idx)", label.isEmpty ? "Email account" : label, kind: "toggle")
            }
            return PortableSurfaceState(key: key, title: "Export FairEmail backup",
                sections: [PortableSection(title: "Select the email accounts to export.", rows: rows)],
                confirmLabel: "Export", dismissLabel: "Cancel")
        case "thunderbirdExportSelect":
            let rows = store.entries.enumerated().compactMap { idx, entry -> PortableRow? in
                guard entry.kind == .email || entry.kind == .password else { return nil }
                let label = [entry.issuer, entry.label].filter { !$0.isEmpty }.joined(separator: " — ")
                return row("choice:\(idx)", label.isEmpty ? "Credential" : label, kind: "toggle")
            }
            return PortableSurfaceState(key: key, title: "Export Thunderbird CSV",
                sections: [PortableSection(title: "Select the email credentials to export.", rows: rows)],
                confirmLabel: "Export", dismissLabel: "Cancel")
        case "openpgpExportDialog":
            let recipientRows = openPgpRecipientNames.enumerated().map { idx, name in
                row("recipient:\(idx)", name, kind: "info")
            }
            return PortableSurfaceState(key: key, title: "Export OpenPGP backup",
                sections: [PortableSection(title: "", rows: recipientRows)],
                confirmLabel: "Export", dismissLabel: "Cancel")
        case "openpgpRestore":
            var rows = [
                row("info", "Choose your secret key after selecting the backup.", kind: "info"),
                row("openpgpSecret", "Choose secret key…",
                    detail: openPgpSecretKey.isEmpty ? "None selected" : "Key selected"),
                row("passphrase", "Key passphrase (optional)", value: draft["passphrase"] ?? "", kind: "secure")
            ]
            if let preview = draft["previewCount"] {
                rows.append(row("previewCount", "Entries", value: preview, kind: "info"))
            }
            return PortableSurfaceState(key: key, title: "Restore OpenPGP backup",
                sections: [PortableSection(title: "", rows: rows)],
                confirmLabel: "Restore", dismissLabel: "Cancel")
        case "passwordPrompt": return passwordPromptSurface()
        default:
            return PortableSurfaceState(key: key, title: "Action",
                sections: [PortableSection(title: "", rows: [])], confirmLabel: "Done",
                dismissLabel: "Close", showConfirm: false)
        }
    }

    private func row(_ key: String, _ title: String, value: String = "", kind: String = "button",
                     checked: Bool = false, enabled: Bool = true, detail: String = "") -> PortableRow {
        PortableRow(key: key, title: title, value: value, kind: kind, checked: checked,
                    enabled: enabled, detail: detail)
    }

    private func linScpImportSurface() -> PortableSurfaceState {
        var rows: [PortableRow] = [
            row("linScpPassword", "Backup password or base64 sessions.key", value: draft["linScpPassword"] ?? "", kind: "secure"),
            row("linScpRead", "Read file")]
        if !linScpCandidates.isEmpty {
            rows.append(contentsOf: linScpCandidates.enumerated().map { index, entry in
                row("linScpSelect:\(index)",
                    "[\(entry.kind == .sshKey ? "Key" : "Session")] \(entry.label)",
                    kind: "toggle", checked: linScpSelected.contains(index))
            })
        }
        return PortableSurfaceState(key: "linScpImport", title: "Import LinSCP Key/Sessions",
            sections: [PortableSection(title: "Choose keys and sessions. Select referenced keys too if the session needs them. Imports merge; existing entries are not replaced.", rows: rows)],
            confirmLabel: "Import", dismissLabel: "Cancel", showConfirm: !linScpCandidates.isEmpty,
            message: store.message ?? "")
    }

    private func linScpExportSurface() -> PortableSurfaceState {
        let candidates = store.entries.enumerated().filter { _, entry in
            entry.kind == .sshKey || entry.kind == .password
        }
        let plain = draft["linScpPlain"] == "true"
        var rows = candidates.map { index, entry in
            row("linScpExportSelect:\(index)",
                "[\(entry.kind == .sshKey ? "Key" : "Session")] \(entry.label)",
                kind: "toggle", checked: linScpSelected.contains(index))
        }
        rows.append(row("linScpPlain", "Export without a password (plain text)",
                        kind: "toggle", checked: plain))
        rows.append(row("info", "Warning: Exported secrets will be accessible by software on your device.", kind: "info"))
        if !plain {
            rows.append(row("linScpExportPassword", "Export password", value: draft["linScpExportPassword"] ?? "", kind: "secure"))
            rows.append(row("linScpExportConfirm", "Confirm password", value: draft["linScpExportConfirm"] ?? "", kind: "secure"))
        }
        return PortableSurfaceState(key: "linScpExport", title: "Export LinSCP Key/Sessions",
            sections: [PortableSection(title: "Choose keys and sessions. Select referenced keys too if the session needs them.", rows: rows)],
            confirmLabel: "Export", dismissLabel: "Cancel")
    }

    private func passwordEditSurface(at index: Int) -> PortableSurfaceState {
        guard store.entries.indices.contains(index),
              let fields = try? store.entries[index].passwordFields() else {
            return PortableSurfaceState(key: "passwordEdit:\(index)", title: "Edit password",
                sections: [], message: "This entry cannot be edited here.")
        }
        return PortableSurfaceState(key: "passwordEdit:\(index)", title: "Edit password",
            sections: [PortableSection(title: "Notes, GUIDs, timestamps and other stored columns are kept as they are.", rows: [
                row("url", "URL", value: draft["url"] ?? fields.value(PasswordCsvExport.fieldURL), kind: "text"),
                row("username", "Username", value: draft["username"] ?? fields.value(PasswordCsvExport.fieldUsername), kind: "text"),
                row("password", "New password (blank keeps the stored one)", value: draft["password"] ?? "", kind: "secure")
            ])], confirmLabel: "Save", dismissLabel: "Cancel")
    }

    private func emailEncryptionLabel(_ raw: String?) -> String {
        switch Int(raw ?? "0") ?? 0 {
        case 0: return "SSL"
        case 1: return "STARTTLS"
        case 2: return "None"
        default: return "SSL"
        }
    }

    private func emailEditorSurface() -> PortableSurfaceState {
        let editing = editorIndex != nil
        let inProto = (Int(draft["inProto"] ?? "0") ?? 0) == 1 ? "POP3" : "IMAP"
        let passHint = editing ? " (blank keeps stored)" : ""
        let sections = [
            PortableSection(title: "Account", rows: [
                row("name", "Display name", value: draft["name"] ?? "", kind: "text"),
                row("address", "Email address", value: draft["address"] ?? "", kind: "text"),
                row("tags", "Tags (comma separated)", value: draft["tags"] ?? "", kind: "text")]),
            PortableSection(title: "Incoming server", rows: [
                row("emailCycleProto", "Protocol", value: inProto, kind: "action"),
                row("inHost", "Server host", value: draft["inHost"] ?? "", kind: "text"),
                row("inPort", "Port", value: draft["inPort"] ?? "993", kind: "text"),
                row("emailCycleInEnc", "Security", value: emailEncryptionLabel(draft["inEnc"]), kind: "action"),
                row("inUser", "User name", value: draft["inUser"] ?? "", kind: "text"),
                row("inPass", "Password\(passHint)", value: draft["inPass"] ?? "", kind: "secure")]),
            PortableSection(title: "Outgoing SMTP server", rows: [
                row("outHost", "Server host", value: draft["outHost"] ?? "", kind: "text"),
                row("outPort", "Port", value: draft["outPort"] ?? "465", kind: "text"),
                row("emailCycleOutEnc", "Security", value: emailEncryptionLabel(draft["outEnc"]), kind: "action"),
                row("outUser", "User name", value: draft["outUser"] ?? "", kind: "text"),
                row("outPass", "Password\(passHint)", value: draft["outPass"] ?? "", kind: "secure")])
        ]
        return PortableSurfaceState(
            key: "emailEditor",
            title: editing ? "Edit email account" : "Add email account",
            sections: sections,
            confirmLabel: "Save",
            dismissLabel: "Cancel",
            message: store.message ?? "")
    }

    private func settingsSurface() -> PortableSurfaceState {
        let quick = store.biometry.isAvailable ? [
            row("biometric", store.biometricUnlockEnabled ? "Disable quick unlock" : "Enable quick unlock",
                detail: "Unlock with \(store.biometryTitle) on this device.")] : []
        let sections = [
            PortableSection(title: "Quick unlock", rows: quick),
            PortableSection(title: "Security", rows: [
                row("autoLock", "Auto-lock after inactivity", detail: "Currently \(store.inactivityLockSeconds) seconds"),
                row("passwordChange", "Change vault password")]),
            PortableSection(title: "Vault", rows: [
                row("info", "Entries", value: String(store.entries.count), kind: "info"),
                row("info2", store.folderPath, kind: "info")]),
            PortableSection(title: "Preferences", rows: [
                row("prefsExport", "Export preferences…"), row("prefsImport", "Import preferences…")]),
            PortableSection(title: "About", rows: [row("about", "About OfflineOTP")])
        ]
        return PortableSurfaceState(key: "settings", title: "Settings", sections: sections,
                                    confirmLabel: "Done", dismissLabel: "Close", showConfirm: false,
                                    message: store.message ?? "")
    }

    private func backupSurface() -> PortableSurfaceState {
        PortableSurfaceState(key: "backups", title: "Backups and links", sections: [
            PortableSection(title: "Encrypted backup", rows: [
                row("backupPassword", "Backup password", value: draft["backupPassword"] ?? "", kind: "secure"),
                row("backupExport", "Export encrypted backup…", detail: "Argon2id + AES-256-GCM"),
                row("backupImport", "Import encrypted backup…")]),
            PortableSection(title: "Other formats", rows: [
                row("csvExport", "Export passwords as browser CSV…"),
                row("importPasswordsCsv", "Import browser passwords…"),
                row("uriImport", "Import an otpauth:// link…"),
                row("qrCamera", "Scan an otpauth:// QR code…")]),
            PortableSection(title: "Email clients", rows: [
                row("fairEmailExport", "Export FairEmail backup…"),
                row("fairEmailImport", "Import FairEmail backup…"),
                row("thunderbirdExport", "Export Thunderbird CSV…"),
                row("thunderbirdImport", "Import Thunderbird CSV…")]),
            PortableSection(title: "OfflineOTP links", rows: [
                row("linScpExport", "Export to LinSCP vault…"),
                row("linScpImport", "Import from LinSCP vault…")]),
            openPgpSection()
        ], confirmLabel: "Done", dismissLabel: "Close", showConfirm: false, message: store.message ?? "")
    }

    private func openPgpSection() -> PortableSection {
        let availability = store.openPgpAvailability
        guard availability.canRestore else {
            return PortableSection(title: "OpenPGP", rows: [
                row("openpgpUnavailable", "This build does not include the OpenPGP engine.", kind: "info"),
                row("openpgpNotice", "OpenPGP runs in this app using key files. No external OpenPGP app is required.", kind: "info")])
        }
        let recipients = row("openpgpRecipient", "Choose a recipient public key…",
                             detail: openPgpRecipients.isEmpty ? "None selected" : "Key selected")
        return PortableSection(title: "OpenPGP", rows: [
            recipients,
            row("openpgpExport", "Export encrypted backup…", enabled: availability.canEncrypt),
            row("openpgpImport", "Import encrypted backup…", enabled: availability.canRestore)])
    }

    private func editorSurface() -> PortableSurfaceState {
        let kind = EntryKind(rawValue: draft["type"] ?? "") ?? editorKind
        var rows: [PortableRow] = [
            row("type", "Type", value: kind.rawValue, kind: "action"),
            row("issuer", "Issuer / title", value: draft["issuer"] ?? "", kind: "text"),
            row("label", "Label", value: draft["label"] ?? "", kind: "text"),
            row("hiddenLabel", "Hidden label", value: draft["hiddenLabel"] ?? "", kind: "text")
        ]
        if kind == .sshKey {
            rows.append(row("publicKey", "Public key", value: draft["publicKey"] ?? "", kind: "text"))
            rows.append(row("privateKey", "Private key", value: draft["privateKey"] ?? "", kind: "secure"))
            rows.append(row("sshHost", "SSH host", value: draft["sshHost"] ?? "", kind: "text"))
            rows.append(row("sshPort", "SSH port", value: draft["sshPort"] ?? "", kind: "text"))
            rows.append(row("sshUsername", "SSH user name", value: draft["sshUsername"] ?? "", kind: "text"))
            rows.append(row("sshPassword", "SSH password", value: draft["sshPassword"] ?? "", kind: "secure"))
        } else {
            rows.append(row("secret", kind == .note ? "Note" : "Secret", value: draft["secret"] ?? "",
                            kind: kind == .note ? "text" : "secure"))
        }
        if kind != .note && kind != .sshKey {
            rows.append(row("digits", "Digits", value: draft["digits"] ?? "6", kind: "text"))
        }
        if kind == .totp || kind == .steam || kind == .hotp {
            rows.append(row(kind == .hotp ? "counter" : "period",
                            kind == .hotp ? "Counter" : "Period (seconds)",
                            value: kind == .hotp ? (draft["counter"] ?? "0") : (draft["period"] ?? "30"),
                            kind: "text"))
        }
        if kind == .motp {
            rows.append(row("pin", "PIN", value: draft["pin"] ?? "", kind: "text"))
        }
        rows.append(row("tags", "Tags (comma separated)", value: draft["tags"] ?? "", kind: "text"))
        return PortableSurfaceState(key: "editor", title: editorIndex == nil ? "Add entry" : "Edit entry",
            sections: [PortableSection(title: "", rows: rows)], confirmLabel: "Save",
            dismissLabel: "Cancel", message: store.message ?? "")
    }

    private func deleteSurface() -> PortableSurfaceState {
        guard let index = editorIndex, store.entries.indices.contains(index) else {
            return PortableSurfaceState(key: "delete", title: "Delete", sections: [],
                                        confirmLabel: "Delete", dismissLabel: "Cancel")
        }
        let name = store.entries[index].issuer.isEmpty ? store.entries[index].label : store.entries[index].issuer
        return PortableSurfaceState(key: "delete", title: "Delete account?",
            sections: [PortableSection(title: "", rows: [row("info",
                "Are you sure you want to remove the account \"\(name)\"? This cannot be undone.",
                kind: "info")])],
            confirmLabel: "Delete", dismissLabel: "Cancel")
    }

    private func qrSurface() -> PortableSurfaceState {
        let payload = editorIndex.flatMap { store.otpUri(for: $0) } ?? ""
        return PortableSurfaceState(key: "qr", title: "QR code",
            sections: [PortableSection(title: "Link", rows: [row("info", payload, kind: "info")])],
            confirmLabel: "Done", dismissLabel: "Close", showConfirm: false)
    }

    private func passwordPromptSurface() -> PortableSurfaceState {
        PortableSurfaceState(key: "passwordPrompt", title: "Backup password",
            sections: [PortableSection(title: "", rows: [
                row("password", "Backup password", value: draft["password"] ?? "", kind: "secure")])],
            confirmLabel: "Continue", dismissLabel: "Cancel")
    }

    private func actionSurface(at index: Int) -> PortableSurfaceState {
        guard store.entries.indices.contains(index) else {
            return PortableSurfaceState(key: "more:", title: "Entry", sections: [], confirmLabel: "Done",
                                        dismissLabel: "Close", showConfirm: false)
        }
        let entry = store.entries[index]
        var rows: [PortableRow] = []
        if entry.kind?.isOtp == true {
            rows.append(row("copy:\(index)", "Copy token"))
        }
        rows.append(row("reveal:\(index)", "Reveal or hide"))
        rows.append(row("tags:\(index)", "Edit tags"))
        if store.otpUri(for: index) != nil {
            rows.append(row("qr:\(index)", "Show QR code"))
        }
        if entry.kind == .hotp {
            rows.append(row("step:\(index)", "Advance counter"))
            rows.append(row("counter:\(index)", "Set counter…"))
        }
        if entry.kind == .motp {
            rows.append(row("pin:\(index)", "Set PIN…"))
        }
        if let note = entry.noteText, !note.isEmpty { rows.append(row("copyNote:\(index)", "Copy note")) }
        if let keys = try? entry.sshKeys() {
            if !keys.publicKey.isEmpty { rows.append(row("copyPublic:\(index)", "Copy public key")) }
            if !keys.privateKey.isEmpty { rows.append(row("copyPrivate:\(index)", "Copy private key")) }
        }
        if let sshPass = entry.sshConnection?.password, !sshPass.isEmpty {
            rows.append(row("copySshPassword:\(index)", "Copy SSH password"))
        }
        if entry.kind == .email, let account = try? entry.emailAccount() {
            if !(account.account["password"]?.stringValue ?? "").isEmpty {
                rows.append(row("copyImapPassword:\(index)", "Copy incoming password"))
            }
            if !(account.identities.first?["password"]?.stringValue ?? "").isEmpty {
                rows.append(row("copySmtpPassword:\(index)", "Copy outgoing password"))
            }
        }
        rows.append(row("delete:\(index)", "Delete entry"))
        return PortableSurfaceState(key: "more:\(index)", title: "Entry actions",
            sections: [PortableSection(title: "", rows: rows)], confirmLabel: "Done",
            dismissLabel: "Close", showConfirm: false)
    }

    private func detailsSurface(at index: Int) -> PortableSurfaceState {
        guard store.entries.indices.contains(index) else {
            return PortableSurfaceState(key: "details:\(index)", title: "Details", sections: [],
                                        confirmLabel: "Done", dismissLabel: "Close", showConfirm: false)
        }
        let entry = store.entries[index]
        var rows: [PortableRow] = []
        var fields: [(String, String)] = [
            ("Type", entry.kind?.rawValue ?? "ENTRY"), ("Issuer", entry.issuer), ("Label", entry.label)
        ]
        if store.isRevealed(entry) {
            fields.append(("Hidden label", entry.hiddenLabel))
            if let note = entry.noteText { fields.append(("Note", note)) }
            if let connection = entry.sshConnection {
                fields.append(("Destination", connection.summary ?? ""))
            }
        }
        fields.append(("Tags", entry.tags.joined(separator: ", ")))
        for (name, value) in fields where !value.isEmpty {
            rows.append(row("info:\(name)", name, value: value, kind: "info"))
        }
        if entry.kind == .password, let material = try? entry.passwordFields() {
            rows.append(row("copyPassword:\(index)", "Copy password", detail: "\(material.items.count) columns"))
            rows.append(row("copyUsername:\(index)", "Copy user name"))
        }
        if entry.kind == .email, let account = try? entry.emailAccount() {
            let detailRows = EmailMaterial.details(account: account, revealSecrets: emailSecretsVisible)
            for (idx, detail) in detailRows.enumerated() where !detail.value.isEmpty {
                rows.append(row("emailDetail:\(idx)", detail.label, value: detail.value, kind: "info"))
            }
            rows.append(row("toggleEmailSecrets:\(index)",
                            emailSecretsVisible ? "Hide passwords" : "Show passwords",
                            kind: "action"))
        }
        rows.append(row("edit:\(index)", "Edit entry"))
        rows.append(row("delete:\(index)", "Delete entry"))
        return PortableSurfaceState(key: "details:\(index)", title: "Details",
            sections: [PortableSection(title: "", rows: rows)], confirmLabel: "Done",
            dismissLabel: "Close", showConfirm: false)
    }

    private func openDetails(at index: Int) {
        guard store.entries.indices.contains(index) else { return }
        openSurface("details:\(index)")
    }

    // MARK: - Shared dialog callbacks

    func change(key: String, value: String) {
        guard active else { return }
        if surfaceKey == "editor" || surfaceKey == "emailEditor" || surfaceKey == "passwordPrompt"
            || surfaceKey == "backups" || surfaceKey == "backupExportDialog"
            || surfaceKey == "biometricEnable" || surfaceKey == "restore"
            || surfaceKey == "autoLock" || surfaceKey == "passwordChange"
            || surfaceKey == "tagEdit" || surfaceKey == "pinEdit" || surfaceKey == "counterEdit"
            || surfaceKey == "uriImport" || surfaceKey == "openpgpRestore"
            || surfaceKey.hasPrefix("passwordEdit:") || surfaceKey == "linScpImport"
            || surfaceKey == "linScpExport" || surfaceKey == "fairEmailImport"
            || surfaceKey == "fairEmailExportSelect" || surfaceKey == "thunderbirdExportSelect" {
            if key == "linScpSelectedIndices" {
                linScpSelected = Set(value.split(separator: ",").compactMap { Int($0) })
            } else if key == "linScpImportSelected" {
                linScpSelected = Set(value.split(separator: ",").compactMap { Int($0) })
            } else {
                draft[key] = value
            }
            refresh()
        }
    }

    func action(key: String) -> String? {
        guard active, store.isUnlocked else { return "The vault locked. Close and try again." }
        defer { if active { refresh() } }
        if key == "dismiss" { closeSurface(); return nil }
        if key == "confirm" {
            // A dialog opened in one session must never apply to a later one. Flow-specific
            // surfaces keep their own epoch when they stage files or candidate lists.
            if let epoch = surfaceEpoch,
               ["passwordChange", "autoLock", "prefsConfirm", "restore", "backupExportDialog",
                "editor", "emailEditor", "delete", "tagEdit", "pinEdit", "counterEdit",
                "biometricEnable", "uriImport", "fairEmailImport", "openpgpRestore",
                "fairEmailExportSelect", "thunderbirdExportSelect"].contains(surfaceKey),
               store.generation != epoch {
                closeSurface()
                return "The vault session changed. Close and reopen this dialog."
            }
            return confirmSurface()
        }
        if key.hasPrefix("sort:") {
            let raw = String(key.dropFirst("sort:".count))
            if let mode = SortMode(rawValue: raw) {
                store.setSortMode(mode)
                message("Sorted by \(mode.title.lowercased())")
                closeSurface()
            }
            return nil
        }
        return surfaceAction(key)
    }

    private func confirmSurface() -> String? {
        switch surfaceKey {
        case "editor": return saveEditor()
        case "emailEditor": return saveEmailEditor()
        case "delete":
            guard let index = editorIndex, store.remove(at: index) else {
                return store.message ?? "The entry could not be removed."
            }
            message("Entry removed.")
            closeSurface()
            return nil
        case "tagEdit":
            guard let index = editorIndex, let tags = draft["tags"] else {
                return "No tags were entered."
            }
            guard store.setTags(at: index, tags: tags.split(separator: ",").map(String.init)) else {
                return store.message ?? "The tags could not be saved."
            }
            message("Tags updated.")
            closeSurface()
            return nil
        case "pinEdit":
            guard let index = editorIndex, store.entries.indices.contains(index),
                  store.entries[index].kind == .motp else {
                return "The entry changed. Close and reopen the PIN prompt."
            }
            let current = store.entries[index]
            let newPin = draft["pin"] ?? ""
            do {
                var updated = try EntryDraft.create(
                    kind: .motp,
                    issuer: current.issuer,
                    label: current.label,
                    hiddenLabel: current.hiddenLabel,
                    secret: current.rawSecretText ?? "",
                    digits: String(current.digits),
                    timing: String(current.period),
                    pin: newPin,
                    tags: current.tags,
                    newEntry: false)
                updated = EntryDraft.preservingMetadata(of: current, in: updated)
                guard store.update(at: index, with: updated) else {
                    return store.message ?? "The PIN could not be saved."
                }
                draft["pin"] = ""
                message("PIN updated.")
                closeSurface()
                return nil
            } catch let error as EntryDraftError {
                return error.message
            } catch {
                return "The PIN could not be saved."
            }
        case "counterEdit":
            guard let index = editorIndex, store.entries.indices.contains(index),
                  store.entries[index].kind == .hotp else {
                return "The entry changed. Close and reopen the counter editor."
            }
            let current = store.entries[index]
            do {
                var updated = try EntryDraft.create(
                    kind: .hotp,
                    issuer: current.issuer,
                    label: current.label,
                    hiddenLabel: current.hiddenLabel,
                    secret: current.secretBase32,
                    digits: String(current.digits),
                    timing: draft["counter"] ?? "0",
                    tags: current.tags,
                    newEntry: false)
                updated = EntryDraft.preservingMetadata(of: current, in: updated)
                guard store.update(at: index, with: updated) else {
                    return store.message ?? "The counter could not be saved."
                }
                message("Counter updated.")
                closeSurface()
                return nil
            } catch let error as EntryDraftError {
                return error.message
            } catch {
                return "The counter could not be saved."
            }
        case "biometricEnable":
            guard let password = draft["password"], store.enableBiometricUnlock(password: password) else {
                return store.message ?? "Quick unlock could not be enabled."
            }
            message("Quick unlock enabled.")
            closeSurface()
            return nil
        case "autoLock":
            guard let raw = draft["seconds"], let seconds = Int(raw), seconds >= 0 else {
                return "Enter a delay of zero or more seconds."
            }
            store.setInactivityLockSeconds(seconds)
            message("Auto-lock delay updated.")
            closeSurface()
            return nil
        case "passwordChange":
            guard store.changePassword(currentPassword: draft["current"] ?? "",
                                       newPassword: draft["next"] ?? "",
                                       confirmation: draft["confirmation"] ?? "") else {
                return store.message ?? "The password could not be changed."
            }
            message("Vault password changed. The vault is locked again.")
            closeSurface()
            return nil
        case "prefsConfirm":
            do {
                let result = try PreferencesExport.apply(pendingImport, into: store.preferencesPort)
                store.reloadInactivityLock()
                pendingImport = Data()
                message("\(result.applied) settings imported.")
                closeSurface()
                return nil
            } catch {
                return "That file is not an OfflineOTP preferences file this version can read."
            }
        case let key where key.hasPrefix("passwordEdit:"):
            return savePasswordEdit()
        case "fairEmailImport":
            guard let plan = store.importFairEmailBackup(data: pendingImport,
                    password: draft["password"].flatMap({ $0.isEmpty ? nil : $0 })) else {
                return store.message ?? "The FairEmail backup could not be read."
            }
            pendingImport = Data()
            message(BackupWording.restoreSummary(plan, replace: false))
            closeSurface()
            return nil
        case "linScpImport":
            guard let epoch = linScpEpoch, store.isUnlocked, store.generation == epoch,
                  !linScpSelected.isEmpty,
                  linScpSelected.allSatisfy({ linScpCandidates.indices.contains($0) }) else {
                return "The selection changed. Close and reopen the import."
            }
            let chosen = linScpCandidates.enumerated()
                .filter { linScpSelected.contains($0.offset) }.map(\.element)
            guard let plan = store.importLinScpSelection(chosen) else {
                return store.message ?? "The LinSCP selection could not be imported."
            }
            linScpCandidates = []; linScpSelected = []; linScpPendingData = Data()
            message(BackupWording.restoreSummary(plan, replace: false))
            closeSurface()
            return nil
        case "linScpExport":
            return exportLinScpSelection()
        case "fairEmailExportSelect":
            let indices = (draft["selectedIndices"] ?? "").split(separator: ",").compactMap { Int($0) }
            guard !indices.isEmpty, indices.allSatisfy({ store.entries.indices.contains($0) }) else {
                return "Select at least one email account to export."
            }
            let chosen = indices.map { store.entries[$0] }
            guard let backup = store.exportFairEmailBackup(selected: chosen) else {
                return store.message ?? "The FairEmail backup could not be written."
            }
            onExport(backup.data, backup.name)
            closeSurface()
            return nil
        case "thunderbirdExportSelect":
            let indices = (draft["selectedIndices"] ?? "").split(separator: ",").compactMap { Int($0) }
            guard !indices.isEmpty, indices.allSatisfy({ store.entries.indices.contains($0) }) else {
                return "Select at least one credential to export."
            }
            let chosen = indices.map { store.entries[$0] }
            guard let backup = store.exportThunderbirdBackup(selected: chosen) else {
                return store.message ?? "The Thunderbird CSV could not be written."
            }
            onExport(backup.data, backup.name)
            closeSurface()
            return nil
        case "backupExportDialog":
            let password = draft["backupPassword"] ?? ""
            let confirmation = draft["backupConfirm"] ?? ""
            guard !password.isEmpty else { return "Enter a backup password first." }
            guard password == confirmation else { return "The backup passwords do not match." }
            guard let backup = store.exportBackup(password: password) else {
                return store.message ?? "The backup could not be written."
            }
            draft["backupPassword"] = ""; draft["backupConfirm"] = ""
            onExport(backup.data, backup.name)
            closeSurface()
            return nil
        case "uriImport":
            let raw = (draft["uri"] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let allowAdditional = draft["allowAdditional"] == "true"
            draft["uri"] = ""
            if raw.lowercased().hasPrefix("otpauth-migration:") {
                return applyImportedPayload(raw, allowAdditional: allowAdditional, closeWhenAdded: true)
            }
            guard store.importUri(raw, allowDuplicateIssuer: allowAdditional).didAdd else {
                return store.message ?? "That link could not be imported."
            }
            message("Entry imported.")
            closeSurface()
            return nil
        case "restore":
            let replace = draft["replace"] == "true"
            guard let plan = store.restore(encrypted: pendingImport,
                                           password: draft["password"] ?? "", replace: replace) else {
                return store.message ?? "The backup could not be read."
            }
            pendingImport = Data()
            message(plan.message ?? "Backup restored.")
            closeSurface()
            return nil
        case "openpgpRestore":
            guard !pendingImport.isEmpty, !openPgpSecretKey.isEmpty else {
                return "Choose both the encrypted backup and your secret key."
            }
            let replace = draft["replace"] == "true"
            guard let plan = store.restoreOpenPgpBackup(message: pendingImport,
                secretKeyBlock: openPgpSecretKey, passphrase: draft["passphrase"], replace: replace) else {
                return store.message ?? "The OpenPGP backup could not be restored."
            }
            pendingImport = Data(); openPgpSecretKey = Data()
            message(plan.message ?? "Backup restored.")
            closeSurface()
            return nil
        case "passwordPrompt":
            if let password = draft["password"], let backup = store.exportBackup(password: password) {
                onExport(backup.data, backup.name)
                closeSurface()
                return nil
            }
            return store.message ?? "Enter a backup password first."
        default: closeSurface(); return nil
        }
    }

    private func exportLinScpSelection() -> String? {
        guard let epoch = linScpEpoch, store.isUnlocked, store.generation == epoch,
              !linScpSelected.isEmpty,
              linScpSelected.allSatisfy({ store.entries.indices.contains($0) }) else {
            return "The selection changed. Close and reopen the export."
        }
        let chosen = linScpSelected.sorted().map { store.entries[$0] }
        guard chosen.allSatisfy({ candidate in store.entries.contains { $0.fields == candidate.fields } }) else {
            return "The selection changed. Close and reopen the export."
        }
        let plain = draft["linScpPlain"] == "true"
        if !plain && (draft["linScpExportPassword"] ?? "").isEmpty {
            return "Enter an export password first."
        }
        if !plain && draft["linScpExportPassword"] != draft["linScpExportConfirm"] {
            return "The export passwords do not match."
        }
        guard let result = store.exportLinScpVault(
            password: draft["linScpExportPassword"] ?? "", selected: chosen, plainText: plain) else {
            return store.message ?? "The LinSCP vault could not be written."
        }
        onExport(result.data, result.name)
        linScpSelected = []
        closeSurface()
        return nil
    }

    private func savePasswordEdit() -> String? {
        guard let index = passwordEditIndex, store.isUnlocked,
              openedGeneration.map { store.generation == $0 } == true,
              store.entries.indices.contains(index),
              store.entries[index].fields == openedFields,
              store.isRevealed(store.entries[index]),
              PasswordCsvExport.supportsCommonEdit(store.entries[index]) else {
            draft["password"] = ""
            return "The entry or session changed. Close and reopen the editor."
        }
        let url = draft["url"] ?? ""
        let username = draft["username"] ?? ""
        let password = draft["password"] ?? ""
        guard url.utf16.count <= PasswordCsvExport.commonEditFieldLimit,
              username.utf16.count <= PasswordCsvExport.commonEditFieldLimit,
              password.utf16.count <= PasswordCsvExport.commonEditFieldLimit else {
            return "Every value must fit the entry limits."
        }
        let entry = store.entries[index]
        do {
            let replacement = try PasswordCsvExport.withEditedFields(entry, url: url,
                username: username, password: password.isEmpty ? nil : password)
            guard store.update(at: index, with: replacement) else {
                return store.message ?? "The password entry could not be saved to the vault."
            }
            draft["password"] = ""
            message("Password entry updated.")
            closeSurface()
            return nil
        } catch {
            draft["password"] = ""
            return "The password entry could not be updated. A password cannot be empty."
        }
    }

    private func saveEmailEditor() -> String? {
        guard let inPort = Int(draft["inPort"]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""),
              (1...65535).contains(inPort),
              let outPort = Int(draft["outPort"]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""),
              (1...65535).contains(outPort) else {
            return "Server ports must be between 1 and 65535."
        }
        let inEnc = Int(draft["inEnc"] ?? "0") ?? 0
        let inProto = Int(draft["inProto"] ?? "0") ?? 0
        let outEnc = Int(draft["outEnc"] ?? "0") ?? 0
        let tags = (draft["tags"] ?? "").split(separator: ",").map(String.init)
        let typedInPass = draft["inPass"] ?? ""
        let typedOutPass = draft["outPass"] ?? ""
        do {
            if let index = editorIndex {
                guard store.entries.indices.contains(index),
                      let existingAccount = try? store.entries[index].emailAccount() else {
                    return "The email entry changed. Close and reopen the editor."
                }
                let inPass = typedInPass.isEmpty ? (existingAccount.account["password"]?.stringValue ?? "") : typedInPass
                let outPass = typedOutPass.isEmpty ? (existingAccount.identities.first?["password"]?.stringValue ?? "") : typedOutPass
                let validated = try EmailMaterial.create(
                    name: draft["name"] ?? "",
                    address: draft["address"] ?? "",
                    incomingHost: draft["inHost"] ?? "",
                    incomingPort: inPort,
                    incomingEncryption: inEnc,
                    incomingProtocol: inProto,
                    incomingUser: draft["inUser"] ?? "",
                    incomingPassword: inPass,
                    incomingAuth: Int(existingAccount.account["auth_type"]?.intValue ?? 1),
                    outgoingHost: draft["outHost"] ?? "",
                    outgoingPort: outPort,
                    outgoingEncryption: outEnc,
                    outgoingUser: draft["outUser"] ?? "",
                    outgoingPassword: outPass,
                    outgoingAuth: Int(existingAccount.identities.first?["auth_type"]?.intValue ?? 1))
                var mergedAccount = existingAccount.account
                for (k, v) in validated.account { mergedAccount[k] = v }
                var mergedIdentities = existingAccount.identities
                if mergedIdentities.isEmpty {
                    mergedIdentities = validated.identities
                } else {
                    var primary = mergedIdentities[0]
                    for (k, v) in validated.identity { primary[k] = v }
                    mergedIdentities[0] = primary
                }
                let account = EmailMaterial.Account(account: mergedAccount, identities: mergedIdentities)
                var built = try Entry.email(account: account, tags: tags)
                built = EntryDraft.preservingMetadata(of: store.entries[index], in: built)
                guard store.update(at: index, with: built) else {
                    return store.message ?? "The email account could not be saved."
                }
            } else {
                let account = try EmailMaterial.create(
                    name: draft["name"] ?? "",
                    address: draft["address"] ?? "",
                    incomingHost: draft["inHost"] ?? "",
                    incomingPort: inPort,
                    incomingEncryption: inEnc,
                    incomingProtocol: inProto,
                    incomingUser: draft["inUser"] ?? "",
                    incomingPassword: typedInPass,
                    outgoingHost: draft["outHost"] ?? "",
                    outgoingPort: outPort,
                    outgoingEncryption: outEnc,
                    outgoingUser: draft["outUser"] ?? "",
                    outgoingPassword: typedOutPass)
                guard store.addEmailAccount(account, tags: tags) else {
                    return store.message ?? "The email account could not be saved."
                }
            }
            draft["inPass"] = ""
            draft["outPass"] = ""
            message("Email account saved.")
            closeSurface()
            return nil
        } catch {
            return "Check the email address, host names, and credential fields."
        }
    }

    private func saveEditor() -> String? {
        let kind = EntryKind(rawValue: draft["type"] ?? "") ?? editorKind
        do {
            let timing = kind == .hotp ? (draft["counter"] ?? "0") : (draft["period"] ?? "30")
            var built = try EntryDraft.create(
                kind: kind,
                issuer: draft["issuer"] ?? "",
                label: draft["label"] ?? "",
                hiddenLabel: draft["hiddenLabel"] ?? "",
                secret: draft["secret"] ?? "",
                digits: draft["digits"] ?? "6",
                timing: timing,
                pin: draft["pin"] ?? "",
                tags: (draft["tags"] ?? "").split(separator: ",").map(String.init),
                publicKey: draft["publicKey"] ?? "",
                privateKey: draft["privateKey"] ?? "",
                sshHost: draft["sshHost"] ?? "",
                sshPortText: draft["sshPort"] ?? "",
                sshUsername: draft["sshUsername"] ?? "",
                sshPassword: draft["sshPassword"] ?? "",
                newEntry: editorIndex == nil)
            if let index = editorIndex, store.entries.indices.contains(index) {
                built = EntryDraft.preservingMetadata(of: store.entries[index], in: built)
                guard store.update(at: index, with: built) else {
                    return store.message ?? "The entry could not be saved."
                }
            } else {
                guard store.add(built) else { return store.message ?? "The entry could not be saved." }
            }
            message("Entry saved.")
            closeSurface()
            return nil
        } catch let error as EntryDraftError {
            return error.message
        } catch {
            return "The entry could not be saved."
        }
    }

    private func surfaceAction(_ key: String) -> String? {
        let parts = key.split(separator: ":", maxSplits: 1).map(String.init)
        guard let command = parts.first else { return nil }
        let index = parts.count > 1 ? Int(parts[1]) : editorIndex
        if command == "linScpSelect", let index {
            if linScpSelected.contains(index) { linScpSelected.remove(index) }
            else { linScpSelected.insert(index) }
            return nil
        }
        if command == "linScpExportSelect", let index {
            if linScpSelected.contains(index) { linScpSelected.remove(index) }
            else { linScpSelected.insert(index) }
            return nil
        }
        if command == "passwordImportMode", parts.count > 1 {
            let mode: PasswordImport.Mode = parts[1] == "archive" ? .archive : .individual
            guard let plan = store.importBrowserPasswords(
                filename: "passwords.csv", data: pendingImport, mode: mode) else {
                return store.message ?? "That password file could not be imported."
            }
            pendingImport = Data()
            message(BackupWording.restoreSummary(plan, replace: false))
            closeSurface()
            return nil
        }
        if command == "link", parts.count > 1, let url = URL(string: parts[1]),
           ["http", "https"].contains(url.scheme ?? "") {
            onOpenLink(url)
            return nil
        }
        if command == "aboutLink", parts.count > 1,
           ["website", "issues"].contains(parts[1]),
           let url = URL(string: AboutInfo.url(id: parts[1])),
           ["http", "https"].contains(url.scheme ?? "") {
            onOpenLink(url)
            return nil
        }
        switch command {
        case "about", "autoLock", "passwordChange", "settings", "backups", "uriImport":
            openSurface(command)
            return nil
        case "type":
            let offerable = EntryDraft.offerableKinds
            let current = EntryKind(rawValue: draft["type"] ?? "") ?? editorKind
            let next = offerable[((offerable.firstIndex(of: current) ?? 0) + 1) % offerable.count]
            draft["type"] = next.rawValue
            return nil
        case "emailCycleProto":
            let current = Int(draft["inProto"] ?? "0") ?? 0
            draft["inProto"] = current == 1 ? "0" : "1"
            return nil
        case "emailCycleInEnc":
            let current = Int(draft["inEnc"] ?? "0") ?? 0
            draft["inEnc"] = String((current + 1) % 3)
            return nil
        case "emailCycleOutEnc":
            let current = Int(draft["outEnc"] ?? "0") ?? 0
            draft["outEnc"] = String((current + 1) % 3)
            return nil
        case "toggleEmailSecrets":
            emailSecretsVisible.toggle()
            return nil
        case "prefsExport":
            do {
                let data = try PreferencesExport.export(store.preferencesPort).bytes
                onExport(data, "offlineotp-preferences.json")
                closeSurface()
                return nil
            } catch {
                return "The preferences could not be exported."
            }
        case "biometric":
            if store.biometricUnlockEnabled { store.disableBiometricUnlock(); closeSurface() }
            else { openSurface("biometricEnable") }
            return nil
        case "aboutLink":
            return nil
        case "openpgpUnavailable":
            return "This build does not include the OpenPGP engine."
        case "linScpImport":
            linScpEpoch = store.generation
            linScpCandidates = []; linScpSelected = []; draft = [:]
            closeSurface(); onImport("linScp"); return nil
        case "linScpExport":
            linScpEpoch = store.generation
            linScpSelected = []; draft = [:]
            openSurface("linScpExport", keepDraft: true)
            return nil
        case "linScpRead":
            guard let epoch = linScpEpoch, store.isUnlocked, store.generation == epoch else {
                return "The vault changed. Close and reopen the import."
            }
            do {
                linScpCandidates = try LinScpVault.importData(linScpPendingData,
                    password: draft["linScpPassword"].flatMap({ $0.isEmpty ? nil : $0 }))
                linScpSelected = []
                draft["linScpPassword"] = ""
                openSurface("linScpImport", keepDraft: true)
                return nil
            } catch {
                return "Could not read the file. Check the format and password."
            }
        case "linScpPlain":
            draft["linScpPlain"] = draft["linScpPlain"] == "true" ? "false" : "true"
            return nil
        case "restorePreview":
            guard let count = store.previewEncryptedBackupCount(
                encrypted: pendingImport, password: draft["password"] ?? "") else {
                return store.message ?? "The backup could not be read."
            }
            draft["previewCount"] = String(count)
            return nil
        case "fairEmailPreview":
            guard let count = store.previewFairEmailBackupCount(
                data: pendingImport,
                password: draft["password"].flatMap({ $0.isEmpty ? nil : $0 })
            ) else {
                return store.message ?? "The FairEmail backup could not be read."
            }
            draft["previewCount"] = String(count)
            return nil
        case "openpgpPreview":
            guard !pendingImport.isEmpty, !openPgpSecretKey.isEmpty else {
                return "Choose both the encrypted backup and your secret key."
            }
            guard let count = store.previewOpenPgpBackupCount(
                message: pendingImport,
                secretKeyBlock: openPgpSecretKey,
                passphrase: draft["passphrase"]
            ) else {
                return store.message ?? "The OpenPGP backup could not be restored."
            }
            draft["previewCount"] = String(count)
            return nil
        case "openpgpClearRecipients":
            openPgpRecipients = []
            openPgpRecipientNames = []
            return nil
        case "openpgpClearSecret":
            openPgpSecretKey = Data()
            draft.removeValue(forKey: "previewCount")
            return nil
        case "openpgpRecipient":
            guard openPgpRecipients.count < 8 else {
                return "Up to 8 recipient key files can be selected."
            }
            onImport("openpgpRecipient"); return nil
        case "openpgpExport":
            guard store.openPgpAvailability.canEncrypt else {
                return "This build does not include the OpenPGP engine."
            }
            if surfaceKey != "openpgpExportDialog" && openPgpRecipients.isEmpty {
                openSurface("openpgpExportDialog", keepDraft: true)
                return nil
            }
            guard !openPgpRecipients.isEmpty else {
                return "Choose a recipient public key first."
            }
            guard let backup = store.exportOpenPgpBackup(recipientKeyBlocks: openPgpRecipients) else {
                return store.message ?? "The OpenPGP backup could not be created."
            }
            onExport(backup.data, backup.name)
            closeSurface()
            return nil
        case "openpgpImport":
            closeSurface(); onImport("openpgpBackup"); return nil
        case "openpgpSecret":
            onImport("openpgpSecret"); return nil
        case "qrCamera":
            closeSurface(); onImport("qrCamera"); return nil
        case "qrImage":
            pendingQrImagePayload = nil
            qrImageFailed = false
            onImport("qrImage")
            return nil
        case "qrImageDiscard":
            pendingQrImagePayload = nil
            qrImageFailed = false
            return nil
        case "qrImageImport":
            guard let payload = pendingQrImagePayload else {
                return "Choose a QR image first."
            }
            pendingQrImagePayload = nil
            let allowAdditional = draft["allowAdditional"] == "true"
            return applyImportedPayload(payload, allowAdditional: allowAdditional, closeWhenAdded: true)
        case "backupImport", "csvImport", "importPasswordsCsv", "prefsImport",
             "fairEmailImport", "thunderbirdImport":
            // File selection stays native; the shared compose form asks for credentials.
            closeSurface()
            let kind: String
            switch command {
            case "prefsImport": kind = "preferences"
            case "importPasswordsCsv": kind = "passwordsCsv"
            case "fairEmailImport": kind = "fairEmail"
            case "thunderbirdImport": kind = "thunderbird"
            default: kind = "backup"
            }
            onImport(kind)
            return nil
        case "fairEmailExport":
            if store.entries.contains(where: { $0.kind == .email }) {
                draft = [:]
                openSurface("fairEmailExportSelect", keepDraft: true)
                return nil
            }
            guard let backup = store.exportFairEmailBackup() else {
                return store.message ?? "The FairEmail backup could not be written."
            }
            onExport(backup.data, backup.name)
            return nil
        case "thunderbirdExport":
            if store.entries.contains(where: { $0.kind == .email }) {
                draft = [:]
                openSurface("thunderbirdExportSelect", keepDraft: true)
                return nil
            }
            guard let backup = store.exportThunderbirdBackup() else {
                return store.message ?? "The Thunderbird CSV could not be written."
            }
            onExport(backup.data, backup.name)
            return nil
        case "backupExport":
            if (draft["backupPassword"] ?? "").isEmpty {
                draft = [:]
                openSurface("backupExportDialog", keepDraft: true)
                return nil
            }
            guard let password = draft["backupPassword"], !password.isEmpty,
                  let backup = store.exportBackup(password: password) else {
                return store.message ?? "Enter a backup password first."
            }
            onExport(backup.data, backup.name)
            closeSurface()
            return nil
        case "csvExport":
            guard let csv = store.exportBrowserPasswordsCsv() else {
                return store.message ?? "No browser passwords could be exported."
            }
            onExport(csv.data, "passwords.csv")
            closeSurface()
            return nil
        case "copy", "copyNote", "copyPublic", "copyPrivate", "copySshPassword",
             "copyPassword", "copyUsername", "copyImapPassword", "copySmtpPassword":
            guard let index, store.entries.indices.contains(index) else { return "The entry changed." }
            let entry = store.entries[index]
            if command == "copy" {
                copyCode(at: index)
                closeSurface()
                return nil
            }
            var secret = ""
            var label = "Value"
            switch command {
            case "copyNote": secret = entry.noteText ?? ""; label = "Note"
            case "copyPublic": secret = (try? entry.sshKeys().publicKey) ?? ""; label = "Public key"
            case "copyPrivate": secret = (try? entry.sshKeys().privateKey) ?? ""; label = "Private key"
            case "copySshPassword": secret = entry.sshConnection?.password ?? ""; label = "SSH password"
            case "copyImapPassword":
                secret = (try? entry.emailAccount().account["password"]?.stringValue) ?? ""
                label = "Incoming password"
            case "copySmtpPassword":
                secret = (try? entry.emailAccount().identities.first?["password"]?.stringValue) ?? ""
                label = "Outgoing password"
            case "copyPassword", "copyUsername":
                if let fields = try? entry.passwordFields() {
                    let wanted = command == "copyPassword" ? "password" : PasswordCsvExport.fieldUsername
                    secret = fields.items.first { $0.name.lowercased() == wanted.lowercased() }?.value ?? ""
                }
                label = command == "copyPassword" ? "Password" : "User name"
            default: break
            }
            guard !secret.isEmpty else { return "This entry has no \(label.lowercased()) to copy." }
            copySecret(secret, label: label, at: index)
            closeSurface()
            return nil
        case "reveal":
            guard let index else { return "The entry changed." }
            store.toggleReveal(at: index)
            closeSurface()
            return nil
        case "step":
            guard let index else { return "The entry changed." }
            if store.advanceCounter(at: index) { message("Counter stepped. Copy the new code.") }
            else { return store.message ?? "This entry has no counter." }
            closeSurface()
            return nil
        case "details":
            guard let index, store.entries.indices.contains(index) else { return "The entry changed." }
            openDetails(at: index)
            return nil
        case "tags":
            guard let index, store.entries.indices.contains(index) else { return "The entry changed." }
            editorIndex = index
            draft = ["tags": store.entries[index].tags.joined(separator: ", ")]
            openSurface("tagEdit", keepDraft: true)
            return nil
        case "pin":
            guard let index, store.entries.indices.contains(index),
                  store.entries[index].kind == .motp else { return "The entry changed." }
            editorIndex = index
            draft = ["pin": store.entries[index].pin]
            openSurface("pinEdit", keepDraft: true)
            return nil
        case "counter":
            guard let index, store.entries.indices.contains(index),
                  store.entries[index].kind == .hotp else { return "The entry changed." }
            editorIndex = index
            draft = ["counter": String(store.entries[index].counter)]
            openSurface("counterEdit", keepDraft: true)
            return nil
        case "qr":
            guard let index, store.otpUri(for: index) != nil else { return "This entry has no QR link." }
            editorIndex = index
            openSurface("qr", keepDraft: true)
            return nil
        case "edit":
            guard let index, store.entries.indices.contains(index) else { return "The entry changed." }
            openEntryEdit(at: index)
            return nil
        case "delete":
            guard let index, store.entries.indices.contains(index) else { return "The entry changed." }
            editorIndex = index
            openSurface("delete", keepDraft: true)
            return nil
        default: return nil
        }
    }

    /// The native picker hands only bytes to the adapter; the key data never reaches Compose.
    func setOpenPgpData(_ data: Data, kind: String, filename: String = "") {
        switch kind {
        case "openpgpRecipient":
            guard openPgpRecipients.count < 8 else { return }
            openPgpRecipients.append(data)
            let label = filename.isEmpty ? "Recipient key #\(openPgpRecipients.count)" : filename
            openPgpRecipientNames.append(label)
            if surfaceKey != "openpgpExportDialog" {
                openSurface("openpgpExportDialog", keepDraft: true)
            } else {
                refresh()
            }
        case "openpgpSecret":
            openPgpSecretKey = data
            draft.removeValue(forKey: "previewCount")
            if !pendingImport.isEmpty { openSurface("openpgpRestore", keepDraft: true) }
        case "openpgpBackup":
            pendingImport = data
            draft = [:]
            openSurface("openpgpRestore", keepDraft: true)
        default: break
        }
    }

    /// Receives a decoded QR image payload from `QrImageReader` without displaying the secret URI.
    func importedQrImagePayload(_ payload: String?) {
        guard active, store.isUnlocked else { return }
        if let payload, !payload.isEmpty {
            pendingQrImagePayload = payload
            qrImageFailed = false
        } else {
            pendingQrImagePayload = nil
            qrImageFailed = true
        }
        if surfaceKey != "uriImport" {
            openSurface("uriImport", keepDraft: true)
        } else {
            refresh()
        }
    }

    private func applyImportedPayload(_ text: String, allowAdditional: Bool, closeWhenAdded: Bool) -> String? {
        switch store.importPayload(text, allowDuplicateIssuer: allowAdditional) {
        case .added:
            message("Scanned code imported. Review the new entry before copying a code.")
            if closeWhenAdded { closeSurface() }
            return nil
        case .duplicateIssuer:
            return "An account for this issuer already exists. Enable the checkbox to add another."
        case .migration(let step):
            if let entries = step.entries {
                let result = store.confirmMigrationImport(entries)
                switch result {
                case .imported:
                    message(result.message)
                    if closeWhenAdded { closeSurface() }
                    return nil
                case .saveFailed, .locked:
                    return result.message
                }
            }
            return step.message
        case .duplicateEntry, .rejected:
            return store.message ?? "That QR code did not contain a supported link."
        }
    }

    /// A scanned otpauth payload is parsed by the native format layer, then offered as a draft.
    func importUriPayload(_ text: String) {
        guard active, store.isUnlocked, !text.isEmpty else { return }
        switch store.importPayload(text) {
        case .added:
            message("Scanned code imported. Review the new entry before copying a code.")
        case .duplicateIssuer:
            message("An account for this issuer already exists. Review the list before adding another.")
        case .migration(let step):
            if let entries = step.entries {
                message(store.confirmMigrationImport(entries).message)
            } else {
                message(step.message ?? "Migration code scanned. Scan the remaining codes.")
            }
        case .duplicateEntry, .rejected:
            message(store.message ?? "That QR code did not contain a supported link.")
        }
        refresh()
    }

    /// Native document pickers hand bytes to the shared restore dialog. Nothing is applied here.
    func imported(data: Data, kind: String) {
        guard active, store.isUnlocked, !data.isEmpty, data != lastImported else { return }
        lastImported = data
        pendingImport = data
        if kind == "fairEmail" {
            linScpPendingData = Data()
            if !store.needsFairEmailPassword(data),
               let plan = store.importFairEmailBackup(data: data, password: nil) {
                pendingImport = Data()
                message(BackupWording.restoreSummary(plan, replace: false))
                return
            }
            draft = [:]
            openSurface("fairEmailImport", keepDraft: true)
            return
        }
        if kind == "passwordsCsv" {
            linScpPendingData = Data()
            draft = [:]
            openSurface("passwordImportChoice", keepDraft: true)
            return
        }
        if kind == "thunderbird" {
            guard let plan = store.importThunderbirdBackup(data: data) else {
                message(store.message ?? "That file is not a valid Thunderbird CSV export.")
                return
            }
            pendingImport = Data()
            message(BackupWording.restoreSummary(plan, replace: false))
            return
        }
        if kind == "linScp" {
            linScpPendingData = data
            linScpEpoch = store.generation
            linScpCandidates = []
            linScpSelected = []
            draft = [:]
            openSurface("linScpImport", keepDraft: true)
            return
        }
        draft = ["password": ""]
        openSurface(kind == "preferences" ? "prefsConfirm" : "restore", keepDraft: true)
    }

    private func close() {
        active = false
        timer?.invalidate()
        presentation.close()
    }

    func invalidate() { close() }
}
