package org.onekash.kashcal.ui.screens.planner

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.planner.PlannerWeekState
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Calendar

/**
 * Checks pull-to-refresh over the real [PlannerView]: its boxes fit the screen with nothing
 * scrolling, so a pull reaches pull-to-refresh only through the view's zero-consuming vertical
 * scrollable, and a sideways swipe must still page the week without refreshing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class PlannerPullToRefreshTest {

    @get:Rule
    val rule = createComposeRule()

    private var refreshes = 0
    private val pages = mutableListOf<Int>()

    private fun render() {
        rule.setContent {
            MaterialTheme {
                val state = rememberPullToRefreshState()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("ptr")
                        .pullToRefresh(isRefreshing = false, state = state, enabled = true, onRefresh = { refreshes++ }),
                ) {
                    PlannerView(
                        plannerState = PlannerWeekState(persistentMapOf(), 20261005, 20261025),
                        anchorDayCode = 20261012,
                        pagerPosition = WeekViewUtils.CENTER_WEEK_PAGE,
                        pendingNavigateToPage = null,
                        firstDayOfWeek = Calendar.MONDAY,
                        todayDayCode = 20261015,
                        nowMs = 0L,
                        timePattern = "h:mm a",
                        showEventEmojis = false,
                        zoom = 1f,
                        widthDp = 360,
                        isCompactHeight = false,
                        onPageChanged = { pages += it },
                        onNavigationConsumed = {},
                        onEmptyDayTap = {},
                        onDayClick = {},
                        onDayHeaderClick = {},
                        onZoomChange = {},
                        tasksByDayCode = emptyMap(),
                        calendarColorById = emptyMap(),
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `pulling down on the Planner starts a refresh`() {
        render()
        rule.onNodeWithTag("ptr").performTouchInput {
            down(Offset(centerX, top + 5f))
            moveBy(Offset(0f, 200f))
            moveBy(Offset(0f, 200f))
            moveBy(Offset(0f, 200f))
            up()
        }
        rule.waitForIdle()
        assertEquals(1, refreshes)
    }

    @Test
    fun `a sideways swipe pages the week and doesn't refresh`() {
        render()
        rule.onNodeWithTag("ptr").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertTrue("paged to the next week, got $pages", pages.contains(WeekViewUtils.CENTER_WEEK_PAGE + 1))
        assertFalse("no refresh", refreshes > 0)
    }
}
