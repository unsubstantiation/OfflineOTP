package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenPgpRecipientIdsTest {
    @Test
    fun trimsEmptyValuesAndDuplicates() {
        assertEquals(listOf("alice@example.org", "bob@example.org"),
            OpenPgpRecipientIds.parse(" alice@example.org, ,bob@example.org,alice@example.org "))
    }

    @Test
    fun emptyAndOversizedRecipientListsAreNotUsable() {
        assertEquals(emptyList<String>(), OpenPgpRecipientIds.parse(null))
        assertEquals(emptyList<String>(), OpenPgpRecipientIds.parse(" , \t ,"))
        assertEquals(emptyList<String>(),
            OpenPgpRecipientIds.parse("a".repeat(InputPolicy.MAX_TEXT_CHARS + 1)))
    }
}
