// Sandbox-only stand-ins, see ../../../android/android_content.kt.
package androidx.core.app

import android.app.Notification
import android.content.Context

/**
 * Builder surface the notification helper uses. The channel id is non-null, mirroring the
 * `@NonNull` AndroidX parameter Kotlin enforces; the builder only records what it was given.
 */
class NotificationCompat private constructor() {
    class Builder(private val context: Context, private var channelId: String) {
        private var smallIcon = 0
        private var title: CharSequence? = null
        private var contentText: CharSequence? = null
        private var style: Style? = null

        fun setSmallIcon(icon: Int): Builder = apply { smallIcon = icon }
        fun setContentTitle(title: CharSequence?): Builder = apply { this.title = title }
        fun setContentText(text: CharSequence?): Builder = apply { this.contentText = text }
        fun setStyle(style: Style?): Builder = apply { this.style = style }
        fun setChannelId(channelId: String): Builder = apply { this.channelId = channelId }
        fun build(): Notification = Notification(channelId, smallIcon, title, (style as? BigTextStyle)?.text, contentText)
    }

    abstract class Style

    class BigTextStyle : Style() {
        var text: CharSequence? = null
            private set

        fun bigText(cs: CharSequence?): BigTextStyle = apply { text = cs }
    }
}
