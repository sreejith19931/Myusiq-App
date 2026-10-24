package com.music.myusiq.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

@Keep
data class GithubRelease(
    @SerializedName("tag_name") val tagName: String = "",
    @SerializedName("name") val name: String? = null,
    @SerializedName("body") val body: String? = null,
    @SerializedName("html_url") val htmlUrl: String = "",
    @SerializedName("assets") val assets: List<GithubAsset> = emptyList()
)

@Keep
data class GithubAsset(
    @SerializedName("name") val name: String = "",
    @SerializedName("browser_download_url") val downloadUrl: String = ""
)

@Keep
data class UpdateInfo(
    val hasUpdate: Boolean,
    val currentVersion: String,
    val latestVersion: String,
    val releaseNotes: String,
    val apkDownloadUrl: String?,
    val releasePageUrl: String
)

sealed class UpdateCheckResult {
    data class Success(val updateInfo: UpdateInfo) : UpdateCheckResult()
    object Skipped : UpdateCheckResult()
    data class Error(val message: String, val isNetworkError: Boolean = false) : UpdateCheckResult()
}

object UpdateManager {
    private const val TAG = "UpdateManager"
    private const val PRIMARY_GITHUB_REPO_URL = "https://api.github.com/repos/sreejith19931/Myusiq-App/releases/latest"
    private const val FALLBACK_GITHUB_REPO_URL = "https://api.github.com/repos/sreejith19931/Myusiq/releases/latest"
    private const val PREFS_NAME = "update_prefs"
    private const val KEY_LAST_CHECK_TIME = "last_check_time"
    private const val KEY_PENDING_INSTALL_PATH = "pending_install_path"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    suspend fun checkForUpdates(context: Context, force: Boolean = false): UpdateCheckResult = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheckTime = prefs.getLong(KEY_LAST_CHECK_TIME, 0)
        val currentTime = System.currentTimeMillis()

        if (!force && (currentTime - lastCheckTime < CHECK_INTERVAL_MS)) {
            Log.d(TAG, "Skipping background update check, recently checked within 24h.")
            return@withContext UpdateCheckResult.Skipped
        }

        val endpoints = listOf(PRIMARY_GITHUB_REPO_URL, FALLBACK_GITHUB_REPO_URL)
        var lastHttpCode = -1
        var networkErrorEncountered = false

