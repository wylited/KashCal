package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * Indexes task summary and description for full-text search (FTS4), the task counterpart of
 * [EventFts].
 *
 * With `contentEntity`, Room keeps this table in step with [Task] through triggers.
 */
@Fts4(contentEntity = Task::class)
@Entity(tableName = "tasks_fts")
data class TaskFts(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,

    @ColumnInfo(name = "summary")
    val summary: String,

    @ColumnInfo(name = "description")
    val description: String?
)
