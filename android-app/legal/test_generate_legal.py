import io
import json
from pathlib import Path
import shutil
import tempfile
import unittest
import zipfile

from generate_legal import LegalError, embedded_notices, generate, license_family, pom_metadata


class LegalGeneratorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name)
        shutil.copytree(Path(__file__).parent / "licenses", self.repo / "android-app/legal/licenses")
        shutil.copyfile(Path(__file__).parent / "supplemental.json", self.repo / "android-app/legal/supplemental.json")
        registry = json.loads((self.repo / "android-app/legal/supplemental.json").read_text())
        for font in registry["fonts"]:
            license_file = self.repo / font["license"]
            license_file.parent.mkdir(parents=True, exist_ok=True)
            license_file.write_text(f"Copyright {font['name']}\nSIL OPEN FONT LICENSE Version 1.1\nFull fixture text")
            for filename in font["files"]:
                path = self.repo / "android-app/core/core-common/src/main/res/font" / filename
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"font fixture")
        self.policy = self.repo / "docs/privacy-policy.md"
        self.policy.parent.mkdir()
        self.policy.write_bytes(b"# Policy\n\nCanonical bytes \xe2\x80\x94 including links.\n")
        self.pom = self.repo / "library.pom"
        self.pom.write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0">
          <name>Example library</name><url>https://example.test/library</url><licenses><license>
          <name>Apache-2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
          </license></licenses></project>''')
        self.jar = self.repo / "library.jar"
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("META-INF/NOTICE.txt", "Actual upstream attribution\nRetain every line")
        self.inventory = self.repo / "inventory.json"
        self.entry = dict(coordinate="org.example:library:1.0", pom=str(self.pom),
                          artifacts=[str(self.jar)], scopes=["releaseRuntimeClasspath"])
        self.inventory.write_text(json.dumps([self.entry]))
        self.output = self.repo / "output"

    def run_generator(self):
        generate(self.inventory, self.repo, self.output)
        return {p.name: p.read_bytes() for p in (self.output / "legal").iterdir()}

    def test_policy_is_copied_verbatim_and_refreshes_on_edit(self):
        self.assertEqual(self.run_generator()["privacy-policy.md"], self.policy.read_bytes())
        self.policy.write_bytes(b"# Updated policy\r\n\r\nNew canonical content\r\n")
        self.assertEqual(self.run_generator()["privacy-policy.md"], self.policy.read_bytes())

    def test_full_notices_and_font_licenses_are_included_without_local_paths(self):
        output = self.run_generator()
        text = output["open-source-licenses.txt"].decode()
        self.assertIn("Actual upstream attribution\nRetain every line", text)
        self.assertIn("END OF TERMS AND CONDITIONS", text)
        self.assertIn("SIL OPEN FONT LICENSE Version 1.1", text)
        self.assertNotIn(str(self.repo), text)
        self.assertNotIn(str(self.repo), output["dependency-list.json"].decode())

    def test_generation_is_byte_reproducible_and_inventory_order_independent(self):
        second = dict(self.entry, coordinate="org.example:second:2.0")
        self.inventory.write_text(json.dumps([second, self.entry]))
        first = self.run_generator()
        self.inventory.write_text(json.dumps([self.entry, second]))
        self.assertEqual(first, self.run_generator())

    def test_unknown_license_fails_even_with_plausible_url(self):
        with self.assertRaisesRegex(LegalError, "Unknown license family"):
            license_family("Unreviewed License", "https://www.apache.org/licenses/LICENSE-2.0.txt")

    def test_missing_license_declaration_fails(self):
        self.pom.write_text("<project><name>Unknown</name></project>")
        with self.assertRaisesRegex(LegalError, "Missing license declaration"):
            pom_metadata(self.pom)

    def test_new_bundled_font_fails_until_licensed(self):
        (self.repo / "android-app/core/core-common/src/main/res/font/new_font.ttf").write_bytes(b"new")
        with self.assertRaisesRegex(LegalError, "font license inventory mismatch"):
            self.run_generator()

    def test_upgrade_requiring_source_notices_fails_until_reviewed(self):
        self.inventory.write_text(json.dumps([dict(self.entry, coordinate="com.android.tools:desugar_jdk_libs:99.0")]))
        with self.assertRaisesRegex(LegalError, "Missing reviewed upstream notices"):
            self.run_generator()

    def test_empty_inventory_fails(self):
        self.inventory.write_text("[]")
        with self.assertRaisesRegex(LegalError, "inventory is empty"):
            self.run_generator()

    def test_nested_aar_notices_are_preserved(self):
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, "w") as jar:
            jar.writestr("META-INF/LICENSE", "Full nested license\nCopyright upstream")
            jar.writestr("META-INF/NOTICE.txt", "Full nested notice")
        aar = io.BytesIO()
        with zipfile.ZipFile(aar, "w") as archive:
            archive.writestr("classes.jar", nested.getvalue())
            archive.writestr("NOTICE", "Top-level notice")
        self.assertEqual(embedded_notices(aar.getvalue()), [
            ("NOTICE", "Top-level notice"),
            ("classes.jar!/META-INF/LICENSE", "Full nested license\nCopyright upstream"),
            ("classes.jar!/META-INF/NOTICE.txt", "Full nested notice"),
        ])


if __name__ == "__main__":
    unittest.main()
