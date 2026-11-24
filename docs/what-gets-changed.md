# What gets changed

Everything `scripts/cleanhome.py setup` does to the projector, and how `scripts/cleanhome.py restore`
undoes it.

Nothing is uninstalled except the Clean Home launcher itself (on restore). Packages are only
*disabled* for user 0, which keeps them on the system partition and makes them come back with
`pm enable` or a factory reset.

## How restore knows the previous values

Before changing anything, `setup` creates `scripts/backup-<timestamp>/` with:

| File | Content |
|---|---|
| `packages-system.txt`, `packages-user.txt`, `packages-disabled.txt` | `pm list packages` output before the run |
| `settings-global.txt`, `settings-secure.txt`, `settings-system.txt` | `settings list` output before the run |
| `state.json` | every change made in this run, with the previous value; written after each step |

`restore` undoes every `state.json` recorded for the connected device (matched by serial
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
| `com.xgimi.bugreportsender` | XGIMI bug report uploader | unless `--skip-debloat` |
| `com.xgimi.doubanfm` | Douban FM music app | unless `--skip-debloat` |
| `fusion.android.tv.demo` | Retail demo mode | unless `--skip-debloat` |
| `com.google.android.feedback` | Google feedback / crash report sender | unless `--skip-debloat` |
| `com.google.android.tvrecommendations` | Recommendation service of the Google TV home screen | unless `--skip-debloat` |
| `com.google.android.play.games` | Google Play Games | unless `--skip-debloat` |
| `com.google.android.videos` | Google TV / Play Movies | unless `--skip-debloat` |
| `com.ted.android.tv` | TED | unless `--skip-debloat` |
| `com.android.printspooler` | Print service | unless `--skip-debloat` |
| `com.google.android.katniss` | Google Assistant / voice search | unless `--skip-debloat` or `--keep-assistant` |
| `com.google.android.tvlauncher` | Google TV home screen | always, but only after Clean Home was shown to start; re-enabled automatically if HOME does not open Clean Home. `restore` re-enables it **first**. |

## Settings

| Setting | New value | Condition | Restore with state.json | Restore without state.json |
|---|---|---|---|---|
| `global window_animation_scale` | `0.5` | always | previous value | `1.0` |
| `global transition_animation_scale` | `0.5` | always | previous value | `1.0` |
| `global animator_duration_scale` | `0.5` | always | previous value | `1.0` |
| `global send_action_app_error` | `0` (no "send crash report" dialogs) | always | previous value | `1` |
| `secure screensaver_components` | `tv.cleanhome.launcher/.PhotoDreamService` | always | previous value | `com.google.android.backdrop/.Backdrop` |
| `system power_key_definition` | `1` (power key shuts down instead of standby; XGIMI-specific) | `--full-power-off` | previous value (deleted if it was unset) | deleted |
| `global private_dns_specifier` | `dns.quad9.net` | `--secure-dns quad9` | previous value (deleted if it was unset) | deleted |
| `global private_dns_mode` | `hostname` | `--secure-dns quad9` | previous value (deleted if it was unset) | deleted |

If name resolution fails after switching to encrypted DNS (checked with `ping example.com` on the
device), `setup` puts both DNS settings back immediately and removes them from `state.json`.

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
| optional `--weather-city`: `am start ... --es weather_city <city>` | stored inside the app, removed with the app |

## On your computer

| Content | Windows | macOS | Linux |
|---|---|---|---|
| `config.json`: projector IP address, photo folder | `%LOCALAPPDATA%\CleanHomeKit\` | `~/Library/Application Support/CleanHomeKit/` | `~/.config/cleanhomekit/` |
| `photo-cache/`: resized copies of your photos | `%LOCALAPPDATA%\CleanHomeKit\` | `~/Library/Caches/CleanHomeKit/` | `~/.cache/cleanhomekit/` |

On Linux, `XDG_CONFIG_HOME` and `XDG_CACHE_HOME` are respected. Delete these folders to remove the
data. The backups and `state.json` (see above) are in `scripts/backup-<timestamp>/`; delete them
once you no longer need `restore`.
