package com.music.myusiq.utils

/**
 * Utility for sanitizing YouTube-downloaded audio titles by removing common clutter
 * such as "(Official Music Video)", "[4K]", "(Audio)", track index prefixes, etc.,
 * and splitting artist and title.
 */
object YouTubeTitleCleaner {

    private val bracketClutterRegex = Regex(
        """(?i)[(\[{]\s*[^)\]}]*?(official|music\s+video|official\s+audio|lyric\s+video|hd\s+1080p|4k|visualizer|mv|topic|full\s+song|audio|soundtrack|music|v\.a\.|various(\s+artists)?|unknown(\s+(artist|album))?|y2mate|ytmp3|ezmp3(\.[a-z]{2,4})?|www\.|[a-z0-9\-]+\.[a-z]{2,6})[^)\]}]*?\s*[)\]}]"""
    )

    private val unbracketedClutterRegex = Regex(
        """(?i)\b(official\s+(music\s+)?video|official\s+audio|lyric\s+video|4k\s+video|hd\s+1080p|v\.a\.|various\s+artists|unknown\s+artist|unknown\s+album|y2mate(\.is|\.com)?|ytmp3|ezmp3(\.[a-z]{2,4})?)\b"""
    )

    private val extensionRegex = Regex("(?i)(\\.mp3|\\.m4a|\\.wav|\\.flac|\\.aac|\\.ogg|\\.wma)$")

    private val trackIndexRegex = Regex("(?i)^(\\d{1,3}[.\\-_\\s]+|track[_\\s-]*\\d{1,3}[.\\-_\\s]*)")

    private val domainRegex = Regex("""(?i)\b(www\.|https?://)?[a-z0-9\-]+\.[a-z]{2,6}\b""")

    /**
     * Cleans YouTube clutter, file extensions, domain URLs, underscores, and leading track numbers from the raw title string.
     *
     * @param rawTitle The raw track title or filename.
     * @return Cleaned title string.
     */
    fun cleanTitle(rawTitle: String): String {
        val step1 = rawTitle
            .replace("_", " ")
            .replace(extensionRegex, "")
            .replace(domainRegex, "")
            .replace(bracketClutterRegex, "")
            .replace(unbracketedClutterRegex, "")
            .replace(Regex("""\(\s*\)|\[\s*\]|\{\s*\}"""), "")
            .trim()

        val step2 = step1.replace(trackIndexRegex, "").trim()
        val cleaned = step2.replace(Regex("""\s+"""), " ")
        return toTitleCase(cleaned)
    }

    /**
     * Cleans raw artist name by removing generic tags, domain URLs, bracketed junk, and track indices.
     *
     * @param rawArtist The raw artist string.
     * @return Cleaned artist name, or "Unknown Artist" if generic/invalid.
     */
    fun cleanArtist(rawArtist: String): String {
        if (isGenericArtistName(rawArtist)) return "Unknown Artist"

        val step1 = rawArtist
            .replace("_", " ")
            .replace(extensionRegex, "")
            .replace(domainRegex, "")
            .replace(bracketClutterRegex, "")
            .replace(unbracketedClutterRegex, "")
            .replace(Regex("""\(\s*\)|\[\s*\]|\{\s*\}"""), "")
            .trim()

        val step2 = step1.replace(trackIndexRegex, "").trim()
        val cleaned = toTitleCase(step2.replace(Regex("""\s+"""), " "))

        return if (isGenericArtistName(cleaned) || cleaned.isBlank()) "Unknown Artist" else cleaned
    }

    /**
     * Cleans raw album title by removing generic tags, domain URLs, bracketed junk, and file extensions.
     *
     * @param rawAlbum The raw album string.
     * @return Cleaned album name, or "Unknown Album" if generic/invalid.
     */
    fun cleanAlbum(rawAlbum: String): String {
        if (isGenericAlbumName(rawAlbum)) return "Unknown Album"

        val step1 = rawAlbum
            .replace("_", " ")
            .replace(extensionRegex, "")
            .replace(domainRegex, "")
            .replace(bracketClutterRegex, "")
            .replace(unbracketedClutterRegex, "")
            .replace(Regex("""\(\s*\)|\[\s*\]|\{\s*\}"""), "")
            .trim()

        val cleaned = toTitleCase(step1.replace(Regex("""\s+"""), " "))

        return if (isGenericAlbumName(cleaned) || cleaned.isBlank()) "Unknown Album" else cleaned
    }

