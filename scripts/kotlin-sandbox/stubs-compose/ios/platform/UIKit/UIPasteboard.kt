// Compile-only signatures. Never writes or simulates a system pasteboard.
package platform.UIKit

const val UIPasteboardOptionExpirationDate = "expirationDate"
const val UIPasteboardOptionLocalOnly = "localOnly"

class UIPasteboard {
    fun setItems(items: List<Map<Any?, *>>, options: Map<Any?, *>) {
        error("UIKit stub must not execute")
    }
    companion object {
        val generalPasteboard: UIPasteboard get() = error("UIKit stub must not execute")
    }
}
