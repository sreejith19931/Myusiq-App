package com.music.rhythm.ui.viewmodels

data class LyricsResponse(
    val lyricContent: String?,
    val suggestedTitle: String?,
    val suggestedArtist: String?,
    val suggestedAlbum: String?,
    val artworkUrl: String? = null,
    val confidenceScore: Float = 0f,
)
