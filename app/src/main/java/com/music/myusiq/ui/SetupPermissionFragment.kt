package com.music.myusiq.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.music.myusiq.databinding.FragmentSetupPermissionBinding
import com.music.myusiq.R

class SetupPermissionFragment : Fragment(R.layout.fragment_setup_permission) {
    private var _binding: FragmentSetupPermissionBinding? = null
    private val binding get() = _binding!!

    private fun getMediaPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun getAllPermissions(): Array<String> {
        val permissions = mutableListOf(getMediaPermission())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return permissions.toTypedArray()
    }

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        refreshAndNotify()
    }

    private val requestMediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        refreshAndNotify()
    }

    private val requestNotifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        refreshAndNotify()
    }

    private val requestBtPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        refreshAndNotify()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSetupPermissionBinding.bind(view)

        binding.cardMedia.setOnClickListener {
            if (!checkStoragePermission()) {
                requestMediaPermissionLauncher.launch(getMediaPermission())
            }
        }

        binding.cardNotif.setOnClickListener {
            if (!checkNotificationPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        binding.cardBluetooth.setOnClickListener {
            if (!checkBluetoothPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestBtPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        binding.btnGrantPermission.setOnClickListener {
            val ungranted = getAllPermissions().filter { perm ->
                ContextCompat.checkSelfPermission(requireContext(), perm) != PackageManager.PERMISSION_GRANTED
            }
            if (ungranted.isNotEmpty()) {
                requestMultiplePermissionsLauncher.launch(ungranted.toTypedArray())
            } else {
                refreshAndNotify()
            }
        }

        refreshAndNotify()
    }

    override fun onResume() {
        super.onResume()
        refreshAndNotify()
    }

    private fun checkStoragePermission(): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), getMediaPermission()) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun checkBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun checkCorePermissions(): Boolean {
        return checkStoragePermission() && checkNotificationPermission()
    }

    private fun refreshAndNotify() {
        val storageGranted = checkStoragePermission()
        val notifGranted = checkNotificationPermission()
        val btGranted = checkBluetoothPermission()
        val coreGranted = checkCorePermissions()

        updateStatusBadge(binding.tvStatusMedia, storageGranted)
        updateStatusBadge(binding.tvStatusNotif, notifGranted)
        updateStatusBadge(binding.tvStatusBt, btGranted)

        if (coreGranted) {
            binding.btnGrantPermission.text = getString(R.string.permission_verified)
            binding.btnGrantPermission.setIconResource(R.drawable.ic_check)
        } else {
            binding.btnGrantPermission.text = getString(R.string.grant_access)
            binding.btnGrantPermission.setIconResource(0)
        }

        (activity as? SetupActivity)?.onPermissionChanged(coreGranted)
    }

    private fun updateStatusBadge(textView: TextView, isGranted: Boolean) {
        if (isGranted) {
            textView.text = getString(R.string.perm_status_granted)
            textView.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            textView.text = getString(R.string.perm_status_pending)
            textView.setTextColor(Color.parseColor("#E53935"))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}