package com.nabiul.dictionary.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.nabiul.dictionary.R
import com.nabiul.dictionary.databinding.ItemDictionaryWordBinding
import com.nabiul.dictionary.model.DictionaryEntry
import com.nabiul.dictionary.model.DictionaryMode

class DictionaryAdapter(
    private var entries: List<DictionaryEntry> = emptyList(),
    private val onSpeakClick: (DictionaryEntry) -> Unit,
    private val onBookmarkClick: (DictionaryEntry, Int) -> Unit,
    private val onCopyClick: (DictionaryEntry) -> Unit
) : RecyclerView.Adapter<DictionaryAdapter.WordViewHolder>() {

    fun updateList(newList: List<DictionaryEntry>) {
        entries = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WordViewHolder {
        val binding = ItemDictionaryWordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return WordViewHolder(binding)
    }

    override fun onBindViewHolder(holder: WordViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

    inner class WordViewHolder(private val binding: ItemDictionaryWordBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: DictionaryEntry) {
            binding.tvWord.text = entry.word
            binding.tvMeaning.text = entry.meaning

            binding.tvModeBadge.text = entry.mode.badgeText
            val badgeColor = when (entry.mode) {
                DictionaryMode.EN_TO_BN -> R.color.badge_en
                DictionaryMode.BN_TO_EN -> R.color.badge_bn
                DictionaryMode.BN_TO_BN -> R.color.badge_bnbn
            }
            binding.tvModeBadge.backgroundTintList = ContextCompat.getColorStateList(binding.root.context, badgeColor)

            updateBookmarkIcon(entry.isBookmarked)

            binding.btnSpeak.setOnClickListener {
                onSpeakClick(entry)
            }

            binding.btnBookmark.setOnClickListener {
                onBookmarkClick(entry, adapterPosition)
            }

            binding.btnCopy.setOnClickListener {
                onCopyClick(entry)
            }
        }

        fun updateBookmarkIcon(isBookmarked: Boolean) {
            val iconRes = if (isBookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark
            val tintColor = if (isBookmarked) R.color.accent else R.color.text_secondary
            binding.btnBookmark.setImageResource(iconRes)
            binding.btnBookmark.setColorFilter(ContextCompat.getColor(binding.root.context, tintColor))
        }
    }
}
