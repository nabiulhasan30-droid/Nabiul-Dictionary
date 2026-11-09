package com.nabiul.dictionary.model

enum class DictionaryMode(val title: String, val badgeText: String) {
    EN_TO_BN("English to Bengali", "EN ➔ BN"),
    BN_TO_EN("Bengali to English", "BN ➔ EN"),
    BN_TO_BN("Bengali to Bengali", "BN ➔ BN")
}

data class DictionaryEntry(
    val id: Long,
    val word: String,
    val meaning: String,
    val mode: DictionaryMode,
    var isBookmarked: Boolean = false
)

data class HistoryItem(
    val id: Long,
    val word: String,
    val mode: DictionaryMode,
    val timestamp: Long
)
