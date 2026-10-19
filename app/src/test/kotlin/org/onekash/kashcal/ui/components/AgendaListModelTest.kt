package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.Task
import org.onekash.kashcal.domain.model.DisplayEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AgendaListModelTest {

    private var nextId = 1L

    private fun roomEvent(
        startTs: Long,
        startDay: Int,
        endDay: Int = startDay,
        title: String = "Event",
        isAllDay: Boolean = false
    ): DisplayEvent.Room {
        val id = nextId++
        val event = Event(id = id, uid = "uid-$id", calendarId = 1L, title = title, startTs = startTs, endTs = startTs + 3_600_000L, dtstamp = startTs, isAllDay = isAllDay)
        val occ = Occurrence(eventId = id, calendarId = 1L, startTs = startTs, endTs = startTs + 3_600_000L, startDay = startDay, endDay = endDay)
        val cal = Calendar(id = 1L, accountId = 1L, caldavUrl = "https://example.com/cal/", displayName = "Cal", color = 0xFF0000.toInt())
        return DisplayEvent.Room(event, occ, cal)
    }

    @Test
    fun `single-day event yields one item with total day count 1`() {
        val model = buildAgendaListModel(listOf(roomEvent(1000L, 20260718)), tasks = emptyList(), todayDayCode = 20260718)
        assertEquals(1, model.groups.size)
        assertEquals(20260718, model.groups[0].dayCode)
        assertEquals(1, model.groups[0].items.size)
        assertEquals(1, model.groups[0].items[0].totalDays)
        assertEquals(1, model.groups[0].items[0].dayNumber)
    }

    @Test
    fun `multi-day event expands to one item per spanned day with Day X of Y`() {
        // Jul 18 -> Jul 20 inclusive = 3 days.
        val model = buildAgendaListModel(listOf(roomEvent(1000L, 20260718, endDay = 20260720)), tasks = emptyList(), todayDayCode = 20260718)
        assertEquals(listOf(20260718, 20260719, 20260720), model.groups.map { it.dayCode })
        val allItems = model.groups.flatMap { it.items }
        assertEquals(3, allItems.size)
        assertTrue(allItems.all { it.totalDays == 3 })
        assertEquals(listOf(1, 2, 3), allItems.map { it.dayNumber })
    }

    @Test
    fun `duplicate title-startTs-day collapses to one item`() {
        val e1 = roomEvent(1000L, 20260718, title = "Same")
        // Same title + same startTs + same day -> deduped.
        val e2Event = e1.event.copy(id = 99L)
        val e2 = DisplayEvent.Room(e2Event, e1.occurrence, e1.calendar)
        val model = buildAgendaListModel(listOf(e1, e2), tasks = emptyList(), todayDayCode = 20260718)
        assertEquals(1, model.groups[0].items.size)
    }

    @Test
    fun `events before today are filtered out`() {
        val past = roomEvent(1000L, 20260716)
        val today = roomEvent(2000L, 20260718)
        val model = buildAgendaListModel(listOf(past, today), tasks = emptyList(), todayDayCode = 20260718)
        assertEquals(listOf(20260718), model.groups.map { it.dayCode })
    }

    @Test
    fun `groups are ordered by day then start time within a day`() {
        val later = roomEvent(5000L, 20260718, title = "Later")
        val earlier = roomEvent(2000L, 20260718, title = "Earlier")
        val nextDay = roomEvent(1000L, 20260719, title = "NextDay")
        val model = buildAgendaListModel(listOf(later, earlier, nextDay), tasks = emptyList(), todayDayCode = 20260718)
        assertEquals(listOf(20260718, 20260719), model.groups.map { it.dayCode })
        assertEquals(listOf("Earlier", "Later"), model.groups[0].items.map { it.displayEvent.title })
    }

    @Test
    fun `header index math accounts for one header plus N cards per preceding group`() {
        // Day A: 2 cards. Day B: 1 card.
        val a1 = roomEvent(1000L, 20260718, title = "A1")
        val a2 = roomEvent(2000L, 20260718, title = "A2")
        val b1 = roomEvent(3000L, 20260719, title = "B1")
        val model = buildAgendaListModel(listOf(a1, a2, b1), tasks = emptyList(), todayDayCode = 20260718)
        // Flat layout: [0]=headerA, [1]=A1, [2]=A2, [3]=headerB, [4]=B1
        assertEquals(0, model.headerIndexByDayCode[20260718])
        assertEquals(3, model.headerIndexByDayCode[20260719])
    }

    @Test
    fun `resolveScrollTargetIndex returns exact header index`() {
        val a = roomEvent(1000L, 20260718)
        val b = roomEvent(2000L, 20260720)
        val model = buildAgendaListModel(listOf(a, b), tasks = emptyList(), todayDayCode = 20260718)
        assertEquals(0, resolveScrollTargetIndex(20260718, model))
        // headerB at index 2 ([0]=headerA,[1]=A,[2]=headerB,[3]=B)
        assertEquals(2, resolveScrollTargetIndex(20260720, model))
    }

    @Test
    fun `resolveScrollTargetIndex falls to the nearest following day`() {
        val a = roomEvent(1000L, 20260718)
        val c = roomEvent(2000L, 20260720)
        val model = buildAgendaListModel(listOf(a, c), tasks = emptyList(), todayDayCode = 20260718)
        // Tapping Jul 19 (no events) -> nearest following header = Jul 20 at index 2.
        assertEquals(2, resolveScrollTargetIndex(20260719, model))
    }

    @Test
    fun `resolveScrollTargetIndex past the last day clamps to the last header`() {
        val a = roomEvent(1000L, 20260718)
        val b = roomEvent(2000L, 20260720)
        val model = buildAgendaListModel(listOf(a, b), tasks = emptyList(), todayDayCode = 20260718)
        // Tapping beyond the last group -> last header (Jul 20 at index 2).
        assertEquals(2, resolveScrollTargetIndex(20260805, model))
    }

    @Test
    fun `resolveScrollTargetIndex on empty model returns -1`() {
        val model = buildAgendaListModel(emptyList(), tasks = emptyList(), todayDayCode = 20260718)
        assertTrue(model.groups.isEmpty())
        assertEquals(-1, resolveScrollTargetIndex(20260718, model))
    }

    // ==================== Task interleave ====================

    private fun task(
        id: Long,
        summary: String = "Task",
        dueTs: Long? = null,
        dueIsAllDay: Boolean = false,
        dtStartTs: Long? = null,
        status: String = Task.STATUS_NEEDS_ACTION,
    ): Task = Task(
        id = id,
        uid = "task-$id@test.local",
        calendarId = 1L,
        summary = summary,
        status = status,
        dueTs = dueTs,
        dueIsAllDay = dueIsAllDay,
        dtStartTs = dtStartTs,
        dtstamp = 0L
    )

    /** Jul 18 2026, 12:00 UTC: a timed due the UTC-zone day code reads as Jul 18. */
    private val jul18Noon = 1_784_376_000_000L

    /** Jul 18 2026, UTC midnight: an all-day due. */
    private val jul18AllDay = 1_784_332_800_000L

    /** Jul 19 2026, UTC midnight: an all-day due the day after the tests' today. */
    private val jul19AllDay = 1_784_419_200_000L

    @Test
    fun `open tasks interleave between all-day and timed events`() {
        val allDay = roomEvent(0L, 20260718, title = "AllDay", isAllDay = true)
        val timed = roomEvent(jul18Noon + 3_600_000L, 20260718, title = "Timed")
        val due = task(id = 1L, summary = "Due", dueTs = jul18Noon)
        val model = buildAgendaListModel(listOf(timed, allDay), listOf(due), todayDayCode = 20260718)
        val group = model.groups.single()
        assertEquals(1, group.allDayItems.size)
        assertEquals(listOf(due), group.openTasks)
        assertEquals(1, group.timedItems.size)
        assertEquals(0, model.headerIndexByDayCode[20260718])
    }

    @Test
    fun `overdue open task folds into today's group`() {
        val due = task(id = 1L, summary = "Overdue", dueTs = jul18Noon)
        val model = buildAgendaListModel(emptyList(), listOf(due), todayDayCode = 20260719)
        val group = model.groups.single()
        assertEquals(20260719, group.dayCode)
        assertEquals(listOf(due), group.openTasks)
    }

    @Test
    fun `completed tasks render only when included and after the open ones`() {
        val done = task(id = 1L, summary = "Done", dueTs = jul18Noon, status = Task.STATUS_COMPLETED)
        val open = task(id = 2L, summary = "Open", dueTs = jul18Noon)
        val model = buildAgendaListModel(emptyList(), listOf(open, done), todayDayCode = 20260718, includeCompletedTasks = true)
        val group = model.groups.single()
        assertEquals(listOf(open), group.openTasks)
        assertEquals(listOf(done), group.completedTasks)

        val gated = buildAgendaListModel(emptyList(), listOf(open, done), todayDayCode = 20260718)
        assertTrue(gated.groups.single().completedTasks.isEmpty())
    }

    @Test
    fun `undated task renders nowhere`() {
        val undated = task(id = 1L, summary = "Undated")
        val model = buildAgendaListModel(emptyList(), listOf(undated), todayDayCode = 20260718)
        assertTrue(model.groups.isEmpty())
    }

    @Test
    fun `task-only day gets a group and shifts the header indexes`() {
        val nextDayEvent = roomEvent(1000L, 20260719, title = "NextDay")
        val due = task(id = 1L, summary = "Due", dueTs = jul18AllDay, dueIsAllDay = true)
        val model = buildAgendaListModel(listOf(nextDayEvent), listOf(due), todayDayCode = 20260718)
        assertEquals(listOf(20260718, 20260719), model.groups.map { it.dayCode })
        // Flat layout: [0]=header18, [1]=task, [2]=header19, [3]=event
        assertEquals(0, model.headerIndexByDayCode[20260718])
        assertEquals(2, model.headerIndexByDayCode[20260719])
    }

    @Test
    fun `all-day due places by its UTC date`() {
        val due = task(id = 1L, summary = "AllDayDue", dueTs = jul19AllDay, dueIsAllDay = true)
        val model = buildAgendaListModel(emptyList(), listOf(due), todayDayCode = 20260718)
        // The UTC date is Jul 19, not the local day a raw timestamp would place it in.
        assertEquals(20260719, model.groups.single().dayCode)
    }
}
