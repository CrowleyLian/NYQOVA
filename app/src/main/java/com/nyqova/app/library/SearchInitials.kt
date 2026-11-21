package com.nyqova.app.library

import android.os.Build
import androidx.room.*
import com.nyqova.core.ChineseKeys
import java.text.Normalizer
import java.util.Locale

@Entity(tableName="search_initials",indices=[Index("initial")])
data class SearchInitial(@PrimaryKey val value: String,val initial: String)

@Dao
interface SearchInitialDao {
    @Query("SELECT names.value FROM (SELECT title AS value FROM tracks UNION SELECT artist FROM tracks UNION SELECT album FROM tracks UNION SELECT genre FROM tracks) names LEFT JOIN search_initials i ON i.value=names.value WHERE i.value IS NULL LIMIT 256")
    fun missing(): List<String>
    @Insert(onConflict=OnConflictStrategy.REPLACE) fun insert(rows: List<SearchInitial>)
}

/** Local name index only: no NAS reads, audio parsing or UI-thread transliteration. */
object SearchInitials {
    @androidx.annotation.RequiresApi(24)
    private object Modern {
        val index=android.icu.text.AlphabeticIndex<Any>(Locale.SIMPLIFIED_CHINESE).addLabels(Locale.ENGLISH).buildImmutableIndex()
        fun initial(value: String)=index.getBucket(index.getBucketIndex(value))?.label?.uppercase(Locale.ROOT)
    }
    fun of(value: String): String {
        val name=ChineseKeys.simplified(value).dropWhile { !it.isLetterOrDigit() }
        if(name.isEmpty()) return "#"
        if(name[0].isDigit()) return "0–9"
        val latin=Normalizer.normalize(name,Normalizer.Form.NFD)[0].uppercaseChar()
        if(latin in 'A'..'Z') return latin.toString()
        if(Build.VERSION.SDK_INT>=24) return Modern.initial(name)?.takeIf { it.length==1 && it[0] in 'A'..'Z' } ?: "#"
        // Android 6 fallback for common GB2312 Han characters; later systems use ICU.
        val bytes=name.take(1).toByteArray(charset("GB2312"))
        if(bytes.size!=2) return "#"
        val code=(bytes[0].toInt() and 255)*256+(bytes[1].toInt() and 255)-65536
        val bounds=intArrayOf(-20319,-20284,-19776,-19219,-18711,-18527,-18240,-17923,-17418,-16475,-16213,-15641,-15166,-14923,-14915,-14631,-14150,-14091,-13319,-12839,-12557,-11848,-11056)
        val letters="ABCDEFGHJKLMNOPQRSTWXYZ"
        return bounds.indices.lastOrNull { code>=bounds[it] && code<=-10247 }?.let { letters[it].toString() } ?: "#"
    }
    fun key(value: String): String {
        val name=ChineseKeys.simplified(value)
        val result=StringBuilder(); var inWord=false
        var offset=0
        while(offset<name.length) {
            val cp=name.codePointAt(offset); offset+=Character.charCount(cp)
            val han=cp in 0x3400..0x9fff || cp in 0xf900..0xfaff || cp in 0x20000..0x323af
            if(han) { result.append(of(String(Character.toChars(cp)))); inWord=false }
            else if(Character.isLetterOrDigit(cp)) {
                if(!inWord) result.append(Normalizer.normalize(String(Character.toChars(cp)),Normalizer.Form.NFD).first().uppercaseChar())
                inWord=true
            } else if(cp!=39 && cp!=0x2019) inWord=false
        }
        return result.toString()
    }
    fun prefix(query: String): String {
        val compact=query.filterNot { it.isWhitespace() }.uppercase(Locale.ROOT)
        return compact.takeIf { it.isNotEmpty() && it.all { c -> c in 'A'..'Z' || c in '0'..'9' } }.orEmpty()
    }
    fun matches(value: String,query: String)=query.isBlank() || value.contains(query,true) || prefix(query).let { it.isNotEmpty() && key(value).startsWith(it) }
    /** Trusted column names only; every user value is bound, including literal LIKE metacharacters. */
    fun predicate(columns: List<String>,query: String,args: MutableList<Any>): String {
        val term="%${query.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")}%"
        val literal=columns.joinToString(" OR ") { args+=term; "$it LIKE ? ESCAPE '\\'" }
        val prefix=prefix(query)
        val initials=if(prefix.isEmpty()) "" else {
            args+="$prefix%"
            " OR EXISTS (SELECT 1 FROM search_initials i WHERE i.initial LIKE ? AND (${columns.joinToString(" OR ") { "i.value=$it" }}))"
        }
        return "($literal$initials)"
    }
    fun ensure(db: LibraryDatabase) {
        while(!Thread.currentThread().isInterrupted) {
            val names=db.searchInitials().missing()
            if(names.isEmpty()) return
            db.searchInitials().insert(names.map { SearchInitial(it,key(it)) })
        }
    }
}
