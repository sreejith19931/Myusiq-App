package com.music.myusiq.domain.usecase

import android.content.Context
import android.net.Uri
import com.music.myusiq.domain.models.Song
import com.music.myusiq.domain.models.JanitorUpdateItem
import com.music.myusiq.ui.viewmodels.PendingTrackEdit
import com.music.myusiq.ui.viewmodels.PlaybackViewModel
import com.music.myusiq.utils.CloudMetadataResolver
import com.music.myusiq.utils.YouTubeTitleCleaner
import com.music.myusiq.utils.ArtworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

sealed class ScanState {
    data class Progress(val percentage: Int, val message: String) : ScanState()
    data class ResultItem(val item: JanitorUpdateItem) : ScanState()
    data class ScanCompleted(val results: List<JanitorUpdateItem>) : ScanState()
}

class JanitorScanUseCase(
    private val viewModel: PlaybackViewModel,
) {
    /**
     * Scans a list of tracks in parallel (max 4 concurrent) to identify fixes and propose updates.
     * Shares album artwork across tracks belonging to the same album.
     *
     * @param tracks The list of tracks to scan.
     * @param context The application context to perform file operations.
     * @return A Flow of ScanState reporting progress and results.
     */
    fun scanTracks(tracks: List<Song>, context: Context): Flow<ScanState> = channelFlow {
        val totalTracks = tracks.size
        val results = java.util.Collections.synchronizedList(mutableListOf<JanitorUpdateItem>())
        val albumArtworkMap = java.util.Collections.synchronizedMap(mutableMapOf<String, Uri>())

        if (totalTracks == 0) {
            send(ScanState.Progress(100, "No tracks provided for scanning."))
            send(ScanState.ScanCompleted(emptyList()))
            return@channelFlow
        }

        // Pre-populate albumArtworkMap from tracks that already have valid artwork cached
        tracks.forEach { song ->
            if (!ArtworkUtils.isGenericAlbum(song.album)) {
                val artFile = ArtworkUtils.getArtworkFile(context, song.id, song.path, song.album)
                if (artFile != null && artFile.exists() && artFile.length() > 0) {
                    val albumKey = song.album.trim().lowercase()
                    if (!albumArtworkMap.containsKey(albumKey)) {
                        albumArtworkMap[albumKey] = Uri.fromFile(artFile)
                    }
                }
            }
        }

        val completedCount = AtomicInteger(0)
        val semaphore = Semaphore(4)

        coroutineScope {
            tracks.forEach { song ->
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            val result = processSingleTrack(song, context, albumArtworkMap)
                            if (result != null) {
                                results.add(result)
                                send(ScanState.ResultItem(result))
                            }
                        } catch (e: Exception) {
                            // Single track failure handled gracefully
                        } finally {
                            val count = completedCount.incrementAndGet()
                            val progressPct = (count * 100 / totalTracks).coerceAtMost(99)
                            send(ScanState.Progress(progressPct, "Scanning $count of $totalTracks: ${song.title}"))
                        }
                    }
                }
            }
        }

        // Second pass: Share album artwork with any track in the results that belongs to the same album and is missing artwork
        results.forEach { item ->
            val albumKey = item.proposedEdit.album.trim().lowercase()
            if (item.proposedEdit.artworkUri == null && !ArtworkUtils.isGenericAlbum(item.proposedEdit.album)) {
                val sharedArt = albumArtworkMap[albumKey]
                if (sharedArt != null) {
                    item.proposedEdit = item.proposedEdit.copy(
                        artworkUri = sharedArt,
                        matchConfidence = maxOf(item.proposedEdit.matchConfidence, 80)
                    )
                    item.isSelected = true
                }
            }
        }

        send(ScanState.Progress(100, "Scan complete. Found ${results.size} candidate updates."))
        send(ScanState.ScanCompleted(results.toList()))
    }

    private suspend fun processSingleTrack(
        song: Song,
        context: Context,
        albumArtworkMap: MutableMap<String, Uri>
    ): JanitorUpdateItem? {
        val (extractedArtist, extractedTitle) = YouTubeTitleCleaner.cleanAndSplit(song.title)
        
        val cleanTitle = if (extractedTitle.isNotBlank() && extractedTitle != song.title) {
            extractedTitle
        } else {
            YouTubeTitleCleaner.cleanTitle(song.title).ifBlank { YouTubeTitleCleaner.cleanFilename(song.path) }
        }
        val cleanArtist = if (!CloudMetadataResolver.isGenericArtist(song.artist)) {
            song.artist
        } else if (extractedArtist != "Unknown Artist" && !CloudMetadataResolver.isGenericArtist(extractedArtist)) {
            extractedArtist
        } else ""

        val metadata = CloudMetadataResolver.resolve(cleanTitle, cleanArtist)

        val currentArtUri = try {
            android.content.ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
        } catch (_: Exception) { null }

        return if (metadata != null) {
            var artworkUri = metadata.artworkUrl?.let { viewModel.downloadImageToCache(context, it) }

            val proposedTitle = metadata.foundTitle ?: cleanTitle
            val proposedArtist = metadata.foundArtist ?: cleanArtist.ifBlank { song.artist }
            val proposedAlbum = metadata.album ?: if (song.album.equals("<unknown>", true)) "Unknown Album" else song.album
            val albumKey = proposedAlbum.trim().lowercase()

            if (artworkUri != null && !ArtworkUtils.isGenericAlbum(proposedAlbum)) {
                albumArtworkMap[albumKey] = artworkUri
            } else if (artworkUri == null && !ArtworkUtils.isGenericAlbum(proposedAlbum)) {
                artworkUri = albumArtworkMap[albumKey]
            }

            val confidencePct = (metadata.matchConfidence * 100).toInt()

            val proposedEdit = PendingTrackEdit(
                songId = song.id,
                title = proposedTitle,
                artist = proposedArtist,
                album = proposedAlbum,
                year = metadata.year ?: "",
                genre = metadata.genre ?: "",
                artworkUri = artworkUri,
                path = song.path,
                matchConfidence = confidencePct
            )

            JanitorUpdateItem(song, currentArtUri, proposedEdit, isSelected = true)
        } else if (isTrackFixCandidate(context, song)) {
            val fallbackTitle = cleanTitle.ifBlank { song.title }
            val fallbackArtist = cleanArtist.ifBlank { song.artist }
            val fallbackAlbum = if (song.album.equals("<unknown>", true)) "Unknown Album" else song.album
            val albumKey = fallbackAlbum.trim().lowercase()

            val sharedArt = if (!ArtworkUtils.isGenericAlbum(fallbackAlbum)) albumArtworkMap[albumKey] else null

            val proposedEdit = PendingTrackEdit(
                songId = song.id,
                title = fallbackTitle,
                artist = fallbackArtist,
                album = fallbackAlbum,
                year = "",
                genre = "",
                artworkUri = sharedArt,
                path = song.path,
                matchConfidence = if (sharedArt != null) 80 else 0
            )

            val isCleaned = fallbackTitle != song.title || fallbackArtist != song.artist || sharedArt != null
            JanitorUpdateItem(song, currentArtUri, proposedEdit, isSelected = isCleaned)
        } else {
            null
        }
    }

    private fun isTrackFixCandidate(context: Context, song: Song): Boolean {
        val isMessy = isMessyOrUnknownTrack(song)
        val artFile = ArtworkUtils.getArtworkFile(context, song.id, song.path, song.album)
        val hasMissingArt = artFile == null || !artFile.exists() || artFile.length() == 0L
        return isMessy || hasMissingArt
    }

    private fun isMessyOrUnknownTrack(song: Song): Boolean {
        val artist = song.artist.trim()
        val title = song.title.trim()

        val isUnknownArtist = CloudMetadataResolver.isGenericArtist(artist)
        val hasFileExtension = Regex("(?i)\\.(mp3|m4a|flac|wav|aac|ogg|wma)$").containsMatchIn(title)
        val isGenericTitle = Regex("(?i)^(track|audio|sound|recording)[\\s\\-_]*\\d*$").matches(title) || title.isBlank()
        
        val cleanedTitle = YouTubeTitleCleaner.cleanTitle(title)
        val hasClutter = !cleanedTitle.equals(title, ignoreCase = true) && 
                         (title.contains("_") || 
                          title.contains(Regex("""(?i)[(\[{].*?(official|music|video|audio|lyric|hd|4k|remix|visualizer|mv|topic|prod|feat|ft|full song).*?[)\]}]""")) || 
                          title.contains(Regex("""(?i)\b(www\.|https?://)?[a-z0-9\-]+\.(com|net|org|io|me|co|info|biz)\b""")))

        val isGenericArtistWithGenericTitle = isUnknownArtist && (isGenericTitle || artist.isBlank())

        return isGenericArtistWithGenericTitle || hasFileExtension || isGenericTitle || hasClutter
    }
}
