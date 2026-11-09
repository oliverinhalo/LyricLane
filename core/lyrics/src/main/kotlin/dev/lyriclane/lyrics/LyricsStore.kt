package dev.lyriclane.lyrics

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Cached lyrics for one track. A row with every lyric field null and
 * [notFound] set is a negative cache entry, so a song LRCLIB does not have is
 * not looked up again on every play.
 */
@Entity(tableName = "lyrics")
data class CachedLyrics(
    @PrimaryKey val cacheKey: String,
    val trackName: String,
    val artistName: String,
    val syncedLyrics: String?,
    val plainLyrics: String?,
    val instrumental: Boolean,
    val notFound: Boolean,
    val fetchedAtMs: Long,
)

/** A per-song timing correction, in milliseconds. Positive shows lyrics sooner. */
@Entity(tableName = "offsets")
data class TrackOffset(
    @PrimaryKey val cacheKey: String,
    val offsetMs: Long,
)

@Dao
interface LyricsDao {

    @Query("SELECT * FROM lyrics WHERE cacheKey = :key LIMIT 1")
    suspend fun find(key: String): CachedLyrics?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: CachedLyrics)

    @Query("DELETE FROM lyrics WHERE cacheKey = :key")
    suspend fun forget(key: String)

    @Query("DELETE FROM lyrics")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM lyrics")
    suspend fun count(): Int

    @Query("SELECT offsetMs FROM offsets WHERE cacheKey = :key LIMIT 1")
    suspend fun offsetFor(key: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOffset(offset: TrackOffset)

    @Query("DELETE FROM offsets")
    suspend fun clearOffsets()
}

@Database(
    entities = [CachedLyrics::class, TrackOffset::class],
    version = 1,
    exportSchema = false,
)
abstract class LyricsDatabase : RoomDatabase() {
    abstract fun lyricsDao(): LyricsDao

    companion object {
        fun create(context: Context): LyricsDatabase =
            Room.databaseBuilder(context, LyricsDatabase::class.java, "lyriclane.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
