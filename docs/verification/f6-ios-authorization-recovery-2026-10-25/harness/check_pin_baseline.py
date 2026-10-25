"""Read-only PIN presence check; run only under the shared device lock."""
from pathlib import Path
import datetime
import json
import plistlib
import subprocess
import sys

device = "3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473"
group = "group.com.flint.peakfocus"
app_data = Path(subprocess.check_output([
    "xcrun", "simctl", "get_app_container", device, "com.flint.peakfocus", "data"
], text=True).strip())
paths = [app_data / "Library/Preferences" / f"{group}.plist"]
shared = Path.home() / "Library/Developer/CoreSimulator/Devices" / device / "data/Containers/Shared/AppGroup"
if shared.exists():
    for folder in shared.iterdir():
        metadata = folder / ".com.apple.mobile_container_manager.metadata.plist"
        if metadata.exists() and plistlib.loads(metadata.read_bytes()).get("MCMMetadataIdentifier") == group:
            paths.append(folder / "Library/Preferences" / f"{group}.plist")
present = any(
    any(key in plistlib.loads(path.read_bytes()) for key in ["flint.pin.hash", "flint.pin.salt"])
    for path in paths if path.exists()
)
record = Path(sys.argv[2])
data = json.loads(record.read_text()) if record.exists() else {"device": device, "checks": {}}
data["checks"][sys.argv[1]] = {
    "PINPresent": present,
    "checkedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "preferenceFilesFound": sum(path.exists() for path in paths),
}
record.write_text(json.dumps(data, indent=2) + "\n")
if present:
    raise SystemExit("F6 baseline blocked: inherited PIN is present; no state was cleared.")
print(f"F6 {sys.argv[1]} PIN baseline: absent (read-only check).")
