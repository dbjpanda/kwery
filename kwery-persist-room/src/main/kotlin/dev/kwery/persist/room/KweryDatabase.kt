package dev.kwery.persist.room

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [CacheEntryRow::class, CacheMetaRow::class, QueuedMutationRow::class],
    version = 1,
    exportSchema = false,
)
internal abstract class KweryDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
    abstract fun queueDao(): QueueDao
}
