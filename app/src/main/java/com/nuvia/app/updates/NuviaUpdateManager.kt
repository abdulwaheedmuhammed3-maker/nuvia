package com.nuvia.app.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
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

/** Accept only stable release tags in the exact vMAJOR.MINOR.PATCH form. */
internal fun stableVersionFromTag(tag: String): String? {
    val match = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(tag) ?: return null
    return match.groupValues.drop(1).joinToString(".")
}

/** Numeric version comparison with missing trailing components treated as zero. */
internal fun compareVersions(first: String, second: String): Int {
    val left = first.split('.').map { it.toIntOrNull() ?: 0 }
    val right = second.split('.').map { it.toIntOrNull() ?: 0 }
    for (index in 0 until maxOf(left.size, right.size)) {
        val a = left.getOrElse(index) { 0 }
        val b = right.getOrElse(index) { 0 }
        if (a != b) return a.compareTo(b)
    }
    return 0
}

object NuviaUpdateManager {
    private const val RELEASES_API =
        "https://api.github.com/repos/abdulwaheedmuhammed3-maker/nuvia/releases?per_page=50"
    private const val APK_ASSET = "app-release.apk"
    private const val CHECKSUM_ASSET = "app-release.apk.sha256"

    /** Call from an IO dispatcher. Selects the newest valid stable release with both assets. */
    fun checkForUpdate(context: Context): NuviaRelease? {
        val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Nuvia-Android-Updater")
        }
        val releases = try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Update check failed (HTTP $code).")
            JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }

        val currentVersion = context.packageManager
            .getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"

        data class Candidate(
            val version: String,
            val notes: String,
            val apkUrl: String,
            val checksumUrl: String
        )

        val latest = (0 until releases.length()).mapNotNull { index ->
            val release = releases.optJSONObject(index) ?: return@mapNotNull null
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) return@mapNotNull null

            val version = stableVersionFromTag(release.optString("tag_name")) ?: return@mapNotNull null
            if (compareVersions(version, currentVersion) <= 0) return@mapNotNull null

            val assets = release.optJSONArray("assets") ?: return@mapNotNull null
            var apkUrl = ""
            var checksumUrl = ""
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                when (name) {
                    APK_ASSET -> apkUrl = url
                    CHECKSUM_ASSET -> checksumUrl = url
                }
            }
            if (apkUrl.isBlank() || checksumUrl.isBlank()) return@mapNotNull null

            Candidate(
                version = version,
                notes = release.optString("body"),
                apkUrl = apkUrl,
                checksumUrl = checksumUrl
            )
        }.maxWithOrNull { first, second -> compareVersions(first.version, second.version) }
            ?: return null

        return NuviaRelease(latest.version, latest.notes, latest.apkUrl, latest.checksumUrl)
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

        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            throw IOException("Could not replace the previous update download.")
        }
        if (!temporary.renameTo(destination)) {
            try {
                temporary.copyTo(destination, overwrite = false)
                temporary.delete()
            } catch (error: Exception) {
                destination.delete()
                temporary.delete()
                throw IOException("Could not save the verified update APK.", error)
            }
        }
        return destination
    }

    fun openInstaller(context: Context, apk: File): InstallerResult {
        if (!apk.isFile || apk.length() == 0L) {
            throw IOException("The update APK is missing or empty. Download it again.")
        }

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
}
