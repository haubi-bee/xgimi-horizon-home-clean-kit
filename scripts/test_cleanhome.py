"""Tests for cleanhome.py against a simulated projector. Run: python3 -m unittest discover -s scripts"""
from __future__ import annotations

import argparse
import contextlib
import io
import shlex
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import cleanhome

try:
    from PIL import Image
except ImportError:
    Image = None

SERIAL = '192.168.1.50:5555'


class FakeProjector(cleanhome.Adb):
    """Answers the adb calls of the kit from an in-memory device."""

    def __init__(self, model: str = 'XK03H'):
        super().__init__('adb')
        self.props = {'ro.product.model': model, 'ro.build.version.release': '11',
                      'ro.build.display.id': 'RT.3.24.327', 'ro.serialno': 'SERIAL1'}
        self.installed = {cleanhome.GOOGLE_LAUNCHER, cleanhome.FILE_MANAGER, cleanhome.ASSISTANT_PACKAGE,
                          'com.google.android.feedback', 'com.ted.android.tv', 'com.android.printspooler',
                          'com.netflix.ninja'}
        self.disabled = {'com.android.printspooler'}
        self.settings = {('global', 'window_animation_scale'): '1.0',
                         ('secure', 'screensaver_components'): cleanhome.DEFAULT_SCREENSAVER}
        self.appops = {}
        self.files = set()
        self.dns_works = True
        self.home_opens_launcher = True
        self.started = []
        self.home_apps = {cleanhome.GOOGLE_LAUNCHER, cleanhome.LAUNCHER_PACKAGE}

    def snapshot(self):
        return (set(self.installed), set(self.disabled), dict(self.settings), dict(self.appops))

    def run(self, arguments, allow_failure=False):
        if arguments[0] == 'connect':
            return [f'connected to {arguments[1]}']
        assert arguments[:2] == ['-s', SERIAL], arguments
        command, rest = arguments[2], arguments[3:]
        if command == 'get-state':
            return ['device']
        if command == 'install':
            self.installed.add(cleanhome.LAUNCHER_PACKAGE)
            return ['Performing Streamed Install', 'Success']
        if command == 'uninstall':
            self.installed.discard(rest[0])
            return ['Success']
        if command == 'push':
            self.files.add(rest[1].rsplit('/', 1)[1])
            return []
        assert command == 'shell', arguments
        return self._shell(rest[0])

    def _shell(self, text):
        words = shlex.split(text)
        if words[0] == 'getprop':
            return [self.props[words[1]]]
        if words[:3] == ['pm', 'list', 'packages']:
            names = self.disabled if '-d' in words else self.installed
            return [f'package:{name}' for name in sorted(names)]
        if words[:2] == ['pm', 'disable-user']:
            self.disabled.add(words[-1])
            return [f'Package {words[-1]} new state: disabled-user']
        if words[:2] == ['pm', 'enable']:
            self.disabled.discard(words[2])
            return [f'Package {words[2]} new state: enabled']
        if words[0] == 'settings':
            action, namespace = words[1], words[2]
            if action == 'list':
                return [f'{key}={value}' for (space, key), value in self.settings.items() if space == namespace]
            if action == 'get':
                return [self.settings.get((namespace, words[3]), 'null')]
            if action == 'put':
                self.settings[(namespace, words[3])] = words[4]
            if action == 'delete':
                self.settings.pop((namespace, words[3]), None)
            return []
        if words[0] == 'appops':
            key = (words[2], words[3])
            if words[1] == 'get':
                return [f'{words[3]}: {self.appops[key]}'] if key in self.appops else ['No operations.']
            self.appops[key] = words[4]
            return []
        if words[0] == 'pidof':
            return ['4242'] if words[1] in self.installed else []
        if words[0] == 'dumpsys':
            launcher_is_home = self.home_opens_launcher and cleanhome.GOOGLE_LAUNCHER in self.disabled
            package = cleanhome.LAUNCHER_PACKAGE if launcher_is_home else cleanhome.GOOGLE_LAUNCHER
            return [f'  mResumedActivity: ActivityRecord{{1 u0 {package}/.Home t1}}']
        if words[0] == 'ping':
            return ['PING example.com (93.184.215.14) 56(84) bytes'] if self.dns_works else ['ping: unknown host']
        if words[0] == 'ls':
            return sorted(self.files) + ['notes.txt']
        if words[0] == 'rm':
            self.files.discard(words[-1].rsplit('/', 1)[1])
            return []
        if words[0] == 'am':
            self.started.append(words)
            return []
        if words[:3] == ['cmd', 'package', 'query-activities']:
            homes = sorted(name for name in self.home_apps if name in self.installed and name not in self.disabled)
            lines = [f'{len(homes) + 1} activities found:']
            for index, name in enumerate(homes):
                lines += [f'  Activity #{index}:', '    priority=0 preferredOrder=0 isDefault=true', f'    {name}/.Home']
            return lines + ['  Activity #9:', '    priority=-1000', '    com.android.tv.settings/.system.FallbackHome']
        if words[0] in ('mkdir', 'input', 'cmd') or words[:2] == ['pm', 'grant']:
            return []
        raise AssertionError(f'unexpected shell command: {text}')


