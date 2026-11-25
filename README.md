# Nuvia for Android

**Nuvia** is an early native Android notification companion built with Kotlin and Jetpack Compose. The first foundation includes a modern dark dashboard, a notification inbox, per-app selection, permission setup, an in-app Dynamic Island preview, a floating overlay preview, and a notification replacement service.

> Project version: 0.1.2.

Project status: source foundation created. This is not a tested APK yet. The current workspace does not have the Android SDK or Android Gradle tooling installed, so a device build and runtime verification still need to happen in Android Studio or CI.

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
3. Grant **Display over other apps** to show the floating island above other apps.
4. On Android 13+, allow Nuvia to post notifications. This is required to create replacement notifications.
5. Open **Apps** and select which apps Nuvia is allowed to manage.
6. Enable **Replace original notifications** only after testing the experience with non-critical apps first.

Floating island display is enabled by default for selected apps. Original-notification replacement is deliberately OFF by default; Nuvia does not automatically dismiss source notifications on first install.

## What the first foundation does

- Captures notifications delivered to Android's `NotificationListenerService`.
- Stores a local, bounded history of up to 100 notification summaries.
- Lets the user select launcher apps individually.
- When replacement is explicitly enabled for a selected app, posts a Nuvia notification using the source notification's content intent and up to three original actions.
- Copies Android `RemoteInput` metadata for supported inline-reply actions, then asks Android to dismiss the source notification after the replacement notification has been posted.
- Shows a short floating island-style overlay for selected apps when overlay permission is granted, independently of original-notification replacement.
- Preserves ongoing notifications, foreground-service notifications, group summaries, calls, alarms, media/transport, navigation and system-category notifications.
- Supports a local privacy option that hides message content in Nuvia's inbox and replacement display.

## Important Android limitations

Nuvia is not an OS-level replacement for Android's notification shade. A third-party app cannot own or redesign the system notification panel. It can listen for notifications, publish its own notification, and dismiss the source notification when Android allows it.

Copying a notification's `PendingIntent` and `RemoteInput` preserves the original action objects where the source app supports it, but it cannot guarantee every app's custom action will continue working after the original notification is dismissed. Messaging apps, banking apps, call apps and OEM-modified Android builds may behave differently. This must be tested app-by-app before enabling replacement broadly.

The floating island uses Android's special **Display over other apps** permission. Android/OEM power management may delay or prevent overlays under some circumstances. The overlay is a compact heads-up presentation, not a privileged system surface.

## Next build milestones

1. Compile and install on the target Xiaomi/Android device.
2. Verify listener access, app discovery and notification capture.
3. Test copied reply actions on WhatsApp, Telegram, Messages and Gmail before relying on them.
4. Test cancellation/replacement ordering and ensure no original notification disappears when Nuvia fails to post its replacement.
5. Refine the island placement, expansion gesture, animation and icon rendering against the target device's status bar/cutout.
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
