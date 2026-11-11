"""Paired ESP32 OTA over pinned mutual TLS; credentials never leave DPAPI storage.

Usage: python tools/rastag_ota.py status
       python tools/rastag_ota.py upload firmware/rastag_link/.pio/build/esp32dev/firmware.bin
Only application images are accepted. USB bootloader/partition migration is separate.
"""
import argparse
import base64
import hashlib
import http.client
import ipaddress
import json
from pathlib import Path
import socket
import ssl
import struct
import time

from cryptography import x509
from cryptography.hazmat.primitives import serialization
import rastag_hardware_link as link
from rastag_wifi_credentials import protect

MAX_IMAGE = 0x1E0000


def inspect_image(raw):
    """Validate a complete unsigned ESP32 app image, including checksum and digest."""
    if not 288 <= len(raw) <= MAX_IMAGE:
        raise ValueError('Application size outside OTA limits')
    if raw[0] != 0xE9 or not 1 <= raw[1] <= 16 or struct.unpack_from('<H', raw, 12)[0] != 0:
        raise ValueError('Expected an ESP32 application image')
    if raw[23] != 1:
        raise ValueError('Image must contain its ESP SHA256 digest')
    if struct.unpack_from('<I', raw, 32)[0] != 0xABCD5432:
        raise ValueError('Missing application descriptor (merged flash images are not OTA images)')
    def text(start):
        field = raw[start:start+32]
        if b'\0' not in field:
            raise ValueError('Invalid application descriptor')
        return field.split(b'\0', 1)[0].decode('ascii')
    version, project = text(48), text(80)
    if project != 'rastag_link' or not version:
        raise ValueError('Wrong application project/version')
    cursor, checksum = 24, 0xEF
    for index in range(raw[1]):
        if cursor+8 > len(raw):
            raise ValueError('Truncated segment header')
        size = struct.unpack_from('<I', raw, cursor+4)[0]
        cursor += 8
        if cursor+size > len(raw) or (index == 0 and size < 256):
            raise ValueError('Truncated segment')
        for byte in raw[cursor:cursor+size]:
            checksum ^= byte
        cursor += size
    checksum_at = (cursor//16+1)*16-1
    end = checksum_at+1
    if end+32 != len(raw) or raw[checksum_at] != checksum:
        raise ValueError('Image checksum/length mismatch')
    if hashlib.sha256(raw[:end]).digest() != raw[end:]:
        raise ValueError('Image digest mismatch')
    return {'version': version, 'project': project, 'bytes': len(raw),
            'sha256': hashlib.sha256(raw).hexdigest(), 'elf_sha256': raw[176:208].hex()}


def load_pair(ip=None):
    # Never call pair(): OTA may not create a new identity or change existing pairing.
    data = json.loads(protect(link.BUNDLE.read_bytes(), decrypt=True))
    if data['device'] != link.DEVICE or data['hostname'] != link.HOST:
        raise ValueError('Unexpected paired device')
    addresses = []
    if ip:
        addresses.append(str(ipaddress.ip_address(ip)))
    else:
        try:
            addresses.append(socket.gethostbyname(data['hostname']))
        except socket.gaierror:
            pass
        if data.get('ip_hint'):
            addresses.append(str(ipaddress.ip_address(data['ip_hint'])))
    if not addresses:
        raise RuntimeError('Cannot resolve paired controller; specify --ip on its LAN')
    data['addresses'] = list(dict.fromkeys(addresses))
    return data


class Connection(http.client.HTTPSConnection):
    def __init__(self, data, context):
        super().__init__(data['hostname'], 8443, context=context, timeout=15)
        self.data = data

    def connect(self):
        last = None
        for address in self.data['addresses']:
            try:
                raw = socket.create_connection((address, 8443), timeout=5)
            except OSError as exc:
                last = exc
                continue
            try:
                raw.settimeout(15)
                self.sock = self._context.wrap_socket(raw, server_hostname=self.data['hostname'])
                cert = x509.load_der_x509_certificate(self.sock.getpeercert(binary_form=True))
                spki = cert.public_key().public_bytes(serialization.Encoding.DER,
                                                     serialization.PublicFormat.SubjectPublicKeyInfo)
                pin = 'sha256/'+base64.b64encode(hashlib.sha256(spki).digest()).decode('ascii')
                if pin != self.data['server_pin']:
                    raise ssl.SSLError('Controller pin mismatch')
                return
            except Exception:
                self.close(); raw.close()
                raise
        raise last or ConnectionError('No controller address')


def response_json(conn):
    response = conn.getresponse()
    raw = response.read(4097)
    if len(raw) > 4096:
        raise ValueError('Controller response too large')
    return response.status, json.loads(raw)


def get(data, context, path='/v2/ota', attempts=3):
    # Only read-only requests may retry when the small embedded listener is busy.
    for attempt in range(attempts):
        conn = Connection(data, context)
        try:
            conn.request('GET', path, headers={'Connection': 'close'})
            code, result = response_json(conn)
            if code != 200:
                raise RuntimeError(f'Controller HTTP {code}')
            if path == '/v2/ota' and result.get('device') != data['device']:
                raise ValueError('Controller identity mismatch')
            return result
        except ssl.SSLError:
            raise  # Certificate and pin failures are not transient.
        except (OSError, http.client.HTTPException):
            if attempt+1 == attempts:
                raise
        finally:
            conn.close()
        time.sleep(0.5*(attempt+1))


def upload(data, context, raw, before, metadata):
    if not before['ready'] or before['project'] != metadata['project'] or len(raw) > before['max_bytes']:
        raise ValueError('Controller is not ready or image does not fit')
    conn = Connection(data, context)
    try:
        conn.putrequest('POST', '/v2/ota')
        for key, value in {'Content-Type': 'application/octet-stream', 'Content-Length': str(len(raw)),
                           'X-Rastag-Boot': before['boot'], 'X-Firmware-SHA256': metadata['sha256'],
                           'Connection': 'close'}.items():
            conn.putheader(key, value)
        conn.endheaders()
        started, progress = time.monotonic(), -1
        for offset in range(0, len(raw), 4096):
            if time.monotonic()-started > 120:
                raise TimeoutError('Upload exceeded time limit; checking device is required')
            conn.send(raw[offset:offset+4096])
            percent = min(100, (offset+4096)*100//len(raw))
            if percent//25 > progress:
                progress = percent//25
                print(json.dumps({'upload_percent': percent}), flush=True)
        code, result = response_json(conn)
        if code != 202 or result.get('accepted') is not True:
            raise RuntimeError(f'OTA rejected: HTTP {code}, {result.get("error", "invalid_ack")}')
        return result
    finally:
        conn.close()


def verify(data, context, before, metadata, timeout=210):
    deadline = time.monotonic()+timeout
    previous_scan = None
    while time.monotonic() < deadline:
        try:
            state = get(data, context)
            if state['boot'] != before['boot'] and state['ready']:
                if (state['elf_sha256'] != metadata['elf_sha256'] or
                    state['version'] != metadata['version'] or state['running'] != before['next']):
                    raise ValueError('Controller rebooted into a different image/slot (possible rollback)')
                if state['image_state'] != 2 or state['boot_partition'] != state['running']:
                    raise ValueError('Updated image has not been confirmed valid')
                hardware = get(data, context, '/v2/state')
                if hardware['boot'] != state['boot']:
                    raise ValueError('Unexpected reboot during verification')
                if hardware['motion']['armed'] or hardware['motion']['driver_enabled']:
                    raise ValueError('Unexpected motion state after update')
                lidar = hardware['lidar']
                if not lidar['configured'] or (lidar['fresh'] and lidar['complete'] and
                    previous_scan is not None and lidar['scan_seq'] > previous_scan):
                    return {'ota': state, 'hardware': hardware, 'lidar_advanced': lidar['configured']}
                previous_scan = lidar['scan_seq'] if lidar['fresh'] else None
        except ssl.SSLError:
            raise
        except (OSError, http.client.HTTPException):
            pass  # Reboot/network recovery. Never resend firmware automatically.
        time.sleep(2)
    raise TimeoutError('Update not verified; inspect status before retrying')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['status', 'upload'])
    parser.add_argument('image', nargs='?', type=Path)
    parser.add_argument('--ip', help='Optional IP; paired hostname, CA and pin are still mandatory')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    report = {'action': args.action, 'passed': False}
    try:
        raw = args.image.read_bytes() if args.action == 'upload' and args.image else None
        metadata = inspect_image(raw) if raw is not None else None
        if args.action == 'upload' and raw is None:
            parser.error('upload requires an application firmware.bin')
        data = load_pair(args.ip)
        with link.client_context(data) as context:
            before = get(data, context)
            report['before'] = before
            if args.action == 'upload':
                report['image'] = metadata
                try:
                    report['ack'] = upload(data, context, raw, before, metadata)
                except (OSError, http.client.HTTPException) as exc:
                    # ACK may be lost after commit: inspect reboot, never double-flash.
                    report['ack_transport_error'] = type(exc).__name__
                report['after'] = verify(data, context, before, metadata)
            report['passed'] = True
    except Exception as exc:
        report['error'] = str(exc)
        report['error_type'] = type(exc).__name__
    finally:
        if args.output:
            args.output.write_text(json.dumps(report, indent=2), encoding='utf-8')
        print(json.dumps(report), flush=True)
    if not report['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
