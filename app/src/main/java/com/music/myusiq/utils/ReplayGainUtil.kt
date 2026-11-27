package com.music.myusiq.utils

import android.util.Log
import org.jaudiotagger.audio.AudioFileIO
import java.io.File
import kotlin.math.log10
import kotlin.math.pow

object ReplayGainUtil {

    private val gainCache = mutableMapOf<String, Float>()
    private val dbRegex = Regex("[+-]?\\d+(?:\\.\\d+)?")

    /**
     * Target loudness for waveform-derived gain: -11 dBFS mean RMS.
     * Modern loud masters measure ~-9 dBFS with the app's RMS extractor,
     * so they land at a gentle -2 dB reduction, while quiet tracks
     * (0.05-0.10 linear RMS) get boosted toward the same level. The final
     * factor is clamped to the same 0.5..1.25 window as tag-based gain.
     */
    private const val WAVEFORM_TARGET_DBFS = -11.0f

    /**
     * Extracts ReplayGain track gain in dB and returns a linear volume factor (0.5f .. 1.25f).
     * Applies a +6 dB pre-amp compensation so normalized audio maintains strong, dynamic volume.
     *
     * When the file carries no ReplayGain tag (typical for converted or
     * downloaded tracks), the Mood Engine's cached RMS waveform is used to
     * derive the loudness instead — no decode, instant lookup.
     */
    suspend fun getVolumeFactorForFile(
        filePath: String,
        useReplayGain: Boolean,
        waveformProvider: suspend () -> String? = { null }
    ): Float {
        if (!useReplayGain || filePath.isBlank()) return 1.0f

        gainCache[filePath]?.let { return it }

        var factor: Float? = null

        val file = File(filePath)
        if (file.exists()) {
            factor = factorFromTag(file)
        }

        if (factor == null) {
            factor = factorFromWaveform(waveformProvider())
        }

        val resolved = factor ?: 1.0f
        gainCache[filePath] = resolved
        return resolved
    }

    private fun factorFromTag(file: File): Float? {
        return try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag ?: return null

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

            if (gainDb == null) return null

            // Pre-amp compensation (+6 dB) so tracks aren't overly quiet
            val compensatedDb = gainDb + 6.0f

            // Linear multiplier: 10^(dB / 20)
            val factor = (10.0.pow(compensatedDb / 20.0)).toFloat().coerceIn(0.5f, 1.25f)
            Log.d("ReplayGainUtil", "ReplayGain for ${file.name}: $gainDb dB (+6dB preamp) -> factor: $factor")
            factor
        } catch (e: Exception) {
            Log.w("ReplayGainUtil", "Could not parse ReplayGain for ${file.name}: ${e.message}")
            null
        }
    }

    /**
     * Derives a loudness-matching factor from the stored RMS waveform.
     * Returns null when no usable waveform exists (blank, stale legacy
     * peak envelope, or saturated garbage), leaving volume untouched.
     */
    fun factorFromWaveform(waveform: String?): Float? {
        if (waveform.isNullOrBlank()) return null
        if (WaveformExtractor.isLegacyPeakEnvelope(waveform) || WaveformExtractor.isSaturated(waveform)) return null

        // Ignore near-silent windows (intro/outro/silence) so they don't
        // skew the mean downward and cause over-boost.
        val values = waveform.split(",").mapNotNull { it.toFloatOrNull() }.filter { it > 0.02f }
        if (values.isEmpty()) return null

        val meanRms = values.average().toFloat()
        if (meanRms <= 0f) return null

        val rmsDb = 20f * log10(meanRms)
        val gainDb = WAVEFORM_TARGET_DBFS - rmsDb
        val factor = (10.0.pow(gainDb / 20.0)).toFloat().coerceIn(0.5f, 1.25f)
        Log.d("ReplayGainUtil", "Waveform gain: meanRMS=${"%.3f".format(meanRms)} (${"%.1f".format(rmsDb)} dBFS) -> ${"%.1f".format(gainDb)} dB -> factor $factor")
        return factor
    }

    fun clearCache() {
        gainCache.clear()
    }
}
