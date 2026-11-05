import SwiftUI
import OfflineOTPKit

/// The unlocked vault: every entry, its code and how long that code has left.
///
/// This is the M1.5 shell, so it lists what the vault holds and copies a code. It deliberately does
/// not claim the tabs, search, tag filter or sort the other clients have — those are M3 in
/// docs/ios-client-plan.md, and pretending otherwise by hiding entries would be worse than showing
/// them all.
struct EntryListView: View {
    @ObservedObject var store: VaultStore
    @State private var toast: String?

    var body: some View {
        NavigationStack {
            TimelineView(.periodic(from: Date(), by: 1)) { context in
                List {
                    // Indices, not the entries themselves: an entry is a bag of decoded fields and has
                    // no identity of its own, so its position is the only stable identifier in the list.
                    ForEach(store.entries.indices, id: \.self) { index in
                        let entry = store.entries[index]
                        EntryRowView(
                            entry: entry,
                            code: store.code(for: entry, at: context.date),
                            now: context.date,
                            onCopy: { code in copy(code) })
                        .listRowBackground(ForestPalette.surface)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .background(ForestPalette.background)
            }
            .navigationTitle("OfflineOTP")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(ForestPalette.appBar, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
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
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(ForestPalette.surfaceElevated)
                        .foregroundColor(ForestPalette.textPrimary)
                        .cornerRadius(12)
                        .padding(.bottom, 24)
                        .transition(.opacity)
                }
            }
            .animation(.easeInOut(duration: 0.15), value: toast)
        }
    }

    private func copy(_ code: String) {
        Clipboard.copy(code)
        toast = "Code copied — the clipboard clears in \(Int(Clipboard.expirySeconds)) s"
        let current = toast
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            if toast == current { toast = nil }
        }
    }
}

/// One entry: who it is for, the code in amber, and the countdown that says how long it lasts.
struct EntryRowView: View {
    let entry: Entry
    let code: String?
    let now: Date
    let onCopy: (String) -> Void

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
                Text(entry.issuer.isEmpty ? entry.label : entry.issuer)
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
            if let code, entry.kind?.isOtp == true {
                Button {
                    onCopy(code)
                } label: {
                    Text(CodeFormat.grouped(code))
                        .font(.system(.title3, design: .monospaced).weight(.semibold))
                        .foregroundColor(soon ? ForestPalette.warning : ForestPalette.otpAmber)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Copy code \(code)")
                CountdownRing(fraction: fraction, soon: soon)
            } else {
                Image(systemName: "doc.text")
                    .foregroundColor(ForestPalette.textHint)
            }
        }
        .padding(.vertical, 6)
    }

    /// What the row says under the name: the kind, and for a note or key the reason there is no code.
    private var subtitle: String {
        guard let kind = entry.kind else { return "Unrecognised entry" }
        switch kind {
        case .note: return "Note — no code, open it on a client that shows notes"
        case .sshKey: return "SSH key — no code"
        case .password, .passwordArchive: return "Password archive — no code"
        case .email: return "Email account — no code"
        case .hotp: return "HOTP · counter \(entry.counter)"
        case .motp: return entry.pin.isEmpty ? "mOTP · PIN required" : "mOTP · 10 s"
        case .steam: return "Steam · \(entry.period) s"
        case .totp: return "TOTP · \(entry.period) s"
        }
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
