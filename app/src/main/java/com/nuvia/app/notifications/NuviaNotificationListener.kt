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
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        if (sbn.packageName == packageName) return

        val notification = sbn.notification ?: return
        if (shouldPreserveOriginal(sbn)) return

        val appName = getAppName(sbn.packageName)
        val title = notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val bigText = notification.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val regularText = notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val lines = notification.extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString(" · ") { it.toString() }.orEmpty()
        val message = bigText.ifBlank { regularText.ifBlank { lines } }

        if (title.isBlank() && message.isBlank()) return

        val hideContent = NuviaStore.isHideContentEnabled(this)
        NuviaStore.saveNotification(
            this,
            NotificationRecord(
                key = sbn.key,
                packageName = sbn.packageName,
                appName = appName,
                title = if (hideContent) "New notification" else title.ifBlank { appName },
                message = if (hideContent) "Content hidden by Nuvia" else message,
                postedAt = sbn.postTime,
                hasActions = !notification.actions.isNullOrEmpty()
            )
        )

        val shouldReplace = NuviaStore.isReplacementEnabled(this) &&
            NuviaStore.isPackageManaged(this, sbn.packageName)

        if (shouldReplace && publishReplacement(sbn, appName, title, message)) {
            if (Settings.canDrawOverlays(this)) {
                IslandOverlayController.show(
                    context = this,
                    appName = appName,
                    title = if (hideContent) "New notification" else title.ifBlank { appName },
                    message = if (hideContent) "Content hidden by Nuvia" else message,
                    contentIntent = notification.contentIntent
                )
            }
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
                .setPriority(NotificationCompat.PRIORITY_HIGH)

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
                    "Nuvia replacements",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications safely mirrored by Nuvia"
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
        private const val CHANNEL_ID = "nuvia_replacements_v1"
    }
}

private object IslandOverlayController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var currentView: View? = null
    private var removeTask: Runnable? = null

    fun show(
        context: Context,
        appName: String,
        title: String,
        message: String,
        contentIntent: PendingIntent?
    ) {
        mainHandler.post {
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager = wm
                currentView?.let { old -> runCatching { wm.removeView(old) } }
                removeTask?.let(mainHandler::removeCallbacks)

                val density = context.resources.displayMetrics.density
                fun dp(value: Int) = (value * density).toInt()

                val root = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(13))
                    background = GradientDrawable().apply {
                        setColor(android.graphics.Color.rgb(14, 17, 27))
                        cornerRadius = dp(28).toFloat()
                        setStroke(dp(1), android.graphics.Color.rgb(52, 60, 80))
                    }
                    elevation = dp(12).toFloat()
                }

                val header = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val dot = View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(android.graphics.Color.rgb(45, 212, 191))
                    }
                }
                header.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)))
                val app = TextView(context).apply {
                    text = appName.uppercase()
                    textSize = 10f
                    letterSpacing = 0.12f
                    setTextColor(android.graphics.Color.rgb(167, 139, 250))
                    setPadding(dp(8), 0, 0, 0)
                }
                header.addView(app, LinearLayout.LayoutParams(0, dp(18), 1f))
                val label = TextView(context).apply {
                    text = "NOW"
                    textSize = 9f
                    letterSpacing = 0.12f
                    setTextColor(android.graphics.Color.rgb(148, 163, 184))
                }
                header.addView(label)
                root.addView(header)

                val titleView = TextView(context).apply {
                    text = title
                    textSize = 14f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(android.graphics.Color.WHITE)
                    setPadding(0, dp(4), 0, 0)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                root.addView(titleView)

                if (message.isNotBlank()) {
                    val messageView = TextView(context).apply {
                        text = message
                        textSize = 12f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(android.graphics.Color.rgb(190, 198, 214))
                        setPadding(0, dp(3), 0, 0)
                    }
                    root.addView(messageView)
                }

                root.setOnClickListener {
                    runCatching { contentIntent?.send() }
                    dismiss()
                }

                val params = WindowManager.LayoutParams(
                    dp(300),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dp(34)
                    if (Build.VERSION.SDK_INT >= 28) {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }

                wm.addView(root, params)
                currentView = root
                removeTask = Runnable { dismiss() }.also { mainHandler.postDelayed(it, 4_800L) }
            } catch (_: Exception) {
                dismiss()
            }
        }
    }

    private fun dismiss() {
        mainHandler.post {
            currentView?.let { view -> runCatching { windowManager?.removeView(view) } }
            currentView = null
            removeTask?.let(mainHandler::removeCallbacks)
            removeTask = null
        }
    }
}
