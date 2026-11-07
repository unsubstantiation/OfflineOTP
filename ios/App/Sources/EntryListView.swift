import SwiftUI
import UniformTypeIdentifiers
import OfflineOTPKit

/// The unlocked vault: the five tabs, a live search, the tag filter, the five orders, and one row
/// per entry with its code.
///
/// The screen is deliberately thin. Every rule that decides *which* entries show and in what order
/// comes from `EntryListProjection` in `OfflineOTPKit`, which mirrors `:core`; this file only draws
/// the result and sends a tap to `VaultStore`. That is what makes "the same vault shows the same
/// order and the same tabs as the other two clients" testable without a screenshot.
struct EntryListView: View {
    @ObservedObject var store: VaultStore

    @State private var toast: String?
    @State private var editor: EditorTarget?
    @State private var tagTarget: IndexedTarget?
    @State private var passwordTarget: IndexedTarget?
    @State private var emailTarget: IndexedTarget?
    @State private var addingEmailAccount = false
    @State private var archiveExport: BackupFile?
    /// True while the pending file is a browser CSV rather than an original archive.
    @State private var archiveExportIsBrowserCsv = false
    @State private var archiveExportName = "passwords.csv"
    @State private var exportingArchive = false
    @State private var deletion: DeletionTarget?
    @State private var backupsShown = false
    @State private var qrCode: QrTarget?
    @State private var settingsShown = false

    /// The QR sheet: the entry's link and the name to show above it.
    private struct QrTarget: Identifiable {
        let id = UUID()
        let title: String
        let payload: String
    }

    /// What the sheet should edit: nothing (add) or one stored position.
    private struct EditorTarget: Identifiable {
        let id = UUID()
        let index: Int?
        var initialKind: EntryKind = .totp
    }

    private struct IndexedTarget: Identifiable {
        let id = UUID()
        let index: Int
    }

