package com.music.myusiq.utils

expect object PlatformUtils {
    fun normalizePath(path: String): String
    fun getCurrentTimeMillis(): Long
}
