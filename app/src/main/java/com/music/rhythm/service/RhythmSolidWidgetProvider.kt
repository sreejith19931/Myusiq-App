package com.music.rhythm.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.SizeF
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.music.rhythm.ui.MainActivity
import com.music.rhythm.R
import com.music.rhythm.domain.models.Song
import java.io.File

class RhythmSolidWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val intent = Intent(context, PlaybackService::class.java).apply {
            action = "UPDATE_WIDGET"
        }
        context.startForegroundService(intent)
    }

    override fun onAppWidgetOptionsChanged(context: Context?, appWidgetManager: AppWidgetManager?, appWidgetId: Int, newOptions: Bundle?) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val intent = Intent(context ?: return, PlaybackService::class.java).apply {
            action = "UPDATE_WIDGET"
        }
        context.startForegroundService(intent)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("MusicWidgetSolid", "onReceive: action=$action")
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
            val standardViews = RemoteViews(context.packageName, R.layout.layout_widget_solid)
            val expandedViews = RemoteViews(context.packageName, R.layout.layout_widget_solid_expanded)

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
                if (minHeight >= 80) expandedViews else standardViews
            }

            // Setup common intents
            setupIntents(context, standardViews)
            setupIntents(context, expandedViews)

            if (song != null) {
                updateViewsWithSong(context, standardViews, song, isPlaying, currentPos)
                updateViewsWithSong(context, expandedViews, song, isPlaying, currentPos)
                
                // Update text immediately
                appWidgetManager.updateAppWidget(appWidgetId, views)

                loadAlbumArt(context, appWidgetId, standardViews, expandedViews, song)
            } else {
                updateViewsEmpty(context, standardViews)
                updateViewsEmpty(context, expandedViews)
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }

        private fun setupIntents(context: Context, views: RemoteViews) {
            val playIntent = Intent(context, PlaybackService::class.java).apply { action = "TOGGLE_PLAY_PAUSE" }
            views.setOnClickPendingIntent(R.id.widgetBtnPlayPause, PendingIntent.getForegroundService(context, 200, playIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            val nextIntent = Intent(context, PlaybackService::class.java).apply { action = "NEXT" }
            views.setOnClickPendingIntent(R.id.widgetBtnNext, PendingIntent.getForegroundService(context, 201, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            val prevIntent = Intent(context, PlaybackService::class.java).apply { action = "PREV" }
            views.setOnClickPendingIntent(R.id.widgetBtnPrev, PendingIntent.getForegroundService(context, 202, prevIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            val appIntent = Intent(context, MainActivity::class.java)
            views.setOnClickPendingIntent(R.id.widgetRoot, PendingIntent.getActivity(context, 23, appIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }

        private fun updateViewsWithSong(context: Context, views: RemoteViews, song: Song, isPlaying: Boolean, currentPos: Long) {
            views.setTextViewText(R.id.widgetTitle, song.title)
            views.setTextViewText(R.id.widgetArtist, song.artist)
            views.setImageViewResource(R.id.widgetBtnPlayPause, if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            
            // Apply tint to buttons for RemoteViews compatibility
            val tintColor = ContextCompat.getColor(context, R.color.widget_solid_icon_tint)
            views.setInt(R.id.widgetBtnPlayPause, "setColorFilter", tintColor)
            views.setInt(R.id.widgetBtnNext, "setColorFilter", tintColor)
            views.setInt(R.id.widgetBtnPrev, "setColorFilter", tintColor)
            
            views.setProgressBar(R.id.widgetProgressBar, song.duration, currentPos.toInt(), false)
        }

        private fun updateViewsEmpty(context: Context, views: RemoteViews) {
            views.setTextViewText(R.id.widgetTitle, "No song playing")
            views.setTextViewText(R.id.widgetArtist, "")
            views.setImageViewResource(R.id.widgetAlbumArt, R.drawable.ic_music_note_2)
            views.setImageViewResource(R.id.widgetBtnPlayPause, R.drawable.ic_play)
            
            // Apply tint to buttons
            val tintColor = ContextCompat.getColor(context, R.color.widget_solid_icon_tint)
            views.setInt(R.id.widgetBtnPlayPause, "setColorFilter", tintColor)
            views.setInt(R.id.widgetBtnNext, "setColorFilter", tintColor)
            views.setInt(R.id.widgetBtnPrev, "setColorFilter", tintColor)
            
            views.setProgressBar(R.id.widgetProgressBar, 100, 0, false)
        }

        private fun loadAlbumArt(context: Context, appWidgetId: Int, standardViews: RemoteViews, expandedViews: RemoteViews, song: Song) {
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
                    // Optionally handle clear
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    // Fallback to placeholder if everything fails
                    updateWidget(null)
                }

                private fun updateWidget(bitmap: Bitmap?) {
                    if (bitmap != null) {
                        standardViews.setImageViewBitmap(R.id.widgetAlbumArt, bitmap)
                        expandedViews.setImageViewBitmap(R.id.widgetAlbumArt, bitmap)
                    } else {
                        standardViews.setImageViewResource(R.id.widgetAlbumArt, R.drawable.ic_music_note_2)
                        expandedViews.setImageViewResource(R.id.widgetAlbumArt, R.drawable.ic_music_note_2)
                    }
                    
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
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, RhythmSolidWidgetProvider::class.java))
            for (id in ids) {
                updateAppWidget(context, appWidgetManager, id, song, isPlaying, currentPos)
            }
        }
    }
}