    /// The confirmation a delete asks, naming the entry as both other clients do.
    private struct DeletionTarget: Identifiable {
        let id = UUID()
        let index: Int
        let name: String
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                CategoryTabStrip(store: store)
                SearchBar(store: store)
                if !store.tags(for: store.category).isEmpty || store.includeUntagged {
                    TagStrip(store: store)
                }
                entryList
            }
            .background(ForestPalette.background)
            .navigationTitle("OfflineOTP")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(ForestPalette.appBar, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Menu {
                        Button("Enter account details…") {
                            editor = EditorTarget(index: nil, initialKind: .totp)
                        }
                        Button("Add secure note…") {
                            editor = EditorTarget(index: nil, initialKind: .note)
                        }
                        Button("Add SSH key…") {
                            editor = EditorTarget(index: nil, initialKind: .sshKey)
                        }
                        Button("Add email account…") {
                            addingEmailAccount = true
                        }
                        Divider()
                        Button("Backups, CSV & QR imports…") {
                            backupsShown = true
                        }
                    } label: {
                        Image(systemName: "plus")
                            .foregroundColor(ForestPalette.textPrimary)
                    }
                    .accessibilityLabel("Add an entry")
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    SortMenu(store: store, onMessage: show)
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        backupsShown = true
                    } label: {
                        Image(systemName: "externaldrive")
                            .foregroundColor(ForestPalette.textPrimary)
                    }
                    .accessibilityLabel("Backups and links")
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        settingsShown = true
                    } label: {
                        Image(systemName: "gearshape")
                            .foregroundColor(ForestPalette.textPrimary)
                    }
                    .accessibilityLabel("Settings")
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        store.lock()
                    } label: {
                        Image(systemName: "lock.fill")
                            .foregroundColor(ForestPalette.textPrimary)
                    }
                    .accessibilityLabel("Lock the vault")
                }
            }
            .overlay(alignment: .bottom) {
                if let toast {
                    Text(toast)
                        .font(.footnote)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(ForestPalette.surfaceElevated)
                        .foregroundColor(ForestPalette.textPrimary)
                        .cornerRadius(12)
                        .padding(.horizontal, 24)
                        .padding(.bottom, 24)
                        .transition(.opacity)
                }
            }
            .animation(.easeInOut(duration: 0.15), value: toast)
            .sheet(item: $editor) { target in
                EntryEditorView(store: store, index: target.index, initialKind: target.initialKind)
            }
            .sheet(item: $tagTarget) { target in
                TagEditorSheet(store: store, index: target.index)
            }
            .sheet(item: $passwordTarget) { target in
                if store.entries.indices.contains(target.index) {
                    PasswordDetailSheet(entry: store.entries[target.index], index: target.index, store: store) {
                        secret, fieldName in
                        copySecret(secret, label: fieldName, from: target.index)
                    }
                }
            }
            .sheet(item: $emailTarget) { target in
                if store.entries.indices.contains(target.index) {
                    EmailDetailSheet(entry: store.entries[target.index]) { secret, fieldName in
                        copySecret(secret, label: fieldName, from: target.index)
                    }
                }
            }
            .sheet(isPresented: $addingEmailAccount) {
                EmailAccountEditorSheet(store: store)
            }
            .sheet(isPresented: $backupsShown) {
                BackupView(store: store)
            }
            .sheet(isPresented: $settingsShown) {
                SettingsView(store: store)
            }
            .sheet(item: $qrCode) { target in
                QrCodeView(title: target.title, payload: target.payload)
            }
            .fileExporter(isPresented: $exportingArchive, document: archiveExport,
                          contentType: .data, defaultFilename: archiveExportName) { result in
                switch result {
                case .success(let url):
                    show(archiveExportIsBrowserCsv
                        ? "Passwords exported to \(url.lastPathComponent). Protect or delete the plaintext copy securely."
                        : "Password file exported to \(url.lastPathComponent)")
                case .failure:
                    show("The password file was not exported.")
                }
                archiveExport = nil
            }
            .alert("Remove entry?", isPresented: Binding(
                get: { deletion != nil },
                set: { presented in if !presented { deletion = nil } }
            ), presenting: deletion) { target in
                Button("Remove", role: .destructive) {
                    if !store.remove(at: target.index) {
                        show(store.message ?? "The entry could not be removed.")
                    }
                }
                Button("Cancel", role: .cancel) {}
            } message: { target in
                Text("Are you sure you want to remove the account \"\(target.name)\"? This cannot be undone.")
            }
        }
    }

    @ViewBuilder
    private var entryList: some View {
        let indices = store.visibleIndices
        if indices.isEmpty {
            EmptyCategoryView(store: store, onAdd: { openCategoryAdd() })
        } else {
            TimelineView(.periodic(from: Date(), by: 1)) { context in
                List {
                    ForEach(Array(indices.enumerated()), id: \.element) { displayedPos, index in
                        let entry = store.entries[index]
                        EntryRowView(
                            entry: entry,
                            code: store.code(for: entry, at: context.date),
                            now: context.date,
                            onCopy: { code in copy(code, from: index) },
                            onCopyNamed: { secret, itemName in copySecret(secret, label: itemName, from: index) },
                            onReveal: { reveal(index) },
                            onStep: { step(at: index) },
                            onDetails: { openDetails(at: index) }
                        )
                        .listRowBackground(ForestPalette.surface)
                        .contextMenu {
                            EntryMenu(
                                entry: entry,
                                canMoveUp: store.canReorder && displayedPos > 0,
                                canMoveDown: store.canReorder && displayedPos < indices.count - 1,
                                onCopy: { code in copy(code, from: index) },
                                onCopyNamed: { secret, itemName in copySecret(secret, label: itemName, from: index) },
                                onReveal: { reveal(index) },
                                onStep: { step(at: index) },
                                onEdit: { editor = EditorTarget(index: index) },
                                onExportCsv: {
                                    guard store.entries.indices.contains(index) else { return }
                                    exportBrowserPasswords([store.entries[index]])
                                },
                                onTags: { tagTarget = IndexedTarget(index: index) },
                                onDetails: { openDetails(at: index) },
                                onQr: { showQr(at: index) },
                                onMoveUp: { _ = store.moveDisplayed(from: displayedPos, to: displayedPos - 1) },
                                onMoveDown: { _ = store.moveDisplayed(from: displayedPos, to: displayedPos + 1) },
                                onDelete: {
                                    deletion = DeletionTarget(index: index,
                                                              name: VaultStore.displayName(entry))
                                })
                        }
                    }
                    .onMove { offsets, destination in
                        guard store.canReorder, let first = offsets.first else { return }
                        let target = destination > first ? destination - 1 : destination
                        _ = store.moveDisplayed(from: first, to: target)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .background(ForestPalette.background)
            }
        }
    }

    // MARK: - Actions

    private func openCategoryAdd() {
        switch store.category {
        case .otp: editor = EditorTarget(index: nil, initialKind: .totp)
        case .notes: editor = EditorTarget(index: nil, initialKind: .note)
        case .sshKeys: editor = EditorTarget(index: nil, initialKind: .sshKey)
        case .passwords: backupsShown = true
        case .emails: addingEmailAccount = true
        }
    }

    /// Writes browser-imported passwords as one CSV in the dialect they share, mirroring
    /// Tools → **Export passwords as browser CSV…** on the desktop. Entries that did not come from a
    /// browser are left out: their column set has no browser form, so writing it here would drop it.
    private func exportBrowserPasswords(_ selection: [Entry]) {
        let candidates = selection.filter {
            $0.kind == .password && PasswordCsvExport.support($0) != .foreign
        }
        guard !candidates.isEmpty else {
            show("This entry was not imported from a browser, so it has no browser CSV form.")
            return
        }
        let dialect = PasswordCsvExport.agreedDialect(candidates)
        do {
            let data = try PasswordCsvExport.exportCsv(entries: candidates, dialect: dialect)
            archiveExportIsBrowserCsv = true
            archiveExportName = "passwords.csv"
            archiveExport = BackupFile(data: data)
            exportingArchive = true
        } catch {
            show("The passwords could not be exported.")
        }
    }

    private func openDetails(at index: Int) {
        guard store.entries.indices.contains(index) else { return }
        let entry = store.entries[index]
        guard entry.detailsVisible else { return }
        switch entry.kind {
        case .password:
            passwordTarget = IndexedTarget(index: index)
        case .passwordArchive:
            archiveExportIsBrowserCsv = false
            if let data = entry.passwordArchiveBytes {
                archiveExportName = entry.label.isEmpty ? "passwords.csv" : entry.label
                archiveExport = BackupFile(data: data)
                exportingArchive = true
            }
        case .email:
            emailTarget = IndexedTarget(index: index)
        default:
            break
        }
    }

    private func copy(_ code: String, from index: Int) {
        // A copy is a use: the stamp and the counter **Last used** and **Most used** read, exactly as
        // on the other clients.
        if !store.recordUse(at: index) {
            show(store.message ?? "The code was copied, but its use could not be recorded.")
        }
        Clipboard.copy(code)
        show("Code copied — the clipboard clears in \(Int(Clipboard.expirySeconds)) s")
    }

    private func copySecret(_ secret: String, label: String, from index: Int) {
        _ = store.recordUse(at: index)
        Clipboard.copy(secret)
        show("\(label) copied — the clipboard clears in \(Int(Clipboard.expirySeconds)) s")
    }

    private func reveal(_ index: Int) {
        let visible = store.entries[index].detailsVisible
        if !store.setDetailsVisible(at: index, !visible) {
            show(store.message ?? "The change could not be saved, so the entry stays concealed.")
        }
    }

    private func showQr(at index: Int) {
        guard let payload = store.otpUri(for: index) else {
            show("This entry cannot be shown as a QR code.")
            return
        }
        let entry = store.entries[index]
        qrCode = QrTarget(title: entry.issuer.isEmpty ? entry.label : entry.issuer,
                          payload: payload)
    }

    private func step(at index: Int) {
        if store.advanceCounter(at: index) {
            show("Counter stepped. Copy the new code for this step.")
        } else {
            show(store.message ?? "This entry has no counter to step.")
        }
    }

    private func show(_ text: String) {
        toast = text
        let current = toast
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
            if toast == current { toast = nil }
        }
    }
}