        for (url in endpoints) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "Myusiq-Android-App")
                    .build()

                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    lastHttpCode = response.code
                    Log.e(TAG, "GitHub API call to $url failed: ${response.code}")
                    continue
                }

                val bodyString = response.body?.string() ?: continue
                val release = Gson().fromJson(bodyString, GithubRelease::class.java)
                    ?: continue

                prefs.edit {
                    putLong(KEY_LAST_CHECK_TIME, currentTime)
                }

                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val currentVersion = packageInfo.versionName ?: "1.0.0"
                val latestVersion = release.tagName.trimStart('v', 'V')

                val hasUpdate = isVersionNewer(currentVersion, latestVersion)

                val apkAsset = release.assets.find {
                    it.name.endsWith(".apk", ignoreCase = true) && !it.name.contains("wear", ignoreCase = true)
                } ?: release.assets.find {
                    it.name.endsWith(".apk", ignoreCase = true)
                } ?: release.assets.firstOrNull()

                val updateInfo = UpdateInfo(
                    hasUpdate = hasUpdate,
                    currentVersion = currentVersion,
                    latestVersion = release.tagName,
                    releaseNotes = release.body ?: "No release notes provided.",
                    apkDownloadUrl = apkAsset?.downloadUrl,
                    releasePageUrl = release.htmlUrl
                )

                return@withContext UpdateCheckResult.Success(updateInfo)

            } catch (e: Exception) {
                when (e) {
                    is UnknownHostException, is SocketTimeoutException, is ConnectException, is IOException -> {
                        networkErrorEncountered = true
                        Log.e(TAG, "Network error checking updates from $url", e)
                    }
                    is JsonSyntaxException -> {
                        Log.e(TAG, "JSON parse error checking updates from $url", e)
                        return@withContext UpdateCheckResult.Error("Error parsing update data from server.")
                    }
                    else -> {
                        Log.e(TAG, "Error checking for updates from $url", e)
                    }
                }
            }
        }

        if (networkErrorEncountered) {
            return@withContext UpdateCheckResult.Error("Unable to check for updates. Check internet connection.", isNetworkError = true)
        }

        if (lastHttpCode == 403 || lastHttpCode == 429) {
            return@withContext UpdateCheckResult.Error("GitHub API rate limit reached. Please try again later.")
        }

        if (lastHttpCode == 404) {
            return@withContext UpdateCheckResult.Error("No update releases found on GitHub.")
        }

        return@withContext UpdateCheckResult.Error("Unable to check for updates. Please try again later.")
    }

    fun isVersionNewer(current: String, latest: String): Boolean {
        try {
            val currentClean = current.trimStart('v', 'V').trim()
            val latestClean = latest.trimStart('v', 'V').trim()

            if (currentClean.equals(latestClean, ignoreCase = true)) return false

            // Extract base version (e.g. "1.2.0" from "1.2.0-beta2") and pre-release status
            val currentBase = currentClean.substringBefore('-')
            val latestBase = latestClean.substringBefore('-')

            val currentParts = currentBase.split(".").map { it.filter { char -> char.isDigit() }.toIntOrNull() ?: 0 }
            val latestParts = latestBase.split(".").map { it.filter { char -> char.isDigit() }.toIntOrNull() ?: 0 }

            val length = maxOf(currentParts.size, latestParts.size)
            for (i in 0 until length) {
                val c = currentParts.getOrElse(i) { 0 }
                val l = latestParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }

            // Base versions are equal (e.g. "1.2.0" vs "1.2.0")
            val currentHasPre = currentClean.contains("-")
            val latestHasPre = latestClean.contains("-")

            // If current is pre-release (e.g. 1.2.0-beta2) and latest is official release (1.2.0), latest is newer
            if (currentHasPre && !latestHasPre) return true
            // If current is official release and latest is pre-release, latest is not newer
            if (!currentHasPre && latestHasPre) return false

            // Both have pre-release tags (e.g. "1.2.0-beta1" vs "1.2.0-beta2")
            if (currentHasPre && latestHasPre) {
                val cNum = currentClean.filter { it.isDigit() }.toIntOrNull() ?: 0
                val lNum = latestClean.filter { it.isDigit() }.toIntOrNull() ?: 0
                return lNum > cNum
            }
        } catch (e: Exception) {
            Log.e(TAG, "Version parse error", e)
        }
        return false
    }

    fun showUpdateDialog(context: Context, updateInfo: UpdateInfo) {
        val rawNotes = updateInfo.releaseNotes
        val cleanNotes = rawNotes
            .replace(Regex("\\[(.*?)\\]\\((.*?)\\)"), "$1")
            .replace(Regex("#{1,6}\\s*"), "")
            .trim()

        val conciseNotes = if (cleanNotes.length > 600) {
            cleanNotes.substring(0, 600) + "...\n(See GitHub for full changelog)"
        } else {
            cleanNotes
        }

        val message = "A new version (${updateInfo.latestVersion}) is available!\n\n" +
                "Highlights:\n$conciseNotes"

        MaterialAlertDialogBuilder(context)
            .setTitle("New Update Available 🚀")
            .setMessage(message)
            .setPositiveButton("Install Update") { _, _ ->
                if (!updateInfo.apkDownloadUrl.isNullOrEmpty()) {
                    downloadAndInstallApk(context, updateInfo.apkDownloadUrl, updateInfo.latestVersion)
                } else {
                    openReleasePage(context, updateInfo.releasePageUrl)
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    fun checkPendingInstall(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val pendingPath = prefs.getString(KEY_PENDING_INSTALL_PATH, null) ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                return
            }

            val file = File(pendingPath)
            if (file.exists() && file.length() > 0) {
                prefs.edit { remove(KEY_PENDING_INSTALL_PATH) }
                promptInstallApk(context, file)
            } else {
                prefs.edit { remove(KEY_PENDING_INSTALL_PATH) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking pending install", e)
        }
    }

    private fun downloadAndInstallApk(context: Context, apkUrl: String, version: String) {
        try {
            val fileName = "Myusiq-$version.apk"
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val apkFile = File(downloadDir, fileName)

            if (apkFile.exists() && apkFile.length() > 0) {
                promptInstallApk(context, apkFile)
                return
            }

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (downloadManager == null) {
                openReleasePage(context, apkUrl)
                return
            }

            if (apkFile.exists()) {
                apkFile.delete()
            }

            val uri = Uri.parse(apkUrl)

            val request = DownloadManager.Request(uri).apply {
                setTitle("Downloading Myusiq Update ($version)")
                setDescription("Downloading update file...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val downloadId = downloadManager.enqueue(request)
            Toast.makeText(context, "Download started. Check notifications.", Toast.LENGTH_SHORT).show()

            val onComplete = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        try {
                            c.unregisterReceiver(this)
                        } catch (e: Exception) {
                            Log.w(TAG, "Receiver already unregistered or failed to unregister", e)
                        }

                        val query = DownloadManager.Query().setFilterById(downloadId)
                        val cursor = downloadManager.query(query)
                        var downloadSuccess = false

                        if (cursor != null && cursor.moveToFirst()) {
                            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else -1
                            cursor.close()

                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                downloadSuccess = true
                            }
                        } else {
                            cursor?.close()
                        }

                        if (downloadSuccess && apkFile.exists()) {
                            promptInstallApk(c, apkFile)
                        } else {
                            Log.e(TAG, "APK Download failed or file missing at ${apkFile.absolutePath}")
                            Toast.makeText(c, "Download failed. Opening release page...", Toast.LENGTH_SHORT).show()
                            openReleasePage(c, apkUrl)
                        }
                    }
                }
            }

            ContextCompat.registerReceiver(
                context,
                onComplete,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating APK download", e)
            openReleasePage(context, apkUrl)
        }
    }

    private fun promptInstallApk(context: Context, file: File) {
        try {
            if (!file.exists()) {
                Log.e(TAG, "APK file does not exist: ${file.absolutePath}")
                Toast.makeText(context, "Update file not found.", Toast.LENGTH_SHORT).show()
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit { putString(KEY_PENDING_INSTALL_PATH, file.absolutePath) }

                Toast.makeText(context, "Please allow Myusiq to install unknown apps in Settings.", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return
            }

            val installUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } else {
                Uri.fromFile(file)
            }

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(installUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error prompting APK install", e)
            Toast.makeText(context, "Downloaded update to Downloads folder: ${file.name}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openReleasePage(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Could not open browser", Toast.LENGTH_SHORT).show()
        }
    }
}
