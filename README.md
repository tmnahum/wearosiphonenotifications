# iPhone Connection

A standalone Wear OS app for direct iPhone notification reception over ANCS. Minimum Android API 33; target API 35 (Wear OS 5.1). Package identity remains `dev.tmn.watchtest` so existing installations upgrade in place.

## Use

Open **iPhone Connection → Connect iPhone**, grant nearby-device and notification permissions, and select the existing paired iPhone. Subsequent connections reuse that selection. Enable **Share System Notifications** for the watch in iPhone Bluetooth settings. No pairing is created or removed.

Home shows connection status and recent conversation previews. Settings contains connection controls, notification settings, local-data clearing and content-free diagnostics. Test buttons and raw notification fields are no longer part of the user interface. The 14-day archive has no browsing UI yet.

Keep the competing Bridge package disabled during ANCS work; see `AGENTS.md`. Force-stop alone was insufficient. Do not change the verified Wear Services collector fix. Transport failures retry with backoff; protocol errors require reconnect. Automatic boot startup is not implemented.

The foreground-service notification is a static, silent, low-importance card posted once per service instance. Connection changes update the app, not that card. Wear OS still decides notification ordering.

## App controls

The app appears in Recents. For watch-face connection status, edit your face, choose a short/long text complication slot, and select **iPhone connection status**. It shows Linked/Linking/Offline/Check; tap for Connection. State changes request updates, with periodic refresh as a fallback (watch-face scheduling can delay them).

For a full swipe-over card, add **iPhone connection status** from the watch’s Tile picker. The Tile shows connection status and opens Connection settings; it does not expose notification contents.

Turn the crown to scroll pages and settings pickers, with smoothed movement, speed-sensitive acceleration and native fling after a fast turn. Touching the screen or changing pages stops crown momentum. Open source-app preferences using **App settings** on a relayed notification or the settings button inside a saved conversation.

**Apps & delivery** lists observed source apps with names fetched from the iPhone. Each app has four independent controls:
- **Notifications: Show with alerts / Show silently / Hidden** — show cards using watch alert settings, show without sound/vibration, or keep only in history.
- **Grouping: App default / Combine all notifications / Separate notifications / By conversation** — mail combines into an app inbox by default (Gmail, Apple Mail, or Email-category notifications); chats use app-specific context. By conversation explicitly opts mail out of app-wide grouping.
- **Display style: Automatic / Message previews / Inbox list / Expanded text** — changes appearance independently of grouping.

**Reset to app defaults** restores Show with alerts and default grouping/presentation for that app. It preserves history and tray dismissals. Old Muted settings migrate to Hidden.

**Display style** is separate from grouping. Automatic prefers message previews for Instagram, Messages, Signal, Discord and WhatsApp, and inbox lists for Gmail and Mail. Other apps use the newest item’s iPhone category: Social → messages, Email → inbox, everything else → expanded text. Explicit per-app Message previews, Inbox list, or Expanded text settings always override these defaults. Categories and preferences persist across restarts; existing history/preferences are migrated. Native previews are bounded for platform layout/payload limits, while saved history remains uncapped.

Automatic grouping uses Messages, Discord, Instagram and Signal-specific visible layouts first. Signal supports visible chat titles, sender-prefixed group bodies, group subtitles and the English sender-in-group title. The default for other apps matches exact title and subtitle, excluding generic/hidden summary labels. These are heuristics rather than true conversation identifiers. Select Separate when labels are ambiguous, or Combine all notifications for an inbox such as Gmail.

Connection screens distinguish Bluetooth setup from fully subscribed notification delivery and automatic retries. Explicit Disconnect stays off; reopening an enabled app or sticky service recreation reconnects the saved phone. Boot startup is still not implemented.

## Conversation previews and storage

