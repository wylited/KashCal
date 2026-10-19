package org.onekash.kashcal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.db.entity.Task

/**
 * Reads and writes [Task] rows; [Task] and its sync callers say what each query is for.
 */
@Dao
interface TasksDao {

    // ========== Read operations ==========

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: Long): Task?

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observeById(id: Long): Flow<Task?>

    @Query("SELECT * FROM tasks WHERE calendar_id = :calendarId AND uid = :uid")
    suspend fun getByUidAndCalendar(uid: String, calendarId: Long): Task?

    @Query("SELECT * FROM tasks WHERE caldav_url = :caldavUrl")
    suspend fun getByCaldavUrl(caldavUrl: String): Task?

    @Query("SELECT * FROM tasks WHERE calendar_id = :calendarId AND sync_status != 'SYNCED'")
    suspend fun getPendingForCalendar(calendarId: Long): List<Task>

    /** Returns the tasks with [ids], in no particular order; unknown ids are skipped. */
    @Query("SELECT * FROM tasks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Task>

    /**
     * Returns every task of [calendarId], any status and sync state: the task pull loads it
     * once per sync for the etag diff and the stale-resource sweep.
     */
    @Query("SELECT * FROM tasks WHERE calendar_id = :calendarId")
    suspend fun getAllForCalendar(calendarId: Long): List<Task>

    /**
     * Observes every task of calendars visible on the schedule ([Calendar.isVisible]), without
     * PENDING_DELETE rows — a soft-deleted task disappears at once, not when its server delete
     * is pushed.
     */
    @Query("""
        SELECT tasks.* FROM tasks
        JOIN calendars ON calendars.id = tasks.calendar_id
        WHERE calendars.is_visible = 1
        AND tasks.sync_status != 'PENDING_DELETE'
        ORDER BY (tasks.due_ts IS NULL AND tasks.dt_start_ts IS NULL),
                 COALESCE(tasks.due_ts, tasks.dt_start_ts) ASC
    """)
    fun observeAllScheduleVisible(): Flow<List<Task>>

    /**
     * Observes every task of agenda-visible calendars ([Calendar.isAgendaVisible]), without
     * PENDING_DELETE rows — a soft-deleted task disappears at once, not when its server delete
     * is pushed.
     */
    @Query("""
        SELECT tasks.* FROM tasks
        JOIN calendars ON calendars.id = tasks.calendar_id
        WHERE calendars.is_agenda_visible = 1
        AND tasks.sync_status != 'PENDING_DELETE'
        ORDER BY (tasks.due_ts IS NULL AND tasks.dt_start_ts IS NULL),
                 COALESCE(tasks.due_ts, tasks.dt_start_ts) ASC
    """)
    fun observeAllAgendaVisible(): Flow<List<Task>>

    /**
     * Returns the open tasks (`due ?? dtstart` within [startTs, endTs]) of calendars visible on
     * the schedule, without PENDING_DELETE rows.
     */
    @Query("""
        SELECT tasks.* FROM tasks
        JOIN calendars ON calendars.id = tasks.calendar_id
        WHERE calendars.is_visible = 1
        AND tasks.sync_status != 'PENDING_DELETE'
        AND tasks.status NOT IN ('COMPLETED', 'CANCELLED')
        AND COALESCE(tasks.due_ts, tasks.dt_start_ts) BETWEEN :startTs AND :endTs
    """)
    suspend fun getOpenTasksBetween(startTs: Long, endTs: Long): List<Task>

    /** Agenda-visible twin of [getOpenTasksBetween] ([Calendar.isAgendaVisible]). */
    @Query("""
        SELECT tasks.* FROM tasks
        JOIN calendars ON calendars.id = tasks.calendar_id
        WHERE calendars.is_agenda_visible = 1
        AND tasks.sync_status != 'PENDING_DELETE'
        AND tasks.status NOT IN ('COMPLETED', 'CANCELLED')
        AND COALESCE(tasks.due_ts, tasks.dt_start_ts) BETWEEN :startTs AND :endTs
    """)
    suspend fun getOpenTasksBetweenAgenda(startTs: Long, endTs: Long): List<Task>

    /**
     * Returns every task (`due ?? dtstart` within [startTs, endTs]), any status, of calendars
     * visible on the schedule, without PENDING_DELETE rows.
     */
    @Query("""
        SELECT tasks.* FROM tasks
        JOIN calendars ON calendars.id = tasks.calendar_id
        WHERE calendars.is_visible = 1
        AND tasks.sync_status != 'PENDING_DELETE'
        AND COALESCE(tasks.due_ts, tasks.dt_start_ts) BETWEEN :startTs AND :endTs
    """)
    suspend fun getTasksBetween(startTs: Long, endTs: Long): List<Task>

    /** Agenda-visible twin of [getTasksBetween] ([Calendar.isAgendaVisible]). */
    @Query("""
        SELECT tasks.* FROM tasks
        JOIN calendars ON calendars.id = tasks.calendar_id
        WHERE calendars.is_agenda_visible = 1
        AND tasks.sync_status != 'PENDING_DELETE'
        AND COALESCE(tasks.due_ts, tasks.dt_start_ts) BETWEEN :startTs AND :endTs
    """)
    suspend fun getTasksBetweenAgenda(startTs: Long, endTs: Long): List<Task>

    /** Returns every task, any status, of agenda-visible calendars, without PENDING_DELETE. */
    @Query("""
        SELECT tasks.* FROM tasks
        JOIN calendars ON calendars.id = tasks.calendar_id
        WHERE calendars.is_agenda_visible = 1
        AND tasks.sync_status != 'PENDING_DELETE'
    """)
    suspend fun getAgendaTasks(): List<Task>

    /**
     * Searches summary and description with FTS4; [query] is FTS4 MATCH syntax and the caller
     * adds the `*` for prefix matching, as [EventsDao.search] does.
     */
    @Query("""
        SELECT tasks.* FROM tasks
        JOIN tasks_fts ON tasks.id = tasks_fts.rowid
        JOIN calendars ON calendars.id = tasks.calendar_id
        WHERE tasks_fts MATCH :query
        AND calendars.is_visible = 1
        AND tasks.sync_status != 'PENDING_DELETE'
        ORDER BY COALESCE(tasks.due_ts, tasks.dt_start_ts) ASC
        LIMIT 200
    """)
    suspend fun searchTasks(query: String): List<Task>

    // ========== Write operations ==========

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: Task): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: Task): Long

    @Update
    suspend fun update(task: Task)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM tasks WHERE calendar_id = :calendarId")
    suspend fun deleteByCalendarId(calendarId: Long)

    /** Soft-deletes: marks the task PENDING_DELETE until its queued server delete is pushed. */
    @Query("""
        UPDATE tasks
        SET sync_status = 'PENDING_DELETE',
            local_modified_at = :now,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun markForDeletion(id: Long, now: Long)

    // ========== Sync status updates ==========

    /** Marks the task SYNCED after a push and clears its error state. */
    @Query("""
        UPDATE tasks
        SET sync_status = 'SYNCED',
            etag = :etag,
            server_modified_at = :now,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun markSynced(id: Long, etag: String?, now: Long)

    /**
     * [markSynced] that also stores [rawIcal], the body the write sent, in the same statement;
     * see [EventsDao.markSyncedWithCopy] for why the body and etag move together.
     */
    @Query("""
        UPDATE tasks
        SET sync_status = 'SYNCED',
            etag = :etag,
            raw_ical = :rawIcal,
            server_modified_at = :now,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun markSyncedWithCopy(id: Long, etag: String?, rawIcal: String, now: Long)

    /** Marks the task SYNCED at its new [caldavUrl] after a create on the server. */
    @Query("""
        UPDATE tasks
        SET sync_status = 'SYNCED',
            caldav_url = :caldavUrl,
            etag = :etag,
            raw_ical = :rawIcal,
            server_modified_at = :now,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun markCreatedOnServerWithCopy(
        id: Long,
        caldavUrl: String,
        etag: String?,
        rawIcal: String,
        now: Long
    )

    /** Sets only the etag a pull saw, leaving sync status alone. */
    @Query("UPDATE tasks SET etag = :etag WHERE id = :id")
    suspend fun updateEtag(id: Long, etag: String?)
}
