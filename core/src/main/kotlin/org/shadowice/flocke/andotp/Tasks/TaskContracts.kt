package org.shadowice.flocke.andotp.Tasks

enum class TaskFailure { UNEXPECTED_EXCEPTION, NULL_RESULT }
enum class TaskCancellation { EXPLICIT, SESSION_REVOKED }
enum class TaskCompletion { VALUE, FAILURE }
enum class TaskStart { STARTED, ALREADY_SUBMITTED, CANCELED }

/** Value is producer completion, not proof of business success (e.g. wrong-password domain result). */
sealed class TaskOutcome<out T> {
    class Value<T>(val value: T) : TaskOutcome<T>() {
        override fun toString(): String = "TaskOutcome.Value(redacted)"
    }
    class Failure<T>(val error: TaskFailure) : TaskOutcome<T>() {
        override fun toString(): String = "TaskOutcome.Failure($error)"
    }
    class Cancelled<T>(val reason: TaskCancellation) : TaskOutcome<T>() {
        override fun toString(): String = "TaskOutcome.Cancelled($reason)"
    }
}

/** Metadata-only lifecycle. No result payloads/Throwable/Activity references in snapshots. */
sealed class TaskState {
    object Ready : TaskState()
    object Running : TaskState()
    class Completed(val completion: TaskCompletion) : TaskState()
    class Delivered(val completion: TaskCompletion) : TaskState()
    class Cancelled(val reason: TaskCancellation) : TaskState()
}

/** Pure state machine; Android scheduling/owned-result disposal stay in the runner adapter. */
class TaskController(val generation: Long) {
    private var state: TaskState = TaskState.Ready
    private var submitted = false
    private var cancellationDelivered = false

    @Synchronized fun snapshot(): TaskState = state
    @Synchronized fun start(): TaskStart {
        if (submitted) return TaskStart.ALREADY_SUBMITTED
        submitted = true
        if (state is TaskState.Cancelled) return TaskStart.CANCELED
        check(state is TaskState.Ready)
        state = TaskState.Running
        return TaskStart.STARTED
    }
    @Synchronized fun complete(completion: TaskCompletion): Boolean {
        if (state !is TaskState.Running) return false
        state = TaskState.Completed(completion)
        return true
    }
    @Synchronized fun cancel(reason: TaskCancellation): Boolean {
        if (state is TaskState.Delivered || state is TaskState.Cancelled) return false
        state = TaskState.Cancelled(reason)
        return true
    }
    @Synchronized fun observeGeneration(current: Long) {
        if (generation != current) cancel(TaskCancellation.SESSION_REVOKED)
    }
    @Synchronized fun deliveryConsumed(): Boolean = state is TaskState.Delivered || (state is TaskState.Cancelled && cancellationDelivered)
    @Synchronized fun claimDelivery(): Boolean = when (val current = state) {
        is TaskState.Completed -> { state = TaskState.Delivered(current.completion); true }
        is TaskState.Cancelled -> if (cancellationDelivered) false else { cancellationDelivered = true; true }
        else -> false
    }
}
