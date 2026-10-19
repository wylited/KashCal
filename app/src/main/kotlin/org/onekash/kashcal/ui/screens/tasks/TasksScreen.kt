package org.onekash.kashcal.ui.screens.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Task
import org.onekash.kashcal.ui.components.taskReadsAsAllDay
import org.onekash.kashcal.ui.shared.contrastForegroundOn
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The Tasks view: filter chips over the whole list, then the open tasks grouped by placement
 * (Overdue → Today → Tomorrow → This week → Later → No date) with the completed ones trailing
 * under [TaskStatusFilter.DONE] and [TaskStatusFilter.ALL].
 *
 * The model ([TasksListModel]) owns grouping, filtering and sorting; this screen only renders
 * groups verbatim and formats the per-row labels. Checkbox tap completes or reopens, row tap
 * opens the quick view, and creating rides the host's FAB.
 *
 * @param todayDayCode today as YYYYMMDD, re-read on the host's resume, so a screen left open
 *   past midnight re-groups on the next resume without watching the clock.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    tasks: List<Task>,
    calendars: List<Calendar>,
    timePattern: String,
    todayDayCode: Int,
    onToggleTaskDone: (Task) -> Unit,
    onTaskClick: (Task) -> Unit,
    modifier: Modifier = Modifier,
) {
    var statusFilter by rememberSaveable { mutableStateOf(TaskStatusFilter.OPEN) }
    var priorityBand by rememberSaveable { mutableStateOf(TaskPriorityBand.ANY) }

    val zone = remember { ZoneId.systemDefault() }
    val groups = remember(tasks, statusFilter, priorityBand, todayDayCode) {
        TasksListModel.build(tasks, statusFilter, priorityBand, zone = zone)
    }
    val counts = remember(tasks, todayDayCode) { TasksListModel.filterCounts(tasks, zone = zone) }
    val calendarsById = remember(calendars) { calendars.associateBy { it.id } }

    Column(modifier = modifier.fillMaxSize()) {
        // Status chips: Open (default), All, Done, each with its count.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            FilterChip(
                selected = statusFilter == TaskStatusFilter.OPEN,
                onClick = { statusFilter = TaskStatusFilter.OPEN },
                label = { Text(chipLabel(R.string.tasks_filter_open, counts.open)) }
            )
            FilterChip(
                selected = statusFilter == TaskStatusFilter.ALL,
                onClick = { statusFilter = TaskStatusFilter.ALL },
                label = { Text(chipLabel(R.string.tasks_filter_all, counts.all)) }
            )
            FilterChip(
                selected = statusFilter == TaskStatusFilter.DONE,
                onClick = { statusFilter = TaskStatusFilter.DONE },
                label = { Text(chipLabel(R.string.tasks_filter_done, counts.done)) }
            )
        }

        // Priority band chips: the row chips' colors in band form. Five chips overflow a phone
        // width, so the row scrolls: unscrollable, the last chip is measured with only the
        // leftover width and its label stacks one character per line. Start-aligned, since a
        // centered arrangement overflows left of scroll position 0.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = priorityBand == TaskPriorityBand.ANY,
                onClick = { priorityBand = TaskPriorityBand.ANY },
                label = { Text(stringResource(R.string.tasks_filter_priority_all)) }
            )
            FilterChip(
                selected = priorityBand == TaskPriorityBand.HIGH,
                onClick = { priorityBand = TaskPriorityBand.HIGH },
                label = { Text(chipLabel(R.string.tasks_filter_p1, counts.high)) }
            )
            FilterChip(
                selected = priorityBand == TaskPriorityBand.MEDIUM,
                onClick = { priorityBand = TaskPriorityBand.MEDIUM },
                label = { Text(chipLabel(R.string.tasks_filter_p2, counts.medium)) }
            )
            FilterChip(
                selected = priorityBand == TaskPriorityBand.LOW,
                onClick = { priorityBand = TaskPriorityBand.LOW },
                label = { Text(chipLabel(R.string.tasks_filter_p3, counts.low)) }
            )
            FilterChip(
                selected = priorityBand == TaskPriorityBand.NONE,
                onClick = { priorityBand = TaskPriorityBand.NONE },
                label = { Text(chipLabel(R.string.tasks_filter_priority_none, counts.none)) }
            )
        }

        if (groups.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.tasks_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                groups.forEach { group ->
                    item(key = "header_${group.id}", contentType = "task_header") {
                        Text(
                            text = group.titleRes?.let { stringResource(it) }.orEmpty(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(
                        items = group.items,
                        key = { "task_${it.task.id}" },
                        contentType = { "task_row" }
                    ) { taskUi ->
                        val calendar = calendarsById[taskUi.task.calendarId]
                        TaskRow(
                            taskUi = taskUi,
                            calendarColor = calendar?.color,
                            timePattern = timePattern,
                            onToggleDone = { onToggleTaskDone(taskUi.task) },
                            onClick = { onTaskClick(taskUi.task) }
                        )
                    }
                }
            }
        }
    }
}

