package com.music.myusiq.utils

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

/**
 * Extracts a per-window peak envelope (0..1) for a track by fully decoding
 * the audio to PCM with [MediaCodec].
 *
 * IMPORTANT: this MUST NOT read compressed bytes via readSampleData() as PCM —
 * doing so produced saturated garbage (every sample ≈ 1.0) which broke the
 * energy analyzer, BPM estimation and the Mood Engine. Decoding to PCM is the
 * only correct path.
 */
object WaveformExtractor {

    private const val WINDOW_COUNT = 100

    suspend fun extractWaveform(context: Context, uri: Uri): String? {
        return withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            try {
                extractor.setDataSource(context, uri, null)
                var audioTrackIndex = -1
                var format: MediaFormat? = null
                var durationUs = 0L
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        format = f
                        if (f.containsKey(MediaFormat.KEY_DURATION)) {
                            durationUs = f.getLong(MediaFormat.KEY_DURATION)
                        }
                        break
                    }
                }
                if (audioTrackIndex == -1 || format == null) return@withContext null
                extractor.selectTrack(audioTrackIndex)

                val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext null
                decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(format, null, null, 0)
                decoder.start()

                // Peak envelope: max |PCM| per time window
                val windows = arrayOfNulls<Int>(WINDOW_COUNT)
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var sawPcm = false

                while (!outputDone) {
                    // Feed input
                    if (!inputDone) {
                        val inIndex = decoder.dequeueInputBuffer(10_000L)
                        if (inIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inIndex)
                            if (inputBuffer == null) {
                                inputDone = true
                            } else {
                                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                if (sampleSize < 0) {
                                    decoder.queueInputBuffer(
                                        inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    inputBuffer.position(0)
                                    inputBuffer.limit(sampleSize)
                                    decoder.queueInputBuffer(
                                        inIndex, 0, sampleSize, extractor.sampleTime, 0
                                    )
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    // Drain output
                    val outIndex = decoder.dequeueOutputBuffer(info, 10_000L)
                    if (outIndex >= 0) {
                        val out = decoder.getOutputBuffer(outIndex)
                        if (out != null && info.size > 0) {
                            val windowIndex = if (durationUs > 0) {
                                ((info.presentationTimeUs * WINDOW_COUNT) / durationUs)
                                    .toInt().coerceIn(0, WINDOW_COUNT - 1)
                            } else {
                                (windows.count { it != null }.coerceAtMost(WINDOW_COUNT - 1))
                            }
                            out.position(0)
                            val isFloatPcm = decoder.outputFormat
                                ?.getInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT) ==
                                android.media.AudioFormat.ENCODING_PCM_FLOAT
                            var peak = 0
                            if (isFloatPcm) {
                                out.order(ByteOrder.LITTLE_ENDIAN)
                                while (out.remaining() >= 4) {
                                    val v = abs(out.float)
                                    if (v > peak) peak = (v * 32768f).toInt()
                                }
                            } else {
                                out.order(ByteOrder.LITTLE_ENDIAN)
                                while (out.remaining() >= 2) {
                                    val v = abs(out.short.toInt())
                                    if (v > peak) peak = v
                                }
                            }
                            if (peak > 0) {
                                sawPcm = true
                                val existing = windows[windowIndex] ?: 0
                                if (peak > existing) windows[windowIndex] = peak
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }

                    // Early exit once every window has data
                    if (windows.all { it != null }) break
                }

                val filled = windows.map { it ?: 0 }
                val amplitudeList = filled.map { (it / 32768f).coerceIn(0f, 1f) }

                if (!sawPcm || amplitudeList.all { it == 0f }) {
                    return@withContext syntheticFallback(uri)
                }

                amplitudeList.joinToString(",") { String.format(Locale.US, "%.3f", it) }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            } finally {
                try { decoder?.stop() } catch (_: Exception) {}
                try { decoder?.release() } catch (_: Exception) {}
                try { extractor.release() } catch (_: Exception) {}
            }
        }
    }

    /** Deterministic shape used only when decoding fails outright. */
    private fun syntheticFallback(uri: Uri): String {
        val hash = uri.toString().hashCode()
        val list = mutableListOf<Float>()
        for (i in 0 until WINDOW_COUNT) {
            val pseudoVal = (sin(i * 0.3 + hash) * 0.5 + 0.5).toFloat()
            val noise = ((i * 37) % 10) / 20f
            list.add((pseudoVal * 0.7f + noise * 0.3f).coerceIn(0.08f, 1f))
        }
        return list.joinToString(",") { String.format(Locale.US, "%.3f", it) }
    }

    /**
     * Detects the corrupt saturated waveforms produced by the old
     * readSampleData-based extractor (every value ≈ 1.0). These must be
     * re-extracted and must never be treated as a usable audio signal.
     */
    fun isSaturated(waveform: String?): Boolean {
        if (waveform.isNullOrBlank()) return false
        val values = waveform.split(",").mapNotNull { it.toFloatOrNull() }
        if (values.size < 10) return false
        return values.all { it in 0.90f..1.01f }
    }
}
