import UIKit

private final class ActivityTrackingWindow: UIWindow {
    var onUserActivity: (() -> Void)?

    override func sendEvent(_ event: UIEvent) {
        if event.type == .touches || event.type == .presses || event.type == .scroll {
            onUserActivity?()
        }
        super.sendEvent(event)
    }
}

/// UIKit entry point. The Compose framework owns every visible screen; this delegate only installs
/// the root controller and its window. There is no SwiftUI `App` scene in the release binary.
@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let root = AppRootViewController()
        let window = ActivityTrackingWindow(frame: UIScreen.main.bounds)
        window.onUserActivity = { [weak root] in root?.noteWindowActivity() }
        window.rootViewController = root
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
}
