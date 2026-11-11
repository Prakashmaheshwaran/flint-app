import contextlib
import hashlib
import io
import json
from pathlib import Path
import plistlib
import shutil
import tempfile
import unittest

import generate_legal
from generate_legal import LegalError, MIT, OFL, validate_markdown

REPO_ROOT = Path(__file__).resolve().parents[2]
POLICY = b"# Privacy policy\n\nCanonical bytes, including a [support link](https://example.test/support).\n"
LICENSE_TEXT = "MIT License\n\nCopyright (c) 2026 Fixture contributors\n\nPermission is hereby granted.\n"
OFL_TEXT = ("Copyright 2026 The Example Project Authors\n"
            "\n"
            "SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007\n"
            "\n"
            "1) Neither the Font Software nor any of its individual components may be sold.\n"
            "\n"
            "THE FONT SOFTWARE IS PROVIDED \"AS IS\".\n")
FONT_PATH = "ios-app/Flint/Resources/Fonts/ExampleSans-Regular.ttf"
FONT_BYTES = b"\x00\x01\x00\x00 font fixture"


def digest(data):
    return hashlib.sha256(data).hexdigest()


class FixtureRepo:
    """A temporary checkout shaped like the real one, with matching hashes."""

    def __init__(self, root):
        self.root = root

    def path(self, relative):
        return self.root / relative

    def write(self, relative, text):
        path = self.path(relative)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def write_bytes(self, relative, data):
        path = self.path(relative)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def inventory(self, intro=None, **overrides):
        font = dict(kind="font", name="Example Sans",
                    files=[dict(path=FONT_PATH, sha256=digest(FONT_BYTES))],
                    licenseFile="design/fonts/ExampleSans-OFL.txt",
                    licenseFileSha256=digest(OFL_TEXT.encode()),
                    license=OFL, source="https://example.test/fonts",
                    changes="static instance of the upstream variable font",
                    bundles=["Flint.app", "ExampleReport.appex"],
                    shippedIn="Flint app, Example report extension")
        application = dict(kind="application", name="Flint", files=[],
                           licenseFile="LICENSE", licenseFileSha256=digest(LICENSE_TEXT.encode()),
                           license=MIT, source="https://example.test/flint",
                           shippedIn="Flint app and all of its extensions")
        font.update(overrides.get("font", {}))
        application.update(overrides.get("application", {}))
        registry = dict(schemaVersion=1, intro=intro or ["Fixture legal introduction."],
                        components=[font, application])
        return self.write("ios-app/legal/components.json", json.dumps(registry, indent=2) + "\n")

    def project_yml(self, packages=None):
        packages = packages if packages is not None else "  FlintCore:\n    path: Packages/FlintCore\n"
        return self.write("ios-app/project.yml", f"""name: Flint

options:
  bundleIdPrefix: app.example

packages:
{packages}
targets:
  Flint:
    type: application
    sources: [Flint]
    dependencies:
      - package: FlintCore
""")

    def package_swift(self, body=None):
        body = body if body is not None else 'let package = Package(name: "FlintCore")\n'
        return self.write("ios-app/Packages/FlintCore/Package.swift", body)

    def app_bundle(self, root="Flint.app", executable="Flint"):
        app = self.path(root)
        app.mkdir(parents=True)
        with (app / "Info.plist").open("wb") as handle:
            plistlib.dump({"CFBundleExecutable": executable, "CFBundleName": root}, handle)
        return app


