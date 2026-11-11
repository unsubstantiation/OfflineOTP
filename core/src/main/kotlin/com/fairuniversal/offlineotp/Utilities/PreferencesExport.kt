package com.fairuniversal.offlineotp.Utilities

import org.json.JSONArray
import org.json.JSONObject

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Portable export/import of the app's preferences (upstream #648).
 *
 * The thing this file must never do is claim a setting is portable when it is not. A preference is
 * only exportable when (a) some client can read it, and (b) its value means the same thing on
 * another install. Two kinds of stored value fail that test, and both are treated differently on
 * purpose:
 *
 * - **Device-bound secrets are never exported.** Android's `pref_backup_password_enc` is the backup
 *   password wrapped by a key in the device's Keystore: the ciphertext verifies nothing anywhere else
 *   and cannot be unwrapped off the device, so writing those bytes into the file would produce a
 *   "portable" backup that silently does not work. The hardware-unlock envelope is bound to the
 *   security key's PRF in the same way. Both are listed in [neverExported] so the reason is visible
 *   rather than an omission.
 * - **Secrets that *are* portable are exported in plaintext, and say so.** The backup password is
 *   read back through the platform's own decryption ([SettingsPort.readBackupPassword]) and written
 *   as a value; OpenPGP recipient requests and the OpenPGP provider choice are free text and travel
 *   as they are. [Export.includesSecrets] reports whether the document actually carries any, so a
 *   client can warn before writing and the reader can refuse a file that claims otherwise.
 *
 * Everything else travels as an explicit typed [Value], never as a bare string: `"true"` and `"1"`
 * are both plausible stored forms of a checkbox, and an importer that guessed would flip settings
 * the user never touched. A key this build does not know is reported in [Import.unknownKeys] and
 * skipped — the file may come from a newer release — instead of failing the whole import.
 *
 * Writers are driven by the exported [catalog] (key + kind), not by a hard-coded reader per key, so
 * adding a setting means adding one `KNOWN` entry and nothing else.
 */
object PreferencesExport {

    /** The document format version. A reader rejects anything it does not know. */
    const val FORMAT = "offlineotp.preferences"
    const val VERSION = 1

    /**
     * Which client wrote the file.
     *
     * The two clients do not share preference key names — Android's preferences are `pref_*`
     * resources, the desktop's vault properties are `auth_method`, `ui_inactivity_lock_seconds` and
     * friends — and several settings have no equivalent on the other side at all. A document is
     * therefore addressed to *one* platform, and an import into another platform is refused with a
     * clear reason rather than applied as the handful of keys that happened to collide. Cross-client
     * transfer would need a real settings model with per-key semantics; pretending two key sets line
     * up would silently half-restore a device.
     */
    const val PLATFORM_ANDROID = "android"
    const val PLATFORM_DESKTOP = "desktop"
    const val PLATFORM_IOS = "ios"

    /** Bounds: a settings file is much smaller than a backup, and a hostile one must not be read. */
    const val MAX_BYTES = 512 * 1024
    const val MAX_KEYS = 512
    const val MAX_STRING_CHARS = 64 * 1024
    const val MAX_SET_VALUES = 256

    /** Where a value lives, which is also what makes it portable or not. */
    enum class Kind {
        /** Readable and writable by every client. */
        BOOLEAN,
        INTEGER,
        STRING,
        STRING_SET,
        /** A secret the user re-enters on the other device; exported only on request. */
        SECRET,
        /** Bound to this device's hardware/Keystore; never exported, never imported. */
        DEVICE_BOUND
    }

    /** One exportable setting: the stable key and its type. */
    class Setting(val key: String, val kind: Kind) {
        override fun toString(): String = "Setting($key, $kind)"
    }

