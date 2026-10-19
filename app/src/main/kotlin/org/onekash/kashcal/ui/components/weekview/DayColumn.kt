package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.rememberTextMeasurer
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.entity.Task
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.components.AgendaWeekBarLogic
import org.onekash.kashcal.ui.components.TaskCheckboxGlyph
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Displays one day column of the time grid, placing timed events by start time and duration.
 *
 * Overlapping events sit side by side in up to [maxVisibleOverlap] slots, or with [cascade] a
 * later event draws on top of the ones it overlaps, indented and outlined, when each of them keeps
 * its first title line above it ([WeekViewUtils.OverlapLayout.Cascade]); a covered event's text
 * then stops above the event on top. Events past the cap of a side-by-side group go behind a
 * "+N more" badge at that group's position.
 *
 * @param now returns the grid's current time in epoch ms ([rememberGridMinuteClock]), read here
 *   so a minute tick recomposes the column, not the page that gave it [date]; events that have
 *   ended by then fade, and the column is today's when [date] is that time's date
 * @param timedTasks open tasks with a timed DUE; each renders as a slim chip at its due time.
 *   All-day and undated tasks never arrive — the agenda and the Tasks screen show those.
 * @param taskColorById calendar color by id, tinting the task chips
 * @param onTaskClick called with the tapped task, for the quick view
 * @param compact draws the 7-day view's compact [EventBlock].
 * @param maxVisibleOverlap side-by-side slots shown before the badge
 * @param onOverflowClick called with the group's hidden events when the badge is tapped
 * @param onEmptyTap called with the date, hour and minute of a tap on empty space, the minute
 *   rounded to the nearest 15, or of [now] from the column's screen-reader Create event click
 * @param onEventDragStart called with the event and where the long press landed: x across the
 *   column from its left edge in a left-to-right layout, y below the event block's top
 */
