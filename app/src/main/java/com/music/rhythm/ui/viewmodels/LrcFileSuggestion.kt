package com.music.rhythm.ui.viewmodels

import java.io.File

data class LrcFileSuggestion(
    val file: File,
    val similarityScore: Float,
)
