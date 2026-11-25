package com.nuvia.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

object NuviaStore {
    private const val PREFS = "nuvia_preferences"
    private const val RECORDS = "notification_records"
    private const val REPLACE_ORIGINALS = "replace_originals"
    private const val HIDE_CONTENT = "hide_content"
    private const val ISLAND_ENABLED = "floating_island_enabled"
    private const val ISLAND_DISPLAY_DURATION_SECONDS = "island_display_duration_seconds"
    private const val MANAGED_PACKAGES = "managed_packages"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveNotification(context: Context, record: NotificationRecord) {
        val current = getNotifications(context).toMutableList()
        val existingIndex = current.indexOfFirst { it.key == record.key }
        if (existingIndex >= 0) current.removeAt(existingIndex)
        current.add(0, record)
        persistNotifications(context, current)
    }

    fun redactExistingNotificationContent(context: Context) {
        val redacted = getNotifications(context).map { record ->
            record.copy(
                title = "New notification",
                message = "Content hidden by Nuvia"
            )
        }
        persistNotifications(context, redacted)
    }

    private fun persistNotifications(context: Context, records: List<NotificationRecord>) {
        val json = JSONArray()
        records.take(100).forEach { item ->
            json.put(JSONObject().apply {
                put("key", item.key)
                put("packageName", item.packageName)
                put("appName", item.appName)
                put("title", item.title)
                put("message", item.message)
                put("postedAt", item.postedAt)
                put("hasActions", item.hasActions)
            })
        }
        prefs(context).edit { putString(RECORDS, json.toString()) }
    }

    fun getNotifications(context: Context): List<NotificationRecord> {
        val raw = prefs(context).getString(RECORDS, "[]") ?: "[]"
        return try {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    val item = json.getJSONObject(index)
                    add(
                        NotificationRecord(
                            key = item.optString("key"),
                            packageName = item.optString("packageName"),
                            appName = item.optString("appName", "App"),
                            title = item.optString("title"),
                            message = item.optString("message"),
                            postedAt = item.optLong("postedAt"),
                            hasActions = item.optBoolean("hasActions")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearNotifications(context: Context) {
        prefs(context).edit { remove(RECORDS) }
    }

    fun isReplacementEnabled(context: Context): Boolean =
        prefs(context).getBoolean(REPLACE_ORIGINALS, false)

    fun setReplacementEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(REPLACE_ORIGINALS, enabled) }
    }

    fun isIslandEnabled(context: Context): Boolean =
        prefs(context).getBoolean(ISLAND_ENABLED, true)

    fun setIslandEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(ISLAND_ENABLED, enabled) }
    }

    fun islandDisplayDurationSeconds(context: Context): Int =
        prefs(context).getInt(ISLAND_DISPLAY_DURATION_SECONDS, 5).coerceIn(1, 15)

    fun setIslandDisplayDurationSeconds(context: Context, seconds: Int) {
        prefs(context).edit { putInt(ISLAND_DISPLAY_DURATION_SECONDS, seconds.coerceIn(1, 15)) }
    }
    fun isHideContentEnabled(context: Context): Boolean =
        prefs(context).getBoolean(HIDE_CONTENT, false)

    fun setHideContentEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(HIDE_CONTENT, enabled) }
    }

    fun managedPackages(context: Context): Set<String> =
        prefs(context).getStringSet(MANAGED_PACKAGES, emptySet())?.toSet() ?: emptySet()

    fun setPackageManaged(context: Context, packageName: String, enabled: Boolean) {
        val updated = managedPackages(context).toMutableSet()
        if (enabled) updated.add(packageName) else updated.remove(packageName)
        prefs(context).edit { putStringSet(MANAGED_PACKAGES, updated) }
    }

    fun setAllPackagesManaged(context: Context, packageNames: Collection<String>) {
        prefs(context).edit { putStringSet(MANAGED_PACKAGES, packageNames.toSet()) }
    }

    fun isPackageManaged(context: Context, packageName: String): Boolean =
        managedPackages(context).contains(packageName)
}
