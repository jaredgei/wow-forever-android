Fixes for a frozen game, a crash with Italian text, and a setup error that wouldn't go away.

## Fixed

- **ERROR #134 with Italian text.** The game stops at start-up with *Failed to download required build info asset* when its text is set to Italian. The app now switches the text to English and keeps Italian audio, so the game starts.
- **"Container creation failed" every time you press Play.** If first-time setup was cut short, the app could never recover from it. It now replaces the broken setup by itself. Your game files, settings and addons stay.

## New

- **A warning before the game's clock stops.** After about 50 days without a device restart, the login buttons disappear and the game looks frozen. If your device has been on for 49 days or more, the app asks you to restart it when you press **Play**. If you're seeing this now, restart your device.

## Changed

- **Debug logging keeps the game from starting by itself** when you open the app, so its logs aren't replaced before you share them. Debug reports also list the game's text language and the time since the device started.

## Install

Download **WoW-Foreverywhere.apk** below, or use **Settings → Update app** inside the app. If you installed an earlier version from this page, it installs as an update and keeps your settings, saved login and addons.

If Android says the app "conflicts with an existing package", your current copy came from somewhere other than this page. Uninstall it first, then install this one. Your game files and addons stay on your storage.

New to the app? See the [README](https://github.com/YoruDev-Ryland/WoWForeverywhere#getting-started) for setup.
