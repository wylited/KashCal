package org.onekash.kashcal.sync.strategy

import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.TasksDao
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.db.entity.Task
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks

/**
 * Tests [PullStrategy]'s task paths against mocked DAOs and a mocked client: the PROPFIND
 * listing, etag diff and stale sweep of [PullStrategy.pullTasks], and the VTODO ingestion of
 * [PullStrategy.processEvents]' task pass for VTODO-only and mixed VEVENT+VTODO resources.
 */
class PullStrategyTaskSyncTest {

    private lateinit var pullStrategy: PullStrategy

    @MockK
    private lateinit var database: KashCalDatabase

    @MockK
    private lateinit var client: CalDavClient

    @MockK
    private lateinit var calendarRepository: CalendarRepository

    @MockK
    private lateinit var eventsDao: EventsDao

    @MockK
    private lateinit var tasksDao: TasksDao

    @MockK
    private lateinit var dataStore: KashCalDataStore

    private val quirks = ICloudQuirks()

    /** A VTODO-capable calendar; hrefs and caldavUrls are absolute, so no URL rewrites apply. */
    private val calendar = Calendar(
        id = 1L,
        accountId = 1L,
        caldavUrl = "https://caldav.example.com/user/tasks/",
        displayName = "Tasks",
        color = -1,
        components = "VEVENT,VTODO"
    )

    private val eventOnlyCalendar = calendar.copy(components = "VEVENT")

    private fun vtodoIcs(uid: String, summary: String, priority: Int = 0) = buildString {
        appendLine("BEGIN:VCALENDAR")
        appendLine("VERSION:2.0")
        appendLine("PRODID:-//Test//EN")
        appendLine("BEGIN:VTODO")
        appendLine("UID:$uid")
        appendLine("DTSTAMP:20260101T000000Z")
        if (priority > 0) appendLine("PRIORITY:$priority")
        appendLine("SUMMARY:$summary")
        appendLine("STATUS:NEEDS_ACTION")
        appendLine("DUE:20260602T120000Z")
        appendLine("END:VTODO")
        appendLine("END:VCALENDAR")
    }

    /** A resource holding both a VEVENT and a VTODO, as mixed collections store them. */
    private val mixedIcs = buildString {
        appendLine("BEGIN:VCALENDAR")
        appendLine("VERSION:2.0")
        appendLine("PRODID:-//Test//EN")
        appendLine("BEGIN:VEVENT")
        appendLine("UID:event-1@example.com")
        appendLine("DTSTAMP:20260101T000000Z")
        appendLine("DTSTART:20260601T120000Z")
        appendLine("DTEND:20260601T130000Z")
        appendLine("SUMMARY:Mixed Event")
        appendLine("END:VEVENT")
        appendLine("BEGIN:VTODO")
        appendLine("UID:todo-1@example.com")
        appendLine("DTSTAMP:20260101T000000Z")
        appendLine("PRIORITY:5")
        appendLine("SUMMARY:Mixed Todo")
        appendLine("STATUS:NEEDS_ACTION")
        appendLine("DUE:20260602T120000Z")
        appendLine("END:VTODO")
        appendLine("END:VCALENDAR")
    }

    private fun task(
        id: Long,
        uid: String,
        caldavUrl: String?,
        etag: String? = null,
        syncStatus: SyncStatus = SyncStatus.SYNCED
    ) = Task(
        id = id,
        uid = uid,
        calendarId = 1L,
        summary = "Task $id",
        dtstamp = 1_000L,
        caldavUrl = caldavUrl,
        etag = etag,
        syncStatus = syncStatus
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this, relaxed = true)

        // runInTransaction runs the block directly.
        coEvery {
            database.runInTransaction(any<suspend () -> Any>())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = firstArg<suspend () -> Any>()
            block()
        }
        every { database.tasksDao() } returns tasksDao