    /**
     * Keys that exist on this platform but cannot leave it. Kept as data so the export manifest can
     * state what was left behind and why, and so a guard can prove nothing slips back in.
     */
    val neverExported: List<Pair<String, String>> = listOf(
        "pref_backup_password_enc" to "the backup password is wrapped by this device's Keystore",
        "pref_backup_password" to "legacy plaintext backup password, migrated on read",
        "pref_auth_credentials" to "the vault credential verifier belongs to this vault and its KDF",
        "pref_auth_salt" to "the vault salt belongs to this vault and its KDF",
        "pref_auth_iterations" to "KDF parameters belong to this vault, not to the app",
        "pref_auth_memory_kib" to "KDF parameters belong to this vault, not to the app",
        "pref_auth_parallelism" to "KDF parameters belong to this vault, not to the app",
        "pref_hardware_unlock" to "the hardware-unlock envelope is bound to this security key",
        "pref_backup_location" to "a SAF tree URI is only meaningful on the device that granted it",
        "pref_openpgp_recipient_files" to "SAF recipient key-file grants belong to the granting device",
        "pref_openpgp_secret_file" to "a SAF secret key-file grant belongs to the granting device",
        "pref_dialog_hide_auto_time" to "a one-session dialog acknowledgement",

        // The desktop's vault material shares the same properties file as its settings. These keys
        // describe *that vault* (its KDF and verifier), so they belong to the vault, not to the app:
        // a new install has its own salt and could not use these values, and naming them in a
        // shareable file would only suggest that vault material is portable.
        "auth_method" to "the vault's unlock method belongs to the vault, not to the app's preferences",
        "auth_encryption" to "the vault's encryption mode belongs to the vault",
        "auth_kdf" to "the vault's KDF belongs to the vault",
        "auth_credentials" to "the vault's verifier belongs to the vault",
        "auth_salt" to "the vault's salt belongs to the vault",
        "auth_iterations" to "the vault's KDF parameters belong to the vault",
        "auth_memory_kib" to "the vault's KDF parameters belong to the vault",
        "auth_parallelism" to "the vault's KDF parameters belong to the vault"
    )

    /**
     * The settings this build knows how to move. Order is the order written, so the file is
     * deterministic. A key missing from here is never exported, even if it is stored.
     */
    val catalog: List<Setting> = listOf(
        // Security / locking
        Setting("pref_auth", Kind.STRING),
        Setting("pref_encryption", Kind.STRING),
        Setting("pref_auth_inactivity", Kind.BOOLEAN),
        Setting("pref_auth_inactivity_timeout", Kind.INTEGER),
        Setting("pref_relock_screen_off", Kind.BOOLEAN),
        Setting("pref_relock_background", Kind.BOOLEAN),
        Setting("pref_hide_in_app_switcher", Kind.BOOLEAN),
        Setting("pref_block_accessibility", Kind.BOOLEAN),
        Setting("pref_block_autofill", Kind.BOOLEAN),
        Setting("pref_auto_unlock_after_autofill", Kind.BOOLEAN),
        Setting("pref_enable_screenshot", Kind.BOOLEAN),
        Setting("pref_enable_android_backup_service", Kind.BOOLEAN),
        Setting("pref_biometric_enabled", Kind.BOOLEAN),
        Setting("pref_panic", Kind.STRING),

        // Appearance / behaviour
        Setting("pref_lang", Kind.STRING),
        Setting("pref_theme", Kind.STRING),
        Setting("pref_theme_mode", Kind.STRING),
        Setting("pref_theme_black_auto", Kind.BOOLEAN),
        Setting("pref_show_prev_token", Kind.BOOLEAN),
        Setting("pref_label_size_sp", Kind.STRING),
        Setting("pref_card_layout", Kind.STRING),
        Setting("pref_label_display", Kind.STRING),
        Setting("pref_tap_single", Kind.STRING),
        Setting("pref_tap_double", Kind.STRING),
        Setting("pref_tap_to_reveal_timeout", Kind.INTEGER),
        Setting("pref_minimize_on_copy", Kind.BOOLEAN),
        Setting("pref_thumbnail_size", Kind.STRING),
        Setting("pref_split_group_size", Kind.STRING),
        Setting("pref_tag_functionality", Kind.STRING),
        Setting("pref_search_includes", Kind.STRING_SET),
        Setting("pref_label_highlight_token", Kind.BOOLEAN),
        Setting("pref_hide_global_timeout", Kind.BOOLEAN),
        Setting("pref_show_individual_timeouts", Kind.BOOLEAN),
        Setting("pref_focus_search_on_start", Kind.BOOLEAN),
        Setting("pref_hide_issuer", Kind.BOOLEAN),
        Setting("pref_sort_mode", Kind.STRING),
        Setting("pref_special_features", Kind.BOOLEAN),
        Setting("pref_all_tags_toggle", Kind.BOOLEAN),
        Setting("pref_no_tags_toggle", Kind.BOOLEAN),
        Setting("pref_tags_toggles", Kind.STRING_SET),

        // Backups
        Setting("pref_backup_ask", Kind.BOOLEAN),
        Setting("pref_backup_append_date_time", Kind.BOOLEAN),
        Setting("pref_backup_broadcasts", Kind.BOOLEAN),
        Setting("pref_backup_default_type", Kind.STRING),
        Setting("pref_backup_auto_password_enc", Kind.STRING),
        Setting("pref_backup_auto_keep", Kind.STRING),
        Setting("pref_auto_backup_use_login", Kind.BOOLEAN),
        Setting("pref_openpgp_mode", Kind.STRING),
        Setting("pref_openpgp_provider", Kind.STRING),
        Setting("pref_openpgp_key_encrypt", Kind.BOOLEAN),
        Setting("pref_openpgp_key_sign", Kind.BOOLEAN),
        Setting("pref_openpgp_verify", Kind.BOOLEAN),

        // The one secret a second install can genuinely use. Everything else secret is either
        // device-bound (above) or belongs to the vault rather than to the app's preferences.
        Setting(SECRET_BACKUP_PASSWORD, Kind.SECRET)
    )

