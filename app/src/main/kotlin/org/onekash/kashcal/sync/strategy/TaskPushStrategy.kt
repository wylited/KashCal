package org.onekash.kashcal.sync.strategy

import android.util.Log
import org.onekash.icaldav.parser.ICalGenerator
import org.onekash.kashcal.data.db.dao.PendingTaskOperationsDao
import org.onekash.kashcal.data.db.dao.TasksDao
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.PendingTaskOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.db.entity.Task
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.sync.RetryDelays
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.parser.icaldav.ICalTodoMapper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Counts and bookkeeping from one [TaskPushStrategy.pushForCalendar] run. [warnings] carries
 * the retryable per-operation problems; the caller records them as session warnings.
 */
data class TaskPushResult(
    val created: Int,
    val updated: Int,
    val deleted: Int,
    val failed: Int,
    /** Tasks whose server copy this cycle wrote; the same cycle's task pull doesn't reap them. */
    val pushedTaskIds: Set<Long>,
    val warnings: List<String>
)

/**
 * Pushes a calendar's ready pending task operations to the CalDAV server in FIFO order, the
 * task counterpart of [PushStrategy] reduced to CREATE/UPDATE/DELETE/MOVE:
 *
 * - CREATE: PUT the serialized VTODO, bumping SEQUENCE first.
 * - UPDATE: PUT with `If-Match`; a null etag is recovered by PROPFIND first. An UPDATE for a
 *   task the server has never seen re-runs as a CREATE.
 * - DELETE: DELETE with `If-Match`, at the operation's target URL when set; success or 404
 *   tears the local row down. A 412 first refetches the etag and retries the delete once,
 *   mirroring [PushStrategy]: server-side drift rewrote the resource, but the user asked to
 *   delete it. Only a second 412 abandons (server-wins).
 * - MOVE: PUT into the target calendar, then DELETE of the source resource — CalDAV has no
 *   MOVE method, so the task exists somewhere at every step. A re-run PUTs the same UID at
 *   the same target URL again, overwriting the first copy. A move into the local account
 *   sends no PUT: from the server's perspective it is a copy-then-delete, so only the source
 *   resource is deleted and the local row is the surviving copy. A move out of the local
 *   account only creates the target copy, as there is no server resource to delete.
 *
 * Unlike events, a CREATE/UPDATE 412 is not retried or handed to conflict resolution: the
 * operation is abandoned and the task row returns to SYNCED so the pull applies the server
 * version (server-wins; there is no task conflict UI). A retryable failure within the
 * operation's budget is rescheduled with exponential backoff ([RetryDelays]); anything else
 * is marked failed.
 */
