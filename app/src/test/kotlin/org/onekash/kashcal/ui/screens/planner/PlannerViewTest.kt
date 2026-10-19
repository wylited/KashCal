package org.onekash.kashcal.ui.screens.planner

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.LayoutDirection
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.planner.PlannerTestEvents
import org.onekash.kashcal.domain.planner.PlannerTestEvents.at
import org.onekash.kashcal.domain.planner.MIN_PLANNER_ZOOM
import org.onekash.kashcal.domain.planner.PlannerWeekState
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.theme.ColorSource
import org.onekash.kashcal.ui.theme.KashCalTheme
import org.onekash.kashcal.ui.theme.ThemeMode
import org.onekash.kashcal.util.DateTimeUtils
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Tests [PlannerView] as the user sees it: the eight boxes and their order, the grid shape, the
 * day headers, which taps go where, the "+N" count in a full box's corner, one-line titles, the
 * loading and free-day states, the cancelled and "was" rows, that no row is clipped at any font
 * scale or zoom, and that a pinch zooms while a swipe pages. Text is measured to fit rows, so
 * graphics are native.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class PlannerViewTest {

    @get:Rule
    val rule = createComposeRule()

    private val zone = ZoneId.of("America/New_York")
    private lateinit var savedZone: TimeZone

    private val mon = LocalDate.of(2026, 10, 12)
    private val thu = mon.plusDays(3)
    private val today = PlannerTestEvents.dayCode(thu)
    private val now = at(thu, 15, 50, zone = zone)
    private val center = WeekViewUtils.CENTER_WEEK_PAGE

    @Before
    fun setup() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }

    @After
    fun tearDown() = TimeZone.setDefault(savedZone)

    private fun stateOf(
        vararg events: DisplayEvent,
        start: LocalDate = mon.minusDays(7),
        end: LocalDate = mon.plusDays(13),
        roomSeries: Map<Long, Boolean> = emptyMap(),
    ): PlannerWeekState {
        val codes = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }
            .map { PlannerTestEvents.dayCode(it) }.toList()
        val byDay = PlannerTestEvents.bucketByDay(codes, events.toList()).mapValues { it.value.toImmutableList() }
        return PlannerWeekState(
            eventsByDayCode = byDay.toPersistentMap(),
            rangeStartDayCode = PlannerTestEvents.dayCode(start),
            rangeEndDayCode = PlannerTestEvents.dayCode(end),
            roomSeriesAllDayById = roomSeries.toPersistentMap(),
        )
    }

    private fun timed(id: Long, date: LocalDate, hour: Int, title: String, status: String = "CONFIRMED") =
        PlannerTestEvents.room(
            id = id, startTs = at(date, hour, zone = zone), endTs = at(date, hour, zone = zone) + PlannerTestEvents.HOUR,
            zone = zone, title = title, status = status,
        )

    /** The theme's colours, read inside the composition. */
    private var scheme: ColorScheme? = null

    private fun headerColor(date: LocalDate) = layoutOf(placedText(label(date), PlannerTestEvents.dayCode(date))).layoutInput.style.color

    private class Calls {
        val emptyTaps = mutableListOf<Long>()
        val dayClicks = mutableListOf<Long>()
        val headers = mutableListOf<LocalDate>()
        val pages = mutableListOf<Int>()
        val zooms = mutableListOf<Float>()
    }

    private fun show(
        state: PlannerWeekState?,
        stateHolder: MutableState<PlannerWeekState?>? = null,
        calls: Calls = Calls(),
        anchor: Int = PlannerTestEvents.dayCode(mon),
        firstDay: Int = Calendar.MONDAY,
        widthDp: Int = 360,
        isCompactHeight: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        dark: Boolean = false,
        initialZoom: Float = 1f,
        todayCode: Int = today,
    ): Calls {
        rule.setContent {
            var zoom by androidx.compose.runtime.remember { mutableFloatStateOf(initialZoom) }
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.FontScale(fontScale) then
                    DeviceConfigurationOverride.LayoutDirection(
                        if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr
                    )
            ) {
                KashCalTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, colorSource = ColorSource.SEED) {
                    scheme = MaterialTheme.colorScheme
                    PlannerView(
                        plannerState = stateHolder?.value ?: state,
                        anchorDayCode = anchor,
                        pagerPosition = center,
                        pendingNavigateToPage = null,
                        firstDayOfWeek = firstDay,
                        todayDayCode = todayCode,
                        nowMs = now,
                        timePattern = "h:mm a",
                        showEventEmojis = false,
                        zoom = zoom,
                        widthDp = widthDp,
                        isCompactHeight = isCompactHeight,
                        onPageChanged = { calls.pages += it },
                        onNavigationConsumed = {},
                        onEmptyDayTap = { calls.emptyTaps += it },
                        onDayClick = { calls.dayClicks += it },
                        onDayHeaderClick = { calls.headers += it },
                        onZoomChange = { calls.zooms += it; zoom = it },
                        tasksByDayCode = emptyMap(),
                        calendarColorById = emptyMap(),
                    )
                }
            }
        }
        rule.waitForIdle()
        return calls
    }

    // Built here, not through the view's own formatter, so a change to the header shows up.
    private fun label(date: LocalDate): String {
        val locale = Locale.getDefault()
        return DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("EEEMMMd", locale), locale).format(date)
    }
    private fun inBox(dayCode: Int) = hasAnyAncestor(hasTestTag("planner_day_$dayCode"))
    private fun hasClickLabel(label: String) = SemanticsMatcher("click label $label") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    /** Returns the placed node with [text] in [dayCode]'s box, skipping the measuring copies. */
    private fun placedText(text: String, dayCode: Int): SemanticsNode =
        rule.onAllNodes(hasText(text) and inBox(dayCode), useUnmergedTree = true)
            .fetchSemanticsNodes().single { it.layoutInfo.isPlaced }

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single()
    }

    private fun rowsIn(dayCode: Int) =
        rule.onAllNodes(hasTestTag("planner_row") and inBox(dayCode)).fetchSemanticsNodes()

    private fun oneLineRowsIn(dayCode: Int) =
        rule.onAllNodes(hasTestTag("planner_row_one_line") and inBox(dayCode)).fetchSemanticsNodes()

    /** Every row a box shows: full rows, ribbons and a one-line last row. */
    private fun shownRowsIn(dayCode: Int) = rowsIn(dayCode) + oneLineRowsIn(dayCode)
    private fun box(dayCode: Int): SemanticsNodeInteraction = rule.onNodeWithTag("planner_day_$dayCode")
    private fun bounds(node: SemanticsNodeInteraction): Rect = node.fetchSemanticsNode().boundsInRoot
    private fun localDate(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    @Test
    fun `a Monday-first week shows seven day boxes in order and the summary box last`() {
        show(stateOf())
        val days = (0..6).map { PlannerTestEvents.dayCode(mon.plusDays(it.toLong())) }
        days.forEach { box(it).assertExists() }
        rule.onNodeWithTag("planner_summary").assertExists()
        rule.onNodeWithText(label(mon)).assertExists()
        // Two columns: Monday left of Tuesday on the first row, Wednesday below Monday.
        assertTrue(bounds(box(days[0])).left < bounds(box(days[1])).left)
        assertTrue(bounds(box(days[2])).top > bounds(box(days[0])).top)
        assertTrue(bounds(rule.onNodeWithTag("planner_summary")).top >= bounds(box(days[6])).top)
    }

    @Test
    fun `a Sunday-first or Saturday-first week starts on that day`() {
        show(stateOf(), anchor = 20261011, firstDay = Calendar.SUNDAY)
        rule.onNodeWithText(label(LocalDate.of(2026, 10, 11))).assertExists()
        assertTrue(bounds(box(20261011)).left < bounds(box(20261012)).left)
    }

    @Test
    fun `a Saturday-first week starts on Saturday`() {
        show(stateOf(), anchor = 20261010, firstDay = Calendar.SATURDAY)
        assertTrue(bounds(box(20261010)).top <= bounds(box(20261011)).top)
        assertTrue(bounds(box(20261010)).left < bounds(box(20261011)).left)
    }

    @Test
    fun `a landscape phone lays out four columns`() {
        show(stateOf(), widthDp = 800, isCompactHeight = true)
        assertEquals(bounds(box(20261012)).top, bounds(box(20261015)).top)
        assertTrue(bounds(box(20261016)).top > bounds(box(20261012)).top)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun `a real landscape window lays out four columns`() {
        show(stateOf(), widthDp = 800, isCompactHeight = true)
        assertEquals(bounds(box(20261012)).top, bounds(box(20261015)).top)
    }

    @Test
    fun `a day header reads like the Day view title, centred, with no count`() {
        show(stateOf(timed(1, thu, 9, "Swim"), timed(2, thu, 11, "Piano")))
        assertEquals("Thu, Oct 15", label(thu))
        val header = placedText(label(thu), today)
        val layout = layoutOf(header)
        val drawnCentre = header.positionInRoot.x + (layout.getLineLeft(0) + layout.getLineRight(0)) / 2
        val boxBounds = bounds(box(today))
        val tolerance = with(rule.density) { 1.dp.toPx() }
        assertEquals(boxBounds.center.x, drawnCentre, tolerance)
        // No event count in the header.
        assertEquals(0, rule.onAllNodes(hasText("2") and inBox(today)).fetchSemanticsNodes().size)
    }

    @Test
    fun `every day header is centred`() {
        show(stateOf())
        (0..6).map { mon.plusDays(it.toLong()) }.forEach { date ->
            val code = PlannerTestEvents.dayCode(date)
            val header = placedText(label(date), code)
            val layout = layoutOf(header)
            val drawnCentre = header.positionInRoot.x + (layout.getLineLeft(0) + layout.getLineRight(0)) / 2
            assertEquals("$date", bounds(box(code)).center.x, drawnCentre, with(rule.density) { 1.dp.toPx() })
        }
    }

    @Test
    fun `weekend headers use the weekend colour`() {
        show(stateOf())
        val colors = scheme!!
        assertEquals(colors.error, headerColor(mon.plusDays(5)))
        assertEquals(colors.error, headerColor(mon.plusDays(6)))
        assertEquals(colors.onSurfaceVariant, headerColor(mon))
        assertEquals(colors.onPrimaryContainer, headerColor(thu))
    }

    @Test
    fun `today keeps its own header colour on a weekend`() {
        val sat = mon.plusDays(5)
        show(stateOf(), todayCode = PlannerTestEvents.dayCode(sat))
        assertEquals(scheme!!.onPrimaryContainer, headerColor(sat))
        assertEquals(scheme!!.error, headerColor(mon.plusDays(6)))
    }

    @Test
    fun `tapping an event opens its day`() {
        val calls = show(stateOf(timed(1, thu, 16, "Swim")))
        rule.onNodeWithText("Swim").performClick()
        assertEquals(listOf(thu), calls.dayClicks.map(::localDate))
        assertTrue(calls.emptyTaps.isEmpty())
    }

    @Test
    fun `tapping an all-day ribbon opens its day`() {
        val trip = PlannerTestEvents.roomAllDay(3, thu, zone).let { it.copy(event = it.event.copy(title = "Sam away")) }
        val calls = show(stateOf(trip))
        rule.onNode(hasText("Sam away") and inBox(today)).performClick()
        assertEquals(listOf(thu), calls.dayClicks.map(::localDate))
    }

    @Test
    fun `rows and ribbons tell a screen reader they open the day`() {
        val trip = PlannerTestEvents.roomAllDay(3, thu, zone).let { it.copy(event = it.event.copy(title = "Sam away")) }
        show(stateOf(trip, timed(1, thu, 16, "Swim")))
        rule.onNode(hasText("Swim") and inBox(today) and hasClickLabel("Show this day's events")).assertExists()
        rule.onNode(hasText("Sam away") and inBox(today) and hasClickLabel("Show this day's events")).assertExists()
    }

    @Test
    fun `tapping empty space in a box asks for a new event on that day`() {
        val calls = show(stateOf(timed(1, thu, 9, "Swim")))
        box(20261015).performTouchInput { click(Offset(centerX, bottom - 8f)) }
        assertEquals(1, calls.emptyTaps.size)
        assertEquals(thu, localDate(calls.emptyTaps.single()))
        assertTrue(calls.dayClicks.isEmpty())
        rule.onNode(inBox(today) and hasClickLabel("Create event")).assertExists()
    }

    private fun longRows(count: Int) =
        (0 until count).map { timed(10L + it, thu, 8 + it, "A long event title number $it that wraps") }.toTypedArray()

    @Test
    fun `a full box counts the rest in its bottom-end corner, which opens the day`() {
        val calls = show(stateOf(*longRows(8)))
        val rows = shownRowsIn(today)
        // One-line titles fit at least three rows in a phone box.
        assertTrue("rows shown ${rows.size}", rows.size >= 3)
        val count = rule.onNode(hasText("+${8 - rows.size}") and inBox(today))
        val countBounds = bounds(count)
        val boxBounds = bounds(box(today))
        val near = with(rule.density) { 8.dp.toPx() }
        assertTrue("count $countBounds right of box $boxBounds", boxBounds.right - countBounds.right <= near)
        assertTrue("count $countBounds bottom of box $boxBounds", boxBounds.bottom - countBounds.bottom <= near)
        assertTrue("count $countBounds in the end half of $boxBounds", countBounds.left > boxBounds.center.x)
        rows.forEach { row -> assertTrue("row ${row.boundsInRoot} under count $countBounds", !row.boundsInRoot.overlaps(countBounds)) }
        count.assertContentDescriptionEquals("+${8 - rows.size} more")
        // No touch target of its own: a tap on it lands on the box behind, which opens the day;
        // a screen reader still gets the action on the count.
        count.assert(hasClickLabel("Show this day's events"))
        count.performClick()
        assertEquals(listOf(thu), calls.dayClicks.map(::localDate))
        assertTrue(calls.emptyTaps.isEmpty())
    }

    @Test
    fun `right to left the count sits in the bottom-start corner`() {
        show(stateOf(*longRows(8)), rtl = true)
        val rows = shownRowsIn(today)
        val countBounds = bounds(rule.onNode(hasText("+${8 - rows.size}") and inBox(today)))
        val boxBounds = bounds(box(today))
        assertTrue("count $countBounds left of box $boxBounds", countBounds.left - boxBounds.left <= with(rule.density) { 8.dp.toPx() })
        rows.forEach { row -> assertTrue("row ${row.boundsInRoot} under count $countBounds", !row.boundsInRoot.overlaps(countBounds)) }
        val last = rows.maxBy { it.boundsInRoot.bottom }
        val gap = last.boundsInRoot.left - countBounds.right
        assertTrue("gap $gap after the count", gap in with(rule.density) { 3.dp.toPx()..5.dp.toPx() })
    }

    @Test
    fun `the row beside the count is kept and narrowed`() {
        show(stateOf(*longRows(8)))
        val rows = shownRowsIn(today)
        val countBounds = bounds(rule.onNode(hasText("+${8 - rows.size}") and inBox(today)))
        val boxBounds = bounds(box(today))
        // No line is held back for the count: the space under the last row is less than a row.
        val sorted = rows.sortedBy { it.boundsInRoot.top }
        val last = sorted.last()
        assertTrue(
            "gap under the last row ${boxBounds.bottom - last.boundsInRoot.bottom} vs row ${last.boundsInRoot.height}",
            boxBounds.bottom - last.boundsInRoot.bottom < last.boundsInRoot.height,
        )
        assertTrue("last row ${last.boundsInRoot} level with $countBounds", last.boundsInRoot.bottom > countBounds.top)
        assertTrue("last row ${last.boundsInRoot} narrowed beside $countBounds", last.boundsInRoot.right <= countBounds.left)
        // The title runs up to a small gap before the drawn count, not to an empty strip.
        // The count is padded only on its corner side, so its text starts at its start edge.
        val gap = countBounds.left - last.boundsInRoot.right
        assertTrue("gap $gap before the count", gap in with(rule.density) { 3.dp.toPx()..5.dp.toPx() })
        // A row above the count keeps the full width.
        assertTrue("first row ${sorted.first().boundsInRoot} narrowed", sorted.first().boundsInRoot.right > countBounds.left)
    }

    private fun ribbons(count: Int) = (0 until count).map { i ->
        PlannerTestEvents.roomAllDay(200L + i, thu, zone).let { it.copy(event = it.event.copy(title = "Ribbon $i")) }
    }

    @Test
    fun `the space left in a full box shows one more event on one line`() {
        // Two ribbons and two full rows leave room for one line, not a full row.
        val calls = show(stateOf(*(ribbons(2) + longRows(8)).toTypedArray()))
        val full = rowsIn(today)
        val oneLine = oneLineRowsIn(today).single()
        val boxBounds = bounds(box(today))
        assertEquals(4, full.size)
        val left = boxBounds.bottom - full.maxOf { it.boundsInRoot.bottom }
        assertTrue("$left px under the full rows", left >= oneLine.boundsInRoot.height && left < full.last().boundsInRoot.height)
        assertTrue("one-line row ${oneLine.boundsInRoot} below the rows", oneLine.boundsInRoot.top >= full.maxOf { it.boundsInRoot.bottom } - 0.5f)
        assertTrue("one-line row ${oneLine.boundsInRoot} inside $boxBounds", oneLine.boundsInRoot.bottom <= boxBounds.bottom + 0.5f)
        assertTrue("one-line row is shorter than a full row", oneLine.boundsInRoot.height < full.last().boundsInRoot.height)
        // The first hidden event: its short start time, then its title.
        rule.onNode(hasTestTag("planner_row_one_line") and hasText("10 am") and hasText("A long event title number 2 that wraps"))
            .assertExists()
        assertOneLineTitleFillsRow(oneLine, rtl = false)
        // Its time ellipsizes rather than wrap or cut to a wrong time.
        val time = placedText("10 am", today)
        assertEquals(TextOverflow.Ellipsis, layoutOf(time).layoutInput.overflow)
        assertEquals(false, layoutOf(time).layoutInput.softWrap)
        val count = rule.onNode(hasText("+5") and inBox(today))
        assertTrue("count beside it", !oneLine.boundsInRoot.overlaps(bounds(count)))
        rule.onNode(hasTestTag("planner_row_one_line") and hasClickLabel("Show this day's events")).performClick()
        assertEquals(listOf(thu), calls.dayClicks.map(::localDate))
    }

    /** Asserts the one-line row's title gets all the width its time leaves, not a share of it. */
    private fun assertOneLineTitleFillsRow(oneLine: SemanticsNode, rtl: Boolean) {
        val title = placedText("A long event title number 2 that wraps", today)
        val time = placedText("10 am", today).boundsInRoot
        val gap = with(rule.density) { 4.dp.toPx() }
        val room = if (rtl) time.left - gap - oneLine.boundsInRoot.left else oneLine.boundsInRoot.right - time.right - gap
        // Longer than its room, so its shown width is the clip, not its own width.
        assertTrue("title ${layoutOf(title).size.width} fits $room", layoutOf(title).size.width > room)
        assertEquals(room, title.boundsInRoot.width, with(rule.density) { 1.dp.toPx() })
    }

    @Test
    fun `right to left the one-line title gets the width its time leaves`() {
        show(stateOf(*(ribbons(2) + longRows(8)).toTypedArray()), rtl = true)
        assertOneLineTitleFillsRow(oneLineRowsIn(today).single(), rtl = true)
    }

    @Test
    fun `too little space left keeps full rows and the count`() {
        // One ribbon leaves less than one line under the last full row.
        show(stateOf(*(ribbons(1) + longRows(8)).toTypedArray()))
        assertTrue(oneLineRowsIn(today).isEmpty())
        val px = rule.density.density
        val left = bounds(box(today)).bottom - rowsIn(today).maxOf { it.boundsInRoot.bottom } - 3 * px
        val oneLine = layoutOf(placedRowTitle()).size.height + 4 * px
        assertTrue("$left px left, one line needs $oneLine", left < oneLine)
        rule.onNode(hasText("+${9 - rowsIn(today).size}") and inBox(today)).assertExists()
    }

    @Test
    fun `when the one-line row is the last event no count shows`() {
        show(stateOf(*(ribbons(2) + longRows(3)).toTypedArray()))
        oneLineRowsIn(today).single()
        assertEquals(0, rule.onAllNodes(hasText("+", substring = true) and inBox(today)).fetchSemanticsNodes().size)
        // Nothing is hidden, so the space left creates an event.
        rule.onNode(inBox(today) and hasClickLabel("Create event")).assertExists()
    }

    @Test
    fun `a box that loses events while shown keeps working`() {
        // Hiding a calendar from the drawer takes events out of boxes that are on screen.
        val full = stateOf(*(ribbons(2) + longRows(8)).toTypedArray())
        val holder = mutableStateOf<PlannerWeekState?>(full)
        show(full, stateHolder = holder)
        rule.runOnIdle { holder.value = stateOf(*longRows(1)) }
        rule.waitForIdle()
        assertEquals(1, shownRowsIn(today).size)
        rule.runOnIdle { holder.value = stateOf() }
        rule.waitForIdle()
        rule.onNode(hasText("Free day") and inBox(today)).assertExists()
        rule.runOnIdle { holder.value = full }
        rule.waitForIdle()
        assertTrue(shownRowsIn(today).size >= 4)
    }

    @Test
    fun `the summary box losing lines while shown keeps working`() {
        // One all-day event a day from Thursday to Sunday, so emptied day boxes take their free-day
        // branch and only the summary box goes from four lines to one.
        val items = (0 until 4).map { i ->
            PlannerTestEvents.roomAllDay(100L + i, mon.plusDays(3L + i), zone)
                .let { it.copy(event = it.event.copy(title = "Summary line $i")) }
        }.toTypedArray()
        val full = stateOf(*items)
        val holder = mutableStateOf<PlannerWeekState?>(full)
        show(full, stateHolder = holder)
        assertTrue(rule.onAllNodes(hasTestTag("planner_summary_line")).fetchSemanticsNodes().size > 1)
        rule.runOnIdle { holder.value = stateOf(items.first()) }
        rule.waitForIdle()
        assertEquals(1, rule.onAllNodes(hasTestTag("planner_summary_line")).fetchSemanticsNodes().size)
    }

    @Test
    fun `a hidden ribbon never becomes a one-line row`() {
        show(stateOf(*ribbons(12).toTypedArray()))
        assertTrue(oneLineRowsIn(today).isEmpty())
    }

    @Test
    fun `a tap under the rows of a full box opens the day`() {
        val calls = show(stateOf(*longRows(8)))
        box(today).performTouchInput { click(Offset(left + 12f, bottom - 1f)) }
        assertEquals(listOf(thu), calls.dayClicks.map(::localDate))
        assertTrue(calls.emptyTaps.isEmpty())
        // The body behind the rows, not a row or the count (it carries the screen-reader action).
        val hidden = 8 - shownRowsIn(today).size
        rule.onNode(
            inBox(today) and hasClickLabel("Show this day's events") and !hasTestTag("planner_row") and
                !hasTestTag("planner_row_one_line") and !hasText("+$hidden"),
        ).assertExists()
    }

    @Test
    fun `a long title is one line cut at the edge with no ellipsis`() {
        val title = "Soccer game at field three, bring orange slices and the folding chairs"
        show(stateOf(timed(1, thu, 9, title)))
        val node = placedText(title, today)
        val layout = layoutOf(node)
        val row = rowsIn(today).single().boundsInRoot
        assertEquals(1, layout.lineCount)
        assertTrue("ellipsized", !layout.isLineEllipsized(0))
        assertTrue("line ${layout.getLineRight(0) - layout.getLineLeft(0)} fits row ${row.width}", layout.getLineRight(0) - layout.getLineLeft(0) > row.width)
    }

    @Test
    fun `a long ribbon title is cut at the edge with no ellipsis`() {
        val title = "School closed for the staff training day, all classes cancelled"
        val strip = PlannerTestEvents.roomAllDay(3, thu, zone).let { it.copy(event = it.event.copy(title = title)) }
        show(stateOf(strip))
        val layout = layoutOf(placedText(title, today))
        assertEquals(1, layout.lineCount)
        assertTrue("ellipsized", !layout.isLineEllipsized(0))
    }

    @Test
    fun `a right to left title shows its start`() =
        assertFirstLetterShown("שיעור פסנתר ארוך מאוד עם הרבה מילים נוספות שלא נכנסות", rtl = true)

    @Test
    fun `a short ribbon title starts at the ribbon's start`() {
        val trip = PlannerTestEvents.roomAllDay(3, thu, zone).let { it.copy(event = it.event.copy(title = "Sam away")) }
        show(stateOf(trip))
        val text = placedText("Sam away", today)
        val ribbon = rowsIn(today).single().boundsInRoot
        val gap = text.positionInRoot.x - ribbon.left
        assertTrue("title starts ${gap}px into the ribbon $ribbon", gap <= with(rule.density) { 12.dp.toPx() })
    }

    @Test
    fun `right to left a short ribbon title starts at the ribbon's start`() {
        val trip = PlannerTestEvents.roomAllDay(3, thu, zone).let { it.copy(event = it.event.copy(title = "Sam away")) }
        show(stateOf(trip), rtl = true)
        val text = placedText("Sam away", today)
        val ribbon = rowsIn(today).single().boundsInRoot
        val gap = ribbon.right - (text.positionInRoot.x + text.size.width)
        assertTrue("title ends ${gap}px from the ribbon's right $ribbon", gap <= with(rule.density) { 12.dp.toPx() })
    }

    private fun assertFirstLetterShown(title: String, rtl: Boolean) {
        show(stateOf(timed(1, thu, 9, title)), rtl = rtl)
        val node = placedText(title, today)
        val first = layoutOf(node).getBoundingBox(0)
        val row = rowsIn(today).single().boundsInRoot
        // positionInRoot is the text's unclipped origin; its bounds are clipped to the row.
        val firstLeft = node.positionInRoot.x + first.left
        val firstRight = node.positionInRoot.x + first.right
        assertTrue("first letter $firstLeft..$firstRight outside row $row", firstLeft >= row.left - 0.5f && firstRight <= row.right + 0.5f)
    }

    @Test
    fun `a right to left title in a left to right layout shows its start`() =
        assertFirstLetterShown("שיעור פסנתר ארוך מאוד עם הרבה מילים נוספות שלא נכנסות", rtl = false)

    @Test
    fun `a left to right title in a right to left layout shows its start`() =
        assertFirstLetterShown("Soccer game at field three, bring orange slices and the folding chairs", rtl = true)

    @Test
    fun `the time line and summary lines keep the ellipsis`() {
        val birthday = PlannerTestEvents.roomAllDay(4, mon.plusDays(5), zone, rrule = "FREQ=YEARLY")
            .let { it.copy(event = it.event.copy(title = "Grandpa's birthday")) }
        show(stateOf(timed(1, thu, 9, "Swim"), birthday))
        val time = rule.onAllNodes(hasText("am", substring = true, ignoreCase = true) and inBox(today), useUnmergedTree = true)
            .fetchSemanticsNodes().first { it.layoutInfo.isPlaced }
        assertEquals(TextOverflow.Ellipsis, layoutOf(time).layoutInput.overflow)
        val line = rule.onAllNodes(
            hasText("Grandpa's birthday") and hasAnyAncestor(hasTestTag("planner_summary")), useUnmergedTree = true,
        ).fetchSemanticsNodes().first { it.layoutInfo.isPlaced }
        assertEquals(TextOverflow.Ellipsis, layoutOf(line).layoutInput.overflow)
    }

    @Test
    fun `tapping a day header opens that day`() {
        val calls = show(stateOf())
        rule.onNodeWithText(label(thu)).performClick()
        assertEquals(listOf(thu), calls.headers)
    }

    @Test
    fun `nothing reads as empty before the week has loaded`() {
        show(null)
        assertEquals(0, rule.onAllNodes(hasText("Free day")).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(hasText("The usual week.")).fetchSemanticsNodes().size)
    }

    @Test
    fun `a loaded empty week shows free days ahead and the usual week`() {
        show(stateOf())
        // Thursday (today) to Sunday are free; Monday to Wednesday are past.
        assertEquals(4, rule.onAllNodes(hasText("Free day")).fetchSemanticsNodes().size)
        // A free day reads as a reward, not a box asking to be filled.
        assertEquals(0, rule.onAllNodes(hasText("Tap to add")).fetchSemanticsNodes().size)
        assertEquals(4, rule.onAllNodes(hasTestTag("planner_free_icon"), useUnmergedTree = true).fetchSemanticsNodes().count { it.layoutInfo.isPlaced })
        rule.onNodeWithText("The usual week.").assertExists()
    }

    @Test
    fun `tapping a free day asks for a new event on that day`() {
        val calls = show(stateOf())
        rule.onNode(inBox(today) and hasClickLabel("Create event")).assertExists()
        box(today).performTouchInput { click(center) }
        assertEquals(listOf(thu), calls.emptyTaps.map(::localDate))
        assertTrue(calls.dayClicks.isEmpty())
    }

    @Test
    fun `a past free day stays blank`() {
        show(stateOf())
        val past = hasAnyAncestor(hasTestTag("planner_day_20261013"))
        assertEquals(0, rule.onAllNodes(hasText("Free day") and past).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(hasText("Tap to add") and past).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(hasTestTag("planner_free_icon") and past, useUnmergedTree = true).fetchSemanticsNodes().count { it.layoutInfo.isPlaced })
    }

    @Test
    @Config(qualifiers = "w360dp-h400dp-mdpi")
    fun `a short free box drops the sun and keeps the label whole`() {
        show(stateOf(), fontScale = 2f)
        assertEquals(0, rule.onAllNodes(hasText("Tap to add") and inBox(today)).fetchSemanticsNodes().size)
        val sun = rule.onAllNodes(hasTestTag("planner_free_icon") and inBox(today), useUnmergedTree = true)
            .fetchSemanticsNodes().count { it.layoutInfo.isPlaced }
        assertEquals("no room for the sun above the label", 0, sun)
        val label = bounds(rule.onNode(hasText("Free day") and inBox(today)))
        val boxBounds = bounds(box(today))
        assertTrue("label $label inside box $boxBounds", label.top >= boxBounds.top && label.bottom <= boxBounds.bottom)
    }

    @Test
    fun `a cancelled event is struck through`() {
        show(stateOf(timed(1, mon.plusDays(4), 10, "Trampoline", status = "CANCELLED")))
        val layouts = mutableListOf<TextLayoutResult>()
        // The row merges its time and title; the title's own node carries the decoration.
        // Unplaced measuring copies sit in the unmerged tree too; take the placed one.
        rule.onAllNodes(hasText("Trampoline") and hasAnyAncestor(hasTestTag("planner_day_20261016")), useUnmergedTree = true)
            .fetchSemanticsNodes().single { it.layoutInfo.isPlaced }
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertEquals(TextDecoration.LineThrough, layouts.single().layoutInput.style.textDecoration)
    }

    @Test
    fun `a moved occurrence says where it was`() {
        val moved = PlannerTestEvents.room(
            id = 5, startTs = at(thu, 16, 30, zone = zone), endTs = at(thu, 18, zone = zone), zone = zone,
            title = "Soccer", seriesId = 9, originalInstanceTime = at(thu, 17, zone = zone),
        )
        show(stateOf(moved, roomSeries = mapOf(9L to false)))
        rule.onNodeWithText("was 5 pm", substring = true).assertExists()
    }

    @Test
    fun `a row's time line is the short form`() {
        val soccer = PlannerTestEvents.room(
            id = 6, startTs = at(thu, 16, 30, zone = zone), endTs = at(thu, 18, zone = zone), zone = zone, title = "Soccer",
        )
        show(stateOf(soccer))
        val time = placedText("4:30–6 pm", today)
        assertTrue("the old ' - ' separator still shows", rule.onAllNodes(hasText(" - ", substring = true) and inBox(today)).fetchSemanticsNodes().isEmpty())
        assertEquals(TextOverflow.Ellipsis, layoutOf(time).layoutInput.overflow)
    }

    @Test
    fun `the summary lists what differs and a tap opens its day`() {
        val birthday = PlannerTestEvents.roomAllDay(1, mon.plusDays(5), zone, rrule = "FREQ=YEARLY")
        val weekly = PlannerTestEvents.roomAllDay(2, mon.plusDays(4), zone, rrule = "FREQ=WEEKLY")
        val calls = show(stateOf(
            birthday.copy(event = birthday.event.copy(title = "Grandpa's birthday")),
            weekly.copy(event = weekly.event.copy(title = "Trash day")),
        ))
        val inSummary = { text: String -> hasText(text) and hasAnyAncestor(hasTestTag("planner_summary")) }
        assertEquals(1, rule.onAllNodes(inSummary("Grandpa's birthday")).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(inSummary("Trash day")).fetchSemanticsNodes().size)
        rule.onNode(inSummary("Grandpa's birthday")).performClick()
        assertEquals(mon.plusDays(5), localDate(calls.dayClicks.single()))
    }

    private fun assertNoRowClipped(scale: Float, rtl: Boolean, zoom: Float) {
        val events = (0 until 6).map { timed(20L + it, thu, 8 + it, "A long event title number $it that wraps") }.toTypedArray()
        show(stateOf(*events), fontScale = scale, rtl = rtl, dark = rtl, initialZoom = zoom)
        val boxBounds = bounds(box(today))
        val rows = shownRowsIn(today)
        // At the largest sizes a box may hold no full row; then it shows a one-line row or only
        // the count.
        val countShown = rule.onAllNodes(hasText("+${6 - rows.size}") and hasAnyAncestor(hasTestTag("planner_day_$today")))
            .fetchSemanticsNodes().isNotEmpty()
        assertTrue("rows or the count shown at $scale/$rtl/$zoom", rows.isNotEmpty() || countShown)
        if (rows.size < 6) assertTrue("hidden rows are counted at $scale/$rtl/$zoom", countShown)
        rows.forEach { row ->
            assertTrue(
                "row ${row.boundsInRoot} outside box $boxBounds at scale $scale rtl $rtl zoom $zoom",
                row.boundsInRoot.bottom <= boxBounds.bottom + 0.5f && row.boundsInRoot.top >= boxBounds.top - 0.5f,
            )
        }
        if (countShown && rows.isNotEmpty()) {
            // A box showing the count leaves less than a one-line row under its last row: a line
            // of text plus the row's 2dp top and bottom, inside the body's 3dp bottom padding.
            val px = rule.density.density * zoom
            val line = layoutOf(placedRowTitle()).size.height
            val oneLine = line + 4 * px
            val left = boxBounds.bottom - rows.maxOf { it.boundsInRoot.bottom } - 3 * px
            assertTrue("$left px left under the rows, a one-line row needs $oneLine at $scale/$rtl/$zoom", left < oneLine)
        }
    }

    /** Returns a placed row title in today's box, to read a line's height from. */
    private fun placedRowTitle(): SemanticsNode =
        rule.onAllNodes(hasText("A long event title number", substring = true) and inBox(today), useUnmergedTree = true)
            .fetchSemanticsNodes().first { it.layoutInfo.isPlaced }

    @Test
    fun `no row is clipped at the default font scale`() = assertNoRowClipped(1f, rtl = false, zoom = 1f)

    @Test
    fun `no row is clipped at font scale 1_3`() = assertNoRowClipped(1.3f, rtl = false, zoom = 1f)

    @Test
    fun `no row is clipped at font scale 2`() = assertNoRowClipped(2f, rtl = false, zoom = 1f)

    @Test
    fun `no row is clipped right to left in dark theme`() = assertNoRowClipped(1f, rtl = true, zoom = 1f)

    @Test
    fun `no row is clipped at font scale 2 with the largest zoom`() = assertNoRowClipped(2f, rtl = false, zoom = 1.6f)

    @Test
    fun `a pinch zooms the boxes and a second pinch carries on from there`() {
        val calls = show(stateOf())
        rule.onRoot().performTouchInput {
            pinch(Offset(centerX - 40f, centerY), Offset(centerX - 50f, centerY), Offset(centerX + 40f, centerY), Offset(centerX + 50f, centerY))
        }
        rule.waitForIdle()
        val first = calls.zooms.last()
        assertTrue("first pinch zoomed in, got $first", first > 1f)
        rule.onRoot().performTouchInput {
            pinch(Offset(centerX - 40f, centerY), Offset(centerX - 48f, centerY), Offset(centerX + 40f, centerY), Offset(centerX + 48f, centerY))
        }
        rule.waitForIdle()
        assertTrue("first pinch stayed under the limit, got $first", first < org.onekash.kashcal.domain.planner.MAX_PLANNER_ZOOM)
        assertTrue("second pinch continued, got ${calls.zooms.last()} after $first", calls.zooms.last() > first)
        assertTrue("a pinch doesn't page", calls.pages.all { it == center })
    }

    @Test
    fun `a slow pinch keeps following the fingers after the slop`() {
        val calls = show(stateOf())
        rule.onRoot().performTouchInput {
            // Wide fingers cross the slop early; the rest of the 10% pinch then arrives at about
            // 0.05% per frame, frames small enough to have been dropped.
            pinch(
                Offset(centerX - 150f, centerY), Offset(centerX - 165f, centerY),
                Offset(centerX + 150f, centerY), Offset(centerX + 165f, centerY),
                durationMillis = 3_000,
            )
        }
        rule.waitForIdle()
        assertTrue("slow pinch reached ${calls.zooms.lastOrNull()}", (calls.zooms.lastOrNull() ?: 1f) > 1.09f)
    }

    @Test
    fun `summary lines leave the FAB corner clear at the smallest zoom`() {
        val items = (0 until 8).map { i ->
            PlannerTestEvents.roomAllDay(100L + i, mon.plusDays(3L + (i % 4)), zone)
                .let { it.copy(event = it.event.copy(title = "A long summary line title number $i")) }
        }.toTypedArray()
        show(stateOf(*items), initialZoom = MIN_PLANNER_ZOOM)
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val fabZone = with(rule.density) { 72.dp.toPx() }
        val lines = rule.onAllNodes(hasTestTag("planner_summary_line")).fetchSemanticsNodes()
        assertTrue("summary lines shown", lines.isNotEmpty())
        lines.filter { it.boundsInRoot.bottom > root.bottom - fabZone }.forEach { line ->
            assertTrue(
                "line ${line.boundsInRoot} runs under the FAB corner (root $root)",
                line.boundsInRoot.right <= root.right - fabZone + 0.5f,
            )
        }
    }

    @Test
    fun `an occurrence moved from another day names that day or date`() {
        val fromTuesday = PlannerTestEvents.room(
            id = 7, startTs = at(thu, 9, zone = zone), endTs = at(thu, 10, zone = zone), zone = zone,
            title = "Dentist", seriesId = 11, originalInstanceTime = at(mon.plusDays(1), 9, zone = zone),
        )
        val fromLastWeek = PlannerTestEvents.room(
            id = 8, startTs = at(thu, 11, zone = zone), endTs = at(thu, 12, zone = zone), zone = zone,
            title = "Piano", seriesId = 12, originalInstanceTime = at(mon.minusDays(3), 11, zone = zone),
        )
        show(stateOf(fromTuesday, fromLastWeek, roomSeries = mapOf(11L to false, 12L to false)))
        val locale = Locale.getDefault()
        val weekday = java.time.format.DateTimeFormatter.ofPattern(
            org.onekash.kashcal.util.DateTimeUtils.localizedPattern("EEE", locale), locale,
        ).format(mon.plusDays(1))
        val shortDate = java.time.format.DateTimeFormatter.ofPattern(
            org.onekash.kashcal.util.DateTimeUtils.localizedPattern("MMMd", locale), locale,
        ).format(mon.minusDays(3))
        // The merged tree holds only placed rows, not their measuring copies.
        rule.onNodeWithText("was $weekday", substring = true).assertExists()
        rule.onNodeWithText("was $shortDate", substring = true).assertExists()
    }

    @Test
    fun `a swipe pages the week and the next week is already filled`() {
        val nextWeekEvent = timed(30, mon.plusDays(8), 10, "Picture day prep")
        val calls = show(stateOf(nextWeekEvent))
        rule.onRoot().performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(center + 1, calls.pages.last())
        rule.onNode(hasText("Picture day prep") and hasAnyAncestor(hasTestTag("planner_day_20261020"))).assertExists()
    }

    @Test
    fun `a page outside the loaded range shows no free days`() {
        show(stateOf(start = mon, end = mon.plusDays(6)))
        rule.onRoot().performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(
            0,
            rule.onAllNodes(hasText("Free day") and hasAnyAncestor(hasTestTag("planner_day_20261022"))).fetchSemanticsNodes().size,
        )
    }
}
