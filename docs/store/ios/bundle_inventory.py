"""Read the shipping bundle inventory from the same XcodeGen spec as the build."""
import json
import plistlib
import subprocess
from pathlib import Path


def shipping_targets(spec: dict) -> dict[str, str]:
    targets = spec["targets"]
    host_name = spec["name"]
    host = targets[host_name]
    if host.get("type") != "application" or host.get("platform") != "iOS":
        raise ValueError("Expected the project-named iOS application target")
    extensions = {
        name for name, target in targets.items()
        if target.get("type") == "app-extension" and target.get("platform") == "iOS"
    }
    embedded = {
        dependency["target"] for dependency in host.get("dependencies", [])
        if dependency.get("target") in extensions and dependency.get("embed", True)
    }
    if embedded != extensions:
        raise ValueError(f"Extensions missing from host embedding: {extensions - embedded}")
    inventory = {}
    for name in [host_name, *sorted(embedded)]:
        identifier = targets[name].get("settings", {}).get("base", {}).get("PRODUCT_BUNDLE_IDENTIFIER")
        if not isinstance(identifier, str) or not identifier or "$" in identifier:
            raise ValueError(f"Target needs a literal bundle identifier: {name}")
        if identifier in inventory.values():
            raise ValueError(f"Duplicate source bundle identifier: {identifier}")
        inventory[name] = identifier
    return inventory


def load_shipping_targets(repository: Path) -> dict[str, str]:
    # XcodeGen is already required to produce the app. Use its parser rather than a
    # second YAML parser, and fail on tool/spec errors instead of accepting fewer bundles.
    result = subprocess.run(
        ["xcodegen", "dump", "--spec", str(repository / "ios-app" / "project.yml"),
         "--type", "json", "--no-env"],
        check=True, capture_output=True, text=True,
    )
    return shipping_targets(json.loads(result.stdout))


def built_bundles(app: Path, targets: dict[str, str]) -> list[tuple[Path, dict, str]]:
    remaining = {identifier: name for name, identifier in targets.items()}
    result = []
    for bundle in [app, *sorted((app / "PlugIns").glob("*.appex"))]:
        with (bundle / "Info.plist").open("rb") as stream:
            info = plistlib.load(stream)
        identifier = info["CFBundleIdentifier"]
        target = remaining.pop(identifier, None)
        if target is None:
            raise ValueError(f"Unexpected or duplicate built bundle ID: {identifier}")
        if (bundle == app) != (target == next(iter(targets))):
            raise ValueError(f"Bundle is in the wrong host/extension location: {identifier}")
        result.append((bundle, info, target))
    if remaining:
        raise ValueError(f"Missing built bundles: {remaining}")
    return result
