// Sandbox-only stand-ins, see ../../../../../android/android_content.kt.
package com.google.android.material.switchmaterial

import android.content.Context
import android.widget.CompoundButton

/** The Material switch; the backup screen only reads and sets its checked state. */
open class SwitchMaterial(context: Context) : CompoundButton() {
    override var isChecked: Boolean = false
}
