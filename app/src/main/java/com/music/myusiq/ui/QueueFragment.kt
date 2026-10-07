package com.music.myusiq.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import kotlinx.coroutines.launch
import com.music.myusiq.domain.models.Song
import com.music.myusiq.utils.*
import com.music.myusiq.ui.*
import com.music.myusiq.ui.viewmodels.PlaybackViewModel
import com.music.myusiq.ui.adapters.TrackAdapter
import com.music.myusiq.ui.adapters.TrackUiModel
import com.music.myusiq.R

class QueueFragment : Fragment(R.layout.fragment_queue) {
    private val viewModel: PlaybackViewModel by activityViewModels()
    private lateinit var rvQueue: RecyclerView
    private lateinit var tvEmptyQueue: TextView
    private lateinit var queueAdapter: TrackAdapter
    private lateinit var tvHeaderSubtitle: TextView
    private var favoriteTrackIds: Set<String> = emptySet()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rvQueue = view.findViewById(R.id.rvQueue)
        tvEmptyQueue = view.findViewById(R.id.tvEmptyQueue)
        rvQueue.layoutManager = SafeLinearLayoutManager(requireContext())
        
        val header = view.findViewById<View?>(R.id.queueHeader)
        header?.findViewById<TextView>(R.id.tvHeaderTitle)?.text = getString(R.string.nav_queue)
        val subtitle = header?.findViewById<TextView>(R.id.tvHeaderSubtitle)
        tvHeaderSubtitle = if (subtitle != null) {
            subtitle
        } else {
            // Fallback or handle missing subtitle in some layouts
            TextView(requireContext()) 
        }

        queueAdapter = TrackAdapter(
            isQueueMode = true,
            onTrackClicked = { selectedSong, imageView ->
                val completeQueue = viewModel.currentQueue.value
                val clickedIndex = completeQueue.indexOfFirst { it.id == selectedSong.id }
                if (clickedIndex != -1) {
                    (activity as? MainActivity)?.playFromView(completeQueue, clickedIndex, imageView, shouldExpand = false)
                }
            }
        )


        
        rvQueue.adapter = queueAdapter
        (rvQueue.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.currentSongIndex.collect { updateQueueDisplay() } }
                launch { viewModel.currentQueue.collect { updateQueueDisplay() } }
                launch { viewModel.isPlaying.collect { updateQueueDisplay() } }
                launch { viewModel.artworkRefreshSignatures.collect { updateQueueDisplay() } }
                launch { viewModel.currentPosition.collect { updateQueueDisplay() } }
                launch { 
                    viewModel.favoriteTrackIds.collect { ids ->
                        favoriteTrackIds = ids
                        updateQueueDisplay()
                    }
                }
            }
        }
        
        setupQueueTouchActions()
    }

    override fun onResume() {
        super.onResume()
        updateQueueDisplay()
    }

    override fun onDestroyView() {
        if (::rvQueue.isInitialized) {
            rvQueue.adapter = null
        }
        if (::queueAdapter.isInitialized) {
            queueAdapter.onOrderChangedListener = null
        }
        super.onDestroyView()
    }

    @SuppressLint("SetTextI18n")
    private fun updateQueueDisplay() {
        if (!isAdded) return
        val completeQueue = viewModel.currentQueue.value
        val currentIndex = viewModel.currentSongIndex.value
        val isPlaying = viewModel.isPlaying.value
        val signatures = viewModel.artworkRefreshSignatures.value
        val position = viewModel.currentPosition.value

        if (completeQueue.isEmpty()) {
            rvQueue.viewVisibility(isVisible = false)
            tvEmptyQueue.viewVisibility(isVisible = true)
            tvHeaderSubtitle.text = "No Tracks"
        } else {
            rvQueue.viewVisibility(isVisible = true)
            tvEmptyQueue.viewVisibility(isVisible = false)
            if (currentIndex == -1) {
                tvHeaderSubtitle.text = "${completeQueue.size} Tracks"
            } else {
                tvHeaderSubtitle.text = "Track ${currentIndex + 1} / ${completeQueue.size}"
            }
            
            val uiModels = completeQueue.mapIndexed { index, song ->
                val normPath = PlatformUtils.normalizePath(song.path)
                val isActive = index == currentIndex
                TrackUiModel(
                    song = song,
                    isFavorite = favoriteTrackIds.contains(song.id.toString()),
                    isActive = isActive,
                    isPast = index < currentIndex,
                    isPlaying = (isActive && isPlaying),
                    progress = if (isActive && (song.duration > 0)) position.toFloat() / song.duration.toFloat() else 0f,
                    artworkSignature = signatures[normPath] ?: 0L
                )
            }
            
            queueAdapter.submitList(uiModels)
        }
    }


    private fun setupQueueTouchActions() {
        val swipeAndDragHandler = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
        ) {
            private var dragFromPosition = RecyclerView.NO_POSITION
            private var dragToPosition = RecyclerView.NO_POSITION

            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = vh.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false

                if (dragFromPosition == RecyclerView.NO_POSITION) {
                    dragFromPosition = fromPos
                }
                dragToPosition = toPos
                viewModel.moveTrackInQueue(fromPos, toPos)
                return true
            }


            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val adapterPosition = viewHolder.bindingAdapterPosition
                val currentList = queueAdapter.currentList
                if (adapterPosition == RecyclerView.NO_POSITION || adapterPosition !in currentList.indices) {
                    updateQueueDisplay()
                    return
                }
                viewModel.deleteTrackFromQueue(adapterPosition)
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                val finalFrom = dragFromPosition
                val finalTo = dragToPosition
                dragFromPosition = RecyclerView.NO_POSITION
                dragToPosition = RecyclerView.NO_POSITION

                if (finalFrom != RecyclerView.NO_POSITION && finalTo != RecyclerView.NO_POSITION && finalFrom != finalTo) {
                    viewModel.reorderQueueTracks(finalFrom, finalTo)
                }
            }

        }
        val itemTouchHelper = ItemTouchHelper(swipeAndDragHandler)
        itemTouchHelper.attachToRecyclerView(rvQueue)
    }

    private fun View.viewVisibility(isVisible: Boolean) {
        this.visibility = if (isVisible) View.VISIBLE else View.GONE
    }

    private class SafeLinearLayoutManager(context: Context) : LinearLayoutManager(context) {
        override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
            try {
                super.onLayoutChildren(recycler, state)
            } catch (e: IndexOutOfBoundsException) {
                Log.e("SafeLayoutManager", "IndexOutOfBoundsException in RecyclerView layout", e)
            }
        }
    }
}
