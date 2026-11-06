package com.fairuniversal.offlineotp.Utilities

/** App-wide enums, activity request codes, intent extras, and cryptographic parameters. */
object Constants {
    // Enums

    /** How the user authenticates: not at all, password, or device credentials. */
    enum class AuthMethod {
        NONE, PASSWORD, DEVICE;

        companion object {
            /**
             * Reads a stored method token, never failing on one this build does not have.
             *
             * The PIN login older releases offered is gone, but a vault set up with it is still
             * encrypted with that PIN: read as `PASSWORD`, the unlock screen asks for the
             * credential and the stored salt/verifier decide, so the vault stays reachable and can
             * be given a real password from Settings instead of being stranded. Returning `NONE`
             * would skip the prompt and leave nothing able to derive the key.
             */
            @JvmStatic
            fun fromStored(token: String): AuthMethod =
                values().firstOrNull { it.name.equals(token.trim(), ignoreCase = true) } ?: PASSWORD
        }
    }

    /** How the database key is protected: wrapped by the KeyStore, or derived from the credentials. */
    enum class EncryptionType {
        KEYSTORE, PASSWORD
    }

    /** Order of the entry list; UNSORTED keeps the manual drag & drop order. */
    enum class SortMode {
        UNSORTED, ISSUER, LABEL, LAST_USED, MOST_USED
    }

    /** The kind of backup file; UNAVAILABLE disables the backup/restore controls. */
    enum class BackupType {
        ENCRYPTED, OPEN_PGP, UNAVAILABLE
    }

    /** How selected tags are combined: any tag (OR), all tags (AND), or only one tag at a time. */
    enum class TagFunctionality {
        OR, AND, SINGLE
    }

    /** The notification channels used by the backup broadcasts. */
    enum class NotificationChannel {
        BACKUP_FAILED, BACKUP_SUCCESS
    }

    /** The entry fields the search looks at (any combination of them). */
    enum class SearchIncludes {
        LABEL, ISSUER, TAGS
    }

    /** The selectable entry card layouts. */
    enum class CardLayouts {
        COMPACT, DEFAULT, FULL
    }

    /** When an automatic encrypted backup is written. */
    enum class AutoBackup {
        OFF, NEW_ENTRIES, ALL_EDITS
    }

    /** The action of a single/double tap on a card. */
    enum class TapMode {
        NOTHING, REVEAL, COPY, COPY_BACKGROUND, SEND_KEYSTROKES
    }

    /** How an overlong label is displayed on the card. */
    enum class LabelDisplay {
        TRUNCATE, SCROLL, MULTILINE
    }

    // Intents (1xx = MainActivity, 2xx = BackupActivity, 3xx = SettingsActivity)
    const val INTENT_MAIN_AUTHENTICATE = 100
    const val INTENT_MAIN_SETTINGS = 101
    const val INTENT_MAIN_BACKUP = 102
    const val INTENT_MAIN_INTRO = 103
    const val INTENT_MAIN_QR_OPEN_IMAGE = 104
    const val INTENT_MAIN_PASSWORD_IMPORT = 105
    const val INTENT_MAIN_PASSWORD_EXPORT = 106
    const val INTENT_MAIN_EMAIL_IMPORT = 107
    const val INTENT_MAIN_EMAIL_EXPORT = 108
    const val INTENT_MAIN_THUNDERBIRD_IMPORT = 109
    const val INTENT_MAIN_THUNDERBIRD_EXPORT = 110
    const val INTENT_MAIN_LINSCP_IMPORT = 111
    const val INTENT_MAIN_LINSCP_EXPORT = 112

    const val INTENT_BACKUP_OPEN_DOCUMENT_CRYPT = 202
    const val INTENT_BACKUP_SAVE_DOCUMENT_CRYPT = 203
    const val INTENT_BACKUP_OPEN_DOCUMENT_PGP = 204
    const val INTENT_BACKUP_SAVE_DOCUMENT_PGP = 205
    const val INTENT_BACKUP_ENCRYPT_PGP = 206
    const val INTENT_BACKUP_DECRYPT_PGP = 207
    const val INTENT_SETTINGS_AUTHENTICATE = 300
    const val INTENT_AUTH_HARDWARE = 400
    const val INTENT_SETTINGS_BACKUP_LOCATION = 301

