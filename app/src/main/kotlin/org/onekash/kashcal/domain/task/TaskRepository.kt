package org.onekash.kashcal.domain.task

import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.PendingTaskOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.db.entity.Task
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.widget.WidgetUpdateManager
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes tasks and queues their sync operations, the task counterpart of [EventWriter]'s
 * offline-first discipline: every write lands in Room at once and reaches the server when the
 * sync it schedules pushes [PendingTaskOperation]s. Every mutation ends by scheduling that
 * sync (mirroring [org.onekash.kashcal.domain.coordinator.EventCoordinator]'s post-edit
 * expedited request) and repainting widgets — without the schedule call a queued operation
 * waits for the next periodic sync, so a completed task could sit unpushed for hours.
 *
 * One exception to the queue: a task of the on-device [AccountProvider.LOCAL] account has no
 * server to reach, so its rows are marked SYNCED and no operation is queued — the push would
 * otherwise never drain it, because local accounts are never synced. Local writes still
 * repaint widgets.
 */
@Singleton
class TaskRepository @Inject constructor(
    private val database: KashCalDatabase,
    private val accountRepository: AccountRepository,
    private val syncScheduler: SyncScheduler,
    private val widgetUpdateManager: WidgetUpdateManager
) {
    private val tasksDao by lazy { database.tasksDao() }
    private val pendingTaskOperationsDao by lazy { database.pendingTaskOperationsDao() }
    private val calendarsDao by lazy { database.calendarsDao() }

    /**
     * Inserts [task] and returns the saved row. A blank UID is generated; a task of a server
     * calendar is marked PENDING_CREATE with a queued CREATE operation, a local-account task
     * is SYNCED with none.
     *
     * Refused for a read-only calendar, like [move]'s target check: the task form leaves
     * those out of its picker, and the push of a queued CREATE for one never runs (its sync
     * is pull-only), so the operation would expire into a row that presents as synced
     * forever.
     */
    suspend fun create(task: Task): Task {
        val (saved, needsServerSync) = database.runInTransaction {
            val now = System.currentTimeMillis()
            val target = calendarsDao.getById(task.calendarId)
            // The task form also leaves read-only calendars out of its picker.
            require(target?.isReadOnly != true) {
                "Cannot create a task in a read-only calendar"
            }
            val isLocal = isLocalCalendar(target)
            val row = task.copy(
                uid = task.uid.ifBlank { generateUid() },
                syncStatus = if (isLocal) SyncStatus.SYNCED else SyncStatus.PENDING_CREATE,
                // Stamped here, never taken from the caller: a stale UI copy must not plant an
                // old value.
                localModifiedAt = now,
                updatedAt = now
            )
            val saved = row.copy(id = tasksDao.insert(row))
            if (!isLocal) {
                queueOperation(saved, PendingTaskOperation.OPERATION_CREATE)
            }
            saved to !isLocal
        }
        notifyChanged(needsServerSync)
        return saved
    }

    /**
     * Persists [task]'s edit and returns the saved row. A task whose stored row has a server
     * URL queues UPDATE and is marked PENDING_UPDATE; one the server has never seen re-types
     * its queued operation to CREATE and stays PENDING_CREATE, so the push uploads the whole
     * task instead of patching nothing. The URL is typed off the stored row, not the
     * argument, so a stale UI copy can't drop a live URL and duplicate the server resource.
     * A local-account task is SYNCED with no operation.
     *
     * Refused when the stored calendar — or, like [move], a calendar the edit moves the task
     * into by changing calendarId — is read-only: the push of the queued operation never
     * runs for one (its sync is pull-only), so it would expire into a row that presents as
     * synced forever.
     */
    suspend fun update(task: Task): Task {
        // A null pair means the row was already gone: the old code returned before announcing,
        // and this keeps that.
        val result = database.runInTransaction {
            val now = System.currentTimeMillis()
            val existing = tasksDao.getById(task.id) ?: return@runInTransaction null
            val storedCalendar = calendarsDao.getById(existing.calendarId)
            // The task form also leaves read-only calendars out of its picker.
            require(storedCalendar?.isReadOnly != true) {
                "Cannot update a task in a read-only calendar"
            }
            val target = if (task.calendarId == existing.calendarId) {
                storedCalendar
            } else {
                // A calendarId change is a move past move()'s guard, so its read-only
                // refusal applies here too.
                val movedTo = calendarsDao.getById(task.calendarId)
                require(movedTo?.isReadOnly != true) {
                    "Cannot move a task to a read-only calendar"
                }
                movedTo
            }
            val isLocal = isLocalCalendar(target)
            val neverOnServer = existing.caldavUrl == null
            val row = task.copy(
                caldavUrl = task.caldavUrl ?: existing.caldavUrl,
                syncStatus = when {
                    isLocal -> SyncStatus.SYNCED
                    neverOnServer -> SyncStatus.PENDING_CREATE
                    else -> SyncStatus.PENDING_UPDATE
                },
                localModifiedAt = now,
                updatedAt = now
            )
            tasksDao.update(row)
            if (!isLocal) {
                queueOperation(
                    row,
                    if (neverOnServer) PendingTaskOperation.OPERATION_CREATE else PendingTaskOperation.OPERATION_UPDATE
                )
            }
            row to !isLocal
        } ?: return task
        notifyChanged(needsServerSync = result.second)
        return result.first
    }

    /**
     * Deletes [task]. Whether the task has a server resource is typed off the stored row, not
     * the possibly-stale argument — a task created offline whose background sync already
     * pushed the CREATE carries a [Task.caldavUrl] in the row that the caller's copy may
     * lack, so its delete must queue a DELETE rather than hard-delete a resource the server
     * now holds. A task with a server resource is soft-deleted (PENDING_DELETE) with a queued
     * DELETE carrying the stored [Task.caldavUrl] as the operation's target URL, so the push
     * removes the server copy and the row disappears with it. A task that never reached the
     * server, or one of a local account, is torn down at once: its row and queued operations
     * are gone when this returns. A missing row is a no-op that still repaints widgets.
     */
    suspend fun delete(task: Task) {
        val needsServerSync = database.runInTransaction {
            val existing = tasksDao.getById(task.id) ?: return@runInTransaction false
            val isLocal = isLocalCalendar(calendarsDao.getById(existing.calendarId))
            if (isLocal || existing.caldavUrl == null) {
                pendingTaskOperationsDao.deleteForTask(existing.id)
                tasksDao.deleteById(existing.id)
                false
            } else {
                tasksDao.markForDeletion(existing.id, System.currentTimeMillis())
                queueOperation(
                    existing,
                    PendingTaskOperation.OPERATION_DELETE,
                    targetUrl = existing.caldavUrl
                )
                true
            }
        }
        notifyChanged(needsServerSync)
    }

    /**
     * Moves [task] into [targetCalendarId] and returns the moved row, queueing one MOVE
     * operation that the push resolves as CREATE into the target calendar followed by DELETE
     * of the source resource (CalDAV has no MOVE method for the fallback path events use).
     * The operation carries the source calendar id, so the source calendar's push still
     * drains it once the row's `calendarId` names the target — a MOVE whose target never
     * syncs (the local account, say) is otherwise undrainable. The moved row keeps
     * [Task.caldavUrl] until the push rewrites it, and is PENDING_UPDATE until then so the
     * source calendar's pull can't re-ingest the resource mid-move.
     *
     * The move is refused when the target is read-only or belongs to another account; tasks
     * have no attendees, so events' invitation misdelivery concern doesn't apply here. Both
     * calendars are loaded fresh, so a stale caller copy can't smuggle a target past the
     * checks.
     *
     * A move between two calendars of the local account queues nothing.
     */
    suspend fun move(task: Task, targetCalendarId: Long): Task {
        val (moved, needsServerSync) = database.runInTransaction {
            val now = System.currentTimeMillis()
            val source = requireNotNull(calendarsDao.getById(task.calendarId)) {
                "Source calendar not found: ${task.calendarId}"
            }
            val target = requireNotNull(calendarsDao.getById(targetCalendarId)) {
                "Target calendar not found: $targetCalendarId"
            }

            // The event form also leaves read-only calendars out of its picker.
            require(!target.isReadOnly) {
                "Cannot move a task to a read-only calendar"
            }
            require(source.accountId == target.accountId) {
                "Cannot move a task to a calendar of a different account " +
                    "(source ${source.accountId}, target ${target.accountId})"
            }

            val bothLocal = isLocalCalendar(target) && isLocalCalendar(source)
            val moved = task.copy(
                calendarId = targetCalendarId,
                syncStatus = if (bothLocal) SyncStatus.SYNCED else SyncStatus.PENDING_UPDATE,
                localModifiedAt = now,
                updatedAt = now
            )
            tasksDao.update(moved)
            if (!bothLocal) {
                queueOperation(
                    moved,
                    PendingTaskOperation.OPERATION_MOVE,
                    targetUrl = task.caldavUrl,
                    targetCalendarId = targetCalendarId,
                    sourceCalendarId = source.id
                )
            }
            moved to !bothLocal
        }
        notifyChanged(needsServerSync)
        return moved
    }

    /**
     * Completes or reopens [task] and returns the saved row, keeping the completion triple
     * consistent (wycal's law): STATUS:COMPLETED, COMPLETED and PERCENT-COMPLETE:100 are
     * written together, and reopening clears all three at once. CANCELLED counts as open, so
     * toggling it completes it. The status change rides [update]'s queue path.
     */
    suspend fun toggleDone(task: Task): Task {
        val now = System.currentTimeMillis()
        val toggled = if (task.isDone) {
            task.copy(
                status = Task.STATUS_NEEDS_ACTION,
                completedTs = null,
                percentComplete = null
            )
        } else {
            task.copy(
                status = Task.STATUS_COMPLETED,
                completedTs = now,
                percentComplete = 100
            )
        }
        return update(toggled)
    }

    /**
     * Replaces [task]'s CREATE, UPDATE and MOVE operations with one fresh [operation]: an
     * older op's payload is superseded by the row's current state, so only the newest intent
     * is pushed. A queued DELETE survives [deleteNonDeleteOpsForTask], so an edit can't
     * resurrect a task already being torn down.
     */
    private suspend fun queueOperation(
        task: Task,
        operation: String,
        targetUrl: String? = null,
        targetCalendarId: Long? = null,
        sourceCalendarId: Long? = null
    ) {
        pendingTaskOperationsDao.deleteNonDeleteOpsForTask(task.id)
        pendingTaskOperationsDao.insert(
            PendingTaskOperation(
                taskId = task.id,
                operation = operation,
                targetUrl = targetUrl,
                targetCalendarId = targetCalendarId,
                sourceCalendarId = sourceCalendarId
            )
        )
    }

    /** True when [calendar] belongs to the on-device [AccountProvider.LOCAL] account. */
    private suspend fun isLocalCalendar(calendar: Calendar?): Boolean {
        if (calendar == null) return false
        val account = accountRepository.getAccountById(calendar.accountId) ?: return false
        return account.provider == AccountProvider.LOCAL
    }

    /**
     * Announces a finished mutation: widgets repaint at once (a local task change is visible
     * with no server), and a server-calendar change requests an expedited sync so the queued
     * operation pushes immediately instead of waiting for the periodic schedule. Rapid
     * consecutive edits share one sync — [SyncScheduler.requestExpeditedSync] keeps an
     * already-queued request and drops the new one.
     *
     * Every caller invokes this after [KashCalDatabase.runInTransaction] has committed, never
     * inside it: the expedited request can start a worker before the transaction commits, and
     * a worker on another connection reads the pre-commit WAL snapshot and finds no queued
     * operation — the ticked-task-never-pushes race.
     */
    private suspend fun notifyChanged(needsServerSync: Boolean) {
        widgetUpdateManager.updateAllWidgets()
        if (needsServerSync) {
            syncScheduler.requestExpeditedSync(forceFullSync = false)
        }
    }

    /** Mirrors [EventWriter]'s UID scheme, so tasks and events share one namespace shape. */
    private fun generateUid(): String = "${UUID.randomUUID()}@kashcal.onekash.org"
}
