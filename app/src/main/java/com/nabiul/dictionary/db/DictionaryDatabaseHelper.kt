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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

class DictionaryDatabaseHelper(private val context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "DictionaryDbHelper"
        const val DB_NAME = "dictionary.db"
        private const val ASSET_DB_GZ = "dictionary.db.gz"
        private const val DB_VERSION = 3
        private const val ASSET_DB_VERSION = 3
        private const val PREFS_NAME = "nabiul_dictionary_prefs"
        private const val KEY_ASSET_VERSION = "asset_db_version"

        @Volatile
        private var INSTANCE: DictionaryDatabaseHelper? = null

        fun getInstance(context: Context): DictionaryDatabaseHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DictionaryDatabaseHelper(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val dbInitMutex = Mutex()

    private data class BookmarkBackup(val word: String, val mode: String, val meaning: String, val timestamp: Long)
    private data class HistoryBackup(val word: String, val mode: String, val timestamp: Long)

    private val dbFile: File
        get() = context.getDatabasePath(DB_NAME)

    suspend fun ensureDatabaseReady() = withContext(Dispatchers.IO) {
        dbInitMutex.withLock {
            val file = dbFile
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentAssetVer = prefs.getInt(KEY_ASSET_VERSION, 0)

            if (file.exists() && file.length() > 0L && currentAssetVer == ASSET_DB_VERSION) {
                return@withLock
            }

            // Preserve any existing user bookmarks and search history before upgrading DB
            val savedBookmarks = mutableListOf<BookmarkBackup>()
            val savedHistory = mutableListOf<HistoryBackup>()
            if (file.exists() && file.length() > 0L) {
                var oldDb: SQLiteDatabase? = null
                try {
                    oldDb = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                    oldDb.rawQuery("SELECT word, mode, meaning, timestamp FROM bookmarks", null).use { c ->
                        val wIdx = c.getColumnIndexOrThrow("word")
                        val mIdx = c.getColumnIndexOrThrow("mode")
                        val mnIdx = c.getColumnIndexOrThrow("meaning")
                        val tsIdx = c.getColumnIndexOrThrow("timestamp")
                        while (c.moveToNext()) {
                            savedBookmarks.add(
                                BookmarkBackup(
                                    c.getString(wIdx),
                                    c.getString(mIdx),
                                    c.getString(mnIdx),
                                    c.getLong(tsIdx)
                                )
                            )
                        }
                    }
                    oldDb.rawQuery("SELECT word, mode, timestamp FROM history", null).use { c ->
                        val wIdx = c.getColumnIndexOrThrow("word")
                        val mIdx = c.getColumnIndexOrThrow("mode")
                        val tsIdx = c.getColumnIndexOrThrow("timestamp")
                        while (c.moveToNext()) {
                            savedHistory.add(
                                HistoryBackup(
                                    c.getString(wIdx),
                                    c.getString(mIdx),
                                    c.getLong(tsIdx)
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping backup from previous DB: ${e.message}")
                } finally {
                    try {
                        oldDb?.close()
                    } catch (_: Exception) {
                    }
                }
            }

            try {
                close()
            } catch (_: Exception) {
            }

            file.parentFile?.mkdirs()
            val tempFile = File(file.parentFile, "$DB_NAME.tmp")
            if (tempFile.exists()) tempFile.delete()

            // Remove stale journal/wal/shm files before replacement
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                val sidecar = File(file.absolutePath + suffix)
                if (sidecar.exists()) sidecar.delete()
            }

            Log.d(TAG, "Extracting compressed dictionary database to ${file.absolutePath}")
            val hasGzAsset = try {
                context.assets.open(ASSET_DB_GZ).close()
                true
            } catch (_: Exception) {
                false
            }

            if (hasGzAsset) {
                context.assets.open(ASSET_DB_GZ).use { rawIn ->
                    GZIPInputStream(BufferedInputStream(rawIn, 65536), 65536).use { gzIn ->
                        FileOutputStream(tempFile).use { out ->
                            val buf = ByteArray(65536)
                            while (true) {
                                val read = gzIn.read(buf)
                                if (read == -1) break
                                out.write(buf, 0, read)
                            }
                            out.fd.sync()
                        }
                    }
                }
            } else {
                context.assets.open(DB_NAME).use { input ->
                    FileOutputStream(tempFile).use { out ->
                        input.copyTo(out, 65536)
                        out.fd.sync()
                    }
                }
            }

            if (file.exists()) file.delete()
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }

            // Restore user bookmarks & history if any existed
            if (savedBookmarks.isNotEmpty() || savedHistory.isNotEmpty()) {
                try {
                    val newDb = writableDatabase
                    newDb.beginTransaction()
                    try {
                        for (b in savedBookmarks) {
                            val cv = ContentValues().apply {
                                put("word", b.word)
                                put("mode", b.mode)
                                put("meaning", b.meaning)
                                put("timestamp", b.timestamp)
                            }
                            newDb.insert("bookmarks", null, cv)
                        }
                        for (h in savedHistory) {
                            val cv = ContentValues().apply {
                                put("word", h.word)
                                put("mode", h.mode)
                                put("timestamp", h.timestamp)
                            }
                            newDb.insert("history", null, cv)
                        }
                        newDb.setTransactionSuccessful()
                    } finally {
                        newDb.endTransaction()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not restore bookmarks/history: ${e.message}")
                }
            }

            prefs.edit().putInt(KEY_ASSET_VERSION, ASSET_DB_VERSION).apply()
            Log.d(TAG, "dictionary.db ready. Size: ${file.length()} bytes")
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Pre-packaged database
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Handled via ASSET_DB_VERSION extraction in ensureDatabaseReady()
    }

    private fun isBengaliText(text: String): Boolean {
        return text.any { it in '\u0980'..'\u09FF' }
    }

    private fun buildPrefixUpperBound(prefix: String): String? {
        if (prefix.isEmpty()) return null
        val last = prefix.last()
        if (last.isSurrogate() || last >= '\uFFFE') return null
        return prefix.dropLast(1) + Char(last.code + 1)
    }

    private fun tableAndColFor(mode: DictionaryMode): Pair<String, String> {
        return when (mode) {
            DictionaryMode.EN_TO_BN -> "en_to_bn" to "meaning"
            DictionaryMode.BN_TO_EN -> "bn_to_en" to "meaning"
            DictionaryMode.BN_TO_BN -> "bn_to_bn" to "definition"
        }
    }

    private fun queryExactAndPrefix(
        db: SQLiteDatabase,
        mode: DictionaryMode,
        query: String,
        limit: Int,
        results: MutableList<DictionaryEntry>,
        addedWords: MutableSet<String>
    ) {
        val (table, meaningCol) = tableAndColFor(mode)

        // Tier 1: Exact match via B-Tree index (<1ms)
        val exactSql = if (mode == DictionaryMode.EN_TO_BN) {
            "SELECT id, word, $meaningCol FROM $table WHERE word = ? COLLATE NOCASE LIMIT 10"
        } else {
            "SELECT id, word, $meaningCol FROM $table WHERE word = ? LIMIT 10"
        }
        db.rawQuery(exactSql, arrayOf(query)).use { c ->
            readCursorIntoResults(c, meaningCol, mode, results, addedWords, labelPrefix = null)
        }

        if (results.size >= limit) return

        // Tier 2: Index-backed Prefix Range Scan (<2ms even on 800k+ rows)
        val prefixKey = if (mode == DictionaryMode.EN_TO_BN) query.lowercase() else query
        val upper = buildPrefixUpperBound(prefixKey)
        val remaining = limit - results.size
        if (upper != null) {
            val prefixSql = if (query.length >= 3) {
                "SELECT id, word, $meaningCol FROM $table WHERE word >= ? AND word < ? ORDER BY LENGTH(word), word LIMIT ?"
            } else {
                "SELECT id, word, $meaningCol FROM $table WHERE word >= ? AND word < ? ORDER BY word LIMIT ?"
            }
            val fetchLimit = if (query.length < 3) maxOf(remaining, 120) else remaining + 10
            val temp = mutableListOf<DictionaryEntry>()
            db.rawQuery(prefixSql, arrayOf(prefixKey, upper, fetchLimit.toString())).use { c ->
                val idIdx = c.getColumnIndexOrThrow("id")
                val wordIdx = c.getColumnIndexOrThrow("word")
                val meaningIdx = c.getColumnIndexOrThrow(meaningCol)
                while (c.moveToNext()) {
                    val word = c.getString(wordIdx)
                    if (addedWords.add(word.lowercase())) {
                        temp.add(DictionaryEntry(c.getLong(idIdx), word, c.getString(meaningIdx), mode))
                    }
                }
            }
            if (query.length < 3 && temp.size > 1) {
                temp.sortWith(compareBy<DictionaryEntry> { it.word.length }.thenBy { it.word })
            }
            for (item in temp) {
                if (results.size >= limit) break
                results.add(item)
            }
        } else {
            val likeSql = "SELECT id, word, $meaningCol FROM $table WHERE word LIKE ? LIMIT ?"
            db.rawQuery(likeSql, arrayOf("$query%", (remaining + 5).toString())).use { c ->
                readCursorIntoResults(c, meaningCol, mode, results, addedWords, labelPrefix = null)
            }
        }
    }

    private fun readCursorIntoResults(
        cursor: Cursor,
        meaningCol: String,
        mode: DictionaryMode,
        results: MutableList<DictionaryEntry>,
        addedWords: MutableSet<String>,
        labelPrefix: String?
    ) {
        val idIdx = cursor.getColumnIndexOrThrow("id")
        val wordIdx = cursor.getColumnIndexOrThrow("word")
        val meaningIdx = cursor.getColumnIndexOrThrow(meaningCol)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idIdx)
            val word = cursor.getString(wordIdx)
            val meaning = cursor.getString(meaningIdx)
            if (addedWords.add(word.lowercase())) {
                val displayWord = if (labelPrefix != null) "$labelPrefix ($word)" else word
                results.add(DictionaryEntry(id, displayWord, meaning, mode))
            }
        }
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
        val addedWords = mutableSetOf<String>()

        // Tier 1 & 2: Exact + Index-backed Prefix search
        queryExactAndPrefix(db, effectiveMode, trimmed, limit, results, addedWords)

        val (table, meaningCol) = tableAndColFor(effectiveMode)

        // Tier 3: Smart Morphological Lemmatization, Spelling Variants & Compound Splitting
        if (results.size < 10) {
            val stemCandidates = generateStemCandidates(trimmed, isQueryBengali)
            for (stem in stemCandidates) {
                if (results.size >= limit) break
                if (addedWords.contains(stem.lowercase())) continue

                val stemSql = if (effectiveMode == DictionaryMode.EN_TO_BN) {
                    "SELECT id, word, $meaningCol FROM $table WHERE word = ? COLLATE NOCASE LIMIT 2"
                } else {
                    "SELECT id, word, $meaningCol FROM $table WHERE word = ? LIMIT 2"
                }
                db.rawQuery(stemSql, arrayOf(stem)).use { sc ->
                    val idIdx = sc.getColumnIndexOrThrow("id")
                    val wordIdx = sc.getColumnIndexOrThrow("word")
                    val meaningIdx = sc.getColumnIndexOrThrow(meaningCol)
                    while (sc.moveToNext()) {
                        val word = sc.getString(wordIdx)
                        if (addedWords.add(word.lowercase())) {
                            val displayWord = "$trimmed (মূল শব্দ: $word)"
                            results.add(DictionaryEntry(sc.getLong(idIdx), displayWord, sc.getString(meaningIdx), effectiveMode))
                        }
                    }
                }
            }
        }

        // Tier 4: Cross-mode Bengali fallback (if BN_TO_BN misses, check BN_TO_EN and vice-versa)
        if (results.isEmpty() && isQueryBengali) {
            val altMode = if (effectiveMode == DictionaryMode.BN_TO_BN) DictionaryMode.BN_TO_EN else DictionaryMode.BN_TO_BN
            queryExactAndPrefix(db, altMode, trimmed, 25, results, addedWords)
            if (results.isEmpty()) {
                val (altTable, altMeaningCol) = tableAndColFor(altMode)
                for (stem in generateStemCandidates(trimmed, true)) {
                    if (addedWords.contains(stem.lowercase())) continue
                    db.rawQuery("SELECT id, word, $altMeaningCol FROM $altTable WHERE word = ? LIMIT 2", arrayOf(stem)).use { sc ->
                        val idIdx = sc.getColumnIndexOrThrow("id")
                        val wordIdx = sc.getColumnIndexOrThrow("word")
                        val meaningIdx = sc.getColumnIndexOrThrow(altMeaningCol)
                        while (sc.moveToNext()) {
                            val word = sc.getString(wordIdx)
                            if (addedWords.add(word.lowercase())) {
                                results.add(DictionaryEntry(sc.getLong(idIdx), "$trimmed (মূল শব্দ: $word)", sc.getString(meaningIdx), altMode))
                            }
                        }
                    }
                }
            }
        }

        // Tier 5: Substring word search fallback (only if still very few results)
        if (results.size < 3 && trimmed.length >= 3) {
            val subSql = "SELECT id, word, $meaningCol FROM $table WHERE word LIKE ? LIMIT 20"
            db.rawQuery(subSql, arrayOf("%$trimmed%")).use { sc ->
                readCursorIntoResults(sc, meaningCol, effectiveMode, results, addedWords, labelPrefix = null)
            }
        }

        // Tier 6: Fast Prefix-Bucket Fuzzy Spelling Correction (catches typos!)
        if (results.isEmpty() && trimmed.length >= 3) {
            val fuzzyHits = findFuzzyMatches(db, effectiveMode, trimmed, maxResults = 10)
            for (entry in fuzzyHits) {
                if (addedWords.add(entry.word.lowercase())) {
                    results.add(entry)
                }
            }
        }

        // Tier 7: Deep definition search fallback if still empty
        if (results.isEmpty() && trimmed.length >= 3) {
            val defSql = "SELECT id, word, $meaningCol FROM $table WHERE $meaningCol LIKE ? LIMIT 15"
            db.rawQuery(defSql, arrayOf("%$trimmed%")).use { dc ->
                readCursorIntoResults(dc, meaningCol, effectiveMode, results, addedWords, labelPrefix = null)
            }
        }

        // Bookmark states (check per actual entry mode so cross-mode fallbacks match too)
        if (results.isNotEmpty()) {
            val bookmarkSets = results.map { it.mode }.distinct().associateWith { getBookmarkedWordsSet(it) }
            for (entry in results) {
                if (bookmarkSets[entry.mode]?.contains(entry.word) == true) {
                    entry.isBookmarked = true
                }
            }
        }

        results
    }

    private fun findFuzzyMatches(
        db: SQLiteDatabase,
        mode: DictionaryMode,
        query: String,
        maxResults: Int
    ): List<DictionaryEntry> {
        val (table, meaningCol) = tableAndColFor(mode)
        val qNorm = if (mode == DictionaryMode.EN_TO_BN) query.lowercase() else query
        val maxDist = if (qNorm.length >= 6) 2 else 1
        val minL = maxOf(2, qNorm.length - maxDist).toString()
        val maxL = (qNorm.length + maxDist).toString()

        val scored = mutableListOf<Pair<Int, DictionaryEntry>>()
        val seenWords = mutableSetOf<String>()

        for (prefixLen in listOf(3, 2, 1)) {
            if (qNorm.length < prefixLen) continue
            val pref = qNorm.take(prefixLen)
            val upper = buildPrefixUpperBound(pref) ?: continue
            val sql = "SELECT id, word, $meaningCol FROM $table WHERE word >= ? AND word < ? AND LENGTH(word) BETWEEN ? AND ? LIMIT 400"
            db.rawQuery(sql, arrayOf(pref, upper, minL, maxL)).use { c ->
                val idIdx = c.getColumnIndexOrThrow("id")
                val wordIdx = c.getColumnIndexOrThrow("word")
                val mIdx = c.getColumnIndexOrThrow(meaningCol)
                while (c.moveToNext()) {
                    val word = c.getString(wordIdx)
                    val wNorm = if (mode == DictionaryMode.EN_TO_BN) word.lowercase() else word
                    if (!seenWords.add(wNorm)) continue
                    val d = levenshteinBounded(qNorm, wNorm, maxDist)
                    if (d in 1..maxDist) {
                        scored.add(d to DictionaryEntry(c.getLong(idIdx), "$word (সম্ভাব্য বানান)", c.getString(mIdx), mode))
                    }
                }
            }
            if (scored.isNotEmpty()) break
        }

        if (scored.isEmpty()) return emptyList()
        scored.sortWith(compareBy<Pair<Int, DictionaryEntry>> { it.first }.thenBy { it.second.word.length })
        return scored.take(maxResults).map { it.second }
    }

    private fun levenshteinBounded(a: String, b: String, maxDist: Int): Int {
        val n = a.length
        val m = b.length
        if (kotlin.math.abs(n - m) > maxDist) return maxDist + 1
        var prev = IntArray(m + 1) { it }
        var curr = IntArray(m + 1)
        for (i in 1..n) {
            curr[0] = i
            var rowMin = curr[0]
            val ca = a[i - 1]
            for (j in 1..m) {
                val cost = if (ca == b[j - 1]) 0 else 1
                val v = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost
                )
                curr[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > maxDist) return maxDist + 1
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[m]
    }

    private fun generateStemCandidates(query: String, isBengali: Boolean): List<String> {
        val list = mutableListOf<String>()
        val q = query.trim()
        if (isBengali) {
            val suffixes = listOf(
                "গুলোকেও", "গুলোরই", "গুলোকে", "গুলোর", "গুলো", "গুলিকে", "গুলির", "গুলি",
                "দেরকেও", "দেরকে", "দের", "সমূহের", "সমূহ", "খানিক", "খানা", "খানি",
                "টিকে", "টাকে", "টার", "টির", "টি", "টা", "কে", "রে", "তে", "য়ে", "য়", "র", "এর",
                "ছিলেন", "ছেন", "তেন", "লেন", "বেন", "ছিল", "ছে", "বো", "তাম", "বে", "ছি", "ছ"
            )
            for (s in suffixes) {
                if (q.endsWith(s) && q.length > s.length + 1) {
                    list.add(q.removeSuffix(s))
                }
            }
            // Common Bengali spelling normalizations
            if (q.contains('ী')) list.add(q.replace('ী', 'ি'))
            if (q.contains('ি')) list.add(q.replace('ি', 'ী'))
            if (q.contains('ূ')) list.add(q.replace('ূ', 'ু'))
            if (q.contains('ু')) list.add(q.replace('ু', 'ূ'))
            if (q.contains('ং')) list.add(q.replace('ং', 'ঙ'))
            if (q.contains('ঙ')) list.add(q.replace('ঙ', 'ং'))
        } else {
            val ql = q.lowercase()
            // Irregular common lemmas
            val irregulars = mapOf(
                "went" to "go", "gone" to "go", "ran" to "run", "children" to "child",
                "mice" to "mouse", "feet" to "foot", "teeth" to "tooth", "geese" to "goose",
                "men" to "man", "women" to "woman", "better" to "good", "best" to "good",
                "worse" to "bad", "worst" to "bad", "bought" to "buy", "brought" to "bring",
                "caught" to "catch", "taught" to "teach", "thought" to "think", "fought" to "fight",
                "sought" to "seek", "wrote" to "write", "written" to "write", "spoke" to "speak",
                "spoken" to "speak", "broke" to "break", "broken" to "break", "chose" to "choose",
                "chosen" to "choose", "drove" to "drive", "driven" to "drive", "ate" to "eat",
                "eaten" to "eat", "fell" to "fall", "fallen" to "fall", "forgot" to "forget",
                "forgotten" to "forget", "forgave" to "forgive", "forgiven" to "forgive",
                "froze" to "freeze", "frozen" to "freeze", "gave" to "give", "given" to "give",
                "grew" to "grow", "grown" to "grow", "hid" to "hide", "hidden" to "hide",
                "knew" to "know", "known" to "know", "rode" to "ride", "ridden" to "ride",
                "rose" to "rise", "risen" to "rise", "saw" to "see", "seen" to "see",
                "shook" to "shake", "shaken" to "shake", "showed" to "show", "shown" to "show",
                "sang" to "sing", "sung" to "sing", "sank" to "sink", "sunk" to "sink",
                "slept" to "sleep", "kept" to "keep", "felt" to "feel", "left" to "leave",
                "lost" to "lose", "met" to "meet", "paid" to "pay", "said" to "say",
                "sold" to "sell", "sent" to "send", "spent" to "spend", "stood" to "stand",
                "understood" to "understand", "told" to "tell", "won" to "win", "wore" to "wear",
                "worn" to "wear", "tore" to "tear", "torn" to "tear", "swore" to "swear",
                "sworn" to "swear", "took" to "take", "taken" to "take", "did" to "do",
                "done" to "do", "was" to "be", "were" to "be", "been" to "be", "am" to "be"
            )
            irregulars[ql]?.let { list.add(it) }

            // UK <-> US spelling variants
            if (ql.contains("our")) list.add(ql.replace("our", "or"))
            if (ql.contains("ise")) list.add(ql.replace("ise", "ize"))
            if (ql.contains("ize")) list.add(ql.replace("ize", "ise"))
            if (ql.endsWith("re") && ql.length > 4) list.add(ql.dropLast(2) + "er")
            if (ql.contains("ae")) list.add(ql.replace("ae", "e"))
            if (ql.contains("oe")) list.add(ql.replace("oe", "e"))
            if (ql.contains("ll")) list.add(ql.replace("ll", "l"))

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
                if (ql.length > 4 && ql[ql.length - 3] == ql[ql.length - 4]) {
                    list.add(ql.substring(0, ql.length - 3))
                }
            }
            if (ql.endsWith("ies") && ql.length > 4) {
                list.add(ql.removeSuffix("ies") + "y")
            }
            if (ql.endsWith("ves") && ql.length > 4) {
                list.add(ql.removeSuffix("ves") + "f")
                list.add(ql.removeSuffix("ves") + "fe")
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
                if (ql.endsWith("ally") && ql.length > 5) {
                    list.add(ql.removeSuffix("ally"))
                    list.add(ql.removeSuffix("ly"))
                }
            }
            if (ql.endsWith("ness") && ql.length > 5) {
                list.add(ql.removeSuffix("ness"))
                if (ql.endsWith("iness") && ql.length > 6) {
                    list.add(ql.removeSuffix("iness") + "y")
                }
            }
            if (ql.endsWith("ment") && ql.length > 5) list.add(ql.removeSuffix("ment"))
            if (ql.endsWith("ation") && ql.length > 6) {
                list.add(ql.removeSuffix("ation") + "e")
                list.add(ql.removeSuffix("ation"))
            }
            if (ql.endsWith("tion") && ql.length > 5) list.add(ql.removeSuffix("tion") + "t")
            if (ql.endsWith("ity") && ql.length > 5) {
                list.add(ql.removeSuffix("ity"))
                list.add(ql.removeSuffix("ity") + "e")
            }
            if (ql.endsWith("able") && ql.length > 5) {
                list.add(ql.removeSuffix("able"))
                list.add(ql.removeSuffix("able") + "e")
            }
            if (ql.endsWith("ible") && ql.length > 5) {
                list.add(ql.removeSuffix("ible"))
                list.add(ql.removeSuffix("ible") + "e")
            }
            if (ql.endsWith("ful") && ql.length > 4) list.add(ql.removeSuffix("ful"))
            if (ql.endsWith("less") && ql.length > 5) list.add(ql.removeSuffix("less"))
            if (ql.endsWith("er") && ql.length > 3) {
                list.add(ql.removeSuffix("er"))
                list.add(ql.removeSuffix("r"))
                if (ql.endsWith("ier") && ql.length > 4) list.add(ql.removeSuffix("ier") + "y")
            }
            if (ql.endsWith("est") && ql.length > 4) {
                list.add(ql.removeSuffix("est"))
                list.add(ql.removeSuffix("st"))
                if (ql.endsWith("iest") && ql.length > 5) list.add(ql.removeSuffix("iest") + "y")
            }
            val prefixes = listOf("un", "in", "im", "il", "ir", "dis", "non", "re", "mis", "pre", "over", "under", "sub", "anti", "super", "multi", "semi")
            for (p in prefixes) {
                if (ql.startsWith(p) && ql.length > p.length + 2) {
                    list.add(ql.removePrefix(p))
                }
            }
            // Hyphen/space compound normalization & component lookup
            if (ql.contains('-')) {
                list.add(ql.replace('-', ' '))
                list.add(ql.replace("-", ""))
                for (part in ql.split('-')) {
                    if (part.length >= 3) list.add(part)
                }
            } else if (ql.contains(' ')) {
                list.add(ql.replace(' ', '-'))
                for (part in ql.split(' ')) {
                    if (part.length >= 3) list.add(part)
                }
            }
        }
        return list.distinct()
    }

    private fun getBookmarkedWordsSet(mode: DictionaryMode): Set<String> {
        val set = mutableSetOf<String>()
        val db = readableDatabase
        db.rawQuery("SELECT word FROM bookmarks WHERE mode = ?", arrayOf(mode.name)).use { c ->
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
            db.delete("bookmarks", "word = ? AND mode = ?", arrayOf(entry.word, entry.mode.name))
            val cv = ContentValues().apply {
                put("word", entry.word)
                put("mode", entry.mode.name)
                put("meaning", entry.meaning)
                put("timestamp", System.currentTimeMillis())
            }
            db.insert("bookmarks", null, cv)
            entry.isBookmarked = true
            true
        }
    }

    suspend fun getAllBookmarks(): List<DictionaryEntry> = withContext(Dispatchers.IO) {
        val results = mutableListOf<DictionaryEntry>()
        val db = readableDatabase
        db.rawQuery("SELECT id, word, mode, meaning FROM bookmarks ORDER BY timestamp DESC", null).use { c ->
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
                } catch (_: Exception) {
                    DictionaryMode.EN_TO_BN
                }
                results.add(DictionaryEntry(id, word, meaning, mode, isBookmarked = true))
            }
        }
        results
    }

    suspend fun addSearchHistory(word: String, mode: DictionaryMode) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete("history", "word = ? AND mode = ?", arrayOf(word, mode.name))
        val cv = ContentValues().apply {
            put("word", word)
            put("mode", mode.name)
            put("timestamp", System.currentTimeMillis())
        }
        db.insert("history", null, cv)
    }

    suspend fun getSearchHistory(limit: Int = 30): List<HistoryItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<HistoryItem>()
        val db = readableDatabase
        db.rawQuery("SELECT id, word, mode, timestamp FROM history ORDER BY timestamp DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
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
                } catch (_: Exception) {
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
