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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ICalTodoMapper] round trips: a parsed VTODO becomes a Task, the Task becomes a VTODO again,
 * and the modeled fields — priority 0–9, all four STATUS values, all-day and timed DUE, RRULE,
 * unknown property lines, SEQUENCE — survive parse → entity → generate.
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
