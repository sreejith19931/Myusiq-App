package com.music.rhythm.utils

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs

object WaveformExtractor {

    suspend fun extractWaveform(context: Context, uri: Uri): String? {
        return withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                val trackCount = extractor.trackCount
                var audioTrackIndex = -1
                for (i in 0 until trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        break
                    }
                }

                if (audioTrackIndex == -1) return@withContext null
                extractor.selectTrack(audioTrackIndex)

                val amplitudes = mutableListOf<Float>()
                val buffer = ByteBuffer.allocate(1024 * 16)
                
                // Sample roughly every 1% of the file for 100 points
                // For simplicity, we just take max peaks at intervals
                var count = 0
                while (extractor.readSampleData(buffer, 0) >= 0) {
                    if (count % 200 == 0) { // Downsample
                        var max = 0
                        for (i in 0 until buffer.remaining() step 2) {
                            if (i + 1 < buffer.remaining()) {
                                val sample = buffer.getShort(i).toInt()
                                max = maxOf(max, abs(sample))
                            }
                        }
                        amplitudes.add(max / 32768f)
                    }
                    if (amplitudes.size >= 100) break
                    extractor.advance()
                    count++
                }
                
                if (amplitudes.isEmpty()) return@withContext null
                amplitudes.joinToString(",") { String.format(Locale.US, "%.3f", it) }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            } finally {
                extractor.release()
            }
        }
    }
}
