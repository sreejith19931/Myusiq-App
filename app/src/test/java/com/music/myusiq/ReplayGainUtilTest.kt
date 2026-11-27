package com.music.myusiq

import com.music.myusiq.utils.ReplayGainUtil
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGainUtilTest {

    @org.junit.Before
    fun setUp() {
        ReplayGainUtil.clearCache()
    }

    @After
    fun tearDown() {
        ReplayGainUtil.clearCache()
    }

    private fun rmsWaveform(mean: Float, count: Int = 300, jitter: Float = 0.01f): String {
        return (0 until count).joinToString(",") { i ->
            val v = mean + (if (i % 2 == 0) jitter else -jitter)
            v.toString()
        }
    }

    @Test
    fun `loud master waveform yields a reduction factor`() = runBlocking {
        val factor = ReplayGainUtil.factorFromWaveform(rmsWaveform(0.35f))
        assertTrue("expected < 1.0 but got $factor", factor != null && factor < 1.0f)
        assertTrue("expected >= 0.5 but got $factor", factor == null || factor >= 0.5f)
    }

    @Test
    fun `quiet waveform is boosted and clamped to 1_25`() = runBlocking {
        val factor = ReplayGainUtil.factorFromWaveform(rmsWaveform(0.08f))
        assertEquals(1.25f, factor!!, 0.01f)
    }

    @Test
    fun `medium waveform stays near unity`() = runBlocking {
        val factor = ReplayGainUtil.factorFromWaveform(rmsWaveform(0.25f))
        assertTrue("expected ~1.0-1.2 but got $factor", factor != null && factor in 1.0f..1.25f)
    }

    @Test
    fun `legacy peak envelope is rejected`() = runBlocking {
        // Legacy extractors store near-full-scale peaks (0.9-1.0)
        val legacy = (0 until 300).joinToString(",") { "0.95" }
        assertEquals(null, ReplayGainUtil.factorFromWaveform(legacy))
    }

    @Test
    fun `saturated envelope is rejected`() = runBlocking {
        val saturated = (0 until 300).joinToString(",") { "0.99" }
        assertEquals(null, ReplayGainUtil.factorFromWaveform(saturated))
    }

    @Test
    fun `silent waveform returns null`() = runBlocking {
        val silent = (0 until 300).joinToString(",") { "0.0" }
        assertEquals(null, ReplayGainUtil.factorFromWaveform(silent))
    }

    @Test
    fun `missing waveform returns null and default path is unity`() = runBlocking {
        assertEquals(null, ReplayGainUtil.factorFromWaveform(null))
        assertEquals(null, ReplayGainUtil.factorFromWaveform(""))

        val factor = ReplayGainUtil.getVolumeFactorForFile("missing_no_waveform.mp3", true)
        assertEquals(1.0f, factor, 0.001f)
    }

    @Test
    fun `no usable waveform after tag miss falls back to unity`() = runBlocking {
        val factor = ReplayGainUtil.getVolumeFactorForFile("missing_silent_waveform.mp3", true) { "0.0,0.0,0.0" }
        assertEquals(1.0f, factor, 0.001f)
    }

    @Test
    fun `usable waveform fills in when file has no tag`() = runBlocking {
        val factor = ReplayGainUtil.getVolumeFactorForFile("missing_loud_waveform.mp3", true) { rmsWaveform(0.35f) }
        assertTrue("expected reduction, got $factor", factor < 1.0f)
    }
}
