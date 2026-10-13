# Watch Test

A small standalone Kotlin Wear OS app for testing native watch notifications before adding iPhone ANCS support. Minimum Android API 33; target API 35 (Wear OS 5.1).

## Build

Requires JDK 17 or newer compatible with Gradle 9.6.1, Android SDK platform 36, and Build Tools 36.0.0. Android Gradle Plugin 9.1 provides built-in Kotlin support.

On this workstation:

```bash
export JAVA_HOME=/home/tmn/Apps/android-studio/jbr
export ANDROID_HOME=/home/tmn/Android/Sdk
./gradlew :app:assembleDebug :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Install

Enable ADB and wireless debugging on the watch. Pair with the IP/port shown under **Pair new device**, then connect using the separate port on the main wireless debugging screen:

```bash
adb pair WATCH_IP:PAIRING_PORT
adb connect WATCH_IP:CONNECTION_PORT
adb -s WATCH_IP:CONNECTION_PORT install -r app/build/outputs/apk/debug/app-debug.apk
adb -s WATCH_IP:CONNECTION_PORT shell am start -n dev.tmn.watchtest/.MainActivity
```

Use the explicit device selector if ADB lists both an IP connection and an mDNS connection for the same watch. Addresses and ports can change.

## Test on the watch

1. Open **Watch Test**, tap **Test notification**, and allow notification permission.
2. Press the crown to return to the watch face, then swipe up. Find **Hello from Watch Test** and expand it to read the full message.
3. Tap the message to return to the app; it should dismiss the notification.
4. Send another test, then tap **Clear test**; the notification should disappear.
5. If permission was denied, enable it through **Notification settings** and retry.

Each test replaces the previous sample instead of accumulating messages. The notification remains until dismissed, tapped, or cleared. Vibration and heads-up presentation depend on the watch's notification settings, wear state, and modes; posting a notification does not force the screen awake.

## Verified device fix

On this Pixel Watch 2, local notifications were posted successfully but absent from the actual tray. Rebooting, public visibility, and switching to NotificationCompat did not fix display. Notification access was enabled only for the legacy Wear OS collector; enabling the collector in the installed Google Wear Services system package made the existing notification appear:

```bash
adb -s WATCH_IP:CONNECTION_PORT shell cmd notification allow_listener com.google.wear.services/com.google.wear.services.notification.stream.listener.NotificationCollectorService 0
```

This is a device repair for the observed missing permission, not an app installation step to apply universally. It preserves other listener approvals. Both the existing card and a freshly posted card were verified in the real tray, and the user confirmed display. The app retains default private visibility and uses AndroidX NotificationCompat. Persistence across another reboot remains untested.

Do not infer visibility from `dumpsys notification` counters: this device continued reporting zero airtime even while the card was visibly displayed. See `HANDOFF.md` for the investigation and diagnostic locations.

## Privacy and scope

Only notification and vibration permissions are declared. No internet access, Bluetooth connection, health access, analytics, server, or notification-listener access. All message text is generated locally. This milestone verifies local display; it does not receive iPhone notifications yet.
