# Pixel Watch / iPhone notification project handoff

Updated 2026-10-13. Read this before working in `/home/tmn/code/watch`.

## Current status and agreed next step

**Local watch notification display is working. Bridge now works too, and the user confirmed the fix survives a restart. Do not restart the old notification-display investigation.**

We have not implemented Bluetooth or ANCS yet. The user wants a private alternative to Bridge for an **iPhone running iOS 18** and a **Pixel Watch 2 running Wear OS 5.1**. The watch was originally set up with an Android phone, now identified as a **Samsung Galaxy S10**, which the user still has. Bridge remains installed on watch and iPhone.

Latest design decision: **implement real Bluetooth/ANCS reception before fancy conversation UI**. User specifically wants to learn what notification data iOS supplies, particularly for group chats, so the presentation is based on actual data. Next milestone is a small diagnostic receiver on the watch, not a polished messaging UI.

Project constraints: direct Bluetooth notification path if pairing/service access permits; no iPhone companion app required for that path, no cloud, analytics, accounts, or INTERNET permission in our app. These are goals, not claims that pairing or all desired features have already been proven.

This turn only refreshes the handoff. No ANCS implementation has started. No PRs or sub-agents involved.

## Next development steps

1. Reconnect ADB using current mDNS discovery. At handoff refresh, `adb devices -l` and `adb mdns services` both returned empty lists; there is no currently confirmed endpoint. User may need to enable Wi-Fi/wireless debugging again.
2. Inspect the existing project and the reference repository below. Preserve this workspace; do not replace it wholesale with the reference app.
3. Investigate and implement the actual BLE pairing/bonding, GATT roles, ANCS service discovery, authorization, and subscription flow. Verify the required roles against reference code/specification rather than assuming that a simple BLE scan is sufficient. Add only the necessary Bluetooth permissions and runtime requests.
4. Build a minimal on-watch diagnostic view for connection state and incoming notification fields. Keep data local and bounded. Use user-generated test messages; avoid dumping unrelated private notifications into chat, repository files, or broad logs.
5. Inspect actual app identifier/display name, notification UID/event flags/category, title, subtitle, message body, date, and available action labels. Distinguish fields supplied by ANCS from anything inferred by our app.
6. Test direct messages, group chats, two groups in the same app, multiple incoming messages, notification updates/removals, hidden previews, locked iPhone, and disconnect/reconnect. Record which fields reliably identify sender/conversation and which do not.
7. Only then design grouping and MessagingStyle notifications. Keep notifications separate when grouping would be ambiguous. Handle reconnects and notification identity correctly; do not assume session notification UIDs are durable conversation IDs.

User-supplied reference: https://github.com/k97/WearBridge . Only its README was reviewed previously; source has NOT been cloned/audited. README claims direct ANCS and testing on Pixel Watch 2 / Wear OS 5, but its iPhone test device uses iOS 26, not this user's iOS 18. Verify its implementation/license before reusing code.

## Workspace and build

- App: **Watch Test**, package `dev.tmn.watchtest`.
- Main source: `app/src/main/java/dev/tmn/watchtest/MainActivity.kt`.
- Framework Activity with scrollable UI: Test notification, Clear test, notification settings, runtime POST_NOTIFICATIONS request.
- AndroidX `NotificationCompat.Builder` and `BigTextStyle`, core 1.17.0; `android.useAndroidX=true`.
- High-importance `test_messages` channel, vector small icon, title **Hello from Watch Test**, synthetic body, CATEGORY_MESSAGE, immutable content intent, auto-cancel, stable notification ID 1.
- Default PRIVATE visibility. No `setLocalOnly` call. Current source and last installed APK match this configuration.
- Manifest permissions currently only POST_NOTIFICATIONS and VIBRATE. No Bluetooth implementation or permissions yet.
- Gradle wrapper 9.6.1, AGP 9.1.0 with built-in Kotlin, compileSdk 36, targetSdk 35, minSdk 33.
- JDK: `/home/tmn/Apps/android-studio/jbr` (Java 25). SDK: `/home/tmn/Android/Sdk`.

