package org.onekash.kashcal.domain.task

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.PendingTaskOperationsDao
import org.onekash.kashcal.data.db.dao.TasksDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.PendingTaskOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.db.entity.Task
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.model.AccountProvider

/**
 * Tests [TaskRepository]'s offline-first writes against mocked DAOs: the local-account
 * shortcut (SYNCED, no operation), the queued CREATE/UPDATE/DELETE/MOVE paths, the move's
 * read-only and cross-account refusals, the completion triple of
 * [TaskRepository.toggleDone], and the soft/hard delete split.
 */
class TaskRepositoryTest {

    private lateinit var database: KashCalDatabase
    private lateinit var tasksDao: TasksDao
    private lateinit var pendingTaskOperationsDao: PendingTaskOperationsDao
    private lateinit var calendarsDao: CalendarsDao
    private lateinit var accountRepository: AccountRepository
    private lateinit var repository: TaskRepository

    private val caldavCalendar = Calendar(
        id = 1L,
        accountId = 2L,
        caldavUrl = "https://caldav.example.com/user/tasks/",
        displayName = "Tasks",
        color = -1
    )

    private val otherCaldavCalendar = caldavCalendar.copy(
        id = 9L,
        caldavUrl = "https://caldav.example.com/user/other/"
    )

    private val localCalendar = Calendar(
        id = 3L,
        accountId = 1L,
        caldavUrl = "local://on-device",
        displayName = "On this device",
        color = -1
    )

    private val otherLocalCalendar = localCalendar.copy(
        id = 4L,
        caldavUrl = "local://on-device-2"
    )

    private fun task(
        id: Long = 5L,
        calendarId: Long = 1L,
        uid: String = "task-uid@example.com",
        caldavUrl: String? = null,
        status: String = Task.STATUS_NEEDS_ACTION
    ) = Task(
        id = id,
        uid = uid,
        calendarId = calendarId,
        summary = "Test Task",
        status = status,
        dtstamp = 1_000L,
        caldavUrl = caldavUrl
    )

    @Before
    fun setup() {
        database = mockk()
        tasksDao = mockk()
        pendingTaskOperationsDao = mockk(relaxed = true)
        calendarsDao = mockk()
        accountRepository = mockk()

        every { database.tasksDao() } returns tasksDao
        every { database.pendingTaskOperationsDao() } returns pendingTaskOperationsDao
        every { database.calendarsDao() } returns calendarsDao

        // The row writes behind most repository paths. Tests below coVerify the ones they
        // care about, so a blanket just-Runs keeps the strict mock from failing first.
        coEvery { tasksDao.update(any()) } just runs
        coEvery { tasksDao.deleteById(any()) } just runs
        coEvery { tasksDao.markForDeletion(any(), any()) } just runs

        // runInTransaction is the mockable withTransaction wrapper; run the block directly.
        coEvery {
            database.runInTransaction(any<suspend () -> Any>())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = firstArg<suspend () -> Any>()
            block()
        }

        coEvery { accountRepository.getAccountById(1L) } returns
            Account(provider = AccountProvider.LOCAL, email = "local@device")
        coEvery { accountRepository.getAccountById(2L) } returns
            Account(provider = AccountProvider.CALDAV, email = "user@example.com")

        repository = TaskRepository(database, accountRepository)
    }

    // ========== Create ==========

    @Test
    fun `create on a local account marks SYNCED and queues nothing`() = runTest {
        coEvery { calendarsDao.getById(3L) } returns localCalendar
        coEvery { tasksDao.insert(any()) } returns 10L

        val saved = repository.create(task(calendarId = 3L, uid = ""))

        assertEquals(SyncStatus.SYNCED, saved.syncStatus)
        assertTrue(saved.uid.endsWith("@kashcal.onekash.org"))
        assertEquals(10L, saved.id)
        coVerify(exactly = 0) { pendingTaskOperationsDao.insert(any()) }
    }

    @Test
    fun `create on a caldav account marks PENDING_CREATE and queues a CREATE operation`() = runTest {
        coEvery { calendarsDao.getById(1L) } returns caldavCalendar
        coEvery { tasksDao.insert(any()) } returns 11L

        val saved = repository.create(task(calendarId = 1L))

        assertEquals(SyncStatus.PENDING_CREATE, saved.syncStatus)
        assertEquals("task-uid@example.com", saved.uid)
        assertNotNull(saved.localModifiedAt)
        coVerify {
            pendingTaskOperationsDao.insert(
                match {
                    it.taskId == 11L && it.operation == PendingTaskOperation.OPERATION_CREATE
                }
            )
        }
    }

