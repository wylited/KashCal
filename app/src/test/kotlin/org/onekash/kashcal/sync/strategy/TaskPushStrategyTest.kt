package org.onekash.kashcal.sync.strategy

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.clearAllMocks
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.dao.PendingTaskOperationsDao
import org.onekash.kashcal.data.db.dao.TasksDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.PendingTaskOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.db.entity.Task
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult

/**
 * Tests [TaskPushStrategy] against a mocked client and DAOs: per-operation outcomes
 * (create, update, delete, move), the CREATE/UPDATE 412 server-wins abandon, the MOVE 412
 * target-copy adoption, the DELETE 412 etag-refetch retry, the SEQUENCE bump on update,
 * retry scheduling, the local-account move edges, and the per-calendar operation filter (a
 * MOVE also drains on its source calendar).
 */
class TaskPushStrategyTest {

    private lateinit var client: CalDavClient
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var accountRepository: AccountRepository
    private lateinit var tasksDao: TasksDao
    private lateinit var pendingTaskOperationsDao: PendingTaskOperationsDao
    private lateinit var strategy: TaskPushStrategy

    private val calendar = Calendar(
        id = 1L,
        accountId = 1L,
        caldavUrl = "https://caldav.example.com/user/tasks/",
        displayName = "Tasks",
        color = -1
    )

    private val targetCalendar = calendar.copy(
        id = 9L,
        caldavUrl = "https://caldav.example.com/user/other/",
        displayName = "Other"
    )

    /** The on-device list; account id 5, resolved to [AccountProvider.LOCAL] in setup. */
    private val localCalendar = Calendar(
        id = 3L,
        accountId = 5L,
        caldavUrl = "local://on-device",
        displayName = "On this device",
        color = -1
    )

    private fun task(
        id: Long = 5L,
        calendarId: Long = 1L,
        uid: String = "task-uid@example.com",
        caldavUrl: String? = null,
        etag: String? = null,
        sequence: Int = 0
    ) = Task(
        id = id,
        uid = uid,
        calendarId = calendarId,
        summary = "Test Task",
        dtstamp = 1_000L,
        caldavUrl = caldavUrl,
        etag = etag,
        sequence = sequence
    )

    private fun operation(
        taskId: Long = 5L,
        operation: String = PendingTaskOperation.OPERATION_CREATE,
        targetUrl: String? = null,
        targetCalendarId: Long? = null,
        sourceCalendarId: Long? = null,
        retryCount: Int = 0
    ) = PendingTaskOperation(
        id = 100L + taskId,
        taskId = taskId,
        operation = operation,
        retryCount = retryCount,
        targetUrl = targetUrl,
        targetCalendarId = targetCalendarId,
        sourceCalendarId = sourceCalendarId
    )

    private fun ready(vararg operations: PendingTaskOperation) {
        coEvery { pendingTaskOperationsDao.getReadyOperations(any()) } returns operations.toList()
    }

