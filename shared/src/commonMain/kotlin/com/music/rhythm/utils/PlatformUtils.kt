package com.music.rhythm.utils

expect object PlatformUtils {
    fun normalizePath(path: String): String
    fun getCurrentTimeMillis(): Long
}
