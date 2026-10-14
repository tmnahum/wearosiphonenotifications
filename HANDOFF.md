# Pixel Watch / iPhone notification bridge handoff

Updated 2026-10-14. Working directory `/home/tmn/code/watch`, branch `feature/ancs-chat-preview`, baseline `e1b79ea`. Implementation remains uncommitted. No remote or PR. No sub-agents used.

## Combined notification delivery setting — 2026-10-14

User confirmed radio options work after the touch fix, then requested combining Shown/Hidden and Alerts. Per-app Notifications now offers Show with alerts, Show silently, Hidden in one picker. Selection updates existing hidden/delivery fields together; choosing Hidden preserves the stored alert preference. No storage migration required. Grouping and Display style stay independent. Reset copy and README updated. assembleDebug/lintDebug/diff check pass. Installed successfully at 192.168.0.162:43329 with explicit Success; Bridge verified disabled. Reopened app.

## Radio-option tap regression fixed — 2026-10-14

User reported notification settings radio options no longer selected. Touch ACTION_DOWN called CrownScrollController.stop(), which invoked ListView.fling(0) before native touch handling; this enters fling touch mode and interferes with row clicks. Added onTouchDown() to cancel pending crown callbacks/momentum without invoking a native fling; both page and dialog touch listeners use it and return false so the native view handles the gesture. Lifecycle stop still cancels native motion. All 72 JVM tests, assembleDebug, lintDebug and diff check pass. Installed at 192.168.0.162:43329 with explicit Success, Bridge verified disabled. Storage loaded without errors after reopening. UI automation found/tapped Notifications: Shown, then after waking found the checked Shown row and tapped it. Follow-up UI capture failed with null root, so callback/dismissal was not verified; do not claim end-to-end interaction passed. No user preferences intentionally changed.

## Settings separation, mail defaults, crown motion and tray dismissal — latest work

**User changed dismissal requirement: watch dismissal must keep history in the app, while hiding those items from native notifications. This supersedes all prior “clear conversation on watch dismissal” notes.**

Implemented:
- AppSettings separates hidden Boolean from Delivery NORMAL/SILENT. UI uses Notifications Shown/Hidden, Alerts Watch default/Silent, Grouping, Display style. WAC3 codec reads WAC1/2 and migrates MUTED to hidden=true; alert preference remains independent when hiding/showing. Per-app Reset to app defaults uses AppSettings(), preserving app identity, history and dismissals.
- Automatic grouping combines Gmail/Apple Mail and Email-category notifications into one app card. Explicit Separate, Combine and new By conversation override the default. Saved categories preserve this on reconstruction. Existing display defaults unchanged.
- History v3 reads v1/v2, adds per-record trayDismissed + dismissal time and per-phone bulk-clear cutoff. Dismissal no longer deletes history. Native cards filter dismissed records; new messages render without cleared history. Edits/replay/regroup/settings cannot directly resurrect dismissed records. Exact undated dismissed replay is matched too. Older queued backlog in a dismissed group is hidden; observed simultaneous disappearance of multiple tracked app cards sets a per-phone cutoff hiding older replay even from previously unprocessed groups. Fresh events and later-dated gap notifications remain eligible. Offline changed/undated data remains fundamentally heuristic.
- Delete intents capture tag and posted-through local ID in their distinct URI, preventing delayed callbacks from dismissing newer items in the same card. NativeTrayTracker compares posted IDs with active native tags before new events/rendering/settings changes; one-second grace avoids mistaking asynchronous posting for dismissal. Programmatic cancellation explicitly forgets tracking first. This covers delayed/missed delete callbacks while the service runs. Bulk clear is inferred when multiple tracked cards disappear together; no Android clear-all reason is exposed by delete intents. A process killed before both callback and reconciliation can still defeat detection; native behavior needs validation.
- CrownScrollController and pure RotaryMotion smooth input on animation frames, add bounded velocity-based acceleration and native fling after release, and cancel momentum on touch/page/pause. Used on pages and all single-choice dialogs. Home label now Notification history.

