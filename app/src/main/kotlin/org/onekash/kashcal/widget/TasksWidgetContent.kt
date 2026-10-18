package org.onekash.kashcal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import org.onekash.kashcal.MainActivity
import org.onekash.kashcal.R
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Open tasks a list widget shows before the rows run out. Sized to a 4x2 widget at the compact
 * row height (~28dp each under the 48dp header): eight rows fill the panel, and the widget
 * scrolls for the rest, so the cap bounds the fetch, not the list.
 */
internal const val TASKS_WIDGET_MAX_TASKS = 8

/**
 * Renders the Tasks widget: its title header over the open tasks, or an empty state.
 *
 * Rows follow the event rows' two styles ([detailedRows]): one compact line, or a two-line
 * stack of title over due. Every row carries the leading empty checkbox that marks a task
 * against an event's color pill.
 *
 * @param tasks the open tasks, ordered by [WidgetDataRepository.getOpenTasks]
 * @param timePattern the time format, for example "h:mm a" or "HH:mm"
 */
@Composable
fun TasksWidgetContent(
    tasks: List<WidgetDataRepository.WidgetTask>,
    timePattern: String,
    isRefreshing: Boolean = false,
    detailedRows: Boolean = false
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetTheme.contentBackground)
            .cornerRadius(16.dp)
    ) {
        TasksWidgetHeader(isRefreshing)

        if (tasks.isEmpty()) {
            TasksEmptyState()
        } else {
            LazyColumn(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(vertical = 4.dp)
            ) {
                tasks.forEach { task ->
                    item(itemId = task.taskId) {
                        TaskRow(task, timePattern, detailedRows)
                    }
                }
            }
        }
    }
}

@Composable
private fun TasksWidgetHeader(isRefreshing: Boolean) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(WidgetTheme.headerBackground)
            // No vertical padding: the 48dp add button sets the header height, so every widget
            // header is 48dp. No end inset: the add button's glyph centering gives the right
            // margin, as in the month widget header.
            .padding(start = WIDGET_HORIZONTAL_MARGIN_DP.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(
                    actionStartActivity<MainActivity>(
                        parameters = actionParametersOf(
                            ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_GO_TO_TODAY
                        )
                    )
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_calendar),
                contentDescription = LocalContext.current.getString(R.string.cd_widget_calendar),
                modifier = GlanceModifier.size(20.dp)
            )
            Spacer(modifier = GlanceModifier.width(8.dp))
            Text(
                text = LocalContext.current.getString(R.string.view_tasks),
                style = TextStyle(
                    color = WidgetTheme.onHeaderBackground,
                    fontSize = WidgetTypography.headerTitle,
                    fontWeight = FontWeight.Medium
                ),
                // The refresh and add buttons take ~96dp on the right; on a narrow widget a long
                // localized title must ellipsize on one line, not wrap and grow the header.
                maxLines = 1
            )
        }
        WidgetRefreshButton(kind = WidgetKind.TASKS, isRefreshing = isRefreshing)
        WidgetAddButton()
    }
}

/** Shows one open task; tapping opens the app. */
@Composable
private fun TaskRow(
    task: WidgetDataRepository.WidgetTask,
    timePattern: String,
    detailedRows: Boolean
) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(
                horizontal = WIDGET_HORIZONTAL_MARGIN_DP.dp,
                vertical = eventRowVerticalPaddingDp(detailedRows).dp
            )
            .clickable(
                actionStartActivity<MainActivity>(
                    parameters = actionParametersOf(
                        ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_GO_TO_TODAY
                    )
                )
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TaskRowInner(task, System.currentTimeMillis(), timePattern, detailedRows)
    }
}

/**
 * Renders the inside of a task list row: the leading empty checkbox, the title and the due.
 * Shared by the Tasks, Agenda and Upcoming widgets so a task reads the same in all three; each
 * wraps it in its own clickable Row, which owns the padding and tap action.
 *
 * [nowMs] picks today for the due text: today's due shows the time (or the all-day label),
 * any other day the date, so an overdue or future task says which day it claims.
 *
 * @param detailedRows false renders one compact line; true renders a two-line stack (title at
 *   full width, then the due) beside a taller checkbox, as [EventRowInner] does for events.
 */
