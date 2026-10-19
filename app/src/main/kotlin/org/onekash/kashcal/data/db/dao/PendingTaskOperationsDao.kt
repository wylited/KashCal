package org.onekash.kashcal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import org.onekash.kashcal.data.db.entity.PendingTaskOperation
import org.onekash.kashcal.domain.sync.RetryDelays

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

    /** Returns the operation with [id], or null. */
    @Query("SELECT * FROM pending_task_operations WHERE id = :id")
    suspend fun getById(id: Long): PendingTaskOperation?

    // ========== Write operations ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(operation: PendingTaskOperation): Long

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

    // ========== Retry lifecycle ==========

    /**
     * Returns IN_PROGRESS operations last updated before [cutoff] to PENDING, and returns how
     * many.
     *
     * Runs at sync start to recover operations a crash left in progress; the caller's one-hour
     * cutoff spares operations another sync is still working on.
     */
    @Query("""
        UPDATE pending_task_operations
        SET status = 'PENDING',
            updated_at = :now
        WHERE status = 'IN_PROGRESS'
        AND updated_at < :cutoff
    """)
    suspend fun resetStaleInProgress(cutoff: Long, now: Long): Int

    /**
     * Returns every FAILED operation to PENDING for a forced full sync, and returns how many.
     *
     * ABANDONED is excluded, unlike the event queue's reset: a task operation is abandoned
     * only on a push conflict (412), where server-wins is the resolution and the pull applies
     * the server version, so re-pushing the local copy would just conflict again.
     */
    @Query("""
        UPDATE pending_task_operations
        SET status = 'PENDING',
            retry_count = 0,
            next_retry_at = 0,
            last_error = NULL,
            updated_at = :now
        WHERE status = 'FAILED'
    """)
    suspend fun resetAllFailed(now: Long): Int

    /**
     * Deletes every operation queued before [cutoff] (the caller passes now minus
     * [RetryDelays.OPERATION_LIFETIME_MS]), with the task rows an expired DELETE had
     * soft-deleted, and returns how many operations went.
     *
     * The task queue has no Force-Sync revival of expired operations, where the event queue
     * parks them ABANDONED for [PendingOperationsDao.resetAllFailed] to re-arm: a task row
     * soft-deleted with its DELETE would otherwise linger forever, since the pull skips
     * tombstones and only a DELETE's push reaps them.
     */
    @Transaction
    suspend fun deleteExpired(cutoff: Long): Int {
        deleteExpiredTombstonedTasks(cutoff)
        return deleteExpiredOperations(cutoff)
    }

    /** Deletes the tombstoned (PENDING_DELETE) tasks whose DELETE operations expired. */
    @Query("""
        DELETE FROM tasks
        WHERE sync_status = 'PENDING_DELETE'
        AND id IN (
            SELECT task_id FROM pending_task_operations
            WHERE operation = 'DELETE'
            AND created_at < :cutoff
        )
    """)
    suspend fun deleteExpiredTombstonedTasks(cutoff: Long): Int

    /** Deletes the expired operations themselves; see [deleteExpired]. */
    @Query("DELETE FROM pending_task_operations WHERE created_at < :cutoff")
    suspend fun deleteExpiredOperations(cutoff: Long): Int
}
