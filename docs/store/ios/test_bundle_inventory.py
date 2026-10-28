import copy
import plistlib
import tempfile
import unittest
from pathlib import Path

from bundle_inventory import built_bundles, shipping_targets


class BundleInventoryTests(unittest.TestCase):
    def spec(self):
        def target(kind, identifier):
            return {"type": kind, "platform": "iOS", "settings": {
                "base": {"PRODUCT_BUNDLE_IDENTIFIER": identifier}}}
        targets = {"Flint": target("application", "com.flint.peakfocus")}
        for suffix in ["monitor", "report", "shield-config", "shield-action", "widgets"]:
            targets[suffix] = target("app-extension", f"com.flint.peakfocus.{suffix}")
        targets["Flint"]["dependencies"] = [{"target": name} for name in targets if name != "Flint"]
        targets["Tests"] = target("bundle.unit-test", "com.flint.peakfocus.tests")
        return {"name": "Flint", "targets": targets}

    def write_bundle(self, directory, identifier):
        directory.mkdir(parents=True)
        with (directory / "Info.plist").open("wb") as stream:
            plistlib.dump({"CFBundleIdentifier": identifier}, stream)

    def test_sixth_extension_is_derived_and_tests_excluded(self):
        targets = shipping_targets(self.spec())
        self.assertEqual(len(targets), 6)
        self.assertEqual(targets["widgets"], "com.flint.peakfocus.widgets")
        self.assertNotIn("Tests", targets)

    def test_future_extension_needs_no_python_inventory_change(self):
        spec = self.spec()
        spec["targets"]["future"] = copy.deepcopy(spec["targets"]["widgets"])
        spec["targets"]["future"]["settings"]["base"]["PRODUCT_BUNDLE_IDENTIFIER"] += ".future"
        spec["targets"]["Flint"]["dependencies"].append({"target": "future"})
        self.assertEqual(len(shipping_targets(spec)), 7)

    def test_declared_but_unembedded_extension_is_rejected(self):
        spec = self.spec()
        spec["targets"]["Flint"]["dependencies"].pop()
        with self.assertRaisesRegex(ValueError, "missing from host embedding"):
            shipping_targets(spec)

    def test_explicit_embed_false_is_rejected(self):
        spec = self.spec()
        spec["targets"]["Flint"]["dependencies"][-1]["embed"] = False
        with self.assertRaises(ValueError):
            shipping_targets(spec)

    def test_duplicate_source_id_is_rejected(self):
        spec = self.spec()
        spec["targets"]["widgets"]["settings"] = spec["targets"]["monitor"]["settings"]
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            shipping_targets(spec)

    def test_missing_or_variable_source_id_is_rejected(self):
        for identifier in [None, "", "$(PRODUCT_NAME)"]:
            spec = self.spec()
            spec["targets"]["widgets"]["settings"]["base"]["PRODUCT_BUNDLE_IDENTIFIER"] = identifier
            with self.assertRaises(ValueError):
                shipping_targets(spec)

    def test_missing_widget_bundle_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            app = Path(temporary) / "Flint.app"
            self.write_bundle(app, "com.flint.peakfocus")
            with self.assertRaisesRegex(ValueError, "Missing built bundles"):
                built_bundles(app, shipping_targets(self.spec()))

    def test_duplicate_or_unexpected_built_id_is_rejected(self):
        for identifier in ["com.flint.peakfocus", "unexpected"]:
            with tempfile.TemporaryDirectory() as temporary:
                app = Path(temporary) / "Flint.app"
                self.write_bundle(app, "com.flint.peakfocus")
                self.write_bundle(app / "PlugIns" / "bad.appex", identifier)
                with self.assertRaisesRegex(ValueError, "Unexpected or duplicate"):
                    built_bundles(app, shipping_targets(self.spec()))

    def test_every_expected_bundle_is_inspected(self):
        targets = shipping_targets(self.spec())
        with tempfile.TemporaryDirectory() as temporary:
            app = Path(temporary) / "Flint.app"
            self.write_bundle(app, targets["Flint"])
            for name, identifier in targets.items():
                if name != "Flint":
                    self.write_bundle(app / "PlugIns" / f"{name}.appex", identifier)
            self.assertEqual({entry[2] for entry in built_bundles(app, targets)}, set(targets))

    def test_host_and_extension_identifiers_cannot_be_swapped(self):
        with tempfile.TemporaryDirectory() as temporary:
            app = Path(temporary) / "Flint.app"
            self.write_bundle(app, "com.flint.peakfocus.widgets")
            with self.assertRaisesRegex(ValueError, "wrong host/extension location"):
                built_bundles(app, shipping_targets(self.spec()))


if __name__ == "__main__":
    unittest.main()
