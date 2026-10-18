package org.onekash.kashcal.domain.model

/**
 * Which surface a read is for, deciding which calendar-visibility column filters it.
 *
 * The schedule (every view but Agenda, plus widgets) reads [Calendar.isVisible]; the Agenda
 * and Tasks surfaces read [Calendar.isAgendaVisible], so a calendar can keep its events on
 * the schedule while its items stay off the agenda, or the other way round.
 */
enum class VisibilityDomain {
    /** Month, week, day, planner, year and the widgets: filters on `calendars.is_visible`. */
    SCHEDULE,

    /** Agenda and Tasks: filters on `calendars.is_agenda_visible`. */
    AGENDA
}
