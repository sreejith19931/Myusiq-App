package com.music.myusiq.domain.usecase

import android.content.Context
import android.net.Uri
import com.music.myusiq.domain.models.Song
import com.music.myusiq.utils.MusicEnergyAnalyzer
import com.music.myusiq.utils.WaveformExtractor
import java.io.File
import kotlin.math.abs

/**
 * Use case responsible for generating a unique acoustic fingerprint and waveform signature for a track.
 *
 * The fingerprint acts as a digital signature of the song's audio characteristics
 * (duration, energy score, peak density, and sampled wave envelope), allowing us to match it against
 * metadata databases even if the existing metadata tags are messy or missing, as well as detecting duplicates.
 */
class AudioFingerprintingUseCase {

    /**
     * Generates an acoustic fingerprint and waveform signature for a given song.
     *
     * @param song The song containing duration and audio file path.
     * @param context Optional Android Context for extracting waveform amplitudes.
     * @return A string signature representing the track's acoustic waveform characteristics.
     */
    suspend fun generateFingerprint(song: Song, context: Context? = null): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val durationSec = (song.duration / 1000).coerceAtLeast(0)
                val file = File(song.path)
                val fileSizeKb = if (file.exists()) file.length() / 1024L else 0L

                var waveform: String? = null
                if (context != null && file.exists()) {
                    val uri = Uri.fromFile(file)
                    waveform = WaveformExtractor.extractWaveform(context, uri)
                }

                val energyScore = if (!waveform.isNullOrBlank()) {
                    MusicEnergyAnalyzer.analyze(waveform)
                } else {
                    5
                }

                val waveSummary = if (!waveform.isNullOrBlank()) {
                    val samples = waveform.split(",").mapNotNull { it.toFloatOrNull() }
                    if (samples.isNotEmpty()) {
                        val mean = samples.average()
                        val maxPeak = samples.maxOrNull() ?: 0f
                        val minPeak = samples.minOrNull() ?: 0f
                        "mean:%.2f|max:%.2f|min:%.2f|samples:%d".format(java.util.Locale.US, mean, maxPeak, minPeak, samples.size)
                    } else {
                        "p:none"
                    }
                } else {
                    "p:none"
                }

                "dur_ms:${song.duration}|dur:${durationSec}s|energy:${energyScore}|size:${fileSizeKb}KB|$waveSummary"
            } catch (_: Exception) {
                "dur_ms:${song.duration}|dur:${song.duration / 1000}s|hash:${song.path.hashCode().toString(16)}"
            }
        }
    }

    /**
     * Calculates the acoustic similarity score (0.0f to 1.0f) between two fingerprints.
     */
    fun calculateSimilarityScore(fp1: String, fp2: String): Float {
        if (fp1 == fp2) return 1.0f
        
        val dur1 = extractValue(fp1, "dur_ms")?.toLongOrNull() ?: 0L
        val dur2 = extractValue(fp2, "dur_ms")?.toLongOrNull() ?: 0L

        if (dur1 <= 0L || dur2 <= 0L) return 0.0f
        val durDiffMs = abs(dur1 - dur2)

        // If duration difference > 4 seconds, they cannot be identical acoustic recordings
        if (durDiffMs > 4000L) return 0.0f

        var score = 1.0f - (durDiffMs.toFloat() / 4000L * 0.3f)

        val energy1 = extractValue(fp1, "energy")?.toIntOrNull() ?: 5
        val energy2 = extractValue(fp2, "energy")?.toIntOrNull() ?: 5
        val energyDiff = abs(energy1 - energy2)
        score -= (energyDiff * 0.05f)

        val mean1 = extractValue(fp1, "mean")?.toFloatOrNull() ?: 0f
        val mean2 = extractValue(fp2, "mean")?.toFloatOrNull() ?: 0f
        if (mean1 > 0f && mean2 > 0f) {
            val meanDiff = abs(mean1 - mean2)
            score -= (meanDiff * 0.2f)
        }

        return score.coerceIn(0.0f, 1.0f)
    }

    /**
     * Determines whether two tracks are duplicates based on acoustic fingerprints.
     */
    fun areDuplicates(fp1: String, fp2: String, threshold: Float = 0.82f): Boolean {
        return calculateSimilarityScore(fp1, fp2) >= threshold
    }

    private fun extractValue(fp: String, key: String): String? {
        val parts = fp.split("|")
        for (part in parts) {
            if (part.startsWith("$key:")) {
                return part.substringAfter(":")
            }
        }
        return null
    }
}
