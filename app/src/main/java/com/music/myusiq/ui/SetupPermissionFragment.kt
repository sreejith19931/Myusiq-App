package com.music.myusiq.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.music.myusiq.R
import com.music.myusiq.databinding.FragmentSetupPermissionBinding
import com.music.myusiq.utils.ThemeUtils

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

        updateCardStatus(binding.cardMedia, binding.tvMediaTitle, binding.tvMediaDesc, binding.tvStatusMedia, storageGranted)
        updateCardStatus(binding.cardNotif, binding.tvNotifTitle, binding.tvNotifDesc, binding.tvStatusNotif, notifGranted)
        updateCardStatus(binding.cardBluetooth, binding.tvBtTitle, binding.tvBtDesc, binding.tvStatusBt, btGranted)

        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        if (coreGranted) {
            val bgGreen = if (isDark) Color.parseColor("#1B3E20") else Color.parseColor("#E8F5E9")
            val fgGreen = if (isDark) Color.parseColor("#81C784") else Color.parseColor("#2E7D32")
            val strokeGreen = Color.parseColor("#4CAF50")

            binding.btnGrantPermission.text = getString(R.string.permission_verified)
            binding.btnGrantPermission.setIconResource(R.drawable.ic_check)
            binding.btnGrantPermission.backgroundTintList = ColorStateList.valueOf(bgGreen)
            binding.btnGrantPermission.setTextColor(fgGreen)
            binding.btnGrantPermission.iconTint = ColorStateList.valueOf(fgGreen)
            binding.btnGrantPermission.setStrokeColor(ColorStateList.valueOf(strokeGreen))
            binding.btnGrantPermission.strokeWidth = 2
        } else {
            val primaryColor = ThemeUtils.getThemeColor(requireContext(), androidx.appcompat.R.attr.colorPrimary)
            val onPrimaryColor = ThemeUtils.getThemeColor(requireContext(), com.google.android.material.R.attr.colorOnPrimary)

            binding.btnGrantPermission.text = getString(R.string.grant_access)
            binding.btnGrantPermission.setIconResource(0)
            binding.btnGrantPermission.backgroundTintList = ColorStateList.valueOf(primaryColor)
            binding.btnGrantPermission.setTextColor(if (onPrimaryColor != 0) onPrimaryColor else Color.WHITE)
            binding.btnGrantPermission.iconTint = null
            binding.btnGrantPermission.strokeWidth = 0
        }

        (activity as? SetupActivity)?.onPermissionChanged(coreGranted)
    }

    private fun updateCardStatus(
        card: MaterialCardView,
        titleTv: TextView,
        descTv: TextView,
        statusTv: TextView,
        isGranted: Boolean
    ) {
        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        if (isGranted) {
            card.setCardBackgroundColor(if (isDark) Color.parseColor("#1B3E20") else Color.parseColor("#E8F5E9"))
            card.strokeColor = Color.parseColor("#4CAF50")
            card.alpha = 1.0f
            titleTv.setTypeface(null, Typeface.BOLD)
            descTv.setTypeface(null, Typeface.BOLD)
        } else {
            card.setCardBackgroundColor(if (isDark) Color.parseColor("#1A1A1A") else Color.parseColor("#F5F5F5"))
            card.strokeColor = if (isDark) Color.parseColor("#444444") else Color.parseColor("#CCCCCC")
            card.alpha = 0.55f
            titleTv.setTypeface(null, Typeface.NORMAL)
            descTv.setTypeface(null, Typeface.NORMAL)
        }
        statusTv.text = ""
        statusTv.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
