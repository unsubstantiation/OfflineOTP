// Non-authoritative sandbox checks; see scripts/kotlin-sandbox/README.md before trusting a result.
// Runs the converted utility classes on a desktop JVM against hand-written platform stubs and
// mirrors the repository's own tests for them. It is not the Gradle unit/lint/instrumentation
// gate, and no Android behaviour (Handler, KeyEvent, resources) is exercised here.
import org.shadowice.flocke.andotp.Tasks.AuthenticationError
import org.shadowice.flocke.andotp.Tasks.AuthenticationOutcome
import org.shadowice.flocke.andotp.Tasks.AuthenticationTask
import org.shadowice.flocke.andotp.Tasks.BackupTaskResult
import org.shadowice.flocke.andotp.Tasks.ChangeCredentialsTask
import org.shadowice.flocke.andotp.Tasks.EncryptedBackupTask
import org.shadowice.flocke.andotp.Tasks.EncryptedRestoreTask
import org.shadowice.flocke.andotp.Tasks.FinishIntroTask
import org.shadowice.flocke.andotp.Tasks.GenericBackupTask
import org.shadowice.flocke.andotp.Tasks.GenericRestoreTask
import org.shadowice.flocke.andotp.Tasks.PGPBackupTask
import org.shadowice.flocke.andotp.Tasks.PGPRestoreTask
import org.shadowice.flocke.andotp.Tasks.TaskCancellation
import org.shadowice.flocke.andotp.Tasks.TaskCompletion
import org.shadowice.flocke.andotp.Tasks.TaskController
import org.shadowice.flocke.andotp.Tasks.TaskFailure
import org.shadowice.flocke.andotp.Tasks.TaskOutcome
import org.shadowice.flocke.andotp.Tasks.TaskStart
import org.shadowice.flocke.andotp.Tasks.TaskState
import org.shadowice.flocke.andotp.Tasks.UiBasedBackgroundTask
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Database.EntryJson
import org.shadowice.flocke.andotp.Database.EntryJsonError
import org.shadowice.flocke.andotp.Database.EntryJsonResult
import org.shadowice.flocke.andotp.Database.ValidatedEntry
import org.shadowice.flocke.andotp.Utilities.EntryThumbnail
import org.shadowice.flocke.andotp.Utilities.LetterBitmap
import org.shadowice.flocke.andotp.Utilities.BoundedInputReader
import org.shadowice.flocke.andotp.Utilities.BackupHelper
import org.shadowice.flocke.andotp.Utilities.StorageAccessHelper
import org.shadowice.flocke.andotp.Utilities.NotificationHelper
import org.shadowice.flocke.andotp.Utilities.ScanQRCodeFromFile
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper
import org.shadowice.flocke.andotp.Utilities.KeyStoreHelper
import org.shadowice.flocke.andotp.Utilities.VaultReadError
import org.shadowice.flocke.andotp.Utilities.VaultReadResult
import org.shadowice.flocke.andotp.Utilities.VaultCommitDecision
import org.shadowice.flocke.andotp.Utilities.VaultCommitState
import org.shadowice.flocke.andotp.Utilities.VaultWriteResult
import org.shadowice.flocke.andotp.Utilities.ConfirmedPasswordTransformationHelper
import org.shadowice.flocke.andotp.Utilities.DimensionConverter
import org.shadowice.flocke.andotp.Utilities.SecretKeyWrapper
import org.shadowice.flocke.andotp.Utilities.BoundedOutput
import org.shadowice.flocke.andotp.Utilities.EditorActionHelper
import org.shadowice.flocke.andotp.Utilities.EncryptedBackupHeader
import org.shadowice.flocke.andotp.Utilities.HandlerTimerScheduler
import org.shadowice.flocke.andotp.Utilities.InputPolicy
import org.shadowice.flocke.andotp.Utilities.OtpHashAlgorithm
import org.shadowice.flocke.andotp.Utilities.OtpKind
import org.shadowice.flocke.andotp.Utilities.OtpParseResult
import org.shadowice.flocke.andotp.Utilities.OwnedSecret
import org.shadowice.flocke.andotp.Utilities.SessionCommandResult
import org.shadowice.flocke.andotp.Utilities.SessionController
import org.shadowice.flocke.andotp.Utilities.SessionFailure
import org.shadowice.flocke.andotp.Utilities.SessionLockReason
import org.shadowice.flocke.andotp.Utilities.SessionRejection
import org.shadowice.flocke.andotp.Utilities.SessionState
import org.shadowice.flocke.andotp.Utilities.ValidatedOtp
import org.shadowice.flocke.andotp.Utilities.VaultSession
import org.shadowice.flocke.andotp.Utilities.ScreenTimerController
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import org.shadowice.flocke.andotp.Utilities.Tools
import org.shadowice.flocke.andotp.Utilities.UIHelper
import org.shadowice.flocke.andotp.View.ItemTouchHelper.ItemTouchHelperAdapter
import org.shadowice.flocke.andotp.View.ItemTouchHelper.ItemTouchHelperViewHolder
import org.shadowice.flocke.andotp.View.ItemTouchHelper.SimpleItemTouchHelperCallback
import org.shadowice.flocke.andotp.View.TagsAdapter
import org.shadowice.flocke.andotp.View.ThumbnailSelectionAdapter
import org.shadowice.flocke.andotp.View.AutoFillable.AutoFillableTextInputEditText
import org.shadowice.flocke.andotp.View.SimpleDoubleClickListener
import org.shadowice.flocke.andotp.Preferences.NumberPickerPreference
import org.shadowice.flocke.andotp.Preferences.PasswordEncryptedPreference
import org.shadowice.flocke.andotp.Dialogs.HideableDialog
import org.shadowice.flocke.andotp.Dialogs.PasswordEntryDialog
import org.shadowice.flocke.andotp.Dialogs.TagsDialog
import org.shadowice.flocke.andotp.Activities.AboutActivity
import org.shadowice.flocke.andotp.Activities.BaseActivity
import org.shadowice.flocke.andotp.Activities.PanicResponderActivity
import org.shadowice.flocke.andotp.Activities.SecureCaptureActivity
import org.shadowice.flocke.andotp.Fragments.AboutFragment
import org.shadowice.flocke.andotp.Fragments.BaseAboutFragment
import org.shadowice.flocke.andotp.Receivers.BackupBroadcastReceiver
import org.shadowice.flocke.andotp.Receivers.EncryptedBackupBroadcastReceiver
import org.shadowice.flocke.andotp.Tasks.EncryptedBackupWorker
import org.shadowice.flocke.andotp.Utilities.BackupAgent
import org.shadowice.flocke.andotp.Activities.AuthenticateActivity
import org.shadowice.flocke.andotp.Activities.BackupActivity
import org.shadowice.flocke.andotp.Activities.BackgroundTaskActivity
import org.shadowice.flocke.andotp.Activities.IntroScreenActivity
import org.shadowice.flocke.andotp.Activities.MainActivity
import org.shadowice.flocke.andotp.Activities.SettingsActivity
import org.shadowice.flocke.andotp.Database.EntryList
import org.shadowice.flocke.andotp.Dialogs.ManualEntryDialog
import org.shadowice.flocke.andotp.Preferences.CredentialsPreference
import org.shadowice.flocke.andotp.View.EntriesCardAdapter
import org.shadowice.flocke.andotp.View.EntryListPresentation
import org.shadowice.flocke.andotp.View.EntryViewHolder
import org.shadowice.flocke.andotp.R

import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.Filterable
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.cardview.widget.CardView
import me.zhanghai.android.materialprogressbar.MaterialProgressBar
import org.openintents.openpgp.util.OpenPgpAppPreference
import org.openintents.openpgp.util.OpenPgpKeyPreference
import com.heinrichreimersoftware.materialintro.app.IntroActivity
import javax.crypto.SecretKey
import java.io.ByteArrayOutputStream
import android.net.Uri
import android.database.Cursor
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.text.method.TransformationMethod
import android.util.Base64
import android.util.DisplayMetrics
import android.graphics.ColorFilter
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewStub
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.ViewPager
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.mikepenz.aboutlibraries.LibsFragment
import com.google.zxing.BarcodeFormat
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.qrcode.QRCodeReader

import android.content.res.Configuration
import android.content.res.Resources
import android.preference.FakeSharedPreferences
import android.preference.ListPreference
import android.preference.PreferenceManager

import org.json.JSONObject

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private var failures = 0

private fun check(name: String, body: () -> Unit) {
    try {
        body()
        println("PASS  $name")
    } catch (t: Throwable) {
        failures++
        println("FAIL  $name -> $t")
        // A one-line failure is enough for assertions, but a harness bug (a missing stub member, a
        // class that cannot be loaded) is only debuggable with the frame that raised it.
        if (System.getenv("SANDBOX_CHECK_TRACE") != null) t.printStackTrace()
    }
}

private fun expect(condition: Boolean, message: () -> String) {
    if (!condition) throw AssertionError(message())
}

private fun expectThrows(type: Class<out Throwable>, body: () -> Unit) {
    try {
        body()
    } catch (t: Throwable) {
        if (type.isInstance(t)) return
        throw AssertionError("Expected ${type.simpleName}, got ${t.javaClass.name}: ${t.message}")
    }
    throw AssertionError("Expected ${type.simpleName}, nothing was thrown")
}

// --- entry document helpers (the stored JSON format) -------------------------------------------

// The seed "1234567890": canonical, unpadded Base32; every fixture below uses it.
private const val ENTRY_SEED = "GEZDGNBVGY3TQOJQ"

/** The document the previous Java serializer produced for a TOTP entry, in field order. */
private const val STORED_ENTRY_JSON =
    "{\"secret\":\"IRQXGICTPFZXIZLNEBUXG5BAONUWG2DFOI======\",\"issuer\":\"\"," +
            "\"label\":\"5 von 5 Sterne\",\"digits\":6,\"type\":\"TOTP\",\"algorithm\":\"SHA1\"," +
            "\"thumbnail\":\"Default\",\"last_used\":0,\"used_frequency\":0,\"period\":30," +
            "\"tags\":[\"test1\",\"test2\"]}"

private fun entryDocument(secret: String = ENTRY_SEED, label: String = "synthetic"): JSONObject =
    JSONObject().put("secret", secret).put("label", label)

private fun entryValue(json: JSONObject): ValidatedEntry {
    val result = EntryJson.parse(json)
    expect(result is EntryJsonResult.Success, { "expected a parsed entry, got ${result}" })
    return (result as EntryJsonResult.Success).value
}

private fun entryError(json: JSONObject): EntryJsonError {
    val result = EntryJson.parse(json)
    expect(result is EntryJsonResult.Failure, { "expected a rejected entry, got Success" })
    return (result as EntryJsonResult.Failure).error
}

private fun ascii(text: String): ByteArray = text.toByteArray(StandardCharsets.US_ASCII)

// --- independent OTP implementation used to cross-check the converted class -------------------

private fun ownHash(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("Hmac$algorithm")
    mac.init(SecretKeySpec(key, "Hmac$algorithm"))
    return mac.doFinal(data)
}

private fun ownTruncated(algorithm: String, key: ByteArray, counter: Long): Int {
    val hash = ownHash(algorithm, key, ByteBuffer.allocate(8).putLong(counter).array())
    val offset = hash[hash.size - 1].toInt() and 0xF
    return ((hash[offset].toInt() and 0x7F) shl 0x18) or
            ((hash[offset + 1].toInt() and 0xFF) shl 0x10) or
            ((hash[offset + 2].toInt() and 0xFF) shl 0x08) or
            (hash[offset + 3].toInt() and 0xFF)
}

private fun ownTotp(algorithm: String, key: ByteArray, period: Int, time: Long, digits: Int, offset: Int): Int {
    val div = Math.pow(10.0, digits.toDouble()).toInt()
    return ownTruncated(algorithm, key, time / period + offset) % div
}

private fun ownSteam(algorithm: String, key: ByteArray, period: Int, time: Long, offset: Int): String {
    val alphabet = "23456789BCDFGHJKMNPQRTVWXY"
    var fullToken = ownTruncated(algorithm, key, time / period + offset)
    val builder = StringBuilder()
    repeat(5) {
        builder.append(alphabet[fullToken % alphabet.length])
        fullToken /= alphabet.length
    }
    return builder.toString()
}

private fun ownMotp(pin: String, secret: String, epoch: Long, offset: Int): String {
    val text = ((epoch / 10) + offset).toString() + secret + pin
    val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray())
    val hex = StringBuilder()
    for (b in digest) hex.append(String.format("%02x", b.toInt() and 0xFF))
    return hex.substring(0, 6)
}

// --- reflection helpers for the Java-visible API shapes ---------------------------------------

private fun methodSignature(method: Method): String {
    val parameters = method.parameterTypes.joinToString(",") { it.simpleName }
    val prefix = if (Modifier.isStatic(method.modifiers)) "static " else ""
    return "$prefix${method.returnType.simpleName} ${method.name}($parameters)"
}

/**
 * The public methods of a type, by signature.
 *
 * A method whose signature names a type this sandbox cannot load (a platform class without a stub)
 * cannot be resolved here. Such a method is skipped and asserted by name and arity instead; see
 * expectMethodArity. Utilities/Constants is compiled from Kotlin, so its enums load.
 */
private fun publicMethods(type: Class<*>): List<String> =
        type.declaredMethods.filter { Modifier.isPublic(it.modifiers) }
                .mapNotNull { method ->
                    try {
                        methodSignature(method)
                    } catch (error: NoClassDefFoundError) {
                        null
                    }
                }.sorted()

private val INT_TYPE: Class<*> = Int::class.javaPrimitiveType!!
private val LONG_TYPE: Class<*> = Long::class.javaPrimitiveType!!
private val FLOAT_TYPE: Class<*> = Float::class.javaPrimitiveType!!
private val BOOLEAN_TYPE: Class<*> = Boolean::class.javaPrimitiveType!!

private fun field(type: Class<*>, name: String): java.lang.reflect.Field? =
        type.declaredFields.firstOrNull { it.name == name }

private fun expectField(type: Class<*>, name: String, fieldType: Class<*>, isStatic: Boolean) {
    val found = field(type, name) ?: throw AssertionError("Missing field ${type.simpleName}.$name")
    expect(found.type == fieldType, { "${type.simpleName}.$name type ${found.type} != $fieldType" })
    expect(Modifier.isStatic(found.modifiers) == isStatic, { "${type.simpleName}.$name static mismatch" })
    if (isStatic) expect(Modifier.isFinal(found.modifiers), { "${type.simpleName}.$name is not final" })
}

private fun expectPublicFieldValue(type: Class<*>, name: String, expected: Any, fieldType: Class<*>, isStatic: Boolean) {
    expectField(type, name, fieldType, isStatic)
    val found = field(type, name)!!
    found.isAccessible = true
    val actual = found.get(if (Modifier.isStatic(found.modifiers)) null else null)
    expect(actual == expected, { "${type.simpleName}.$name = $actual, expected $expected" })
}

private fun expectMethod(type: Class<*>, signature: String) {
    expect(publicMethods(type).contains(signature), { "${type.simpleName} is missing [$signature]; has ${publicMethods(type)}" })
}

/** Asserts that a public method exists, without resolving its parameter types. */
private fun expectMethodArity(type: Class<*>, name: String, parameterCount: Int) {
    expectDeclaredMethod(type, name, parameterCount) { Modifier.isPublic(it) }
}

/** Asserts that a protected method exists, without resolving its parameter types. */
private fun expectProtectedMethodArity(type: Class<*>, name: String, parameterCount: Int) {
    expectDeclaredMethod(type, name, parameterCount) { Modifier.isProtected(it) }
}

private fun expectDeclaredMethod(type: Class<*>, name: String, parameterCount: Int,
                                 visibility: (Int) -> Boolean) {
    val found = type.declaredMethods.any {
        it.name == name && it.parameterCount == parameterCount && visibility(it.modifiers)
    }
    expect(found, { "${type.simpleName} is missing a $name() with $parameterCount parameter(s)" })
}

// --- fake scheduler for the timer controller --------------------------------------------------

private class FakeScheduler : ScreenTimerController.Scheduler {
    private val pending = LinkedHashMap<Runnable, Long>()
    private var now = 0L

    override fun postDelayed(runnable: Runnable, delayMillis: Long) {
        pending[runnable] = now + delayMillis
        lastPosted = runnable
    }

    override fun remove(runnable: Runnable) {
        pending.remove(runnable)
    }

    override fun elapsedRealtime(): Long = now

    /** Runs every callback that comes due, moving the virtual clock forward. */
    fun advance(millis: Long) {
        val target = now + millis
        while (true) {
            val next = pending.entries.filter { it.value <= target }.minByOrNull { it.value } ?: break
            pending.remove(next.key)
            now = next.value
            next.key.run()
        }
        now = target
    }

    /** Moves the clock without running callbacks (device asleep). */
    fun skip(millis: Long) {
        now += millis
    }

    var lastPosted: Runnable? = null
        private set

    fun runNext(): Boolean {
        val next = pending.entries.minByOrNull { it.value } ?: return false
        pending.remove(next.key)
        next.key.run()
        return true
    }

    fun scheduledCount(): Int = pending.size
}

/** Captures what a listener posts; the platform view would schedule it on its handler. */
private class FakeView : View() {
    val posted = ArrayList<Runnable>()
    var lastDelay = -1L
        private set

    override fun postDelayed(runnable: Runnable, delayMillis: Long): Boolean {
        posted.add(runnable)
        lastDelay = delayMillis
        return true
    }
}

private class RecordingListener : SimpleDoubleClickListener() {
    var singles = 0
    var doubles = 0

    override fun onSingleClick(v: View) {
        singles++
    }

    override fun onDoubleClick(v: View) {
        doubles++
    }
}

// --- fakes for the drag & drop, dialog and password helper checks -------------------------------

private class RecordingDragAdapter : ItemTouchHelperAdapter {
    val moves = ArrayList<Pair<Int, Int>>()

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        moves.add(Pair(fromPosition, toPosition))
        return true
    }
}

private class DragViewHolder(view: View, viewType: Int, position: Int) : RecyclerView.ViewHolder(view) {
    override val itemViewType: Int = viewType
    override val adapterPosition: Int = position
}

private class SelectableViewHolder(view: View) : RecyclerView.ViewHolder(view), ItemTouchHelperViewHolder {
    var selections = 0
    var clears = 0

    override fun onItemSelected() {
        selections++
    }

    override fun onItemClear() {
        clears++
    }
}

private class RecordingInputMethodManager : InputMethodManager() {
    val shownViews = ArrayList<View>()
    val hiddenTokens = ArrayList<IBinder?>()
    var toggles = 0

    override fun showSoftInput(view: View, flags: Int): Boolean {
        shownViews.add(view)
        return true
    }

    override fun hideSoftInputFromWindow(windowToken: IBinder?, flags: Int): Boolean {
        hiddenTokens.add(windowToken)
        return true
    }

    override fun toggleSoftInput(showFlags: Int, hideFlags: Int) {
        toggles++
    }
}

private class FakeUiContext : Context() {
    val imm = RecordingInputMethodManager()

    override fun getSystemService(name: String): Any? {
        return if (name == Context.INPUT_METHOD_SERVICE) imm else null
    }
}

private class FakePasswordInput : TextInputEditText() {
    val selections = ArrayList<Int>()

    override var transformationMethod: TransformationMethod? = null
    override val selectionEnd: Int = 7

    override fun setSelection(index: Int) {
        selections.add(index)
    }
}

/** Fresh recording widgets for one password dialog construction, served through the lookup hook. */
private class FakePasswordDialogWidgets {
    val layout = TextInputLayout()
    val input = TextInputEditText()
    val confirm = android.widget.EditText()
    val warning = android.widget.TextView()
    val ok = android.widget.Button()
    val cancel = android.widget.Button()

    init {
        ok.id = R.id.buttonOk
        cancel.id = R.id.buttonCancel
        AppCompatDialog.viewProvider = { id ->
            when (id) {
                R.id.passwordInputLayout -> layout
                R.id.passwordInput -> input
                R.id.passwordConfirm -> confirm
                R.id.tooShortWarning -> warning
                R.id.buttonOk -> ok
                R.id.buttonCancel -> cancel
                else -> null
            }
        }
    }
}

// --- fakes for the background runner checks ----------------------------------------------------

/** Waits until the worker thread has queued its delivery on the fake main loop. */
private fun awaitQueuedDelivery() {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (Handler.posted.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
    expect(!Handler.posted.isEmpty(), { "the runner never queued a delivery" })
}

/** Synchronously runs what the runner posted until the fake main queue stays empty. */
private fun drainMainThreadDeliveries() {
    var rounds = 0
    while (rounds++ < 50) {
        var ran = false
        while (true) {
            val runnable = Handler.posted.poll() ?: break
            ran = true
            runnable.run()
        }
        if (!ran) break
    }
}

private class BlockingRunnerTask(fallback: String) : UiBasedBackgroundTask<String>(fallback) {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val discarded = AtomicInteger(0)
    val discardedOnce = CountDownLatch(1)
    val cleanupRuns = AtomicInteger(0)

    override fun doInBackground(): String {
        started.countDown()
        try {
            release.await(5, TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            // Unwind after the interrupt; the late value must still be disposed, like on device.
        }
        return "late-value"
    }

    override fun discardResult(result: String) {
        if (discarded.incrementAndGet() == 1) discardedOnce.countDown()
    }

    override fun onCancellation() {
        cleanupRuns.incrementAndGet()
    }
}

private class FailingRunnerTask(fallback: String, private val failure: IllegalStateException) : UiBasedBackgroundTask<String>(fallback) {
    override fun doInBackground(): String = throw failure
}

private class NullRunnerTask(fallback: String?) : UiBasedBackgroundTask<String?>(fallback) {
    override fun doInBackground(): String? = null
}

/** A task that finishes at once and records that it ran and that it was canceled. */
private class RecordingTask : UiBasedBackgroundTask<String>("failed") {
    /** Whether the worker thread ran the task. */
    @Volatile
    var started: Boolean = false

    /** Whether the task was canceled. */
    @Volatile
    var canceled: Boolean = false

    override fun doInBackground(): String {
        started = true
        return "done"
    }

    override fun onCancellation() {
        canceled = true
    }
}

/** A task that runs until it is canceled, and records that it ran and that it was canceled. */
private class BlockingRecordingTask : UiBasedBackgroundTask<String>("failed") {
    /** Whether the worker thread ran the task. */
    @Volatile
    var started: Boolean = false

    /** Whether the task was canceled. */
    @Volatile
    var canceled: Boolean = false

    override fun doInBackground(): String {
        started = true
        try {
            // The cancellation interrupts the worker.
            Thread.sleep(5000)
        } catch (interrupted: InterruptedException) {
            // Unwind after the interrupt.
        }
        return "done"
    }

    override fun onCancellation() {
        canceled = true
    }
}

/** Recording preference over the fake superclass, not a real PreferenceManager/persistence test. */
private class SandboxNumberPreference(context: Context) : NumberPickerPreference(context, null) {
    val persisted = mutableListOf<Int>()
    var restoredValue: Int? = null
    var persistedFallback: Int? = null
    var allowChange = true
    val proposed = mutableListOf<Int>()
    lateinit var widget: android.widget.NumberPicker

    override fun persistInt(value: Int): Boolean { persisted.add(value); return true }
    override fun getPersistedInt(defaultValue: Int): Int {
        persistedFallback = defaultValue
        return restoredValue ?: defaultValue
    }
    override fun callChangeListener(value: Any?): Boolean {
        expect(widget.focusCleared, { "picker text must be committed before listener dispatch" })
        proposed.add(value as Int)
        return allowChange
    }
    fun createAndBind(): android.widget.FrameLayout {
        val root = onCreateDialogView() as android.widget.FrameLayout
        widget = root.getChildAt(0) as android.widget.NumberPicker
        onBindDialogView(root)
        return root
    }
    fun close(positive: Boolean) = onDialogClosed(positive)
    fun initialize(restore: Boolean, value: Any?) = onSetInitialValue(restore, value)
    fun defaultValue(array: android.content.res.TypedArray, index: Int): Any = onGetDefaultValue(array, index)
}


// --- fake document provider for the storage helper -------------------------------------------

/** A `content:` URI built through the replicated `Uri.Builder` (the stub's `parse` stays inert). */
private fun contentUri(vararg segments: String): Uri {
    val builder = Uri.Builder().scheme("content").authority("org.example.documents")
    for (segment in segments) builder.appendPath(segment)
    return builder.build()
}

/** A `file:` URI over an absolute path, one segment at a time so `..` survives unnormalized. */
private fun fileUri(path: String): Uri {
    val builder = Uri.Builder().scheme("file")
    for (segment in path.split('/').filter { it.isNotEmpty() }) builder.appendPath(segment)
    return builder.build()
}

/** Serves one row (or none) of document metadata and records that it was closed. */
private class FakeCursor(private val displayName: String?, private val hasRow: Boolean = true,
                         private val column: Int = 0) : Cursor {
    var closed = false
    override fun moveToFirst(): Boolean = hasRow
    override fun moveToNext(): Boolean = false
    override fun getColumnIndex(columnName: String?): Int =
        if (columnName == OpenableColumns.DISPLAY_NAME) column else -1
    override fun getColumnIndexOrThrow(columnName: String?): Int = getColumnIndex(columnName)
    override fun getString(columnIndex: Int): String? = displayName
    override fun getInt(columnIndex: Int): Int = 0
    override fun isNull(columnIndex: Int): Boolean = displayName == null
    override fun close() {
        closed = true
    }
}

/** Records every provider call; writes go to [target] through the replicated descriptor. */
private class FakeResolver : android.content.ContentResolver() {
    val calls = ArrayList<String>()
    var target: File? = null
    var descriptor: ParcelFileDescriptor? = null
    var failure: RuntimeException? = null
    var cursor: Cursor? = null
    var projection: List<String>? = null
    var input: InputStream? = null

    override fun openFileDescriptor(uri: Uri?, mode: String?): ParcelFileDescriptor? {
        calls.add("open $mode")
        failure?.let { throw it }
        val file = target ?: return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode)).also { descriptor = it }
    }

    override fun query(uri: Uri?, projection: Array<String>?, selection: String?,
                       selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        calls.add("query")
        this.projection = projection?.toList()
        failure?.let { throw it }
        return cursor
    }

    override fun openInputStream(uri: Uri?): InputStream? {
        calls.add("read")
        failure?.let { throw it }
        return input
    }
}

private class FakeStorageContext(val resolver: FakeResolver, private val cache: File) : Context() {
    override fun getContentResolver(): android.content.ContentResolver = resolver
    override fun getCacheDir(): File = cache
}

/** Answers the permission checks from a script and counts how often it was asked. */
private class ScriptedPermission(private val refuseAt: Int = -1) : java.util.function.BooleanSupplier {
    var asked = 0
    override fun getAsBoolean(): Boolean {
        asked++
        return asked != refuseAt
    }
}

/** A stream that reports whether it was closed. */
private class ClosingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
    var closed = false
    override fun close() {
        closed = true
        super.close()
    }
}

/** Produces [size] zero bytes lazily, so an oversized document needs no large fixture. */
private class ZeroStream(private val size: Long) : InputStream() {
    private var produced = 0L
    override fun read(): Int = if (produced++ < size) 0 else -1
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (produced >= size) return -1
        val count = minOf(length.toLong(), size - produced).toInt()
        java.util.Arrays.fill(buffer, offset, offset + count, 0.toByte())
        produced += count
        return count
    }
}

// --- crypto/keystore helper fixtures (fifteenth batch) ------------------------------------------

/**
 * The GCM nonce length and the backup iteration bounds. Constants.kt holds the real values as
 * `internal const val`s; these are an independent expectation, kept equal by the Python guards.
 */
private const val ENCRYPTION_IV_BYTES = 12
private const val BACKUP_ITERATIONS_MIN = 140000
private const val BACKUP_ITERATIONS_MAX = 160000
private const val AUTH_ITERATIONS_MIN = 30000

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }

/** Independent PBKDF2-HMAC-SHA1 derivation used to cross-check the converted credential factory. */
private fun ownPbkdf2(password: String, salt: ByteArray, iterations: Int, lengthBytes: Int): ByteArray {
    val spec = PBEKeySpec(password.toCharArray(), salt, iterations, lengthBytes * 8)
    try {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

/**
 * The Java callers compile against the static forwarder, so assert exactly that one. Candidates are
 * filtered by name and arity before their parameter types are read, so overloads that mention a
 * type without a class file (a platform class without a stub) cannot break the lookup for the ones
 * that do load.
 */
private fun staticMethod(type: Class<*>, name: String, parameters: List<Class<*>>): Method {
    for (candidate in type.declaredMethods.filter {
        it.name == name && Modifier.isStatic(it.modifiers) && it.parameterCount == parameters.size
    }) {
        val types = try { candidate.parameterTypes.toList() } catch (_: Throwable) { null } ?: continue
        if (types == parameters) return candidate
    }
    throw AssertionError("${type.simpleName}.$name(${parameters.joinToString(",") { it.simpleName }}) is missing")
}

/**
 * The Java-visible surface of a compiled class, read from its own class file.
 *
 * `Class.getDeclaredMethods()` resolves each declared signature eagerly, so a class whose signature
 * names a type this sandbox cannot load cannot be described by reflection: the JVM throws while
 * collecting the members, before a name is compared. Parsing the class file reports the same names,
 * descriptors and access flags without resolving a single referenced type, which is exactly what a
 * Java caller compiles against.
 */
private class CompiledClass(bytes: ByteArray) {
    class Member(val name: String, val descriptor: String, val flags: Int) {
        /** `getBoolean(IZ)Z` for a method, `context Landroid/content/Context;` for a field. */
        val signature: String get() = if (descriptor.startsWith("(")) name + descriptor else "$name $descriptor"
        val isPublic: Boolean get() = Modifier.isPublic(flags)
        val isPrivate: Boolean get() = Modifier.isPrivate(flags)
        val isStatic: Boolean get() = Modifier.isStatic(flags)
        val isFinal: Boolean get() = Modifier.isFinal(flags)

        /** Kotlin's own artefacts: `..$lambda$0`, accessors of a companion holder. */
        val isSynthetic: Boolean get() = name.contains('$')
    }

    private val input = DataInputStream(ByteArrayInputStream(bytes))
    private val utf8 = HashMap<Int, String>()
    private val classNames = HashMap<Int, Int>()

    val flags: Int
    val name: String
    val superName: String
    val fields: List<Member>
    val methods: List<Member>

    init {
        if (input.readInt() != 0xCAFEBABE.toInt()) throw AssertionError("not a class file")
        input.readUnsignedShort()                       // minor_version
        input.readUnsignedShort()                       // major_version
        val poolSize = input.readUnsignedShort()
        var index = 1
        while (index < poolSize) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf8[index] = readUtf8()
                3, 4 -> skip(4)
                5, 6 -> { skip(8); index++ }            // eight-byte constants occupy two slots
                7 -> classNames[index] = input.readUnsignedShort()
                8, 16, 19, 20 -> skip(2)
                9, 10, 11, 12, 17, 18 -> skip(4)
                15 -> skip(3)
                else -> throw AssertionError("unknown constant pool tag $tag")
            }
            index++
        }
        flags = input.readUnsignedShort()
        name = className(input.readUnsignedShort())
        superName = className(input.readUnsignedShort())
        skip(input.readUnsignedShort() * 2)             // interfaces
        fields = readMembers()
        methods = readMembers()
    }

    val publicMembers: List<Member> get() = methods.filter { it.isPublic && !it.isSynthetic }
    val privateMembers: List<Member> get() = methods.filter { it.isPrivate && !it.isSynthetic }

    private fun readMembers(): List<Member> {
        val members = ArrayList<Member>()
        repeat(input.readUnsignedShort()) {
            val accessFlags = input.readUnsignedShort()
            val memberName = utf8.getValue(input.readUnsignedShort())
            val descriptor = utf8.getValue(input.readUnsignedShort())
            readAttributes()
            members.add(Member(memberName, descriptor, accessFlags))
        }
        return members
    }

    /** Attributes (code, exceptions, …) are not needed, but their lengths have to be walked over. */
    private fun readAttributes() {
        repeat(input.readUnsignedShort()) {
            input.readUnsignedShort()
            skip(input.readInt())
        }
    }

    private fun readUtf8(): String {
        val bytes = ByteArray(input.readUnsignedShort())
        input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun className(index: Int): String = utf8.getValue(classNames.getValue(index))

    private fun skip(count: Int) {
        input.skipBytes(count)
    }
}

/** Reads the class file the class loader holds for [type]. */
private fun compiledClass(type: Class<*>): CompiledClass {
    val entry = type.name.substringAfterLast('.') + ".class"
    val stream = type.getResourceAsStream(entry)
            ?: throw AssertionError("no class file for ${type.name}")
    return stream.use { CompiledClass(it.readBytes()) }
}

private fun expectThrowsClause(type: Class<*>, name: String, parameters: List<Class<*>>, vararg declared: Class<out Throwable>) {
    val method = staticMethod(type, name, parameters)
    expect(method.exceptionTypes.toSet() == declared.toSet(),
            { "${type.simpleName}.$name throws ${method.exceptionTypes.toList()}, expected ${declared.toList()}" })
}

// --- converted settings facade -------------------------------------------------------------------

/**
 * The preference-key and default-value view for the converted `Settings`, which addresses everything
 * through string resources: keys are distinct synthetic strings per id and the *default* strings the
 * code parses into enums carry the values `app/src/main/res/values/settings.xml` declares, so an
 * empty store behaves like a fresh install. Every lookup is recorded so a check can assert which
 * resource an accessor resolved. Those literals are pinned against the XML by
 * scripts/tests/test_kotlin_settings_conversion.py.
 */
private fun settingsDefaultValue(id: Int): String? = when (id) {
    R.string.settings_default_encryption -> "keystore"
    R.string.settings_default_lang -> "system"
    R.string.settings_default_theme_mode -> "auto"
    R.string.settings_default_theme -> "light"
    R.string.settings_default_label_display -> "truncate"
    R.string.settings_default_card_layout -> "default"
    R.string.settings_default_tap_single -> "nothing"
    R.string.settings_default_tap_double -> "nothing"
    R.string.settings_default_thumbnail_size -> "46dp"
    R.string.settings_default_split_group_size -> "3"
    R.string.settings_default_tag_functionality -> "or"
    R.string.settings_default_auto_backup_password_enc -> "off"
    else -> null
}

private class SettingsResources(
    private val consulted: MutableList<Int>,
    private val config: Configuration
) : Resources() {

    override fun getInteger(id: Int): Int = when (id) {
        // settings.xml: settings_default_tap_to_reveal_timeout / settings_default_label_size
        R.integer.settings_default_tap_to_reveal_timeout -> 30
        R.integer.settings_default_label_size -> 18
        else -> id
    }

    override fun getString(id: Int): String {
        consulted.add(id)
        return settingsDefaultValue(id) ?: "res:" + id
    }

    override fun getStringArray(id: Int): Array<String> =
        if (id == R.array.settings_defaults_search_includes) arrayOf("label", "issuer") else emptyArray()

    override val displayMetrics: DisplayMetrics get() = METRICS

    override val configuration: Configuration get() = config

    companion object {
        /** 2x density, so the "46dp" default resolves to 92 px and the checks can see the formula run. */
        val METRICS: DisplayMetrics = DisplayMetrics().apply {
            density = 2.0f
            scaledDensity = 2.0f
            xdpi = 320f
            ydpi = 320f
        }
    }
}

/** Context whose resource strings are the preference keys themselves, plus the default values above. */
private open class SettingsContext : Context() {
    val consulted = ArrayList<Int>()
    val config = Configuration()
    private val settingsResources = SettingsResources(consulted, config)

    override val resources: Resources get() = settingsResources

    override fun getString(resId: Int): String {
        consulted.add(resId)
        return settingsDefaultValue(resId) ?: settingsKey(resId)
    }

    /**
     * The formatted overload keeps the arguments, so a check can see what a content description
     * or a hint was built from (the platform substitutes them into the string resource).
     */
    override fun getString(resId: Int, vararg formatArgs: Any?): String {
        consulted.add(resId)
        val base = settingsDefaultValue(resId) ?: settingsKey(resId)
        return if (formatArgs.isEmpty()) base else base + " " + formatArgs.joinToString(" ")
    }
}

/**
 * Context for the converted components: it reads the settings like [SettingsContext] and hands
 * out a recording notification manager, so a check can see which message was posted.
 */
private class ComponentContext : SettingsContext() {
    /** The notifications that were posted through this context. */
    val notifications = NotificationManager()

    override fun getSystemService(name: String): Any? =
        if (name == Context.NOTIFICATION_SERVICE) notifications else null
}

/**
 * The converted base activity, driven by the checks: it reads the settings like
 * [SettingsContext] and records what the base class delivered.
 */
private class ScreenActivity : BaseActivity() {
    /** How often the vault lock was delivered to the activity. */
    var lockEvents = 0

    /** How often the screen-off callback was delivered. */
    var screenOffEvents = 0

    /** Whether the activity requires an open vault session. */
    var requiresSession: Boolean = false

    /** Whether the activity is closed when the screen turns off. */
    var destroyOnScreenOff: Boolean = true

    /** The settings of the activity are read from the in-memory store. */
    override fun getString(resId: Int): String = settingsKey(resId)

    fun create() {
        onCreate(null)
    }

    fun resume() {
        onResume()
    }

    fun destroy() {
        onDestroy()
    }

    /** Runs the deliveries that were posted to the UI thread. */
    fun drainUiThread() {
        while (uiThreadDeliveries.isNotEmpty()) uiThreadDeliveries.removeAt(0).run()
    }

    override fun requiresVaultSession(): Boolean = requiresSession

    override fun onVaultLocked() {
        lockEvents++
    }

    override fun shouldDestroyOnScreenOff(): Boolean = destroyOnScreenOff
}

/** The preference key the fake context maps a resource id to. */
private fun settingsKey(resId: Int): String = "key:" + resId

/**
 * The key a platform fragment uses for a preference: the fragment stub resolves the resource id
 * to "res:<id>", so the checks address the preferences of a fragment the same way.
 */
private fun fragmentKey(resId: Int): String = "res:" + resId

/**
 * The converted panic responder, driven by the checks: it answers with a controllable intent and
 * reads the settings like [SettingsContext].
 */
private class PanicActivity : PanicResponderActivity() {
    /** The intent the activity was started with. */
    var launchIntent: Intent? = null

    override fun getIntent(): Intent? = launchIntent

    override fun getString(resId: Int): String = settingsKey(resId)

    fun create() {
        onCreate(null)
    }
}

/**
 * The converted QR scanner activity, driven by the checks: it reads the settings like
 * [SettingsContext] and hands out its window, so the checks can see the flags.
 */
private class ScannerActivity : SecureCaptureActivity() {
    /** The resources the activity reads; the checks read the applied locale from them. */
    val config = Configuration()

    override fun getString(resId: Int): String = settingsKey(resId)

    override val resources: Resources get() = ScannerResources(config)

    fun initialize(): DecoratedBarcodeView = initializeContent()
}

/** Scanner resources keep a separate configuration; the sandbox records localized wrapping on Context. */
private class ScannerResources(private val config: Configuration) : Resources() {
    override val configuration: Configuration get() = config
}

/**
 * Runs the block with the vault transaction poisoned. The converted construction runs
 * `DatabaseHelper.recoverPendingDatabase`, and the pending-file transaction it would otherwise
 * touch refuses to run here (FileHelper over the AtomicFile stand-in), so a dropped short-circuit
 * would fail loudly.
 */
private fun <T> withPoisonedTransaction(block: () -> T): T {
    val poison = DatabaseHelper::class.java.getDeclaredField("transactionPoisoned")
    poison.isAccessible = true
    // The flag is restored, not cleared: a nested block (a settings fixture inside a component
    // check) must not un-poison the block around it.
    val previous = poison.getBoolean(null)
    poison.setBoolean(null, true)
    try {
        return block()
    } finally {
        poison.setBoolean(null, previous)
    }
}

/** One clean in-memory preference store plus the `Settings` instance reading and writing it. */
private class SettingsFixture(fixtureContext: SettingsContext = SettingsContext()) {
    val context: SettingsContext = fixtureContext
    val settings: Settings
    val store: FakeSharedPreferences

    init {
        PreferenceManager.reset()
        settings = withPoisonedTransaction { Settings(context) }
        store = PreferenceManager.getDefaultSharedPreferences(context) as FakeSharedPreferences
    }

    fun seed(resId: Int, value: String) {
        store.edit().putString(settingsKey(resId), value).commit()
    }

    fun seed(resId: Int, value: Boolean) {
        store.edit().putBoolean(settingsKey(resId), value).commit()
    }

    fun stored(resId: Int): String? = store.getString(settingsKey(resId), null)

    fun storedSet(resId: Int): MutableSet<String> = HashSet(store.getStringSet(settingsKey(resId), emptySet())!!)
}

// --- fakes for the about-screen checks ----------------------------------------------------------

/** An inflated about layout: scripted children per id, so lookups return the check's widgets. */
private class AboutRoot : View() {
    val children = HashMap<Int, View>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : View> findViewById(id: Int): T? = children[id] as T?
}

/** The intro wizard, with the protected lifecycle method the platform calls for the check. */
private class IntroWizard : IntroScreenActivity() {
    fun createWizard() {
        onCreate(null)
    }
}

/** An inflated entry card: scripted children per id, so the card binding can be observed. */
private class CardRoot : View() {
    val children = HashMap<Int, View>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : View> findViewById(id: Int): T? = children[id] as T?
}

/** A layout stub that inflates the check's scripted root instead of a real layout. */
private class ScriptedViewStub(private val root: View) : ViewStub() {
    var inflations = 0
        private set

    override fun inflate(): View {
        inflations++
        return root
    }
}

/** Resources that serve one scripted raw library document instead of a real raw resource. */
private class AboutLibResources(private val libJson: String) : Resources() {
    override fun openRawResource(id: Int): InputStream =
        ByteArrayInputStream(libJson.toByteArray(Charsets.UTF_8))
}

/**
 * The host of the about fragment: mapped strings, a recording clipboard and recording (or
 * refusing) activity starts, like [SettingsContext] plus the clipboard service.
 */
private class AboutHost : FragmentActivity() {
    val started = ArrayList<Intent>()
    val clipboard = ClipboardManager()
    /** When set, `startActivity` throws this instead of recording the start. */
    var startFailure: RuntimeException? = null

    override fun getString(resId: Int): String = settingsKey(resId)

    override fun getSystemService(name: String): Any? =
        if (name == Context.CLIPBOARD_SERVICE) clipboard else null

    override fun startActivity(intent: Intent) {
        val failure = startFailure
        if (failure != null) throw failure
        started.add(intent)
    }
}

/**
 * The converted about activity, driven by the checks: it serves the scripted toolbar/layout
 * stub/pager children, the mapped resource strings and a scripted raw library list.
 */
private class AboutScreenActivity(private val libJson: String) : AboutActivity() {
    val toolbar = Toolbar(this)
    val root = AboutRoot()
    val pager = ViewPager()
    val tabs = TabLayout(this)
    val stub = ScriptedViewStub(root)

    init {
        root.children[R.id.viewPager] = pager
        root.children[R.id.tabLayout] = tabs
    }

    override fun getString(resId: Int): String = settingsKey(resId)

    override val resources: Resources get() = AboutLibResources(libJson)

    @Suppress("UNCHECKED_CAST")
    override fun <T : View> findViewById(id: Int): T? = when (id) {
        R.id.container_toolbar -> toolbar as T?
        R.id.container_stub -> stub as T?
        else -> null
    }

    fun create() {
        onCreate(null)
    }

    fun up(): Boolean = onSupportNavigateUp()

    fun back() {
        onBackPressed()
    }
}

/** One about page with its scripted layout children and the host it runs against. */
private class AboutPageFixture {
    val host = AboutHost()
    val root = AboutRoot()
    val versionLayout = LinearLayout(host)
    val license = LinearLayout(host)
    val changelog = LinearLayout(host)
    val source = LinearLayout(host)
    val faq = LinearLayout(host)
    val author1 = LinearLayout(host)
    val author2 = LinearLayout(host)
    val authorOriginal = LinearLayout(host)
    val contributors = LinearLayout(host)
    val support = LinearLayout(host)
    val version = TextView()
    val originalExtra = TextView()
    val author1Content = LinearLayout(host)
    val author2Content = LinearLayout(host)
    /** The tint filters the image rows received; recorded instead of drawn. */
    val imageFilters = ArrayList<ColorFilter?>()

    init {
        root.children[R.id.about_layout_version] = versionLayout
        root.children[R.id.about_text_version] = version
        root.children[R.id.about_layout_license] = license
        root.children[R.id.about_layout_changelog] = changelog
        root.children[R.id.about_layout_source] = source
        root.children[R.id.about_layout_faq] = faq
        root.children[R.id.aboutLayoutAuthor1] = author1
        root.children[R.id.aboutLayoutAuthor2] = author2
        root.children[R.id.aboutLayoutOriginalAuthor] = authorOriginal
        root.children[R.id.about_author_original_extra] = originalExtra
        root.children[R.id.about_layout_contributors] = contributors
        root.children[R.id.about_layout_support] = support
        root.children[R.id.aboutLayoutAuthor1Content] = author1Content
        root.children[R.id.aboutLayoutAuthor2Content] = author2Content
        for (id in BaseAboutFragment.imageResources) {
            val image = ImageView(host)
            image.setImageDrawable(object : Drawable() {
                override fun setColorFilter(filter: ColorFilter?) {
                    imageFilters.add(filter)
                }
            })
            root.children[id] = image
        }
    }

    /** Inflates the fragment's layout with the scripted children against the scripted host. */
    fun inflate(fragment: BaseAboutFragment): View {
        fragment.host = host
        LayoutInflater.layoutProvider = { res, _, _ ->
            if (res == R.layout.fragment_about) root else View()
        }
        try {
            return withPoisonedTransaction {
                fragment.onCreateView(LayoutInflater.from(host), null, null)
            }
        } finally {
            LayoutInflater.layoutProvider = null
        }
    }
}

/** Resets the static and global state the about screens accumulate between checks. */
private fun resetAboutState() {
    BaseAboutFragment.lastTap = 0
    BaseAboutFragment.taps = 0
    BaseAboutFragment.currentToast = null
    Toast.shown.clear()
    Toast.shownTexts.clear()
    Toast.cancels = 0
    AlertDialog.Builder.created.clear()
    AlertDialog.Builder.lastCreated = null
    PreferenceManager.reset()
    PackageManager.missingPackage = null
    PackageManager.packageInfo = PackageInfo().apply { versionName = "9.9.9-test" }
}

fun main() {
    val keySha1 = ascii("12345678901234567890")
    val keySha256 = ascii("12345678901234567890123456789012")
    val keySha512 = ascii("1234567890123456789012345678901234567890123456789012345678901234")

    check("RFC 6238 TOTP vectors (SHA-1/256/512, eight digits)") {
        val vectors = listOf(
                59L to Triple(94287082, 46119246, 90693936),
                1111111109L to Triple(7081804, 68084774, 25091201),
                1111111111L to Triple(14050471, 67062674, 99943326),
                1234567890L to Triple(89005924, 91819424, 93441116),
                2000000000L to Triple(69279037, 90698825, 38618901),
                20000000000L to Triple(65353130, 77737706, 47863826)
        )
        for ((time, expected) in vectors) {
            val period = TokenCalculator.TOTP_DEFAULT_PERIOD
            expect(TokenCalculator.TOTP_RFC6238(keySha1, period, time, 8, TokenCalculator.HashAlgorithm.SHA1) == expected.first,
                    { "SHA1 @$time" })
            expect(TokenCalculator.TOTP_RFC6238(keySha256, period, time, 8, TokenCalculator.HashAlgorithm.SHA256) == expected.second,
                    { "SHA256 @$time" })
            expect(TokenCalculator.TOTP_RFC6238(keySha512, period, time, 8, TokenCalculator.HashAlgorithm.SHA512) == expected.third,
                    { "SHA512 @$time" })
        }
    }

    check("RFC 4226 HOTP vectors") {
        val expected = listOf("755224", "287082", "359152", "969429", "338314",
                "254676", "287922", "162583", "399871", "520489")
        expected.forEachIndexed { counter, token ->
            expect(TokenCalculator.HOTP(keySha1, counter.toLong(), 6, TokenCalculator.HashAlgorithm.SHA1) == token,
                    { "HOTP counter $counter" })
        }
    }

    check("TOTP/HOTP agree with an independent HMAC implementation") {
        val algorithms = mapOf(
                TokenCalculator.HashAlgorithm.SHA1 to "SHA1",
                TokenCalculator.HashAlgorithm.SHA256 to "SHA256",
                TokenCalculator.HashAlgorithm.SHA512 to "SHA512")
        for ((algorithm, name) in algorithms) {
            val key = when (algorithm) {
                TokenCalculator.HashAlgorithm.SHA1 -> keySha1
                TokenCalculator.HashAlgorithm.SHA256 -> keySha256
                else -> keySha512
            }
            for (time in listOf(0L, 1L, 59L, 1111111109L, 1234567890L, 2000000000L)) {
                for (period in listOf(1, 30, 60, 86400)) {
                    expect(TokenCalculator.TOTP_RFC6238(key, period, time, 8, algorithm) == ownTotp(name, key, period, time, 8, 0),
                            { "$name period $period time $time" })
                }
                expect(TokenCalculator.TOTP_RFC6238(key, 30, time, 8, algorithm, 1) == ownTotp(name, key, 30, time, 8, 1),
                        { "$name offset step at $time" })
                expect(TokenCalculator.TOTP_RFC6238(key, 30, time, 8, algorithm, -1) == ownTotp(name, key, 30, time, 8, -1),
                        { "$name negative offset at $time" })
            }
        }
    }

    check("token strings keep leading zeros and reject unsafe parameters") {
        expect(Tools.formatTokenString(7081804, 8) == "07081804", { Tools.formatTokenString(7081804, 8) })
        expect(Tools.formatTokenString(0, 6) == "000000", { Tools.formatTokenString(0, 6) })
        val padded = TokenCalculator.TOTP_RFC6238(keySha1, 30, 1111111109L, 8, TokenCalculator.HashAlgorithm.SHA1)
        expect(Tools.formatTokenString(padded, 8) == "07081804", { Tools.formatTokenString(padded, 8) })
        expectThrows(IllegalArgumentException::class.java) {
            TokenCalculator.TOTP_RFC6238(keySha1, 0, 59L, 6, TokenCalculator.HashAlgorithm.SHA1)
        }
        expectThrows(IllegalArgumentException::class.java) {
            TokenCalculator.HOTP(keySha1, Long.MAX_VALUE, 6, TokenCalculator.HashAlgorithm.SHA1)
        }
        expectThrows(IllegalArgumentException::class.java) {
            TokenCalculator.HOTP(keySha1, 0L, 10, TokenCalculator.HashAlgorithm.SHA1)
        }
    }

    check("Steam tokens match the alphabet mapping of the same truncation") {
        val before = System.currentTimeMillis() / 1000
        val token = TokenCalculator.TOTP_Steam(keySha1, TokenCalculator.TOTP_DEFAULT_PERIOD,
                TokenCalculator.STEAM_DEFAULT_DIGITS, TokenCalculator.HashAlgorithm.SHA1, 0)
        val after = System.currentTimeMillis() / 1000
        expect(token.length == 5, { token })
        expect(token.all { it in "23456789BCDFGHJKMNPQRTVWXY" }, { token })
        val expected = setOf(
                ownSteam("SHA1", keySha1, 30, before, 0),
                ownSteam("SHA1", keySha1, 30, after, 0))
        expect(expected.contains(token), { "Steam token $token not in $expected" })
    }

    check("mOTP matches an independent MD5 computation") {
        val epoch = 1697055600L
        expect(TokenCalculator.MOTP("1234", "0123456789abcdef", epoch, 0) == ownMotp("1234", "0123456789abcdef", epoch, 0),
                { TokenCalculator.MOTP("1234", "0123456789abcdef", epoch, 0) })
        expect(TokenCalculator.MOTP("1234", "0123456789abcdef", epoch, 1) == ownMotp("1234", "0123456789abcdef", epoch, 1),
                { TokenCalculator.MOTP("1234", "0123456789abcdef", epoch, 1) })
        expect(TokenCalculator.MOTP("p", "s", epoch, 0).length == 6, { TokenCalculator.MOTP("p", "s", epoch, 0) })
    }

    check("input policy keeps the documented bounds") {
        expect(!InputPolicy.validImage(0, 100), { "zero width" })
        expect(!InputPolicy.validImage(-1, -1), { "negative" })
        expect(!InputPolicy.validImage(Int.MAX_VALUE, Int.MAX_VALUE), { "overflow" })
        expect(!InputPolicy.validImage(8192, 8192), { "pixel ceiling" })
        expect(InputPolicy.validImage(2000, 2000), { "accepted" })
        expect(!InputPolicy.validImage(2001, 2000), { "over pixel ceiling" })

        val deep = StringBuilder()
        repeat(InputPolicy.MAX_JSON_DEPTH + 1) { deep.append('[') }
        expectThrows(IllegalArgumentException::class.java) { InputPolicy.jsonDepth(deep.toString()) }
        InputPolicy.jsonDepth("""[{"label":"brackets [] {} and \"escaped quote"}]""")
        expectThrows(IllegalArgumentException::class.java) { InputPolicy.jsonDepth("[{}") }
        expectThrows(IllegalArgumentException::class.java) { InputPolicy.jsonDepth("{'single':1}") }
        expectThrows(IllegalArgumentException::class.java) { InputPolicy.jsonDepth("// comment") }

        expectThrows(IllegalArgumentException::class.java) { InputPolicy.tokenParameters(0, 6, 0) }
        expectThrows(IllegalArgumentException::class.java) { InputPolicy.tokenParameters(86401, 6, 0) }
        expectThrows(IllegalArgumentException::class.java) { InputPolicy.tokenParameters(30, 10, 0) }
        expectThrows(IllegalArgumentException::class.java) { InputPolicy.tokenParameters(30, 6, -1) }
        expectThrows(IllegalArgumentException::class.java) { InputPolicy.tokenParameters(30, 6, Long.MAX_VALUE) }
        InputPolicy.tokenParameters(1, 1, 0)
        InputPolicy.tokenParameters(86400, 9, Long.MAX_VALUE - 1)

        expect(BoundedInputReader.MAX_DOCUMENT_BYTES == 16 * 1024 * 1024, { "document byte limit" })
    }

    check("entry documents keep the stored format, field order and defaults") {
        val parsed = entryValue(JSONObject(STORED_ENTRY_JSON))
        try {
            expect(parsed.kind == OtpKind.TOTP, { "kind" })
            expect(parsed.label == "5 von 5 Sterne", { "label" })
            expect(parsed.issuer.isEmpty(), { "issuer" })
            expect(parsed.period == 30 && parsed.digits == 6 && parsed.counter == 0L, { "OTP parameters" })
            expect(parsed.algorithm == OtpHashAlgorithm.SHA1, { "algorithm" })
            expect(parsed.thumbnailName == "Default", { "thumbnail" })
            expect(parsed.tags == listOf("test1", "test2"), { "tags" })
            expect(parsed.lastUsed == 0L && parsed.usedFrequency == 0L, { "usage metadata" })
            expect(parsed.copySecret().contentEquals("Das System ist sicher".toByteArray(StandardCharsets.UTF_8)),
                    { "seed" })

            // The stored format is the Java serializer's: same names, same order, same values.
            val written = EntryJson.serialize(parsed.kind, parsed.copySecret(), parsed.issuer, parsed.label,
                    parsed.digits, parsed.period, parsed.counter, parsed.algorithm, parsed.thumbnailName!!,
                    parsed.lastUsed, parsed.usedFrequency, parsed.tags, "")
            expect(written.toString() == STORED_ENTRY_JSON, { "stored form changed: $written" })
            expect(written.keys().asSequence().toList() == listOf("secret", "issuer", "label", "digits", "type",
                    "algorithm", "thumbnail", "last_used", "used_frequency", "period", "tags"),
                    { "field order changed: ${written.keys().asSequence().toList()}" })
        } finally {
            parsed.close()
        }

        // A legacy document that predates most fields keeps the documented defaults.
        val legacy = entryValue(JSONObject().put("secret", ENTRY_SEED).put("label", "legacy"))
        try {
            expect(legacy.kind == OtpKind.TOTP && legacy.period == 30 && legacy.digits == 6, { "legacy defaults" })
            expect(legacy.counter == 0L && legacy.algorithm == OtpHashAlgorithm.SHA1, { "legacy parameters" })
            expect(legacy.issuer.isEmpty() && legacy.tags.isEmpty(), { "legacy text" })
            expect(legacy.thumbnailName == null && legacy.lastUsed == 0L && legacy.usedFrequency == 0L,
                    { "legacy metadata" })
        } finally {
            legacy.close()
        }

        // Steam keeps its own digit default, and a document may carry numbers as strings.
        val steam = entryValue(entryDocument().put("type", "STEAM"))
        try {
            expect(steam.kind == OtpKind.STEAM && steam.digits == 5, { "Steam digit default" })
        } finally {
            steam.close()
        }
        val strings = entryValue(entryDocument().put("period", "0030").put("digits", "8"))
        try {
            expect(strings.period == 30 && strings.digits == 8, { "numeric strings" })
        } finally {
            strings.close()
        }

        // HOTP writes its counter and no period; TOTP writes its period and no counter.
        val hotp = EntryJson.serialize(OtpKind.HOTP, ascii("12345678901234567890"), "", "hotp", 8, 30, 42L,
                OtpHashAlgorithm.SHA256, "GitHub", 5L, 7L, listOf("work"), "")
        expect(hotp.getString("type") == "HOTP" && hotp.getString("algorithm") == "SHA256", { "HOTP fields" })
        expect(hotp.getLong("counter") == 42L && !hotp.has("period"), { "HOTP counter/period" })
        expect(hotp.getLong("last_used") == 5L && hotp.getLong("used_frequency") == 7L, { "usage metadata" })
        expect(hotp.getJSONArray("tags").getString(0) == "work", { "tags array" })
        val totp = EntryJson.serialize(OtpKind.TOTP, ascii("12345678901234567890"), "ACME", "totp", 6, 60, 0L,
                OtpHashAlgorithm.SHA1, "Default", 0L, 0L, emptyList(), "")
        expect(totp.getInt("period") == 60 && !totp.has("counter"), { "TOTP period/counter" })
        expect(totp.getString("issuer") == "ACME" && totp.getJSONArray("tags").length() == 0, { "TOTP fields" })
    }

    check("the mOTP PIN is stored, round-trips and defaults to empty in older documents") {
        // An mOTP entry that has a PIN carries it, and the document survives a round trip.
        val written = EntryJson.serialize(OtpKind.MOTP, ascii("0123456789abcdef"), "ACME", "alice", 6, 30, 0L,
                OtpHashAlgorithm.SHA1, "Default", 0L, 0L, emptyList(), "1234")
        expect(written.has("pin") && written.getString("pin") == "1234", { "the PIN was not written" })
        val parsed = entryValue(JSONObject(written.toString()))
        try {
            expect(parsed.kind == OtpKind.MOTP && parsed.pin == "1234",
                    { "the stored PIN did not survive the round trip" })
            val again = EntryJson.serialize(parsed.kind, parsed.copySecret(), parsed.issuer, parsed.label,
                    parsed.digits, parsed.period, parsed.counter, parsed.algorithm, parsed.thumbnailName!!,
                    parsed.lastUsed, parsed.usedFrequency, parsed.tags, parsed.pin)
            expect(again.toString() == written.toString(), { "the mOTP document changed: $again" })
        } finally {
            parsed.close()
        }

        // Only an mOTP entry that actually has a PIN writes the field, so every document the Java
        // serializer produced stays byte-identical.
        expect(!EntryJson.serialize(OtpKind.MOTP, ascii("0123456789abcdef"), "", "alice", 6, 30, 0L,
                OtpHashAlgorithm.SHA1, "Default", 0L, 0L, emptyList(), "").has("pin"),
                { "an empty mOTP PIN must not be written" })
        expect(!EntryJson.serialize(OtpKind.TOTP, ascii("12345678901234567890"), "", "totp", 6, 30, 0L,
                OtpHashAlgorithm.SHA1, "Default", 0L, 0L, emptyList(), "1234").has("pin"),
                { "a PIN on a non-mOTP entry must not be written" })

        // A document written before the field existed reads back as an empty PIN.
        val legacy = entryValue(entryDocument().put("type", "MOTP"))
        try {
            expect(legacy.kind == OtpKind.MOTP && legacy.pin.isEmpty(), { "the legacy mOTP PIN default" })
        } finally {
            legacy.close()
        }

        // The PIN is bounded like the other entry text, and a non-string PIN is a rejected field.
        expect(entryError(entryDocument().put("type", "MOTP")
                .put("pin", "5".repeat(InputPolicy.MAX_TAG_CHARS + 1))) == EntryJsonError.TEXT_TOO_LONG,
                { "an oversized PIN must be rejected" })
        expect(entryError(entryDocument().put("type", "MOTP").put("pin", 5))
                == EntryJsonError.INVALID_TEXT, { "a numeric PIN must be rejected" })

        // The Entry adapter carries the stored PIN into the model and back into the document.
        val entry = Entry(JSONObject(written.toString()))
        try {
            expect(entry.getPin() == "1234", { "the adapter did not carry the stored PIN" })
            expect(entry.toJSON().toString() == written.toString(), { "the adapter round trip changed it" })
        } finally {
            entry.destroy()
        }
    }

    check("entry documents reject malformed fields with a closed error set") {
        expect(EntryJsonError.values().map { it.name } == listOf("INVALID_TEXT", "TEXT_TOO_LONG",
                "MISSING_SECRET", "INVALID_SECRET", "INVALID_NUMBER", "MISSING_COUNTER",
                "INVALID_PARAMETERS", "UNSUPPORTED_TYPE", "INVALID_ALGORITHM", "INVALID_TAGS", "TOO_MANY_TAGS"),
                { "the error set is no longer closed: ${EntryJsonError.values().map { it.name }}" })

        // Same cases the device test EntryInputSecurityTest asserts an exception for.
        expect(entryError(entryDocument().put("period", 0)) == EntryJsonError.INVALID_PARAMETERS, { "period 0" })
        expect(entryError(entryDocument().put("period", -1)) == EntryJsonError.INVALID_PARAMETERS, { "period -1" })
        expect(entryError(entryDocument().put("period", 86401)) == EntryJsonError.INVALID_PARAMETERS, { "period max" })
        expect(entryError(entryDocument().put("period", 4294967326L)) == EntryJsonError.INVALID_NUMBER,
                { "period beyond an int" })
        expect(entryError(entryDocument().put("period", 1.5)) == EntryJsonError.INVALID_NUMBER, { "period 1.5" })
        expect(entryError(entryDocument().put("period", "1.5")) == EntryJsonError.INVALID_NUMBER,
                { "period \"1.5\"" })
        expect(entryError(entryDocument().put("period", "9223372036854775808")) == EntryJsonError.INVALID_NUMBER,
                { "period overflow" })
        expect(entryError(entryDocument().put("digits", 10)) == EntryJsonError.INVALID_PARAMETERS, { "digits 10" })
        expect(entryError(entryDocument().put("digits", 0)) == EntryJsonError.INVALID_PARAMETERS, { "digits 0" })
        expect(entryError(entryDocument().put("type", "STEAM").put("digits", 6)) == EntryJsonError.INVALID_PARAMETERS,
                { "Steam digits" })

        // Counter-based entries require a usable counter.
        expect(entryError(entryDocument().put("type", "HOTP")) == EntryJsonError.MISSING_COUNTER,
                { "missing HOTP counter" })
        expect(entryError(entryDocument().put("type", "HOTP").put("counter", Long.MAX_VALUE)) ==
                EntryJsonError.INVALID_PARAMETERS, { "HOTP counter ceiling" })
        expect(entryError(entryDocument().put("type", "HOTP").put("counter", -1)) == EntryJsonError.INVALID_PARAMETERS,
                { "negative HOTP counter" })
        val counter = entryValue(entryDocument().put("type", "HOTP").put("counter", 42L))
        try {
            expect(counter.kind == OtpKind.HOTP && counter.counter == 42L, { "HOTP counter" })
        } finally {
            counter.close()
        }

        // Text fields: missing, JSON null and non-string values all reject, names stay case-sensitive.
        expect(entryError(JSONObject().put("label", "no secret")) == EntryJsonError.MISSING_SECRET,
                { "missing secret" })
        expect(entryError(entryDocument().put("secret", JSONObject.NULL)) == EntryJsonError.INVALID_TEXT,
                { "null secret" })
        expect(entryError(entryDocument().put("secret", 5)) == EntryJsonError.INVALID_TEXT, { "numeric secret" })
        expect(entryError(JSONObject().put("secret", ENTRY_SEED)) == EntryJsonError.INVALID_TEXT, { "missing label" })
        expect(entryError(entryDocument().put("label", 5)) == EntryJsonError.INVALID_TEXT, { "numeric label" })
        expect(entryError(entryDocument().put("type", "totp")) == EntryJsonError.UNSUPPORTED_TYPE,
                { "lowercase type" })
        expect(entryError(entryDocument().put("type", 7)) == EntryJsonError.INVALID_TEXT, { "numeric type" })
        expect(entryError(entryDocument().put("algorithm", "sha1")) == EntryJsonError.INVALID_ALGORITHM,
                { "lowercase algorithm" })
        expect(entryError(entryDocument().put("algorithm", 5)) == EntryJsonError.INVALID_TEXT, { "numeric algorithm" })
        expect(entryError(entryDocument().put("label", "x".repeat(InputPolicy.MAX_TEXT_CHARS + 1))) ==
                EntryJsonError.TEXT_TOO_LONG, { "oversized label" })

        // Seeds must be canonical Base32 and stay within the seed bounds.
        expect(entryError(entryDocument(secret = "")) == EntryJsonError.INVALID_SECRET, { "empty seed" })
        expect(entryError(entryDocument(secret = "!!!")) == EntryJsonError.INVALID_SECRET, { "non-Base32 seed" })
        expect(entryError(entryDocument(secret = "$ENTRY_SEED=")) == EntryJsonError.INVALID_SECRET,
                { "non-canonical seed" })
        expect(entryError(entryDocument(secret = "A".repeat(InputPolicy.MAX_SECRET_BYTES * 2 + 1))) ==
                EntryJsonError.INVALID_SECRET, { "oversized seed" })

        // Tags: count, element type and length are all bounded.
        val tooManyTags = org.json.JSONArray()
        for (index in 0..InputPolicy.MAX_TAGS) tooManyTags.put("tag")
        expect(entryError(entryDocument().put("tags", tooManyTags)) == EntryJsonError.TOO_MANY_TAGS, { "tag count" })
        expect(entryError(entryDocument().put("tags", org.json.JSONArray().put(42))) == EntryJsonError.INVALID_TAGS,
                { "numeric tag" })
        expect(entryError(entryDocument().put("tags", "not an array")) == EntryJsonError.INVALID_TAGS,
                { "tags not an array" })
        expect(entryError(entryDocument().put("tags", org.json.JSONArray().put("x".repeat(InputPolicy.MAX_TAG_CHARS + 1)))) ==
                EntryJsonError.TEXT_TOO_LONG, { "oversized tag" })

        // Seeds are accepted in either letter case and without padding.
        val lower = entryValue(entryDocument(secret = ENTRY_SEED.lowercase()))
        try {
            expect(lower.copySecret().contentEquals(ascii("1234567890")), { "lowercase seed" })
        } finally {
            lower.close()
        }

        // Display metadata is best-effort: an unknown/oversized/absent name is not a rejection.
        val unknownThumbnail = entryValue(entryDocument().put("thumbnail", "NotAThumbnail"))
        try {
            expect(unknownThumbnail.thumbnailName == "NotAThumbnail", { "unknown thumbnail name carried" })
        } finally {
            unknownThumbnail.close()
        }
        val oversizedThumbnail = entryValue(entryDocument().put("thumbnail", "x".repeat(InputPolicy.MAX_TEXT_CHARS + 1)))
        try {
            expect(oversizedThumbnail.thumbnailName == null, { "oversized thumbnail name dropped" })
        } finally {
            oversizedThumbnail.close()
        }
        val unreadableUsage = entryValue(entryDocument().put("last_used", "x").put("used_frequency", 1.5))
        try {
            expect(unreadableUsage.lastUsed == 0L && unreadableUsage.usedFrequency == 1L,
                    { "usage fallbacks: ${unreadableUsage.lastUsed}/${unreadableUsage.usedFrequency}" })
        } finally {
            unreadableUsage.close()
        }
    }

    check("entry document values are one-use, redacted and wipe their seed") {
        val value = entryValue(entryDocument(label = "redact-me"))
        val seedField = ValidatedEntry::class.java.getDeclaredField("seed")
        seedField.isAccessible = true
        val seed = seedField.get(value) as ByteArray
        expect(seed.contentEquals(ascii("1234567890")), { "stored seed" })

        val copy = value.copySecret()
        copy.fill(0)
        expect(value.copySecret().contentEquals(ascii("1234567890")),
                { "a returned copy must not be the stored seed" })
        expect(!value.toString().contains("redact-me") && !value.toString().contains("GEZD"),
                { "value diagnostics leaked: $value" })
        value.close()
        expect(seed.all { it == 0.toByte() }, { "close() did not wipe the stored seed" })
        value.close()
        expectThrows(IllegalStateException::class.java) { value.copySecret() }

        val success = EntryJsonResult.Success(entryValue(entryDocument()))
        expect(!success.toString().contains("GEZD") && !success.toString().contains("synthetic"),
                { "success diagnostics leaked: $success" })
        (success.value).close()

        val failure = EntryJson.parse(entryDocument(secret = "not-a-seed"))
        expect(failure is EntryJsonResult.Failure, { "invalid seed accepted" })
        expect(failure.toString() == "Failure(error=INVALID_SECRET)", { "failure diagnostics: $failure" })
    }

    check("entry secret checks keep the Base32 and mOTP hex rules") {
        expect(Entry.validateSecret(ENTRY_SEED, Entry.OTPType.TOTP), { "canonical Base32" })
        expect(Entry.validateSecret(ENTRY_SEED.lowercase(), Entry.OTPType.TOTP), { "lowercase Base32" })
        expect(Entry.validateSecret("IRQXGICTPFZXIZLNEBUXG5BAONUWG2DFOI======", Entry.OTPType.TOTP),
                { "padded Base32" })
        expect(!Entry.validateSecret("$ENTRY_SEED=", Entry.OTPType.TOTP), { "non-canonical Base32" })
        expect(!Entry.validateSecret("", Entry.OTPType.TOTP), { "empty Base32" })
        expect(!Entry.validateSecret(null, Entry.OTPType.TOTP), { "null Base32" })
        expect(!Entry.validateSecret("!!!", Entry.OTPType.TOTP), { "invalid Base32" })
        expect(!Entry.validateSecret("A".repeat(InputPolicy.MAX_SECRET_BYTES * 2 + 1), Entry.OTPType.TOTP),
                { "oversized Base32" })
        expect(!Entry.validateSecret("GEZDGNBVGY3TQOJQ", Entry.OTPType.MOTP), { "Base32 is not mOTP hex" })
        expect(Entry.validateSecret("0123456789abcdef", Entry.OTPType.MOTP), { "mOTP hex" })
        expect(Entry.validateSecret("0123456789ABCDEF", Entry.OTPType.MOTP), { "uppercase mOTP hex" })
        expect(!Entry.validateSecret("012", Entry.OTPType.MOTP), { "odd mOTP hex" })
        // The historical check accepted an empty mOTP secret; commons-codec decodes it to nothing.
        expect(Entry.validateSecret("", Entry.OTPType.MOTP), { "empty mOTP hex" })

        expect(EntryJson.decodeBase32Secret(ENTRY_SEED)!!.contentEquals(ascii("1234567890")),
                { "decodeBase32Secret" })
        expect(EntryJson.decodeBase32Secret(null) == null, { "null seed" })
        expect(EntryJson.decodeBase32Secret("$ENTRY_SEED=") == null, { "non-canonical seed" })
    }

    check("the converted entry class keeps its Java-visible shapes") {
        val entry = Entry::class.java
        expect(Modifier.isPublic(entry.modifiers), { "Entry is not public" })
        expect(!Modifier.isFinal(entry.modifiers), { "Entry must stay extendable like the Java class" })
        expect(Entry.OTPType.values().map { it.name } == listOf("TOTP", "HOTP", "MOTP", "STEAM"),
                { "OTPType constants changed" })
        expect(Entry.COLOR_RED == 1, { "COLOR_RED" })

        fun constructorOf(vararg parameters: Class<*>): java.lang.reflect.Constructor<*> =
            entry.declaredConstructors.firstOrNull { it.parameterTypes.contentEquals(parameters) }
                ?: throw AssertionError("missing Entry constructor ${parameters.map { it.simpleName }}")

        expect(entry.declaredConstructors.any { it.parameterTypes.isEmpty() }, { "missing Entry()" })
        constructorOf(Entry.OTPType::class.java, String::class.java, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, String::class.java, String::class.java,
                TokenCalculator.HashAlgorithm::class.java, java.util.List::class.java)
        constructorOf(Entry.OTPType::class.java, String::class.java, LONG_TYPE, Int::class.javaPrimitiveType!!,
                String::class.java, String::class.java, TokenCalculator.HashAlgorithm::class.java,
                java.util.List::class.java)
        constructorOf(Entry.OTPType::class.java, String::class.java, String::class.java, String::class.java,
                java.util.List::class.java)

        // The legacy adapters keep declaring the checked exception their Java callers catch.
        for (parameters in listOf(arrayOf<Class<*>>(String::class.java), arrayOf<Class<*>>(JSONObject::class.java))) {
            val constructor = constructorOf(*parameters)
            expect(constructor.exceptionTypes.contains(Exception::class.java),
                    { "Entry(${parameters.first().simpleName}) no longer declares throws Exception" })
        }

        expect(entry.getMethod("getThumbnail").returnType == EntryThumbnail.EntryThumbnails::class.java,
                { "thumbnail getter type changed" })
    }

    check("thumbnail names retain exact and ordered whole-word lookup semantics") {
        val thumbnails = EntryThumbnail.EntryThumbnails.values()
        for (thumbnail in thumbnails) {
            expect(EntryThumbnail.EntryThumbnails.valueOfIgnoreCase(thumbnail.name.lowercase()) == thumbnail,
                    { "case-insensitive lookup: ${thumbnail.name}" })
            expect(thumbnail.assetType == EntryThumbnail.AssetType.Vector, { "asset default" })
        }
        expect(EntryThumbnail.EntryThumbnails.valueOfFuzzy("GitHub Inc.") == EntryThumbnail.EntryThumbnails.GitHub,
                { "whole-word lookup" })
        // Declaration order, not textual position or longest match: Git precedes GitHub.
        expect(EntryThumbnail.EntryThumbnails.valueOfFuzzy("GitHub and Git") == EntryThumbnail.EntryThumbnails.Git,
                { "lookup order changed" })
        for (unknown in listOf("not-a-brand", "MyGitHubAccount", "GitHub_extra", "")) {
            expectThrows(IllegalArgumentException::class.java) { EntryThumbnail.EntryThumbnails.valueOfFuzzy(unknown) }
        }
        for (unknown in listOf(null, " GitHub", "GitHub ", "unknown")) {
            expectThrows(IllegalArgumentException::class.java) { EntryThumbnail.EntryThumbnails.valueOfIgnoreCase(unknown) }
        }
    }

    check("thumbnail helpers retain their Java-visible entry points") {
        val thumbnail = EntryThumbnail::class.java
        expect(!Modifier.isFinal(thumbnail.modifiers), { "EntryThumbnail was extendable" })
        thumbnail.getConstructor()
        val graphic = thumbnail.getMethod("getThumbnailGraphic", Context::class.java, String::class.java,
                String::class.java, Int::class.javaPrimitiveType!!, EntryThumbnail.EntryThumbnails::class.java)
        expect(Modifier.isStatic(graphic.modifiers) && graphic.returnType == android.graphics.Bitmap::class.java,
                { "static graphic API" })
        val enum = EntryThumbnail.EntryThumbnails::class.java
        for (name in listOf("valueOfIgnoreCase", "valueOfFuzzy")) {
            val method = enum.getMethod(name, String::class.java)
            expect(Modifier.isStatic(method.modifiers) && method.returnType == enum, { name })
        }
        expect(enum.getMethod("getResource").returnType == Int::class.javaPrimitiveType, { "resource getter" })
        expect(enum.getMethod("getAssetType").returnType == EntryThumbnail.AssetType::class.java, { "asset getter" })
        LetterBitmap::class.java.getConstructor(Context::class.java)
        val tile = LetterBitmap::class.java.getMethod("getLetterTile", String::class.java, String::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        expect(tile.returnType == android.graphics.Bitmap::class.java, { "tile API" })
    }

    check("tag lists preserve sorted names, borrowed state and copied snapshots") {
        val states = hashMapOf<String, Boolean?>("zeta" to false, "Alpha" to true, "alpha" to false)
        val adapter = TagsAdapter(Context(), states)
        expect(adapter.getTags() == listOf("Alpha", "alpha", "zeta"), { "natural sort" })
        expect(adapter.getActiveTags() == listOf("Alpha"), { "active sort" })
        expect(adapter.getTagState("missing") == false && adapter.getTagState(null) == false, { "unknown tag" })
        val before = adapter.changeNotifications
        adapter.setTagState("missing", true)
        expect(!states.containsKey("missing") && adapter.changeNotifications > before, { "unknown toggle" })
        adapter.setTagState("zeta", true)
        expect(states["zeta"] == true, { "constructor must borrow state map" })
        states["alpha"] = true
        expect(adapter.allTagsActive(), { "external state changes are still visible" })
        adapter.getTagsWithState()["alpha"] = false
        expect(adapter.getTagState("alpha") == true, { "snapshot must be independent" })
        val row = adapter.getView(0, null, android.view.ViewGroup()) as android.widget.CheckedTextView
        expect(row.text == "Alpha" && row.isChecked, { "row bind" })
        val reused = adapter.getView(2, row, android.view.ViewGroup())
        expect(reused === row && row.text == "zeta" && row.isChecked, { "row reuse" })
        val replacement = hashMapOf<String, Boolean?>("two" to false, "one" to true)
        adapter.setTags(replacement)
        expect(adapter.getTags() == listOf("one", "two") && adapter.getCount() == 2, { "replace, not merge" })
        expect(adapter.getItem(0) == "one" && adapter.getActiveTags() == listOf("one"), { "replacement data" })
        adapter.setTagState("two", true)
        expect(replacement["two"] == true && adapter.allTagsActive(), { "replacement map must be borrowed" })
    }

    check("empty tags remain all-active and boxed null states are not silently unchecked") {
        val adapter = TagsAdapter(Context(), hashMapOf())
        expect(adapter.allTagsActive() && adapter.getActiveTags().isEmpty(), { "empty tags" })
        adapter.setTags(hashMapOf("tag" to true))
        adapter.setTagState("tag", null)
        expect(adapter.getTagState("tag") == null, { "boxed null state" })
        expectThrows(NullPointerException::class.java) { adapter.getActiveTags() }
        expectThrows(NullPointerException::class.java) { adapter.allTagsActive() }
    }

    check("autofill delegates before callbacks and ignores non-text values and cleared listeners") {
        val field = AutoFillableTextInputEditText(Context())
        val text = android.view.autofill.AutofillValue.forText("preview")
        var calls = 0
        field.setAutoFillTextListener {
            expect(field.autofilledValue === text, { "super.autofill must run first" })
            expect(it.toString() == "preview", { "callback text" })
            calls++
        }
        field.autofill(text)
        field.autofill(android.view.autofill.AutofillValue.forToggle(true))
        field.autofill(null) // Only the fake superclass tolerates null; not a platform null guarantee.
        expect(calls == 1, { "only text should trigger callbacks" })
        field.setAutoFillTextListener(null)
        field.autofill(text)
        expect(calls == 1 && field.autofilledValue === text, { "listener clearing must not disable platform autofill" })
    }

    check("autofill and thumbnail picker keep the Java-visible API shapes") {
        val picker = ThumbnailSelectionAdapter::class.java
        expect(!Modifier.isFinal(picker.modifiers), { "picker was extendable" })
        picker.getConstructor(Context::class.java, String::class.java, String::class.java)
        picker.getMethod("filter", String::class.java)
        expect(picker.getMethod("getItem", Int::class.javaPrimitiveType!!).returnType == Any::class.java,
                { "getItem Object return type" })
        expect(picker.getMethod("getItemId", Int::class.javaPrimitiveType!!).returnType == LONG_TYPE, { "item id" })
        val field = AutoFillableTextInputEditText::class.java
        expect(!Modifier.isFinal(field.modifiers), { "autofill widget was extendable" })
        field.getConstructor(Context::class.java)
        field.getConstructor(Context::class.java, android.util.AttributeSet::class.java)
        field.getConstructor(Context::class.java, android.util.AttributeSet::class.java, Int::class.javaPrimitiveType!!)
        field.getMethod("setAutoFillTextListener", AutoFillableTextInputEditText.AutoFillTextListener::class.java)
        val listener = AutoFillableTextInputEditText.AutoFillTextListener::class.java
        expect(listener.isInterface && listener.declaredMethods.size == 1, { "Java SAM listener" })
        listener.getMethod("onTextAutoFilled", CharSequence::class.java)
    }

    check("number picker keeps default and configured bounds, layout and summary") {
        val defaults = SandboxNumberPreference(Context())
        defaults.setValue(12)
        val root = defaults.createAndBind()
        expect(root.childCount == 1, { "one picker per dialog" })
        expect(defaults.widget.minValue == 0 && defaults.widget.maxValue == 100 && defaults.widget.wrapSelectorWheel,
                { "default bounds and wrap" })
        expect(defaults.widget.value == 12 && defaults.summary.toString() == "12", { "bind current value" })
        val params = defaults.widget.layoutParams as android.widget.FrameLayout.LayoutParams
        expect(params.width == -2 && params.height == -2 && params.gravity == android.view.Gravity.CENTER,
                { "wrap-content, centered picker" })
        var recycled = false
        val attrs = object : android.content.res.TypedArray() {
            override fun getInteger(index: Int, defValue: Int): Int = when (index) {
                R.styleable.NumberPickerPreference_minValue -> 3
                R.styleable.NumberPickerPreference_maxValue -> 9
                else -> throw AssertionError("unexpected integer attribute")
            }
            override fun getBoolean(index: Int, defValue: Boolean): Boolean {
                expect(index == R.styleable.NumberPickerPreference_wrapSelectorWheel, { "wrap attribute" })
                return false
            }
            override fun recycle() { recycled = true }
        }
        val context = object : Context() {
            override fun obtainStyledAttributes(attrsSet: android.util.AttributeSet?, styleable: IntArray): android.content.res.TypedArray {
                expect(styleable === R.styleable.NumberPickerPreference, { "styleable array" })
                return attrs
            }
        }
        val configured = SandboxNumberPreference(context)
        expect(recycled, { "typed array must be recycled after reading" })
        configured.setValue(5)
        configured.createAndBind()
        expect(configured.widget.minValue == 3 && configured.widget.maxValue == 9 && !configured.widget.wrapSelectorWheel,
                { "configured bounds" })
    }

    check("number picker cancellation and veto do not persist a proposed edit") {
        val pref = SandboxNumberPreference(Context())
        pref.setValue(5)
        pref.createAndBind()
        pref.widget.value = 7
        val persistedBefore = pref.persisted.size
        pref.close(false)
        expect(!pref.widget.focusCleared && pref.proposed.isEmpty() && pref.persisted.size == persistedBefore,
                { "cancel must not commit or notify" })
        pref.allowChange = false
        pref.close(true)
        expect(pref.widget.focusCleared && pref.proposed == listOf(7), { "veto must be offered committed edit" })
        expect(pref.getValue() == 5 && pref.persisted.size == persistedBefore && pref.summary.toString() == "5",
                { "veto must preserve value and summary" })
        pref.allowChange = true
        pref.close(true)
        expect(pref.getValue() == 7 && pref.persisted.last() == 7 && pref.summary.toString() == "7", { "accepted edit" })
    }

    check("number preference keeps default parsing and restore-versus-initial-value semantics") {
        val pref = SandboxNumberPreference(Context())
        val missingDefault = object : android.content.res.TypedArray() {
            override fun getInt(index: Int, defValue: Int): Int {
                expect(index == 4 && defValue == 0, { "typed-array index and min fallback" })
                return 8
            }
        }
        expect(pref.defaultValue(missingDefault, 4) == 8, { "boxed default value" })
        pref.initialize(false, 8)
        expect(pref.getValue() == 8 && pref.persisted.last() == 8 && pref.summary.toString() == "8", { "initial default" })
        pref.restoredValue = 19
        pref.initialize(true, null)
        expect(pref.getValue() == 19 && pref.persistedFallback == 0 && pref.summary.toString() == "19", { "restore persisted" })
        pref.restoredValue = null
        pref.initialize(true, 99)
        expect(pref.getValue() == 0 && pref.summary.toString() == "0", { "missing persisted value uses min, not default object" })
    }

    check("number preference and hideable dialog keep their Java entry points") {
        val number = NumberPickerPreference::class.java
        expect(!Modifier.isFinal(number.modifiers), { "preference must remain extendable" })
        number.getConstructor(Context::class.java, android.util.AttributeSet::class.java)
        number.getConstructor(Context::class.java, android.util.AttributeSet::class.java, Int::class.javaPrimitiveType!!)
        expect(number.getField("DEFAULT_MAX_VALUE").getInt(null) == 100, { "max constant" })
        expect(number.getField("DEFAULT_MIN_VALUE").getInt(null) == 0, { "min constant" })
        expect(number.getField("DEFAULT_WRAP_SELECTOR_WHEEL").getBoolean(null), { "wrap constant" })
        number.getMethod("setValue", Int::class.javaPrimitiveType!!)
        expect(number.getMethod("getValue").returnType == Int::class.javaPrimitiveType, { "value getter" })
        for (method in number.declaredMethods.filter { it.name.startsWith("on") }) {
            expect(Modifier.isProtected(method.modifiers) && !Modifier.isFinal(method.modifiers), { "overridable protected hook ${method.name}" })
        }
        val dialog = HideableDialog::class.java
        expect(!Modifier.isFinal(dialog.modifiers), { "dialog must remain extendable" })
        dialog.getConstructor(Context::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        val show = dialog.getMethod("ShowHidableDialog", Context::class.java, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        expect(Modifier.isStatic(show.modifiers), { "legacy Java show spelling/static API" })
        expect(View.OnClickListener::class.java.isAssignableFrom(dialog), { "click listener" })
        expect(android.widget.CompoundButton.OnCheckedChangeListener::class.java.isAssignableFrom(dialog), { "checkbox listener" })
    }

    check("password entry dialog keeps its enable rules, editor actions and dismissal flow") {
        val enterWidgets = FakePasswordDialogWidgets()
        try {
            var entered: String? = null
            val dialog = PasswordEntryDialog(Context(), PasswordEntryDialog.Mode.ENTER, true, false,
                    PasswordEntryDialog.PasswordEnteredCallback { entered = it })

            expect(dialog.titleResource == R.string.dialog_title_enter_password, { "dialog title changed" })
            expect(dialog.contentResource == R.layout.dialog_password_entry, { "dialog layout changed" })
            expect(!enterWidgets.ok.isEnabled, { "OK must start disabled" })
            expect(enterWidgets.confirm.visibility == View.GONE, { "ENTER mode must hide the confirmation field" })
            expect(enterWidgets.input.transformationMethod is PasswordTransformationMethod &&
                    enterWidgets.confirm.transformationMethod is PasswordTransformationMethod,
                    { "both fields must start masked" })
            expect(enterWidgets.layout.importantForAccessibilityMode == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS &&
                    enterWidgets.confirm.importantForAccessibilityMode == View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                    { "the accessibility block is not applied to the right views" })
            expect(enterWidgets.layout.importantForAutofillMode == 0 && enterWidgets.confirm.importantForAutofillMode == 0,
                    { "autofill must not be blocked when it was not requested" })
            expect(enterWidgets.warning.text == "text:${R.string.settings_label_short_password}",
                    { "the short-password warning text changed" })

            enterWidgets.input.setText("abcde")
            dialog.onTextChanged("", 0, 0, 0)
            expect(enterWidgets.warning.visibility == View.VISIBLE && !enterWidgets.ok.isEnabled,
                    { "a short password must warn and keep OK disabled" })

            enterWidgets.input.setText("abcdef")
            dialog.onTextChanged("", 0, 0, 0)
            expect(enterWidgets.warning.visibility == View.GONE && enterWidgets.ok.isEnabled,
                    { "ENTER mode must enable OK at the minimum length" })

            expect(dialog.onEditorAction(enterWidgets.input, EditorInfo.IME_ACTION_DONE, null),
                    { "the done action must be consumed" })
            expect(entered == "abcdef", { "OK did not deliver the entered password" })
            expect(dialog.dismissals == 1, { "OK must dismiss the dialog" })

            // The enter-key release that follows a hardware enter is swallowed, without a second delivery.
            expect(dialog.onEditorAction(enterWidgets.input, 0, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)),
                    { "the enter-key release must be consumed" })
            expect(entered == "abcdef" && dialog.dismissals == 1, { "the enter-key release must be a no-op" })

            // Any other action without its key event still fails, like the Java dereference did.
            expectThrows(NullPointerException::class.java) {
                dialog.onEditorAction(enterWidgets.input, EditorInfo.IME_ACTION_NEXT, null)
            }
        } finally {
            AppCompatDialog.viewProvider = null
        }

        val updateWidgets = FakePasswordDialogWidgets()
        try {
            var entered: String? = null
            val dialog = PasswordEntryDialog(Context(), PasswordEntryDialog.Mode.UPDATE, false, true,
                    PasswordEntryDialog.PasswordEnteredCallback { entered = it })

            expect(updateWidgets.confirm.visibility == View.VISIBLE,
                    { "UPDATE mode must show the confirmation field" })
            expect(updateWidgets.layout.importantForAutofillMode == View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS &&
                    updateWidgets.confirm.importantForAutofillMode == View.IMPORTANT_FOR_AUTOFILL_NO,
                    { "the autofill block is not applied to the right views" })
            expect(!updateWidgets.ok.isEnabled, { "OK must start disabled" })

            updateWidgets.input.setText("abcdef")
            updateWidgets.confirm.setText("abcdxf")
            dialog.onTextChanged("", 0, 0, 0)
            expect(updateWidgets.warning.visibility == View.GONE && !updateWidgets.ok.isEnabled,
                    { "a mismatched confirmation must keep OK disabled" })

            updateWidgets.confirm.setText("abcdef")
            dialog.onTextChanged("", 0, 0, 0)
            expect(updateWidgets.ok.isEnabled, { "matching inputs must enable OK" })

            updateWidgets.input.setText("abc")
            updateWidgets.confirm.setText("abc")
            dialog.onTextChanged("", 0, 0, 0)
            expect(updateWidgets.warning.visibility == View.VISIBLE && !updateWidgets.ok.isEnabled,
                    { "matching but short inputs must still warn" })

            updateWidgets.cancel.performClick()
            expect(dialog.dismissals == 1 && entered == null,
                    { "cancel must dismiss without delivering a password" })

            updateWidgets.input.setText("secret1")
            updateWidgets.confirm.setText("secret1")
            dialog.onTextChanged("", 0, 0, 0)
            updateWidgets.ok.performClick()
            expect(entered == "secret1" && dialog.dismissals == 2,
                    { "OK must deliver the password and dismiss" })

            // The Java code tolerated a missing callback and still dismissed.
            val widgets = FakePasswordDialogWidgets()
            val silent = PasswordEntryDialog(Context(), PasswordEntryDialog.Mode.ENTER, false, false, null)
            widgets.ok.performClick()
            expect(silent.dismissals == 1, { "a missing callback must still dismiss" })
        } finally {
            AppCompatDialog.viewProvider = null
        }
    }

    check("tag dialog wires the picker and the new-tag flow to the adapter") {
        val context = Context()
        val states = HashMap<String, Boolean?>()
        states["Alpha"] = true
        states["beta"] = false
        val tagsAdapter = TagsAdapter(context, states)
        var newTagCalls = 0
        var selectedCalls = 0
        val newTagCallable = object : java.util.concurrent.Callable<Any?> {
            override fun call(): Any? {
                newTagCalls++
                return null
            }
        }
        val selectedTagsCallable = object : java.util.concurrent.Callable<Any?> {
            override fun call(): Any? {
                selectedCalls++
                return null
            }
        }

        AlertDialog.Builder.created.clear()
        TagsDialog.show(context, tagsAdapter, newTagCallable, selectedTagsCallable)

        expect(AlertDialog.Builder.created.size == 1, { "the picker must create exactly one dialog" })
        val selector = AlertDialog.Builder.created[0]
        expect(selector.shown, { "the picker was not shown" })
        expect(selector.titleId == R.string.label_tags, { "picker title changed" })
        val list = (selector.contentView as android.widget.FrameLayout).getChildAt(0) as android.widget.ListView
        expect(list.adapter === tagsAdapter, { "the picker must show the given adapter" })

        val row = android.widget.CheckedTextView()
        row.setText("Alpha")
        row.isChecked = true
        list.onItemClickListener!!.onItemClick(null, row, 0, 0L)
        expect(!row.isChecked && tagsAdapter.getTagState("Alpha") == false,
                { "tapping a row must toggle the tag state" })

        selector.neutralButtonListener!!.onClick(selector, DialogInterface.BUTTON_NEUTRAL)
        expect(AlertDialog.Builder.created.size == 2, { "the new-tag button must open the second dialog" })
        val newTagDialog = AlertDialog.Builder.created[1]
        expect(newTagDialog.shown && newTagDialog.titleId == R.string.button_new_tag, { "new-tag dialog changed" })
        val tagInput = (newTagDialog.contentView as android.widget.FrameLayout).getChildAt(0) as android.widget.EditText
        tagInput.setText("fresh")
        newTagDialog.positiveButtonListener!!.onClick(newTagDialog, DialogInterface.BUTTON_POSITIVE)
        expect(tagsAdapter.getTagState("fresh") == true, { "a created tag must start active" })
        expect(newTagCalls == 1, { "the new-tag callable did not run" })

        selector.positiveButtonListener!!.onClick(selector, DialogInterface.BUTTON_POSITIVE)
        expect(selectedCalls == 1, { "the selection callable did not run" })
        selector.negativeButtonListener!!.onClick(selector, DialogInterface.BUTTON_NEGATIVE)
        expect(selector.dismissals == 1, { "cancel must dismiss the picker" })

        // The Java null checks kept both callables optional.
        TagsDialog.show(context, tagsAdapter, null, null)
        val bare = AlertDialog.Builder.created[2]
        bare.neutralButtonListener!!.onClick(bare, DialogInterface.BUTTON_NEUTRAL)
        val bareInput = (AlertDialog.Builder.created[3].contentView as android.widget.FrameLayout).getChildAt(0) as android.widget.EditText
        bareInput.setText("more")
        AlertDialog.Builder.created[3].positiveButtonListener!!
                .onClick(AlertDialog.Builder.created[3], DialogInterface.BUTTON_POSITIVE)
        expect(tagsAdapter.getTagState("more") == true, { "the new-tag flow must survive missing callables" })
        bare.positiveButtonListener!!.onClick(bare, DialogInterface.BUTTON_POSITIVE)
    }

    check("the password dialogs, tag picker and encrypted preference keep their Java entry points") {
        val entryDialog = PasswordEntryDialog::class.java
        expect(Modifier.isPublic(entryDialog.modifiers), { "PasswordEntryDialog is not public" })
        expect(!Modifier.isFinal(entryDialog.modifiers), { "PasswordEntryDialog must stay extendable" })
        entryDialog.getConstructor(Context::class.java, PasswordEntryDialog.Mode::class.java,
                BOOLEAN_TYPE, BOOLEAN_TYPE, PasswordEntryDialog.PasswordEnteredCallback::class.java)
        expect(PasswordEntryDialog.Mode.values().map { it.name } == listOf("ENTER", "UPDATE"),
                { "Mode constants changed" })
        val callback = PasswordEntryDialog.PasswordEnteredCallback::class.java
        expect(callback.isInterface, { "PasswordEnteredCallback is not an interface" })
        expectMethod(callback, "void onPasswordEntered(String)")
        expect(View.OnClickListener::class.java.isAssignableFrom(entryDialog), { "click listener" })
        expect(TextWatcher::class.java.isAssignableFrom(entryDialog), { "text watcher" })
        expect(android.widget.TextView.OnEditorActionListener::class.java.isAssignableFrom(entryDialog),
                { "editor action listener" })

        val tags = TagsDialog::class.java
        expect(Modifier.isPublic(tags.modifiers) && Modifier.isFinal(tags.modifiers),
                { "the static-only Java class becomes a final object" })
        expect(tags.getField("INSTANCE") != null, { "TagsDialog lost its INSTANCE singleton" })
        expectMethod(tags, "static void show(Context,TagsAdapter,Callable,Callable)")

        val preference = PasswordEncryptedPreference::class.java
        expect(Modifier.isPublic(preference.modifiers), { "PasswordEncryptedPreference is not public" })
        expect(!Modifier.isFinal(preference.modifiers), { "PasswordEncryptedPreference must stay extendable" })
        preference.getConstructor(Context::class.java, android.util.AttributeSet::class.java)
        expect(PasswordEncryptedPreference.Mode.values().map { it.name } == listOf("PASSWORD", "PIN"),
                { "Mode constants changed" })
        expectMethod(preference, "void setMode(Mode)")
        expect(!Modifier.isFinal(preference.getDeclaredMethod("setMode", PasswordEncryptedPreference.Mode::class.java).modifiers),
                { "setMode must stay overridable like the Java method" })
        expect(View.OnClickListener::class.java.isAssignableFrom(preference), { "click listener" })
        expect(TextWatcher::class.java.isAssignableFrom(preference), { "text watcher" })
    }

    check("the converted vault repository keeps its Java-visible surface and fails closed") {
        val helper = DatabaseHelper::class.java
        expect(Modifier.isPublic(helper.modifiers), { "DatabaseHelper is not public" })
        // Static-only utility: the deliberate `object` delta is INSTANCE plus static forwarders.
        expectPublicFieldValue(helper, "INSTANCE", DatabaseHelper, DatabaseHelper::class.java, true)
        // The lock every vault transaction is serialized on, shared with the Java callers that
        // synchronize on DatabaseHelper.DatabaseFileLock.
        expectPublicFieldValue(helper, "DatabaseFileLock", VaultSession.MutationLock, Any::class.java, true)
        expect(DatabaseHelper.DatabaseFileLock === VaultSession.MutationLock, { "vault lock identity" })
        expectPublicFieldValue(helper, "TRANSACTION_ID", "vault_transaction_id", String::class.java, true)
        expectPublicFieldValue(helper, "PENDING_FILE", "secrets.pending", String::class.java, true)

        for (signature in listOf(
                "static boolean recoverPendingDatabase(Context)",
                "static VaultReadResult readVault(Context,SecretKey)",
                "static LoadResult loadDatabaseResult(Context,SecretKey)",
                "static ArrayList loadDatabase(Context,SecretKey)",
                "static VaultWriteResult saveVault(Context,ArrayList,SecretKey)",
                "static boolean saveDatabase(Context,ArrayList,SecretKey)",
                "static boolean changeEncryption(Context,SecretKey,SecretKey,boolean,SettingsCommit)",
                "static VaultWriteResult changeVaultEncryption(Context,SecretKey,SecretKey,boolean,VaultSettingsCommit)",
                "static boolean canInitialize(Context)",
                "static boolean backupDatabase(Context)",
                "static boolean restoreDatabaseBackup(Context)",
                "static void wipeDatabase(Context)",
                "static String entriesToString(ArrayList)",
                "static ArrayList stringToEntries(String)")) {
            expectMethod(helper, signature)
        }

        // Nested types stay named the way Java callers and the instrumentation tests refer to them.
        val status = DatabaseHelper.Status::class.java
        expect(status.isEnum, { "Status is not an enum" })
        expect(DatabaseHelper.Status.values().map { it.name } == listOf("SUCCESS", "MISSING", "MISSING_KEY",
                "AUTHENTICATION_FAILED", "CORRUPT", "IO_FAILURE"), { "Status constants changed" })
        expectField(DatabaseHelper.VaultException::class.java, "status", DatabaseHelper.Status::class.java, false)
        expect(IllegalStateException::class.java.isAssignableFrom(DatabaseHelper.VaultException::class.java),
                { "VaultException no longer is an IllegalStateException" })
        expectField(DatabaseHelper.LoadResult::class.java, "status", DatabaseHelper.Status::class.java, false)
        // Both legacy `status` members were public final fields in Java and stay fields, not getters,
        // and neither nested type gained a Java-callable constructor (Kotlin's synthetic accessor
        // for the companion factory is not callable from Java source).
        for (owner in listOf(DatabaseHelper.VaultException::class.java, DatabaseHelper.LoadResult::class.java)) {
            val statusField = field(owner, "status")!!
            expect(Modifier.isPublic(statusField.modifiers) && Modifier.isFinal(statusField.modifiers),
                    { "${owner.simpleName}.status is no longer a public final field" })
            expect(owner.declaredMethods.none { it.name == "getStatus" },
                    { "${owner.simpleName} grew a getStatus() accessor" })
            expect(owner.declaredConstructors.none { !it.isSynthetic && !Modifier.isPrivate(it.modifiers) },
                    { "${owner.simpleName} gained a Java-callable constructor" })
        }
        expect(AutoCloseable::class.java.isAssignableFrom(DatabaseHelper.LoadResult::class.java),
                { "LoadResult is not AutoCloseable" })
        expectMethod(DatabaseHelper.LoadResult::class.java, "boolean isSuccess()")
        expectMethod(DatabaseHelper.LoadResult::class.java, "ArrayList requireEntries()")
        expectMethod(DatabaseHelper.LoadResult::class.java, "void close()")
        // Both metadata callbacks stay single-method interfaces so Java lambdas keep compiling.
        for (callback in listOf(DatabaseHelper.SettingsCommit::class.java,
                DatabaseHelper.VaultSettingsCommit::class.java)) {
            expect(callback.isInterface, { "${callback.simpleName} is not an interface" })
            expect(callback.declaredMethods.count { Modifier.isPublic(it.modifiers) } == 1,
                    { "${callback.simpleName} is no longer a single-method interface" })
        }
        expectMethod(DatabaseHelper.SettingsCommit::class.java, "boolean commit(String)")
        expectMethod(DatabaseHelper.VaultSettingsCommit::class.java, "VaultCommitDecision commit(String)")

        // Every read error keeps a legacy Status, including the mapping the instrumented
        // "failed preference commit" test depends on, and an empty vault is Loaded(empty).
        expect(DatabaseHelper.LoadResult.create(VaultReadResult.Unavailable(VaultReadError.MISSING)).status
                == DatabaseHelper.Status.MISSING, { "MISSING mapping" })
        expect(DatabaseHelper.LoadResult.create(VaultReadResult.Unavailable(VaultReadError.MISSING_KEY)).status
                == DatabaseHelper.Status.MISSING_KEY, { "MISSING_KEY mapping" })
        expect(DatabaseHelper.LoadResult.create(VaultReadResult.Unavailable(VaultReadError.AUTHENTICATION_FAILED)).status
                == DatabaseHelper.Status.AUTHENTICATION_FAILED, { "AUTHENTICATION_FAILED mapping" })
        expect(DatabaseHelper.LoadResult.create(VaultReadResult.Unavailable(VaultReadError.CORRUPT)).status
                == DatabaseHelper.Status.CORRUPT, { "CORRUPT mapping" })
        expect(DatabaseHelper.LoadResult.create(VaultReadResult.Unavailable(VaultReadError.IO_FAILURE)).status
                == DatabaseHelper.Status.IO_FAILURE, { "IO_FAILURE mapping" })
        // The legacy status set has no recovery value, and the instrumented test that wipes a failed
        // preference commit expects IO_FAILURE rather than a thrown IllegalArgumentException.
        expect(DatabaseHelper.LoadResult.create(VaultReadResult.Unavailable(VaultReadError.RECOVERY_REQUIRED)).status
                == DatabaseHelper.Status.IO_FAILURE, { "RECOVERY_REQUIRED mapping" })

        val missing = DatabaseHelper.LoadResult.create(VaultReadResult.Unavailable(VaultReadError.MISSING))
        expect(!missing.isSuccess(), { "an unavailable vault reported itself as loaded" })
        missing.close()
        missing.close()
        val empty = DatabaseHelper.LoadResult.create(VaultReadResult.Loaded(ArrayList()))
        expect(empty.status == DatabaseHelper.Status.SUCCESS && empty.isSuccess(), { "empty vault load" })
        expect(empty.requireEntries().isEmpty(), { "empty vault entries" })
        empty.close()

        val unavailable = DatabaseHelper.LoadResult.create(VaultReadResult.Unavailable(VaultReadError.AUTHENTICATION_FAILED))
        val raised = try {
            unavailable.requireEntries()
            null
        } catch (error: DatabaseHelper.VaultException) {
            error
        }
        expect(raised?.status == DatabaseHelper.Status.AUTHENTICATION_FAILED, { "VaultException status" })
        expect(raised?.message == "Vault unavailable: AUTHENTICATION_FAILED",
                { "VaultException message: ${raised?.message}" })

        // The document boundary fails closed on the desktop JVM too: every rejection is a
        // VaultException with CORRUPT, never a short/empty list that could overwrite a vault.
        fun expectCorrupt(data: String?) {
            val error = try {
                DatabaseHelper.stringToEntries(data)
                null
            } catch (thrown: DatabaseHelper.VaultException) {
                thrown
            }
            expect(error?.status == DatabaseHelper.Status.CORRUPT, { "expected CORRUPT for [$data], got $error" })
        }
        expectCorrupt(null)
        expectCorrupt("")
        expectCorrupt("{")
        expectCorrupt("{\"secret\":\"GEZDGNBVGY3TQOJQ\"}")   // an object, not an array of entries
        expectCorrupt("[] []")                                    // trailing content after the array
        expectCorrupt("[{'secret':'GEZDGNBVGY3TQOJQ'}]")          // the vault format is double-quoted
        expect(DatabaseHelper.stringToEntries("[]").isEmpty(), { "an empty vault document is an empty list" })
        expect(DatabaseHelper.stringToEntries(" [ ] ").isEmpty(), { "whitespace around the array" })

        // Caller errors stay caller errors: they must not be reported as a corrupt vault.
        expectThrows(IllegalArgumentException::class.java) { DatabaseHelper.entriesToString(null) }
        expect(DatabaseHelper.entriesToString(ArrayList()) == "[]", { "empty vault serializes to []" })
    }


    check("the vault document round-trips real entries and rejects a partial document whole") {
        // Entry and its thumbnail catalogue are both Kotlin now, so the repository's document path
        // runs over real entries here; the encrypted file transaction around it still does not.
        val stored = "[$STORED_ENTRY_JSON]"
        val entries = DatabaseHelper.stringToEntries(stored)
        try {
            expect(entries.size == 1, { "expected one entry, got ${entries.size}" })
            expect(entries[0].getLabel() == "5 von 5 Sterne", { "label ${entries[0].getLabel()}" })
            expect(entries[0].getTags() == listOf("test1", "test2"), { "tags ${entries[0].getTags()}" })
            // The writer reproduces the stored document byte for byte: field order and defaults.
            val rewritten = DatabaseHelper.entriesToString(entries)
            expect(rewritten == stored, { "rewritten vault differs: $rewritten" })
        } finally {
            for (entry in entries) entry.destroy()
        }
        // One bad entry rejects the whole document; the valid prefix is never returned.
        for (document in listOf("[$STORED_ENTRY_JSON,{}]",
                "[$STORED_ENTRY_JSON,{\"secret\":\"\"}]",
                "[$STORED_ENTRY_JSON,1]",
                "[$STORED_ENTRY_JSON")) {
            val error = try {
                DatabaseHelper.stringToEntries(document)
                null
            } catch (thrown: DatabaseHelper.VaultException) {
                thrown
            }
            expect(error?.status == DatabaseHelper.Status.CORRUPT, { "expected CORRUPT for [$document], got $error" })
        }
        // A destroyed (seedless) entry cannot be written: CORRUPT, never a partial document.
        val destroyed = DatabaseHelper.stringToEntries(stored)
        destroyed[0].destroy()
        val writeError = try {
            DatabaseHelper.entriesToString(destroyed)
            null
        } catch (thrown: DatabaseHelper.VaultException) {
            thrown
        }
        expect(writeError?.status == DatabaseHelper.Status.CORRUPT, { "destroyed entry write: $writeError" })
        // The entry-count bound is a caller error, checked before any entry is serialized.
        val oversized = ArrayList(java.util.Collections.nCopies(InputPolicy.MAX_ENTRIES + 1, destroyed[0]))
        expectThrows(IllegalArgumentException::class.java) { DatabaseHelper.entriesToString(oversized) }
    }

    check("a poisoned vault transaction short-circuits every entry point before storage") {
        // `transactionPoisoned` is what an ambiguous metadata commit leaves behind; that commit path
        // needs FileHelper over the AtomicFile stand-in (which refuses) and SharedPreferences, so the
        // flag is set directly. Every entry point below must then return before touching storage:
        // reaching FileHelper would fail with UnsupportedOperationException, so a dropped guard
        // cannot pass silently.
        val poison = DatabaseHelper::class.java.getDeclaredField("transactionPoisoned")
        expect(Modifier.isPrivate(poison.modifiers) && Modifier.isStatic(poison.modifiers) &&
                poison.type == BOOLEAN_TYPE, { "poison flag shape changed" })
        poison.isAccessible = true
        val context = Context()
        val key = SecretKeySpec(ByteArray(32), "AES")
        val commits = AtomicInteger()
        poison.setBoolean(null, true)
        try {
            expect(!DatabaseHelper.recoverPendingDatabase(context), { "recovery ran while poisoned" })
            val read = DatabaseHelper.readVault(context, key)
            expect(read == VaultReadResult.Unavailable(VaultReadError.RECOVERY_REQUIRED), { "read: $read" })
            DatabaseHelper.loadDatabaseResult(context, key).use { legacy ->
                expect(legacy.status == DatabaseHelper.Status.IO_FAILURE, { "legacy load: ${legacy.status}" })
            }
            val saved = DatabaseHelper.saveVault(context, ArrayList(), key)
            expect(saved == VaultWriteResult.RecoveryRequired(VaultCommitState.UNKNOWN), { "save: $saved" })
            expect(VaultSession.snapshot() is SessionState.Failed, { "a poisoned write left the session usable" })
            // A null list is accepted at the boundary (the Java reported it after the read).
            expect(!DatabaseHelper.saveDatabase(context, null, key), { "legacy save while poisoned" })
            val changed = DatabaseHelper.changeVaultEncryption(context, key, key, false) {
                commits.incrementAndGet()
                VaultCommitDecision.COMMITTED
            }
            expect(changed == VaultWriteResult.RecoveryRequired(VaultCommitState.UNKNOWN), { "change: $changed" })
            expect(!DatabaseHelper.changeEncryption(context, key, key, true) { commits.incrementAndGet() > 0 },
                    { "legacy change while poisoned" })
            expect(commits.get() == 0, { "a metadata commit ran while poisoned" })
            expect(!DatabaseHelper.canInitialize(context), { "initialization allowed while poisoned" })
            expect(!DatabaseHelper.restoreDatabaseBackup(context), { "restore ran while poisoned" })
        } finally {
            poison.setBoolean(null, false)
            VaultSession.lock(SessionLockReason.RESET) // leave no failed session behind for later checks
        }
    }

    check("live metadata callbacks defer Settings startup recovery and migration") {
        // The real callback/promotion sequence is an Android test. Here the guard is set directly
        // to check that reentrant Settings construction and repository reads stay out of storage.
        val active = DatabaseHelper::class.java.getDeclaredField("metadataCommitInProgress")
        active.isAccessible = true
        val previous = active.getBoolean(null)
        val context = SettingsContext()
        PreferenceManager.reset()
        val store = PreferenceManager.getDefaultSharedPreferences(context)
        store.edit().putString("perf_theme_mode", "manual").commit()
        active.setBoolean(null, true)
        try {
            expect(!DatabaseHelper.recoverPendingDatabase(context), { "live journal treated as abandoned" })
            Settings(context)
            expect(store.contains("perf_theme_mode"), { "Settings migrated during a live metadata callback" })
            expect(DatabaseHelper.readVault(context, null) ==
                    VaultReadResult.Unavailable(VaultReadError.RECOVERY_REQUIRED), { "read during commit" })
            expect(!DatabaseHelper.canInitialize(context), { "nested initialization during commit" })
        } finally {
            active.setBoolean(null, previous)
            PreferenceManager.reset()
        }
    }

    check("document writes are content-only, bounded and re-check permission around every chunk") {
        val directory = java.nio.file.Files.createTempDirectory("andotp-sandbox").toFile()
        try {
            val resolver = FakeResolver()
            val context = FakeStorageContext(resolver, File(directory, "cache"))
            val document = File(directory, "backup.json.aes")
            val uri = contentUri("document", "backup.json.aes")
            val data = ByteArray(20000) { (it % 251).toByte() }

            // Three 8 KiB chunks: asked before opening, after opening, before each chunk and after the flush.
            resolver.target = document
            val permission = ScriptedPermission()
            expect(StorageAccessHelper.saveFile(context, uri, data, permission), { "a permitted write failed" })
            expect(document.readBytes().contentEquals(data), { "written bytes differ" })
            expect(permission.asked == 6, { "permission asked ${permission.asked} times, expected 6" })
            expect(resolver.calls == listOf("open wt"), { "provider calls ${resolver.calls}" })
            expect(!resolver.descriptor!!.getFileDescriptor().valid(), { "descriptor left open after a write" })

            // Revoked before the third chunk: false, the write stops there and nothing stays open.
            val revoked = ScriptedPermission(refuseAt = 5)
            expect(!StorageAccessHelper.saveFile(context, uri, data, revoked), { "a revoked write reported success" })
            expect(document.length() == 16384L, { "revocation wrote ${document.length()} bytes" })
            expect(revoked.asked == 5, { "a revoked write kept asking: ${revoked.asked}" })
            expect(!resolver.descriptor!!.getFileDescriptor().valid(), { "descriptor left open after revocation" })

            // Refused right after opening (nothing written) and after the final flush (all written, still false).
            expect(!StorageAccessHelper.saveFile(context, uri, data, ScriptedPermission(refuseAt = 2)), { "after-open refusal" })
            expect(document.length() == 0L, { "after-open refusal wrote ${document.length()} bytes" })
            expect(!StorageAccessHelper.saveFile(context, uri, data, ScriptedPermission(refuseAt = 6)), { "final refusal" })
            expect(document.length() == data.size.toLong(), { "final refusal must not truncate what was written" })

            // An empty document is opened, flushed and permitted three times; the String overload is UTF-8.
            val empty = ScriptedPermission()
            expect(StorageAccessHelper.saveFile(context, uri, ByteArray(0), empty) && empty.asked == 3, { "empty write" })
            expect(StorageAccessHelper.saveFile(context, uri, "Grüße"), { "String overload" })
            expect(document.readText(Charsets.UTF_8) == "Grüße", { "String overload encoding" })

            // Refused before the provider is touched: the scheme, the bounds, a missing URI/data,
            // and a permission that is already gone.
            resolver.calls.clear()
            val longUri = contentUri("x".repeat(InputPolicy.MAX_URI_CHARS))
            for ((label, refused) in listOf(
                    "null uri" to { StorageAccessHelper.saveFile(context, null, data) },
                    "null data" to { StorageAccessHelper.saveFile(context, uri, null as ByteArray?) },
                    "file scheme" to { StorageAccessHelper.saveFile(context, fileUri(document.path), data) },
                    "long uri" to { StorageAccessHelper.saveFile(context, longUri, data) },
                    "oversized" to { StorageAccessHelper.saveFile(context, uri, ByteArray(BoundedInputReader.MAX_DOCUMENT_BYTES + 1)) },
                    "not permitted" to { StorageAccessHelper.saveFile(context, uri, data, ScriptedPermission(refuseAt = 1)) })) {
                expect(!refused(), { "$label was written" })
            }
            expect(resolver.calls.isEmpty(), { "the provider was reached by a refused write: ${resolver.calls}" })
            // The exact bound is still accepted.
            expect(StorageAccessHelper.saveFile(context, uri, ByteArray(BoundedInputReader.MAX_DOCUMENT_BYTES)), { "exact bound" })

            // Provider failures are false, never an exception: no descriptor, or a SecurityException.
            resolver.target = null
            expect(!StorageAccessHelper.saveFile(context, uri, data), { "a missing descriptor reported success" })
            resolver.failure = SecurityException("revoked grant")
            expect(!StorageAccessHelper.saveFile(context, uri, data), { "a provider exception reported success" })
        } finally {
            directory.deleteRecursively()
        }
    }

    check("document reads are bounded and file URIs stay confined to the private cache") {
        val directory = java.nio.file.Files.createTempDirectory("andotp-sandbox").toFile()
        try {
            val resolver = FakeResolver()
            val cache = File(directory, "cache").apply { mkdirs() }
            val context = FakeStorageContext(resolver, cache)
            val bytes = "{\"version\":1}".toByteArray(Charsets.UTF_8)

            // A content document is read through the provider, and the provider's stream is closed.
            val stream = ClosingStream(bytes)
            resolver.input = stream
            expect(StorageAccessHelper.loadFile(context, contentUri("document", "a")).contentEquals(bytes), { "content read" })
            expect(stream.closed, { "the provider stream was left open" })
            resolver.input = ByteArrayInputStream(bytes)
            expect(StorageAccessHelper.loadFileString(context, contentUri("document", "a")) == "{\"version\":1}", { "text read" })

            // A cache fixture is allowed, through the same provider path.
            resolver.input = ByteArrayInputStream(bytes)
            expect(StorageAccessHelper.loadFile(context, fileUri(File(cache, "fixture.png").path)).contentEquals(bytes),
                    { "a private cache fixture was refused" })

            // Everything else is an IOException before the provider is asked for a stream.
            resolver.calls.clear()
            val sibling = File(directory, "cache-evil/fixture.png").path      // shares the cache prefix
            val traversal = cache.path + "/../secrets.dat"                     // canonicalized out of the cache
            for ((label, uri) in listOf(
                    "null" to null,
                    "long" to contentUri("x".repeat(InputPolicy.MAX_URI_CHARS)),
                    "http" to Uri.Builder().scheme("https").authority("example.org").appendPath("a").build(),
                    "outside" to fileUri(File(directory, "secrets.dat").path),
                    "sibling" to fileUri(sibling),
                    "traversal" to fileUri(traversal),
                    "cache itself" to fileUri(cache.path),
                    "empty path" to Uri.Builder().scheme("file").build())) {
                expectThrows(IOException::class.java) { StorageAccessHelper.loadFile(context, uri) }
                expect(StorageAccessHelper.loadFileString(context, uri) == "", { "$label produced text" })
            }
            expect(resolver.calls.isEmpty(), { "a refused URI reached the provider: ${resolver.calls}" })

            // The byte limit holds regardless of what the provider reports or streams.
            resolver.input = ZeroStream(BoundedInputReader.MAX_DOCUMENT_BYTES + 1L)
            expectThrows(IOException::class.java) { StorageAccessHelper.loadFile(context, contentUri("big")) }
            resolver.input = null
            expectThrows(IOException::class.java) { StorageAccessHelper.loadFile(context, contentUri("none")) }
            // Provider exceptions propagate from loadFile and become "" in loadFileString.
            resolver.failure = SecurityException("revoked grant")
            expectThrows(SecurityException::class.java) { StorageAccessHelper.loadFile(context, contentUri("a")) }
            expect(StorageAccessHelper.loadFileString(context, contentUri("a")) == "", { "provider failure produced text" })
        } finally {
            directory.deleteRecursively()
        }
    }

    check("document display names are advisory, bounded and lose ASCII control characters") {
        val resolver = FakeResolver()
        val context = FakeStorageContext(resolver, File("."))
        val uri = contentUri("document", "last-segment.json")

        val cursor = FakeCursor("My\u0000Backup\n\t.json\u007F")
        resolver.cursor = cursor
        expect(StorageAccessHelper.getContentFileName(context, uri) == "MyBackup.json", { "control characters kept" })
        expect(cursor.closed, { "the metadata cursor was left open" })
        expect(resolver.projection == listOf(OpenableColumns.DISPLAY_NAME), { "projection ${resolver.projection}" })
        // \p{Cntrl} is the ASCII set of java.util.regex: a C1 control such as U+0085 is not stripped.
        resolver.cursor = FakeCursor("name\u0085")
        expect(StorageAccessHelper.getContentFileName(context, uri) == "name\u0085", { "C1 control handling changed" })

        // Unusable metadata falls back to the last path segment.
        for (unusable in listOf<Cursor?>(null, FakeCursor("x", hasRow = false), FakeCursor("x", column = -1), FakeCursor(null))) {
            resolver.cursor = unusable
            expect(StorageAccessHelper.getContentFileName(context, uri) == "last-segment.json", { "fallback for $unusable" })
        }
        resolver.failure = IllegalStateException("provider died")
        expect(StorageAccessHelper.getContentFileName(context, uri) == "last-segment.json", { "query failure" })
        resolver.failure = null

        // The length bound applies before stripping; bad input yields the "backup" placeholder.
        resolver.cursor = FakeCursor("\u0000".repeat(InputPolicy.MAX_TEXT_CHARS + 1))
        expect(StorageAccessHelper.getContentFileName(context, uri) == "backup", { "the bound was applied after stripping" })
        resolver.cursor = FakeCursor("n".repeat(InputPolicy.MAX_TEXT_CHARS))
        expect(StorageAccessHelper.getContentFileName(context, uri) == "n".repeat(InputPolicy.MAX_TEXT_CHARS), { "exact bound" })
        resolver.cursor = null
        expect(StorageAccessHelper.getContentFileName(context, null) == "backup", { "null uri" })
        expect(StorageAccessHelper.getContentFileName(context, contentUri("x".repeat(InputPolicy.MAX_URI_CHARS))) == "backup",
                { "long uri" })
        expect(StorageAccessHelper.getContentFileName(context, Uri.Builder().scheme("content").authority("a").build()) == "backup",
                { "no metadata and no path segment" })

        // Only content URIs are queried; a file URI goes straight to its last segment.
        resolver.calls.clear()
        expect(StorageAccessHelper.getContentFileName(context, fileUri("/data/cache/plain.png")) == "plain.png", { "file name" })
        expect(resolver.calls.isEmpty(), { "a file URI was queried: ${resolver.calls}" })
    }

    check("the storage and backup helpers keep their static Java surface") {
        val storage = StorageAccessHelper::class.java
        expectPublicFieldValue(storage, "INSTANCE", StorageAccessHelper, storage, true)
        for (signature in listOf(
                "static boolean saveFile(Context,Uri,byte[])",
                "static boolean saveFile(Context,Uri,byte[],BooleanSupplier)",
                "static boolean saveFile(Context,Uri,String)",
                "static String getContentFileName(Context,Uri)",
                "static byte[] loadFile(Context,Uri)",
                "static String loadFileString(Context,Uri)")) {
            expectMethod(storage, signature)
        }
        // Java callers (ScanQRCodeFromFile) catch IOException around loadFile, so it must stay declared.
        val load = storage.getMethod("loadFile", Context::class.java, Uri::class.java)
        expect(load.exceptionTypes.toList() == listOf(IOException::class.java), { "loadFile throws ${load.exceptionTypes.toList()}" })
        expect(storage.declaredMethods.filter { Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) }
                .none { it.exceptionTypes.isNotEmpty() && it.name != "loadFile" }, { "an unexpected checked exception appeared" })

        // BackupHelper's methods are not reflected here (the Python guards pin their names and
        // signatures); the nested result type is, and it stays a mutable,
        // extendable Java-style holder with public fields and a public no-argument constructor.
        val backupFile = BackupHelper.BackupFile::class.java
        expect(!Modifier.isFinal(backupFile.modifiers) && Modifier.isStatic(backupFile.modifiers),
                { "BackupFile is final or no longer a static nested class" })
        expect(backupFile.enclosingClass == BackupHelper::class.java, { "BackupFile moved" })
        for ((name, fieldType) in listOf("file" to androidx.documentfile.provider.DocumentFile::class.java, "errorMessage" to INT_TYPE)) {
            val found = field(backupFile, name)!!
            expect(found.type == fieldType && Modifier.isPublic(found.modifiers) && !Modifier.isFinal(found.modifiers) &&
                    !Modifier.isStatic(found.modifiers), { "BackupFile.$name is no longer a public mutable field" })
        }
        val fresh = BackupHelper.BackupFile()
        expect(fresh.file == null && fresh.errorMessage == 0, { "BackupFile defaults changed" })
        expect(backupFile.declaredMethods.none { it.name.startsWith("get") || it.name.startsWith("set") },
                { "BackupFile grew accessors instead of fields" })
    }

    check("QR image decoding keeps its bounds, two decode passes and the last failure's message") {
        val resolver = FakeResolver()
        val context = FakeStorageContext(resolver, File("."))
        val image = contentUri("document", "qr.png")
        val decodes = ArrayList<Boolean>()        // inJustDecodeBounds of every BitmapFactory call
        var fullDecodeConfig: Bitmap.Config? = null
        fun serve(boundsWidth: Int, boundsHeight: Int, bitmap: Bitmap?) {
            resolver.input = ByteArrayInputStream(byteArrayOf(1, 2, 3))
            decodes.clear()
            BitmapFactory.decoder = { bounds, options ->
                decodes.add(bounds)
                if (bounds) {
                    options.outWidth = boundsWidth
                    options.outHeight = boundsHeight
                    null
                } else {
                    fullDecodeConfig = options.inPreferredConfig
                    bitmap
                }
            }
        }
        fun scan(uri: Uri? = image): Pair<String?, List<Int>> {
            Toast.shown.clear()
            QRCodeReader.calls.clear()
            val contents = ScanQRCodeFromFile.scanQRImage(context, uri)
            return contents to ArrayList(Toast.shown)
        }
        val loadError = null to listOf(R.string.toast_file_load_error)
        val stderr = System.err
        val printed = java.io.ByteArrayOutputStream()
        System.setErr(java.io.PrintStream(printed, true))
        try {
            // Unreadable documents: an IOException (no URI) and a provider RuntimeException.
            serve(4, 3, Bitmap(4, 3))
            expect(scan(null) == loadError, { "missing document" })
            resolver.failure = SecurityException("revoked grant")
            expect(scan() == loadError, { "provider failure" })
            resolver.failure = null
            expect(decodes.isEmpty(), { "an unreadable document reached the decoder" })

            // Bounds are checked before the full decode: undecodable, empty and oversized images.
            for ((width, height) in listOf(-1 to -1, 0 to 3, InputPolicy.MAX_IMAGE_DIMENSION + 1 to 3, 3000 to 3000)) {
                serve(width, height, Bitmap(4, 3))
                expect(scan() == loadError, { "bounds ${width}x$height were accepted" })
                expect(decodes == listOf(true), { "the full decode ran after rejected bounds ${width}x$height" })
            }
            // The full decode may still fail, or disagree with the bounds; the bitmap is recycled.
            serve(4, 3, null)
            expect(scan() == loadError && decodes == listOf(true, false), { "a failed full decode" })
            val shrunk = Bitmap(0, 3)
            serve(4, 3, shrunk)
            expect(scan() == loadError && shrunk.isRecycled(), { "an invalid decoded bitmap was kept" })
            expect(QRCodeReader.calls.isEmpty(), { "a rejected image reached the QR reader" })

            // First pass: ARGB_8888 pixels copied row by row, the bitmap recycled, QR-only hints.
            val pixels = IntArray(12) { it * 7 + 1 }
            val bitmap = Bitmap(4, 3, pixels)
            serve(4, 3, bitmap)
            QRCodeReader.script.add(com.google.zxing.Result("otpauth://totp/synthetic?secret=GEZDGNBVGY3TQOJQ"))
            expect(scan() == ("otpauth://totp/synthetic?secret=GEZDGNBVGY3TQOJQ" to emptyList<Int>()), { "first pass" })
            expect(bitmap.isRecycled() && fullDecodeConfig == Bitmap.Config.ARGB_8888, { "decode config or recycling" })
            expect(QRCodeReader.calls.size == 1, { "a successful first pass was retried" })
            val (decoded, firstHints) = QRCodeReader.calls[0]
            val source = decoded.binarizerForChecks().getLuminanceSource() as RGBLuminanceSource
            expect(source.getWidth() == 4 && source.getHeight() == 3 && source.pixels.contentEquals(pixels),
                    { "the luminance source does not carry the bitmap's pixels" })
            expect(firstHints == mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)),
                    { "first-pass hints $firstHints" })

            // Second pass with TRY_HARDER on the same binary bitmap, after a failure or a textless result.
            for (first in listOf<Any>(NotFoundException.getNotFoundInstance(), com.google.zxing.Result(null))) {
                serve(4, 3, Bitmap(4, 3, IntArray(12)))
                QRCodeReader.script.add(first)
                QRCodeReader.script.add(com.google.zxing.Result("second"))
                expect(scan() == ("second" to emptyList<Int>()), { "retry after $first" })
                expect(QRCodeReader.calls.size == 2 && QRCodeReader.calls[0].first === QRCodeReader.calls[1].first,
                        { "the retry decoded a different bitmap" })
                expect(QRCodeReader.calls[1].second == mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                        DecodeHintType.TRY_HARDER to true), { "retry hints ${QRCodeReader.calls[1].second}" })
            }

            // Both passes fail: the last ReaderException decides the message; none means "not found".
            val checksum = ChecksumException.getChecksumInstance()
            val format = FormatException.getFormatInstance()
            val notFound = NotFoundException.getNotFoundInstance()
            for ((outcomes, message) in listOf(
                    listOf<Any>(checksum, format) to R.string.toast_qr_format_error,
                    listOf<Any>(format, checksum) to R.string.toast_qr_checksum_exception,
                    listOf<Any>(notFound, notFound) to R.string.toast_qr_error,
                    listOf<Any>(checksum, com.google.zxing.Result(null)) to R.string.toast_qr_checksum_exception,
                    listOf<Any>(com.google.zxing.Result(null), com.google.zxing.Result(null)) to R.string.toast_qr_error)) {
                serve(4, 3, Bitmap(4, 3, IntArray(12)))
                QRCodeReader.script.addAll(outcomes)
                expect(scan() == (null to listOf(message)), { "failure message for $outcomes" })
            }
            expect(R.string.toast_qr_error != R.string.toast_qr_format_error, { "resource stand-ins are not distinct" })
            // The Java printed each reported failure's stack trace; that is kept.
            expect(printed.toString().contains("com.google.zxing.FormatException") &&
                    printed.toString().contains("com.google.zxing.NotFoundException"), { "stack traces no longer printed" })
        } finally {
            System.setErr(stderr)
            BitmapFactory.decoder = null
            QRCodeReader.script.clear()
            QRCodeReader.calls.clear()
            Toast.shown.clear()
        }
    }

    check("the notification permission request is gated on Android 13 and a missing grant") {
        class FakeActivity(private val granted: Boolean) : android.app.Activity() {
            override fun checkSelfPermission(permission: String): Int =
                if (granted && permission == android.Manifest.permission.POST_NOTIFICATIONS) PackageManager.PERMISSION_GRANTED
                else PackageManager.PERMISSION_DENIED
        }
        class FakeFragment(private val hostActivity: android.app.Activity?) : android.app.Fragment() {
            override fun getActivity(): android.app.Activity? = hostActivity
        }
        val sdk = android.os.Build.VERSION.SDK_INT
        try {
            android.os.Build.VERSION.SDK_INT = 32
            val beforeTiramisu = FakeFragment(FakeActivity(granted = false))
            NotificationHelper.requestNotificationPermission(beforeTiramisu)
            expect(beforeTiramisu.requested.isEmpty(), { "asked for a runtime permission before Android 13" })

            android.os.Build.VERSION.SDK_INT = 33
            val missing = FakeFragment(FakeActivity(granted = false))
            NotificationHelper.requestNotificationPermission(missing)
            expect(missing.requested == listOf(listOf("android.permission.POST_NOTIFICATIONS") to 1),
                    { "request ${missing.requested}" })
            val granted = FakeFragment(FakeActivity(granted = true))
            NotificationHelper.requestNotificationPermission(granted)
            expect(granted.requested.isEmpty(), { "asked again although granted" })
            val detached = FakeFragment(null)
            NotificationHelper.requestNotificationPermission(detached)
            expect(detached.requested.isEmpty(), { "a detached fragment asked" })
        } finally {
            android.os.Build.VERSION.SDK_INT = sdk
        }
    }

    check("the QR and notification helpers keep their static Java surface") {
        val qr = ScanQRCodeFromFile::class.java
        expectPublicFieldValue(qr, "INSTANCE", ScanQRCodeFromFile, qr, true)
        expect(publicMethods(qr) == listOf("static String scanQRImage(Context,Uri)"), { "QR surface ${publicMethods(qr)}" })
        // The hint maps are built once: QR only, and the retry map is a copy that adds TRY_HARDER.
        fun hints(name: String): Map<*, *> = field(qr, name)!!.let {
            it.isAccessible = true
            it.get(null) as Map<*, *>
        }
        val normal = hints("HINTS")
        val harder = hints("HINTS_HARDER")
        expect(normal is java.util.EnumMap<*, *> && harder is java.util.EnumMap<*, *> && normal !== harder,
                { "hint maps are not separate EnumMaps" })
        expect(normal[DecodeHintType.POSSIBLE_FORMATS] is java.util.Vector<*>, { "barcode formats container changed" })
        expect(normal == mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)), { "normal hints $normal" })
        expect(harder == mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true), { "harder hints $harder" })

        // NotificationHelper's channel and notification members are not reflected here (the Python
        // guards pin them); its fields are.
        val notifications = NotificationHelper::class.java
        expectPublicFieldValue(notifications, "INSTANCE", NotificationHelper, notifications, true)
        val permission = field(notifications, "POST_NOTIFICATIONS_PERMISSION")!!
        permission.isAccessible = true
        expect(Modifier.isPrivate(permission.modifiers) && permission.get(null) == "android.permission.POST_NOTIFICATIONS",
                { "permission constant changed" })
    }

    check("bounded document reads reject oversize input and keep the caller's stream") {
        expect(BoundedInputReader.read(ByteArrayInputStream(ByteArray(0)), 0).isEmpty(), { "empty" })
        val exact = byteArrayOf(0, 1, 255.toByte())
        expect(BoundedInputReader.read(ByteArrayInputStream(exact), exact.size).contentEquals(exact), { "exact" })
        expectThrows(IOException::class.java) { BoundedInputReader.read(ByteArrayInputStream(ByteArray(4)), 3) }
        expectThrows(IOException::class.java) { BoundedInputReader.read(ByteArrayInputStream(ByteArray(1)), 0) }
        expectThrows(IOException::class.java) { BoundedInputReader.read(null, 10) }
        expectThrows(IllegalArgumentException::class.java) { BoundedInputReader.read(ByteArrayInputStream(ByteArray(0)), -1) }
        expectThrows(IllegalArgumentException::class.java) { BoundedInputReader.read(ByteArrayInputStream(ByteArray(0)), Int.MAX_VALUE) }

        var consumed = 0
        val unending = object : InputStream() {
            override fun read(): Int {
                consumed++
                return 7
            }
        }
        expectThrows(IOException::class.java) { BoundedInputReader.read(unending, 9000) }
        expect(consumed == 9001, { "consumed $consumed" })

        val data = ByteArray(9000) { 7 }
        val shortReader = object : ByteArrayInputStream(data) {
            private var returnZero = true
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                if (returnZero) {
                    returnZero = false
                    return 0
                }
                return super.read(bytes, offset, minOf(count, 3))
            }
        }
        expect(BoundedInputReader.read(shortReader, data.size).contentEquals(data), { "short reads" })

        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("provider failed")
        }
        expectThrows(IOException::class.java) { BoundedInputReader.read(broken, 10) }

        var closed = false
        val owned = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() {
                closed = true
            }
        }
        BoundedInputReader.read(owned, 1)
        expect(!closed, { "caller stream was closed" })
    }

    check("bounded provider output cannot become partial data") {
        val output = BoundedOutput(3)
        output.write(byteArrayOf(1, 2, 3), 0, 3)
        expectThrows(IllegalStateException::class.java) { output.write(4) }
        expect(output.overflowed(), { "overflow flag" })
        expect(output.size() == 3, { "size ${output.size()}" })

        val fresh = BoundedOutput(0)
        expect(!fresh.overflowed(), { "flag before writes" })
        fresh.write(ByteArray(0), 0, 0)
        expectThrows(IllegalStateException::class.java) { fresh.write(byteArrayOf(1), 0, 1) }
        expect(fresh.overflowed() && fresh.size() == 0, { "size after rejected write" })
        expectThrows(IllegalArgumentException::class.java) { BoundedOutput(-1) }

        val negative = BoundedOutput(2)
        expectThrows(IllegalStateException::class.java) { negative.write(byteArrayOf(1), 0, -1) }
        expect(negative.overflowed(), { "negative length counts as overflow" })
        // A negative offset is still the superclass's business (platform IndexOutOfBoundsException).
        val offset = BoundedOutput(2)
        expectThrows(IndexOutOfBoundsException::class.java) { offset.write(byteArrayOf(1), -1, 1) }
    }

    check("encrypted backup header framing is validated before derivation") {
        fun backup(iterations: Int): ByteArray {
            val data = ByteArray(EncryptedBackupHeader.MIN_BACKUP_BYTES)
            ByteBuffer.wrap(data).putInt(iterations)
            Arrays.fill(data, 4, data.size, 7.toByte())
            return data
        }
        expect(EncryptedBackupHeader.MIN_BACKUP_BYTES == 44, { "minimum size" })
        for (count in intArrayOf(1, 10000, 140000, 160000, EncryptedBackupHeader.MAX_ITERATIONS)) {
            val header = EncryptedBackupHeader.parse(backup(count))
            expect(header.getIterations() == count, { "iterations $count" })
            expect(header.getSalt().size == 12, { "salt size" })
            expect(header.getEncrypted().size == 28, { "cipher size" })
        }
        expectThrows(IllegalArgumentException::class.java) { EncryptedBackupHeader.parse(null) }
        for (length in 0 until EncryptedBackupHeader.MIN_BACKUP_BYTES) {
            val truncated = Arrays.copyOf(backup(140000), length)
            expectThrows(IllegalArgumentException::class.java) { EncryptedBackupHeader.parse(truncated) }
        }
        for (count in intArrayOf(Int.MIN_VALUE, -1, 0, EncryptedBackupHeader.MAX_ITERATIONS + 1, Int.MAX_VALUE)) {
            expectThrows(IllegalArgumentException::class.java) { EncryptedBackupHeader.parse(backup(count)) }
        }
        val oversized = ByteArray(BoundedInputReader.MAX_DOCUMENT_BYTES + 1)
        ByteBuffer.wrap(oversized).putInt(140000)
        expectThrows(IllegalArgumentException::class.java) { EncryptedBackupHeader.parse(oversized) }

        val data = backup(140000)
        val header = EncryptedBackupHeader.parse(data)
        Arrays.fill(data, 0)
        expect(header.getIterations() == 140000, { "iterations after source wipe" })
        expect(header.getSalt()[0] == 7.toByte(), { "salt after source wipe" })
        expect(header.getEncrypted()[0] == 7.toByte(), { "cipher after source wipe" })
        header.getSalt()[0] = 0
        header.getEncrypted()[0] = 0
        expect(header.getSalt()[0] == 7.toByte(), { "salt copy" })
        expect(header.getEncrypted()[0] == 7.toByte(), { "cipher copy" })
    }

    check("screen timers stay single-loop, cancellable and permanently closed") {
        var ticks = 0
        var expiries = 0
        val scheduler = FakeScheduler()
        val timers = ScreenTimerController(scheduler, Runnable { ticks++ }, Runnable { expiries++ })

        timers.startTicks()
        timers.startTicks()
        scheduler.advance(3000)
        expect(ticks == 4, { "ticks after 3s with double start: $ticks" })

        timers.pause()
        val ticksAfterPause = ticks
        scheduler.advance(5000)
        expect(ticks == ticksAfterPause, { "ticks after pause: $ticks" })

        timers.startTicks()
        scheduler.advance(2000)
        expect(ticks == ticksAfterPause + 3, { "ticks after resume: $ticks" })

        timers.restartInactivity(5000)
        scheduler.advance(4999)
        expect(expiries == 0, { "expired early: $expiries" })
        scheduler.advance(1)
        expect(expiries == 1, { "expiry missing: $expiries" })

        timers.restartInactivity(1000)
        timers.restartInactivity(10000)
        scheduler.advance(2000)
        expect(expiries == 1, { "superseded inactivity fired: $expiries" })

        timers.cancelInactivity()
        scheduler.advance(60000)
        expect(expiries == 1, { "cancelled inactivity fired: $expiries" })

        timers.close()
        val ticksAfterClose = ticks
        timers.startTicks()
        timers.restartInactivity(1)
        scheduler.advance(60000)
        expect(ticks == ticksAfterClose && expiries == 1, { "closed controller restarted" })
    }

    check("stale timer callbacks cannot restart a stopped or closed controller") {
        var ticks = 0
        val scheduler = FakeScheduler()
        val timers = ScreenTimerController(scheduler, Runnable { ticks++ }, Runnable {})
        timers.startTicks()
        val stale = scheduler.runNext()
        expect(stale && ticks == 1, { "first tick" })
        val dequeued = scheduler.lastPosted!!
        timers.stopTicks()
        dequeued.run()
        expect(ticks == 1, { "dequeued tick still ran the callback" })
        expect(!scheduler.runNext(), { "stopped timer rescheduled itself" })

        var expired = 0
        val closing = FakeScheduler()
        lateinit var closeTimers: ScreenTimerController
        closeTimers = ScreenTimerController(closing, Runnable { closeTimers.stopTicks() }, Runnable { expired++ })
        closeTimers.startTicks()
        closing.advance(5000)
        expect(ticks == 1, { "self-stopping tick loop kept running" })

        closeTimers.restartInactivity(3000)
        val pendingExpiry = closing.lastPosted!!
        closing.skip(10000)
        closeTimers.close()
        pendingExpiry.run()
        expect(expired == 0, { "closed controller expired the session: $expired" })
    }

    check("inactivity uses elapsed time and survives a device sleep") {
        var expired = 0
        val scheduler = FakeScheduler()
        val timers = ScreenTimerController(scheduler, Runnable {}, Runnable { expired++ })
        timers.restartInactivity(5000)
        scheduler.advance(1000)
        expect(expired == 0, { "early expiry" })
        scheduler.skip(60000)
        scheduler.advance(0)
        expect(expired == 1, { "expiry missed after sleep: $expired" })

        var disabled = 0
        val other = FakeScheduler()
        val zero = ScreenTimerController(other, Runnable {}, Runnable { disabled++ })
        zero.restartInactivity(0)
        other.advance(60000)
        expect(disabled == 0, { "zero delay expired" })
        zero.restartInactivity(-5)
        other.advance(60000)
        expect(disabled == 0, { "negative delay expired" })
    }

    check("session contract keeps one authority and one-use secrets") {
        val session = SessionController(Any())
        expect(session.snapshot() is SessionState.Locked, { "initial state" })
        val attempt = session.beginUnlock()
        expect(session.currentKey() == null, { "key before activation" })
        val first = SecretKeySpec(ByteArray(16), "AES")
        expect(session.activate(first, attempt.generation) is SessionCommandResult.Applied, { "activation" })
        expect(session.currentKey() === first, { "current key" })
        val epoch = session.snapshot().generation
        session.activate(first, epoch)
        expect(session.snapshot().generation == epoch, { "same key advanced the generation" })
        session.activate(SecretKeySpec(ByteArray(16), "AES"), epoch)
        expect(!session.isCurrent(epoch), { "stale generation is still current" })
    }

    check("stale activation and a failed session never install a key") {
        val session = SessionController(Any())
        val epoch = session.beginUnlock().generation
        session.lock(SessionLockReason.SCREEN_OFF)
        val stale = session.activate(SecretKeySpec(ByteArray(16), "AES"), epoch) as SessionCommandResult.Rejected
        expect(stale.reason == SessionRejection.STALE_GENERATION, { "stale rejection" })
        session.fail(SessionFailure.RECOVERY_REQUIRED)
        val rejected = session.activate(SecretKeySpec(ByteArray(16), "AES"),
                session.snapshot().generation) as SessionCommandResult.Rejected
        expect(rejected.reason == SessionRejection.FAILED_SESSION, { "failed rejection" })
        expect(session.currentKey() == null, { "key installed into a failed session" })
        session.beginUnlock()
        expect(session.snapshot() is SessionState.Unlocking, { "a fresh attempt is explicit" })
    }

    check("owned secrets are one-use and redacted") {
        val source = byteArrayOf(1, 2, 3)
        val secret = OwnedSecret.copyOf(source)
        source.fill(0)
        expect(secret.toString() == "OwnedSecret(redacted)", { secret.toString() })
        val transferred = secret.takeBytes()
        expect(transferred.contentEquals(byteArrayOf(1, 2, 3)), { "transferred bytes" })
        secret.close()
        expect(transferred.contentEquals(byteArrayOf(1, 2, 3)), { "ownership stayed with the caller" })
        expectThrows(IllegalStateException::class.java) { secret.takeBytes() }
        transferred.fill(0)

        val closed = OwnedSecret.copyOf(ByteArray(16))
        closed.close()
        closed.close()
        expectThrows(IllegalStateException::class.java) { closed.takeBytes() }
        expectThrows(IllegalArgumentException::class.java) { OwnedSecret.copyOf(ByteArray(0)) }
        expectThrows(IllegalArgumentException::class.java) { OwnedSecret.copyOf(ByteArray(65)) }
    }

    check("task lifecycle is one-shot and revocation replaces pending completion") {
        val task = TaskController(10)
        expect(task.snapshot() is TaskState.Ready, { "ready" })
        expect(task.start() == TaskStart.STARTED, { "start" })
        expect(task.snapshot() is TaskState.Running, { "running" })
        expect(task.start() == TaskStart.ALREADY_SUBMITTED, { "double start" })
        expect(task.complete(TaskCompletion.VALUE), { "complete" })
        expect(task.claimDelivery(), { "claim delivery" })
        expect((task.snapshot() as TaskState.Delivered).completion == TaskCompletion.VALUE, { "delivered value" })
        expect(task.deliveryConsumed(), { "delivery consumed" })
        expect(!task.claimDelivery(), { "second claim" })
        expect(!task.cancel(TaskCancellation.EXPLICIT), { "cancel after delivery" })

        val revoked = TaskController(5)
        revoked.start()
        revoked.complete(TaskCompletion.VALUE)
        revoked.observeGeneration(6)
        expect((revoked.snapshot() as TaskState.Cancelled).reason == TaskCancellation.SESSION_REVOKED, { "revocation" })
        expect(revoked.claimDelivery(), { "cancellation delivery" })
        expect(!revoked.complete(TaskCompletion.FAILURE), { "complete after revocation" })

        val delivered = TaskController(5)
        delivered.start()
        delivered.complete(TaskCompletion.VALUE)
        delivered.claimDelivery()
        delivered.observeGeneration(6)
        expect(delivered.snapshot() is TaskState.Delivered, { "delivered state must survive revocation" })

        val preStart = TaskController(0)
        expect(preStart.cancel(TaskCancellation.EXPLICIT), { "pre-start cancel" })
        expect(preStart.start() == TaskStart.CANCELED, { "start after cancel" })
        expect(!preStart.complete(TaskCompletion.VALUE), { "complete without start" })
        expect(preStart.claimDelivery(), { "first cancellation delivery" })
        expect(!preStart.claimDelivery(), { "second cancellation delivery" })
        expect(preStart.start() == TaskStart.ALREADY_SUBMITTED, { "restart after cancel" })

        val firstReason = TaskController(1)
        firstReason.cancel(TaskCancellation.EXPLICIT)
        firstReason.observeGeneration(2)
        expect((firstReason.snapshot() as TaskState.Cancelled).reason == TaskCancellation.EXPLICIT,
                { "the first cancellation reason wins" })
        expect(!firstReason.cancel(TaskCancellation.SESSION_REVOKED), { "second cancellation" })
    }

    check("outcome and OTP diagnostics never print secrets") {
        expect(TaskOutcome.Value("synthetic-secret").toString() == "TaskOutcome.Value(redacted)",
                { TaskOutcome.Value("synthetic-secret").toString() })
        expect(TaskOutcome.Failure<String>(TaskFailure.NULL_RESULT).error == TaskFailure.NULL_RESULT, { "failure error" })
        val otp = ValidatedOtp.create(OtpKind.TOTP, byteArrayOf(1, 2), 30, 6, 0, "issuer", "label",
                OtpHashAlgorithm.SHA1, emptyList())
        expect(otp.toString() == "ValidatedOtp(redacted)", { otp.toString() })
        expect(OtpParseResult.Success(otp).toString() == "OtpParseResult.Success(redacted)",
                { OtpParseResult.Success(otp).toString() })
        expect(otp.copySecret().contentEquals(byteArrayOf(1, 2)), { "secret copy" })
        otp.close()
        expectThrows(IllegalStateException::class.java) { otp.copySecret() }
        val verified = AuthenticationOutcome.verified(ByteArray(32), 7)
        expect(verified.toString() == "AuthenticationOutcome.Verified(redacted)", { verified.toString() })
        expect(verified.generation == 7L, { "outcome generation" })
        verified.close()
        expect(AuthenticationOutcome.Rejected(AuthenticationError.DENIED).error == AuthenticationError.DENIED,
                { "rejected outcome" })
    }

    check("secret key wrapper round-trips AES keys through real RSA wrapping") {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()
        val wrapper = SecretKeyWrapper(pair)

        val raw = ByteArray(16) { (it + 1).toByte() }
        val wrapped = wrapper.wrap(SecretKeySpec(raw, "AES"))
        expect(wrapped.size == 256, { "wrapped length ${wrapped.size} is not the RSA modulus size" })
        expect(!wrapped.contentEquals(raw), { "wrapped blob exposes the key" })

        val unwrapped = wrapper.unwrap(wrapped)
        expect(unwrapped.algorithm == "AES", { "unwrapped algorithm ${unwrapped.algorithm}" })
        expect(unwrapped.encoded.contentEquals(raw), { "round trip changed the key bytes" })

        // Proves the transformation is the mirrored RSA/PKCS#1 v1.5 constant, not just any round trip.
        val manual = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        manual.init(Cipher.DECRYPT_MODE, pair.private)
        expect(manual.doFinal(wrapped).contentEquals(raw), { "wrapped bytes are not RSA/PKCS1Padding output" })

        val tampered = wrapped.copyOf()
        tampered[10] = (tampered[10].toInt() xor 0x01).toByte()
        expectThrows(GeneralSecurityException::class.java) { wrapper.unwrap(tampered) }
        expectThrows(GeneralSecurityException::class.java) { SecretKeyWrapper(generator.generateKeyPair()).unwrap(wrapped) }
        expectThrows(GeneralSecurityException::class.java) { wrapper.unwrap(ByteArray(8)) }
        expectThrows(GeneralSecurityException::class.java) { wrapper.unwrap(ByteArray(300)) }

        // A 32-byte key (the largest AES size used by the vault) still round-trips.
        val longKey = ByteArray(32) { (it * 7 + 3).toByte() }
        expect(wrapper.unwrap(wrapper.wrap(SecretKeySpec(longKey, "AES"))).encoded.contentEquals(longKey),
                { "AES-256 round trip changed the key bytes" })
    }

    check("double-click listener separates single and double taps") {
        val view = FakeView()
        val listener = RecordingListener()

        listener.onClick(view)
        expect(listener.singles == 0 && listener.doubles == 0, { "a tap fired immediately" })
        expect(view.posted.size == 1 && view.lastDelay == 300L, { "single tap not delayed by 300ms" })
        view.posted[0].run()
        expect(listener.singles == 1 && listener.doubles == 0, { "pending single click did not fire" })

        val doubleView = FakeView()
        val doubleListener = RecordingListener()
        doubleListener.onClick(doubleView)
        doubleListener.onClick(doubleView)
        expect(doubleListener.doubles == 1 && doubleListener.singles == 0, { "second tap did not become a double click" })
        doubleView.posted.forEach { it.run() }
        expect(doubleListener.singles == 0, { "suppressed single click still fired" })

        // A third rapid tap is not a second double click, matching the original firstTap guard.
        doubleListener.onClick(doubleView)
        expect(doubleListener.doubles == 1, { "third rapid tap produced another double click" })

        // A pause longer than the delta makes the next tap a fresh single click.
        Thread.sleep(350)
        doubleListener.onClick(doubleView)
        doubleView.posted.last().run()
        expect(doubleListener.singles == 1, { "slow tap after a double click was not a single click" })
    }

    check("dimension strings convert with the documented units and rounding") {
        val metrics = DisplayMetrics()
        metrics.density = 2.0f
        metrics.scaledDensity = 2.5f
        metrics.xdpi = 320f
        metrics.ydpi = 320f

        expect(DimensionConverter.stringToDimensionPixelSize("12dp", metrics) == 24, { "12dp" })
        expect(DimensionConverter.stringToDimensionPixelSize("12dip", metrics) == 24, { "12dip" })
        expect(DimensionConverter.stringToDimensionPixelSize("12px", metrics) == 12, { "12px" })
        expect(DimensionConverter.stringToDimensionPixelSize("12DP", metrics) == 24, { "12DP" })
        expect(DimensionConverter.stringToDimensionPixelSize(" 12 dp ", metrics) == 24, { "padded input" })
        expect(DimensionConverter.stringToDimensionPixelSize("1.5sp", metrics) == 4, { "1.5sp rounding" })
        expect(DimensionConverter.stringToDimensionPixelSize("2in", metrics) == 640, { "2in" })
        expect(DimensionConverter.stringToDimensionPixelSize("72pt", metrics) == 320, { "72pt" })
        expect(DimensionConverter.stringToDimensionPixelSize("25.4mm", metrics) == 320, { "25.4mm" })
        expect(DimensionConverter.stringToDimensionPixelSize("0dp", metrics) == 0, { "0dp" })
        expect(DimensionConverter.stringToDimension("12dp", metrics) == 24.0f, { "stringToDimension" })

        // Values that round to zero pixels still become one pixel when positive.
        val sparse = DisplayMetrics()
        sparse.density = 0.001f
        expect(DimensionConverter.stringToDimensionPixelSize("12dp", sparse) == 1, { "sub-pixel value" })

        for (invalid in listOf("12", "12zz", "", "dp12", "12.5.5dp", "-12dp", "12 dp x")) {
            expectThrows(NumberFormatException::class.java) {
                DimensionConverter.stringToDimensionPixelSize(invalid, metrics)
            }
        }

        val lookup = DimensionConverter.dimensionConstantLookup
        expect(lookup.size == 7, { "lookup size ${lookup.size}" })
        expect(lookup["px"] == 0 && lookup["dip"] == 1 && lookup["dp"] == 1 && lookup["sp"] == 2
                && lookup["pt"] == 3 && lookup["in"] == 4 && lookup["mm"] == 5, { "unit mapping changed" })
        expectThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (lookup as MutableMap<String, Int>)["xx"] = 1
        }
    }

    check("drag & drop callback forwards moves and wires the view holder callbacks") {
        val recyclerView = RecyclerView()
        recyclerView.setLayoutManager(LinearLayoutManager())
        val adapter = RecordingDragAdapter()
        val callback = SimpleItemTouchHelperCallback(adapter)

        // Long-press dragging starts enabled and can be switched off, like the sorted/search flows do.
        expect(callback.isLongPressDragEnabled(), { "dragging must start enabled" })
        callback.setDragEnabled(false)
        expect(!callback.isLongPressDragEnabled(), { "setDragEnabled(false) did not disable dragging" })
        callback.setDragEnabled(true)
        expect(callback.isLongPressDragEnabled(), { "setDragEnabled(true) did not re-enable dragging" })
        expect(!callback.isItemViewSwipeEnabled(), { "swiping must stay disabled" })

        // Vertical list: UP|DOWN only; grid: all four axes. The flag composition lives in the
        // stub (documented replication), so the assertions decode the documented bit fields:
        // the low byte carries swipe|drag and the swipe byte (bits 8-15) must stay empty.
        val listFlags = callback.getMovementFlags(recyclerView, DragViewHolder(View(), 0, 0))
        expect(((listFlags shr 16) and 0xFF) == (ItemTouchHelper.UP or ItemTouchHelper.DOWN), { "list drag flags $listFlags" })
        expect((listFlags and 0xFF) == (ItemTouchHelper.UP or ItemTouchHelper.DOWN) &&
                ((listFlags shr 8) and 0xFF) == 0, { "list must not enable swiping" })
        recyclerView.setLayoutManager(GridLayoutManager())
        val gridFlags = callback.getMovementFlags(recyclerView, DragViewHolder(View(), 0, 0))
        expect(((gridFlags shr 16) and 0xFF) ==
                (ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT),
                { "grid drag flags $gridFlags" })
        expect(((gridFlags shr 8) and 0xFF) == 0, { "grid must not enable swiping" })

        // Moves between items of the same type are forwarded; mixed types are rejected.
        val source = DragViewHolder(View(), 0, 2)
        val target = DragViewHolder(View(), 0, 5)
        expect(callback.onMove(recyclerView, source, target), { "same-type move rejected" })
        expect(adapter.moves == listOf(Pair(2, 5)), { "move not forwarded: ${adapter.moves}" })
        val foreign = DragViewHolder(View(), 1, 6)
        expect(!callback.onMove(recyclerView, source, foreign), { "cross-type move accepted" })
        expect(adapter.moves.size == 1, { "cross-type move was forwarded" })

        // Select/clear lifecycle only fires for item-touch-holders and non-idle states.
        val selectable = SelectableViewHolder(View())
        callback.onSelectedChanged(selectable, ItemTouchHelper.ACTION_STATE_DRAG)
        expect(selectable.selections == 1, { "active item was not selected" })
        callback.onSelectedChanged(selectable, ItemTouchHelper.ACTION_STATE_IDLE)
        expect(selectable.selections == 1, { "idle state re-selected the item" })
        callback.onSelectedChanged(null, ItemTouchHelper.ACTION_STATE_DRAG)
        selectable.itemView.alpha = 0.3f
        callback.clearView(recyclerView, selectable)
        expect(selectable.clears == 1 && selectable.itemView.alpha == SimpleItemTouchHelperCallback.ALPHA_FULL,
                { "clearView did not restore the idle alpha" })

        // The swipe fade (unreachable while swiping is disabled) keeps its arithmetic, and the
        // non-swipe branch still delegates to the super class.
        val swiped = SelectableViewHolder(View())
        swiped.itemView.width = 200
        callback.onChildDraw(Canvas(), recyclerView, swiped, -50f, 0f, ItemTouchHelper.ACTION_STATE_SWIPE, true)
        expect(swiped.itemView.alpha == 0.75f, { "swipe alpha ${swiped.itemView.alpha}" })
        expect(swiped.itemView.translationX == -50f, { "swipe translation" })
        callback.onChildDraw(Canvas(), recyclerView, swiped, -50f, 0f, ItemTouchHelper.ACTION_STATE_DRAG, true)
        expect(swiped.itemView.translationX == -50f, { "drag draw changed the view" })
        callback.onSwiped(swiped, ItemTouchHelper.DOWN) // disabled feature: must stay a no-op
    }

    check("generic dialogs and keyboard helpers keep their wiring") {
        val context = FakeUiContext()
        val view = FakeView()

        var okRuns = 0
        UIHelper.showGenericDialog(context, 11, 13)
        val plain = AlertDialog.Builder.lastCreated ?: throw AssertionError("no dialog was created")
        expect(plain.shown, { "dialog was not shown" })
        plain.positiveButtonListener!!.onClick(plain, DialogInterface.BUTTON_POSITIVE)
        expect(okRuns == 0, { "a missing handler was invoked" })

        UIHelper.showGenericDialog(context, 11, 13) { okRuns++ }
        val withOk = AlertDialog.Builder.lastCreated ?: throw AssertionError("no second dialog")
        withOk.positiveButtonListener!!.onClick(withOk, DialogInterface.BUTTON_POSITIVE)
        expect(okRuns == 1, { "confirmed action did not run" })

        UIHelper.showKeyboard(context, view)
        expect(context.imm.shownViews == listOf<View>(view) && context.imm.toggles == 0,
                { "default showKeyboard must request, not toggle" })

        UIHelper.showKeyboard(context, view, true)
        expect(context.imm.toggles == 1, { "forced showKeyboard must toggle" })

        // A null flag was never passed; the Java unboxing would have thrown for one.
        expectThrows(NullPointerException::class.java) { UIHelper.showKeyboard(context, view, null) }

        UIHelper.hideKeyboard(context, view)
        expect(context.imm.hiddenTokens.size == 1, { "hideKeyboard did not hide" })

        // A missing view stays a no-op, exactly like the original null guards.
        UIHelper.showKeyboard(context, null)
        UIHelper.hideKeyboard(context, null)
        expect(context.imm.shownViews.size == 1 && context.imm.hiddenTokens.size == 1 && context.imm.toggles == 1,
                { "a missing view must stay a no-op" })
    }

    check("password visibility toggles keep both fields and the cursor in sync") {
        val layout = TextInputLayout()
        val password = FakePasswordInput()
        val confirm = FakePasswordInput()

        ConfirmedPasswordTransformationHelper.setup(layout, password, confirm)
        expect(password.transformationMethod is PasswordTransformationMethod &&
                confirm.transformationMethod is PasswordTransformationMethod,
                { "setup must start with both fields masked" })
        expect(layout.endIconClickListener != null, { "no end-icon listener was installed" })

        val listener = layout.endIconClickListener!!
        listener.onClick(layout)
        expect(password.transformationMethod == null && confirm.transformationMethod == null,
                { "first toggle must unmask both fields" })
        expect(password.selections == listOf(7) && confirm.selections == listOf(7),
                { "cursor position was not restored" })
        expect(layout.refreshCount == 1, { "layout was not refreshed" })

        listener.onClick(layout)
        expect(password.transformationMethod is PasswordTransformationMethod &&
                confirm.transformationMethod is PasswordTransformationMethod,
                { "second toggle must re-mask both fields" })
        expect(password.selections == listOf(7, 7), { "cursor position was not kept on re-mask" })
        expect(layout.refreshCount == 2, { "second toggle did not refresh the layout" })
    }

    check("backup task results keep their factories, defaults and redaction") {
        val success = BackupTaskResult.success(BackupTaskResult.ResultType.BACKUP, "andotp-backup.json")
        expect(success.success && success.resultType == BackupTaskResult.ResultType.BACKUP,
                { "success reports the wrong state" })
        expect(success.payload == "andotp-backup.json", { "file name payload changed" })
        expect(success.messageId == R.string.backup_toast_export_success, { "success message id changed" })
        expect(!success.isPGP && success.decryptIntent == null && success.uri == null, { "PGP defaults changed" })

        val failure = BackupTaskResult.failure(BackupTaskResult.ResultType.RESTORE, 4242)
        expect(!failure.success && failure.payload == null && failure.messageId == 4242,
                { "failure reports the wrong state" })

        val decryptIntent = Intent()
        val uri = Uri()
        val pgp = BackupTaskResult(BackupTaskResult.ResultType.RESTORE, true, "decrypted", 0, true, decryptIntent, uri)
        expect(pgp.isPGP && pgp.decryptIntent === decryptIntent && pgp.uri === uri, { "PGP constructor changed" })
        pgp.isPGP = false
        expect(!pgp.isPGP, { "isPGP is no longer writable" })

        // payload can be the whole parsed database; the default toString must not broadcast it.
        expect(!success.toString().contains("andotp-backup"), { "payload leaked through toString" })
        expect(BackupTaskResult.ResultType.values().map { it.name } == listOf("NONE", "BACKUP", "RESTORE"),
                { "ResultType constants changed" })
        expect(BackupTaskResult.ResultType.valueOf("NONE") == BackupTaskResult.ResultType.NONE, { "valueOf broke" })
    }

    check("background runner delivers one owned value through the fake main loop") {
        Handler.posted.clear()
        val task = BlockingRunnerTask("fallback")
        val delivered = ArrayList<TaskOutcome<String>>()
        task.setOutcomeCallback { delivered.add(it) }
        task.setOutcomeCallback(null) // rebinding before start must be a no-op, not a delivery leak
        task.setOutcomeCallback { delivered.add(it) }
        task.execute()
        expect(task.started.await(5, TimeUnit.SECONDS), { "background work never started" })
        task.release.countDown()
        awaitQueuedDelivery()
        drainMainThreadDeliveries()
        expect(delivered.size == 1, { "delivered ${delivered.size} outcomes" })
        val value = delivered[0] as? TaskOutcome.Value<*> ?: throw AssertionError("not a value: ${delivered[0]}")
        expect(value.value == "late-value", { "wrong payload ${value.value}" })
        expect(task.getState() is TaskState.Delivered, { "state ${task.getState()}" })

        // A binding after the delivery was consumed is ignored instead of replaying it.
        val late = ArrayList<TaskOutcome<String>>()
        task.setOutcomeCallback { late.add(it) }
        drainMainThreadDeliveries()
        expect(late.isEmpty(), { "a consumed delivery was replayed" })
        expectThrows(IllegalStateException::class.java) { task.execute() }
    }

    check("null and thrown results become failures and legacy clients get the fallback") {
        Handler.posted.clear()
        val nullTask = NullRunnerTask("fallback")
        val outcomes = ArrayList<TaskOutcome<String?>>()
        nullTask.setOutcomeCallback { outcomes.add(it) }
        nullTask.execute()
        awaitQueuedDelivery()
        drainMainThreadDeliveries()
        expect(outcomes.size == 1 && outcomes[0] is TaskOutcome.Failure, { "a null result was not a typed failure" })
        expect((outcomes[0] as TaskOutcome.Failure<*>).error == TaskFailure.NULL_RESULT, { "wrong failure code" })

        val legacyNull = NullRunnerTask("fallback")
        var legacyResult: String? = "unset"
        legacyNull.setCallback { legacyResult = it }
        legacyNull.execute()
        awaitQueuedDelivery()
        drainMainThreadDeliveries()
        expect(legacyResult == "fallback", { "legacy callback received $legacyResult" })

        val failing = FailingRunnerTask("legacy-fallback", IllegalStateException("synthetic-provider-failure"))
        val typed = ArrayList<TaskOutcome<String>>()
        failing.setOutcomeCallback { typed.add(it) }
        failing.execute()
        awaitQueuedDelivery()
        drainMainThreadDeliveries()
        expect(typed.size == 1 && typed[0] is TaskOutcome.Failure, { "a thrown result was not a typed failure" })
        expect((typed[0] as TaskOutcome.Failure<*>).error == TaskFailure.UNEXPECTED_EXCEPTION,
                { "wrong failure kind ${typed[0]}" })
        expect(failing.getState() is TaskState.Delivered, { "failure was not delivered" })
    }

    check("cancellation is one-shot, cleans up once and disposes the fallback") {
        val task = BlockingRunnerTask("fallback")
        val delivered = ArrayList<TaskOutcome<String>>()
        task.setOutcomeCallback { delivered.add(it) }
        task.cancel()
        expect(task.cleanupRuns.get() == 1, { "cancel() did not run owned-input cleanup" })
        task.release.countDown() // the worker never started; this is harmless
        drainMainThreadDeliveries()
        expect(delivered.size == 1 && delivered[0] is TaskOutcome.Cancelled, { "cancel did not deliver cancellation" })
        expect((delivered[0] as TaskOutcome.Cancelled<*>).reason == TaskCancellation.EXPLICIT, { "wrong reason" })

        // A cancelled task can still be started once: the CANCELED path disposes the fallback,
        // keeps the cleanup idempotent and does not deliver again (the callback was consumed).
        task.execute()
        expect(task.cleanupRuns.get() == 1, { "cleanup must stay idempotent" })
        expect(task.discarded.get() == 1, { "the cancelled start did not dispose the fallback" })
        drainMainThreadDeliveries()
        expect(delivered.size == 1, { "a cancelled start delivered again" })
        expectThrows(IllegalStateException::class.java) { task.execute() }
    }

    check("explicit cancellation disposes the late result and never calls the legacy callback") {
        val task = BlockingRunnerTask("fallback")
        val legacy = AtomicInteger(0)
        task.setCallback { legacy.incrementAndGet() }
        task.execute()
        expect(task.started.await(5, TimeUnit.SECONDS), { "work never started" })
        task.cancel()
        task.release.countDown()
        expect(task.discardedOnce.await(5, TimeUnit.SECONDS), { "the late value was never disposed" })
        drainMainThreadDeliveries()
        expect(legacy.get() == 0, { "a cancelled task delivered a legacy result" })
        expect(task.discarded.get() == 1, { "unexpected disposal count ${task.discarded.get()}" })
    }

    check("session revocation cancels in-flight work and disposes its result") {
        val task = BlockingRunnerTask("fallback")
        val delivered = ArrayList<TaskOutcome<String>>()
        task.setOutcomeCallback { delivered.add(it) }
        task.execute()
        expect(task.started.await(5, TimeUnit.SECONDS), { "work never started" })
        VaultSession.lock() // advances the generation; the captured one is stale now
        task.release.countDown()
        expect(task.discardedOnce.await(5, TimeUnit.SECONDS), { "the produced value was never disposed" })
        drainMainThreadDeliveries()
        expect(delivered.size == 1 && delivered[0] is TaskOutcome.Cancelled, { "revocation did not deliver cancellation" })
        expect((delivered[0] as TaskOutcome.Cancelled<*>).reason == TaskCancellation.SESSION_REVOKED, { "wrong reason" })
        expect(task.cleanupRuns.get() == 1, { "revocation cleanup ran ${task.cleanupRuns.get()} times" })
    }

    check("a task captured before revocation is cancelled before its work begins") {
        val task = BlockingRunnerTask("fallback")
        VaultSession.lock() // stale generation before start
        val delivered = ArrayList<TaskOutcome<String>>()
        task.setOutcomeCallback { delivered.add(it) }
        task.execute()
        drainMainThreadDeliveries()
        expect(task.cleanupRuns.get() == 1, { "cancelled start did not run cleanup" })
        expect(delivered.size == 1 && delivered[0] is TaskOutcome.Cancelled, { "stale start was not cancelled" })
        expect((delivered[0] as TaskOutcome.Cancelled<*>).reason == TaskCancellation.SESSION_REVOKED, { "wrong reason" })
        expect(task.discarded.get() == 1, { "the fallback was not disposed on the cancelled start" })
    }

    check("converted classes keep the Java-visible API shapes") {
        expectPublicFieldValue(BoundedInputReader::class.java, "MAX_DOCUMENT_BYTES", 16 * 1024 * 1024, INT_TYPE, true)
        expectMethod(BoundedInputReader::class.java, "static byte[] read(InputStream,int)")
        val read = BoundedInputReader::class.java.declaredMethods.first { it.name == "read" }
        expect(read.exceptionTypes.contains(IOException::class.java), { "read() no longer declares IOException" })

        expectPublicFieldValue(EncryptedBackupHeader::class.java, "MIN_BACKUP_BYTES", 44, INT_TYPE, true)
        expectPublicFieldValue(EncryptedBackupHeader::class.java, "MAX_ITERATIONS", 1000000, INT_TYPE, true)
        expectMethod(EncryptedBackupHeader::class.java, "static EncryptedBackupHeader parse(byte[])")
        expectMethod(EncryptedBackupHeader::class.java, "int getIterations()")
        expectMethod(EncryptedBackupHeader::class.java, "byte[] getSalt()")
        expectMethod(EncryptedBackupHeader::class.java, "byte[] getEncrypted()")
        val constructors = EncryptedBackupHeader::class.java.declaredConstructors
        expect(constructors.filter { !it.isSynthetic }.all { Modifier.isPrivate(it.modifiers) },
                { "header constructor is public: ${constructors.filter { !it.isSynthetic }.map { it.toGenericString() }}" })

        expectPublicFieldValue(TokenCalculator::class.java, "TOTP_DEFAULT_PERIOD", 30, INT_TYPE, true)
        expectPublicFieldValue(TokenCalculator::class.java, "TOTP_DEFAULT_DIGITS", 6, INT_TYPE, true)
        expectPublicFieldValue(TokenCalculator::class.java, "HOTP_INITIAL_COUNTER", 1, INT_TYPE, true)
        expectPublicFieldValue(TokenCalculator::class.java, "STEAM_DEFAULT_DIGITS", 5, INT_TYPE, true)
        expectField(TokenCalculator::class.java, "DEFAULT_ALGORITHM",
                TokenCalculator.HashAlgorithm::class.java, true)
        expect(!publicMethods(TokenCalculator::class.java).any { it.contains("getDEFAULT_ALGORITHM") },
                { "DEFAULT_ALGORITHM is no longer a field" })
        expectMethod(TokenCalculator::class.java, "static int TOTP_RFC6238(byte[],int,long,int,HashAlgorithm)")
        expectMethod(TokenCalculator::class.java, "static int TOTP_RFC6238(byte[],int,long,int,HashAlgorithm,int)")
        expectMethod(TokenCalculator::class.java, "static String TOTP_RFC6238(byte[],int,int,HashAlgorithm,int)")
        expectMethod(TokenCalculator::class.java, "static String TOTP_Steam(byte[],int,int,HashAlgorithm,int)")
        expectMethod(TokenCalculator::class.java, "static String HOTP(byte[],long,int,HashAlgorithm)")
        expectMethod(TokenCalculator::class.java, "static String MOTP(String,String,long,int)")
        expect(TokenCalculator.HashAlgorithm::class.java.isEnum, { "HashAlgorithm is not an enum" })
        expect(TokenCalculator.HashAlgorithm.values().map { it.name } == listOf("SHA1", "SHA256", "SHA512"),
                { "HashAlgorithm constants changed" })
        expect(TokenCalculator.HashAlgorithm.valueOf("SHA1") == TokenCalculator.DEFAULT_ALGORITHM, { "default algorithm" })

        expectMethod(BoundedOutput::class.java, "void write(int)")
        expectMethod(BoundedOutput::class.java, "void write(byte[],int,int)")
        expectMethod(BoundedOutput::class.java, "boolean overflowed()")
        val boundedWrites = BoundedOutput::class.java.declaredMethods.filter { it.name == "write" || it.name == "overflowed" }
        expect(boundedWrites.all { Modifier.isSynchronized(it.modifiers) },
                { "BoundedOutput lost its monitor: ${boundedWrites.map { methodSignature(it) }}" })
        expect(BoundedOutput::class.java.declaredConstructors.any { it.parameterTypes.contentEquals(arrayOf<Class<*>>(INT_TYPE)) },
                { "missing BoundedOutput(int)" })

        val wrapper = SecretKeyWrapper::class.java
        expect(Modifier.isPublic(wrapper.modifiers), { "SecretKeyWrapper is not public" })
        expect(!Modifier.isFinal(wrapper.modifiers), { "SecretKeyWrapper must stay extendable like the Java class" })
        val wrapperConstructor = wrapper.declaredConstructors.single { !it.isSynthetic }
        expect(Modifier.isPublic(wrapperConstructor.modifiers), { "SecretKeyWrapper constructor is not public" })
        expect(wrapperConstructor.parameterTypes.contentEquals(arrayOf<Class<*>>(java.security.KeyPair::class.java)),
                { "SecretKeyWrapper constructor parameter changed" })
        expect(wrapperConstructor.exceptionTypes.contains(GeneralSecurityException::class.java)
                && wrapperConstructor.exceptionTypes.contains(java.io.IOException::class.java),
                { "SecretKeyWrapper constructor lost its checked exceptions" })
        expectMethod(wrapper, "byte[] wrap(SecretKey)")
        expectMethod(wrapper, "SecretKey unwrap(byte[])")
        for (name in listOf("wrap", "unwrap")) {
            val method = wrapper.declaredMethods.first { it.name == name }
            expect(method.exceptionTypes.contains(GeneralSecurityException::class.java),
                    { "SecretKeyWrapper.$name lost its checked exception" })
        }

        val listener = SimpleDoubleClickListener::class.java
        expect(Modifier.isAbstract(listener.modifiers), { "SimpleDoubleClickListener is not abstract" })
        expect(View.OnClickListener::class.java.isAssignableFrom(listener),
                { "SimpleDoubleClickListener no longer implements View.OnClickListener" })
        expect(listener.declaredConstructors.any { Modifier.isPublic(it.modifiers) && it.parameterCount == 0 },
                { "SimpleDoubleClickListener lost its public no-argument constructor" })
        expectMethod(listener, "void onSingleClick(View)")
        expectMethod(listener, "void onDoubleClick(View)")
        expectMethod(listener, "void onClick(View)")
        expect(listener.declaredMethods.filter { Modifier.isPublic(it.modifiers) && it.name.startsWith("on") }
                .all { !Modifier.isStatic(it.modifiers) },
                { "public click callbacks must stay instance methods" })
        expect(listener.declaredMethods.filter { it.name == "onSingleClick" || it.name == "onDoubleClick" }
                .all { Modifier.isAbstract(it.modifiers) },
                { "click callbacks must stay abstract for Java subclasses" })
        // Kotlin's SAM conversion of the posted Runnable adds a private static lambda helper, like
        // the lambdas already used by the Java sources; it must stay private.
        expect(listener.declaredMethods.filter { it.name.contains("\$") }.all { Modifier.isPrivate(it.modifiers) },
                { "synthetic lambda helpers must stay private" })

        val dimensions = DimensionConverter::class.java
        expectMethod(dimensions, "static int stringToDimensionPixelSize(String,DisplayMetrics)")
        expectMethod(dimensions, "static float stringToDimension(String,DisplayMetrics)")
        expectPublicFieldValue(dimensions, "dimensionConstantLookup", DimensionConverter.dimensionConstantLookup,
                Map::class.java, true)
        // Converting a static-only utility to a Kotlin object removes the public constructor and
        // makes the class final. That is the deliberate, documented delta shared by this conversion
        // and the earlier Tools/InputPolicy ones; every call site uses only the static members.
        for (utility in listOf(dimensions, InputPolicy::class.java, TokenCalculator::class.java)) {
            expect(utility.declaredConstructors.all { Modifier.isPrivate(it.modifiers) },
                    { "${utility.simpleName} should not be instantiable after conversion" })
            expect(Modifier.isFinal(utility.modifiers), { "${utility.simpleName} object is not final" })
            expect(utility.declaredFields.any { it.name == "INSTANCE" },
                    { "${utility.simpleName} object is missing INSTANCE" })
        }

        expectMethod(OwnedSecret::class.java, "static OwnedSecret copyOf(byte[])")
        expectMethod(SessionController::class.java, "SessionState snapshot()")
        expectMethod(SessionController::class.java, "boolean isCurrent(long)")
        expectMethod(SessionController::class.java, "SessionCommandResult activate(SecretKey,long)")
        expectMethod(TaskController::class.java, "TaskStart start()")
        expectMethod(AuthenticationOutcome::class.java, "static Verified verified(byte[],long)")

        expectMethod(EditorActionHelper::class.java, "static boolean isActionDoneOrKeyboardEnter(int,KeyEvent)")
        expectMethod(EditorActionHelper::class.java, "static boolean isActionUpKeyboardEnter(KeyEvent)")

        expect(ScreenTimerController.Scheduler::class.java.isAssignableFrom(HandlerTimerScheduler::class.java),
                { "HandlerTimerScheduler no longer implements Scheduler" })
        expectMethod(HandlerTimerScheduler::class.java, "void postDelayed(Runnable,long)")
        expectMethod(HandlerTimerScheduler::class.java, "void remove(Runnable)")
        expectMethod(HandlerTimerScheduler::class.java, "long elapsedRealtime()")

        expectPublicFieldValue(InputPolicy::class.java, "MAX_URI_CHARS", 16384, INT_TYPE, true)
        expectPublicFieldValue(InputPolicy::class.java, "MAX_ENTRIES", 2000, INT_TYPE, true)
        expectPublicFieldValue(InputPolicy::class.java, "MAX_JSON_DEPTH", 32, INT_TYPE, true)
        expectPublicFieldValue(InputPolicy::class.java, "MAX_TEXT_CHARS", 1024, INT_TYPE, true)
        expectPublicFieldValue(InputPolicy::class.java, "MAX_TAGS", 32, INT_TYPE, true)
        expectPublicFieldValue(InputPolicy::class.java, "MAX_TAG_CHARS", 128, INT_TYPE, true)
        expectPublicFieldValue(InputPolicy::class.java, "MAX_SECRET_BYTES", 2048, INT_TYPE, true)
        expectPublicFieldValue(InputPolicy::class.java, "MAX_PERIOD_SECONDS", 86400, INT_TYPE, true)
        expectPublicFieldValue(InputPolicy::class.java, "MAX_IMAGE_DIMENSION", 8192, INT_TYPE, true)
        expectPublicFieldValue(InputPolicy::class.java, "MAX_IMAGE_PIXELS", 4_000_000L, LONG_TYPE, true)
        expectMethod(InputPolicy::class.java, "static void text(String,int)")
        expectMethod(InputPolicy::class.java, "static void jsonDepth(String)")
        expectMethod(InputPolicy::class.java, "static boolean validImage(int,int)")
        expectMethod(InputPolicy::class.java, "static void tokenParameters(int,int,long)")
    }

    check("the drag/drop trio and dialog/task helpers keep the Java-visible API shapes") {
        val moveAdapter = ItemTouchHelperAdapter::class.java
        expect(moveAdapter.isInterface, { "ItemTouchHelperAdapter is no longer an interface" })
        expectMethod(moveAdapter, "boolean onItemMove(int,int)")

        val selectHolder = ItemTouchHelperViewHolder::class.java
        expect(selectHolder.isInterface, { "ItemTouchHelperViewHolder is no longer an interface" })
        expectMethod(selectHolder, "void onItemSelected()")
        expectMethod(selectHolder, "void onItemClear()")

        val callback = SimpleItemTouchHelperCallback::class.java
        expect(!Modifier.isFinal(callback.modifiers), { "SimpleItemTouchHelperCallback must stay extendable" })
        expect(ItemTouchHelper.Callback::class.java.isAssignableFrom(callback), { "callback lost its super class" })
        expectPublicFieldValue(callback, "ALPHA_FULL", 1.0f, FLOAT_TYPE, true)
        expectMethod(callback, "void setDragEnabled(boolean)")
        expectMethod(callback, "boolean isLongPressDragEnabled()")
        expectMethod(callback, "boolean isItemViewSwipeEnabled()")
        val callbackConstructors = callback.declaredConstructors.filter { !it.isSynthetic }
        expect(callbackConstructors.size == 1 &&
                callbackConstructors[0].parameterTypes.contentEquals(arrayOf<Class<*>>(ItemTouchHelperAdapter::class.java)),
                { "callback constructor parameter changed" })

        // Static-only utilities became objects: the same deliberate, documented delta as the
        // earlier Tools/InputPolicy/DimensionConverter conversions.
        for (utility in listOf(UIHelper::class.java, ConfirmedPasswordTransformationHelper::class.java)) {
            expect(Modifier.isFinal(utility.modifiers), { "${utility.simpleName} object is not final" })
            expect(utility.declaredFields.any { it.name == "INSTANCE" },
                    { "${utility.simpleName} object is missing INSTANCE" })
            expect(utility.declaredConstructors.all { Modifier.isPrivate(it.modifiers) },
                    { "${utility.simpleName} should not be instantiable after conversion" })
        }
        expectMethod(UIHelper::class.java, "static void showGenericDialog(Context,int,int)")
        expectMethod(UIHelper::class.java, "static void showGenericDialog(Context,int,int,Runnable)")
        expectMethod(UIHelper::class.java, "static void showKeyboard(Context,View)")
        expectMethod(UIHelper::class.java, "static void showKeyboard(Context,View,Boolean)")
        expectMethod(UIHelper::class.java, "static void hideKeyboard(Context,View)")
        expectMethod(ConfirmedPasswordTransformationHelper::class.java,
                "static void setup(TextInputLayout,TextInputEditText,EditText)")

        val result = BackupTaskResult::class.java
        expect(!Modifier.isFinal(result.modifiers), { "BackupTaskResult must stay extendable" })
        expectField(result, "success", BOOLEAN_TYPE, false)
        expectField(result, "payload", String::class.java, false)
        expectField(result, "messageId", INT_TYPE, false)
        expectField(result, "resultType", BackupTaskResult.ResultType::class.java, false)
        val isPgp = field(result, "isPGP") ?: throw AssertionError("Missing field BackupTaskResult.isPGP")
        expect(isPgp.type == BOOLEAN_TYPE && !Modifier.isFinal(isPgp.modifiers),
                { "isPGP must stay a writable public field" })
        expectField(result, "decryptIntent", Intent::class.java, false)
        expectField(result, "uri", Uri::class.java, false)
        expectMethod(result, "static BackupTaskResult success(ResultType,String)")
        expectMethod(result, "static BackupTaskResult failure(ResultType,int)")
        expect(result.declaredConstructors.any { !it.isSynthetic && it.parameterCount == 7 },
                { "missing the seven-argument constructor" })
        expect(result.declaredConstructors.any { !it.isSynthetic && it.parameterCount == 4 },
                { "missing the four-argument constructor" })
        expect(BackupTaskResult.ResultType::class.java.isEnum, { "ResultType is not an enum" })
    }

    check("the background runner keeps its Java-visible API shapes") {
        val runner = UiBasedBackgroundTask::class.java
        expect(!Modifier.isFinal(runner.modifiers), { "the runner must stay extendable by task subclasses" })
        val constructors = runner.declaredConstructors.filter { !it.isSynthetic }
        expect(constructors.size == 1 &&
                constructors[0].parameterTypes.contentEquals(arrayOf<Class<*>>(Object::class.java)),
                { "the runner constructor changed: ${constructors.map { it.toGenericString() }}" })
        expectMethod(runner, "void setCallback(UiCallback)")
        expectMethod(runner, "void setOutcomeCallback(OutcomeCallback)")
        expectMethod(runner, "TaskState getState()")
        expectMethod(runner, "boolean isCanceled()")
        // The Java method was overridable and EncryptedBackupTask overrides it; a final isCanceled
        // would not even compile against the (Java or Kotlin) subclass.
        expect(!Modifier.isFinal(runner.declaredMethods.first { it.name == "isCanceled" }.modifiers),
                { "isCanceled() must stay overridable for broadcast-mode tasks" })
        expectMethod(runner, "void cancel()")
        expectMethod(runner, "void execute()")
        expectMethod(runner, "void disposeOutcome(TaskOutcome)")

        val background = runner.declaredMethods.first { it.name == "doInBackground" }
        expect(Modifier.isProtected(background.modifiers) && Modifier.isAbstract(background.modifiers),
                { "doInBackground must stay protected and abstract" })
        expect(background.exceptionTypes.contains(Exception::class.java), { "doInBackground lost its throws clause" })
        for (overridable in listOf("onCancellation", "discardResult")) {
            val method = runner.declaredMethods.first { it.name == overridable }
            expect(Modifier.isProtected(method.modifiers) && !Modifier.isFinal(method.modifiers) &&
                    !Modifier.isStatic(method.modifiers),
                    { "$overridable must stay protected and overridable" })
        }
        for (helper in listOf("wasCanceled", "taskGeneration")) {
            val method = runner.declaredMethods.first { it.name == helper }
            expect(Modifier.isProtected(method.modifiers) && Modifier.isFinal(method.modifiers),
                    { "$helper must stay protected final" })
        }

        val nested = runner.declaredClasses.filter { it.isInterface }.map { it.simpleName }.sorted()
        expect(nested == listOf("OutcomeCallback", "UiCallback"), { "nested interfaces changed: $nested" })
        val outcomeCallback = runner.declaredClasses.first { it.simpleName == "OutcomeCallback" }
        expect(outcomeCallback.declaredMethods.single { !it.isSynthetic && Modifier.isAbstract(it.modifiers) }.name == "onOutcome",
                { "OutcomeCallback abstract method changed" })
        val uiCallback = runner.declaredClasses.first { it.simpleName == "UiCallback" }
        expect(uiCallback.declaredMethods.single { !it.isSynthetic && Modifier.isAbstract(it.modifiers) }.name == "onResult",
                { "UiCallback abstract method changed" })

        // The runner reads the live session through the real VaultSession facade.
        expectField(VaultSession::class.java, "MutationLock", Any::class.java, true)
        expectMethod(VaultSession::class.java, "static long generation()")
        expectMethod(VaultSession::class.java, "static void addChangeListener(Runnable)")
        expectMethod(VaultSession::class.java, "static void removeChangeListener(Runnable)")
    }

    check("converted task subclasses keep their Java-visible API shapes") {
        /*
         * Reflection can only see signatures whose referenced types are on this classpath. Members
         * mentioning a type outside the compiled set (a platform class without a stub) are covered by
         * the Python source guards instead; the converted Kotlin sources, Constants included, are
         * all compiled and on the classpath. Generic erasure is why EncryptedBackupTask's constructors are
         * still checkable: ArrayList<Entry> erases to ArrayList.
         */
        val runner = UiBasedBackgroundTask::class.java
        // ChangeEncryptionTask implements EncryptionHelper.EncryptionChangeCallback, a platform-free
        // interface that is compiled here; the Python source guards pin the rest of its shape.
        for (task in listOf(AuthenticationTask::class.java, GenericBackupTask::class.java,
                GenericRestoreTask::class.java, PGPBackupTask::class.java, PGPRestoreTask::class.java,
                EncryptedBackupTask::class.java, EncryptedRestoreTask::class.java,
                ChangeCredentialsTask::class.java, FinishIntroTask::class.java)) {
            expect(Modifier.isPublic(task.modifiers), { "${task.simpleName} is not public" })
            expect(!Modifier.isFinal(task.modifiers),
                    { "${task.simpleName} must stay extendable like the Java class" })
            expect(runner.isAssignableFrom(task), { "${task.simpleName} no longer extends the runner" })
        }
        expect(Modifier.isAbstract(GenericBackupTask::class.java.modifiers), { "GenericBackupTask must stay abstract" })
        expect(Modifier.isAbstract(GenericRestoreTask::class.java.modifiers), { "GenericRestoreTask must stay abstract" })

        fun constructorParameters(type: Class<*>, parameterCount: Int): List<Class<*>> {
            val constructor = type.declaredConstructors.single { !it.isSynthetic && it.parameterCount == parameterCount }
            return constructor.parameterTypes.toList()
        }

        // The backup bases keep the (Context, Uri) constructor the concrete tasks delegate to.
        for (base in listOf(GenericBackupTask::class.java, GenericRestoreTask::class.java)) {
            expect(constructorParameters(base, 2) == listOf(Context::class.java, Uri::class.java),
                    { "${base.simpleName} constructor changed" })
        }

        // EncryptedBackupTask: both Java constructors survive (erasure drops the Entry element type).
        expect(constructorParameters(EncryptedBackupTask::class.java, 4) ==
                listOf(Context::class.java, java.util.ArrayList::class.java, String::class.java, Uri::class.java),
                { "EncryptedBackupTask(Context,ArrayList,String,Uri) changed" })
        expect(constructorParameters(EncryptedBackupTask::class.java, 6) ==
                listOf(Context::class.java, java.util.ArrayList::class.java, String::class.java, Uri::class.java,
                        EncryptedBackupTask.Mode::class.java, java.util.function.BooleanSupplier::class.java),
                { "EncryptedBackupTask(Context,ArrayList,String,Uri,Mode,BooleanSupplier) changed" })
        expect(EncryptedBackupTask.Mode::class.java.isEnum, { "Mode is not an enum" })
        expect(EncryptedBackupTask.Mode.values().map { it.name } == listOf("USER", "AUTO_EDIT", "BROADCAST"),
                { "Mode constants changed" })

        // Tasks whose signatures reference only compiled types keep their full member shapes.
        expect(constructorParameters(AuthenticationTask::class.java, 4) ==
                listOf(Context::class.java, BOOLEAN_TYPE, String::class.java, String::class.java),
                { "AuthenticationTask(Context,boolean,String,String) changed" })
        expect(constructorParameters(EncryptedRestoreTask::class.java, 4) ==
                listOf(Context::class.java, Uri::class.java, String::class.java, BOOLEAN_TYPE),
                { "EncryptedRestoreTask(Context,Uri,String,boolean) changed" })
        expect(constructorParameters(PGPRestoreTask::class.java, 3) ==
                listOf(Context::class.java, Uri::class.java, Intent::class.java),
                { "PGPRestoreTask(Context,Uri,Intent) changed" })
        expect(constructorParameters(PGPBackupTask::class.java, 3) ==
                listOf(Context::class.java, String::class.java, Uri::class.java),
                { "PGPBackupTask(Context,String,Uri) changed" })

        fun expectProtected(type: Class<*>, name: String, returnType: Class<*>) {
            // Generic overrides also produce public volatile bridge methods; assert the real one.
            val method = type.declaredMethods.first { it.name == name && !it.isBridge && !it.isSynthetic }
            expect(Modifier.isProtected(method.modifiers) && !Modifier.isStatic(method.modifiers),
                    { "${type.simpleName}.$name must stay a protected instance method" })
            expect(method.returnType == returnType, { "${type.simpleName}.$name return type changed" })
            expect(!Modifier.isFinal(method.modifiers), { "${type.simpleName}.$name must stay overridable" })
        }
        expectProtected(AuthenticationTask::class.java, "doInBackground", AuthenticationOutcome::class.java)
        expectProtected(AuthenticationTask::class.java, "discardResult", Void.TYPE)
        expectProtected(EncryptedRestoreTask::class.java, "doInBackground", BackupTaskResult::class.java)
        expectProtected(PGPRestoreTask::class.java, "doInBackground", BackupTaskResult::class.java)

        // The intro result keeps both Java constructors and its public final fields.
        val introResult = FinishIntroTask.Result::class.java
        expect(constructorParameters(introResult, 2) == listOf(BOOLEAN_TYPE, ByteArray::class.java),
                { "FinishIntroTask.Result(boolean,byte[]) changed" })
        expect(constructorParameters(introResult, 3) ==
                listOf(BOOLEAN_TYPE, ByteArray::class.java, LONG_TYPE),
                { "FinishIntroTask.Result(boolean,byte[],long) changed" })
        for ((name, fieldType) in listOf("saveSuccessful" to BOOLEAN_TYPE,
                "encryptionKey" to ByteArray::class.java, "generation" to LONG_TYPE)) {
            val found = introResult.declaredFields.first { it.name == name }
            expect(Modifier.isPublic(found.modifiers) && Modifier.isFinal(found.modifiers),
                    { "FinishIntroTask.Result.$name must stay a public final field" })
            expect(found.type == fieldType, { "FinishIntroTask.Result.$name type changed" })
        }
    }

    check("credential derivation matches an independent PBKDF2 and the RFC 6070 vector") {
        // RFC 6070, PBKDF2-HMAC-SHA1, P = "passwordPASSWORDpassword",
        // S = "saltSALTsaltSALTsaltSALTsaltSALTsalt", c = 4096, dkLen = 200 bits.
        // The factory derives 256 bits, so the vector is the 25-byte prefix of its output.
        val password = "passwordPASSWORDpassword"
        val salt = ascii("saltSALTsaltSALTsaltSALTsaltSALTsalt")
        val derived = EncryptionHelper.generatePBKDF2Credentials(password, salt, 4096)
        val combined = derived.key + derived.password
        expect(hex(combined.copyOfRange(0, 25)) == "3d2eec4fe41c849b80c8d83662c0e44a8b291a964cf2f07038",
                { "RFC 6070 prefix mismatch: ${hex(combined)}" })

        val own = ownPbkdf2(password, salt, 4096, 32)
        expect(hex(combined) == hex(own), { "the independent PBKDF2 derivation differs" })
        expect(derived.password.size == 16 && derived.key.size == 16, { "the halves must stay 16 bytes" })
        expect(hex(derived.key) == hex(own.copyOfRange(0, 16)), { "the key must be the lower half" })
        expect(hex(derived.password) == hex(own.copyOfRange(16, 32)), { "the password must be the upper half" })

        // Fresh arrays per call: wiping one result must not corrupt the next.
        val again = EncryptionHelper.generatePBKDF2Credentials(password, salt, 4096)
        expect(again.key !== derived.key && again.password !== derived.password, { "results share arrays" })
        Arrays.fill(derived.key, 0.toByte())
        Arrays.fill(derived.password, 0.toByte())
        expect(hex(again.key + again.password) == hex(own), { "wiping one result changed another" })

        // The backup key is the same PBKDF2 output without the split.
        val backupKey = EncryptionHelper.generateSymmetricKeyPBKDF2(password, 4096, salt)
        expect(backupKey.algorithm == "AES", { "backup key algorithm ${backupKey.algorithm}" })
        expect(backupKey.encoded.contentEquals(own), { "backup key derivation differs from PBKDF2" })
    }

    check("credential derivation rejects the Java-invalid parameters before the provider") {
        val salt = ByteArray(8) { 1 }
        val tooLong = "x".repeat(InputPolicy.MAX_TEXT_CHARS + 1)
        for ((label, invalid) in listOf<Pair<String, () -> Unit>>(
                "null password" to { EncryptionHelper.generatePBKDF2Credentials(null, salt, 1000) },
                "null salt" to { EncryptionHelper.generatePBKDF2Credentials("password", null, 1000) },
                "short salt" to { EncryptionHelper.generatePBKDF2Credentials("password", ByteArray(7) { 1 }, 1000) },
                "zero iterations" to { EncryptionHelper.generatePBKDF2Credentials("password", salt, 0) },
                "too many iterations" to { EncryptionHelper.generatePBKDF2Credentials("password", salt, 20_000_001) },
                "over-long password" to { EncryptionHelper.generatePBKDF2Credentials(tooLong, salt, 1000) },
                "backup: null password" to { EncryptionHelper.generateSymmetricKeyPBKDF2(null, 1000, salt) },
                "backup: null salt" to { EncryptionHelper.generateSymmetricKeyPBKDF2("password", 1000, null) },
                "backup: short salt" to { EncryptionHelper.generateSymmetricKeyPBKDF2("password", 1000, ByteArray(7)) },
                "backup: zero iterations" to { EncryptionHelper.generateSymmetricKeyPBKDF2("password", 0, salt) })) {
            expectThrows(IllegalArgumentException::class.java, invalid)
        }
        // The boundaries themselves stay accepted: an 8-byte salt, MAX_TEXT_CHARS password, one iteration.
        expect(EncryptionHelper.generatePBKDF2Credentials("y".repeat(InputPolicy.MAX_TEXT_CHARS), salt, 1).key.size == 16,
                { "the maximum accepted password was rejected" })
        expect(EncryptionHelper.generateSymmetricKeyPBKDF2("password", 1, salt).algorithm == "AES",
                { "the minimum accepted backup parameters were rejected" })
    }

    check("legacy SHA-256 key derivation and raw byte wrapping are unchanged") {
        expect(hex(EncryptionHelper.generateSymmetricKeyFromPassword("").encoded) ==
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", { "SHA-256 empty-password key" })
        expect(hex(EncryptionHelper.generateSymmetricKeyFromPassword("password").encoded) ==
                "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8", { "SHA-256 password key" })
        expect(EncryptionHelper.generateSymmetricKeyFromPassword("password").algorithm == "AES", { "legacy key algorithm" })
        expect(EncryptionHelper.generateSymmetricKey(ByteArray(16) { 7 }).encoded.contentEquals(ByteArray(16) { 7 }),
                { "generateSymmetricKey must wrap the given bytes" })
    }

    check("the framed AES/GCM reader rejects null and every short frame before provider work") {
        // The desktop JDK's SunJCE rejects the unchanged Android nonce type (it requires
        // GCMParameterSpec while the app's IvParameterSpec path is what ships), so the cipher
        // itself cannot run here. What this executes is the security-relevant part that precedes
        // the provider: null and every frame shorter than nonce + 128-bit tag fail closed with the
        // Java IllegalArgumentException. The vectors, tamper and tag cases on real AES/GCM stay with
        // ApplicationTest/EncryptionSecurityTest on a device.
        val key = SecretKeySpec(ByteArray(16) { 3 }, "AES")
        expectThrows(IllegalArgumentException::class.java) { EncryptionHelper.decrypt(key, null) }
        for (length in 0 until ENCRYPTION_IV_BYTES + 16) {
            expectThrows(IllegalArgumentException::class.java) { EncryptionHelper.decrypt(key, ByteArray(length)) }
        }
        // A frame that is long enough reaches the provider instead, so it is not this guard's job.
        expect(ENCRYPTION_IV_BYTES + 16 == 28, { "the frame guard bound changed" })
    }

    check("asymmetric helpers encrypt and decrypt with a real RSA key pair") {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()
        val plain = ascii("synthetic-backup-password")
        val cipherText = EncryptionHelper.encrypt(pair.public, plain)
        expect(cipherText.size == 256, { "PKCS#1 v1.5 ciphertext length ${cipherText.size}" })
        expect(!cipherText.contentEquals(plain), { "RSA ciphertext exposes the plaintext" })
        expect(EncryptionHelper.decrypt(pair.private, cipherText).contentEquals(plain), { "RSA round trip changed the secret" })
        expectThrows(GeneralSecurityException::class.java) { EncryptionHelper.decrypt(generator.generateKeyPair().private, cipherText) }
        expectThrows(GeneralSecurityException::class.java) { EncryptionHelper.decrypt(pair.private, ByteArray(8)) }
    }

    check("random helpers keep their lengths and the documented iteration bounds") {
        expect(EncryptionHelper.generateRandom(0).isEmpty(), { "zero-length random returned bytes" })
        expect(EncryptionHelper.generateRandom(ENCRYPTION_IV_BYTES).size == ENCRYPTION_IV_BYTES, { "nonce length" })
        expect(EncryptionHelper.generateRandom(64).size == 64, { "random length" })
        val first = EncryptionHelper.generateRandom(32)
        val second = EncryptionHelper.generateRandom(32)
        expect(!first.contentEquals(second), { "two 32-byte draws were identical" })

        // Constants.PBKDF2_MIN/MAX_BACKUP_ITERATIONS and PBKDF2_MIN_AUTH_ITERATIONS are
        // package-private Java constants; the Python guard pins these literals against them.
        val seen = HashSet<Int>()
        repeat(300) {
            val iterations = EncryptionHelper.generateRandomIterations()
            expect(iterations in BACKUP_ITERATIONS_MIN..BACKUP_ITERATIONS_MAX, { "backup iterations out of range: $iterations" })
            seen.add(iterations)
        }
        expect(seen.size > 1, { "generateRandomIterations returned a constant" })

        val benchmarked = EncryptionHelper.benchmarkIterations("synthetic-password", ByteArray(16) { 5 })
        expect(benchmarked in AUTH_ITERATIONS_MIN..2000000, { "benchmarked iterations out of range: $benchmarked" })
    }

    check("converted crypto and keystore helpers keep their Java-visible API shapes") {
        // EncryptionHelper's static surface, throws clauses and callback interface are pinned by the
        // Python source guard, because the signatures that name the platform's crypto types and the
        // nested callback are not all reflected here. Everything that can be reflected is asserted
        // below.
        val helper = EncryptionHelper::class.java
        expect(Modifier.isFinal(helper.modifiers), { "EncryptionHelper must be the final object class" })
        expect(Modifier.isPrivate(helper.declaredConstructors.single().modifiers), { "the object constructor must stay private" })

        val credentials = EncryptionHelper.PBKDF2Credentials::class.java
        expect(!Modifier.isFinal(credentials.modifiers), { "PBKDF2Credentials must stay extendable like the Java class" })
        expect(Modifier.isPublic(credentials.declaredConstructors.single().modifiers),
                { "PBKDF2Credentials must keep its public no-arg constructor" })
        for (name in listOf("password", "key")) {
            val found = field(credentials, name) ?: throw AssertionError("PBKDF2Credentials.$name is missing")
            expect(found.type == ByteArray::class.java, { "PBKDF2Credentials.$name type ${found.type}" })
            expect(Modifier.isPublic(found.modifiers) && !Modifier.isStatic(found.modifiers) && !Modifier.isFinal(found.modifiers),
                    { "PBKDF2Credentials.$name must stay a public mutable instance field" })
        }

        expect(EncryptionHelper.EncryptionChangeResult::class.java.isEnum, { "EncryptionChangeResult must stay an enum" })
        expect(EncryptionHelper.EncryptionChangeResult.values().map { it.name } ==
                listOf("SUCCESS", "CHANGE_FAILURE", "BACKUP_FAILURE", "MISSING_NEW_KEY", "DECRYPTION_FAILED", "TASK_CREATION_FAILED"),
                { "EncryptionChangeResult constants changed" })

        // KeyStoreHelper's signatures only mention types that load here, so its whole surface is
        // reflectable: five static members with their unchanged parameter and throws clauses.
        val keyStore = KeyStoreHelper::class.java
        expect(Modifier.isFinal(keyStore.modifiers), { "KeyStoreHelper must be the final object class" })
        expect(Modifier.isPrivate(keyStore.declaredConstructors.single().modifiers), { "the object constructor must stay private" })
        expect(publicMethods(keyStore).containsAll(listOf(
                "static void wipeKeys(Context)",
                "static KeyPair loadAsymmetricKeyPair(Context,String)",
                "static KeyPair loadOrGenerateAsymmetricKeyPair(Context,String)",
                "static SecretKey loadEncryptionKeyFromKeyStore(Context,boolean)",
                "static SecretKey createEncryptionKey(Context)")),
                { "KeyStoreHelper's static surface changed: ${publicMethods(keyStore)}" })
        expectThrowsClause(keyStore, "loadAsymmetricKeyPair", listOf(Context::class.java, String::class.java),
                GeneralSecurityException::class.java, IOException::class.java)
        expectThrowsClause(keyStore, "loadOrGenerateAsymmetricKeyPair", listOf(Context::class.java, String::class.java),
                GeneralSecurityException::class.java, IOException::class.java)
        for ((name, parameters) in listOf("wipeKeys" to listOf(Context::class.java),
                "loadEncryptionKeyFromKeyStore" to listOf(Context::class.java, BOOLEAN_TYPE),
                "createEncryptionKey" to listOf(Context::class.java))) {
            val method = staticMethod(keyStore, name, parameters)
            expect(method.exceptionTypes.isEmpty(), { "KeyStoreHelper.$name gained checked exceptions" })
        }
    }


    check("the compiled settings class keeps the Java-visible surface of Settings.java") {
        // `Class.getDeclaredMethods()` resolves every declared signature eagerly, so the compiled class
        // file is read directly: it carries the same names, descriptors and access flags, without
        // resolving a single referenced type — the surface every Java caller compiles against.
        val compiled = compiledClass(Settings::class.java)

        expect(Modifier.isPublic(compiled.flags), { "Settings must stay public" })
        expect(!Modifier.isFinal(compiled.flags) && !Modifier.isAbstract(compiled.flags),
                { "Settings must stay an open, instantiable class (androidTest subclasses it)" })
        expect(compiled.superName == "java/lang/Object", { "Settings gained a superclass: ${compiled.superName}" })

        // The public surface, verbatim and complete, reviewed member by member against the Java file.
        // Adding or dropping one is a deliberate change to the Java-visible contract.
        val expectedPublic = listOf(
            "<init>(Landroid/content/Context;)V",
            "clear(Z)V",
            "commitVaultSettings(Ljava/lang/String;Lorg/shadowice/flocke/andotp/Utilities/Constants\$EncryptionType;Lorg/shadowice/flocke/andotp/Utilities/Constants\$AuthMethod;Lorg/shadowice/flocke/andotp/Utilities/Settings\$PreparedCredentials;Z)Z",
            "disableAutomaticBackups()V",
            "getAllTagsToggle()Z",
            "getAndroidBackupServiceEnabled()Z",
            "getAuthCredentials()Ljava/lang/String;",
            "getAuthInactivity()Z",
            "getAuthInactivityDelay()I",
            "getAuthMethod()Lorg/shadowice/flocke/andotp/Utilities/Constants\$AuthMethod;",
            "getAutoBackupEncryptedFullEnabled()Z",
            "getAutoBackupEncryptedPasswordsEnabled()Z",
            "getAutoUnlockAfterAutofill()Z",
            "getBackupAsk()Z",
            "getBackupBroadcasts()Ljava/util/Set;",
            "getBackupLocation()Landroid/net/Uri;",
            "getBackupPassword()Ljava/lang/String;",
            "getBackupPasswordEnc()Ljava/lang/String;",
            "getBlockAccessibility()Z",
            "getBlockAutofill()Z",
            "getBoolean(IZ)Z",
            "getCardLayout()Lorg/shadowice/flocke/andotp/Utilities/Constants\$CardLayouts;",
            "getDefaultBackupType()Lorg/shadowice/flocke/andotp/Utilities/Constants\$BackupType;",
            "getEncryption()Lorg/shadowice/flocke/andotp/Utilities/Constants\$EncryptionType;",
            "getFirstTimeWarningShown()Z",
            "getIsAppendingDateTimeToBackups()Z",
            "getIterations()I",
            "getLabelDisplay()Lorg/shadowice/flocke/andotp/Utilities/Constants\$LabelDisplay;",
            "getLabelSize()I",
            "getLocale()Ljava/util/Locale;",
            "getNoTagsToggle()Z",
            "getOldCredentials(Lorg/shadowice/flocke/andotp/Utilities/Constants\$AuthMethod;)Ljava/lang/String;",
            "getOpenPGPEncryptionUserIDs()Ljava/lang/String;",
            "getOpenPGPProvider()Ljava/lang/String;",
            "getOpenPGPSigningKey()J",
            "getOpenPGPVerify()Z",
            "getPanicResponse()Ljava/util/Set;",
            "getRelockOnBackground()Z",
            "getRelockOnScreenOff()Z",
            "getSalt()[B",
            "getScreenshotsEnabled()Z",
            "getSearchValues()Ljava/util/List;",
            "getShowPrevToken()Z",
            "getSortMode()Lorg/shadowice/flocke/andotp/Utilities/Constants\$SortMode;",
            "getSpecialFeatures()Z",
            "getTagFunctionality()Lorg/shadowice/flocke/andotp/Utilities/Constants\$TagFunctionality;",
            "getTagToggle(Ljava/lang/String;)Z",
            "getTapDouble()Lorg/shadowice/flocke/andotp/Utilities/Constants\$TapMode;",
            "getTapSingle()Lorg/shadowice/flocke/andotp/Utilities/Constants\$TapMode;",
            "getTapToReveal()Z",
            "getTapToRevealTimeout()I",
            "getTheme()I",
            "getThumbnailSize()I",
            "getThumbnailVisible()Z",
            "getTokenSplitGroupSize()I",
            "getUsedTokensDialogShown()Z",
            "isBackupLocationSet()Z",
            "isEncryptedBackupBroadcastEnabled()Z",
            "isFocusSearchOnStartEnabled()Z",
            "isHideGlobalTimeoutEnabled()Z",
            "isHideIssuerEnabled()Z",
            "isHighlightTokenOptionEnabled()Z",
            "isMinimizeAppOnCopyEnabled()Z",
            "isShowIndividualTimeoutsEnabled()Z",
            "prepareAuthCredentials(Ljava/lang/String;)Lorg/shadowice/flocke/andotp/Utilities/Settings\$PreparedCredentials;",
            "registerPreferenceChangeListener(Landroid/content/SharedPreferences\$OnSharedPreferenceChangeListener;)V",
            "removeAuthPINHash()V",
            "removeAuthPasswordHash()V",
            "setAllTagsToggle(Ljava/lang/Boolean;)V",
            "setAndroidBackupServiceEnabled(Z)V",
            "setAuthCredentials(Ljava/lang/String;)[B",
            "setAuthCredentials(Ljava/lang/String;Ljava/util/function/BooleanSupplier;)[B",
            "setAuthMethod(Lorg/shadowice/flocke/andotp/Utilities/Constants\$AuthMethod;)V",
            "setBackupLocation(Landroid/net/Uri;)V",
            "setBoolean(IZ)V",
            "setDefaultBackupType(Lorg/shadowice/flocke/andotp/Utilities/Constants\$BackupType;)V",
            "setEncryption(Ljava/lang/String;)V",
            "setEncryption(Lorg/shadowice/flocke/andotp/Utilities/Constants\$EncryptionType;)V",
            "setFirstTimeWarningShown(Z)V",
            "setIterations(I)V",
            "setLocale(Ljava/lang/String;)V",
            "setNoTagsToggle(Ljava/lang/Boolean;)V",
            "setSalt([B)V",
            "setSortMode(Lorg/shadowice/flocke/andotp/Utilities/Constants\$SortMode;)V",
            "setSpecialFeatures(Z)V",
            "setTagToggle(Ljava/lang/String;Ljava/lang/Boolean;)V",
            "setUsedTokensDialogShown(Z)V",
            "tryAcquireBroadcastBackupSlot()Z",
            "unregisterPreferenceChangeListener(Landroid/content/SharedPreferences\$OnSharedPreferenceChangeListener;)V",
        )
        val publicSurface = compiled.publicMembers.map { it.signature }
        expect(publicSurface.toSet() == expectedPublic.toSet() && publicSurface.size == expectedPublic.size, {
            "the public surface changed: missing ${expectedPublic.filterNot { it in publicSurface }}, " +
                    "unexpected ${publicSurface.filterNot { it in expectedPublic }}"
        })

        // Everything else stayed private, exactly as in the Java class: an accessor that had widened to
        // public or protected shows up in the list above, one that lost a modifier shows up here.
        val expectedPrivate = listOf(
            "getAutoBackupEncryptedSetting()Lorg/shadowice/flocke/andotp/Utilities/Constants\$AutoBackup;",
            "getInt(II)I",
            "getIntValue(II)I",
            "getLong(IJ)J",
            "getResInt(I)I",
            "getResString(I)Ljava/lang/String;",
            "getString(II)Ljava/lang/String;",
            "getString(ILjava/lang/String;)Ljava/lang/String;",
            "getStringSet(ILjava/util/Set;)Ljava/util/Set;",
            "migrateDeprecatedSettings()V",
            "remove(I)V",
            "setInt(II)V",
            "setString(ILjava/lang/String;)V",
            "setStringSet(ILjava/util/Set;)V",
        )
        val privateSurface = compiled.privateMembers.map { it.signature }
        expect(privateSurface.toSet() == expectedPrivate.toSet() && privateSurface.size == expectedPrivate.size,
                { "the private members changed: ${privateSurface.sorted()}" })
        val widened = compiled.methods.filter { !it.isPublic && !it.isPrivate && !it.isSynthetic && it.name != "<clinit>" }
        expect(widened.isEmpty(), { "a member became protected or package-private: ${widened.map { it.signature }}" })
        expect(compiled.methods.none { it.isSynthetic && !it.isPrivate },
                { "a synthetic member leaked into the Java-visible surface" })

        // Java reaches every member through an instance and subclasses may override them, so nothing
        // may become static or final.
        expect(compiled.publicMembers.all { !it.isStatic && !it.isFinal },
                { "static or final public members: " +
                        compiled.publicMembers.filter { it.isStatic || it.isFinal }.map { it.signature } })

        // The acceptance-critical shapes must not gain a primitive overload: Java's
        // `setAllTagsToggle(false)` means "no explicit choice", which only the boxed parameter can say.
        for (rejected in listOf("setAllTagsToggle(Z)V", "setNoTagsToggle(Z)V",
                "setTagToggle(Ljava/lang/String;Z)V")) {
            expect(compiled.methods.none { it.signature == rejected },
                    { "$rejected exists: the boxed Boolean parameter is part of the Java contract" })
        }

        // Two private collaborators, the two language lists and the single companion placeholder
        // Kotlin adds; nothing else may become a field.
        expect(compiled.fields.map { it.signature }.toSet() == setOf(
                "Companion Lorg/shadowice/flocke/andotp/Utilities/Settings\$Companion;",
                "context Landroid/content/Context;",
                "settings Landroid/content/SharedPreferences;",
                "newLangs Ljava/util/List;",
                "oldLangs Ljava/util/List;"),
                { "the fields changed: ${compiled.fields.map { it.signature }}" })
        val companion = compiled.fields.single { it.name == "Companion" }
        expect(companion.isPublic && companion.isStatic && companion.isFinal,
                { "the companion holder must stay public static final" })
        for (fieldName in listOf("context", "settings")) {
            val field = compiled.fields.single { it.name == fieldName }
            expect(field.isPrivate && field.isFinal && !field.isStatic,
                    { "$fieldName must stay a private final instance field: ${field.flags}" })
        }
        for (fieldName in listOf("newLangs", "oldLangs")) {
            val field = compiled.fields.single { it.name == fieldName }
            expect(field.isPrivate && field.isFinal && field.isStatic,
                    { "$fieldName must stay a private static final list: ${field.flags}" })
        }
        expect(compiled.fields.single { it.name == "context" }.descriptor == "Landroid/content/Context;" &&
                compiled.fields.single { it.name == "settings" }.descriptor == "Landroid/content/SharedPreferences;",
                { "the two collaborators changed type" })

        // The nested holder is what Java builds in `prepareAuthCredentials` and reads `credentials.key`
        // from: public final, public final JvmField members, and only `destroy()` on top.
        val prepared = compiledClass(Settings.PreparedCredentials::class.java)
        expect(Modifier.isPublic(prepared.flags) && Modifier.isFinal(prepared.flags) &&
                !Modifier.isAbstract(prepared.flags), { "PreparedCredentials must stay a public final class" })
        expect(prepared.methods.map { it.signature }.toSet() ==
                setOf("<init>([BLjava/lang/String;Ljava/lang/String;I)V", "destroy()V"),
                { "PreparedCredentials methods changed: ${prepared.methods.map { it.signature }}" })
        expect(prepared.fields.map { it.signature }.toSet() ==
                setOf("key [B", "verifier Ljava/lang/String;", "salt Ljava/lang/String;", "iterations I"),
                { "PreparedCredentials fields changed: ${prepared.fields.map { it.signature }}" })
        expect(prepared.fields.all { it.isPublic && it.isFinal },
                { "a PreparedCredentials field stopped being public final" })
        expect(prepared.privateMembers.isEmpty(), { "an unexpected private member appeared" })
    }

    check("the settings facade keeps the stored keys and the documented non-enum defaults") {
        val fixture = SettingsFixture()
        val settings = fixture.settings

        // An empty store must behave like a fresh install: every accessor falls back to its default.
        expect(settings.getRelockOnScreenOff(), { "relock on screen off must default to true" })
        expect(!settings.getRelockOnBackground(), { "relock on background must default to false" })
        expect(!settings.getBlockAccessibility(), { "block accessibility" })
        expect(!settings.getBlockAutofill(), { "block autofill" })
        expect(!settings.getAutoUnlockAfterAutofill(), { "auto unlock after autofill" })
        expect(!settings.getScreenshotsEnabled(), { "screenshots" })
        expect(!settings.getShowPrevToken(), { "show previous token" })
        expect(settings.getBackupAsk(), { "backup ask" })
        expect(settings.getIsAppendingDateTimeToBackups(), { "append date/time to backups" })
        expect(settings.isHighlightTokenOptionEnabled(), { "highlight token" })
        expect(!settings.isHideGlobalTimeoutEnabled(), { "hide global timeout" })
        expect(!settings.isShowIndividualTimeoutsEnabled(), { "show individual timeouts" })
        expect(!settings.isFocusSearchOnStartEnabled(), { "focus search on start" })
        expect(!settings.isHideIssuerEnabled(), { "hide issuer" })
        expect(!settings.isMinimizeAppOnCopyEnabled(), { "minimize on copy" })
        expect(!settings.getAuthInactivity(), { "auth inactivity" })
        expect(settings.getAuthInactivityDelay() == 0, { "auth inactivity delay" })
        expect(!settings.getUsedTokensDialogShown(), { "used tokens dialog" })
        expect(!settings.getFirstTimeWarningShown(), { "first-time warning" })
        expect(!settings.getSpecialFeatures(), { "special features" })
        expect(!settings.getOpenPGPVerify(), { "OpenPGP verify" })
        expect(settings.getOpenPGPSigningKey() == 0L, { "OpenPGP signing key" })
        expect(settings.getOpenPGPProvider() == "" && settings.getOpenPGPEncryptionUserIDs() == "",
                { "the OpenPGP selectors must default to empty" })
        expect(!settings.isBackupLocationSet(), { "no backup location is configured by default" })
        expect(settings.getBackupPassword() == "" && settings.getBackupPasswordEnc() == "",
                { "the backup password must default to empty" })
        expect(settings.getAuthCredentials() == "", { "the credentials must default to empty" })
        expect(settings.getPanicResponse().isEmpty(), { "the panic response must default to empty" })
        expect(settings.getBackupBroadcasts().isEmpty() && !settings.isEncryptedBackupBroadcastEnabled(),
                { "no backup broadcast is enabled by default" })
        expect(settings.getAllTagsToggle() && settings.getNoTagsToggle(), { "both tag toggles default to true" })
        expect(!settings.getAndroidBackupServiceEnabled(), { "the platform backup service stays off" })
        expect(settings.getTapToRevealTimeout() == 30, { "tap to reveal timeout default" })
        expect(settings.getLabelSize() == 18, { "label size default" })
        expect(settings.getTokenSplitGroupSize() == 3, { "split group size default" })
        expect(settings.getIterations() == 30000, { "the iteration default must stay PBKDF2_MIN_AUTH_ITERATIONS" })
        expect(settings.getThumbnailSize() == 92 && settings.getThumbnailVisible(),
                { "the 46dp default at density 2 must resolve through DimensionConverter: ${settings.getThumbnailSize()}" })

        // The defaults this code parses into enums are resource values; they are read here and
        // asserted (as the strings the code then parses) so the XML defaults stay reachable, and the
        // enum mapping itself is pinned by the source guard and KotlinSettingsInteropTest.
        fixture.context.consulted.clear()
        settings.getRelockOnBackground()
        expect(fixture.context.consulted == listOf(R.string.settings_key_relock_background),
                { "consulted ${fixture.context.consulted}" })
        fixture.context.consulted.clear()
        settings.getThumbnailSize()
        // Which resources are read is the contract; the order they are evaluated in is not.
        expect(fixture.context.consulted.toSet() ==
                setOf(R.string.settings_key_thumbnail_size, R.string.settings_default_thumbnail_size) &&
                fixture.context.consulted.size == 2,
                { "consulted ${fixture.context.consulted}" })

        // Setters write exactly the key they read and nothing else.
        fixture.store.edit().clear().commit()
        settings.setFirstTimeWarningShown(true)
        expect(fixture.store.getBoolean(settingsKey(R.string.settings_key_security_backup_warning), false),
                { "setFirstTimeWarningShown did not write its key" })
        settings.setUsedTokensDialogShown(true)
        expect(fixture.store.getAll().keys == setOf(
                settingsKey(R.string.settings_key_security_backup_warning),
                settingsKey(R.string.settings_key_last_used_dialog_shown)),
                { "setters wrote unexpected keys: ${fixture.store.getAll().keys}" })
        settings.setIterations(42000)
        expect(settings.getIterations() == 42000, { "iterations round trip" })

        settings.setLocale("de_DE")
        expect(settings.getLocale() == Locale("de", "DE"), { "locale ${settings.getLocale()}" })
        settings.setLocale("de")
        expect(settings.getLocale() == Locale("de"), { "locale ${settings.getLocale()}" })
        settings.setLocale("system")
        expect(settings.getLocale() == Tools.getSystemLocale(),
                { "the literal system language must resolve through Tools.getSystemLocale()" })

        settings.setBackupLocation(Uri.parse("content://org.example.backup/vault.aes"))
        expect(settings.isBackupLocationSet(), { "a stored location must be reported as set" })
        expect(settings.getBackupLocation().toString() == "content://org.example.backup/vault.aes",
                { "backup location round trip: ${settings.getBackupLocation()}" })

        // The salt/verifier pair is Base64 URL_SAFE, and a missing salt is a programming error.
        settings.setSalt(ByteArray(16) { (it + 1).toByte() })
        val salt = settings.getSalt()
        expect(salt.contentEquals(ByteArray(16) { (it + 1).toByte() }), { "the salt must round-trip" })
        expect(!settings.getSalt().let { java.util.Base64.getEncoder().encodeToString(it) }.contains('+'),
                { "the stored salt must use the URL-safe alphabet" })

        // The language migration lists are private static data on the class itself; the mapping they
        // encode is asserted here and applied by migrateDeprecatedSettings, which needs the file
        // transaction and stays device-tested.
        fun languageList(name: String): List<String> {
            val field = Settings::class.java.getDeclaredField(name).also { it.isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            return field.get(null) as List<String>
        }
        val newLangs = languageList("newLangs")
        val oldLangs = languageList("oldLangs")
        expect(newLangs.size == 25 && oldLangs.size == 25, { "language list sizes: ${newLangs.size}/${oldLangs.size}" })
        for ((old, new) in listOf(
            "ar_SA" to "ar", "de_DE" to "de", "en_US" to "en", "pt_BR" to "pt_BR",
            "zh_TW" to "zh_TW", "fr_FR" to "fr", "uk_UA" to "uk"
        )) {
            expect(newLangs[oldLangs.indexOf(old)] == new, { "the $old migration maps to ${newLangs[oldLangs.indexOf(old)]}" })
        }
    }

    check("tag toggles keep the unchecked-tag representation and the boxed setters' failure mode") {
        val fixture = SettingsFixture()
        val settings = fixture.settings

        expect(settings.getTagToggle("unknown"), { "an unknown tag must default to checked" })

        settings.setTagToggle("work", false)
        expect(!settings.getTagToggle("work"), { "an unchecked tag must stay unchecked" })
        expect(fixture.storedSet(R.string.settings_key_tags_toggles) == setOf("work"),
                { "only unchecked tags are stored: ${fixture.storedSet(R.string.settings_key_tags_toggles)}" })

        settings.setTagToggle("work", true)
        expect(settings.getTagToggle("work"), { "re-checking a tag must restore the default" })
        expect(fixture.storedSet(R.string.settings_key_tags_toggles).isEmpty(),
                { "a checked tag must be removed, not stored: ${fixture.storedSet(R.string.settings_key_tags_toggles)}" })

        settings.setTagToggle("one", false)
        settings.setTagToggle("two", false)
        settings.setTagToggle("one", true)
        expect(fixture.storedSet(R.string.settings_key_tags_toggles) == setOf("two"),
                { "the stored unchecked set is wrong: ${fixture.storedSet(R.string.settings_key_tags_toggles)}" })
        expect(!settings.getTagToggle("two") && settings.getTagToggle("three"),
                { "unknown tags must stay checked while stored ones are reported as unchecked" })

        // A read must not alias the stored set: mutating the returned set cannot uncheck a stored tag.
        fixture.storedSet(R.string.settings_key_tags_toggles).add("three")
        expect(settings.getTagToggle("three"), { "the stored set was shared with the caller" })

        settings.setAllTagsToggle(false)
        expect(!settings.getAllTagsToggle(), { "setAllTagsToggle(false)" })
        settings.setNoTagsToggle(false)
        expect(!settings.getNoTagsToggle(), { "setNoTagsToggle(false)" })

        // The boxed parameters dereference their argument, exactly like the Java unboxing did.
        expectThrows(NullPointerException::class.java) { settings.setAllTagsToggle(null) }
        expectThrows(NullPointerException::class.java) { settings.setNoTagsToggle(null) }
        expectThrows(NullPointerException::class.java) { settings.setTagToggle("work", null) }
        expect(!settings.getAllTagsToggle(), { "a null boxed argument must not write a default" })
    }

    check("the broadcast-backup slot is throttled, survives a backwards clock and is cleared") {
        val fixture = SettingsFixture()
        val settings = fixture.settings

        expect(settings.tryAcquireBroadcastBackupSlot(), { "the first broadcast backup must be granted" })
        expect(!settings.tryAcquireBroadcastBackupSlot(), { "a second one within the window must be refused" })
        expect(fixture.store.getLong("last_broadcast_backup_elapsed", -1) > 0,
                { "the granted slot must be stamped with the elapsed realtime" })

        // A clock that moved backwards must not block the slot forever (`now >= last` guard).
        fixture.store.edit().putLong("last_broadcast_backup_elapsed", SystemClock.elapsedRealtime() + 60000).commit()
        expect(settings.tryAcquireBroadcastBackupSlot(), { "a backwards clock must not block the slot" })
        // An expired window is granted again.
        fixture.store.edit().putLong("last_broadcast_backup_elapsed", SystemClock.elapsedRealtime() - 60000).commit()
        expect(settings.tryAcquireBroadcastBackupSlot(), { "an expired window must be granted" })

        fixture.store.edit().putStringSet(settingsKey(R.string.settings_key_backup_broadcasts), setOf("encrypted")).commit()
        expect(settings.isEncryptedBackupBroadcastEnabled(), { "the encrypted broadcast opt-in" })
        settings.disableAutomaticBackups()
        expect(!settings.isEncryptedBackupBroadcastEnabled(), { "disabling must clear the opt-in" })
        expect(fixture.storedSet(R.string.settings_key_backup_broadcasts).isEmpty(),
                { "disabling must store an empty broadcast set" })
        expect(fixture.stored(R.string.settings_key_auto_backup_password_enc) == "off",
                { "disabling must store the off value: ${fixture.stored(R.string.settings_key_auto_backup_password_enc)}" })
    }

    check("credential staging derives the key/verifier split and refuses without consent") {
        val fixture = SettingsFixture()
        val settings = fixture.settings

        expectThrows(IllegalArgumentException::class.java) { settings.prepareAuthCredentials(null) }
        expectThrows(IllegalArgumentException::class.java) { settings.prepareAuthCredentials("") }
        expectThrows(IllegalArgumentException::class.java) {
            settings.prepareAuthCredentials("x".repeat(InputPolicy.MAX_TEXT_CHARS + 1))
        }
        expectThrows(IllegalStateException::class.java) { settings.getSalt() }
        fixture.seed(R.string.settings_key_split_group_size, "not-a-number")
        expectThrows(NumberFormatException::class.java) { settings.getTokenSplitGroupSize() }
        fixture.store.edit().remove(settingsKey(R.string.settings_key_split_group_size)).commit()

        val prepared = settings.prepareAuthCredentials("synthetic-password")
        try {
            // PBKDF2_LENGTH is 256 bits, split into the 128-bit key and the 128-bit password half.
            expect(prepared.key.size == 16, { "the derived key must stay 128 bits, was ${prepared.key.size}" })
            expect(prepared.iterations >= 30000, { "iterations must stay bounded: ${prepared.iterations}" })
            val salt = Base64.decode(prepared.salt, Base64.URL_SAFE)
            expect(salt.size == 16, { "the stored salt must be 16 bytes, was ${salt.size}" })
            val derived = EncryptionHelper.generatePBKDF2Credentials("synthetic-password", salt, prepared.iterations)
            try {
                expect(derived.key.contentEquals(prepared.key),
                        { "the prepared key must be the derived key for the stored salt/iterations" })
                expect(Base64.decode(prepared.verifier, Base64.URL_SAFE).contentEquals(derived.password),
                        { "the verifier must be the Base64 URL_SAFE form of the derived password half" })
                expect(!prepared.salt.contains('+') && !prepared.verifier.contains('+'),
                        { "prepared material must use the URL-safe alphabet" })
            } finally {
                derived.key.fill(0)
                derived.password.fill(0)
            }

            // destroy() wipes the copy it owns and nothing else.
            val foreign = prepared.key.clone()
            prepared.destroy()
            expect(prepared.key.all { it == 0.toByte() }, { "destroy must wipe the owned key" })
            expect(foreign.any { it != 0.toByte() }, { "destroy must not touch a foreign array" })
        } finally {
            prepared.destroy()
        }

        // No mutation may be staged without the caller's consent: the supplier is asked first, so the
        // consent-free path cannot reach the encryption type / vault read at all here.
        val untouched = fixture.store.getAll()
        expect(settings.setAuthCredentials("synthetic-password") { false } == null,
                { "the permission supplier must be asked before the commit" })
        expect(fixture.store.getAll() == untouched, { "a non-consenting upgrade must not write preferences" })
    }

    check("theme and locale selection follow the stored mode, the night configuration and the defaults") {
        val fixture = SettingsFixture()
        val settings = fixture.settings

        expect(settings.getTheme() == R.style.AppTheme_NoActionBar, { "the default theme must be light" })
        fixture.seed(R.string.settings_key_theme, "dark")
        expect(settings.getTheme() == R.style.AppTheme_Dark_NoActionBar, { "the dark theme" })
        fixture.seed(R.string.settings_key_theme, "black")
        expect(settings.getTheme() == R.style.AppTheme_Black_NoActionBar, { "the black theme" })
        fixture.seed(R.string.settings_key_theme, "green")
        expect(settings.getTheme() == R.style.AppTheme_NoActionBar,
                { "an unknown theme name must keep the initial light theme" })

        // "auto" (the XML default) needs API 29+ and reads the night mode from the configuration.
        val previousSdk = Build.VERSION.SDK_INT
        try {
            Build.VERSION.SDK_INT = Build.VERSION_CODES.Q - 1
            expect(settings.getTheme() == R.style.AppTheme_NoActionBar,
                    { "below API 29 the stored theme name decides" })

            Build.VERSION.SDK_INT = Build.VERSION_CODES.Q
            fixture.context.config.uiMode = Configuration.UI_MODE_NIGHT_YES
            expect(settings.getTheme() == R.style.AppTheme_Dark_NoActionBar,
                    { "night mode with the auto setting must use the dark theme" })
            fixture.seed(R.string.settings_key_theme_black_auto, true)
            expect(settings.getTheme() == R.style.AppTheme_Black_NoActionBar,
                    { "the black-auto preference must select the black theme" })
            fixture.context.config.uiMode = Configuration.UI_MODE_NIGHT_NO
            expect(settings.getTheme() == R.style.AppTheme_NoActionBar,
                    { "light night mode with the auto setting must use the light theme" })
            fixture.context.config.uiMode = Configuration.UI_MODE_NIGHT_UNDEFINED
            expect(settings.getTheme() == R.style.AppTheme_NoActionBar,
                    { "an undefined night mode must fall back to the light theme" })
        } finally {
            Build.VERSION.SDK_INT = previousSdk
            fixture.context.config.uiMode = Configuration.UI_MODE_NIGHT_UNDEFINED
        }
    }

    check("settings never re-enable the platform backup service, whatever the stored value says") {
        val fixture = SettingsFixture()
        fixture.store.edit().putBoolean(settingsKey(R.string.settings_key_enable_android_backup_service), true).commit()
        expect(!fixture.settings.getAndroidBackupServiceEnabled(),
                { "the getter must stay hard-false like the Java one" })
        fixture.settings.setAndroidBackupServiceEnabled(true)
        expect(!fixture.store.getBoolean(settingsKey(R.string.settings_key_enable_android_backup_service), true),
                { "the setter must keep writing false" })
    }

    check("the backup broadcast receiver ignores every broadcast that is not its own") {
        // The action gate is the first thing the receiver does: another action is not ours and
        // must not reach the settings, the notifications or the work manager. The failure paths
        // behind it need the AndroidKeyStore and the device's notification channels, so they are
        // covered by the source guards and the device test instead.
        val context = ComponentContext()
        val work = WorkManager.getInstance(context)
        work.reset()
        val receiver = EncryptedBackupBroadcastReceiver()
        val foreign = Intent()
        foreign.intentAction = "org.shadowice.flocke.andotp.broadcast.SOMETHING_ELSE"

        receiver.onReceive(context, null)
        receiver.onReceive(context, foreign)
        expect(context.notifications.posted.isEmpty(),
                { "an unrelated broadcast notified: ${context.notifications.posted}" })
        expect(work.enqueued.isEmpty(), { "an unrelated broadcast queued work" })
    }

    check("the backup worker fails early when the broadcast is disabled or the work is stopped") {
      // The worker builds its own Settings, whose construction recovers a pending vault file.
      // That recovery needs the file transaction (FileHelper over AtomicFile), which refuses to run here.
      withPoisonedTransaction {
        val context = ComponentContext()
        val fixture = SettingsFixture(context)
        val worker = EncryptedBackupWorker(context, WorkerParameters())

        // The broadcast opt-in is missing: the work fails without a notification.
        expect(worker.doWork().kind == "failure", { "the disabled broadcast did not fail the work" })
        expect(context.notifications.posted.isEmpty(),
                { "the disabled broadcast notified: ${context.notifications.posted}" })

        // Stopped work fails the same way, before any settings are read.
        fixture.store.edit().putStringSet(settingsKey(R.string.settings_key_backup_broadcasts), setOf("encrypted")).commit()
        worker.stopped = true
        expect(worker.doWork().kind == "failure", { "the stopped work did not fail" })
        expect(context.notifications.posted.isEmpty(),
                { "the stopped work notified: ${context.notifications.posted}" })
      }
    }

    check("the legacy backup agent still refuses the platform restore") {
        val agent = BackupAgent()
        agent.onCreate()
        agent.onBackup(null, null, null)
        agent.onFullBackup(null)
        // The refusal must not be preceded by a Kotlin null check on the platform's arguments.
        val failure = try {
            agent.onRestore(null, 1, null)
            null
        } catch (error: IOException) {
            error
        }
        expect(failure != null, { "the platform restore was not refused" })
        expect(failure!!.message == "Platform restore is disabled; use portable encrypted backups",
                { "wrong message: ${failure!!.message}" })
    }

    check("the converted backup components keep their Java-visible API shapes") {
        // The two constants stay public static final fields: the panic responder and the settings
        // screen cancel the queued work by its name.
        for ((name, value) in listOf("UNIQUE_WORK_NAME" to "encrypted_backup",
                "ACTION" to "org.shadowice.flocke.andotp.broadcast.ENCRYPTED_BACKUP")) {
            val field = EncryptedBackupBroadcastReceiver::class.java.getDeclaredField(name)
            expect(Modifier.isPublic(field.modifiers) && Modifier.isStatic(field.modifiers)
                    && Modifier.isFinal(field.modifiers), { "$name must stay a public static final field" })
            expect(field.type == String::class.java, { "$name must stay a String" })
            expect(field.get(null) == value, { "$name changed: ${field.get(null)}" })
        }

        // The receiver stays extendable, its base class stays abstract and the helper stays protected.
        expect(!Modifier.isFinal(EncryptedBackupBroadcastReceiver::class.java.modifiers),
                { "the receiver must stay extendable" })
        expect(Modifier.isAbstract(BackupBroadcastReceiver::class.java.modifiers),
                { "the receiver base class must stay abstract" })
        val helper = BackupBroadcastReceiver::class.java.getDeclaredMethod("canSaveBackup", Context::class.java)
        expect(Modifier.isProtected(helper.modifiers) && !Modifier.isStatic(helper.modifiers),
                { "canSaveBackup must stay a protected instance method" })
        expect(helper.returnType == Boolean::class.javaPrimitiveType, { "canSaveBackup must keep returning a boolean" })

        // The worker keeps its WorkManager constructor and its result type.
        val worker = EncryptedBackupWorker::class.java
        expect(!Modifier.isFinal(worker.modifiers), { "the worker must stay extendable" })
        val constructor = worker.getDeclaredConstructor(Context::class.java, WorkerParameters::class.java)
        expect(Modifier.isPublic(constructor.modifiers), { "the worker constructor must stay public" })
        val doWork = worker.getDeclaredMethod("doWork")
        expect(doWork.returnType.name == "androidx.work.ListenableWorker\$Result",
                { "the worker must keep returning the WorkManager result: ${doWork.returnType.name}" })

        // The legacy agent keeps its class shape and the refused callback.
        expect(!Modifier.isFinal(BackupAgent::class.java.modifiers), { "the backup agent must stay extendable" })
        val restore = BackupAgent::class.java.getDeclaredMethod("onRestore",
                android.app.backup.BackupDataInput::class.java, Int::class.javaPrimitiveType,
                android.os.ParcelFileDescriptor::class.java)
        expect(Modifier.isPublic(restore.modifiers), { "onRestore must stay public" })
        expect(restore.exceptionTypes.toList() == listOf(IOException::class.java),
                { "onRestore must keep declaring IOException" })
    }

    check("the base activity locks the vault when the screen turns off") {
      // The activity builds its own Settings, whose construction recovers a pending vault file.
      // That recovery needs the file transaction (FileHelper over AtomicFile), which refuses to run here.
      withPoisonedTransaction {
        val activity = ScreenActivity()
        activity.create()

        // The screen-off receiver is registered with the platform's action.
        expect(activity.registeredReceivers.size == 1, { "the screen-off receiver was not registered" })
        val filter = activity.registeredReceivers[0].second
        expect(filter.action == Intent.ACTION_SCREEN_OFF, { "wrong filter: ${filter.action}" })
        val receiver = activity.registeredReceivers[0].first

        // Another action is not ours: nothing happens.
        receiver.onReceive(activity, Intent())
        expect(activity.finishes == 0, { "another broadcast finished the activity" })
        expect(activity.screenOffEvents == 0, { "another broadcast notified the callback" })

        // The screen-off broadcast notifies the callback, locks the vault and finishes.
        activity.setBroadcastCallback { activity.screenOffEvents++ }
        val screenOff = Intent()
        screenOff.intentAction = Intent.ACTION_SCREEN_OFF
        receiver.onReceive(activity, screenOff)
        expect(activity.screenOffEvents == 1, { "the screen-off callback was not delivered" })
        expect(activity.finishes == 1, { "the activity was not finished: ${activity.finishes}" })

        // The lock is delivered to the activity on the UI thread.
        activity.drainUiThread()
        expect(activity.lockEvents == 1, { "the vault lock was not delivered: ${activity.lockEvents}" })

        // The receiver is unregistered when the activity is destroyed.
        activity.destroy()
        expect(activity.registeredReceivers.isEmpty(), { "the screen-off receiver was not unregistered" })
      }
    }

    check("the base activity rejects the touches of an obscured window") {
        val activity = ScreenActivity()

        val obscured = MotionEvent()
        obscured.eventFlags = MotionEvent.FLAG_WINDOW_IS_OBSCURED
        expect(!activity.dispatchTouchEvent(obscured), { "an obscured touch was accepted" })
        expect(activity.dispatchedTouches.isEmpty(), { "an obscured touch reached the activity" })

        val normal = MotionEvent()
        expect(activity.dispatchTouchEvent(normal), { "a normal touch was rejected" })
        expect(activity.dispatchedTouches.size == 1, { "the normal touch was not dispatched" })

        // From Android 10 on, a partially obscured window is refused as well.
        val sdk = Build.VERSION.SDK_INT
        try {
            Build.VERSION.SDK_INT = Build.VERSION_CODES.Q
            val partial = MotionEvent()
            partial.eventFlags = MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
            expect(!activity.dispatchTouchEvent(partial), { "a partially obscured touch was accepted" })
            expect(activity.dispatchedTouches.size == 1, { "the partially obscured touch reached the activity" })
        } finally {
            Build.VERSION.SDK_INT = sdk
        }
    }

    check("the panic responder wipes only what the panic action asks for") {
        PreferenceManager.reset()
        val activity = PanicActivity()
        val work = WorkManager.getInstance(activity)
        work.reset()
        val store = PreferenceManager.getDefaultSharedPreferences(activity) as FakeSharedPreferences

        try {
            // The activity builds its own Settings, whose construction recovers a pending vault
            // file. That recovery needs the file transaction (FileHelper over AtomicFile), which refuses here.
            withPoisonedTransaction {
            // A foreign action changes nothing at all.
            val foreign = Intent()
            foreign.intentAction = "info.guardianproject.panic.action.OTHER"
            activity.launchIntent = foreign
            activity.create()
            expect(activity.removedTasks == 1, { "the task was not removed" })
            expect(work.cancelled.isEmpty(), { "a foreign action cancelled the backup work" })
            expect(store.getString(settingsKey(R.string.settings_key_auto_backup_password_enc), null) == null,
                    { "a foreign action disabled the automatic backups" })
            expect(VaultSession.getKey() == null, { "a foreign action opened the vault" })

            // The panic action without a configured response only removes the task.
            val trigger = Intent()
            trigger.intentAction = PanicResponderActivity.PANIC_TRIGGER_ACTION
            activity.launchIntent = trigger
            activity.create()
            expect(activity.removedTasks == 2, { "the task was not removed" })
            expect(work.cancelled.isEmpty(), { "the panic action cancelled the backup work without a response" })
            expect(store.getString(settingsKey(R.string.settings_key_auto_backup_password_enc), null) == null,
                    { "the panic action disabled the automatic backups without a response" })

            // A configured "settings" response disables the automatic backups, cancels the pending
            // encrypted backup and locks the vault.
            store.edit().putStringSet(settingsKey(R.string.settings_key_panic), setOf("settings")).commit()
            work.reset()
            VaultSession.open(SecretKeySpec(ByteArray(32), "AES"))
            expect(VaultSession.getKey() != null, { "the vault session could not be opened" })
            activity.create()
            expect(activity.removedTasks == 3, { "the task was not removed" })
            expect(work.cancelled == listOf(EncryptedBackupBroadcastReceiver.UNIQUE_WORK_NAME),
                    { "the pending encrypted backup was not cancelled: ${work.cancelled}" })
            expect(store.getString(settingsKey(R.string.settings_key_auto_backup_password_enc), "") == "off",
                    { "the automatic backups were not disabled" })
            expect(store.stringSet(settingsKey(R.string.settings_key_backup_broadcasts)).isEmpty(),
                    { "the backup broadcasts were not disabled" })
            expect(VaultSession.getKey() == null, { "the vault was not locked" })

            // A configured "accounts" response reaches the wipe of the database and the keys. The
            // wipe itself needs the file transaction (FileHelper over AtomicFile), which refuses to
            // run here, so the check can only see that the wipe was reached: its first file
            // operation fails with UnsupportedOperationException, and nothing is deleted.
            store.edit().putStringSet(settingsKey(R.string.settings_key_panic), setOf("accounts")).commit()
            work.reset()
            expectThrows(UnsupportedOperationException::class.java) { activity.create() }
            expect(work.cancelled == listOf(EncryptedBackupBroadcastReceiver.UNIQUE_WORK_NAME),
                    { "the pending encrypted backup was not cancelled: ${work.cancelled}" })
            val state = VaultSession.snapshot()
            expect(state is SessionState.Locked && state.reason == SessionLockReason.RESET,
                    { "the database wipe was not reached: $state" })
            }
        } finally {
            // The session is global; no check may leave an open vault behind.
            VaultSession.lock(SessionLockReason.RESET)
        }
    }

    check("the QR scanner activity honours the theme, the language and the screenshot setting") {
        PreferenceManager.reset()
        val activity = ScannerActivity()
        val store = PreferenceManager.getDefaultSharedPreferences(activity) as FakeSharedPreferences
        store.edit().putString(settingsKey(R.string.settings_key_lang), "de_DE").commit()

        val sdk = Build.VERSION.SDK_INT
        val defaultLocale = Locale.getDefault()
        try {
            // The platform calls attachBaseContext before onCreate; drive that hook explicitly here.
            activity.attachBaseContext(activity)
            expect(activity.createdConfiguration?.currentLocale == Locale("de", "DE"),
                    { "the wrong language context was created: ${activity.createdConfiguration?.currentLocale}" })

            // Below Android 11 the system bars are hidden with the fullscreen flag.
            Build.VERSION.SDK_INT = Build.VERSION_CODES.Q
            withPoisonedTransaction { activity.initialize() }

            expect(activity.appliedTheme == R.style.AppTheme_NoActionBar,
                    { "the wrong theme was applied: ${activity.appliedTheme}" })

            val flags = activity.getWindow().flagCalls.map { it.first }
            expect(flags.contains(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED),
                    { "the hardware acceleration was not requested" })
            expect(flags.contains(WindowManager.LayoutParams.FLAG_SECURE),
                    { "the screenshots were not blocked" })
            expect(flags.contains(WindowManager.LayoutParams.FLAG_FULLSCREEN),
                    { "the system bars were not hidden" })

            // With the screenshots enabled the window is not made secure.
            store.edit().putBoolean(settingsKey(R.string.settings_key_enable_screenshot), true).commit()
            val second = ScannerActivity()
            withPoisonedTransaction { second.initialize() }
            expect(!second.getWindow().flagCalls.map { it.first }.contains(WindowManager.LayoutParams.FLAG_SECURE),
                    { "the window was made secure although the screenshots are enabled" })

            // From Android 11 on the system bars are hidden with the insets controller.
            Build.VERSION.SDK_INT = Build.VERSION_CODES.R
            val third = ScannerActivity()
            val controller = WindowInsetsController()
            third.getWindow().windowInsetsController = controller
            withPoisonedTransaction { third.initialize() }
            expect(controller.hiddenTypes == (WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()),
                    { "the system bars were not hidden: ${controller.hiddenTypes}" })
            expect(controller.behavior == WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
                    { "the wrong system bar behaviour was requested: ${controller.behavior}" })
            expect(!third.getWindow().flagCalls.map { it.first }.contains(WindowManager.LayoutParams.FLAG_FULLSCREEN),
                    { "the fullscreen flag was set although the insets controller was used" })
        } finally {
            Build.VERSION.SDK_INT = sdk
            Locale.setDefault(defaultLocale)
        }
    }

    check("the about tab wires its rows, its version metadata and its image tint") {
        resetAboutState()
        val fixture = AboutPageFixture()
        val view = fixture.inflate(BaseAboutFragment())

        expect(view === fixture.root, { "the fragment inflated the wrong layout" })
        expect(fixture.version.text == "9.9.9-test",
                { "the version name was not bound: ${fixture.version.text}" })
        expect(fixture.imageFilters.size == BaseAboutFragment.imageResources.size,
                { "not every image row was tinted: ${fixture.imageFilters.size}" })
        expect(fixture.imageFilters.all { it != null }, { "an image row kept no theme filter" })

        // Every row opens its own link as a VIEW intent.
        val rows = listOf(
                fixture.license to "https://github.com/andOTP/andOTP/blob/master/LICENSE.txt",
                fixture.changelog to "https://github.com/andOTP/andOTP/blob/master/CHANGELOG.md",
                fixture.source to "https://github.com/andOTP/andOTP",
                fixture.faq to "https://github.com/andOTP/andOTP/wiki/Frequently-Asked-Questions",
                fixture.author1 to "https://github.com/flocke",
                fixture.author2 to "https://github.com/richyhbm",
                fixture.authorOriginal to "https://github.com/0xbb",
                fixture.contributors to "https://github.com/andOTP/andOTP/graphs/contributors",
                fixture.support to "https://github.com/andOTP/andOTP/blob/master/README.md#contribute",
                fixture.originalExtra to "https://github.com/0xbb/otp-authenticator")
        for ((row, uri) in rows) {
            row.performClick()
            val last = fixture.host.started.lastOrNull()
            expect(last != null && last.getAction() == Intent.ACTION_VIEW
                            && last.data.toString() == uri,
                    { "$uri produced ${last?.getAction()} ${last?.data}" })
        }

        // A missing activity handler falls back to the clipboard with the copied-to-clipboard toast.
        fixture.host.startFailure = ActivityNotFoundException()
        fixture.host.started.clear()
        fixture.license.performClick()
        expect(fixture.host.started.isEmpty(), { "a refused start still recorded an intent" })
        val clips = fixture.host.clipboard.clips
        expect(clips.size == 1 && clips[0].text == "https://github.com/andOTP/andOTP/blob/master/LICENSE.txt",
                { "the URI was not copied: ${clips.map { it.text }}" })
        expect(Toast.shownTexts == listOf<CharSequence>("key:" + R.string.about_toast_copied_to_clipboard),
                { "the fallback did not toast the copied message: ${Toast.shownTexts}" })

        // A package without a version name keeps an empty version text; a missing package does too.
        resetAboutState()
        PackageManager.packageInfo = PackageInfo()
        val withoutVersion = AboutPageFixture()
        withoutVersion.inflate(BaseAboutFragment())
        expect(withoutVersion.version.text == null,
                { "a missing version name became ${withoutVersion.version.text}" })

        resetAboutState()
        PackageManager.missingPackage = "org.shadowice.flocke.andotp"
        val missingPackage = AboutPageFixture()
        missingPackage.inflate(BaseAboutFragment())
        expect(missingPackage.version.text == "",
                { "a missing package kept a version name: ${missingPackage.version.text}" })
    }

    check("seven quick taps on the version enable the hidden special features") {
        resetAboutState()
        val fixture = AboutPageFixture()
        fixture.inflate(BaseAboutFragment())
        val store = PreferenceManager.getDefaultSharedPreferences(fixture.host) as FakeSharedPreferences

        // The first tap of a burst only starts it; the counter toasts appear from the third count.
        fixture.versionLayout.performClick()
        expect(BaseAboutFragment.taps == 0, { "the first tap counted" })
        for (tap in 1..7) fixture.versionLayout.performClick()
        expect(BaseAboutFragment.taps == 7, { "the quick taps did not count up: ${BaseAboutFragment.taps}" })
        expect(Toast.shownTexts == listOf<CharSequence>("3", "4", "5", "6", "7"),
                { "the counter toasts are wrong: ${Toast.shownTexts}" })
        // Each new count toast cancels its predecessor (four cancellations after the third count).
        expect(Toast.cancels == 4, { "the counter toasts were not cancelled: ${Toast.cancels}" })

        // The seventh count opens the confirmation dialog instead of enabling anything yet.
        expect(!store.getBoolean(settingsKey(R.string.settings_key_special_features), false),
                { "the special features were enabled without confirmation" })
        val dialog = AlertDialog.Builder.lastCreated
        expect(dialog != null && dialog.positiveButtonListener != null && dialog.negativeButtonListener != null,
                { "the confirmation dialog did not wire both buttons" })

        // Cancelling changes nothing; confirming enables the feature and toasts it.
        dialog!!.negativeButtonListener!!.onClick(dialog, DialogInterface.BUTTON_NEGATIVE)
        expect(!store.getBoolean(settingsKey(R.string.settings_key_special_features), false),
                { "cancel enabled the special features" })
        dialog.positiveButtonListener!!.onClick(dialog, DialogInterface.BUTTON_POSITIVE)
        expect(store.getBoolean(settingsKey(R.string.settings_key_special_features), false),
                { "the confirmation did not enable the special features" })
        expect(Toast.shown == listOf(R.string.about_toast_special_features),
                { "the enabling was not toasted: ${Toast.shown}" })

        // A slow tap restarts the count instead of adding to it, and toasts nothing.
        Toast.shownTexts.clear()
        Thread.sleep(600)
        fixture.versionLayout.performClick()
        expect(BaseAboutFragment.taps == 0, { "a slow tap kept the count: ${BaseAboutFragment.taps}" })
        expect(Toast.shownTexts.isEmpty(), { "a slow tap toasted the counter: ${Toast.shownTexts}" })

        // With the features already enabled the second burst only reports them on the seventh count.
        // Counts 1 and 2 re-show the stale "7" toast first, exactly as the Java `currentToast.show()`
        // at the end of the quick-tap branch did; the counter texts then replace it and the seventh
        // count swaps in the already-enabled message before it is shown.
        for (tap in 1..7) fixture.versionLayout.performClick()
        expect(BaseAboutFragment.taps == 7, { "the count after the restart is wrong: ${BaseAboutFragment.taps}" })
        expect(Toast.shownTexts == listOf<CharSequence>("7", "7", "3", "4", "5", "6"),
                { "the restarted counter toasts are wrong: ${Toast.shownTexts}" })
        expect(Toast.shown == listOf(R.string.about_toast_special_features,
                R.string.about_toast_special_features_enabled),
                { "the already-enabled toast is wrong: ${Toast.shown}" })
        expect(AlertDialog.Builder.created.size == 1,
                { "the dialog was shown again: ${AlertDialog.Builder.created.size}" })
    }

    check("the fdroid about tab adds the donation rows with the clipboard fallback") {
        resetAboutState()
        val fixture = AboutPageFixture()
        val view = fixture.inflate(AboutFragment())
        expect(view === fixture.root, { "the fdroid fragment inflated the wrong layout" })
        expect(fixture.author1Content.childCount == 1 && fixture.author2Content.childCount == 1,
                { "the donation rows were not added: ${fixture.author1Content.childCount}/${fixture.author2Content.childCount}" })

        fixture.author1Content.getChildAt(0).performClick()
        val first = fixture.host.started.lastOrNull()
        expect(first != null && first.data.toString() == "https://flocke.shadowice.org/donate.html",
                { "the first donation link is wrong: ${first?.data}" })

        // Any start failure falls back to the clipboard, not only a missing handler.
        fixture.host.startFailure = IllegalStateException("synthetic refusal")
        fixture.host.started.clear()
        fixture.author2Content.getChildAt(0).performClick()
        val clips = fixture.host.clipboard.clips
        expect(clips.size == 1 && clips[0].text == "https://richyhbm.co.uk/donate",
                { "the donation link was not copied: ${clips.map { it.text }}" })
    }

    check("the about screen builds the about tab and the library tab") {
        resetAboutState()
        val activity = withPoisonedTransaction {
            val screen = AboutScreenActivity("""{"libraries":[{"name":"a"}]}""")
            screen.create()
            screen
        }

        // The shell wiring: title, container layout, toolbar and the inflated about component.
        expect(activity.titleResource == R.string.about_activity_title, { "wrong title: ${activity.titleResource}" })
        expect(activity.contentResource == R.layout.activity_container, { "wrong content layout" })
        expect(activity.supportActionBar === activity.toolbar, { "the toolbar was not installed" })
        expect(activity.stub.getLayoutResource() == R.layout.component_about, { "wrong stub layout" })
        expect(activity.stub.inflations == 1, { "the stub layout was not inflated" })

        val adapter = activity.pager.getAdapter() as FragmentPagerAdapter
        expect(activity.tabs.getWiredPager() === activity.pager, { "the tabs were not wired to the pager" })
        expect(adapter.getCount() == 2, { "wrong page count: ${adapter.getCount()}" })

        // Page 0 is the flavor's about tab; page 1 is the library list built from the raw document.
        expect(adapter.getItem(0) is AboutFragment, { "the first page is not the about tab" })
        val libsPage = adapter.getItem(1) as LibsFragment
        expect(libsPage.licenseShown && libsPage.versionShown, { "the library page lost its flags" })
        expect(libsPage.libs?.json == """{"libraries":[{"name":"a"}]}""",
                { "the library list was not loaded: ${libsPage.libs?.json}" })
        expect(adapter.getPageTitle(0) == settingsKey(R.string.about_tab_about),
                { "wrong first page title: ${adapter.getPageTitle(0)}" })
        expect(adapter.getPageTitle(1) == settingsKey(R.string.about_tab_libraries),
                { "wrong second page title: ${adapter.getPageTitle(1)}" })
        expect(adapter.getPageTitle(2) == null, { "an unknown page got a title" })
        expectThrows(IndexOutOfBoundsException::class.java) { adapter.getItem(2) }

        // An empty raw document becomes an empty library list, not a parse failure.
        val empty = withPoisonedTransaction {
            val screen = AboutScreenActivity("")
            screen.create()
            screen
        }
        val emptyPage = (empty.pager.getAdapter() as FragmentPagerAdapter).getItem(1) as LibsFragment
        expect(emptyPage.libs?.json == "{}",
                { "an empty document did not become the empty list: ${emptyPage.libs?.json}" })

        // Up-navigation and back both finish the screen.
        val finishesBefore = activity.finishes
        expect(activity.up(), { "the up navigation was refused" })
        activity.back()
        expect(activity.finishes == finishesBefore + 2,
                { "the screen was not finished: ${activity.finishes}" })
    }

    check("the about screens keep their Java-visible API shapes") {
        val base = BaseAboutFragment::class.java
        val flavor = AboutFragment::class.java
        val screen = AboutActivity::class.java

        // The Java classes were all extendable and kept their inheritance.
        expect(!Modifier.isFinal(base.modifiers), { "BaseAboutFragment became final" })
        expect(!Modifier.isFinal(flavor.modifiers), { "AboutFragment became final" })
        expect(!Modifier.isFinal(screen.modifiers), { "AboutActivity became final" })
        expect(BaseActivity::class.java.isAssignableFrom(screen), { "AboutActivity lost its base" })
        expect(base.isAssignableFrom(flavor), { "AboutFragment lost its base" })

        // The two public helpers keep their names and stay overridable.
        for (name in (arrayOf("openURI", "copyToClipboard"))) {
            val method = base.getMethod(name, String::class.java)
            expect(Modifier.isPublic(method.modifiers) && !Modifier.isFinal(method.modifiers),
                    { "$name lost its public overridable shape" })
        }

        // The Java package-private statics are public static fields here (documented widening).
        val images = base.getField("imageResources")
        expect(Modifier.isStatic(images.modifiers) && images.type == IntArray::class.java,
                { "imageResources lost its static array shape" })
        val lastTap = base.getField("lastTap")
        expect(Modifier.isStatic(lastTap.modifiers) && lastTap.type == java.lang.Long.TYPE,
                { "lastTap lost its static long shape" })
        val taps = base.getField("taps")
        expect(Modifier.isStatic(taps.modifiers) && taps.type == Integer.TYPE,
                { "taps lost its static int shape" })
        val currentToast = base.getField("currentToast")
        expect(Modifier.isStatic(currentToast.modifiers) && currentToast.type == Toast::class.java,
                { "currentToast lost its static toast shape" })
    }


    // --- twenty-first batch: the converted activities, the manual entry dialog, the credential
    // preference and the two card adapters ---------------------------------------------------
    //
    // Members whose signatures name a platform class without a stub cannot be reflected here.
    // Those classes are checked by their class shape below (name, modifiers, base classes and
    // nested types); their members are pinned by the Python source guards and by the Java
    // instrumentation tests, which run against the real Android runtime.

    check("the converted UI classes keep their Java-visible API shapes") {
        val loader = Thread.currentThread().contextClassLoader
        fun load(name: String): Class<*> = Class.forName(name, false, loader)

        // The credential preference is inflated from the preference XML by its name, so the class
        // name, the two-argument constructor and the DEFAULT_VALUE constant must stay as they were.
        val credentials = CredentialsPreference::class.java
        expect(!Modifier.isFinal(credentials.modifiers), { "CredentialsPreference must stay extendable" })
        val constructor = credentials.getDeclaredConstructor(
            android.content.Context::class.java, android.util.AttributeSet::class.java)
        expect(Modifier.isPublic(constructor.modifiers),
                { "the CredentialsPreference constructor must stay public" })
        expectMethod(credentials, "void setOldEncryptionKey(SecretKey)")
        expectMethod(credentials, "void setEncryptionChangeCallback(EncryptionChangeCallback)")

        // The manual entry dialog was a static-only class: it becomes the documented object with
        // static forwarders, and the nested callback stays a nested interface.
        val manual = ManualEntryDialog::class.java
        expect(Modifier.isFinal(manual.modifiers), { "the ManualEntryDialog object is not final" })
        expect(manual.getDeclaredField("INSTANCE").type == manual,
                { "the ManualEntryDialog object is missing INSTANCE" })
        expectMethod(manual, "static void show(MainActivity,Settings,EntriesCardAdapter)")
        expectMethod(manual, "static void show(MainActivity,Settings,EntriesCardAdapter,Entry,UpdateCallback)")
        expect(ManualEntryDialog.UpdateCallback::class.java.isInterface,
                { "the manual entry callback is no longer an interface" })

        // The entry card: the same base classes and the same constructor.
        val holder = load("org.shadowice.flocke.andotp.View.EntryViewHolder")
        expect(!Modifier.isFinal(holder.modifiers), { "EntryViewHolder must stay extendable" })
        expect(RecyclerView.ViewHolder::class.java.isAssignableFrom(holder),
                { "EntryViewHolder lost its RecyclerView.ViewHolder base" })
        expect(ItemTouchHelperViewHolder::class.java.isAssignableFrom(holder),
                { "EntryViewHolder lost the drag/drop callback" })
        expect(EntryViewHolder.Callback::class.java.isInterface,
                { "the card callback is no longer an interface" })

        // The card adapter: the same base classes, and the read/write key field.
        val adapter = load("org.shadowice.flocke.andotp.View.EntriesCardAdapter")
        expect(!Modifier.isFinal(adapter.modifiers), { "EntriesCardAdapter must stay extendable" })
        expect(RecyclerView.Adapter::class.java.isAssignableFrom(adapter),
                { "EntriesCardAdapter lost its RecyclerView.Adapter base" })
        expect(Filterable::class.java.isAssignableFrom(adapter),
                { "EntriesCardAdapter lost the Filterable contract" })
        val encryptionKey = adapter.getDeclaredField("encryptionKey")
        expect(!Modifier.isStatic(encryptionKey.modifiers) && !Modifier.isFinal(encryptionKey.modifiers) &&
                encryptionKey.type == SecretKey::class.java,
                { "encryptionKey must stay a writable SecretKey field" })

        // The main activity stays extendable and keeps its base classes.
        val main = load("org.shadowice.flocke.andotp.Activities.MainActivity")
        expect(!Modifier.isFinal(main.modifiers), { "MainActivity must stay extendable" })
        expect(BaseActivity::class.java.isAssignableFrom(main),
                { "MainActivity lost its BaseActivity base" })

        // The settings screen and its fragment keep their names, their nesting and their bases.
        val settings = load("org.shadowice.flocke.andotp.Activities.SettingsActivity")
        expect(!Modifier.isFinal(settings.modifiers), { "SettingsActivity must stay extendable" })
        expect(BackgroundTaskActivity::class.java.isAssignableFrom(settings),
                { "SettingsActivity lost its BackgroundTaskActivity base" })
        val settingsFragment = load("org.shadowice.flocke.andotp.Activities.SettingsActivity\$SettingsFragment")
        expect(Modifier.isStatic(settingsFragment.modifiers) && Modifier.isPublic(settingsFragment.modifiers),
                { "SettingsFragment must stay a public static nested class" })
        expect(android.preference.PreferenceFragment::class.java.isAssignableFrom(settingsFragment),
                { "SettingsFragment lost its PreferenceFragment base" })

        // The intro wizard keeps its four slides under their original names.
        val intro = load("org.shadowice.flocke.andotp.Activities.IntroScreenActivity")
        expect(!Modifier.isFinal(intro.modifiers), { "IntroScreenActivity must stay extendable" })
        expect(IntroActivity::class.java.isAssignableFrom(intro),
                { "IntroScreenActivity lost its IntroActivity base" })
        for (name in listOf("EncryptionFragment", "AuthenticationFragment", "AndroidSyncFragment",
                "IntroFinishedFragment")) {
            val nested = load("org.shadowice.flocke.andotp.Activities.IntroScreenActivity\$$name")
            expect(Modifier.isStatic(nested.modifiers) && Modifier.isPublic(nested.modifiers),
                    { "$name must stay a public static nested class" })
        }

        // The remaining activities keep their base classes and their public members.
        val authenticate = AuthenticateActivity::class.java
        expect(!Modifier.isFinal(authenticate.modifiers), { "AuthenticateActivity must stay extendable" })
        expect(BackgroundTaskActivity::class.java.isAssignableFrom(authenticate),
                { "AuthenticateActivity lost its BackgroundTaskActivity base" })
        expectMethod(authenticate, "void onBackPressed()")
        expectProtectedMethodArity(authenticate, "onTaskResult", 1)
        expectProtectedMethodArity(authenticate, "onTaskFailure", 1)

        val background = BackgroundTaskActivity::class.java
        expect(!Modifier.isFinal(background.modifiers), { "BackgroundTaskActivity must stay extendable" })
        expectMethod(background, "void onResume()")
        expectProtectedMethodArity(background, "onTaskResult", 1)
        expectProtectedMethodArity(background, "onTaskFailure", 1)
        expectProtectedMethodArity(background, "onReturnToCanceledTask", 0)
        expectProtectedMethodArity(background, "setupUiForTaskState", 1)
        expectProtectedMethodArity(background, "cancelTaskOnScreenOff", 0)
        expectProtectedMethodArity(background, "cancelBackgroundTask", 0)
        expectProtectedMethodArity(background, "startBackgroundTask", 1)

        val backup = load("org.shadowice.flocke.andotp.Activities.BackupActivity")
        expect(!Modifier.isFinal(backup.modifiers), { "BackupActivity must stay extendable" })
        expect(BackgroundTaskActivity::class.java.isAssignableFrom(backup),
                { "BackupActivity lost its BackgroundTaskActivity base" })

        // The retained task fragment keeps its name, its generic parameter and its members.
        val taskFragment = load("org.shadowice.flocke.andotp.Activities.BackgroundTaskActivity\$TaskFragment")
        expect(Modifier.isStatic(taskFragment.modifiers) && Modifier.isPublic(taskFragment.modifiers),
                { "TaskFragment must stay a public static nested class" })
        expectMethod(taskFragment, "void startTask(UiBasedBackgroundTask)")
        expectMethod(taskFragment, "void setOutcomeCallback(OutcomeCallback)")
        expectMethod(taskFragment, "boolean isCanceled()")
        expectMethod(taskFragment, "void cancelTask()")
    }

    check("the entry card binds the token, the labels, the tags and the counter") {
        val fixture = SettingsFixture()
        // The thumbnail is switched off, so the check does not depend on the letter bitmap.
        fixture.seed(R.string.settings_key_thumbnail_size, "0dp")
        val context = fixture.context

        val root = CardRoot()
        val card = CardView(context)
        val value = TextView()
        val valuePrev = TextView()
        val valueLayout = LinearLayout(context)
        val coverLayout = LinearLayout(context)
        val counterLayout = LinearLayout(context)
        val thumbnailFrame = FrameLayout(context)
        val thumbnail = ImageView(context)
        val label = TextView()
        val tags = TextView()
        val counter = TextView()
        val progress = MaterialProgressBar(context)
        val menu = ImageButton(context)
        val copy = ImageButton(context)
        val cover = ImageView(context)
        val icon = android.graphics.drawable.BitmapDrawable(null, null)
        menu.setImageDrawable(icon)
        copy.setImageDrawable(icon)
        cover.setImageDrawable(icon)
        root.children[R.id.card_view] = card
        root.children[R.id.valueText] = value
        root.children[R.id.valueTextPrev] = valuePrev
        root.children[R.id.valueLayout] = valueLayout
        root.children[R.id.coverLayout] = coverLayout
        root.children[R.id.counterLayout] = counterLayout
        root.children[R.id.thumbnailFrame] = thumbnailFrame
        root.children[R.id.thumbnailImg] = thumbnail
        root.children[R.id.textViewLabel] = label
        root.children[R.id.textViewTags] = tags
        root.children[R.id.counter] = counter
        root.children[R.id.cardProgressBar] = progress
        root.children[R.id.menuButton] = menu
        root.children[R.id.copyButton] = copy
        root.children[R.id.coverImg] = cover

        val holder = withPoisonedTransaction { EntryViewHolder(context, root, false) }

        // The entry has no issuer, so the card does not read the card layout (an enum from Constants).
        val entry = Entry(Entry.OTPType.TOTP, ENTRY_SEED, 30, 6, "", "label",
            TokenCalculator.HashAlgorithm.SHA1, arrayListOf("tag1", "tag2"))
        entry.updateOTP(true)
        withPoisonedTransaction { holder.updateValues(entry) }

        // The unformatted token is kept in the view's tag, so the copy action has the raw value.
        expect(value.tag == entry.getCurrentOTP(), { "the token was not stored in the view tag" })
        expect(!value.text.isNullOrEmpty(), { "the token was not shown" })
        // The label ends up in its own line, the tags in a comma separated list.
        expect(label.text.toString() == "label", { "the label is wrong: ${label.text}" })
        expect(tags.text.toString() == "tag1, tag2", { "the tags are wrong: ${tags.text}" })
        // A time-based entry with the default period has no counter and no progress bar.
        expect(counterLayout.visibility == View.GONE, { "the counter must stay hidden" })
        expect(progress.visibility == View.GONE, { "the progress bar must stay hidden" })
        // Without tap-to-reveal the token is shown and the cover is not.
        expect(valueLayout.visibility == View.VISIBLE, { "the token layout must be visible" })
        expect(coverLayout.visibility == View.GONE, { "the cover layout must be hidden" })
        // The thumbnail is switched off in this fixture, so no letter bitmap is drawn.
        expect(thumbnailFrame.visibility == View.GONE, { "the thumbnail frame must stay hidden" })
        // The copy button describes what it copies.
        expect(copy.contentDescription.toString().contains("label"),
                { "the copy description is wrong: ${copy.contentDescription}" })

        // A counter based entry shows the counter instead of the progress bar.
        val hotp = Entry(Entry.OTPType.HOTP, ENTRY_SEED, 42L, 6, "", "label",
            TokenCalculator.HashAlgorithm.SHA1, arrayListOf("tag1", "tag2"))
        withPoisonedTransaction { holder.updateValues(hotp) }
        expect(counterLayout.visibility == View.VISIBLE, { "the counter must be visible for a HOTP entry" })
        expect(counter.text.toString() == "42", { "the counter is wrong: ${counter.text}" })
    }

    check("the retained task fragment starts, hands over and cancels the task") {
        val fragment = BackgroundTaskActivity.TaskFragment<String>()
        val task = RecordingTask()

        fragment.startTask(task)
        // The fragment hands the outcome to the registered callback.
        var delivered: String? = null
        fragment.setOutcomeCallback { outcome ->
            if (outcome is TaskOutcome.Value) delivered = outcome.value
        }
        awaitQueuedDelivery()
        drainMainThreadDeliveries()
        expect(task.started, { "the task was not started" })
        expect(delivered == "done", { "the outcome was not delivered: $delivered" })
        expect(!fragment.isCanceled(), { "a finished task must not be canceled" })

        // Cancelling a running task is what the activity does when the screen is locked.
        val running = BlockingRecordingTask()
        val second = BackgroundTaskActivity.TaskFragment<String>()
        second.startTask(running)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!running.started && System.nanoTime() < deadline) Thread.sleep(10)
        expect(running.started, { "the task never started" })
        second.cancelTask()
        expect(running.canceled, { "the running task was not canceled" })
        expect(second.isCanceled(), { "the canceled fragment does not report the cancellation" })
    }

    check("token grouping terminates for non-positive chunks; entry hash follows content equality") {
        expect(Tools.formatToken("12345678", 0) == "12345678", { "chunk 0 must return the input" })
        expect(Tools.formatToken("12345678", -3) == "12345678", { "negative chunk must return the input" })
        expect(Tools.formatToken("12345678", 3) == "12 345 678", { Tools.formatToken("12345678", 3).toString() })
        expect(Tools.formatToken(null, 3) == null, { "null must stay null" })

        // Two entries built from the same seed text hold distinct arrays but equal content.
        val first = Entry(Entry.OTPType.TOTP, ENTRY_SEED, 30, 6, "ACME", "label",
            TokenCalculator.HashAlgorithm.SHA1, arrayListOf())
        val second = Entry(Entry.OTPType.TOTP, ENTRY_SEED, 30, 6, "ACME", "label",
            TokenCalculator.HashAlgorithm.SHA1, arrayListOf())
        expect(first == second, { "content-equal entries must be equal" })
        expect(first.hashCode() == second.hashCode(), { "equal entries must hash alike" })
    }

    check("a non-default Steam period survives JSON and URI round trips; defaults stay unchanged") {
        val steam = Entry(Entry.OTPType.STEAM, ENTRY_SEED, 60, TokenCalculator.STEAM_DEFAULT_DIGITS, "ACME", "steam",
            TokenCalculator.HashAlgorithm.SHA1, arrayListOf())
        val json = steam.toJSON()
        expect(json.getInt("period") == 60, { "the Steam period was not serialized: $json" })
        val restored = Entry(json)
        expect(restored.getPeriod() == 60, { "the Steam period was lost on JSON restore: ${restored.getPeriod()}" })

        val uri = steam.toUri().toString()
        expect(uri.contains("period=60"), { "the Steam period is missing from the URI: $uri" })

        val defaultSteam = Entry(Entry.OTPType.STEAM, ENTRY_SEED, 30, TokenCalculator.STEAM_DEFAULT_DIGITS, "ACME", "steam",
            TokenCalculator.HashAlgorithm.SHA1, arrayListOf())
        expect(!defaultSteam.toJSON().has("period"), { "a default Steam period must not be written" })
        expect(!defaultSteam.toUri().toString().contains("period="), { "a default Steam period must not be in the URI" })
    }

    check("the previous token is the one for the period before the current one; an empty mOTP PIN shows none") {
        val key = ascii("1234567890")
        val totp = Entry(Entry.OTPType.TOTP, ENTRY_SEED, 30, 6, "ACME", "label",
            TokenCalculator.HashAlgorithm.SHA1, arrayListOf())
        val before = System.currentTimeMillis() / 1000
        totp.updateOTP(true)
        val after = System.currentTimeMillis() / 1000
        // The previous token is the one for the period before the update's own time; the period
        // boundary may pass between the two clock reads, so either reading is accepted.
        fun prevAt(time: Long): String = Tools.formatTokenString(
            TokenCalculator.TOTP_RFC6238(key, 30, time, 6, TokenCalculator.HashAlgorithm.SHA1, -1), 6)
        val prev = totp.getPrevOTP()
        expect(prev == prevAt(before) || prev == prevAt(after), { "unexpected previous token: $prev" })
        expect(prev != totp.getCurrentOTP(), { "the previous token must differ from the current one" })

        val motp = Entry(Entry.OTPType.MOTP, ENTRY_SEED, 10, 6, "ACME", "motp",
            TokenCalculator.HashAlgorithm.SHA1, arrayListOf())
        motp.updateOTP(true)
        expect(motp.getCurrentOTP() == "PINREQ", { "an empty PIN must show the placeholder: ${motp.getCurrentOTP()}" })
        expect(motp.getPrevOTP() == "", { "the placeholder must not be shown as a previous token: ${motp.getPrevOTP()}" })
    }

    check("TOTP and Steam tokens refresh after the wall clock moves backwards") {
        val key = ascii("1234567890")
        val totp = Entry(Entry.OTPType.TOTP, ENTRY_SEED, 30, 6, "ACME", "label",
            TokenCalculator.HashAlgorithm.SHA1, arrayListOf())
        expect(totp.updateOTPAt(300L, false), { "the initial future-step token was not calculated" })
        val futureToken = Tools.formatTokenString(
            TokenCalculator.TOTP_RFC6238(key, 30, 300L, 6, TokenCalculator.HashAlgorithm.SHA1, 0), 6)
        expect(totp.getCurrentOTP() == futureToken, { "the explicit TOTP time was not used" })
        expect(totp.updateOTPAt(60L, false), { "a backwards clock jump did not refresh the TOTP" })
        val rewoundToken = Tools.formatTokenString(
            TokenCalculator.TOTP_RFC6238(key, 30, 60L, 6, TokenCalculator.HashAlgorithm.SHA1, 0), 6)
        val rewoundPrevious = Tools.formatTokenString(
            TokenCalculator.TOTP_RFC6238(key, 30, 60L, 6, TokenCalculator.HashAlgorithm.SHA1, -1), 6)
        expect(totp.getCurrentOTP() == rewoundToken, { "the TOTP still shows its future-step token" })
        expect(totp.getPrevOTP() == rewoundPrevious, { "the previous TOTP was not recalculated after rewind" })
        expect(!totp.updateOTPAt(60L, false), { "the same time step should not recalculate repeatedly" })

        val steam = Entry(Entry.OTPType.STEAM, ENTRY_SEED, 30, TokenCalculator.STEAM_DEFAULT_DIGITS,
            "ACME", "steam", TokenCalculator.HashAlgorithm.SHA1, arrayListOf())
        steam.updateOTPAt(300L, false)
        expect(steam.updateOTPAt(60L, false), { "a backwards clock jump did not refresh the Steam token" })
        expect(steam.getCurrentOTP() == TokenCalculator.TOTP_SteamAt(key, 30, 60L,
            TokenCalculator.STEAM_DEFAULT_DIGITS, TokenCalculator.HashAlgorithm.SHA1, 0),
            { "the Steam token still uses the future step" })
        expect(steam.getPrevOTP() == TokenCalculator.TOTP_SteamAt(key, 30, 60L,
            TokenCalculator.STEAM_DEFAULT_DIGITS, TokenCalculator.HashAlgorithm.SHA1, -1),
            { "the previous Steam token was not recalculated after rewind" })

        val replacement = ascii("new time-based secret")
        totp.setSecret(replacement)
        expect(totp.getCurrentOTP() == "", { "changing the secret left a cached OTP visible" })
        expect(totp.updateOTPAt(60L, false), { "changing the secret did not invalidate the cached time step" })
        expect(totp.getCurrentOTP() == Tools.formatTokenString(
            TokenCalculator.TOTP_RFC6238(replacement, 30, 60L, 6, TokenCalculator.HashAlgorithm.SHA1, 0), 6),
            { "the TOTP was not recalculated with the replacement secret" })

        val motpSeed = ascii("motp seed")
        val motp = Entry(Entry.OTPType.MOTP, "motp seed", "ACME", "motp", arrayListOf())
        motp.setPin("1234")
        expect(motp.updateOTPAt(60L, false), { "the initial mOTP was not calculated" })
        motp.setPin("4321")
        expect(motp.getCurrentOTP() == "", { "changing the mOTP PIN left a cached OTP visible" })
        expect(motp.updateOTPAt(60L, false), { "changing the mOTP PIN did not invalidate the cached time step" })
        expect(motp.getCurrentOTP() == TokenCalculator.MOTP("4321", String(motpSeed), 60L, 0),
            { "the mOTP was not recalculated with the replacement PIN" })
    }

    check("mOTP refreshes on its 10-second step even when the stored period is 30") {
        val pin = "1234"
        val motpSeed = "motp seed"
        val motp = Entry(Entry.OTPType.MOTP, motpSeed, "ACME", "motp", arrayListOf())
        expect(motp.getPeriod() == 30, { "an mOTP entry keeps the stored period 30: ${motp.getPeriod()}" })
        expect(motp.getTokenStepSeconds() == 10, { "mOTP must step every 10 seconds" })
        motp.setPin(pin)
        expect(motp.updateOTPAt(60L, false), { "the initial mOTP was not calculated" })
        expect(motp.getCurrentOTP() == TokenCalculator.MOTP(pin, motpSeed, 60L, 0),
            { "the mOTP at 60s is wrong: ${motp.getCurrentOTP()}" })
        expect(!motp.updateOTPAt(65L, false), { "mOTP recalculated inside its 10-second step" })
        expect(motp.updateOTPAt(70L, false), { "mOTP did not refresh at the 10-second boundary" })
        expect(motp.getCurrentOTP() == TokenCalculator.MOTP(pin, motpSeed, 70L, 0),
            { "the mOTP at 70s is wrong: ${motp.getCurrentOTP()}" })
        expect(motp.getPrevOTP() == TokenCalculator.MOTP(pin, motpSeed, 60L, 0),
            { "the previous mOTP at 70s should be the 60s token: ${motp.getPrevOTP()}" })
        expect(motp.updateOTPAt(95L, false), { "mOTP did not refresh after a skipped step" })
        expect(motp.getCurrentOTP() == TokenCalculator.MOTP(pin, motpSeed, 95L, 0),
            { "the mOTP at 95s is wrong: ${motp.getCurrentOTP()}" })
    }

    if (failures == 0) {
        println("\nAll sandbox checks passed.")
    } else {
        println("\n$failures sandbox check(s) failed.")
        kotlin.system.exitProcess(1)
    }
}
