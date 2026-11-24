# xgimi-horizon-home-clean-kit

An ad-free home screen with your own photos as wallpaper, plus a reversible debloat, for the
**XGIMI Horizon Pro** projector (Android TV 11).

No custom ROM, no root, no bootloader unlocking. The launcher is a normal app; the setup only
disables packages (it never uninstalls system apps) and records every change, so
the `restore` command puts the projector back the way it was. The setup runs from **Windows, macOS
or Linux**.

## Features

- **Clean Home launcher** (`tv.cleanhome.launcher`), built for low-memory Android TV devices:
  - photo slideshow as the home screen background (portrait photos are shown whole on a blurred
    backdrop instead of being cropped);
  - a row of the installed apps, ordered by how often you use them;
  - a recommendations row with the "continue watching" and preview content of your installed apps,
    hidden until you move UP into it, so the photo stays free;
  - after a minute without a key press the menu fades out and only the photo, the clock and the
    weather remain; any key brings it back;
  - one tile per HDMI input, which you can rename (e.g. "HDMI 2" to "PlayStation");
  - header shortcuts for HDMI, Wi-Fi settings and system settings, next to the clock;
  - optional current weather next to the clock (Open-Meteo, no account or API key);
  - English and German user interface; clock and date follow the device language.
- **Photo screensaver** "Clean Home Photos" with the same photos, a slow zoom and a small clock.
- **One script for Windows, macOS and Linux** (`scripts/cleanhome.py`, Python) to find the
  projector, set it up, sync a photo folder and undo everything.

## Status

Early pre-release, tested on a single device (see below). On that device, from Windows, all of this
was run with the published APK: `find`, `setup`, `sync-photos` and `restore`, the photo wallpaper
and screensaver, the app and recommendations rows, the HDMI tiles and renaming, the header
shortcuts, the weather display and the idle fade-out.

Not yet tried on a real device: running the script from macOS or Linux (there it is covered by
automated tests against a simulated projector only), and `setup` on a projector in factory state
as one run (the test device already had the packages disabled by hand, step by step). Read
`docs/what-gets-changed.md` before running the setup, and expect rough edges.

## Tested on

One XGIMI Horizon Pro (model XK03H), Android TV 11, firmware RT.3.24.327.

Other XGIMI models, other firmware versions and other Android TV devices are **untested**.
The `setup` command refuses to run on other models unless you pass `--force`. The launcher itself uses only
standard Android TV APIs (plus XGIMI's own input switcher when it exists), so it may work elsewhere,
but nobody has checked.

## Requirements

- A computer with Windows, macOS or Linux in the same network as the projector.
- Python 3.8 or newer and adb:

  | | Python | adb |
  |---|---|---|
  | Windows | `winget install Python.Python.3.13` | `winget install Google.PlatformTools` |
  | macOS | `brew install python` | `brew install android-platform-tools` |
  | Debian/Ubuntu | preinstalled | `sudo apt install adb` |
  | Fedora | preinstalled | `sudo dnf install android-tools` |
  | Arch | `sudo pacman -S python` | `sudo pacman -S android-tools` |

  Open a new terminal window afterwards. adb is also found in an Android SDK (`ANDROID_HOME`), or
  pass `--adb <path>`.
- Only for copying photos: the Python package Pillow. Windows: `py -m pip install pillow`, macOS:
  `python3 -m pip install --user pillow`, Debian/Ubuntu: `sudo apt install python3-pil`, Fedora:
  `sudo dnf install python3-pillow`, Arch: `sudo pacman -S python-pillow`.
- Network debugging on the projector: *Settings > Device Preferences > About*, press OK on *Build*
  seven times, then *Settings > Device Preferences > Developer options* and enable *Network
  debugging* (on some firmware: *USB debugging*). Confirm the "Allow debugging?" prompt on the
  projector screen the first time the script connects.
- Only to build the launcher yourself: JDK 17 and the Android SDK (platform 34). Not needed
  otherwise; the setup downloads the published APK.

The examples below use `python3`; on Windows type `py` instead.

## Quick start

```sh
cd scripts

# 1. Find the projector's IP address
python3 cleanhome.py find

# 2. Set it up (remembers the address for the other commands)
python3 cleanhome.py setup --ip 192.168.1.50

# 3. Copy your photos (remembers the folder)
python3 cleanhome.py sync-photos --folder ~/Pictures/Wallpaper
```

`setup` downloads the published launcher APK from this repository's
[releases](https://github.com/haubi-bee/xgimi-horizon-home-clean-kit/releases) and checks it against
the checksum stored in the script, so you do not need to build anything. An APK you built yourself
wins: `scripts/CleanHome.apk`, then a release build, then the debug build in
`launcher/app/build/outputs/apk/`; or pass `--apk <path>`. Every command explains its
options with `--help`.

Options:

| Option | Effect |
|---|---|
| `--skip-debloat` | Do not disable any packages. |
| `--keep-assistant` | Keep the Google Assistant / voice search enabled. |
| `--full-power-off` | Power key shuts the projector down instead of standby. |
| `--secure-dns quad9` | Encrypted DNS via `dns.quad9.net`; rolled back automatically if name resolution fails. Default: `off`. |
| `--weather-city "Hamburg"` | Preset the weather location. |
| `--force` | Run on a model other than the XK03H (untested). |

Do not run `setup` while someone is watching: installing can show a Play Protect dialog, and the
script presses HOME. **Turn network debugging off again afterwards** (see
[docs/security.md](docs/security.md)).

Run `sync-photos` again whenever the folder changes. It resizes photos to at most 2560 px (JPEG),
applies the EXIF orientation and mirrors the folder to `/sdcard/Pictures/CleanHome`. HEIC/AVIF/RAW
files are skipped; export them as JPEG first.

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
- The recommendations row is invisible until you press UP from the app row.
- After a minute without a key press the menu fades out; the first key press only brings it back.
- The photo screensaver starts after the idle time set in the system settings
  (*Device Preferences > Screen saver*).

## Build from source

```sh
cd launcher
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

On Windows use `.\gradlew.bat` instead of `./gradlew`. The APK is
`launcher/app/build/outputs/apk/debug/app-debug.apk`. Gradle needs the SDK location, either via the
`ANDROID_HOME` environment variable or a `launcher/local.properties` file with `sdk.dir=...`
(gitignored).

Release builds: create `launcher/keystore.properties` (gitignored) with `storeFile`, `storePassword`,
`keyAlias` and `keyPassword`, then run `./gradlew assembleRelease`. Without that file the release
APK is built unsigned.

Tests of the setup script: `python3 -m unittest discover -s scripts`.

Logs: `adb logcat -s CleanHome`.

## Undo

```sh
cd scripts
python3 cleanhome.py restore
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
