package com.music.rhythm.utils

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

import androidx.core.net.toUri

object ArtworkUtils {
    private val sArtworkUri = "content://media/external/audio/albumart".toUri()

    fun trackUri(songId: Long): Uri = 
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
        
    @Suppress("unused")
    fun albumUri(albumId: Long): Uri = 
        ContentUris.withAppendedId(sArtworkUri, albumId)
}
