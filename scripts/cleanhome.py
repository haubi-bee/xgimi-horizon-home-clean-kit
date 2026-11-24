#!/usr/bin/env python3
"""Clean Home kit: find, set up, feed and restore an XGIMI Horizon Pro from Windows, macOS or Linux.

    cleanhome.py find                       find the projector in the local network
    cleanhome.py setup --ip 192.168.1.50    debloat, install the launcher, make it the home screen
    cleanhome.py sync-photos --folder DIR   copy a photo folder to the projector
    cleanhome.py restore                    undo everything setup changed

Needs Python 3.8 or newer and adb. Only "sync-photos" needs an extra package (Pillow).
Run "cleanhome.py <command> --help" for the options of a command.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import datetime
import hashlib
import http.client
import ipaddress
import json
import os
import re
import shlex
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent

LAUNCHER_PACKAGE = 'tv.cleanhome.launcher'
HOME_COMPONENT = LAUNCHER_PACKAGE + '/.HomeActivity'
DREAM_COMPONENT = LAUNCHER_PACKAGE + '/.PhotoDreamService'
GOOGLE_LAUNCHER = 'com.google.android.tvlauncher'
FILE_MANAGER = 'com.xgimi.filemanager'
DEFAULT_SCREENSAVER = 'com.google.android.backdrop/.Backdrop'
PHOTO_DIR = '/sdcard/Pictures/CleanHome'
SUPPORTED_MODEL = 'XK03H'
ADB_PORT = 5555

# The published launcher build that "setup" downloads when no APK is given or built locally.
# The checksum pins exactly this file, so a changed download is refused.
APK_VERSION = '0.1.0'
APK_SHA256 = '5df1584ff275ec60a021f09e39dc3d777300f5aecaa82689bcc188c2eb21ead8'
APK_URL = ('https://github.com/haubi-bee/xgimi-horizon-home-clean-kit/releases/download/'
           f'v{APK_VERSION}/CleanHome.apk')
CAST_PORT = 8008

# Disabled with "pm disable-user --user 0" (reversible with "pm enable"), only if installed.
BLOAT_PACKAGES = [
    'com.xgimi.bugreportsender',             # XGIMI bug report uploader
    'com.xgimi.doubanfm',                    # Douban FM music app (China)
    'fusion.android.tv.demo',                # retail demo mode
    'com.google.android.feedback',           # Google feedback / crash reports
    'com.google.android.tvrecommendations',  # recommendation service of the Google TV home screen
    'com.google.android.play.games',         # Google Play Games
    'com.google.android.videos',             # Google TV / Play Movies
    'com.ted.android.tv',                    # TED
    'com.android.printspooler',              # print service (no printers on a projector)
]
ASSISTANT_PACKAGE = 'com.google.android.katniss'  # Google Assistant / voice search (microphone)

MAX_EDGE = 2560
JPEG_QUALITY = 88
PHOTO_EXTENSIONS = {'.jpg', '.jpeg', '.png', '.bmp', '.tif', '.tiff', '.webp'}
UNSUPPORTED_EXTENSIONS = {'.heic', '.heif', '.avif', '.raw', '.cr2', '.nef', '.arw'}
REMOTE_PHOTO_NAME = re.compile(r'^[0-9a-f]{16}\.jpg$')

PYTHON = 'py' if sys.platform == 'win32' else 'python3'

_sleep = time.sleep


class KitError(Exception):
    """An error that is reported to the user without a traceback."""


# ---------------------------------------------------------------- output

def _color(text: str, code: str, stream) -> str:
    if stream.isatty() and not os.environ.get('NO_COLOR'):
        return f'\033[{code}m{text}\033[0m'
    return text


def step(text: str) -> None:
    print()
    print(_color(f'== {text}', '36', sys.stdout))


def warn(text: str) -> None:
    print(_color(f'WARNING: {text}', '33', sys.stderr), file=sys.stderr)


def notice(text: str) -> None:
    print(_color(text, '33', sys.stdout))


def success(text: str) -> None:
    print(_color(text, '32', sys.stdout))


# ---------------------------------------------------------------- local files

def kit_dirs() -> tuple[Path, Path]:
    """Returns (settings folder, photo cache folder) for this operating system."""
    home = Path.home()
    if sys.platform == 'win32':
        base = Path(os.environ.get('LOCALAPPDATA') or home / 'AppData' / 'Local') / 'CleanHomeKit'
        return base, base / 'photo-cache'
    if sys.platform == 'darwin':
        return (home / 'Library' / 'Application Support' / 'CleanHomeKit',
                home / 'Library' / 'Caches' / 'CleanHomeKit' / 'photo-cache')
    config = Path(os.environ.get('XDG_CONFIG_HOME') or home / '.config') / 'cleanhomekit'
    cache = Path(os.environ.get('XDG_CACHE_HOME') or home / '.cache') / 'cleanhomekit' / 'photo-cache'
    return config, cache


KIT_DIR, CACHE_DIR = kit_dirs()


def real_path(path: Path) -> Path:
    """The path as other programs (adb) see it. Python from the Microsoft Store redirects writes below
    AppData into a private folder; only Python itself finds such files under the path it was given."""
    return Path(os.path.realpath(path))


def read_json(path: Path):
    # utf-8-sig also reads files written by the earlier PowerShell scripts (UTF-8 with BOM).
    return json.loads(path.read_text(encoding='utf-8-sig'))


def write_json(path: Path, data) -> None:
    path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


def load_config() -> dict:
    config = {'ip': None, 'photoFolder': None}
    path = KIT_DIR / 'config.json'
    if path.exists():
        try:
            saved = read_json(path)
            for name in config:
                if saved.get(name):
                    config[name] = str(saved[name])
        except (OSError, ValueError, AttributeError):
            warn(f'Ignoring unreadable config file {path}')
    return config


def save_config(config: dict) -> None:
    KIT_DIR.mkdir(parents=True, exist_ok=True)
    write_json(KIT_DIR / 'config.json', config)


def validate_ip(value: str) -> str:
    try:
        return str(ipaddress.IPv4Address(value.strip()))
    except ValueError:
        raise KitError(f'Not an IPv4 address: {value}') from None


def resolve_device_ip(value: str | None) -> str:
    """The given address, else the remembered one. The result is remembered for the next run."""
    config = load_config()
    value = value or config['ip']
    if not value:
        raise KitError(f'No projector address known. Pass --ip <address> (find it with "{PYTHON} cleanhome.py find").')
    config['ip'] = validate_ip(value)
    save_config(config)
    return config['ip']


def default_photo_folder() -> Path:
    pictures = None
    if sys.platform == 'win32':
        pictures = _windows_pictures_folder()
    elif sys.platform != 'darwin' and shutil.which('xdg-user-dir'):
        try:
            answer = subprocess.run(['xdg-user-dir', 'PICTURES'], stdout=subprocess.PIPE,
                                    stderr=subprocess.DEVNULL, encoding='utf-8', errors='replace').stdout.strip()
            # xdg-user-dir answers with the home folder itself when no pictures folder is configured.
            if answer and Path(answer) != Path.home():
                pictures = Path(answer)
        except OSError:
            pass
    return (pictures or Path.home() / 'Pictures') / 'CleanHome'


def _windows_pictures_folder() -> Path | None:
    """The real Pictures folder, which can be redirected (for example into OneDrive)."""
    try:
        import ctypes
        import uuid
        folder_id = (ctypes.c_ubyte * 16).from_buffer_copy(
            uuid.UUID('33E28130-4E1E-4676-835A-98395C3BC3BB').bytes_le)
        pointer = ctypes.c_void_p()
        if ctypes.windll.shell32.SHGetKnownFolderPath(ctypes.byref(folder_id), 0, None, ctypes.byref(pointer)) != 0:
            return None
        try:
            return Path(ctypes.wstring_at(pointer.value))
        finally:
            ctypes.windll.ole32.CoTaskMemFree.argtypes = [ctypes.c_void_p]
            ctypes.windll.ole32.CoTaskMemFree(pointer)
    except (OSError, AttributeError, ValueError):
        return None


# ---------------------------------------------------------------- adb

def adb_install_hint() -> str:
    if sys.platform == 'win32':
        return 'Install it with "winget install Google.PlatformTools" and open a new terminal window.'
    if sys.platform == 'darwin':
        return 'Install it with "brew install android-platform-tools".'
    return ('Install it with your package manager, e.g. "sudo apt install adb" (Debian/Ubuntu), '
            '"sudo dnf install android-tools" (Fedora) or "sudo pacman -S android-tools" (Arch).')


def find_adb(explicit: str | None = None) -> str:
    if explicit:
        found = shutil.which(explicit) or (explicit if Path(explicit).is_file() else None)
        if not found:
            raise KitError(f'adb not found at {explicit}')
        return str(found)
    found = shutil.which('adb')
    if found:
        return found
    name = 'adb.exe' if sys.platform == 'win32' else 'adb'
    home = Path.home()
    candidates = [Path(os.environ[variable]) / 'platform-tools' / name
                  for variable in ('ANDROID_HOME', 'ANDROID_SDK_ROOT') if os.environ.get(variable)]
    if sys.platform == 'win32':
        local = Path(os.environ.get('LOCALAPPDATA') or home / 'AppData' / 'Local')
        candidates.append(local / 'Android' / 'Sdk' / 'platform-tools' / name)
        candidates.extend(sorted((local / 'Microsoft' / 'WinGet' / 'Packages').glob(f'Google.PlatformTools*/**/{name}')))
    elif sys.platform == 'darwin':
        candidates.append(home / 'Library' / 'Android' / 'sdk' / 'platform-tools' / name)
    else:
        candidates.append(home / 'Android' / 'Sdk' / 'platform-tools' / name)
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    raise KitError('adb not found. ' + adb_install_hint())


class Adb:
    """Runs adb. Output is stdout and stderr together, as lines; success is the exit code."""

    def __init__(self, executable: str):
        self.executable = executable
        self.serial = ''

    def run(self, arguments: list[str], allow_failure: bool = False) -> list[str]:
        try:
            result = subprocess.run([self.executable, *arguments], stdin=subprocess.DEVNULL,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    encoding='utf-8', errors='replace')
        except OSError as error:
            raise KitError(f'Could not run {self.executable}: {error}') from None
        lines = result.stdout.splitlines()
        if result.returncode != 0 and not allow_failure:
            raise KitError(f"adb {' '.join(arguments)} failed (exit code {result.returncode}): {' '.join(lines)}")
        return lines

    def device(self, *arguments: str, allow_failure: bool = False) -> list[str]:
        return self.run(['-s', self.serial, *arguments], allow_failure)

    def shell(self, command: str, allow_failure: bool = False) -> list[str]:
        return self.device('shell', command, allow_failure=allow_failure)

    def shell_text(self, command: str) -> str:
        return '\n'.join(self.shell(command, allow_failure=True)).strip()

    def connect(self, address: str) -> None:
        self.serial = f'{address}:{ADB_PORT}'
        self.run(['connect', self.serial], allow_failure=True)
        state = ' '.join(self.device('get-state', allow_failure=True)).strip()
        if state != 'device':
            raise KitError(f"Projector at {self.serial} is not ready ({state}). Is it on, is network debugging "
                           "enabled, and was the 'Allow debugging?' prompt on the projector confirmed?")

    def packages(self, flags: str = '') -> set[str]:
        names = set()
        for line in self.shell(f'pm list packages {flags}'.strip()):
            name = re.sub(r'^package:', '', line).strip()
            if name:
                names.add(name)
        return names

    def home_packages(self) -> set[str]:
        """Packages of the installed, enabled home screen apps (without the system's placeholder)."""
        lines = self.shell('cmd package query-activities --brief -a android.intent.action.MAIN '
                           '-c android.intent.category.HOME', allow_failure=True)
        names = set()
        for line in lines:
            match = re.match(r'^\s+([\w.]+)/[\w.$]+$', line)
            if match and 'FallbackHome' not in line:
                names.add(match.group(1))
        return names

    def get_setting(self, namespace: str, key: str) -> str | None:
        value = self.shell_text(f'settings get {namespace} {key}')
        return None if value in ('', 'null') else value

    def put_setting(self, namespace: str, key: str, value: str | None, allow_failure: bool = False) -> None:
        if value is None or value == '':
            self.shell(f'settings delete {namespace} {key}', allow_failure)
        else:
            self.shell(f'settings put {namespace} {key} {shlex.quote(value)}', allow_failure)


# ---------------------------------------------------------------- setup

class SetupState:
    """state.json of one setup run: every change with its previous value, saved after each step."""

    def __init__(self, path: Path, data: dict):
        self.path = path
        self.data = data
        self.save()

    def save(self) -> None:
        write_json(self.path, self.data)

    def _setting(self, namespace: str, key: str) -> dict | None:
        for entry in self.data['settings']:
            if entry['namespace'] == namespace and entry['key'] == key:
                return entry
        return None

    def track_setting(self, adb: Adb, namespace: str, key: str, value: str) -> None:
        """Changes a setting and records the previous value (the first previous value wins)."""
        previous = adb.get_setting(namespace, key)
        adb.put_setting(namespace, key, value)
        entry = self._setting(namespace, key)
        if entry:
            entry['value'] = value
        else:
            self.data['settings'].append({'namespace': namespace, 'key': key, 'previous': previous, 'value': value})
        self.save()
        print(f"  {namespace} {key} = {value} (was: {'unset' if previous is None else previous})")

    def undo_setting(self, adb: Adb, namespace: str, key: str) -> None:
        entry = self._setting(namespace, key)
        if not entry:
            return
        adb.put_setting(namespace, key, entry['previous'])
        self.data['settings'].remove(entry)
        self.save()


def resolve_apk(path: str | None) -> Path:
    if path:
        apk = Path(path).expanduser()
        if not apk.is_file():
            raise KitError(f'APK not found: {path}')
        return apk.resolve()
    outputs = SCRIPT_DIR.parent / 'launcher' / 'app' / 'build' / 'outputs' / 'apk'
    candidates = [SCRIPT_DIR / 'CleanHome.apk',
                  outputs / 'release' / 'app-release.apk',
                  outputs / 'debug' / 'app-debug.apk']
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    return download_apk()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def download_apk() -> Path:
    """The published launcher APK, downloaded once into the settings folder and checked."""
    target = KIT_DIR / f'CleanHome-{APK_VERSION}.apk'
    if target.is_file() and file_sha256(target) == APK_SHA256:
        return real_path(target)
    step(f'Downloading Clean Home {APK_VERSION}')
    print(f'  {APK_URL}')
    KIT_DIR.mkdir(parents=True, exist_ok=True)
    partial = target.with_suffix('.part')
    try:
        with urllib.request.urlopen(APK_URL, timeout=30) as response, partial.open('wb') as stream:
            shutil.copyfileobj(response, stream)
    except (OSError, urllib.error.URLError) as error:
        partial.unlink(missing_ok=True)
        raise KitError(f'Could not download the launcher ({error}). Download {APK_URL} yourself and pass it '
                       'with --apk <path>.') from None
    found = file_sha256(partial)
    if found != APK_SHA256:
        partial.unlink()
        raise KitError(f'The downloaded launcher does not match the expected checksum (got {found}). '
                       'Nothing was installed. Update this repository and try again.')
    os.replace(partial, target)
    target = real_path(target)
    print(f'  Saved and verified: {target}')
    return target


def run_setup(adb: Adb, options, backup_root: Path) -> Path:
    """Performs the setup and returns the path of the written state.json."""
    apk = resolve_apk(options.apk)
    ip = resolve_device_ip(options.ip)

    step(f'Connecting to {ip}')
    adb.connect(ip)
    model = adb.shell_text('getprop ro.product.model')
    release = adb.shell_text('getprop ro.build.version.release')
    build = adb.shell_text('getprop ro.build.display.id')
    device_serial = adb.shell_text('getprop ro.serialno')
    print(f'  Connected: {model}, Android {release}, build {build}')
    if model != SUPPORTED_MODEL:
        if not options.force:
            raise KitError(f"This script is only tested on the XGIMI Horizon Pro ({SUPPORTED_MODEL}); found "
                           f"'{model}'. Nothing was changed. Use --force to run anyway (untested).")
        warn(f"Model '{model}' is not the tested {SUPPORTED_MODEL}. Continuing because of --force.")

    # 1) backup
    step('Backing up the current state')
    backup_dir = backup_root / ('backup-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
    backup_dir.mkdir(parents=True, exist_ok=True)

    def dump(name: str, command: str) -> None:
        (backup_dir / name).write_text('\n'.join(adb.shell(command)) + '\n', encoding='utf-8')

    dump('packages-system.txt', 'pm list packages -f -s')
    dump('packages-user.txt', 'pm list packages -f -3')
    dump('packages-disabled.txt', 'pm list packages -d')
    for namespace in ('global', 'secure', 'system'):
        dump(f'settings-{namespace}.txt', f'settings list {namespace}')

    installed = adb.packages()
    disabled = adb.packages('-d')
    state = SetupState(backup_dir / 'state.json', {
        'formatVersion': 1,
        'createdUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'device': {'serial': device_serial, 'model': model, 'androidRelease': release, 'build': build},
        'disabledPackages': [],
        'settings': [],
        'appops': [],
        'launcherWasInstalled': LAUNCHER_PACKAGE in installed,
        'launcherInstalled': False,
        'googleLauncherDisabled': False,
    })
    print(f'  Backup: {backup_dir}')

    # 2) debloat
    if options.skip_debloat:
        step('Skipping package changes (--skip-debloat)')
    else:
        step('Disabling bloat and telemetry packages (reversible)')
        to_disable = list(BLOAT_PACKAGES)
        if not options.keep_assistant:
            to_disable.append(ASSISTANT_PACKAGE)
        for package in to_disable:
            if package not in installed:
                continue
            if package in disabled:
                print(f'  {package} already disabled')
                continue
            result = ' '.join(adb.shell(f'pm disable-user --user 0 {package}', allow_failure=True))
            if 'disabled' in result:
                state.data['disabledPackages'].append(package)
                state.save()
                print(f'  {package} disabled')
            else:
                warn(f'{package} could not be disabled: {result}')

    step('Faster animations, no crash report dialogs')
    state.track_setting(adb, 'global', 'window_animation_scale', '0.5')
    state.track_setting(adb, 'global', 'transition_animation_scale', '0.5')
    state.track_setting(adb, 'global', 'animator_duration_scale', '0.5')
    state.track_setting(adb, 'global', 'send_action_app_error', '0')

    # 3) install the launcher
    step(f'Installing {apk}')
    install = ' '.join(adb.device('install', '-r', str(apk), allow_failure=True))
    if 'Success' not in install:
        raise KitError(f'Installation failed: {install}')
    state.data['launcherInstalled'] = True
    state.save()
    adb.shell(f'pm grant {LAUNCHER_PACKAGE} android.permission.READ_EXTERNAL_STORAGE')
    adb.shell(f'pm grant {LAUNCHER_PACKAGE} android.permission.READ_TV_LISTINGS')
    adb.shell(f'mkdir -p {PHOTO_DIR}')
    print(f'  Installed, permissions granted, photo folder {PHOTO_DIR} ready.')

    # 4) home screen
    step('Making Clean Home the home screen')
    # The Google launcher has a higher HOME priority while it is enabled, so it is only disabled once
    # Clean Home has been proven to start.
    adb.shell(f'am start -n {HOME_COMPONENT}')
    running = False
    for _ in range(10):
        _sleep(1)
        if adb.shell_text(f'pidof {LAUNCHER_PACKAGE}'):
            running = True
            break
    if not running:
        raise KitError('Clean Home does not start. The Google launcher stays active; run the "restore" '
                       'command to undo the other changes.')
    home_result = ' '.join(adb.shell(f'cmd package set-home-activity {HOME_COMPONENT}', allow_failure=True))
    print(f'  set-home-activity: {home_result}')
    installed = adb.packages()
    disabled = adb.packages('-d')
    if GOOGLE_LAUNCHER in installed and GOOGLE_LAUNCHER not in disabled:
        adb.shell(f'pm disable-user --user 0 {GOOGLE_LAUNCHER}')
        state.data['googleLauncherDisabled'] = True
        state.save()
        print(f'  {GOOGLE_LAUNCHER} disabled')
    _sleep(3)
    adb.shell('input keyevent KEYCODE_HOME')
    _sleep(5)
    resumed = adb.shell_text("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -n 1")
    if LAUNCHER_PACKAGE not in resumed:
        if state.data['googleLauncherDisabled']:
            adb.shell(f'pm enable {GOOGLE_LAUNCHER}')
            state.data['googleLauncherDisabled'] = False
            state.save()
        adb.shell('input keyevent KEYCODE_HOME')
        raise KitError(f'HOME does not open Clean Home ({resumed}). The Google launcher was re-enabled; run the '
                       '"restore" command to undo the other changes.')
    print('  HOME opens Clean Home.')

    # 5) screensaver
    step('Photo screensaver')
    state.track_setting(adb, 'secure', 'screensaver_components', DREAM_COMPONENT)

    # 6) optional power key
    if options.full_power_off:
        step('Power key: full shutdown instead of standby')
        state.track_setting(adb, 'system', 'power_key_definition', '1')

    # 7) optional encrypted DNS
    if options.secure_dns == 'quad9':
        step('Encrypted DNS (dns.quad9.net)')
        state.track_setting(adb, 'global', 'private_dns_specifier', 'dns.quad9.net')
        state.track_setting(adb, 'global', 'private_dns_mode', 'hostname')
        resolved = False
        for _ in range(4):
            _sleep(3)
            # "PING host (address)" means the name was resolved, even if ICMP itself is blocked.
            if re.search(r'PING example\.com \(', adb.shell_text('ping -c 1 -W 3 example.com 2>&1')):
                resolved = True
                break
        if resolved:
            print('  Name resolution works.')
        else:
            state.undo_setting(adb, 'global', 'private_dns_mode')
            state.undo_setting(adb, 'global', 'private_dns_specifier')
            warn('Name resolution failed with encrypted DNS; the previous DNS settings were restored.')

    # 8) no sideloading from the file manager
    if FILE_MANAGER in installed:
        step('Revoking APK installs from the XGIMI file manager')
        current = adb.shell_text(f'appops get {FILE_MANAGER} REQUEST_INSTALL_PACKAGES')
        match = re.search(r'REQUEST_INSTALL_PACKAGES:\s*(\w+)', current)
        previous_mode = match.group(1) if match else 'default'
        adb.shell(f'appops set {FILE_MANAGER} REQUEST_INSTALL_PACKAGES deny')
        state.data['appops'].append({'package': FILE_MANAGER, 'op': 'REQUEST_INSTALL_PACKAGES',
                                     'previous': previous_mode, 'value': 'deny'})
        state.save()
        print(f'  REQUEST_INSTALL_PACKAGES = deny (was: {previous_mode})')

    # 9) optional weather location
    if options.weather_city is not None:
        city = options.weather_city.strip()
        step(f'Weather location: {city}')
        adb.shell(f'am start -n {HOME_COMPONENT} --es weather_city {shlex.quote(city)}')
        print('  Sent to the launcher; it looks the place up itself (a message appears on the TV).')

    return state.path


def cmd_setup(options) -> None:
    print()
    notice('WARNING: do not run this while someone is watching or playing on the projector.')
    notice('         Installing can show a Play Protect dialog, and the script presses HOME on the device.')
    state_path = run_setup(Adb(find_adb(options.adb)), options, SCRIPT_DIR)
    print()
    success('Done. Clean Home is the home screen.')
    print(f'  Changes recorded in: {state_path}')
    print(f'  Copy photos:          {PYTHON} cleanhome.py sync-photos --folder "<folder with photos>"')
    print(f'  Undo everything:      {PYTHON} cleanhome.py restore')
    print()
    notice('IMPORTANT: turn network debugging off again on the projector (Developer options).')
    notice('           While it is on, anyone on your network can control the device.')


# ---------------------------------------------------------------- restore

def find_state_files(backup_root: Path, backup_dir: str | None, device_serial: str) -> list[Path]:
    if backup_dir:
        path = Path(backup_dir).expanduser() / 'state.json'
        if path.is_file():
            return [path]
        warn(f'No state.json in {backup_dir}; restoring defaults instead.')
        return []
    matching = []
    # Newest first, so the oldest recorded values (the original state) are written last and win.
    for folder in sorted(backup_root.glob('backup-*'), key=lambda item: item.name, reverse=True):
        path = folder / 'state.json'
        if not path.is_file():
            continue
        recorded = (read_json(path).get('device') or {}).get('serial')
        if recorded and device_serial and recorded != device_serial:
            print(f'  Skipping {path} (recorded for another device)')
            continue
        matching.append(path)
    return matching


def run_restore(adb: Adb, options, backup_root: Path) -> None:
    ip = resolve_device_ip(options.ip)

    step(f'Connecting to {ip}')
    adb.connect(ip)
    device_serial = adb.shell_text('getprop ro.serialno')
    installed = adb.packages()

    def enable_package(package: str) -> None:
        if package in installed:
            result = ' '.join(adb.shell(f'pm enable {package}', allow_failure=True))
            print(f'  {package} -> {result}')

    def put_setting(namespace: str, key: str, value: str | None) -> None:
        adb.put_setting(namespace, key, value, allow_failure=True)
        if value is None or value == '':
            print(f'  {namespace} {key} -> deleted (system default)')
        else:
            print(f'  {namespace} {key} -> {value}')

    def set_appop(package: str, op: str, mode: str) -> None:
        if package in installed:
            adb.shell(f'appops set {package} {op} {mode}', allow_failure=True)
            print(f'  {package} {op} -> {mode}')

    state_files = find_state_files(backup_root, options.backup_dir, device_serial)
    states = [read_json(path) for path in state_files]

    # HOME must have a target once Clean Home is gone. The Google launcher stays as it was only if
    # setup did not disable it (it was already off) and another home screen app is installed.
    other_homes = adb.home_packages() - {LAUNCHER_PACKAGE, GOOGLE_LAUNCHER}
    if states and other_homes and not any(state.get('googleLauncherDisabled') for state in states):
        step('Home screen')
        print(f"  The Google launcher was already disabled before the setup and stays that way; "
              f"HOME goes to: {', '.join(sorted(other_homes))}")
    else:
        step('Re-enabling the Google launcher first')
        enable_package(GOOGLE_LAUNCHER)

    if not state_files:
        step('No recorded setup found: restoring factory defaults')
        for package in BLOAT_PACKAGES + [ASSISTANT_PACKAGE]:
            enable_package(package)
        put_setting('global', 'window_animation_scale', '1.0')
        put_setting('global', 'transition_animation_scale', '1.0')
        put_setting('global', 'animator_duration_scale', '1.0')
        put_setting('global', 'send_action_app_error', '1')
        put_setting('global', 'private_dns_specifier', None)
        put_setting('global', 'private_dns_mode', None)
        put_setting('system', 'power_key_definition', None)
        put_setting('secure', 'screensaver_components', DEFAULT_SCREENSAVER)
        set_appop(FILE_MANAGER, 'REQUEST_INSTALL_PACKAGES', 'allow')
    for path, state in zip(state_files, states):
        step(f'Undoing {path}')
        if state.get('googleLauncherDisabled'):
            enable_package(GOOGLE_LAUNCHER)
        for package in state.get('disabledPackages') or []:
            enable_package(package)
        for entry in reversed(state.get('settings') or []):
            put_setting(entry['namespace'], entry['key'], entry.get('previous'))
        for op in state.get('appops') or []:
            set_appop(op['package'], op['op'], op['previous'])

    step(f'Removing Clean Home (photos in {PHOTO_DIR} stay on the device)')
    if LAUNCHER_PACKAGE in installed:
        result = ' '.join(adb.device('uninstall', LAUNCHER_PACKAGE, allow_failure=True))
        print(f'  uninstall: {result}')
    else:
        print('  Not installed.')

    adb.shell('input keyevent KEYCODE_HOME', allow_failure=True)


def cmd_restore(options) -> None:
    run_restore(Adb(find_adb(options.adb)), options, SCRIPT_DIR)
    print()
    success('Done. The projector is back to its previous home screen and settings.')
    notice('IMPORTANT: turn network debugging off again on the projector (Developer options).')


# ---------------------------------------------------------------- sync-photos

def load_pillow():
    try:
        from PIL import Image, ImageOps
    except ImportError:
        if sys.platform == 'win32':
            hint = '"py -m pip install pillow"'
        elif sys.platform == 'darwin':
            hint = '"python3 -m pip install --user pillow"'
        else:
            hint = ('your package manager, e.g. "sudo apt install python3-pil" (Debian/Ubuntu), '
                    '"sudo dnf install python3-pillow" (Fedora) or "sudo pacman -S python-pillow" (Arch)')
        raise KitError(f'Resizing photos needs the Python package Pillow. Install it with {hint}.') from None
    return Image, ImageOps


def stable_name(relative_path: str, size: int, modified_ns: int) -> str:
    """File name on the device for one source photo: changes when the photo is replaced or edited."""
    key = f'{relative_path}|{size}|{modified_ns}'
    return hashlib.sha1(key.encode('utf-8')).hexdigest()[:16] + '.jpg'


def convert_photo(source: Path, target: Path) -> None:
    """Writes an upright JPEG with a long edge of at most MAX_EDGE pixels."""
    Image, ImageOps = load_pillow()
    with Image.open(source) as opened:
        image = ImageOps.exif_transpose(opened)  # portrait photos upright
        if image.mode in ('RGBA', 'LA', 'PA') or 'transparency' in image.info:
            rgba = image.convert('RGBA')
            image = Image.new('RGB', rgba.size, (0, 0, 0))
            image.paste(rgba, mask=rgba.getchannel('A'))
        else:
            image = image.convert('RGB')
        scale = min(1.0, MAX_EDGE / max(image.size))
        if scale < 1.0:
            size = (max(1, round(image.width * scale)), max(1, round(image.height * scale)))
            image = image.resize(size, getattr(Image, 'Resampling', Image).LANCZOS)
        # Written under a temporary name first, so an interrupted run leaves no broken cache file.
        partial = target.with_suffix('.part')
        image.save(partial, 'JPEG', quality=JPEG_QUALITY)
        os.replace(partial, target)


def run_sync(options, make_adb) -> None:
    config = load_config()
    if options.folder:
        folder = Path(options.folder).expanduser()
        if not folder.is_dir():
            raise KitError(f'Photo folder not found: {options.folder}')
        config['photoFolder'] = str(folder.resolve())
    if not config['photoFolder']:
        config['photoFolder'] = str(default_photo_folder())
    if options.ip:
        config['ip'] = validate_ip(options.ip)
    save_config(config)
    if not config['ip']:
        raise KitError(f'No projector address known. Pass --ip <address> (find it with "{PYTHON} cleanhome.py find").')

    root = Path(config['photoFolder'])
    if not root.is_dir():
        root.mkdir(parents=True, exist_ok=True)
        print(f'Photo folder created: {root} - put photos into it and run this command again.')
        return

    load_pillow()
    adb = make_adb()

    # convert
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    files = sorted(path for path in root.rglob('*') if path.is_file())
    photos = [path for path in files if path.suffix.lower() in PHOTO_EXTENSIONS]
    unsupported = [path for path in files if path.suffix.lower() in UNSUPPORTED_EXTENSIONS]
    if unsupported:
        warn(f'{len(unsupported)} file(s) skipped (format not supported, please export as JPEG): '
             + ', '.join(path.name for path in unsupported[:5]))

    wanted = set()
    converted = failed = 0
    for photo in photos:
        info = photo.stat()
        name = stable_name(photo.relative_to(root).as_posix(), info.st_size, info.st_mtime_ns)
        target = CACHE_DIR / name
        if not target.exists():
            try:
                convert_photo(photo, target)
                converted += 1
            except Exception as error:  # Pillow raises many different types for broken files
                warn(f'Could not convert {photo.name}: {error}')
                failed += 1
                continue
        wanted.add(name)
    for cached in CACHE_DIR.iterdir():
        if cached.is_file() and cached.name not in wanted:
            cached.unlink()

    # mirror to the device
    adb.connect(config['ip'])
    adb.shell(f'mkdir -p {PHOTO_DIR}')
    remote = [line.strip() for line in adb.shell(f'ls -1 {PHOTO_DIR}', allow_failure=True)]
    remote = [name for name in remote if REMOTE_PHOTO_NAME.match(name)]
    pushed = removed = 0
    for name in sorted(wanted):
        if name not in remote:
            adb.device('push', str(real_path(CACHE_DIR / name)), f'{PHOTO_DIR}/{name}')
            pushed += 1
    for name in remote:
        if name not in wanted:
            adb.shell(f"rm -f '{PHOTO_DIR}/{name}'")
            removed += 1

    print(f'Folder: {root}')
    print(f'Photos: {len(wanted)} | newly converted: {converted} | copied: {pushed} | '
          f'removed from device: {removed} | failed: {failed}')


def cmd_sync_photos(options) -> None:
    run_sync(options, lambda: Adb(find_adb(options.adb)))


# ---------------------------------------------------------------- find

def local_subnets() -> list[str]:
    """First three octets of the /24 network of each active IPv4 address of this computer."""
    addresses = []
    try:
        # Connecting a UDP socket sends nothing; it only makes the system pick the outgoing address.
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(('192.0.2.1', 9))
            addresses.append(probe.getsockname()[0])
    except OSError:
        pass
    try:
        addresses.extend(info[4][0] for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET))
    except OSError:
        pass
    subnets = []
    for address in addresses:
        parsed = ipaddress.IPv4Address(address)
        # Only home and office networks; this skips loopback, link-local and VPN address ranges.
        if not parsed.is_private or parsed.is_loopback or parsed.is_link_local or parsed.is_unspecified:
            continue
        subnet = address.rsplit('.', 1)[0]
        if subnet not in subnets:
            subnets.append(subnet)
    return subnets


def cast_device_name(address: str, timeout: float) -> str | None:
    """Name of the Google Cast device at this address, or None if there is none."""
    connection = http.client.HTTPConnection(address, CAST_PORT, timeout=timeout)
    try:
        connection.request('GET', '/setup/eureka_info')
        response = connection.getresponse()
        if response.status != 200:
            return None
        info = json.loads(response.read().decode('utf-8', errors='replace'))
    except (OSError, ValueError, http.client.HTTPException):
        return None
    finally:
        connection.close()
    if not isinstance(info, dict):
        return None
    return str(info.get('name') or '(no name)')


def tcp_port_open(address: str, port: int, timeout: float) -> bool:
    try:
        with socket.create_connection((address, port), timeout=timeout):
            return True
    except OSError:
        return False


def cmd_find(options) -> None:
    if options.subnet:
        if not re.match(r'^\d{1,3}\.\d{1,3}\.\d{1,3}$', options.subnet):
            raise KitError(f"Use the first three octets, e.g. 192.168.1 (got '{options.subnet}').")
        validate_ip(options.subnet + '.1')
        subnets = [options.subnet]
    else:
        subnets = local_subnets()
    if not subnets:
        raise KitError('No active IPv4 network found on this computer. Pass --subnet, e.g. --subnet 192.168.1')

    if not 200 <= options.timeout_ms <= 10000:
        raise KitError('--timeout-ms must be between 200 and 10000.')
    timeout = options.timeout_ms / 1000
    found = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=128) as pool:
        for subnet in subnets:
            print(f'Scanning {subnet}.1-254 ...')
            addresses = [f'{subnet}.{host}' for host in range(1, 255)]
            for address, name in zip(addresses, pool.map(lambda item: cast_device_name(item, timeout), addresses)):
                if name is not None:
                    debugging = 'on' if tcp_port_open(address, ADB_PORT, timeout) else 'off'
                    found.append((address, name, debugging))

    if not found:
        print('No Cast devices found. Is the projector on and in the same network as this computer?')
        print('After switching it on, it can take about two minutes until it answers.')
        return
    found.sort(key=lambda item: ipaddress.IPv4Address(item[0]))
    rows = [('IP', 'Name', 'Network debugging')] + found
    widths = [max(len(row[column]) for row in rows) for column in range(3)]
    print()
    for row in rows:
        print('  '.join(cell.ljust(width) for cell, width in zip(row, widths)).rstrip())
    print()
    if len(found) == 1:
        address, _, debugging = found[0]
        if debugging == 'off':
            print('Turn on network debugging on the projector (Developer options), then run:')
        else:
            print('Next step:')
        print(f'  {PYTHON} cleanhome.py setup --ip {address}')


