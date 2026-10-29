/*
 * Issuer-to-thumbnail resolution for the brands whose spelling differs from the persisted name.
 *
 * The entry model resolves a logo once, from the issuer string, and stores the *name* in the
 * vault. These vectors pin both halves of that lookup: the punctuation/spacing fold that lets
 * "Login.gov" reach the persisted `LoginGov`, and the small alias table for abbreviations that
 * share no spelling with the brand name. They also assert that every catalog name resolves to
 * itself, so a future name that only matches after folding cannot become unreachable.
 */
package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailResolutionTest {
    @Test
    fun exactBrandNamesResolveToTheirIcon() {
        assertEquals("Oracle", ThumbnailCatalog.resolveName("Oracle"))
        assertEquals("Veeqo", ThumbnailCatalog.resolveName("Veeqo"))
        assertEquals("Discord", ThumbnailCatalog.resolveName("Discord"))
        assertEquals("Parsec", ThumbnailCatalog.resolveName("Parsec"))
        assertEquals("LoginGov", ThumbnailCatalog.resolveName("Login.gov"))
    }

    @Test
    fun punctuationAndSpacingVariantsResolveToTheSameIcon() {
        for (issuer in listOf("Login.gov", "login.gov", "login gov", "LOGIN.GOV", "LoginGov")) {
            assertEquals(issuer, "LoginGov", ThumbnailCatalog.resolveName(issuer))
        }
        assertEquals("BattleNet", ThumbnailCatalog.resolveName("Battle.net"))
        assertEquals("GovUK", ThumbnailCatalog.resolveName("GOV.UK"))
        assertEquals("DigitalOcean", ThumbnailCatalog.resolveName("digital ocean"))
        assertEquals("Charles_Schwab", ThumbnailCatalog.resolveName("charles schwab"))
    }

    @Test
    fun unambiguousAbbreviationsUseTheAliasTable() {
        assertEquals("AmazonWebServices", ThumbnailCatalog.resolveName("AWS"))
        assertEquals("OnePassword", ThumbnailCatalog.resolveName("1Password"))
        assertEquals("Microsoft", ThumbnailCatalog.resolveName("Microsoft Azure"))
        assertEquals("GoogleCloud", ThumbnailCatalog.resolveName("GCP"))
        assertEquals("Tutanota", ThumbnailCatalog.resolveName("Tuta"))
    }

    @Test
    fun unknownIssuersKeepTheLetterTile() {
        for (issuer in listOf("", "Acme Internal Vault", "acme-corp-internal", "12345")) {
            assertEquals(issuer, ThumbnailCatalog.DEFAULT_NAME, ThumbnailCatalog.resolveName(issuer))
        }
        assertEquals(ThumbnailCatalog.DEFAULT_NAME, ThumbnailCatalog.normalizeName("NotABrand"))
        assertEquals(ThumbnailCatalog.DEFAULT_NAME, ThumbnailCatalog.normalizeName(null))
    }

    @Test
    fun everyCatalogNameResolvesToItself() {
        for (name in ThumbnailCatalog.NAMES) {
            assertEquals(name, ThumbnailCatalog.normalizeName(name))
            assertEquals(name, ThumbnailCatalog.resolveName(name))
        }
    }
}
