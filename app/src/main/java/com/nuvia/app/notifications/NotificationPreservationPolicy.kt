package com.nuvia.app.notifications

import android.app.Notification

/**
 * Notifications that participate in ongoing or grouped system UI must remain owned by
 * their source app. Replacing only part of a notification group can leave a stale summary.
 */
internal fun shouldPreserveOriginalNotification(
    isOngoing: Boolean,
    flags: Int,
    groupKey: String?,
    category: String?
): Boolean {
    if (isOngoing) return true
    if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return true
    if (flags and Notification.FLAG_ONGOING_EVENT != 0) return true
    if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return true
    if (!groupKey.isNullOrBlank()) return true

    return when (category) {
        Notification.CATEGORY_CALL,
        Notification.CATEGORY_ALARM,
        Notification.CATEGORY_SERVICE,
        Notification.CATEGORY_TRANSPORT,
        Notification.CATEGORY_NAVIGATION,
        Notification.CATEGORY_SYSTEM -> true
        else -> false
    }
}
