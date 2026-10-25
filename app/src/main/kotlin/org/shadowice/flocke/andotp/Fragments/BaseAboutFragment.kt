package org.shadowice.flocke.andotp.Fragments

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.Tools

/** Shared About tab UI and link handling; flavors extend this with their own extras. */
open class BaseAboutFragment : Fragment() {
    private var settings: Settings? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_about, container, false)

        settings = Settings(getActivity()!!)

        val filter = Tools.getThemeColorFilter(getActivity()!!, android.R.attr.textColorSecondary)
        for (i in imageResources) {
            val imgView = requireNotNull(v.findViewById<ImageView>(i))
            imgView.getDrawable()!!.mutate().setColorFilter(filter)
        }

        var versionName: String? = ""
        try {
            val packageInfo = getActivity()!!.getPackageManager()
                .getPackageInfo(getActivity()!!.getPackageName(), 0)
            versionName = packageInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }

        val versionLayout = requireNotNull(v.findViewById<LinearLayout>(R.id.about_layout_version))

        versionLayout.setOnClickListener {
            val thisTap = System.currentTimeMillis()

            if (thisTap - lastTap < 500) {
                taps = taps + 1

                // Only the toast created by this tap is shown. The previous counter toast is
                // cancelled and forgotten, so a cancelled toast can never be re-shown: that used
                // to repeat the last count (for example "7") on the next, unrelated quick tap.
                currentToast?.cancel()
                currentToast = null

                if (taps == 7) {
                    if (settings!!.getSpecialFeatures())
                        currentToast = Toast.makeText(getActivity()!!, R.string.about_toast_special_features_enabled, Toast.LENGTH_LONG)
                    else
                        enableSpecialFeatures()

                    // A burst ends at seven: the next one starts counting from one again.
                    taps = 0
                } else if (taps >= 3) {
                    currentToast = Toast.makeText(getActivity()!!, taps.toString(), Toast.LENGTH_SHORT)
                }

                currentToast?.show()
            } else {
                // A slow tap ends the burst; drop its toast so it cannot reappear later.
                taps = 0
                currentToast?.cancel()
                currentToast = null
            }

            lastTap = thisTap
        }

        val version = requireNotNull(v.findViewById<TextView>(R.id.about_text_version))
        version.setText(versionName)

        val license = requireNotNull(v.findViewById<LinearLayout>(R.id.about_layout_license))
        val changelog = requireNotNull(v.findViewById<LinearLayout>(R.id.about_layout_changelog))
        val source = requireNotNull(v.findViewById<LinearLayout>(R.id.about_layout_source))
        val faq = requireNotNull(v.findViewById<LinearLayout>(R.id.about_layout_faq))

        license.setOnClickListener { openURI(LICENSE_URI) }
        changelog.setOnClickListener { openURI(CHANGELOG_URI) }
        source.setOnClickListener { openURI(GITHUB_URI) }
        faq.setOnClickListener { openURI(ISSUES_URI) }

        val author1Layout = requireNotNull(v.findViewById<LinearLayout>(R.id.aboutLayoutAuthor1))
        author1Layout.setOnClickListener { openURI(AUTHOR1_GITHUB) }

        val author2Layout = requireNotNull(v.findViewById<LinearLayout>(R.id.aboutLayoutAuthor2))
        author2Layout.setOnClickListener { openURI(AUTHOR2_GITHUB) }

        val authorOrigialLayout = requireNotNull(v.findViewById<LinearLayout>(R.id.aboutLayoutOriginalAuthor))
        val authorOriginalApp = requireNotNull(v.findViewById<TextView>(R.id.about_author_original_extra))
        authorOrigialLayout.setOnClickListener { openURI(AUTHOR_ORIGINAL_GITHUB) }
        authorOriginalApp.setOnClickListener {
            try {
                openURI(AUTHOR_ORIGINAL_EXTRA)
            } catch (ignored: Exception) {
                copyToClipboard(AUTHOR_ORIGINAL_EXTRA)
            }
        }

        val contributors = requireNotNull(v.findViewById<LinearLayout>(R.id.about_layout_contributors))
        contributors.setOnClickListener { openURI(CONTRIBUTORS_URI) }

        val supportLayout = requireNotNull(v.findViewById<LinearLayout>(R.id.about_layout_support))
        supportLayout.setOnClickListener { openURI(SUPPORT_URI) }

        return v
    }

    /** Enables the hidden special-features screen (offered after seven quick taps on the version). */
    private fun enableSpecialFeatures() {
        val builder = AlertDialog.Builder(getActivity()!!)
        builder.setTitle(R.string.about_title_special_features)
                .setMessage(R.string.about_dialog_special_features)
                .setPositiveButton(android.R.string.ok, DialogInterface.OnClickListener { _, _ ->
                    settings!!.setSpecialFeatures(true)
                    Toast.makeText(getActivity()!!, R.string.about_toast_special_features, Toast.LENGTH_LONG).show()
                })
                .setNegativeButton(android.R.string.cancel, DialogInterface.OnClickListener { _, _ -> })
                .create()
                .show()
    }

    /** Opens the URI in a suitable app. */
    open fun openURI(uri: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse(uri)

        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            copyToClipboard(uri)
        }
    }

    /** Copies the URI to the clipboard (fallback when no app can open it). */
    open fun copyToClipboard(uri: String) {
        val clipboard = getActivity()!!.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
        val clip = ClipData.newPlainText(getString(R.string.app_name), uri)
        clipboard!!.setPrimaryClip(clip)
        Toast.makeText(getActivity()!!, getString(R.string.about_toast_copied_to_clipboard), Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val GITHUB_URI = "https://github.com/unsubstantiation/OfflineOTP"
        private const val CHANGELOG_URI = "$GITHUB_URI/blob/master/CHANGELOG.md"
        private const val LICENSE_URI = "$GITHUB_URI/blob/master/LICENSE.txt"
        private const val ISSUES_URI = "$GITHUB_URI/issues"

        private const val AUTHOR1_GITHUB = "https://github.com/flocke"
        private const val AUTHOR2_GITHUB = "https://github.com/richyhbm"

        private const val AUTHOR_ORIGINAL_GITHUB = "https://github.com/0xbb"
        private const val AUTHOR_ORIGINAL_EXTRA = "$AUTHOR_ORIGINAL_GITHUB/otp-authenticator"

        private const val CONTRIBUTORS_URI = "$GITHUB_URI/graphs/contributors"

        private const val SUPPORT_URI = "$GITHUB_URI/blob/master/README.md#contributing"

        /** About-tab icons tinted with the secondary text color. */
        @JvmField
        val imageResources: IntArray = intArrayOf(
                R.id.aboutImgVersion, R.id.aboutImgLicense, R.id.aboutImgChangelog, R.id.aboutImgSource,
                R.id.aboutImgFaq, R.id.aboutImgAuthor2, R.id.aboutImgAuthorOriginal,
                R.id.aboutImgContributors, R.id.aboutImgSupport
        )

        /** Shared across fragment instances so the tap counter survives recreation. */
        @JvmField
        var lastTap: Long = 0

        @JvmField
        var taps: Int = 0

        @JvmField
        var currentToast: Toast? = null
    }
}
