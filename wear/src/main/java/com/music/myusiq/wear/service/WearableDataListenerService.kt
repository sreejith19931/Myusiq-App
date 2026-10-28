package com.music.myusiq.wear.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.music.myusiq.wear.R
import com.music.myusiq.wear.ui.WearUpdatePromptActivity
import java.io.File

class WearableDataListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == "/myusiq/wear_apk") {
                try {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val asset = dataMap.getAsset("apkAsset")
                    if (asset != null) {
                        val fdTask = Wearable.getDataClient(this).getFdForAsset(asset)
                        val fd = Tasks.await(fdTask)
                        val cacheFile = File(cacheDir, "myusiq_wear_update.apk")

                        fd.inputStream.use { input ->
                            cacheFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }

                        if (cacheFile.exists() && cacheFile.length() > 0) {
                            Log.d("WearDataListener", "Received Wear APK update file (${cacheFile.length()} bytes)")
                            showUpdateNotificationAndPrompt(cacheFile)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WearDataListener", "Error receiving Wear APK update asset", e)
                }
            }
        }
    }

    private fun showUpdateNotificationAndPrompt(cacheFile: File) {
        val intent = Intent(this, WearUpdatePromptActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("apk_path", cacheFile.absolutePath)
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w("WearDataListener", "Direct activity start failed or restricted, using notification", e)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = "wear_app_updates"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            channelId,
            "App Updates",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications when a watch app update is ready to install."
        }
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Myusiq Update Ready 🚀")
            .setContentText("Tap to install the latest version on your watch.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1001, notification)
    }
}
