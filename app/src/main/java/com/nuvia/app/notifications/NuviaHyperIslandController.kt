package com.nuvia.app.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.nuvia.app.R
import com.nuvia.app.data.NuviaStore
import io.github.d4viddf.hyperisland_kit.HyperIslandNotification
import io.github.d4viddf.hyperisland_kit.HyperPicture
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoLeft
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoRight
import io.github.d4viddf.hyperisland_kit.models.PicInfo
import io.github.d4viddf.hyperisland_kit.models.TextInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Uses Xiaomi's native Focus Notification/HyperIsland protocol when the device supports it.
 * Other Android devices use a centred persistent overlay as a fallback.
 */
object NuviaHyperIslandController {
    private const val CHANNEL_ID = "nuvia_hyper_island_v1"
    private const val PERMANENT_ID = 9999
    private val activeIds = ConcurrentHashMap<String, Int>()
    private val activeKeysById = ConcurrentHashMap<Int, String>()

    private fun reserveNotificationId(key: String): Int {
        activeIds[key]?.let { return it }
        var candidate = key.hashCode() and 0x7fffffff
        if (candidate < 10_000 || candidate == PERMANENT_ID) candidate = 10_000
        while (true) {
            if (candidate == PERMANENT_ID) candidate++
            val owner = activeKeysById.putIfAbsent(candidate, key)
            if (owner == null || owner == key) return candidate
            candidate = if (candidate == Int.MAX_VALUE) 10_000 else candidate + 1
        }
    }

    fun onNativeNotificationRemoved(id: Int) {
        if (id == PERMANENT_ID) return
        val key = activeKeysById.remove(id) ?: return
        activeIds.remove(key, id)
    }

