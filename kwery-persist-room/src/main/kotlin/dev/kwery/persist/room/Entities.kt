package dev.kwery.persist.room

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * One cached entry, one row.
 *
 * The whole point of the Room stores: a file persister rewrites every byte when
 * one entry changes, and at ten thousand entries that is a megabyte of flash per
 * change. Rows let SQLite touch only the pages that actually moved.
 */
@Entity(tableName = "cache_entries")
internal data class CacheEntryRow(
    @PrimaryKey val keyHash: String,
    val data: String,
    val dataUpdatedAt: Long,
)

/** Snapshot-level fields, which belong to the cache rather than any entry. */
@Entity(tableName = "cache_meta")
internal data class CacheMetaRow(
    @PrimaryKey val id: Int = 0,
    val timestamp: Long,
    val buster: String,
    val schemaVersion: Int,
)

@Entity(tableName = "queued_mutations")
internal data class QueuedMutationRow(
    @PrimaryKey val id: String,
    val keyHash: String,
    val variables: String,
    val scopeId: String?,
    val submittedAt: Long,
    val attempts: Int,
    val lastError: String?,
    val deadLetter: String?,
)

@Dao
internal interface CacheDao {
    @Query("SELECT * FROM cache_entries")
    suspend fun entries(): List<CacheEntryRow>

    @Query("SELECT keyHash, dataUpdatedAt FROM cache_entries")
    suspend fun fingerprints(): List<Fingerprint>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<CacheEntryRow>)

    @Query("DELETE FROM cache_entries WHERE keyHash IN (:keyHashes)")
    suspend fun deleteByKey(keyHashes: List<String>)

    @Query("DELETE FROM cache_entries")
    suspend fun clearEntries()

    @Query("SELECT * FROM cache_meta WHERE id = 0")
    suspend fun meta(): CacheMetaRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setMeta(row: CacheMetaRow)

    @Query("DELETE FROM cache_meta")
    suspend fun clearMeta()

    /**
     * One transaction for the whole write.
     *
     * A half-applied persist is worse than a stale one: an entry deleted but
     * its metadata not updated leaves a cache that reads as newer than it is.
     */
    @Transaction
    suspend fun applyChanges(changed: List<CacheEntryRow>, gone: List<String>, meta: CacheMetaRow) {
        if (changed.isNotEmpty()) upsert(changed)
        if (gone.isNotEmpty()) deleteByKey(gone)
        setMeta(meta)
    }

    @Transaction
    suspend fun clearAll() {
        clearEntries()
        clearMeta()
    }
}

/** Just enough of a row to tell whether it changed, without reading the data. */
internal data class Fingerprint(val keyHash: String, val dataUpdatedAt: Long)

@Dao
internal interface QueueDao {
    @Query("SELECT * FROM queued_mutations ORDER BY submittedAt ASC")
    suspend fun all(): List<QueuedMutationRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: QueuedMutationRow)

    @Query("DELETE FROM queued_mutations WHERE id = :id")
    suspend fun remove(id: String)
}
