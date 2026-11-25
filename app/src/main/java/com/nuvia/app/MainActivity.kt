package com.nuvia.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nuvia.app.data.NotificationRecord
import com.nuvia.app.data.NuviaStore
import com.nuvia.app.notifications.NuviaHyperIslandController
import com.nuvia.app.updates.InstallerResult
import com.nuvia.app.updates.NuviaRelease
import com.nuvia.app.updates.NuviaUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val Midnight = Color(0xFF090B12)
private val SurfaceOne = Color(0xFF111624)
private val SurfaceTwo = Color(0xFF171E2E)
private val Line = Color(0xFF252D40)
private val Violet = Color(0xFFA78BFA)
private val Teal = Color(0xFF2DD4BF)
private val Muted = Color(0xFF9AA5B8)
private val TextMain = Color(0xFFF4F6FB)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NuviaTheme { NuviaRoot() } }
    }
}

@Composable
private fun NuviaTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Violet,
        onPrimary = Midnight,
        secondary = Teal,
        background = Midnight,
        surface = SurfaceOne,
        onSurface = TextMain,
        onBackground = TextMain,
        surfaceVariant = SurfaceTwo,
        onSurfaceVariant = Muted,
        outline = Line
    )
    MaterialTheme(colorScheme = colors, content = content)
}

private enum class Destination(val label: String) {
    HOME("Home"), INBOX("Inbox"), APPS("Apps"), SETTINGS("Settings")
}

