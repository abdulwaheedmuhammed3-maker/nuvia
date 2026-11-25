package com.nuvia.app.notifications

import android.app.Notification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPreservationPolicyTest {

    @Test
    fun preservesOngoingAndForegroundNotifications() {
        assertTrue(shouldPreserveOriginalNotification(true, 0, null))
        assertTrue(shouldPreserveOriginalNotification(false, Notification.FLAG_FOREGROUND_SERVICE, null))
        assertTrue(shouldPreserveOriginalNotification(false, Notification.FLAG_ONGOING_EVENT, null))
    }

    @Test
    fun preservesGroupSummariesButAllowsGroupedMessageChildren() {
        assertTrue(shouldPreserveOriginalNotification(false, Notification.FLAG_GROUP_SUMMARY, Notification.CATEGORY_MESSAGE))
        // Messaging apps group conversation notifications. A child must remain eligible
        // for the island; only the summary stays with the source app.
        assertFalse(shouldPreserveOriginalNotification(false, 0, Notification.CATEGORY_MESSAGE))
    }

    @Test
    fun preservesCriticalSystemCategories() {
        listOf(
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_ALARM,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_SYSTEM
        ).forEach { category ->
            assertTrue(shouldPreserveOriginalNotification(false, 0, category))
        }
    }

    @Test
    fun allowsOrdinaryUngroupedMessages() {
        assertFalse(shouldPreserveOriginalNotification(false, 0, Notification.CATEGORY_MESSAGE))
        assertFalse(shouldPreserveOriginalNotification(false, 0, null))
    }

    @Test
    fun cleanupOnlyMatchesNuviaMirrorOrMarkedNativeIslandNotifications() {
        assertTrue(shouldClearNuviaNotification("nuvia:source-key", false))
        assertTrue(shouldClearNuviaNotification(null, true))
        assertFalse(shouldClearNuviaNotification(null, false))
        assertFalse(shouldClearNuviaNotification("another-tag", false))
    }
}