@Composable
fun DayColumn(
    date: LocalDate,
    events: List<DisplayEvent>,
    timedTasks: List<Task> = emptyList(),
    taskColorById: Map<Long, Int> = emptyMap(),
    hourHeight: Dp = WeekViewUtils.HOUR_HEIGHT,
    now: () -> Long,
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mma",
    startHour: Int = WeekViewUtils.START_HOUR,
    maxVisibleOverlap: Int = WeekViewUtils.MAX_VISIBLE_OVERLAP,
    compact: Boolean = false,
    cascade: Boolean = false,
    onEventClick: (DisplayEvent) -> Unit,
    onTaskClick: (Task) -> Unit = {},
    onOverflowClick: (List<DisplayEvent>) -> Unit,
    onEmptyTap: (LocalDate, Int, Int) -> Unit = { _, _, _ -> },  // (date, hour, minute)
    onEventDragStart: ((DisplayEvent, Offset) -> Unit)? = null,
    onEventDrag: ((Offset) -> Unit)? = null,
    onEventDragEnd: (() -> Unit)? = null,
    onEventDragCancel: (() -> Unit)? = null,
    isDropTarget: Boolean = false,
    modifier: Modifier = Modifier
) {
    val endHour = WeekViewUtils.END_HOUR
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    // A cascaded event must leave the event it covers its first title line.
    val firstLine = eventBlockFirstLineHeight(compact, textMeasurer)
    val overlapLayout = if (cascade) {
        WeekViewUtils.OverlapLayout.Cascade(minOffset = firstLine)
    } else {
        WeekViewUtils.OverlapLayout.SideBySide
    }
    val positionedEvents = remember(events, date, startHour, hourHeight, maxVisibleOverlap, overlapLayout) {
        val dayIndex = date.dayOfWeek.value % 7  // 0=Sunday
        WeekViewUtils.positionEventsForDay(
            events, date, dayIndex, hourHeight, startHour, endHour, maxVisibleOverlap, overlapLayout
        )
    }

    // Each group shows the events in its first maxVisibleOverlap slots, plus a badge. Side by
    // side, a group is an overlap cluster; cascaded, it is one level's split.
    val groupedEvents = remember(positionedEvents, cascade) {
        if (cascade) {
            positionedEvents.groupBy { it.groupId }.values.toList()
        } else {
            groupOverlappingEvents(positionedEvents)
        }
    }
    // Deeper levels draw last, so a cascaded event sits on top and takes the taps over it.
    val drawOrder = remember(groupedEvents) {
        groupedEvents.flatMap { WeekViewUtils.groupForDisplay(it, maxVisibleOverlap).first }
            .sortedBy { it.cascadeDepth }
    }

    val nowMs = now()
    val todayDayCode = DateTimeUtils.eventTsToDayCode(nowMs, isAllDay = false)
    val isToday = date == DateTimeUtils.eventTsToLocalDate(nowMs, isAllDay = false)
    val todayLabel = stringResource(R.string.label_today)
    val dateDescription = remember(date, isToday, todayLabel) {
        AgendaWeekBarLogic.cellContentDescription(date, isToday, isSelected = false, todayLabel = todayLabel, selectedLabel = "")
    }
    val createLabel = stringResource(R.string.cd_create_event)

    val todayBorderColor = MaterialTheme.colorScheme.primary
    val dropTargetColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val hourHeightPx = with(density) { hourHeight.toPx() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .then(
                if (isDropTarget) {
                    Modifier.background(dropTargetColor)
                } else {
                    Modifier
                }
            )
            .then(
                if (isToday) {
                    Modifier.border(
                        width = 2.dp,
                        color = todayBorderColor,
                        shape = RectangleShape
                    )
                } else {
                    Modifier
                }
            )
    ) {
        val columnWidth = maxWidth

        // Background tap target, below the events in z-order so an event tap hits EventBlock.
        // Screen readers get it as one item, the column's date, whose click opens a new event on
        // that day at the current time, since they have no empty spot to tap. Without the click
        // action a double tap would land on the item's centre, which an event can cover.
        Box(
            modifier = Modifier
                .matchParentSize()
                .semantics {
                    contentDescription = dateDescription
                    onClick(label = createLabel) {
                        val time = DateTimeUtils.eventTsToZonedDateTime(now(), isAllDay = false)
                        onEmptyTap(date, time.hour, time.minute)
                        true
                    }
                }
                .pointerInput(date, startHour, hourHeight) {
                    detectTapGestures(
                        onTap = { offset ->
                            val (hour, minute) = WeekViewUtils.offsetToTime(offset.y, hourHeightPx, startHour = startHour)
                            onEmptyTap(date, hour, minute)
                        }
                    )
                }
        )

        drawOrder.forEach { positioned ->
            // Key each EventBlock on its event's stableKey so that when the day re-sorts after a
            // reschedule, Compose moves the node and its long-lived pointerInput coroutine with
            // its event. Keyed by position, the node would be rebound to another event and fire
            // that event's tap and drag callbacks.
            key(positioned.displayEvent.stableKey) {
                val eventWidth = columnWidth * positioned.widthFraction
                val eventLeft = columnWidth * positioned.leftFraction

                val event = positioned.displayEvent
                val isDraggable = onEventDragStart != null &&
                    !event.isReadOnly &&
                    !event.isAllDay

                EventBlock(
                    displayEvent = event,
                    height = positioned.height,
                    isPast = DateTimeUtils.isEventPast(event.endTs, event.endDay, event.isAllDay, nowMs, todayDayCode),
                    showEventEmojis = showEventEmojis,
                    timePattern = timePattern,
                    onClick = { onEventClick(event) },
                    isDraggable = isDraggable,
                    onDragStart = if (isDraggable) { offset ->
                        // The block reports the press inside itself; move it to the column's
                        // coordinates by the block's place in the column and its margin.
                        val blockStartPx = with(density) { (eventLeft + BLOCK_MARGIN).toPx() }
                        onEventDragStart?.invoke(event, offset + Offset(blockStartPx, 0f))
                    } else null,
                    onDrag = onEventDrag,
                    onDragEnd = onEventDragEnd,
                    onDragCancel = onEventDragCancel,
                    compact = compact,
                    outlined = positioned.cascadeDepth > 0,
                    textMeasurer = textMeasurer,
                    textHeight = positioned.textHeight,
                    modifier = Modifier
                        .offset(x = eventLeft, y = positioned.topOffset)
                        .width(eventWidth - BLOCK_MARGIN * 2)
                        .padding(horizontal = BLOCK_MARGIN)
                )
            }
        }

        // Timed-due tasks at their due time, drawn above the event blocks: a slim chip, distinct
        // from the filled event blocks, opening the task quick view. The full 24-hour grid
        // starts at [startHour], so a due minute always maps inside the column.
        timedTasks.forEach { task ->
            key("task_${task.id}") {
                val dueTs = task.dueTs ?: return@forEach
                val due = Instant.ofEpochMilli(dueTs).atZone(ZoneId.systemDefault())
                val minutesFromStart = (due.hour * 60 + due.minute) - startHour * 60
                TaskTimeGridBlock(
                    task = task,
                    color = Color(taskColorById[task.calendarId] ?: 0xFF6200EE.toInt()),
                    isPast = dueTs < nowMs,
                    onClick = { onTaskClick(task) },
                    modifier = Modifier
                        .offset(y = hourHeight * (minutesFromStart / 60f))
                        .padding(horizontal = BLOCK_MARGIN)
                )
            }
        }

        groupedEvents.forEach { group ->
            val (visibleEvents, overflowCount) = WeekViewUtils.groupForDisplay(group, maxVisibleOverlap)

            // Badge when some of the group's events sit in a slot past the cap, at the bottom of
            // the group's first visible event and at the group's start across the column.
            if (overflowCount > 0 && visibleEvents.isNotEmpty()) {
                val firstVisible = visibleEvents.first()
                val badgeTop = firstVisible.topOffset + firstVisible.height - 16.dp

                OverflowBadge(
                    count = overflowCount,
                    compact = compact,
                    onClick = {
                        val overflowEvents = group
                            .filter { it.overlapIndex >= maxVisibleOverlap }
                            .map { it.displayEvent }
                        onOverflowClick(overflowEvents)
                    },
                    modifier = Modifier
                        .offset(y = badgeTop)
                        .badgeAtStartWithin(columnWidth, columnWidth * firstVisible.groupLeftFraction + 2.dp)
                )
            }
        }

        // Day separator at the right edge.
        VerticalDivider(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            thickness = 0.5.dp,
            color = dividerColor
        )
    }
}