    @Before
    fun setup() {
        client = mockk()
        calendarRepository = mockk()
        accountRepository = mockk()
        tasksDao = mockk()
        pendingTaskOperationsDao = mockk(relaxed = true)
        strategy = TaskPushStrategy(calendarRepository, accountRepository, tasksDao, pendingTaskOperationsDao)

        // The row writes behind every push path. Tests below coVerify the ones they care
        // about, so a blanket just-Runs keeps the strict mock from failing on housekeeping.
        coEvery { tasksDao.update(any()) } just runs
        coEvery { tasksDao.deleteById(any()) } just runs
        coEvery { tasksDao.markSynced(any(), any(), any()) } just runs
        coEvery { tasksDao.markSyncedWithCopy(any(), any(), any(), any()) } just runs
        coEvery { tasksDao.markCreatedOnServerWithCopy(any(), any(), any(), any(), any()) } just runs
        coEvery { tasksDao.updateEtag(any(), any()) } just runs

        // Account 1 backs the server calendars, account 5 the local one.
        coEvery { accountRepository.getAccountById(1L) } returns
            Account(provider = AccountProvider.CALDAV, email = "user@example.com")
        coEvery { accountRepository.getAccountById(5L) } returns
            Account(provider = AccountProvider.LOCAL, email = "local@device")
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ========== CREATE ==========

    @Test
    fun `create bumps sequence, uploads and marks the row created on server`() = runTest {
        ready(operation())
        coEvery { tasksDao.getByIds(any()) } returns listOf(task())
        val body = slot<String>()
        coEvery {
            client.createEvent(calendar.caldavUrl, "task-uid@example.com", capture(body))
        } returns CalDavResult.success(Pair("https://caldav.example.com/user/tasks/task-uid.ics", "\"e1\""))

        val result = strategy.pushForCalendar(calendar, client)

        assertEquals(1, result.created)
        assertEquals(setOf(5L), result.pushedTaskIds)
        assertTrue(body.captured.contains("BEGIN:VTODO"))
        coVerify { tasksDao.update(match { it.id == 5L && it.sequence == 1 }) }
        coVerify {
            tasksDao.markCreatedOnServerWithCopy(
                5L, "https://caldav.example.com/user/tasks/task-uid.ics", "\"e1\"", any(), any()
            )
        }
        coVerify { pendingTaskOperationsDao.deleteById(105L) }
    }

    @Test
    fun `create conflict abandons the op and returns the row to the server`() = runTest {
        ready(operation())
        coEvery { tasksDao.getByIds(any()) } returns listOf(task())
        coEvery { client.createEvent(any(), any(), any()) } returns CalDavResult.conflictError("Conflict")

        val result = strategy.pushForCalendar(calendar, client)

        assertEquals(1, result.failed)
        assertEquals(1, result.warnings.size)
        coVerify { pendingTaskOperationsDao.markAbandoned(105L, any(), any()) }
        coVerify { tasksDao.markSynced(5L, null, any()) }
    }

    @Test
    fun `retryable create failure reschedules within the retry budget`() = runTest {
        ready(operation(retryCount = 0))
        coEvery { tasksDao.getByIds(any()) } returns listOf(task())
        coEvery { client.createEvent(any(), any(), any()) } returns
            CalDavResult.error(500, "boom", isRetryable = true)

        val result = strategy.pushForCalendar(calendar, client)

        assertEquals(1, result.failed)
        assertEquals(1, result.warnings.size)
        coVerify { pendingTaskOperationsDao.scheduleRetry(105L, any(), "boom", any()) }
        coVerify(exactly = 0) { pendingTaskOperationsDao.markFailed(any(), any(), any()) }
    }

    @Test
    fun `exhausted create failure is marked failed without rescheduling`() = runTest {
        ready(operation(retryCount = 10))
        coEvery { tasksDao.getByIds(any()) } returns listOf(task())
        coEvery { client.createEvent(any(), any(), any()) } returns
            CalDavResult.error(500, "boom", isRetryable = true)

        val result = strategy.pushForCalendar(calendar, client)

        assertEquals(1, result.failed)
        coVerify(exactly = 0) { pendingTaskOperationsDao.scheduleRetry(any(), any(), any(), any()) }
        coVerify { pendingTaskOperationsDao.markFailed(105L, "boom", any()) }
    }

    // ========== UPDATE ==========

    @Test
    fun `update sends If-Match and marks the row synced with the sent body`() = runTest {
        ready(operation(operation = PendingTaskOperation.OPERATION_UPDATE))
        coEvery { tasksDao.getByIds(any()) } returns
            listOf(task(caldavUrl = "https://caldav.example.com/user/tasks/task-uid.ics", etag = "\"e1\""))
        val body = slot<String>()
        coEvery {
            client.updateEvent(
                "https://caldav.example.com/user/tasks/task-uid.ics",
                capture(body),
                "\"e1\""
            )
        } returns CalDavResult.success("\"e2\"")

        val result = strategy.pushForCalendar(calendar, client)

        assertEquals(1, result.updated)
        assertEquals(setOf(5L), result.pushedTaskIds)
        assertTrue(body.captured.contains("BEGIN:VTODO"))
        coVerify {
            tasksDao.markSyncedWithCopy(5L, "\"e2\"", body.captured, any())
        }
    }

    @Test
    fun `update recovers a missing etag via PROPFIND before the PUT`() = runTest {
        ready(operation(operation = PendingTaskOperation.OPERATION_UPDATE))
        coEvery { tasksDao.getByIds(any()) } returns
            listOf(task(caldavUrl = "https://caldav.example.com/user/tasks/task-uid.ics", etag = null))
        coEvery { client.fetchEtag("https://caldav.example.com/user/tasks/task-uid.ics") } returns
            CalDavResult.success("\"fresh\"")
        coEvery {
            client.updateEvent("https://caldav.example.com/user/tasks/task-uid.ics", any(), "\"fresh\"")
        } returns CalDavResult.success("\"e2\"")

        val result = strategy.pushForCalendar(calendar, client)

        assertEquals(1, result.updated)
        coVerify { tasksDao.updateEtag(5L, "\"fresh\"") }
        coVerify { tasksDao.markSyncedWithCopy(5L, "\"e2\"", any(), any()) }
    }

    @Test
    fun `update of a task without a server URL re-runs as CREATE`() = runTest {
        ready(operation(operation = PendingTaskOperation.OPERATION_UPDATE))
        coEvery { tasksDao.getByIds(any()) } returns listOf(task())
        coEvery {
            client.createEvent(calendar.caldavUrl, "task-uid@example.com", any())
        } returns CalDavResult.success(Pair("https://caldav.example.com/user/tasks/task-uid.ics", "\"e1\""))

        val result = strategy.pushForCalendar(calendar, client)

        assertEquals(1, result.created)
        coVerify(exactly = 0) { client.updateEvent(any(), any(), any()) }
        coVerify {
            tasksDao.markCreatedOnServerWithCopy(
                5L, "https://caldav.example.com/user/tasks/task-uid.ics", "\"e1\"", any(), any()
            )
        }
    }

    @Test
    fun `update bumps SEQUENCE once per send`() = runTest {
        ready(operation(operation = PendingTaskOperation.OPERATION_UPDATE))
        coEvery { tasksDao.getByIds(any()) } returns
            listOf(
                task(
                    caldavUrl = "https://caldav.example.com/user/tasks/task-uid.ics",
                    etag = "\"e1\"",
                    sequence = 3
                )
            )
        coEvery {
            client.updateEvent("https://caldav.example.com/user/tasks/task-uid.ics", any(), "\"e1\"")
        } returns CalDavResult.success("\"e2\"")

        val result = strategy.pushForCalendar(calendar, client)

        assertEquals(1, result.updated)
        // Exactly like processCreate: the bump is persisted before the PUT, once per send.
        coVerify(exactly = 1) { tasksDao.update(match { it.id == 5L && it.sequence == 4 }) }
        coVerify { tasksDao.markSyncedWithCopy(5L, "\"e2\"", any(), any()) }
    }

    // ========== DELETE ==========

    @Test
    fun `delete removes the server resource and the local row`() = runTest {
        ready(operation(operation = PendingTaskOperation.OPERATION_DELETE))
        coEvery { tasksDao.getByIds(any()) } returns
            listOf(task(caldavUrl = "https://caldav.example.com/user/tasks/task-uid.ics", etag = "\"e1\""))
        coEvery {
            client.deleteEvent("https://caldav.example.com/user/tasks/task-uid.ics", "\"e1\"")
        } returns CalDavResult.success(Unit)

        val result = strategy.pushForCalendar(calendar, client)

        assertEquals(1, result.deleted)
        coVerify { tasksDao.deleteById(5L) }
        coVerify { pendingTaskOperationsDao.deleteById(105L) }
    }

    @Test
    fun `delete 404 counts as deleted and tears the local row down`() = runTest {
        ready(
            operation(
                operation = PendingTaskOperation.OPERATION_DELETE,
                targetUrl = "https://caldav.example.com/user/tasks/task-uid.ics"
            )
        )
        coEvery { tasksDao.getByIds(any()) } returns listOf(task())
        coEvery { client.deleteEvent(any(), any()) } returns CalDavResult.notFoundError("Not Found")

        val result = strategy.pushForCalendar(calendar, client)

        assertEquals(1, result.deleted)
        coVerify { tasksDao.deleteById(5L) }
    }

    @Test
    fun `delete of a task that was never on the server only deletes locally`() = runTest {
        ready(operation(operation = PendingTaskOperation.OPERATION_DELETE))
        coEvery { tasksDao.getByIds(any()) } returns listOf(task())

        val result = strategy.pushForCalendar(calendar, client)

        assertEquals(1, result.deleted)
        coVerify(exactly = 0) { client.deleteEvent(any(), any()) }
        coVerify { tasksDao.deleteById(5L) }
    }

    @Test
    fun `delete 412 refetches the etag and retries once`() = runTest {
        val url = "https://caldav.example.com/user/tasks/task-uid.ics"
        ready(
            operation(
                operation = PendingTaskOperation.OPERATION_DELETE,
                targetUrl = url
            )
        )
        coEvery { tasksDao.getByIds(any()) } returns listOf(task(caldavUrl = url, etag = "\"stale\""))
        coEvery { client.deleteEvent(url, "\"stale\"") } returns CalDavResult.conflictError("Conflict")
        coEvery { client.fetchEtag(url) } returns CalDavResult.success("\"fresh\"")
        coEvery { client.deleteEvent(url, "\"fresh\"") } returns CalDavResult.success(Unit)

        val result = strategy.pushForCalendar(calendar, client)

        // The retry deletes the resource and tears the local row down; the op drains.
        assertEquals(1, result.deleted)
        assertEquals(0, result.failed)
        coVerify { client.deleteEvent(url, "\"fresh\"") }
        coVerify { tasksDao.deleteById(5L) }
        coVerify { pendingTaskOperationsDao.deleteById(105L) }
    }

    @Test
    fun `a second 412 on delete abandons server-wins`() = runTest {
        val url = "https://caldav.example.com/user/tasks/task-uid.ics"
        ready(
            operation(
                operation = PendingTaskOperation.OPERATION_DELETE,
                targetUrl = url
            )
        )
        coEvery { tasksDao.getByIds(any()) } returns listOf(task(caldavUrl = url, etag = "\"stale\""))
        coEvery { client.deleteEvent(url, "\"stale\"") } returns CalDavResult.conflictError("Conflict")
        coEvery { client.fetchEtag(url) } returns CalDavResult.success("\"fresh\"")
        coEvery { client.deleteEvent(url, "\"fresh\"") } returns CalDavResult.conflictError("Conflict")

        val result = strategy.pushForCalendar(calendar, client)

        // Drifted again: no loop, the row returns to the server's version on the next pull.
        assertEquals(1, result.failed)
        coVerify { pendingTaskOperationsDao.markAbandoned(105L, any(), any()) }
        coVerify { tasksDao.markSynced(5L, null, any()) }
        coVerify(exactly = 0) { tasksDao.deleteById(any()) }
    }

    @Test
    fun `delete 412 with a refetch that finds the resource gone deletes locally`() = runTest {
        val url = "https://caldav.example.com/user/tasks/task-uid.ics"
        ready(
            operation(
                operation = PendingTaskOperation.OPERATION_DELETE,
                targetUrl = url
            )
        )
        coEvery { tasksDao.getByIds(any()) } returns listOf(task(caldavUrl = url, etag = "\"stale\""))
        coEvery { client.deleteEvent(url, "\"stale\"") } returns CalDavResult.conflictError("Conflict")
        coEvery { client.fetchEtag(url) } returns CalDavResult.notFoundError("Not Found")

        val result = strategy.pushForCalendar(calendar, client)

        assertEquals(1, result.deleted)
        coVerify { tasksDao.deleteById(5L) }
        coVerify(exactly = 1) { client.deleteEvent(any(), any()) }
    }

    // ========== MOVE ==========

    @Test
    fun `move creates in the target, deletes the source and rewrites the row URL`() = runTest {
        val sourceUrl = "https://caldav.example.com/user/tasks/task-uid.ics"
        val targetUrl = "https://caldav.example.com/user/other/task-uid.ics"
        ready(
            operation(
                operation = PendingTaskOperation.OPERATION_MOVE,
                targetUrl = sourceUrl,
                targetCalendarId = 9L
            )
        )
        coEvery { tasksDao.getByIds(any()) } returns listOf(task(caldavUrl = sourceUrl, etag = "\"e1\""))
        coEvery { calendarRepository.getCalendarById(9L) } returns targetCalendar
        coEvery { client.createEvent(targetCalendar.caldavUrl, "task-uid@example.com", any()) } returns
            CalDavResult.success(Pair(targetUrl, "\"e2\""))
        coEvery { client.deleteEvent(sourceUrl, "\"e1\"") } returns CalDavResult.success(Unit)

        // The op names its target, so the target calendar's run drains it; the source run
        // pulls a MOVE only through source_calendar_id.
        val result = strategy.pushForCalendar(targetCalendar, client)

        assertEquals(1, result.created)
        assertEquals(1, result.deleted)
        coVerify { tasksDao.markCreatedOnServerWithCopy(5L, targetUrl, "\"e2\"", any(), any()) }
        coVerify { pendingTaskOperationsDao.deleteById(105L) }
    }

    @Test
    fun `move create 412 adopts the existing target copy and finishes the move`() = runTest {
        val sourceUrl = "https://caldav.example.com/user/tasks/task-uid.ics"
        val targetUrl = "https://caldav.example.com/user/other/task-uid@example.com.ics"
        ready(
            operation(
                operation = PendingTaskOperation.OPERATION_MOVE,
                targetUrl = sourceUrl,
                targetCalendarId = 9L
            )
        )
        coEvery { tasksDao.getByIds(any()) } returns listOf(task(caldavUrl = sourceUrl, etag = "\"e1\""))
        coEvery { calendarRepository.getCalendarById(9L) } returns targetCalendar
        // A retry after a partial first attempt: the target copy already exists, so the
        // If-None-Match PUT 412s.
        coEvery { client.createEvent(targetCalendar.caldavUrl, "task-uid@example.com", any()) } returns
            CalDavResult.conflictError("Precondition failed")
        coEvery { client.fetchEtag(targetUrl) } returns CalDavResult.success("\"e2\"")
        coEvery { client.deleteEvent(sourceUrl, "\"e1\"") } returns CalDavResult.success(Unit)

        // The op names its target, so the target calendar's run drains it.
        val result = strategy.pushForCalendar(targetCalendar, client)

        // The existing copy is adopted (fetched etag, client-built URL) and the source delete
        // completes the move instead of abandoning and stranding the copy as a duplicate.
        assertEquals(1, result.created)
        assertEquals(1, result.deleted)
        assertEquals(0, result.failed)
        coVerify { client.fetchEtag(targetUrl) }
        coVerify {
            tasksDao.markCreatedOnServerWithCopy(5L, targetUrl, "\"e2\"", any(), any())
        }
        coVerify { pendingTaskOperationsDao.deleteById(105L) }
        coVerify(exactly = 0) { pendingTaskOperationsDao.markAbandoned(any(), any(), any()) }
        coVerify(exactly = 0) { tasksDao.markSynced(any(), any(), any()) }
    }

    @Test
    fun `move conflict restores the source calendar so the source pull heals the row`() = runTest {
        val sourceUrl = "https://caldav.example.com/user/tasks/task-uid.ics"
        ready(
            operation(
                operation = PendingTaskOperation.OPERATION_MOVE,
                targetUrl = sourceUrl,
                targetCalendarId = 9L,
                sourceCalendarId = 1L
            )
        )
        // Mid-move state: the row is parked on the target calendar at the source URL.
        coEvery { tasksDao.getByIds(any()) } returns
            listOf(task(calendarId = 9L, caldavUrl = sourceUrl, etag = "\"e1\""))
        coEvery { calendarRepository.getCalendarById(9L) } returns targetCalendar
        coEvery { calendarRepository.getCalendarById(1L) } returns calendar
        // 409 is a first-attempt conflict (no copy to adopt, unlike a 412), so the op is
        // abandoned server-wins and the row is re-parked on the source calendar.
        coEvery { client.createEvent(targetCalendar.caldavUrl, any(), any()) } returns
            CalDavResult.error(409, "Conflict")

        // The op names its target, so the target calendar's run drains it.
        val result = strategy.pushForCalendar(targetCalendar, client)

        assertEquals(1, result.failed)
        coVerify { pendingTaskOperationsDao.markAbandoned(105L, any(), any()) }
        val updates = mutableListOf<Task>()
        coVerify(atLeast = 1) { tasksDao.update(capture(updates)) }
        // The row returns to the source calendar at the source URL, SYNCED with no etag, so
        // the source pull's UID/URL match applies the server copy instead of double-ingesting;
        // the SEQUENCE bump that never reached the server is undone too.
        assertTrue(
            updates.any {
                it.calendarId == 1L && it.caldavUrl == sourceUrl &&
                    it.syncStatus == SyncStatus.SYNCED && it.etag == null && it.sequence == 0
            }
        )
        // The pre-push SEQUENCE bump writes calendar 9 first; the restore must be the last word.
        assertEquals(1L, updates.last().calendarId)
        coVerify(exactly = 0) { tasksDao.markSynced(any(), any(), any()) }
    }

    @Test
    fun `a MOVE drains on its source calendar's push`() = runTest {
        val sourceUrl = "https://caldav.example.com/user/tasks/task-uid.ics"
        ready(
            operation(
                operation = PendingTaskOperation.OPERATION_MOVE,
                targetUrl = sourceUrl,
                targetCalendarId = 9L,
                sourceCalendarId = 1L
            )
        )
        coEvery { tasksDao.getByIds(any()) } returns listOf(task(caldavUrl = sourceUrl, etag = "\"e1\""))
        coEvery { calendarRepository.getCalendarById(9L) } returns targetCalendar
        coEvery { calendarRepository.getCalendarById(1L) } returns calendar
        coEvery { client.createEvent(targetCalendar.caldavUrl, "task-uid@example.com", any()) } returns
            CalDavResult.success(Pair("https://caldav.example.com/user/other/task-uid.ics", "\"e2\""))
        coEvery { client.deleteEvent(sourceUrl, "\"e1\"") } returns CalDavResult.success(Unit)

        // The row already names the target calendar, so only source_calendar_id pulls this
        // operation into the source calendar's run — the push still resolves the whole MOVE.
        val result = strategy.pushForCalendar(calendar, client)

        assertEquals(1, result.created)
        assertEquals(1, result.deleted)
        coVerify { client.createEvent(targetCalendar.caldavUrl, any(), any()) }
        coVerify { client.deleteEvent(sourceUrl, "\"e1\"") }
        coVerify { pendingTaskOperationsDao.deleteById(105L) }
    }

    @Test
    fun `a MOVE owned by neither target nor source is not pushed`() = runTest {
        ready(
            operation(
                operation = PendingTaskOperation.OPERATION_MOVE,
                targetCalendarId = 9L,
                sourceCalendarId = 9L
            )
        )
        coEvery { tasksDao.getByIds(any()) } returns listOf(task(calendarId = 2L))

        val result = strategy.pushForCalendar(calendar, client)

        assertEquals(0, result.created)
        assertEquals(0, result.failed)
        coVerify(exactly = 0) { client.createEvent(any(), any(), any()) }
        coVerify(exactly = 0) { pendingTaskOperationsDao.markInProgress(any(), any()) }
    }

    @Test
    fun `a MOVE into the local account deletes the source and keeps the local row`() = runTest {
        val sourceUrl = "https://caldav.example.com/user/tasks/task-uid.ics"
        ready(
            operation(
                operation = PendingTaskOperation.OPERATION_MOVE,
                targetUrl = sourceUrl,
                targetCalendarId = 3L,
                sourceCalendarId = 1L
            )
        )
        coEvery { tasksDao.getByIds(any()) } returns listOf(task(caldavUrl = sourceUrl, etag = "\"e1\""))
        coEvery { calendarRepository.getCalendarById(3L) } returns localCalendar
        coEvery { calendarRepository.getCalendarById(1L) } returns calendar
        coEvery { client.deleteEvent(sourceUrl, "\"e1\"") } returns CalDavResult.success(Unit)

        // Drained by the source calendar's run: the target is the local account, which never
        // syncs and would otherwise strand the operation forever.
        val result = strategy.pushForCalendar(calendar, client)

        // Copy-then-delete from the server's perspective: no PUT anywhere, the source
        // resource goes, and the local row is the surviving copy without a server URL/etag.
        assertEquals(0, result.created)
        assertEquals(1, result.deleted)
        coVerify(exactly = 0) { client.createEvent(any(), any(), any()) }
        coVerify { client.deleteEvent(sourceUrl, "\"e1\"") }
        coVerify {
            tasksDao.update(
                match {
                    it.id == 5L && it.caldavUrl == null && it.etag == null &&
                        it.syncStatus == SyncStatus.SYNCED
                }
            )
        }
        coVerify(exactly = 0) { tasksDao.markCreatedOnServerWithCopy(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a MOVE out of the local account only creates the target copy`() = runTest {
        ready(
            operation(
                operation = PendingTaskOperation.OPERATION_MOVE,
                targetCalendarId = 9L,
                sourceCalendarId = 3L
            )
        )
        coEvery { tasksDao.getByIds(any()) } returns listOf(task(calendarId = 3L))
        coEvery { calendarRepository.getCalendarById(9L) } returns targetCalendar
        coEvery { calendarRepository.getCalendarById(3L) } returns localCalendar
        coEvery { client.createEvent(targetCalendar.caldavUrl, "task-uid@example.com", any()) } returns
            CalDavResult.success(Pair("https://caldav.example.com/user/other/task-uid.ics", "\"e2\""))

        // Drained by the target calendar's run; the local source has no server resource.
        val result = strategy.pushForCalendar(targetCalendar, client)

        assertEquals(1, result.created)
        assertEquals(0, result.deleted)
        coVerify { client.createEvent(targetCalendar.caldavUrl, any(), any()) }
        coVerify(exactly = 0) { client.deleteEvent(any(), any()) }
        coVerify {
            tasksDao.markCreatedOnServerWithCopy(
                5L, "https://caldav.example.com/user/other/task-uid.ics", "\"e2\"", any(), any()
            )
        }
    }

    @Test
    fun `update pushes the stored body patched, keeping unmodeled properties and cohabitants`() = runTest {
        val url = "https://caldav.example.com/user/tasks/task-uid.ics"
        val rawIcal = listOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Other//Client//EN",
            "BEGIN:VEVENT",
            "UID:event-1@example.com",
            "DTSTAMP:20260101T000000Z",
            "DTSTART:20260601T120000Z",
            "DTEND:20260601T130000Z",
            "SUMMARY:Cohabiting event",
            "END:VEVENT",
            "BEGIN:VTODO",
            "UID:task-uid@example.com",
            "DTSTAMP:20260101T000000Z",
            "SUMMARY:Test Task",
            "STATUS:NEEDS-ACTION",
            "CATEGORIES:work",
            "ATTENDEE;CN=Alice:mailto:alice@example.com",
            "BEGIN:VALARM",
            "ACTION:DISPLAY",
            "TRIGGER:-PT30M",
            "END:VALARM",
            "END:VTODO",
            "END:VCALENDAR"
        ).joinToString("\r\n")
        ready(operation(operation = PendingTaskOperation.OPERATION_UPDATE))
        coEvery { tasksDao.getByIds(any()) } returns listOf(
            task(caldavUrl = url, etag = "\"e1\"")
                .copy(rawIcal = rawIcal, summary = "Edited locally")
        )
        val body = slot<String>()
        coEvery { client.updateEvent(url, capture(body), "\"e1\"") } returns
            CalDavResult.success("\"e2\"")

        val result = strategy.pushForCalendar(calendar, client)

        assertEquals(1, result.updated)
        // The edit landed...
        assertTrue(body.captured.contains("SUMMARY:Edited locally"))
        assertTrue(!body.captured.contains("SUMMARY:Test Task"))
        // ...and the PUT body still carries everything else the resource holds.
        assertTrue(body.captured.contains("BEGIN:VEVENT"))
        assertTrue(body.captured.contains("SUMMARY:Cohabiting event"))
        assertTrue(body.captured.contains("CATEGORIES:work"))
        assertTrue(body.captured.contains("ATTENDEE;CN=Alice:mailto:alice@example.com"))
        assertTrue(body.captured.contains("BEGIN:VALARM"))
        assertTrue(body.captured.contains("TRIGGER:-PT30M"))
        // The stored body the write sent becomes the next merge base.
        coVerify { tasksDao.markSyncedWithCopy(5L, "\"e2\"", body.captured, any()) }
    }

    @Test
    fun `move adopt 404 falls through to a plain re-PUT and finishes the move`() = runTest {
        val sourceUrl = "https://caldav.example.com/user/tasks/task-uid@example.com.ics"
        val targetUrl = "https://caldav.example.com/user/other/task-uid@example.com.ics"
        ready(
            operation(
                operation = PendingTaskOperation.OPERATION_MOVE,
                targetUrl = sourceUrl,
                targetCalendarId = 9L
            )
        )
        coEvery { tasksDao.getByIds(any()) } returns listOf(task(caldavUrl = sourceUrl, etag = "\"e1\""))
        coEvery { calendarRepository.getCalendarById(9L) } returns targetCalendar
        // First PUT 412s as after a partial attempt, but the copy the etag fetch would adopt
        // is gone again by the time the PROPFIND runs — previously a permanent failure.
        coEvery { client.createEvent(targetCalendar.caldavUrl, "task-uid@example.com", any()) } returnsMany listOf(
            CalDavResult.conflictError("Precondition failed"),
            CalDavResult.success(Pair(targetUrl, "\"e9\""))
        )
        coEvery { client.fetchEtag(targetUrl) } returns CalDavResult.notFoundError("Not Found")
        coEvery { client.deleteEvent(sourceUrl, "\"e1\"") } returns CalDavResult.success(Unit)

        val result = strategy.pushForCalendar(targetCalendar, client)

        assertEquals(1, result.created)
        assertEquals(1, result.deleted)
        assertEquals(0, result.failed)
        coVerify(exactly = 2) { client.createEvent(any(), any(), any()) }
        coVerify { client.deleteEvent(sourceUrl, "\"e1\"") }
        coVerify { tasksDao.markCreatedOnServerWithCopy(5L, targetUrl, "\"e9\"", any(), any()) }
        coVerify { pendingTaskOperationsDao.deleteById(105L) }
        coVerify(exactly = 0) { pendingTaskOperationsDao.markFailed(any(), any(), any()) }
    }

    @Test
    fun `push warnings carry no task summary, URL or etag`() = runTest {
        ready(operation())
        coEvery { tasksDao.getByIds(any()) } returns listOf(
            task(
                caldavUrl = "https://caldav.example.com/user/tasks/task-uid.ics",
                etag = "\"secret-etag\""
            )
        )
        coEvery { client.createEvent(any(), any(), any()) } returns
            CalDavResult.error(500, "boom", isRetryable = true)

        val result = strategy.pushForCalendar(calendar, client)

        val warning = result.warnings.single()
        assertTrue(warning.contains("task-uid.ics"))
        assertTrue(!warning.contains("Test Task"))
        assertTrue(!warning.contains("https://"))
        assertTrue(!warning.contains("secret-etag"))
    }

    // ========== Calendar filter ==========

    @Test
    fun `operations of other calendars are not pushed`() = runTest {
        ready(operation(taskId = 7L))
        coEvery { tasksDao.getByIds(any()) } returns listOf(task(id = 7L, calendarId = 2L))

        val result = strategy.pushForCalendar(calendar, client)

        assertEquals(0, result.created)
        assertEquals(0, result.failed)
        coVerify(exactly = 0) { client.createEvent(any(), any(), any()) }
        coVerify(exactly = 0) { pendingTaskOperationsDao.markInProgress(any(), any()) }
    }

    @Test
    fun `an operation whose task row is gone is filtered out untouched`() = runTest {
        ready(operation())
        coEvery { tasksDao.getByIds(any()) } returns emptyList()

        val result = strategy.pushForCalendar(calendar, client)

        assertEquals(0, result.created)
        assertEquals(0, result.failed)
        coVerify(exactly = 0) { client.createEvent(any(), any(), any()) }
        // The per-calendar filter can't place an op without its row, so the loop never runs;
        // the op lingers exactly like an event op whose event vanished.
        coVerify(exactly = 0) { pendingTaskOperationsDao.markInProgress(any(), any()) }
        coVerify(exactly = 0) { pendingTaskOperationsDao.deleteById(any()) }
    }
}
