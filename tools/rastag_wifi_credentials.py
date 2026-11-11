"""Local interactive Wi-Fi entry. Windows DPAPI protects the file at rest."""
import ctypes
import json
import os
from pathlib import Path

STORE = Path(os.environ['LOCALAPPDATA']) / 'ARG/RastagHardwareLink/wifi.dpapi'


class Blob(ctypes.Structure):
    _fields_ = [('size', ctypes.c_ulong), ('data', ctypes.POINTER(ctypes.c_ubyte))]


def protect(data, decrypt=False):
    source = ctypes.create_string_buffer(data)
    incoming = Blob(len(data), ctypes.cast(source, ctypes.POINTER(ctypes.c_ubyte)))
    outgoing = Blob()
    api = ctypes.windll.crypt32.CryptUnprotectData if decrypt else ctypes.windll.crypt32.CryptProtectData
    if not api(ctypes.byref(incoming), None, None, None, None, 1, ctypes.byref(outgoing)):
        raise ctypes.WinError()
    try:
        return ctypes.string_at(outgoing.data, outgoing.size)
    finally:
        ctypes.windll.kernel32.LocalFree(outgoing.data)


def load():
    return json.loads(protect(STORE.read_bytes(), decrypt=True))


def main():
    import tkinter as tk
    from tkinter import messagebox
    root = tk.Tk()
    root.title('Rastag - ESP32 Wi-Fi setup')
    root.geometry('470x260')
    root.attributes('-topmost', True)
    tk.Label(root, text='اتصال امن رستاگ و ESP32 به Wi-Fi', font=('Tahoma', 12)).pack(pady=12)
    tk.Label(root, text='Wi-Fi name / نام شبکه').pack()
    ssid = tk.Entry(root, width=40)
    ssid.insert(0, 'Master24')
    ssid.pack()
    tk.Label(root, text='Wi-Fi password / رمز شبکه').pack(pady=(10, 0))
    password = tk.Entry(root, width=40, show='*')
    password.pack()
    tk.Label(root, text='رمز به‌صورت رمزگذاری‌شده روی همین کامپیوتر ذخیره می‌شود.', font=('Tahoma', 9)).pack(pady=8)

    def save():
        name, secret = ssid.get(), password.get()
        if not 1 <= len(name.encode('utf-8')) <= 32 or not 8 <= len(secret.encode('utf-8')) <= 63:
            messagebox.showerror('Wi-Fi', 'SSID: 1–32 bytes; WPA2 password: 8–63 bytes.')
            return
        data = json.dumps({'ssid': name, 'password': secret}, ensure_ascii=False).encode('utf-8')
        STORE.parent.mkdir(parents=True, exist_ok=True)
        STORE.write_bytes(protect(data))
        password.delete(0, tk.END)
        root.destroy()

    tk.Button(root, text='ذخیره برای اتصال / Save', command=save, width=26).pack()
    password.focus_set()
    root.mainloop()


if __name__ == '__main__':
    main()