72 JVM tests/build/lint pass, including new dismissal history/restart/edit/replay/undated/backlog/new-message/settings-reset tests, tracker cancellation/race grace, mail defaults/overrides, WAC2 migration to hidden+alerts, and rotary acceleration/reversal/reset.

2026-10-14 installation confirmed: rediscovered endpoint 192.168.0.162:43329, verified Bridge disabled, installed with explicit Success and verified installed SHA256 equals local APK. Opened/woke app; receiver ready=true, phase=CONNECTED, 303 events/23 received/4 posted at check, storage loaded=true/saveError=false/logError=false. History migrated to version3 (96 records at initial check); catalog WAC3 verified. Later service check reported one tray-dismissed record. No personal contents emitted. Physical crown feel and actual Wear clear-all behavior still need user validation; service counters alone do not establish those. Earlier failed installation is superseded.

User asked about watch replies: current app does not support them. Apple ANCS provides predefined positive/negative notification actions but no free-text reply payload; adding keyboard/dictation alone cannot provide replies. A separate messaging-service integration would be needed. No reply/action implementation added.


## Instagram group identity

User authorized inspecting a recent Instagram sender/group example. Private in-memory inspection of saved v2 history confirmed category=0 (Other), title=sender and subtitle=group (subtitle matches the user-provided group closely; no personal names/body copied here). Existing generic fallback keyed both sender and subtitle, which separated direct/group but would split participants into separate group cards. Added Instagram-specific subtitle-only group key plus separate direct-sender namespace. Explicit app-wide/separate grouping settings still win. Same-named contact and group do not merge. User subsequently requested popular-app defaults: Automatic now prefers MESSAGES for Instagram, Messages, Signal, Discord and WhatsApp, and INBOX for Gmail/Mail, then category fallback for all other apps. Explicit display overrides always win. Thus the observed category0 Instagram group uses message previews by default. Synthetic tests cover multi-sender group aggregation, direct/other-group isolation, identical group/contact label, overrides and absent/generic previews. All57 tests, assemble and lint pass. Both group-key and final popular-app-default builds installed successfully with explicit Success. Opened/woke app after final installation; receiver restored history with loaded=true/saveError=false/logError=false and was CONNECTING at the immediate check. User asked logic priority: current display = explicit per-app style > known app default > category > text. Grouping = explicit APP/SEPARATE override > app-specific title/subtitle rule > conservative exact-title/subtitle fallback > individual. Body parsing is limited to recognized layouts, not free-text semantic classification.

## Latest installation confirmed

User requested another install attempt. mDNS endpoint adb-32281JEEJW01G0-VPWAI6._adb-tls-connect._tcp / 192.168.0.162:34587 responded. Bridge verified disabled. Latest combined APK (14,374,719 bytes: crown, notification app-settings action, Recents, complication, Tile, category presentation and migrations) installed with explicit Success; on-device SHA256 matches local APK. Opened/woke app. Receiver verified ready=true, phase=CONNECTED, 307 events/4 received at check, 39 known apps/36 fetched names, loaded=true/saveError=false/logError=false. Left running. Prior pending-install statements below are superseded. Physical crown behavior, Tile/complication layout and style overrides still need user/device interaction validation; those were not claimed proven by the install.

## Category-driven display styles — latest addition

User authorized category-based default presentation with per-app overrides. Added DisplayStyle AUTOMATIC/MESSAGES/INBOX/TEXT independently of Grouping. Automatic uses latest item category: Social(4)→MessagingStyle, Email(6)→InboxStyle, every other/unknown wire category→BigTextStyle. Legacy category=-1 falls back to known chat/mail app IDs, otherwise text. Apps & delivery → app → Display style offers Automatic, Message previews, Inbox list, Expanded text; existing internal EVENT_APPS silently refreshes active cards. Group keys/history unchanged. Inbox shows newest seven native lines with total count; expanded text uses last25 items/up to12,000 chars; all saved history remains uncapped. Messages explicitly supplies group title only for group cards.

