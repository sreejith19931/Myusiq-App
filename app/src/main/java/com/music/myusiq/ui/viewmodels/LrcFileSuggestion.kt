package com.music.myusiq.ui.viewmodels

import java.io.File

data class LrcFileSuggestion(
    val file: File,
    val similarityScore: Float,
)
