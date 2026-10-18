package org.onekash.kashcal.ui.screens.tasks

import androidx.annotation.StringRes
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.db.entity.Task
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The Tasks screen's status filter. OPEN keeps the tasks that still need action, DONE the
 * completed ones and ALL both. A CANCELLED task counts as neither done nor needing action, so
 * it shows under ALL only.
 */
enum class TaskStatusFilter { OPEN, ALL, DONE }

/**
 * The Tasks screen's priority band filter. Bands mirror the row chips' display colors: 1–3,
 * 4–6, 7–9 and the undefined 0.
 */
enum class TaskPriorityBand(private val priorities: Set<Int>) {
    ANY(emptySet()),
    HIGH(setOf(1, 2, 3)),
    MEDIUM(setOf(4, 5, 6)),
    LOW(setOf(7, 8, 9)),
    NONE(setOf(0));

    /** True when [priority] belongs in the band; [ANY] takes everything. */
    fun matches(priority: Int): Boolean = priorities.isEmpty() || priority in priorities
}

/** One rendered task row: the entity plus the display fields the grouping precomputed. */
data class TaskUi(
    val task: Task,
    /** The task's placement time, [Task.whenTs]; null when the task has neither DUE nor DTSTART. */
    val whenTs: Long?,
    /** True when the task is open and placed before today, so the row shows the error-colored due. */
    val isOverdue: Boolean,
)

/** One non-empty group of the Tasks screen's list. [titleRes] is one of the `tasks_group_*` keys. */
data class TasksGroupUi(
    /** Stable LazyColumn key, e.g. "today". */
    val id: String,
    @StringRes val titleRes: Int?,
    val items: List<TaskUi>,
)

/** Chip counts for the Tasks screen's filter rows, over every task a sync would keep. */
data class TaskFilterCounts(
    val open: Int,
    val all: Int,
    val done: Int,
    val high: Int,
    val medium: Int,
    val low: Int,
    val none: Int,
)

/**
 * Turns the raw task list into the Tasks screen's grouped, filtered and sorted sections. Pure
 * logic with an injected clock, so the group boundaries are testable; the Screen only formats
 * the per-row labels.
 *
 * Placement uses the whole local day of [Task.whenTs], and an all-day DUE reads as a date (its
 * stored UTC midnight maps back to the calendar date) rather than an instant, so a due of
 * "today" stays today in every zone instead of turning overdue after lunch in the west.
 */
object TasksListModel {

    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val OVERDUE_GROUP_ID = "overdue"
    private const val TODAY_GROUP_ID = "today"
    private const val TOMORROW_GROUP_ID = "tomorrow"
    private const val THIS_WEEK_GROUP_ID = "this_week"
    private const val LATER_GROUP_ID = "later"
    private const val NO_DATE_GROUP_ID = "no_date"
    private const val COMPLETED_GROUP_ID = "completed"

    /** The overdue / today / tomorrow / this-week / later / no-date sections, in render order. */
    private val PLACEMENT_GROUPS = listOf(
        OVERDUE_GROUP_ID to R.string.tasks_group_overdue,
        TODAY_GROUP_ID to R.string.tasks_group_today,
        TOMORROW_GROUP_ID to R.string.tasks_group_tomorrow,
        THIS_WEEK_GROUP_ID to R.string.tasks_group_this_week,
        LATER_GROUP_ID to R.string.tasks_group_later,
        NO_DATE_GROUP_ID to R.string.tasks_group_no_date,
    )

