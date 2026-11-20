import UIKit

/// UIKit entry point. The Compose framework owns every visible screen; this delegate only installs
/// the root controller and its window. There is no SwiftUI `App` scene in the release binary.
@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = AppRootViewController()
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
}
