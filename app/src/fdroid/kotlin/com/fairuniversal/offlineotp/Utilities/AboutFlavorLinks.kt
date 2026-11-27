package com.fairuniversal.offlineotp.Utilities

import com.fairuniversal.offlineotp.ui.vault.AboutLink

/** Flavor metadata only; the common Compose About page owns all presentation. */
object AboutFlavorLinks {
    fun extras(): List<AboutLink> = listOf(
        AboutLink("donateAuthor1", "Support Jakob Nixdorf", "https://flocke.shadowice.org/donate.html"),
        AboutLink("donateAuthor2", "Support Richy HBM", "https://richyhbm.co.uk/donate"))
}
