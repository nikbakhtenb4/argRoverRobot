"""Keep controller replacement isolated from the previous board's credentials."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import rastag_hardware_link as link


class ControllerSelection(unittest.TestCase):
    def setUp(self):
        self.old_device, self.old_android = link.DEVICE, link.ANDROID
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.settings = patch.multiple(link, ROOT=self.root, ACTIVE=self.root / 'active-device.json')
        self.settings.start()

    def tearDown(self):
        self.settings.stop()
        link.use_device(self.old_device, self.old_android)
        self.temp.cleanup()

    def test_keys_are_independent_and_old_bundle_is_preserved(self):
        with patch.object(link, 'protect', side_effect=lambda b, **kw: b):
            link.use_device('rastag-esp-000000000001')
            first = link.pair()
            first_bytes = link.BUNDLE.read_bytes()
            first_path = link.BUNDLE
            link.use_device('rastag-esp-000000000002')
            second = link.pair()
            self.assertNotEqual(first['server_pin'], second['server_pin'])
            self.assertNotEqual(first['ca'], second['ca'])
            self.assertEqual(first_path.read_bytes(), first_bytes)
            self.assertEqual(link.pair(), second)

    def test_invalid_selector_cannot_escape_private_store(self):
        for device in ['../elsewhere', 'rastag-esp-../file', 'rastag-esp-00000000000Z', None]:
            with self.subTest(device=device), self.assertRaises(ValueError):
                link.use_device(device)

    def test_wrong_bundle_is_rejected(self):
        link.use_device('rastag-esp-000000000002')
        link.BUNDLE.write_text(json.dumps({'device': 'rastag-esp-000000000001'}))
        with patch.object(link, 'protect', side_effect=lambda b, **kw: b):
            with self.assertRaises(ValueError):
                link.pair()
            with self.assertRaises(ValueError):
                link.activate()
        self.assertFalse(link.ACTIVE.exists())

    def test_activation_requires_existing_pair_and_contains_no_keys(self):
        link.use_device('rastag-esp-000000000002', '192.0.2.1:5555')
        with self.assertRaises(FileNotFoundError):
            link.activate()
        with patch.object(link, 'protect', side_effect=lambda b, **kw: b):
            link.pair()
            link.activate()
        self.assertEqual(json.loads(link.ACTIVE.read_text()),
                         {'device': link.DEVICE, 'android': '192.0.2.1:5555'})

    def test_ready_requires_this_board_and_known_inactive_state(self):
        link.use_device('rastag-esp-000000000002')
        for state in ['motion_disabled', 'motion_disarmed']:
            self.assertTrue(link.ready_line('LINK_READY '+link.DEVICE+' '+state))
        for line in ['LINK_READY rastag-esp-000000000001 motion_disarmed',
                     'LINK_READY '+link.DEVICE+' motion_armed',
                     'LINK_READY '+link.DEVICE+' motion_disarmed extra']:
            self.assertFalse(link.ready_line(line))


if __name__ == '__main__':
    unittest.main()
