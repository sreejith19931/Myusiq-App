package com.music.myusiq.ui.components

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import com.music.myusiq.R
import com.music.myusiq.ui.viewmodels.PlaybackViewModel

class LyricEditorBottomSheet : BottomSheetDialogFragment() {
    private val viewModel: PlaybackViewModel by activityViewModels()

    private lateinit var tvSongTitle: TextView
    private lateinit var tvCurrentLinePrompt: TextView
    private lateinit var etLyricsText: TextInputEditText
    private lateinit var btnMarkTimestamp: MaterialButton
    private lateinit var btnSaveLrc: MaterialButton
    private lateinit var btnCancel: MaterialButton

    private var currentLineIndex = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_lyric_editor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvSongTitle = view.findViewById(R.id.tvSongTitle)
        tvCurrentLinePrompt = view.findViewById(R.id.tvCurrentLinePrompt)
        etLyricsText = view.findViewById(R.id.etLyricsText)
        btnMarkTimestamp = view.findViewById(R.id.btnMarkTimestamp)
        btnSaveLrc = view.findViewById(R.id.btnSaveLrc)
        btnCancel = view.findViewById(R.id.btnCancel)

        btnCancel.setOnClickListener { dismiss() }

        btnMarkTimestamp.setOnClickListener {
            markCurrentTimestamp()
        }

        btnSaveLrc.setOnClickListener {
            val content = etLyricsText.text?.toString() ?: ""
            val songPath = viewModel.currentPlayingSong.value?.path ?: ""
            if (content.isNotBlank() && songPath.isNotBlank()) {
                viewModel.saveLyrics(songPath, content)
                Toast.makeText(requireContext(), "Synced LRC saved!", Toast.LENGTH_SHORT).show()
                dismiss()
            } else {
                Toast.makeText(requireContext(), "Song path or lyrics empty", Toast.LENGTH_SHORT).show()
            }
        }

        setupObservers()
    }

    private fun markCurrentTimestamp() {
        val currentPositionMs = viewModel.currentPosition.value
        val minutes = (currentPositionMs / 1000) / 60
        val seconds = (currentPositionMs / 1000) % 60
        val hundredths = (currentPositionMs % 1000) / 10
        val timestampStr = String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, hundredths)

        val fullText = etLyricsText.text?.toString() ?: ""
        val rawLines = fullText.split("\n").toMutableList()

        if (rawLines.isEmpty() || currentLineIndex >= rawLines.size) {
            Toast.makeText(requireContext(), "No more lines to sync", Toast.LENGTH_SHORT).show()
            return
        }

        val targetLine = rawLines[currentLineIndex].trim()
        val cleanText = if (targetLine.startsWith("[")) {
            targetLine.substringAfter("]")
        } else {
            targetLine
        }

        rawLines[currentLineIndex] = "$timestampStr $cleanText"
        etLyricsText.setText(rawLines.joinToString("\n"))

        currentLineIndex++
        if (currentLineIndex < rawLines.size) {
            tvCurrentLinePrompt.text = "Next: ${rawLines[currentLineIndex].trim()}"
        } else {
            tvCurrentLinePrompt.text = "All lines synced! Tap 'Save Synced LRC'"
        }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.currentPlayingSong.collectLatest { song ->
                        if (song != null) {
                            tvSongTitle.text = "${song.title} - ${song.artist}"
                            val existingLyrics = viewModel.getLyricsForSong(song.path)
                            if (!existingLyrics.isNullOrBlank() && etLyricsText.text.isNullOrBlank()) {
                                etLyricsText.setText(existingLyrics)
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val TAG = "LyricEditorBottomSheet"
        fun newInstance() = LyricEditorBottomSheet()
    }
}