    /**
     * The backup password. Android keeps it wrapped by a Keystore key, so it is read back through
     * the platform ([SettingsPort.readBackupPassword]) and written back the same way; the key-file
     * OpenPGP grants are SAF URIs and cannot move (see [neverExported]).
     */
    const val SECRET_BACKUP_PASSWORD = "secret_backup_password"

    /**
     * The desktop's portable settings, and the whole of them.
     *
     * The desktop stores list order, inactivity lock delay and Compose card layout beside the vault. Everything else in its properties file is `auth_*` and describes *this vault's*
     * KDF and verifier, so it belongs to the vault rather than to the app and cannot be moved to
     * another install (a new vault has its own salt). A desktop export is therefore a small file,
     * and [Export.keysWritten] says so instead of implying more.
     */
    val desktopCatalog: List<Setting> = listOf(
        Setting(UI_SORT_MODE, Kind.STRING),
        Setting(UI_INACTIVITY_LOCK_SECONDS, Kind.INTEGER),
        Setting(UI_CARD_LAYOUT, Kind.STRING)
    )

    /** Desktop key for the Compose card layout; ignored by the shipping Swing renderer. */
    const val UI_CARD_LAYOUT = "ui_card_layout"
    /** Desktop key for the saved list order. Mirrors `VaultController.SORT_MODE_KEY`. */
    const val UI_SORT_MODE = "ui_sort_mode"
    /** Desktop key for the inactivity lock delay. Mirrors `VaultSettingsKeys.UI_INACTIVITY_LOCK_SECONDS`. */
    const val UI_INACTIVITY_LOCK_SECONDS = "ui_inactivity_lock_seconds"

    /** The catalog for [platform]; an unknown platform has none, so nothing can be exported for it. */
    @JvmStatic
    fun catalogFor(platform: String): List<Setting> = when (platform) {
        PLATFORM_ANDROID -> catalog
        PLATFORM_DESKTOP -> desktopCatalog
        else -> emptyList()
    }

    private val byKey: Map<String, Setting> = catalog.associateBy { it.key }
    private val desktopByKey: Map<String, Setting> = desktopCatalog.associateBy { it.key }

    /** The catalog entry for [key] on [platform], or null when it has no portable meaning there. */
    @JvmStatic
    @JvmOverloads
    fun setting(key: String, platform: String = PLATFORM_ANDROID): Setting? =
        if (platform == PLATFORM_DESKTOP) desktopByKey[key] else byKey[key]

    /** True when [key] is deliberately left out of exports; [whyNotExported] says why. */
    @JvmStatic
    fun isDeviceBound(key: String): Boolean = neverExported.any { it.first == key }

    /** The reason [key] never travels, or null when it is not a known device-bound key. */
    @JvmStatic
    fun whyNotExported(key: String): String? = neverExported.firstOrNull { it.first == key }?.second

    /**
     * The platform side of the exchange: a flat key/value store plus the two things only the platform
     * knows how to do — read a stored secret back in the clear, and write one.
     */
    interface SettingsPort {
        /** Every stored key, so keys the catalog does not know can be reported as skipped. */
        fun storedKeys(): Set<String>

        /** Null when [key] is absent, so an absent setting is never written as a default. */
        fun read(key: String): Value?

        /**
         * The backup password in the clear, or null when the user has none or the platform can no
         * longer unwrap it (a reinstall, a wiped Keystore). Callers must not treat null as "empty
         * password": it means "nothing to export".
         */
        fun readBackupPassword(): String?

        /** Writes [value]; the platform re-encodes secrets for its own store. */
        fun write(key: String, value: Value)
    }

    /** One typed preference value. Equality is by type *and* content, so an int never equals a string. */
    sealed class Value {
        class Bool(val value: Boolean) : Value() {
            override fun equals(other: Any?): Boolean = other is Bool && other.value == value
            override fun hashCode(): Int = value.hashCode()
            override fun toString(): String = "Bool($value)"
        }

        class IntValue(val value: Int) : Value() {
            override fun equals(other: Any?): Boolean = other is IntValue && other.value == value
            override fun hashCode(): Int = value
            override fun toString(): String = "IntValue($value)"
        }