    @Test
    fun `create stamps localModifiedAt itself, ignoring a stale caller copy`() = runTest {
        coEvery { calendarsDao.getById(1L) } returns caldavCalendar
        coEvery { tasksDao.insert(any()) } returns 11L

        repository.create(task(calendarId = 1L).copy(localModifiedAt = 5L))

        coVerify {
            tasksDao.insert(match { it.localModifiedAt != 5L && it.localModifiedAt == it.updatedAt })
        }
    }

    // ========== Update ==========

    @Test
    fun `update of a synced task queues UPDATE and marks PENDING_UPDATE`() = runTest {
        coEvery { calendarsDao.getById(1L) } returns caldavCalendar
        coEvery { tasksDao.getById(5L) } returns
            task(caldavUrl = "https://caldav.example.com/user/tasks/a.ics")

        val saved = repository.update(task(id = 5L).copy(summary = "Edited"))

        assertEquals(SyncStatus.PENDING_UPDATE, saved.syncStatus)
        assertEquals("Edited", saved.summary)
        coVerify {
            tasksDao.update(match { it.id == 5L && it.syncStatus == SyncStatus.PENDING_UPDATE })
        }
        coVerify {
            pendingTaskOperationsDao.insert(
                match { it.taskId == 5L && it.operation == PendingTaskOperation.OPERATION_UPDATE }
            )
        }
    }

    @Test
    fun `update of a task without a server URL re-types the operation to CREATE`() = runTest {
        coEvery { calendarsDao.getById(1L) } returns caldavCalendar
        coEvery { tasksDao.getById(5L) } returns task(id = 5L)

        val saved = repository.update(task(id = 5L).copy(summary = "Edited"))

        assertEquals(SyncStatus.PENDING_CREATE, saved.syncStatus)
        coVerify {
            pendingTaskOperationsDao.insert(
                match { it.taskId == 5L && it.operation == PendingTaskOperation.OPERATION_CREATE }
            )
        }
    }

    @Test
    fun `update of a local-account task stays SYNCED and queues nothing`() = runTest {
        coEvery { calendarsDao.getById(3L) } returns localCalendar
        coEvery { tasksDao.getById(5L) } returns task(id = 5L, calendarId = 3L)

        val saved = repository.update(task(id = 5L, calendarId = 3L).copy(summary = "Edited"))

        assertEquals(SyncStatus.SYNCED, saved.syncStatus)
        coVerify(exactly = 0) { pendingTaskOperationsDao.insert(any()) }
    }

    // ========== toggleDone ==========

    @Test
    fun `toggleDone on an open task writes the completion triple`() = runTest {
        val url = "https://caldav.example.com/user/tasks/a.ics"
        coEvery { calendarsDao.getById(1L) } returns caldavCalendar
        coEvery { tasksDao.getById(5L) } returns task(caldavUrl = url)

        val done = repository.toggleDone(task(id = 5L, caldavUrl = url))

        assertEquals(Task.STATUS_COMPLETED, done.status)
        assertNotNull(done.completedTs)
        assertEquals(100, done.percentComplete)
        coVerify {
            tasksDao.update(
                match {
                    it.id == 5L && it.status == Task.STATUS_COMPLETED &&
                        it.completedTs != null && it.percentComplete == 100
                }
            )
        }
        coVerify {
            pendingTaskOperationsDao.insert(
                match { it.operation == PendingTaskOperation.OPERATION_UPDATE }
            )
        }
    }

    @Test
    fun `toggleDone on a completed task clears the completion triple`() = runTest {
        val url = "https://caldav.example.com/user/tasks/a.ics"
        coEvery { calendarsDao.getById(1L) } returns caldavCalendar
        coEvery { tasksDao.getById(5L) } returns task(caldavUrl = url)

        val reopened = repository.toggleDone(
            task(id = 5L, caldavUrl = url, status = Task.STATUS_COMPLETED)
        )

        assertEquals(Task.STATUS_NEEDS_ACTION, reopened.status)
        assertNull(reopened.completedTs)
        assertNull(reopened.percentComplete)
        coVerify {
            tasksDao.update(
                match {
                    it.id == 5L && it.status == Task.STATUS_NEEDS_ACTION &&
                        it.completedTs == null && it.percentComplete == null
                }
            )
        }
    }

    @Test
    fun `toggleDone on a cancelled task completes it`() = runTest {
        val url = "https://caldav.example.com/user/tasks/a.ics"
        coEvery { calendarsDao.getById(1L) } returns caldavCalendar
        coEvery { tasksDao.getById(5L) } returns task(caldavUrl = url, status = Task.STATUS_CANCELLED)

        val done = repository.toggleDone(
            task(id = 5L, caldavUrl = url, status = Task.STATUS_CANCELLED)
        )

        assertEquals(Task.STATUS_COMPLETED, done.status)
    }

    // ========== Delete ==========

