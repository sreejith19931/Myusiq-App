package com.music.myusiq.utils

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
                var durationUs = 0L
                for (i in 0 until trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        if (format.containsKey(MediaFormat.KEY_DURATION)) {
                            durationUs = format.getLong(MediaFormat.KEY_DURATION)
                        }
                        break
                    }
                }

                if (audioTrackIndex == -1) return@withContext null
                extractor.selectTrack(audioTrackIndex)

                val amplitudes = mutableListOf<Float>()
                val buffer = ByteBuffer.allocate(1024 * 16)

                if (durationUs > 0L) {
                    // Extract 100 points evenly sampled across the whole track duration (0% to 99%)
                    val stepUs = durationUs / 100L
                    for (step in 0 until 100) {
                        val seekTargetUs = step * stepUs
                        extractor.seekTo(seekTargetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        buffer.clear()
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize > 0) {
                            var max = 0
                            for (i in 0 until buffer.remaining() step 2) {
                                if (i + 1 < buffer.remaining()) {
                                    val sample = buffer.getShort(i).toInt()
                                    max = maxOf(max, abs(sample))
                                }
                            }
                            amplitudes.add(max / 32768f)
                        }
                    }
                } else {
                    // Fallback for streams without duration header: sample sequentially
                    var count = 0
                    while (extractor.readSampleData(buffer, 0) >= 0) {
                        if (count % 150 == 0) {
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
