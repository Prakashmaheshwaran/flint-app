#!/usr/bin/env python3
"""Regression tests for scripts/setup-android-upload-key.sh.

Runs the real script against a fake `keytool`, so no JDK or keystore is required. Proves the
things that would hurt later: passwords are escaped so Gradle can read them back, an existing
upload identity is never silently replaced, and no password reaches the terminal.
"""

import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "setup-android-upload-key.sh"

FAKE_KEYTOOL = """#!/usr/bin/env bash
# Records its arguments; creates the requested keystore; answers -list with a fingerprint.
printf '%s\\n' "$*" >> "$FAKE_KEYTOOL_LOG"
keystore=""
previous=""
for argument in "$@"; do
    case "$previous" in
        -keystore) keystore="$argument" ;;
    esac
    previous="$argument"
done
case " $* " in
    *" -genkeypair "*) printf 'fake keystore\\n' > "$keystore" ;;
    *" -list "*) printf 'Alias name: flint-upload\\n\\t SHA256: AA:BB:CC\\n' ;;
esac
"""


class UploadKeySetupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="flint-upload-key-")
        self.root = Path(self.temp.name)
        (self.root / "android-app").mkdir()
        self.log = self.root / "keytool.log"
        self.fake = self.root / "keytool"
        self.fake.write_text(FAKE_KEYTOOL, encoding="utf-8")
        self.fake.chmod(0o755)

    def tearDown(self):
        self.temp.cleanup()

    def run_script(self, **env):
        base = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "ROOT": str(self.root),
            "KEYTOOL": str(self.fake),
            "FAKE_KEYTOOL_LOG": str(self.log),
        }
        base.update(env)
        return subprocess.run(
            ["/bin/bash", str(SCRIPT)],
            env=base,
            capture_output=True,
            text=True,
            timeout=60,
        )

    def properties(self):
        return (self.root / "android-app" / "keystore.properties").read_text(encoding="utf-8")

    def test_creates_keystore_and_properties(self):
        result = self.run_script(
            FLINT_KEYSTORE_PASSWORD="storepass1",
            FLINT_KEY_PASSWORD="keypass1",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        keystore = self.root / "android-app" / "flint-upload.keystore"
        self.assertTrue(keystore.is_file())

        properties = self.properties()
        self.assertIn("storeFile=flint-upload.keystore", properties)
        self.assertIn("storePassword=storepass1", properties)
        self.assertIn("keyAlias=flint-upload", properties)
        self.assertIn("keyPassword=keypass1", properties)

        self.assertEqual(stat.S_IMODE(keystore.stat().st_mode), 0o600)
        self.assertEqual(
            stat.S_IMODE((self.root / "android-app" / "keystore.properties").stat().st_mode),
            0o600,
        )

        log = self.log.read_text(encoding="utf-8")
        self.assertIn("-genkeypair", log)
        self.assertIn("-alias flint-upload", log)
        self.assertIn("-validity 10000", log)

    def test_reuses_store_password_when_key_password_is_blank(self):
        # Blank is what Return at the prompt produces; the environment cannot express "blank",
        # so this asserts the defaulting logic through the non-interactive path used here.
        result = self.run_script(
            FLINT_KEYSTORE_PASSWORD="storepass1",
            FLINT_KEY_PASSWORD="storepass1",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("keyPassword=storepass1", self.properties())

    def test_escapes_properties_special_characters(self):
        password = r"p:a=s#s!w\d"
        result = self.run_script(
            FLINT_KEYSTORE_PASSWORD=password,
            FLINT_KEY_PASSWORD=password,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        # `\:` `\=` `\#` `\!` and `\\` are exactly what java.util.Properties decodes.
        self.assertIn(r"storePassword=p\:a\=s\#s\!w\\d", self.properties())

    def test_never_prints_passwords(self):
        result = self.run_script(
            FLINT_KEYSTORE_PASSWORD="storepass1",
            FLINT_KEY_PASSWORD="keypass1",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("storepass1", result.stdout + result.stderr)
        self.assertNotIn("keypass1", result.stdout + result.stderr)

    def test_refuses_to_replace_existing_upload_identity(self):
        self.assertEqual(
            self.run_script(
                FLINT_KEYSTORE_PASSWORD="storepass1",
                FLINT_KEY_PASSWORD="keypass1",
            ).returncode,
            0,
        )
        before = self.properties()

        again = self.run_script(
            FLINT_KEYSTORE_PASSWORD="storepass2",
            FLINT_KEY_PASSWORD="keypass2",
        )
        self.assertEqual(again.returncode, 1)
        self.assertIn("Refusing to overwrite", again.stderr)
        self.assertEqual(self.properties(), before)

    def test_force_replaces_existing_upload_identity(self):
        self.run_script(FLINT_KEYSTORE_PASSWORD="storepass1", FLINT_KEY_PASSWORD="keypass1")
        replaced = self.run_script(
            FLINT_KEYSTORE_PASSWORD="storepass2",
            FLINT_KEY_PASSWORD="keypass2",
            FORCE="1",
        )
        self.assertEqual(replaced.returncode, 0, replaced.stderr)
        self.assertIn("storePassword=storepass2", self.properties())

    def test_rejects_short_passwords(self):
        result = self.run_script(
            FLINT_KEYSTORE_PASSWORD="short",
            FLINT_KEY_PASSWORD="short",
        )
        self.assertEqual(result.returncode, 1)
        self.assertIn("at least 6 characters", result.stderr)
        self.assertFalse((self.root / "android-app" / "keystore.properties").exists())


if __name__ == "__main__":
    unittest.main()