    /**
     * Groups [tasks] under [status] and [band]. Empty groups are dropped, so the caller renders
     * the result verbatim; the completed section (ALL and DONE filters only — OPEN shows open
     * rows alone) sorts newest first and always closes the list.
     */
    fun build(
        tasks: List<Task>,
        status: TaskStatusFilter,
        band: TaskPriorityBand,
        clock: () -> Long = System::currentTimeMillis,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<TasksGroupUi> {
        val active = tasks
            .filter { it.syncStatus != SyncStatus.PENDING_DELETE }
            .filter { band.matches(it.priority) }
        val today = today(clock(), zone)

        val placementTasks = when (status) {
            TaskStatusFilter.OPEN -> active.filter { it.isOpen }
            TaskStatusFilter.ALL -> active.filterNot { it.isDone }
            TaskStatusFilter.DONE -> emptyList()
        }

        val groups = mutableListOf<TasksGroupUi>()
        if (status != TaskStatusFilter.DONE) {
            val byGroup = placementTasks.groupBy { placementGroupId(it, today, zone) }
            for ((id, titleRes) in PLACEMENT_GROUPS) {
                val members = byGroup[id].orEmpty()
                if (members.isNotEmpty()) {
                    groups += TasksGroupUi(
                        id, titleRes,
                        members.map(toTaskUi(today, zone)).sortedWith(placementOrder)
                    )
                }
            }
        }
        // OPEN shows open rows only; the completed section belongs to ALL and DONE.
        if (status != TaskStatusFilter.OPEN) {
            val completed = active.filter { it.isDone }
            if (completed.isNotEmpty()) {
                groups += TasksGroupUi(
                    COMPLETED_GROUP_ID,
                    R.string.tasks_group_completed,
                    completed.map(toTaskUi(today, zone)).sortedWith(completedOrder),
                )
            }
        }
        return groups
    }

    /**
     * Counts the filter chips show: one per status chip over its own predicate and one per
     * priority band over every kept task, so a chip's number never depends on the other filter.
     */
    fun filterCounts(
        tasks: List<Task>,
        clock: () -> Long = System::currentTimeMillis,
        zone: ZoneId = ZoneId.systemDefault(),
    ): TaskFilterCounts {
        val kept = tasks.filter { it.syncStatus != SyncStatus.PENDING_DELETE }
        return TaskFilterCounts(
            open = kept.count { it.isOpen },
            all = kept.size,
            done = kept.count { it.isDone },
            high = kept.count { TaskPriorityBand.HIGH.matches(it.priority) },
            medium = kept.count { TaskPriorityBand.MEDIUM.matches(it.priority) },
            low = kept.count { TaskPriorityBand.LOW.matches(it.priority) },
            none = kept.count { TaskPriorityBand.NONE.matches(it.priority) },
        )
    }

    /** The local day [nowMs] falls on; the reference every group boundary compares against. */
    private fun today(nowMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

    /** The placement section of [task] relative to [today], keyed like [PLACEMENT_GROUPS]. */
    private fun placementGroupId(task: Task, today: LocalDate, zone: ZoneId): String {
        val date = placementDate(task, zone) ?: return NO_DATE_GROUP_ID
        return when {
            date.isBefore(today) -> OVERDUE_GROUP_ID
            date == today -> TODAY_GROUP_ID
            date == today.plusDays(1) -> TOMORROW_GROUP_ID
            date.isBefore(today.plusDays(7)) -> THIS_WEEK_GROUP_ID
            else -> LATER_GROUP_ID
        }
    }

    /**
     * The local day a task places itself on, or null when it has neither DUE nor DTSTART. An
     * all-day DUE is stored as UTC midnight, so it reads back as a date in UTC; a DTSTART
     * fallback keeps its all-day nature only when it is midnight-aligned, since the row carries
     * no flag for it.
     */
    private fun placementDate(task: Task, zone: ZoneId): LocalDate? {
        val ts = task.whenTs ?: return null
        val readsAsDate = if (task.dueTs != null) task.dueIsAllDay else Math.floorMod(ts, DAY_MS) == 0L
        val atZone = if (readsAsDate) ZoneOffset.UTC else zone
        return Instant.ofEpochMilli(ts).atZone(atZone).toLocalDate()
    }

    private fun toTaskUi(today: LocalDate, zone: ZoneId): (Task) -> TaskUi = { task ->
        val date = placementDate(task, zone)
        TaskUi(
            task = task,
            whenTs = task.whenTs,
            isOverdue = task.isOpen && date != null && date.isBefore(today),
        )
    }

    /** Priority first (undefined 0 last), then placement time with undated tasks last, then title. */
    private val placementOrder: Comparator<TaskUi> =
        Comparator<TaskUi> { a, b ->
            val byPriority = a.task.priority.asSortKey().compareTo(b.task.priority.asSortKey())
            if (byPriority != 0) return@Comparator byPriority
            val aTs = a.whenTs
            val bTs = b.whenTs
            when {
                aTs == null && bTs == null -> 0
                aTs == null -> 1
                bTs == null -> -1
                else -> aTs.compareTo(bTs)
            }
        }.thenComparing { a, b -> a.task.summary.lowercase().compareTo(b.task.summary.lowercase()) }

    /** Newest completion first, undated completions last, then title. */
    private val completedOrder: Comparator<TaskUi> =
        Comparator<TaskUi> { a, b ->
            val aTs = a.task.completedTs
            val bTs = b.task.completedTs
            when {
                aTs == null && bTs == null -> 0
                aTs == null -> 1
                bTs == null -> -1
                else -> bTs.compareTo(aTs)
            }
        }.thenComparing { a, b -> a.task.summary.lowercase().compareTo(b.task.summary.lowercase()) }
}

/** Maps an RFC 5545 priority to a sort key: defined priorities first, the undefined 0 last. */
private fun Int.asSortKey(): Int = if (this == 0) Int.MAX_VALUE else this