Persist category in SavedNotification and AncsCardMessage and preserve across restoration, regrouping, UID edits and replay. HistoryCodec writes version2, reads versions1/2 (legacy category=-1). AppCatalogCodec writes WAC2, reads WAC1/WAC2; legacy settings keep delivery/grouping and get Automatic display. Existing filenames unchanged for in-place AtomicFile migration. NotificationLog upgrades schema/version2 in a transaction with category column default -1; future rows record real category. No personal content read for this work. Five new tests cover category/override precedence, legacy catalog and history migration (including dismissed fingerprints), restore/regroup/replay category updates, and combined expanded text. All54 JVM tests, assemble and lint pass; synthetic SQLite migration also checked. Installation and native rendering validation deferred until user makes watch available. Prior notes about missing retained categories/rendering are superseded.

## Connection Tile — latest addition

User explicitly requested a swipe-over Tile as well as the complication. Added ConnectionTileService, protected by BIND_TILE_PROVIDER, label iPhone connection status, bundled synthetic preview, responsive PrimaryLayout with status/detail and Connection button. Button passes the existing connection-settings destination extra. Reads live process phase only; swiping does not start Bluetooth or read message storage. State changes request Tile updates alongside complication updates, with 300s freshness fallback and request on Tile entry (legacy onTileEnterEvent for current watch compatibility; deprecated in newer Tiles API). Dependencies tiles 1.6.2 / protolayout-material 1.4.2 / concurrent-futures 1.2.0. Build, 49 existing JVM tests and lint pass (0 errors). No device attempts: user will make watch available later. Recents/complication/crown/Tile still need install and live validation.

User asked whether notification types can come from iPhone data. Confirmed Event.category already parsed with flags, but SavedNotification and archive currently omit category and renderer does not use it. ANCS categories: other, incoming call, missed call, voicemail, social, schedule, email, news, health/fitness, business/finance, location, entertainment. Broad semantic category is useful but not original iOS layout or a chat/thread marker. Proposed category + known app/layout rules + per-app overrides; no rendering/storage change made merely in response to this question.

## Recents and connection complication

User said the watch will be available later, so no further device/install attempts this turn. Removed MainActivity excludeFromRecents=true; Back from home now backgrounds the task instead of finishing/removing it. Added a watch-face complication (assumed meaning of homescreen widget; user subsequently requested a swipe-over Tile too; implemented above). ConnectionComplicationService supports SHORT_TEXT/LONG_TEXT, label iPhone connection status, compact states Linked/Linking/Offline/Check and tap-to-Connection. Reads live process phase, never a persisted connected boolean. WatchApplication listens only for phase changes, coalesces updates for 1 second, and requests complication updates. System refresh fallback 300s, data validity 600s to avoid indefinitely cached Linked after process death; ambient/platform scheduling may delay display changes. Binding protected by BIND_COMPLICATION_PROVIDER. No new notification content exposed. Added watchface-complications-data-source 1.2.1 (official guide version). Pending device validation includes actual Recents behavior, watch-face slot selection/rendering/tap and connection transitions, plus previous crown/settings shortcut work. Build, 49 JVM tests and lint pass (0 errors); device installation deferred. Notification rendering is unchanged: discussed separate presentation choice (chat vs inbox vs plain) rather than silently changing grouping.

## Crown and app-settings shortcuts

