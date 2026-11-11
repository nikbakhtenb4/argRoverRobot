"""Private USB pairing and v1 communication probes for the controller.

No credentials are accepted on the command line or printed. Pairing material stays
in Windows DPAPI storage outside the repository; Android imports into its Keystore.
"""
import argparse
import base64
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import hashlib
import http.client
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import ssl
import subprocess
import tempfile
import time

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID
import serial
from rastag_wifi_credentials import protect, load as wifi_credentials

ROOT = Path(os.environ['LOCALAPPDATA']) / 'ARG/RastagHardwareLink'
BUNDLE = ROOT / 'pair-083af2b76f3c.dpapi'
DEVICE = 'rastag-esp-083af2b76f3c'
HOST = DEVICE + '.local'
ADB = r'E:\Android\SdkMain\platform-tools\adb.exe'
ANDROID = '20260207134648'
PACKAGE = 'com.argsecure.rastagmobility'


def pem_key(k):
    return k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                           serialization.NoEncryption()).decode('ascii')


def pem_cert(c):
    return c.public_bytes(serialization.Encoding.PEM).decode('ascii')


def certificate(key, name, issuer=None, issuer_key=None, client=False, ca=False):
    now = datetime.now(timezone.utc)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, name)])
    b = x509.CertificateBuilder().subject_name(subject).issuer_name(issuer.subject if issuer else subject)
    b = b.public_key(key.public_key()).serial_number(x509.random_serial_number())
    b = b.not_valid_before(now-timedelta(days=1)).not_valid_after(now+timedelta(days=3650 if ca else 730))
    b = b.add_extension(x509.BasicConstraints(ca=ca, path_length=0 if ca else None), critical=True)
    b = b.add_extension(x509.KeyUsage(digital_signature=True, content_commitment=False,
        key_encipherment=False, data_encipherment=False, key_agreement=False,
        key_cert_sign=ca, crl_sign=ca, encipher_only=None, decipher_only=None), critical=True)
    if not ca:
        b = b.add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.CLIENT_AUTH if client
            else ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
        if not client:
            b = b.add_extension(x509.SubjectAlternativeName([x509.DNSName(name)]),critical=False)
    return b.sign(issuer_key or key,hashes.SHA256())


def pair():
    if BUNDLE.exists():
        return json.loads(protect(BUNDLE.read_bytes(), decrypt=True))
    ROOT.mkdir(parents=True,exist_ok=True)
    ca_key, server_key, client_key = [ec.generate_private_key(ec.SECP256R1()) for _ in range(3)]
    ca = certificate(ca_key,'Rastag USB pair 083af2b76f3c',ca=True)
    server = certificate(server_key,HOST,ca,ca_key)
    client = certificate(client_key,'Rastag controller client',ca,ca_key,client=True)
    spki = server.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)
    data = {'version':1, 'device':DEVICE, 'hostname':HOST, 'ca':pem_cert(ca),
            'server_cert':pem_cert(server), 'server_key':pem_key(server_key),
            'client_cert':pem_cert(client), 'client_key':pem_key(client_key),
            'server_pin':'sha256/'+base64.b64encode(hashlib.sha256(spki).digest()).decode('ascii')}
    # CA private key deliberately not retained: only these two identities can join.
    with BUNDLE.open('xb') as f: f.write(protect(json.dumps(data).encode('utf-8')))
    return data


def adb(args, data=None):
    return subprocess.run([ADB,'-s',ANDROID]+args,input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True).stdout


def android_import(data, include_hint=False):
    keys = ['version','device','hostname','ca','client_cert','client_key','server_pin']
    if include_hint: keys.append('ip_hint')
    incoming = {k:data[k] for k in keys if k in data}
    command = (f"run-as {PACKAGE} sh -c 'umask 077; mkdir -p no_backup; "
               "cat > no_backup/hardware_pair_import.tmp && "
               "mv no_backup/hardware_pair_import.tmp no_backup/hardware_pair_import.json'")
    adb(['shell',command],json.dumps(incoming).encode('utf-8'))
    print('Android private pairing import staged.')


