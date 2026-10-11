package com.music.myusiq.utils

import android.util.Log
import org.jaudiotagger.audio.AudioFileIO
import java.io.File
import kotlin.math.pow

object ReplayGainUtil {

    private val gainCache = mutableMapOf<String, Float>()
    private val dbRegex = Regex("[+-]?\\d+(?:\\.\\d+)?")

    /**
     * Extracts ReplayGain track gain in dB and returns a linear volume factor (0.5f .. 1.25f).
     * Applies a +6 dB pre-amp compensation so normalized audio maintains strong, dynamic volume.
     */
    fun getVolumeFactorForFile(filePath: String, useReplayGain: Boolean): Float {
        if (!useReplayGain || filePath.isBlank()) return 1.0f

        gainCache[filePath]?.let { return it }

        val file = File(filePath)
        if (!file.exists()) return 1.0f

        return try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag

            if (tag == null) {
                gainCache[filePath] = 1.0f
                return 1.0f
            }

            var gainDb: Float? = null

            // Search tag fields
            val iterator = tag.fields
            while (iterator.hasNext()) {
                val field = iterator.next()
                val fieldStr = field.toString()
                if (fieldStr.contains("REPLAYGAIN_TRACK_GAIN", ignoreCase = true) ||
                    fieldStr.contains("REPLAYGAIN_ALBUM_GAIN", ignoreCase = true)) {
                    val match = dbRegex.find(fieldStr.substringAfter("REPLAYGAIN"))
                    if (match != null) {
                        gainDb = match.value.toFloatOrNull()
                        if (gainDb != null) break
                    }
                }
            }

            if (gainDb == null) {
                gainCache[filePath] = 1.0f
                return 1.0f
            }

            // Pre-amp compensation (+6 dB) so tracks aren't overly quiet
            val compensatedDb = gainDb + 6.0f

            // Linear multiplier: 10^(dB / 20)
            val factor = (10.0.pow(compensatedDb / 20.0)).toFloat().coerceIn(0.5f, 1.25f)
            gainCache[filePath] = factor
            Log.d("ReplayGainUtil", "ReplayGain for ${file.name}: $gainDb dB (+6dB preamp) -> factor: $factor")
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