Added rotary-encoder scrolling for every Activity page, with focused scroll containers and device-scaled AXIS_SCROLL handling. Delivery/grouping picker lists handle crown scrolling too. Saved conversation pages have a source-app settings button and Back returns to the conversation. Native relay cards have an App settings action with a distinct per-app immutable PendingIntent; cold and existing Activity launches wait for repository loading. Renamed app-wide grouping to “Combine all notifications” and explicitly explained that different chats/senders share one card. Existing preference values unchanged. Build, 49 existing JVM tests and lint pass. Installation attempted at 192.168.0.162:34587 with Bridge verified disabled. APK transfer completed, but install timed out before Success; both IP and mDNS shell commands then stalled and IP reconnect failed. A subsequent installed-APK SHA256 check confirmed the previous build was still installed. A second install via mDNS also transferred but timed out without Success. Do not claim this update is installed until checking installed APK or reinstalling. Physical crown scrolling and app-settings action navigation remain unverified on device. Next: rediscover endpoint and install app/build/outputs/apk/debug/app-debug.apk, then verify crown and notification settings shortcut. No settings, bonds or user messages altered during this UI task.

## App controls, grouping and state review — latest work

User confirmed prior build works and requested a persistence/flow review, Signal grouping, default fallback grouping, real app display names, a stored app directory with mute/silent/combine settings, and clearer connection status. Existing natural Signal notifications were explicitly authorized for local layout inspection; no contents/names were printed or saved on host. Three saved Signal records had distinct title-only contexts, empty subtitles; one had a sender-colon body layout. No multi-message same-group live sample yet.

Implemented and installed:
- `AppCatalog.kt`: persisted observed apps with iPhone display name, last seen, Delivery NORMAL/SILENT/MUTED, Grouping AUTOMATIC/APP/SEPARATE. Existing message records populate the catalog on upgrade. Preferences use an independent private AtomicFile, so clearing conversations/log does not reset them.
- `AncsAppAttributes.kt`: Get App Attributes command 1 / Display Name 0, strict fragmented response parser, write-ack/response ordering. Service serializes these alongside notification requests, caches fetched names, and skips optional lookups that fail rather than retry-looping them. Names shown in notification subtext and app UI; friendly labels are interim fallbacks.
- Settings → Apps & delivery: per-app delivery/grouping choices. Muted suppresses native cards but retains history/log. Silent uses a separate LOW-importance channel. Combine creates one source-scoped app thread, preserving notification title as sender and subtitle/body. Changes update current cards silently and preserve records. Gmail stays at defaults unless user changes it.
- Signal visible-title grouping, sender-colon body presentation, subtitle-group and English sender-in-group variants. Exact title fallback is best-effort for title-only layouts; labels are not true thread IDs. Unknown apps use exact title+subtitle grouping, excluding obvious generic/hidden summaries. Separate mode disables grouping. No guarantee for identical display names/localized ambiguous layouts.
- Explicit `ConnectionPhase` controls UI: disconnected/loading/connecting/preparing/connected/retrying/error. Connected only after notification subscriptions succeed. Main connection action is context-sensitive; repeated Connect for an already-ready phone doesn't reconnect. Service supports sticky process recreation and app reopening resumes an enabled saved phone. Explicit Disconnect persists disabled state. No boot receiver added.

Review fixes: unreadable persisted files are preserved rather than overwritten by later empty saves; restored/replayed timestamps survive watch timezone changes; grouping changes keep session UID mapping; fresh events cannot be evicted by historical backlog at queue capacity; duplicate teardown writes removed; settings enqueue an immediate save. Existing crash-window caveat for coalesced history writes remains. Long-run unlimited-history scaling and offline edit/undated matching remain best-effort limitations.

49 JVM tests plus assemble/lint pass (0 lint errors, 5 warnings). New tests cover app-name request bytes/every fragment boundary/ack ordering/malformed replies, catalog preference round trip, Signal/fallback rules, app-wide regrouping with UID edits, timezone restore+replay, and backlog saturation. Final APK installed successfully. App reopened and automatically resumed its saved enabled connection: ready=true, phase=CONNECTED, 297 events and 27 received at the check, loaded=true/saveError=false/logError=false. Private catalog survived replacement/restart with 39 apps and 36 fetched display names, all default settings preserved. A native metadata check found six relay cards, all with app labels and none with bundle-identifier labels. Bridge reconfirmed disabled. ADB mDNS stalled; explicit IP `192.168.0.162:34587` restored responsiveness. UI automation is intermittently unreliable; end-to-end delivery-setting interactions and same-group Signal multi-message aggregation still need live validation. No personal contents were emitted.

