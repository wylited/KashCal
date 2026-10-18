package org.onekash.kashcal.ui.components

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Task
import org.onekash.kashcal.ui.components.pickers.DateTimeSheet
import org.onekash.kashcal.ui.components.pickers.EventFormRow
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.shared.contrastForegroundOn
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The task form's fields. [dueDateMillis] is the date picker's local-midnight timestamp of the
 * DUE date; [dueTimezone] carries the loaded task's DUE zone through an edit so saving doesn't
 * silently move a foreign-zone due to the device zone. A new or all-day due leaves it null and
 * saves the device zone, exactly how a new event form behaves.
 */
data class TaskFormState(
    val editingTaskId: Long? = null,
    val title: String = "",
    val notes: String = "",
    /** Whether the task has a due at all; false saves an undated task (it stays at its DTSTART). */
    val hasDue: Boolean = false,
    val dueDateMillis: Long? = null,
    val dueHour: Int = 9,
    val dueMinute: Int = 0,
    val isAllDay: Boolean = false,
    val dueTimezone: String? = null,
    /** RFC 5545 PRIORITY: 0 none, 1 highest, 9 lowest. */
    val priority: Int = 0,
    val calendarId: Long? = null,
    val calendarName: String = "",
    val calendarColor: Int? = null,
    /** Read-only display of the loaded rule; recurring tasks are never client-expanded. */
    val rrule: String? = null,
    val isSaving: Boolean = false,
) {
    val isEditMode: Boolean get() = editingTaskId != null
}

/**
 * The persisted DUE fields for this state: epoch millis, the IANA zone of a timed due (null for
 * all-day) and the all-day flag. An all-day due stores as UTC midnight of the picked date, the
 * same convention as all-day event starts.
 */
fun TaskFormState.toDueFields(): Triple<Long?, String?, Boolean> {
    if (!hasDue || dueDateMillis == null) return Triple(null, null, false)
    if (isAllDay) return Triple(DateTimeUtils.localDateToUtcMidnight(dueDateMillis), null, true)
    val zone = dueTimezone.asZoneId() ?: ZoneId.systemDefault()
    val dueTs = Instant.ofEpochMilli(dueDateMillis).atZone(zone).toLocalDate()
        .atTime(dueHour, dueMinute)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()
    return Triple(dueTs, zone.id, false)
}

/** Builds the form state from a task; the calendar fields are resolved by the sheet. */
fun Task.toTaskFormState(): TaskFormState {
    var pickerDate: Long? = null
    var hour = 9
    var minute = 0
    if (dueTs != null) {
        if (dueIsAllDay) {
            pickerDate = DateTimeUtils.utcMidnightToLocalDate(dueTs)
        } else {
            val zone = dueTimezone.asZoneId() ?: ZoneId.systemDefault()
            val zoned = Instant.ofEpochMilli(dueTs).atZone(zone)
            pickerDate = zoned.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            hour = zoned.hour
            minute = zoned.minute
        }
    }
    return TaskFormState(
        editingTaskId = id,
        title = summary,
        notes = description.orEmpty(),
        hasDue = dueTs != null,
        dueDateMillis = pickerDate,
        dueHour = hour,
        dueMinute = minute,
        isAllDay = dueIsAllDay,
        dueTimezone = if (dueIsAllDay || dueTs == null) null else dueTimezone,
        priority = priority,
        rrule = rrule,
    )
}

private fun String?.asZoneId(): ZoneId? =
    this?.let { runCatching { ZoneId.of(it) }.getOrNull() }

