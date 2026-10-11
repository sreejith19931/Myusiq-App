package com.music.myusiq.utils

import android.util.Log
import org.jaudiotagger.audio.AudioFileIO
import java.io.File
import kotlin.math.pow

object ReplayGainUtil {

    private val gainCache = mutableMapOf<String, Float>()

    /**
     * Extracts ReplayGain track gain in dB and returns a linear volume factor (0.1f .. 1.0f).
     * Format example in tag: "-3.50 dB" or "+1.2 dB" or "-5.2"
     */
    fun getVolumeFactorForFile(filePath: String, useReplayGain: Boolean): Float {
        if (!useReplayGain || filePath.isBlank()) return 1.0f

        gainCache[filePath]?.let { return it }

        val file = File(filePath)
        if (!file.exists()) return 1.0f

        return try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag ?: return 1.0f

            var gainStr: String? = null
            
            // Search through tag fields for REPLAYGAIN
            val iterator = tag.fields
            while (iterator.hasNext()) {
                val field = iterator.next()
                val id = field.id.uppercase()
                if (id.contains("REPLAYGAIN_TRACK_GAIN") || id.contains("REPLAYGAIN_ALBUM_GAIN") || id == "TXXX") {
                    val strVal = field.toString()
                    if (strVal.contains("REPLAYGAIN_TRACK_GAIN", ignoreCase = true) || strVal.contains("REPLAYGAIN_ALBUM_GAIN", ignoreCase = true)) {
                        gainStr = strVal.substringAfter("=").substringAfter(":").trim()
                        break
                    }
                }
            }

            if (gainStr.isNullOrBlank()) {
                gainCache[filePath] = 1.0f
                return 1.0f
            }

            // Parse e.g. "-3.50 dB" -> -3.50
            val cleanDbStr = gainStr.replace("dB", "", true).replace("db", "", true).trim()
            val dbVal = cleanDbStr.toFloatOrNull() ?: 0.0f

            // Linear multiplier: 10^(dB / 20)
            val factor = (10.0.pow(dbVal / 20.0)).toFloat().coerceIn(0.1f, 1.0f)
            gainCache[filePath] = factor
            Log.d("ReplayGainUtil", "ReplayGain for ${file.name}: $gainStr ($dbVal dB) -> Volume factor: $factor")
            factor
        } catch (e: Exception) {
            Log.w("ReplayGainUtil", "Could not parse ReplayGain for $filePath: ${e.message}")
            gainCache[filePath] = 1.0f
            1.0f
        }
    }

    fun clearCache() {
        gainCache.clear()
    }
}