- Messages direct chats use the sender display label; named groups use the subtitle. Discord channels use the observed isolated channel/server labels. These are best-effort display-context keys, not iOS thread IDs. Identical names, hidden previews, renamed chats and unverified layouts can be ambiguous. Signal and generic fallbacks are described above; ambiguous labels can still collide.
- Local conversation history has **no message-count or conversation-count cap**. Native MessagingStyle cards include the most recent 25 messages, Android's notification-container capacity. The app pages the conversation list and message views. Android also limits outstanding app notifications: when the tray is full, older visible relay cards make room for newer ones without deleting their saved history.
- `NotificationHistory` assigns durable local IDs. ANCS UIDs only map to them for the current Bluetooth session. On reconnect, pre-existing notifications with a valid posted date match saved app/title/subtitle/body/date fields within the same phone. Matching consumes occurrences one-for-one. Live duplicate messages remain distinct. Missing dates are not guessed; edits made while disconnected can produce a second record.
- `HistoryRepository` restores off the UI thread and saves a versioned AtomicFile on a serial worker. Updates coalesce over 750 ms; orderly session teardown flushes. An abrupt process kill can lose the latest unflushed updates. No cloud backup.
- Clearing a **watch notification** hides its posted items from the tray while keeping them in app history. Dismissals persist through edits, replay, restart and settings changes. New messages can appear without including dismissed items. A private delete intent plus reconciliation against active native cards handles dismissal; programmatic cancellations are tracked separately. Clearing multiple tracked cards together also suppresses older queued/replayed backlog. Clearing on the **iPhone** preserves app history too. Only **Clear all local data** deletes local history.
- Content fingerprints of locally cleared notifications prevent dated backlog replays from resurrecting them. Only fingerprints are retained, not cleared message bodies. Undated replays cannot be suppressed reliably. Clearing all local data removes conversations and the separate log; suppression fingerprints remain to prevent immediate replay resurrection.
- `NotificationLog` keeps a separate SQLite archive of received notification observations for 14 days, including replay/update indicators and posted dates. It is groundwork for a future history feature, not a chat-history source or archive browser. Reconnect observations can appear more than once. Expired entries are removed on startup, on writes, on app resume and hourly while the process runs; after the app is stopped, cleanup resumes on next start. Watch notification dismissal does not clear this log. **Settings → Clear all local data** clears both the conversations and log.

Both storage files live in app-private `noBackupFilesDir`; backup is disabled. No INTERNET permission, cloud, analytics, or notification-listener access. Personal notification text must never be copied into host logs, chat, repository files or fixtures. Service diagnostics expose only status/counters; internal layout inspection remains available for scoped debugging.

## Protocol and verification

`AncsProtocol.kt` validates events, request encoding and fragmented responses. `AncsSession.kt` serializes requests, handles write/response ordering, suppresses removed/superseded requests and prioritizes fresh events over backlog. The queue remains bounded at 512 pending UIDs; this is transport backpressure, not a history retention cap.

72 JVM tests cover protocol/session handling, grouping, restart/reconnect merging, repeated identical messages, source isolation, edits, watch-vs-phone dismissal, retention beyond the old limits, codec corruption and the 14-day cutoff. Build and lint are required before installation. Real watch ranking, swipe dismissal and long-run reconnects require device verification.

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

## Verified device fix

On this Pixel Watch 2, local notifications were posted successfully but absent from the actual tray. Rebooting, public visibility, and switching to NotificationCompat did not fix display. Notification access was enabled only for the legacy Wear OS collector; enabling the collector in the installed Google Wear Services system package made the existing notification appear:

```bash
adb -s WATCH_IP:CONNECTION_PORT shell cmd notification allow_listener com.google.wear.services/com.google.wear.services.notification.stream.listener.NotificationCollectorService 0
```

This is a device repair for the observed missing permission, not an app installation step to apply universally. It preserves other listener approvals. Both the existing card and a freshly posted card were verified in the real tray, and the user confirmed display. The app retains default private visibility and uses AndroidX NotificationCompat. The user confirmed that the display fix survives a reboot and also restores Bridge notifications.

Do not infer visibility from `dumpsys notification` counters: this device continued reporting zero airtime even while the card was visibly displayed. See `HANDOFF.md` for the investigation and diagnostic locations.
