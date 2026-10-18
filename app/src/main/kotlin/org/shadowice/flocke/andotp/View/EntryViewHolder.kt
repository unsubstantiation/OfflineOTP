package org.shadowice.flocke.andotp.View

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.ColorFilter
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.StyleSpan
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView

import me.zhanghai.android.materialprogressbar.MaterialProgressBar

import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.EntryThumbnail
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.Tools
import org.shadowice.flocke.andotp.View.ItemTouchHelper.ItemTouchHelperViewHolder

import java.util.Locale

/**
 * A single entry card and its (single/double) click handling.
 *
 * Converted from the Java class of the same name. The card binding, the tap handling and the
 * progress animation are unchanged.
 *
 * Documented deltas: the layout children are resolved with `requireNotNull`, and the private
 * `AdapterPositionSafeCallbackConsumer` interface stays a nested type of this class.
 */
open class EntryViewHolder(context: Context, v: View, tapToReveal: Boolean) :
    RecyclerView.ViewHolder(v), ItemTouchHelperViewHolder {

    private val context: Context = context
    private var callback: Callback? = null
    private var tapToReveal: Boolean = false

    private val card: CardView
    private val valueLayout: LinearLayout
    private val coverLayout: LinearLayout
    private val counterLayout: LinearLayout
    private val thumbnailFrame: FrameLayout
    private val thumbnailImg: ImageView
    private val menuButton: ImageButton
    private val copyButton: ImageButton
    private val value: TextView
    private val valuePrev: TextView
    private val label: TextView
    private val counter: TextView
    private val tags: TextView
    private val progressBar: MaterialProgressBar

    init {
        card = requireNotNull(v.findViewById<CardView>(R.id.card_view))
        value = requireNotNull(v.findViewById<TextView>(R.id.valueText))
        valuePrev = requireNotNull(v.findViewById<TextView>(R.id.valueTextPrev))
        valueLayout = requireNotNull(v.findViewById<LinearLayout>(R.id.valueLayout))
        thumbnailFrame = requireNotNull(v.findViewById<FrameLayout>(R.id.thumbnailFrame))
        thumbnailImg = requireNotNull(v.findViewById<ImageView>(R.id.thumbnailImg))
        coverLayout = requireNotNull(v.findViewById<LinearLayout>(R.id.coverLayout))
        label = requireNotNull(v.findViewById<TextView>(R.id.textViewLabel))
        tags = requireNotNull(v.findViewById<TextView>(R.id.textViewTags))
        counterLayout = requireNotNull(v.findViewById<LinearLayout>(R.id.counterLayout))
        counter = requireNotNull(v.findViewById<TextView>(R.id.counter))
        progressBar = requireNotNull(v.findViewById<MaterialProgressBar>(R.id.cardProgressBar))

        menuButton = requireNotNull(v.findViewById<ImageButton>(R.id.menuButton))
        copyButton = requireNotNull(v.findViewById<ImageButton>(R.id.copyButton))
        val invisibleImg = requireNotNull(v.findViewById<ImageView>(R.id.coverImg))

        // Style the buttons in the current theme colors
        val colorFilter: ColorFilter = Tools.getThemeColorFilter(context, android.R.attr.textColorSecondary)

        // app:srcCompat drawables are missing when the layout is inflated without AppCompat's factory.
        menuButton.getDrawable()?.setColorFilter(colorFilter)
        copyButton.getDrawable()?.setColorFilter(colorFilter)
        invisibleImg.getDrawable()?.setColorFilter(colorFilter)

        setupOnClickListeners(menuButton, copyButton)

        setTapToReveal(tapToReveal)
    }

    private fun setupOnClickListeners(menuButton: ImageButton, copyButton: ImageButton) {
        menuButton.setOnClickListener { view ->
            adapterPositionSafeCallback { callback, adapterPosition ->
                callback.onMenuButtonClicked(view, adapterPosition)
            }
        }

        copyButton.setOnClickListener { view ->
            adapterPositionSafeCallback { callback, adapterPosition ->
                callback.onCopyButtonClicked(value.tag.toString(), adapterPosition)
            }
        }

        counterLayout.setOnClickListener { view ->
            adapterPositionSafeCallback { callback, adapterPosition ->
                callback.onCounterClicked(adapterPosition)
            }
        }

        counterLayout.setOnLongClickListener { view ->
            adapterPositionSafeCallback { callback, adapterPosition ->
                callback.onCounterLongPressed(adapterPosition)
            }
            false
        }

        card.setOnClickListener(object : SimpleDoubleClickListener() {
            override fun onSingleClick(v: View) {
                adapterPositionSafeCallback { callback, adapterPosition ->
                    callback.onCardSingleClicked(adapterPosition, value.tag.toString())
                }
            }

            override fun onDoubleClick(v: View) {
                adapterPositionSafeCallback { callback, adapterPosition ->
                    callback.onCardDoubleClicked(adapterPosition, value.tag.toString())
                }
            }
        })
    }

    /** Consumer for a click event, called only with a bound view holder. */
    private fun interface AdapterPositionSafeCallbackConsumer {
        /** The specified [Callback] is guaranteed to be non-null, and adapterPosition is
         * guaranteed to be a valid position. */
        fun accept(callback: Callback, adapterPosition: Int)
    }

    /** Runs the callback with the clicked position, if the view holder is still bound. */
    private fun adapterPositionSafeCallback(safeCallback: AdapterPositionSafeCallbackConsumer) {
        val clickedPosition = adapterPosition
        val current = callback
        if (current != null && clickedPosition != RecyclerView.NO_POSITION) {
            safeCallback.accept(current, clickedPosition)
        }
    }

    /** Fills the card with the entry's token, labels, tags and counter. */
    fun updateValues(entry: Entry) {
        val settings = Settings(context)

        if (entry.getType() == Entry.OTPType.HOTP) {
            counterLayout.visibility = View.VISIBLE
            counter.setText(String.format(Locale.ENGLISH, "%d", entry.getCounter()))
        } else {
            counterLayout.visibility = View.GONE
        }

        val tokenFormatted = Tools.formatToken(entry.getCurrentOTP(), settings.getTokenSplitGroupSize())

        val issuerText = entry.getIssuer()
        val labelText = entry.getLabel()

        var contentHint = ""

        val labelBuilder = SpannableStringBuilder()

        if (!TextUtils.isEmpty(issuerText) && !settings.isHideIssuerEnabled()) {
            labelBuilder.append(issuerText)

            labelBuilder.setSpan(StyleSpan(Typeface.BOLD), 0, issuerText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

            contentHint = issuerText
        }

        if (!TextUtils.isEmpty(issuerText) && !TextUtils.isEmpty(labelText) && !settings.isHideIssuerEnabled()) {
            var separatorText = " - " //   = non-breaking space

            if (settings.getCardLayout() == Constants.CardLayouts.FULL)
                separatorText = "\n"

            labelBuilder.append(separatorText)

            contentHint = issuerText + " - " + labelText
        }

        if (!TextUtils.isEmpty(labelText)) {
            labelBuilder.append(labelText)

            if (TextUtils.isEmpty(issuerText) || settings.isHideIssuerEnabled())
                contentHint = labelText
        }

        label.setText(labelBuilder)

        copyButton.contentDescription = context.getString(R.string.button_card_copy_format, contentHint)
        menuButton.contentDescription = context.getString(R.string.button_card_options_format, contentHint)

        value.setText(tokenFormatted)
        // save the unformatted token to the tag of this TextView for copy/paste
        value.tag = entry.getCurrentOTP()

        if (settings.getShowPrevToken()) {
            val tokenPrev = entry.getPrevOTP()

            if (tokenPrev != null && tokenPrev.isNotEmpty()) {
                val tokenFormattedPrev = Tools.formatToken(tokenPrev, settings.getTokenSplitGroupSize())

                valuePrev.visibility = View.VISIBLE
                valuePrev.setText(tokenFormattedPrev)
            } else {
                valuePrev.visibility = View.GONE
            }
        } else {
            valuePrev.visibility = View.GONE
        }

        val entryTags = entry.getTags()

        val stringBuilder = StringBuilder()
        for (i in entryTags.indices) {
            stringBuilder.append(entryTags[i])
            if (i < entryTags.size - 1) {
                stringBuilder.append(", ")
            }
        }
        tags.setText(stringBuilder.toString())

        tags.visibility = if (entryTags.isEmpty()) View.GONE else View.VISIBLE
        thumbnailFrame.visibility = if (settings.getThumbnailVisible()) View.VISIBLE else View.GONE

        val thumbnailSize = settings.getThumbnailSize()
        if (settings.getThumbnailVisible()) {
            thumbnailImg.setImageBitmap(
                EntryThumbnail.getThumbnailGraphic(context, issuerText, labelText, thumbnailSize, entry.getThumbnail()))
        }

        if (entry.isTimeBased() && (entry.hasNonDefaultPeriod() || settings.isShowIndividualTimeoutsEnabled())) {
            if (!this.tapToReveal || entry.isVisible()) {
                progressBar.visibility = View.VISIBLE
                updateProgress(entry)
            } else {
                progressBar.visibility = View.INVISIBLE
            }
        } else {
            progressBar.visibility = View.GONE
        }

        if (this.tapToReveal) {
            if (entry.isVisible()) {
                valueLayout.visibility = View.VISIBLE
                coverLayout.visibility = View.GONE
            } else {
                valueLayout.visibility = View.GONE
                coverLayout.visibility = View.VISIBLE
            }
        }
    }

    /** Animates the progress bar to the remaining time of the current period. */
    private fun updateProgress(entry: Entry) {
        val step = entry.getTokenStepSeconds()
        val progress = (step - (System.currentTimeMillis() / 1000) % step).toInt()

        progressBar.max = step * 100
        progressBar.progress = progress * 100

        val animation = ObjectAnimator.ofInt(progressBar, "progress", (progress - 1) * 100)
        animation.setDuration(org.shadowice.flocke.andotp.Activities.MainActivity.animatorDuration)
        animation.setInterpolator(LinearInterpolator())
        animation.start()
    }

    /** Applies the label size (the tag line is scaled along with it). */
    fun setLabelSize(size: Int) {
        label.textSize = size.toFloat()
        tags.textSize = 0.75f * size
    }

    /** Resizes the thumbnail image. */
    fun setThumbnailSize(size: Int) {
        val params = requireNotNull(thumbnailImg.layoutParams)
        params.height = size
        params.width = size
        thumbnailImg.layoutParams = params
        thumbnailImg.requestLayout()
    }

    /** Configures how an overlong label is displayed (truncated, scrolling or multiline). */
    fun setLabelScroll(labelDisplay: Constants.LabelDisplay, cardLayout: Constants.CardLayouts) {
        when (labelDisplay) {
            Constants.LabelDisplay.TRUNCATE -> {
                label.ellipsize = TextUtils.TruncateAt.END
                label.setHorizontallyScrolling(false)
                label.isSelected = false
                label.maxLines = if (cardLayout == Constants.CardLayouts.FULL) 2 else 1
            }
            Constants.LabelDisplay.SCROLL -> {
                label.ellipsize = TextUtils.TruncateAt.MARQUEE
                label.setHorizontallyScrolling(true)
                label.isSelected = true
                label.maxLines = if (cardLayout == Constants.CardLayouts.FULL) 2 else 1
            }
            Constants.LabelDisplay.MULTILINE -> {
                label.ellipsize = null
                label.setHorizontallyScrolling(false)
                label.isSelected = false
                label.maxLines = 10
            }
        }
    }

    /** Shows the cover instead of the token while tap-to-reveal is active. */
    private fun setTapToReveal(enabled: Boolean) {
        tapToReveal = enabled

        if (enabled) {
            valueLayout.visibility = View.GONE
            coverLayout.visibility = View.VISIBLE
        } else {
            valueLayout.visibility = View.VISIBLE
            coverLayout.visibility = View.GONE
        }
    }

    override fun onItemSelected() {
        callback?.onMoveEventStart()
        card.alpha = 0.5f
    }

    override fun onItemClear() {
        callback?.onMoveEventStop()
        card.alpha = 1f
    }

    fun setCallback(cb: Callback?) {
        this.callback = cb
    }

    /** Events of one card that the adapter reacts to. */
    interface Callback {
        fun onMoveEventStart()
        fun onMoveEventStop()

        fun onMenuButtonClicked(parentView: View, position: Int)
        fun onCopyButtonClicked(text: String, position: Int)

        fun onCardSingleClicked(position: Int, text: String)
        fun onCardDoubleClicked(position: Int, text: String)

        fun onCounterClicked(position: Int)
        fun onCounterLongPressed(position: Int)
    }

    /**
     * Colors the token text with the theme's "expiring" color when [color] is
     * [Entry.COLOR_RED], and with the secondary text color otherwise.
     */
    fun updateColor(color: Int) {
        val textColor: Int
        if (color == Entry.COLOR_RED) {
            textColor = Tools.getThemeColor(context, R.attr.colorExpiring)
        } else {
            textColor = Tools.getThemeColor(context, android.R.attr.textColorSecondary)
        }

        value.setTextColor(textColor)
        valuePrev.setTextColor(textColor)
    }
}
