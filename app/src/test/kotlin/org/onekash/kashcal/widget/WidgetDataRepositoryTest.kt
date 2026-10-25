package org.onekash.kashcal.widget

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.db.entity.Task
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.model.VisibilityDomain
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.domain.reader.TaskReader
import org.onekash.kashcal.util.DateTimeUtils
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tests [WidgetDataRepository] over a mocked [DisplayEventRepository] returning Room events.
 *
 * Covers:
 * - empty results from getTodayEvents and getEventsInRange
 * - sorting all-day first, then by start time, in each of the three methods
 * - past detection, including an all-day event today that isn't past
 * - the cancelled flag, with cancelled events kept, not dropped
 * - DisplayEvent to WidgetEvent mapping, and the default color when there is no calendar color
 * - getWeekEvents returning 7 entries, all empty with no events
 * - getEventsInRange keyed by day across two days
 * - getOpenTasks: open-only, PENDING_DELETE excluded, when-ascending with undated last, limited
 * - getTodayTasks: overdue kept, future and undated dropped
 */
class WidgetDataRepositoryTest {

    private lateinit var displayEventRepository: DisplayEventRepository
    private lateinit var taskReader: TaskReader
    private lateinit var repository: WidgetDataRepository

    private val testCalendar = Calendar(
        id = 1L,
        accountId = 1L,
        caldavUrl = "https://test.com/cal/",
        displayName = "Test Calendar",
        color = 0xFF2196F3.toInt(),
        isVisible = true
    )

    @Before
    fun setup() {
        displayEventRepository = mockk(relaxed = true)
        taskReader = mockk(relaxed = true)
        val mockContext: android.content.Context = mockk(relaxed = true)
        repository = WidgetDataRepository(mockContext, displayEventRepository, taskReader)
    }

    // ========== Empty State Tests ==========

    @Test
    fun `getTodayEvents returns empty list when no events exist`() = runTest {
        coEvery { displayEventRepository.getDisplayEventsGroupedByDayOnce(any(), any()) } returns emptyMap()

        val events = repository.getTodayEvents()
        assertTrue(events.isEmpty())
    }

    // ========== Sorting Tests ==========

    @Test
    fun `getTodayEvents sorts timed events by start time`() = runTest {
        val today = LocalDate.now()
        val todayCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
        val zone = ZoneId.systemDefault()

        val time1 = today.atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
        val time2 = today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val time3 = today.atTime(11, 30).atZone(zone).toInstant().toEpochMilli()

        val events = listOf(
            createDisplayEvent(1L, "Afternoon Meeting", time1, time1 + 3600000, todayCode),
            createDisplayEvent(2L, "Morning Standup", time2, time2 + 1800000, todayCode),
            createDisplayEvent(3L, "Lunch", time3, time3 + 3600000, todayCode)
        )

        coEvery { displayEventRepository.getDisplayEventsGroupedByDayOnce(any(), any()) } returns
            mapOf(todayCode to events)

        val result = repository.getTodayEvents()
        assertEquals(3, result.size)
        assertEquals("Morning Standup", result[0].title)
        assertEquals("Lunch", result[1].title)
        assertEquals("Afternoon Meeting", result[2].title)
    }

    @Test
    fun `getTodayEvents sorts all-day events before timed events`() = runTest {
        val today = LocalDate.now()
        val todayCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
        val zone = ZoneId.systemDefault()

        val timedStart = today.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val timedEnd = timedStart + 3600000

        val allDayStart = today.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val allDayEnd = allDayStart + 86400000 - 1

        val events = listOf(
            createDisplayEvent(1L, "Timed Event", timedStart, timedEnd, todayCode),
            createDisplayEvent(2L, "All Day Event", allDayStart, allDayEnd, todayCode, isAllDay = true)
        )

        coEvery { displayEventRepository.getDisplayEventsGroupedByDayOnce(any(), any()) } returns
            mapOf(todayCode to events)

        val result = repository.getTodayEvents()
        assertEquals(2, result.size)
        assertEquals("All Day Event", result[0].title)
        assertTrue(result[0].isAllDay)
        assertEquals("Timed Event", result[1].title)
        assertFalse(result[1].isAllDay)
    }

    // ========== Past Event Detection Tests ==========

