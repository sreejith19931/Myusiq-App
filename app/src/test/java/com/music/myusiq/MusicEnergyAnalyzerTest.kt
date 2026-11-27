package com.music.myusiq

import com.music.myusiq.utils.MusicEnergyAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicEnergyAnalyzerTest {

    private fun envelope(vararg values: Float) = values.joinToString(",") { it.toString() }

    @Test
    fun `analyze returns saturated guard for corrupt all-max waveforms`() {
        val saturated = List(100) { 0.99f }.joinToString(",")
        assertEquals(5, MusicEnergyAnalyzer.analyze(saturated))
        assertEquals(120, MusicEnergyAnalyzer.estimateBpm(saturated))
    }

    @Test
    fun `analyze rejects legacy peak-envelope waveforms as unusable`() {
        // Legacy peak envelopes mix full-scale peaks with quiet windows;
        // they must never count as real audio evidence.
        val legacy = List(100) { i -> if (i % 3 == 0) 0.95f else 0.2f }.joinToString(",")
        assertEquals(5, MusicEnergyAnalyzer.analyze(legacy))
        assertEquals(120, MusicEnergyAnalyzer.estimateBpm(legacy))
    }

    @Test
    fun `analyze maps loud RMS envelope to high energy`() {
        val loud = List(100) { 0.30f }.joinToString(",")
        assertTrue("expected >=8, got ${MusicEnergyAnalyzer.analyze(loud)}", MusicEnergyAnalyzer.analyze(loud) >= 8)
    }

    @Test
    fun `analyze maps quiet RMS envelope to low energy`() {
        val quiet = List(100) { 0.06f }.joinToString(",")
        assertTrue("expected <=4, got ${MusicEnergyAnalyzer.analyze(quiet)}", MusicEnergyAnalyzer.analyze(quiet) <= 4)
    }

    @Test
    fun `estimateBpm terminates and stays in range for sparse two-peak envelope`() {
        // Regression: only two peaks far apart rounded the raw BPM to 0 and
        // the doubling loop (`while (bpm < 70) bpm *= 2`) spun forever.
        val sparse = List(99) { i -> if (i == 0 || i == 98) 0.9f else 0.05f }.joinToString(",")
        val bpm = MusicEnergyAnalyzer.estimateBpm(sparse, 180_000L)
        assertTrue("expected bpm in 70..180, got $bpm", bpm in 70..180)
    }

    @Test
    fun `estimateBpm returns in range for regular beat envelope`() {
        // Peaks every 4 windows on a 3-minute track → ~40 BPM contour → doubled into range.
        val regular = List(100) { i -> if (i % 4 == 0) 0.9f else 0.05f }.joinToString(",")
        val bpm = MusicEnergyAnalyzer.estimateBpm(regular, 180_000L)
        assertTrue("expected bpm in 70..180, got $bpm", bpm in 70..180)
    }

    @Test
    fun `estimateBpm handles null and blank waveforms`() {
        assertEquals(120, MusicEnergyAnalyzer.estimateBpm(null))
        assertEquals(120, MusicEnergyAnalyzer.estimateBpm(""))
    }

    @Test
    fun `estimateHarmonicKey returns valid camelot key`() {
        val key = MusicEnergyAnalyzer.estimateHarmonicKey("0.1,0.2,0.3")
        assertTrue(key in setOf("1A", "1B", "2A", "2B", "3A", "3B", "4A", "4B", "5A", "5B", "6A", "6B", "7A", "7B", "8A", "8B", "9A", "9B", "10A", "10B", "11A", "11B", "12A", "12B"))
    }
}