def setup_options(apk, **overrides):
    values = dict(ip='192.168.1.50', adb=None, apk=str(apk), skip_debloat=False, keep_assistant=False,
                  full_power_off=False, secure_dns='off', weather_city=None, force=False)
    values.update(overrides)
    return argparse.Namespace(**values)


class KitTestCase(unittest.TestCase):
    def enter(self, context):
        value = context.__enter__()
        self.addCleanup(context.__exit__, None, None, None)
        return value

    def setUp(self):
        self.temp = Path(self.enter(tempfile.TemporaryDirectory()))
        self.backups = self.temp / 'backups'
        self.backups.mkdir()
        self.apk = self.temp / 'CleanHome.apk'
        self.apk.write_bytes(b'apk')
        self.enter(mock.patch.object(cleanhome, 'KIT_DIR', self.temp / 'kit'))
        self.enter(mock.patch.object(cleanhome, 'CACHE_DIR', self.temp / 'kit' / 'photo-cache'))
        self.enter(mock.patch.object(cleanhome, '_sleep', lambda seconds: None))
        self.enter(contextlib.redirect_stdout(io.StringIO()))
        self.enter(contextlib.redirect_stderr(io.StringIO()))

    def restore(self, device, **overrides):
        values = dict(ip=None, adb=None, backup_dir=None)
        values.update(overrides)
        cleanhome.run_restore(device, argparse.Namespace(**values), self.backups)


