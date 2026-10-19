package org.onekash.kashcal.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.db.entity.Task
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [TasksDao]'s visibility-domain and range queries over an in-memory Room database:
 * the schedule/agenda split ([Calendar.isVisible] vs [Calendar.isAgendaVisible]) and the
 * open/all-status and date-range filters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TasksDaoTest {

    private lateinit var database: KashCalDatabase
    private lateinit var tasksDao: TasksDao
    private var scheduleOnlyCalendarId: Long = 0
    private var agendaOnlyCalendarId: Long = 0

    /** 2026-06-01T12:00:00Z, with a day's width around it. */
    private val rangeStart = 1_780_308_000_000L
    private val rangeEnd = 1_781_000_000_000L

    private fun task(
        calendarId: Long,
        uid: String,
        summary: String,
        dueTs: Long? = rangeStart,
        status: String = Task.STATUS_NEEDS_ACTION,
        syncStatus: SyncStatus = SyncStatus.SYNCED
    ) = Task(
        uid = uid,
        calendarId = calendarId,
        summary = summary,
        status = status,
        dueTs = dueTs,
        dtstamp = 1_000L,
        syncStatus = syncStatus
    )

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        tasksDao = database.tasksDao()

        runTest {
            val accountId = database.accountsDao().insert(
                Account(provider = AccountProvider.CALDAV, email = "test@example.com")
            )
            scheduleOnlyCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = accountId,
                    caldavUrl = "https://caldav.example.com/home/",
                    displayName = "Schedule only",
                    color = -1,
                    isVisible = true,
                    isAgendaVisible = false
                )
            )
            agendaOnlyCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = accountId,
                    caldavUrl = "https://caldav.example.com/agenda/",
                    displayName = "Agenda only",
                    color = -1,
                    isVisible = false,
                    isAgendaVisible = true
                )
            )
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    // ========== Visibility domains ==========

    @Test
    fun `observeAllScheduleVisible returns only schedule-visible calendars`() = runTest {
        tasksDao.insert(task(scheduleOnlyCalendarId, "uid-1", "On schedule"))
        tasksDao.insert(task(agendaOnlyCalendarId, "uid-2", "Agenda only"))
        tasksDao.insert(
            task(
                scheduleOnlyCalendarId, "uid-3", "Deleted on schedule",
                syncStatus = SyncStatus.PENDING_DELETE
            )
        )

        val schedule = tasksDao.observeAllScheduleVisible().first()

        assertEquals(listOf("On schedule"), schedule.map { it.summary })
    }

    @Test
    fun `observeAllAgendaVisible returns only agenda-visible calendars`() = runTest {
        tasksDao.insert(task(scheduleOnlyCalendarId, "uid-1", "On schedule"))
        tasksDao.insert(task(agendaOnlyCalendarId, "uid-2", "Agenda only"))
        tasksDao.insert(
            task(
                agendaOnlyCalendarId, "uid-3", "Deleted on agenda",
                syncStatus = SyncStatus.PENDING_DELETE
            )
        )

        val agenda = tasksDao.observeAllAgendaVisible().first()

        assertEquals(listOf("Agenda only"), agenda.map { it.summary })
    }

    @Test
    fun `getOpenTasksBetween applies the domain split and drops closed rows`() = runTest {
        tasksDao.insert(task(scheduleOnlyCalendarId, "uid-1", "Open on schedule"))
        tasksDao.insert(task(agendaOnlyCalendarId, "uid-2", "Open on agenda"))
        tasksDao.insert(
            task(
                scheduleOnlyCalendarId, "uid-3", "Done on schedule",
                status = Task.STATUS_COMPLETED
            )
        )
        tasksDao.insert(
            task(
                agendaOnlyCalendarId, "uid-4", "Deleted on agenda",
                syncStatus = SyncStatus.PENDING_DELETE
            )
        )

        val schedule = tasksDao.getOpenTasksBetween(rangeStart, rangeEnd)
        val agenda = tasksDao.getOpenTasksBetweenAgenda(rangeStart, rangeEnd)

        assertEquals(listOf("Open on schedule"), schedule.map { it.summary })
        assertEquals(listOf("Open on agenda"), agenda.map { it.summary })
    }

    @Test
    fun `getTasksBetween returns every status in range for the asked domain`() = runTest {
        tasksDao.insert(task(scheduleOnlyCalendarId, "uid-1", "Open"))
        tasksDao.insert(
            task(
                scheduleOnlyCalendarId, "uid-2", "Done",
                status = Task.STATUS_COMPLETED
            )
        )
        tasksDao.insert(task(agendaOnlyCalendarId, "uid-3", "Agenda task"))
        // Outside the range: never returned.
        tasksDao.insert(task(scheduleOnlyCalendarId, "uid-4", "Undated", dueTs = null))

        val schedule = tasksDao.getTasksBetween(rangeStart, rangeEnd)
        val agenda = tasksDao.getTasksBetweenAgenda(rangeStart, rangeEnd)

        assertEquals(listOf("Open", "Done"), schedule.map { it.summary })
        assertEquals(listOf("Agenda task"), agenda.map { it.summary })
    }

    // ========== Batch and per-calendar reads ==========

    @Test
    fun `getByIds returns the requested rows and skips unknown ids`() = runTest {
        val first = tasksDao.insert(task(scheduleOnlyCalendarId, "uid-1", "First"))
        tasksDao.insert(task(scheduleOnlyCalendarId, "uid-2", "Second"))

        val loaded = tasksDao.getByIds(listOf(first, 9_999L))

        assertEquals(listOf("First"), loaded.map { it.summary })
    }

    @Test
    fun `getAllForCalendar returns every row of one calendar only`() = runTest {
        tasksDao.insert(task(scheduleOnlyCalendarId, "uid-1", "Mine"))
        tasksDao.insert(task(agendaOnlyCalendarId, "uid-2", "Theirs"))

        val loaded = tasksDao.getAllForCalendar(scheduleOnlyCalendarId)

        assertEquals(listOf("Mine"), loaded.map { it.summary })
    }
}