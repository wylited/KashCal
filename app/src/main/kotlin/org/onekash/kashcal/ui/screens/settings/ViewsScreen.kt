package org.onekash.kashcal.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.onekash.kashcal.R
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.ui.components.SettingsTopAppBar
import org.onekash.kashcal.ui.components.viewModeLabel
import org.onekash.kashcal.ui.viewmodels.ViewMode
import javax.inject.Inject
import kotlin.math.abs

/**
 * A per-view option the Views settings' options sheet edits. [labelRes] is the row's label;
 * which views a key applies to is [VIEW_OPTION_APPLICABILITY].
 */
enum class ViewOptionKey(@StringRes val labelRes: Int) {
    /** Week and Planner drop Saturday and Sunday. */
    HIDE_WEEKENDS(R.string.view_opt_hide_weekends),

    /** Month, Month (Full) and Agenda carry a week-number column or caption. Writes the shared
     *  week-numbers preference (the Display settings' "Show week numbers"), not a per-view key. */
    WEEK_NUMBERS(R.string.view_opt_week_numbers),

    /** Agenda also lists completed tasks. */
    AGENDA_COMPLETED_TASKS(R.string.view_opt_agenda_completed_tasks),

    /** Month grids badge days with due tasks. */
    MONTH_TASK_BADGE(R.string.view_opt_month_task_badge),
}

/** The views each [ViewOptionKey] applies to; a view's sheet lists only its own options. */
private val VIEW_OPTION_APPLICABILITY: Map<ViewOptionKey, Set<ViewMode>> = mapOf(
    ViewOptionKey.HIDE_WEEKENDS to setOf(ViewMode.WEEK, ViewMode.PLANNER),
    ViewOptionKey.WEEK_NUMBERS to setOf(ViewMode.MONTH, ViewMode.MONTH_FULL, ViewMode.AGENDA),
    ViewOptionKey.AGENDA_COMPLETED_TASKS to setOf(ViewMode.AGENDA),
    ViewOptionKey.MONTH_TASK_BADGE to setOf(ViewMode.MONTH, ViewMode.MONTH_FULL),
)

/**
 * The Views settings' state: the full ordered view list (hidden views included, in the slot
 * [KashCalDataStore.setVisibleViews] keeps for them), the hidden keys, and the per-view option
 * values. [weekNumbers] mirrors the shared week-numbers preference, not a per-view key.
 */
data class ViewsSettingsState(
    val order: List<ViewMode>,
    val hidden: Set<String>,
    val hideWeekends: Boolean = false,
    val weekNumbers: Boolean = false,
    val agendaCompletedTasks: Boolean = false,
    val monthTaskBadge: Boolean = true,
) {
    /** Whether [mode] shows in the pickers. */
    fun isVisible(mode: ViewMode): Boolean = mode.key !in hidden

    /** How many views currently show; the last one can't be hidden. */
    fun visibleCount(): Int = order.count { it.key !in hidden }

    /** The option value shown in a view's sheet for [key]. */
    fun optionValue(key: ViewOptionKey): Boolean = when (key) {
        ViewOptionKey.HIDE_WEEKENDS -> hideWeekends
        ViewOptionKey.WEEK_NUMBERS -> weekNumbers
        ViewOptionKey.AGENDA_COMPLETED_TASKS -> agendaCompletedTasks
        ViewOptionKey.MONTH_TASK_BADGE -> monthTaskBadge
    }

    companion object {
        /**
         * Value before the DataStore emits: the ViewMode declaration order minus the
         * drawer-only views, nothing hidden, options at their defaults.
         */
        fun initial(): ViewsSettingsState = ViewsSettingsState(
            order = ViewMode.entries.filterNot {
                it == ViewMode.INSIGHTS || it == ViewMode.CONTACTS
            },
            hidden = emptySet(),
        )
    }
}

/**
 * Commits the Views settings. This screen gets its own small ViewModel rather than reusing
 * [org.onekash.kashcal.ui.viewmodels.HomeViewModel]: Settings lives in its own activity, so a
 * hiltViewModel there would build a second HomeViewModel and rerun its initialization (observers
 * and a startup sync) for no reason. The commit path mirrors
 * [org.onekash.kashcal.ui.viewmodels.HomeViewModel.setViewsOrder], including the default-view
 * repair, so whichever VM writes first produces the same persisted state.
 */