/**
 * Padding on each side of an event block inside its layout box, which is twice this narrower
 * than its slot: the drawn block starts this far into the slot and ends three times this short of
 * its end, so neighbouring blocks sit four times this apart.
 */
private val BLOCK_MARGIN = 1.dp

/**
 * Groups events by start time, adding each to the first group holding an event it overlaps.
 */
private fun groupOverlappingEvents(
    events: List<WeekViewUtils.PositionedEvent>
): List<List<WeekViewUtils.PositionedEvent>> {
    if (events.isEmpty()) return emptyList()

    val sorted = events.sortedBy { it.startMinutes }
    val groups = mutableListOf<MutableList<WeekViewUtils.PositionedEvent>>()

    for (event in sorted) {
        val overlappingGroup = groups.find { group ->
            group.any { other ->
                event.startMinutes < other.endMinutes &&
                    event.endMinutes > other.startMinutes
            }
        }

        if (overlappingGroup != null) {
            overlappingGroup.add(event)
        } else {
            groups.add(mutableListOf(event))
        }
    }

    return groups
}

/**
 * Places the element [start] in from the column's start, moved back so it ends inside [columnWidth]
 * when it is wider than the room left; past the column it would sit under the next day, which
 * draws above it and takes its taps.
 */
private fun Modifier.badgeAtStartWithin(columnWidth: Dp, start: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0))
    val x = minOf(start.roundToPx(), columnWidth.roundToPx() - placeable.width).coerceAtLeast(0)
    layout(placeable.width, placeable.height) { placeable.placeRelative(x, 0) }
}

/**
 * One timed task in the day column: a slim rounded chip outlined in the calendar color over a
 * light tint, its checkbox glyph leading the summary — visibly not a filled event block. The
 * glyph is static here; completion happens in the quick view the chip opens.
 */
@Composable
private fun TaskTimeGridBlock(
    task: Task,
    color: Color,
    isPast: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val done = task.isDone
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .alpha(if (done || isPast) 0.6f else 1f)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TaskCheckboxGlyph(
            isDone = done,
            color = color,
            contentDescription = null,
            onToggle = null,
            size = 12.dp
        )
        Text(
            text = task.summary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textDecoration = if (done) TextDecoration.LineThrough else null,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}
