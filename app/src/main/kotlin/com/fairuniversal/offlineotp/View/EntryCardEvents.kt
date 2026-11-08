package com.fairuniversal.offlineotp.View

import android.view.View

/** Android card commands; popup anchoring is the only View dependency. */
interface EntryCardEvents {
    fun onMoveEventStart()
    fun onMoveEventStop()

    fun onPrivacyClicked(position: Int)
    fun onMenuButtonClicked(parentView: View, position: Int)
    fun onCopyButtonClicked(text: String, position: Int)

    fun onCardSingleClicked(position: Int, text: String)
    fun onCardDoubleClicked(position: Int, text: String)

    fun onCounterClicked(position: Int)
    fun onCounterLongPressed(position: Int)
}

