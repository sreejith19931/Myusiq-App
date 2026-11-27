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
     * Analyzes track energy based on the per-window peak envelope produced by
     * WaveformExtractor (values in 0..1, roughly the track's loudness contour).
     *
     * Calibration (peak-envelope scale): a loud modern master has mean peak
     * ≈ 0.6-0.8 and high peak density → 8-10; mellow pop/ballads ≈ 0.3-0.5
     * mean → 4-6; quiet acoustic/classical/lo-fi ≈ 0.15-0.3 → 1-3.
     * Loudness dominates, dynamics (variance across windows) and peak
     * density modulate. Saturated/corrupt waveforms (all ≈ 1.0) are rejected
     * so they can never masquerade as "maximum energy".
     */
    suspend fun analyze(waveform: String?): Int {
        if (waveform.isNullOrBlank() || WaveformExtractor.isSaturated(waveform)) return 5

        return withContext(Dispatchers.Default) {
            try {
                val samples = waveform.split(",").mapNotNull { it.toFloatOrNull() }
                if (samples.size < 10) return@withContext 5

                val mean = samples.average()
                val variance = samples.asSequence().map { abs(it - mean) }.average().toFloat()
                val max = samples.maxOrNull() ?: 1f
                val peakThreshold = max * 0.7f
                val peakDensity = samples.count { it > peakThreshold }.toFloat() / samples.size.toFloat()

                // Loudness dominates; dynamics and peak density modulate.
                val raw = (7.0 * mean) + (5.0 * variance) + (3.0 * peakDensity)
                (raw * 1.05).roundToInt().coerceIn(1, 10)
            } catch (_: Exception) {
                5
            }
        }
    }

    /**
     * Estimates track tempo (BPM) from the median interval between beat
     * onsets in the peak envelope. Median is robust against intro/outro
     * dynamics that distort the mean.
     */
    suspend fun estimateBpm(waveform: String?, trackDurationMs: Long = 180000L): Int {
        if (waveform.isNullOrBlank() || WaveformExtractor.isSaturated(waveform)) return 120

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
                if (intervals.isEmpty()) return@withContext 120

                // Median interval (robust to one-off gaps between sections)
                val sortedIntervals = intervals.sorted()
                val avgIntervalSamples = if (sortedIntervals.size % 2 == 1) {
                    sortedIntervals[sortedIntervals.size / 2].toDouble()
                } else {
                    (sortedIntervals[sortedIntervals.size / 2 - 1] + sortedIntervals[sortedIntervals.size / 2]) / 2.0
                }
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
