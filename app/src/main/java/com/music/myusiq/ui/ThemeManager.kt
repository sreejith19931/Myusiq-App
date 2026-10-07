package com.music.myusiq.ui

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.music.myusiq.R
import kotlin.math.max

class ThemeManager(private val activity: AppCompatActivity) {

    fun setupWindowInsets(
        rootLayout: View, 
        navHost: View, 
        bottomNav: View, 
        navRail: View? = null,
        navSpacer: View? = null,
        exitConfirm: View? = null,
        onInsetsChanged: () -> Unit,
    ) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            
            val bottomPadding = max(systemBars.bottom, ime.bottom)
            
            val miniPlayerHeight = activity.resources.getDimensionPixelSize(R.dimen.mini_player_height)
            val baseNavHeight = if (bottomNav.isVisible) {
                activity.resources.getDimensionPixelSize(R.dimen.bottom_nav_height)
            } else 0
            val totalNavHeight = baseNavHeight + bottomPadding
            
            // Adjust navHost padding to account for bottom navigation and system bars
            navHost.setPadding(0, systemBars.top, 0, totalNavHeight + miniPlayerHeight)
            
            // Bottom nav should just sit above the system navigation bar (or ime)
            bottomNav.setPadding(0, 0, 0, bottomPadding)

            // Rail should handle top/bottom safe areas
            navRail?.setPadding(0, systemBars.top, 0, bottomPadding)
            
            // Adjust exit confirm popup to be below status bar
            exitConfirm?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemBars.top + activity.resources.getDimensionPixelSize(R.dimen.exit_confirm_margin_top)
            }
            
            // Sync the spacer height in the player sheet
            navSpacer?.updateLayoutParams {
                height = totalNavHeight
            }
            
            onInsetsChanged()
            insets
        }
    }
}
