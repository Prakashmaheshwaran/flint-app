"""Read-only one-guest and absent-PIN guard; run only under the shared device lock."""
import json
from pathlib import Path
import plistlib
import subprocess
import sys

device = '3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473'
group = 'group.com.flint.peakfocus'
devices = [d for entries in json.loads(subprocess.check_output(
    ['xcrun', 'simctl', 'list', 'devices', 'available', '-j'], text=True
))['devices'].values() for d in entries]
assert any(d['udid'] == device for d in devices), 'Authorized guest is missing'
booted = [d['udid'] for d in devices if d['state'] == 'Booted']
assert all(d == device for d in booted), 'Another guest is booted; refusing to alter it'
processes = [Path(line.strip()).name for line in subprocess.check_output(
    ['ps', '-axo', 'comm'], text=True
).splitlines()[1:]]
assert not any(name.startswith('qemu-system-') or name == 'emulator' for name in processes), 'Android guest is active'

paths = []
data = subprocess.run(['xcrun', 'simctl', 'get_app_container', device, 'com.flint.peakfocus', 'data'],
                      text=True, capture_output=True)
if data.returncode == 0:
    paths.append(Path(data.stdout.strip()) / 'Library/Preferences' / f'{group}.plist')
shared = Path.home() / 'Library/Developer/CoreSimulator/Devices' / device / 'data/Containers/Shared/AppGroup'
if shared.exists():
    for container in shared.iterdir():
        metadata = container / '.com.apple.mobile_container_manager.metadata.plist'
        if metadata.exists() and plistlib.loads(metadata.read_bytes()).get('MCMMetadataIdentifier') == group:
            paths.append(container / 'Library/Preferences' / f'{group}.plist')
present = any(any(key in plistlib.loads(p.read_bytes()) for key in ('flint.pin.hash', 'flint.pin.salt'))
              for p in paths if p.exists())
report = {'guest': device, 'bootedGuests': booted, 'appInstalled': data.returncode == 0,
          'examinedPreferenceFiles': sum(p.exists() for p in paths), 'pinPresent': present}
if len(sys.argv) > 1:
    Path(sys.argv[1]).write_text(json.dumps(report, indent=2) + '\n')
assert not present, 'PIN exists: refusing to alter or clear app data'
print('Authorized guest guard passed; current PIN absent. No values read into logs.')
