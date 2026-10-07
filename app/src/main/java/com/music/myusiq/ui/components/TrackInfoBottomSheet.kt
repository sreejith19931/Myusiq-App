package com.music.myusiq.ui.components

import android.content.ContentUris
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Size
import android.widget.ImageView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.color.MaterialColors
import com.music.myusiq.R
import com.music.myusiq.ui.viewmodels.PlaybackViewModel
import com.music.myusiq.domain.models.Song
import com.music.myusiq.utils.fetchTrackDetailedInfo

class TrackInfoBottomSheet : BottomSheetDialogFragment() {
    private val viewModel: PlaybackViewModel by activityViewModels()
    
    private var selectedArtworkUri: Uri? = null
    private var ivEditAlbumArtRef: ImageView? = null

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            selectedArtworkUri = uri
            ivEditAlbumArtRef?.setImageURI(uri)
        }
    }

    companion object {
        private const val ARG_SONG_ID = "arg_song_id"
        private const val ARG_START_EDIT = "arg_start_edit"

        fun newInstance(songId: Long, startEdit: Boolean = false): TrackInfoBottomSheet {
            return TrackInfoBottomSheet().apply {
                arguments = Bundle().apply { 
                    putLong(ARG_SONG_ID, songId)
                    putBoolean(ARG_START_EDIT, startEdit)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.dialog_track_info, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val songId = arguments?.getLong(ARG_SONG_ID) ?: return
        val startEdit = arguments?.getBoolean(ARG_START_EDIT) ?: false
        val container = view.findViewById<LinearLayout>(R.id.infoPropertiesContainer)
        val progressBar = view.findViewById<ProgressBar>(R.id.infoProgressBar)
        val ivInfoAlbumArt = view.findViewById<ImageView>(R.id.ivInfoAlbumArt)
        val btnEdit = view.findViewById<MaterialButton>(R.id.btnEditFromInfo)

        lifecycleScope.launch(Dispatchers.IO) {
            val library = viewModel.songsLibrary.value
            val song = library.find { it.id == songId } ?: return@launch
            
            val details = fetchTrackDetailedInfo(requireContext(), song)
            
            val trackUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
            val bitmap: Bitmap? = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    requireContext().contentResolver.loadThumbnail(trackUri, Size(300, 300), null)
                } else {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(requireContext(), trackUri)
                    val artBytes = retriever.embeddedPicture
                    retriever.release()
                    artBytes?.let {
                        BitmapFactory.decodeByteArray(it, 0, it.size)
                    }
                }
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (startEdit) {
                    displayEditTagsDialog(song)
                }
                progressBar.visibility = View.GONE
                container.visibility = View.VISIBLE
                
                ivInfoAlbumArt?.let { iv ->
                    iv.visibility = View.VISIBLE
                    if (bitmap != null) {
                        iv.setImageBitmap(bitmap)
                    } else {
                        iv.setImageResource(R.drawable.ic_music_note_2)
                    }
                }

                container.removeAllViews()
                addPropertyRow(container, "Title", details.title)
                addPropertyRow(container, "Artist", details.artist)
                addPropertyRow(container, "Album", details.album)
                addPropertyRow(container, "Year", details.year)
                addPropertyRow(container, "Genre", details.genre)
                addPropertyRow(container, "Format", details.format)
                addPropertyRow(container, "Bitrate", details.bitrate)
                addPropertyRow(container, "Duration", details.duration)
                addPropertyRow(container, "Size", details.size)
                addPropertyRow(container, "Path", details.path)

                btnEdit.setOnClickListener {
                    displayEditTagsDialog(song)
                }
            }
        }
    }

    private fun displayEditTagsDialog(song: Song) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_tags, null)
        val ivEditAlbumArt = dialogView.findViewById<ImageView>(R.id.ivEditAlbumArt)
        val btnChangeArt = dialogView.findViewById<View>(R.id.btnChangeArt)
        val etTitle = dialogView.findViewById<TextInputEditText>(R.id.etTitle)
        val etArtist = dialogView.findViewById<TextInputEditText>(R.id.etArtist)
        val etAlbum = dialogView.findViewById<TextInputEditText>(R.id.etAlbum)
        val etYear = dialogView.findViewById<TextInputEditText>(R.id.etYear)
        val etGenre = dialogView.findViewById<TextInputEditText>(R.id.etGenre)

        selectedArtworkUri = null
        ivEditAlbumArtRef = ivEditAlbumArt
        etTitle.setText(song.title)
        etArtist.setText(song.artist)
        etAlbum.setText(song.album)

        etYear.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) etYear.setText("") }
        etGenre.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) etGenre.setText("") }

        lifecycleScope.launch(Dispatchers.IO) {
            val details = fetchTrackDetailedInfo(requireContext(), song)
            withContext(Dispatchers.Main) {
                etYear.setText(details.year)
                etGenre.setText(details.genre)
                
                val trackUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
                try {
                    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        requireContext().contentResolver.loadThumbnail(trackUri, Size(300, 300), null)
                    } else {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(requireContext(), trackUri)
                        val artBytes = retriever.embeddedPicture
                        retriever.release()
                        artBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    }
                    if (bitmap != null) ivEditAlbumArt.setImageBitmap(bitmap)
                } catch (_: Exception) {
                    ivEditAlbumArt.setImageResource(R.drawable.ic_music_note_2)
                }
            }
        }

        btnChangeArt.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Edit Metadata")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val newTitle = etTitle.text?.toString()?.trim().orEmpty().ifBlank { song.title }
                val newArtist = etArtist.text?.toString()?.trim().orEmpty().ifBlank { song.artist }
                val newAlbum = etAlbum.text?.toString()?.trim().orEmpty().ifBlank { song.album }
                val newYear = etYear.text?.toString()?.trim().orEmpty()
                val newGenre = etGenre.text?.toString()?.trim().orEmpty()

                viewModel.permanentUpdateSongMetadata(
                    requireContext(), song.id, newTitle, newArtist, newAlbum,
                    newYear, newGenre, selectedArtworkUri, song.path,
                )
                dismiss() // Close info sheet after saving
            }
            .show()
    }
    private fun addPropertyRow(parent: LinearLayout, label: String, value: String) {
        val context = parent.context
        val itemLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        val labelTv = TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        val valueTv = TextView(context).apply {
            text = value
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
        }
        itemLayout.addView(labelTv)
        itemLayout.addView(valueTv)
        parent.addView(itemLayout)
    }
}