@Composable
private fun NuviaRoot() {
    val context = LocalContext.current
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    var notifications by remember { mutableStateOf(NuviaStore.getNotifications(context)) }
    var listenerEnabled by remember { mutableStateOf(isNotificationListenerEnabled(context)) }
    var overlayEnabled by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notificationsAllowed by remember { mutableStateOf(NuviaHyperIslandController.canPostNotifications(context)) }
    var availableUpdate by remember { mutableStateOf<NuviaRelease?>(null) }

    LaunchedEffect(Unit) {
        delay(900)
        availableUpdate = withContext(Dispatchers.IO) {
            runCatching { NuviaUpdateManager.checkForUpdate(context) }.getOrNull()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            notifications = NuviaStore.getNotifications(context)
            listenerEnabled = isNotificationListenerEnabled(context)
            overlayEnabled = Settings.canDrawOverlays(context)
            notificationsAllowed = NuviaHyperIslandController.canPostNotifications(context)
            delay(1200)
        }
    }

    Scaffold(
        containerColor = Midnight,
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0E1320),
                tonalElevation = 0.dp,
                modifier = Modifier.border(1.dp, Line.copy(alpha = 0.55f))
            ) {
                Destination.entries.forEach { item ->
                    val selected = destination == item
                    NavigationBarItem(
                        selected = selected,
                        onClick = { destination = item },
                        icon = {
                            val image = when (item) {
                                Destination.HOME -> Icons.Rounded.Waves
                                Destination.INBOX -> Icons.Rounded.Inbox
                                Destination.APPS -> Icons.Rounded.Apps
                                Destination.SETTINGS -> Icons.Rounded.Settings
                            }
                            Icon(image, contentDescription = item.label)
                        },
                        label = { Text(item.label, fontSize = 10.sp) },
                        alwaysShowLabel = false
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(targetState = destination, label = "nuvia-navigation") { page ->
                when (page) {
                    Destination.HOME -> HomeScreen(
                        notifications = notifications,
                        availableUpdate = availableUpdate,
                        listenerEnabled = listenerEnabled,
                        overlayEnabled = overlayEnabled,
                        onOpenSettings = { destination = Destination.SETTINGS },
                        onOpenInbox = { destination = Destination.INBOX }
                    )
                    Destination.INBOX -> InboxScreen(notifications)
                    Destination.APPS -> AppsScreen()
                    Destination.SETTINGS -> SettingsScreen(
                        listenerEnabled = listenerEnabled,
                        overlayEnabled = overlayEnabled,
                        notificationsAllowed = notificationsAllowed,
                        onRefreshPermissions = {
                            listenerEnabled = isNotificationListenerEnabled(context)
                            overlayEnabled = Settings.canDrawOverlays(context)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PageColumn(
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Midnight)
            .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier)
            .padding(horizontal = 20.dp)
            .padding(top = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content
    )
}

@Composable
private fun BrandHeader(eyebrow: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(Violet, Color(0xFF6852CD)))),
            contentAlignment = Alignment.Center
        ) {
            Text("N", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(11.dp))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("nuvia", color = TextMain, fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp)
            if (eyebrow != null) Text(eyebrow, color = Muted, fontSize = 10.sp, letterSpacing = 1.3.sp)
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .clip(CircleShape)
                .background(SurfaceOne)
                .border(1.dp, Line, CircleShape)
                .padding(horizontal = 11.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Teal))
                Text("BETA", color = Muted, fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    notifications: List<NotificationRecord>,
    availableUpdate: NuviaRelease?,
    listenerEnabled: Boolean,
    overlayEnabled: Boolean,
    onOpenSettings: () -> Unit,
    onOpenInbox: () -> Unit
) {
    PageColumn(scrollable = true) {
        BrandHeader("YOUR NOTIFICATIONS, REIMAGINED")
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Everything,\nin one flow.", fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.1).sp)
            Text("A calmer way to experience every alert on your phone.", color = Muted, fontSize = 14.sp)
        }

        IslandPreviewCard()

        availableUpdate?.let { release ->
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceOne),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Teal.copy(alpha = 0.55f))
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    Icon(Icons.Rounded.Bolt, null, tint = Teal, modifier = Modifier.size(21.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Nuvia ${release.version} is available", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Open Settings to download and install the update.", color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                    TextButton(onClick = onOpenSettings) { Text("View", color = Teal) }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard(
                modifier = Modifier.weight(1f),
                value = notifications.size.toString(),
                label = "Captured",
                icon = Icons.Rounded.NotificationsNone,
                tint = Violet
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                value = if (listenerEnabled) "Ready" else "Off",
                label = "Access",
                icon = Icons.Rounded.Security,
                tint = if (listenerEnabled) Teal else Color(0xFFF4B860)
            )
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceOne),
            shape = RoundedCornerShape(22.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Line)
        ) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val nativeIslandReady = NuviaHyperIslandController.isXiaomiDevice() &&
                    NuviaHyperIslandController.isNativeReady(LocalContext.current)
                val islandReady = overlayEnabled || nativeIslandReady
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Make Nuvia yours", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Grant the permissions required by your device.", color = Muted, fontSize = 12.sp)
                    }
                    Icon(Icons.Rounded.Bolt, null, tint = Violet)
                }
                SetupStep("01", "Notification access", if (listenerEnabled) "Connected" else "Needs setup", listenerEnabled)
                SetupStep("02", "Floating island", if (islandReady) "Ready" else "Needs setup", islandReady)
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Midnight)
                ) {
                    Text("Continue setup", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(7.dp))
                    Icon(Icons.Rounded.ChevronRight, null)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Recent activity", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Your latest captured notifications", color = Muted, fontSize = 11.sp)
            }
            TextButton(onClick = onOpenInbox) { Text("View all", color = Violet) }
        }
        if (notifications.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.NotificationsNone,
                title = "Your inbox is waiting",
                body = "Once notification access is connected, supported app alerts will appear here."
            )
        } else {
            notifications.take(3).forEach { NotificationRow(it) }
        }
    }
}