    @Test
    fun `getTodayEvents marks past events as isPast=true`() = runTest {
        val now = System.currentTimeMillis()
        val todayCode = DateTimeUtils.eventTsToDayCode(now, isAllDay = false)

        val pastEnd = now - 2 * 3600000
        val pastStart = pastEnd - 3600000
        val futureStart = now + 3600000
        val futureEnd = now + 2 * 3600000

        val events = listOf(
            createDisplayEvent(1L, "Past Event", pastStart, pastEnd, todayCode),
            createDisplayEvent(2L, "Future Event", futureStart, futureEnd, todayCode)
        )

        coEvery { displayEventRepository.getDisplayEventsGroupedByDayOnce(any(), any()) } returns
            mapOf(todayCode to events)

        val result = repository.getTodayEvents()
        val pastEvent = result.find { it.title == "Past Event" }
        val futureEvent = result.find { it.title == "Future Event" }

        assertTrue("Past event should be marked as past", pastEvent?.isPast == true)
        assertFalse("Future event should not be marked as past", futureEvent?.isPast == true)
    }

    // ========== Cancelled Event Detection Tests ==========

    @Test
    fun `getTodayEvents marks cancelled events as isCancelled=true`() = runTest {
        val now = System.currentTimeMillis()
        val todayCode = DateTimeUtils.eventTsToDayCode(now, isAllDay = false)
        val start = now + 3600000
        val end = now + 2 * 3600000

        val events = listOf(
            createDisplayEvent(1L, "Cancelled Event", start, end, todayCode, status = "CANCELLED"),
            createDisplayEvent(2L, "Confirmed Event", start, end, todayCode, status = "CONFIRMED")
        )

        coEvery { displayEventRepository.getDisplayEventsGroupedByDayOnce(any(), any()) } returns
            mapOf(todayCode to events)

        val result = repository.getTodayEvents()
        val cancelled = result.find { it.title == "Cancelled Event" }
        val confirmed = result.find { it.title == "Confirmed Event" }

        assertTrue("Cancelled event should be marked cancelled", cancelled?.isCancelled == true)
        assertFalse("Confirmed event should not be marked cancelled", confirmed?.isCancelled == true)
    }

    @Test
    fun `getTodayEvents keeps cancelled events visible alongside confirmed ones`() = runTest {
        // The widget must render cancelled events crossed off, not drop them: both events
        // survive, and only the cancelled one carries isCancelled.
        val now = System.currentTimeMillis()
        val todayCode = DateTimeUtils.eventTsToDayCode(now, isAllDay = false)
        val start = now + 3600000
        val end = now + 2 * 3600000

        val events = listOf(
            createDisplayEvent(1L, "Cancelled Event", start, end, todayCode, status = "CANCELLED"),
            createDisplayEvent(2L, "Confirmed Event", start, end, todayCode, status = "CONFIRMED")
        )

        coEvery { displayEventRepository.getDisplayEventsGroupedByDayOnce(any(), any()) } returns
            mapOf(todayCode to events)

        val result = repository.getTodayEvents()
        assertEquals("Both events must be returned; cancelled is crossed off, not hidden", 2, result.size)
        assertTrue(
            "Cancelled event survives with its flag set",
            result.any { it.title == "Cancelled Event" && it.isCancelled }
        )
        assertTrue(
            "Confirmed event survives without the flag",
            result.any { it.title == "Confirmed Event" && !it.isCancelled }
        )
    }

    @Test
    fun `getTodayEvents all-day event today is NOT past`() = runTest {
        val todayCode = DateTimeUtils.eventTsToDayCode(System.currentTimeMillis(), false)

        val todayStart = java.time.LocalDate.now()
            .atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        val todayEnd = todayStart + 24 * 3600 * 1000 - 1

        val events = listOf(
            createDisplayEvent(1L, "All-Day Today", todayStart, todayEnd, todayCode, isAllDay = true)
        )

        coEvery { displayEventRepository.getDisplayEventsGroupedByDayOnce(any(), any()) } returns
            mapOf(todayCode to events)

        val result = repository.getTodayEvents()
        assertEquals(1, result.size)
        assertFalse(
            "All-day event for today should NOT be marked as past",
            result[0].isPast
        )
    }

    // ========== Mapping Tests ==========

    @Test
    fun `getTodayEvents maps DisplayEvent properties correctly`() = runTest {
        val today = LocalDate.now()
        val todayCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
        val zone = ZoneId.systemDefault()

        val startTs = today.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val endTs = startTs + 3600000

        val events = listOf(
            createDisplayEvent(42L, "Meeting", startTs, endTs, todayCode, calendarColor = 0xFFFF0000.toInt())
        )

        coEvery { displayEventRepository.getDisplayEventsGroupedByDayOnce(any(), any()) } returns
            mapOf(todayCode to events)

        val result = repository.getTodayEvents()
        assertEquals(1, result.size)
        val widgetEvent = result[0]
        assertEquals(42L, widgetEvent.eventId)
        assertEquals("Meeting", widgetEvent.title)
        assertEquals(startTs, widgetEvent.startTs)
        assertEquals(endTs, widgetEvent.endTs)
        assertFalse(widgetEvent.isAllDay)
        assertEquals(0xFFFF0000.toInt(), widgetEvent.calendarColor)
    }

