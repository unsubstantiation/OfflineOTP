// Sandbox-only stand-ins, see ../../android/android_content.kt.
//
// The AndroidX lifecycle surface the converted task base activity uses. The lifecycle itself is
// not modelled: the observer is registered and removed, and the checks inspect that.
package androidx.lifecycle

/** The owner of a lifecycle; the process lifecycle owner is the application. */
interface LifecycleOwner {
    /** The platform declares this as `getLifecycle()`; Kotlin sees it as the `lifecycle` property. */
    val lifecycle: Lifecycle
}

/** A lifecycle; only the observer registration is modelled. */
class Lifecycle {
    /** The lifecycle states; the instrumentation tests move scenarios between them. */
    enum class State { CREATED, STARTED, RESUMED, DESTROYED }

    /** The observers that were added, in order. */
    val observers = ArrayList<DefaultLifecycleObserver>()

    fun addObserver(observer: DefaultLifecycleObserver) {
        observers.add(observer)
    }

    fun removeObserver(observer: DefaultLifecycleObserver) {
        observers.removeAll { it === observer }
    }
}

/** The process-wide lifecycle observer hooks the converted activity implements. */
interface DefaultLifecycleObserver {
    fun onCreate(owner: LifecycleOwner) {}
    fun onStart(owner: LifecycleOwner) {}
    fun onResume(owner: LifecycleOwner) {}
    fun onPause(owner: LifecycleOwner) {}
    fun onStop(owner: LifecycleOwner) {}
    fun onDestroy(owner: LifecycleOwner) {}
}

/**
 * The application-wide lifecycle owner. The platform owns exactly one instance; here every call
 * returns the same stand-in so a check can inspect the registered observers.
 */
class ProcessLifecycleOwner : LifecycleOwner {
    override val lifecycle: Lifecycle = Lifecycle()

    companion object {
        private val instance = ProcessLifecycleOwner()

        fun get(): ProcessLifecycleOwner = instance
    }
}
