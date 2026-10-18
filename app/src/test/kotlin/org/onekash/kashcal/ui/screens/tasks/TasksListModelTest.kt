package org.onekash.kashcal.ui.screens.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.db.entity.Task
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Group boundaries, filters and sort order of [TasksListModel], on a fixed clock. The zone is
 * fixed too: UTC for the boundary math, a west-of-UTC zone for the all-day placement rule.
 */
class TasksListModelTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    /** 2026-10-18 15:00 UTC, a Sunday afternoon. */
    private val now: Long = Instant.parse("2026-10-18T15:00:00Z").toEpochMilli()
    private val clock: () -> Long = { now }

    private fun day(dayOfMonth: Int, hour: Int = 12): Long =
        LocalDate.of(2026, 10, dayOfMonth).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun task(
        id: Long,
        summary: String = "task $id",
        priority: Int = 0,
        status: String = Task.STATUS_NEEDS_ACTION,
        dueTs: Long? = null,
        dueIsAllDay: Boolean = false,
        dtStartTs: Long? = null,
        completedTs: Long? = null,
        syncStatus: SyncStatus = SyncStatus.SYNCED,
        percentComplete: Int? = null,
    ): Task = Task(
        id = id,
        uid = "uid-$id",
        calendarId = 1,
        summary = summary,
        status = status,
        priority = priority,
        dueTs = dueTs,
        dueIsAllDay = dueIsAllDay,
        dtStartTs = dtStartTs,
        completedTs = completedTs,
        syncStatus = syncStatus,
        percentComplete = percentComplete,
        dtstamp = now,
    )

    private fun ids(groups: List<TasksGroupUi>): List<Long> =
        groups.flatMap { group -> group.items.map { it.task.id } }

    private fun build(
        tasks: List<Task>,
        status: TaskStatusFilter = TaskStatusFilter.OPEN,
        band: TaskPriorityBand = TaskPriorityBand.ANY,
    ): List<TasksGroupUi> = TasksListModel.build(tasks, status, band, clock, zone)

    // ========== Placement boundaries ==========

    @Test
    fun `groups follow overdue, today, tomorrow, this week, later, no date order`() {
        val groups = build(
            listOf(
                task(1, dueTs = day(20)), // this week (Tuesday)
                task(2),                  // no date
                task(3, dueTs = day(17)), // overdue
                task(4, dueTs = day(19)), // tomorrow
                task(5, dueTs = day(18)), // today
                task(6, dueTs = day(25)), // later
            )
        )
        assertEquals(
            listOf("overdue", "today", "tomorrow", "this_week", "later", "no_date"),
            groups.map { it.id }
        )
        assertEquals(listOf(3L, 5L, 4L, 1L, 6L, 2L), ids(groups))
    }

    @Test
    fun `midnight rollover moves today's task into overdue`() {
        val tasks = listOf(task(1, dueTs = day(18)))
        assertEquals(listOf("today"), build(tasks).map { it.id })

        val oneMinutePastMidnight = TasksListModel.build(
            tasks,
            TaskStatusFilter.OPEN,
            TaskPriorityBand.ANY,
            { Instant.parse("2026-10-19T00:01:00Z").toEpochMilli() },
            zone,
        )
        assertEquals(listOf("overdue"), oneMinutePastMidnight.map { it.id })
    }

    @Test
    fun `this week window closes after seven days`() {
        // Today + 6 days is the last "this week" day; today + 7 is "later".
        val groups = build(
            listOf(
                task(1, dueTs = day(24)),
                task(2, dueTs = day(25)),
            )
        )
        assertEquals(listOf("this_week", "later"), groups.map { it.id })
    }

    @Test
    fun `undated open task places at dtstart, midnight-aligned as a date`() {
        val groups = build(
            listOf(
                // Timed 12:00 UTC: not midnight-aligned, so it reads in-zone — today.
                task(1, dtStartTs = day(18)),
                // Exactly UTC midnight: reads as an all-day date — also today.
                task(2, dtStartTs = day(18, hour = 0)),
            )
        )
        assertEquals(listOf("today"), groups.map { it.id })
        // Midnight reads first: the comparator orders by raw whenTs, and 00:00 < 12:00.
        assertEquals(listOf(2L, 1L), ids(groups))
    }

    @Test
    fun `all-day due reads as its calendar date in a west-of-UTC zone`() {
        // 2026-10-18 23:00 in New York (UTC-4) is 2026-10-19 03:00 UTC, but the all-day due of
        // the 19th (stored as UTC midnight) must stay "tomorrow", never overdue.
        val newYork: ZoneId = ZoneId.of("America/New_York")
        val lateEveningUtc = Instant.parse("2026-10-19T03:00:00Z").toEpochMilli()
        val groups = TasksListModel.build(
            listOf(task(1, dueTs = day(19), dueIsAllDay = true)),
            TaskStatusFilter.OPEN,
            TaskPriorityBand.ANY,
            { lateEveningUtc },
            newYork,
        )
        assertEquals(listOf("tomorrow"), groups.map { it.id })
    }

    // ========== Filters ==========

    @Test
    fun `status filter splits open, done and cancelled`() {
        val tasks = listOf(
            task(1, dueTs = day(18)),
            task(2, dueTs = day(18), status = Task.STATUS_COMPLETED, completedTs = day(18), percentComplete = 100),
            task(3, dueTs = day(18), status = Task.STATUS_CANCELLED),
        )
        assertEquals(listOf(1L), ids(build(tasks, TaskStatusFilter.OPEN)))
        assertEquals(listOf(1L, 3L, 2L), ids(build(tasks, TaskStatusFilter.ALL)))
        assertEquals(listOf("completed"), build(tasks, TaskStatusFilter.DONE).map { it.id })
        assertEquals(listOf(2L), ids(build(tasks, TaskStatusFilter.DONE)))
    }

    @Test
    fun `priority band filter narrows every group`() {
        val tasks = listOf(
            task(1, priority = 1, dueTs = day(18)),
            task(2, priority = 5, dueTs = day(18)),
            task(3, priority = 9, dueTs = day(18)),
            task(4, priority = 0, dueTs = day(18)),
        )
        assertEquals(listOf(1L), ids(build(tasks, band = TaskPriorityBand.HIGH)))
        assertEquals(listOf(4L), ids(build(tasks, band = TaskPriorityBand.NONE)))

        // Counts are independent of the build filter and never include deleted rows.
        val counts = TasksListModel.filterCounts(tasks, clock, zone)
        assertEquals(1, counts.high)
        assertEquals(1, counts.medium)
        assertEquals(1, counts.low)
        assertEquals(1, counts.none)
        assertEquals(4, counts.all)
        assertEquals(4, counts.open)
        assertEquals(0, counts.done)
    }

    // ========== Sorting ==========

    @Test
    fun `sorts by priority with zero last, then time with nulls last, then summary`() {
        val groups = build(
            listOf(
                task(1, summary = "b", priority = 0, dueTs = day(18, 9)),
                task(2, summary = "A", priority = 3, dueTs = day(18, 18)),
                task(3, summary = "a", priority = 3, dueTs = day(18, 8)),
                task(4, summary = "c", priority = 9),
            )
        )
        // Task 4 has no date, so it forms its own trailing no_date group.
        assertEquals(2, groups.size)
        assertEquals(
            // 3 before 2 (same priority, earlier time first), then 1 (priority 0 last among
            // the dated), then 4 (undated last, in the no_date group).
            listOf(3L, 2L, 1L, 4L),
            ids(groups)
        )
    }

    @Test
    fun `case-insensitive summary tiebreak`() {
        val groups = build(
            listOf(
                task(1, summary = "banana", dueTs = day(18)),
                task(2, summary = "Apple", dueTs = day(18)),
            )
        )
        assertEquals(listOf(2L, 1L), ids(groups))
    }

    @Test
    fun `completed group sorts newest completion first`() {
        val groups = build(
            listOf(
                task(1, status = Task.STATUS_COMPLETED, completedTs = day(17), percentComplete = 100),
                task(2, status = Task.STATUS_COMPLETED, completedTs = day(18), percentComplete = 100),
            ),
            TaskStatusFilter.DONE,
        )
        assertEquals(listOf(2L, 1L), ids(groups))
    }

    // ========== Exclusions, overdue flag, empties ==========

    @Test
    fun `pending delete rows vanish from groups and counts`() {
        val tasks = listOf(
            task(1, dueTs = day(18)),
            task(2, dueTs = day(18), syncStatus = SyncStatus.PENDING_DELETE),
        )
        assertEquals(listOf(1L), ids(build(tasks)))

        val counts = TasksListModel.filterCounts(tasks, clock, zone)
        assertEquals(1, counts.all)
        assertEquals(1, counts.open)
    }

    @Test
    fun `overdue flag set only on open tasks before today`() {
        val groups = build(
            listOf(
                task(1, dueTs = day(17)),
                task(2, dueTs = day(17), status = Task.STATUS_COMPLETED, completedTs = day(17), percentComplete = 100),
            ),
            TaskStatusFilter.ALL,
        )
        val overdue = groups.first { it.id == "overdue" }
        assertTrue(overdue.items.single().let { it.task.id == 1L && it.isOverdue })

        val completed = groups.last()
        assertEquals("completed", completed.id)
        val completedItem = completed.items.single()
        assertEquals(2L, completedItem.task.id)
        assertFalse(completedItem.isOverdue)
    }

    @Test
    fun `empty input yields no groups`() {
        assertTrue(build(emptyList()).isEmpty())
        assertTrue(
            build(
                listOf(task(1, status = Task.STATUS_COMPLETED, completedTs = day(18), percentComplete = 100)),
                TaskStatusFilter.OPEN,
            ).isEmpty()
        )
    }
}
