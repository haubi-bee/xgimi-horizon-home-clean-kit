# What gets changed

Everything `scripts/setup.ps1` does to the projector, and how `scripts/restore.ps1` undoes it.

Nothing is uninstalled except the Clean Home launcher itself (on restore). Packages are only
*disabled* for user 0, which keeps them on the system partition and makes them come back with
`pm enable` or a factory reset.

## How restore knows the previous values

Before changing anything, `setup.ps1` creates `scripts/backup-<timestamp>/` with:

| File | Content |
|---|---|
| `packages-system.txt`, `packages-user.txt`, `packages-disabled.txt` | `pm list packages` output before the run |
| `settings-global.txt`, `settings-secure.txt`, `settings-system.txt` | `settings list` output before the run |
| `state.json` | every change made in this run, with the previous value; written after each step |

`restore.ps1` undoes every `state.json` recorded for the connected device (matched by serial
number), newest first, so the values from before the first run win. With `-BackupDir` it undoes only
that run. If no `state.json` exists, it applies the defaults in the "Restore without state.json"
column below.

The backup folders contain device details (serial number, full package list). They are listed in
`.gitignore`; do not publish them.

## Packages

Disabled with `pm disable-user --user 0 <package>`, only if the package is installed and not already
disabled. Restored with `pm enable <package>`.

| Package | What it is | Condition |
|---|---|---|
| `com.xgimi.bugreportsender` | XGIMI bug report uploader | unless `-SkipDebloat` |
| `com.xgimi.doubanfm` | Douban FM music app | unless `-SkipDebloat` |
| `fusion.android.tv.demo` | Retail demo mode | unless `-SkipDebloat` |
| `com.google.android.feedback` | Google feedback / crash report sender | unless `-SkipDebloat` |
| `com.google.android.tvrecommendations` | Recommendation service of the Google TV home screen | unless `-SkipDebloat` |
| `com.google.android.play.games` | Google Play Games | unless `-SkipDebloat` |
| `com.google.android.videos` | Google TV / Play Movies | unless `-SkipDebloat` |
| `com.ted.android.tv` | TED | unless `-SkipDebloat` |
| `com.android.printspooler` | Print service | unless `-SkipDebloat` |
| `com.google.android.katniss` | Google Assistant / voice search | unless `-SkipDebloat` or `-KeepAssistant` |
| `com.google.android.tvlauncher` | Google TV home screen | always, but only after Clean Home was shown to start; re-enabled automatically if HOME does not open Clean Home. `restore.ps1` re-enables it **first**. |

## Settings

| Setting | New value | Condition | Restore with state.json | Restore without state.json |
|---|---|---|---|---|
| `global window_animation_scale` | `0.5` | always | previous value | `1.0` |
| `global transition_animation_scale` | `0.5` | always | previous value | `1.0` |
| `global animator_duration_scale` | `0.5` | always | previous value | `1.0` |
| `global send_action_app_error` | `0` (no "send crash report" dialogs) | always | previous value | `1` |
| `secure screensaver_components` | `tv.cleanhome.launcher/.PhotoDreamService` | always | previous value | `com.google.android.backdrop/.Backdrop` |
| `system power_key_definition` | `1` (power key shuts down instead of standby; XGIMI-specific) | `-FullPowerOff` | previous value (deleted if it was unset) | deleted |
| `global private_dns_specifier` | `dns.quad9.net` | `-SecureDns quad9` | previous value (deleted if it was unset) | deleted |
| `global private_dns_mode` | `hostname` | `-SecureDns quad9` | previous value (deleted if it was unset) | deleted |

If name resolution fails after switching to encrypted DNS (checked with `ping example.com` on the
device), `setup.ps1` puts both DNS settings back immediately and removes them from `state.json`.

## App operations

| Package | Operation | New mode | Condition | Restore |
|---|---|---|---|---|
| `com.xgimi.filemanager` | `REQUEST_INSTALL_PACKAGES` | `deny` (no APK installs from USB sticks via the file manager) | if the package exists | previous mode from `state.json`, else `allow` |

## Clean Home launcher

| Change | Restore |
|---|---|
| `adb install -r` of the APK | `adb uninstall tv.cleanhome.launcher` (always, also without `state.json`) |
| `pm grant` of `READ_EXTERNAL_STORAGE` and `READ_TV_LISTINGS` | removed with the app |
| `mkdir -p /sdcard/Pictures/CleanHome` | **kept**, including your photos; delete it on the TV or with `adb shell rm -r /sdcard/Pictures/CleanHome` |
| `cmd package set-home-activity tv.cleanhome.launcher/.HomeActivity` | cleared by the uninstall; the Google launcher is enabled again |
| optional `-WeatherCity`: `am start ... --es weather_city <city>` | stored inside the app, removed with the app |

## On this PC

| Path | Content | Remove |
|---|---|---|
| `%LOCALAPPDATA%\CleanHomeKit\config.json` | projector IP address, photo folder | delete the folder |
| `%LOCALAPPDATA%\CleanHomeKit\photo-cache\` | resized copies of your photos | delete the folder |
| `scripts\backup-<timestamp>\` | backups and `state.json` (see above) | delete after you no longer need `restore.ps1` |
