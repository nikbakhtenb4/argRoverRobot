"""Host tests for the actual C parser/control sources; no device access or actuator commands."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--zig', type=Path, help='Path to a Zig compiler (tested: 0.13.0)')
    args = parser.parse_args()
    zig = args.zig or ROOT / 'artifacts/host-toolchain/ziglang/zig.exe'
    compiler = [str(zig), 'cc'] if zig.is_file() else [shutil.which('cc') or shutil.which('gcc') or 'cc']
    sources = ROOT / 'firmware/rastag_link/src'
    output = ROOT / 'artifacts' / ('rastag-esp32-control-tests.exe' if os.name == 'nt' else 'rastag-esp32-control-tests')
    output.parent.mkdir(parents=True, exist_ok=True)
    command = compiler + ['-std=c11', '-Wall', '-Wextra', '-Werror', '-O1', '-fsanitize=undefined',
              '-fno-sanitize-recover=all', '-I', str(sources), str(sources.parent / 'tests/test_control.c')]
    command += [str(sources / name) for name in ('b0602.c', 'motion_control.c', 'link_protocol.c', 'lidar_wire.c')]
    subprocess.run(command + ['-o', str(output)], check=True)
    run = subprocess.run([str(output)], capture_output=True, text=True, check=True, cwd=ROOT)
    print(run.stdout, end='')
    fixture = ROOT / 'mobility-api/src/test/resources/lidar-rls2-golden.bin'
    if fixture.exists() and fixture.read_bytes() != (ROOT / 'artifacts/rastag-lidar-wire-golden.bin').read_bytes():
        raise RuntimeError('C encoder output differs from the Android protocol fixture')
    count = int(re.search(r'PASS: (\d+) assertions', run.stdout).group(1))
    (ROOT / 'artifacts/rastag-esp32-v2-unit-tests.json').write_text(json.dumps({
        'state': 'passed', 'assertions': count, 'fuzz_bytes': 300000,
        'undefined_behavior_sanitizer': True, 'hardware_config': 'lidar_rx27_legacy_generic_controller_fixture',
        'fixture': 'manufacturer V1.0 example and synthetic packets; signed angle offset and wrap verified'
    }, indent=2), encoding='utf-8')

if __name__ == '__main__':
    main()
