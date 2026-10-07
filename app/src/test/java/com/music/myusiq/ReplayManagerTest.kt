package com.music.myusiq

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.music.myusiq.utils.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.*

class ReplayManagerTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `getActiveReplays returns Yearly and Monthly in early January`() {
        // Given: Jan 2nd, 2026 (Friday)
        val calendar = GregorianCalendar(2026, Calendar.JANUARY, 2, 12, 0, 0)

        // When
        val replays = ReplayManager.getActiveReplays(calendar)

        // Then
        assertEquals(2, replays.size)
        assertTrue(replays.any { it.type == ReplayType.YEARLY })
        assertTrue(replays.any { it.type == ReplayType.MONTHLY })
        assertTrue(replays.none { it.type == ReplayType.WEEKLY }) // Friday is not Mon-Wed
    }

    @Test
    fun `getActiveReplays returns Weekly on a Monday mid-month`() {
        // Given: Jan 19th, 2026 (Monday)
        val calendar = GregorianCalendar(2026, Calendar.JANUARY, 19, 12, 0, 0)
        // Verify day of week is indeed Monday
        assertEquals(Calendar.MONDAY, calendar.get(Calendar.DAY_OF_WEEK))

        // When
        val replays = ReplayManager.getActiveReplays(calendar)

        // Then
        assertEquals("Should only have Weekly recap", 1, replays.size)
        assertTrue(replays.any { it.type == ReplayType.WEEKLY })
        assertTrue(replays.none { it.type == ReplayType.YEARLY })
        assertTrue(replays.none { it.type == ReplayType.MONTHLY })
    }

    @Test
    fun `getActiveReplays returns empty list in mid-month weekend`() {
        // Given: Jan 18th, 2026 (Sunday)
        val calendar = GregorianCalendar(2026, Calendar.JANUARY, 18, 12, 0, 0)
        assertEquals(Calendar.SUNDAY, calendar.get(Calendar.DAY_OF_WEEK))

        // When
        val replays = ReplayManager.getActiveReplays(calendar)

        // Then
        assertTrue(replays.isEmpty())
    }

    @Test
    fun `isDismissed returns true when shared preferences has the key`() {
        // Given
        val context = mockk<Context>()
        val prefs = mockk<SharedPreferences>()
        every { context.getSharedPreferences("replay_prefs", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getBoolean("dismissed_TEST_KEY", false) } returns true

        // When
        val result = ReplayManager.isDismissed(context, "TEST_KEY")

        // Then
        assertTrue(result)
    }
}
