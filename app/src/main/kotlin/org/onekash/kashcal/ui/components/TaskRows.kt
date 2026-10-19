package org.onekash.kashcal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.entity.Task
import org.onekash.kashcal.ui.shared.contrastForegroundOn
import org.onekash.kashcal.util.DateTimeUtils
import java.time.LocalDate
import java.time.ZoneId

private const val DAY_MS = 24 * 60 * 60 * 1000L

/**
 * True when the task's placement time reads as a date rather than an instant: an all-day DUE,
 * or a DTSTART fallback stored at UTC midnight (the row carries no all-day flag for the
 * fallback, so the alignment stands in for one), as [org.onekash.kashcal.ui.screens.tasks.TasksListModel]
 * reads it.
 */
internal fun taskReadsAsAllDay(task: Task): Boolean =
    if (task.dueTs != null) {
        task.dueIsAllDay
    } else {
        task.whenTs?.let { Math.floorMod(it, DAY_MS) == 0L } == true
    }

/**
 * The local day a task places itself on (`due ?? dtstart`, [Task.whenTs]), or null when it has
 * neither. An all-day value reads as its UTC calendar date, so a due of "today" stays today in
 * every zone instead of turning over at the local midnight boundary.
 */
internal fun taskPlacementDate(task: Task, zone: ZoneId): LocalDate? {
    val whenTs = task.whenTs ?: return null
    return DateTimeUtils.eventTsToLocalDate(whenTs, taskReadsAsAllDay(task), zone)
}

/** [taskPlacementDate] as a YYYYMMDD day code. */
internal fun taskPlacementDayCode(task: Task, zone: ZoneId): Int? {
    val whenTs = task.whenTs ?: return null
    return DateTimeUtils.eventTsToDayCode(whenTs, taskReadsAsAllDay(task), zone)
}

/**
 * True when an open task places before [todayDayCode], the Tasks screen's overdue rule; an
 * undated task is never overdue.
 */
internal fun taskIsOverdue(task: Task, todayDayCode: Int, zone: ZoneId): Boolean =
    task.isOpen && (taskPlacementDayCode(task, zone) ?: Int.MAX_VALUE) < todayDayCode

/**
 * The round checkbox: an outlined circle when open, filled with [color] and a check when done —
 * the Tasks screen's control, shared so the agenda, search and planner rows read identically. A
 * custom control, since a task's check marks completion rather than selection and carries the
 * calendar's color. Tap completes or reopens when [onToggle] is given: the control then grows to
 * the 48dp minimum touch target with a checkbox role, mirroring TasksScreen's TaskCheckbox, with
 * the [size] circle as the visual inside. A null toggle renders a static glyph for the dense
 * surfaces.
 *
 * @param color the calendar's color, or the theme primary when the calendar is unknown.
 */
@Composable
internal fun TaskCheckboxGlyph(
    isDone: Boolean,
    color: Color,
    contentDescription: String?,
    onToggle: (() -> Unit)?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    // The 48dp touch target with Role.Checkbox + toggleable state when interactive, so a11y
    // services see a checkbox; the visual stays the [size] circle inside. A static glyph keeps
    // the compact [size] and no control semantics.
    Box(
        modifier = modifier
            .then(
                if (onToggle != null) {
                    Modifier
                        .size(48.dp)
                        .toggleable(
                            value = isDone,
                            role = Role.Checkbox,
                            onValueChange = { onToggle() }
                        )
                } else {
                    Modifier.size(size)
                }
            )
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (isDone) color else Color.Transparent)
                .border(width = maxOf(1.dp, size / 12), color = if (isDone) color else MaterialTheme.colorScheme.onSurfaceVariant, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (isDone) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = contrastForegroundOn(color),
                    modifier = Modifier.size(size * 2 / 3)
                )
            }
        }
    }
}

/** Band colors of the Tasks screen's priority numbers: 1–3 error, 4–6 tertiary, 7–9 muted. */
@Composable
internal fun taskPriorityColor(priority: Int): Color = when (priority) {
    in 1..3 -> MaterialTheme.colorScheme.error
    in 4..6 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * The row's due label: the formatted time for a timed placement, the localized "All day" for a
 * date-like one. [includeDate] prefixes the short date, as the search rows show it.
 */
@Composable
internal fun taskDueLabel(task: Task, timePattern: String, includeDate: Boolean = false): String {
    val whenTs = task.whenTs ?: return ""
    val readsAsDate = taskReadsAsAllDay(task)
    val timeText = DateTimeUtils.formatEventTime(whenTs, readsAsDate, timePattern)
    return when {
        timeText.isNotEmpty() && includeDate ->
            "${DateTimeUtils.formatEventDateShort(whenTs, readsAsDate)} $timeText"
        timeText.isNotEmpty() -> timeText
        else -> stringResource(R.string.label_all_day)
    }
}