class SetupAndRestoreTest(KitTestCase):
    def test_setup_changes_the_device_and_records_it(self):
        device = FakeProjector()
        state_path = cleanhome.run_setup(device, setup_options(self.apk, weather_city=" St. John's "), self.backups)

        self.assertIn(cleanhome.LAUNCHER_PACKAGE, device.installed)
        self.assertIn(cleanhome.GOOGLE_LAUNCHER, device.disabled)
        self.assertIn(cleanhome.ASSISTANT_PACKAGE, device.disabled)
        self.assertNotIn('com.netflix.ninja', device.disabled)
        self.assertEqual(device.settings[('secure', 'screensaver_components')], cleanhome.DREAM_COMPONENT)
        self.assertEqual(device.appops[(cleanhome.FILE_MANAGER, 'REQUEST_INSTALL_PACKAGES')], 'deny')
        self.assertEqual(device.started[-1][-2:], ['weather_city', "St. John's"])

        state = cleanhome.read_json(state_path)
        # Already disabled before the setup, so restore must not enable it.
        self.assertNotIn('com.android.printspooler', state['disabledPackages'])
        self.assertTrue(state['googleLauncherDisabled'])
        self.assertEqual(cleanhome.load_config()['ip'], '192.168.1.50')

    def test_restore_brings_back_the_original_state(self):
        device = FakeProjector()
        before = device.snapshot()
        cleanhome.run_setup(device, setup_options(self.apk, full_power_off=True, secure_dns='quad9'), self.backups)
        self.assertNotEqual(device.snapshot(), before)

        self.restore(device)
        installed, disabled, settings, appops = device.snapshot()
        self.assertEqual((installed, disabled, settings), before[:3])
        self.assertEqual(appops, {(cleanhome.FILE_MANAGER, 'REQUEST_INSTALL_PACKAGES'): 'default'})

    def test_restore_keeps_a_previously_disabled_google_launcher_when_another_home_app_exists(self):
        device = FakeProjector()
        device.installed.add('org.example.home')
        device.home_apps.add('org.example.home')
        device.disabled.add(cleanhome.GOOGLE_LAUNCHER)
        before = device.snapshot()
        cleanhome.run_setup(device, setup_options(self.apk), self.backups)
        self.restore(device)
        self.assertEqual(device.snapshot()[:3], before[:3])

    def test_restore_enables_the_google_launcher_when_no_other_home_app_exists(self):
        device = FakeProjector()
        device.disabled.add(cleanhome.GOOGLE_LAUNCHER)
        device.home_opens_launcher = True
        cleanhome.run_setup(device, setup_options(self.apk), self.backups)
        self.restore(device)
        self.assertNotIn(cleanhome.GOOGLE_LAUNCHER, device.disabled)

    def test_restore_ignores_runs_of_another_device(self):
        device = FakeProjector()
        cleanhome.run_setup(device, setup_options(self.apk), self.backups)
        device.props['ro.serialno'] = 'OTHER'
        self.assertEqual(cleanhome.find_state_files(self.backups, None, 'OTHER'), [])

    def test_restore_without_a_recorded_run_uses_defaults(self):
        device = FakeProjector()
        device.disabled.update({cleanhome.GOOGLE_LAUNCHER, cleanhome.ASSISTANT_PACKAGE})
        device.installed.add(cleanhome.LAUNCHER_PACKAGE)
        device.settings[('global', 'private_dns_mode')] = 'hostname'
        self.restore(device, ip='192.168.1.50')

        self.assertEqual(device.disabled, set())
        self.assertNotIn(cleanhome.LAUNCHER_PACKAGE, device.installed)
        self.assertNotIn(('global', 'private_dns_mode'), device.settings)
        self.assertEqual(device.settings[('secure', 'screensaver_components')], cleanhome.DEFAULT_SCREENSAVER)

    def test_other_models_are_refused_without_changes(self):
        device = FakeProjector(model='Chromecast')
        before = device.snapshot()
        with self.assertRaisesRegex(cleanhome.KitError, 'only tested'):
            cleanhome.run_setup(device, setup_options(self.apk), self.backups)
        self.assertEqual(device.snapshot(), before)
        self.assertEqual(list(self.backups.iterdir()), [])

    def test_encrypted_dns_is_rolled_back_when_names_do_not_resolve(self):
        device = FakeProjector()
        device.dns_works = False
        state_path = cleanhome.run_setup(device, setup_options(self.apk, secure_dns='quad9'), self.backups)

        self.assertNotIn(('global', 'private_dns_mode'), device.settings)
        self.assertNotIn(('global', 'private_dns_specifier'), device.settings)
        keys = [entry['key'] for entry in cleanhome.read_json(state_path)['settings']]
        self.assertNotIn('private_dns_mode', keys)

    def test_google_launcher_comes_back_when_home_does_not_open_clean_home(self):
        device = FakeProjector()
        device.home_opens_launcher = False
        with self.assertRaisesRegex(cleanhome.KitError, 'HOME does not open'):
            cleanhome.run_setup(device, setup_options(self.apk), self.backups)
        self.assertNotIn(cleanhome.GOOGLE_LAUNCHER, device.disabled)

    def test_state_written_by_the_powershell_scripts_is_readable(self):
        folder = self.backups / 'backup-20260101-000000'
        folder.mkdir()
        (folder / 'state.json').write_text(
            '{"device": {"serial": "SERIAL1"}, "disabledPackages": ["com.ted.android.tv"], "settings": '
            '[{"namespace": "global", "key": "send_action_app_error", "previous": null, "value": "0"}], '
            '"appops": [], "googleLauncherDisabled": true}', encoding='utf-8-sig')
        device = FakeProjector()
        device.disabled.update({cleanhome.GOOGLE_LAUNCHER, 'com.ted.android.tv'})
        device.settings[('global', 'send_action_app_error')] = '0'
        self.restore(device, ip='192.168.1.50')

        self.assertEqual(device.disabled, {'com.android.printspooler'})
        self.assertNotIn(('global', 'send_action_app_error'), device.settings)


