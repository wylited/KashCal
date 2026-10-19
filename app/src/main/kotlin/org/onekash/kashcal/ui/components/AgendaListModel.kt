package org.onekash.kashcal.ui.components

import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.Task
import org.onekash.kashcal.domain.model.DisplayEvent
import java.time.ZoneId

/**
 * One rendered agenda entry. A multi-day event produces one item per day it
 * spans, carrying its [dayNumber] within [totalDays] so the card can show
 * "Day X of Y".
 */
internal data class AgendaDisplayItem(
    val displayEvent: DisplayEvent,
    val displayDay: Int,   // YYYYMMDD for this display entry
    val dayNumber: Int,    // 1, 2, 3... (which day of a multi-day event)
    val totalDays: Int     // total days in the event (1 for single-day)
)

/**
 * A day's worth of agenda items under a single date header, in render order: the all-day
 * events, then the day's open tasks, then its completed ones (when the caller included them),
 * then the timed events — the Agenda widget's day ordering.
 */
internal data class AgendaDayGroup(
    val dayCode: Int,
    val allDayItems: List<AgendaDisplayItem>,
    val openTasks: List<Task>,
    val completedTasks: List<Task>,
    val timedItems: List<AgendaDisplayItem>
) {
    /** Every event item of the day, all-day before timed. */
    val items: List<AgendaDisplayItem>
        get() = allDayItems + timedItems
}

/**
 * The full render model for the agenda LazyColumn: the ordered [groups] and a
 * map from each group's day code to the flat LazyColumn index of its header.
 *
 * The flat layout is, per group, one header item followed by its event cards
 * and task rows, so a group's header index is the count of all preceding
 * headers, cards and rows. The LazyColumn must match this layout, with no
 * items before the first header, or [resolveScrollTargetIndex] lands on the
 * wrong item.
 */
internal data class AgendaListModel(
    val groups: List<AgendaDayGroup>,
    val headerIndexByDayCode: Map<Int, Int>
)

/**
 * Expands [events] into the agenda render model: splits multi-day events per
 * day, keeps entries from today on, dedups by title, startTs and day, and groups
 * and sorts by day then start time, all-day events before timed ones.
 *
 * [tasks] interleave per day by their placement day (`due ?? dtstart`): open
 * tasks sit between the day's all-day and timed events, the completed ones after
 * the open when [includeCompletedTasks]. Open tasks placing before today render
 * in today's group, the Agenda widget's overdue fold, so they stay visible; a
 * completed past-due task is not overdue and drops out with its past day.
 * Undated tasks render nowhere here — the Tasks screen owns them. Pure, so it
 * can run in a `remember`.
 */
internal fun buildAgendaListModel(
    events: List<DisplayEvent>,
    tasks: List<Task>,
    todayDayCode: Int,
    includeCompletedTasks: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault()
): AgendaListModel {
    val expandedItems = events.flatMap { displayEvent ->
        val isMultiDay = displayEvent.endDay > displayEvent.startDay
        if (isMultiDay) {
            val items = mutableListOf<AgendaDisplayItem>()
            var currentDay = displayEvent.startDay
            var dayNum = 1
            val total = Occurrence.calculateDaysBetween(displayEvent.startDay, displayEvent.endDay) + 1
            while (currentDay <= displayEvent.endDay) {
                items.add(AgendaDisplayItem(displayEvent, currentDay, dayNum, total))
                currentDay = Occurrence.incrementDayCode(currentDay)
                dayNum++
            }
            items
        } else {
            listOf(AgendaDisplayItem(displayEvent, displayEvent.startDay, 1, 1))
        }
    }.filter { item ->
        item.displayDay >= todayDayCode
    }.distinctBy { item ->
        "${item.displayEvent.title}-${item.displayEvent.startTs}-${item.displayDay}"
    }.sortedWith(
        compareBy(
            { it.displayDay },                 // primary: by date
            { !it.displayEvent.isAllDay },     // secondary: all-day before timed
            { it.displayEvent.startTs }        // tertiary: by original start time
        )
    )

    val openTasksByDay = LinkedHashMap<Int, MutableList<Task>>()
    val completedTasksByDay = LinkedHashMap<Int, MutableList<Task>>()
    for (task in tasks) {
        val placementDay = taskPlacementDayCode(task, zone) ?: continue
        if (task.isDone) {
            if (includeCompletedTasks && placementDay >= todayDayCode) {
                completedTasksByDay.getOrPut(placementDay) { mutableListOf() }.add(task)
            }
        } else {
            // Overdue folds into today, matching the Agenda widget's task list.
            val renderDay = if (placementDay < todayDayCode) todayDayCode else placementDay
            openTasksByDay.getOrPut(renderDay) { mutableListOf() }.add(task)
        }
    }

    val eventsByDay = expandedItems.groupBy { it.displayDay }
    val groups = (eventsByDay.keys + openTasksByDay.keys + completedTasksByDay.keys)
        .distinct()
        .sorted()
        .map { dayCode ->
            val (allDay, timed) = eventsByDay[dayCode].orEmpty().partition { it.displayEvent.isAllDay }
            AgendaDayGroup(
                dayCode = dayCode,
                allDayItems = allDay,
                openTasks = openTasksByDay[dayCode].orEmpty(),
                completedTasks = completedTasksByDay[dayCode].orEmpty(),
                timedItems = timed
            )
        }

    // Flat index of each header: sum of (1 header + N cards and rows) for preceding groups.
    val headerIndexByDayCode = LinkedHashMap<Int, Int>()
    var index = 0
    for (group in groups) {
        headerIndexByDayCode[group.dayCode] = index
        index += 1 + group.allDayItems.size + group.openTasks.size +
            group.completedTasks.size + group.timedItems.size
    }

    return AgendaListModel(groups, headerIndexByDayCode)
}

/**
 * Returns the LazyColumn item index to scroll to for [dayCode]: that day's
 * header, else the nearest following day's header (so tapping an empty day still
 * scrolls forward), else the last group's header. Returns -1 for an empty model;
 * the caller must skip scrolling then.
 */
internal fun resolveScrollTargetIndex(dayCode: Int, model: AgendaListModel): Int {
    if (model.groups.isEmpty()) return -1
    model.headerIndexByDayCode[dayCode]?.let { return it }
    val following = model.groups.firstOrNull { it.dayCode > dayCode }
    if (following != null) return model.headerIndexByDayCode.getValue(following.dayCode)
    return model.headerIndexByDayCode.getValue(model.groups.last().dayCode)
}
