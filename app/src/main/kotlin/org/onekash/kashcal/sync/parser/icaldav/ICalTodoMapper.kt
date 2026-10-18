package org.onekash.kashcal.sync.parser.icaldav

import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalTodo
import org.onekash.icaldav.model.TodoStatus
import org.onekash.icaldav.model.RRule
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.db.entity.Task
import java.time.ZoneId

/**
 * Maps an icaldav [ICalTodo] to a Room [Task] and back, the task counterpart of
 * [ICalEventMapper].
 *
 * Dates become epoch milliseconds with the zone kept beside them, unknown properties ride as
 * their original lines, and priority keeps its full 0–9 range.
 */
object ICalTodoMapper {

    /**
     * Maps [todo] to a synced Room task.
     *
     * @param rawIcal the original ICS, kept for round trips.
     */
    fun toEntity(
        todo: ICalTodo,
        rawIcal: String?,
        calendarId: Long,
        caldavUrl: String?,
        etag: String?
    ): Task {
        val now = System.currentTimeMillis()
        return Task(
            uid = todo.uid,
            calendarId = calendarId,
            summary = todo.summary?.ifEmpty { null } ?: "Untitled",
            description = todo.description,
            status = todo.status.name,
            priority = todo.priority.coerceIn(0, 9),
            percentComplete = todo.percentComplete.takeIf { it != 0 },
            dueTs = todo.due?.timestamp,
            dueTimezone = todo.due?.timezone?.id,
            dueIsAllDay = todo.due?.isDate == true,
            dtStartTs = todo.dtStart?.timestamp,
            completedTs = todo.completed?.timestamp,
            rrule = todo.rrule?.toICalString(),
            sequence = todo.sequence,
            unknownLines = todo.unknownPropertyLines
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n"),
            rawIcal = rawIcal,
            caldavUrl = caldavUrl,
            etag = etag,
            syncStatus = SyncStatus.SYNCED,
            dtstamp = todo.dtstamp?.timestamp ?: now,
            createdAt = todo.created?.timestamp ?: now,
            updatedAt = now,
            serverModifiedAt = todo.lastModified?.timestamp ?: now
        )
    }

    /**
     * Maps [task] back to an [ICalTodo] for a push. DTSTAMP is now (RFC 5545 §3.8.7.2), the
     * row's SEQUENCE and unknown lines ride along, so only the modeled fields are rewritten.
     */
    fun toICalTodo(task: Task): ICalTodo {
        val now = System.currentTimeMillis()
        return ICalTodo(
            uid = task.uid,
            summary = task.summary,
            description = task.description,
            due = task.dueTs?.let { toICalDateTime(it, task.dueTimezone, task.dueIsAllDay) },
            percentComplete = task.percentComplete ?: 0,
            status = TodoStatus.fromString(task.status),
            priority = task.priority.coerceIn(0, 9),
            dtStart = task.dtStartTs?.let { toICalDateTime(it, task.dueTimezone, task.dueIsAllDay) },
            completed = task.completedTs?.let { toUtcDateTime(it) },
            sequence = task.sequence,
            dtstamp = toUtcDateTime(now),
            created = toUtcDateTime(task.createdAt),
            lastModified = toUtcDateTime(now),
            rrule = task.rrule?.let { RRule.parse(it) },
            unknownPropertyLines = task.unknownLines
                ?.split('\n')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
        )
    }

    /**
     * Rebuilds a zoned or all-day [ICalDateTime]; a null [timezone] means UTC, exactly how
     * [ICalDateTime.parse] stores a Z time or a DATE value.
     */
    private fun toICalDateTime(timestamp: Long, timezone: String?, isAllDay: Boolean): ICalDateTime {
        return if (isAllDay) {
            ICalDateTime(timestamp = timestamp, timezone = null, isUtc = true, isDate = true)
        } else {
            val zone = timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ICalDateTime(
                timestamp = timestamp,
                timezone = zone,
                isUtc = zone == null,
                isDate = false
            )
        }
    }

    private fun toUtcDateTime(timestamp: Long): ICalDateTime =
        ICalDateTime(timestamp = timestamp, timezone = null, isUtc = true, isDate = false)
}
