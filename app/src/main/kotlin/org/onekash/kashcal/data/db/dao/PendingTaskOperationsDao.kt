package org.onekash.kashcal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import org.onekash.kashcal.data.db.entity.PendingTaskOperation

/**
 * Reads and writes the task sync queue; [PendingTaskOperation] says how the push drains it.
 */
@Dao
interface PendingTaskOperationsDao {

    /** Returns the PENDING operations due by [now], oldest first. */
    @Query("""
        SELECT * FROM pending_task_operations
        WHERE status = 'PENDING'
          AND next_retry_at <= :now
        ORDER BY created_at ASC
    """)
    suspend fun getReadyOperations(now: Long): List<PendingTaskOperation>

    /** Returns [taskId]'s operations, any status, oldest first. */
    @Query(
        "SELECT * FROM pending_task_operations WHERE task_id = :taskId ORDER BY created_at ASC"
    )
    suspend fun getForTask(taskId: Long): List<PendingTaskOperation>

    /** Returns whether [taskId] has an operation in any status but ABANDONED. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM pending_task_operations " +
            "WHERE task_id = :taskId AND status != 'ABANDONED')"
    )
    suspend fun hasPendingForTask(taskId: Long): Boolean

    // ========== Write operations ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(operation: PendingTaskOperation): Long

    @Update
    suspend fun update(operation: PendingTaskOperation)

    @Query("DELETE FROM pending_task_operations WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Deletes every operation of [taskId], any status. */
    @Query("DELETE FROM pending_task_operations WHERE task_id = :taskId")
    suspend fun deleteForTask(taskId: Long)

    /**
     * Deletes [taskId]'s CREATE, UPDATE and MOVE operations, any status. A queued DELETE
     * survives, so an edit queued after a delete can't resurrect a task the push is still
     * tearing down; [TaskRepository.queueOperation] clears these before inserting a fresh op.
     */
    @Query("DELETE FROM pending_task_operations WHERE task_id = :taskId AND operation != 'DELETE'")
    suspend fun deleteNonDeleteOpsForTask(taskId: Long)

    // ========== Status updates ==========

    @Query("""
        UPDATE pending_task_operations
        SET status = 'IN_PROGRESS',
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun markInProgress(id: Long, now: Long)

    /** Requeues an operation for [nextRetryAt], counting the retry and recording [error]. */
    @Query("""
        UPDATE pending_task_operations
        SET status = 'PENDING',
            retry_count = retry_count + 1,
            next_retry_at = :nextRetryAt,
            last_error = :error,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun scheduleRetry(id: Long, nextRetryAt: Long, error: String, now: Long)

    /** Marks an operation FAILED; the task row keeps its local copy for the next sync. */
    @Query("""
        UPDATE pending_task_operations
        SET status = 'FAILED',
            last_error = :error,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun markFailed(id: Long, error: String, now: Long)

    /**
     * Abandons an operation the push gives up on (a 412 conflict, server-wins) and returns the
     * local row to the server's version on the next pull.
     */
    @Query("""
        UPDATE pending_task_operations
        SET status = 'ABANDONED',
            last_error = :error,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun markAbandoned(id: Long, error: String, now: Long)

    /** Returns one operation to PENDING, ready now, with a fresh retry count. */
    @Query("""
        UPDATE pending_task_operations
        SET status = 'PENDING',
            retry_count = 0,
            next_retry_at = 0,
            last_error = NULL,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun resetToPending(id: Long, now: Long)
}