# ---------------------------------------------------------------- command line

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog='cleanhome.py',
        description='Clean Home kit for the XGIMI Horizon Pro (XK03H, Android TV 11).')
    commands = parser.add_subparsers(dest='command', metavar='<command>')
    commands.required = True

    def add_device_options(command) -> None:
        command.add_argument('--ip', help='IPv4 address of the projector (remembered, so later runs can omit it)')
        command.add_argument('--adb', help='path of the adb program, if it is not found automatically')

    find = commands.add_parser(
        'find', help='find the projector in the local network',
        description='Scans the /24 network(s) of this computer for Google Cast devices (Android TV projectors '
                    'included) and shows whether network debugging (port 5555) is on.')
    find.add_argument('--subnet', help='scan this /24 network instead, given as its first three octets, e.g. 192.168.1')
    find.add_argument('--timeout-ms', type=int, default=1500, help='timeout per device in milliseconds (default 1500)')
    find.set_defaults(handler=cmd_find)

    setup = commands.add_parser(
        'setup', help='debloat, install the launcher, make it the home screen',
        description='Disables bloat and telemetry packages, installs the Clean Home launcher and makes it the home '
                    'screen and photo screensaver. Everything is reversible: packages are disabled (never '
                    'uninstalled), and every changed value is written, together with its previous value, to '
                    'backup-<timestamp>/state.json next to this script. Do not run this while someone is watching.')
    add_device_options(setup)
    setup.add_argument('--apk', help='launcher APK to install (default: CleanHome.apk next to this script, else a '
                                     'build of ../launcher, else the published release is downloaded and verified)')
    setup.add_argument('--skip-debloat', action='store_true', help='do not disable any packages')
    setup.add_argument('--keep-assistant', action='store_true', help='keep the Google Assistant / voice search enabled')
    setup.add_argument('--full-power-off', action='store_true',
                       help='make the power key shut the projector down instead of entering standby')
    setup.add_argument('--secure-dns', choices=('quad9', 'off'), default='off',
                       help='quad9: encrypted DNS via dns.quad9.net, rolled back if name resolution fails (default: off)')
    setup.add_argument('--weather-city', help='city for the weather next to the clock (looked up on the TV via Open-Meteo)')
    setup.add_argument('--force', action='store_true', help='run on models other than the XK03H (untested)')
    setup.set_defaults(handler=cmd_setup)

    sync = commands.add_parser(
        'sync-photos', help='copy a photo folder to the projector',
        description='Resizes the photos of a folder (long edge at most 2560 px, JPEG, EXIF orientation applied) and '
                    'mirrors them to /sdcard/Pictures/CleanHome: photos removed from the folder are removed from '
                    'the device as well. Needs the Python package Pillow.')
    sync.add_argument('--folder', help='photo folder on this computer, searched recursively (default: the remembered '
                                       'folder, else Pictures/CleanHome, created if missing)')
    add_device_options(sync)
    sync.set_defaults(handler=cmd_sync_photos)

    restore = commands.add_parser(
        'restore', help='undo everything setup changed',
        description='Undoes every setup run recorded for the connected device (newest first, so the original values '
                    'win) and uninstalls Clean Home. Without a recorded run, factory defaults are restored. Photos '
                    'stay on the device.')
    add_device_options(restore)
    restore.add_argument('--backup-dir', help='undo only the run recorded in this backup folder')
    restore.set_defaults(handler=cmd_restore)
    return parser


def main(arguments: list[str] | None = None) -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, 'reconfigure'):
            stream.reconfigure(errors='replace')
    if sys.platform == 'win32':
        os.system('')  # switches the Windows console to ANSI colors
    options = build_parser().parse_args(arguments)
    try:
        options.handler(options)
    except KitError as error:
        print(_color(f'ERROR: {error}', '31', sys.stderr), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print('\nInterrupted.', file=sys.stderr)
        return 130
    return 0


if __name__ == '__main__':
    sys.exit(main())
