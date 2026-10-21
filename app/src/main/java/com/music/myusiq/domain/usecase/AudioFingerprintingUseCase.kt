package com.music.myusiq.domain.usecase

import android.content.Context
import android.net.Uri
import com.music.myusiq.domain.models.Song
import com.music.myusiq.utils.MusicEnergyAnalyzer
import com.music.myusiq.utils.WaveformExtractor
import java.io.File

/**
 * Use case responsible for generating a unique acoustic fingerprint and waveform signature for a track.
 *
 * The fingerprint acts as a digital signature of the song's audio characteristics
 * (duration, energy score, peak density, and sampled wave envelope), allowing us to match it against
 * metadata databases even if the existing metadata tags are messy or missing.
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
}
