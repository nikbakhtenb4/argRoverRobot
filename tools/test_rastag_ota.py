"""Offline validation of OTA input corruption, slot boundaries and pinned TLS."""
import hashlib
import json
from pathlib import Path
import struct
import sys
import unittest
from unittest.mock import patch
import ssl
import rastag_ota as ota


def fixture():
    # A tiny format-valid ESP32 image, not executable firmware. Never sent to a device.
    raw = bytearray(288)
    raw[0:2] = bytes([0xE9, 1]); raw[23] = 1
    struct.pack_into('<II', raw, 24, 0x3F400020, 256)
    struct.pack_into('<I', raw, 32, 0xABCD5432)
    raw[48:53] = b'9.9.9'
    raw[80:91] = b'rastag_link'
    raw[176:208] = bytes(range(32))
    checksum = 0xEF
    for byte in raw[32:]: checksum ^= byte
    raw += bytes(15)+bytes([checksum])
    return bytes(raw)+hashlib.sha256(raw).digest()


class ImageValidation(unittest.TestCase):
    def test_valid(self):
        r = ota.inspect_image(fixture())
        self.assertEqual(r['version'], '9.9.9')
        self.assertEqual(r['elf_sha256'], bytes(range(32)).hex())

    def test_rejects_corrupted_or_wrong_images(self):
        valid = fixture()
        cases = {'empty': b'', 'truncated': valid[:-1], 'trailing': valid+b'\0',
                 'merged_flash': b'\xff'*4096+valid, 'oversized': b'\0'*(ota.MAX_IMAGE+1)}
        for name, offset, value in [('chip',12,5),('segments',1,0),('project',80,ord('X')),
                                    ('payload',220,1),('checksum',303,0),('sha',335,0),
                                    ('missing_hash',23,0),('descriptor',32,0)]:
            changed = bytearray(valid); changed[offset] = value
            cases[name] = bytes(changed)
        for name, raw in cases.items():
            with self.subTest(case=name), self.assertRaises(ValueError):
                ota.inspect_image(raw)

    def test_unbounded_segment_is_rejected(self):
        changed = bytearray(fixture()); struct.pack_into('<I',changed,28,0xFFFFFFFF)
        with self.assertRaises(ValueError): ota.inspect_image(changed)

    def test_no_upload_without_ready_status(self):
        raw = fixture()
        for before in [{'ready':False,'project':'rastag_link','max_bytes':10000},
                       {'ready':True,'project':'other','max_bytes':10000},
                       {'ready':True,'project':'rastag_link','max_bytes':1}]:
            with patch.object(ota, 'Connection') as connection, self.assertRaises(ValueError):
                ota.upload({},None,raw,before,ota.inspect_image(raw))
            connection.assert_not_called()

    def test_boot_confirmation_rejects_rollback(self):
        state = {'boot':'new','ready':True,'elf_sha256':'wrong','version':'old','running':'ota_0'}
        with patch.object(ota,'get',return_value=state), self.assertRaises(ValueError):
            ota.verify({},None,{'boot':'old','next':'ota_1'},ota.inspect_image(fixture()),1)

    def test_boot_confirmation_requires_valid_state(self):
        meta=ota.inspect_image(fixture())
        state={'boot':'new','ready':True,**meta,'running':'ota_1','image_state':1,'boot_partition':'ota_1'}
        with patch.object(ota,'get',return_value=state), self.assertRaises(ValueError):
            ota.verify({},None,{'boot':'old','next':'ota_1'},meta,1)

    def test_status_retries_a_transient_disconnect(self):
        with patch.object(ota,'Connection') as connection, patch.object(ota.time,'sleep'), \
             patch.object(ota,'response_json',side_effect=[ota.http.client.RemoteDisconnected(),(200,{'device':'paired'})]):
            self.assertEqual(ota.get({'device':'paired'},None),{'device':'paired'})
            self.assertEqual(connection.call_count,2)
            self.assertEqual(connection.return_value.close.call_count,2)

    def test_certificate_failure_is_never_retried(self):
        with patch.object(ota,'Connection') as connection:
            connection.return_value.request.side_effect=ssl.SSLError('pin mismatch')
            with self.assertRaises(ssl.SSLError): ota.get({'device':'paired'},None)
            self.assertEqual(connection.call_count,1)


if __name__ == '__main__':
    if len(sys.argv)>1 and sys.argv[1].endswith('.bin'):
        print(json.dumps(ota.inspect_image(Path(sys.argv.pop(1)).read_bytes())))
    unittest.main()
