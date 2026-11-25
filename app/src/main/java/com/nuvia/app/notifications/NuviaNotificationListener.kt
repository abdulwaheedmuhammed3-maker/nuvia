package com.nuvia.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.RemoteInput as AndroidRemoteInput
import android.content.pm.PackageManager
import androidx.core.graphics.drawable.toBitmap
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.Manifest
import com.nuvia.app.R
import com.nuvia.app.data.NotificationRecord
import com.nuvia.app.data.NuviaStore

class NuviaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        createMirrorChannel()
        NuviaHyperIslandController.refreshPermanent(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        if (sbn.packageName == packageName) return

        val notification = sbn.notification ?: return
        val preserveOriginal = shouldPreserveOriginal(sbn)

        val appName = getAppName(sbn.packageName)
        val title = notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val bigText = notification.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val regularText = notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val lines = notification.extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString(" · ") { it.toString() }.orEmpty()
        val message = bigText.ifBlank { regularText.ifBlank { lines } }

        if (title.isBlank() && message.isBlank()) return

        val hideContent = NuviaStore.isHideContentEnabled(this)
        val visibleTitle = if (hideContent) "New notification" else title.ifBlank { appName }
        val visibleMessage = if (hideContent) "Content hidden by Nuvia" else message

        if (!preserveOriginal) {
            NuviaStore.saveNotification(
                this,
                NotificationRecord(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    appName = appName,
                    title = visibleTitle,
                    message = visibleMessage,
                    postedAt = sbn.postTime,
                    hasActions = !notification.actions.isNullOrEmpty()
                )
            )
        }

        val isManagedApp = NuviaStore.isPackageManaged(this, sbn.packageName)
        val islandEnabled = NuviaStore.isIslandEnabled(this) && isManagedApp

        // Native Xiaomi HyperIsland is used when available. Android overlay is only a fallback.
        // Critical/ongoing notifications stay entirely with their source app. Do not
        // create a second island alert for a notification we deliberately preserve.
        val islandShown = if (islandEnabled && !preserveOriginal) {
            NuviaHyperIslandController.showNotification(
                context = this,
                sbn = sbn,
                appName = appName,
                title = visibleTitle,
                message = visibleMessage
            )
        } else {
            false
        }

        // In island mode, replace the source only after Nuvia confirms that the island alert
        // was posted. Otherwise leave the original untouched so the notification is not lost.
        val shouldReplace = ((islandEnabled && islandShown) ||
            NuviaStore.isReplacementEnabled(this)) && isManagedApp && !preserveOriginal

        // Replacing the source notification remains an independent optional setting.
        if (shouldReplace) {
            publishReplacement(sbn, appName, title, message)
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int
    ) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        if (sbn == null) return

        // Release native-island key mappings when Nuvia's own timed island notification expires.
        if (sbn.packageName == packageName) {
            if (sbn.tag == null) NuviaHyperIslandController.onNativeNotificationRemoved(sbn.id)
            return
        }

        // Do not react to REASON_LISTENER_CANCEL: that is Nuvia dismissing the source
        // after its quiet copy is posted. App/user cancellations should close the matching island.
        val sourceWasCancelled = reason == REASON_CANCEL ||
            reason == REASON_CANCEL_ALL ||
            reason == REASON_APP_CANCEL ||
            reason == REASON_APP_CANCEL_ALL ||
            reason == REASON_GROUP_SUMMARY_CANCELED ||
            reason == REASON_TIMEOUT
        if (sourceWasCancelled) {
            NuviaHyperIslandController.dismiss(this, sbn.key)
        }
    }

    private fun shouldPreserveOriginal(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification
        val flags = notification.flags
        if (sbn.isOngoing) return true
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return true
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return true
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return true

        return when (notification.category) {
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_ALARM,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_SYSTEM -> true
            else -> false
        }
    }

    private fun publishReplacement(
        sbn: StatusBarNotification,
        appName: String,
        title: String,
        message: String
    ): Boolean {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false

        return try {
            createMirrorChannel()
            if (!isQuietMirrorChannelEnabled()) return false
            val original = sbn.notification
            val hideContent = NuviaStore.isHideContentEnabled(this)
            val visibleTitle = if (hideContent) "New notification" else title.ifBlank { appName }
            val visibleMessage = if (hideContent) "Content hidden by Nuvia" else message
            // The shade notification is technically posted by Nuvia, but use the source
            // application's launcher icon as its large/avatar icon so each card is identifiable.
            val sourceAppLargeIcon = runCatching {
                packageManager.getApplicationIcon(sbn.packageName).toBitmap(width = 96, height = 96)
            }.getOrNull()

            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_nuvia_stat)
                .setLargeIcon(sourceAppLargeIcon)
                .setSubText(appName)
                .setContentTitle(visibleTitle)
                .setContentText(visibleMessage)
                .setStyle(NotificationCompat.BigTextStyle().bigText(visibleMessage))
                .setContentIntent(original.contentIntent)
                .setDeleteIntent(original.deleteIntent)
                .setCategory(original.category)
                .setWhen(sbn.postTime)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)

            original.actions?.take(3)?.forEach { action ->
                val actionIcon = action.icon.takeIf { it != 0 } ?: R.drawable.ic_nuvia_stat
                val actionBuilder = NotificationCompat.Action.Builder(
                    actionIcon,
                    action.title,
                    action.actionIntent
                )

                action.remoteInputs?.forEach { input ->
                    actionBuilder.addRemoteInput(input.toCompatRemoteInput())
                }
                actionBuilder.setAllowGeneratedReplies(action.allowGeneratedReplies)
                if (Build.VERSION.SDK_INT >= 28) {
                    actionBuilder.setSemanticAction(action.semanticAction)
                }
                actionBuilder.addExtras(action.extras)
                builder.addAction(actionBuilder.build())
            }

            val replacementTag = "nuvia:${sbn.key}"
            NotificationManagerCompat.from(this).notify(replacementTag, sbn.id, builder.build())

            // NotificationManager.notify() has no result. Confirm the quiet copy is active
            // before dismissing the source, so blocked channels cannot silently lose an alert.
            val replacementIsActive = getSystemService(NotificationManager::class.java)
                .activeNotifications
                .any { it.tag == replacementTag && it.id == sbn.id }
            if (!replacementIsActive) return false

            cancelNotification(sbn.key)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun AndroidRemoteInput.toCompatRemoteInput(): androidx.core.app.RemoteInput {
        val builder = androidx.core.app.RemoteInput.Builder(resultKey)
            .setLabel(label)
            .setChoices(choices)
            .setAllowFreeFormInput(allowFreeFormInput)
            .addExtras(extras)
        if (Build.VERSION.SDK_INT >= 29) {
            builder.setEditChoicesBeforeSending(editChoicesBeforeSending)
        }
        if (Build.VERSION.SDK_INT >= 26) {
            allowedDataTypes?.forEach { builder.setAllowDataType(it, true) }
        }
        return builder.build()
    }

    private fun isQuietMirrorChannelEnabled(): Boolean {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val channel = getSystemService(NotificationManager::class.java)
            .getNotificationChannel(CHANNEL_ID) ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun createMirrorChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Nuvia quiet copies",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Quiet copies retained in the notification shade while Nuvia's island handles pop-up alerts"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(true)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun getAppName(packageName: String): String = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName.substringAfterLast('.')
    }

    companion object {
        private const val CHANNEL_ID = "nuvia_replacements_v2"
    }
}