    fun isXiaomiDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ||
            manufacturer.contains("poco")
    }

    fun isNativeReady(context: Context): Boolean {
        val app = context.applicationContext
        if (!canPostNotifications(app)) return false
        return runCatching {
            HyperIslandNotification.isSupported(app) && hasFocusNotificationPermission(app)
        }.getOrDefault(false)
    }

    private fun isPostPermissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun canPostNotifications(context: Context): Boolean {
        val app = context.applicationContext
        return isPostPermissionGranted(app) &&
            NotificationManagerCompat.from(app).areNotificationsEnabled()
    }

    private fun hasFocusNotificationPermission(context: Context): Boolean {
        return try {
            val args = Bundle().apply { putString("package", context.packageName) }
            val result = context.contentResolver.call(
                Uri.parse("content://miui.statusbar.notification.public"),
                "canShowFocus",
                null,
                args
            )
            result?.getBoolean("canShowFocus", false) == true
        } catch (_: Exception) {
            false
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        NuviaStore.setIslandEnabled(app, enabled)
        refreshPermanent(app)
    }

    fun refreshPermanent(context: Context) {
        val app = context.applicationContext
        if (!NuviaStore.isIslandEnabled(app)) {
            cancelAll(app)
            IslandOverlayController.hide()
            return
        }

        if (isNativeReady(app)) {
            IslandOverlayController.hide()
            ensureChannel(app)
            postPermanentIsland(app)
        } else {
            NotificationManagerCompat.from(app).cancel(PERMANENT_ID)
            if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(app)) {
                IslandOverlayController.ensurePersistent(app)
            } else {
                IslandOverlayController.hide()
            }
        }
    }

    fun showNotification(
        context: Context,
        sbn: StatusBarNotification,
        appName: String,
        title: String,
        message: String
    ): Boolean {
        val app = context.applicationContext
        if (!NuviaStore.isIslandEnabled(app)) return false

        if (isNativeReady(app)) {
            IslandOverlayController.hide()
            return postNativeNotification(
                context = app,
                key = sbn.key,
                packageName = sbn.packageName,
                appName = appName,
                title = title,
                message = message,
                contentIntent = sbn.notification.contentIntent
            )
        } else if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(app)) {
            val icon = runCatching { app.packageManager.getApplicationIcon(sbn.packageName) }.getOrNull()
            return IslandOverlayController.showNotification(
                context = app,
                appName = appName,
                title = title,
                message = message,
                appIcon = icon,
                contentIntent = sbn.notification.contentIntent
            )
        }
        return false
    }

    fun showTest(context: Context, packageName: String?) {
        val app = context.applicationContext
        if (packageName.isNullOrBlank()) return
        val pm = app.packageManager
        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault("Selected app")
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                app,
                packageName.hashCode(),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val icon = runCatching { pm.getApplicationIcon(packageName) }.getOrNull()
        if (isNativeReady(app)) {
            IslandOverlayController.hide()
            postNativeNotification(
                context = app,
                key = "nuvia-test:$packageName",
                packageName = packageName,
                appName = label,
                title = "Island preview",
                message = "This preview uses the selected app's own icon.",
                contentIntent = pendingIntent
            )
        } else {
            IslandOverlayController.showNotification(
                context = app,
                appName = label,
                title = "Island preview",
                message = "This preview uses the selected app's own icon.",
                appIcon = icon,
                contentIntent = pendingIntent
            )
        }
    }

    fun dismiss(context: Context, key: String) {
        val id = activeIds.remove(key) ?: return
        activeKeysById.remove(id, key)
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    fun clearActiveNotifications(context: Context) {
        val app = context.applicationContext
        activeIds.values.toSet().forEach { id ->
            runCatching { NotificationManagerCompat.from(app).cancel(id) }
        }
        activeIds.clear()
        activeKeysById.clear()

        // Quiet shade copies are posted with this private tag prefix.
        runCatching {
            app.getSystemService(NotificationManager::class.java).activeNotifications
                .filter { it.tag?.startsWith("nuvia:") == true }
                .forEach { statusBarNotification ->
                    NotificationManagerCompat.from(app).cancel(
                        statusBarNotification.tag,
                        statusBarNotification.id
                    )
                }
        }
        IslandOverlayController.clearNotification()
    }

    private fun cancelAll(context: Context) {
        activeIds.values.toSet().forEach { id ->
            runCatching { NotificationManagerCompat.from(context).cancel(id) }
        }
        activeIds.clear()
        activeKeysById.clear()
        NotificationManagerCompat.from(context).cancel(PERMANENT_ID)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Nuvia HyperIsland",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Silent notifications used to render Nuvia's HyperOS island"
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                    }
                )
            }
        }
    }

    private fun canPostToIslandChannel(context: Context): Boolean {
        if (!canPostNotifications(context)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(CHANNEL_ID) ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun postPermanentIsland(context: Context) {
        ensureChannel(context)
        if (!canPostToIslandChannel(context)) return
        runCatching {
            val builder = HyperIslandNotification.Builder(context, "nuvia_permanent", "Nuvia")
            builder.setEnableFloat(false)
            builder.setIslandConfig(
                timeout = 86_400_000,
                dismissible = false,
                highlightColor = "#FFFFFF",
                expandedTimeMs = 0
            )
            builder.setShowNotification(false)
            builder.setReopen(true)
            builder.setIslandFirstFloat(false)

            val blank = "\u00A0".repeat(4)
            builder.setBigIslandInfo(
                left = ImageTextInfoLeft(1, null, TextInfo(blank, blank)),
                right = null
            )
            builder.setSmallIsland("")

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_nuvia_stat)
                .setContentTitle("Nuvia")
                .setContentText("Permanent island")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addExtras(builder.buildResourceBundle())
                .build()
            notification.extras.putString("miui.focus.param", builder.buildJsonParam())
            NotificationManagerCompat.from(context).notify(PERMANENT_ID, notification)
        }
    }

    private fun postNativeNotification(
        context: Context,
        key: String,
        packageName: String,
        appName: String,
        title: String,
        message: String,
        contentIntent: PendingIntent?
    ): Boolean {
        ensureChannel(context)
        if (!canPostToIslandChannel(context)) return false
        val id = reserveNotificationId(key)
        val posted = runCatching {
            val icon = context.packageManager.getApplicationIcon(packageName)
            val iconBitmap = drawableBitmap(icon)
            val picKey = "nuvia_app_icon"
            val hiddenKey = "nuvia_transparent"
            val builder = HyperIslandNotification.Builder(context, "nuvia_$packageName", appName)
            builder.setEnableFloat(true)
            builder.setIslandConfig(
                timeout = 60_000,
                dismissible = true,
                highlightColor = "#FFFFFF",
                expandedTimeMs = NuviaStore.islandDisplayDurationSeconds(context) * 1_000
            )
            builder.setShowNotification(false)
            builder.setReopen(true)
            builder.setIslandFirstFloat(true)
            builder.addPicture(HyperPicture(picKey, iconBitmap))
            builder.addPicture(HyperPicture(hiddenKey, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)))
            builder.setBaseInfo(type = 2, title = title.ifBlank { appName }, content = message)
            builder.setIconTextInfo(picKey = picKey, title = title.ifBlank { appName }, content = message)
            builder.setBigIslandInfo(
                left = ImageTextInfoLeft(1, PicInfo(1, picKey), TextInfo("", "")),
                right = ImageTextInfoRight(
                    1,
                    PicInfo(1, hiddenKey),
                    TextInfo(title.ifBlank { appName }, message)
                )
            )
            builder.setSmallIsland(picKey)

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_nuvia_stat)
                .setContentTitle(title.ifBlank { appName })
                .setContentText(message)
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .addExtras(builder.buildResourceBundle())
                .build()
            notification.extras.putString("miui.focus.param", builder.buildJsonParam())
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        }.getOrDefault(false)
        if (posted) {
            activeIds[key] = id
        } else {
            activeKeysById.remove(id, key)
            activeIds.remove(key, id)
        }
        return posted
    }

    private fun drawableBitmap(drawable: Drawable): Bitmap {
        return runCatching { drawable.toBitmap(width = 96, height = 96) }.getOrElse {
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
            bitmap
        }
    }
}

