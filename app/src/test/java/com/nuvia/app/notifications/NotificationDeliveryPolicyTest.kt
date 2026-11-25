package com.nuvia.app.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDeliveryPolicyTest {

    @Test
    fun unmanagedAppsAreNeverReplaced() {
        val plan = planNotificationDelivery(
            isManagedApp = false,
            islandEnabled = true,
            replacementEnabled = true,
            preserveOriginal = false,
            quietCopyReady = true
        )

        assertFalse(plan.showIsland)
        assertFalse(plan.canPostQuietCopy)
        assertFalse(plan.shouldPostQuietCopy(islandWasShown = true))
    }

    @Test
    fun criticalNotificationsRemainWithTheirSourceApp() {
        val plan = planNotificationDelivery(
            isManagedApp = true,
            islandEnabled = true,
            replacementEnabled = true,
            preserveOriginal = true,
            quietCopyReady = true
        )

        assertFalse(plan.showIsland)
        assertFalse(plan.canPostQuietCopy)
    }

    @Test
    fun islandModeRequiresReadyQuietCopyChannel() {
        val blocked = planNotificationDelivery(
            isManagedApp = true,
            islandEnabled = true,
            replacementEnabled = false,
            preserveOriginal = false,
            quietCopyReady = false
        )

        assertFalse(blocked.showIsland)
        assertFalse(blocked.canPostQuietCopy)

        val ready = planNotificationDelivery(
            isManagedApp = true,
            islandEnabled = true,
            replacementEnabled = false,
            preserveOriginal = false,
            quietCopyReady = true
        )

        assertTrue(ready.showIsland)
        assertTrue(ready.shouldPostQuietCopy(islandWasShown = true))
        assertFalse(ready.shouldPostQuietCopy(islandWasShown = false))
    }

    @Test
    fun replacementOnlyModeWorksWithoutIsland() {
        val plan = planNotificationDelivery(
            isManagedApp = true,
            islandEnabled = false,
            replacementEnabled = true,
            preserveOriginal = false,
            quietCopyReady = true
        )

        assertFalse(plan.showIsland)
        assertTrue(plan.shouldPostQuietCopy(islandWasShown = false))
    }

    @Test
    fun failedQuietCopyRemovesTheIslandPreview() {
        assertTrue(
            shouldDismissIslandAfterReplacementFailure(
                islandWasShown = true,
                replacementWasPosted = false
            )
        )
        assertFalse(
            shouldDismissIslandAfterReplacementFailure(
                islandWasShown = true,
                replacementWasPosted = true
            )
        )
        assertFalse(
            shouldDismissIslandAfterReplacementFailure(
                islandWasShown = false,
                replacementWasPosted = false
            )
        )
    }
}
