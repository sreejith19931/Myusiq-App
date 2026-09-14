package com.music.rhythm.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.music.rhythm.databinding.FragmentSetupPermissionBinding
import com.music.rhythm.R

class SetupPermissionFragment : Fragment(R.layout.fragment_setup_permission) {
    private var _binding: FragmentSetupPermissionBinding? = null
    private val binding get() = _binding!!

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        updateUI(isGranted)
        (activity as? SetupActivity)?.onPermissionChanged(isGranted)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSetupPermissionBinding.bind(view)

        binding.btnGrantPermission.setOnClickListener {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            requestPermissionLauncher.launch(permission)
        }

        updateUI(checkPermission())
    }

    private fun checkPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateUI(isGranted: Boolean) {
        if (isGranted) {
            binding.btnGrantPermission.isEnabled = false
            binding.btnGrantPermission.text = getString(R.string.permission_verified)
            binding.btnGrantPermission.setIconResource(R.drawable.ic_check)
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