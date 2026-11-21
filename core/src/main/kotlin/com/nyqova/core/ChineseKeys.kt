package com.nyqova.core

/** Character-level OpenCC keys for lookup only; user-visible titles are left untouched. */
object ChineseKeys {
    private fun dictionary(name: String): Map<String, String> = ChineseKeys::class.java.classLoader
        .getResourceAsStream("opencc/$name.txt")?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.mapNotNull { row ->
                val parts = row.split('\t', limit = 2)
                if(parts.size == 2) parts[0] to parts[1].substringBefore(' ') else null
            }.toMap()
        } ?: emptyMap()
    private val simplified by lazy { dictionary("TSCharacters") }
    private val traditional by lazy { dictionary("STCharacters") }
    fun simplified(value: String) = value.map { simplified[it.toString()] ?: it.toString() }.joinToString("")
    fun traditional(value: String) = value.map { traditional[it.toString()] ?: it.toString() }.joinToString("")
}
