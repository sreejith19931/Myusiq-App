package com.music.myusiq.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt

object MusicEnergyAnalyzer {

    /**
     * Analyzes track energy based on waveform variance and peak density.
     * Higher variance and frequent high peaks = Higher energy.
     */
    suspend fun analyze(waveform: String?): Int {
        if (waveform.isNullOrBlank()) return 5 // Default middle-ground
        
        return withContext(Dispatchers.Default) {
            try {
                val samples = waveform.split(",").mapNotNull { it.toFloatOrNull() }
                if (samples.isEmpty()) return@withContext 5

                // 1. RMS (Root Mean Square) for overall loudness/power
                val rms = sqrt(samples.asSequence().map { it * it }.average()).toFloat()

                // 2. Variance (How much the amplitude changes)
                val average = samples.average()
                val variance = samples.asSequence().map { abs(it - average) }.average().toFloat()

                // 3. Peak Density (Frequent spikes above 80% max)
                val max = samples.maxOrNull() ?: 1f
                val peakThreshold = max * 0.8f
                val peaks = samples.count { it > peakThreshold }
                val peakDensity = peaks.toFloat() / samples.size.toFloat()

                // Normalize scores to 1-10 range
                val score = (rms * 4) + (variance * 3) + (peakDensity * 3)
                val finalScore = (score * 15).toInt().coerceIn(1, 10)
                finalScore
            } catch (_: Exception) {
                5
            }
        }
    }
}
