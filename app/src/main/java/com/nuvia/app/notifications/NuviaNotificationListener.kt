package com.nuvia.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.RemoteInput as AndroidRemoteInput
import android.content.Context
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
        val deliveryPlan = planNotificationDelivery(
            isManagedApp = isManagedApp,
            islandEnabled = NuviaStore.isIslandEnabled(this),
            replacementEnabled = NuviaStore.isReplacementEnabled(this),
            preserveOriginal = preserveOriginal,
            quietCopyReady = canPostQuietCopies(this)
        )

        // The policy never emits an island for unmanaged, critical, or unsafe-to-mirror alerts.
        val islandShown = if (deliveryPlan.showIsland) {
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

        // In island mode, only replace the source after both the island and its quiet
        // shade copy have succeeded. Replacement-only mode remains independently available.
        if (deliveryPlan.shouldPostQuietCopy(islandShown)) {
            val replacementPosted = publishReplacement(sbn, appName, title, message)
            if (shouldDismissIslandAfterReplacementFailure(islandShown, replacementPosted)) {
                NuviaHyperIslandController.dismiss(this, sbn.key)
            }
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
            if (sbn.tag == null) {
                if (sbn.id == NATIVE_PERMANENT_ID) {
                    // The native idle island is configured with a bounded system timeout.
                    // Reconcile it after Android removes it, but let refreshPermanent honour
                    // the current island toggle and notification/Focus permissions.
                    NuviaHyperIslandController.refreshPermanent(this)
                } else {
                    NuviaHyperIslandController.onNativeNotificationRemoved(sbn.id)
                }
            }
            return
        }

        // Nuvia's own cancelNotification() is the only source-removal event that
        // must leave its mirrored shade copy in place. All other removal reasons,
        // including snooze, channel/app disable, timeout and user dismissal, clear it.
        if (shouldRemoveMirrorForSourceRemoval(reason, REASON_LISTENER_CANCEL)) {
            NotificationManagerCompat.from(this).cancel("nuvia:${sbn.key}", sbn.id)
            NuviaHyperIslandController.dismiss(this, sbn.key)
        }
    }

    private fun shouldPreserveOriginal(sbn: StatusBarNotification): Boolean =
        shouldPreserveOriginalNotification(
            isOngoing = sbn.isOngoing,
            flags = sbn.notification.flags,
            groupKey = sbn.groupKey,
            category = sbn.notification.category
        )

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
                // The source action icon resource belongs to the source app, not Nuvia.
                // Reusing that integer as a Nuvia resource ID can throw during copy creation.
                // Use Nuvia's own valid icon while preserving the original action intent.
                val actionBuilder = NotificationCompat.Action.Builder(
                    R.drawable.ic_nuvia_stat,
                    action.title,
                    action.actionIntent
                )

                // Android separates text RemoteInputs from data-only inputs used for
                // attachments such as images. Copy both sets so reply actions retain them.
                val remoteInputs = buildList {
                    addAll(action.remoteInputs.orEmpty())
                    addAll(action.dataOnlyRemoteInputs.orEmpty())
                }.distinctBy { it.resultKey }
                remoteInputs.forEach { input ->
                    actionBuilder.addRemoteInput(input.toCompatRemoteInput())
                }
                actionBuilder.setAllowGeneratedReplies(action.allowGeneratedReplies)
                if (Build.VERSION.SDK_INT >= 28) {
                    val semanticAction = when (action.semanticAction) {
                        Notification.Action.SEMANTIC_ACTION_REPLY -> NotificationCompat.Action.SEMANTIC_ACTION_REPLY
                        Notification.Action.SEMANTIC_ACTION_MARK_AS_READ -> NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ
                        Notification.Action.SEMANTIC_ACTION_MARK_AS_UNREAD -> NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_UNREAD
                        Notification.Action.SEMANTIC_ACTION_DELETE -> NotificationCompat.Action.SEMANTIC_ACTION_DELETE
                        Notification.Action.SEMANTIC_ACTION_ARCHIVE -> NotificationCompat.Action.SEMANTIC_ACTION_ARCHIVE
                        Notification.Action.SEMANTIC_ACTION_MUTE -> NotificationCompat.Action.SEMANTIC_ACTION_MUTE
                        Notification.Action.SEMANTIC_ACTION_UNMUTE -> NotificationCompat.Action.SEMANTIC_ACTION_UNMUTE
                        Notification.Action.SEMANTIC_ACTION_THUMBS_UP -> NotificationCompat.Action.SEMANTIC_ACTION_THUMBS_UP
                        Notification.Action.SEMANTIC_ACTION_THUMBS_DOWN -> NotificationCompat.Action.SEMANTIC_ACTION_THUMBS_DOWN
                        Notification.Action.SEMANTIC_ACTION_CALL -> NotificationCompat.Action.SEMANTIC_ACTION_CALL
                        Notification.Action.SEMANTIC_ACTION_PLAY -> NotificationCompat.Action.SEMANTIC_ACTION_PLAY
                        Notification.Action.SEMANTIC_ACTION_PAUSE -> NotificationCompat.Action.SEMANTIC_ACTION_PAUSE
                        Notification.Action.SEMANTIC_ACTION_STOP -> NotificationCompat.Action.SEMANTIC_ACTION_STOP
                        else -> NotificationCompat.Action.SEMANTIC_ACTION_NONE
                    }
                    actionBuilder.setSemanticAction(semanticAction)
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
            val editChoices = when (editChoicesBeforeSending) {
                AndroidRemoteInput.EDIT_CHOICES_BEFORE_SENDING_ENABLED ->
                    androidx.core.app.RemoteInput.EDIT_CHOICES_BEFORE_SENDING_ENABLED
                AndroidRemoteInput.EDIT_CHOICES_BEFORE_SENDING_DISABLED ->
                    androidx.core.app.RemoteInput.EDIT_CHOICES_BEFORE_SENDING_DISABLED
                else -> androidx.core.app.RemoteInput.EDIT_CHOICES_BEFORE_SENDING_AUTO
            }
            builder.setEditChoicesBeforeSending(editChoices)
        }
        allowedDataTypes?.forEach { builder.setAllowDataType(it, true) }
        return builder.build()
    }

    private fun isQuietMirrorChannelEnabled(): Boolean = canPostQuietCopies(this)

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
        private const val NATIVE_PERMANENT_ID = 9999

        fun canPostQuietCopies(context: Context): Boolean {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
            val channel = context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL_ID) ?: return false
            // Only a low/min-importance channel is safe to replace the source pop-up.
            return channel.importance == NotificationManager.IMPORTANCE_LOW ||
                channel.importance == NotificationManager.IMPORTANCE_MIN
        }
    }
}
