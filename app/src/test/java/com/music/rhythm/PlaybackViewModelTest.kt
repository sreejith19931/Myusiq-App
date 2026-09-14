package com.music.rhythm

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Environment
import android.os.Looper
import android.util.Log
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.music.rhythm.data.AppDatabase
import com.music.rhythm.data.dao.PlaylistDao
import com.music.rhythm.data.dao.SongDao
import com.music.rhythm.data.dao.SongHistoryDao
import com.music.rhythm.service.PlaybackService
import com.music.rhythm.service.PlaybackPersistence
import com.music.rhythm.ui.viewmodels.PlaybackViewModel
import com.music.rhythm.utils.PlatformUtils
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import androidx.media3.session.MediaController
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    
    private val application = mockk<MainApplication>(relaxed = true)
    private val persistence = mockk<PlaybackPersistence>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val songDao = mockk<SongDao>(relaxed = true)
    private val playlistDao = mockk<PlaylistDao>(relaxed = true)
    private val songHistoryDao = mockk<SongHistoryDao>(relaxed = true)
    private val audioManager = mockk<AudioManager>(relaxed = true)
    private val mediaController = mockk<MediaController>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.v(any<String>(), any<String>()) } returns 0
        
        every { application.applicationContext } returns application
        every { application.packageName } returns "com.music.rhythm"
        
        mockkObject(DatabaseProvider)
        every { DatabaseProvider.getDatabase(any()) } returns database
        every { database.songDao() } returns songDao
        every { database.playlistDao() } returns playlistDao
        every { database.songHistoryDao() } returns songHistoryDao
        
        every { application.playbackPersistence } returns persistence
        every { application.getSystemService(Context.AUDIO_SERVICE) } returns audioManager
        
        mockkStatic(Environment::class)
        every { Environment.getExternalStorageDirectory() } returns File("/mock")
        
        mockkStatic(Looper::class)
        val mockLooper = mockk<Looper>(relaxed = true)
        every { Looper.getMainLooper() } returns mockLooper
        
        mockkObject(PlatformUtils)
        every { PlatformUtils.normalizePath(any()) } answers { it.invocation.args[0] as String }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `setShuffleEnabled updates state flow`() = runTest {
        // Given
        val viewModel = PlaybackViewModel(application)
        val field = PlaybackViewModel::class.java.getDeclaredField("mediaController")
        field.isAccessible = true
        field.set(viewModel, mediaController)
        
        // When
        viewModel.setShuffleEnabled(true)
        
        // Then
        verify { mediaController.shuffleModeEnabled = true }
    }

    @Test
    fun `setRepeatState updates state flow`() = runTest {
        // Given
        val viewModel = PlaybackViewModel(application)
        val field = PlaybackViewModel::class.java.getDeclaredField("mediaController")
        field.isAccessible = true
        field.set(viewModel, mediaController)
        
        // When
        viewModel.setRepeatState(PlaybackService.RepeatState.ONE)
        
        // Then
        verify { mediaController.repeatMode = any() }
    }
}
