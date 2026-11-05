import SwiftUI

/// The app shell.
///
/// Unlock a vault, then the same list the other two clients show: five tabs, a live search, the tag
/// filter, the five orders, a reveal per entry, codes with a countdown, and add, edit and delete.
/// Everything about the *format* and every rule about *which* entry shows where — the vault files,
/// the backup, the entry document, the tokens, the tabs, the search, the tag rule, the orders — is in
/// `OfflineOTPKit` and shared with the other clients through the fixed vectors; this layer only
/// draws it. See docs/ios-client-plan.md for what is still missing (imports, backups, Face ID).
@main
struct OfflineOTPApp: App {
    @StateObject private var store = VaultStore()

    var body: some Scene {
        WindowGroup {
            RootView(store: store)
                .preferredColorScheme(.dark)
        }
    }
}

struct RootView: View {
    @ObservedObject var store: VaultStore
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            ForestPalette.background.ignoresSafeArea()
            if store.isUnlocked {
                EntryListView(store: store)
            } else {
                UnlockView(store: store)
            }
            if scenePhase != .active {
                // iOS cannot block screenshots the way Android's FLAG_SECURE does, and it takes the
                // app-switcher snapshot while the scene is inactive. Covering the content with the
                // app background keeps a code out of that snapshot.
                ForestPalette.background.ignoresSafeArea()
                Image(systemName: "lock.fill")
                    .font(.system(size: 44, weight: .light))
                    .foregroundColor(ForestPalette.textHint)
            }
        }
    }
}
