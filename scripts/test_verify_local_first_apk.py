import tempfile
import unittest
import zipfile
import warnings
from pathlib import Path

from verify_local_first_apk import violations


class LocalFirstApkTest(unittest.TestCase):
    def check_apk(self, manifest=b"local manifest", dex=b"local classes", extra=None):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "release.apk"
            with zipfile.ZipFile(path, "w") as apk:
                apk.writestr("AndroidManifest.xml", manifest)
                if dex is not None:
                    apk.writestr("classes.dex", dex)
                for name, content in (extra or {}).items():
                    apk.writestr(name, content)
            return violations(path)

    def test_local_apk_passes(self):
        self.assertEqual([], self.check_apk())

    def test_utf8_network_permission_is_rejected(self):
        self.assertTrue(self.check_apk(manifest=b"android.permission.INTERNET"))

    def test_utf16_network_permission_is_rejected(self):
        self.assertTrue(self.check_apk(manifest="android.permission.ACCESS_NETWORK_STATE".encode("utf-16-le")))

    def test_firebase_code_is_rejected(self):
        self.assertTrue(self.check_apk(dex=b"Lcom/google/firebase/auth/FirebaseAuth;"))

    def test_connected_client_in_secondary_dex_is_rejected(self):
        self.assertTrue(self.check_apk(extra={"classes2.dex": b"Lcom/flint/peakfocus/feature/connected/ConnectedScreen;"}))

    def test_missing_classes_are_rejected(self):
        self.assertTrue(self.check_apk(dex=None))

    def test_missing_manifest_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "release.apk"
            with zipfile.ZipFile(path, "w") as apk:
                apk.writestr("classes.dex", b"local classes")
            self.assertTrue(violations(path))

    def test_unreadable_or_invalid_apk_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "release.apk"
            self.assertTrue(violations(path))
            path.write_bytes(b"not an APK")
            self.assertTrue(violations(path))

    def check_bundle(self, manifest=b"protobuf local manifest", dex=b"local classes", extra=None):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "release.aab"
            with zipfile.ZipFile(path, "w") as bundle:
                bundle.writestr("BundleConfig.pb", b"bundle config")
                if manifest is not None:
                    bundle.writestr("base/manifest/AndroidManifest.xml", manifest)
                if dex is not None:
                    bundle.writestr("base/dex/classes.dex", dex)
                for name, content in (extra or {}).items():
                    bundle.writestr(name, content)
            return violations(path)

    def test_local_bundle_passes(self):
        self.assertEqual([], self.check_bundle())

    def test_bundle_base_network_permissions_rejected(self):
        for permission in (b"android.permission.INTERNET", b"android.permission.ACCESS_NETWORK_STATE"):
            with self.subTest(permission=permission):
                self.assertTrue(self.check_bundle(manifest=b"\x0a\x1a" + permission))

    def test_bundle_feature_manifest_network_permission_rejected(self):
        self.assertTrue(self.check_bundle(extra={
            "future/manifest/AndroidManifest.xml": b"android.permission.INTERNET",
        }))

    def test_bundle_account_code_in_base_secondary_or_feature_dex_rejected(self):
        for name in ("base/dex/classes2.dex", "future/dex/classes.dex"):
            for prefix in (b"Lcom/google/firebase/auth/Auth;", b"Lcom/flint/peakfocus/feature/connected/Account;"):
                with self.subTest(name=name, prefix=prefix):
                    self.assertTrue(self.check_bundle(extra={name: prefix}))

    def test_bundle_requires_base_manifest_and_primary_dex(self):
        self.assertTrue(self.check_bundle(manifest=None))
        self.assertTrue(self.check_bundle(dex=None, extra={"future/dex/classes.dex": b"classes"}))

    def test_bundle_requires_bundle_config(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "release.aab"
            with zipfile.ZipFile(path, "w") as bundle:
                bundle.writestr("base/manifest/AndroidManifest.xml", b"manifest")
                bundle.writestr("base/dex/classes.dex", b"classes")
            self.assertTrue(violations(path))

    def test_empty_manifest_or_dex_rejected(self):
        self.assertTrue(self.check_bundle(manifest=b""))
        self.assertTrue(self.check_bundle(dex=b""))
        self.assertTrue(self.check_apk(manifest=b""))
        self.assertTrue(self.check_apk(dex=b""))

    def test_combined_apk_and_bundle_layout_rejected(self):
        self.assertTrue(self.check_bundle(extra={"AndroidManifest.xml": b"local"}))

    def test_duplicate_archive_entries_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "release.apk"
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with zipfile.ZipFile(path, "w") as apk:
                    apk.writestr("AndroidManifest.xml", b"safe")
                    apk.writestr("AndroidManifest.xml", b"android.permission.INTERNET")
                    apk.writestr("classes.dex", b"classes")
            self.assertTrue(violations(path))