        class Text(val value: String) : Value() {
            override fun equals(other: Any?): Boolean = other is Text && other.value == value
            override fun hashCode(): Int = value.hashCode()
            // A secret's toString must not print it: this type is used for the backup password too.
            override fun toString(): String = "Text(${value.length} chars)"
        }

        class TextSet(val values: List<String>) : Value() {
            override fun equals(other: Any?): Boolean = other is TextSet && other.values == values
            override fun hashCode(): Int = values.hashCode()
            override fun toString(): String = "TextSet($values)"
        }
    }

    /**
     * What one export produced, and whether it carries secrets the user must protect.
     *
     * [skipped] lists stored keys this build has no portable meaning for — a setting from a newer
     * release, or a platform-only key. Device-bound keys are deliberately absent from it: see
     * [neverExported] for those.
     */
    class Export(val bytes: ByteArray, val keysWritten: Int, val skipped: List<String>) {
        /** True when the document holds at least one [Kind.SECRET] value. */
        var includesSecrets: Boolean = false
            internal set

        fun zero() = bytes.fill(0)

        override fun toString(): String =
            "PreferencesExport.Export($keysWritten keys, secrets=$includesSecrets, skipped=${skipped.size})"
    }

    /** What one import changed, and what it deliberately left alone. */
    class Import(
        val applied: Int,
        val unknownKeys: List<String>,
        val refusedKeys: List<String>,
        val includesSecrets: Boolean
    ) {
        override fun toString(): String =
            "PreferencesExport.Import(applied=$applied, unknown=${unknownKeys.size}, " +
                "refused=${refusedKeys.size}, secrets=$includesSecrets)"
    }

    class InvalidPreferencesException(message: String) : IllegalArgumentException(message)

    /**
     * The file is a valid preferences document, but written by another client. Separate from
     * [InvalidPreferencesException] so a caller can say "this is not the right platform" instead of
     * "this file is broken".
     */
    class WrongPlatformException(val source: String, val target: String) :
        IllegalArgumentException("Preferences for $source cannot be applied to $target")

    /**
     * Serialises the readable settings of [port].
     *
     * @param includeSecrets when false the three [Kind.SECRET] values are omitted, which is what a
     *   user who wants to sync the file should choose. When true the backup password is read in the
     *   clear, so the caller must warn first — [Export.includesSecrets] exists for that.
     */
    @JvmStatic
    @JvmOverloads
    fun export(port: SettingsPort, includeSecrets: Boolean, platform: String = PLATFORM_ANDROID): Export {
        val settings = JSONObject()
        var written = 0
        var secrets = false
        val platformCatalog = catalogFor(platform)
        for (entry in platformCatalog) {
            val value = when (entry.kind) {
                Kind.DEVICE_BOUND -> null
                Kind.SECRET -> {
                    if (!includeSecrets) continue
                    port.readBackupPassword()?.takeIf { it.isNotEmpty() }?.let { Value.Text(it) }
                }
                else -> port.read(entry.key)
            } ?: continue
            write(settings, entry, value, ::InvalidPreferencesException)
            written++
            if (entry.kind == Kind.SECRET) secrets = true
        }
        // Keys the store holds that this build cannot carry are reported, never silently dropped —
        // but a device-bound key is *not* enumerated here. Naming "pref_hardware_unlock" in a file the
        // user may share would disclose which security feature this device has configured, and
        // [neverExported] already documents those keys statically for anyone who wants to know.
        val known = platformCatalog.map { it.key }.toSet()
        val deviceBound = neverExported.map { it.first }.toSet()
        val skipped = (port.storedKeys() - known - deviceBound).sorted()
        val document = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("platform", platform)
            .put("includesSecrets", secrets)
            .put("settings", settings)
        if (skipped.isNotEmpty()) document.put("skipped", JSONArray(skipped))
        val bytes = exportBytes(document.toString())
        return Export(bytes, written, skipped).also { it.includesSecrets = secrets }
    }

