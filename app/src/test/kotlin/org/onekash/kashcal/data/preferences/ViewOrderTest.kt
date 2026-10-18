package org.onekash.kashcal.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests the customizable-views preference round trip: [KashCalDataStore.parseViewOrder]'s
 * self-healing (garbage keys dropped, missing keys appended in canonical order), the hidden
 * set's exclusion from the picker-visible list, and a [KashCalDataStore.setVisibleViews] write
 * reading back the same order through [KashCalDataStore.visibleViewsOrder].
 *
 * The DataStore round trip runs against a real file-backed DataStore (not a mock), because the
 * behavior under test is exactly the parse/edit/read cycle. The DataStore's actor scope shares
 * runTest's scheduler, so the writes complete in virtual time.
 */
class ViewOrderTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    /** Scopes created by [createDataStore]; cancelled after every test. */
    private val dataStoreScopes = mutableListOf<CoroutineScope>()

    /** Cancelled after every test so no DataStore actor outlives its test's scheduler. */
    @After
    fun tearDown() {
        dataStoreScopes.forEach { it.cancel() }
    }

    /**
     * Builds a [KashCalDataStore] over a fresh file-backed DataStore. The DataStore's actor
     * scope shares runTest's scheduler, so writes complete in virtual time.
     */
    private fun TestScope.createDataStore(): KashCalDataStore {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        dataStoreScopes.add(scope)
        val preferences = PreferenceDataStoreFactory.create(scope = scope) {
            File(tmpFolder.root, "view_order_test.preferences_pb")
        }
        // The context is never touched: the override DataStore short-circuits the delegate.
        return KashCalDataStore(mockk(relaxed = true), overrideDataStore = preferences)
    }

    @Test
    fun `parse drops keys that are not valid views`() {
        val parsed = KashCalDataStore.parseViewOrder("month,bogus_view,week,42")
        // The bogus keys go; the stored valid keys keep their order and the self-heal
        // appends every key the stored list didn't mention, in canonical order.
        assertEquals(
            listOf(
                KashCalDataStore.VIEW_MONTH,
                KashCalDataStore.VIEW_WEEK,
                KashCalDataStore.VIEW_AGENDA,
                KashCalDataStore.VIEW_TASKS,
                KashCalDataStore.VIEW_DAY,
                KashCalDataStore.VIEW_THREE_DAYS,
                KashCalDataStore.VIEW_PLANNER,
                KashCalDataStore.VIEW_MONTH_FULL,
                KashCalDataStore.VIEW_YEAR,
            ),
            parsed,
        )
    }

    @Test
    fun `parse appends missing keys in canonical order after the stored ones`() {
        val parsed = KashCalDataStore.parseViewOrder("year,agenda")
        assertEquals(
            listOf(
                KashCalDataStore.VIEW_YEAR,
                KashCalDataStore.VIEW_AGENDA,
                // Every valid key the stored list didn't mention, in canonical order.
                KashCalDataStore.VIEW_MONTH,
                KashCalDataStore.VIEW_TASKS,
                KashCalDataStore.VIEW_DAY,
                KashCalDataStore.VIEW_THREE_DAYS,
                KashCalDataStore.VIEW_WEEK,
                KashCalDataStore.VIEW_PLANNER,
                KashCalDataStore.VIEW_MONTH_FULL,
            ),
            parsed,
        )
    }

    @Test
    fun `parse of a blank stored value yields the canonical order`() {
        val canonical = listOf(
            KashCalDataStore.VIEW_MONTH,
            KashCalDataStore.VIEW_AGENDA,
            KashCalDataStore.VIEW_TASKS,
            KashCalDataStore.VIEW_DAY,
            KashCalDataStore.VIEW_THREE_DAYS,
            KashCalDataStore.VIEW_WEEK,
            KashCalDataStore.VIEW_PLANNER,
            KashCalDataStore.VIEW_MONTH_FULL,
            KashCalDataStore.VIEW_YEAR,
        )
        assertEquals(canonical, KashCalDataStore.parseViewOrder(null))
        assertEquals(canonical, KashCalDataStore.parseViewOrder(""))
    }

    @Test
    fun `parse keeps the first occurrence when a key appears twice`() {
        val parsed = KashCalDataStore.parseViewOrder("week,week,month")
        // First occurrence wins; the self-heal appends the keys the stored list didn't name.
        assertEquals(
            listOf(
                KashCalDataStore.VIEW_WEEK,
                KashCalDataStore.VIEW_MONTH,
                KashCalDataStore.VIEW_AGENDA,
                KashCalDataStore.VIEW_TASKS,
                KashCalDataStore.VIEW_DAY,
                KashCalDataStore.VIEW_THREE_DAYS,
                KashCalDataStore.VIEW_PLANNER,
                KashCalDataStore.VIEW_MONTH_FULL,
                KashCalDataStore.VIEW_YEAR,
            ),
            parsed,
        )
    }

    @Test
    fun `hidden keys are excluded from the visible list but stay in the order`() = runTest {
        val dataStore = createDataStore()
        dataStore.setVisibleViews(listOf(KashCalDataStore.VIEW_DAY, KashCalDataStore.VIEW_WEEK))

        val order = dataStore.visibleViewsOrder.first()
        val hidden = dataStore.hiddenViews.first()

        // Day and Week lead in the user's order; the unmentioned keys follow in canonical order.
        assertEquals(
            listOf(
                KashCalDataStore.VIEW_DAY,
                KashCalDataStore.VIEW_WEEK,
                KashCalDataStore.VIEW_MONTH,
                KashCalDataStore.VIEW_AGENDA,
                KashCalDataStore.VIEW_TASKS,
                KashCalDataStore.VIEW_THREE_DAYS,
                KashCalDataStore.VIEW_PLANNER,
                KashCalDataStore.VIEW_MONTH_FULL,
                KashCalDataStore.VIEW_YEAR,
            ),
            order,
        )
        // Hidden is exactly the valid keys the committed list didn't mention.
        assertEquals(
            setOf(
                KashCalDataStore.VIEW_MONTH,
                KashCalDataStore.VIEW_AGENDA,
                KashCalDataStore.VIEW_TASKS,
                KashCalDataStore.VIEW_THREE_DAYS,
                KashCalDataStore.VIEW_PLANNER,
                KashCalDataStore.VIEW_MONTH_FULL,
                KashCalDataStore.VIEW_YEAR,
            ),
            hidden,
        )
        // The picker list is the order minus hidden.
        assertEquals(
            listOf(KashCalDataStore.VIEW_DAY, KashCalDataStore.VIEW_WEEK),
            order.filterNot { it in hidden },
        )
    }

    @Test
    fun `setVisibleViews round-trips through visibleViewsOrder`() = runTest {
        val dataStore = createDataStore()
        val committed = listOf(
            KashCalDataStore.VIEW_WEEK,
            KashCalDataStore.VIEW_MONTH,
            KashCalDataStore.VIEW_AGENDA,
        )
        dataStore.setVisibleViews(committed)

        val order = dataStore.visibleViewsOrder.first()
        val hidden = dataStore.hiddenViews.first()

        assertEquals(committed, order.filterNot { it in hidden })
        assertEquals(committed, order.take(committed.size))
    }

    @Test
    fun `setVisibleViews drops unknown keys and a re-shown view clears its hidden flag`() = runTest {
        val dataStore = createDataStore()

        // A bogus key must not enter the stored state.
        dataStore.setVisibleViews(listOf("nope", KashCalDataStore.VIEW_MONTH, KashCalDataStore.VIEW_AGENDA))
        val hidden = dataStore.hiddenViews.first()
        assertEquals(
            listOf(KashCalDataStore.VIEW_MONTH, KashCalDataStore.VIEW_AGENDA),
            dataStore.visibleViewsOrder.first().filterNot { it in hidden },
        )

        // Re-showing every view empties the hidden set entirely.
        dataStore.setVisibleViews(
            listOf(
                KashCalDataStore.VIEW_MONTH,
                KashCalDataStore.VIEW_AGENDA,
                KashCalDataStore.VIEW_TASKS,
                KashCalDataStore.VIEW_DAY,
                KashCalDataStore.VIEW_THREE_DAYS,
                KashCalDataStore.VIEW_WEEK,
                KashCalDataStore.VIEW_PLANNER,
                KashCalDataStore.VIEW_MONTH_FULL,
                KashCalDataStore.VIEW_YEAR,
            )
        )
        assertEquals(emptySet<String>(), dataStore.hiddenViews.first())
    }
}
