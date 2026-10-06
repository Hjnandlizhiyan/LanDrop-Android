# Optional verification

The Android app builds without these tools. Existing result summaries in `docs/` describe the checks already performed on the protocol core and debug APK.

## JVM protocol interoperability

Use JDK 25 and Node.js 22 or newer. Clone the [PCdeliver desktop source](https://github.com/Hjnandlizhiyan/PCdeliver), and provide a JVM `org.json` library as `verification/json.jar` (test dependency only, not bundled into the Android app or tracked in Git).

From the Android project root:

```powershell
# Adjust these paths for your machine.
$env:LANDROP_DESKTOP_SOURCE = 'D:\PCdeliver\server\app.js'
$env:LANDROP_JAVA = 'java'
javac -encoding UTF-8 -cp verification/json.jar -d verification/classes app/src/main/java/com/deepseeklover/landrop/LanDropEngine.java verification/ProtocolHarness.java
node --test verification/protocol.test.cjs
```

This checks Android-core ↔ desktop and Android-core ↔ Android-core transfers, Unicode, binary hashes, empty/duplicate files, peer credentials, transfer switches, cancellation, partial-file cleanup, and persisted settings/history.

## Android emulator checks

Install a **debug APK** on a dedicated emulator. The signed release APK intentionally disables WebView debugging and cannot use these CDP scripts. Install Playwright in your external test environment and set `PLAYWRIGHT_MODULE` to its module path when it is not available through normal Node module resolution.

The scripts accept `LANDROP_ADB` (default: `adb` on PATH), `LANDROP_DESKTOP_SOURCE`, and `PLAYWRIGHT_MODULE` (default: `playwright`). They use CDP at `http://127.0.0.1:9223`; forward that port to the app's `webview_devtools_remote_<pid>` socket with adb after launching the app.

For `android-ui.test.cjs`, also forward host TCP 45878 to the emulator's TCP 45878, and reverse emulator TCP 45890 to host TCP 45890. That script starts the real desktop server, exercises file and text transfers, uses system pickers, changes settings/history, and creates test receive files. The native UI labels assume an English Android emulator. Use an isolated emulator profile.

- `android-ui.test.cjs`: file/text interchange, document picker, receive-folder authorization, layouts, language, and background receiving.
- `device-select.test.cjs`: stable option nodes, selection/focus, progress polling, rename/reorder, language, and offline changes.
- `community.test.cjs`: creator links open in Chrome, QQ clipboard, and bilingual mobile layout. Opens external pages and changes the emulator clipboard.

## Optional UI regeneration

The committed assets are sufficient to build. To regenerate from the matching desktop source, supply its `public` directory explicitly:

```powershell
node verification/prepare-assets.cjs D:\PCdeliver\public
```

The transformations currently target desktop **0.4.3** / Android **0.1.2**. Review the generated diff before committing it.
