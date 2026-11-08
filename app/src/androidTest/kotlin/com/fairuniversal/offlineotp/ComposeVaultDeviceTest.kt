package com.fairuniversal.offlineotp

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fairuniversal.offlineotp.ui.vault.VaultActions
import com.fairuniversal.offlineotp.ui.vault.VaultAppearance
import com.fairuniversal.offlineotp.ui.vault.VaultCardState
import com.fairuniversal.offlineotp.ui.vault.VaultComposeView
import com.fairuniversal.offlineotp.ui.vault.VaultEntryKind
import com.fairuniversal.offlineotp.ui.vault.VaultStrings
import com.fairuniversal.offlineotp.ui.vault.VaultUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose semantics/lifecycle tests. The sandbox only type-checks these; it cannot run them. */
@RunWith(AndroidJUnit4::class)
class ComposeVaultDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val strings = VaultStrings.English
    private val card = VaultCardState(
        id = 1L, kind = VaultEntryKind.OTP, issuer = "Example", label = "alice",
        thumbnailName = "GitHub", token = "123 456", previousToken = "654 321",
        detailsVisible = true, revealed = true, copyable = true, customPeriodSeconds = 45
    )

    private fun frame(row: VaultCardState) = VaultUiState(cards = listOf(row), totalEntries = 1)

    @Test
    fun frameReplacementUsesCurrentActionsAndClearIsTerminal() {
        lateinit var view: VaultComposeView
        var firstCopies = 0
        var secondCopies = 0
        compose.runOnIdle {
            val localized = strings.copy(customPeriod = { seconds ->
                compose.activity.getString(R.string.format_custom_period, seconds)
            })
            view = VaultComposeView(compose.activity, localized, VaultActions(), VaultAppearance(showControls = false))
            compose.activity.setContentView(view)
            view.render(frame(card), VaultActions(onCopyToken = { firstCopies++ }))
        }
        compose.onNodeWithText("123 456", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.format_custom_period, 45),
            useUnmergedTree = true).assertExists()
        compose.onNodeWithText(strings.copyAction, useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals(1, firstCopies)
            view.render(frame(card.copy(token = "222 333")), VaultActions(onCopyToken = { secondCopies++ }))
        }
        compose.onNodeWithText("123 456", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("222 333", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(strings.copyAction, useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals(1, firstCopies)
            assertEquals(1, secondCopies)
            view.clear()
            view.render(frame(card), VaultActions(onCopyToken = { secondCopies++ }))
        }
        compose.onNodeWithText("123 456", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("222 333", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(strings.copyAction, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun disablingThumbnailsSkipsTheHostArtworkLoader() {
        lateinit var view: VaultComposeView
        var requests = 0
        compose.runOnIdle {
            view = VaultComposeView(compose.activity, strings, VaultActions(),
                VaultAppearance(showControls = false, showThumbnails = false)) {
                requests++
                null
            }
            compose.activity.setContentView(view)
            view.render(frame(card))
        }
        compose.onNodeWithText("123 456", useUnmergedTree = true).assertExists()
        compose.runOnIdle {
            assertEquals(0, requests)
            view.clear()
        }
    }

    @Test
    fun concealedFramesHaveNoProtectedTextOrArtworkRequests() {
        lateinit var view: VaultComposeView
        val requests = ArrayList<String>()
        // Mirrors the controller's redacted frame, not an entry with secrets merely hidden by styling.
        val concealed = card.copy(detailsVisible = false, revealed = false, copyable = false,
            token = null, previousToken = null, thumbnailName = "", customPeriodSeconds = null)
        compose.runOnIdle {
            view = VaultComposeView(compose.activity, strings, VaultActions(), VaultAppearance(showControls = false)) { name ->
                requests.add(name)
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
            compose.activity.setContentView(view)
            view.render(frame(concealed))
        }
        compose.onNodeWithText("alice", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("123 456", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("45 s", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(emptyList<String>(), requests)
            view.render(frame(card))
        }
        compose.onNodeWithText("123 456", useUnmergedTree = true).assertExists()
        compose.runOnIdle {
            assertEquals(listOf("GitHub"), requests)
            view.render(frame(card.copy(token = "222 333")))
        }
        compose.onNodeWithText("222 333", useUnmergedTree = true).assertExists()
        compose.runOnIdle {
            assertEquals(listOf("GitHub"), requests) // A tick must not reload artwork.
            view.render(frame(card.copy(thumbnailName = "Google")))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf("GitHub", "Google"), requests)
            view.render(frame(concealed))
        }
        for (text in listOf("123 456", "654 321", "45 s", strings.copyAction)) {
            compose.onNodeWithText(text, useUnmergedTree = true).assertDoesNotExist()
        }
        compose.runOnIdle {
            assertEquals(listOf("GitHub", "Google"), requests)
            view.clear()
        }
    }
}
