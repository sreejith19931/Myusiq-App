package com.music.rhythm.utils

object AlbumUtils {
    private val moviePattern = Regex("(?i)\\(from\\s+([^)]+)\\)")

    fun getGroupingKey(albumTag: String?): String {
        if (albumTag.isNullOrBlank()) return "Unknown Album"
        val match = moviePattern.find(albumTag)
        return if (match != null) {
            match.groupValues[1].replace("_", "").trim().replaceFirstChar { it.uppercase() }
        } else {
            albumTag.trim()
        }
    }
}