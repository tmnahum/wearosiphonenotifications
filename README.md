# Watch Test

A standalone Kotlin Wear OS app for direct iPhone notification reception over ANCS, with a session-scoped conversation preview API. Minimum Android API 33; target API 35 (Wear OS 5.1).

## Conversation previews

`bridge/Conversation.kt` accepts normalized messages with a source app and an explicit conversation ID, bounds each preview to eight messages and each session to 32 conversations, and renders them with AndroidX `MessagingStyle`. The caller must clear previews when its ANCS session ends. Since ANCS does not provide a thread ID, the adapter must leave `conversationId` unset until an app-specific identity is verified; ambiguous messages should stay separate.

Use **Group preview sample** in the app to post a local three-message conversation to the watch. This is synthetic UI data and does not represent a live iPhone notification.

`bridge/AncsProtocol.kt` parses events, encodes requests and reassembles fragmented responses. `AncsSession.kt` serializes bounded requests, waits for both write acknowledgement and response completion, and suppresses removed or superseded results. `AncsBridgeService` connects only to an existing bond, subscribes to Data Source before Notification Source, and posts received notifications as individual expandable watch cards. Notifications are updated/removed by their session UID. Live Discord channel previews use the observed sender + channel + server labels; named iMessage group previews use the observed title=sender, subtitle=group layout. Messages notifications with a sender title and no group subtitle use a separate direct-chat key and native one-to-one MessagingStyle. Both use native MessagingStyle. They are best-effort display-context groups, not true iOS thread IDs. Generic Messages app/summary titles are excluded. Direct-chat keys are inferred from visible sender labels, so identical contact names or missing group context can be ambiguous. Other formats remain individual cards until verified.

Choose **Choose paired iPhone**, then the iPhone entry. Enable **Share System Notifications** for the watch in iPhone Bluetooth settings. The app never creates a bond. Disconnects and service changes end the session. Transport disconnects retry the selected bond with increasing delays; Disconnect cancels retries. Protocol errors still require manual reconnect. Reboot startup is not implemented. Run only one direct ANCS receiver at a time. Bridge must be disabled before device testing (`adb -s ENDPOINT shell pm disable-user --user 0 com.orienlabs.bridge.wear`); force-stop alone did not keep it inactive. See `AGENTS.md` for the standing instruction and reversal command. Both apps running together produced mismatched ANCS responses.

Diagnostics retain six recent records in process memory while the activity is paused. Session data and relayed cards are cleared when the session ends. The service's ADB dump exposes counters and connection status only, never notification contents.

`AncsConversations.kt` maintains up to 32 cards by notification timestamp, with eight messages per conversation. Older backlog records cannot evict newer chats. Different notification UIDs preserve identical repeated messages; updates replace the same UID; removals update or cancel the associated conversation card. A reconnect clears the session. Discord channel/server label collisions cannot be distinguished over ANCS; actual Discord threads are separate only if the supplied context distinguishes them. Signal, Discord direct-message and other thread formats still need verified device examples; their notifications remain individual cards.

The event queue holds up to 512 pending UIDs to handle initial iPhone backlogs. Content-free layout diagnostics can be requested using `dumpsys activity service dev.tmn.watchtest/.bridge.AncsBridgeService layout CONVERSATION_LABEL`; output describes field relationships, not names or message bodies. Up to 512 title/subtitle layout records stay in session memory, with six detailed records shown in the app.

## Build

Requires JDK 17 or newer compatible with Gradle 9.6.1, Android SDK platform 36, and Build Tools 36.0.0. Android Gradle Plugin 9.1 provides built-in Kotlin support.

On this workstation:

```bash
export JAVA_HOME=/home/tmn/Apps/android-studio/jbr
export ANDROID_HOME=/home/tmn/Android/Sdk
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
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

This is a device repair for the observed missing permission, not an app installation step to apply universally. It preserves other listener approvals. Both the existing card and a freshly posted card were verified in the real tray, and the user confirmed display. The app retains default private visibility and uses AndroidX NotificationCompat. The user confirmed that the display fix survives a reboot and also restores Bridge notifications.

Do not infer visibility from `dumpsys notification` counters: this device continued reporting zero airtime even while the card was visibly displayed. See `HANDOFF.md` for the investigation and diagnostic locations.

## Privacy and scope

The app uses Bluetooth connect, notification, vibration and connected-device foreground service permissions. No INTERNET permission, cloud, analytics, or notification-listener access. Notification text remains in memory and in local Android notification cards; the app does not log or persist it. Synthetic samples remain separate from live reception.
