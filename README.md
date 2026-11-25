# Nuvia for Android

**Nuvia** is an early native Android notification companion built with Kotlin and Jetpack Compose. The first foundation includes a modern dark dashboard, a notification inbox, per-app selection, permission setup, an in-app Dynamic Island preview, a floating overlay preview, and a notification replacement service.

> Project version: 0.1.5.

Project status: GitHub Actions builds the debug APK on pushes to main. Device-level behaviour still needs verification on the target Android/HyperOS phone.

## Open the project

1. Extract this folder on a computer with Android Studio installed.
2. Open the `Nuvia` folder in Android Studio.
3. Use JDK 17 or newer and Android SDK Platform 36.
4. Install Gradle 9.6.0. This source archive includes the wrapper properties but omits the generated wrapper JAR and launch scripts; after installing Gradle, run `gradle wrapper --gradle-version 9.6.0` from the project root.
5. Sync the project, then run the `app` configuration on an Android 8.0+ device.

The project targets Android 16 (API 36) and supports Android 8.0 (API 26) and later.

## First-run setup

1. Open Nuvia and go to **Settings**.
2. Grant **Notification access**. This is required to read notifications from other apps.
3. Grant **Display over other apps** for the fallback capsule on devices without native HyperOS support.
4. On Xiaomi/POCO/Redmi HyperOS, also enable Nuvia's **Focus notifications** and **Show in status bar** permissions for native HyperIsland rendering. On Android 13+, allow Nuvia to post notifications.
5. Open **Apps** and select which apps Nuvia is allowed to manage.
6. Enable **Replace original notifications** only after testing the experience with non-critical apps first.

Floating island display is enabled by default for selected apps. Original-notification replacement is deliberately OFF by default; Nuvia does not automatically dismiss source notifications on first install.

## Duplicate notification handling

For selected apps, when the floating island can render, Nuvia now replaces the source alert with a silent copy in the notification shade. The island is the only interrupting pop-up; the quiet copy remains available in the shade with the original content intent and supported actions. The mirror channel uses low importance and no sound or vibration. Calls, alarms, navigation, ongoing service alerts, group summaries and system-category notifications remain untouched. If the island surface or notification-posting permission is unavailable, Nuvia leaves the original notification in place rather than risk losing the alert.

## What the first foundation does

- Captures notifications delivered to Android's `NotificationListenerService`.
- Stores a local, bounded history of up to 100 notification summaries.
- Lets the user select launcher apps individually.
- When replacement is explicitly enabled for a selected app, posts a Nuvia notification using the source notification's content intent and up to three original actions.
- Copies Android `RemoteInput` metadata for supported inline-reply actions, then asks Android to dismiss the source notification after the replacement notification has been posted.
- On supported Xiaomi/POCO/Redmi HyperOS devices, publishes native Focus Notifications with the source app's own icon and a permanent empty island when idle. On other Android devices, uses a centred persistent capsule overlay as a fallback.
- Preserves ongoing notifications, foreground-service notifications, group summaries, calls, alarms, media/transport, navigation and system-category notifications.
- Supports a local privacy option that hides message content in Nuvia's inbox and replacement display.

## In-app updates and release signing

Nuvia checks the repository's latest GitHub Release when the Settings screen is opened. When a newer version is published, the app can download its APK and open Android's package installer. Android deliberately requires the user to approve installation; Nuvia cannot silently replace its own APK as an ordinary third-party app.

Production release APKs are signed with one persistent upload key. Before publishing the first release:

1. Generate a private keystore on a trusted computer with the JDK command:
   `keytool -genkeypair -v -keystore nuvia-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias nuvia`
2. Back up `nuvia-release.jks` securely. Never commit it to this repository or share it publicly.
3. Base64-encode the keystore file and add these repository secrets under **Settings → Secrets and variables → Actions**: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`.
4. Publish a GitHub Release using a tag matching the Gradle version, for example `v0.1.4`. The published-release workflow builds a signed release APK and attaches it, with its SHA-256 checksum, to that release.

For PowerShell, create the Base64 value with:
`[Convert]::ToBase64String([IO.File]::ReadAllBytes("nuvia-release.jks"))`

**One-time migration warning:** earlier CI debug APKs were signed with temporary runner-generated debug keys. Android will reject a differently signed APK as an in-place update. The first permanently signed release therefore cannot update an installation made from those old CI debug APKs. Do not uninstall the current installation until any data you need has been backed up; uninstalling normally removes Nuvia's local app data.

## Important Android limitations

The GitHub Release updater is intended for the sideloaded APK distribution. Before publishing Nuvia on Google Play, remove the self-update installer permission and use Play's own update delivery instead: Google Play policy does not allow `REQUEST_INSTALL_PACKAGES` to be used for an app to update itself.

Nuvia is not an OS-level replacement for Android's notification shade. A third-party app cannot own or redesign the system notification panel. It can listen for notifications, publish its own notification, and dismiss the source notification when Android allows it.

Copying a notification's `PendingIntent` and `RemoteInput` preserves the original action objects where the source app supports it, but it cannot guarantee every app's custom action will continue working after the original notification is dismissed. Messaging apps, banking apps, call apps and OEM-modified Android builds may behave differently. This must be tested app-by-app before enabling replacement broadly.

On supported Xiaomi/POCO/Redmi HyperOS firmware, the island is rendered by the system through its Focus Notification protocol, provided the required system permissions are enabled. On other devices, Nuvia uses Android's **Display over other apps** permission for a fallback capsule. That fallback is not a privileged system surface and OEM power management can affect its availability.

## Next build milestones

1. Compile and install on the target Xiaomi/Android device.
2. Verify listener access, app discovery and notification capture.
3. Test copied reply actions on WhatsApp, Telegram, Messages and Gmail before relying on them.
4. Test cancellation/replacement ordering and ensure no original notification disappears when Nuvia fails to post its replacement.
5. Verify native HyperIsland rendering, app-specific icon selection, persistent idle state and dismissal behaviour on the target Xiaomi/HyperOS firmware.
6. Add durable local storage, notification grouping, per-app rules and diagnostics after the core replacement path is stable.

## Source layout

- `app/src/main/java/com/nuvia/app/MainActivity.kt` — Compose UI and navigation.
- `app/src/main/java/com/nuvia/app/data/` — local settings and notification history.
- `app/src/main/java/com/nuvia/app/notifications/` — notification listener, replacement pipeline and floating overlay.
- `app/src/main/AndroidManifest.xml` — permissions and listener service declaration.

## Build configuration

- Android Gradle Plugin: 9.4.0
- Gradle: 9.6.0
- AGP built-in Kotlin support (AGP 9.x); Compose compiler plugin: 2.2.10
- Compose BOM: 2026.09.00
- Java: 17

The versions are pinned rather than using dynamic `+` versions.
