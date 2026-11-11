"""Compile and exercise the shipping ARG Rover controller without touching GPIOs."""
import subprocess
from pathlib import Path
import json
import re
import shutil
import os

root=Path(__file__).resolve().parents[1]
src=root/'firmware/rastag_link/src'
binary=root/('artifacts/arg-rover-tests.exe' if os.name=='nt' else 'artifacts/arg-rover-tests')
binary.parent.mkdir(parents=True,exist_ok=True)
zig=root/'artifacts/host-toolchain/ziglang/zig.exe'
compiler=[str(zig),'cc'] if zig.exists() else [shutil.which('cc') or shutil.which('gcc') or 'cc']
subprocess.run(compiler+['-std=c11','-Wall','-Wextra','-Werror','-O1',
               '-fsanitize=undefined','-fno-sanitize-recover=all','-I',str(src),str(src/'arg_rover.c'),str(src/'b0602.c'),
               str(src.parent/'tests/test_arg_rover.c'),'-o',str(binary)],check=True)
r=subprocess.run([str(binary)],capture_output=True,text=True,check=True)
print(r.stdout,end='')
(root/'artifacts/arg-rover-unit-tests.json').write_text(json.dumps({'passed':True,'assertions':int(re.search(r'PASS: (\d+)',r.stdout).group(1)),'ubsan':True},indent=2))
