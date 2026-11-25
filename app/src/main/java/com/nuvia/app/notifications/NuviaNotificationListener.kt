package com.nuvia.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput as AndroidRemoteInput
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
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
        val islandSurfaceReady = NuviaHyperIslandController.isNativeReady(this) ||
            Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        // In island mode, replace the source only when the island can actually render.
        // Its quiet copy stays in the shade; the island is the only interrupting surface.
        val shouldReplace = ((islandEnabled && islandSurfaceReady) ||
            NuviaStore.isReplacementEnabled(this)) && isManagedApp && !preserveOriginal

        // Native Xiaomi HyperIsland is used when available. Android overlay is only a fallback.
        if (islandEnabled) {
            NuviaHyperIslandController.showNotification(
                context = this,
                sbn = sbn,
                appName = appName,
                title = visibleTitle,
                message = visibleMessage
            )
        }

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
        if (sbn != null && sbn.packageName != packageName &&
            (reason == REASON_CANCEL || reason == REASON_CANCEL_ALL)
        ) {
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
            val original = sbn.notification
            val hideContent = NuviaStore.isHideContentEnabled(this)
            val visibleTitle = if (hideContent) "New notification" else title.ifBlank { appName }
            val visibleMessage = if (hideContent) "Content hidden by Nuvia" else message

            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_nuvia_stat)
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

            NotificationManagerCompat.from(this).notify("nuvia:${sbn.key}", sbn.id, builder.build())
            // Only dismiss the source notification after Nuvia's replacement has been posted.
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
