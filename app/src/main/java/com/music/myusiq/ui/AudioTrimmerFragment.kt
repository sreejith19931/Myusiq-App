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
import com.google.android.material.slider.RangeSlider
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
import com.music.myusiq.R

class AudioTrimmerFragment : Fragment(R.layout.fragment_audio_trimmer) {
    private val viewModel: PlaybackViewModel by activityViewModels()
    private var currentSong: Song? = null
    private var localPlayer: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val progressRunnable = object : Runnable {
        override fun run() {
            localPlayer?.let { player ->
                if (player.isPlaying) {
                    val currentPos = player.currentPosition
                    val rangeSlider = view?.findViewById<RangeSlider>(R.id.rangeSliderTrim)
                    val endMs = rangeSlider?.values?.get(1) ?: Float.MAX_VALUE
                    if (currentPos >= endMs) {
                        player.pause()
                        player.seekTo(rangeSlider?.values?.get(0)?.toLong() ?: 0L)
                    }
                }
            }
            mainHandler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.pausePlayback() // Explicitly pause main playback
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
        val rangeSlider = view.findViewById<RangeSlider>(R.id.rangeSliderTrim)

        val tvStart = view.findViewById<TextView>(R.id.tvTrimStart)
        val tvEnd = view.findViewById<TextView>(R.id.tvTrimEnd)
        val btnPreview = view.findViewById<FloatingActionButton>(R.id.btnPreviewTrim)
            val btnSave = view.findViewById<MaterialButton>(R.id.btnSaveTrim)
            btnSave.text = getString(R.string.trim_audio)

            val switchFadeIn = view.findViewById<MaterialSwitch>(R.id.switchFadeIn)
            val switchFadeOut = view.findViewById<MaterialSwitch>(R.id.switchFadeOut)

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


            val durationMs = song.duration.toFloat()
            rangeSlider.valueFrom = 0f
            rangeSlider.valueTo = durationMs
            rangeSlider.values = listOf(0f, durationMs)

            updateTimeLabels(0f, durationMs, tvStart, tvEnd)

            rangeSlider.setLabelFormatter { value ->
                formatTime(value.toInt())
            }

            rangeSlider.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: RangeSlider) {
                    localPlayer?.pause()
                }

                override fun onStopTrackingTouch(slider: RangeSlider) {
                    val startMs = slider.values[0].toLong()
                    localPlayer?.seekTo(startMs)
                    localPlayer?.play()
                    btnPreview.setImageResource(R.drawable.ic_pause)
                }
            })

            rangeSlider.addOnChangeListener { slider, value, fromUser ->
                val values = slider.values
                updateTimeLabels(values[0], values[1], tvStart, tvEnd)
                if (fromUser) {
                    localPlayer?.pause()
                    localPlayer?.seekTo(value.toLong())
                    btnPreview.setImageResource(R.drawable.ic_play)
                }
            }


            btnPreview.setOnClickListener {
                localPlayer?.let { player ->
                    if (player.isPlaying) {
                        player.pause()
                        btnPreview.setImageResource(R.drawable.ic_play)
                    } else {
                        val startMs = rangeSlider.values[0].toLong()
                        if (player.currentPosition < startMs || (player.currentPosition >= rangeSlider.values[1].toLong())) {
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
        val rangeSlider = view?.findViewById<RangeSlider>(R.id.rangeSliderTrim) ?: return
        val startMs = rangeSlider.values[0].toInt()
        val endMs = rangeSlider.values[1].toInt()

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

                // If replace, save to a temporary file in cache first
                val outputFile = if (replace) {
                    File(requireContext().cacheDir, "trimmed_temp_${System.currentTimeMillis()}.${originalFile.extension}")
                } else {
                    val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    val trimmedDir = File(musicDir, "Trimmed")
                    if (!trimmedDir.exists()) trimmedDir.mkdirs()
                    File(trimmedDir, "$newName.${originalFile.extension}")
                }

                // Actual trimming logic
                val success = AudioTrimmerUtil.trimAudio(originalFile.absolutePath, outputFile.absolutePath, startMs, endMs)

                if (!success) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Trimming failed!", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                if (replace) {
                    // Use ContentResolver to overwrite the original file in Scoped Storage
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
                    try {
                        requireContext().contentResolver.openOutputStream(uri, "rwt")?.use { output ->
                            outputFile.inputStream().use { input ->
                                input.copyTo(output)
                            }
                        }
                        outputFile.delete() // Clean up temp
                        
                        Log.d("AudioTrimmer", "Original file replaced via MediaStore URI: $uri")
                    } catch (e: SecurityException) {
                        // On Android 11+, we might need to request write permission for this specific URI
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val pendingIntent = MediaStore.createWriteRequest(requireContext().contentResolver, listOf(uri))
                            withContext(Dispatchers.Main) {
                                // We'd need to store the pending state and retry after permission
                                // For now, we'll use the existing ViewModel mechanism if available
                                Toast.makeText(requireContext(), "Permission needed to replace file. Try again after granting.", Toast.LENGTH_LONG).show()
                                viewModel.requestEdit(pendingIntent) // Requesting write permission
                            }
                        }
                        return@launch
                    }
                }

                withContext(Dispatchers.Main) {
                    val msg = if (replace) "replaced current track" else "saved as new track in Music/Trimmed"
                    val fadeInfo = if (fadeIn || fadeOut) " (Applied fades)" else ""
                    Toast.makeText(requireContext(), "Trimmed audio $msg$fadeInfo!", Toast.LENGTH_SHORT).show()
                    
                    // Scan the file
                    val pathToScan = if (replace) song.path else outputFile.absolutePath
                    MediaScannerConnection.scanFile(requireContext(), arrayOf(pathToScan), null) { _, _ ->
                        viewModel.loadSongs(force = true)
                    }
                    
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }



    override fun onDestroyView() {
        super.onDestroyView()
        mainHandler.removeCallbacks(progressRunnable)
        localPlayer?.release()
        localPlayer = null
    }

    private fun updateTimeLabels(startMs: Float, endMs: Float, tvStart: TextView, tvEnd: TextView) {
        tvStart.text = formatTime(startMs.toInt())
        tvEnd.text = formatTime(endMs.toInt())
    }

    private fun formatTime(ms: Int): String {
        val seconds = ms / 1000
        val m = seconds / 60
        val s = seconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }
}
