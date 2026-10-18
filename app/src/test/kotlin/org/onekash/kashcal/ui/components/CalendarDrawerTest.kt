package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.viewmodels.ViewMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the order of [CalendarDrawer]'s calendar sections: Room accounts under "Calendars", then
 * "Contacts", then "Device calendars", then the Offline account (KashCal's own calendars) under a
 * heading of its own, before Settings; and the notifications-off mark, which every device calendar
 * carries while device reminders are off. The screen is tall so every LazyColumn item is composed.
 * The drawer also always leads with an "Insights"/"Contacts" view-tab row; section assertions
 * therefore target the sections' heading semantics, not the tab's text.
 *
 * Runs under Robolectric; run in isolation given the repo's multi-class native-crash flake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class CalendarDrawerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val local = Account(id = 1, provider = AccountProvider.LOCAL, email = "local")
    private val icloud = Account(id = 2, provider = AccountProvider.ICLOUD, email = "alex@example.test", displayName = "alex@example.test")
    private val birthdays = Account(id = 4, provider = AccountProvider.CONTACTS, email = "birthdays", displayName = "Birthdays")

    private fun calendar(id: Long, accountId: Long, name: String, muted: Boolean = false) =
        Calendar(id = id, accountId = accountId, caldavUrl = "cal://$id", displayName = name, color = id.toInt(), isNotificationMuted = muted)

    private val phone = DeviceCalendar(
        id = 100, displayName = "Phone", color = 100, accountName = "alex@gmail.example", accountType = "org.example",
        visible = true, accessLevel = 700
    )

    private val toggled = mutableListOf<Long>()

    private fun show(
        calendars: List<Calendar>,
        accounts: List<Account>,
        deviceCalendars: List<DeviceCalendar> = listOf(phone),
        mutedDeviceIds: Set<Long> = emptySet(),
        deviceRemindersEnabled: Boolean = true,
    ) {
        val groups = CalendarGroup.fromCalendarsAndAccounts(calendars, accounts, localLabel = "Offline", icsLabel = "Calendar Feeds")
        composeTestRule.setContent {
            MaterialTheme {
                CalendarDrawer(
                    currentViewMode = ViewMode.WEEK,
                    calendarGroups = groups.toImmutableList(),
                    deviceCalendarsEnabled = deviceCalendars.isNotEmpty(),
                    enabledDeviceCalendars = deviceCalendars.toImmutableList(),
                    hiddenDeviceCalendarIds = persistentSetOf(),
                    mutedDeviceCalendarIds = mutedDeviceIds.toPersistentSet(),
                    deviceRemindersEnabled = deviceRemindersEnabled,
                    onViewSelect = {},
                    onToggleCalendar = { toggled += it },
                    onToggleDeviceCalendarVisibility = {},
                    onSettingsClick = {},
                )
            }
        }
    }

    private fun everything() = show(
        calendars = listOf(
            calendar(10, local.id, "My calendar", muted = true),
            calendar(20, icloud.id, "Work"),
            calendar(40, birthdays.id, "Birthdays"),
        ),
        accounts = listOf(local, icloud, birthdays),
    )

    private fun top(text: String) = composeTestRule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    /** Y of a section heading. Disambiguates from same-named view tabs: the drawer always
     * shows an "Insights"/"Contacts" tab row, while only sections carry [heading] semantics. */
    private fun headingNodes(text: String) = composeTestRule.onAllNodes(
        hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    )

    private fun topHeading(text: String) =
        headingNodes(text).fetchSemanticsNodes().single().positionInRoot.y

    @Test
    fun `the Offline calendars come after the device calendars and before Settings`() {
        everything()

        assertTrue(top("Device calendars") < top("Offline"))
        assertTrue(top("Phone") < top("My calendar"))
        assertTrue(top("My calendar") < top("Settings"))
    }

    @Test
    fun `other accounts keep their sub-header under Calendars and Offline isn't one of them`() {
        everything()

        assertTrue(top("Calendars") < top("alex@example.test"))
        assertTrue(top("alex@example.test") < top("Work"))
        assertTrue(top("Work") < topHeading("Contacts"))
        assertEquals(1, composeTestRule.onAllNodes(hasText("Offline")).fetchSemanticsNodes().size)
    }

    @Test
    fun `with only Offline and contacts calendars there is no Calendars header but the divider stays`() {
        show(
            calendars = listOf(calendar(10, local.id, "My calendar"), calendar(40, birthdays.id, "Birthdays")),
            accounts = listOf(local, birthdays),
            deviceCalendars = emptyList(),
        )

        composeTestRule.onNodeWithText("Calendars").assertDoesNotExist()
        composeTestRule.onNodeWithTag(DRAWER_CALENDARS_DIVIDER_TAG).assertExists()
        assertTrue(composeTestRule.onNodeWithTag(DRAWER_CALENDARS_DIVIDER_TAG).fetchSemanticsNode().positionInRoot.y < topHeading("Contacts"))
        assertTrue(topHeading("Contacts") < top("Offline"))
    }

    @Test
    fun `with device calendars off Offline follows the contacts calendars`() {
        show(
            calendars = listOf(calendar(10, local.id, "My calendar"), calendar(20, icloud.id, "Work"), calendar(40, birthdays.id, "Birthdays")),
            accounts = listOf(local, icloud, birthdays),
            deviceCalendars = emptyList(),
        )

        composeTestRule.onNodeWithText("Device calendars").assertDoesNotExist()
        assertTrue(top("Birthdays") < top("Offline"))
        assertTrue(top("My calendar") < top("Settings"))
    }

    @Test
    fun `the Offline header is a heading`() {
        everything()

        composeTestRule.onNodeWithText("Offline").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun `an Offline row still toggles its calendar and shows the bell-off mark when muted`() {
        everything()

        composeTestRule.onNodeWithText("My calendar").performClick()

        assertEquals(listOf(10L), toggled)
        composeTestRule.onNode(hasTestTag(CALENDAR_ROW_MUTED_ICON_TAG), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `with only Offline calendars the drawer shows just the Offline section`() {
        show(calendars = listOf(calendar(10, local.id, "My calendar")), accounts = listOf(local), deviceCalendars = emptyList())

        composeTestRule.onNodeWithText("Calendars").assertDoesNotExist()
        // The drawer-level "Contacts" view tab (like Insights) is always present; only the
        // birthdays/anniversaries *section* is absent here.
        headingNodes("Contacts").assertCountEquals(0)
        composeTestRule.onNodeWithTag(DRAWER_CALENDARS_DIVIDER_TAG).assertDoesNotExist()
        assertTrue(top("Offline") < top("My calendar"))
        assertTrue(top("My calendar") < top("Settings"))
    }

    private val tablet = phone.copy(id = 101, displayName = "Tablet")

    private fun showMarks(deviceRemindersEnabled: Boolean) = show(
        calendars = listOf(
            calendar(10, local.id, "My calendar", muted = true),
            calendar(20, icloud.id, "Work"),
        ),
        accounts = listOf(local, icloud),
        deviceCalendars = listOf(phone, tablet),
        mutedDeviceIds = setOf(tablet.id),
        deviceRemindersEnabled = deviceRemindersEnabled,
    )

    private fun marked(name: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "notifications off")
        .let { composeTestRule.onAllNodes(hasText(name) and it).fetchSemanticsNodes().size == 1 }

    @Test
    fun `with device reminders off every device calendar shows the notifications-off mark`() {
        showMarks(deviceRemindersEnabled = false)

        assertTrue(marked("Phone"))
        assertTrue(marked("Tablet"))
        assertTrue(marked("My calendar"))
        assertFalse(marked("Work"))
    }

    @Test
    fun `with device reminders on only muted calendars show the mark`() {
        showMarks(deviceRemindersEnabled = true)

        assertFalse(marked("Phone"))
        assertTrue(marked("Tablet"))
        assertTrue(marked("My calendar"))
        assertFalse(marked("Work"))
    }
}
