package com.fairuniversal.offlineotp.domain

class IniCredentialSection(val name: String, val fields: Map<String, String>) {
    override fun toString() = "IniCredentialSection(redacted)"
}
/** Bounded sessions.ini syntax, escaped values and duplicate-section ordering shared across hosts.
 * lscp1 credential decryption is deliberately left to native vetted crypto adapters.
 */
class LinScpSessionCodec {
    fun parse(text: String): List<IniCredentialSection>? = try {
        require(text.length <= EntryDocumentCodec.MAX_BYTES && EntryDocumentCodec.utf8Size(text) <= EntryDocumentCodec.MAX_BYTES)
        val sections = linkedMapOf<String, IniCredentialSection>()
        var current: String? = null; val fields = linkedMapOf<String, String>()
        fun flush() {
            current?.trim()?.takeIf { it.isNotEmpty() }?.let {
                require(sections.containsKey(it) || sections.size < EntryDocumentCodec.MAX_ENTRIES)
                sections[it] = IniCredentialSection(it, fields.toMap())
            }
            fields.clear()
        }
        for (raw in text.lineSequence()) {
            val line = raw.removeSuffix("\r"); val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith(';')) continue
            if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
                flush(); current = unescape(trimmed.substring(1, trimmed.length - 1)); continue
            }
            val equal = line.indexOf('='); require(equal > 0 && current != null)
            val name = line.substring(0, equal).trim(); require(name.isNotEmpty())
            // More fields cannot form a bounded portable credential entry.
            require(fields.containsKey(name) || fields.size < 2000)
            fields[name] = unescape(line.substring(equal + 1))
        }
        flush(); require(sections.isNotEmpty()); sections.values.toList()
    } catch (_: Exception) { null }
    private fun unescape(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (c == '\\' && i < text.length) when (val next = text[i++]) {
                'n' -> append('\n'); 'r' -> append('\r'); 't' -> append('\t'); '\\' -> append('\\')
                else -> { append('\\'); append(next) }
            } else append(c)
        }
    }
}
