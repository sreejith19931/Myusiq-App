package com.music.myusiq.ui

import android.content.ContentUris
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import com.music.myusiq.domain.models.Song
import com.music.myusiq.ui.viewmodels.PlaybackViewModel
import com.music.myusiq.utils.AudioTrimmerUtil
import com.music.myusiq.utils.WaveformExtractor
import com.music.myusiq.ui.components.TrimmerWaveformView
import com.music.myusiq.R

class AudioTrimmerFragment : Fragment(R.layout.fragment_audio_trimmer) {
    private val viewModel: PlaybackViewModel by activityViewModels()
    private var currentSong: Song? = null
    private var localPlayer: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentStartMs = 0f
    private var currentEndMs = 0f
    private var zoomFromMs = 0f
    private var zoomToMs = 0f
    private var durationMs = 1f

    private val progressRunnable = object : Runnable {
        override fun run() {
            localPlayer?.let { player ->
                val waveformView = view?.findViewById<TrimmerWaveformView>(R.id.trimmerWaveformView)
                if (player.isPlaying) {
                    val currentPos = player.currentPosition
                    waveformView?.setPlaybackPosition(currentPos, durationMs)

                    if (currentPos >= currentEndMs) {
                        player.pause()
                        player.seekTo(currentStartMs.toLong())
                        waveformView?.setPlaybackPosition(-1L, durationMs)
                    }
                } else {
                    waveformView?.setPlaybackPosition(-1L, durationMs)
                }
            }
            mainHandler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.pausePlayback()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val songId = arguments?.getLong("songId") ?: return
        
        val header = view.findViewById<View>(R.id.trimmerHeader)
        header.findViewById<TextView>(R.id.tvHeaderTitle).setText(R.string.trim_audio)
        header.findViewById<TextView>(R.id.tvHeaderSubtitle).visibility = View.GONE
        header.findViewById<View>(R.id.btnBack).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        val tvTitle = view.findViewById<TextView>(R.id.tvTrimTrackTitle)
        val tvArtist = view.findViewById<TextView>(R.id.tvTrimTrackArtist)
        val tvStart = view.findViewById<TextView>(R.id.tvTrimStart)
        val tvEnd = view.findViewById<TextView>(R.id.tvTrimEnd)
        val tvDuration = view.findViewById<TextView>(R.id.tvSelectionDuration)
        val btnPreview = view.findViewById<FloatingActionButton>(R.id.btnPreviewTrim)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSaveTrim)
        btnSave.text = getString(R.string.trim_audio)

        val switchFadeIn = view.findViewById<MaterialSwitch>(R.id.switchFadeIn)
        val switchFadeOut = view.findViewById<MaterialSwitch>(R.id.switchFadeOut)
        val waveformView = view.findViewById<TrimmerWaveformView>(R.id.trimmerWaveformView)

        localPlayer = ExoPlayer.Builder(requireContext()).build()

        viewLifecycleOwner.lifecycleScope.launch {
            val songs = viewModel.songsLibrary.first()
            currentSong = songs.find { it.id == songId }
            val song = currentSong ?: return@launch

            tvTitle.text = song.title
            tvArtist.text = song.artist
            
            val mediaItem = MediaItem.fromUri(File(song.path).toUri())
            localPlayer?.setMediaItem(mediaItem)
            localPlayer?.prepare()

            durationMs = song.duration.toFloat()
            currentStartMs = 0f
            currentEndMs = durationMs
            zoomFromMs = 0f
            zoomToMs = durationMs

            updateTimeLabels(currentStartMs, currentEndMs, tvStart, tvEnd, tvDuration)

            waveformView.setSelectionRange(currentStartMs, currentEndMs, durationMs)
            waveformView.setZoomRange(zoomFromMs, zoomToMs, durationMs)

            waveformView.onRangeChangedListener = { start, end ->
                currentStartMs = start
                currentEndMs = end
                updateTimeLabels(start, end, tvStart, tvEnd, tvDuration)
            }

            waveformView.onSeekListener = { seekTime ->
                localPlayer?.pause()
                localPlayer?.seekTo(seekTime.toLong())
                btnPreview.setImageResource(R.drawable.ic_play)
                waveformView.setPlaybackPosition(seekTime.toLong(), durationMs)
            }

            val btnZoomIn = view.findViewById<MaterialButton>(R.id.btnZoomIn)
            val btnZoomOut = view.findViewById<MaterialButton>(R.id.btnZoomOut)
            val btnZoomReset = view.findViewById<MaterialButton>(R.id.btnZoomReset)

            var zoomLevel = 1f
            fun applyZoom() {
                val center = (currentStartMs + currentEndMs) / 2f
                val halfSpan = (durationMs / 2f) / zoomLevel
                zoomFromMs = (center - halfSpan).coerceAtLeast(0f)
                zoomToMs = (center + halfSpan).coerceAtMost(durationMs)
                
                if (zoomToMs - zoomFromMs < 1000f) return

                btnZoomReset.text = "${zoomLevel.toInt()}x"
                waveformView.setZoomRange(zoomFromMs, zoomToMs, durationMs)
            }

            btnZoomIn.setOnClickListener {
                if (zoomLevel < 16f) {
                    zoomLevel *= 2f
                    applyZoom()
                }
            }

            btnZoomOut.setOnClickListener {
                if (zoomLevel > 1f) {
                    zoomLevel /= 2f
                    applyZoom()
                }
            }

            btnZoomReset.setOnClickListener {
                zoomLevel = 1f
                zoomFromMs = 0f
                zoomToMs = durationMs
                btnZoomReset.text = "1x"
                waveformView.setZoomRange(zoomFromMs, zoomToMs, durationMs)
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val waveform = WaveformExtractor.extractWaveform(requireContext(), File(song.path).toUri())
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        waveformView.setWaveform(waveform)
                    }
                }
            }

