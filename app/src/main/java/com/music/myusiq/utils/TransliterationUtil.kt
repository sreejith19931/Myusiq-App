package com.music.myusiq.utils

import android.icu.text.Transliterator
import android.os.Build
import android.util.Log

/**
 * Native Offline Transliteration Utility using Android ICU (API 29+).
 * Natively transliterates scripts (Devanagari, Kanji/Kana, Hanzi, Cyrillic, Korean, Arabic, etc.)
 * to Romanized Latin characters completely offline.
 */
object TransliterationUtil {

    private const val TAG = "TransliterationUtil"
    private const val TRANSLITERATOR_ID = "Any-Latin; Latin-ASCII"

    private val transliterator: Transliterator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Transliterator.getInstance(TRANSLITERATOR_ID)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Transliterator with ID: $TRANSLITERATOR_ID", e)
                null
            }
        } else {
            null
        }
    }

    /**
     * Checks if native ICU transliteration is supported on the device (API 29+).
     */
    fun isAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && transliterator != null
    }

    /**
     * Transliterates given input text from any script (Devanagari, Kanji/Kana, Hanzi, Cyrillic, Korean, Arabic, etc.)
     * to Romanized Latin characters completely offline.
     *
     * @param text Raw text in any script.
     * @return Romanized Latin string, or original text if transliteration is unavailable or fails.
     */
    fun transliterate(text: String): String {
        if (text.isBlank()) return text
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return try {
                transliterator?.transliterate(text) ?: text
            } catch (e: Exception) {
                Log.e(TAG, "Error transliterating text: $text", e)
                text
            }
        }
        return text
    }

    /**
     * Transliterates text using a custom ICU Transliterator ID (e.g. "Devanagari-Latin", "Han-Latin").
     */
    fun transliterateWithCustomId(text: String, transformId: String): String {
        if (text.isBlank()) return text
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return try {
                val customTransliterator = Transliterator.getInstance(transformId)
                customTransliterator.transliterate(text)
            } catch (e: Exception) {
                Log.e(TAG, "Error transliterating text with custom ID $transformId: $text", e)
                text
            }
        }
        return text
    }

    /**
     * Automatically transliterates raw lyrics (both LRC timestamped and plain text).
     * Replaces non-Latin script lines (Devanagari, Kanji/Kana, Hanzi, Cyrillic, Korean, Arabic, etc.)
     * with their Romanized Latin transliterated equivalent.
     * English or already-Latin lyrics remain untouched.
     */
    fun transliterateLyrics(rawLyrics: String): String {
        if (rawLyrics.isBlank() || !isAvailable()) return rawLyrics

        val lrcRegex = Regex("""^(\[\d{2}:\d{2}\.\d{2,3}])(.*)$""")
        val sb = StringBuilder()

        rawLyrics.lines().forEachIndexed { index, line ->
            if (index > 0) sb.append("\n")

            val trimmedLine = line.trim()
            val lrcMatch = lrcRegex.find(trimmedLine)

            if (lrcMatch != null) {
                val timestamp = lrcMatch.groupValues[1]
                val content = lrcMatch.groupValues[2].trim()
                if (content.isNotBlank()) {
                    val transliterated = transliterate(content)
                    sb.append("$timestamp$transliterated")
                } else {
                    sb.append(line)
                }
            } else if (trimmedLine.isNotBlank()) {
                val transliterated = transliterate(trimmedLine)
                sb.append(transliterated)
            } else {
                sb.append(line)
            }
        }

        return sb.toString()
    }
}