    @Test
    fun `getTodayEvents uses default color when calendar color is 0`() = runTest {
        val today = LocalDate.now()
        val todayCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
        val zone = ZoneId.systemDefault()

        val startTs = today.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val endTs = startTs + 3600000

        val events = listOf(
            createDisplayEvent(1L, "Event", startTs, endTs, todayCode, calendarColor = 0)
        )

        coEvery { displayEventRepository.getDisplayEventsGroupedByDayOnce(any(), any()) } returns
            mapOf(todayCode to events)

        val result = repository.getTodayEvents()
        assertEquals(0xFF2196F3.toInt(), result[0].calendarColor)
    }

    // ========== Week Events Tests ==========

    @Test
    fun `getWeekEvents returns exactly 7 entries`() = runTest {
        coEvery { displayEventRepository.getDisplayEventsGroupedByDayOnce(any(), any()) } returns emptyMap()

        val weekEvents = repository.getWeekEvents()
        assertEquals(7, weekEvents.size)
        weekEvents.values.forEach { assertTrue(it.isEmpty()) }
    }

    @Test
    fun `getWeekEvents sorts events within each day`() = runTest {
        val today = LocalDate.now()
        val todayCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
        val zone = ZoneId.systemDefault()

        val time1 = today.atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
        val time2 = today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

        val events = listOf(
            createDisplayEvent(1L, "Afternoon", time1, time1 + 3600000, todayCode),
            createDisplayEvent(2L, "Morning", time2, time2 + 1800000, todayCode)
        )

        coEvery { displayEventRepository.getDisplayEventsGroupedByDayOnce(any(), any()) } returns
            mapOf(todayCode to events)

        val weekEvents = repository.getWeekEvents()
        val todayEvents = weekEvents[todayCode]!!
        assertEquals(2, todayEvents.size)
        assertEquals("Morning", todayEvents[0].title)
        assertEquals("Afternoon", todayEvents[1].title)
    }

    // ========== Range Events Tests ==========

    @Test
    fun `getEventsInRange returns events for specified range`() = runTest {
        val today = LocalDate.now()
        val todayCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
        val tomorrowCode = today.plusDays(1).let { it.year * 10000 + it.monthValue * 100 + it.dayOfMonth }
        val zone = ZoneId.systemDefault()

        val time1 = today.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val time2 = today.plusDays(1).atTime(14, 0).atZone(zone).toInstant().toEpochMilli()

        val events = mapOf(
            todayCode to listOf(
                createDisplayEvent(1L, "Today Event", time1, time1 + 3600000, todayCode)
            ),
            tomorrowCode to listOf(
                createDisplayEvent(2L, "Tomorrow Event", time2, time2 + 3600000, tomorrowCode)
            )
        )

        coEvery { displayEventRepository.getDisplayEventsGroupedByDayOnce(todayCode, tomorrowCode) } returns events

        val result = repository.getEventsInRange(todayCode, tomorrowCode)
        assertEquals(2, result.size)
        assertEquals("Today Event", result[todayCode]?.first()?.title)
        assertEquals("Tomorrow Event", result[tomorrowCode]?.first()?.title)
    }

