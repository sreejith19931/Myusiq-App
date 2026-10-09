package com.music.myusiq.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.net.Uri
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnticipateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.music.myusiq.domain.models.Song
import com.music.myusiq.domain.models.TopTrack
import com.music.myusiq.data.dao.SongHistoryDao
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.card.MaterialCardView
import com.google.android.material.navigationrail.NavigationRailView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import com.music.myusiq.R
import com.music.myusiq.MainApplication
import com.music.myusiq.ui.adapters.RecapAdapter
import com.music.myusiq.ui.viewmodels.PlaybackViewModel
import com.music.myusiq.utils.*
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds

class MainActivity : AppCompatActivity(), SensorEventListener {
    private val viewModel: PlaybackViewModel by viewModels()
    private lateinit var themeSettingsManager: ThemeSettingsManager
    private lateinit var themeManager: ThemeManager
    private lateinit var permissionManager: PermissionManager
    
    private lateinit var miniPlayerBar: LinearLayout
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    private lateinit var bottomNavigationView: BottomNavigationView
    private var navigationRail: NavigationRailView? = null
    private lateinit var swipablePlayerSheet: View
    private lateinit var fullPlayerContainer: View
    private lateinit var layoutExitConfirm: View
    
    private lateinit var deleteResultLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var editActionLauncher: ActivityResultLauncher<IntentSenderRequest>
    
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var shakeTimestamp: Long = 0
    private var isNavigating = false
    private var exitConfirmJob: Job? = null

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val overlay = findViewById<View>(R.id.fullScreenOverlayContainer)
            if (overlay.isVisible) {
                hideFullScreenOverlay()
            } else if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            } else if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
            } else if (layoutExitConfirm.isVisible) {
                moveTaskToBack(true)
                finish()
            } else {
                showExitConfirmPopup()
            }
        }
    }

    private fun showExitConfirmPopup() {
        layoutExitConfirm.findViewById<View>(R.id.btnDismissExit).setOnClickListener {
            hideExitConfirmPopup()
        }
        
        layoutExitConfirm.visibility = View.VISIBLE
        layoutExitConfirm.alpha = 0f
        layoutExitConfirm.animate()
            .alpha(1f)
            .setDuration(300L)
            .start()
            
        exitConfirmJob?.cancel()
        exitConfirmJob = lifecycleScope.launch {
            delay(5.seconds)
            hideExitConfirmPopup()
        }
    }

    private fun hideExitConfirmPopup() {
        if (!::layoutExitConfirm.isInitialized || (layoutExitConfirm.visibility != View.VISIBLE)) return
        
        layoutExitConfirm.animate()
            .alpha(0f)
            .setDuration(300L)
            .withEndAction { 
                layoutExitConfirm.visibility = View.GONE 
            }
            .start()
        
        exitConfirmJob?.cancel()
        exitConfirmJob = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::bottomSheetBehavior.isInitialized) {
            outState.putInt("player_sheet_state", bottomSheetBehavior.state)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        themeSettingsManager = ThemeSettingsManager(this)
        themeManager = ThemeManager(this)
        permissionManager = PermissionManager(this)
        sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)

        // Synchronously fetch and apply theme/color settings before super.onCreate to prevent flickering.
        runBlocking {
            try {
                val mode = themeSettingsManager.themeModeFlow.first()
                val style = themeSettingsManager.colorStyleFlow.first()
                val useCustomFont = themeSettingsManager.useCustomFontFlow.first()
                themeSettingsManager.applyThemeMode(mode)
                themeSettingsManager.applyColorStyle(this@MainActivity, style, useCustomFont)
            } catch (e: Exception) {
                Log.e("MainActivity", "Error applying theme sync", e)
            }
        }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContentView(R.layout.activity_main)

        setupUI()
        setupViewModelObservers()
        viewModel.bindToPlaybackService(this)
        setupLaunchers()
        
        savedInstanceState?.let {
            val state = it.getInt("player_sheet_state", BottomSheetBehavior.STATE_COLLAPSED)
            // Apply state immediately if possible, or use post to ensure view is measured
            bottomSheetBehavior.state = state
            val offset = if (state == BottomSheetBehavior.STATE_EXPANDED) 1f else 0f
            syncNavigationState(offset)
            swipablePlayerSheet.post {
                (supportFragmentManager.findFragmentByTag("TAB_PLAYER") as? PlayerFragment)?.setTransitionAlpha(offset)
            }
        }
        
        val availableReplay = ReplayManager.getAvailableReplay(this)
        if (availableReplay != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val db = (application as MainApplication).database
                val topTracks = db.songHistoryDao().getTopTracksInRange(availableReplay.startTime, availableReplay.endTime, 10)
                if (topTracks.size >= 5) {
                    withContext(Dispatchers.Main) { showReplayOverlay(availableReplay) }
                }
            }
        }
        
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fullPlayerFragmentContainer, PlayerFragment(), "TAB_PLAYER")
                .commit()
        }

        permissionManager.onStoragePermissionGranted = {
            viewModel.loadSongs()
            permissionManager.checkNotificationPermission()
        }
        permissionManager.checkAndRequestStoragePermission()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        
        lifecycleScope.launch {
            delay(2.seconds)
            val updateInfo = UpdateManager.checkForUpdates(this@MainActivity, force = false)
            if (updateInfo != null && updateInfo.hasUpdate) {
                UpdateManager.showUpdateDialog(this@MainActivity, updateInfo)
            }
        }

        onBackPressedDispatcher.addCallback(this, backPressedCallback)
    }

    private fun setupUI() {
        miniPlayerBar = findViewById(R.id.miniPlayerBar)
        bottomNavigationView = findViewById(R.id.bottomNavigationView)
        navigationRail = findViewById(R.id.navigationRail)
        swipablePlayerSheet = findViewById(R.id.swipablePlayerSheet)
        fullPlayerContainer = findViewById(R.id.fullPlayerFragmentContainer)
        layoutExitConfirm = findViewById(R.id.layoutExitConfirm)
        bottomSheetBehavior = BottomSheetBehavior.from(swipablePlayerSheet)
        
        bottomNavigationView.post { updatePeekHeight() }
        
        themeManager.setupWindowInsets(
            findViewById(R.id.rootContainer),
            findViewById(R.id.navHostFragment),
            bottomNavigationView,
            navigationRail,
            findViewById(R.id.playerSheetNavSpacer),
            layoutExitConfirm
        ) { bottomNavigationView.post { updatePeekHeight() } }

        bottomSheetBehavior.isDraggable = true
        bottomSheetBehavior.isHideable = false 
        
        val expandListener = View.OnClickListener {
            if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED) {
                expandFullPlayer()
            }
        }
        
        miniPlayerBar.setOnClickListener(expandListener)
        
        val miniPlayerGestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                    if (bottomSheetBehavior.state != BottomSheetBehavior.STATE_COLLAPSED) return false
                    val deltaX = (e1?.x ?: 0f) - e2.x
                    if (abs(deltaX) > 80 && abs(velocityX) > 80) {
                        if (deltaX > 0) viewModel.playNextSong() else viewModel.playPreviousSong()
                        return true
                    }
                    return false
                }
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean = false
            }
        )
        
        @SuppressLint("ClickableViewAccessibility")
        miniPlayerBar.setOnTouchListener { _, event ->
            miniPlayerGestureDetector.onTouchEvent(event)
        }

        swipablePlayerSheet.setOnClickListener(expandListener)
        fullPlayerContainer.setOnClickListener(expandListener)
        @SuppressLint("ClickableViewAccessibility")
        fullPlayerContainer.setOnTouchListener { _, event ->
            if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED) {
                miniPlayerGestureDetector.onTouchEvent(event)
            } else {
                false
            }
        }
        
        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                syncNavigationState(if (newState == BottomSheetBehavior.STATE_EXPANDED) 1f else if (newState == BottomSheetBehavior.STATE_COLLAPSED) 0f else -1f)
                
                when (newState) {
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        miniPlayerBar.visibility = View.GONE
                        miniPlayerBar.elevation = 0f
                        miniPlayerBar.background?.alpha = 0
                        fullPlayerContainer.alpha = 1f
                        fullPlayerContainer.visibility = View.VISIBLE
                        (supportFragmentManager.findFragmentByTag("TAB_PLAYER") as? PlayerFragment)?.setTransitionAlpha(1f)
                        swipablePlayerSheet.elevation = 40 * resources.displayMetrics.density
                        
                        val themeSurface = ThemeUtils.getThemeColor(this@MainActivity, com.google.android.material.R.attr.colorSurface)
                        val adaptiveColor = viewModel.currentBgColor.value ?: themeSurface
                        miniPlayerBar.background?.mutate()?.setTint(adaptiveColor)
                    }
                    BottomSheetBehavior.STATE_COLLAPSED -> {
                        miniPlayerBar.visibility = View.VISIBLE
                        miniPlayerBar.elevation = 0f
                        miniPlayerBar.alpha = 1f
                        miniPlayerBar.background?.alpha = 255
                        fullPlayerContainer.alpha = 1f
                        fullPlayerContainer.elevation = 4 * resources.displayMetrics.density
                        fullPlayerContainer.visibility = View.VISIBLE
                        (supportFragmentManager.findFragmentByTag("TAB_PLAYER") as? PlayerFragment)?.setTransitionAlpha(0f)
                        swipablePlayerSheet.elevation = 16 * resources.displayMetrics.density
                        
                        val themeSurface = ThemeUtils.getThemeColor(this@MainActivity, com.google.android.material.R.attr.colorSurface)
                        val adaptiveColor = viewModel.currentBgColor.value ?: themeSurface
                        miniPlayerBar.background?.mutate()?.setTint(adaptiveColor)
                    }
                    BottomSheetBehavior.STATE_HIDDEN -> {
                        miniPlayerBar.visibility = View.GONE
                    }
                    BottomSheetBehavior.STATE_DRAGGING, BottomSheetBehavior.STATE_SETTLING, BottomSheetBehavior.STATE_HALF_EXPANDED -> {
                        miniPlayerBar.visibility = View.VISIBLE
                        fullPlayerContainer.visibility = View.VISIBLE
                    }
                }
            }
            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                val offset = slideOffset.coerceIn(0f, 1f)
                syncNavigationState(offset)

                val themeSurface = ThemeUtils.getThemeColor(this@MainActivity, com.google.android.material.R.attr.colorSurface)
                val adaptiveColor = viewModel.currentBgColor.value ?: themeSurface
                
                miniPlayerBar.background?.mutate()?.setTint(adaptiveColor)

                miniPlayerBar.background.alpha = ((1f - offset) * 255).toInt()
                (supportFragmentManager.findFragmentByTag("TAB_PLAYER") as? PlayerFragment)?.setTransitionAlpha(offset)

                miniPlayerBar.visibility = View.VISIBLE 
                fullPlayerContainer.alpha = 1f
                fullPlayerContainer.visibility = View.VISIBLE
            }
        })

        swipablePlayerSheet.post {
            val offset = if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) 1f else 0f
            (supportFragmentManager.findFragmentByTag("TAB_PLAYER") as? PlayerFragment)?.setTransitionAlpha(offset)
            syncNavigationState(offset)
            updatePeekHeight()
        }

        val savedTab = sharedPreferences.getInt("last_tab", R.id.dest_tracks)
        val validTab = if (bottomNavigationView.menu.findItem(savedTab) != null) savedTab else R.id.dest_tracks
        bottomNavigationView.selectedItemId = validTab
        navigationRail?.selectedItemId = validTab
        
        bottomNavigationView.setOnItemSelectedListener { item ->
            if (!isNavigating) {
                isNavigating = true
                if (navigationRail?.selectedItemId != item.itemId) {
                    navigationRail?.selectedItemId = item.itemId
                }
                navigateToTab(item.itemId)
                isNavigating = false
            }
            true
        }

        navigationRail?.setOnItemSelectedListener { item ->
            if (!isNavigating) {
                isNavigating = true
                if (bottomNavigationView.selectedItemId != item.itemId) {
                    bottomNavigationView.selectedItemId = item.itemId
                }
                navigateToTab(item.itemId)
                isNavigating = false
            }
            true
        }
        
        if (supportFragmentManager.findFragmentByTag("TAB_$validTab") == null) {
            navigateToTab(validTab, initial = true)
        }
    }

    private fun navigateToTab(id: Int, initial: Boolean = false) {
        val currentTab = sharedPreferences.getInt("last_tab", R.id.dest_tracks)
        sharedPreferences.edit { putInt("last_tab", id) }
        
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)

        val fragment = when(id) {
            R.id.dest_tracks -> TracksFragment()
            R.id.dest_playlists -> PlaylistsFragment()
            R.id.dest_queue -> QueueFragment()
            R.id.dest_albums -> AlbumsFragment()
            R.id.dest_folders -> FoldersFragment()
            R.id.dest_settings -> SettingsFragment()
            else -> TracksFragment()
        }
        
        val isSettings = id == R.id.dest_settings
        val tag = "TAB_$id"
        
        val transaction = supportFragmentManager.beginTransaction()
        transaction.setReorderingAllowed(true)
        
        if (!initial) {
            if (isSettings) {
                transaction.setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down, R.anim.slide_in_up, R.anim.slide_out_down)
            } else {
                val tabs = listOf(R.id.dest_tracks, R.id.dest_queue, R.id.dest_playlists, R.id.dest_albums, R.id.dest_folders, R.id.dest_settings)
                val oldIdx = tabs.indexOf(currentTab)
                val newIdx = tabs.indexOf(id)
                
                if (newIdx > oldIdx) {
                    transaction.setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
                } else {
                    transaction.setCustomAnimations(R.anim.slide_in_left, R.anim.slide_out_right)
                }
            }
        }

        transaction.replace(R.id.navHostFragment, fragment, tag).commit()
    }


    private fun setupViewModelObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.currentPlayingSong.collectLatest { song -> updatePlayerUI(song) } }
                launch { viewModel.expandPlayerSignal.collectLatest { if (it) { expandFullPlayer(); viewModel.consumeExpandPlayerSignal() } } }
                launch { viewModel.pendingDeleteIntent.collectLatest { it?.let { deleteResultLauncher.launch(it) } } }
                launch { viewModel.pendingWriteIntent.collectLatest { it?.let { editActionLauncher.launch(it) } } }
                
                launch { viewModel.currentBgColor.collectLatest { color -> 
                    val themeSurface = ThemeUtils.getThemeColor(this@MainActivity, com.google.android.material.R.attr.colorSurface)
                    val themePrimary = ThemeUtils.getThemeColor(this@MainActivity, androidx.appcompat.R.attr.colorPrimary)
                    
                    val adaptiveColor = color ?: themeSurface
                    
                    miniPlayerBar.background?.mutate()?.setTint(adaptiveColor)
                    
                    findViewById<View>(R.id.rootContainer).setBackgroundColor(themeSurface)
                    findViewById<View>(R.id.rootMainLayout).setBackgroundColor(Color.TRANSPARENT)
                    findViewById<View>(R.id.navHostFragment).setBackgroundColor(Color.TRANSPARENT)
                    
                    val playerSheet = findViewById<View>(R.id.swipablePlayerSheet)
                    (playerSheet as? MaterialCardView)?.setCardBackgroundColor(Color.TRANSPARENT)
                    playerSheet.setBackgroundColor(Color.TRANSPARENT)
                    
                    window.decorView.setBackgroundColor(themeSurface)

                    val iconColorStateList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked)),
                        intArrayOf(themePrimary, ColorUtils.setAlphaComponent(themePrimary, 160))
                    )
                    bottomNavigationView.backgroundTintList = ColorStateList.valueOf(themeSurface)
                    bottomNavigationView.itemIconTintList = iconColorStateList
                    bottomNavigationView.itemTextColor = iconColorStateList
                    
                    navigationRail?.backgroundTintList = ColorStateList.valueOf(themeSurface)
                    navigationRail?.itemIconTintList = iconColorStateList
                    navigationRail?.itemTextColor = iconColorStateList
                } }
                launch { viewModel.navigateToTab.collect { tabId ->
                    tabId?.let {
                        if (!isNavigating) {
                            isNavigating = true
                            val navView = findViewById<BottomNavigationView>(R.id.bottomNavigationView)
                            navView.selectedItemId = it
                            navigationRail?.selectedItemId = it
                            viewModel.consumeNavigateToTab()
                            isNavigating = false
                        }
                    }
                } }
            }
        }
    }

    private fun updatePlayerUI(song: Song?) {
        if (song == null) {
            if (!bottomSheetBehavior.isHideable) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                miniPlayerBar.visibility = View.VISIBLE
                miniPlayerBar.alpha = 1f
                miniPlayerBar.background?.alpha = 255
            } else {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                miniPlayerBar.visibility = View.GONE
            }
            return
        }
        
        if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_HIDDEN) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
        
        miniPlayerBar.visibility = View.VISIBLE
        miniPlayerBar.alpha = 1f
        miniPlayerBar.background?.alpha = 255
        
        fullPlayerContainer.visibility = View.VISIBLE
        fullPlayerContainer.alpha = 1f

        val playerFrag = supportFragmentManager.findFragmentByTag("TAB_PLAYER") as? PlayerFragment
        if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED) {
            playerFrag?.setTransitionAlpha(0f)
            miniPlayerBar.background?.alpha = 255
        } else if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
            playerFrag?.setTransitionAlpha(1f)
            miniPlayerBar.background?.alpha = 0
        }

        bottomSheetBehavior.isHideable = false
        updatePeekHeight()
    }



    private fun updatePeekHeight() {
        val miniHeight = resources.getDimensionPixelSize(R.dimen.mini_player_height)
        val navHeight = if (bottomNavigationView.isVisible) {
            bottomNavigationView.height
        } else 0
        
        if (navHeight > 0 || !bottomNavigationView.isVisible) {
            bottomSheetBehavior.peekHeight = miniHeight + navHeight
        } else {
            bottomSheetBehavior.peekHeight = miniHeight + resources.getDimensionPixelSize(R.dimen.bottom_nav_height)
        }
    }

    fun expandFullPlayer() {
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    fun collapseFullPlayer() {
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    fun isPlayerCollapsed(): Boolean {
        return bottomSheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED
    }

    fun playFromView(songs: List<Song>, index: Int, sourceView: View, shouldExpand: Boolean = true) {
        if (shouldExpand) {
            val location = IntArray(2)
            sourceView.getLocationOnScreen(location)
            val size = sourceView.width
            
            val playerFrag = supportFragmentManager.findFragmentByTag("TAB_PLAYER") as? PlayerFragment
            playerFrag?.setCustomMorphStart(location[0].toFloat(), location[1].toFloat(), size.toFloat())
            
            expandFullPlayer()
        }
        viewModel.playSong(songs, index, shouldExpand = false) 
    }

    private fun setupLaunchers() {
        deleteResultLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { 
            if (it.resultCode == RESULT_OK) viewModel.processTrackDeletion()
            else viewModel.consumePendingDeleteIntent()
        }
        editActionLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            if (it.resultCode == RESULT_OK) viewModel.executePendingWrite(this)
            else viewModel.clearPendingWriteIntent()
        }
    }

    fun showReplayOverlay(info: ReplayInfo) {
        val overlay = findViewById<View>(R.id.layoutReplayOverlay)
        val tvTitle = overlay.findViewById<TextView>(R.id.tvReplayTitle)
        val tvSubtitle = overlay.findViewById<TextView>(R.id.tvReplaySubtitle)
        val btnPlay = overlay.findViewById<View>(R.id.btnPlayReplay)
        val btnDismiss = overlay.findViewById<View>(R.id.btnDismissReplay)
        val rvReplaySongs = overlay.findViewById<RecyclerView>(R.id.rvReplaySongs)
        
        tvTitle.text = info.title
        tvSubtitle.text = info.subtitle
        
        lifecycleScope.launch(Dispatchers.IO) {
            val db = (application as MainApplication).database
            val topTracks = db.songHistoryDao().getTopTracksInRange(info.startTime, info.endTime, 10)
            withContext(Dispatchers.Main) {
                if (topTracks.isNotEmpty()) {
                    rvReplaySongs.layoutManager = LinearLayoutManager(this@MainActivity)
                    rvReplaySongs.adapter = RecapAdapter(topTracks)
                    rvReplaySongs.visibility = View.VISIBLE
                } else {
                    rvReplaySongs.visibility = View.GONE
                }
            }
        }

        btnPlay.setOnClickListener {
            overlay.visibility = View.GONE
            lifecycleScope.launch(Dispatchers.IO) {
                val db = (application as MainApplication).database
                val topTracks = db.songHistoryDao().getTopTracksInRange(info.startTime, info.endTime, 10)
                val mediaItems = topTracks.mapNotNull { track ->
                    viewModel.originalTrackList.find { it.id.toString() == track.songId }
                }
                if (mediaItems.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        viewModel.playSong(mediaItems, index = 0, playImmediately = true)
                        expandFullPlayer()
                    }
                }
            }
        }
        
        btnDismiss.setOnClickListener {
            overlay.visibility = View.GONE
            ReplayManager.markAsDismissed(this, info.periodKey)
        }
        
        overlay.setOnClickListener { overlay.visibility = View.GONE }
        overlay.visibility = View.VISIBLE
    }

    fun showFullScreenOverlay(fragment: Fragment) {
        val container = findViewById<View>(R.id.fullScreenOverlayContainer)
        container.visibility = View.VISIBLE
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.slide_in_up, 0, 0, R.anim.slide_out_down)
            .replace(R.id.fullScreenOverlayContainer, fragment, "FULL_SCREEN_OVERLAY")
            .commit()
    }

    fun hideFullScreenOverlay() {
        val container = findViewById<View>(R.id.fullScreenOverlayContainer)
        supportFragmentManager.findFragmentByTag("FULL_SCREEN_OVERLAY")?.let { fragment ->
            supportFragmentManager.beginTransaction()
                .setCustomAnimations(0, R.anim.slide_out_down)
                .remove(fragment)
                .commit()
        }
        container.visibility = View.GONE
    }

    private fun syncNavigationState(offset: Float) {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val effectiveOffset = if (offset == -1f) {
            if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) 1f else 0f
        } else offset

        if (isLandscape) {
            bottomNavigationView.visibility = View.GONE
            navigationRail?.visibility = View.VISIBLE
            navigationRail?.translationX = -(navigationRail?.width?.toFloat() ?: 0f) * effectiveOffset
        } else {
            bottomNavigationView.visibility = View.VISIBLE
            navigationRail?.visibility = View.GONE
            bottomNavigationView.translationY = bottomNavigationView.height * effectiveOffset
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        
        val isEnabled = sharedPreferences.getBoolean("shake_to_shuffle", false)
        if (!isEnabled) return

        val g = sqrt((event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2]).toDouble()) / 9.8
        if (g > 2.7) {
            val now = System.currentTimeMillis()
            if (shakeTimestamp + 500 < now) {
                shakeTimestamp = now
                viewModel.setShuffleEnabled(!(viewModel.shuffleModeEnabled.value))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
}
