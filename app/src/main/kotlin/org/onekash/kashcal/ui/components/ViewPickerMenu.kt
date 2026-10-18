package org.onekash.kashcal.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CalendarViewMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.viewmodels.ViewMode

/**
 * One view-mode row of the picker and drawer: the mode and its icon. Public so hosts can pass
 * a customized order (the Views settings' order, hidden removed) into [ViewPickerButton].
 */
data class ViewOption(
    val mode: ViewMode,
    val icon: ImageVector
)

/**
 * The selectable calendar views in ViewMode declaration order — the pickers' default. INSIGHTS
 * and CONTACTS stay out: the drawer lists them in their own rows, and neither is a persisted
 * default view.
 */
val defaultViewOptions: List<ViewOption> =
    ViewMode.entries
        .filterNot { it == ViewMode.INSIGHTS || it == ViewMode.CONTACTS }
        .map { ViewOption(it, iconForMode(it)) }

internal fun iconForMode(mode: ViewMode): ImageVector = when (mode) {
    ViewMode.MONTH -> Icons.Default.CalendarMonth
    ViewMode.AGENDA -> Icons.Default.ViewAgenda
    ViewMode.TASKS -> Icons.Default.Checklist
    ViewMode.DAY -> Icons.Default.CalendarToday
    ViewMode.THREE_DAYS -> Icons.Default.ViewWeek
    ViewMode.WEEK -> Icons.Default.DateRange
    ViewMode.PLANNER -> Icons.Default.ViewModule
    ViewMode.MONTH_FULL -> Icons.Default.GridView
    ViewMode.YEAR -> Icons.Default.CalendarViewMonth
    ViewMode.INSIGHTS -> Icons.Default.Insights
    ViewMode.CONTACTS -> Icons.Default.Contacts
}

@Composable
internal fun viewModeLabel(mode: ViewMode): String = when (mode) {
    ViewMode.AGENDA -> stringResource(R.string.view_agenda)
    ViewMode.DAY -> stringResource(R.string.view_day)
    ViewMode.THREE_DAYS -> stringResource(R.string.view_three_days)
    ViewMode.WEEK -> stringResource(R.string.view_week)
    ViewMode.PLANNER -> stringResource(R.string.view_planner)
    ViewMode.MONTH -> stringResource(R.string.view_month)
    ViewMode.MONTH_FULL -> stringResource(R.string.view_month_full)
    ViewMode.YEAR -> stringResource(R.string.view_year)
    ViewMode.INSIGHTS -> stringResource(R.string.view_insights)
    ViewMode.TASKS -> stringResource(R.string.view_tasks)
    ViewMode.CONTACTS -> stringResource(R.string.view_contacts)
}

/**
 * Shows the current view's icon with a small chevron; a tap opens a menu of [options] that
 * reports the chosen view through [onViewSelect]. Hosts pass the Views settings' order minus
 * the hidden views; the default is [defaultViewOptions].
 */
@Composable
fun ViewPickerButton(
    currentView: ViewMode,
    onViewSelect: (ViewMode) -> Unit,
    modifier: Modifier = Modifier,
    options: List<ViewOption> = defaultViewOptions
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = iconForMode(currentView),
                    contentDescription = stringResource(R.string.cd_calendar_view),
                    modifier = Modifier.size(24.dp)
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(12.dp)
                        .align(Alignment.BottomEnd)
                        .offset(x = 4.dp, y = 4.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                val isActive = currentView == option.mode
                DropdownMenuItem(
                    text = { Text(viewModeLabel(option.mode)) },
                    onClick = {
                        expanded = false
                        onViewSelect(option.mode)
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = option.icon,
                            contentDescription = null,
                            tint = if (isActive) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = if (isActive) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.cd_active_view),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null
                )
            }
        }
    }
}
