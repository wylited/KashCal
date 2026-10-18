package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores one VTODO task (RFC 5545 §3.6.2) of a calendar.
 *
 * The sync fields mirror [Event]: local edits apply at once and push later. A task places
 * itself at `due ?? dtstart` ([whenTs]); unlike events there is no occurrence expansion, so
 * recurring tasks stay one row and completion defers to the server.
 */
@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = Calendar::class,
            parentColumns = ["id"],
            childColumns = ["calendar_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["calendar_id"]),
        Index(value = ["calendar_id", "uid"], unique = true),
        Index(value = ["due_ts"]),
        Index(value = ["caldav_url"]),
        Index(value = ["sync_status"])
    ]
)
data class Task(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // ========== Identity ==========

    /** RFC 5545 UID, such as `uuid@domain`. */
    @ColumnInfo(name = "uid")
    val uid: String,

    /** Owning calendar; deleting the calendar cascades to its tasks. */
    @ColumnInfo(name = "calendar_id")
    val calendarId: Long,

    // ========== Content ==========

    /** RFC 5545 SUMMARY. */
    @ColumnInfo(name = "summary")
    val summary: String,

    /** RFC 5545 DESCRIPTION. */
    @ColumnInfo(name = "description")
    val description: String? = null,

    /**
     * VTODO STATUS as a [TodoStatusName] string: NEEDS_ACTION, IN_PROCESS, COMPLETED or
     * CANCELLED. A task is open when it is neither COMPLETED nor CANCELLED.
     */
    @ColumnInfo(name = "status")
    val status: String = STATUS_NEEDS_ACTION,

    /** RFC 5545 PRIORITY: 0 undefined, 1 highest, 9 lowest. Kept full-range, not banded. */
    @ColumnInfo(name = "priority")
    val priority: Int = 0,

    /** RFC 5545 PERCENT-COMPLETE, 0 to 100; null when unset (a completed task stores 100). */
    @ColumnInfo(name = "percent_complete")
    val percentComplete: Int? = null,

    // ========== Dates ==========

    /** RFC 5545 DUE as epoch millis; UTC midnight of the day for an all-day due. */
    @ColumnInfo(name = "due_ts")
    val dueTs: Long? = null,

    /** IANA zone of DUE, e.g. "Europe/Berlin"; null for UTC, floating or all-day values. */
    @ColumnInfo(name = "due_timezone")
    val dueTimezone: String? = null,

    /** Whether DUE is a DATE (all-day) instead of a DATE-TIME. */
    @ColumnInfo(name = "due_is_all_day")
    val dueIsAllDay: Boolean = false,

    /** RFC 5545 DTSTART as epoch millis; the fallback placement when DUE is absent. */
    @ColumnInfo(name = "dt_start_ts")
    val dtStartTs: Long? = null,

    /** RFC 5545 COMPLETED as epoch millis, always UTC; set exactly when STATUS is COMPLETED. */
    @ColumnInfo(name = "completed_ts")
    val completedTs: Long? = null,

    /** RFC 5545 RRULE, e.g. "FREQ=WEEKLY;BYDAY=MO". Never client-expanded; display-only. */
    @ColumnInfo(name = "rrule")
    val rrule: String? = null,

    // ========== Round trip ==========

    /** RFC 5545 SEQUENCE, bumped before each push. */
    @ColumnInfo(name = "sequence")
    val sequence: Int = 0,

    /**
     * Unknown iCal properties as their original content lines, newline-joined and written back
     * on push, so unmodeled properties survive a round trip.
     */
    @ColumnInfo(name = "unknown_lines")
    val unknownLines: String? = null,

    /** The server's ICS for this task, the body a push sent or a pull received. */
    @ColumnInfo(name = "raw_ical")
    val rawIcal: String? = null,

    // ========== Sync metadata ==========

    /** URL of the task's CalDAV resource; null until the server create lands. */
    @ColumnInfo(name = "caldav_url")
    val caldavUrl: String? = null,

    /** The server's ETag, sent as If-Match for optimistic concurrency. */
    @ColumnInfo(name = "etag")
    val etag: String? = null,

    /** Whether the task needs pushing, and how. */
    @ColumnInfo(name = "sync_status")
    val syncStatus: SyncStatus = SyncStatus.SYNCED,

    /** RFC 5545 DTSTAMP, required on a VTODO. */
    @ColumnInfo(name = "dtstamp")
    val dtstamp: Long,

    // ========== Timestamps ==========

    /** Creation time; a pull takes the server's CREATED when present. */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /** Time of the row's last update. */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    /** Time of the last local edit. */
    @ColumnInfo(name = "local_modified_at")
    val localModifiedAt: Long? = null,

    /** Server-side modification time: LAST-MODIFIED on a pull, the push time on a push. */
    @ColumnInfo(name = "server_modified_at")
    val serverModifiedAt: Long? = null
) {
    // ========== Computed properties ==========

    /** True when the task is neither COMPLETED nor CANCELLED. */
    val isOpen: Boolean
        get() = status != STATUS_COMPLETED && status != STATUS_CANCELLED

    /** True when the task is COMPLETED. */
    val isDone: Boolean
        get() = status == STATUS_COMPLETED

    /** The moment the task places itself at: DUE when set, else DTSTART; null when neither. */
    val whenTs: Long?
        get() = dueTs ?: dtStartTs

    /**
     * True when the task has local changes a pull must not overwrite; see
     * [Event.hasPendingChanges] for the protected states.
     */
    fun hasPendingChanges(): Boolean {
        return syncStatus == SyncStatus.PENDING_CREATE ||
               syncStatus == SyncStatus.PENDING_UPDATE ||
               syncStatus == SyncStatus.PENDING_DELETE
    }

    companion object {
        /** [status] values; the names of icaldav-core's TodoStatus enum. */
        const val STATUS_NEEDS_ACTION = "NEEDS_ACTION"
        const val STATUS_IN_PROCESS = "IN_PROCESS"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_CANCELLED = "CANCELLED"
    }
}
