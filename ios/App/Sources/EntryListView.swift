import SwiftUI
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
    @State private var deletion: DeletionTarget?

    /// What the sheet should edit: nothing (add) or one stored position.
    private struct EditorTarget: Identifiable {
        let id = UUID()
        let index: Int?
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
                    Button {
                        editor = EditorTarget(index: nil)
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
                EntryEditorView(store: store, index: target.index)
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
            EmptyCategoryView(store: store, onAdd: { editor = EditorTarget(index: nil) })
        } else {
            TimelineView(.periodic(from: Date(), by: 1)) { context in
                List {
                    ForEach(indices, id: \.self) { index in
                        let entry = store.entries[index]
                        EntryRowView(
                            entry: entry,
                            code: store.code(for: entry, at: context.date),
                            now: context.date,
                            onCopy: { code in copy(code, from: index) },
                            onReveal: { reveal(index) },
                            onStep: { step(at: index) }
                        )
                        .listRowBackground(ForestPalette.surface)
                        .contextMenu {
                            EntryMenu(
                                entry: entry,
                                onCopy: { code in copy(code, from: index) },
                                onReveal: { reveal(index) },
                                onStep: { step(at: index) },
                                onEdit: { editor = EditorTarget(index: index) },
                                onDelete: {
                                    deletion = DeletionTarget(index: index,
                                                              name: VaultStore.displayName(entry))
                                })
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .background(ForestPalette.background)
            }
        }
    }

    // MARK: - Actions

    private func copy(_ code: String, from index: Int) {
        // A copy is a use: the stamp and the counter **Last used** and **Most used** read, exactly as
        // on the other clients.
        if !store.recordUse(at: index) {
            show(store.message ?? "The code was copied, but its use could not be recorded.")
        }
        Clipboard.copy(code)
        show("Code copied — the clipboard clears in \(Int(Clipboard.expirySeconds)) s")
    }

    private func reveal(_ index: Int) {
        let visible = store.entries[index].detailsVisible
        if !store.setDetailsVisible(at: index, !visible) {
            show(store.message ?? "The change could not be saved, so the entry stays concealed.")
        }
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
                    TagChip(title: tag, selected: store.selectedTags.contains(tag)) {
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
struct EntryRowView: View {
    let entry: Entry
    let code: String?
    let now: Date
    let onCopy: (String) -> Void
    let onReveal: () -> Void
    let onStep: () -> Void

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
        HStack(spacing: 14) {
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
                Text(subtitle)
                    .font(.caption2)
                    .foregroundColor(ForestPalette.textHint)
            }
            Spacer(minLength: 8)
            trailing
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
                    .frame(width: 44, height: 32)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Reveal \(title)")
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
                .accessibilityLabel(entry.kind == .hotp
                                    ? "Step the counter, currently \(code)"
                                    : "Copy code \(code)")
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
        } else {
            Image(systemName: "doc.text")
                .foregroundColor(ForestPalette.textHint)
                .accessibilityLabel("No code")
        }
    }

    private var title: String {
        if !entry.issuer.isEmpty { return entry.issuer }
        if !entry.label.isEmpty { return entry.label }
        return "Untitled entry"
    }

    /// What the row says under the name: the kind, and for a note or key the reason there is no code.
    private var subtitle: String {
        guard let kind = entry.kind else { return "Unrecognised entry" }
        let concealed = entry.detailsVisible ? "" : " · concealed"
        switch kind {
        case .note: return "Note — no code, open it on a client that shows notes" + concealed
        case .sshKey: return "SSH key — no code" + concealed
        case .password, .passwordArchive: return "Password archive — no code" + concealed
        case .email: return "Email account — no code" + concealed
        case .hotp: return "HOTP · counter \(entry.counter)" + concealed
        case .motp: return (entry.pin.isEmpty ? "mOTP · PIN required" : "mOTP · 10 s") + concealed
        case .steam: return "Steam · \(entry.period) s" + concealed
        case .totp: return "TOTP · \(entry.period) s" + concealed
        }
    }
}

/// The row menu: the same actions the other clients offer, gated by the same rules.
///
/// Copy, Edit, Tags and QR are unavailable until the entry is revealed; Edit is also unavailable for
/// the structured kinds this client can only preserve — Android and the desktop disable exactly
/// those.
struct EntryMenu: View {
    let entry: Entry
    let onCopy: (String) -> Void
    let onReveal: () -> Void
    let onStep: () -> Void
    let onEdit: () -> Void
    let onDelete: () -> Void

    private var editable: Bool {
        guard entry.detailsVisible else { return false }
        switch entry.kind {
        case .password, .passwordArchive, .email, .sshKey, nil: return false
        default: return true
        }
    }

    var body: some View {
        Button(entry.detailsVisible ? "Conceal" : "Reveal", action: onReveal)
        if entry.detailsVisible, entry.kind?.isOtp == true {
            Button("Copy code") {
                // The row already holds the code it drew; the menu asks the same generator again so
                // a stale value can never be copied from a menu that was open across a step.
                if let code = try? entry.code(unixTime: Int64(Date().timeIntervalSince1970)) {
                    onCopy(code)
                }
            }
            if entry.kind == .hotp {
                Button("Step counter", action: onStep)
            }
        }
        Button("Edit…", action: onEdit)
            .disabled(!editable)
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
        case .notes: return "Secure notes are listed here; they have no code."
        case .sshKeys: return "SSH keys are listed here. This build shows them but cannot create them."
        case .passwords: return "Password archives are listed here; only an import creates them."
        case .emails: return "Email accounts are listed here; only an import creates them."
        }
    }
}
