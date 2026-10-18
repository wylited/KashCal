package org.onekash.kashcal.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.entity.Task
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Shows one task's details in a bottom sheet, the task counterpart of [EventQuickViewSheet]:
 * status, due, calendar and notes, with Complete/Reopen, Edit and a two-tap Delete.
 *
 * @param onToggleDone flips the completion triple (status, COMPLETED, PERCENT-COMPLETE) in the
 *   repository; the sheet re-renders from the task the host re-reads.
 * @param onDelete called after the inline confirm, once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskQuickViewSheet(
    task: Task,
    calendarColor: Int,
    calendarName: String,
    timeFormat: String = "system",
    onDismiss: () -> Unit,
    onToggleDone: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val is24HourDevice = DateFormat.is24HourFormat(context)
    val timePattern = remember(timeFormat, is24HourDevice) {
        DateTimeUtils.getTimePattern(timeFormat, is24HourDevice)
    }
    val isOverdue = remember(task) { task.isOverdueTask() }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = !task.description.isNullOrBlank()
    )
    var showDeleteConfirmation by remember(task.id) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
                // Announce the sheet (by the task summary) when it opens.
                .semantics { paneTitle = task.summary }
        ) {
            // Calendar pill, the header strip's left half of the event sheet.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color(calendarColor))
                )
                Text(
                    text = calendarName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Details with color stripe, as the event sheet lays them out.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(IntrinsicSize.Min)
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(
                            color = Color(calendarColor),
                            shape = RoundedCornerShape(2.dp)
                        )
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = task.summary,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (task.isDone) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                        modifier = Modifier.semantics { heading() }
                    )

                    // Status row: the completion state as a colored dot and its label.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(statusDotColor(task.status))
                        )
                        Text(
                            text = stringResource(R.string.task_quickview_status),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = statusLabel(task.status),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Due row: the placement value, error-colored when the task is overdue.
                    if (task.whenTs != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.task_quickview_due),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = dueValueLabel(task, timePattern),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isOverdue) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    if (task.rrule != null) {
                        Text(
                            text = "\uD83D\uDD01 ${stringResource(R.string.task_repeats)} · ${task.rrule}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (!task.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = task.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Actions: Complete/Reopen, Edit, Delete — the delete swaps to the two-tap
            // Cancel/Confirm pair, exactly as the event quick view confirms.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!showDeleteConfirmation) {
                    FilledTonalButton(
                        onClick = onToggleDone,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (task.isDone) Icons.Default.Replay else Icons.Default.Check,
                            contentDescription = stringResource(
                                if (task.isDone) R.string.cd_task_reopen else R.string.cd_task_complete
                            )
                        )
                    }
                    FilledTonalButton(
                        onClick = onEdit,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.action_edit)
                        )
                    }
                    FilledTonalButton(
                        onClick = { showDeleteConfirmation = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.action_delete)
                        )
                    }
                } else {
                    FilledTonalButton(
                        onClick = { showDeleteConfirmation = false },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(R.string.action_cancel),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            showDeleteConfirmation = false
                            onDelete()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.task_delete_confirm),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

/** The status dot's color: in-progress active, open waiting, done muted, cancelled errored. */
@Composable
private fun statusDotColor(status: String): Color = when (status) {
    Task.STATUS_IN_PROCESS -> MaterialTheme.colorScheme.primary
    Task.STATUS_COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant
    Task.STATUS_CANCELLED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.tertiary
}

/** The `task_status_*` label for a [Task.STATUS_*] value. */
@Composable
private fun statusLabel(status: String): String = when (status) {
    Task.STATUS_IN_PROCESS -> stringResource(R.string.task_status_in_progress)
    Task.STATUS_COMPLETED -> stringResource(R.string.task_status_completed)
    Task.STATUS_CANCELLED -> stringResource(R.string.task_status_cancelled)
    else -> stringResource(R.string.task_status_open)
}

/** The due line: the date, with the time when the due is timed. */
@Composable
private fun dueValueLabel(task: Task, timePattern: String): String {
    val whenTs = task.whenTs ?: return ""
    val dateText = DateTimeUtils.formatEventDateShort(whenTs, task.dueIsAllDay)
    val timeText = DateTimeUtils.formatEventTime(whenTs, task.dueIsAllDay, timePattern)
    return if (timeText.isEmpty()) dateText else "$dateText $timeText"
}

/**
 * True when an open task's placement day is before today, the same day-based rule the task
 * list groups by: an all-day due reads as its calendar date, not as a UTC-midnight instant.
 */
private fun Task.isOverdueTask(today: LocalDate = LocalDate.now()): Boolean {
    if (!isOpen) return false
    val ts = whenTs ?: return false
    val zone = if (dueTs != null && dueIsAllDay) ZoneOffset.UTC else ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
    return date.isBefore(today)
}