def provision(port):
    data = pair()
    wifi = wifi_credentials()
    payload = {'device':DEVICE,'hostname':HOST,'ssid':wifi['ssid'],'password':wifi['password'],
               'server_cert':data['server_cert'],'server_key':data['server_key'],'client_ca':data['ca'],
               'epoch':int(time.time())}
    message = json.dumps(payload,separators=(',',':')).encode('utf-8')+b'\n'
    if len(message) >= 12288: raise RuntimeError('Provisioning size limit')
    deadline = time.monotonic()+100
    sent = False
    # Do not toggle boot pins or replay provisioning to an unidentified device.
    with serial.Serial(port,115200,timeout=1,write_timeout=5,dsrdtr=False,rtscts=False) as uart:
        uart.dtr = False; uart.rts = False
        while time.monotonic() < deadline:
            line = uart.readline(512).decode('ascii',errors='ignore').strip()
            if line == 'LINK_UNPAIRED '+DEVICE and not sent:
                uart.write(message); uart.flush(); sent = True
            if line == 'LINK_PROVISIONED '+DEVICE:
                print('USB identity matched; credentials provisioned.')
            if line.startswith(('LINK_WIFI_RETRY ', 'LINK_WAITING_TIME', 'LINK_TIME_REJECTED')):
                print(line,flush=True)
            if line.startswith('LINK_IP '):
                data['ip_hint'] = str(ipaddress.ip_address(line.split()[1]))
                BUNDLE.write_bytes(protect(json.dumps(data).encode('utf-8')))
                print('Controller Wi-Fi address:',data['ip_hint'])
            if line == 'LINK_READY '+DEVICE+' motion_disabled':
                android_import(data)
                print('Controller mTLS server ready; motion disabled.')
                return
            if line == 'LINK_REJECTED': raise RuntimeError('USB provisioning rejected')
    raise TimeoutError('Controller not ready; no credentials printed. Inspect bounded serial status.')


@contextmanager
def client_context(data, identity=True, rogue=False, trust=True):
    ctx = ssl.create_default_context(cadata=data['ca'] if trust else None)
    ctx.minimum_version = ctx.maximum_version = ssl.TLSVersion.TLSv1_2
    with tempfile.TemporaryDirectory(prefix='tls-',dir=ROOT) as td:
        if identity:
            cert, key = data['client_cert'],data['client_key']
            if rogue:
                k = ec.generate_private_key(ec.SECP256R1())
                cert, key = pem_cert(certificate(k,'unpaired test client',client=True)),pem_key(k)
            cp,kp = Path(td)/'client.pem',Path(td)/'key.pem'
            cp.write_text(cert,encoding='ascii'); kp.write_text(key,encoding='ascii')
            ctx.load_cert_chain(cp,kp)
        yield ctx


def request(data, ctx, path, body=None, cache=None):
    class Connection(http.client.HTTPSConnection):
        def connect(self):
            raw = socket.create_connection((data['ip_hint'],8443),timeout=5)
            try: self.sock = self._context.wrap_socket(raw,server_hostname=HOST)
            except Exception: raw.close(); raise
            cert = x509.load_der_x509_certificate(self.sock.getpeercert(binary_form=True))
            spki = cert.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)
            pin = 'sha256/'+base64.b64encode(hashlib.sha256(spki).digest()).decode('ascii')
            if pin != data['server_pin']: self.sock.close(); raise ssl.SSLError('Pin mismatch')
    if cache is None:
        conn = Connection(HOST,8443,context=ctx,timeout=5)
    else:
        if 'connection' not in cache: cache['connection'] = Connection(HOST,8443,context=ctx,timeout=5)
        conn = cache['connection']
    try:
        raw = json.dumps(body) if body is not None else None
        conn.request('POST' if body is not None else 'GET',path,body=raw,headers={'Content-Type':'application/json','Connection':'close' if cache is None else 'keep-alive'})
        response = conn.getresponse(); payload = response.read(4097)
        if len(payload)>4096: raise RuntimeError('Response size limit')
        return response.status,json.loads(payload) if payload.startswith(b'{') else {}
    finally:
        if cache is None: conn.close()


@contextmanager
def persistent_requests(data,ctx):
    cache = {}
    try:
        def send(path,body=None): return request(data,ctx,path,body,cache)
        def close():
            if 'connection' in cache: cache['connection'].close()
        send.close = close
        yield send
    finally:
        if 'connection' in cache: cache['connection'].close()


