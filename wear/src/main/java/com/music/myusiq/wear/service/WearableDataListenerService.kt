package com.music.myusiq.wear.service

import android.content.Intent
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
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
                            val intent = Intent(this, WearUpdatePromptActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra("apk_path", cacheFile.absolutePath)
                            }
                            startActivity(intent)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WearDataListener", "Error receiving Wear APK update asset", e)
                }
            }
        }
    }
}
