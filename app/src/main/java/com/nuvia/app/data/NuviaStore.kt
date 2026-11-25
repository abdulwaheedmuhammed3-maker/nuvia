package com.nuvia.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object NuviaStore {
    private const val PREFS = "nuvia_preferences"
    private const val RECORDS = "notification_records"
    private const val REPLACE_ORIGINALS = "replace_originals"
    private const val HIDE_CONTENT = "hide_content"
    private const val ISLAND_ENABLED = "floating_island_enabled"
    private const val MANAGED_PACKAGES = "managed_packages"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveNotification(context: Context, record: NotificationRecord) {
        val current = getNotifications(context).toMutableList()
        val existingIndex = current.indexOfFirst { it.key == record.key }
        if (existingIndex >= 0) current.removeAt(existingIndex)
        current.add(0, record)
        val json = JSONArray()
        current.take(100).forEach { item ->
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
        prefs(context).edit().putString(RECORDS, json.toString()).apply()
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
        prefs(context).edit().remove(RECORDS).apply()
    }

    fun isReplacementEnabled(context: Context): Boolean =
        prefs(context).getBoolean(REPLACE_ORIGINALS, false)

    fun setReplacementEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(REPLACE_ORIGINALS, enabled).apply()
    }

    fun isIslandEnabled(context: Context): Boolean =
        prefs(context).getBoolean(ISLAND_ENABLED, true)

    fun setIslandEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(ISLAND_ENABLED, enabled).apply()
    }
    fun isHideContentEnabled(context: Context): Boolean =
        prefs(context).getBoolean(HIDE_CONTENT, false)

    fun setHideContentEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(HIDE_CONTENT, enabled).apply()
    }

    fun managedPackages(context: Context): Set<String> =
        prefs(context).getStringSet(MANAGED_PACKAGES, emptySet())?.toSet() ?: emptySet()

    fun setPackageManaged(context: Context, packageName: String, enabled: Boolean) {
        val updated = managedPackages(context).toMutableSet()
        if (enabled) updated.add(packageName) else updated.remove(packageName)
        prefs(context).edit().putStringSet(MANAGED_PACKAGES, updated).apply()
    }

    fun setAllPackagesManaged(context: Context, packageNames: Collection<String>) {
        prefs(context).edit().putStringSet(MANAGED_PACKAGES, packageNames.toSet()).apply()
    }

    fun isPackageManaged(context: Context, packageName: String): Boolean =
        managedPackages(context).contains(packageName)
}
