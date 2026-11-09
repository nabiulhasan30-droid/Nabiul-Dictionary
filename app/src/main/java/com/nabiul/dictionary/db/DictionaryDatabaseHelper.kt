package com.nabiul.dictionary.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.nabiul.dictionary.model.DictionaryEntry
import com.nabiul.dictionary.model.DictionaryMode
import com.nabiul.dictionary.model.HistoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class DictionaryDatabaseHelper(private val context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "DictionaryDbHelper"
        const val DB_NAME = "dictionary.db"
        private const val DB_VERSION = 2

        @Volatile
        private var INSTANCE: DictionaryDatabaseHelper? = null

        fun getInstance(context: Context): DictionaryDatabaseHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DictionaryDatabaseHelper(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val dbFile: File
        get() = context.getDatabasePath(DB_NAME)

    suspend fun ensureDatabaseReady() = withContext(Dispatchers.IO) {
        val file = dbFile
        val assetLength = try {
            context.assets.open(DB_NAME).use { it.available().toLong() }
        } catch (e: Exception) {
            0L
        }

        // If file does not exist or size doesn't match the new expanded asset db
        if (!file.exists() || (assetLength > 0 && file.length() != assetLength)) {
            file.parentFile?.mkdirs()
            Log.d(TAG, "Copying updated dictionary.db from assets to ${file.absolutePath}")
            context.assets.open(DB_NAME).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "dictionary.db copy completed. Size: ${file.length()} bytes")
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Pre-packaged database
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    }

    private fun isBengaliText(text: String): Boolean {
        return text.any { it in '\u0980'..'\u09FF' }
    }

    suspend fun searchWords(query: String, requestedMode: DictionaryMode, limit: Int = 60): List<DictionaryEntry> = withContext(Dispatchers.IO) {
        ensureDatabaseReady()
        val results = mutableListOf<DictionaryEntry>()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext results

        val isQueryBengali = isBengaliText(trimmed)

        // Auto-detect mode if user typed in opposite script
        val effectiveMode = when {
            isQueryBengali && requestedMode == DictionaryMode.EN_TO_BN -> DictionaryMode.BN_TO_EN
            !isQueryBengali && requestedMode != DictionaryMode.EN_TO_BN -> DictionaryMode.EN_TO_BN
            else -> requestedMode
        }

        val db = readableDatabase
        val table = when (effectiveMode) {
            DictionaryMode.EN_TO_BN -> "en_to_bn"
            DictionaryMode.BN_TO_EN -> "bn_to_en"
            DictionaryMode.BN_TO_BN -> "bn_to_bn"
        }
        val meaningCol = if (effectiveMode == DictionaryMode.BN_TO_BN) "definition" else "meaning"

        // Step 1: Query exact and prefix matches
        val sql = """
            SELECT id, word, $meaningCol FROM $table 
            WHERE word LIKE ?
            ORDER BY 
                CASE 
                    WHEN LOWER(word) = LOWER(?) THEN 1 
                    WHEN LOWER(word) LIKE LOWER(?) || '%' THEN 2 
                    ELSE 3 
                END,
                LENGTH(word),
                word
            LIMIT ?
        """.trimIndent()

        val cursor: Cursor = db.rawQuery(sql, arrayOf("%$trimmed%", trimmed, trimmed, limit.toString()))
        val addedWords = mutableSetOf<String>()

        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow("id")
            val wordIdx = c.getColumnIndexOrThrow("word")
            val meaningIdx = c.getColumnIndexOrThrow(meaningCol)

            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val word = c.getString(wordIdx)
                val meaning = c.getString(meaningIdx)
                results.add(DictionaryEntry(id, word, meaning, effectiveMode))
                addedWords.add(word.lowercase())
            }
        }

        // Step 2: Smart Morphological Lemmatization (Stemming Fallback)
        if (results.size < 5) {
            val stemCandidates = generateStemCandidates(trimmed, isQueryBengali)
            for (stem in stemCandidates) {
                if (addedWords.contains(stem.lowercase())) continue

                val stemSql = "SELECT id, word, $meaningCol FROM $table WHERE word = ? COLLATE NOCASE LIMIT 2"
                val stemCursor = db.rawQuery(stemSql, arrayOf(stem))
                stemCursor.use { sc ->
                    val idIdx = sc.getColumnIndexOrThrow("id")
                    val wordIdx = sc.getColumnIndexOrThrow("word")
                    val meaningIdx = sc.getColumnIndexOrThrow(meaningCol)

                    while (sc.moveToNext()) {
                        val id = sc.getLong(idIdx)
                        val word = sc.getString(wordIdx)
                        val meaning = sc.getString(meaningIdx)
                        val displayWord = "$trimmed (মূল শব্দ: $word)"
                        results.add(DictionaryEntry(id, displayWord, meaning, effectiveMode))
                        addedWords.add(word.lowercase())
                    }
                }
            }
        }

        // Step 3: Deep definition search fallback if still empty
        if (results.isEmpty() && trimmed.length >= 3) {
            val defSql = "SELECT id, word, $meaningCol FROM $table WHERE $meaningCol LIKE ? LIMIT 15"
            val defCursor = db.rawQuery(defSql, arrayOf("%$trimmed%"))
            defCursor.use { dc ->
                val idIdx = dc.getColumnIndexOrThrow("id")
                val wordIdx = dc.getColumnIndexOrThrow("word")
                val meaningIdx = dc.getColumnIndexOrThrow(meaningCol)

                while (dc.moveToNext()) {
                    val id = dc.getLong(idIdx)
                    val word = dc.getString(wordIdx)
                    val meaning = dc.getString(meaningIdx)
                    if (!addedWords.contains(word.lowercase())) {
                        results.add(DictionaryEntry(id, word, meaning, effectiveMode))
                        addedWords.add(word.lowercase())
                    }
                }
            }
        }

        // Bookmark states
        val bookmarkedWords = getBookmarkedWordsSet(effectiveMode)
        for (entry in results) {
            if (bookmarkedWords.contains(entry.word)) {
                entry.isBookmarked = true
            }
        }

        results
    }

    private fun generateStemCandidates(query: String, isBengali: Boolean): List<String> {
        val list = mutableListOf<String>()
        val q = query.trim()
        if (isBengali) {
            val suffixes = listOf(
                "গুলোকে", "গুলোর", "গুলো", "গুলি", "গুলিকে", "গুলির",
                "দেরকে", "দের", "খানিক", "খানা", "খানি", "টিকে",
                "টার", "টির", "টি", "টা", "কে", "রে", "তে", "য়ে", "র", "এর",
                "ছিলেন", "ছেন", "তেন", "লেন", "বেন", "ছিল", "ছে", "বো", "তাম", "বে"
            )
            for (s in suffixes) {
                if (q.endsWith(s) && q.length > s.length + 1) {
                    list.add(q.removeSuffix(s))
                }
            }
        } else {
            val ql = q.lowercase()
            if (ql.endsWith("ing") && ql.length > 4) {
                list.add(ql.removeSuffix("ing"))
                list.add(ql.removeSuffix("ing") + "e")
                if (ql.length > 5 && ql[ql.length - 4] == ql[ql.length - 5]) {
                    list.add(ql.substring(0, ql.length - 4))
                }
            }
            if (ql.endsWith("ed") && ql.length > 3) {
                list.add(ql.removeSuffix("ed"))
                list.add(ql.removeSuffix("d"))
                if (ql.endsWith("ied") && ql.length > 4) {
                    list.add(ql.removeSuffix("ied") + "y")
                }
            }
            if (ql.endsWith("ies") && ql.length > 4) {
                list.add(ql.removeSuffix("ies") + "y")
            }
            if (ql.endsWith("es") && ql.length > 3) {
                list.add(ql.removeSuffix("es"))
            }
            if (ql.endsWith("s") && ql.length > 2) {
                list.add(ql.removeSuffix("s"))
            }
            if (ql.endsWith("ly") && ql.length > 3) {
                list.add(ql.removeSuffix("ly"))
                if (ql.endsWith("ily") && ql.length > 4) {
                    list.add(ql.removeSuffix("ily") + "y")
                }
            }
            if (ql.endsWith("ness") && ql.length > 5) {
                list.add(ql.removeSuffix("ness"))
            }
            if (ql.endsWith("able") && ql.length > 5) {
                list.add(ql.removeSuffix("able"))
                list.add(ql.removeSuffix("able") + "e")
            }
            if (ql.endsWith("ful") && ql.length > 4) {
                list.add(ql.removeSuffix("ful"))
            }
            if (ql.endsWith("less") && ql.length > 5) {
                list.add(ql.removeSuffix("less"))
            }
            if (ql.endsWith("er") && ql.length > 3) {
                list.add(ql.removeSuffix("er"))
            }
            if (ql.endsWith("est") && ql.length > 4) {
                list.add(ql.removeSuffix("est"))
            }
            val prefixes = listOf("un", "in", "im", "dis", "non", "re")
            for (p in prefixes) {
                if (ql.startsWith(p) && ql.length > p.length + 2) {
                    list.add(ql.removePrefix(p))
                }
            }
        }
        return list.distinct()
    }

    private fun getBookmarkedWordsSet(mode: DictionaryMode): Set<String> {
        val set = mutableSetOf<String>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT word FROM bookmarks WHERE mode = ?", arrayOf(mode.name))
        cursor.use { c ->
            val wordIdx = c.getColumnIndexOrThrow("word")
            while (c.moveToNext()) {
                set.add(c.getString(wordIdx))
            }
        }
        return set
    }

    suspend fun toggleBookmark(entry: DictionaryEntry): Boolean = withContext(Dispatchers.IO) {
        val db = writableDatabase
        if (entry.isBookmarked) {
            db.delete("bookmarks", "word = ? AND mode = ?", arrayOf(entry.word, entry.mode.name))
            entry.isBookmarked = false
            false
        } else {
            val cv = ContentValues().apply {
                put("word", entry.word)
                put("mode", entry.mode.name)
                put("meaning", entry.meaning)
                put("timestamp", System.currentTimeMillis())
            }
            db.insertWithOnConflict("bookmarks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            entry.isBookmarked = true
            true
        }
    }

    suspend fun getAllBookmarks(): List<DictionaryEntry> = withContext(Dispatchers.IO) {
        val results = mutableListOf<DictionaryEntry>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT id, word, mode, meaning FROM bookmarks ORDER BY timestamp DESC", null)
        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow("id")
            val wordIdx = c.getColumnIndexOrThrow("word")
            val modeIdx = c.getColumnIndexOrThrow("mode")
            val meaningIdx = c.getColumnIndexOrThrow("meaning")

            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val word = c.getString(wordIdx)
                val modeStr = c.getString(modeIdx)
                val meaning = c.getString(meaningIdx)
                val mode = try {
                    DictionaryMode.valueOf(modeStr)
                } catch (e: Exception) {
                    DictionaryMode.EN_TO_BN
                }
                results.add(DictionaryEntry(id, word, meaning, mode, isBookmarked = true))
            }
        }
        results
    }

    suspend fun addSearchHistory(word: String, mode: DictionaryMode) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("word", word)
            put("mode", mode.name)
            put("timestamp", System.currentTimeMillis())
        }
        db.insertWithOnConflict("history", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun getSearchHistory(limit: Int = 30): List<HistoryItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<HistoryItem>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT id, word, mode, timestamp FROM history ORDER BY timestamp DESC LIMIT ?", arrayOf(limit.toString()))
        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow("id")
            val wordIdx = c.getColumnIndexOrThrow("word")
            val modeIdx = c.getColumnIndexOrThrow("mode")
            val tsIdx = c.getColumnIndexOrThrow("timestamp")

            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val word = c.getString(wordIdx)
                val modeStr = c.getString(modeIdx)
                val timestamp = c.getLong(tsIdx)
                val mode = try {
                    DictionaryMode.valueOf(modeStr)
                } catch (e: Exception) {
                    DictionaryMode.EN_TO_BN
                }
                list.add(HistoryItem(id, word, mode, timestamp))
            }
        }
        list
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete("history", null, null)
    }
}
