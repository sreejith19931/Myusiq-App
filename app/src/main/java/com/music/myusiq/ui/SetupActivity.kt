package com.music.myusiq.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AnticipateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.music.myusiq.databinding.ActivitySetupBinding
import kotlinx.coroutines.launch
import com.music.myusiq.R
import com.music.myusiq.utils.*

class SetupActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySetupBinding
    private val themeSettingsManager by lazy { ThemeSettingsManager(applicationContext) }
    
    private var hasPermission = false
    private var selectedFolders = listOf<Uri>()
    private var chosenThemeMode = "system"
    private var chosenPalette = "system_colors"
    private var currentFoldersFragment: SetupFoldersFragment? = null

    fun registerFoldersFragment(fragment: SetupFoldersFragment?) {
        currentFoldersFragment = fragment
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()

        val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (savedInstanceState != null) {
            hasPermission = savedInstanceState.getBoolean("hasPermission")
            selectedFolders = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                savedInstanceState.getParcelableArrayList("selectedFolders", Uri::class.java) ?: listOf()
            } else {
                @Suppress("DEPRECATION")
                savedInstanceState.getParcelableArrayList("selectedFolders") ?: listOf()
            }
            chosenThemeMode = savedInstanceState.getString("chosenThemeMode", "system") ?: "system"
            chosenPalette = savedInstanceState.getString("chosenPalette", "system_colors") ?: "system_colors"
        } else {
            chosenThemeMode = sharedPreferences.getString("cached_theme_mode", "system") ?: "system"
            chosenPalette = sharedPreferences.getString("cached_color_style", "system_colors") ?: "system_colors"
        }

        val cachedFont = sharedPreferences.getBoolean("cached_use_custom_font", true)

        themeSettingsManager.applyThemeMode(chosenThemeMode)
        themeSettingsManager.applyColorStyle(this, chosenPalette)
        themeSettingsManager.applyTypography(this, cachedFont)

        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("app_setup_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("is_setup_complete", false)) {
            launchMainActivityAndFinish()
            return
        }

        // Custom exit animation for the splash screen
        splashScreen.setOnExitAnimationListener { splashScreenView ->
            try {
                val iconView = splashScreenView.iconView
                iconView.animate()
                    .scaleX(2f)
                    .scaleY(2f)
                    .alpha(0f)
                    .setDuration(500L)
                    .setInterpolator(AnticipateInterpolator())
                    .withEndAction {
                        splashScreenView.remove()
                    }
                    .start()
            } catch (_: Exception) {
                splashScreenView.remove()
            }
        }
        
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hasPermission = checkPermissionGranted()

        setupViewPager()
        setupNavigation()

        val savedPage = savedInstanceState?.getInt("currentPage", 0) ?: 0
        if (savedPage > 0) {
            binding.viewPagerSetup.setCurrentItem(savedPage, false)
        }
    }

    private fun checkPermissionGranted(): Boolean {
        val mediaGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return mediaGranted && notifGranted
    }

    private fun setupViewPager() {
        val adapter = SetupPagerAdapter(this)
        binding.viewPagerSetup.adapter = adapter
        binding.viewPagerSetup.isUserInputEnabled = false // Disable manual swipe, use controls

        binding.viewPagerSetup.setPageTransformer { page, position ->
            when {
                position < -1 -> { page.alpha = 0f }
                position <= 0 -> {
                    page.alpha = 1f
                    page.translationX = 0f
                    page.scaleX = 1f
                    page.scaleY = 1f
                }
                position <= 1 -> {
                    page.alpha = 1 - position
                    page.translationX = page.width * -position * 0.25f
                    val scaleFactor = 0.85f + (1 - position) * 0.15f
                    page.scaleX = scaleFactor
                    page.scaleY = scaleFactor
                }
                else -> { page.alpha = 0f }
            }
        }

        binding.viewPagerSetup.registerOnPageChangeCallback(object :
            ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateNavigationButtons(position)
                updateProgressBar(position)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        UpdateManager.checkPendingInstall(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("hasPermission", hasPermission)
        outState.putParcelableArrayList("selectedFolders", ArrayList(selectedFolders))
        outState.putString("chosenThemeMode", chosenThemeMode)
        outState.putString("chosenPalette", chosenPalette)
        if (::binding.isInitialized) {
            outState.putInt("currentPage", binding.viewPagerSetup.currentItem)
        }
    }

    private fun updateProgressBar(position: Int) {
        val bars = listOf(
            binding.progressStep1,
            binding.progressStep2,
            binding.progressStep3,
            binding.progressStep4
        )

        val activeColor = ThemeUtils.getThemeColor(this, androidx.appcompat.R.attr.colorPrimary)
        val inactiveColor = ThemeUtils.getThemeColor(this, com.google.android.material.R.attr.colorSurfaceVariant)

        bars.forEachIndexed { index, view ->
            if (index <= position) {
                view.backgroundTintList = ColorStateList.valueOf(activeColor)
            } else {
                view.backgroundTintList = ColorStateList.valueOf(inactiveColor)
            }
        }
    }

    fun navigateToNextPage() {
        when (binding.viewPagerSetup.currentItem) {
            0 -> {
                binding.viewPagerSetup.currentItem = 1
            }
            1 -> {
                if (hasPermission) {
                    binding.viewPagerSetup.currentItem = 2
                } else {
                    Toast.makeText(this, R.string.authorize_permissions_toast, Toast.LENGTH_SHORT).show()
                }
            }
            2 -> {
                val hasFolders = currentFoldersFragment?.hasSelectedFolders() ?: true
                if (hasFolders) {
                    binding.viewPagerSetup.currentItem = 3
                } else {
                    Toast.makeText(this, "Please select at least one folder to continue", Toast.LENGTH_SHORT).show()
                }
            }
            3 -> {
                finishSetup()
            }
        }
    }

    private fun setupNavigation() {
        binding.btnNext.setOnClickListener {
            navigateToNextPage()
        }

        binding.btnBack.setOnClickListener {
            val currentItem = binding.viewPagerSetup.currentItem
            if (currentItem > 0) {
                binding.viewPagerSetup.currentItem = currentItem - 1
            }
        }
    }

    private fun updateNavigationButtons(position: Int) {
        if (position == 0) {
            binding.layoutNavigation.visibility = View.GONE
            binding.divider.visibility = View.GONE
        } else {
            binding.layoutNavigation.visibility = View.VISIBLE
            binding.divider.visibility = View.VISIBLE
            binding.btnBack.visibility = View.VISIBLE
            binding.btnNext.text = if (position == 3) getString(R.string.launch_application) else getString(R.string.next_step)
        }
    }

    fun onPermissionChanged(granted: Boolean) {
        hasPermission = granted
    }

    fun onFoldersChanged(folders: List<Uri>) {
        selectedFolders = folders
    }

    fun getSelectedFolders(): List<Uri> = selectedFolders
    fun getChosenPalette(): String = chosenPalette
    fun getChosenThemeMode(): String = chosenThemeMode

    fun onThemeChanged(theme: String) {
        if (chosenThemeMode == theme) return
        chosenThemeMode = theme
        getSharedPreferences("app_prefs", MODE_PRIVATE).edit()
            .putString("cached_theme_mode", theme)
            .commit()
        lifecycleScope.launch {
            themeSettingsManager.saveThemeMode(theme)
            themeSettingsManager.applyThemeMode(theme)
        }
    }

    fun onPaletteChanged(palette: String) {
        if (chosenPalette == palette) return
        chosenPalette = palette
        getSharedPreferences("app_prefs", MODE_PRIVATE).edit()
            .putString("cached_color_style", palette)
            .commit()
        lifecycleScope.launch {
            themeSettingsManager.saveColorStyle(palette)
        }
        recreate()
    }

    private fun finishSetup() {
        binding.btnNext.isEnabled = false
        lifecycleScope.launch {
            try {
                themeSettingsManager.saveThemeMode(chosenThemeMode)
                themeSettingsManager.saveColorStyle(chosenPalette)
                themeSettingsManager.applyThemeMode(chosenThemeMode)
            } catch (_: Exception) {
                // Ignore
            }
            
            val folderPreferences = FolderPreferences(applicationContext)
            selectedFolders.forEach { uri ->
                folderPreferences.addAllowedFolderUri(uri.toString())
            }

            val cachedFont = getSharedPreferences("app_prefs", MODE_PRIVATE).getBoolean("cached_use_custom_font", true)

            getSharedPreferences("app_prefs", MODE_PRIVATE).edit().apply {
                putString("cached_theme_mode", chosenThemeMode)
                putString("cached_color_style", chosenPalette)
                putBoolean("cached_use_custom_font", cachedFont)
                apply()
            }
            
            getSharedPreferences("app_setup_prefs", MODE_PRIVATE).edit().apply {
                putBoolean("is_setup_complete", true)
                putString("use_theme_mode_string", chosenThemeMode)
                apply()
            }

            launchMainActivityAndFinish()
        }
    }

    private fun launchMainActivityAndFinish() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private class SetupPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 4
        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> SetupWelcomeFragment()
                1 -> SetupPermissionFragment()
                2 -> SetupFoldersFragment()
                else -> SetupPersonalizeFragment()
            }
        }
    }
}