class LegalTestCase(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repo = FixtureRepo(Path(self.temporary.name).resolve())
        self.repo.write_bytes("docs/privacy-policy.md", POLICY)
        self.repo.write("LICENSE", LICENSE_TEXT)
        self.repo.write("design/fonts/ExampleSans-OFL.txt", OFL_TEXT)
        self.repo.write_bytes(FONT_PATH, FONT_BYTES)
        self.repo.write("ios-app/Flint/Resources/Fonts/ExampleSans-OFL.txt", OFL_TEXT)
        self.repo.project_yml()
        self.repo.package_swift()
        self.repo.inventory()

    def legal(self, name):
        return self.repo.path("ios-app/Flint/Resources/Legal") / name

    def generate(self):
        with contextlib.redirect_stdout(io.StringIO()):
            generate_legal.main(["--repo", str(self.repo.root)])

    def build(self):
        registry = generate_legal.load_inventory(self.repo.root)
        return generate_legal.build_outputs(self.repo.root, registry)

    def main(self, *args):
        """Run the CLI and return (exit code, stdout, stderr)."""
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            try:
                code = generate_legal.main(["--repo", str(self.repo.root), *args])
            except SystemExit as exit_:
                code = exit_.code
        return code, out.getvalue(), err.getvalue()

    def assert_legal_error(self, fragment):
        with self.assertRaises(LegalError) as caught:
            self.build()
        self.assertIn(fragment, str(caught.exception))
        return str(caught.exception)

    # ── generation and drift ──────────────────────────────────────────────

    def test_policy_is_copied_byte_for_byte(self):
        self.generate()
        self.assertEqual(self.legal("privacy-policy.md").read_bytes(), POLICY)
        self.repo.write_bytes("docs/privacy-policy.md", b"# Updated\r\n\r\nNew canonical bytes.\r\n")
        self.generate()
        self.assertEqual(self.legal("privacy-policy.md").read_bytes(), b"# Updated\r\n\r\nNew canonical bytes.\r\n")

    def test_notices_embed_inventory_and_license_texts(self):
        self.generate()
        notices = self.legal("open-source-licenses.md").read_text()
        self.assertIn("Fixture legal introduction.", notices)
        self.assertIn("## Example Sans", notices)
        self.assertIn("- Files: ExampleSans-Regular.ttf", notices)
        self.assertIn("- Shipped in: Flint app, Example report extension", notices)
        self.assertIn("- Changes: static instance of the upstream variable font", notices)
        self.assertIn("```text\n" + OFL_TEXT.rstrip("\n") + "\n```", notices)
        self.assertIn("## Flint", notices)
        self.assertIn("MIT License", notices)
        self.assertNotIn(str(self.repo.root), notices)

    def test_generation_is_reproducible_and_reports_no_change(self):
        self.generate()
        legal = self.repo.path("ios-app/Flint/Resources/Legal")
        before = {p.name: p.read_bytes() for p in legal.iterdir()}
        code, stdout, _ = self.main()
        self.assertEqual(code, 0)
        self.assertIn("already up to date", stdout)
        self.assertEqual(before, {p.name: p.read_bytes() for p in legal.iterdir()})

    def test_check_passes_on_a_clean_tree(self):
        self.generate()
        code, stdout, stderr = self.main("--check")
        self.assertEqual(code, 0, stderr)
        self.assertIn("match their sources", stdout)

    def test_check_fails_on_policy_drift(self):
        self.generate()
        self.repo.write_bytes("docs/privacy-policy.md", POLICY + b"Extra.\n")
        code, _, stderr = self.main("--check")
        self.assertEqual(code, 1)
        self.assertIn("ios-app/Flint/Resources/Legal/privacy-policy.md", stderr)
        self.assertIn("Run `make ios-legal` and commit the result.", stderr)

    def test_check_fails_on_notices_drift(self):
        self.generate()
        self.legal("open-source-licenses.md").write_text("# Edited by hand\n")
        code, _, stderr = self.main("--check")
        self.assertEqual(code, 1)
        self.assertIn("ios-app/Flint/Resources/Legal/open-source-licenses.md", stderr)
        self.assertIn("Run `make ios-legal` and commit the result.", stderr)
        self.assertEqual(self.legal("open-source-licenses.md").read_text(), "# Edited by hand\n")

    def test_check_reports_missing_bundled_output(self):
        code, _, stderr = self.main("--check")
        self.assertEqual(code, 1)
        self.assertIn("privacy-policy.md", stderr)
        self.assertFalse(self.legal("privacy-policy.md").exists())

    def test_check_does_not_write(self):
        self.generate()
        self.repo.write_bytes("docs/privacy-policy.md", POLICY + b"Extra.\n")
        before = self.legal("privacy-policy.md").read_bytes()
        self.main("--check")
        self.assertEqual(self.legal("privacy-policy.md").read_bytes(), before)

    def test_cli_reports_source_failures_with_the_standard_prefix(self):
        self.repo.write("LICENSE", LICENSE_TEXT + "changed\n")
        code, _, stderr = self.main("--check")
        self.assertEqual(code, 1)
        self.assertIn("iOS legal generation failed: LICENSE:", stderr)

    # ── Markdown subset ──────────────────────────────────────────────────

    def test_markdown_subset_accepts_the_supported_constructs(self):
        text = ("# Title\n\n## Section\n\nWrapped paragraph text\ncontinues here.\n\n"
                "- one item\n- two items\n\n```text\nverbatim > | * 1. ** `\n\nblank line kept\n```\n\n"
                "A [link](https://example.test/page) stays selectable text.\n")
        validate_markdown(text, "supported.md")

    def test_rejects_each_unsupported_markdown_construct(self):
        cases = [
            ("> quoted\n", "block quote"),
            ("| a | b |\n|---|---|\n", "table"),
            ("* item\n", "asterisk list"),
            ("+ item\n", "plus list"),
            ("1. item\n", "numbered list"),
            ("#### Deep heading\n", "heading deeper than three levels"),
            ("# Heading\nwrapped heading text\n", "heading must be one line"),
            ("## Heading\n\nparagraph\n", None),
            ("Paragraph with <b>html</b>.\n", "HTML"),
            ("![alt](image.png)\n", "image"),
            ("Inline `code` span.\n", "code span"),
            ("**bold** text\n", "bold (**)"),
            ("__bold__ text\n", "bold (__)"),
            ("[ref]: https://example.test\n", "reference link definition"),
            ("Title\n===\n", "setext heading underline"),
            ("Title\n---\n", "setext heading underline"),
            ("[dangling bracket]\n", "only [label](url)"),
            ("```text\nunclosed\n", "unclosed code fence"),
            ("```text\nouter\n```inner\n```\n", "nested code fence"),
            ("Paragraph\n```text\nfenced\n```\n", "must start after a blank line"),
        ]
        for text, fragment in cases:
            with self.subTest(text=text):
                if fragment is None:
                    validate_markdown(text, "ok.md")
                    continue
                with self.assertRaises(LegalError) as caught:
                    validate_markdown(text, "bad.md")
                self.assertIn(fragment, str(caught.exception))

    def test_rejects_a_policy_outside_the_subset(self):
        self.repo.write_bytes("docs/privacy-policy.md", b"# Policy\n\n> quoted\n")
        self.assert_legal_error("block quote")
        self.repo.write_bytes("docs/privacy-policy.md", b"# Policy\n\n| a | b |\n")
        self.assert_legal_error("table")
        self.repo.write_bytes("docs/privacy-policy.md", b"\xef\xbb\xbf# Policy\n")
        self.assert_legal_error("byte-order mark")
        self.repo.write_bytes("docs/privacy-policy.md", b"# Policy\n\n\xff\n")
        self.assert_legal_error("not valid UTF-8")

    def test_validates_the_generated_notices_too(self):
        self.repo.inventory(intro=["Flint ships | no third-party code."])
        self.assert_legal_error("unsupported Markdown table")

    def test_fenced_license_text_is_not_reparsed_as_markdown(self):
        self.assertIn(b"- Files: ExampleSans-Regular.ttf", self.build()["open-source-licenses.md"])
        self.assertIn(b"1) Neither the Font Software", self.build()["open-source-licenses.md"])

    # ── Source inventory ─────────────────────────────────────────────────

    def test_rejects_remote_swift_packages(self):
        for remote in ("    url: https://example.test/pkg\n    from: 1.0.0\n",
                       "    github: example/pkg\n",
                       "    path: Packages/FlintCore\n    branch: main\n"):
            with self.subTest(remote=remote):
                self.repo.project_yml(f"  Remote:\n{remote}")
                with self.assertRaises(LegalError) as caught:
                    self.build()
                self.assertRegex(str(caught.exception), "only local Packages/ paths may ship|unrecognized")

    def test_rejects_package_outside_packages_directory(self):
        self.repo.project_yml("  FlintCore:\n    path: ../shared/FlintCore\n")
        self.assert_legal_error("must be under Packages/")

    def test_rejects_unrecognized_packages_key(self):
        self.repo.project_yml("  FlintCore:\n    path: Packages/FlintCore\n    binaryTarget: Foo.xcframework\n")
        self.assert_legal_error("unrecognized packages key")

    def test_rejects_package_manifest_dependencies(self):
        self.repo.package_swift('let package = Package(dependencies: [.package(url: "https://e.test/p", from: "1.0.0")])\n')
        self.assert_legal_error("external .package( dependencies are not reviewed")

    def test_rejects_resolved_packages_and_vendored_binaries(self):
        for name, relative in (("Package.resolved", "ios-app/Packages/FlintCore/Package.resolved"),
                               ("framework", "ios-app/Flint/Resources/Vendor.framework/Vendor"),
                               ("xcframework", "ios-app/Flint/Resources/Vendor.xcframework/Info.plist"),
                               ("archive", "ios-app/Flint/libvendor.a"),
                               ("dylib", "ios-app/Flint/libvendor.dylib")):
            with self.subTest(name=name):
                self.repo.write_bytes(relative, b"vendored")
                try:
                    with self.assertRaises(LegalError) as caught:
                        self.build()
                finally:
                    self.repo.path(relative).unlink()
                    parent = self.repo.path(relative).parent
                    if parent.name.endswith((".framework", ".xcframework")):
                        shutil.rmtree(parent)
                self.assertRegex(str(caught.exception), "not reviewed")

    def test_rejects_carthage_and_binary_dependencies(self):
        for declaration in ("      - carthage: Vendor\n", "      - framework: Vendor.framework\n"):
            with self.subTest(declaration=declaration):
                self.repo.write("ios-app/project.yml",
                                self.repo.path("ios-app/project.yml").read_text() + declaration)
                with self.assertRaises(LegalError) as caught:
                    self.build()
                self.assertIn("are not reviewed", str(caught.exception))

    def test_allows_apple_sdk_dependencies(self):
        self.repo.write("ios-app/project.yml",
                        self.repo.path("ios-app/project.yml").read_text()
                        + "      - sdk: FamilyControls.framework\n")
        self.build()

    def test_rejects_unreviewed_font_files(self):
        self.repo.write_bytes("ios-app/Flint/Resources/Fonts/Extra-Bold.ttf", b"extra")
        message = self.assert_legal_error("Font license inventory mismatch")
        self.assertIn("Extra-Bold.ttf", message)

    def test_rejects_declared_font_that_is_missing(self):
        self.repo.path(FONT_PATH).unlink()
        self.assert_legal_error("is missing")

    def test_rejects_font_hash_mismatch(self):
        self.repo.write_bytes(FONT_PATH, b"changed bytes")
        self.assert_legal_error("does not match the reviewed")

    def test_rejects_license_hash_mismatch(self):
        self.repo.write("design/fonts/ExampleSans-OFL.txt", OFL_TEXT + "Extra clause.\n")
        self.assert_legal_error("ExampleSans-OFL.txt: sha256")

    def test_rejects_empty_license_text(self):
        self.repo.write("design/fonts/ExampleSans-OFL.txt", " \n\t")
        self.repo.inventory(font=dict(licenseFileSha256=digest(b" \n\t")))
        self.assert_legal_error("Required text is empty")

    def test_rejects_unreviewed_font_license(self):
        self.repo.inventory(font=dict(license="Apache License, Version 2.0"))
        self.assert_legal_error("must declare 'SIL Open Font License, Version 1.1'")

    def test_rejects_ofl_copy_beside_the_fonts_that_differs(self):
        self.repo.write("ios-app/Flint/Resources/Fonts/ExampleSans-OFL.txt", OFL_TEXT + "stale\n")
        self.assert_legal_error("differs from design/fonts/ExampleSans-OFL.txt")

    def test_rejects_application_license_hash_mismatch(self):
        self.repo.write("LICENSE", LICENSE_TEXT + "Extra.\n")
        message = self.assert_legal_error("LICENSE: sha256")
        self.assertIn("Flint", message)

    # ── Built bundle ─────────────────────────────────────────────────────

    def ready_bundle(self):
        """Generate the documents, then lay out the Debug bundles the real build makes."""
        self.generate()
        app = self.repo.app_bundle()
        shutil.copyfile(self.legal("privacy-policy.md"), app / "privacy-policy.md")
        shutil.copyfile(self.legal("open-source-licenses.md"), app / "open-source-licenses.md")
        shutil.copyfile(self.repo.path(FONT_PATH), app / "ExampleSans-Regular.ttf")
        report = app / "PlugIns/ExampleReport.appex"
        report.mkdir(parents=True)
        with (report / "Info.plist").open("wb") as handle:
            plistlib.dump({"CFBundleExecutable": "ExampleReport"}, handle)
        shutil.copyfile(self.repo.path(FONT_PATH), report / "ExampleSans-Regular.ttf")
        return app

    def verify(self, app):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = generate_legal.main(["--repo", str(self.repo.root), "--verify-bundle", str(app)])
        return code, out.getvalue(), err.getvalue()

    def assert_bundle_error(self, app, fragment):
        code, _, stderr = self.verify(app)
        self.assertEqual(code, 1, stderr)
        self.assertIn(fragment, stderr)
        return stderr

    def test_verify_bundle_accepts_a_matching_debug_build(self):
        app = self.ready_bundle()
        code, stdout, stderr = self.verify(app)
        self.assertEqual(code, 0, stderr)
        self.assertIn("Verified Flint.app: privacy-policy.md, open-source-licenses.md, 2 bundle(s)", stdout)
        self.assertIn("Flint.app: 1 font file(s): ExampleSans-Regular.ttf", stdout)
        self.assertIn("ExampleReport.appex: 1 font file(s): ExampleSans-Regular.ttf", stdout)

    def test_verify_bundle_requires_the_bundled_policy(self):
        app = self.ready_bundle()
        (app / "privacy-policy.md").unlink()
        self.assert_bundle_error(app, "Flint.app/privacy-policy.md: missing from the built bundle")
        (app / "privacy-policy.md").write_bytes(POLICY + b"Edited.\n")
        self.assert_bundle_error(app, "Flint.app/privacy-policy.md: differs from")

    def test_verify_bundle_requires_the_bundled_notices(self):
        app = self.ready_bundle()
        (app / "open-source-licenses.md").write_bytes(b"# Edited\n")
        self.assert_bundle_error(app, "Flint.app/open-source-licenses.md: differs from")

    def test_verify_bundle_rejects_an_undeclared_font_in_an_extension(self):
        app = self.ready_bundle()
        (app / "PlugIns/ExampleReport.appex/Unreviewed.ttf").write_bytes(b"extra")
        stderr = self.assert_bundle_error(app, "undeclared third-party font")
        self.assertIn("ExampleReport.appex/Unreviewed.ttf", stderr)

    def test_verify_bundle_rejects_a_font_with_unreviewed_bytes(self):
        app = self.ready_bundle()
        (app / "PlugIns/ExampleReport.appex/ExampleSans-Regular.ttf").write_bytes(b"replaced")
        self.assert_bundle_error(app, "undeclared third-party font")

    def test_verify_bundle_rejects_a_declared_bundle_that_lacks_the_font(self):
        app = self.ready_bundle()
        (app / "PlugIns/ExampleReport.appex/ExampleSans-Regular.ttf").unlink()
        stderr = self.assert_bundle_error(app, "expected in ['ExampleReport.appex', 'Flint.app']")
        self.assertIn("found in ['Flint.app']", stderr)

    def test_verify_bundle_rejects_a_missing_declared_bundle(self):
        app = self.ready_bundle()
        shutil.rmtree(app / "PlugIns")
        self.assert_bundle_error(app, "declared for Example Sans but the built app has no such bundle")

    def test_verify_bundle_rejects_a_font_in_an_undeclared_bundle(self):
        app = self.ready_bundle()
        extra = app / "PlugIns/Other.appex"
        extra.mkdir()
        with (extra / "Info.plist").open("wb") as handle:
            plistlib.dump({"CFBundleExecutable": "Other"}, handle)
        shutil.copyfile(self.repo.path(FONT_PATH), extra / "ExampleSans-Regular.ttf")
        stderr = self.assert_bundle_error(app, "found in ['ExampleReport.appex', 'Flint.app', 'Other.appex']")
        self.assertIn("expected in ['ExampleReport.appex', 'Flint.app']", stderr)

    def test_verify_bundle_rejects_embedded_third_party_code(self):
        cases = [("Flint.app/Frameworks/Vendor.framework/Vendor", "embedded framework"),
                 ("Flint.app/PlugIns/ExampleReport.appex/Vendor.xcframework/Info.plist", "embedded xcframework"),
                 ("Flint.app/PlugIns/ExampleReport.appex/libvendor.dylib", "embedded dynamic library"),
                 ("Flint.app/Frameworks/Assets", "non-empty Frameworks/ directory")]
        for relative, fragment in cases:
            with self.subTest(relative=relative):
                app = self.ready_bundle()
                try:
                    self.repo.write_bytes(f"Flint.app/{relative.removeprefix('Flint.app/')}", b"binary")
                    self.assert_bundle_error(app, fragment)
                finally:
                    shutil.rmtree(app)

    def test_verify_bundle_allows_empty_frameworks_directory(self):
        app = self.ready_bundle()
        (app / "Frameworks").mkdir()
        code, _, stderr = self.verify(app)
        self.assertEqual(code, 0, stderr)

    def test_verify_bundle_allows_xcode_debug_dylibs(self):
        app = self.ready_bundle()
        (app / "Flint.debug.dylib").write_bytes(b"debug")
        (app / "__preview.dylib").write_bytes(b"preview")
        # Each extension gets its own Debug artifacts, verified in its own pass.
        (app / "PlugIns/ExampleReport.appex/ExampleReport.debug.dylib").write_bytes(b"debug")
        (app / "PlugIns/ExampleReport.appex/__preview.dylib").write_bytes(b"preview")
        code, stdout, stderr = self.verify(app)
        self.assertEqual(code, 0, stderr)
        self.assertIn("no embedded third-party code", stdout)
        self.assertIn("Flint.app: 1 font file(s): ExampleSans-Regular.ttf; "
                      "Xcode Debug dylib(s): Flint.debug.dylib, __preview.dylib", stdout)
        self.assertIn("ExampleReport.debug.dylib", stdout)

    def test_verify_bundle_rejects_other_root_dylibs(self):
        app = self.ready_bundle()
        (app / "Helper.debug.dylib").write_bytes(b"not this bundle's executable")
        self.assert_bundle_error(app, "Helper.debug.dylib: embedded dynamic library")
        (app / "Helper.debug.dylib").unlink()
        (app / "Sub").mkdir()
        (app / "Sub/Flint.debug.dylib").write_bytes(b"nested")
        self.assert_bundle_error(app, "Sub/Flint.debug.dylib: embedded dynamic library")


class RealRepositoryTest(unittest.TestCase):
    def test_real_checkout_is_clean(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = generate_legal.main(["--repo", str(REPO_ROOT), "--check"])
        self.assertEqual(code, 0, err.getvalue())


if __name__ == "__main__":
    unittest.main()