@Composable
internal fun RowScope.TaskRowInner(
    task: WidgetDataRepository.WidgetTask,
    nowMs: Long,
    timePattern: String,
    detailedRows: Boolean
) {
    val context = LocalContext.current
    val dueText = formatWidgetTaskTime(
        context = context,
        task = task,
        todayCode = DateTimeUtils.eventTsToDayCode(nowMs, isAllDay = false),
        timePattern = timePattern,
        allDayText = context.getString(R.string.label_all_day)
    )

    TaskCheckbox(heightDp = if (detailedRows) TASK_CHECKBOX_DETAILED_DP else TASK_CHECKBOX_COMPACT_DP)
    Spacer(modifier = GlanceModifier.width(BAR_TO_TIME_GAP_DP.dp))

    if (detailedRows) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = task.title,
                style = TextStyle(
                    color = WidgetTheme.primaryText,
                    fontSize = WidgetTypography.contentTitle,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1
            )
            // Same color as the title so the row reads as one unit, as in the detailed event row.
            Text(
                text = dueText.orEmpty(),
                style = TextStyle(
                    color = WidgetTheme.primaryText,
                    fontSize = WidgetTypography.secondary
                ),
                maxLines = 1
            )
        }
    } else {
        Text(
            text = task.title,
            style = TextStyle(
                color = WidgetTheme.primaryText,
                fontSize = WidgetTypography.contentTitle,
                fontWeight = FontWeight.Medium
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight()
        )
        if (dueText != null) {
            Text(
                text = dueText,
                style = TextStyle(
                    color = WidgetTheme.secondaryText,
                    fontSize = WidgetTypography.secondary
                ),
                maxLines = 1
            )
        }
    }
}

/**
 * The leading empty checkbox marking a task row. A translucent-look fill, not a glyph or a
 * border: Glance text falls back to system fonts where a missing checkbox glyph renders as
 * tofu, Glance 1.2 has no border modifier, and Glance has no alpha on colors — the `outline`
 * role reads as an unchecked mark at widget scale and draws everywhere.
 */
@Composable
private fun TaskCheckbox(heightDp: Int) {
    Box(
        modifier = GlanceModifier
            .size(heightDp.dp)
            .cornerRadius(3.dp)
            .background(WidgetTheme.dimmedOnHeaderBackground)
    ) {}
}

/** Height of the compact single-line task row's checkbox, in dp. */
internal const val TASK_CHECKBOX_COMPACT_DP = 16

/** Height of the detailed two-line task row's checkbox, in dp. */
internal const val TASK_CHECKBOX_DETAILED_DP = 24

/**
 * Returns the due text of a task row: the time for a due (or dtstart) today, the all-day label
 * for an all-day due today, and the date for any other day; null when the task is undated. A
 * pure mapping over the resolved [timePattern], so it stays unit-testable without a harness.
 */
internal fun formatWidgetTaskTime(
    context: Context,
    task: WidgetDataRepository.WidgetTask,
    todayCode: Int,
    timePattern: String,
    allDayText: String
): String? {
    val whenTs = task.whenTs ?: return null
    if (task.dayCode == todayCode) {
        if (task.isAllDay) return allDayText
        val formatter = DateTimeFormatter.ofPattern(timePattern, Locale.getDefault())
        return Instant.ofEpochMilli(whenTs)
            .atZone(ZoneId.systemDefault())
            .format(formatter)
            .lowercase(Locale.getDefault())
    }
    val date = DateTimeUtils.eventTsToLocalDate(whenTs, task.isAllDay)
    val formatter = DateTimeFormatter.ofPattern(
        DateTimeUtils.localizedPattern("EEEMMMd"),
        Locale.getDefault()
    )
    return date.format(formatter)
}

/** Shows the no-open-tasks state; tapping opens the app. */
@Composable
private fun TasksEmptyState() {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(16.dp)
            .clickable(
                actionStartActivity<MainActivity>(
                    parameters = actionParametersOf(
                        ActionParameters.Key<String>(EXTRA_ACTION) to ACTION_GO_TO_TODAY
                    )
                )
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = LocalContext.current.getString(R.string.tasks_widget_empty),
            style = TextStyle(
                color = WidgetTheme.secondaryText,
                fontSize = WidgetTypography.contentTitle
            )
        )
    }
}
