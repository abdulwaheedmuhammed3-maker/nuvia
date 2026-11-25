package com.nuvia.app.notifications

internal data class NotificationDeliveryPlan(
    val showIsland: Boolean,
    val canPostQuietCopy: Boolean
) {
    fun shouldPostQuietCopy(islandWasShown: Boolean): Boolean =
        canPostQuietCopy && (!showIsland || islandWasShown)
}

internal fun planNotificationDelivery(
    isManagedApp: Boolean,
    islandEnabled: Boolean,
    replacementEnabled: Boolean,
    preserveOriginal: Boolean,
    quietCopyReady: Boolean
): NotificationDeliveryPlan {
    val eligible = isManagedApp && !preserveOriginal
    if (!eligible || !quietCopyReady) {
        return NotificationDeliveryPlan(showIsland = false, canPostQuietCopy = false)
    }

    val showIsland = islandEnabled
    return NotificationDeliveryPlan(
        showIsland = showIsland,
        canPostQuietCopy = showIsland || replacementEnabled
    )
}

internal fun shouldDismissIslandAfterReplacementFailure(
    islandWasShown: Boolean,
    replacementWasPosted: Boolean
): Boolean = islandWasShown && !replacementWasPosted

/** Clear a mirrored card for every source removal except the cancellation Nuvia performs itself. */
internal fun shouldRemoveMirrorForSourceRemoval(
    removalReason: Int,
    listenerCancelReason: Int
): Boolean = removalReason != listenerCancelReason