/// The five tabs, in the other clients' order, with the number of entries each one holds.
///
/// Every entry lands in exactly one tab, including the kinds this build cannot display, so switching
/// tabs can never make vault data disappear — it only changes which part of it is on screen.
struct CategoryTabStrip: View {
    @ObservedObject var store: VaultStore

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(EntryCategory.allCases, id: \.self) { category in
                    let selected = store.category == category
                    Button {
                        store.category = category
                    } label: {
                        HStack(spacing: 6) {
                            Text(category.title)
                            Text("\(store.entryCount(for: category))")
                                .opacity(0.7)
                        }
                        .font(.subheadline.weight(selected ? .semibold : .regular))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(selected ? ForestPalette.appBar : ForestPalette.surface)
                        .foregroundColor(selected ? ForestPalette.textPrimary
                                                  : ForestPalette.textSecondary)
                        .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(category.title) tab")
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
    }
}

/// The live search. It stays live across a tab, tag or sort change — both other clients keep it, and
/// the desktop test `aLiveSearchSurvivesTabAndTagChangesLikeAndroid` pins that rule.
struct SearchBar: View {
    @ObservedObject var store: VaultStore

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundColor(ForestPalette.textHint)
            TextField("Search label, issuer or tag", text: $store.query)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .foregroundColor(ForestPalette.textPrimary)
            if !store.query.isEmpty {
                Button {
                    store.query = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(ForestPalette.textHint)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear the search")
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(ForestPalette.surface)
        .cornerRadius(10)
        .padding(.horizontal, 16)
    }
}

/// The tag chips for the current tab, with the combination mode and the tagless switch.
///
/// The rule behind them is `EntryTagFilter.matches`, shared with both other clients, so the same
/// selection shows the same entries everywhere.
struct TagStrip: View {
    @ObservedObject var store: VaultStore

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(store.tags(for: store.category), id: \.self) { tag in
                    TagChip(title: tagChipTitle(tag),
                            selected: store.selectedTags.contains(tag)) {
                        store.toggleTag(tag)
                    }
                }
                TagChip(title: "Untagged", selected: store.includeUntagged) {
                    store.includeUntagged.toggle()
                }
                Menu {
                    ForEach(TagFunctionality.allCases, id: \.self) { functionality in
                        Button {
                            store.setTagFunctionality(functionality)
                        } label: {
                            Text(functionality == store.tagFunctionality
                                 ? "✓ \(functionalityLabel(functionality))"
                                 : functionalityLabel(functionality))
                        }
                    }
                } label: {
                    Text(functionalityLabel(store.tagFunctionality))
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(ForestPalette.surfaceVariant)
                        .foregroundColor(ForestPalette.textPrimary)
                        .clipShape(Capsule())
                }
                .accessibilityLabel("How selected tags combine")
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
        }
    }

