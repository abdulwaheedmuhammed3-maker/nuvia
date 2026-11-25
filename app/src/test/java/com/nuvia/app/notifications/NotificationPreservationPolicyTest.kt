package com.nuvia.app.notifications

import android.app.Notification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPreservationPolicyTest {

    @Test
    fun preservesOngoingAndForegroundNotifications() {
        assertTrue(shouldPreserveOriginalNotification(true, 0, null, null))
        assertTrue(
            shouldPreserveOriginalNotification(
                false, Notification.FLAG_FOREGROUND_SERVICE, null, null
            )
        )
        assertTrue(
            shouldPreserveOriginalNotification(
                false, Notification.FLAG_ONGOING_EVENT, null, null
            )
        )
    }

    @Test
    fun preservesGroupSummariesAndGroupedChildren() {
        assertTrue(
            shouldPreserveOriginalNotification(
                false, Notification.FLAG_GROUP_SUMMARY, "group.messages", null
            )
        )
        assertTrue(
            shouldPreserveOriginalNotification(
                false, 0, "group.messages", Notification.CATEGORY_MESSAGE
            )
        )
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
            assertTrue(shouldPreserveOriginalNotification(false, 0, null, category))
        }
    }

    @Test
    fun allowsOrdinaryUngroupedMessages() {
        assertFalse(
            shouldPreserveOriginalNotification(
                false, 0, null, Notification.CATEGORY_MESSAGE
            )
        )
        assertFalse(shouldPreserveOriginalNotification(false, 0, "", null))
    }
}
