package com.nuvia.app.notifications

import android.app.Notification

/**
 * Keep ongoing/system-critical alerts and group summaries under source-app control.
 * Ordinary grouped conversation notifications remain eligible for island presentation.
 */
internal fun shouldPreserveOriginalNotification(
    isOngoing: Boolean,
    flags: Int,
    category: String?
): Boolean {
    if (isOngoing) return true
    if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return true
    if (flags and Notification.FLAG_ONGOING_EVENT != 0) return true
    if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return true

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