Lifecycle review also gates auto-resume on a resumed Activity (avoids a delayed load starting a foreground service from the background), and refuses service startup without Bluetooth connect permission. The service remains running after verification.

## Latest update — iPhone Connection UI and local storage (supersedes older session-only descriptions below)

User requested a quiet connection notification, persistent history with reconnect/backlog deduplication, and a user-oriented UI. They explicitly rejected message-count limits and chose **watch dismissal only** to reset saved conversation history; iPhone dismissal preserves it. They also requested a separate **14-day local notification log**, with **no archive UI yet**.

Implemented:
- Static silent low-importance foreground card, posted once per service instance; live status stays in the app. That intermediate status-only APK installed successfully. The combined build is now installed; see live verification below.
- App label **iPhone Connection**, existing package unchanged. Home shows connection and recent conversations; connection selection/reconnect/disconnect, notification settings, clear-all-data and content-free diagnostics are separate screens. Test controls/raw field dump removed from UI. Native watch-sized controls and paged conversation views.
- Uncapped local conversation model, source-scoped keys, stable hashed notification tags. Native notification renderer takes last 25 messages (platform MessagingStyle capacity); saved history is not capped.
- `NotificationHistory.kt`: durable local IDs, transient per-session UID mappings, one-for-one app/title/subtitle/body/date replay matching, fresh-event duplicates preserved, watch-dismissal clears conversation and persists only suppression fingerprints. iPhone removals cancel the last active card but retain collected history. Missing dates/changed content across sessions remain best-effort limitations.
- `HistoryRepository.kt`: private no-backup AtomicFile, versioned validated codec, asynchronous restore and serial/coalesced writes. `HistoryDismissReceiver` handles native watch swipe/clear-all through explicit unexported delete intents and waits for persistence. Tapping cards no longer auto-cancels them.
- `NotificationLog.kt`: private SQLite received-observation archive, 14-day retention by local receipt time; startup/activity/write/hourly cleanup. No archive browser. Stopped processes clean up on next start. Watch dismissal preserves log; Clear all local data deletes conversations and log. Dated replay-suppression hashes are retained so cleared phone backlog does not immediately recreate history.
- 38 JVM tests pass, assemble and lint pass (0 errors, 4 warnings). Persistence/replay/UID reuse/duplicates/phone isolation/clearing/codec tests use synthetic content only. Long-run uncapped history scaling and real swipe callbacks still need device verification.

Device recovery and live verification (2026-10-13 evening): user force-rebooted the watch and re-enabled wireless debugging. Cause of prior black screen remains unknown. mDNS rediscovered `192.168.0.162:34587`; use serial `adb-32281JEEJW01G0-VPWAI6._adb-tls-connect._tcp`. Bridge confirmed disabled-user. Combined app installed successfully and app UI opened. Real reception completed an initial 292-event backlog. App-private history contained 292 records; a subsequent new notification made 293. After restart/reconnect replay, the 293-record snapshot checksum remained unchanged, verifying deduplication and persistence for this sample. No private fields were printed or saved on the host; snapshots were inspected only in memory for counts/checksums.

Device testing caught SQLite initialization failing because `execSQL(PRAGMA secure_delete=ON)` returns a result; fixed it to `rawQuery(...).use { moveToFirst() }`. The corrected app wrote archive rows (204 observed during replay), and service diagnostics reported loaded=true, saveError=false, logError=false. Added content-free storage flags to service dump.

