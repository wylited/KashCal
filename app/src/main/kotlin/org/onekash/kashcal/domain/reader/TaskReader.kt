package org.onekash.kashcal.domain.reader

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.db.entity.Task
import org.onekash.kashcal.domain.model.VisibilityDomain
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads tasks for the UI, filtering on the [VisibilityDomain] the surface asked for: the
 * schedule views and widgets read [org.onekash.kashcal.data.db.entity.Calendar.isVisible],
 * while Agenda and Tasks read
 * [org.onekash.kashcal.data.db.entity.Calendar.isAgendaVisible] — the per-calendar split lets
 * a calendar feed one surface and not the other. Task counterpart of [EventReader].
 */
@Singleton
class TaskReader @Inject constructor(
    private val database: KashCalDatabase
) {
    private val tasksDao by lazy { database.tasksDao() }

    /**
     * Observes every task of visible calendars in [domain], ordered by `due ?? dtstart` with
     * undated tasks last.
     */
    fun observeTasks(domain: VisibilityDomain): Flow<List<Task>> = when (domain) {
        VisibilityDomain.SCHEDULE -> tasksDao.observeAllScheduleVisible()
        VisibilityDomain.AGENDA -> tasksDao.observeAllAgendaVisible()
    }

    /**
     * Returns the open tasks (`due ?? dtstart` within [startTs, endTs]) of visible calendars
     * in [domain], without PENDING_DELETE rows.
     */
    suspend fun getOpenTasksBetween(
        startTs: Long,
        endTs: Long,
        domain: VisibilityDomain
    ): List<Task> = when (domain) {
        VisibilityDomain.SCHEDULE -> tasksDao.getOpenTasksBetween(startTs, endTs)
        VisibilityDomain.AGENDA -> tasksDao.getOpenTasksBetweenAgenda(startTs, endTs)
    }

    /**
     * Returns every task (`due ?? dtstart` within [startTs, endTs]), any status, of visible
     * calendars in [domain], without PENDING_DELETE rows.
     */
    suspend fun getTasksBetween(
        startTs: Long,
        endTs: Long,
        domain: VisibilityDomain
    ): List<Task> = when (domain) {
        VisibilityDomain.SCHEDULE -> tasksDao.getTasksBetween(startTs, endTs)
        VisibilityDomain.AGENDA -> tasksDao.getTasksBetweenAgenda(startTs, endTs)
    }

    /**
     * Searches task summary and description with FTS4; each whitespace-separated token of
     * [query] becomes a `token*` prefix term, as [EventReader.searchEvents] does. Results are
     * limited to visible calendars and ordered by `due ?? dtstart`.
     */
    suspend fun searchTasks(query: String): List<Task> {
        if (query.isBlank()) return emptyList()
        val ftsQuery = query.trim().split("\\s+".toRegex())
            .joinToString(" ") { "$it*" }
        return tasksDao.searchTasks(ftsQuery)
    }

    /** Returns the task with [id], or null. */
    suspend fun getTaskById(id: Long): Task? = tasksDao.getById(id)

    /**
     * Observes the task with [id] whatever its calendar's visibility, mapping a PENDING_DELETE
     * row to null as the list queries drop soft-deleted rows, so the quick-view sheet closes on
     * delete at once and a schedule-domain tap (week-grid chip, search result) keeps rendering
     * when the task's calendar is agenda-hidden.
     */
    fun observeTaskById(id: Long): Flow<Task?> =
        tasksDao.observeById(id).map { it?.takeIf { task -> task.syncStatus != SyncStatus.PENDING_DELETE } }
}
