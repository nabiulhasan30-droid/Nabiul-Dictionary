package com.nabiul.dictionary

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.nabiul.dictionary.databinding.ActivityMainBinding
import com.nabiul.dictionary.db.DictionaryDatabaseHelper
import com.nabiul.dictionary.model.DictionaryEntry
import com.nabiul.dictionary.model.DictionaryMode
import com.nabiul.dictionary.tts.TtsManager
import com.nabiul.dictionary.ui.DictionaryAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var dbHelper: DictionaryDatabaseHelper
    private lateinit var ttsManager: TtsManager
    private lateinit var adapter: DictionaryAdapter

    private var currentMode = DictionaryMode.EN_TO_BN
    private var searchJob: Job? = null
    private var isViewingBookmarks = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dbHelper = DictionaryDatabaseHelper.getInstance(this)
        ttsManager = TtsManager(this)

        setupRecyclerView()
        setupListeners()
        setupModeChips()

        // Prepare database on startup
        lifecycleScope.launch {
            binding.layoutLoading.visibility = View.VISIBLE
            dbHelper.ensureDatabaseReady()
            binding.layoutLoading.visibility = View.GONE
        }
    }

    private fun setupRecyclerView() {
        adapter = DictionaryAdapter(
            onSpeakClick = { entry ->
                val cleanWord = entry.word.substringBefore("(").trim()
                ttsManager.speak(cleanWord, entry.mode)
            },
            onBookmarkClick = { entry, pos ->
                lifecycleScope.launch {
                    val isAdded = dbHelper.toggleBookmark(entry)
                    adapter.notifyItemChanged(pos)
                    val msg = if (isAdded) getString(R.string.bookmark_added) else getString(R.string.bookmark_removed)
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    if (isViewingBookmarks && !isAdded) {
                        loadBookmarks()
                    }
                }
            },
            onCopyClick = { entry ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = "${entry.word}\n${entry.meaning}"
                val clip = ClipData.newPlainText("Dictionary Meaning", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@MainActivity, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        )

        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.adapter = adapter
    }

    private fun setupListeners() {
        binding.etSearch.addTextChangedListener { text ->
            val query = text?.toString()?.trim().orEmpty()
            binding.btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
            isViewingBookmarks = false

            searchJob?.cancel()
            if (query.isEmpty()) {
                adapter.updateList(emptyList())
                binding.rvResults.visibility = View.GONE
                binding.layoutEmptyState.visibility = View.VISIBLE
                binding.tvEmptyTitle.text = getString(R.string.search_guide)
                binding.tvEmptySubtitle.text = "সম্পূর্ণ অফলাইনে ইংরেজি থেকে বাংলা, বাংলা থেকে ইংরেজি এবং বাংলা থেকে বাংলা ২৪৪,০০০+ শব্দের বিশদ অর্থ জানুন।"
            } else {
                searchJob = lifecycleScope.launch {
                    delay(120) // Fast debounce
                    performSearch(query)
                }
            }
        }

        binding.btnClearSearch.setOnClickListener {
            binding.etSearch.text?.clear()
        }

        binding.btnViewBookmarks.setOnClickListener {
            loadBookmarks()
        }

        binding.btnViewHistory.setOnClickListener {
            showHistoryDialog()
        }
    }

    private fun setupModeChips() {
        binding.chipEnToBn.setOnClickListener { switchMode(DictionaryMode.EN_TO_BN) }
        binding.chipBnToEn.setOnClickListener { switchMode(DictionaryMode.BN_TO_EN) }
        binding.chipBnToBn.setOnClickListener { switchMode(DictionaryMode.BN_TO_BN) }
        updateChipStyles()
    }

    private fun switchMode(mode: DictionaryMode) {
        if (currentMode == mode) return
        currentMode = mode
        updateChipStyles()

        val hint = when (mode) {
            DictionaryMode.EN_TO_BN -> "ইংরেজি শব্দ লিখুন (যেমন: book, hope)..."
            DictionaryMode.BN_TO_EN -> "বাংলা শব্দ লিখুন (যেমন: বই, আশা)..."
            DictionaryMode.BN_TO_BN -> "বাংলা শব্দ লিখুন (বাংলা একাডেমি অর্থ)..."
        }
        binding.etSearch.hint = hint

        val currentQuery = binding.etSearch.text?.toString()?.trim().orEmpty()
        if (currentQuery.isNotEmpty()) {
            performSearch(currentQuery)
        }
    }

    private fun updateChipStyles() {
        val selectedBg = ContextCompat.getColor(this, R.color.chip_bg_selected)
        val selectedText = ContextCompat.getColor(this, R.color.chip_text_selected)
        val unselectedBg = ContextCompat.getColor(this, R.color.chip_bg_unselected)
        val unselectedText = ContextCompat.getColor(this, R.color.chip_text_unselected)

        fun applyStyle(button: com.google.android.material.button.MaterialButton, isSelected: Boolean) {
            button.setBackgroundColor(if (isSelected) selectedBg else unselectedBg)
            button.setTextColor(if (isSelected) selectedText else unselectedText)
            button.strokeWidth = if (isSelected) 0 else 1
        }

        applyStyle(binding.chipEnToBn, currentMode == DictionaryMode.EN_TO_BN)
        applyStyle(binding.chipBnToEn, currentMode == DictionaryMode.BN_TO_EN)
        applyStyle(binding.chipBnToBn, currentMode == DictionaryMode.BN_TO_BN)
    }

    private fun performSearch(query: String) {
        lifecycleScope.launch {
            val results = dbHelper.searchWords(query, currentMode)
            if (results.isNotEmpty()) {
                dbHelper.addSearchHistory(query, currentMode)
                adapter.updateList(results)
                binding.rvResults.visibility = View.VISIBLE
                binding.layoutEmptyState.visibility = View.GONE
            } else {
                adapter.updateList(emptyList())
                binding.rvResults.visibility = View.GONE
                binding.layoutEmptyState.visibility = View.VISIBLE
                binding.tvEmptyTitle.text = getString(R.string.no_results)
                binding.tvEmptySubtitle.text = "'$query' এর জন্য কোনো অর্থ খুঁজে পাওয়া যায়নি।"
            }
        }
    }

    private fun loadBookmarks() {
        lifecycleScope.launch {
            isViewingBookmarks = true
            val bookmarks = dbHelper.getAllBookmarks()
            if (bookmarks.isNotEmpty()) {
                adapter.updateList(bookmarks)
                binding.rvResults.visibility = View.VISIBLE
                binding.layoutEmptyState.visibility = View.GONE
                Toast.makeText(this@MainActivity, "সংরক্ষিত ${bookmarks.size} টি শব্দ লোড হয়েছে", Toast.LENGTH_SHORT).show()
            } else {
                adapter.updateList(emptyList())
                binding.rvResults.visibility = View.GONE
                binding.layoutEmptyState.visibility = View.VISIBLE
                binding.tvEmptyTitle.text = "কোনো বুকমার্ক নেই"
                binding.tvEmptySubtitle.text = "শব্দের পাশে বুকমার্ক আইকনে চাপ দিয়ে শব্দ সংরক্ষণ করুন।"
            }
        }
    }

    private fun showHistoryDialog() {
        lifecycleScope.launch {
            val history = dbHelper.getSearchHistory()
            if (history.isEmpty()) {
                Toast.makeText(this@MainActivity, "কোনো সাম্প্রতিক অনুসন্ধানের ইতিহাস নেই", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val items = history.map { "${it.word}  (${it.mode.badgeText})" }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.history_title)
                .setItems(items) { _, which ->
                    val selected = history[which]
                    currentMode = selected.mode
                    updateChipStyles()
                    binding.etSearch.setText(selected.word)
                }
                .setNegativeButton("বন্ধ করুন", null)
                .setPositiveButton(R.string.clear_history) { _, _ ->
                    lifecycleScope.launch {
                        dbHelper.clearHistory()
                        Toast.makeText(this@MainActivity, "ইতিহাস মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
    }
}
