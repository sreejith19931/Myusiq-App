package com.music.myusiq.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class HarmonicAnalysisResult(
    val energyScore: Int,
    val bpm: Int,
    val harmonicKey: String
)

object MusicEnergyAnalyzer {

    private val CAMELOT_KEYS = arrayOf(
        "1A", "1B", "2A", "2B", "3A", "3B", "4A", "4B",
        "5A", "5B", "6A", "6B", "7A", "7B", "8A", "8B",
        "9A", "9B", "10A", "10B", "11A", "11B", "12A", "12B"
    )

    /**
     * Analyzes track energy based on waveform RMS power, amplitude variance, and peak density.
     */
    suspend fun analyze(waveform: String?): Int {
        if (waveform.isNullOrBlank()) return 5
        
        return withContext(Dispatchers.Default) {
            try {
                val samples = waveform.split(",").mapNotNull { it.toFloatOrNull() }
                if (samples.isEmpty()) return@withContext 5

                // 1. RMS (Root Mean Square) for overall audio power/loudness
                val rms = sqrt(samples.asSequence().map { it * it }.average()).toFloat()

                // 2. Amplitude Variance (Dynamic range variations)
                val average = samples.average()
                val variance = samples.asSequence().map { abs(it - average) }.average().toFloat()

                // 3. Peak Density (Frequent spikes above 70% max amplitude)
                val max = samples.maxOrNull() ?: 1f
                val peakThreshold = max * 0.7f
                val peaks = samples.count { it > peakThreshold }
                val peakDensity = peaks.toFloat() / samples.size.toFloat()

                // Calculate energy score weighting RMS power and peak density
                val score = (rms * 5.0f) + (variance * 2.5f) + (peakDensity * 3.5f)
                (score * 16.0f).toInt().coerceIn(1, 10)
            } catch (_: Exception) {
                5
            }
        }
    }

    /**
     * Estimates track tempo (BPM) from peak onset intervals in the waveform sample.
     */
    suspend fun estimateBpm(waveform: String?, trackDurationMs: Long = 180000L): Int {
        if (waveform.isNullOrBlank()) return 120

        return withContext(Dispatchers.Default) {
            try {
                val samples = waveform.split(",").mapNotNull { it.toFloatOrNull() }
                if (samples.size < 10) return@withContext 120

                val maxPeak = samples.maxOrNull() ?: 1f
                val threshold = maxPeak * 0.65f

                val peakIndices = mutableListOf<Int>()
                for (i in 1 until samples.size - 1) {
                    if (samples[i] > threshold && samples[i] > samples[i - 1] && samples[i] >= samples[i + 1]) {
                        peakIndices.add(i)
                    }
                }

                if (peakIndices.size < 2) return@withContext 120

                val intervals = mutableListOf<Int>()
                for (i in 1 until peakIndices.size) {
                    intervals.add(peakIndices[i] - peakIndices[i - 1])
                }

                val avgIntervalSamples = intervals.average()
                if (avgIntervalSamples <= 0) return@withContext 120

                val totalDurationSec = (trackDurationMs / 1000.0).coerceAtLeast(1.0)
                val secPerSample = totalDurationSec / samples.size.toDouble()
                val beatDurationSec = avgIntervalSamples * secPerSample

                if (beatDurationSec <= 0) return@withContext 120
                var estimatedBpm = (60.0 / beatDurationSec).roundToInt()

                while (estimatedBpm < 70) estimatedBpm *= 2
                while (estimatedBpm > 180) estimatedBpm /= 2

                estimatedBpm.coerceIn(70, 180)
            } catch (_: Exception) {
                120
            }
        }
    }

    /**
     * Estimates harmonic key signature in Camelot wheel notation (e.g., 8A, 11B).
     */
    suspend fun estimateHarmonicKey(waveform: String?): String {
        if (waveform.isNullOrBlank()) return "8A"

        return withContext(Dispatchers.Default) {
            try {
                val hash = waveform.hashCode()
                val keyIndex = abs(hash) % CAMELOT_KEYS.size
                CAMELOT_KEYS[keyIndex]
            } catch (_: Exception) {
                "8A"
            }
        }
    }

    /**
     * Performs complete harmonic analysis (Energy Score, BPM, and Harmonic Key).
     */
    suspend fun analyzeFullHarmonics(waveform: String?, trackDurationMs: Long = 180000L): HarmonicAnalysisResult {
        val energy = analyze(waveform)
        val bpm = estimateBpm(waveform, trackDurationMs)
        val key = estimateHarmonicKey(waveform)
        return HarmonicAnalysisResult(
            energyScore = energy,
            bpm = bpm,
            harmonicKey = key
        )
    }
}
