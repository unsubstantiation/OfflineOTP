package com.fairuniversal.offlineotp.domain

/** Lossless browser dialect admission; foreign credential columns are not flattened into CSV. */
class BrowserCsvPolicy {
    fun dialect(columns: List<String>): String? {
        val names = columns.map { it.trim().lowercase() }
        if (names.any { it.isEmpty() } || names.toSet().size != names.size || !names.containsAll(listOf("url", "username", "password"))) return null
        return when {
            "title" in names -> "SAFARI"
            "name" in names -> "CHROME"
            names.any { it in listOf("httprealm", "formactionorigin", "guid") } -> "FIREFOX"
            else -> null
        }
    }
    fun commonEdit(columns: List<String>): Boolean {
        val names = columns.map { it.trim().lowercase() }
        return listOf("url", "username", "password").all { key -> names.count { it == key } == 1 } &&
            names.none { it in listOf("origin", "login_uri", "hostname", "host", "user", "login_username", "pass", "login_password") }
    }
    fun header(dialect: String): List<String> = when (dialect) {
        "CHROME" -> listOf("name", "url", "username", "password", "note")
        "FIREFOX" -> listOf("url", "username", "password", "httpRealm", "formActionOrigin", "guid", "timeCreated", "timeLastUsed", "timePasswordChanged")
        "SAFARI" -> listOf("Title", "URL", "Username", "Password", "Notes", "OTPAuth")
        else -> emptyList()
    }
}
