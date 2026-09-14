package com.music.rhythm.domain.models

data class FolderItem(
    val absolutePath: String,
    val songCount: Int,
    var isIncluded: Boolean = true,
) {
    val folderName: String
        get() = absolutePath.substringAfterLast('/').ifEmpty { "Root" }
}
