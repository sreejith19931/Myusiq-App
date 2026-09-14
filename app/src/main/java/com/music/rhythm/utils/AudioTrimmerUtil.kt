package com.music.rhythm.utils

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.FileOutputStream
import java.nio.ByteBuffer

object AudioTrimmerUtil {
    private const val TAG = "AudioTrimmerUtil"

    /**
     * Trims an audio file using MediaExtractor and MediaMuxer.
     * This performs a packet-level copy (no re-encoding), so it's fast but may not be 
     * accurate to the millisecond if the format uses large frames.
     */
    fun trimAudio(sourcePath: String, outputPath: String, startMs: Int, endMs: Int): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(sourcePath)
            val trackCount = extractor.trackCount
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            
            for (i in 0 until trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = f
                    break
                }
            }

            if ((audioTrackIndex == -1) || (format == null)) {
                Log.e(TAG, "No audio track found in $sourcePath")
                return false
            }

            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            Log.d(TAG, "Trimming $mime from $startMs to $endMs")

            // MP3 files often fail with MediaMuxer. Fallback to manual frame copy.
            if (mime.equals("audio/mpeg", ignoreCase = true)) {
                return trimMp3Manually(extractor, audioTrackIndex, outputPath, startMs, endMs)
            }

            return trimWithMuxer(extractor, audioTrackIndex, format, outputPath, startMs, endMs)
        } catch (e: Exception) {
            Log.e(TAG, "Error trimming audio: $sourcePath", e)
            return false
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun trimWithMuxer(extractor: MediaExtractor, trackIndex: Int, format: MediaFormat, outputPath: String, startMs: Int, endMs: Int): Boolean {
        var muxer: MediaMuxer? = null
        try {
            extractor.selectTrack(trackIndex)
            
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val muxerFormat = if (mime.contains("webm") || mime.contains("opus")) {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            } else {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }

            muxer = MediaMuxer(outputPath, muxerFormat)
            val writeTrackIndex = muxer.addTrack(format)
            muxer.start()

            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            
            val bufferSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).let { if (it > 0) it else 1024 * 256 }
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()
            var firstSampleTimeUs: Long = -1

            while (true) {
                bufferInfo.offset = 0
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sampleTime = extractor.sampleTime
                if (sampleTime > endUs) break
                
                if (sampleTime < startUs) {
                    extractor.advance()
                    continue
                }

                if (firstSampleTimeUs == -1L) firstSampleTimeUs = sampleTime

                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = sampleTime - firstSampleTimeUs
                
                val sampleFlags = extractor.sampleFlags
                var bufferFlags = 0
                if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    bufferFlags = bufferFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                }
                bufferInfo.flags = bufferFlags
                
                muxer.writeSampleData(writeTrackIndex, buffer, bufferInfo)
                extractor.advance()
            }

            muxer.stop()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Muxer trim failed", e)
            return false
        } finally {
            try { muxer?.release() } catch (e: Exception) {}
        }
    }

    private fun trimMp3Manually(extractor: MediaExtractor, trackIndex: Int, outputPath: String, startMs: Int, endMs: Int): Boolean {
        var fos: FileOutputStream? = null
        try {
            extractor.selectTrack(trackIndex)
            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            
            fos = FileOutputStream(outputPath)
            val buffer = ByteBuffer.allocateDirect(1024 * 256)

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sampleTime = extractor.sampleTime
                if (sampleTime > endUs) break
                
                if (sampleTime < startUs) {
                    extractor.advance()
                    continue
                }

                val bytes = ByteArray(sampleSize)
                buffer.get(bytes)
                fos.write(bytes)
                buffer.clear()
                
                extractor.advance()
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Manual MP3 trim failed", e)
            return false
        } finally {
            try { fos?.close() } catch (e: Exception) {}
        }
    }
}
