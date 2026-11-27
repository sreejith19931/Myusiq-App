package com.music.myusiq.utils

import com.music.myusiq.domain.models.Song
import java.io.BufferedReader
import java.io.File
import java.io.OutputStream

/**
 * Minimal M3U (extended) playlist serializer/parser for playlist
 * export/import. Export writes absolute file paths so the playlist works
 * in Myusiq and any local player; import matches entries against the
 * library by full normalized path, falling back to file name matching
 * for relocated files.
 */
object M3UUtils {

    fun serialize(name: String, songs: List<Song>): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        if (name.isNotBlank()) sb.append("#PLAYLIST:").append(name).append('\n')
        for (song in songs) {
            val secs = (song.duration / 1000L).coerceAtLeast(0L)
            sb.append("#EXTINF:").append(secs).append(',')
            sb.append(song.artist.ifBlank { "Unknown Artist" }).append(" - ")
            sb.append(song.title.ifBlank { song.path.substringAfterLast('/') }).append('\n')
            sb.append(song.path).append('\n')
        }
        return sb.toString()
    }

    fun write(name: String, songs: List<Song>, stream: OutputStream): Boolean =
        try {
            stream.use { it.write(serialize(name, songs).toByteArray(Charsets.UTF_8)) }
            true
        } catch (_: Exception) {
            false
        }

    data class ParsedM3U(val name: String?, val paths: List<String>)

    fun parse(text: String): ParsedM3U {
        var name: String? = null
        val paths = mutableListOf<String>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isBlank() -> {}
                line.startsWith("#PLAYLIST:") -> name = line.removePrefix("#PLAYLIST:").trim()
                line.startsWith("#") -> {} // #EXTM3U / #EXTINF and friends
                else -> {
                    // tolerate file:// URIs
                    val path = if (line.startsWith("file://")) {
                        try { java.net.URI(line).path } catch (_: Exception) { line.removePrefix("file://") }
                    } else line
                    if (path.isNotBlank()) paths.add(path)
                }
            }
        }
        return ParsedM3U(name, paths)
    }

    fun parseFile(file: File): ParsedM3U? =
        try {
            file.bufferedReader().use { parse(it.readText()) }
        } catch (_: Exception) {
            null
        }

    fun parseStream(reader: BufferedReader): ParsedM3U? =
        try {
            parse(reader.readText())
        } catch (_: Exception) {
            null
        }
}
