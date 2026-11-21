package com.nyqova.app.library

import androidx.room.*

/** Durable enrichment queue. Basic tracks remain usable if enrichment is cancelled or offline. */
@Entity(tableName="audio_index",primaryKeys=["source","path"],indices=[Index("source")])
data class IndexedAudio(val source: String,val path: String,val folder: String,val size: Long,val modified: String,
    val fingerprint: String,val ready: Boolean=false,val attempts: Int=0,val coverPath: String?=null,val coverModified: String="",val coverSize: Long=0)

@Dao
interface AudioIndexDao {
    @Query("SELECT * FROM audio_index WHERE source=:source") fun files(source: String): List<IndexedAudio>
    @Query("SELECT * FROM audio_index WHERE source=:source AND ready=0 AND attempts<2 ORDER BY attempts,path LIMIT :limit") fun pending(source: String,limit: Int=24): List<IndexedAudio>
    @Query("SELECT COUNT(*) FROM audio_index WHERE source=:source AND ready=0 AND attempts<2") fun pendingCount(source: String): Int
    @Query("SELECT COUNT(*) FROM audio_index WHERE source=:source AND ready=0 AND attempts>=2") fun failedCount(source: String): Int
    @Insert(onConflict=OnConflictStrategy.REPLACE) fun save(files: List<IndexedAudio>)
    @Query("DELETE FROM audio_index WHERE source=:source AND path=:path") fun delete(source: String,path: String)
    @Query("UPDATE audio_index SET attempts=0 WHERE source=:source AND ready=0") fun retry(source: String)
}
