package com.nuvia.app.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class NuviaRelease(
    val version: String,
    val notes: String,
    val apkUrl: String
)

enum class InstallerResult { OPENED, PERMISSION_REQUIRED }

object NuviaUpdateManager {
    private const val RELEASE_API =
        "https://api.github.com/repos/abdulwaheedmuhammed3-maker/nuvia/releases/latest"

    /** Call from an IO dispatcher. Returns null when the installed version is current. */
    fun checkForUpdate(context: Context): NuviaRelease? {
        val connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Nuvia-Android-Updater")
        }
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw IOException("No Nuvia release has been published yet.")
            }
            if (code !in 200..299) throw IOException("Update check failed (HTTP $code).")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val version = json.optString("tag_name").removePrefix("v").trim()
            if (version.isBlank()) throw IOException("The latest release has no version tag.")

            val currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
            if (compareVersions(version, currentVersion) <= 0) return null

            val assets = json.optJSONArray("assets") ?: throw IOException("The release has no APK asset.")
            var apkUrl = ""
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url")
                    if (apkUrl.isNotBlank()) break
                }
            }
            if (apkUrl.isBlank()) throw IOException("The release APK is not available yet.")
            return NuviaRelease(version, json.optString("body"), apkUrl)
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads the published APK into app-private cache. Call from an IO dispatcher. */
    fun downloadApk(context: Context, release: NuviaRelease): File {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val destination = File(directory, "nuvia-update.apk")
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "Nuvia-Android-Updater")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("APK download failed (HTTP ${connection.responseCode}).")
            }
            connection.inputStream.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            if (!destination.isFile || destination.length() == 0L) {
                throw IOException("The downloaded APK is empty.")
            }
            return destination
        } finally {
            connection.disconnect()
        }
    }

    fun openInstaller(context: Context, apk: File): InstallerResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(permissionIntent)
            return InstallerResult.PERMISSION_REQUIRED
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val installerIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installerIntent)
        return InstallerResult.OPENED
    }

    private fun compareVersions(first: String, second: String): Int {
        val left = first.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val right = second.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }
}
