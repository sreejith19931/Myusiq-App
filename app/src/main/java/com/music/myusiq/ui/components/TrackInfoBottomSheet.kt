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
import java.io.File
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

    private val pickGalleryImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
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
            
            val artFile = com.music.myusiq.utils.ArtworkUtils.getArtworkFile(requireContext(), song.id, song.path)
            val bitmap: Bitmap? = if (artFile != null && artFile.exists() && artFile.length() > 0) {
                BitmapFactory.decodeFile(artFile.absolutePath)
            } else {
                try {
                    val trackUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        requireContext().contentResolver.loadThumbnail(trackUri, Size(300, 300), null)
                    } else {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(requireContext(), trackUri)
                        val artBytes = retriever.embeddedPicture
                        retriever.release()
                        artBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    }
                } catch (_: Exception) { null }
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
            val artFile = com.music.myusiq.utils.ArtworkUtils.getArtworkFile(requireContext(), song.id, song.path)
            val bitmap: Bitmap? = if (artFile != null && artFile.exists() && artFile.length() > 0) {
                BitmapFactory.decodeFile(artFile.absolutePath)
            } else {
                try {
                    val trackUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        requireContext().contentResolver.loadThumbnail(trackUri, Size(300, 300), null)
                    } else {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(requireContext(), trackUri)
                        val artBytes = retriever.embeddedPicture
                        retriever.release()
                        artBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    }
                } catch (_: Exception) { null }
            }

            withContext(Dispatchers.Main) {
                etYear.setText(details.year)
                etGenre.setText(details.genre)
                
                if (selectedArtworkUri == null) {
                    if (bitmap != null) {
                        ivEditAlbumArt.setImageBitmap(bitmap)
                    } else {
                        ivEditAlbumArt.setImageResource(R.drawable.ic_music_note_2)
                    }
                }
            }
        }

        btnChangeArt.setOnClickListener {
            try {
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } catch (_: Exception) {
                pickGalleryImage.launch("image/*")
            }
        }

        val containerLayout = (dialogView as? android.widget.ScrollView)?.getChildAt(0) as? LinearLayout
        val btnSearchOnline = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Search Online & Fix Metadata"
            setIconResource(R.drawable.ic_search)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 16
            }
            setOnClickListener {
                showManualSearchDialog(requireContext(), song) { candidate, artUri ->
                    etTitle.setText(candidate.foundTitle ?: song.title)
                    etArtist.setText(candidate.foundArtist ?: song.artist)
                    etAlbum.setText(candidate.album ?: song.album)
                    etYear.setText(candidate.year ?: "")
                    etGenre.setText(candidate.genre ?: "")
                    if (artUri != null) {
                        selectedArtworkUri = artUri
                        ivEditAlbumArt.setImageURI(artUri)
                    } else if (candidate.artworkUrl != null) {
                        com.bumptech.glide.Glide.with(requireContext()).load(candidate.artworkUrl).into(ivEditAlbumArt)
                    }
                }
            }
        }
        containerLayout?.addView(btnSearchOnline, 1)

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
            setTextColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF757575.toInt()))
        }
        val valueTv = TextView(context).apply {
            text = value
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0xFF212121.toInt()))
        }
        itemLayout.addView(labelTv)
        itemLayout.addView(valueTv)
        parent.addView(itemLayout)
    }

    private fun showManualSearchDialog(
        context: android.content.Context,
        song: Song,
        onSelected: (com.music.myusiq.utils.CloudMetadata, Uri?) -> Unit
    ) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_manual_cloud_search, null)
        val etQuery = dialogView.findViewById<TextInputEditText>(R.id.etManualSearchQuery)
        val btnSearch = dialogView.findViewById<MaterialButton>(R.id.btnRunManualSearch)
        val progress = dialogView.findViewById<ProgressBar>(R.id.progressManualSearch)
        val rvResults = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvManualSearchResults)

        val initialQuery = "${song.artist} ${song.title}".trim().ifBlank { File(song.path).nameWithoutExtension }
        etQuery.setText(initialQuery)

        rvResults.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)

        var dialog: androidx.appcompat.app.AlertDialog? = null

        fun doSearch() {
            val q = etQuery.text?.toString()?.trim().orEmpty()
            if (q.isBlank()) return
            progress.visibility = View.VISIBLE
            rvResults.visibility = View.GONE

            lifecycleScope.launch(Dispatchers.IO) {
                val results = com.music.myusiq.utils.CloudMetadataResolver.searchMultiple(q, song.title, song.artist)
                withContext(Dispatchers.Main) {
                    progress.visibility = View.GONE
                    rvResults.visibility = View.VISIBLE
                    if (results.isNotEmpty()) {
                        rvResults.adapter = CandidateResultsAdapter(results) { selectedCandidate ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val uri = selectedCandidate.artworkUrl?.let { viewModel.downloadImageToCache(context, it) }
                                withContext(Dispatchers.Main) {
                                    onSelected(selectedCandidate, uri)
                                    dialog?.dismiss()
                                }
                            }
                        }
                    } else {
                        android.widget.Toast.makeText(context, "No online matches found", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnSearch.setOnClickListener { doSearch() }

        dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
        doSearch()
    }
}

class CandidateResultsAdapter(
    private val candidates: List<com.music.myusiq.utils.CloudMetadata>,
    private val onSelect: (com.music.myusiq.utils.CloudMetadata) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<CandidateResultsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val ivArt: ImageView = view.findViewById(R.id.ivResultArt)
        val tvTitle: TextView = view.findViewById(R.id.tvResultTitle)
        val tvArtist: TextView = view.findViewById(R.id.tvResultArtist)
        val tvAlbumInfo: TextView = view.findViewById(R.id.tvResultAlbumInfo)
        val btnSelect: MaterialButton = view.findViewById(R.id.btnSelectCandidate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_cloud_search_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = candidates[position]
        val context = holder.itemView.context

        holder.tvTitle.text = item.foundTitle ?: "Unknown Title"
        holder.tvArtist.text = item.foundArtist ?: "Unknown Artist"
        val pct = (item.matchConfidence * 100).toInt()
        val infoStr = listOf(item.album, item.year, item.genre, "$pct% match").filterNotNull().filter { it.isNotBlank() }.joinToString(" • ")
        holder.tvAlbumInfo.text = infoStr

        if (item.artworkUrl != null) {
            com.bumptech.glide.Glide.with(context).load(item.artworkUrl).placeholder(R.drawable.ic_music_note_2).error(R.drawable.ic_music_note_2).into(holder.ivArt)
        } else {
            com.bumptech.glide.Glide.with(context).load(R.drawable.ic_music_note_2).into(holder.ivArt)
        }

        holder.itemView.setOnClickListener { onSelect(item) }
        holder.btnSelect.setOnClickListener { onSelect(item) }
    }

    override fun getItemCount(): Int = candidates.size
}