package org.shadowice.flocke.andotp.Dialogs

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.Tools

/** Dialog with a "don't show again" checkbox that remembers the choice in settings. */
open class HideableDialog(context: Context, titleId: Int, msgId: Int, private val hideSettingId: Int) :
    AppCompatDialog(context, Tools.getThemeResource(context, R.attr.dialogTheme)),
    View.OnClickListener, CompoundButton.OnCheckedChangeListener {

    private val settings = Settings(context)

    init {
        setTitle(titleId)
        setContentView(R.layout.dialog_dont_show_again)
        val content = requireNotNull(findViewById<TextView>(R.id.dialogContent))
        val dontShowAgain = requireNotNull(findViewById<CheckBox>(R.id.dontShowAgain))
        val buttonOk = requireNotNull(findViewById<Button>(R.id.buttonOk))
        content.setText(msgId)
        dontShowAgain.setOnCheckedChangeListener(this)
        buttonOk.setOnClickListener(this)
    }

    override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
        if (hideSettingId > 0) settings.setBoolean(hideSettingId, isChecked)
    }

    override fun onClick(view: View) {
        dismiss()
    }

    companion object {
        /** Shows the dialog unless its [hideSettingId] preference is already set. */
        @JvmStatic
        fun ShowHidableDialog(context: Context, titleId: Int, msgId: Int, hideSettingId: Int) {
            val settings = Settings(context)
            if (!settings.getBoolean(hideSettingId, false)) {
                HideableDialog(context, titleId, msgId, hideSettingId).show()
            }
        }
    }
}