    /**
     * Applies the settings in [bytes] to [port].
     *
     * All-or-nothing on parse: a document that is truncated, oversized, of another format or carrying
     * a value of the wrong type for its key is rejected before anything is written, so a half-applied
     * import cannot leave the app in a state nobody chose. Unknown keys are skipped and reported.
     * Device-bound keys are refused even when the file contains them.
     */
    @JvmStatic
    @JvmOverloads
    fun import(bytes: ByteArray, port: SettingsPort, platform: String = PLATFORM_ANDROID): Import {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw InvalidPreferencesException("Invalid size")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw InvalidPreferencesException("Invalid encoding")
        }
        val document = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw InvalidPreferencesException("Invalid document")
        }
        if (document.optString("format") != FORMAT) throw InvalidPreferencesException("Not a preferences file")
        if (document.optInt("version", -1) != VERSION) throw InvalidPreferencesException("Unsupported version")
        // Addressed to one client: see [PLATFORM_ANDROID]. A mismatch is refused before any write.
        val sourcePlatform = document.optString("platform") ?: ""
        if (sourcePlatform != platform) throw WrongPlatformException(sourcePlatform, platform)
        val settings = try {
            document.getJSONObject("settings")
        } catch (_: Exception) {
            throw InvalidPreferencesException("Missing settings")
        }
        if (settings.length() > MAX_KEYS) throw InvalidPreferencesException("Too many settings")

        // Parse everything first; only a document that fully validates reaches the store.
        val accepted = ArrayList<Pair<Setting, Value>>()
        val unknown = ArrayList<String>()
        val refused = ArrayList<String>()
        var secrets = document.optBoolean("includesSecrets", false)
        val keys = settings.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            // A device-bound key comes first: a file that carries one was either hand-edited or
            // written by a build that wrongly exported it, and either way it is refused rather than
            // filed away as "a key from a newer release".
            if (isDeviceBound(key)) { refused.add(key); continue }
            val entry = setting(key, platform)
            if (entry == null) { unknown.add(key); continue }
            if (entry.kind == Kind.DEVICE_BOUND) { refused.add(key); continue }
            accepted.add(entry to read(settings, entry))
        }
        // The document must not understate what it carries: a secret present is a secret.
        if (accepted.any { it.first.kind == Kind.SECRET }) secrets = true
        for ((entry, value) in accepted) port.write(entry.key, value)
        return Import(accepted.size, unknown.sorted(), refused.sorted(), secrets)
    }

    private fun write(target: JSONObject, entry: Setting, value: Value, fail: (String) -> Exception) {
        // The stored value must match the catalog's type; a mismatch is a bug in the platform port,
        // not something to coerce silently.
        val json = when (entry.kind) {
            Kind.BOOLEAN -> (value as? Value.Bool ?: throw fail("${entry.key} is not a boolean")).value
            Kind.INTEGER -> (value as? Value.IntValue ?: throw fail("${entry.key} is not an integer")).value
            Kind.STRING, Kind.SECRET -> {
                val text = (value as? Value.Text ?: throw fail("${entry.key} is not text")).value
                require(text.length <= MAX_STRING_CHARS) { "${entry.key} is too long" }
                text
            }
            Kind.STRING_SET -> {
                val values = (value as? Value.TextSet ?: throw fail("${entry.key} is not a set")).values
                require(values.size <= MAX_SET_VALUES) { "${entry.key} has too many values" }
                JSONArray(values)
            }
            Kind.DEVICE_BOUND -> throw fail("${entry.key} never leaves this device")
        }
        target.put(entry.key, json)
    }

    private fun read(source: JSONObject, entry: Setting): Value {
        val value = source.opt(entry.key) ?: throw InvalidPreferencesException("Missing ${entry.key}")
        return when (entry.kind) {
            Kind.BOOLEAN -> Value.Bool(value as? Boolean
                ?: throw InvalidPreferencesException("${entry.key} is not a boolean"))
            Kind.INTEGER -> Value.IntValue((value as? Number)?.toInt()
                ?: throw InvalidPreferencesException("${entry.key} is not an integer"))
            Kind.STRING, Kind.SECRET -> {
                val text = value as? String
                    ?: throw InvalidPreferencesException("${entry.key} is not text")
                if (text.length > MAX_STRING_CHARS) throw InvalidPreferencesException("${entry.key} is too long")
                Value.Text(text)
            }
            Kind.STRING_SET -> {
                val array = value as? JSONArray
                    ?: throw InvalidPreferencesException("${entry.key} is not a set")
                if (array.length() > MAX_SET_VALUES) throw InvalidPreferencesException("${entry.key} has too many values")
                Value.TextSet((0 until array.length()).map { index ->
                    array.opt(index) as? String
                        ?: throw InvalidPreferencesException("${entry.key} has a non-text value")
                })
            }
            Kind.DEVICE_BOUND -> throw InvalidPreferencesException("${entry.key} never leaves this device")
        }
    }

    /** A bounded UTF-8 write; the document is rejected rather than truncated past [MAX_BYTES]. */
    private fun exportBytes(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) {
            bytes.fill(0)
            throw InvalidPreferencesException("Preferences export is too large")
        }
        return bytes
    }
}
