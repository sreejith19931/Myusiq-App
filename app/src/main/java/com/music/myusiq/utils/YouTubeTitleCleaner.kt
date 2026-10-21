package com.music.myusiq.utils

/**
 * Utility for sanitizing YouTube-downloaded audio titles by removing common clutter
 * such as "(Official Music Video)", "[4K]", "(Audio)", track index prefixes, etc.,
 * and splitting artist and title.
 */
object YouTubeTitleCleaner {

    private val bracketClutterRegex = Regex(
        """(?i)[(\[{].*?(official|music\s+video|official\s+audio|lyric\s+video|hd\s+1080p|4k|visualizer|mv|topic|full\s+song|y2mate|ytmp3).*?[)\]}]"""
    )

    private val unbracketedClutterRegex = Regex(
        """(?i)\b(official\s+(music\s+)?video|official\s+audio|lyric\s+video|4k\s+video|hd\s+1080p|y2mate(\.is|\.com)?|ytmp3)\b"""
    )

    private val extensionRegex = Regex("(?i)(\\.mp3|\\.m4a|\\.wav|\\.flac|\\.aac|\\.ogg|\\.wma)$")

    private val trackIndexRegex = Regex("(?i)^(\\d{1,3}[.\\-_\\s]+|track[_\\s-]*\\d{1,3}[.\\-_\\s]*)")

    private val domainRegex = Regex("""(?i)\b(www\.|https?://)?[a-z0-9\-]+\.(com|net|org|io|me|co|info|biz)\b""")

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
            .trim()

        val step2 = step1.replace(trackIndexRegex, "").trim()
        val cleaned = step2.replace(Regex("""\s+"""), " ")
        return toTitleCase(cleaned)
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

        return if (cleaned.contains("-")) {
            val parts = cleaned.split("-", limit = 2)
            val artist = toTitleCase(parts[0].trim())
            val title = toTitleCase(parts[1].trim())
            Pair(if (artist.isNotBlank()) artist else "Unknown Artist", if (title.isNotBlank()) title else cleaned)
        } else {
            Pair("Unknown Artist", cleaned)
        }
    }
}