/**
 * Non-Xiaomi fallback. The capsule stays visible and centred; notification cards expand
 * from it briefly, then collapse back to the persistent capsule instead of disappearing.
 */
object IslandOverlayController {
    private const val COLLAPSED_WIDTH_DP = 116
    private const val COLLAPSED_HEIGHT_DP = 36
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var iconView: ImageView? = null
    private var details: LinearLayout? = null
    private var appView: TextView? = null
    private var timeView: TextView? = null
    private var dotView: View? = null
    private var titleView: TextView? = null
    private var messageView: TextView? = null
    private var closeView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var expanded = false
    private var currentIntent: PendingIntent? = null
    private var collapseTask: Runnable? = null
    private var appContext: Context? = null

    fun ensurePersistent(context: Context) {
        handler.post {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) return@post
            createIfNeeded(context.applicationContext)
            setExpanded(false)
        }
    }

    fun showNotification(
        context: Context,
        appName: String,
        title: String,
        message: String,
        appIcon: Drawable?,
        contentIntent: PendingIntent?
    ): Boolean {
        // NotificationListenerService invokes onNotificationPosted on the main thread.
        // Refuse replacement if we cannot synchronously attach the overlay; otherwise the
        // original app alert must remain visible as the safe fallback.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return false
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) return false

        createIfNeeded(context.applicationContext)
        if (!attached) return false

        currentIntent = contentIntent
        iconView?.apply {
            if (appIcon != null) {
                setImageDrawable(appIcon)
                visibility = View.VISIBLE
            } else {
                visibility = View.INVISIBLE
            }
        }
        appView?.text = appName.uppercase()
        titleView?.text = title
        messageView?.text = message
        if (!setExpanded(true)) return false

        collapseTask?.let(handler::removeCallbacks)
        collapseTask = Runnable { setExpanded(false) }.also {
            handler.postDelayed(it, NuviaStore.islandDisplayDurationSeconds(context).toLong() * 1_000L)
        }
        return true
    }

    private fun createIfNeeded(context: Context) {
        if (root != null && attached) return
        appContext = context
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(5), dp(6), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.rgb(13, 16, 25))
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), Color.rgb(34, 37, 44))
            }
            elevation = dp(10).toFloat()
            isClickable = true
            setOnClickListener {
                val intent = currentIntent
                if (expanded && intent != null) {
                    runCatching { intent.send() }
                    setExpanded(false)
                } else {
                    setExpanded(!expanded)
                }
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.INVISIBLE
        }
        header.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))

        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(45, 212, 191))
            }
            visibility = View.GONE
        }
        header.addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)).apply {
            leftMargin = dp(8)
            rightMargin = dp(6)
        })

        val detail = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val app = TextView(context).apply {
            textSize = 9f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(172, 178, 190))
            maxLines = 1
        }
        val title = TextView(context).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        detail.addView(app)
        detail.addView(title)
        header.addView(detail, LinearLayout.LayoutParams(0, dp(40), 1f))

        val time = TextView(context).apply {
            text = "NOW"
            textSize = 9f
            letterSpacing = 0.08f
            setTextColor(Color.rgb(151, 160, 177))
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            visibility = View.GONE
        }
        header.addView(time, LinearLayout.LayoutParams(dp(42), dp(30)))

        val close = TextView(context).apply {
            text = "×"
            textSize = 20f
            setTextColor(Color.rgb(160, 167, 180))
            gravity = Gravity.CENTER
            visibility = View.GONE
            setOnClickListener { setExpanded(false) }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(24), dp(28)))
        container.addView(header)

        val message = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.rgb(196, 202, 214))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
            setPadding(dp(38), dp(3), dp(8), dp(3))
        }
        container.addView(message)

        root = container
        iconView = icon
        details = detail
        appView = app
        titleView = title
        messageView = message
        timeView = time
        dotView = dot
        closeView = close

        val collapsedWidth = dp(COLLAPSED_WIDTH_DP)
        val collapsedHeight = dp(COLLAPSED_HEIGHT_DP)

        val statusBarResource = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (statusBarResource > 0) {
            context.resources.getDimensionPixelSize(statusBarResource)
        } else dp(24)

        val layout = WindowManager.LayoutParams(
            collapsedWidth,
            collapsedHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight + dp(3)
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        params = layout
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            windowManager?.addView(container, layout)
            attached = true
        } catch (_: Exception) {
            attached = false
        }
    }

    private fun setExpanded(value: Boolean): Boolean {
        val view = root ?: return false
        val wm = windowManager ?: return false
        val layout = params ?: return false
        val context = appContext ?: return false
        expanded = value
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        if (value) {
            details?.visibility = View.VISIBLE
            messageView?.visibility = View.VISIBLE
            closeView?.visibility = View.GONE
            dotView?.visibility = View.VISIBLE
            timeView?.visibility = View.VISIBLE
            iconView?.visibility = if (iconView?.drawable != null) View.VISIBLE else View.INVISIBLE
            view.gravity = Gravity.CENTER_VERTICAL
            view.setPadding(dp(12), dp(8), dp(10), dp(8))
            layout.width = (context.resources.displayMetrics.widthPixels * 0.80f).toInt().coerceAtMost(dp(380)).coerceAtLeast(dp(280))
            layout.height = dp(92)
        } else {
            details?.visibility = View.GONE
            messageView?.visibility = View.GONE
            closeView?.visibility = View.GONE
            dotView?.visibility = View.GONE
            timeView?.visibility = View.GONE
            iconView?.visibility = View.INVISIBLE
            view.gravity = Gravity.CENTER
            view.setPadding(dp(6), dp(5), dp(6), dp(5))
            layout.width = dp(COLLAPSED_WIDTH_DP)
            layout.height = dp(COLLAPSED_HEIGHT_DP)
        }
        try {
            wm.updateViewLayout(view, layout)
            view.animate().cancel()
            view.scaleX = 0.94f
            view.scaleY = 0.94f
            view.animate().scaleX(1f).scaleY(1f).setDuration(180L).start()
            return true
        } catch (_: Exception) {
            hide()
            return false
        }
    }

    fun clearNotification() {
        handler.post {
            collapseTask?.let(handler::removeCallbacks)
            collapseTask = null
            currentIntent = null
            titleView?.text = ""
            messageView?.text = ""
            if (attached) setExpanded(false)
        }
    }

    fun hide() {
        handler.post {
            collapseTask?.let(handler::removeCallbacks)
            collapseTask = null
            val view = root
            root = null
            attached = false
            expanded = false
            iconView = null
            details = null
            appView = null
            titleView = null
            messageView = null
            closeView = null
            timeView = null
            dotView = null
            params = null
            currentIntent = null
            if (view != null) runCatching { windowManager?.removeView(view) }
            windowManager = null
            appContext = null
        }
    }
}
