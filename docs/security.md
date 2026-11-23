# Security notes

## Network debugging is an open admin port

The scripts talk to the projector over adb on TCP port 5555 ("network debugging"). While it is
enabled, any device on your network that the projector has authorized can install apps, read
files and change settings, and new devices only need someone to press "Allow" on the projector
screen once.

- Turn network debugging **off** again (Developer options) when you are done with setup or photo
  syncing. Turn it on only for the time you run a script.
- Do not run the scripts on networks you do not control (hotel, office, guest Wi-Fi).
- If you no longer use this PC for it, revoke the authorization on the projector: Developer
  options > "Revoke USB debugging authorizations".

## What the app is allowed to do, and why

| Permission | Why |
|---|---|
| `READ_EXTERNAL_STORAGE` | Read the photos in `/sdcard/Pictures/CleanHome` for the wallpaper and the screensaver. Granted by `setup.ps1`. |
| `READ_TV_LISTINGS`, `com.android.providers.tv.permission.READ_EPG_DATA` | Read the "watch next" and preview programs that installed apps publish, for the recommendations row. Granted by `setup.ps1`. |
| `QUERY_ALL_PACKAGES` | A launcher must see every launchable app. |
| `INTERNET` | Load poster images of the recommendations; load the weather if a location is set. |
| `ACCESS_NETWORK_STATE` | Show whether Wi-Fi is connected in the header. |

The app targets API level 29 on purpose (direct file access to the photo folder). It is meant to be
sideloaded, not published on Google Play.

## Network requests the app makes

Only these, and nothing else:

1. **Poster images** of the recommendations row. The URLs come from the installed apps' own preview
   data (the TV provider), so the requests go to the servers of those apps (for example YouTube or
   Netflix image hosts), the same as with the stock home screen.
2. **Open-Meteo**, only if you set a weather location:
   - `geocoding-api.open-meteo.com` once when you set the location (sends the city name and the
     device language);
   - `api.open-meteo.com` at most every 30 minutes while the home screen is visible (sends the
     latitude/longitude of that city, rounded to 4 decimals).

No location set means no weather request at all. There are no analytics, no crash reporting and
no telemetry of any kind.

## The weather intent extra

`HomeActivity` accepts a `weather_city` string extra so that `setup.ps1 -WeatherCity` can preset the
location. As with any launcher activity, other apps on the device can start it too, so an installed
app could change the weather city. The only effect is a different city in the weather line and the
corresponding Open-Meteo request; no other data is accepted from intents.

## The scripts

- They never uninstall system packages; they disable them for user 0 (see
  [what-gets-changed.md](what-gets-changed.md)).
- They write backups with your device's package list and serial number into `scripts/backup-*/`.
  These are gitignored; keep them private.
- They only connect to the IP address you pass (or the one remembered in
  `%LOCALAPPDATA%\CleanHomeKit\config.json`). `find-projector.ps1` sends one HTTP request per
  address of your local /24 network to port 8008 (the Google Cast setup endpoint) and checks port
  5555 on the devices that answer.
