package com.music.myusiq.utils

import android.os.Environment
import java.io.File

actual object PlatformUtils {
    actual fun normalizePath(path: String): String {
        if (path.isBlank()) return ""
        
        // 1. Basic formatting: convert to absolute, remove double slashes
        var normalized = File(path).absolutePath.replace("//", "/")
        
        // 2. Handle standard Android storage aliases
        val externalStorage = Environment.getExternalStorageDirectory().absolutePath
        val aliases = arrayOf("/sdcard", "/mnt/sdcard", "/storage/self/primary")
        
        for (alias in aliases) {
            if (normalized.startsWith(alias, ignoreCase = true)) {
                normalized = normalized.replaceFirst(alias, externalStorage, ignoreCase = true)
                break
            }
        }
        
        // 3. Remove trailing slash for consistency
        if (normalized.endsWith("/") && normalized.length > 1) {
            normalized = normalized.substring(0, normalized.length - 1)
        }
        
        return normalized
    }

    actual fun getCurrentTimeMillis(): Long = System.currentTimeMillis()
}
