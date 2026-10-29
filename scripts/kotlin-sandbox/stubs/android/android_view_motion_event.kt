// Sandbox-only stand-ins, see android_content.kt.
package android.view

/**
 * Stand-in for the platform motion event.
 *
 * The flag constants and the `obtain(...)` overload that carries the flag word are the platform's,
 * so the obscured-touch check of the converted base activity can be driven here. No touch dispatch,
 * gesture, coordinate transformation or gesture detection exists.
 */
open class MotionEvent {
    /** The flags of the event. */
    var eventFlags: Int = 0

    /** The action this event carries; the platform exposes `getAction()`. */
    private var actionValue: Int = ACTION_DOWN

    /** The x coordinate of the first pointer; the platform exposes `getX()`. */
    private var xValue: Float = 0f

    /** The y coordinate of the first pointer; the platform exposes `getY()`. */
    private var yValue: Float = 0f

    open fun getFlags(): Int = eventFlags

    fun getAction(): Int = actionValue

    fun getX(): Float = xValue

    fun getY(): Float = yValue

    /** Releases the event; the stand-in owns no pooled resources. */
    fun recycle() {}

    /** Per-pointer identity, as the platform's public obtain(...) overload takes it. */
    class PointerProperties {
        var id: Int = 0
        var toolType: Int = TOOL_TYPE_FINGER
    }

    /** Per-pointer coordinates, as the platform's public obtain(...) overload takes them. */
    class PointerCoords {
        var x: Float = 0f
        var y: Float = 0f
        var pressure: Float = 0f
        var size: Float = 0f
    }

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_CANCEL = 3

        const val TOOL_TYPE_FINGER = 1

        // Platform values (MotionEvent.FLAG_*): bit 9 marks a touch that passed through another
        // window, bit 10 the partially-obscured case added in Android 10.
        const val FLAG_WINDOW_IS_OBSCURED = 0x00000200
        const val FLAG_WINDOW_IS_PARTIALLY_OBSCURED = 0x00000400

        /** Compile-only shorthand used by device touch tests; this stub does not dispatch events. */
        @JvmStatic
        fun obtain(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float,
                   metaState: Int): MotionEvent {
            val event = MotionEvent()
            event.actionValue = action
            event.xValue = x
            event.yValue = y
            return event
        }

        /**
         * Mirrors the public `MotionEvent.obtain(...)` overload that takes the flag word. The
         * stand-in records the action, the first pointer and the flags; the rest is ignored.
         */
        @JvmStatic
        fun obtain(
            downTime: Long,
            eventTime: Long,
            action: Int,
            pointerCount: Int,
            pointerProperties: Array<PointerProperties>,
            pointerCoords: Array<PointerCoords>,
            metaState: Int,
            buttonState: Int,
            xPrecision: Float,
            yPrecision: Float,
            deviceId: Int,
            edgeFlags: Int,
            source: Int,
            flags: Int
        ): MotionEvent {
            require(pointerCount > 0 && pointerProperties.isNotEmpty() && pointerCoords.isNotEmpty())
            val event = MotionEvent()
            event.actionValue = action
            event.eventFlags = flags
            event.xValue = pointerCoords[0].x
            event.yValue = pointerCoords[0].y
            return event
        }
    }
}