            btnPreview.setOnClickListener {
                localPlayer?.let { player ->
                    if (player.isPlaying) {
                        player.pause()
                        btnPreview.setImageResource(R.drawable.ic_play)
                    } else {
                        val startMs = currentStartMs.toLong()
                        if (player.currentPosition < startMs || (player.currentPosition >= currentEndMs.toLong())) {
                            player.seekTo(startMs)
                        }
                        player.play()
                        btnPreview.setImageResource(R.drawable.ic_pause)
                    }
                }
            }

            btnSave.setOnClickListener {
                val isFadeIn = switchFadeIn.isChecked
                val isFadeOut = switchFadeOut.isChecked
                showSaveOptionsDialog(isFadeIn, isFadeOut)
            }
        }
        mainHandler.post(progressRunnable)
    }

    private fun showSaveOptionsDialog(fadeIn: Boolean, fadeOut: Boolean) {
        val song = currentSong ?: return
        val startMs = currentStartMs.toInt()
        val endMs = currentEndMs.toInt()

        val originalFile = File(song.path)
        val defaultName = "trimmed_${originalFile.nameWithoutExtension}"
        
        val container = FrameLayout(requireContext())
        val editText = TextInputEditText(requireContext())
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        val margin = (24 * resources.displayMetrics.density).toInt()
        lp.setMargins(margin, margin / 2, margin, 0)
        editText.layoutParams = lp
        editText.setText(defaultName)
        editText.hint = "File Name"
        container.addView(editText)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Save Trimmed Audio")
            .setMessage("Enter a name and choose how to save.")
            .setView(container)
            .setNeutralButton(R.string.cancel, null)
            .setNegativeButton("Replace Current") { _, _ ->
                executeTrim(song, startMs, endMs, replace = true, newName = originalFile.nameWithoutExtension, fadeIn, fadeOut)
            }
            .setPositiveButton("Save as New") { _, _ ->
                val enteredName = editText.text?.toString()?.ifBlank { defaultName } ?: defaultName
                executeTrim(song, startMs, endMs, replace = false, newName = enteredName, fadeIn, fadeOut)
            }
            .show()
    }

    private fun executeTrim(song: Song, startMs: Int, endMs: Int, replace: Boolean, newName: String, fadeIn: Boolean, fadeOut: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val originalFile = File(song.path)
                if (!originalFile.exists()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Original file not found!", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val outputFile = if (replace) {
                    File(requireContext().cacheDir, "trimmed_temp_${System.currentTimeMillis()}.${originalFile.extension}")
                } else {
                    val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    val trimmedDir = File(musicDir, "Trimmed")
                    if (!trimmedDir.exists()) trimmedDir.mkdirs()
                    File(trimmedDir, "$newName.${originalFile.extension}")
                }

                val success = AudioTrimmerUtil.trimAudio(originalFile.absolutePath, outputFile.absolutePath, startMs, endMs, fadeIn, fadeOut)

                if (!success) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Trimming failed!", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                if (replace) {
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
                    try {
                        requireContext().contentResolver.openOutputStream(uri, "rwt")?.use { output ->
                            outputFile.inputStream().use { input ->
                                input.copyTo(output)
                            }
                        }
                        outputFile.delete()
                    } catch (e: SecurityException) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val pendingIntent = MediaStore.createWriteRequest(requireContext().contentResolver, listOf(uri))
                            withContext(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "Permission needed to replace file. Try again after granting.", Toast.LENGTH_LONG).show()
                                viewModel.requestEdit(pendingIntent)
                            }
                        }
                        return@launch
                    }
                }

                withContext(Dispatchers.Main) {
                    val msg = if (replace) "replaced current track" else "saved as new track in Music/Trimmed"
                    val fadeInfo = if (fadeIn || fadeOut) " (Applied fades)" else ""
                    Toast.makeText(requireContext(), "Trimmed audio $msg$fadeInfo!", Toast.LENGTH_SHORT).show()
                    
                    val pathToScan = if (replace) song.path else outputFile.absolutePath
                    MediaScannerConnection.scanFile(requireContext(), arrayOf(pathToScan), null) { _, _ ->
                        viewModel.loadSongs(force = true)
                    }
                    
                    if (replace) {
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    } else {
                        showTrimSuccessDialog(outputFile)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showTrimSuccessDialog(outputFile: File) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Trim Successful!")
            .setMessage("Your trimmed audio has been successfully saved.")
            .setPositiveButton("Done") { _, _ ->
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
            .setNeutralButton("Share") { _, _ ->
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", outputFile)
                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "audio/*"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(android.content.Intent.createChooser(shareIntent, "Share Trimmed Audio"))
                } catch (e: Exception) {
                    Toast.makeText(ctx, "Sharing failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Set as Ringtone") { _, _ ->
                try {
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.Audio.Media.DATA, outputFile.absolutePath)
                        put(MediaStore.Audio.Media.TITLE, outputFile.nameWithoutExtension)
                        put(MediaStore.Audio.Media.MIME_TYPE, "audio/*")
                        put(MediaStore.Audio.Media.IS_RINGTONE, true)
                        put(MediaStore.Audio.Media.IS_NOTIFICATION, false)
                        put(MediaStore.Audio.Media.IS_ALARM, false)
                        put(MediaStore.Audio.Media.IS_MUSIC, false)
                    }
                    val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        android.media.RingtoneManager.setActualDefaultRingtoneUri(ctx, android.media.RingtoneManager.TYPE_RINGTONE, uri)
                        Toast.makeText(ctx, "Successfully set as default ringtone!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(ctx, "Failed to set ringtone: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainHandler.removeCallbacks(progressRunnable)
        localPlayer?.release()
        localPlayer = null
    }

    private fun updateTimeLabels(startMs: Float, endMs: Float, tvStart: TextView, tvEnd: TextView, tvDuration: TextView) {
        tvStart.text = formatTime(startMs.toInt())
        tvEnd.text = formatTime(endMs.toInt())
        val duration = (endMs - startMs).toInt().coerceAtLeast(0)
        tvDuration.text = formatTime(duration)
    }

    private fun formatTime(ms: Int): String {
        val seconds = ms / 1000
        val m = seconds / 60
        val s = seconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }
}
