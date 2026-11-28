package com.fairuniversal.offlineotp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.BrowserPasswordCsv
import com.fairuniversal.offlineotp.Utilities.GoogleAuthenticatorMigration
import com.fairuniversal.offlineotp.Utilities.MigrationParseResult
import com.fairuniversal.offlineotp.Utilities.PasswordCsvExport
import com.fairuniversal.offlineotp.Utilities.PasswordImport
import com.fairuniversal.offlineotp.Utilities.PendingMigrationImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs shared codecs through the actual Android variant and native entry ownership adapters. */
@RunWith(AndroidJUnit4::class)
class InterchangeCodecDeviceTest {
    @Test
    fun browserCsvRoundTripRetainsUnknownColumnsAndQuotedSecrets() {
        val csv = "name,url,username,password,note,provider\r\nSite,example.invalid,alice,\"p,\"\"w\"\"\",\"line1\nline2\",keep\r\n".toByteArray()
        val imported = PasswordImport.prepare(csv, "browser.csv", PasswordImport.Mode.INDIVIDUAL)
        var exported: ByteArray? = null
        try {
            exported = PasswordCsvExport.exportCsv(imported, PasswordCsvExport.Dialect.CHROME)
            val parsed = BrowserPasswordCsv.parse(exported) as BrowserPasswordCsv.Result.Success
            assertEquals("keep", parsed.rows.single().valueAt(5))
            assertEquals("p,\"w\"", parsed.rows.single().valueAt(3))
            assertEquals("line1\nline2", parsed.rows.single().valueAt(4))
        } finally { imported.forEach { it.destroy() }; csv.fill(0); exported?.fill(0) }
    }

    @Test
    fun migrationAndOtpUseSharedValidationAndConsumeNativePendingOwnershipOnce() {
        val otp = Entry("otpauth://hotp/alice%2Bwork?secret=JBSWY3DPEHPK3PXP&counter=42&issuer=Acme")
        try {
            val reopened = Entry(otp.toUriString())
            try { assertEquals(42L, reopened.getCounter()); assertEquals("alice+work", reopened.getLabel()) }
            finally { reopened.destroy() }
        } finally { otp.destroy() }
        // Minimal proto3 export: one TOTP account with a public one-byte test seed, version/size 1.
        val proto = byteArrayOf(10, 5, 10, 1, 1, 48, 2, 16, 1, 24, 1)
        val result = GoogleAuthenticatorMigration.parse("otpauth-migration://offline?data=" + java.util.Base64.getEncoder().encodeToString(proto))
        assertTrue(result is MigrationParseResult.Success)
        val payload = (result as MigrationParseResult.Success).payload
        try {
            val accounts = payload.takeAccounts()
            try { assertEquals(1, accounts.size) } finally { accounts.forEach { it.close() } }
        } finally { payload.close(); proto.fill(0) }
        val entry = Entry("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP")
        val pending = PendingMigrationImport(listOf(entry), 7)
        assertNull(pending.take(8, true)); assertNull(entry.getSecret()); assertNull(pending.take(7, true))
        pending.close()
    }
}
