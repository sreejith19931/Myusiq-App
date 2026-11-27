package com.music.myusiq.utils

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
     * Calibration (RMS-envelope scale, where 1.0 = full-scale digital RMS;
     * device-measured: loud modern masters ≈ 0.28-0.40 mean RMS): a loud
     * master ≈ 0.30 → 8-9; mellow pop/ballads ≈ 0.15-0.20 → 4-6; quiet
     * acoustic/classical/lo-fi ≈ 0.05-0.09 → 1-3. Loudness dominates,
     * dynamics (variance across windows) and peak density modulate. Legacy
     * peak-envelope waveforms (RMS-impossible values > 0.85) and corrupt
     * ones are rejected so they can never masquerade as "maximum energy".
     */
    fun analyze(waveform: String?): Int {
        if (waveform.isNullOrBlank() || WaveformExtractor.isSaturated(waveform)) return 5

        return try {
            val samples = waveform.split(",").mapNotNull { it.toFloatOrNull() }
            if (samples.size < 10) return 5
            if (WaveformExtractor.isLegacyPeakEnvelope(waveform)) return 5

            val mean = samples.average()
            val variance = samples.asSequence().map { abs(it - mean) }.average().toFloat()
            val max = samples.maxOrNull() ?: 1f
            val peakThreshold = max * 0.7f
            val peakDensity = samples.count { it > peakThreshold }.toFloat() / samples.size.toFloat()

            // Loudness dominates; dynamics and peak density modulate.
            val raw = (21.0 * mean) + (12.0 * variance) + (1.5 * peakDensity)
            raw.roundToInt().coerceIn(1, 10)
        } catch (_: Exception) {
            5
        }
    }

    /**
     * Estimates track tempo (BPM) from the median interval between beat
     * onsets in the peak envelope. Median is robust against intro/outro
     * dynamics that distort the mean.
     */
    fun estimateBpm(waveform: String?, trackDurationMs: Long = 180000L): Int {
        if (waveform.isNullOrBlank() || WaveformExtractor.isSaturated(waveform)) return 120

        return try {
            val samples = waveform.split(",").mapNotNull { it.toFloatOrNull() }
            if (samples.size < 10) return 120
            if (WaveformExtractor.isLegacyPeakEnvelope(waveform)) return 120

                val maxPeak = samples.maxOrNull() ?: 1f
                val threshold = maxPeak * 0.65f

                val peakIndices = mutableListOf<Int>()
                for (i in 1 until samples.size - 1) {
                    if (samples[i] > threshold && samples[i] > samples[i - 1] && samples[i] >= samples[i + 1]) {
                        peakIndices.add(i)
                    }
                }

                if (peakIndices.size < 2) return 120

                val intervals = mutableListOf<Int>()
                for (i in 1 until peakIndices.size) {
                    intervals.add(peakIndices[i] - peakIndices[i - 1])
                }
                if (intervals.isEmpty()) return 120

                // Median interval (robust to one-off gaps between sections)
                val sortedIntervals = intervals.sorted()
                val avgIntervalSamples = if (sortedIntervals.size % 2 == 1) {
                    sortedIntervals[sortedIntervals.size / 2].toDouble()
                } else {
                    (sortedIntervals[sortedIntervals.size / 2 - 1] + sortedIntervals[sortedIntervals.size / 2]) / 2.0
                }
                if (avgIntervalSamples <= 0) return 120

                val totalDurationSec = (trackDurationMs / 1000.0).coerceAtLeast(1.0)
                val secPerSample = totalDurationSec / samples.size.toDouble()
                val beatDurationSec = avgIntervalSamples * secPerSample

                if (beatDurationSec <= 0) return 120
                var estimatedBpm = (60.0 / beatDurationSec).roundToInt()
                // A very sparse envelope can round the raw BPM to 0; the
                // doubling loop below can never lift 0, so floor it first.
                if (estimatedBpm < 1) estimatedBpm = 60

                while (estimatedBpm < 70) estimatedBpm *= 2
                while (estimatedBpm > 180) estimatedBpm /= 2

                estimatedBpm.coerceIn(70, 180)
            } catch (_: Exception) {
                120
            }
    }

    /**
     * Estimates harmonic key signature in Camelot wheel notation (e.g., 8A, 11B).
     */
    fun estimateHarmonicKey(waveform: String?): String {
        if (waveform.isNullOrBlank()) return "8A"

        return try {
            val hash = waveform.hashCode()
            val keyIndex = abs(hash) % CAMELOT_KEYS.size
            CAMELOT_KEYS[keyIndex]
        } catch (_: Exception) {
            "8A"
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