@HiltViewModel
class ViewsScreenViewModel @Inject constructor(
    private val dataStore: KashCalDataStore
) : ViewModel() {

    val state: StateFlow<ViewsSettingsState> = combine(
        combine(dataStore.visibleViewsOrder, dataStore.hiddenViews) { order, hidden -> order to hidden },
        combine(
            dataStore.viewOptHideWeekends,
            dataStore.showWeekNumbers
        ) { hideWeekends, weekNumbers -> hideWeekends to weekNumbers },
        combine(
            dataStore.viewOptAgendaCompletedTasks,
            dataStore.viewOptMonthTaskBadge
        ) { agendaCompleted, monthBadge -> agendaCompleted to monthBadge },
    ) { (order, hidden), (hideWeekends, weekNumbers), (agendaCompleted, monthBadge) ->
        ViewsSettingsState(
            order = order.mapNotNull { key -> ViewMode.entries.firstOrNull { it.key == key } },
            hidden = hidden,
            hideWeekends = hideWeekends,
            weekNumbers = weekNumbers,
            agendaCompletedTasks = agendaCompleted,
            monthTaskBadge = monthBadge,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ViewsSettingsState.initial()
    )

    /**
     * Commits [orderedVisible], the visible views in display order. When the persisted default
     * view is among the now-hidden it is re-pointed at the first visible view, so the next cold
     * start can't open a view the pickers no longer list. The running session keeps its own
     * current view; the repaired default applies from the next launch.
     */
    fun setViewsOrder(orderedVisible: List<ViewMode>) {
        viewModelScope.launch {
            dataStore.setVisibleViews(orderedVisible.map { it.key })
            val firstVisible = orderedVisible.firstOrNull()?.key ?: return@launch
            val default = dataStore.getDefaultCalendarView()
            if (orderedVisible.none { it.key == default }) {
                dataStore.setDefaultCalendarView(firstVisible)
            }
        }
    }

    /** Writes one per-view option; the consuming views follow their own preference reads.
     *  WEEK_NUMBERS is the exception: it writes the shared week-numbers preference
     *  ([KashCalDataStore.setShowWeekNumbers]), which the Month, Month (Full) and Agenda views —
     *  and the month widget — already read, so the option surfaces that one behavior per view. */
    fun setViewOption(key: ViewOptionKey, value: Boolean) {
        viewModelScope.launch {
            when (key) {
                ViewOptionKey.HIDE_WEEKENDS -> dataStore.setViewOptHideWeekends(value)
                ViewOptionKey.WEEK_NUMBERS -> dataStore.setShowWeekNumbers(value)
                ViewOptionKey.AGENDA_COMPLETED_TASKS -> dataStore.setViewOptAgendaCompletedTasks(value)
                ViewOptionKey.MONTH_TASK_BADGE -> dataStore.setViewOptMonthTaskBadge(value)
            }
        }
    }

    /**
     * Shows or hides [mode], then commits the visible views in their current order — the same
     * write a drag drop makes, so a checkbox toggle and a reorder can't disagree. Hiding the
     * last visible view is refused (the checkbox disables itself; mirrored here so a stale
     * enabled state can't commit an empty picker).
     */
    fun setViewVisible(mode: ViewMode, visible: Boolean) {
        val current = state.value
        if (!visible && current.visibleCount() <= 1) return
        val visibleModes = current.order.filter {
            (it != mode && it.key !in current.hidden) || (it == mode && visible)
        }
        setViewsOrder(visibleModes)
    }
}

/**
 * The Views settings screen: every calendar view as one row — drag handle, label, a visibility
 * checkbox and a chevron into the view's options sheet. Rows drag by long-press; a drop commits
 * the new order, as does a checkbox toggle. Hiding a view removes it from the committed list;
 * re-showing it returns at the end of the list, where [org.onekash.kashcal.data.preferences.KashCalDataStore.setVisibleViews]
 * stores the unmentioned (hidden) keys.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewsScreen(
    state: ViewsSettingsState,
    onNavigateBack: () -> Unit,
    onReorder: (List<ViewMode>) -> Unit,
    onToggleVisible: (ViewMode, Boolean) -> Unit,
    onSetOption: (ViewOptionKey, Boolean) -> Unit
) {
    // The view whose options sheet is open; null closes it.
    var optionsFor by remember { mutableStateOf<ViewMode?>(null) }

    Scaffold(
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.settings_views_title),
                onNavigateBack = onNavigateBack,
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                text = stringResource(R.string.settings_views_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            ReorderableViewsList(
                state = state,
                onReorder = onReorder,
                onToggleVisible = onToggleVisible,
                onOpenOptions = { mode -> optionsFor = mode }
            )
        }
    }

    optionsFor?.let { mode ->
        ViewOptionsSheet(
            mode = mode,
            state = state,
            onSetOption = onSetOption,
            onDismiss = { optionsFor = null }
        )
    }
}

/** Fixed row height of the reorder list; the drag math moves rows in whole rows. */
private val VIEW_ROW_HEIGHT = 48.dp

/**
 * The reorderable row list. While a long-press drag moves a row, [state]'s order is copied into
 * local state and re-ordered one slot at a time as the dragged row's centre passes a
 * neighbour's; the drag offset is compensated by the slots crossed, so the row stays under the
 * finger. Dropping commits the visible rows of the local order through [onReorder].
 */
@Composable
private fun ReorderableViewsList(
    state: ViewsSettingsState,
    onReorder: (List<ViewMode>) -> Unit,
    onToggleVisible: (ViewMode, Boolean) -> Unit,
    onOpenOptions: (ViewMode) -> Unit
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { VIEW_ROW_HEIGHT.toPx() }

    // Order while a drag shuffles rows; reset whenever the settings emit a new order.
    var dragOrder by remember(state.order) { mutableStateOf(state.order) }
    var draggingMode by remember { mutableStateOf<ViewMode?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // Each composed row's centre Y, in the list's viewport coordinates. Uniform row heights
    // make the drag math slot arithmetic; the map only needs the drag start row's centre and
    // the neighbours' comparisons.
    val rowCenters = remember { mutableStateMapOf<ViewMode, Float>() }

    // Read through State so the gesture detectors, which outlive recompositions, never act on
    // a captured list (the same discipline EventBlock's drag uses).
    val currentOrder by rememberUpdatedState(state.order)
    val currentHidden by rememberUpdatedState(state.hidden)

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = dragOrder, key = { it.key }) { mode ->
            val isDragged = mode == draggingMode
            val visible = state.isVisible(mode)
            val reorderHandle = stringResource(R.string.cd_view_reorder)
            val visibilityLabel = stringResource(R.string.cd_view_visibility)

            Row(
                modifier = Modifier
                    .height(VIEW_ROW_HEIGHT)
                    .fillMaxWidth()
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragged) dragOffset else 0f }
                    .onGloballyPositioned { coords ->
                        rowCenters[mode] = coords.positionInParent().y + coords.size.height / 2f
                    }
                    .pointerInput(mode) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragOrder = currentOrder
                                draggingMode = mode
                                dragOffset = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                if (draggingMode != mode) return@detectDragGesturesAfterLongPress
                                dragOffset += dragAmount.y
                                val from = dragOrder.indexOf(mode)
                                val center = rowCenters[mode] ?: return@detectDragGesturesAfterLongPress
                                val projected = center + dragOffset
                                val target = dragOrder.indices
                                    .filter { it != from }
                                    .minByOrNull {
                                        abs((rowCenters[dragOrder[it]] ?: Float.MAX_VALUE) - projected)
                                    }
                                if (target != null) {
                                    // Move one slot toward the row whose centre was crossed,
                                    // then shift the offset by the same slot so the dragged row
                                    // keeps its position under the finger.
                                    val step = if (target > from) from + 1 else from - 1
                                    dragOrder = dragOrder.toMutableList().apply {
                                        add(step, removeAt(from))
                                    }
                                    dragOffset -= (step - from) * rowHeightPx
                                }
                            },
                            onDragEnd = {
                                onReorder(dragOrder.filter { it.key !in currentHidden })
                                draggingMode = null
                                dragOffset = 0f
                            },
                            onDragCancel = {
                                draggingMode = null
                                dragOffset = 0f
                            }
                        )
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = reorderHandle,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = viewModeLabel(mode),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Checkbox(
                    checked = visible,
                    // The last visible view can't be hidden: an empty picker is an unrecoverable
                    // state, since nothing would be left to re-show it from.
                    enabled = visible || state.visibleCount() > 1,
                    onCheckedChange = { checked -> onToggleVisible(mode, checked) },
                    modifier = Modifier.semantics { contentDescription = visibilityLabel }
                )
                // A view with no options of its own (TASKS this pass) renders no chevron: a
                // sheet that would open empty is worse than no affordance.
                if (mode in VIEW_OPTION_APPLICABILITY.values.flatten()) {
                    IconButton(onClick = { onOpenOptions(mode) }) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * One view's options sheet: a toggle per [ViewOptionKey] that applies to the view, in the
 * enum's order. Values and writes go through the shared per-view preferences, so the sheet and
 * the consuming views can't disagree — except [ViewOptionKey.WEEK_NUMBERS], which reads and
 * writes the app-wide week-numbers preference the Month, Month (Full) and Agenda grids consume.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewOptionsSheet(
    mode: ViewMode,
    state: ViewsSettingsState,
    onSetOption: (ViewOptionKey, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = viewModeLabel(mode),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            ViewOptionKey.entries
                .filter { mode in VIEW_OPTION_APPLICABILITY.getValue(it) }
                .forEach { key ->
                    SettingsToggleRow(
                        label = stringResource(key.labelRes),
                        checked = state.optionValue(key),
                        onCheckedChange = { checked -> onSetOption(key, checked) },
                        showDivider = false
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
        }
    }
}
