package com.nyqova.app.library

import androidx.room.*
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import java.util.UUID

@Entity(tableName = "favorites", primaryKeys = ["source", "kind", "target"])
data class Favorite(val source: String, val kind: String, val target: String, val addedAt: Long = System.currentTimeMillis())
@Entity(tableName = "playlists", indices = [Index("source")])
data class SavedPlaylist(@PrimaryKey val id: String = UUID.randomUUID().toString(), val source: String, val name: String,
    val kind: String = "manual", val folder: String = "", val recursive: Boolean = true, val createdAt: Long = System.currentTimeMillis())
@Entity(tableName = "playlist_items", primaryKeys = ["playlistId", "trackId"])
data class PlaylistMember(val playlistId: String, val trackId: String, val position: Int)
@Entity(tableName = "play_history", primaryKeys = ["source", "trackId"])
data class PlayHistory(val source: String, val trackId: String, val playedAt: Long)
data class LibraryGroup(val name: String, val trackCount: Int, val artworkPath: String?)

@Dao
interface CollectionsDao {
    @RawQuery fun count(query: SupportSQLiteQuery): Int
    @RawQuery fun albums(query: SupportSQLiteQuery): List<AlbumSummary>
    @RawQuery fun groups(query: SupportSQLiteQuery): List<LibraryGroup>
    @Query("SELECT target FROM favorites WHERE source=:source AND kind=:kind") fun favorites(source: String, kind: String): List<String>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun favorite(row: Favorite)
    @Query("DELETE FROM favorites WHERE source=:source AND kind=:kind AND target=:target") fun unfavorite(source: String, kind: String, target: String)
    @Query("SELECT * FROM playlists WHERE source=:source ORDER BY createdAt, name") fun playlists(source: String): List<SavedPlaylist>
    @Query("SELECT * FROM playlists WHERE id=:id") fun playlist(id: String): SavedPlaylist?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun savePlaylist(row: SavedPlaylist)
    @Query("DELETE FROM playlists WHERE id=:id") fun deletePlaylist(id: String)
    @Query("DELETE FROM playlist_items WHERE playlistId=:id") fun clearMembers(id: String)
    @Query("SELECT COALESCE(MAX(position),-1)+1 FROM playlist_items WHERE playlistId=:id") fun nextPosition(id: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun add(row: PlaylistMember)
    @Query("DELETE FROM playlist_items WHERE playlistId=:id AND trackId=:track") fun remove(id: String, track: String)
    @Query("SELECT * FROM playlist_items WHERE playlistId=:id ORDER BY position") fun members(id: String): List<PlaylistMember>
    @Update fun updateMembers(rows: List<PlaylistMember>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun history(row: PlayHistory)
    @RawQuery fun tracks(query: SupportSQLiteQuery): List<LibraryTrack>
    @Query("SELECT * FROM tracks WHERE id=:id") fun track(id: String): LibraryTrack?
}

data class TrackScope(val source: String, val kind: String = "all", val target: String = "", val recursive: Boolean = true,
    val search: String = "", val sort: String = "title", val albumKey: String = "", val initial: String = "") {
    fun query(limit: Int = -1, offset: Int = 0): SupportSQLiteQuery = buildQuery(limit,offset,false)
    fun countQuery(): SupportSQLiteQuery = buildQuery(-1,0,true)
    fun albumsQuery(limit: Int=80,offset: Int=0): SupportSQLiteQuery = buildQuery(limit,offset,false,true)
    fun albumCountQuery(): SupportSQLiteQuery = buildQuery(-1,0,true,true)
    private fun buildQuery(limit: Int,offset: Int,count: Boolean,albums: Boolean=false): SupportSQLiteQuery {
        val args = mutableListOf<Any>(source)
        val sql = StringBuilder(when {
            albums && count -> "SELECT COUNT(*) FROM (SELECT t.albumKey FROM tracks t "
            albums -> "SELECT t.albumKey,t.album AS title,CASE WHEN COUNT(DISTINCT NULLIF(t.artist,''))>1 THEN '群星' ELSE MAX(t.artist) END AS artist,MAX(t.artworkPath) AS artworkPath,COUNT(*) AS trackCount FROM tracks t "
            count -> "SELECT COUNT(*) FROM tracks t "
            else -> "SELECT t.* FROM tracks t "
        })
        when(kind) {
            "favorites" -> sql.append("JOIN favorites f ON f.source=t.source AND f.target=t.id AND f.kind='track' ")
            "recent" -> sql.append("JOIN play_history h ON h.source=t.source AND h.trackId=t.id ")
            "playlist" -> sql.append("JOIN playlist_items p ON p.trackId=t.id ")
        }
        sql.append("WHERE t.source=? ")
        when(kind) {
            "artist" -> { sql.append("AND t.artist=? "); args += target }
            "genre" -> { sql.append("AND t.genre=? "); args += target }
            "album" -> { sql.append("AND t.albumKey=? "); args += target }
            "playlist" -> { sql.append("AND p.playlistId=? "); args += target }
            "folder" -> if(target.isNotEmpty() || !recursive) {
                sql.append(if(recursive) "AND (t.folder=? OR substr(t.folder,1,length(?)+1)=?||'/') " else "AND t.folder=? ")
                args += target
                if(recursive) { args += target; args += target }
            }
        }
        if(albumKey.isNotEmpty()) { sql.append("AND t.albumKey=? "); args+=albumKey }
        if(initial.isNotEmpty()) {
            val fields=if(albums) "i.value=t.album OR i.value=t.artist" else "i.value=t.title OR i.value=t.artist OR i.value=t.album"
            sql.append("AND EXISTS (SELECT 1 FROM search_initials i WHERE i.initial=? AND ($fields)) "); args+=initial
        }
        if(search.isNotBlank()) {
            sql.append("AND ${SearchInitials.predicate(if(albums) listOf("t.album","t.artist") else listOf("t.title","t.artist","t.album"),search,args)} ")
        }
        if(albums) {
            sql.append("GROUP BY t.albumKey,t.album ")
            if(count) sql.append(")") else sql.append(if(sort=="artist") "ORDER BY artist COLLATE NOCASE,t.album COLLATE NOCASE,t.albumKey " else "ORDER BY t.album COLLATE NOCASE,t.albumKey ")
        }
        if(!count && !albums) sql.append(when(kind) {
            "added" -> "ORDER BY t.addedAt DESC,t.album,t.number,t.id "
            "album" -> "ORDER BY t.number,t.title "
            "playlist" -> "ORDER BY p.position "
            "recent" -> "ORDER BY h.playedAt DESC "
            else -> when(sort) { "artist" -> "ORDER BY t.artist COLLATE NOCASE,t.album,t.number,t.title "; "album" -> "ORDER BY t.album COLLATE NOCASE,t.number,t.title "; else -> "ORDER BY t.title COLLATE NOCASE,t.id " }
        })
        if(limit >= 0) { sql.append("LIMIT ? OFFSET ?"); args += limit; args += offset }
        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }
}
