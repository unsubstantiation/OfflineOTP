package org.shadowice.flocke.andotp.Utilities

import android.content.Context
import android.preference.PreferenceManager
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * A non-secret, durable retry marker. The optional keyed digest records the vault immediately
 * before the first pending change, so an interrupted failed save does not produce a new backup.
 * Marker changes are serialized with vault mutations; no entries or passwords are persisted here.
 */
internal object PendingAutomaticBackup {
    private const val TOKEN = "internal_pending_automatic_backup"
    private const val BEFORE = "internal_pending_backup_before_hmac"
    private val domain = "OfflineOTP automatic backup pending v1".toByteArray(StandardCharsets.UTF_8)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun claim(context: Context, token: String): Boolean = synchronized(VaultSession.MutationLock) {
        read(context)?.token == token && inFlight.add(token)
    }

    // Called during task cancellation under the task's callback lock: never take MutationLock.
    fun release(token: String) { inFlight.remove(token) }

    data class Marker(val token: String, val before: String?)
    data class Stage(val marker: Marker, val previous: Marker?, val durable: Boolean)

    fun read(context: Context): Marker? = synchronized(VaultSession.MutationLock) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val token = prefs.getString(TOKEN, null) ?: return@synchronized null
        Marker(token, prefs.getString(BEFORE, null))
    }

    fun stage(context: Context, before: String?): Stage = synchronized(VaultSession.MutationLock) {
        val previous = read(context)
        val marker = Marker(UUID.randomUUID().toString(), previous?.before ?: if (previous == null) before else null)
        val edit = PreferenceManager.getDefaultSharedPreferences(context).edit().putString(TOKEN, marker.token)
        if (marker.before == null) edit.remove(BEFORE) else edit.putString(BEFORE, marker.before)
        Stage(marker, previous, edit.commit())
    }

    fun rollback(context: Context, stage: Stage) = synchronized(VaultSession.MutationLock) {
        if (read(context)?.token != stage.marker.token) return@synchronized
        val edit = PreferenceManager.getDefaultSharedPreferences(context).edit()
        val previous = stage.previous
        if (previous == null) edit.remove(TOKEN).remove(BEFORE)
        else {
            edit.putString(TOKEN, previous.token)
            if (previous.before == null) edit.remove(BEFORE) else edit.putString(BEFORE, previous.before)
        }
        edit.commit()
    }

    /** A pre-change digest cannot suppress retry while an older snapshot can still finish. */
    fun canDropReverted(context: Context, marker: Marker, digest: String?): Boolean = synchronized(VaultSession.MutationLock) {
        val current = read(context)
        current == marker && marker.before != null && marker.before == digest &&
            inFlight.none { it != marker.token }
    }

    fun complete(context: Context, token: String) = synchronized(VaultSession.MutationLock) {
        val current = read(context) ?: return@synchronized
        val edit = PreferenceManager.getDefaultSharedPreferences(context).edit()
        if (current.token == token) {
            edit.remove(TOKEN).remove(BEFORE).commit()
        } else if (current.before != null) {
            // An older snapshot succeeded while a newer change was pending. The prior digest no
            // longer proves that a reversion needs no backup: force a retry of the newer state.
            edit.remove(BEFORE).commit()
        }
    }

    /** A reset starts a different vault; an old pending change must not follow it. */
    fun clear(context: Context) = synchronized(VaultSession.MutationLock) {
        inFlight.clear()
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(TOKEN).remove(BEFORE).commit()
    }

    /** Returns null for non-exportable legacy Keystore keys; retry then errs toward preserving data. */
    fun fingerprint(serialized: String, key: SecretKey): String? {
        // Some providers may return an aliased encoding. Wipe only our own copy, never the
        // provider's array, which might also be the active vault key.
        val encoded = try { key.encoded } catch (_: RuntimeException) { null } ?: return null
        val raw = encoded.clone()
        var derived: ByteArray? = null
        var plaintext: ByteArray? = null
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(raw, "HmacSHA256"))
            derived = mac.doFinal(domain)
            mac.init(SecretKeySpec(derived, "HmacSHA256"))
            plaintext = serialized.toByteArray(StandardCharsets.UTF_8)
            return mac.doFinal(plaintext).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } catch (_: Exception) {
            return null
        } finally {
            raw.fill(0)
            derived?.fill(0)
            plaintext?.fill(0)
        }
    }
}
