package com.music.rhythm.ui

import android.content.ContentUris
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.music.rhythm.ui.viewmodels.PlaybackViewModel
import com.music.rhythm.R

class DrivingModeFragment : Fragment(R.layout.fragment_driving_mode) {
    private val viewModel: PlaybackViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ivArt = view.findViewById<ImageView>(R.id.ivDrivingArt)
        val tvTitle = view.findViewById<TextView>(R.id.tvDrivingTitle)
        val tvArtist = view.findViewById<TextView>(R.id.tvDrivingArtist)
        val btnPrev = view.findViewById<MaterialButton>(R.id.btnDrivingPrev)
        val btnPlayPause = view.findViewById<FloatingActionButton>(R.id.btnDrivingPlayPause)
        val btnNext = view.findViewById<MaterialButton>(R.id.btnDrivingNext)
        val btnExit = view.findViewById<MaterialButton>(R.id.btnExitDriving)

        btnPlayPause.setOnClickListener { viewModel.togglePlayPause() }
        btnPrev.setOnClickListener { viewModel.playPreviousSong() }
        btnNext.setOnClickListener { viewModel.playNextSong() }
        btnExit.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.currentPlayingSong.collectLatest { song ->
                        if (song != null) {
                            tvTitle.text = song.title
                            tvArtist.text = song.artist
                            val artworkUri = ContentUris.withAppendedId(
                                "content://media/external/audio/albumart".toUri(),
                                song.albumId,
                            )
                            Glide.with(this@DrivingModeFragment)
                                .load(artworkUri)
                                .placeholder(R.drawable.ic_music_note_2)
                                .error(R.drawable.ic_music_note_2)
                                .into(ivArt)
                            
                            tvTitle.isSelected = true
                        }
                    }
                }

                launch {
                    viewModel.isPlaying.collectLatest { isPlaying ->
                        btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                    }
                }
            }
        }
    }
}