        // Default: an "All" lookback, so pullFull lists from startMs = 0.
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)

        // Default: PROPFIND Depth:1 fails, so pullFull falls back to calendar-query.
        coEvery { client.fetchAllEtags(any()) } returns CalDavResult.error(501, "Not supported")

        pullStrategy = PullStrategy(
            database = database,
            calendarRepository = calendarRepository,
            eventsDao = eventsDao,
            attendeesDao = mockk(relaxed = true),
            occurrenceGenerator = mockk(relaxed = true),
            defaultQuirks = quirks,
            dataStore = dataStore,
            inviteNotifier = mockk(relaxed = true),
            accountRepository = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========== pullTasks ==========

    @Test
    fun `pullTasks fetches new and changed resources and sweeps stale rows`() = runTest {
        val knownUrl = "https://caldav.example.com/user/tasks/known.ics"
        val stale = task(id = 30L, uid = "gone-uid@example.com", caldavUrl = "https://caldav.example.com/user/tasks/gone.ics")

        coEvery { client.fetchAllEtags(calendar.caldavUrl) } returns CalDavResult.success(
            listOf(
                "https://caldav.example.com/user/tasks/new.ics" to "\"e-new\"",
                knownUrl to "\"e-known-2\""
            )
        )
        coEvery { tasksDao.getAllForCalendar(calendar.id) } returns listOf(
            task(id = 20L, uid = "known-uid@example.com", caldavUrl = knownUrl, etag = "\"e-known-1\""),
            stale
        )
        // Only the changed resources are fetched: the new one and the etag-moved known one.
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns CalDavResult.success(listOf(
            CalDavEvent(
                href = "https://caldav.example.com/user/tasks/new.ics",
                url = "https://caldav.example.com/user/tasks/new.ics",
                etag = "\"e-new\"",
                icalData = vtodoIcs("new-uid@example.com", "New Task", priority = 5)
            ),
            CalDavEvent(
                href = knownUrl,
                url = knownUrl,
                etag = "\"e-known-2\"",
                icalData = vtodoIcs("known-uid@example.com", "Known Task")
            )
        ))
        coEvery { tasksDao.getByUidAndCalendar("new-uid@example.com", calendar.id) } returns null
        coEvery {
            tasksDao.getByUidAndCalendar("known-uid@example.com", calendar.id)
        } returns task(id = 20L, uid = "known-uid@example.com", caldavUrl = knownUrl, etag = "\"e-known-1\"")

        val result = pullStrategy.pullTasks(calendar, quirks, client)

        assertEquals(1, result.added)
        assertEquals(1, result.updated)
        assertEquals(1, result.deleted)
        coVerify { tasksDao.deleteById(30L) }

        val upserted = mutableListOf<Task>()
        coVerify { tasksDao.upsert(capture(upserted)) }
        val newTask = upserted.first { it.uid == "new-uid@example.com" }
        assertEquals(5, newTask.priority)
        assertEquals("\"e-new\"", newTask.etag)
        assertEquals("https://caldav.example.com/user/tasks/new.ics", newTask.caldavUrl)
        // The changed task keeps its row id and creation time through the upsert.
        val knownTask = upserted.first { it.uid == "known-uid@example.com" }
        assertEquals(20L, knownTask.id)
        assertEquals("\"e-known-2\"", knownTask.etag)
    }

    @Test
    fun `pullTasks keeps pending and recently pushed tasks out of the sweep`() = runTest {
        val pending = task(
            id = 40L,
            uid = "pending-uid@example.com",
            caldavUrl = "https://caldav.example.com/user/tasks/pending.ics",
            syncStatus = SyncStatus.PENDING_UPDATE
        )
        val pushed = task(
            id = 41L,
            uid = "pushed-uid@example.com",
            caldavUrl = "https://caldav.example.com/user/tasks/pushed.ics"
        )
        // The server lists neither resource: both rows survive anyway.
        coEvery { client.fetchAllEtags(calendar.caldavUrl) } returns CalDavResult.success(emptyList())
        coEvery { tasksDao.getAllForCalendar(calendar.id) } returns listOf(pending, pushed)

        val result = pullStrategy.pullTasks(
            calendar, quirks, client, recentlyPushedTaskIds = setOf(41L)
        )

        assertEquals(0, result.deleted)
        coVerify(exactly = 0) { tasksDao.deleteById(any()) }
        coVerify(exactly = 0) { client.fetchEventsByHref(any(), any()) }
    }

    @Test
    fun `pullTasks ignores a calendar without VTODO support`() = runTest {
        val result = pullStrategy.pullTasks(eventOnlyCalendar, quirks, client)

        assertEquals(0, result.added)
        assertEquals(0, result.updated)
        assertEquals(0, result.deleted)
        coVerify(exactly = 0) { client.fetchAllEtags(any()) }
    }

    @Test
    fun `pullTasks warns instead of failing when the listing errors`() = runTest {
        coEvery { client.fetchAllEtags(calendar.caldavUrl) } returns
            CalDavResult.error(503, "temporarily unavailable", isRetryable = true)

        val result = pullStrategy.pullTasks(calendar, quirks, client)

        assertEquals(0, result.added)
        assertEquals(0, result.updated)
        assertEquals(0, result.deleted)
        coVerify(exactly = 0) { tasksDao.getAllForCalendar(any()) }
    }

    // ========== processEvents task pass ==========

    @Test
    fun `processEvents ingests the VTODO half of a mixed resource`() = runTest {
        val resourceUrl = "https://caldav.example.com/user/tasks/mixed.ics"

        // Drive a full pull: changed ctag, no sync token, one resource in the listing.
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(
            CalendarMetadataProbe(ctag = "ctag-new", displayName = null, color = null, isReadOnly = null)
        )
        coEvery { calendarRepository.getCalendarById(calendar.id) } returns calendar
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery {
            client.fetchEtagsInRange(calendar.caldavUrl, any(), any())
        } returns CalDavResult.success(listOf(resourceUrl to "\"e-mixed\""))
        coEvery { eventsDao.deleteDuplicateMasterEvents() } returns 0
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getEtagMapForCalendar(calendar.id, any(), any()) } returns emptyList()
        coEvery {
            client.fetchEventsByHref(calendar.caldavUrl, any())
        } returns CalDavResult.success(listOf(
            CalDavEvent(href = resourceUrl, url = resourceUrl, etag = "\"e-mixed\"", icalData = mixedIcs)
        ))
        coEvery { eventsDao.getMasterByUidAndCalendar("event-1@example.com", calendar.id) } returns null
        coEvery { eventsDao.getByCaldavUrl(resourceUrl) } returns null
        coEvery { eventsDao.getSyncStatus(any()) } returns SyncStatus.SYNCED
        coEvery { eventsDao.upsert(any()) } returns 1L
        coEvery { tasksDao.getByUidAndCalendar("todo-1@example.com", calendar.id) } returns null
        coEvery { tasksDao.getByCaldavUrl(resourceUrl) } returns null

        pullStrategy.pull(calendar, client = client)

        coVerify { eventsDao.upsert(any()) }
        val upserted = slot<Task>()
        coVerify { tasksDao.upsert(capture(upserted)) }
        assertEquals("todo-1@example.com", upserted.captured.uid)
        assertEquals(5, upserted.captured.priority)
        assertEquals("\"e-mixed\"", upserted.captured.etag)
        assertEquals(resourceUrl, upserted.captured.caldavUrl)
    }

    @Test
    fun `processEvents keeps a pending task over the server version`() = runTest {
        val resourceUrl = "https://caldav.example.com/user/tasks/mixed.ics"
        val localPending = task(
            id = 50L,
            uid = "todo-1@example.com",
            caldavUrl = resourceUrl,
            syncStatus = SyncStatus.PENDING_UPDATE
        )

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(
            CalendarMetadataProbe(ctag = "ctag-new", displayName = null, color = null, isReadOnly = null)
        )
        coEvery { calendarRepository.getCalendarById(calendar.id) } returns calendar
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery {
            client.fetchEtagsInRange(calendar.caldavUrl, any(), any())
        } returns CalDavResult.success(listOf(resourceUrl to "\"e-mixed-2\""))
        coEvery { eventsDao.deleteDuplicateMasterEvents() } returns 0
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getEtagMapForCalendar(calendar.id, any(), any()) } returns emptyList()
        coEvery {
            client.fetchEventsByHref(calendar.caldavUrl, any())
        } returns CalDavResult.success(listOf(
            CalDavEvent(href = resourceUrl, url = resourceUrl, etag = "\"e-mixed-2\"", icalData = mixedIcs)
        ))
        coEvery { eventsDao.getMasterByUidAndCalendar("event-1@example.com", calendar.id) } returns null
        coEvery { eventsDao.getByCaldavUrl(resourceUrl) } returns null
        coEvery { eventsDao.getSyncStatus(any()) } returns SyncStatus.SYNCED
        coEvery { eventsDao.upsert(any()) } returns 1L
        coEvery {
            tasksDao.getByUidAndCalendar("todo-1@example.com", calendar.id)
        } returns localPending

        pullStrategy.pull(calendar, client = client)

        coVerify { eventsDao.upsert(any()) }
        coVerify(exactly = 0) { tasksDao.upsert(any()) }
    }

    @Test
    fun `processEvents holds a recently pushed task over the server version`() = runTest {
        val resourceUrl = "https://caldav.example.com/user/tasks/mixed.ics"
        val justPushed = task(
            id = 60L,
            uid = "todo-1@example.com",
            caldavUrl = resourceUrl,
            etag = "\"e-old\""
        )

        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(
            CalendarMetadataProbe(ctag = "ctag-new", displayName = null, color = null, isReadOnly = null)
        )
        coEvery { calendarRepository.getCalendarById(calendar.id) } returns calendar
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success(null)
        coEvery {
            client.fetchEtagsInRange(calendar.caldavUrl, any(), any())
        } returns CalDavResult.success(listOf(resourceUrl to "\"e-mixed-2\""))
        coEvery { eventsDao.deleteDuplicateMasterEvents() } returns 0
        coEvery { eventsDao.getByCalendarIdInRange(calendar.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getEtagMapForCalendar(calendar.id, any(), any()) } returns emptyList()
        coEvery {
            client.fetchEventsByHref(calendar.caldavUrl, any())
        } returns CalDavResult.success(listOf(
            CalDavEvent(href = resourceUrl, url = resourceUrl, etag = "\"e-mixed-2\"", icalData = mixedIcs)
        ))
        coEvery { eventsDao.getMasterByUidAndCalendar("event-1@example.com", calendar.id) } returns null
        coEvery { eventsDao.getByCaldavUrl(resourceUrl) } returns null
        coEvery { eventsDao.getSyncStatus(any()) } returns SyncStatus.SYNCED
        coEvery { eventsDao.upsert(any()) } returns 1L
        coEvery {
            tasksDao.getByUidAndCalendar("todo-1@example.com", calendar.id)
        } returns justPushed

        // The ids the engine's task push wrote this cycle ride the event pull through to the
        // mixed-resource task pass; without them the changed etag would overwrite the local
        // version with a possibly stale read.
        pullStrategy.pull(calendar, client = client, recentlyPushedTaskIds = setOf(60L))

        // The event half lands; the VTODO half is held.
        coVerify { eventsDao.upsert(any()) }
        coVerify(exactly = 0) { tasksDao.upsert(any()) }
    }

    @Test
    fun `processEvents skips the task pass for a calendar without VTODO support`() = runTest {
        val resourceUrl = "https://caldav.example.com/user/home/mixed.ics"
        val eventOnly = eventOnlyCalendar.copy(caldavUrl = "https://caldav.example.com/user/home/")

        coEvery { client.getCtag(eventOnly.caldavUrl) } returns CalDavResult.success(
            CalendarMetadataProbe(ctag = "ctag-new", displayName = null, color = null, isReadOnly = null)
        )
        coEvery { calendarRepository.getCalendarById(eventOnly.id) } returns eventOnly
        coEvery { client.getSyncToken(eventOnly.caldavUrl) } returns CalDavResult.success(null)
        coEvery {
            client.fetchEtagsInRange(eventOnly.caldavUrl, any(), any())
        } returns CalDavResult.success(listOf(resourceUrl to "\"e-mixed\""))
        coEvery { eventsDao.deleteDuplicateMasterEvents() } returns 0
        coEvery { eventsDao.getByCalendarIdInRange(eventOnly.id, any(), any()) } returns emptyList()
        coEvery { eventsDao.getEtagMapForCalendar(eventOnly.id, any(), any()) } returns emptyList()
        coEvery {
            client.fetchEventsByHref(eventOnly.caldavUrl, any())
        } returns CalDavResult.success(listOf(
            CalDavEvent(href = resourceUrl, url = resourceUrl, etag = "\"e-mixed\"", icalData = mixedIcs)
        ))
        coEvery { eventsDao.getMasterByUidAndCalendar("event-1@example.com", eventOnly.id) } returns null
        coEvery { eventsDao.getByCaldavUrl(resourceUrl) } returns null
        coEvery { eventsDao.getSyncStatus(any()) } returns SyncStatus.SYNCED
        coEvery { eventsDao.upsert(any()) } returns 1L

        pullStrategy.pull(eventOnly, client = client)

        coVerify { eventsDao.upsert(any()) }
        coVerify(exactly = 0) { tasksDao.getByUidAndCalendar(any(), any()) }
        coVerify(exactly = 0) { tasksDao.upsert(any()) }
    }
}
