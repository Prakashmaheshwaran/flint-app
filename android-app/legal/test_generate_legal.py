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

    def test_license_is_inherited_from_resolved_parent_pom(self):
        parent = self.repo / "parent.pom"
        parent.write_bytes(self.pom.read_bytes())
        self.pom.write_text("<project><name>Child library</name></project>")
        self.entry["parents"] = [dict(coordinate="org.example:parent:1.0", pom=str(parent))]
        self.inventory.write_text(json.dumps([self.entry]))
        component = json.loads(self.run_generator()["dependency-list.json"])["components"][0]
        self.assertEqual(component["name"], "Child library")
        self.assertEqual(component["inheritedLicenseFrom"], "org.example:parent:1.0")
        self.assertEqual(component["licenses"][0]["family"], "Apache-2.0")

    def test_bsd_requires_the_actual_artifacts_full_license(self):
        self.pom.write_text(self.pom.read_text().replace("Apache-2.0", "BSD-3-Clause"))
        with self.assertRaisesRegex(LegalError, "No bundled full license text.*BSD-3-Clause"):
            self.run_generator()
        actual = (Path(__file__).parent / "licenses/desugar-2.1.5/SOURCE-NOTICES.txt").read_text()
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("LICENSE", actual)
        self.assertIn(actual.strip().encode(), self.run_generator()["open-source-licenses.txt"])

    def test_new_bundled_font_fails_until_licensed(self):
        (self.repo / "android-app/core/core-common/src/main/res/font/new_font.ttf").write_bytes(b"new")
        with self.assertRaisesRegex(LegalError, "font license inventory mismatch"):
            self.run_generator()

    def use_glance_protobuf(self, version="1.1.1"):
        self.pom.write_text(self.pom.read_text().replace("Apache-2.0", "BSD-3-Clause"))
        self.entry["coordinate"] = f"androidx.glance:glance-appwidget-external-protobuf:{version}"
        self.inventory.write_text(json.dumps([self.entry]))

    def test_reviewed_glance_includes_actual_upstream_bsd_copyright(self):
        self.use_glance_protobuf()
        actual = (Path(__file__).parent / "licenses/protobuf-28.2-LICENSE.txt").read_text()
        output = self.run_generator()
        self.assertIn(actual.strip().encode(), output["open-source-licenses.txt"])
        self.assertIn(b"Copyright 2008 Google Inc.", output["open-source-licenses.txt"])
        component = json.loads(output["dependency-list.json"])["components"][0]
        self.assertEqual(component["licenses"][0]["family"], "BSD-3-Clause")
        self.assertEqual(len([n for n in component["notices"] if "protobuf-28.2-LICENSE.txt" in n["source"]]), 1)

    def test_glance_version_upgrade_without_source_review_fails(self):
        self.use_glance_protobuf("1.1.2")
        with self.assertRaisesRegex(LegalError, "No bundled full license text"):
            self.run_generator()
        actual = (Path(__file__).parent / "licenses/protobuf-28.2-LICENSE.txt").read_text()
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("LICENSE", actual)
        with self.assertRaisesRegex(LegalError, "Missing reviewed upstream notices"):
            self.run_generator()

    def test_glance_missing_empty_and_holderless_source_license_fail(self):
        self.use_glance_protobuf()
        path = self.repo / "android-app/legal/licenses/protobuf-28.2-LICENSE.txt"
        actual = path.read_bytes()
        path.unlink()
        with self.assertRaises(FileNotFoundError):
            self.run_generator()
        path.write_text(" \n\t")
        with self.assertRaisesRegex(LegalError, "Required license/notice text is empty"):
            self.run_generator()
        path.write_bytes(actual.replace(b"Copyright 2008 Google Inc.  All rights reserved.\n", b""))
        with self.assertRaisesRegex(LegalError, "No bundled full license text"):
            self.run_generator()

    def test_glance_supplement_does_not_allow_unknown_license_family(self):
        self.use_glance_protobuf()
        self.pom.write_text(self.pom.read_text().replace("BSD-3-Clause", "Unknown license"))
        with self.assertRaisesRegex(LegalError, "Unknown license family"):
            self.run_generator()

    def test_glance_registry_addition_does_not_change_outputs_without_glance(self):
        with_glance_entry = self.run_generator()
        registry_path = self.repo / "android-app/legal/supplemental.json"
        registry = json.loads(registry_path.read_text())
        registry["supplements"] = [s for s in registry["supplements"] if "glance" not in s["coordinate"]]
        registry_path.write_text(json.dumps(registry))
        self.assertEqual(with_glance_entry, self.run_generator())

    def test_upgrade_requiring_source_notices_fails_until_reviewed(self):
        self.inventory.write_text(json.dumps([dict(self.entry, coordinate="com.android.tools:desugar_jdk_libs:99.0")]))
        with self.assertRaisesRegex(LegalError, "Missing reviewed upstream notices"):
            self.run_generator()

    def test_empty_inventory_fails(self):
        self.inventory.write_text("[]")
        with self.assertRaisesRegex(LegalError, "inventory is empty"):
            self.run_generator()

    def test_empty_required_font_family_and_supplement_text_fails(self):
        registry = json.loads((self.repo / "android-app/legal/supplemental.json").read_text())
        supplement = registry["supplements"][0]
        supplement["coordinate"] = "org.example:library:1.0"
        (self.repo / "android-app/legal/supplemental.json").write_text(json.dumps(registry))
        paths = [self.repo / registry["fonts"][0]["license"],
                 self.repo / "android-app/legal" / registry["families"]["Apache-2.0"],
                 self.repo / "android-app/legal" / supplement["files"][0]]
        for path in paths:
            original = path.read_bytes()
            try:
                for empty in ("", " \n\t\r\n"):
                    with self.subTest(path=path.name, empty=empty):
                        path.write_text(empty)
                        with self.assertRaisesRegex(LegalError, f"Required license/notice text is empty: .*{path.name}"):
                            self.run_generator()
            finally:
                path.write_bytes(original)

    def test_missing_required_license_file_fails(self):
        registry = json.loads((self.repo / "android-app/legal/supplemental.json").read_text())
        (self.repo / registry["fonts"][0]["license"]).unlink()
        with self.assertRaises(FileNotFoundError):
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
