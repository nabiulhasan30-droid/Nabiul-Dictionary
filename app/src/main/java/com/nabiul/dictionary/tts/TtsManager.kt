package com.nabiul.dictionary.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.nabiul.dictionary.model.DictionaryMode
import java.util.Locale

class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false

    companion object {
        private const val TAG = "TtsManager"
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            tts?.language = Locale.US
            Log.d(TAG, "TTS initialized successfully")
        } else {
            Log.e(TAG, "Failed to initialize TTS engine")
        }
    }

    fun speak(text: String, mode: DictionaryMode) {
        if (!isInitialized || tts == null) return

        val locale = when (mode) {
            DictionaryMode.EN_TO_BN -> Locale.US
            DictionaryMode.BN_TO_EN, DictionaryMode.BN_TO_BN -> Locale("bn", "BD")
        }

        val available = tts?.isLanguageAvailable(locale)
        if (available == TextToSpeech.LANG_AVAILABLE || available == TextToSpeech.LANG_COUNTRY_AVAILABLE) {
            tts?.language = locale
        } else if (mode != DictionaryMode.EN_TO_BN) {
            // Try Indian Bengali
            tts?.language = Locale("bn", "IN")
        } else {
            tts?.language = Locale.US
        }

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "DICTIONARY_WORD_SPEAK")
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TTS", e)
        }
    }
}
