/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.fairuniversal.offlineotp.Activities

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.fairuniversal.offlineotp.Fragments.AboutFragment
import com.fairuniversal.offlineotp.R
import com.fairuniversal.offlineotp.Utilities.Settings
import com.fairuniversal.offlineotp.Utilities.applySpecialFeatures
import com.fairuniversal.offlineotp.ui.host.AboutScreenComposeView
import com.fairuniversal.offlineotp.ui.host.DeleteConfirmationComposeView
import com.fairuniversal.offlineotp.ui.portable.PortableLibraryNotice
import org.json.JSONArray
import org.json.JSONObject

/** Shared About/library presentation; Android owns generated build metadata and external intents. */
open class AboutActivity : BaseActivity() {
    private var closed = false
    private var confirmation: DeleteConfirmationComposeView? = null
    private var taps = 0
    private var lastTap = 0L
    private var page: AboutScreenComposeView? = null
    private val feedbackHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var feedbackExpiry: Runnable? = null

    private fun clearFeedback() {
        feedbackExpiry?.let { feedbackHandler.removeCallbacks(it) }
        feedbackExpiry = null
    }

    private fun showFeedback(message: String) {
        if (closed || isFinishing()) return
        val host = page ?: return
        clearFeedback()
        val revision = host.showNotice(message)
        val expiry = Runnable { host.dismissNotice(revision) }
        feedbackExpiry = expiry
        feedbackHandler.postDelayed(expiry, 5000L)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.about_activity_title)
        val version = try { getPackageManager().getPackageInfo(getPackageName(), 0).versionName ?: "" }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { "" }
        val libraries = try {
            resources.openRawResource(R.raw.aboutlibraries).bufferedReader(Charsets.UTF_8).use {
                parseLibraryNotices(it.readText().ifBlank { "{}" })
            }
        } catch (_: Exception) {
            // Never silently substitute a generic catalog for this build's generated notices.
            listOf(PortableLibraryNotice("Library notices unavailable", "Please consult the source distribution for this build."))
        }
        val host = AboutScreenComposeView(this, version, libraries, AboutFragment().sharedExtraLinks(),
            onOpenLink = ::openLink, onVersionTap = ::versionTap, onClose = { finish() })
        page = host
        setContentView(host)
    }

    private fun openLink(url: String): Boolean {
        if (closed || isFinishing()) return false
        try { startActivity(Intent(Intent.ACTION_VIEW).apply { data = Uri.parse(url) }) }
        catch (_: ActivityNotFoundException) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), url))
            showFeedback(getString(R.string.about_toast_copied_to_clipboard))
        }
        return true
    }

    private fun versionTap() {
        if (closed || isFinishing()) return
        val now = System.currentTimeMillis()
        taps = if (now - lastTap < 500) taps + 1 else 0
        lastTap = now
        clearFeedback()
        page?.showNotice("")
        if (taps == 7) {
            taps = 0
            if (Settings(this).getSpecialFeatures()) {
                showFeedback(getString(R.string.about_toast_special_features_enabled))
            } else showSpecialFeatures()
        } else if (taps >= 3) showFeedback(taps.toString())
    }

    private fun showSpecialFeatures() {
        if (confirmation != null) return
        lateinit var prompt: DeleteConfirmationComposeView
        prompt = DeleteConfirmationComposeView(this, getString(R.string.about_title_special_features),
            getString(R.string.about_dialog_special_features), getString(android.R.string.ok),
            getString(android.R.string.cancel), onConfirm = {
                if (confirmation === prompt) {
                    confirmation = null
                    prompt.dismiss()
                    if (Settings(this).applySpecialFeatures(this, true))
                        showFeedback(getString(R.string.about_toast_special_features))
                }
            }, onDismiss = {
                if (confirmation === prompt) { confirmation = null; prompt.dismiss() }
            })
        confirmation = prompt
        prompt.setOnDismissListener { if (confirmation === prompt) confirmation = null }
        prompt.show()
    }

    override fun onDestroy() {
        closed = true
        confirmation?.dismiss()
        confirmation = null
        clearFeedback()
        page?.close()
        page = null
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    override fun onBackPressed() { finish(); super.onBackPressed() }

    companion object {
        /** AboutLibraries JSON is build-generated: retain versions, URLs and full license text. */
        internal fun parseLibraryNotices(json: String): List<PortableLibraryNotice> {
            val root = JSONObject(json)
            val libraries = root.opt("libraries") as? JSONArray ?: return emptyList()
            val licenses = root.opt("licenses") as? JSONObject
            return (0 until libraries.length()).map { index ->
                val lib = libraries.getJSONObject(index)
                val ids = lib.opt("licenses") as? JSONArray
                val notices = if (ids == null) emptyList() else (0 until ids.length()).map { i ->
                    val id = ids.getString(i)
                    val license = licenses?.opt(id) as? JSONObject
                    if (license == null) id else listOf("name", "url", "licenseContent")
                        .mapNotNull { license.optString(it)?.takeIf { value -> value.isNotBlank() } }.joinToString("\n\n")
                }
                PortableLibraryNotice(
                    listOfNotNull(lib.optString("name"), lib.optString("artifactVersion")).joinToString(" "),
                    notices.joinToString("\n\n"),
                    listOfNotNull(lib.optString("uniqueId"), lib.optString("website"), lib.optString("description"))
                        .joinToString("\n"))
            }
        }
    }
}
