package org.shadowice.flocke.andotp.Fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

import org.shadowice.flocke.andotp.R

/** F-Droid flavor: adds donation links for both authors to the About tab. */
open class AboutFragment : BaseAboutFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        val v = super.onCreateView(inflater, container, savedInstanceState)

        val author1Content = requireNotNull(v.findViewById<LinearLayout>(R.id.aboutLayoutAuthor1Content))
        val author2Content = requireNotNull(v.findViewById<LinearLayout>(R.id.aboutLayoutAuthor2Content))

        val extra1 = inflater.inflate(R.layout.part_author_extra, null)
        val extra2 = inflater.inflate(R.layout.part_author_extra, null)

        extra1.setOnClickListener {
            try {
                openURI(ABOUT_AUTHOR1_EXTRA_LINK)
            } catch (ignored: Exception) {
                copyToClipboard(ABOUT_AUTHOR1_EXTRA_LINK)
            }
        }

        extra2.setOnClickListener {
            try {
                openURI(ABOUT_AUTHOR2_EXTRA_LINK)
            } catch (ignored: Exception) {
                copyToClipboard(ABOUT_AUTHOR2_EXTRA_LINK)
            }
        }

        author1Content.addView(extra1)
        author2Content.addView(extra2)

        return v
    }

    companion object {
        private const val ABOUT_AUTHOR1_EXTRA_LINK = "https://flocke.shadowice.org/donate.html"
        private const val ABOUT_AUTHOR2_EXTRA_LINK = "https://richyhbm.co.uk/donate"
    }
}
