package org.shadowice.flocke.andotp.Utilities

/**
 * Routes the text decoded from a QR image to the right portable parser. This is the platform-free
 * half of QR import: a [QrImageDecoder] produces the string (zxing on both platforms), and this
 * object decides whether it is a Google Authenticator migration, a single `otpauth://` URI, or
 * unrecognized/invalid content. The returned parser results own their seeds and are AutoCloseable.
 */
object QrImport {

    sealed class Outcome {
        /** One `otpauth://` URI parsed successfully; close the result after copying its seed. */
        class Otp(val result: OtpParseResult.Success) : Outcome()

        /** A Google Authenticator migration payload, possibly containing many accounts. */
        class Migration(val result: MigrationParseResult.Success) : Outcome()

        /** A recognized scheme whose content failed validation; [reason] carries the parser error. */
        class Invalid(val reason: String) : Outcome()

        /** Blank text or content that is neither a migration URI nor an otpauth URI. */
        object Unrecognized : Outcome()
    }

    @JvmStatic
    fun classify(decoded: String?): Outcome {
        if (decoded.isNullOrBlank()) return Outcome.Unrecognized

        if (GoogleAuthenticatorMigration.isMigration(decoded)) {
            return when (val parsed = GoogleAuthenticatorMigration.parse(decoded)) {
                is MigrationParseResult.Success -> Outcome.Migration(parsed)
                is MigrationParseResult.Failure -> Outcome.Invalid(parsed.error.name)
            }
        }

        return when (val parsed = OtpUriParser.parse(decoded)) {
            is OtpParseResult.Success -> Outcome.Otp(parsed)
            is OtpParseResult.Failure -> Outcome.Invalid(parsed.error.name)
        }
    }
}
