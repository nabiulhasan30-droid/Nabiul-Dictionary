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
        private const val DB_VERSION = 1

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
        if (!file.exists() || file.length() == 0L) {
            file.parentFile?.mkdirs()
            Log.d(TAG, "Copying dictionary.db from assets to ${file.absolutePath}")
            context.assets.open(DB_NAME).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "dictionary.db copy completed. Size: ${file.length()} bytes")
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Tables are already created in pre-packaged database
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    }

    suspend fun searchWords(query: String, mode: DictionaryMode, limit: Int = 60): List<DictionaryEntry> = withContext(Dispatchers.IO) {
        ensureDatabaseReady()
        val results = mutableListOf<DictionaryEntry>()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext results

        val db = readableDatabase
        val table = when (mode) {
            DictionaryMode.EN_TO_BN -> "en_to_bn"
            DictionaryMode.BN_TO_EN -> "bn_to_en"
            DictionaryMode.BN_TO_BN -> "bn_to_bn"
        }

        val meaningCol = if (mode == DictionaryMode.BN_TO_BN) "definition" else "meaning"

        // Prioritize: 1. Exact match, 2. Prefix match (starts with query), 3. Substring match
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
        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow("id")
            val wordIdx = c.getColumnIndexOrThrow("word")
            val meaningIdx = c.getColumnIndexOrThrow(meaningCol)

            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val word = c.getString(wordIdx)
                val meaning = c.getString(meaningIdx)
                results.add(DictionaryEntry(id, word, meaning, mode))
            }
        }

        // Check bookmark states
        val bookmarkedWords = getBookmarkedWordsSet(mode)
        for (entry in results) {
            if (bookmarkedWords.contains(entry.word)) {
                entry.isBookmarked = true
            }
        }

        results
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