    @Test
    fun `delete of a synced task soft-deletes and queues DELETE with the target URL`() = runTest {
        coEvery { calendarsDao.getById(1L) } returns caldavCalendar
        val url = "https://caldav.example.com/user/tasks/a.ics"

        repository.delete(task(caldavUrl = url))

        coVerify { tasksDao.markForDeletion(5L, any()) }
        coVerify {
            pendingTaskOperationsDao.insert(
                match {
                    it.taskId == 5L && it.operation == PendingTaskOperation.OPERATION_DELETE &&
                        it.targetUrl == url
                }
            )
        }
        coVerify(exactly = 0) { tasksDao.deleteById(any()) }
    }

    @Test
    fun `delete of a never-synced task is a hard delete`() = runTest {
        coEvery { calendarsDao.getById(1L) } returns caldavCalendar

        repository.delete(task(caldavUrl = null))

        coVerify { pendingTaskOperationsDao.deleteForTask(5L) }
        coVerify { tasksDao.deleteById(5L) }
        coVerify(exactly = 0) { tasksDao.markForDeletion(any(), any()) }
        coVerify(exactly = 0) { pendingTaskOperationsDao.insert(any()) }
    }

    @Test
    fun `delete of a local-account task is a hard delete`() = runTest {
        coEvery { calendarsDao.getById(3L) } returns localCalendar

        repository.delete(task(calendarId = 3L, caldavUrl = "https://caldav.example.com/user/tasks/a.ics"))

        coVerify { pendingTaskOperationsDao.deleteForTask(5L) }
        coVerify { tasksDao.deleteById(5L) }
    }

    // ========== Move ==========

    @Test
    fun `move changes the row's calendar and queues MOVE with target and source URL`() = runTest {
        coEvery { calendarsDao.getById(9L) } returns otherCaldavCalendar
        coEvery { calendarsDao.getById(1L) } returns caldavCalendar
        val url = "https://caldav.example.com/user/tasks/a.ics"

        val moved = repository.move(task(caldavUrl = url), targetCalendarId = 9L)

        assertEquals(9L, moved.calendarId)
        assertEquals(SyncStatus.PENDING_UPDATE, moved.syncStatus)
        coVerify {
            tasksDao.update(match { it.id == 5L && it.calendarId == 9L })
        }
        coVerify {
            pendingTaskOperationsDao.insert(
                match {
                    it.taskId == 5L && it.operation == PendingTaskOperation.OPERATION_MOVE &&
                        it.targetCalendarId == 9L && it.targetUrl == url &&
                        it.sourceCalendarId == 1L
                }
            )
        }
    }

    @Test
    fun `move to a read-only calendar is rejected`() {
        coEvery { calendarsDao.getById(9L) } returns otherCaldavCalendar.copy(isReadOnly = true)
        coEvery { calendarsDao.getById(1L) } returns caldavCalendar

        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.move(task(), targetCalendarId = 9L) }
        }
        assertEquals("Cannot move a task to a read-only calendar", error.message)
        coVerify(exactly = 0) { tasksDao.update(any()) }
        coVerify(exactly = 0) { pendingTaskOperationsDao.insert(any()) }
    }

    @Test
    fun `move to another account's calendar is rejected`() {
        coEvery { calendarsDao.getById(9L) } returns otherCaldavCalendar.copy(accountId = 7L)
        coEvery { calendarsDao.getById(1L) } returns caldavCalendar

        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.move(task(), targetCalendarId = 9L) }
        }
        assertTrue(error.message!!.startsWith("Cannot move a task to a calendar of a different account"))
        coVerify(exactly = 0) { tasksDao.update(any()) }
        coVerify(exactly = 0) { pendingTaskOperationsDao.insert(any()) }
    }

    @Test
    fun `move between two local calendars queues nothing`() = runTest {
        coEvery { calendarsDao.getById(4L) } returns otherLocalCalendar
        coEvery { calendarsDao.getById(3L) } returns localCalendar

        val moved = repository.move(task(calendarId = 3L), targetCalendarId = 4L)

        assertEquals(4L, moved.calendarId)
        assertEquals(SyncStatus.SYNCED, moved.syncStatus)
        coVerify(exactly = 0) { pendingTaskOperationsDao.insert(any()) }
    }

    // ========== Operation queue hygiene ==========

    @Test
    fun `queueing an operation clears the task's older non-DELETE operations first`() = runTest {
        coEvery { calendarsDao.getById(1L) } returns caldavCalendar
        coEvery { tasksDao.getById(5L) } returns
            task(caldavUrl = "https://caldav.example.com/user/tasks/a.ics")

        repository.update(task(id = 5L).copy(summary = "Edited"))

        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            pendingTaskOperationsDao.deleteNonDeleteOpsForTask(5L)
        }
        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            pendingTaskOperationsDao.insert(any())
        }
    }
}
