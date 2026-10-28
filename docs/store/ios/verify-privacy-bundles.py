#!/usr/bin/env python3
"""Verify privacy resources in an unsigned simulator build or signed archive app."""
import argparse
import plistlib
from pathlib import Path
from bundle_inventory import built_bundles, load_shipping_targets


def verify(app: Path) -> None:
    root = Path(__file__).resolve().parents[3]
    targets = load_shipping_targets(root)
    for bundle, info, target in built_bundles(app, targets):
        bundle_id = info["CFBundleIdentifier"]
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


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path, help="Path to the built Flint.app")
    verify(parser.parse_args().app)
