// Sandbox-only stand-ins, see android_content.kt.
package android.text

/** Minimal read-only span surface used by the card-formatting checks. */
interface Spanned : CharSequence {
    fun <T> getSpans(start: Int, end: Int, type: Class<T>): Array<T>
    fun getSpanStart(span: Any): Int
    fun getSpanEnd(span: Any): Int
}

/** The mutable span interface; the card label uses exclusive-exclusive span flags. */
interface Spannable : Spanned {
    fun setSpan(span: Any, start: Int, end: Int, flags: Int)

    companion object {
        const val SPAN_EXCLUSIVE_EXCLUSIVE = 0x21
        const val SPAN_INCLUSIVE_EXCLUSIVE = 0x11
    }
}

/**
 * The spanned text builder of the card label. The stand-in records the appended text and the
 * spans; it does not lay out or measure anything.
 */
class SpannableStringBuilder : Spannable, CharSequence {
    /** The text that was appended. */
    val text: StringBuilder = StringBuilder()

    /** The spans that were set, in order. */
    val spans: MutableList<Pair<Any, IntArray>> = ArrayList()

    fun append(text: CharSequence?): SpannableStringBuilder {
        this.text.append(text)
        return this
    }

    override fun setSpan(span: Any, start: Int, end: Int, flags: Int) {
        spans.add(span to intArrayOf(start, end, flags))
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> getSpans(start: Int, end: Int, type: Class<T>): Array<T> {
        val matches = spans.filter { (span, range) ->
            type.isInstance(span) && range[0] < end && range[1] > start
        }
        val result = java.lang.reflect.Array.newInstance(type, matches.size) as Array<T>
        matches.forEachIndexed { index, (span, _) -> result[index] = type.cast(span) }
        return result
    }

    override fun getSpanStart(span: Any): Int = spans.firstOrNull { it.first === span }?.second?.get(0) ?: -1
    override fun getSpanEnd(span: Any): Int = spans.firstOrNull { it.first === span }?.second?.get(1) ?: -1

    override val length: Int get() = text.length

    override fun get(index: Int): Char = text[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        text.subSequence(startIndex, endIndex)

    override fun toString(): String = text.toString()
}
