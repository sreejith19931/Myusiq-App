package com.music.myusiq.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt

object MusicEnergyAnalyzer {

    /**
     * Analyzes track energy based on waveform RMS power, amplitude variance, and peak density.
     * Higher RMS and frequent high peaks = Higher energy (6-10).
     * Low RMS and smooth variance = Low energy (1-4).
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

                // Calculate energy score weighting RMS power and peak density heavily
                val score = (rms * 5.0f) + (variance * 2.5f) + (peakDensity * 3.5f)
                val finalScore = (score * 16.0f).toInt().coerceIn(1, 10)
                finalScore
            } catch (_: Exception) {
                5
            }
        }
    }
}
