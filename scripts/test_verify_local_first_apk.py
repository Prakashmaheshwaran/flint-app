import tempfile
import unittest
import zipfile
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
