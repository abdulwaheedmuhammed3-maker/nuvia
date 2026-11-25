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
import java.security.MessageDigest

data class NuviaRelease(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val checksumUrl: String
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
            var checksumUrl = ""
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                val assetUrl = asset.optString("browser_download_url")
                if (name.endsWith(".apk.sha256", ignoreCase = true)) {
                    checksumUrl = assetUrl
                } else if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = assetUrl
                }
            }
            if (apkUrl.isBlank()) throw IOException("The release APK is not available yet.")
            if (checksumUrl.isBlank()) throw IOException("The release checksum is not available yet.")
            return NuviaRelease(version, json.optString("body"), apkUrl, checksumUrl)
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads the APK and verifies its published SHA-256 before returning it. Call on IO. */
    fun downloadApk(context: Context, release: NuviaRelease): File {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val destination = File(directory, "nuvia-update.apk")
        val temporary = File(directory, "nuvia-update.apk.part")
        temporary.delete()

        val apkConnection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "Nuvia-Android-Updater")
        }
        try {
            if (apkConnection.responseCode !in 200..299) {
                throw IOException("APK download failed (HTTP ${apkConnection.responseCode}).")
            }
            apkConnection.inputStream.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            apkConnection.disconnect()
        }

        if (!temporary.isFile || temporary.length() == 0L) {
            temporary.delete()
            throw IOException("The downloaded APK is empty.")
        }

        val checksumConnection = (URL(release.checksumUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "Nuvia-Android-Updater")
        }
        val expectedHash = try {
            if (checksumConnection.responseCode !in 200..299) {
                throw IOException("Could not retrieve the release checksum.")
            }
            checksumConnection.inputStream.bufferedReader().use { it.readText() }
                .trim()
                .split(Regex("\\s+"))
                .firstOrNull()
                ?.lowercase()
                .orEmpty()
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            checksumConnection.disconnect()
        }

        val digest = MessageDigest.getInstance("SHA-256")
        temporary.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        if (expectedHash.length != 64 || !actualHash.equals(expectedHash, ignoreCase = true)) {
            temporary.delete()
            throw IOException("APK integrity check failed. The file was not installed.")
        }

        if (destination.exists()) destination.delete()
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
        return destination
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
