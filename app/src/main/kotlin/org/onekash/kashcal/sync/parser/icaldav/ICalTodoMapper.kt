package org.onekash.kashcal.sync.parser.icaldav

import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalTodo
import org.onekash.icaldav.model.TodoStatus
import org.onekash.icaldav.model.RRule
import org.onekash.icaldav.parser.ICalGenerator
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.db.entity.Task
import java.time.ZoneId

/**
 * Maps an icaldav [ICalTodo] to a Room [Task] and back, the task counterpart of
 * [ICalEventMapper].
 *
 * Dates become epoch milliseconds with the zone kept beside them, unknown properties ride as
 * their original lines, and priority keeps its full 0–9 range. [buildPushBody] turns a row
 * plus its stored server body into the PUT body, patching the stored body like [IcsPatcher]
 * does for events instead of regenerating a single-VTODO file.
 */
object ICalTodoMapper {

    /** Parses stored bodies for [buildPushBody]; default registry, as [IcsPatcher] holds one. */
    private val pushParser by lazy { ICalParser() }

    /**
     * The VTODO properties a [Task] column models. [buildPushBody] replaces exactly these in
     * the stored component; every other property line is re-emitted verbatim, so properties
     * the parser handles but no column holds — ATTENDEE, ORGANIZER, CATEGORIES, LOCATION,
     * URL, GEO, CLASS — and everything unknown survive a push.
     */
    private val modeledPropertyNames = setOf(
        "UID", "DTSTAMP", "DTSTART", "DUE", "COMPLETED", "RRULE",
        "SUMMARY", "DESCRIPTION", "STATUS", "SEQUENCE", "PRIORITY", "PERCENT-COMPLETE",
        "CREATED", "LAST-MODIFIED"
    )

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

    /**
     * Builds the PUT body for [task] by patching the stored server body [rawIcal], the task
     * counterpart of [IcsPatcher.patch]: a PUT replaces the whole resource, so the body must
     * carry everything the stored resource holds, not just this task's columns.
     *
     * - The task's master VTODO (its UID, no RECURRENCE-ID) is regenerated from the row's
     *   modeled columns — summary, status, priority, percent, due, dtstart, description,
     *   rrule, completed, sequence, stamps — and spliced in where the stored one sat. Every
     *   other property line of the stored component and every nested component (VALARM) is
     *   re-emitted verbatim, folding included, so ATTENDEE, ORGANIZER, CATEGORIES, LOCATION,
     *   URL, GEO, CLASS, X-* and alarms survive the push.
     * - Cohabiting components — VEVENTs, other VTODOs, RECURRENCE-ID exception VTODOs of the
     *   same task, VTIMEZONEs — are re-emitted verbatim too. A VTIMEZONE the regenerated
     *   DUE/DTSTART timezone needs is added when the stored body carries none for it.
     * - DTSTART has no form columns of its own (a row stores only the instant; the form came
     *   from DUE's columns), so when the instant is unchanged the stored DTSTART line is kept
     *   verbatim, VALUE=DATE and TZID parameters included; it is regenerated only when the
     *   value actually moved.
     *
     * @return the patched body, or null when the merge can't run — no [rawIcal], a body that
     *   won't parse or line up with the row, no stored master VTODO with the task's UID. The
     *   caller then falls back to full regeneration from the columns alone.
     */
    fun buildPushBody(task: Task, rawIcal: String?, generator: ICalGenerator): String? {
        if (rawIcal.isNullOrBlank()) return null

        // The parsed master decides the DTSTART comparison and proves the body parses.
        val parsedMaster = pushParser.parseAllTodos(rawIcal).getOrNull()
            ?.firstOrNull { it.uid == task.uid && it.recurrenceId == null }
            ?: return null

        val stored = splitVCalendar(rawIcal) ?: return null
        val storedMaster = stored.entries.filterIsInstance<RawComponent>().firstOrNull {
            it.name == "VTODO" &&
                it.propertyValue("UID") == task.uid &&
                it.property("RECURRENCE-ID") == null
        } ?: return null

        // Only modeled columns are regenerated; the row's unknownLines ride in the stored
        // component's preserved lines instead, so they are not written twice.
        val regenerated = generator.generate(
            toICalTodo(task).copy(unknownPropertyLines = emptyList()),
            preserveDtstamp = false
        )
        val regeneratedCalendar = splitVCalendar(regenerated) ?: return null
        val regeneratedTodo = regeneratedCalendar.entries.filterIsInstance<RawComponent>()
            .firstOrNull { it.name == "VTODO" }
            ?: return null
        val regeneratedTimezones = regeneratedCalendar.entries.filterIsInstance<RawComponent>()
            .filter { it.name == "VTIMEZONE" }

        // Keep the stored DTSTART line when the instant didn't move: the row has no columns
        // for its form, and regenerating it would rewrite a DATE as a UTC date-time.
        val storedDtStart = storedMaster.property("DTSTART")
        val keepStoredDtStart =
            storedDtStart != null && parsedMaster.dtStart?.timestamp == task.dtStartTs

        val replacement = buildString {
            append("BEGIN:VTODO\r\n")
            for (prop in regeneratedTodo.entries) {
                if (prop is RawProperty) {
                    append(
                        if (prop.name == "DTSTART" && keepStoredDtStart) storedDtStart!!.raw
                        else prop.raw
                    ).append("\r\n")
                }
            }
            for (entry in storedMaster.entries) {
                when (entry) {
                    is RawComponent -> append(render(entry))
                    is RawProperty -> if (entry.name !in modeledPropertyNames) {
                        append(entry.raw).append("\r\n")
                    }
                }
            }
            append("END:VTODO\r\n")
        }

        // A zoned regenerated DUE (or moved DTSTART) may name a TZID the stored body never
        // carried; add the generator's VTIMEZONE for it, ahead of the first component.
        val neededTzids = regeneratedTodo.entries
            .filterIsInstance<RawProperty>()
            .flatMapTo(mutableSetOf()) { tzidParam.findAll(it.logical).map { m -> m.groupValues[1] } }
        val storedTzids = stored.entries
            .filterIsInstance<RawComponent>()
            .filter { it.name == "VTIMEZONE" }
            .mapNotNullTo(mutableSetOf()) { it.propertyValue("TZID") }
        val missingTimezones = regeneratedTimezones.filter {
            val tzid = it.propertyValue("TZID")
            tzid != null && tzid in neededTzids && tzid !in storedTzids
        }

        return buildString {
            append(stored.beginRaw).append("\r\n")
            var timezonesFlushed = missingTimezones.isEmpty()
            for (entry in stored.entries) {
                if (entry is RawComponent) {
                    if (!timezonesFlushed) {
                        missingTimezones.forEach { append(render(it)) }
                        timezonesFlushed = true
                    }
                    if (entry === storedMaster) append(replacement) else append(render(entry))
                } else if (entry is RawProperty) {
                    append(entry.raw).append("\r\n")
                }
            }
            append(stored.endRaw ?: "END:${stored.name}").append("\r\n")
        }
    }