class DownloadTest(KitTestCase):
    def setUp(self):
        super().setUp()
        self.enter(mock.patch.object(cleanhome, 'SCRIPT_DIR', self.temp / 'empty' / 'scripts'))
        self.release = self.temp / 'release.apk'
        self.release.write_bytes(b'published apk')
        self.enter(mock.patch.object(cleanhome, 'APK_URL', self.release.as_uri()))

    def test_published_apk_is_downloaded_once_and_verified(self):
        with mock.patch.object(cleanhome, 'APK_SHA256', cleanhome.file_sha256(self.release)):
            apk = cleanhome.resolve_apk(None)
            self.assertEqual(apk.read_bytes(), b'published apk')
            self.release.unlink()  # the second call must not need the network
            self.assertEqual(cleanhome.resolve_apk(None), apk)

    def test_changed_download_is_refused(self):
        with self.assertRaisesRegex(cleanhome.KitError, 'checksum'):
            cleanhome.resolve_apk(None)
        self.assertEqual([path.name for path in cleanhome.KIT_DIR.iterdir()], [])

    def test_failed_download_names_the_manual_way(self):
        self.release.unlink()
        with self.assertRaisesRegex(cleanhome.KitError, '--apk'):
            cleanhome.resolve_apk(None)


class AddressTest(KitTestCase):
    def test_addresses_are_validated(self):
        self.assertEqual(cleanhome.validate_ip(' 192.168.1.50 '), '192.168.1.50')
        for value in ('192.168.1', '192.168.1.256', 'projector.local', '::1'):
            with self.assertRaises(cleanhome.KitError):
                cleanhome.validate_ip(value)

    def test_unknown_address_is_an_error(self):
        with self.assertRaisesRegex(cleanhome.KitError, 'No projector address'):
            cleanhome.resolve_device_ip(None)


@unittest.skipIf(Image is None, 'Pillow is not installed')
class SyncPhotosTest(KitTestCase):
    def sync(self, device, folder):
        options = argparse.Namespace(folder=str(folder), ip='192.168.1.50', adb=None)
        cleanhome.run_sync(options, lambda: device)

    def test_photos_are_resized_and_mirrored(self):
        photos = self.temp / 'photos'
        (photos / 'trip').mkdir(parents=True)
        Image.new('RGB', (4000, 1000), 'red').save(photos / 'wide.png')
        Image.new('RGBA', (100, 300), (0, 0, 255, 0)).save(photos / 'trip' / 'clear.png')
        (photos / 'broken.jpg').write_bytes(b'not a photo')
        (photos / 'phone.heic').write_bytes(b'x')
        device = FakeProjector()
        device.files.add('0123456789abcdef.jpg')  # left over from an earlier sync

        self.sync(device, photos)
        self.assertEqual(len(device.files), 2)
        self.assertNotIn('0123456789abcdef.jpg', device.files)
        sizes = set()
        for name in device.files:
            with Image.open(cleanhome.CACHE_DIR / name) as image:
                self.assertEqual(image.format, 'JPEG')
                sizes.add(image.size)
        self.assertEqual(sizes, {(2560, 640), (100, 300)})

        (photos / 'wide.png').unlink()
        self.sync(device, photos)
        self.assertEqual(len(device.files), 1)
        self.assertEqual(len(list(cleanhome.CACHE_DIR.iterdir())), 1)

    def test_name_changes_when_the_photo_changes(self):
        name = cleanhome.stable_name('trip/a.jpg', 10, 1)
        self.assertRegex(name, cleanhome.REMOTE_PHOTO_NAME)
        self.assertEqual(name, cleanhome.stable_name('trip/a.jpg', 10, 1))
        self.assertNotEqual(name, cleanhome.stable_name('trip/a.jpg', 11, 1))


if __name__ == '__main__':
    unittest.main()
