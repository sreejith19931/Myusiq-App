package com.music.myusiq.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.SizeF
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import androidx.core.net.toUri
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.music.myusiq.ui.MainActivity
import com.music.myusiq.R
import com.music.myusiq.domain.models.Song
import java.io.File

class MyusiqWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val intent = Intent(context, PlaybackService::class.java).apply {
            action = "UPDATE_WIDGET"
        }
        context.startForegroundService(intent)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val intent = Intent(context, PlaybackService::class.java).apply {
            action = "UPDATE_WIDGET"
        }
        context.startForegroundService(intent)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("MusicWidget", "onReceive: action=$action")
        super.onReceive(context, intent)
        if ((action == "TOGGLE_PLAY_PAUSE") || (action == "NEXT") || (action == "PREV") || (action == "UPDATE_WIDGET")) {
            val serviceIntent = Intent(context, PlaybackService::class.java).apply {
                this.action = action
            }
            context.startForegroundService(serviceIntent)
        }
    }

    companion object {
        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, song: Song? = null, isPlaying: Boolean = false, currentPos: Long = 0) {
            Log.d("MusicWidget", "updateAppWidget id: $appWidgetId, song: ${song?.title}, isPlaying: $isPlaying")
            
            val standardViews = RemoteViews(context.packageName, R.layout.layout_widget_standard)
            val expandedViews = RemoteViews(context.packageName, R.layout.layout_widget_expanded)

            val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RemoteViews(
                    mapOf(
                        SizeF(100f, 60f) to standardViews,
                        SizeF(250f, 80f) to expandedViews,
                        SizeF(250f, 200f) to expandedViews,
                    ),
                )
            } else {
                val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
                val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                // If height is > 80dp, use expanded layout
                if (minHeight >= 80) expandedViews else standardViews
            }

            // Setup common intents
            setupIntents(context, standardViews)
            setupIntents(context, expandedViews)

            if (song != null) {
                updateViewsWithSong(standardViews, song, isPlaying, currentPos)
                updateViewsWithSong(expandedViews, song, isPlaying, currentPos)
                
                // Update text immediately
                appWidgetManager.updateAppWidget(appWidgetId, views)

                loadAlbumArt(context, appWidgetId, standardViews, expandedViews, song, isPlaying, currentPos)
            } else {
                updateViewsEmpty(standardViews)
                updateViewsEmpty(expandedViews)
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }

        private fun setupIntents(context: Context, views: RemoteViews) {
            val playIntent = Intent(context, PlaybackService::class.java).apply { action = "TOGGLE_PLAY_PAUSE" }
            views.setOnClickPendingIntent(R.id.widgetBtnPlayPause, PendingIntent.getForegroundService(context, 100, playIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            val nextIntent = Intent(context, PlaybackService::class.java).apply { action = "NEXT" }
            views.setOnClickPendingIntent(R.id.widgetBtnNext, PendingIntent.getForegroundService(context, 101, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            val prevIntent = Intent(context, PlaybackService::class.java).apply { action = "PREV" }
            views.setOnClickPendingIntent(R.id.widgetBtnPrev, PendingIntent.getForegroundService(context, 102, prevIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            val appIntent = Intent(context, MainActivity::class.java)
            views.setOnClickPendingIntent(R.id.widgetRoot, PendingIntent.getActivity(context, 13, appIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }

        private fun updateViewsWithSong(views: RemoteViews, song: Song, isPlaying: Boolean, currentPos: Long, vibrantColor: Int? = null, mutedColor: Int? = null) {
            views.setTextViewText(R.id.widgetTitle, song.title)
            views.setTextViewText(R.id.widgetArtist, song.artist)
            views.setImageViewResource(R.id.widgetBtnPlayPause, if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            
            vibrantColor?.let {
                // Tint icons
                views.setInt(R.id.widgetBtnPlayPause, "setColorFilter", it)
                views.setInt(R.id.widgetBtnNext, "setColorFilter", it)
                views.setInt(R.id.widgetBtnPrev, "setColorFilter", it)
            }
            
            mutedColor?.let {
                // Apply a semi-transparent muted color to the background
                val alphaColor = ColorUtils.setAlphaComponent(it, 220)
                views.setInt(R.id.widgetRoot, "setBackgroundColor", alphaColor)
            }

            views.setProgressBar(R.id.widgetProgressBar, song.duration, currentPos.toInt(), false)
        }

        private fun updateViewsEmpty(views: RemoteViews) {
            views.setTextViewText(R.id.widgetTitle, "No song playing")
            views.setTextViewText(R.id.widgetArtist, "")
            views.setImageViewResource(R.id.widgetAlbumArt, R.drawable.ic_music_note_2)
            views.setImageViewResource(R.id.widgetBtnPlayPause, R.drawable.ic_play)
            views.setProgressBar(R.id.widgetProgressBar, 100, 0, false)
        }

        private fun loadAlbumArt(context: Context, appWidgetId: Int, standardViews: RemoteViews, expandedViews: RemoteViews, song: Song, isPlaying: Boolean, currentPos: Long) {
            val trackUri = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                song.id
            )
            val sArtworkUri = "content://media/external/audio/albumart".toUri()
            val albumArtUri = ContentUris.withAppendedId(sArtworkUri, song.albumId)

            val target = object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    updateWidget(resource)
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    updateWidget(null)
                }

                private fun updateWidget(bitmap: Bitmap?) {
                    if (bitmap != null) {
                        standardViews.setImageViewBitmap(R.id.widgetAlbumArt, bitmap)
                        expandedViews.setImageViewBitmap(R.id.widgetAlbumArt, bitmap)
                        
                        Palette.from(bitmap).generate { palette ->
                            val vibrant = palette?.getVibrantColor(Color.WHITE)
                            val muted = palette?.getDarkMutedColor(Color.DKGRAY)
                            
                            updateViewsWithSong(standardViews, song, isPlaying, currentPos, vibrant, muted)
                            updateViewsWithSong(expandedViews, song, isPlaying, currentPos, vibrant, muted)
                            applyUpdate()
                        }
                    } else {
                        standardViews.setImageViewResource(R.id.widgetAlbumArt, R.drawable.ic_music_note_2)
                        expandedViews.setImageViewResource(R.id.widgetAlbumArt, R.drawable.ic_music_note_2)
                        
                        updateViewsWithSong(standardViews, song, isPlaying, currentPos)
                        updateViewsWithSong(expandedViews, song, isPlaying, currentPos)
                        applyUpdate()
                    }
                }

                private fun applyUpdate() {
                    val combinedViews = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        RemoteViews(
                            mapOf(
                                SizeF(100f, 60f) to standardViews,
                                SizeF(250f, 80f) to expandedViews,
                                SizeF(250f, 200f) to expandedViews,
                            )
                        )
                    } else {
                        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
                        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                        if (minHeight >= 80) expandedViews else standardViews
                    }
                    AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, combinedViews)
                }
            }

            Glide.with(context.applicationContext)
                .asBitmap()
                .load(trackUri)
                .transform(RoundedCorners(24))
                .placeholder(R.drawable.ic_music_note_2)
                .error(
                    Glide.with(context.applicationContext)
                        .asBitmap()
                        .load(File(song.path))
                        .transform(RoundedCorners(24))
                        .error(
                            Glide.with(context.applicationContext)
                                .asBitmap()
                                .load(albumArtUri)
                                .transform(RoundedCorners(24))
                                .error(R.drawable.ic_music_note_2)
                        )
                )
                .into(target)
        }

        fun updateAllWidgets(context: Context, song: Song?, isPlaying: Boolean, currentPos: Long = 0) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, MyusiqWidgetProvider::class.java))
            for (id in ids) {
                updateAppWidget(context, appWidgetManager, id, song, isPlaying, currentPos)
            }
        }
    }
}