/**
 * Creates or edits one task in a full-screen dialog, the task counterpart of [EventFormSheet]:
 * the same Dialog shell (keyboard-first, back through the discard guard) and form-row idioms,
 * but no recurrence editing (an RRULE shows read-only; recurrence is server-owned) and no
 * occurrence scopes (a task never expands).
 *
 * @param task the task to edit, or null to create one.
 * @param calendars the candidate calendars; the sheet lists only `supportsVTodo` ones and
 *   preselects [task]'s calendar on an edit.
 * @param onSave persists the form; on success the sheet closes itself, on failure it stays open
 *   with the user's edits and a re-enabled Save.
 * @param onDelete tears the task down; the row shows only in edit mode and asks twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskFormSheet(
    task: Task?,
    calendars: List<Calendar>,
    calendarGroups: List<CalendarGroup>,
    timeFormat: String = "system",
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY,
    onDismiss: () -> Unit,
    onSave: suspend (TaskFormState) -> Result<Task>,
    onDelete: ((Task) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val taskCalendars = remember(calendars) { calendars.filter { it.supportsVTodo } }
    var state by remember(task) {
        mutableStateOf(
            (task?.toTaskFormState() ?: TaskFormState()).withCalendarResolved(taskCalendars)
        )
    }
    val initialSnapshot = remember(task) { state }

    var isSaving by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    val hasUnsavedChanges = state.copy(isSaving = false) != initialSnapshot.copy(isSaving = false)

    // Same state machine as the event form: a clean form dismisses at once, a dirty one asks,
    // and an in-flight save blocks dismissal so the write can't be torn down mid-flight.
    val attemptDismiss: () -> Unit = when {
        isSaving -> ({})
        hasUnsavedChanges && !showDiscardConfirm -> ({ showDiscardConfirm = true })
        else -> onDismiss
    }
    BackHandler { attemptDismiss() }

    val scope = rememberCoroutineScope()
    val performSave: () -> Unit = {
        if (state.title.isNotBlank() && state.calendarId != null && !state.isSaving) {
            val saving = state.copy(isSaving = true)
            state = saving
            isSaving = true
            scope.launch {
                val result = onSave(saving.copy(isSaving = false))
                if (result.isSuccess) {
                    onDismiss()
                } else {
                    // Keep the form open with the user's edits for a retry; the mirror of the
                    // event form's scopeSaveFailedTick path, without the deferral.
                    isSaving = false
                    state = saving.copy(isSaving = false)
                }
            }
        }
    }

    val context = LocalContext.current
    val use24Hour = remember(timeFormat) {
        timeFormat != "12h" && (timeFormat == "24h" || DateFormat.is24HourFormat(context))
    }

    // A full-screen Dialog, not a ModalBottomSheet, so the keyboard rises at once; back goes
    // through the BackHandler into the discard guard, exactly like the event form.
    Dialog(
        onDismissRequest = attemptDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
        )
    ) {
        Surface(modifier = modifier.fillMaxSize()) {
            val paneTitleText = if (state.isEditMode) {
                stringResource(R.string.action_edit)
            } else {
                stringResource(R.string.task_new)
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showDiscardConfirm) {
                        Button(
                            onClick = {
                                showDiscardConfirm = false
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.action_discard_confirm),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    } else {
                        TextButton(onClick = attemptDismiss) {
                            Text(text = stringResource(R.string.action_cancel), maxLines = 1, softWrap = false)
                        }
                    }
                    Text(
                        text = paneTitleText,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                            .semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val calColor = state.calendarColor?.let { Color(it) }
                        ?: MaterialTheme.colorScheme.primary
                    val contrastColor = contrastForegroundOn(calColor)
                    Button(
                        onClick = performSave,
                        enabled = state.title.isNotBlank() && state.calendarId != null && !state.isSaving,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = calColor,
                            contentColor = contrastColor
                        ),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = contrastColor
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.action_save),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                HorizontalDivider()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    var expandedPicker by remember { mutableStateOf<String?>(null) }

                    OutlinedTextField(
                        value = state.title,
                        onValueChange = { state = state.copy(title = it) },
                        label = { Text(stringResource(R.string.task_field_title)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    OutlinedTextField(
                        value = state.notes,
                        onValueChange = { state = state.copy(notes = it) },
                        label = { Text(stringResource(R.string.label_notes)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // Due: the row opens the shared date/time sheet; the all-day switch rides
                    // beside the value, as DateTimeDisplayRow pairs its toggle with the values.
                    var showDueSheet by remember { mutableStateOf(false) }
                    EventFormRow(
                        icon = Icons.Default.EventAvailable,
                        iconContentDescription = stringResource(R.string.task_field_due),
                        showExpandIcon = true,
                        onToggle = { showDueSheet = true }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.task_field_due),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = dueValueLabel(state, use24Hour),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (state.hasDue) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (state.hasDue) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.label_all_day),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Switch(
                                    checked = state.isAllDay,
                                    onCheckedChange = { state = state.copy(isAllDay = it) }
                                )
                            }
                        }
                    }

                    if (showDueSheet) {
                        DateTimeSheet(
                            label = stringResource(R.string.task_field_due),
                            selectedDateMillis = state.dueDateMillis
                                ?: LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                            selectedHour = state.dueHour,
                            selectedMinute = state.dueMinute,
                            isAllDay = state.isAllDay,
                            use24Hour = use24Hour,
                            onConfirm = { dateMillis, hour, minute ->
                                state = state.copy(
                                    hasDue = true,
                                    dueDateMillis = dateMillis,
                                    dueHour = hour,
                                    dueMinute = minute
                                )
                                showDueSheet = false
                            },
                            onDismiss = { showDueSheet = false },
                            firstDayOfWeek = firstDayOfWeek
                        )
                    }

                    // Priority: the full 1–9 range as chips; the banded colors are a display
                    // concern of the list rows, not of the choice itself.
                    EventFormRow(
                        icon = Icons.Default.Flag,
                        iconContentDescription = stringResource(R.string.task_field_priority),
                        isExpanded = expandedPicker == "priority",
                        showExpandIcon = true,
                        expandedContent = {
                            PriorityChipRow(
                                selected = state.priority,
                                onSelect = { state = state.copy(priority = it) }
                            )
                        },
                        onToggle = {
                            expandedPicker = if (expandedPicker == "priority") null else "priority"
                        }
                    ) {
                        Text(
                            text = priorityLabel(state.priority),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    TaskCalendarPickerRow(
                        selectedCalendarId = state.calendarId,
                        selectedCalendarName = state.calendarName,
                        selectedCalendarColor = state.calendarColor,
                        calendarGroups = calendarGroups,
                        isExpanded = expandedPicker == "calendar",
                        onToggle = { expandedPicker = if (expandedPicker == "calendar") null else "calendar" },
                        onSelect = { calendar ->
                            state = state.copy(
                                calendarId = calendar.id,
                                calendarName = calendar.displayName,
                                calendarColor = calendar.color
                            )
                            expandedPicker = null
                        }
                    )

                    if (state.isEditMode && state.rrule != null) {
                        EventFormRow(
                            icon = Icons.Default.Repeat,
                            iconContentDescription = stringResource(R.string.task_repeats)
                        ) {
                            Text(
                                text = stringResource(R.string.task_repeats) + " · " + state.rrule,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (state.isEditMode && task != null && onDelete != null) {
                        var confirmDelete by remember(task.id) { mutableStateOf(false) }
                        EventFormRow(
                            icon = Icons.Default.DeleteOutline,
                            iconContentDescription = stringResource(R.string.action_delete),
                            iconTint = MaterialTheme.colorScheme.error,
                            onToggle = {
                                if (confirmDelete) onDelete(task) else confirmDelete = true
                            }
                        ) {
                            Text(
                                text = stringResource(
                                    if (confirmDelete) R.string.task_delete_confirm else R.string.action_delete
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

/** Resolves the calendar fields against [taskCalendars]: the task's own calendar, else the first. */
private fun TaskFormState.withCalendarResolved(taskCalendars: List<Calendar>): TaskFormState {
    val selected = calendarId?.let { id -> taskCalendars.firstOrNull { it.id == id } }
        ?: taskCalendars.firstOrNull()
        ?: return this
    return copy(
        calendarId = selected.id,
        calendarName = selected.displayName,
        calendarColor = selected.color
    )
}

