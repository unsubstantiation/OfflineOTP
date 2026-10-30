package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPasswordCsvTest {
    private fun parse(text: String) = BrowserPasswordCsv.parse(text.toByteArray(Charsets.UTF_8))
    private fun success(text: String) = parse(text) as BrowserPasswordCsv.Result.Success
    private fun fails(text: String, error: BrowserPasswordCsv.Error) {
        assertEquals(error, (parse(text) as BrowserPasswordCsv.Result.Failure).error)
    }

    @Test fun chromeFieldsAndQuotedMultilineContentSurvive() {
        val result = success("\uFEFFname,url,username,password,note\r\n" +
            "Example,https://example.test,alice,\"p,\"\"q\"\"\",\"one\r\ntwo\"\r\n")
        assertEquals(BrowserPasswordCsv.Browser.CHROME, result.browser)
        assertEquals("p,\"q\"", result.rows[0].valueAt(3))
        assertEquals("one\r\ntwo", result.rows[0].valueAt(4))
        assertTrue(!result.toString().contains("alice"))
        assertTrue(!result.rows[0].toString().contains("p,"))
    }

    @Test fun firefoxAndSafariPreserveEveryColumn() {
        val firefox = success("url,username,password,httpRealm,formActionOrigin,guid\n" +
            "https://example.test,bob,secret,realm,origin,id\n")
        assertEquals(BrowserPasswordCsv.Browser.FIREFOX, firefox.browser)
        assertEquals("realm", firefox.rows[0].valueAt(3))
        val safari = success("Title,URL,Username,Password,Notes,OTPAuth\n" +
            "Site,https://example.test,user,secret,note,otpauth://example\n")
        assertEquals(BrowserPasswordCsv.Browser.SAFARI, safari.browser)
        assertEquals("OTPAuth", safari.columns[5])
        assertEquals("otpauth://example", safari.rows[0].valueAt(5))
    }

    @Test fun rejectsMalformedOrAmbiguousInputWithoutLeakingIt() {
        fails("name,url,username,password\na,b,c,\"unterminated", BrowserPasswordCsv.Error.INVALID_CSV)
        fails("name,url,username,password\na,b,c,\"bad\"x", BrowserPasswordCsv.Error.INVALID_CSV)
        fails("name,url,username,password\na,b,c", BrowserPasswordCsv.Error.INVALID_CSV)
        fails("name,url,username,password,PASSWORD\na,b,c,d,e", BrowserPasswordCsv.Error.INVALID_CSV)
        fails("url,username,password\na,b,c", BrowserPasswordCsv.Error.UNSUPPORTED_FORMAT)
        val invalidUtf8 = byteArrayOf(0xc3.toByte(), 0x28)
        assertEquals(BrowserPasswordCsv.Error.INVALID_UTF8,
            (BrowserPasswordCsv.parse(invalidUtf8) as BrowserPasswordCsv.Result.Failure).error)
        fails("name,url,username,password\na,b,c," + "x".repeat(BrowserPasswordCsv.MAX_FIELD_CHARS + 1),
            BrowserPasswordCsv.Error.TOO_LARGE)
    }

    @Test fun boundedRowsAndInput() {
        val header = "name,url,username,password\n"
        val rows = "site,url,user,pass\n".repeat(BrowserPasswordCsv.MAX_ROWS + 1)
        fails(header + rows, BrowserPasswordCsv.Error.TOO_MANY_ROWS)
        assertEquals(BrowserPasswordCsv.Error.TOO_LARGE,
            (BrowserPasswordCsv.parse(ByteArray(BrowserPasswordCsv.MAX_FILE_BYTES + 1)) as BrowserPasswordCsv.Result.Failure).error)
    }
}
