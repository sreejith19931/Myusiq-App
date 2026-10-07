package com.music.myusiq.utils

/**
 * Utility for sanitizing YouTube-downloaded audio titles by removing common clutter
 * such as "(Official Music Video)", "[4K]", "(Audio)", etc., and splitting artist and title.
 */
object YouTubeTitleCleaner {

    private val clutterRegex = Regex(
        """(?i)[(\[{].*?(official|music|video|audio|lyric|hd|4k|remix|visualizer|mv|topic).*?[)\]}]"""
    )

    private val extensionRegex = Regex("(?i)\\.mp3|\\.m4a|\\.wav|\\.flac|\\.aac|\\.ogg|y2mate|dl")

    /**
     * Cleans YouTube clutter and extra file extensions from the raw title string.
     *
     * @param rawTitle The raw track title or filename.
     * @return Cleaned title string.
     */
    fun cleanTitle(rawTitle: String): String {
        return rawTitle
            .replace(clutterRegex, "")
            .replace(extensionRegex, "")
            .trim()
    }

    /**
     * Cleans YouTube clutter and attempts to split "Artist - Title" format.
     *
     * @param rawTitle Raw title string (e.g. "Artist - Song Name (Official Video)")
     * @return Pair of (Artist, Title)
     */
    fun cleanAndSplit(rawTitle: String): Pair<String, String> {
        val cleaned = cleanTitle(rawTitle)

        return if (cleaned.contains("-")) {
            val parts = cleaned.split("-", limit = 2)
            Pair(parts[0].trim(), parts[1].trim()) // (Artist, Title)
        } else {
            Pair("Unknown Artist", cleaned)
        }
    }
}
