package com.tristinbaker.inkshelf.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM libraries ORDER BY name COLLATE NOCASE ASC")
    fun observeLibraries(): Flow<List<LibraryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLibraries(libraries: List<LibraryEntity>)

    @Query("DELETE FROM libraries WHERE id = :id")
    suspend fun deleteLibrary(id: String)

    @Query("DELETE FROM libraries")
    suspend fun clearLibraries()
}

@Dao
interface ItemDao {
    @Query("SELECT * FROM items WHERE libraryId = :libraryId")
    fun observeItems(libraryId: String): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE libraryId = :libraryId AND seriesId = :seriesId")
    fun observeSeriesItems(libraryId: String, seriesId: String): Flow<List<ItemEntity>>

    @Query("SELECT COUNT(*) FROM items WHERE libraryId = :libraryId")
    suspend fun count(libraryId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<ItemEntity>)

    @Query("DELETE FROM items WHERE libraryId = :libraryId AND id NOT IN (:keepIds)")
    suspend fun deleteStale(libraryId: String, keepIds: List<String>)

    @Query("DELETE FROM items WHERE libraryId = :libraryId")
    suspend fun deleteLibraryItems(libraryId: String)

    @Query("DELETE FROM items")
    suspend fun clearItems()

    /**
     * Replaces a library's snapshot atomically. Deleting first inside a
     * transaction means a refresh can never leave rows from a previous sync
     * interleaved with the new ones.
     */
    @Transaction
    suspend fun replaceLibrarySnapshot(libraryId: String, items: List<ItemEntity>) {
        deleteLibraryItems(libraryId)
        upsert(items)
    }
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE itemId = :itemId ORDER BY trackIndex ASC")
    fun observeForItem(itemId: String): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE state IN ('queued', 'running')")
    suspend fun unfinished(): List<DownloadEntity>

    /** Finished files for one book, in track order, for offline playback. */
    @Query(
        "SELECT * FROM downloads WHERE itemId = :itemId AND state = 'done' " +
            "AND uri IS NOT NULL ORDER BY trackIndex ASC",
    )
    suspend fun doneForItem(itemId: String): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM downloads WHERE itemId = :itemId")
    suspend fun deleteForItem(itemId: String)
}

@Dao
interface BookMetadataDao {
    @Query("SELECT * FROM book_metadata WHERE itemId = :itemId")
    suspend fun get(itemId: String): BookMetadataEntity?

    @Query("SELECT * FROM book_metadata ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<BookMetadataEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(metadata: BookMetadataEntity)

    @Query("DELETE FROM book_metadata WHERE itemId = :itemId")
    suspend fun delete(itemId: String)

    /** Progress only, so a playback tick does not rewrite the whole row. */
    @Query(
        "UPDATE book_metadata SET currentTime = :currentTime, isFinished = :isFinished " +
            "WHERE itemId = :itemId",
    )
    suspend fun updateProgress(itemId: String, currentTime: Double, isFinished: Boolean)
}

@Database(
    entities = [
        LibraryEntity::class,
        ItemEntity::class,
        DownloadEntity::class,
        BookMetadataEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    companion object {
        /**
         * Adds the chapter cache column. Book metadata already exists at v3, and
         * the column is nullable with a null default, so this is a pure shape
         * change: every existing row stays intact, including resume positions.
         */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE book_metadata ADD COLUMN chaptersJson TEXT")
            }
        }
    }

    abstract fun libraryDao(): LibraryDao
    abstract fun itemDao(): ItemDao
    abstract fun downloadDao(): DownloadDao
    abstract fun bookMetadataDao(): BookMetadataDao
}
