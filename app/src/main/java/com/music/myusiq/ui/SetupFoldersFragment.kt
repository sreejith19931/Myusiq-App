package com.music.myusiq.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.music.myusiq.R
import com.music.myusiq.data.platform.AndroidMusicScanner
import com.music.myusiq.databinding.FragmentSetupFoldersBinding
import com.music.myusiq.domain.models.FolderItem
import com.music.myusiq.ui.adapters.FolderAdapter
import com.music.myusiq.utils.FolderPreferences
import com.music.myusiq.utils.PlatformUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SetupFoldersFragment : Fragment(R.layout.fragment_setup_folders) {
    private var _binding: FragmentSetupFoldersBinding? = null
    private val binding get() = _binding!!
    
    private val selectedFolders = mutableSetOf<Uri>()
    private lateinit var folderAdapter: FolderAdapter

    private val openDocumentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let { 
            addFolder(it)
            (activity as? SetupActivity)?.onFoldersChanged(selectedFolders.toList())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSetupFoldersBinding.bind(view)

        setupRecyclerView()

        // Restore previously selected or saved folders
        val activityFolders = (activity as? SetupActivity)?.getSelectedFolders() ?: emptyList()
        if (activityFolders.isNotEmpty()) {
            selectedFolders.addAll(activityFolders)
        } else {
            val savedFolderUris = FolderPreferences(requireContext()).getAllowedFolderUris()
            savedFolderUris.forEach { uriStr ->
                try {
                    selectedFolders.add(uriStr.toUri())
                } catch (_: Exception) {}
            }
        }

        binding.btnSelectFolder.setOnClickListener {
            openDocumentTreeLauncher.launch(null)
        }

        binding.cardSelectedFolder.setOnClickListener {
            if (selectedFolders.isNotEmpty()) {
                selectedFolders.clear()
                (activity as? SetupActivity)?.onFoldersChanged(emptyList())
                updateUI()
            }
        }

        loadDeviceFolders()
        updateUI()
    }

    private fun setupRecyclerView() {
        folderAdapter = FolderAdapter(
            onToggleFolder = { folder, isChecked ->
                val prefs = FolderPreferences(requireContext())
                prefs.toggleFolderBlacklist(folder.absolutePath, isChecked)
                loadDeviceFolders()
            },
            onFolderClicked = { _ ->
                // Visual toggle
            },
            onPlayFolderRequested = { _ -> }
        )
        binding.rvSetupFolders.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSetupFolders.adapter = folderAdapter
    }

    private fun loadDeviceFolders() {
        binding.pbLoadingFolders.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context ?: return@launch
            val scanner = AndroidMusicScanner(ctx)
            val songs = scanner.scanAllSongs()
            val blacklistedPaths = scanner.getBlacklistedFolderPaths()
            val allowedPaths = scanner.getAllowedFolderUris()

            val folderMap = mutableMapOf<String, Int>()
            songs.forEach { song ->
                val lastSlash = song.path.lastIndexOf('/')
                val parent = if (lastSlash != -1) song.path.substring(0, lastSlash) else ""
                if (parent.isNotEmpty()) {
                    folderMap[parent] = (folderMap[parent] ?: 0) + 1
                }
            }

            val commonDirs = listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS),
                java.io.File(Environment.getExternalStorageDirectory(), "Audio"),
            )
            for (dir in commonDirs) {
                if (dir.exists() && dir.isDirectory) {
                    val normPath = PlatformUtils.normalizePath(dir.absolutePath)
                    if (!folderMap.containsKey(normPath)) {
                        folderMap[normPath] = 0
                    }
                }
            }

            val items = folderMap.map { (path, count) ->
                val isAllowed = allowedPaths.isEmpty() || allowedPaths.any { path.lowercase().startsWith(it.lowercase()) }
                val isBlacklisted = blacklistedPaths.contains(path)
                FolderItem(path, count, isAllowed && !isBlacklisted)
            }.sortedBy { it.folderName.lowercase() }

            withContext(Dispatchers.Main) {
                _binding?.let { b ->
                    b.pbLoadingFolders.visibility = View.GONE
                    folderAdapter.submitList(items)
                    b.tvScannedFoldersLabel.visibility = if (items.isNotEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun addFolder(uri: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            android.util.Log.w("SetupFoldersFragment", "Could not take persistable URI permission for $uri: ${e.message}")
        }
        selectedFolders.add(uri)
        updateUI()
        loadDeviceFolders()
    }

    private fun updateUI() {
        if (selectedFolders.isEmpty()) {
            binding.tvSelectedFolder.text = getString(R.string.no_directories_selected)
            binding.tvSelectedFolder.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.tab_indicator_text))
        } else {
            val displayBuilder = StringBuilder()
            selectedFolders.forEach { uri ->
                val folderName = if (DocumentsContract.isTreeUri(uri)) {
                    val documentId = DocumentsContract.getTreeDocumentId(uri)
                    documentId.split(":").lastOrNull() ?: documentId
                } else {
                    uri.lastPathSegment ?: "Folder"
                }
                displayBuilder.append("• /$folderName\n")
            }
            displayBuilder.append("\n(Tap box to clear selection)")
            binding.tvSelectedFolder.text = displayBuilder.toString().trimEnd()
            binding.tvSelectedFolder.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}