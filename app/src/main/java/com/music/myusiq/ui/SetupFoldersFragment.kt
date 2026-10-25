package com.music.myusiq.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import com.music.myusiq.ui.adapters.CustomFolderAdapter
import com.music.myusiq.ui.adapters.CustomFolderItem
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
    private lateinit var customFolderAdapter: CustomFolderAdapter

    private var discoveredFoldersList = listOf<FolderItem>()
    private var cachedFolderMap: Map<String, Int>? = null

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

        setupRecyclerViews()
        setupModeToggle()
        setupSmartPresets()

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

        if (checkPermissionGranted()) {
            loadDeviceFolders(forceRescan = true)
        }
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        if (checkPermissionGranted() && cachedFolderMap.isNullOrEmpty()) {
            loadDeviceFolders(forceRescan = true)
        }
    }

    private fun checkPermissionGranted(): Boolean {
        val ctx = context ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun setupRecyclerViews() {
        folderAdapter = FolderAdapter(
            onToggleFolder = { folder, isChecked ->
                val prefs = FolderPreferences(requireContext())
                prefs.toggleFolderBlacklist(folder.absolutePath, isChecked)
                refreshFolderInclusions()
            },
            onFolderClicked = { _ -> },
            onPlayFolderRequested = { _ -> }
        )
        binding.rvSetupFolders.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSetupFolders.adapter = folderAdapter

        customFolderAdapter = CustomFolderAdapter(
            onRemoveFolderClicked = { customItem ->
                removeCustomFolder(customItem)
            }
        )
        binding.rvCustomFolders.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCustomFolders.adapter = customFolderAdapter
    }

    private fun setupModeToggle() {
        val prefs = FolderPreferences(requireContext())
        val currentMode = prefs.getScanMode()

        if (currentMode == FolderPreferences.SCAN_MODE_WHITELIST) {
            binding.toggleGroupScanMode.check(R.id.btnModeWhitelist)
        } else {
            binding.toggleGroupScanMode.check(R.id.btnModeScanAll)
        }

        binding.toggleGroupScanMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newMode = if (checkedId == R.id.btnModeWhitelist) {
                    FolderPreferences.SCAN_MODE_WHITELIST
                } else {
                    FolderPreferences.SCAN_MODE_ALL
                }
                prefs.setScanMode(newMode)
                updateUI()
                refreshFolderInclusions()
            }
        }
    }

    private fun setupSmartPresets() {
        val prefs = FolderPreferences(requireContext())

        binding.chipExcludeVoiceNotes.isChecked = prefs.isExcludeVoiceNotesEnabled()
        binding.chipExcludeMessaging.isChecked = prefs.isExcludeMessagingEnabled()
        binding.chipExcludeRingtones.isChecked = prefs.isExcludeRingtonesEnabled()

        binding.chipExcludeVoiceNotes.setOnCheckedChangeListener { _, isChecked ->
            prefs.setExcludeVoiceNotesEnabled(isChecked)
            refreshFolderInclusions()
        }
        binding.chipExcludeMessaging.setOnCheckedChangeListener { _, isChecked ->
            prefs.setExcludeMessagingEnabled(isChecked)
            refreshFolderInclusions()
        }
        binding.chipExcludeRingtones.setOnCheckedChangeListener { _, isChecked ->
            prefs.setExcludeRingtonesEnabled(isChecked)
            refreshFolderInclusions()
        }
    }

    private fun loadDeviceFolders(forceRescan: Boolean = false) {
        if (!forceRescan && !cachedFolderMap.isNullOrEmpty()) {
            refreshFolderInclusions()
            return
        }

        if (!checkPermissionGranted()) {
            cachedFolderMap = null
            return
        }

        binding.pbLoadingFolders.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context ?: return@launch
            val scanner = AndroidMusicScanner(ctx)
            val songs = scanner.scanAllSongs()

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

            cachedFolderMap = folderMap

            withContext(Dispatchers.Main) {
                _binding?.let { b ->
                    b.pbLoadingFolders.visibility = View.GONE
                    refreshFolderInclusions()
                }
            }
        }
    }

    private fun refreshFolderInclusions() {
        val folderMap = cachedFolderMap
        if (folderMap == null) {
            if (checkPermissionGranted()) {
                loadDeviceFolders(forceRescan = true)
            }
            return
        }
        val ctx = context ?: return
        val prefs = FolderPreferences(ctx)

        // Initialize setup folders default: deselect all discovered folders by default on initial setup
        if (!prefs.isSetupFoldersInitialized() && folderMap.isNotEmpty()) {
            folderMap.keys.forEach { path ->
                prefs.toggleFolderBlacklist(path, isIncluded = false)
            }
            prefs.setSetupFoldersInitialized(true)
        }

        val scanner = AndroidMusicScanner(ctx)
        val blacklistedPaths = scanner.getBlacklistedFolderPaths()
        val allowedPaths = scanner.getAllowedFolderUris()

        val items = folderMap.map { (path, count) ->
            val isAllowed = allowedPaths.isEmpty() || allowedPaths.any { path.lowercase().startsWith(it.lowercase()) }
            val isBlacklisted = blacklistedPaths.any { path.lowercase().contains(it.lowercase()) }
            FolderItem(path, count, isAllowed && !isBlacklisted)
        }.sortedBy { it.folderName.lowercase() }

        discoveredFoldersList = items

        _binding?.let { b ->
            folderAdapter.submitList(items)
            b.tvScannedFoldersLabel.visibility = if (items.isNotEmpty()) View.VISIBLE else View.GONE
            updateLiveSummary()
        }
    }

    private fun addFolder(uri: Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: Exception) {
            android.util.Log.w("SetupFoldersFragment", "Could not take persistable URI permission for $uri: ${e.message}")
        }
        selectedFolders.add(uri)

        val prefs = FolderPreferences(requireContext())
        prefs.addAllowedFolderUri(uri.toString())

        updateUI()
        loadDeviceFolders(forceRescan = true)
    }

    private fun removeCustomFolder(customItem: CustomFolderItem) {
        val uriStr = customItem.uriString
        try {
            val uri = uriStr.toUri()
            selectedFolders.remove(uri)
        } catch (_: Exception) {}

        val prefs = FolderPreferences(requireContext())
        prefs.removeAllowedFolderUri(uriStr)

        (activity as? SetupActivity)?.onFoldersChanged(selectedFolders.toList())

        updateUI()
        refreshFolderInclusions()
    }

    private fun updateUI() {
        val prefs = FolderPreferences(requireContext())
        val isWhitelistMode = prefs.getScanMode() == FolderPreferences.SCAN_MODE_WHITELIST

        if (isWhitelistMode) {
            binding.cardSmartPresets.visibility = View.GONE
            binding.cardDiscoveredFolders.visibility = View.GONE
            binding.cardWhitelistFolders.visibility = View.VISIBLE

            val customItems = selectedFolders.map { uri ->
                val folderName = if (DocumentsContract.isTreeUri(uri)) {
                    val documentId = DocumentsContract.getTreeDocumentId(uri)
                    documentId.split(":").lastOrNull() ?: documentId
                } else {
                    uri.lastPathSegment ?: "Custom Folder"
                }
                CustomFolderItem(uriString = uri.toString(), displayName = folderName)
            }

            customFolderAdapter.submitList(customItems)
            binding.tvEmptyWhitelistMessage.visibility = if (customItems.isEmpty()) View.VISIBLE else View.GONE
            binding.rvCustomFolders.visibility = if (customItems.isNotEmpty()) View.VISIBLE else View.GONE
        } else {
            binding.cardSmartPresets.visibility = View.VISIBLE
            binding.cardDiscoveredFolders.visibility = View.VISIBLE
            binding.cardWhitelistFolders.visibility = View.GONE
        }

        updateLiveSummary()
    }

    private fun updateLiveSummary() {
        val prefs = FolderPreferences(requireContext())
        val isWhitelistMode = prefs.getScanMode() == FolderPreferences.SCAN_MODE_WHITELIST

        if (isWhitelistMode) {
            val count = selectedFolders.size
            if (count == 0) {
                binding.tvLiveTrackSummary.text = "No custom whitelist directories selected."
            } else {
                binding.tvLiveTrackSummary.text = "$count Whitelisted Custom ${if (count == 1) "Directory" else "Directories"} Active"
            }
        } else {
            val activeFolders = discoveredFoldersList.filter { it.isIncluded }
            val totalTracks = activeFolders.sumOf { it.songCount }
            val folderCount = activeFolders.size

            binding.tvLiveTrackSummary.text = "$totalTracks Tracks Found across $folderCount Selected ${if (folderCount == 1) "Directory" else "Directories"}"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