Android caps outstanding app notifications; the service now avoids filling the tray with old backlog cards by evicting the oldest visible relay card before admitting a newer one when there are already 48 app cards (reserves system headroom). History is not deleted by this programmatic cancellation. Native cards still contain only the latest 25 messages; saved conversation history has no count cap.

Restore was slow on the watch. Optimized bulk reconstruction to avoid per-record full-model diff/retention scans and replaced per-byte String.format hashing. All 38 tests/build/lint passed again. The final performance-fix APK installed successfully. Final receiver verified ready=true, receiving replay (74/291 at the check), loaded=true, saveError=false, logError=false. Native notification inspection found 47 relayed cards, including 12 MessagingStyle conversation cards. The status card title is iPhone Connection and its creation/update timestamps remained unchanged while new cards arrived. The receiver is left running. Real swipe-dismissal and extended reliability still need verification.

No personal notification fields or webhook secret saved to the repository or printed. App-private notification persistence is now explicitly user-requested. Earlier notes saying no on-disk content or session-only retention are historical.

## Standing device instruction

The user explicitly requested remembering to disable Bridge before ANCS work. See `AGENTS.md`: verify `com.orienlabs.bridge.wear` is disabled-user before connecting/testing our receiver; force-stop alone was insufficient. Keep it installed, preserve its bonds/data, and leave disabled unless the user requests switching back.

## Goal and priorities

Build a private direct iPhone → Pixel Watch notification relay with native expandable conversation previews when source data supports reliable grouping. User prioritizes Signal and iMessage, possibly Instagram later. Discord webhook testing is explicitly authorized by the user in chat; its secret must not be copied into this repository, documentation or logs.

Devices: iPhone iOS 18, Pixel Watch 2 Wear OS 5.1 (API 35), originally configured using Galaxy S10. Bridge remains installed. No INTERNET permission, cloud, analytics or app notification-listener access.

## Pairing investigation

At session start the user described two watch entries on iPhone. They now report it is back to **one**, with **Share System Notifications enabled**. A watch Bluetooth dump shows one bonded iPhone and Bluetooth clients registered for both Bridge and Watch Test. This does not establish what caused the temporary duplicate. Do not claim it was a second physical device or that a second bond was created. No new pairing, bond removal, Bluetooth reset or security-setting change was performed this session.

The prior app could try `createBond()` after selecting an anonymous scan result. That fallback and BLE scanning have now been removed. The app lists all existing bonds, independent of device name, and connects only to the chosen bonded identity.

## Current implementation

- `AncsProtocol.kt`: valid 19-byte Get Notification Attributes request; only title/subtitle/message take length parameters. Fragmented UTF-8 tuple reassembly with bounds/header/order checks.
- `AncsSession.kt`: pure Kotlin request state, bounded/coalesced queue (512), response retained across fragments; waits for both write acknowledgement and response; suppresses removed/superseded records. Connection teardown resets state.
- `AncsBridgeService.kt`: main-looper GATT callbacks, stale-client checks, DS then NS subscription, serialized writes, connection/setup/response timeouts, write errors, explicit session teardown on service change, no automatic pairing. Transport disconnects/service changes now retry the selected bond with exponential backoff (2–60 seconds), cancelable via Disconnect; protocol errors do not auto-retry. No boot startup.
- `AncsConversations.kt` now groups observed Discord channel/server labels and observed iMessage named-group subtitles into native MessagingStyle cards, with sender names and up to eight messages. 32-card bound, retaining newest notification timestamps rather than backlog arrival order; unrelated or unrecognized formats use individual BigTextStyle cards. UIDs identify message updates/removals; last removal cancels the card. Session ends clear relayed cards. Group keys are explicitly display-context heuristics, not real iOS thread IDs. Same-named group/server/channel collisions remain possible. Actual Discord thread separation requires a distinct visible context, not yet tested.
- `AncsDiagnostics.kt`: six recent records in process memory, surviving Activity pause; cleared on session end. No fields in logs or on disk. Service `dumpsys activity service` exposes only counters/status.
- `Conversation.kt`: existing normalized-message API and synthetic MessagingStyle demo. Synthetic normalized API remains separate from the live display-context grouping adapter. ANCS has no standard thread ID; do not claim these label keys are actual thread identifiers.
- Activity now puts iPhone controls first; diagnostic fields are collapsed behind Show / hide details. It retains local diagnostics across pause/resume. Device buttons are not rebuilt on status changes, fixing the jumping UI.

