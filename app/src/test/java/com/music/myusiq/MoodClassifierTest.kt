package com.music.myusiq

import com.music.myusiq.domain.models.PlaylistProfile
import com.music.myusiq.domain.usecase.MoodClassifier
import com.music.myusiq.domain.usecase.MoodEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodClassifierTest {

    private fun signals(
        energy: Int = -1,
        bpm: Int = -1,
        genre: String = "",
        title: String = "",
        lyrics: String = "",
        extraGenres: Set<String> = emptySet(),
    ) = MoodClassifier.TrackSignals(energy, bpm, genre, title, lyrics, extraGenres)

    @Test
    fun `high energy dance track classifies as energize or party`() {
        val result = MoodClassifier.classify(
            signals(energy = 9, bpm = 128, genre = "EDM / Dance", title = "Turn It Up")
        )
        assertTrue(result.primaryMood == MoodClassifier.MOOD_ENERGIZE || result.primaryMood == MoodClassifier.MOOD_PARTY)
        assertTrue(result.confidence >= MoodClassifier.CONFIDENCE_THRESHOLD)
    }

    @Test
    fun `lofi low energy track classifies as relax or focus`() {
        val result = MoodClassifier.classify(
            signals(energy = 2, bpm = 78, genre = "Lo-Fi Chillhop", title = "Quiet Window")
        )
        assertTrue(result.primaryMood == MoodClassifier.MOOD_RELAX || result.primaryMood == MoodClassifier.MOOD_FOCUS)
    }

    @Test
    fun `unknown genre with online trap tag classifies energetic`() {
        val result = MoodClassifier.classify(
            signals(energy = 8, bpm = 140, genre = "Unknown Genre", title = "No Tag Song", extraGenres = setOf("trap"))
        )
        assertTrue(result.primaryMood == MoodClassifier.MOOD_ENERGIZE || result.primaryMood == MoodClassifier.MOOD_PARTY)
    }

    @Test
    fun `romance keywords beat neutral audio`() {
        val result = MoodClassifier.classify(
            signals(energy = 4, bpm = 88, genre = "", title = "I Love You")
        )
        assertEquals(MoodClassifier.MOOD_ROMANCE, result.primaryMood)
    }

    @Test
    fun `ambiguous track gets no primary mood`() {
        // No audio, no genre, no keywords
        val result = MoodClassifier.classify(signals(title = "Track 12"))
        assertEquals("", result.primaryMood)
        assertTrue(result.confidence < MoodClassifier.CONFIDENCE_THRESHOLD)
    }

    @Test
    fun `no signals at all yields empty scoreboard`() {
        val result = MoodClassifier.classify(signals())
        assertTrue(result.scores.values.all { it == 0.0 })
        assertEquals("", result.primaryMood)
    }

    @Test
    fun `learning captures energy profile and ranks similar tracks higher`() {
        // Simulate a gym playlist: five energetic tracks, one mellow outlier
        val gymSignals = List(5) { signals(energy = 9, bpm = 130, genre = "Rock", title = "Power Track $it") } +
            signals(energy = 3, bpm = 80, genre = "Lo-Fi", title = "Slow one")
        val profile = MoodClassifier.learn(gymSignals)!!
        assertTrue(profile.sampleSize == 6)
        assertTrue(profile.meanEnergy > 7.0)

        val energetic = MoodClassifier.learnedFit(profile, signals(energy = 9, bpm = 132, genre = "Metal"))
        val mellow = MoodClassifier.learnedFit(profile, signals(energy = 2, bpm = 75, genre = "Sleep"))
        assertTrue(energetic > mellow)
        assertTrue(energetic > 0.6)
        assertTrue(mellow < 0.4)
    }

    @Test
    fun `playlist name maps to mood hints`() {
        assertTrue(MoodClassifier.moodsForPlaylistName("Gym Anthems 💪").contains(MoodClassifier.MOOD_ENERGIZE))
        assertTrue(MoodClassifier.moodsForPlaylistName("Chill Sunday").contains(MoodClassifier.MOOD_RELAX))
        assertTrue(MoodClassifier.moodsForPlaylistName("Love Songs").contains(MoodClassifier.MOOD_ROMANCE))
        assertTrue(MoodClassifier.moodsForPlaylistName("Road Trip 2024").contains(MoodClassifier.MOOD_DRIVE))
        assertFalse(MoodClassifier.moodsForPlaylistName("Downloads").contains(MoodClassifier.MOOD_ENERGIZE))
    }

    @Test
    fun `mood score serialization round trips`() {
        val scores = mapOf(
            MoodClassifier.MOOD_ENERGIZE to 0.72,
            MoodClassifier.MOOD_RELAX to 0.11,
            MoodClassifier.MOOD_FOCUS to 0.0,
        )
        val serialized = MoodClassifier.serializeMoodScores(scores)
        val parsed = MoodClassifier.parseMoodScores(serialized)
        assertEquals(0.72, parsed[MoodClassifier.MOOD_ENERGIZE]!!, 1e-9)
        assertEquals(0.11, parsed[MoodClassifier.MOOD_RELAX]!!, 1e-9)
        assertEquals(0.0, parsed[MoodClassifier.MOOD_FOCUS]!!, 1e-9)
    }

    @Test
    fun `learned profile with zero sample size yields no fit`() {
        val emptyProfile = PlaylistProfile(profileKey = "playlist:1", sampleSize = 0)
        assertEquals(0.0, MoodClassifier.learnedFit(emptyProfile, signals(energy = 9)), 1e-9)
    }
    @Test
    fun `keyword evidence separates Indian romance titles from non-romance`() {
        val ishq = MoodClassifier.keywordMoodEvidence("Aakhri Ishq (From \"Dhurandhar The Revenge\")")
        assertTrue("ishq should evidence romance, got $ishq", "romance" in ishq)

        val didi = MoodClassifier.keywordMoodEvidence("Didi (Sher-E-Baloch) [From \"Dhurandhar The Revenge\"]")
        assertFalse("Didi has no romance keyword, got $didi", "romance" in didi)
    }

    @Test
    fun `keyword evidence covers english keywords and party kuthu`() {
        val love = MoodClassifier.keywordMoodEvidence("Love Story")
        assertTrue("love song should evidence romance, got $love", "romance" in love)

        val kuthu = MoodClassifier.keywordMoodEvidence("Kuthu Fire Dance")
        assertTrue("kuthu should evidence party, got $kuthu", "party" in kuthu)

        val plain = MoodClassifier.keywordMoodEvidence("23 Theme")
        assertTrue("plain title should carry no evidence, got $plain", plain.isEmpty())
    }

    @Test
    fun `listening feedback ema responds to completions and skips`() {
        var fb = MoodClassifier.nextListenFeedback(0.0, 0.95f) // full listen
        assertEquals(0.3, fb, 1e-9)
        fb = MoodClassifier.nextListenFeedback(fb, 0.9f)
        assertEquals(0.51, fb, 1e-9)

        fb = MoodClassifier.nextListenFeedback(0.0, 0.1f) // early skip
        assertEquals(-0.3, fb, 1e-9)
        fb = MoodClassifier.nextListenFeedback(fb, 0.05f)
        assertEquals(-0.51, fb, 1e-9)

        // Middle-band playback (partial listen) leaves feedback unchanged
        assertEquals(0.4, MoodClassifier.nextListenFeedback(0.4, 0.5f), 1e-9)
        // Bounds respected
        assertEquals(-1.0, MoodClassifier.nextListenFeedback(-1.0, 0.0f), 1e-9)
        assertEquals(1.0, MoodClassifier.nextListenFeedback(1.0, 1.0f), 1e-9)
    }

}
