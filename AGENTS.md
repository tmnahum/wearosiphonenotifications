# Watch bridge project instructions

Read `HANDOFF.md` before device work.

## Keep Bridge disabled during ANCS work

The user explicitly wants this remembered: before connecting or testing our ANCS receiver, verify that the competing Bridge watch app (`com.orienlabs.bridge.wear`) is disabled. Two receivers running together produced mismatched ANCS attribute responses. Force-stop did not keep Bridge inactive; it registered as a Bluetooth client again.

Use the current ADB endpoint, discovered with `/home/tmn/Android/Sdk/platform-tools/adb mdns services`:

```bash
adb -s ENDPOINT shell pm list packages -d com.orienlabs.bridge.wear
adb -s ENDPOINT shell pm disable-user --user 0 com.orienlabs.bridge.wear
```

Disabling Bridge for this project's device testing is authorized by the user. Keep it installed and preserve its data and Bluetooth bonds. Leave it disabled after testing unless the user asks to switch back. To switch back, stop our receiver first, then:

```bash
adb -s ENDPOINT shell pm enable --user 0 com.orienlabs.bridge.wear
```

Do not reset Bluetooth, create or remove bonds, or change watch security settings to resolve receiver conflicts. Preserve the previously verified Google Wear Services notification-listener fix documented in `HANDOFF.md`.

## Notification privacy

Keep personal notification text out of logs, chat, repository files and test fixtures. Use synthetic messages or user-approved conversation examples. Prefer content-free service counters and layout diagnostics. Never save the Discord webhook secret in this repository.
