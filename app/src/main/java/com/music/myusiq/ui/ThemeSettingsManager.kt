package com.music.myusiq.ui

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.music.myusiq.R

val Context.dataStore by preferencesDataStore(name = "theme_settings")

class ThemeSettingsManager(private val context: Context) {

    companion object {
        val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        val COLOR_STYLE_KEY = stringPreferencesKey("color_style")
        val USE_ALBUM_ART_THEMING_KEY = booleanPreferencesKey("use_album_art_theming")
        val USE_BLURRED_BACKGROUND_KEY = booleanPreferencesKey("use_blurred_background")
        val USE_LYRIC_OVERLAY_KEY = booleanPreferencesKey("use_lyric_overlay")
        val USE_SINGLE_LINE_LYRICS_KEY = booleanPreferencesKey("use_single_line_lyrics")
        val USE_CUSTOM_FONT_KEY = booleanPreferencesKey("use_custom_font")
        val USE_GLASSMORPHISM_PLAYER_KEY = booleanPreferencesKey("use_glassmorphism_player")
        val USE_LYRIC_FOCUS_BLUR_KEY = booleanPreferencesKey("use_lyric_focus_blur")
    }

    val themeModeFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[THEME_MODE_KEY] ?: "system"
    }

    val colorStyleFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[COLOR_STYLE_KEY] ?: "system_colors"
    }

    val useAlbumArtThemingFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[USE_ALBUM_ART_THEMING_KEY] ?: true
    }

    val useBlurredBackgroundFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[USE_BLURRED_BACKGROUND_KEY] ?: false
    }

    val useLyricOverlayFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[USE_LYRIC_OVERLAY_KEY] ?: true
    }

    val useSingleLineLyricsFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[USE_SINGLE_LINE_LYRICS_KEY] ?: false
    }

    val useCustomFontFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[USE_CUSTOM_FONT_KEY] ?: true
    }

    val useGlassmorphismPlayerFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[USE_GLASSMORPHISM_PLAYER_KEY] ?: true
    }

    val useLyricFocusBlurFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[USE_LYRIC_FOCUS_BLUR_KEY] ?: true
    }

    suspend fun saveThemeMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[THEME_MODE_KEY] = mode }
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
            .putString("cached_theme_mode", mode)
            .commit()
    }

    suspend fun saveColorStyle(style: String) {
        context.dataStore.edit { prefs -> prefs[COLOR_STYLE_KEY] = style }
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
            .putString("cached_color_style", style)
            .commit()
    }

    suspend fun saveUseAlbumArtTheming(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[USE_ALBUM_ART_THEMING_KEY] = enabled }
    }

    suspend fun saveUseBlurredBackground(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[USE_BLURRED_BACKGROUND_KEY] = enabled }
    }

    suspend fun saveUseLyricOverlay(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[USE_LYRIC_OVERLAY_KEY] = enabled }
    }

    suspend fun saveUseSingleLineLyrics(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[USE_SINGLE_LINE_LYRICS_KEY] = enabled }
    }

    suspend fun saveUseCustomFont(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[USE_CUSTOM_FONT_KEY] = enabled }
    }

    suspend fun saveUseGlassmorphismPlayer(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[USE_GLASSMORPHISM_PLAYER_KEY] = enabled }
    }

    suspend fun saveUseLyricFocusBlur(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[USE_LYRIC_FOCUS_BLUR_KEY] = enabled }
    }

    fun applyThemeMode(mode: String) {
        when (mode) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }
    fun applyColorStyle(activity: Activity, style: String) {
        if (activity.isFinishing || activity.isDestroyed) return

        when (style) {
            "palette_red"    -> activity.setTheme(R.style.Theme_Palette_Red)
            "palette_orange" -> activity.setTheme(R.style.Theme_Palette_Orange)
            "palette_yellow" -> activity.setTheme(R.style.Theme_Palette_Yellow)
            "palette_green"  -> activity.setTheme(R.style.Theme_Palette_Green)
            "palette_blue"   -> activity.setTheme(R.style.Theme_Palette_Blue)
            "palette_indigo" -> activity.setTheme(R.style.Theme_Palette_Indigo)
            "palette_violet" -> activity.setTheme(R.style.Theme_Palette_Violet)
            "palette_midnight" -> activity.setTheme(R.style.Theme_Palette_Midnight)
            "palette_slate"    -> activity.setTheme(R.style.Theme_Palette_Slate)
            "palette_forest"   -> activity.setTheme(R.style.Theme_Palette_Forest)
            "palette_charcoal" -> activity.setTheme(R.style.Theme_Palette_Charcoal)
            "palette_royal"    -> activity.setTheme(R.style.Theme_Palette_Royal)
            "system_colors"  -> {
                activity.setTheme(R.style.Theme_Myusiq)
                DynamicColors.applyToActivityIfAvailable(activity)
            }
            else             -> activity.setTheme(R.style.Theme_Myusiq)
        }
    }

    fun applyTypography(activity: Activity, useCustomFont: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (useCustomFont) {
            activity.theme.applyStyle(R.style.ThemeOverlay_App_CustomFonts, true)
        }
    }

    fun applyColorStyle(activity: Activity, style: String, useCustomFont: Boolean = false) {
        applyColorStyle(activity, style)
        applyTypography(activity, useCustomFont)
    }
}