package org.onekash.kashcal.widget

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.onekash.kashcal.data.contacts.ContactEventTitleFormatter
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.db.entity.Task
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.model.VisibilityDomain
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.domain.reader.TaskReader
import org.onekash.kashcal.ui.components.taskReadsAsAllDay
import org.onekash.kashcal.util.DateTimeUtils
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads events and tasks for the Agenda, Week, Month, Upcoming and Tasks widgets. Events come
 * from visible calendars, Room and device calendars merged by [DisplayEventRepository]; tasks
 * come from [TaskReader] in the schedule visibility domain — widgets follow
 * [VisibilityDomain.SCHEDULE], per its KDoc.
 *
 * Every method sorts a day's events all-day first, then by start time, and marks past events
 * ([WidgetEvent.isPast]): Agenda and Week rows render them grayed and struck through, and the
 * Upcoming widget hides them. Task methods sort by `due ?? dtstart` with undated last.
 */
@Singleton
class WidgetDataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val displayEventRepository: DisplayEventRepository,
    private val taskReader: TaskReader
) {
    /** One event occurrence as a widget renders it. */
    data class WidgetEvent(
        val eventId: Long,
        val occurrenceStartTs: Long,
        val title: String,
        val startTs: Long,
        val endTs: Long,
        val isAllDay: Boolean,
        val calendarColor: Int,
        val isPast: Boolean,
        val isDeviceEvent: Boolean,
        val startDay: Int,
        val isCancelled: Boolean = false,
        val isFree: Boolean = false,
        val endDay: Int = startDay
    ) {
        /** Multi-day events span more than one day cell and render as continuous bars. */
        val isMultiDay: Boolean get() = startDay != endDay

        /** Stable identity across the day buckets the event appears in (for span dedup). */
        val spanKey: String get() = "$eventId:$occurrenceStartTs"
    }

    /**
     * One open task as a widget renders it, placed at `due ?? dtstart`. A task without either
     * date has [whenTs] and [dayCode] null/0 and only the Upcoming and Tasks widgets list it,
     * as their undated tail.
     */
    data class WidgetTask(
        val taskId: Long,
        val title: String,
        /** `dueTs ?: dtStartTs`, epoch millis; null when the task has neither. */
        val whenTs: Long?,
        /** Whether the due date is a DATE (all-day); a dtstart-placed task is treated as timed. */
        val isAllDay: Boolean,
        /** YYYYMMDD day code of [whenTs]; 0 when undated. */
        val dayCode: Int,
        /** RFC 5545 PRIORITY, 0 undefined. Only a stable-order tiebreak today. */
        val priority: Int = 0
    )

    /** Returns today's events, for the Agenda widget. */
    suspend fun getTodayEvents(): List<WidgetEvent> {
        val now = System.currentTimeMillis()
        val todayCode = DateTimeUtils.eventTsToDayCode(now, isAllDay = false)

        val eventsMap = displayEventRepository.getDisplayEventsGroupedByDayOnce(todayCode, todayCode)
        val todayEvents = eventsMap[todayCode] ?: return emptyList()

        return todayEvents.map { toWidgetEvent(it) }
            .sortedWith(compareBy({ !it.isAllDay }, { it.startTs }))
    }

    /**
     * Returns the events of the 7 days from today, for the Week widget, keyed by dayCode
     * (YYYYMMDD). Always exactly 7 entries, empty days included. A multi-day event appears on
     * each day it spans within the window.
     */
    suspend fun getWeekEvents(): Map<Int, List<WidgetEvent>> {
        // today, tomorrow, ..., today + 6
        val dayCodes = (0..6).map { offset ->
            val date = LocalDate.now().plusDays(offset.toLong())
            date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
        }

        val startDayCode = dayCodes.first()
        val endDayCode = dayCodes.last()

        val eventsMap = displayEventRepository.getDisplayEventsGroupedByDayOnce(startDayCode, endDayCode)

        // Every day gets an entry, even with no events.
        return dayCodes.associateWith { dayCode ->
            eventsMap[dayCode].orEmpty()
                .map { toWidgetEvent(it) }
                .sortedWith(compareBy({ !it.isAllDay }, { it.startTs }))
        }
    }

    /**
     * Returns the events from [startDayCode] to [endDayCode] (YYYYMMDD, inclusive), keyed by
     * dayCode, for the Month widget's grid range and the Upcoming widget's horizon. Only days
     * with events have an entry.
     */
    suspend fun getEventsInRange(startDayCode: Int, endDayCode: Int): Map<Int, List<WidgetEvent>> {
        val eventsMap = displayEventRepository.getDisplayEventsGroupedByDayOnce(startDayCode, endDayCode)

        return eventsMap.mapValues { (_, displayEvents) ->
            displayEvents
                .map { toWidgetEvent(it) }
                .sortedWith(compareBy({ !it.isAllDay }, { it.startTs }))
        }
    }

    /**
     * Observes every task of schedule-visible calendars, unordered beyond the DAO's own
     * ordering. The Flow half of the events' Flow + suspend-snapshot pair; nothing observes it
     * yet, but widgets that gain live data read through it.
     */
    fun observeTasksForWidget(): Flow<List<Task>> = taskReader.observeTasks(VisibilityDomain.SCHEDULE)

    /**
     * Returns up to [limit] open tasks — [Task.isOpen], no PENDING_DELETE rows — of
     * schedule-visible calendars for the Tasks and Upcoming widgets: due or starting first,
     * undated last, priority and then title breaking ties so the order is stable.
     */
    suspend fun getOpenTasks(limit: Int): List<WidgetTask> {
        if (limit <= 0) return emptyList()
        return taskReader.observeTasks(VisibilityDomain.SCHEDULE).first()
            .asSequence()
            .filter { it.isOpen && it.syncStatus != SyncStatus.PENDING_DELETE }
            .map { toWidgetTask(it) }
            .sortedWith(
                compareBy(
                    { it.whenTs == null },
                    { it.whenTs },
                    { if (it.priority == 0) Int.MAX_VALUE else it.priority },
                    { it.title.lowercase() }
                )
            )
            .take(limit)
            .toList()
    }

    /**
     * Returns the open tasks on today or overdue, for the Agenda widget: a task stands in its
     * due (or dtstart) day, and an open task whose day has passed still claims today's list
     * until it is done, as the in-app agenda does. Undated tasks never appear here.
     */
    suspend fun getTodayTasks(): List<WidgetTask> {
        val todayCode = DateTimeUtils.eventTsToDayCode(System.currentTimeMillis(), isAllDay = false)
        return taskReader.observeTasks(VisibilityDomain.SCHEDULE).first()
            .asSequence()
            .filter { it.isOpen && it.syncStatus != SyncStatus.PENDING_DELETE }
            .map { toWidgetTask(it) }
            .filter { it.dayCode in 1..todayCode }
            .sortedWith(
                compareBy(
                    { it.whenTs },
                    { if (it.priority == 0) Int.MAX_VALUE else it.priority },
                    { it.title.lowercase() }
                )
            )
            .toList()
    }

    /** Maps a [Task] to a [WidgetTask]; the day code follows the all-day flag, as events do. */
    private fun toWidgetTask(task: Task): WidgetTask {
        val whenTs = task.whenTs
        val isAllDay = taskReadsAsAllDay(task)
        return WidgetTask(
            taskId = task.id,
            title = task.summary,
            whenTs = whenTs,
            isAllDay = isAllDay,
            dayCode = whenTs?.let { DateTimeUtils.eventTsToDayCode(it, isAllDay) } ?: 0,
            priority = task.priority
        )
    }

    /**
     * Maps a [DisplayEvent] to a [WidgetEvent]. A set event color wins over the calendar color;
     * when the winner is 0, [DEFAULT_CALENDAR_COLOR] is used.
     */
    private fun toWidgetEvent(displayEvent: DisplayEvent): WidgetEvent {
        return WidgetEvent(
            eventId = when (displayEvent) {
                is DisplayEvent.Room -> displayEvent.event.id
                is DisplayEvent.Device -> displayEvent.instance.eventId
            },
            occurrenceStartTs = displayEvent.startTs,
            title = when (displayEvent) {
                is DisplayEvent.Room -> ContactEventTitleFormatter.format(
                    displayEvent.event, displayEvent.startTs, context.resources
                )
                is DisplayEvent.Device -> displayEvent.title
            },
            startTs = displayEvent.startTs,
            endTs = displayEvent.endTs,
            isAllDay = displayEvent.isAllDay,
            calendarColor = (displayEvent.eventColor ?: displayEvent.calendarColor).takeIf { it != 0 } ?: DEFAULT_CALENDAR_COLOR,
            isPast = DateTimeUtils.isEventPast(displayEvent.endTs, displayEvent.endDay, displayEvent.isAllDay),
            isDeviceEvent = displayEvent is DisplayEvent.Device,
            startDay = displayEvent.startDay,
            isCancelled = displayEvent.isCancelled,
            isFree = displayEvent.isFree,
            endDay = displayEvent.endDay
        )
    }

    companion object {
        /** Material Blue 500. */
        private const val DEFAULT_CALENDAR_COLOR = 0xFF2196F3.toInt()
    }
}
