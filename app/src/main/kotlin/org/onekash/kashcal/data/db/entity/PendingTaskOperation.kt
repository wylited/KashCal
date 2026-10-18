package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.onekash.kashcal.domain.sync.RetryDelays

/**
 * Queues a sync operation for a local change to a task in a server calendar, the task
 * counterpart of [PendingOperation].
 *
 * The push drains ready operations oldest first, retrying a failure up to [maxRetries] times
 * with exponential backoff ([RetryDelays]). MOVE is PUT to the target plus DELETE of the
 * source; CalDAV has no MOVE method for the fallback path events use.
 */
@Entity(
    tableName = "pending_task_operations",
    indices = [
        Index(value = ["status", "next_retry_at"]),
        Index(value = ["task_id"])
    ]
)
data class PendingTaskOperation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The task. Not a foreign key, so an operation outlives its deleted task. */
    @ColumnInfo(name = "task_id")
    val taskId: Long,

    /** One of the OPERATION_ constants: CREATE, UPDATE, DELETE or MOVE. */
    @ColumnInfo(name = "operation")
    val operation: String,

    /** One of the STATUS_ constants: PENDING, IN_PROGRESS, FAILED or ABANDONED. */
    @ColumnInfo(name = "status", defaultValue = "'PENDING'")
    val status: String = "PENDING",

    /** Retries made so far. */
    @ColumnInfo(name = "retry_count", defaultValue = "0")
    val retryCount: Int = 0,

    /** Retries allowed before the operation fails. */
    @ColumnInfo(name = "max_retries", defaultValue = "5")
    val maxRetries: Int = 10,

    /** Earliest time of the next attempt, epoch millis; 0 is ready now. */
    @ColumnInfo(name = "next_retry_at", defaultValue = "0")
    val nextRetryAt: Long = 0,

    /** Last error message, for diagnostics. */
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    /**
     * Server URL captured at queue time, so the push still knows it after Task.caldavUrl is
     * cleared. Set for DELETE and MOVE.
     */
    @ColumnInfo(name = "target_url")
    val targetUrl: String? = null,

    /** Calendar a MOVE takes the task to. */
    @ColumnInfo(name = "target_calendar_id")
    val targetCalendarId: Long? = null
) {
    companion object {
        const val OPERATION_CREATE = "CREATE"
        const val OPERATION_UPDATE = "UPDATE"
        const val OPERATION_DELETE = "DELETE"
        const val OPERATION_MOVE = "MOVE"  // PUT to the target, then DELETE the source

        const val STATUS_PENDING = "PENDING"
        const val STATUS_IN_PROGRESS = "IN_PROGRESS"
        const val STATUS_FAILED = "FAILED"

        /**
         * Terminal status of an operation past its lifetime; unlike FAILED it is never picked
         * up again. Kept for parity with [PendingOperation.STATUS_ABANDONED].
         */
        const val STATUS_ABANDONED = "ABANDONED"
    }
}
