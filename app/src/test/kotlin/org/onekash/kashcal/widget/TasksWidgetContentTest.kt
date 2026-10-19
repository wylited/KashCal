package org.onekash.kashcal.widget

import androidx.glance.action.ActionParameters
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests [taskActionParameters], the tap payload every widget task row sends. The row must open
 * that task's Quick View (ACTION_SHOW_TASK + [EXTRA_TASK_ID]), not just the app at today.
 */
class TasksWidgetContentTest {

    @Test
    fun `taskActionParameters carries the task Quick View deep link`() {
        val params = taskActionParameters(42L)

        assertEquals(ACTION_SHOW_TASK, params[ActionParameters.Key<String>(EXTRA_ACTION)])
        assertEquals(42L, params[ActionParameters.Key<Long>(EXTRA_TASK_ID)])
    }
}
