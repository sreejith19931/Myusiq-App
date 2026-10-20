package com.music.myusiq.utils

object AlbumUtils {
    private val moviePattern = Regex("(?i)\\(from\\s+([^)]+)\\)")

    fun getGroupingKey(albumTag: String?): String {
        if (albumTag.isNullOrBlank()) return "Unknown Album"
        val match = moviePattern.find(albumTag)
        val rawKey = if (match != null) {
            match.groupValues[1]
        } else {
            albumTag
        }
        val cleaned = rawKey
            .replace(Regex("""^[\\"'({\s\-_]+"""), "")
            .replace(Regex("""[\\"')} \s\-_]+$"""), "")
            .trim()
        return if (cleaned.isNotBlank()) cleaned else "Unknown Album"
    }

    fun getSortKey(name: String): String {
        val clean = name.replace(Regex("""^[^a-zA-Z0-9]+"""), "").trim().lowercase()
        return clean.ifBlank { name.lowercase() }
    }
}
