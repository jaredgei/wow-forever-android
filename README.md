# WoW Forever for Android

Play the **World of Warcraft: Forever beta** on Snapdragon Android handhelds as a normal Android app. You tap the icon, press Play, and the game runs.

This uses Blizzard's own **Windows ARM64** WoW client, so the game itself runs natively on the phone's CPU. Wine translates the Windows calls, and DXVK plus a patched Turnip Vulkan driver render the game on the Adreno GPU. Nothing is emulated as x86.

> Unofficial community project. Not affiliated with or endorsed by Blizzard Entertainment. You need your own Battle.net account with WoW Forever beta access. This repo and its releases contain **no** Blizzard game files.

---

## Supported devices

| Device | Status |
| :--- | :--- |
| AYN Thor (Snapdragon 8 Gen 2 / Adreno 740) | Tested, reaches the game world with controller support |
| Retroid Pocket 6 (Snapdragon 8 Gen 2 / Adreno 740) | Tested by the original community setup |
| AYN Odin 2 / Mini (Snapdragon 8 Gen 2) | Expected to work |
| AYN Odin Portal (Snapdragon 8 Elite / Adreno 830) | Supported via bundled Turnip v32 driver |
| RedMagic 11 Pro (Snapdragon 8 Elite / Adreno 840) | Tested in-world by arusiasotto |
| Samsung Galaxy S23/S24/S25 / Z Fold series | Supported (with automatic Samsung UBWC optimization) |

Requirements:

- Snapdragon 8 series (Adreno 7xx or Adreno 8xx). The app bundles both the Adreno 740 driver and the Turnip v32 driver for Snapdragon 8 Elite / Adreno 8xx, automatically detecting the GPU and applying the optimal driver and environment flags.
- Android 10 or newer, 64-bit.
- About **80 GB** free for the game data (internal storage or SD card), plus about 4 GB of internal storage for the app and its Windows environment.
- A Mac or PC with the WoW Forever beta installed through Battle.net, to copy the game data from.

---

## Installation

### 1. Install the app