    @Test
    fun `getEventsInRange returns empty map when no events exist`() = runTest {
        coEvery { displayEventRepository.getDisplayEventsGroupedByDayOnce(any(), any()) } returns emptyMap()

        val result = repository.getEventsInRange(20260301, 20260331)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getEventsInRange sorts events within each day`() = runTest {
        val today = LocalDate.now()
        val todayCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
        val zone = ZoneId.systemDefault()

        val time1 = today.atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
        val time2 = today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val allDayStart = today.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

        val events = listOf(
            createDisplayEvent(1L, "Afternoon", time1, time1 + 3600000, todayCode),
            createDisplayEvent(2L, "Morning", time2, time2 + 1800000, todayCode),
            createDisplayEvent(3L, "All Day", allDayStart, allDayStart + 86400000 - 1, todayCode, isAllDay = true)
        )

        coEvery { displayEventRepository.getDisplayEventsGroupedByDayOnce(any(), any()) } returns
            mapOf(todayCode to events)

        val result = repository.getEventsInRange(todayCode, todayCode)
        val dayEvents = result[todayCode]!!
        assertEquals(3, dayEvents.size)
        assertEquals("All Day", dayEvents[0].title)
        assertTrue(dayEvents[0].isAllDay)
        assertEquals("Morning", dayEvents[1].title)
        assertEquals("Afternoon", dayEvents[2].title)
    }

    @Test
    fun `getEventsInRange uses default color for zero calendar color`() = runTest {
        val today = LocalDate.now()
        val todayCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
        val zone = ZoneId.systemDefault()
        val time = today.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

        val events = listOf(
            createDisplayEvent(1L, "No Color", time, time + 3600000, todayCode, calendarColor = 0)
        )

        coEvery { displayEventRepository.getDisplayEventsGroupedByDayOnce(any(), any()) } returns
            mapOf(todayCode to events)

        val result = repository.getEventsInRange(todayCode, todayCode)
        assertEquals(0xFF2196F3.toInt(), result[todayCode]?.first()?.calendarColor)
    }

    @Test
    fun `getEventsInRange detects past events correctly`() = runTest {
        val now = System.currentTimeMillis()
        val todayCode = DateTimeUtils.eventTsToDayCode(now, isAllDay = false)

        val pastEnd = now - 2 * 3600000
        val pastStart = pastEnd - 3600000
        val futureStart = now + 3600000
        val futureEnd = now + 2 * 3600000

        val events = listOf(
            createDisplayEvent(1L, "Past", pastStart, pastEnd, todayCode),
            createDisplayEvent(2L, "Future", futureStart, futureEnd, todayCode)
        )

        coEvery { displayEventRepository.getDisplayEventsGroupedByDayOnce(any(), any()) } returns
            mapOf(todayCode to events)

        val result = repository.getEventsInRange(todayCode, todayCode)
        val dayEvents = result[todayCode]!!
        assertTrue(dayEvents.find { it.title == "Past" }!!.isPast)
        assertFalse(dayEvents.find { it.title == "Future" }!!.isPast)
    }

    // ========== Task Reads ==========

    private fun stubTasks(tasks: List<Task>) {
        every { taskReader.observeTasks(VisibilityDomain.SCHEDULE) } returns flowOf(tasks)
    }

    @Test
    fun `getOpenTasks returns empty list when no tasks exist`() = runTest {
        stubTasks(emptyList())

        assertTrue(repository.getOpenTasks(limit = 8).isEmpty())
    }

    @Test
    fun `getOpenTasks returns empty list for a non-positive limit`() = runTest {
        stubTasks(listOf(createTask(1L, "Anything")))

        assertTrue(repository.getOpenTasks(limit = 0).isEmpty())
    }

    @Test
    fun `getOpenTasks keeps only open tasks without pending deletes`() = runTest {
        stubTasks(
            listOf(
                createTask(1L, "Open"),
                createTask(2L, "Completed", status = Task.STATUS_COMPLETED),
                createTask(3L, "Cancelled", status = Task.STATUS_CANCELLED),
                createTask(4L, "Deleted", syncStatus = SyncStatus.PENDING_DELETE)
            )
        )

        val result = repository.getOpenTasks(limit = 8)
        assertEquals(listOf("Open"), result.map { it.title })
    }

    @Test
    fun `getOpenTasks sorts by when with undated last`() = runTest {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val later = today.plusDays(2).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val sooner = today.plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

        stubTasks(
            listOf(
                createTask(1L, "Undated"),
                createTask(2L, "Later", dueTs = later),
                createTask(3L, "Sooner", dueTs = sooner)
            )
        )

        val result = repository.getOpenTasks(limit = 8)
        assertEquals(listOf("Sooner", "Later", "Undated"), result.map { it.title })
    }

    @Test
    fun `getOpenTasks breaks when ties by priority then title`() = runTest {
        val zone = ZoneId.systemDefault()
        val due = LocalDate.now().plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

        stubTasks(
            listOf(
                createTask(1L, "Beta", dueTs = due, priority = 5),
                createTask(2L, "Alpha", dueTs = due, priority = 1),
                createTask(3L, "Zulu", dueTs = due, priority = 1)
            )
        )

        val result = repository.getOpenTasks(limit = 8)
        assertEquals(listOf("Alpha", "Zulu", "Beta"), result.map { it.title })
    }

    @Test
    fun `getOpenTasks respects the limit`() = runTest {
        stubTasks(
            listOf(
                createTask(1L, "First"),
                createTask(2L, "Second"),
                createTask(3L, "Third")
            )
        )

        assertEquals(listOf("First", "Second"), repository.getOpenTasks(limit = 2).map { it.title })
    }

    @Test
    fun `getTodayTasks keeps overdue tasks and drops future and undated ones`() = runTest {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val overdue = today.minusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val dueToday = today.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val future = today.plusDays(3).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

        stubTasks(
            listOf(
                createTask(1L, "Overdue", dueTs = overdue),
                createTask(2L, "Due today", dueTs = dueToday),
                createTask(3L, "Future", dueTs = future),
                createTask(4L, "Undated"),
                createTask(5L, "Done today", dueTs = dueToday, status = Task.STATUS_COMPLETED)
            )
        )

        val result = repository.getTodayTasks()
        assertEquals(listOf("Overdue", "Due today"), result.map { it.title })
    }

    @Test
    fun `getOpenTasks maps the all-day flag and day code from the due`() = runTest {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val timedDue = today.atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
        val allDayDue = today.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val expectedCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth

        stubTasks(
            listOf(
                createTask(1L, "Timed", dueTs = timedDue),
                createTask(2L, "All day", dueTs = allDayDue, dueIsAllDay = true)
            )
        )

        val result = repository.getOpenTasks(limit = 8)
        val timed = result.first { it.title == "Timed" }
        val allDay = result.first { it.title == "All day" }
        assertFalse(timed.isAllDay)
        assertEquals(expectedCode, timed.dayCode)
        assertTrue(allDay.isAllDay)
        assertEquals(expectedCode, allDay.dayCode)
    }

    @Test
    fun `getOpenTasks sorts undefined priority after P1 through P9`() = runTest {
        val zone = ZoneId.systemDefault()
        val due = LocalDate.now().plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

        stubTasks(
            listOf(
                createTask(1L, "Undefined", dueTs = due, priority = 0),
                createTask(2L, "Lowest", dueTs = due, priority = 9),
                createTask(3L, "Highest", dueTs = due, priority = 1)
            )
        )

        val result = repository.getOpenTasks(limit = 8)
        assertEquals(listOf("Highest", "Lowest", "Undefined"), result.map { it.title })
    }

    @Test
    fun `getTodayTasks sorts undefined priority after defined ones`() = runTest {
        val zone = ZoneId.systemDefault()
        val due = LocalDate.now().atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

        stubTasks(
            listOf(
                createTask(1L, "Undefined", dueTs = due, priority = 0),
                createTask(2L, "Defined", dueTs = due, priority = 1)
            )
        )

        val result = repository.getTodayTasks()
        assertEquals(listOf("Defined", "Undefined"), result.map { it.title })
    }

    @Test
    fun `DTSTART-only UTC-midnight task places on its UTC calendar day as all-day`() = runTest {
        val today = LocalDate.now()
        val dtStartUtcMidnight = today.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val expectedCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth

        stubTasks(listOf(createTask(1L, "Dtstart only", dtStartTs = dtStartUtcMidnight)))

        val result = repository.getOpenTasks(limit = 8)
        val task = result.single()
        assertTrue(task.isAllDay)
        assertEquals(expectedCode, task.dayCode)
    }

    // ========== Helper Methods ==========

    private fun createDisplayEvent(
        id: Long,
        title: String,
        startTs: Long,
        endTs: Long,
        dayCode: Int,
        isAllDay: Boolean = false,
        calendarColor: Int = testCalendar.color,
        status: String = "CONFIRMED"
    ): DisplayEvent {
        val event = Event(
            id = id,
            uid = "$title-$id@test.com",
            calendarId = 1L,
            title = title,
            startTs = startTs,
            endTs = endTs,
            isAllDay = isAllDay,
            status = status,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )
        val occurrence = Occurrence(
            eventId = id,
            calendarId = 1L,
            startTs = startTs,
            endTs = endTs,
            startDay = dayCode,
            endDay = dayCode
        )
        val calendar = if (calendarColor != 0) testCalendar.copy(color = calendarColor) else null
        return DisplayEvent.Room(event, occurrence, calendar)
    }

    /** A minimal open task; [dueTs] places it, [dtStartTs] stays null unless a test needs it. */
    private fun createTask(
        id: Long,
        title: String,
        dueTs: Long? = null,
        dueIsAllDay: Boolean = false,
        dtStartTs: Long? = null,
        status: String = Task.STATUS_NEEDS_ACTION,
        priority: Int = 0,
        syncStatus: SyncStatus = SyncStatus.SYNCED
    ): Task = Task(
        id = id,
        uid = "$title-$id@test.com",
        calendarId = 1L,
        summary = title,
        status = status,
        priority = priority,
        dueTs = dueTs,
        dueIsAllDay = dueIsAllDay,
        dtStartTs = dtStartTs,
        syncStatus = syncStatus,
        dtstamp = System.currentTimeMillis()
    )
}