@Composable
private fun IslandPreviewCard() {
    Card(
        shape = RoundedCornerShape(27.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceOne),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LIVE ISLAND", color = Muted, fontSize = 10.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("PREVIEW", color = Teal, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(174.dp)
                    .clip(RoundedCornerShape(21.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF1B2540), Color(0xFF101725), Color(0xFF211A3B))))
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 13.dp)
                        .fillMaxWidth(0.94f)
                        .clip(RoundedCornerShape(30.dp))
                        .background(Color(0xFF0D1019))
                        .border(1.dp, Color(0xFF34384A), RoundedCornerShape(30.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Teal))
                        Spacer(Modifier.width(8.dp))
                        Text("SNAPCHAT", color = Color(0xFFB8C0D2), fontSize = 9.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Text("NOW", color = Color(0xFF9099AB), fontSize = 9.sp, letterSpacing = 0.8.sp)
                    }
                    Text("Eragbai Juliet", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("replied to your Chat", color = Color(0xFFB8C0D2), fontSize = 12.sp, maxLines = 1)
                }
                Text(
                    "Notification preview",
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 15.dp),
                    color = Color(0xFFB8C0D2),
                    fontSize = 11.sp
                )
            }
            Text("The source app's notification expands from the persistent island.", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier,
    value: String,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceOne),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line)
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
            Text(value, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(label, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SetupStep(number: String, title: String, status: String, complete: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(if (complete) Teal.copy(alpha = 0.13f) else SurfaceTwo),
            contentAlignment = Alignment.Center
        ) {
            if (complete) Icon(Icons.Rounded.CheckCircle, null, tint = Teal, modifier = Modifier.size(16.dp))
            else Text(number, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(status, color = if (complete) Teal else Muted, fontSize = 11.sp)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Muted, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun InboxScreen(notifications: List<NotificationRecord>) {
    val context = LocalContext.current
    PageColumn {
        BrandHeader("YOUR PERSONAL NOTIFICATION SPACE")
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Inbox", fontSize = 31.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
                Text("Everything important, gathered in one place.", color = Muted, fontSize = 12.sp)
            }
            Text("${notifications.size} saved", color = Violet, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        if (notifications.isEmpty()) {
            EmptyState(Icons.Rounded.Inbox, "Nothing here yet", "New notifications will appear here after you grant Nuvia notification access.")
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LATEST", color = Muted, fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { NuviaStore.clearNotifications(context) }) {
                    Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Clear", color = Muted, fontSize = 11.sp)
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(notifications, key = { it.key }) { record -> NotificationRow(record, expanded = true) }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

private data class LaunchableApp(val packageName: String, val label: String)

@Composable
private fun AppsScreen() {
    val context = LocalContext.current
    val apps = remember { getLaunchableApps(context) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(NuviaStore.managedPackages(context)) }
    val filtered = apps.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }

    PageColumn {
        BrandHeader("CHOOSE WHAT NUVIA MANAGES")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Your apps", fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
            Text("Choose which apps Nuvia manages. With the island on, their alerts appear in the island and are kept quietly in your shade.", color = Muted, fontSize = 12.sp)
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceOne),
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Line)
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Shield, null, tint = Teal, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Safe by default", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Calls, alarms and ongoing system alerts stay untouched.", fontSize = 10.sp, color = Muted)
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search installed apps", color = Muted) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Muted) },
            singleLine = true,
            shape = RoundedCornerShape(15.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${selected.size} selected", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                selected = if (selected.size == apps.size) emptySet() else apps.map { it.packageName }.toSet()
                NuviaStore.setAllPackagesManaged(context, selected)
            }) {
                Text(if (selected.size == apps.size) "Clear all" else "Select all", color = Violet, fontSize = 12.sp)
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            items(filtered, key = { it.packageName }) { app ->
                AppSelectionRow(
                    app = app,
                    checked = selected.contains(app.packageName),
                    onCheckedChange = { checked ->
                        val next = selected.toMutableSet().apply {
                            if (checked) add(app.packageName) else remove(app.packageName)
                        }
                        selected = next
                        NuviaStore.setPackageManaged(context, app.packageName, checked)
                    }
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun AppSelectionRow(app: LaunchableApp, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(SurfaceOne)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(39.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFF2C3351), Color(0xFF1B2436)))),
            contentAlignment = Alignment.Center
        ) {
            Text(app.label.take(1).uppercase(), color = Violet, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(app.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, color = Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsScreen(
    listenerEnabled: Boolean,
    overlayEnabled: Boolean,
    notificationsAllowed: Boolean,
    onRefreshPermissions: () -> Unit
) {
    val context = LocalContext.current
    var updateRelease by remember { mutableStateOf<NuviaRelease?>(null) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }
    var updateStatus by remember { mutableStateOf("Checking the latest public release…") }
    var updateCheckBusy by remember { mutableStateOf(false) }
    var updateDownloadBusy by remember { mutableStateOf(false) }
    val updateScope = rememberCoroutineScope()

    fun checkForUpdates() {
        if (updateCheckBusy) return
        updateScope.launch {
            updateCheckBusy = true
            val result = withContext(Dispatchers.IO) {
                runCatching { NuviaUpdateManager.checkForUpdate(context) }
            }
            result.onSuccess { release ->
                updateRelease = release
                updateStatus = if (release == null) {
                    "Nuvia is up to date."
                } else {
                    "A newer version is available."
                }
                downloadedApk = null
            }.onFailure { error ->
                updateRelease = null
                updateStatus = error.message ?: "Couldn't check for updates. Try again."
            }
            updateCheckBusy = false
        }
    }

    LaunchedEffect(Unit) { checkForUpdates() }
    var replacement by remember { mutableStateOf(NuviaStore.isReplacementEnabled(context)) }
    var islandEnabled by remember { mutableStateOf(NuviaStore.isIslandEnabled(context)) }
    var islandDisplayDuration by remember { mutableIntStateOf(NuviaStore.islandDisplayDurationSeconds(context)) }
    var hideContent by remember { mutableStateOf(NuviaStore.isHideContentEnabled(context)) }
    var showPermissionHint by remember { mutableStateOf(false) }
    var permissionRefresh by remember { mutableIntStateOf(0) }
    val hasPostPermission = remember(permissionRefresh) {
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val canPostNotifications = hasPostPermission && notificationsAllowed
    val selectedApps = NuviaStore.managedPackages(context).size
    val postPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        showPermissionHint = !granted
        permissionRefresh += 1
        onRefreshPermissions()
    }

    PageColumn(scrollable = true) {
        BrandHeader("CONTROL THE EXPERIENCE")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Settings", fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
            Text("Fine-tune how notifications appear and behave.", color = Muted, fontSize = 12.sp)
        }

        SettingsGroup("PERMISSIONS") {
            PermissionRow(
                icon = Icons.Rounded.NotificationsActive,
                title = "Notification access",
                description = "Read incoming alerts from supported apps.",
                status = if (listenerEnabled) "Connected" else "Required",
                complete = listenerEnabled,
                onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            )
            Divider(color = Line.copy(alpha = 0.7f))
            PermissionRow(
                icon = Icons.Rounded.Layers,
                title = "Display over other apps",
                description = "Fallback only when native HyperOS island support is unavailable.",
                status = if (overlayEnabled) "Allowed" else "Optional",
                complete = overlayEnabled,
                onClick = {
                    if (Build.VERSION.SDK_INT >= 23) {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }
            )
            if (NuviaHyperIslandController.isXiaomiDevice()) {
                Divider(color = Line.copy(alpha = 0.7f))
                val nativeReady = NuviaHyperIslandController.isNativeReady(context)
                PermissionRow(
                    icon = Icons.Rounded.Bolt,
                    title = "HyperOS Focus notifications",
                    description = "Required for Xiaomi's native HyperIsland presentation. Enable Focus notifications and Show in status bar for Nuvia.",
                    status = if (nativeReady) "Ready" else "Needs setup",
                    complete = nativeReady,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                )
            }
            if (overlayEnabled || (NuviaHyperIslandController.isXiaomiDevice() && NuviaHyperIslandController.isNativeReady(context))) {
                OutlinedButton(
                    onClick = {
                        NuviaHyperIslandController.showTest(
                            context,
                            NuviaStore.managedPackages(context).firstOrNull()
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    enabled = selectedApps > 0
                ) {
                    Icon(Icons.Rounded.Waves, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Test with selected app icon")
                }
            }
            Divider(color = Line.copy(alpha = 0.7f))
            PermissionRow(
                icon = Icons.Rounded.Bolt,
                title = "Post notifications",
                description = "Needed for Nuvia's island alerts and quiet notification copies.",
                status = when {
                    !hasPostPermission -> "Required"
                    !notificationsAllowed -> "Disabled"
                    else -> "Allowed"
                },
                complete = canPostNotifications,
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && !hasPostPermission) {
                        postPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
            )
        }

        SettingsGroup("NOTIFICATION BEHAVIOUR") {
            SettingSwitch(
                icon = Icons.Rounded.Waves,
                title = "Floating island",
                description = "Show one pop-up in island mode. Selected apps are automatically mirrored quietly to the notification shade, not shown twice.",
                checked = islandEnabled,
                enabled = true,
                onCheckedChange = {
                    islandEnabled = it
                    NuviaHyperIslandController.setEnabled(context, it)
                }
            )
            if (islandEnabled && (!overlayEnabled || selectedApps == 0) &&
                !(NuviaHyperIslandController.isXiaomiDevice() && NuviaHyperIslandController.isNativeReady(context))
            ) {
                Text(
                    text = when {
                        selectedApps == 0 -> "Choose at least one app in the Apps tab."
                        else -> "Allow Display over other apps, or enable HyperOS Focus notifications for native rendering."
                    },
                    color = Color(0xFFF4B860),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 44.dp, bottom = 4.dp)
                )
            }
            Divider(color = Line.copy(alpha = 0.7f))
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Island display duration", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("Time a new notification stays expanded before the island collapses.", color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                    Text(
                        "$islandDisplayDuration sec",
                        color = Teal,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Slider(
                    value = islandDisplayDuration.toFloat(),
                    onValueChange = { value ->
                        val seconds = value.roundToInt().coerceIn(1, 15)
                        islandDisplayDuration = seconds
                        NuviaStore.setIslandDisplayDurationSeconds(context, seconds)
                    },
                    valueRange = 1f..15f,
                    steps = 13,
                    enabled = islandEnabled
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("1 sec", color = Muted, fontSize = 9.sp)
                    Text("15 sec", color = Muted, fontSize = 9.sp)
                }
            }
            Divider(color = Line.copy(alpha = 0.7f))
            SettingSwitch(
                icon = Icons.Rounded.Waves,
                title = "Quiet original notifications",
                description = if (islandEnabled) {
                    "Automatic in island mode: selected apps are replaced by silent shade copies, leaving the island as the only pop-up."
                } else {
                    "When island mode is off, replace selected apps' original alerts with quiet copies in the notification shade."
                },
                checked = islandEnabled || replacement,
                enabled = listenerEnabled && canPostNotifications && selectedApps > 0 && !islandEnabled,
                onCheckedChange = { checked ->
                    replacement = checked
                    NuviaStore.setReplacementEnabled(context, checked)
                }
            )
            if (islandEnabled && !canPostNotifications) {
                Text(
                    text = "Allow Nuvia to post notifications so it can keep a quiet copy in your shade. Without this permission, the original app pop-up cannot be suppressed.",
                    color = Color(0xFFF4B860),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(start = 44.dp, bottom = 4.dp)
                )
            }
            if (!listenerEnabled || !canPostNotifications || selectedApps == 0) {
                Text(
                    text = when {
                        !listenerEnabled -> "Grant notification access to enable replacement."
                        !hasPostPermission -> "Allow Nuvia to post notifications first."
                        !notificationsAllowed -> "Enable Nuvia notifications in Android app settings."
                        else -> "Choose at least one app in the Apps tab."
                    },
                    color = Color(0xFFF4B860),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 44.dp, bottom = 4.dp)
                )
            }
            Divider(color = Line.copy(alpha = 0.7f))
            SettingSwitch(
                icon = Icons.Rounded.Lock,
                title = "Hide notification content",
                description = "Store and display a generic preview instead of message text.",
                checked = hideContent,
                enabled = true,
                onCheckedChange = { enabled ->
                    hideContent = enabled
                    NuviaStore.setHideContentEnabled(context, enabled)
                    if (enabled) {
                        NuviaStore.redactExistingNotificationContent(context)
                        NuviaHyperIslandController.clearActiveNotifications(context)
                    }
                }
            )
        }

        SettingsGroup("APP UPDATES") {
            Text(updateStatus, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            updateRelease?.let { release ->
                Text("Version ${release.version} is ready", color = Teal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (release.notes.isNotBlank()) {
                    Text(release.notes.take(360), color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                }
                Button(
                    onClick = {
                        updateRelease?.let { selectedRelease ->
                            updateScope.launch {
                                updateDownloadBusy = true
                                updateStatus = "Downloading version ${selectedRelease.version}…"
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { NuviaUpdateManager.downloadApk(context, selectedRelease) }
                                }
                                result.onSuccess { apk ->
                                    downloadedApk = apk
                                    runCatching { NuviaUpdateManager.openInstaller(context, apk) }
                                        .onSuccess { installer ->
                                            updateStatus = when (installer) {
                                                InstallerResult.PERMISSION_REQUIRED ->
                                                    "APK downloaded. Allow Nuvia to install unknown apps, then tap Install update."
                                                InstallerResult.OPENED ->
                                                    "Android's installer is open. Confirm the update there."
                                            }
                                        }
                                        .onFailure { error ->
                                            updateStatus = error.message ?: "The installer could not be opened."
                                        }
                                }.onFailure { error ->
                                    updateStatus = error.message ?: "APK download failed. Try again."
                                }
                                updateDownloadBusy = false
                            }
                        }
                    },
                    enabled = !updateDownloadBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Midnight)
                ) {
                    Text(if (updateDownloadBusy) "Downloading…" else "Download and install")
                }
            }
            downloadedApk?.let { apk ->
                OutlinedButton(
                    onClick = {
                        runCatching { NuviaUpdateManager.openInstaller(context, apk) }
                            .onSuccess { result ->
                                updateStatus = if (result == InstallerResult.PERMISSION_REQUIRED) {
                                    "Allow Nuvia to install unknown apps in Android settings, then tap Install update again."
                                } else {
                                    "Android's installer is open. Confirm the update there."
                                }
                            }
                            .onFailure { error ->
                                updateStatus = error.message ?: "The installer could not be opened."
                            }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Install downloaded update")
                }
            }
            OutlinedButton(
                onClick = { checkForUpdates() },
                enabled = !updateCheckBusy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (updateCheckBusy) "Checking…" else "Check for updates")
            }
        }

        SettingsGroup("ABOUT") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Icon(Icons.Rounded.Smartphone, null, tint = Violet)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Nuvia for Android", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Early build · Version 0.1.9", color = Muted, fontSize = 10.sp)
                }
                Text("BETA", color = Teal, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (showPermissionHint) Text("Notification permission was not granted. Replacement remains off.", color = Color(0xFFF4B860), fontSize = 11.sp)
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceOne)
            .border(1.dp, Line, RoundedCornerShape(20.dp))
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Text(title, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        content()
    }
}

@Composable
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    status: String,
    complete: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceTwo), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Violet, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(status, color = if (complete) Teal else Color(0xFFF4B860), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            Icon(Icons.Rounded.ChevronRight, null, tint = Muted, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun SettingSwitch(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(SurfaceTwo), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (enabled) Violet else Muted, modifier = Modifier.size(17.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) TextMain else Muted)
            Text(description, color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun NotificationRow(record: NotificationRecord, expanded: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(17.dp))
            .background(SurfaceOne)
            .border(1.dp, Line.copy(alpha = 0.8f), RoundedCornerShape(17.dp))
            .padding(13.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFF353154), Color(0xFF1E2B40)))),
            contentAlignment = Alignment.Center
        ) {
            Text(record.appName.take(1).uppercase(), color = Violet, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(record.appName, fontSize = 11.sp, color = Violet, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(relativeTime(record.postedAt), color = Muted, fontSize = 9.sp)
            }
            Text(record.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = if (expanded) 2 else 1, overflow = TextOverflow.Ellipsis)
            if (record.message.isNotBlank()) {
                Text(record.message, color = Muted, fontSize = 11.sp, maxLines = if (expanded) 4 else 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
            }
            if (record.hasActions) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Rounded.Forum, null, tint = Teal, modifier = Modifier.size(12.dp))
                    Text("Source app included action buttons", color = Teal, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(SurfaceOne)
            .border(1.dp, Line, RoundedCornerShape(22.dp))
            .padding(25.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(SurfaceTwo), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Violet, modifier = Modifier.size(24.dp))
        }
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(body, color = Muted, fontSize = 11.sp, lineHeight = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

private fun getLaunchableApps(context: Context): List<LaunchableApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    val resolved: List<ResolveInfo> = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
    return resolved
        .mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            if (packageName == context.packageName) return@mapNotNull null
            val label = info.loadLabel(context.packageManager)?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            LaunchableApp(packageName, label)
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase(Locale.getDefault()) }
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
    return enabled.split(":").any { it.contains(context.packageName) }
}

private fun relativeTime(timestamp: Long): String {
    if (timestamp <= 0L) return "now"
    val delta = System.currentTimeMillis() - timestamp
    return when {
        delta < 60_000L -> "now"
        delta < 3_600_000L -> "${delta / 60_000L}m"
        delta < 86_400_000L -> "${delta / 3_600_000L}h"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}
