package androidx.activity

import android.app.Activity
import android.view.View

// Compile-only. Real lifecycle/Compose behavior must be tested on Android.
open class ComponentActivity : Activity() {
    fun setContentView(view: View) { error("device-only compile stub") }
}