Download `WoW-Forever.apk` from the [latest release](https://github.com/jaredgei/wow-forever-android/releases/latest) and install it on your device. The app's package name is `app.wowforever`, so it can sit alongside GameNative or Winlator.

### 2. Copy your WoW game data to the device (one-time setup)

Copy two things from your Battle.net install (on a Mac that's `/Applications/World of Warcraft/`) into a folder on the device. The default is `/storage/emulated/0/WoW Forever/`. Any other folder works too, including one on an SD card. You only need to do this **once**; all subsequent game updates are handled directly in-app over Wi-Fi.

```
WoW Forever/
├── .build.info                   <- from the install root
└── Data/                         <- the whole Data folder (~67 GB)
```

- `Data/` is the same on every platform, so a Mac, Windows, or ARM install all work.
- `.build.info` is a hidden file. Copy it too. Without it the game fails with *"CAS system was unable to initialize"*.
- You don't need `WowB-ARM64.exe`. Mac and x86 installs don't ship it, so the app downloads the matching Windows ARM64 client from Blizzard's CDN when you press Play, and checks its hashes.
- If you used a different folder, or moved it, the launcher shows **Locate Game Files**. Pick the folder that contains `.build.info` and `Data/`. **Change Location** switches it later.

Transfer the files over to a `WoW Forever/` folder on your device. You can do this by:

- **SD Card:** Insert the microSD card into your computer and copy the files over (make sure your file manager shows hidden files so `.build.info` is included).
- **USB File Transfer:** Connect your device to your computer via USB (in File Transfer / MTP mode) and copy the files directly to internal storage (default: `/storage/emulated/0/WoW Forever/`).
- **ADB Script:** If you have ADB installed and this repo checked out, you can use the sync script to copy initial files easily over USB:

```bash
WOW_SRC="/Applications/World of Warcraft" tools/sync_wow_to_device.sh
```

### 3. Play

Open **WoW Forever**. Once your game files and Battle.net credentials are configured, the app automatically launches straight into the game. If credentials are not yet configured, the launcher stops at the setup screen so you can enter them before playing. The first launch downloads the ARM64 game client and installs the Windows environment, which takes a few minutes and needs an internet connection.

- **Setup screen:** to access folder settings, forget credentials, check environment status, or view updates, hold **Start + Select + L2 + R2** (or tap the back button) during the loading splash to cancel boot and return to the setup screen.
- The app creates a basic `WTF/Config.wtf` on first run and always sets `gxApi "D3D11"`, the renderer that works with DXVK.

### Controls and signing in

- **Controller:** built-in handheld controllers work in-game. WoW's own gamepad mode handles the mapping.
- **In-game menu:** press Back (the button or the back swipe gesture) to open the sidebar. It has **Keyboard**, on-screen controls, performance overlay and **Exit**.
- **Keyboard:** the sidebar's **Keyboard** opens the Android keyboard. On dual-screen devices like the Thor it appears on the bottom screen. Symbols like `@` work, and so does pasting.
- **Battle.net Auto-Login:** configure credentials directly on the launcher setup screen (**Configure Login** / **Update Login**). Credentials are saved encrypted on-device via Android Keystore. On boot, the launcher generates `_classic_beta_/login.txt`, which the WoW client automatically reads on startup to sign in natively without macro simulation or synthetic clicks. Use **Forget Saved Login** on the launcher setup screen to clear credentials and remove the login file.
  - Authenticator codes still have to be entered by hand.

---

## Updating the game

WoW Forever v2.0 features a native in-app game updater connected directly to Blizzard's public edge CDNs. **You no longer need a computer or USB cables to keep your game updated.**

Whenever Blizzard patches the game:
1. **Live Version Check:** The launcher automatically checks the live version against Blizzard's public patch service on startup.
2. **Update Gate:** If an update is detected, auto-launch cancels and displays the update card with the remote build version, plus a secondary *Launch Anyway (Outdated)* override.
3. **One-Tap Update:** Tapping **UPDATE TO {version}** downloads the new ARM64 client binaries (`WowB-ARM64.exe` and companion DLLs), saves remote configs to `Data/config/`, syncs new CASC index archives to `Data/indices/`, and atomically updates `.build.info` on disk over Wi-Fi.
4. **Instant Boot:** Once the update completes, the launcher automatically transitions into booting World of Warcraft at 60 FPS.
5. **In-Game Streaming:** The game engine's internal streaming client seamlessly streams any newly introduced assets from Blizzard's edge CDNs during gameplay.

*(Optional fallback: If you ever want to re-seed or mirror your full PC installation over USB, `tools/sync_wow_to_device.sh` is still available.)*

---

## Troubleshooting

| Symptom | Fix |
| :--- | :--- |
| *CAS system was unable to initialize: no active install info entries* | `.build.info` or `_classic_beta_/.flavor.info` is missing on the device. |
| *No realms available* / no servers listed | The device client is out of date. Tap **Update** on the launcher setup screen, or restart the app with Wi-Fi enabled. |
| Keyboard doesn't appear | Force-stop Gboard (Settings → Apps → Gboard → Force stop) and open **Keyboard** again. It can get stuck on the second screen. |
| Returns to the launcher after "Launching Game…" | Check the files under `_classic_beta_/Errors/` on the device. |
| Handheld frontend (e.g. Cocoon) shows the wrong icon | The frontend cached an old icon. Set it with the frontend's "Edit App Artwork", or reinstall the app. |

---

## Building from source

Requirements: JDK 17 and the Android SDK (with build-tools and platform-tools).

```bash
tools/fetch_components.sh           # Wine/Proton, DXVK and Turnip archives (~130 MB, not in git)
./gradlew assembleModernRelease     # app/build/outputs/apk/modern/release/app-modern-release.apk
./gradlew assembleModernDebug       # unminified debug build, faster to iterate on
```

Release builds are signed with `app/keystores/keystore.properties` when it exists (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Otherwise they fall back to the debug key. Builds signed with different keys can't update each other, so switching between them means uninstalling first.

`fetch_components.sh` downloads the three runtime archives from this repo's `components-v1` release and checks them against `tools/components.sha256`. If you already have them, pass a folder instead: `tools/fetch_components.sh /path/to/components`.

---

## Credits and how it works

This project packages other people's work into a single-purpose app. None of it would exist without:

- **[GameNative](https://github.com/utkarshdalal/GameNative)** by Utkarsh Dalal and contributors (GPL-3.0). The Android app, the Wine container management and the X server are all GameNative, based on v1.2.1. GameNative in turn builds on **[Pluvia](https://github.com/oxters168/Pluvia)**, **[Winlator](https://github.com/brunodev85/winlator)**, **[Winlator Cmod](https://github.com/coffincolors/winlator)** and the **[Bionic Vulkan wrapper](https://github.com/leegao/bionic-vulkan-wrapper)**.
- **The WoW Forever RP6 community bundle**, which first got the beta running on a Retroid Pocket 6 in GameNative and supplied the three custom runtime components:
  - **Proton 11 ARM64EC** built from [The412Banner/proton-wine](https://github.com/The412Banner/proton-wine/tree/e5fa703ed7f7329e20d7ede481ab185cf8b1a8b2) with an ARM64 copied-syscall fix and an NLS fallback allocation patch.
  - **DXVK 2.4.1 (aarch64)** built from [doitsujin/dxvk](https://github.com/doitsujin/dxvk/tree/0cf05780abd7250c2cd713b7749cf32180157cf5).
    - **Mesa Turnip (Adreno 740)** built from Mesa [`fe067b17d9`](https://github.com/mirror/mesa/tree/fe067b17d9) with a patch limiting barycentric waits to the current block, which fixes a shader scheduler assertion on Adreno 740.
  - **Mesa Turnip (Adreno 8xx / Snapdragon 8 Elite)** built by **[arusiasotto](https://github.com/arusiasotto/wow-forever-a840)** from **[whitebelyash/mesa-unified](https://github.com/whitebelyash/mesa-unified)** (`turnip/gen8` v32) with the barycentric scheduler fix and ICD exports.
- **[Wine](https://www.winehq.org/)**, **[Proton](https://github.com/ValveSoftware/Proton)**, **[DXVK](https://github.com/doitsujin/dxvk)**, **[Mesa](https://mesa3d.org/)**, **[FEX-Emu](https://github.com/FEX-Emu/FEX)**, **[box64](https://github.com/ptitSeb/box64)** and **[PulseAudio](https://www.freedesktop.org/wiki/Software/PulseAudio/)**.
- **Blizzard Entertainment** for World of Warcraft and its Windows ARM64 client. The app icon and splash art come from Blizzard's official [WoW Forever page](https://worldofwarcraft.blizzard.com/en-us/forever). Blizzard owns them and the World of Warcraft marks.

Full third-party license details are in [`THIRD_PARTY_NOTICES`](THIRD_PARTY_NOTICES).

### What this fork changes from GameNative

GameNative is a general game library with Steam, GOG, Epic, Amazon, EA and Rockstar stores, mod management, VR support and per-game container settings. This fork turns it into a launcher for one pre-configured container:

- **Standalone identity:** package `app.wowforever`, WoW name, icons, banners and splash screen, installable next to GameNative.
- **Direct launch & instant boot:** the app opens to a WoW splash screen (`ui/screen/wow/WoWForeverScreen.kt`) and automatically boots straight into the game once configured. Holding **Start + Select + L2 + R2** or pressing Back on the loading screen cancels boot to return to the setup screen.
- **Pre-configured container:** bionic, Proton 11 ARM64EC, Turnip through the Vulkan wrapper, DXVK 2.4.1 aarch64, WINEESYNC off, all 8 cores, 1920x1080, and `G:` mapped to `/storage/emulated/0/WoW Forever`.
- **Native ARM64 launch:** GameNative wraps every Windows program in `winhandler.exe`, an x86-64 helper that needs x86 emulation. ARM64 executables now launch directly, so the game never goes through FEX, and the working directory is set from the mapped drive.
- **Game file setup:** the launcher writes `_classic_beta_/.flavor.info` and a basic `WTF/Config.wtf` if they're missing, forces `gxApi "D3D11"` (so a copied Mac config that says Metal can't break it), and refuses to start without `.build.info`.
- **Package-name fixes:** several paths were hard-coded to `app.gamenative`: the bionic library path rewrite (`WINEMU_HOST_PKG` / `HOST_PKG`), the gamepad shared-memory files, the DXVK state cache and the default drives. The controller path was the reason controllers didn't work.
- **Small robustness fixes:** non-numeric container IDs no longer crash the ID parser, and the bionic redirect library is copied in if it's missing.
- **Keyboard fixes:** GameNative's on-screen keyboard dropped shifted symbols (`@` came through as `2`), because it sent key codes without the character or the Shift key. The fix is in `Keyboard.java` and `IMEInputReceiver.kt`. The keyboard now always goes through the IME receiver, so it appears on the Thor's bottom screen.
- **Battle.net auto-login:** native `login.txt` client authentication managed via Keystore-encrypted credentials (`ui/screen/wow/BattleNetSignIn.kt`). Eliminates coordinate-based typing macros in favor of the game client's built-in startup authentication, with configuration available directly from the launcher setup screen.
- **Native In-App Game Updater (v2.0):** Live version check against Blizzard patch services, direct-from-CDN ARM64 binary and manifest downloads, CASC index synchronization, and atomic `.build.info` updates on-device over Wi-Fi without any PC dependency.
- **Lean runtime & startup:** eliminated continuous background accelerometer polling during gameplay in favor of native OS window management, removed cold-boot bitmap allocations, guarded background performance metric collection loops, and switched DNS to native platform resolution.
- **Removed:** every store backend (Steam and JavaSteam, GOG, Epic, Amazon, EA, Rockstar), library, login, settings, custom game scanner, and downloads screens, ExoPlayer and browser dependencies, `DownloadService`, `ContainerMigrator`, Nexus mod management, the Meta Quest/XR build, PostHog analytics, Play Integrity, the self-updater, the Room database, the notification prompt, extra bundled Box64/FEX/DXVK versions and the legacy (Android 9) build. That's roughly 135k lines of code and about half the APK size.
- **Tooling:** `tools/download_wow_arm64.py` fetches the ARM64 client from Blizzard's CDN, `tools/sync_wow_to_device.sh` handles updates, and `tools/fetch_components.sh` downloads the runtime archives.

## License

GPL-3.0, the same as GameNative. See [`LICENSE`](LICENSE). The bundled runtime components carry their own licenses (LGPL, MIT and others). See `THIRD_PARTY_NOTICES` and the notices inside each component archive.