/** The due row's value line: the date, with the time when the due is timed. */
@Composable
private fun dueValueLabel(state: TaskFormState, use24Hour: Boolean): String {
    if (!state.hasDue || state.dueDateMillis == null) return ""
    val dateText = DateTimeUtils.formatEventDate(
        state.dueDateMillis,
        isAllDay = false,
        pattern = DateTimeUtils.localizedPattern("EEEMMMd")
    )
    if (state.isAllDay) return dateText
    val timeText = DateTimeUtils.formatEventTime(
        duePreviewInstant(state),
        isAllDay = false,
        pattern = if (use24Hour) "HH:mm" else "h:mm a"
    )
    return "$dateText $timeText"
}

/** Rebuilds the due instant from the picker fields, for display only. */
private fun duePreviewInstant(state: TaskFormState): Long {
    val dueDate = state.dueDateMillis ?: return 0L
    val zone = state.dueTimezone.asZoneId() ?: ZoneId.systemDefault()
    return Instant.ofEpochMilli(dueDate).atZone(zone).toLocalDate()
        .atTime(state.dueHour, state.dueMinute)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()
}

/** "None" for the undefined priority, else the number itself. */
@Composable
private fun priorityLabel(priority: Int): String =
    if (priority == 0) stringResource(R.string.task_priority_none) else priority.toString()

/** The None + 1–9 chips, wrapping like the tag chip row does. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PriorityChipRow(selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 52.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        FilterChip(
            selected = selected == 0,
            onClick = { onSelect(0) },
            label = { Text(stringResource(R.string.task_priority_none)) }
        )
        for (priority in 1..9) {
            FilterChip(
                selected = selected == priority,
                onClick = { onSelect(priority) },
                label = { Text(priority.toString()) }
            )
        }
    }
}

/**
 * The calendar row and its expanded picker, listing only the VTODO-capable calendars by
 * account. A near-copy of [CalendarPickerRow], which can't be reused because it filters its
 * groups on `supportsEvents`.
 */
@Composable
private fun TaskCalendarPickerRow(
    selectedCalendarId: Long?,
    selectedCalendarName: String,
    selectedCalendarColor: Int?,
    calendarGroups: List<CalendarGroup>,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (Calendar) -> Unit,
) {
    EventFormRow(
        icon = Icons.Default.CalendarMonth,
        iconContentDescription = stringResource(R.string.label_calendar),
        isExpanded = isExpanded,
        showExpandIcon = true,
        onToggle = onToggle,
        expandedContent = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val groups = calendarGroups
                    .filterNot { it.isDeviceSection }
                    .map { group -> group to group.calendars.filter { it.supportsVTodo } }
                    .filter { (_, calendars) -> calendars.isNotEmpty() }
                if (groups.isEmpty()) {
                    Text(
                        text = stringResource(R.string.tasks_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
                groups.forEach { (group, calendars) ->
                    Text(
                        text = group.accountName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                    )
                    calendars.forEach { calendar ->
                        val isSelected = calendar.id == selectedCalendarId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onSelect(calendar) }
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else Color.Transparent
                                )
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(Color(calendar.color))
                            )
                            Text(
                                text = calendar.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = stringResource(R.string.cd_selected),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (selectedCalendarColor != null) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color(selectedCalendarColor))
                )
            }
            Text(
                text = selectedCalendarName.ifEmpty { stringResource(R.string.label_calendar) },
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
