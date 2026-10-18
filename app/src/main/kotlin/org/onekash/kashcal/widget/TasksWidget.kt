package org.onekash.kashcal.widget

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.state.PreferencesGlanceStateDefinition
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.onekash.kashcal.data.preferences.KashCalDataStore

/**
 * The Tasks widget: the Tasks title over the next [TASKS_WIDGET_MAX_TASKS] open tasks — due or
 * starting first, undated last — each with its checkbox and due, or the "no open tasks" state.
 * Tapping a row or the header opens the app; there is no task-specific deep link in the widget
 * action plumbing yet.
 *
 * Refreshes on each [WidgetUpdateManager.updateAllWidgets] call (a task or event write, a sync,
 * midnight or a settings change) and every 30 minutes. [WIDGET_REFRESH_STAMP] lives in
 * [PreferencesGlanceStateDefinition]; the fetch runs inside [provideContent] via
 * [fetchTasksData] so a Glance 1.1 session re-runs it on update (see [MonthWidget]).
 */
class TasksWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override val previewSizeMode = WidgetPreviewSizes.TASKS

    override val stateDefinition = PreferencesGlanceStateDefinition

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TasksWidgetEntryPoint {
        fun widgetDataRepository(): WidgetDataRepository
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(context, TasksWidgetEntryPoint::class.java)
        val repository = entryPoint.widgetDataRepository()
        val dataStore = KashCalDataStore(context)
        // Resolve the accent before provideContent so the first RemoteViews carry the picked seed.
        // Seeding produceState with null renders a frame on the platform dynamic palette, and a
        // host that snapshots it then leaves a seed user on wallpaper colors. Null colors mean the
        // DYNAMIC source on the system face.
        val initialAccent = resolveWidgetAccentColors(context, dataStore).colors

        provideContent {
            val prefs = currentState<Preferences>()
            val stamp = prefs[WIDGET_REFRESH_STAMP] ?: 0L
            val isRefreshing = isRefreshCueActive(prefs[WIDGET_REFRESHING_UNTIL], System.currentTimeMillis())
            // "No open tasks" may flash on cold start until fetchTasksData resolves; there is
            // no loading UI.
            val data by produceState(
                initialValue = TasksData(
                    tasks = emptyList(),
                    timePattern = "h:mm a",
                    detailedRows = false
                ),
                key1 = stamp
            ) {
                value = fetchTasksData(repository, dataStore, context)
            }
            val accentColors by produceState(initialValue = initialAccent, key1 = stamp) {
                value = resolveWidgetAccentColors(context, dataStore).colors
            }
            GlanceTheme(colors = accentColors ?: GlanceTheme.colors) {
                TasksWidgetContent(
                    tasks = data.tasks,
                    timePattern = data.timePattern,
                    isRefreshing = isRefreshing,
                    detailedRows = data.detailedRows
                )
            }
        }
    }

    /**
     * Renders sample open tasks into the widget picker so this widget stands apart from the
     * others. Reads no stored data: previews are published once per app version, so anything
     * user-specific would be frozen at publish time.
     */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { TasksPreviewContent(context) }
    }
}
