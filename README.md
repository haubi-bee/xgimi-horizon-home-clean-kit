# xgimi-horizon-home-clean-kit

An ad-free home screen with your own photos as wallpaper, plus a reversible debloat, for the
**XGIMI Horizon Pro** projector (Android TV 11).

No custom ROM, no root, no bootloader unlocking. The launcher is a normal app; the setup only
disables packages (it never uninstalls system apps) and records every change, so
`scripts/restore.ps1` puts the projector back the way it was.

## Features

- **Clean Home launcher** (`tv.cleanhome.launcher`), built for low-memory Android TV devices:
  - photo slideshow as the home screen background (portrait photos are shown whole on a blurred
    backdrop instead of being cropped);
  - a row of the installed apps, ordered by how often you use them;
  - a recommendations row with the "continue watching" and preview content of your installed apps,
    dimmed until you move into it;
  - one tile per HDMI input, which you can rename (e.g. "HDMI 2" to "PlayStation");
  - header shortcuts for HDMI, Wi-Fi settings and system settings, next to the clock;
  - optional current weather next to the clock (Open-Meteo, no account or API key);
  - English and German user interface; clock and date follow the device language.
- **Photo screensaver** "Clean Home Photos" with the same photos, a slow zoom and a small clock.
- **PC scripts** (Windows PowerShell 5.1) to find the projector, set it up, sync a photo folder and
  undo everything.

## Status

Early pre-release. The launcher's core (photo wallpaper and screensaver, usage-ordered app row,
dimmed recommendations row, settings shortcut) and every individual adb step the scripts perform
were verified on the device below. The code in this repository was then reorganised for
publication, and the following parts have **not yet been run on a real device**: the HDMI input
tiles and renaming, the HDMI and Wi-Fi header shortcuts, the weather display, and `setup.ps1` /
`restore.ps1` as complete scripts. Read `docs/what-gets-changed.md` before running the setup, and
expect rough edges. There is no prebuilt APK yet; build it from source.

## Tested on

One XGIMI Horizon Pro (model XK03H), Android TV 11, firmware RT.3.24.327.

Other XGIMI models, other firmware versions and other Android TV devices are **untested**.
`setup.ps1` refuses to run on other models unless you pass `-Force`. The launcher itself uses only
standard Android TV APIs (plus XGIMI's own input switcher when it exists), so it may work elsewhere,
but nobody has checked.

## Requirements

- A Windows PC in the same network as the projector.
- adb: `winget install Google.PlatformTools` (open a new PowerShell window afterwards).
- Network debugging on the projector: *Settings > Device Preferences > About*, press OK on *Build*
  seven times, then *Settings > Device Preferences > Developer options* and enable *Network
  debugging* (on some firmware: *USB debugging*). Confirm the "Allow debugging?" prompt on the
  projector screen the first time a script connects.
- To build from source: JDK 17 and the Android SDK (platform 34). A prebuilt APK is not part of
  this repository.

If PowerShell refuses to run the scripts, allow local scripts for this window only:
`Set-ExecutionPolicy -Scope Process Bypass`.

## Quick start

```powershell
cd scripts

# 1. Find the projector's IP address
.\find-projector.ps1

# 2. Set it up (remembers the address for the other scripts)
.\setup.ps1 -Ip 192.168.1.50

# 3. Copy your photos (remembers the folder)
.\sync-photos.ps1 -Folder "D:\Photos\Wallpaper"
```

`setup.ps1` looks for the APK as `scripts\CleanHome.apk`, then for a release build, then for the
debug build in `launcher\app\build\outputs\apk\`; or pass `-Apk <path>`.

Options:

| Option | Effect |
|---|---|
| `-SkipDebloat` | Do not disable any packages. |
| `-KeepAssistant` | Keep the Google Assistant / voice search enabled. |
| `-FullPowerOff` | Power key shuts the projector down instead of standby. |
| `-SecureDns quad9` | Encrypted DNS via `dns.quad9.net`; rolled back automatically if name resolution fails. Default: `off`. |
| `-WeatherCity "Hamburg"` | Preset the weather location. |
| `-Force` | Run on a model other than the XK03H (untested). |

Do not run `setup.ps1` while someone is watching: installing can show a Play Protect dialog, and the
script presses HOME. **Turn network debugging off again afterwards** (see
[docs/security.md](docs/security.md)).

Run `sync-photos.ps1` again whenever the folder changes. It resizes photos to at most 2560 px
(JPEG), applies the EXIF orientation and mirrors the folder to `/sdcard/Pictures/CleanHome`.
HEIC/WebP/RAW files are skipped; export them as JPEG first.

## Using it on the TV

- **D-pad** moves between the rows; **OK** starts an app or input.
- **Long-press OK** (or the MENU key) on a tile: *App info* (apps only), *Rename*, *Hide*.
- **Rename** opens a text field; press OK on it to get the on-screen keyboard. An empty name
  restores the original one. Renamed apps with a banner show their new name as a caption.
- **UP** from the top row reaches the header buttons; LEFT/RIGHT moves between them; DOWN goes back.
  - *HDMI*: switches to the HDMI input, or lists them if there are several (hidden if the device has
    none).
  - *Wi-Fi*: opens the Wi-Fi settings; the icon shows whether Wi-Fi is connected.
  - *Settings* (gear): opens the system settings. **Long-press** it for *Unhide all apps* and
    *Weather location…*.
- **Weather location…**: type a city name; the launcher looks it up with Open-Meteo and shows the
  temperature (°F in the US, °C elsewhere) under the date. An empty name turns the weather off.
- The recommendations row stays dimmed until you move into it.
- The photo screensaver starts after the idle time set in the system settings
  (*Device Preferences > Screen saver*).

## Build from source

```powershell
cd launcher
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

The APK is `launcher\app\build\outputs\apk\debug\app-debug.apk`. Gradle needs the SDK location,
either via the `ANDROID_HOME` environment variable or a `launcher\local.properties` file with
`sdk.dir=...` (gitignored).

Release builds: create `launcher\keystore.properties` (gitignored) with `storeFile`, `storePassword`,
`keyAlias` and `keyPassword`, then run `.\gradlew.bat assembleRelease`. Without that file the release
APK is built unsigned.

Logs: `adb logcat -s CleanHome`.

## Undo

```powershell
cd scripts
.\restore.ps1
```

It re-enables the Google home screen first, then restores every package, setting and app operation
from the recorded `state.json` files (or factory defaults if there are none), and uninstalls Clean
Home. Your photos stay in `/sdcard/Pictures/CleanHome`. The exact list of changes is in
[docs/what-gets-changed.md](docs/what-gets-changed.md).

## Security

See [docs/security.md](docs/security.md). In short:

- Network debugging is an open admin port. Turn it off after setup.
- The app requests storage access (your photos), TV listings (recommendations), package visibility
  (app row), network state (Wi-Fi icon) and internet access.
- The only network requests the app makes: poster images from the installed apps' own preview data,
  and Open-Meteo if a weather location is set. No telemetry.

## What it does not do

- No workaround for Netflix certification or any other DRM or streaming-quality limitation.
- No streaming sources, add-ons or content of any kind.
- No telemetry, analytics or tracking.
- No changes to the firmware, no root.

## License

[MIT](LICENSE)
