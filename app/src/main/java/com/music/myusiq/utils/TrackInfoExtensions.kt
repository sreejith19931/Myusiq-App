package com.music.myusiq.utils

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.util.Locale
import com.music.myusiq.domain.models.Song
import com.music.myusiq.domain.models.SongOverride
import com.music.myusiq.DatabaseProvider
import kotlinx.coroutines.runBlocking

data class TrackDetailedInfo(
    val title: String,
    val artist: String,
    val album: String,
    val year: String,
    val genre: String,
    val format: String,
    val duration: String,
    val bitrate: String,
    val size: String,
    val path: String,
)

fun fetchTrackDetailedInfo(context: Context, song: Song): TrackDetailedInfo {
    val trackUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
    val retriever = MediaMetadataRetriever()
    var bitrateStr = "Unknown"
    var durationStr = "Unknown"
    var mimeType = "Unknown"
    var fileSizeStr = "Unknown"
    var year = "Unknown"
    var genre = "Unknown"

    try {
        val projection = arrayOf(MediaStore.Audio.Media.SIZE)
        context.contentResolver.query(trackUri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE))
                if (bytes > 0) {
                    fileSizeStr = String.format(Locale.getDefault(), "%.2f MB", bytes.toFloat() / (1024 * 1024))
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    
    try {
        retriever.setDataSource(context, trackUri)
        mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "audio/unknown"
        val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
        bitrate?.let {
            bitrateStr = "${it / 1000} kbps"
        }
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        if (durationMs != null) {
            val minutes = (durationMs / 1000) / 60
            val seconds = (durationMs / 1000) % 60
            durationStr = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
        year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: "Unknown"
        genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: "Unknown"
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        retriever.release()
    }
    
    val audioFormat = mimeType.substringAfter("/").uppercase()
    
    // 3. Apply local overrides if present
    val songDao = DatabaseProvider.getDatabase(context).songDao()
    val override = runBlocking { songDao.getAllOverrides() }.find { it.path == PlatformUtils.normalizePath(song.path) }

    return TrackDetailedInfo(
        override?.title ?: song.title, 
        override?.artist ?: song.artist, 
        override?.album ?: song.album, 
        override?.year?.ifBlank { year } ?: year, 
        override?.genre?.ifBlank { genre } ?: genre, 
        audioFormat, durationStr, bitrateStr, fileSizeStr, song.path
    )
}

fun Song.toMediaItem(): MediaItem {
    val trackUri = ContentUris.withAppendedId(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        this.id
    )
    val artworkUri = ContentUris.withAppendedId(
        "content://media/external/audio/albumart".toUri(),
        this.albumId
    )

    val extras = Bundle().apply {
        putLong("id", this@toMediaItem.id)
        putString("path", this@toMediaItem.path)
        putLong("albumId", this@toMediaItem.albumId)
        putInt("duration", this@toMediaItem.duration)
    }

    val metadata = MediaMetadata.Builder()
        .setTitle(this.title)
        .setArtist(this.artist)
        .setAlbumTitle(this.album)
        .setArtworkUri(artworkUri)
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setExtras(extras)
        .build()

    return MediaItem.Builder()
        .setMediaId(this.id.toString())
        .setUri(trackUri)
        .setMediaMetadata(metadata)
        .build()
}

fun createBrowsableMediaItem(
    mediaId: String,
    title: String,
    mediaType: Int,
    artworkUri: Uri? = null
): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setMediaType(mediaType)
        .setIsPlayable(false)
        .setIsBrowsable(true)
        .setArtworkUri(artworkUri)
        .build()
    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(metadata)
        .build()
}

fun formatTime(ms: Int): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}
