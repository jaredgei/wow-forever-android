Fixes the game never starting on Android 17.

## Fixes

- **Android 17.** On Android 17 the game sat on "Launching game..." forever: Wine got stuck waiting on itself inside one of the helper libraries the app loads into it, before it could print anything. On Android 17 the app now loads a simpler version of that library. Other Android versions are unchanged. Thanks to rossieeissor for testing six builds to track it down (#9).
- **Back while the game is starting.** If the game got stuck starting, pressing Back froze the app for several seconds until Android closed it. It now returns to the app within a couple of seconds.

## Debug reports

With debug logging on in **Settings → Troubleshooting**, debug reports now show much more when the game doesn't start:

- The system log for all of the app's processes, including the game's, not just the app itself.
- Each step of Wine's startup.
- If the game hasn't started after 30 seconds, which parts of it are still running and what they're waiting on.

## Install

Download **WoW-Foreverywhere.apk** below, or use **Settings → Update app** inside the app. If you installed an earlier version from this page, it installs as an update and keeps your settings, saved login and addons.

If Android says the app "conflicts with an existing package", your current copy came from somewhere other than this page. Uninstall it first, then install this one. Your game files and addons stay on your storage.

New to the app? See the [README](https://github.com/YoruDev-Ryland/WoWForeverywhere#getting-started) for setup.
