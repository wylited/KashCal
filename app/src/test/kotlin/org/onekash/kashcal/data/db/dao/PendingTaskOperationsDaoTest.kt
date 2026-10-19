package org.onekash.kashcal.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.PendingTaskOperation
import org.onekash.kashcal.data.db.entity.Task
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Tests [PendingTaskOperationsDao]'s retry lifecycle over an in-memory Room database, the task
 * counterpart of [PendingOperationsDaoTest]'s coverage:
 *
 * - Crash recovery of stale IN_PROGRESS operations ([PendingTaskOperationsDao.resetStaleInProgress])
 * - The forced-sync reset of FAILED operations ([PendingTaskOperationsDao.resetAllFailed])
 * - The 30-day expiry deletion, including the tombstoned task an expired DELETE leaves
 *   ([PendingTaskOperationsDao.deleteExpired])
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PendingTaskOperationsDaoTest {

    private lateinit var database: KashCalDatabase
    private lateinit var pendingTaskOpsDao: PendingTaskOperationsDao
    private lateinit var tasksDao: TasksDao
    private var testCalendarId: Long = 0
    private var testTaskId: Long = 0

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        pendingTaskOpsDao = database.pendingTaskOperationsDao()
        tasksDao = database.tasksDao()

        runTest {
            val accountId = database.accountsDao().insert(
                Account(provider = AccountProvider.CALDAV, email = "test@example.com")
            )
            testCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = accountId,
                    caldavUrl = "https://caldav.example.com/home/",
                    displayName = "Test Calendar",
                    color = -1
                )
            )
            testTaskId = tasksDao.insert(
                Task(
                    uid = "test-task-uid",
                    calendarId = testCalendarId,
                    summary = "Test Task",
                    status = Task.STATUS_NEEDS_ACTION,
                    dtstamp = System.currentTimeMillis()
                )
            )
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun createOperation(
        taskId: Long = testTaskId,
        operation: String = PendingTaskOperation.OPERATION_CREATE,
        status: String = PendingTaskOperation.STATUS_PENDING,
        createdAt: Long = System.currentTimeMillis(),
        updatedAt: Long = System.currentTimeMillis(),
        nextRetryAt: Long = 0
    ): PendingTaskOperation {
        return PendingTaskOperation(
            id = 0,
            taskId = taskId,
            operation = operation,
            status = status,
            createdAt = createdAt,
            updatedAt = updatedAt,
            nextRetryAt = nextRetryAt
        )
    }

    // ==================== Stale IN_PROGRESS Recovery Tests ====================

    @Test
    fun `resetStaleInProgress resets old IN_PROGRESS operations`() = runTest {
        val now = System.currentTimeMillis()
        val twoHoursAgo = now - TimeUnit.HOURS.toMillis(2)
        val thirtyMinutesAgo = now - TimeUnit.MINUTES.toMillis(30)

        // Stuck operation (2 hours old), built directly to control updatedAt
        val stuckOpId = pendingTaskOpsDao.insert(PendingTaskOperation(
            taskId = testTaskId,
            operation = PendingTaskOperation.OPERATION_UPDATE,
            status = PendingTaskOperation.STATUS_IN_PROGRESS,
            updatedAt = twoHoursAgo
        ))

        // Recent operation (30 min): not reset
        val recentOpId = pendingTaskOpsDao.insert(PendingTaskOperation(
            taskId = testTaskId,
            operation = PendingTaskOperation.OPERATION_UPDATE,
            status = PendingTaskOperation.STATUS_IN_PROGRESS,
            updatedAt = thirtyMinutesAgo
        ))

        // PENDING operation: not affected
        val pendingOpId = pendingTaskOpsDao.insert(PendingTaskOperation(
            taskId = testTaskId,
            operation = PendingTaskOperation.OPERATION_CREATE,
            status = PendingTaskOperation.STATUS_PENDING,
            updatedAt = twoHoursAgo
        ))

        val oneHourAgo = now - TimeUnit.HOURS.toMillis(1)
        val resetCount = pendingTaskOpsDao.resetStaleInProgress(cutoff = oneHourAgo, now = now)

        assertEquals(1, resetCount)
        assertEquals(PendingTaskOperation.STATUS_PENDING, pendingTaskOpsDao.getById(stuckOpId)?.status)
        assertEquals(PendingTaskOperation.STATUS_IN_PROGRESS, pendingTaskOpsDao.getById(recentOpId)?.status)
        assertEquals(PendingTaskOperation.STATUS_PENDING, pendingTaskOpsDao.getById(pendingOpId)?.status)
    }

    @Test
    fun `resetStaleInProgress returns zero when no stuck operations`() = runTest {
        val now = System.currentTimeMillis()

        // Only PENDING and FAILED operations (no IN_PROGRESS)
        pendingTaskOpsDao.insert(createOperation(status = PendingTaskOperation.STATUS_PENDING))
        val failedId = pendingTaskOpsDao.insert(createOperation())
        pendingTaskOpsDao.markFailed(failedId, "Error", now)

        val oneHourAgo = now - TimeUnit.HOURS.toMillis(1)
        val resetCount = pendingTaskOpsDao.resetStaleInProgress(cutoff = oneHourAgo, now = now)

        assertEquals(0, resetCount)
    }

    // ==================== Reset All Failed Tests ====================

    @Test
    fun `resetAllFailed returns failed operations to a fresh PENDING state`() = runTest {
        val now = System.currentTimeMillis()
        val retriedId = pendingTaskOpsDao.insert(createOperation())
        val freshFailedId = pendingTaskOpsDao.insert(createOperation())
        pendingTaskOpsDao.insert(createOperation()) // This one stays pending
        val abandonedId = pendingTaskOpsDao.insert(
            createOperation(status = PendingTaskOperation.STATUS_ABANDONED)
        )

        // A failure that already burned a retry and sits on a future retry slot.
        pendingTaskOpsDao.markFailed(retriedId, "Error 1", now)
        pendingTaskOpsDao.scheduleRetry(retriedId, now + TimeUnit.MINUTES.toMillis(4), "Error 1", now)
        pendingTaskOpsDao.markFailed(retriedId, "Error 1", now)
        pendingTaskOpsDao.markFailed(freshFailedId, "Error 2", now)

        val resetCount = pendingTaskOpsDao.resetAllFailed(now)

        assertEquals(2, resetCount)

        // A fresh retry budget: back to PENDING, ready now, error cleared.
        val retried = pendingTaskOpsDao.getById(retriedId)
        assertEquals(PendingTaskOperation.STATUS_PENDING, retried?.status)
        assertEquals(0, retried?.retryCount)
        assertEquals(0L, retried?.nextRetryAt)
        assertNull(retried?.lastError)

        assertEquals(PendingTaskOperation.STATUS_PENDING, pendingTaskOpsDao.getById(freshFailedId)?.status)

        // Both reset operations are immediately ready for the push again.
        assertEquals(3, pendingTaskOpsDao.getReadyOperations(now).size)

        // ABANDONED is a 412 server-wins resolution the pull owns: never revived here.
        assertEquals(
            PendingTaskOperation.STATUS_ABANDONED,
            pendingTaskOpsDao.getById(abandonedId)?.status
        )
    }

    // ==================== Expiry Deletion Tests ====================

    /** Inserts an operation already past the 30-day lifetime, so deleteExpired finds it. */
    private suspend fun insertExpiredOperation(
        operation: String = PendingTaskOperation.OPERATION_CREATE,
        taskId: Long = testTaskId
    ): Long {
        return pendingTaskOpsDao.insert(
            createOperation(
                taskId = taskId,
                operation = operation,
                createdAt = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(31)
            )
        )
    }

    @Test
    fun `deleteExpired deletes operations past their lifetime and keeps fresh ones`() = runTest {
        val now = System.currentTimeMillis()
        val expiredId = insertExpiredOperation()
        val freshId = pendingTaskOpsDao.insert(createOperation())

        val deletedCount = pendingTaskOpsDao.deleteExpired(cutoff = now - TimeUnit.DAYS.toMillis(30))

        assertEquals(1, deletedCount)
        assertNull(pendingTaskOpsDao.getById(expiredId))
        assertNotNull(pendingTaskOpsDao.getById(freshId))
    }

    @Test
    fun `deleteExpired removes the tombstoned task of an expired DELETE operation`() = runTest {
        val now = System.currentTimeMillis()

        // A soft-deleted task whose DELETE never made it out: expired with its operation.
        insertExpiredOperation(operation = PendingTaskOperation.OPERATION_DELETE)
        tasksDao.markForDeletion(testTaskId, now)

        // Control: a tombstone whose DELETE is fresh stays for the next push.
        val survivorTaskId = tasksDao.insert(
            Task(
                uid = "survivor-task-uid",
                calendarId = testCalendarId,
                summary = "Survivor",
                status = Task.STATUS_NEEDS_ACTION,
                dtstamp = now
            )
        )
        pendingTaskOpsDao.insert(
            createOperation(
                taskId = survivorTaskId,
                operation = PendingTaskOperation.OPERATION_DELETE
            )
        )
        tasksDao.markForDeletion(survivorTaskId, now)

        val deletedCount = pendingTaskOpsDao.deleteExpired(cutoff = now - TimeUnit.DAYS.toMillis(30))

        assertEquals(1, deletedCount)
        assertNull(tasksDao.getById(testTaskId))
        assertNotNull(tasksDao.getById(survivorTaskId))
    }

    @Test
    fun `deleteExpired keeps the task row of an expired CREATE operation`() = runTest {
        // Expiry discards the operation, never a task with content: the pull keeps such a row
        // fresh, and a never-synced row stays visible to its owner.
        val now = System.currentTimeMillis()
        insertExpiredOperation(operation = PendingTaskOperation.OPERATION_CREATE)

        val deletedCount = pendingTaskOpsDao.deleteExpired(cutoff = now - TimeUnit.DAYS.toMillis(30))

        assertEquals(1, deletedCount)
        assertNotNull(tasksDao.getById(testTaskId))
    }
}
