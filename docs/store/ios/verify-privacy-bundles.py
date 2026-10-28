#!/usr/bin/env python3
"""Verify privacy resources in an unsigned simulator build or signed archive app."""
import argparse
import plistlib
from pathlib import Path


def verify(app: Path) -> None:
    root = Path(__file__).resolve().parents[3]
    targets = {
        "Flint": "com.flint.peakfocus",
        "DeviceActivityMonitorExtension": "com.flint.peakfocus.monitor",
        "ShieldConfigurationExtension": "com.flint.peakfocus.shield-config",
        "ShieldActionExtension": "com.flint.peakfocus.shield-action",
        "DeviceActivityReportExtension": "com.flint.peakfocus.report",
    }
    bundles = [app, *sorted((app / "PlugIns").glob("*.appex"))]
    assert len(bundles) == 5, f"Expected host + four extensions, found {bundles}"
    remaining = dict(targets)
    for bundle in bundles:
        with (bundle / "Info.plist").open("rb") as stream:
            info = plistlib.load(stream)
        bundle_id = info["CFBundleIdentifier"]
        target = next((name for name, identifier in remaining.items() if identifier == bundle_id), None)
        assert target is not None, f"Unexpected or duplicate bundle ID: {bundle_id}"
        del remaining[target]
        source = root / "ios-app" / "Privacy" / target / "PrivacyInfo.xcprivacy"
        with source.open("rb") as stream:
            expected = plistlib.load(stream)
        with (bundle / "PrivacyInfo.xcprivacy").open("rb") as stream:
            actual = plistlib.load(stream)
        assert actual == expected, f"Manifest differs from source: {bundle}"
        assert actual["NSPrivacyTracking"] is False, bundle
        assert actual["NSPrivacyTrackingDomains"] == [], bundle
        assert actual["NSPrivacyCollectedDataTypes"] == [], bundle
        reasons = [{
            "NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryUserDefaults",
            "NSPrivacyAccessedAPITypeReasons": ["CA92.1", "1C8F.1"] if target == "Flint" else ["1C8F.1"],
        }]
        assert actual["NSPrivacyAccessedAPITypes"] == reasons, bundle
        print(f"Verified privacy manifest: {bundle_id}")
    assert not remaining, f"Missing bundles: {remaining}"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path, help="Path to the built Flint.app")
    verify(parser.parse_args().app)