    private func functionalityLabel(_ functionality: TagFunctionality) -> String {
        switch functionality {
        case .or: return "Any tag"
        case .and: return "All tags"
        case .single: return "One tag"
        }
    }

    /// The chip text: `name (count)` like Android's drawer rows and the desktop filter panel, so a
    /// tag is never shown without how many entries carry it. A tag with no count yet keeps its bare
    /// name, which is what Android's `TagsAdapter` does when the counts map has no entry.
    private func tagChipTitle(_ tag: String) -> String {
        guard let count = store.tagCounts(for: store.category)[tag] else { return tag }
        return "\(tag) (\(count))"
    }
}

struct TagChip: View {
    let title: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.caption)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(selected ? ForestPalette.primary : ForestPalette.surface)
                .foregroundColor(selected ? ForestPalette.onPrimary : ForestPalette.textSecondary)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// The five orders, under the same names and in the same order as the other clients' menus.
struct SortMenu: View {
    @ObservedObject var store: VaultStore
    let onMessage: (String) -> Void

    var body: some View {
        Menu {
            ForEach(SortMode.allCases, id: \.self) { mode in
                Button {
                    store.setSortMode(mode)
                    onMessage("Sorted by \(mode.title.lowercased())")
                } label: {
                    Text(mode == store.sortMode ? "✓ \(mode.title)" : mode.title)
                }
            }
        } label: {
            Image(systemName: "arrow.up.arrow.down")
                .foregroundColor(ForestPalette.textPrimary)
        }
        .accessibilityLabel("Sort the list, currently \(store.sortMode.title)")
    }
}

/// One entry: who it is for, its code in amber, and the countdown that says how long it lasts.
///
/// A concealed entry shows no code and offers no copy — the rule both other clients apply by gating
/// the copy button on `details_visible`. Its issuer and label stay visible, because they are what
/// identifies the row a person is trying to reveal.
struct EntryAvatarView: View {
    let text: String

    private var initial: String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let ch = trimmed.first else { return "?" }
        return String(ch).uppercased()
    }

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 8)
                .fill(ForestPalette.surfaceVariant)
                .frame(width: 36, height: 36)
            Text(initial)
                .font(.subheadline.weight(.bold))
                .foregroundColor(ForestPalette.accent)
        }
        .accessibilityHidden(true)
    }
}

