package com.music.myusiq.domain.models

import android.net.Uri
import com.music.myusiq.ui.viewmodels.PendingTrackEdit

/**
 * A wrapper for a track song and its suggested metadata edit from the Janitor Scan.
 * This object is used to track which tracks need user intervention or which ones were automatically fixed.
 */
data class JanitorUpdateItem(
    val song: Song,
    val currentArtworkUri: Uri?,
    var proposedEdit: PendingTrackEdit,
    var isSelected: Boolean = true
)