    /**
     * Checks if artist name is generic or invalid.
     */
    fun isGenericArtistName(artist: String?): Boolean {
        if (artist.isNullOrBlank()) return true
        val lower = artist.trim().lowercase()
        return lower == "unknown artist" ||
               lower == "<unknown>" ||
               lower == "unknown" ||
               lower == "null" ||
               lower == "various artists" ||
               lower == "various" ||
               lower == "v.a." ||
               lower == "va" ||
               lower == "n/a" ||
               lower == "none" ||
               lower == "artist" ||
               lower.matches(Regex("""^track\s*\d*$"""))
    }

    /**
     * Checks if album name is generic, file-sharing folder name, or invalid.
     */
    fun isGenericAlbumName(album: String?): Boolean {
        if (album.isNullOrBlank()) return true
        val lower = album.trim().lowercase().replace("_", " ").replace("-", " ")
        return lower == "unknown album" ||
               lower == "<unknown>" ||
               lower == "unknown" ||
               lower == "null" ||
               lower == "n/a" ||
               lower == "none" ||
               lower == "album" ||
               lower == "audio" ||
               lower == "soundtrack" ||
               lower == "music" ||
               lower == "singles" ||
               lower == "download" ||
               lower == "downloads" ||
               lower == "media" ||
               lower == "storage" ||
               lower == "sdcard" ||
               lower == "sd card" ||
               lower == "emulated" ||
               lower == "documents" ||
               lower == "shareme" ||
               lower == "share me" ||
               lower == "mishare" ||
               lower == "mi share" ||
               lower == "shareit" ||
               lower == "share it" ||
               lower == "xender" ||
               lower == "bluetooth" ||
               lower == "snaptube" ||
               lower == "snap tube" ||
               lower == "vidmate" ||
               lower == "vid mate" ||
               lower == "tubemate" ||
               lower == "tube mate" ||
               lower == "y2mate" ||
               lower == "ytmp3" ||
               lower == "ezmp3" ||
               lower == "whatsapp" ||
               lower == "whatsapp audio" ||
               lower == "telegram" ||
               lower == "telegram audio" ||
               lower == "instagram" ||
               lower == "facebook" ||
               lower == "tiktok" ||
               lower == "ringtones" ||
               lower == "notifications" ||
               lower == "alarms" ||
               lower == "voice notes" ||
               lower == "recordings"
    }

    /**
     * Converts all-caps or all-lowercase titles to Title Case.
     */
    fun toTitleCase(input: String): String {
        if (input.isBlank()) return input
        val isAllCaps = input.none { it.isLowerCase() } && input.any { it.isLetter() }
        val isAllLower = input.none { it.isUpperCase() } && input.any { it.isLetter() }

        if (isAllCaps || isAllLower) {
            return input.split(" ").joinToString(" ") { word ->
                when {
                    word.equals("feat", ignoreCase = true) || word.equals("ft", ignoreCase = true) -> "feat."
                    word.length > 1 -> word.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    else -> word.uppercase()
                }
            }
        }
        return input
    }

    /**
     * Cleans raw storage filename (e.g. `01_despacito_y2mate.is.mp3`) by stripping file extensions,
     * web tags, domain names, underscores, and leading track numbers.
     */
    fun cleanFilename(path: String): String {
        val fileName = java.io.File(path).nameWithoutExtension
        return cleanTitle(fileName)
    }

    /**
     * Cleans YouTube clutter, strips track numbers, and attempts to split "Artist - Title" format.
     *
     * @param rawTitle Raw title string (e.g. "01 - Artist - Song Name (Official Video)")
     * @return Pair of (Artist, Title)
     */
    fun cleanAndSplit(rawTitle: String): Pair<String, String> {
        val cleaned = cleanTitle(rawTitle)

        // Split on a spaced dash only ("Artist - Title"). Splitting on any
        // dash would mangle artists like "Jay-Z" into artist "Jay".
        val spacedDash = Regex("""\s+-\s+""")
        return if (spacedDash.containsMatchIn(cleaned)) {
            val parts = spacedDash.split(cleaned, limit = 2)
            val artist = cleanArtist(parts[0].trim())
            val title = cleanTitle(parts[1].trim())
            Pair(artist, if (title.isNotBlank()) title else cleaned)
        } else {
            Pair("Unknown Artist", cleaned)
        }
    }
}

