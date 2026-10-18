package org.onekash.kashcal.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.viewmodels.ViewMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the view picker's entries so an edit to [defaultViewOptions] can't silently scramble what
 * the user sees: the ViewMode declaration order (Month, Agenda, Tasks, Day, 3 Days, Week,
 * Planner, Month Full, Year) — the same order the DataStore's self-healing fallback and the
 * Views settings' initial list use — each selectable view once (not INSIGHTS/CONTACTS) with a
 * distinct icon that [iconForMode] finds by mode.
 *
 * The order is asserted twice on purpose: on the list itself, and on the rendered dropdown by
 * comparing each row's top bound. [defaultViewOptions] also feeds the navigation drawer's view
 * rows, so the list-level assertion covers the drawer too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class ViewPickerOrderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** The ViewMode declaration order minus the drawer-only INSIGHTS and CONTACTS. */
    private val expectedOrder = listOf(
        ViewMode.MONTH,
        ViewMode.AGENDA,
        ViewMode.TASKS,
        ViewMode.DAY,
        ViewMode.THREE_DAYS,
        ViewMode.WEEK,
        ViewMode.PLANNER,
        ViewMode.MONTH_FULL,
        ViewMode.YEAR,
    )

    @Test
    fun `view options follow the ViewMode declaration order`() {
        assertEquals(expectedOrder, defaultViewOptions.map { it.mode })
    }

    @Test
    fun `view options offer every selectable view exactly once`() {
        // INSIGHTS and CONTACTS are reached from the drawer's own rows; neither is a persisted
        // default view.
        val selectable = ViewMode.entries.filterNot {
            it == ViewMode.INSIGHTS || it == ViewMode.CONTACTS
        }
        assertEquals(selectable.toSet(), defaultViewOptions.map { it.mode }.toSet())
        assertEquals(defaultViewOptions.size, defaultViewOptions.map { it.mode }.toSet().size)
    }

    @Test
    fun `every view option carries a distinct icon`() {
        val icons = defaultViewOptions.map { it.icon }
        assertEquals(icons.size, icons.toSet().size)
    }

    @Test
    fun `icon lookup resolves by mode rather than list position`() {
        // Guards the reorder: a positional lookup would hand back a neighbour's icon.
        defaultViewOptions.forEach { option ->
            assertEquals(
                "Icon for ${option.mode} must match its own entry",
                option.icon,
                iconForMode(option.mode),
            )
        }
    }

    @Test
    fun `dropdown renders the view entries in declaration order`() {
        composeTestRule.setContent {
            ViewPickerButton(currentView = ViewMode.MONTH, onViewSelect = {})
        }

        composeTestRule.onNodeWithContentDescription("Calendar view").performClick()

        val labels = listOf("Month", "Agenda", "Tasks", "Day", "3 Days", "Week", "Planner", "Month (Full)", "Year")
        val tops = labels.map { label ->
            composeTestRule.onNodeWithText(label, useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot.top
        }

        tops.zipWithNext().forEachIndexed { index, (above, below) ->
            assertTrue(
                "${labels[index]} must render above ${labels[index + 1]}",
                above < below,
            )
        }
    }
}