/** "Open · 3": a chip label with its count; a zero count still names the empty chip. */
@Composable
private fun chipLabel(labelRes: Int, count: Int): String =
    "${stringResource(labelRes)} · $count"

/** One task row: round checkbox, summary, priority number and the due chip. */
@Composable
private fun TaskRow(
    taskUi: TaskUi,
    calendarColor: Int?,
    timePattern: String,
    onToggleDone: () -> Unit,
    onClick: () -> Unit,
) {
    val task = taskUi.task
    val done = task.isDone
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TaskCheckbox(
            isDone = done,
            color = calendarColor?.let { Color(it) } ?: MaterialTheme.colorScheme.primary,
            contentDescription = stringResource(
                if (done) R.string.cd_task_reopen else R.string.cd_task_complete
            ),
            onToggle = onToggleDone
        )
        Text(
            text = task.summary,
            style = MaterialTheme.typography.bodyLarge,
            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (done) TextDecoration.LineThrough else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (task.priority in 1..9) {
            val priorityDescription = stringResource(R.string.cd_task_priority, task.priority)
            Text(
                text = task.priority.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = priorityColor(task.priority),
                modifier = Modifier.semantics {
                    contentDescription = priorityDescription
                }
            )
        }
        if (task.whenTs != null) {
            TaskDueChip(
                task = task,
                isOverdue = taskUi.isOverdue,
                timePattern = timePattern
            )
        }
    }
}

/** Priority band colors, the same bands the Views settings' chips label: 1–3, 4–6, 7–9. */
@Composable
private fun priorityColor(priority: Int): Color = when (priority) {
    in 1..3 -> MaterialTheme.colorScheme.error
    in 4..6 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * The round checkbox: an outlined circle when open, filled with the calendar color (or primary)
 * and a check when done. A custom control, since a task's check marks completion rather than
 * selection and carries the calendar's color.
 */
@Composable
private fun TaskCheckbox(
    isDone: Boolean,
    color: Color,
    contentDescription: String,
    onToggle: () -> Unit,
) {
    val checkColor = contrastForegroundOn(color)
    // The 48dp touch target with Role.Checkbox + toggleable state, so a11y services see a
    // checkbox; the visual stays the 24dp circle.
    Box(
        modifier = Modifier
            .size(48.dp)
            .toggleable(
                value = isDone,
                role = Role.Checkbox,
                onValueChange = { onToggle() }
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (isDone) color else Color.Transparent)
                .border(
                    width = 2.dp,
                    color = if (isDone) color else MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isDone) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = checkColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** The trailing due chip: error when overdue, accent today, muted otherwise. */
@Composable
private fun TaskDueChip(
    task: Task,
    isOverdue: Boolean,
    timePattern: String,
) {
    val whenTs = task.whenTs ?: return
    // Only a date-placed value renders date-only: a real all-day DUE, or a DTSTART fallback
    // stored at UTC midnight — the same rule [WidgetDataRepository.toWidgetTask] applies.
    val isAllDayDue = taskReadsAsAllDay(task)
    val containerColor = when {
        isOverdue -> MaterialTheme.colorScheme.error
        isToday(whenTs, isAllDayDue) -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        isOverdue -> MaterialTheme.colorScheme.onError
        isToday(whenTs, isAllDayDue) -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        contentColor = contentColor
    ) {
        Text(
            text = dueChipLabel(whenTs, isAllDayDue, timePattern),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

/** The date, with the time when the placement value is timed. */
@Composable
private fun dueChipLabel(whenTs: Long, isAllDay: Boolean, timePattern: String): String {
    val dateText = DateTimeUtils.formatEventDateShort(whenTs, isAllDay)
    val timeText = DateTimeUtils.formatEventTime(whenTs, isAllDay, timePattern)
    return if (timeText.isEmpty()) dateText else "$dateText $timeText"
}

/**
 * True when the placement moment falls on today: a date-placed value (an all-day due, or a
 * UTC-midnight DTSTART fallback) reads as its UTC calendar date, matching the grouping, so
 * "today's" chip survives west-of-UTC afternoons; a timed value reads in the device zone.
 */
private fun isToday(whenTs: Long, isAllDay: Boolean): Boolean {
    val zone = if (isAllDay) ZoneOffset.UTC else ZoneId.systemDefault()
    return Instant.ofEpochMilli(whenTs).atZone(zone).toLocalDate() == LocalDate.now()
}
