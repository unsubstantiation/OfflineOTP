package org.shadowice.flocke.andotp.Activities

import android.app.Fragment
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

import org.shadowice.flocke.andotp.Tasks.TaskFailure
import org.shadowice.flocke.andotp.Tasks.TaskOutcome
import org.shadowice.flocke.andotp.Tasks.UiBasedBackgroundTask

/**
 * Base activity that runs a single [UiBasedBackgroundTask] inside a retained fragment
 * so in-flight work survives configuration changes.
 */
abstract class BackgroundTaskActivity<Result> : BaseActivity() {
    @JvmField
    protected val TAG_TASK_FRAGMENT: String = this.javaClass.simpleName + ".TaskFragment"

    private var observer: ProcessLifecycleObserver? = null
    private var activeTask: UiBasedBackgroundTask<Result>? = null
    private var activeFragment: TaskFragment<Result>? = null

    /** Called with the result of a finished task; the UI is already back to its idle state. */
    protected abstract fun onTaskResult(result: Result)

    protected open fun onTaskFailure(error: TaskFailure) {
        onReturnToCanceledTask()
    }

    protected open fun onReturnToCanceledTask() {
        // Override to react when the user comes back to a canceled task
    }

    protected open fun setupUiForTaskState(running: Boolean) {
        // Override to reflect the running state in the UI
    }

    /** True to cancel the running task when the app goes to the background. */
    protected open fun cancelTaskOnScreenOff(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (cancelTaskOnScreenOff()) {
            val created = ProcessLifecycleObserver()
            observer = created

            ProcessLifecycleOwner.get().lifecycle.addObserver(created)
        }
    }

    override fun onDestroy() {
        observer?.let {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(it)
        }

        observer = null

        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing()) checkBackgroundTask()
    }

    override fun onPause() {
        super.onPause()

        // Detach the callback while paused so a finished task does not reference a dead activity.
        findTaskFragment()?.setOutcomeCallback(null)
    }

    /** Cancels the running task (if any) and restores the idle UI. */
    protected fun cancelBackgroundTask() {
        findTaskFragment()?.cancelTask()

        setupUiForTaskState(false)
    }

    /** Hands the task to the retained fragment and switches to the running UI. */
    protected fun startBackgroundTask(task: UiBasedBackgroundTask<Result>) {
        var fragment = findTaskFragment()
        if (fragment != null && !fragment.isCanceled()) {
            task.setOutcomeCallback(null)
            task.cancel()
            return
        }
        if (fragment == null) {
            fragment = TaskFragment()
            activeFragment = fragment
            getFragmentManager().beginTransaction().add(fragment, TAG_TASK_FRAGMENT).commit()
        } else {
            fragment.setOutcomeCallback(null)
        }
        activeTask = task
        task.setOutcomeCallback { outcome -> handleTaskOutcome(task, outcome) }
        fragment.startTask(task)
        setupUiForTaskState(true)
    }

    private fun handleTaskOutcome(source: UiBasedBackgroundTask<Result>, outcome: TaskOutcome<Result>) {
        if (source !== activeTask) {
            source.disposeOutcome(outcome)
            return
        }
        val fragment = findTaskFragment()
        activeTask = null
        activeFragment = null
        if (fragment != null && fragment.task === source) {
            fragment.task = null
            getFragmentManager().beginTransaction().remove(fragment).commit()
        }
        setupUiForTaskState(false)
        if (outcome is TaskOutcome.Value) onTaskResult(outcome.value)
        else if (outcome is TaskOutcome.Failure) onTaskFailure(outcome.error)
        else onReturnToCanceledTask()
    }

    /** Rebind the same task on resume, including a retained cancellation outcome. */
    private fun checkBackgroundTask() {
        val fragment = findTaskFragment()
        if (fragment == null) {
            setupUiForTaskState(false)
            return
        }
        activeFragment = fragment
        // The fragment is only returned while it still holds a task.
        val source = fragment.task!!
        activeTask = source
        fragment.setOutcomeCallback { outcome -> handleTaskOutcome(source, outcome) }
        setupUiForTaskState(!fragment.isCanceled())
    }

    /** The retained task fragment, or null if none is attached. */
    @Suppress("UNCHECKED_CAST")
    private fun findTaskFragment(): TaskFragment<Result>? {
        val active = activeFragment
        if (active != null && active.task != null) return active
        val fragment = getFragmentManager().findFragmentByTag(TAG_TASK_FRAGMENT)

        if (fragment is TaskFragment<*> && fragment.task != null) return fragment as TaskFragment<Result>
        else return null
    }

    /** Cancels the running task when the app goes to the background (if the activity asks for it). */
    private inner class ProcessLifecycleObserver : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            if (cancelTaskOnScreenOff())
                cancelBackgroundTask()
        }
    }

    /** Retained fragment that keeps the running task alive across configuration changes. */
    class TaskFragment<Result> : Fragment() {
        /** The task this fragment keeps alive; the owning activity reads and clears it. */
        internal var task: UiBasedBackgroundTask<Result>? = null

        init {
            setRetainInstance(true)
        }

        fun startTask(task: UiBasedBackgroundTask<Result>) {
            this.task = task
            task.execute()
        }

        fun setOutcomeCallback(callback: UiBasedBackgroundTask.OutcomeCallback<Result>?) {
            this.task?.setOutcomeCallback(callback)
        }

        override fun onDestroy() {
            val current = task
            if (current != null) {
                current.setOutcomeCallback(null)
                current.cancel()
            }
            super.onDestroy()
        }

        fun isCanceled(): Boolean {
            val current = task
            return if (current != null) current.isCanceled() else true
        }

        fun cancelTask() {
            task?.cancel()
        }
    }
}
