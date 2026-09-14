package com.music.rhythm.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.music.rhythm.databinding.FragmentSetupFoldersBinding
import com.music.rhythm.R

class SetupFoldersFragment : Fragment(R.layout.fragment_setup_folders) {
    private var _binding: FragmentSetupFoldersBinding? = null
    private val binding get() = _binding!!
    
    private val selectedFolders = mutableSetOf<Uri>()

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

        binding.btnSelectFolder.setOnClickListener {
            openDocumentTreeLauncher.launch(null)
        }

        updateUI()
    }

    private fun addFolder(uri: Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            selectedFolders.add(uri)
            updateUI()
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
            binding.tvSelectedFolder.text = displayBuilder.toString().trimEnd()
            binding.tvSelectedFolder.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}