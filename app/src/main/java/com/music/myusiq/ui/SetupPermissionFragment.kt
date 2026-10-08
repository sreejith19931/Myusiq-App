package com.music.myusiq.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.music.myusiq.databinding.FragmentSetupPermissionBinding
import com.music.myusiq.R

class SetupPermissionFragment : Fragment(R.layout.fragment_setup_permission) {
    private var _binding: FragmentSetupPermissionBinding? = null
    private val binding get() = _binding!!

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()

        // 1. Storage / Media Access
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        // 2. Audio Visualizer & Equalizer
        permissions.add(Manifest.permission.RECORD_AUDIO)

        // 3. Bluetooth Output Devices
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        return permissions.toTypedArray()
    }

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val storageGranted = checkStoragePermission()
        val allGranted = checkAllPermissions()
        updateUI(storageGranted, allGranted)
        (activity as? SetupActivity)?.onPermissionChanged(storageGranted)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSetupPermissionBinding.bind(view)

        binding.btnGrantPermission.setOnClickListener {
            requestMultiplePermissionsLauncher.launch(getRequiredPermissions())
        }

        val storageGranted = checkStoragePermission()
        val allGranted = checkAllPermissions()
        updateUI(storageGranted, allGranted)
    }

    private fun checkStoragePermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAllPermissions(): Boolean {
        val ctx = context ?: return false
        return getRequiredPermissions().all { perm ->
            ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun updateUI(storageGranted: Boolean, allGranted: Boolean) {
        if (allGranted) {
            binding.btnGrantPermission.isEnabled = false
            binding.btnGrantPermission.text = getString(R.string.permission_verified)
            binding.btnGrantPermission.setIconResource(R.drawable.ic_check)
        } else if (storageGranted) {
            binding.btnGrantPermission.isEnabled = true
            binding.btnGrantPermission.text = getString(R.string.grant_access)
            binding.btnGrantPermission.setIconResource(0)
        } else {
            binding.btnGrantPermission.isEnabled = true
            binding.btnGrantPermission.text = getString(R.string.grant_access)
            binding.btnGrantPermission.setIconResource(0)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}