```bash
JAVA_HOME=/home/tmn/Apps/android-studio/jbr ANDROID_HOME=/home/tmn/Android/Sdk ./gradlew :app:assembleDebug :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Last build/lint passed, zero errors and four warnings: AGP version, newer AndroidX dependency available, dataExtractionRules, Wear activity taskAffinity. No automated test suite yet; local display was tested on the physical watch. Meaningful parser/state-machine tests will be useful when ANCS is implemented.

Workspace was not initialized as a Git repository during this work. README contains build/install instructions and a device-fix note. Its sentence saying reboot persistence is untested is now superseded by the user's confirmation in this handoff.

## ADB and device operation

ADB is not on PATH:
```bash
/home/tmn/Android/Sdk/platform-tools/adb mdns services
/home/tmn/Android/Sdk/platform-tools/adb connect WATCH_IP:CONNECTION_PORT
/home/tmn/Android/Sdk/platform-tools/adb -s WATCH_IP:CONNECTION_PORT shell am start -n dev.tmn.watchtest/.MainActivity
```

- Last successful endpoint: `192.168.0.162:40601`. Earlier port was 37547. Rediscover; do not assume either is still valid.
- mDNS name: `adb-32281JEEJW01G0-VPWAI6._adb-tls-connect._tcp`.
- Wireless debugging was successfully paired. Re-pair only if needed; connection port differs from pairing port.
- ADB can list one watch by both IP and mDNS; always select it explicitly with `-s`.
- API 35 / Android 15, 384×384 screen, product/device `r11btwifi`.
- Host Wi-Fi: `malkin-nahum`, previously host IP `192.168.0.153`, host connected on 5 GHz. Same SSID provides 2.4 GHz for watch.
- USB cable was tried but neither lsusb nor ADB detected the watch; do not assert the cable lacks data. Bluetooth debugging is unsupported on Wear OS 3+.
- Use bounded command timeouts. Let an exec session finish before reading its screenshot; unfinished commands previously produced empty/missing images.
- Watch frequently sleeps/relocks off-wrist/on charger. User was told Settings → Security → Screen lock → None disables locking but removes watch Wallet payment cards. Whether user actually changed it is unconfirmed. Do not change security settings via ADB. Stay awake is separate from pattern lock.
- Automated gestures sometimes landed in Quick Settings, app drawer, or other apps. Verify each screen and coordinate with user to avoid competing touches. User opening the actual notification tray was useful.
- Prior UI dump path: `/data/local/tmp/watchtest-ui.xml`. Prefer filtering text for our package/title rather than printing private cards.

## Verified notification fix — preserve it

Root cause of the observed missing cards: **the current Google Wear Services notification collector lacked notification-listener access**. Only the legacy collector was approved/live:

`com.google.android.wearable.app/com.google.android.clockwork.stream.NotificationCollectorService`

The installed `com.google.wear.services` package had another collector which was not approved/live. Enabled it with this additive command, preserving the existing listener approvals:

```bash
adb -s ENDPOINT shell cmd notification allow_listener com.google.wear.services/com.google.wear.services.notification.stream.listener.NotificationCollectorService 0
```

The newly enabled collector reported connected / initial fetch complete. The already-posted PRIVATE Watch Test notification immediately appeared in the actual tray. Agent screenshot and user independently confirmed it. A freshly posted test also appeared marked **Now**, with full synthetic body available in the UI hierarchy.

User then reported: **Bridge works too; fix survives restart**, and later that things seem to work fine. This is user-confirmed persistence, not an additional agent-run reboot. Why the permission was missing is unknown; do not blame Bridge or a particular setup command without evidence.

Do not routinely reapply or undo this fix. If rollback is specifically needed, the inverse is `cmd notification disallow_listener` with the same component and user 0. Do not manually enable disabled legacy WCS components: several legacy services were disabled while their replacements existed in the newer package.

### Experiments that did NOT fix it

- Removing `.setLocalOnly(true)`.
- Rebooting (boot completion and low uptime confirmed it happened).
- Setting synthetic notification visibility PUBLIC. Reverted.
- Switching framework Notification.Builder to AndroidX NotificationCompat. Compat remains in the app, but also failed before collector access was fixed.

Notifications were already granted, channel HIGH/unblocked, `mIntercept=false`, `mHidden=false`, suppressed visual effects zero. DND, bedtime, theater modes were off; lockscreen notification visibility settings were enabled.

**Do not use `mVisibleSinceMs=0` / `airtimeCount=0` as proof that a Wear OS card is invisible. They stayed zero even AFTER screenshots proved display.** Actual tray screenshots and user observation are the evidence. The legacy collector's `Dumping from 0 listeners` was only a clue, not independently established as a diagnostic rule.

Clear-button and content-intent auto-cancel behavior were not conclusively verified by the agent. Do not claim those individual tests passed.

### Evidence and privacy

Diagnostics are under `/tmp/watchtest-debug/`, not guaranteed to survive cleanup:
- `user-tray.png`: actual tray said No notifications before fix.
- `collector-tray.png`: existing Watch Test card visible after fix.
- `fresh-tray3.png`: fresh card marked Now.
- `expanded.xml`: synthetic body with time 4:46 PM.
- `notifications-compat.txt`, `notifications-working.txt`, package/service/settings dumps and other screenshots.

Raw diagnostics may contain personal notification content. Do not copy them into the repo/handoff or repeatedly print unrelated content.

**Earlier Gmail attribution remains unconfirmed.** Bridge had Gmail-tagged records, but the user thought the visible email belonged to the Android phone account. Native Gmail is installed too. The later confirmation that Bridge works does not establish the source of that earlier Gmail card.

## Conversation notification discussion

User observed that Android-forwarded chat notifications can expand into a conversation even without the source app installed on the watch. Explained that Wear OS supports native expandable `NotificationCompat.MessagingStyle` notifications containing multiple messages, sender names and timestamps.

We can supply that presentation, but available iPhone data controls its usefulness. ANCS exposes notifications, not a full chat-history API or a general typed-reply transport. Do not promise full conversations, reliable group IDs, avatars, attachment retrieval, or arbitrary replies before verifying actual fields/capabilities. ANCS actions are predefined positive/negative actions with labels; do not assume their meanings.

The standard ANCS specification scopes identifiers/data to a connection session and recommends discarding session state on disconnect. Review it before designing history retention/reconciliation; a persistent chat-history feature is not already agreed or implemented.

User chose real ANCS investigation first rather than another synthetic UI prototype. Native notification header will generally show OUR app name, just as Bridge shows Bridge; replacing the system app label requires privileged permission, unavailable to ordinary apps. Original source app can be identified within content we control.

Useful primary sources:
- ANCS specification: https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/Specification/Specification.html
- Wear notifications / MessagingStyle: https://developer.android.com/training/wearables/notifications
- Android conversations: https://developer.android.com/develop/ui/views/notifications/conversations
- App-name substitution permission: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/core/res/AndroidManifest.xml (`SUBSTITUTE_NOTIFICATION_APP_NAME`, signature|privileged).

## Side topic: Gemini (advice only, no installation performed)

User asked about replacing watch Google Assistant with Gemini. Pixel Watch 2 / Wear OS 5.1 meets Gemini's Wear OS 4+ requirement. Official setup requires a connected Android phone with Gemini selected as digital assistant, eligible account/region/language, and current watch apps. Suggested using the original Galaxy S10: update Google app, install/open Gemini and select it, reconnect watch, update Assistant/Gemini on watch, finish setup.

S10 meets published Gemini phone requirements (Android 9+, 2 GB RAM). Do not claim setup was performed or Gemini works independently with the user's iPhone. Proposed testing watch Wi-Fi operation after disconnecting Android; not done.

Sources:
- https://support.google.com/gemini/answer/16401122?hl=en
- https://support.google.com/googlepixelwatch/answer/16393978?hl=en
- https://support.google.com/gemini/answer/14579026?hl=en

Keep the next session focused on the requested ANCS milestone unless the user steers back to Gemini.
