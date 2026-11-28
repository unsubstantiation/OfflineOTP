package com.fairuniversal.offlineotp.desktop.storage

import com.fairuniversal.offlineotp.Utilities.AutomaticBackupCredentialCodec
import com.fairuniversal.offlineotp.Utilities.BoundedInputReader
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import javax.crypto.SecretKey

/** Encrypted local credential record. No writes to/export through portable preferences. */
class DesktopAutomaticBackupStore(directory: File) {
    class Configuration(val folder: String, val password: CharArray, val minutes: Int, val keep: Int,
                        val recipientKeys: List<ByteArray> = emptyList(), val requiresSigning: Boolean = false,
                        val vaultCopy: Boolean = false) : AutoCloseable {
        override fun close() { password.fill('\u0000'); recipientKeys.forEach { it.fill(0) } }
        override fun toString() = "AutomaticBackupConfiguration(redacted)"
    }
    private val file = File(directory, FILENAME)
    val exists: Boolean get() = Files.exists(file.toPath(), NOFOLLOW_LINKS)
    fun save(key: SecretKey, config: Configuration) {
        require(File(config.folder).isAbsolute && !Files.isSymbolicLink(file.toPath()))
        val document = AutomaticBackupCredentialCodec.configurationDocument(config.folder, config.password,
            config.minutes, config.keep, config.recipientKeys, config.requiresSigning, config.vaultCopy)
        try {
            val encrypted = AutomaticBackupCredentialCodec.seal(key, document)
            try { NioAtomicFileStore(file).writeAtomic(encrypted) } finally { encrypted.fill(0) }
        } finally { document.fill(0) }
    }
    fun load(key: SecretKey): Configuration? {
        if (!exists) return null
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
        val encrypted = file.inputStream().use { BoundedInputReader.read(it, AutomaticBackupCredentialCodec.MAX_PLAINTEXT_BYTES + 32) }
        val document = try { AutomaticBackupCredentialCodec.open(key, encrypted) } finally { encrypted.fill(0) }
        try {
            AutomaticBackupCredentialCodec.parseConfiguration(document).use { config ->
                require(File(config.folder).isAbsolute)
                return Configuration(config.folder, config.password.copyOf(), config.minutes, config.keep, config.recipientKeys.map { it.copyOf() }, config.requiresSigning, config.vaultCopy)
            }
        } finally { document.fill(0) }
    }
    /** Removal failure is not reported as successful disablement across restarts. */
    fun forget() { Files.deleteIfExists(file.toPath()) }
    companion object { const val FILENAME = "automatic-backup.credentials" }
}