struct EntryRowView: View {
    let entry: Entry
    let code: String?
    let now: Date
    let onCopy: (String) -> Void
    var onCopyNamed: (String, String) -> Void = { _, _ in }
    let onReveal: () -> Void
    let onStep: () -> Void
    var onDetails: () -> Void = {}

    private var kindName: String { entry.kind?.rawValue ?? "ENTRY" }

    private var remaining: Int? {
        CodeFormat.secondsRemaining(kind: kindName, period: entry.period, now: now)
    }

    private var fraction: Double {
        CodeFormat.fractionRemaining(kind: kindName, period: entry.period, now: now)
    }

    private var soon: Bool {
        guard let remaining else { return false }
        return remaining <= 5
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 14) {
                EntryAvatarView(text: title)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(.headline)
                        .foregroundColor(ForestPalette.textPrimary)
                        .lineLimit(1)
                    if !entry.issuer.isEmpty, !entry.label.isEmpty {
                        Text(entry.label)
                            .font(.caption)
                            .foregroundColor(ForestPalette.textSecondary)
                            .lineLimit(1)
                    }
                    if entry.detailsVisible, !entry.hiddenLabel.isEmpty {
                        Text(entry.hiddenLabel)
                            .font(.caption)
                            .foregroundColor(ForestPalette.accent)
                            .lineLimit(1)
                    }
                    Text(subtitle)
                        .font(.caption2)
                        .foregroundColor(ForestPalette.textHint)
                }
                // One stop for the name, so VoiceOver does not read three fragments as three items, and
                // so a long issuer and a long label are read in the order they are drawn.
                .accessibilityElement(children: .combine)
                .accessibilityLabel("\(title), \(subtitle)")
                Spacer(minLength: 8)
                trailing
            }
            if entry.detailsVisible, entry.kind == .note, let note = entry.noteText, !note.isEmpty {
                Text(note)
                    .font(.footnote)
                    .foregroundColor(ForestPalette.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(ForestPalette.surfaceVariant)
                    .cornerRadius(8)
            }
        }
        .padding(.vertical, 6)
    }

    @ViewBuilder
    private var trailing: some View {
        if !entry.detailsVisible {
            Button(action: onReveal) {
                Image(systemName: "eye")
                    .font(.title3)
                    .foregroundColor(ForestPalette.textSecondary)
                    // The minimum a finger — or a Switch Control cursor — can reliably hit.
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Reveal \(title)")
            .accessibilityHint("Shows the code and lets it be copied")
        } else if entry.kind?.isOtp == true, let code {
            HStack(spacing: 8) {
                Button {
                    if entry.kind == .hotp { onStep() } else { onCopy(code) }
                } label: {
                    Text(CodeFormat.grouped(code))
                        .font(.system(.title3, design: .monospaced).weight(.semibold))
                        .foregroundColor(soon ? ForestPalette.warning : ForestPalette.otpAmber)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
                .buttonStyle(.plain)
                // The action, then how the code actually sounds, then how long it has left: read as
                // one character at a time so it can be typed into another client.
                .accessibilityLabel(entry.kind == .hotp ? "Next code" : "Copy code")
                .accessibilityValue(accessibilityCode(code))
                .accessibilityHint(entry.kind == .hotp
                                   ? "Steps the counter to the next code"
                                   : "Copies the code; the clipboard clears after "
                                     + "\(Int(Clipboard.expirySeconds)) seconds")
                if entry.kind != .hotp {
                    CountdownRing(fraction: fraction, soon: soon)
                }
            }
        } else if entry.kind?.isOtp == true {
            // Revealed but without a code: an mOTP entry with no PIN.
            Text(Entry.motpNoPinCode)
                .font(.caption.weight(.semibold))
                .foregroundColor(ForestPalette.warning)
                .accessibilityLabel("This entry needs a PIN before it can show a code")
        } else if entry.kind == .note, let note = entry.noteText {
            Button("Copy") {
                onCopyNamed(note, "Note")
            }
            .buttonStyle(.bordered)
            .tint(ForestPalette.primary)
            .accessibilityLabel("Copy note")
        } else if entry.kind == .sshKey, let keys = try? entry.sshKeys() {
            Menu {
                if !keys.publicKey.isEmpty {
                    Button("Copy public key") { onCopyNamed(keys.publicKey, "Public key") }
                }
                if !keys.privateKey.isEmpty {
                    Button("Copy private key") { onCopyNamed(keys.privateKey, "Private key") }
                }
                if let pw = entry.sshConnection?.password, !pw.isEmpty {
                    Button("Copy SSH password") { onCopyNamed(pw, "SSH password") }
                }
            } label: {
                Image(systemName: "key.fill")
                    .foregroundColor(ForestPalette.accent)
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel("Copy SSH credential")
        } else if entry.kind == .password || entry.kind == .email {
            Button(action: onDetails) {
                Text("Details")
                    .font(.caption.weight(.semibold))
            }
            .buttonStyle(.bordered)
            .tint(ForestPalette.primary)
            .accessibilityLabel("Open \(title) details")
        } else if entry.kind == .passwordArchive {
            Button(action: onDetails) {
                Text("Export")
                    .font(.caption.weight(.semibold))
            }
            .buttonStyle(.bordered)
            .tint(ForestPalette.primary)
            .accessibilityLabel("Export \(title)")
        } else {
            Image(systemName: "doc.text")
                .foregroundColor(ForestPalette.textHint)
                .accessibilityLabel("No code")
        }
    }

    /// The spoken value of the code: the characters one at a time, plus the time left when the entry
    /// rolls over — the ring itself is decorative and hidden from VoiceOver.
    private func accessibilityCode(_ code: String) -> String {
        let spoken = CodeFormat.spoken(code)
        let timeLeft = CodeFormat.remainingDescription(remaining)
        return timeLeft.isEmpty ? spoken : spoken + ", " + timeLeft
    }

    private var title: String {
        if !entry.issuer.isEmpty { return entry.issuer }
        if !entry.label.isEmpty { return entry.label }
        return "Untitled entry"
    }

    /// What the row says under the name: the kind, and for an SSH key its destination summary when revealed.
    private var subtitle: String {
        guard let kind = entry.kind else { return "Unrecognised entry" }
        let concealed = entry.detailsVisible ? "" : " · concealed"
        switch kind {
        case .note: return "Secure note" + concealed
        case .sshKey:
            if entry.detailsVisible, let dest = entry.sshConnection?.summary {
                return "SSH key · \(dest)"
            }
            return "SSH key" + concealed
        case .password: return "Saved password" + concealed
        case .passwordArchive: return "Password archive" + concealed
        case .email: return "Email account" + concealed
        case .hotp: return "HOTP · counter \(entry.counter)" + concealed
        case .motp: return (entry.pin.isEmpty ? "mOTP · PIN required" : "mOTP · 10 s") + concealed
        case .steam: return "Steam · \(entry.period) s" + concealed
        case .totp: return "TOTP · \(entry.period) s" + concealed
        }
    }
}

/// The row menu: the same actions the other clients offer, gated by the same rules.
struct EntryMenu: View {
    let entry: Entry
    var canMoveUp: Bool = false
    var canMoveDown: Bool = false
    let onCopy: (String) -> Void
    var onCopyNamed: (String, String) -> Void = { _, _ in }
    let onReveal: () -> Void
    let onStep: () -> Void
    let onEdit: () -> Void
    /// Writes this entry as a browser CSV; offered only when it has one (`PasswordCsvExport`).
    var onExportCsv: () -> Void = {}
    var onTags: () -> Void = {}
    var onDetails: () -> Void = {}
    let onQr: () -> Void
    var onMoveUp: () -> Void = {}
    var onMoveDown: () -> Void = {}
    let onDelete: () -> Void

    private var editable: Bool {
        guard entry.detailsVisible else { return false }
        switch entry.kind {
        case .password, .passwordArchive, .email, nil: return false
        default: return true
        }
    }

    var body: some View {
        Button(entry.detailsVisible ? "Conceal" : "Reveal", action: onReveal)
        if entry.detailsVisible, entry.kind?.isOtp == true {
            Button("Copy code") {
                if let code = try? entry.code(unixTime: Int64(Date().timeIntervalSince1970)) {
                    onCopy(code)
                }
            }
            if entry.kind == .hotp {
                Button("Step counter", action: onStep)
            }
        }
        if entry.detailsVisible, entry.kind == .note, let note = entry.noteText {
            Button("Copy note") { onCopyNamed(note, "Note") }
        }
        if entry.detailsVisible, entry.kind == .sshKey, let keys = try? entry.sshKeys() {
            if !keys.publicKey.isEmpty {
                Button("Copy public key") { onCopyNamed(keys.publicKey, "Public key") }
            }
            if !keys.privateKey.isEmpty {
                Button("Copy private key") { onCopyNamed(keys.privateKey, "Private key") }
            }
            if let pw = entry.sshConnection?.password, !pw.isEmpty {
                Button("Copy SSH password") { onCopyNamed(pw, "SSH password") }
            }
        }
        if entry.kind == .password {
            Button("View password details…", action: onDetails)
                .disabled(!entry.detailsVisible)
            if case .exportable(let dialect) = PasswordCsvExport.support(entry) {
                Button("Export as \(dialect.browserName) CSV…", action: onExportCsv)
                    .disabled(!entry.detailsVisible)
            }
            if entry.detailsVisible, let pw = (try? entry.passwordFields())?.value("password"), !pw.isEmpty {
                Button("Copy password") { onCopyNamed(pw, "Password") }
            }
        }
        if entry.kind == .passwordArchive {
            Button("Export password file…", action: onDetails)
                .disabled(!entry.detailsVisible)
        }
        if entry.kind == .email {
            Button("View email details…", action: onDetails)
                .disabled(!entry.detailsVisible)
        }
        if entry.kind?.isOtp == true {
            Button("Show QR code…", action: onQr)
                .disabled(!entry.detailsVisible)
        }
        Button("Edit…", action: onEdit)
            .disabled(!editable)
        Button("Tags…", action: onTags)
            .disabled(!entry.detailsVisible)
        if canMoveUp || canMoveDown {
            Divider()
            Button("Move up", action: onMoveUp).disabled(!canMoveUp)
            Button("Move down", action: onMoveDown).disabled(!canMoveDown)
        }
        Button("Remove…", role: .destructive, action: onDelete)
            .disabled(!entry.detailsVisible)
    }
}

/// The countdown around the code: a full ring at the start of a step, empty at the end.
struct CountdownRing: View {
    let fraction: Double
    let soon: Bool

    var body: some View {
        ZStack {
            Circle()
                .stroke(ForestPalette.otpAmberMuted, lineWidth: 3)
            Circle()
                .trim(from: 0, to: max(0, min(1, fraction)))
                .stroke(soon ? ForestPalette.warning : ForestPalette.accent,
                        style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .frame(width: 26, height: 26)
        .accessibilityHidden(true)
    }
}

/// What a tab says when the vault has nothing to show there — never an empty screen with no reason.
struct EmptyCategoryView: View {
    @ObservedObject var store: VaultStore
    let onAdd: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "tray")
                .font(.system(size: 40, weight: .light))
                .foregroundColor(ForestPalette.textHint)
            Text(headline)
                .font(.headline)
                .foregroundColor(ForestPalette.textPrimary)
            Text(detail)
                .font(.footnote)
                .multilineTextAlignment(.center)
                .foregroundColor(ForestPalette.textSecondary)
            if store.query.isEmpty, store.selectedTags.isEmpty {
                Button("Add an entry", action: onAdd)
                    .buttonStyle(.borderedProminent)
                    .tint(ForestPalette.primary)
            } else {
                Button("Clear the search and tags") {
                    store.clearFilters()
                }
                .buttonStyle(.bordered)
                .tint(ForestPalette.accent)
            }
            Spacer()
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(ForestPalette.background)
    }

    private var headline: String {
        if !store.query.isEmpty { return "No entry matches \"\(store.query)\"" }
        if !store.selectedTags.isEmpty || store.includeUntagged {
            return "No entry with those tags"
        }
        return "Nothing in \(store.category.title) yet"
    }

    private var detail: String {
        if !store.query.isEmpty {
            return "The search covers labels, issuers and tags. Changing the tab does not end it."
        }
        if !store.selectedTags.isEmpty || store.includeUntagged {
            return "Tag filters combine with the search and the chosen order."
        }
        switch store.category {
        case .otp: return "Time-based and counter-based codes appear here."
        case .notes: return "Add a secure note to store encrypted text in your vault."
        case .sshKeys: return "Add an SSH key or import a LinSCP credential vault."
        case .passwords: return "Import a browser password CSV or LinSCP session file."
        case .emails: return "Add an email account or import FairEmail / Thunderbird credentials."
        }
    }
}
