package org.onekash.kashcal.sync.parser.icaldav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.TodoStatus
import org.onekash.icaldav.parser.ICalGenerator
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.db.entity.Task
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ICalTodoMapper] round trips: a parsed VTODO becomes a Task, the Task becomes a VTODO again,
 * and the modeled fields — priority 0–9, all four STATUS values, all-day and timed DUE, RRULE,
 * unknown property lines, SEQUENCE — survive parse → entity → generate. The push body builder
 * ([ICalTodoMapper.buildPushBody]) is covered below: an edit patch of the stored server body
 * that must preserve everything without a column.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ICalTodoMapperTest {

    private val parser = ICalParser()
    private val generator = ICalGenerator()

    private fun todo(ics: String) =
        parser.parseAllTodos(ics).getOrNull()?.single() ?: error("no VTODO parsed from:\n$ics")

    private fun task(ics: String, calendarId: Long = 7L, caldavUrl: String? = "https://x/t1.ics") =
        ICalTodoMapper.toEntity(todo(ics), rawIcal = ics, calendarId = calendarId, caldavUrl = caldavUrl, etag = "\"e1\"")

    private fun roundTripped(ics: String): org.onekash.icaldav.model.ICalTodo {
        val entity = task(ics)
        val regenerated = generator.generate(
            ICalTodoMapper.toICalTodo(entity),
            preserveDtstamp = true
        )
        return todo(regenerated)
    }

    @Test
    fun `priority 0 through 9 round trips in both directions`() {
        for (priority in 0..9) {
            val ics = vtodo("PRIORITY:$priority")
            val entity = task(ics)
            assertEquals(priority, entity.priority)
            assertEquals(priority, roundTripped(ics).priority)
        }
    }

    @Test
    fun `all four status values round trip`() {
        val byName = mapOf(
            "NEEDS-ACTION" to TodoStatus.NEEDS_ACTION,
            "IN-PROCESS" to TodoStatus.IN_PROCESS,
            "COMPLETED" to TodoStatus.COMPLETED,
            "CANCELLED" to TodoStatus.CANCELLED
        )
        for ((ical, model) in byName) {
            val ics = vtodo("STATUS:$ical")
            assertEquals(model.name, task(ics).status)
            assertEquals(model, roundTripped(ics).status)
        }
    }

    @Test
    fun `an all-day due round trips as a DATE value`() {
        val ics = vtodo("DUE;VALUE=DATE:20261024")
        val entity = task(ics)
        assertTrue(entity.dueIsAllDay)
        assertEquals(null, entity.dueTimezone)

        val out = roundTripped(ics)
        assertTrue(out.due?.isDate == true)
        assertEquals("20261024", out.due?.toLocalDate().toString().replace("-", ""))
    }

    @Test
    fun `a zoned timed due keeps its timezone`() {
        val ics = vtodo("DUE;TZID=Europe/Berlin:20261024T170000")
        val entity = task(ics)
        assertEquals("Europe/Berlin", entity.dueTimezone)
        assertTrue(!entity.dueIsAllDay)

        val out = roundTripped(ics)
        assertEquals("Europe/Berlin", out.due?.timezone?.id)
        assertEquals("20261024T170000", out.due?.toICalString())
    }

    @Test
    fun `a utc timed due keeps its moment`() {
        val ics = vtodo("DUE:20261024T150000Z")
        val entity = task(ics)
        assertEquals(java.time.Instant.parse("2026-10-24T15:00:00Z").toEpochMilli(), entity.dueTs)

        val out = roundTripped(ics)
        assertEquals("20261024T150000Z", out.due?.toICalString())
    }

    @Test
    fun `rrule and sequence survive the round trip`() {
        val ics = vtodo("RRULE:FREQ=WEEKLY;BYDAY=MO,WE", extra = "SEQUENCE:4")
        val entity = task(ics)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE", entity.rrule)
        assertEquals(4, entity.sequence)

        val out = roundTripped(ics)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE", out.rrule?.toICalString())
        assertEquals(4, out.sequence)
    }

    @Test
    fun `unknown property lines ride as stored lines and come back`() {
        val ics = vtodo("X-KASHCAL-WEIGHT:3", extra = "X-OTHER;P=1:kept")
        val entity = task(ics)
        assertNotNull(entity.unknownLines)
        assertTrue(entity.unknownLines!!.contains("X-KASHCAL-WEIGHT:3"))
        assertTrue(entity.unknownLines!!.contains("X-OTHER;P=1:kept"))

        val out = roundTripped(ics)
        assertTrue(
            "unknown lines come back",
            out.unknownPropertyLines.any { it.startsWith("X-KASHCAL-WEIGHT:") } &&
                out.unknownPropertyLines.any { it.startsWith("X-OTHER;") }
        )
    }

    @Test
    fun `completion triple maps to status, percent and timestamp`() {
        val ics = vtodo(
            "STATUS:COMPLETED",
            extra = "PERCENT-COMPLETE:100\nCOMPLETED:20261020T090000Z"
        )
        val entity = task(ics)
        assertEquals("COMPLETED", entity.status)
        assertEquals(100, entity.percentComplete)
        assertEquals(
            java.time.Instant.parse("2026-10-20T09:00:00Z").toEpochMilli(),
            entity.completedTs
        )

        val out = roundTripped(ics)
        assertEquals(TodoStatus.COMPLETED, out.status)
        assertEquals(100, out.percentComplete)
        assertEquals("20261020T090000Z", out.completed?.toICalString())
    }

    @Test
    fun `an unset percent complete maps to null and back to unset`() {
        val ics = vtodo()
        val entity = task(ics)
        assertNull(entity.percentComplete)
        assertEquals(0, roundTripped(ics).percentComplete)
    }

    @Test
    fun `dtstart maps and regenerates when due is absent`() {
        val ics = vtodo("DTSTART;TZID=Europe/Berlin:20261020T090000")
        val entity = task(ics)
        assertEquals(
            java.time.ZonedDateTime.of(2026, 10, 20, 9, 0, 0, 0, java.time.ZoneId.of("Europe/Berlin"))
                .toInstant().toEpochMilli(),
            entity.dtStartTs
        )
        assertEquals(entity.dtStartTs, entity.whenTs)
    }

    @Test
    fun `a pulled task arrives synced with its server identity`() {
        val entity = task(vtodo(), calendarId = 3L, caldavUrl = "https://srv/t/abc.ics")
        assertEquals(SyncStatus.SYNCED, entity.syncStatus)
        assertEquals(3L, entity.calendarId)
        assertEquals("https://srv/t/abc.ics", entity.caldavUrl)
        assertEquals("\"e1\"", entity.etag)
    }

    // ========== buildPushBody (the push patches the stored body) ==========

    /** The generator TaskPushStrategy holds; same PRODID as event pushes. */
    private val pushGenerator = ICalGenerator(prodId = "-//KashCal//KashCal 2.0//EN")

    /** [task] over the master VTODO of a resource that may hold more components. */
    private fun masterTask(ics: String): Task {
        val master = parser.parseAllTodos(ics).getOrNull()
            ?.firstOrNull { it.recurrenceId == null }
            ?: error("no master VTODO parsed from:\n$ics")
        return ICalTodoMapper.toEntity(
            master, rawIcal = ics, calendarId = 7L, caldavUrl = "https://x/t1.ics", etag = "\"e1\""
        )
    }

    private fun ics(vararg lines: String): String =
        lines.joinToString("\r\n", postfix = "\r\n")

    @Test
    fun `an edit push preserves unmodeled properties and the VALARM of the stored component`() {
        val raw = ics(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Other//Client//EN",
            "BEGIN:VTODO",
            "UID:roundtrip@example.test",
            "DTSTAMP:20261001T120000Z",
            "SUMMARY:Round trip",
            "STATUS:NEEDS-ACTION",
            "DUE:20261024T150000Z",
            "LOCATION:Office",
            "CATEGORIES:work,urgent",
            "ORGANIZER;CN=Bob:mailto:bob@example.com",
            "ATTENDEE;CN=Alice;PARTSTAT=NEEDS-ACTION:mailto:alice@example.com",
            "URL:https://example.com/task",
            "GEO:37.386013;-122.082932",
            "CLASS:PRIVATE",
            "X-CUSTOM-PROP:keep-me",
            "BEGIN:VALARM",
            "ACTION:DISPLAY",
            "TRIGGER:-PT30M",
            "DESCRIPTION:Reminder",
            "END:VALARM",
            "END:VTODO",
            "END:VCALENDAR"
        )
        val edited = masterTask(raw).copy(
            summary = "Renamed",
            status = "COMPLETED",
            percentComplete = 100,
            completedTs = 1_764_000_000_000L
        )

        val body = ICalTodoMapper.buildPushBody(edited, raw, pushGenerator)
            ?: error("buildPushBody returned null")

        // Modeled columns are rewritten.
        assertTrue(body.contains("SUMMARY:Renamed"))
        assertTrue(body.contains("STATUS:COMPLETED"))
        assertTrue(!body.contains("SUMMARY:Round trip"))
        assertTrue(!body.contains("STATUS:NEEDS-ACTION"))
        // Everything without a column rides verbatim.
        assertTrue(body.contains("LOCATION:Office"))
        assertTrue(body.contains("CATEGORIES:work,urgent"))
        assertTrue(body.contains("ORGANIZER;CN=Bob:mailto:bob@example.com"))
        assertTrue(body.contains("ATTENDEE;CN=Alice;PARTSTAT=NEEDS-ACTION:mailto:alice@example.com"))
        assertTrue(body.contains("URL:https://example.com/task"))
        assertTrue(body.contains("GEO:37.386013;-122.082932"))
        assertTrue(body.contains("CLASS:PRIVATE"))
        assertTrue(body.contains("X-CUSTOM-PROP:keep-me"))
        // The VALARM sub-component survives.
        assertTrue(body.contains("BEGIN:VALARM"))
        assertTrue(body.contains("TRIGGER:-PT30M"))
        assertTrue(body.contains("END:VALARM"))

        // The patched body parses back to the edited task plus the preserved properties.
        val reparsed = parser.parseAllTodos(body).getOrNull()?.single()
        assertNotNull(reparsed)
        assertEquals("Renamed", reparsed!!.summary)
        assertEquals(TodoStatus.COMPLETED, reparsed.status)
        assertEquals("Office", reparsed.location)
        assertEquals(listOf("work", "urgent"), reparsed.categories)
        assertEquals("alice@example.com", reparsed.attendees.single().email)
    }

    @Test
    fun `an edit push keeps cohabiting VEVENT, exception VTODO and VTIMEZONE components`() {
        val raw = ics(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Test//EN",
            "BEGIN:VTIMEZONE",
            "TZID:Europe/Berlin",
            "BEGIN:STANDARD",
            "DTSTART:19701025T030000",
            "TZOFFSETFROM:+0200",
            "TZOFFSETTO:+0100",
            "END:STANDARD",
            "END:VTIMEZONE",
            "BEGIN:VEVENT",
            "UID:event-9@example.test",
            "DTSTAMP:20261001T120000Z",
            "DTSTART:20260601T120000Z",
            "DTEND:20260601T130000Z",
            "SUMMARY:Cohabiting event",
            "END:VEVENT",
            "BEGIN:VTODO",
            "UID:roundtrip@example.test",
            "DTSTAMP:20261001T120000Z",
            "SUMMARY:Round trip",
            "STATUS:NEEDS-ACTION",
            "END:VTODO",
            "BEGIN:VTODO",
            "UID:roundtrip@example.test",
            "DTSTAMP:20261001T120000Z",
            "RECURRENCE-ID;TZID=Europe/Berlin:20261026T120000",
            "SUMMARY:Exception day",
            "STATUS:COMPLETED",
            "END:VTODO",
            "END:VCALENDAR"
        )
        val edited = masterTask(raw).copy(summary = "Renamed")

        val body = ICalTodoMapper.buildPushBody(edited, raw, pushGenerator)
            ?: error("buildPushBody returned null")

        // The cohabiting VEVENT rides untouched.
        assertTrue(body.contains("SUMMARY:Cohabiting event"))
        assertTrue(body.contains("BEGIN:VEVENT"))
        // The stored VTIMEZONE rides untouched.
        assertTrue(body.contains("TZID:Europe/Berlin"))
        // Exactly two VTODOs: the replaced master and the preserved exception.
        assertEquals(2, Regex("BEGIN:VTODO").findAll(body).count())
        assertTrue(body.contains("RECURRENCE-ID;TZID=Europe/Berlin:20261026T120000"))
        assertTrue(body.contains("SUMMARY:Exception day"))
        assertTrue(body.contains("SUMMARY:Renamed"))
        assertTrue(!body.contains("SUMMARY:Round trip"))

        val reparsed = parser.parseAllTodos(body).getOrNull().orEmpty()
        assertEquals(2, reparsed.size)
        assertEquals("Renamed", reparsed.first { it.recurrenceId == null }.summary)
        assertEquals("Exception day", reparsed.first { it.recurrenceId != null }.summary)
    }

    @Test
    fun `an unchanged all-day DTSTART without DUE survives a toggle-done push`() {
        val raw = ics(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Test//EN",
            "BEGIN:VTODO",
            "UID:roundtrip@example.test",
            "DTSTAMP:20261001T120000Z",
            "SUMMARY:Round trip",
            "STATUS:NEEDS-ACTION",
            "DTSTART;VALUE=DATE:20261025",
            "END:VTODO",
            "END:VCALENDAR"
        )
        val edited = masterTask(raw).copy(
            status = "COMPLETED",
            percentComplete = 100,
            completedTs = 1_764_000_000_000L
        )

        val body = ICalTodoMapper.buildPushBody(edited, raw, pushGenerator)
            ?: error("buildPushBody returned null")

        // The original DATE form is kept verbatim, not rebuilt as a UTC date-time.
        assertTrue(body.contains("DTSTART;VALUE=DATE:20261025"))
        assertTrue("DTSTART must stay a DATE", !Regex("DTSTART:\\d{8}T").containsMatchIn(body))
        assertTrue(body.contains("STATUS:COMPLETED"))
    }

    @Test
    fun `a moved DTSTART is regenerated instead of kept`() {
        val raw = ics(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Test//EN",
            "BEGIN:VTODO",
            "UID:roundtrip@example.test",
            "DTSTAMP:20261001T120000Z",
            "SUMMARY:Round trip",
            "STATUS:NEEDS-ACTION",
            "DTSTART;VALUE=DATE:20261025",
            "END:VTODO",
            "END:VCALENDAR"
        )
        val edited = masterTask(raw).copy(dtStartTs = masterTask(raw).dtStartTs!! + 3_600_000L)

        val body = ICalTodoMapper.buildPushBody(edited, raw, pushGenerator)
            ?: error("buildPushBody returned null")

        assertTrue("moved DTSTART must be regenerated", Regex("DTSTART:\\d{8}T\\d{6}Z").containsMatchIn(body))
        assertTrue(!body.contains("DTSTART;VALUE=DATE:20261025"))
    }

    @Test
    fun `a zoned DUE gets a VTIMEZONE when the stored body carries none`() {
        val raw = ics(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Test//EN",
            "BEGIN:VTODO",
            "UID:roundtrip@example.test",
            "DTSTAMP:20261001T120000Z",
            "SUMMARY:Round trip",
            "DUE;TZID=Europe/Berlin:20261024T170000",
            "END:VTODO",
            "END:VCALENDAR"
        )
        val edited = masterTask(raw).copy(summary = "Renamed")

        val body = ICalTodoMapper.buildPushBody(edited, raw, pushGenerator)
            ?: error("buildPushBody returned null")

        assertTrue(body.contains("DUE;TZID=Europe/Berlin:20261024T170000"))
        assertTrue("missing VTIMEZONE must be added", body.contains("BEGIN:VTIMEZONE"))
        assertTrue(body.contains("TZID:Europe/Berlin"))
    }

    @Test
    fun `unmergeable stored bodies return null so the caller regenerates`() {
        val valid = ics(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//EN",
            "BEGIN:VTODO", "UID:roundtrip@example.test", "DTSTAMP:20261001T120000Z",
            "SUMMARY:Round trip", "END:VTODO", "END:VCALENDAR"
        )
        val task = masterTask(valid)

        assertNull(ICalTodoMapper.buildPushBody(task, null, pushGenerator))
        assertNull(ICalTodoMapper.buildPushBody(task, "not an ics body at all", pushGenerator))
        assertNull(ICalTodoMapper.buildPushBody(task, valid.replace("roundtrip@", "other-uid@"), pushGenerator))
    }

    private fun vtodo(prop: String? = null, extra: String? = null): String = listOf(
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//KashCal//Test//EN",
        "BEGIN:VTODO",
        "UID:roundtrip@example.test",
        "DTSTAMP:20261001T120000Z",
        "SUMMARY:Round trip",
        prop,
        extra,
        "END:VTODO",
        "END:VCALENDAR",
        ""
    ).filterNotNull().joinToString("\r\n")
}