def probe(output):
    data = pair(); results = []
    for name, kwargs in [('missing_client_certificate',{'identity':False}),
                          ('unpaired_client_certificate',{'rogue':True}),('wrong_server_trust',{'trust':False})]:
        with client_context(data,**kwargs) as ctx:
            try: request(data,ctx,'/v1/status')
            except (ssl.SSLError,ConnectionError,http.client.RemoteDisconnected): results.append({'test':name,'passed':True})
            else: results.append({'test':name,'passed':False})
    with client_context(data) as ctx, persistent_requests(data,ctx) as send:
        code, session = send('/v1/session',{'protocol':1})
        assert code==200 and session['motion_enabled'] is False and session['seq']==0
        token = session['session']
        tests = [('heartbeat', '/v1/heartbeat',{'session':token,'seq':1},200),
                 ('duplicate','/v1/heartbeat',{'session':token,'seq':1},409),
                 ('wrong_session','/v1/heartbeat',{'session':'0'*32,'seq':2},409),
                 ('fractional_sequence','/v1/heartbeat',{'session':token,'seq':2.5},400),
                 ('unexpected_field','/v1/heartbeat',{'session':token,'seq':2,'move':1},400),
                 ('actuation_unavailable','/v1/move',{'speed':1},404),
                 ('next_heartbeat','/v1/heartbeat',{'session':token,'seq':2},200)]
        for name,path,body,expected in tests:
            code,reply = send(path,body)
            results.append({'test':name,'passed':code==expected,'http':code})
            if name == 'actuation_unavailable': send.close()
        send.close() # Let the application lease expire without reusing an idle TCP socket.
        time.sleep(7.3)
        code,reply = send('/v1/heartbeat',{'session':token,'seq':3})
        results.append({'test':'expired_lease','passed':code==409,'http':code})
        code,new = send('/v1/session',{'protocol':1})
        results.append({'test':'reconnect','passed':code==200 and new['session']!=token and new['motion_enabled'] is False})
        code,_ = send('/v1/heartbeat',{'session':token,'seq':4})
        results.append({'test':'replaced_session','passed':code==409})
    report = {'device':DEVICE,'timestamp':datetime.now(timezone.utc).isoformat(),'tests':results,'passed':all(x['passed'] for x in results)}
    Path(output).write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps({'passed':report['passed'],'checks':len(results),'report':str(output)}))
    if not report['passed']: raise RuntimeError('Hardware protocol checks failed')


def status(output=None):
    """Read-only handoff check. Never creates credentials or renews the controller lease."""
    if not BUNDLE.is_file():
        raise FileNotFoundError('No existing hardware pair')
    data = json.loads(protect(BUNDLE.read_bytes(),decrypt=True))
    if 'ip_hint' not in data:
        raise RuntimeError('No USB-confirmed controller address')
    app = json.loads(adb(['exec-out','run-as',PACKAGE,'cat','no_backup/hardware_link_status.json']))
    with client_context(data) as ctx:
        code, board = request(data,ctx,'/v1/status')
    age = int(time.time()*1000)-int(app['updated_at'])
    good = (code == 200 and board.get('device') == DEVICE and board.get('protocol') == 1
            and board.get('live') is True and board.get('motion_enabled') is False
            and app.get('state') == 'connected' and app.get('motion_enabled') is False
            and 0 <= age <= 10000)
    report = {'state':'connected' if good else 'check_failed',
              'checked_at':datetime.now(timezone.utc).isoformat(), 'transport':'wifi_mtls',
              'controller_http':code, 'controller_live':board.get('live'),
              'controller_uptime_ms':board.get('uptime_ms'), 'android_state':app.get('state'),
              'android_status_age_ms':age, 'android_sequence':app.get('seq'),
              'android_failures':app.get('failures'), 'motion_enabled':board.get('motion_enabled')}
    if output:
        Path(output).write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(report))
    if not good:
        raise RuntimeError('Hardware link not confirmed')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action',choices=['pair','provision','import','probe','status'])
    parser.add_argument('--port',default='COM5')
    parser.add_argument('--output')
    args = parser.parse_args()
    if args.action=='pair': pair(); print('Private per-device pairing material ready.')
    elif args.action=='provision': provision(args.port)
    elif args.action=='import': android_import(pair())
    elif args.action=='status': status(args.output)
    else: probe(args.output or 'artifacts/rastag-hardware-probe.json')


if __name__=='__main__':
    try: main()
    except Exception as e:
        # Secret-bearing payloads and exceptions never go to CLI output.
        print('Hardware operation failed:',type(e).__name__)
        raise SystemExit(1)