@Singleton
class TaskPushStrategy @Inject constructor(
    private val calendarRepository: CalendarRepository,
    private val accountRepository: AccountRepository,
    private val tasksDao: TasksDao,
    private val pendingTaskOperationsDao: PendingTaskOperationsDao
) {
    /** Same instance pattern as [IcsPatcher]'s; PRODID matches what event pushes send. */
    private val generator = ICalGenerator(
        prodId = "-//KashCal//KashCal 2.0//EN",
        includeAppleExtensions = true
    )

    companion object {
        private const val TAG = "TaskPushStrategy"

        /** Returns the .ics filename of [url], so warnings don't carry the full URL. */
        private fun filenameOf(url: String?): String =
            url?.substringAfterLast('/')?.ifEmpty { url } ?: "unknown"

        /** Returns the operation name used in warnings. */
        private fun operationName(op: String): String = when (op) {
            PendingTaskOperation.OPERATION_CREATE -> "CREATE"
            PendingTaskOperation.OPERATION_UPDATE -> "UPDATE"
            PendingTaskOperation.OPERATION_DELETE -> "DELETE"
            PendingTaskOperation.OPERATION_MOVE -> "MOVE"
            else -> op
        }
    }

    /**
     * Pushes [calendar]'s ready task operations through [client], which the caller creates per
     * account. A MOVE drains on the calendar it names: its target ([PendingTaskOperation
     * .targetCalendarId]), its source ([PendingTaskOperation.sourceCalendarId]), or — for a
     * legacy operation with neither — its task's current calendar. Every other operation
     * follows its task's current calendar.
     */
    suspend fun pushForCalendar(calendar: Calendar, client: CalDavClient): TaskPushResult {
        val readyOperations = pendingTaskOperationsDao.getReadyOperations(System.currentTimeMillis())
        if (readyOperations.isEmpty()) {
            return TaskPushResult(0, 0, 0, 0, emptySet(), emptyList())
        }

        // Load every task once up front, not per operation.
        val tasksCache = tasksDao.getByIds(readyOperations.map { it.taskId }.distinct())
            .associateBy { it.id }

        // Which calendar an operation belongs to. Keep in step with
        // TaskRepository.queueOperation and PendingOperationsDao.getConflictOperationsForCalendar.
        val calendarOperations = readyOperations.filter { operation ->
            if (operation.operation == PendingTaskOperation.OPERATION_MOVE) {
                operation.targetCalendarId == calendar.id ||
                    (operation.targetCalendarId == null &&
                        tasksCache[operation.taskId]?.calendarId == calendar.id) ||
                    operation.sourceCalendarId == calendar.id
            } else {
                tasksCache[operation.taskId]?.calendarId == calendar.id
            }
        }
        if (calendarOperations.isEmpty()) {
            return TaskPushResult(0, 0, 0, 0, emptySet(), emptyList())
        }

        Log.d(TAG, "Processing ${calendarOperations.size} pending task operations")

        var created = 0
        var updated = 0
        var deleted = 0
        var failed = 0
        val pushedTaskIds = mutableSetOf<Long>()
        val warnings = mutableListOf<String>()

        for (operation in calendarOperations) {
            pendingTaskOperationsDao.markInProgress(operation.id, System.currentTimeMillis())

            val task = tasksCache[operation.taskId]
            when (val result = processOperation(operation, task, calendar, client)) {
                is TaskOpResult.Success -> {
                    pendingTaskOperationsDao.deleteById(operation.id)
                    created += result.created
                    updated += result.updated
                    deleted += result.deleted
                    result.pushedTaskId?.let { pushedTaskIds.add(it) }
                }
                is TaskOpResult.Conflict -> {
                    abandonOnConflict(operation, task, warnings)
                    failed++
                }
                is TaskOpResult.Error -> {
                    val msg = "Task push ${operationName(operation.operation)} failed " +
                        "(${result.code}) for ${filenameOf(task?.caldavUrl)}: ${result.message}"
                    if (result.isRetryable && operation.retryCount < operation.maxRetries) {
                        // Retried next sync, so a warning, not an error.
                        scheduleRetry(operation, result.message)
                        warnings.add(msg)
                    } else {
                        pendingTaskOperationsDao.markFailed(
                            operation.id,
                            result.message,
                            System.currentTimeMillis()
                        )
                    }
                    failed++
                }
            }
        }

        Log.d(TAG, "Task push complete: created=$created, updated=$updated, deleted=$deleted, failed=$failed")
        return TaskPushResult(created, updated, deleted, failed, pushedTaskIds, warnings)
    }

    /** Dispatches [operation] by type; [task] may be null when its row was deleted meanwhile. */
    private suspend fun processOperation(
        operation: PendingTaskOperation,
        task: Task?,
        calendar: Calendar,
        client: CalDavClient
    ): TaskOpResult = when (operation.operation) {
        PendingTaskOperation.OPERATION_CREATE -> processCreate(task, calendar, client)
        PendingTaskOperation.OPERATION_UPDATE -> processUpdate(task, calendar, client)
        PendingTaskOperation.OPERATION_DELETE -> processDelete(operation, task, client)
        PendingTaskOperation.OPERATION_MOVE -> processMove(operation, task, client)
        else -> TaskOpResult.Error(-1, "Unknown operation: ${operation.operation}", false)
    }

    /** Creates the task on the server, bumping SEQUENCE first (RFC 5545 §3.8.7.4). */
    private suspend fun processCreate(
        task: Task?,
        calendar: Calendar,
        client: CalDavClient
    ): TaskOpResult {
        if (task == null) {
            Log.d(TAG, "Task already deleted locally, skipping CREATE")
            return TaskOpResult.Success()
        }

        val bumped = task.copy(sequence = task.sequence + 1)
        tasksDao.update(bumped)
        val icalData = serialize(bumped)

        Log.d(TAG, "Creating task on server: ${bumped.summary} (${bumped.uid})")
        val result = client.createEvent(calendar.caldavUrl, bumped.uid, icalData)

        return when {
            result.isSuccess() -> {
                val (url, etag) = result.getOrNull()
                    ?: return TaskOpResult.Error(-1, "Unexpected null result from create", false)
                tasksDao.markCreatedOnServerWithCopy(
                    bumped.id, url, etag, icalData, System.currentTimeMillis()
                )
                Log.d(TAG, "Task created successfully: $url")
                TaskOpResult.Success(created = 1, pushedTaskId = bumped.id)
            }
            result.isConflict() -> TaskOpResult.Conflict
            else -> {
                val error = (result as? CalDavResult.Error)
                    ?: return TaskOpResult.Error(-1, "Unexpected result type", false)
                Log.e(TAG, "Failed to create task: ${error.message}")
                TaskOpResult.Error(error.code, error.message, error.isRetryable)
            }
        }
    }

    /** Updates the task on the server; one without a server resource re-runs as a CREATE. */
    private suspend fun processUpdate(
        task: Task?,
        calendar: Calendar,
        client: CalDavClient
    ): TaskOpResult {
        if (task == null) {
            Log.d(TAG, "Task already deleted locally, skipping UPDATE")
            return TaskOpResult.Success()
        }
        if (task.caldavUrl == null) {
            // Never created on the server.
            Log.w(TAG, "Task has no caldavUrl, treating UPDATE as CREATE")
            return processCreate(task, calendar, client)
        }

        val caldavUrl = task.caldavUrl!!

        // Recover a null or empty etag via PROPFIND (a server may omit <getetag> on pull).
        val effectiveEtag: String
        if (!task.etag.isNullOrEmpty()) {
            effectiveEtag = task.etag
        } else {
            Log.w(TAG, "Task has no etag, fetching via PROPFIND for ${filenameOf(caldavUrl)}")
            when (val fetchResult = client.fetchEtag(caldavUrl)) {
                is CalDavResult.Success -> {
                    val fetched = fetchResult.data
                    if (fetched == null) {
                        // Success with no etag: not retryable.
                        Log.e(TAG, "PROPFIND returned null etag for task ${task.id}")
                        return TaskOpResult.Error(-1, "No etag for task update", false)
                    }
                    // The in-memory task keeps etag=null; the PUT uses effectiveEtag.
                    tasksDao.updateEtag(task.id, fetched)
                    Log.d(TAG, "Recovered task etag via PROPFIND: ${fetched.take(8)}...")
                    effectiveEtag = fetched
                }
                is CalDavResult.Error -> {
                    // Keeps the error's retryability (network errors retry, auth errors don't).
                    Log.e(TAG, "Task PROPFIND failed for task ${task.id}: ${fetchResult.message}")
                    return TaskOpResult.Error(
                        fetchResult.code,
                        "PROPFIND fallback failed: ${fetchResult.message}",
                        fetchResult.isRetryable
                    )
                }
            }
        }

        // One SEQUENCE bump per send, like [processCreate] (RFC 5545 §3.8.7.4).
        val bumped = task.copy(sequence = task.sequence + 1)
        tasksDao.update(bumped)
        val icalData = serialize(bumped)
        Log.d(TAG, "Updating task on server: ${bumped.summary} with etag='$effectiveEtag'")
        val result = client.updateEvent(caldavUrl, icalData, effectiveEtag)

        return when {
            result.isSuccess() -> {
                val newEtag = result.getOrNull()
                    ?: return TaskOpResult.Error(-1, "Unexpected null result from update", false)
                tasksDao.markSyncedWithCopy(bumped.id, newEtag, icalData, System.currentTimeMillis())
                Log.d(TAG, "Task updated successfully")
                TaskOpResult.Success(updated = 1, pushedTaskId = bumped.id)
            }
            result.isConflict() -> TaskOpResult.Conflict
            else -> {
                val error = (result as? CalDavResult.Error)
                    ?: return TaskOpResult.Error(-1, "Unexpected result type", false)
                Log.e(TAG, "Failed to update task: ${error.message}")
                TaskOpResult.Error(error.code, error.message, error.isRetryable)
            }
        }
    }

    /**
     * Deletes the task's server resource, then the local row; a 404 counts as deleted. A 412
     * refetches the etag and retries once — the server may have rewritten the resource since
     * queueing (server-side drift), and the user still asked for the delete. Only a second
     * 412 abandons server-wins, like every other task conflict.
     */
    private suspend fun processDelete(
        operation: PendingTaskOperation,
        task: Task?,
        client: CalDavClient
    ): TaskOpResult {
        val caldavUrl = operation.targetUrl ?: task?.caldavUrl
        if (caldavUrl == null) {
            // Never on the server: only the local teardown remains.
            Log.d(TAG, "Task was never on server, deleting locally")
            task?.let { tasksDao.deleteById(it.id) }
            return TaskOpResult.Success(deleted = 1)
        }

        Log.d(TAG, "Deleting task from server: ${task?.summary ?: "unknown"} with etag='${task?.etag.orEmpty()}'")
        val result = client.deleteEvent(caldavUrl, task?.etag.orEmpty())

        return when {
            // 404: removed elsewhere between queueing and this push; the teardown is the same.
            result.isSuccess() || result.isNotFound() -> {
                Log.d(TAG, "Task deleted successfully")
                task?.let { tasksDao.deleteById(it.id) }
                TaskOpResult.Success(deleted = 1)
            }
            result.isConflict() -> {
                // 412: the resource exists but the If-Match etag no longer matches. Refetch
                // the etag and retry the delete once, mirroring [PushStrategy].
                Log.w(TAG, "412 on delete for ${task?.summary ?: "unknown"}, fetching fresh etag for retry")
                val freshEtagResult = client.fetchEtag(caldavUrl)
                when {
                    // Removed elsewhere between the delete and the refetch: done.
                    freshEtagResult.isNotFound() -> {
                        Log.d(TAG, "Refetch shows task already gone, treating delete as done")
                        task?.let { tasksDao.deleteById(it.id) }
                        TaskOpResult.Success(deleted = 1)
                    }
                    else -> {
                        val freshEtag = freshEtagResult.getOrNull()
                        if (freshEtag.isNullOrEmpty()) {
                            // No etag to retry with: server-wins, like a second 412.
                            Log.w(TAG, "fetchEtag returned no etag for delete of ${task?.summary ?: "unknown"}")
                            TaskOpResult.Conflict
                        } else {
                            val retryResult = client.deleteEvent(caldavUrl, freshEtag)
                            when {
                                retryResult.isSuccess() || retryResult.isNotFound() -> {
                                    Log.d(TAG, "412 delete retry succeeded for ${task?.summary ?: "unknown"}")
                                    task?.let { tasksDao.deleteById(it.id) }
                                    TaskOpResult.Success(deleted = 1)
                                }
                                // Drifted again: the second 412 abandons server-wins, no loop.
                                retryResult.isConflict() -> TaskOpResult.Conflict
                                else -> {
                                    val error = (retryResult as? CalDavResult.Error)
                                        ?: return TaskOpResult.Error(
                                            -1, "Unexpected result type from delete retry", false
                                        )
                                    Log.e(TAG, "412 delete retry failed for ${task?.summary ?: "unknown"}: ${error.message}")
                                    TaskOpResult.Error(error.code, error.message, error.isRetryable)
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                val error = (result as? CalDavResult.Error)
                    ?: return TaskOpResult.Error(-1, "Unexpected result type", false)
                Log.e(TAG, "Failed to delete task: ${error.message}")
                TaskOpResult.Error(error.code, error.message, error.isRetryable)
            }
        }
    }

    /**
     * Moves the task by CREATE into the target calendar and DELETE of the source resource; the
     * row's URL and etag are rewritten to the target copy when both writes land. A failed
     * source delete leaves the operation queued: the retry PUTs over the target copy (same
     * UID, same URL) and tries the source again.
     *
     * Two edges need no server-to-server pair. A move INTO the local account is, from the
     * server's perspective, a copy-then-delete: the source resource is deleted and the local
     * row is the surviving copy, so no PUT runs and the row goes back to SYNCED with no
     * server URL or etag. A move OUT of the local account only creates the target copy, as
     * there is no server resource to delete.
     */
    private suspend fun processMove(
        operation: PendingTaskOperation,
        task: Task?,
        client: CalDavClient
    ): TaskOpResult {
        if (task == null) {
            Log.d(TAG, "Task already deleted locally, skipping MOVE")
            return TaskOpResult.Success()
        }

        val targetCalendarId = operation.targetCalendarId
            ?: return TaskOpResult.Error(-1, "No target calendar for MOVE", false)
        val targetCalendar = calendarRepository.getCalendarById(targetCalendarId)
            ?: return TaskOpResult.Error(-1, "Target calendar not found for MOVE", false)
        val sourceCalendar = operation.sourceCalendarId?.let {
            calendarRepository.getCalendarById(it)
        }
        val targetIsLocal = isLocalCalendar(targetCalendar)
        val sourceIsLocal = isLocalCalendar(sourceCalendar)

        // Server → local: delete the source resource only; the local row is the surviving
        // copy. Nothing is pushed, so no SEQUENCE bump and no pushedTaskId.
        if (targetIsLocal) {
            val sourceUrl = operation.targetUrl ?: task.caldavUrl
            var deleted = 0
            if (sourceUrl != null && !sourceIsLocal) {
                Log.d(TAG, "MOVE to local: deleting source resource $sourceUrl")
                val deleteResult = client.deleteEvent(sourceUrl, task.etag.orEmpty())
                if (deleteResult.isError() && !deleteResult.isNotFound()) {
                    // A 404 source is already gone; any other failure is retried with the
                    // whole MOVE.
                    val error = deleteResult as CalDavResult.Error
                    Log.w(TAG, "MOVE source delete failed (${error.code}): ${error.message}")
                    return TaskOpResult.Error(error.code, error.message, error.isRetryable)
                }
                deleted = 1
            }
            val now = System.currentTimeMillis()
            tasksDao.update(
                task.copy(
                    caldavUrl = null,
                    etag = null,
                    syncStatus = SyncStatus.SYNCED,
                    serverModifiedAt = now,
                    updatedAt = now
                )
            )
            Log.d(TAG, "MOVE to local: source deleted, local row is the surviving copy")
            return TaskOpResult.Success(deleted = deleted)
        }

        // Server → server, or local → server: create the target copy, then delete the source
        // resource unless the move started on the local account.
        val bumped = task.copy(sequence = task.sequence + 1)
        tasksDao.update(bumped)
        val icalData = serialize(bumped)

        Log.d(TAG, "MOVE: creating task ${bumped.uid} in target calendar $targetCalendarId")
        val createResult = client.createEvent(targetCalendar.caldavUrl, bumped.uid, icalData)
        if (createResult.isError()) {
            val error = createResult as CalDavResult.Error
            if (createResult.isConflict()) return TaskOpResult.Conflict
            Log.e(TAG, "MOVE create failed (${error.code}): ${error.message}")
            return TaskOpResult.Error(error.code, error.message, error.isRetryable)
        }
        val (url, etag) = createResult.getOrNull()
            ?: return TaskOpResult.Error(-1, "Unexpected null result from move create", false)

        var deleted = 0
        if (!sourceIsLocal) {
            val sourceUrl = operation.targetUrl ?: task.caldavUrl
            if (sourceUrl != null) {
                Log.d(TAG, "MOVE: deleting source resource $sourceUrl")
                val deleteResult = client.deleteEvent(sourceUrl, task.etag.orEmpty())
                if (deleteResult.isError() && !deleteResult.isNotFound()) {
                    // A 404 source is already gone; any other failure is retried with the
                    // whole MOVE.
                    val error = deleteResult as CalDavResult.Error
                    Log.w(TAG, "MOVE source delete failed (${error.code}): ${error.message}")
                    return TaskOpResult.Error(error.code, error.message, error.isRetryable)
                }
                deleted = 1
            }
        }

        tasksDao.markCreatedOnServerWithCopy(
            bumped.id, url, etag, icalData, System.currentTimeMillis()
        )
        Log.d(TAG, "MOVE Phase 1: Task created successfully at $url")
        return TaskOpResult.Success(created = 1, deleted = deleted, pushedTaskId = bumped.id)
    }

    /**
     * Resolves a 412 server-wins: the operation is abandoned and the task row returns to
     * SYNCED, so the next pull stops skipping it as pending-local and applies the server copy.
     */
    private suspend fun abandonOnConflict(
        operation: PendingTaskOperation,
        task: Task?,
        warnings: MutableList<String>
    ) {
        val now = System.currentTimeMillis()
        pendingTaskOperationsDao.markAbandoned(operation.id, "Conflict: server has newer version", now)
        task?.let { tasksDao.markSynced(it.id, etag = null, now) }
        warnings.add(
            "Task push ${operationName(operation.operation)} conflict (412) for ${filenameOf(task?.caldavUrl)}"
        )
    }

    /** Reschedules [operation] with backoff and records [error] on it. */
    private suspend fun scheduleRetry(operation: PendingTaskOperation, error: String) {
        val now = System.currentTimeMillis()
        val delay = RetryDelays.calculateRetryDelay(operation.retryCount)
        pendingTaskOperationsDao.scheduleRetry(operation.id, now + delay, error, now)
    }

    /** True when [calendar] belongs to the on-device [AccountProvider.LOCAL] account. */
    private suspend fun isLocalCalendar(calendar: Calendar?): Boolean {
        if (calendar == null) return false
        val account = accountRepository.getAccountById(calendar.accountId) ?: return false
        return account.provider == AccountProvider.LOCAL
    }

    /** Regenerates the whole VTODO from the row; unknown lines ride along, so nothing is lost. */
    private fun serialize(task: Task): String =
        generator.generate(ICalTodoMapper.toICalTodo(task), preserveDtstamp = false)

    /** Outcome of pushing one pending task operation. */
    private sealed class TaskOpResult {
        /** The operation completed and is removed from the queue. */
        data class Success(
            val created: Int = 0,
            val updated: Int = 0,
            val deleted: Int = 0,
            val pushedTaskId: Long? = null
        ) : TaskOpResult()

        /** The server refused the write with a 412 conflict; resolved server-wins. */
        data object Conflict : TaskOpResult()

        /** The operation failed; retried while [isRetryable] and retries remain, else marked failed. */
        data class Error(
            val code: Int,
            val message: String,
            val isRetryable: Boolean
        ) : TaskOpResult()
    }
}
