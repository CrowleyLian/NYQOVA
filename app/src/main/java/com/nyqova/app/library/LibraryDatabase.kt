package com.nyqova.app.library

import android.content.Context
import androidx.room.*
import com.nyqova.app.storage.Song

@Entity(tableName = "tracks", indices = [Index("source"), Index(value = ["source", "folder"]), Index("albumKey")])
data class LibraryTrack(@PrimaryKey val id: String, val source: String, val folder: String, val albumKey: String,
    val title: String, val artist: String, val album: String, val path: String, val cuePath: String?, val number: Int,
    val startUs: Long, val endUs: Long?, val durationMs: Long, val artworkPath: String?, val lyricsPath: String?, val lyricsOffsetMs: Long,
    @ColumnInfo(defaultValue="''") val genre: String = "", @ColumnInfo(defaultValue="0") val addedAt: Long = 0,
    @ColumnInfo(defaultValue="0") val fileSize: Long = 0) {
    fun song() = Song(title, path, artist, album, startUs, endUs, durationMs, artworkPath, lyricsPath, lyricsOffsetMs, id)
}
@Entity(tableName = "directories", primaryKeys = ["source", "path"])
data class IndexedDirectory(val source: String, val path: String, val fingerprint: String)
data class AlbumSummary(val albumKey: String, val title: String, val artist: String, val artworkPath: String?, val trackCount: Int)

@Dao
interface LibraryDao {
    @Query("SELECT albumKey, album AS title, CASE WHEN COUNT(DISTINCT NULLIF(artist,'')) > 1 THEN '群星' ELSE MAX(artist) END AS artist, MAX(artworkPath) AS artworkPath, COUNT(*) AS trackCount FROM tracks WHERE source=:source GROUP BY albumKey, album ORDER BY album COLLATE NOCASE")
    fun albums(source: String): List<AlbumSummary>
    @Query("SELECT * FROM tracks WHERE albumKey=:album ORDER BY number, title COLLATE NOCASE")
    fun albumTracks(album: String): List<LibraryTrack>
    @Query("SELECT * FROM tracks WHERE source=:source AND folder=:folder ORDER BY number, title")
    fun folderTracks(source: String, folder: String): List<LibraryTrack>
    @Query("SELECT * FROM tracks WHERE source=:source AND path=:path") fun fileTracks(source: String, path: String): List<LibraryTrack>
    @Query("UPDATE tracks SET artworkPath=:path WHERE albumKey=:album AND artworkPath IS NULL") fun fillArtwork(album: String, path: String)
    @Query("UPDATE tracks SET artworkPath=NULL WHERE artworkPath=:path") fun clearArtworkPath(path: String)
    @Query("SELECT * FROM directories WHERE source=:source")
    fun directories(source: String): List<IndexedDirectory>
    @Query("DELETE FROM tracks WHERE source=:source") fun deleteTracks(source: String)
    @Query("DELETE FROM tracks WHERE source=:source AND folder=:folder") fun deleteFolderTracks(source: String,folder: String)
    @Query("DELETE FROM directories WHERE source=:source AND path=:path") fun deleteDirectory(source: String,path: String)
    @Query("UPDATE tracks SET artworkPath=:path WHERE source=:source AND folder=:folder AND artworkPath IS NULL") fun fillFolderArtwork(source: String,folder: String,path: String)
    @Query("DELETE FROM directories WHERE source=:source") fun deleteDirectories(source: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertTracks(rows: List<LibraryTrack>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertDirectories(rows: List<IndexedDirectory>)
}
@Database(entities = [LibraryTrack::class, IndexedDirectory::class, Favorite::class, SavedPlaylist::class, PlaylistMember::class, PlayHistory::class,IndexedAudio::class,SearchInitial::class], version = 6, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun collections(): CollectionsDao
    abstract fun searchInitials(): SearchInitialDao
    abstract fun audioIndex(): AudioIndexDao
    companion object {
        @Volatile private var instance: LibraryDatabase? = null
        fun get(context: Context): LibraryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LibraryDatabase::class.java, "music-v1.db")
                .addMigrations(object : androidx.room.migration.Migration(1, 2) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS favorites (source TEXT NOT NULL,kind TEXT NOT NULL,target TEXT NOT NULL,addedAt INTEGER NOT NULL,PRIMARY KEY(source,kind,target))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS playlists (id TEXT NOT NULL PRIMARY KEY,source TEXT NOT NULL,name TEXT NOT NULL,kind TEXT NOT NULL,folder TEXT NOT NULL,recursive INTEGER NOT NULL,createdAt INTEGER NOT NULL)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_playlists_source ON playlists(source)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS playlist_items (playlistId TEXT NOT NULL,trackId TEXT NOT NULL,position INTEGER NOT NULL,PRIMARY KEY(playlistId,trackId))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS play_history (source TEXT NOT NULL,trackId TEXT NOT NULL,playedAt INTEGER NOT NULL,PRIMARY KEY(source,trackId))")
                    }
                }, object : androidx.room.migration.Migration(2,3) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE tracks ADD COLUMN genre TEXT NOT NULL DEFAULT ''")
                        db.execSQL("ALTER TABLE tracks ADD COLUMN addedAt INTEGER NOT NULL DEFAULT 0")
                        db.execSQL("ALTER TABLE tracks ADD COLUMN fileSize INTEGER NOT NULL DEFAULT 0")
                    }
                },object : androidx.room.migration.Migration(3,4) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS audio_index (source TEXT NOT NULL,path TEXT NOT NULL,folder TEXT NOT NULL,size INTEGER NOT NULL,modified TEXT NOT NULL,fingerprint TEXT NOT NULL,ready INTEGER NOT NULL,attempts INTEGER NOT NULL,coverPath TEXT,coverModified TEXT NOT NULL,coverSize INTEGER NOT NULL,PRIMARY KEY(source,path))")
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_audio_index_source ON audio_index(source)")
                    }
                },object : androidx.room.migration.Migration(4,5) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS search_initials (value TEXT NOT NULL PRIMARY KEY,initial TEXT NOT NULL)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_search_initials_initial ON search_initials(initial)")
                    }
                },object : androidx.room.migration.Migration(5,6) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        // The previous cache stored only one bucket letter. Rebuild local names on demand.
                        db.execSQL("DELETE FROM search_initials")
                    }
                })
                .build().also { instance = it }
        }
    }
}
