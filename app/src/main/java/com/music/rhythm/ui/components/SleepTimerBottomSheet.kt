package com.music.rhythm.ui.components

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.music.rhythm.R
import com.music.rhythm.ui.viewmodels.PlaybackViewModel
import com.music.rhythm.utils.ThemeUtils

class SleepTimerBottomSheet : BottomSheetDialogFragment() {
    private val viewModel: PlaybackViewModel by activityViewModels()
    private lateinit var btnStopAfterTrack: MaterialButton
    private lateinit var btnTurnOffTimer: MaterialButton
    private lateinit var chips: List<Chip>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_sleep_timer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val chip15 = view.findViewById<Chip>(R.id.chip15)
        val chip30 = view.findViewById<Chip>(R.id.chip30)
        val chip45 = view.findViewById<Chip>(R.id.chip45)
        val chip60 = view.findViewById<Chip>(R.id.chip60)
        chips = listOf(chip15, chip30, chip45, chip60)
        btnTurnOffTimer = view.findViewById(R.id.btnTurnOffTimer)
        btnStopAfterTrack = view.findViewById(R.id.btnStopAfterTrack)

        chip15.setOnClickListener { startTimerAndDismiss(15) }
        chip30.setOnClickListener { startTimerAndDismiss(30) }
        chip45.setOnClickListener { startTimerAndDismiss(45) }
        chip60.setOnClickListener { startTimerAndDismiss(60) }
        btnTurnOffTimer.setOnClickListener { viewModel.cancelSleepTimer(); dismiss() }
        btnStopAfterTrack.setOnClickListener { viewModel.toggleStopAfterCurrentTrack(); dismiss() }

        setupObservers()
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.isSleepTimerActive.collectLatest { btnTurnOffTimer.visibility = if (it) View.VISIBLE else View.GONE } }
                launch { viewModel.stopAfterCurrentTrack.collectLatest { updateStopAfterTrackUI(it) } }
                launch { viewModel.currentVibrantColor.collectLatest { color ->
                    val accent = color ?: ThemeUtils.getThemeColor(requireContext(), android.R.attr.colorPrimary)
                    val tint = ColorStateList.valueOf(accent)
                    
                    chips.forEach { it.chipStrokeColor = tint; it.setTextColor(accent) }
                    btnTurnOffTimer.setTextColor(accent)
                    updateStopAfterTrackUI(viewModel.stopAfterCurrentTrack.value)
                }}
            }
        }
    }

    private fun updateStopAfterTrackUI(isActive: Boolean) {
        val context = requireContext()
        val accent = viewModel.currentVibrantColor.value ?: ThemeUtils.getThemeColor(context, android.R.attr.colorPrimary)
        val color = if (isActive) accent
                    else MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.GRAY)
        btnStopAfterTrack.setTextColor(color)
        btnStopAfterTrack.iconTint = ColorStateList.valueOf(color)
        btnStopAfterTrack.setIconResource(if (isActive) R.drawable.ic_check else R.drawable.ic_stop_after_track)
    }

    private fun startTimerAndDismiss(minutes: Int) {
        viewModel.startSleepTimer(minutes)
        dismiss()
    }

    companion object {
        const val TAG = "SleepTimerBottomSheet"
        fun newInstance() = SleepTimerBottomSheet()
    }
}
