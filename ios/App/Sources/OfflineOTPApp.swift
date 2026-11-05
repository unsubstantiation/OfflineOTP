import SwiftUI

/// The app shell.
///
/// This is a first, honest shell: unlock a vault, see the entries it holds, copy a code. Everything
/// about the *format* — the vault files, the backup, the entry document, the tokens — is in
/// `OfflineOTPKit` and shared with the other clients through the fixed vectors; this layer only
/// draws it. See docs/ios-client-plan.md for what is still missing (imports, backups, Face ID, and
/// the tabs the other clients have).
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