    /** A TZID parameter, as in `DUE;TZID=Europe/Berlin:...`; IANA ids never carry the quoted set. */
    private val tzidParam = Regex(";TZID=([^;:\"]+)")

    /** One unfolded content line, with the folded text it was read from (CRLF-joined). */
    private class RawProperty(val name: String, val logical: String, val raw: String)

    /**
     * One BEGIN/END-delimited component: its property lines and nested components in document
     * order. [beginRaw]/[endRaw] keep the original delimiters so a preserved component is
     * re-emitted byte for byte (line breaks normalized to CRLF).
     */
    private class RawComponent(name: String, val beginRaw: String) {
        val name = name.uppercase()
        val entries = mutableListOf<Any>()
        var endRaw: String? = null

        /** The first property named [name], or null. */
        fun property(name: String): RawProperty? =
            entries.filterIsInstance<RawProperty>().firstOrNull { it.name == name.uppercase() }

        /** The value of the first property named [name] (text after the first ':'), or null. */
        fun propertyValue(name: String): String? =
            property(name)?.logical?.substringAfter(':', "")?.takeIf { it.isNotEmpty() }
    }

    /**
     * Splits [ical] into the component tree of its single VCALENDAR, unfolding continuation
     * lines while keeping each logical line's original folded text. Returns null for anything
     * that isn't exactly one balanced VCALENDAR with no stray content.
     */
    private fun splitVCalendar(ical: String): RawComponent? {
        val root = RawComponent("(root)", "")
        val stack = ArrayDeque<RawComponent>()
        stack.addLast(root)

        var logical = StringBuilder()
        var raw = StringBuilder()
        var haveCurrent = false
        var balanced = true

        fun flush() {
            if (!haveCurrent) return
            val line = logical.toString()
            when (propertyNameOf(line)) {
                "BEGIN" -> {
                    val component = RawComponent(line.substringAfter(':', ""), raw.toString())
                    stack.lastOrNull()?.entries?.add(component)
                    stack.addLast(component)
                }
                "END" -> {
                    val component = stack.removeLastOrNull()
                    if (component == null || component === root ||
                        component.name != line.substringAfter(':', "").trim().uppercase()
                    ) {
                        balanced = false
                    } else {
                        component.endRaw = raw.toString()
                    }
                }
                else -> stack.lastOrNull()?.entries?.add(
                    RawProperty(propertyNameOf(line), line, raw.toString())
                )
            }
        }

        for (physical in ical.trimStart('\uFEFF').split(Regex("\r\n|\r|\n"))) {
            if (physical.isEmpty()) continue
            if ((physical[0] == ' ' || physical[0] == '\t') && haveCurrent) {
                logical.append(physical, 1, physical.length)
                raw.append("\r\n").append(physical)
            } else {
                flush()
                logical = StringBuilder(physical)
                raw = StringBuilder(physical)
                haveCurrent = true
            }
        }
        flush()

        if (!balanced || stack.size != 1) return null
        val components = root.entries.filterIsInstance<RawComponent>()
        if (root.entries.size != components.size || components.size != 1 ||
            components[0].name != "VCALENDAR"
        ) return null
        return components[0]
    }

    /** Re-emits a stored component and everything in it verbatim, CRLF line breaks. */
    private fun render(component: RawComponent): String = buildString {
        append(component.beginRaw).append("\r\n")
        for (entry in component.entries) {
            when (entry) {
                is RawProperty -> append(entry.raw).append("\r\n")
                is RawComponent -> append(render(entry))
            }
        }
        append(component.endRaw ?: "END:${component.name}").append("\r\n")
    }

    /** The upper-case property name: the text before the first ';' or ':'. */
    private fun propertyNameOf(line: String): String {
        val end = line.indexOfFirst { it == ';' || it == ':' }
        return (if (end >= 0) line.substring(0, end) else line).trim().uppercase()
    }
}