## Verification this session

- Twenty-three JVM protocol/session/conversation tests pass (14 grouping tests plus 9 protocol tests), including every split boundary, one-byte fragments, multibyte UTF-8, high unsigned UID, exact request encoding, removal while in flight, superseding modification, response-before-write-callback, queue bounds and malformed responses.
- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` passed. Lint: 0 errors, 11 warnings (dependency versions, backup/task configuration, redundant API checks, unused strings).
- Updated debug APK installed successfully using `adb install --no-streaming -r` after streamed installation stalled.
- **Real ANCS reception and Android notification posting now verified through service counters**: after temporarily force-stopping Bridge and reconnecting, counters showed 286 events, 10 received/posted, with 55 queued and more processing. Actual tray confirmation for our card remains pending. A Discord-only grouping build produced four live conversation cards before a transport timeout. The latest build is installed. On the final retention build, privacy-filtered Android notification inspection verified four native Discord conversation cards (two multi-message, up to five messages) and two native iMessage conversation cards (one multi-message, up to four messages). The specific user-approved group was also earlier verified with MessagingStyle and group flag set. No personal message bodies or sender names were printed or persisted.
- Discord synthetic webhook messages A1, A2, A3 sent successfully (HTTP 204). User confirmed Discord and the error status card appeared on watch. With Bridge running, our receiver failed with `Unexpected ANCS attribute 5; expected 1`. With Bridge temporarily force-stopped (`am force-stop com.orienlabs.bridge.wear`), then our app reconnected, reception/posts succeeded. Strong evidence of concurrent ANCS request interference. Bridge remains installed and paired but is now **disabled-user for exclusive ANCS testing** (`pm disable-user --user 0 com.orienlabs.bridge.wear`), after it repeatedly registered again following force-stop. Re-enable with `pm enable --user 0 com.orienlabs.bridge.wear`; do not run both receivers concurrently. Reopening it may restart the conflict. A3 was sent after successful standalone reception; asked user to confirm the Watch Test card. No private content printed or persisted.
- Fixed jumping device UI: preserve buttons across status/field broadcasts, rebuilding only when bonded device list changes. Build/lint passed and fix installed. Do not repeatedly ask user to verify iPhone pairing: Bridge already works.

## Direct iMessage chat update

The user requested one-to-one iMessage grouping and asked whether grouping can generalize to all apps. Implemented a Messages-only sender-label rule when subtitle is empty and title/body are nonempty, excluding common generic app/summary titles. Direct-chat and group keys use separate rule namespaces, preventing a contact and same-named group from merging. Native MessagingStyle now uses the appropriate group flag. Added tests for repeated direct messages, separate senders, same-named group/contact isolation, and generic/unknown-app fallbacks. Build/lint and all 23 tests pass. The updated APK installed successfully. ADB stalled while reopening the app/receiver; manual connection and live direct-card verification are pending. Last IP transport failed to reconnect; mDNS transport was listed but its service query also stalled. Do not claim direct-card native verification yet.

Explain: the bounded conversation store/rendering is shared across apps; each app needs a verified or explicitly best-effort identity rule. ANCS exposes display fields rather than iOS thread IDs. Same-name contacts, missing group context, unnamed groups, renamed chats, summaries and hidden previews can be ambiguous. No generic cross-app title grouping was enabled. Signal/other unverified layouts remain individual cards.

## Latest conversation work

- User confirmed the basic A3 relay card and requested per-conversation previews. Priorities remain Discord, iMessage and Signal.
- Synthetic Discord D1/D2 sent with different webhook display names. Locally observed ANCS title pattern is FSI sender PDI (FSI #channel PDI, FSI server PDI), empty subtitle. Parser requires this exact isolated format to avoid punctuation-based accidental splits. No personal labels/message bodies copied into repo or chat.
- User authorized inspecting an existing iMessage group. Content-free layout query verified app `com.apple.MobileSMS`, title=sender, subtitle=group, body does not start with title. Added generic named-group rule and tests with synthetic names; do not store user's actual group name in fixtures.
- `dumpsys activity service ... layout LABEL` prints only field relationships. Up to 512 title/subtitle layout records are kept in memory, separate from six visible diagnostic records. No raw body content is stored in layout diagnostics.
- Bridge was found registered again during reconnect errors; force-stop did not keep it inactive. Its watch package is now disabled-user for the test (reversible using `pm enable --user 0 com.orienlabs.bridge.wear`). It remains installed/paired; concurrent operation interferes with our ANCS responses.
- Device had transient status 133 and status 8 transport failures; automatic retry against the selected existing bond added. Write-start failures also retry, with the status code surfaced. The next request is scheduled 100 ms after leaving a GATT callback. Fresh events are prioritized over pre-existing backlog. The paced-request build completed a 302-event backlog with 302 received, no pending requests, and the connection still ready. Long-run recovery behavior remains unverified; do not claim the transport is fully reliable.

## Next steps

1. User already confirmed basic A3 relay. Native conversation-card metadata is now verified for Discord and iMessage; ask user to inspect expansion/sender lines when convenient. Keep Bridge disabled for all ANCS device testing.
2. User-authorized Discord webhook in chat has sent A1–A3 and D1–D4; D1/D3 used synthetic sender Alex and D2/D4 sender Sam. Avoid sending unnecessary repeats. Allow time for the iPhone to refresh. Do not print webhook secret or copy personal notification fields into chat/repo/logs.
3. Verify actual card display, updates/removals, activity pause/resume and reconnect. Do not run Bridge and our direct ANCS receiver concurrently without solving shared response interference. Identify ours by Watch Test header.
4. Continue visual verification and test updates/removals of native MessagingStyle cards for Discord and iMessage. Signal, Discord direct-message and additional Discord thread formats remain unverified and use individual cards. Obtain scoped examples before adding further rules.
5. Improve connection resilience and app UI after successful end-to-end reception, without resetting bonds or disturbing the verified notification collector fix.

## Build and device access

Package `dev.tmn.watchtest`; Kotlin/AndroidX core 1.17.0; Gradle 9.6.1; AGP 9.1.0; compileSdk 36; target 35; min 33.

```bash
JAVA_HOME=/home/tmn/Apps/android-studio/jbr ANDROID_HOME=/home/tmn/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
/home/tmn/Android/Sdk/platform-tools/adb mdns services
/home/tmn/Android/Sdk/platform-tools/adb -s 192.168.0.162:33141 install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
/home/tmn/Android/Sdk/platform-tools/adb -s 192.168.0.162:33141 shell am start -n dev.tmn.watchtest/.MainActivity
/home/tmn/Android/Sdk/platform-tools/adb -s 192.168.0.162:33141 shell dumpsys activity service dev.tmn.watchtest/.bridge.AncsBridgeService
```

Last endpoint `192.168.0.162:33141` worked for installation. Rediscover if needed. ADB often lists the device while commands stall; transport disconnect/reconnect can help, no evidence establishes sleep as the cause. Use bounded timeouts and wait for command completion. Do not re-pair ADB unless actually needed. Watch is 384×384. Do not change lock/security settings. Avoid broad notification/logcat dumps, which may include personal content.

Protocol sources:
- https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/Specification/Specification.html
- https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/Appendix/Appendix.html
- https://developer.android.com/training/wearables/notifications

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