    // Intent extras
    const val EXTRA_AUTH_PASSWORD_KEY = "password_key"
    const val EXTRA_AUTH_NEW_ENCRYPTION = "new_encryption"
    const val EXTRA_AUTH_MESSAGE = "message"
    const val EXTRA_AUTH_ALLOW_HARDWARE = "allow_hardware_unlock"

    const val EXTRA_BACKUP_ENCRYPTION_KEY = "encryption_key"
    const val EXTRA_BACKUP_PENDING_ACTION = "backup_pending_action"
    const val EXTRA_BACKUP_PENDING_REPLACE = "backup_pending_replace"

    const val EXTRA_INTRO_FINISHED = "setup_finished"
    const val EXTRA_INTRO_ENCRYPTION_KEY = "encryption_key"

    const val EXTRA_SETTINGS_ENCRYPTION_CHANGED = "encryption_changed"
    const val EXTRA_SETTINGS_ENCRYPTION_KEY = "encryption_key"

    // Encryption algorithms and definitions
    internal const val ALGORITHM_SYMMETRIC = "AES/GCM/NoPadding"
    const val ALGORITHM_ASYMMETRIC = "RSA/ECB/PKCS1Padding"

    const val ENCRYPTION_KEY_LENGTH = 32 // 256-bit AES-256-GCM vault key (password Argon2id or KeyStore-wrapped)
    const val ENCRYPTION_IV_LENGTH = 12 // 96-bit GCM nonce, also the salt length of legacy PBKDF2 backups

    const val INT_LENGTH = 4 // Byte length of 32-bit big-endian integer fields in backup headers

    // KDF identifiers & Argon2id settings (RFC 9106 v1.3: 64 MiB memory, 3 passes, 4 lanes)
    const val KDF_ARGON2ID = "argon2id"
    const val KDF_PBKDF2 = "pbkdf2"
    const val ARGON2_MEMORY_KIB = 65536
    const val ARGON2_ITERATIONS = 3
    const val ARGON2_PARALLELISM = 4
    const val ARGON2_SALT_LENGTH = 16
    const val ARGON2_AUTH_HASH_LENGTH = 64 // 512 bits: auth splits into 32-byte AES-256 key + 32-byte verifier
    const val ARGON2_HASH_LENGTH = 32 // 256 bits: backups use all 32 bytes as an AES-256 key

    // Legacy PBKDF2 settings (retained for reading/restoring existing vaults and backups)
    const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA1"
    const val PBKDF2_LENGTH = 256 // PBKDF2 output in bits: auth splits it into two 128-bit halves, backup keys use all 32 bytes
    const val PBKDF2_SALT_LENGTH = 16

    // Legacy PBKDF2 settings for authentication
    const val PBKDF2_TARGET_AUTH_TIME = 1000 // ms
    const val PBKDF2_MIN_AUTH_ITERATIONS = 30000
    const val PBKDF2_BENCHMARK_ITERATIONS = 50000

    // Legacy PBKDF2 settings for backups
    const val PBKDF2_MIN_BACKUP_ITERATIONS = 140000
    const val PBKDF2_MAX_BACKUP_ITERATIONS = 160000

    // Authentication
    const val AUTH_MIN_PASSWORD_LENGTH = 6

    // KeyStore
    const val KEYSTORE_ALIAS_PASSWORD = "password"
    const val KEYSTORE_ALIAS_WRAPPING = "settings"

    // Database files
    const val FILENAME_ENCRYPTED_KEY = "otp.key"
    const val FILENAME_DATABASE = "secrets.dat"
    const val FILENAME_DATABASE_BACKUP = "secrets.dat.bck"

    // Backup files
    const val BACKUP_FILENAME_CRYPT = "otp_accounts.json.aes"
    const val BACKUP_FILENAME_PGP = "otp_accounts.json.gpg"

    const val BACKUP_FILENAME_CRYPT_FORMAT = "otp_accounts_%s.json.aes"
    const val BACKUP_FILENAME_PGP_FORMAT = "otp_accounts_%s.json.gpg"

    const val BACKUP_MIMETYPE_CRYPT = "binary/aes"
    const val BACKUP_MIMETYPE_PGP = "application/pgp-encrypted"
}
