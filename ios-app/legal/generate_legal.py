#!/usr/bin/env python3
"""Generate Flint's bundled iOS privacy policy and open-source notices offline.

The canonical policy is copied byte for byte from docs/privacy-policy.md. Third-party
content is described by the reviewed inventory in components.json and its full license
text is embedded verbatim, so the app never fetches legal text at runtime. stdlib only;
no network.
"""

import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import re
import sys


LEGAL_DIR = Path("ios-app/Flint/Resources/Legal")
POLICY_NAME = "privacy-policy.md"
NOTICES_NAME = "open-source-licenses.md"
POLICY_SOURCE = Path("docs/privacy-policy.md")
INVENTORY = Path("ios-app/legal/components.json")
PROJECT_SPEC = Path("ios-app/project.yml")
FONT_SUFFIXES = frozenset({".ttf", ".otf", ".ttc", ".woff", ".woff2"})
OFL = "SIL Open Font License, Version 1.1"
MIT = "MIT License"
# Licenses already reviewed for the components this repository ships.
REVIEWED_LICENSES = {"font": OFL, "application": MIT}
# Build outputs and generated projects at ios-app/ are not reviewed sources. They are
# matched by location so a resource folder named "build" deeper in the tree is still scanned.
SKIPPED_ROOT_DIRECTORIES = frozenset({"DerivedData", "build", ".build"})
# Xcode's Debug bundles carry SwiftUI/Previews artifacts at the bundle root; they are
# first-party toolchain output, not third-party code.
ALLOWED_DEBUG_DYLIBS = frozenset({"__preview.dylib"})
NESTED_BUNDLE_SUFFIXES = (".appex", ".app", ".xctest")
# Xcode's dynamic re-link of a local Swift package product, e.g.
# FlintCore_-2E84F2DDD2CBE471_PackageProduct.framework.
PACKAGE_PRODUCT_FRAMEWORK = re.compile(r"([A-Za-z0-9_]+)_-[0-9A-F]+_PackageProduct\.framework")
REMOTE_PACKAGE_KEYS = frozenset({
    "url", "github", "from", "branch", "revision", "exactVersion", "version", "minorVersion",
    "majorVersion", "checksum", "registry",
})
# Inline constructs the Swift renderer does not implement. Every one of these must fail
# generation instead of shipping markup that would be displayed literally.
UNSUPPORTED_INLINE = (
    (re.compile(r"\*\*"), "bold (**)"),
    (re.compile(r"__"), "bold (__)"),
)
UNSUPPORTED_LINES = (
    (re.compile(r"^#{4,}"), "heading deeper than three levels"),
    (re.compile(r"^\s*>"), "block quote"),
    (re.compile(r"\|"), "table"),
    (re.compile(r"^\s*\*\s"), "asterisk list"),
    (re.compile(r"^\s*\+\s"), "plus list"),
    (re.compile(r"^\s*\d+[.)]\s"), "numbered list"),
    (re.compile(r"^\[[^\]]*\]:"), "reference link definition"),
    (re.compile(r"<"), "HTML"),
    (re.compile(r"!\["), "image"),
    (re.compile(r"`"), "code span"),
    (re.compile(r"^=+\s*$"), "setext heading underline"),
    (re.compile(r"^-+\s*$"), "setext heading underline"),
)
# Same pattern as FlintLegalMarkdown.inlineText: no brackets in a label, no parentheses
# or whitespace in a target.
LINK = re.compile(r"\[[^\[\]]+\]\([^()\s]+\)")
HEADING_PREFIXES = ("# ", "## ", "### ")
FENCE_CLOSE = "```"
FENCE_OPEN_TEXT = "```text"
FENCE_OPENERS = (FENCE_CLOSE, FENCE_OPEN_TEXT)


class LegalError(ValueError):
    pass


def digest(data):
    return hashlib.sha256(data).hexdigest()


def relative(repo, path):
    return path.relative_to(repo).as_posix()


def read_utf8(path, label):
    """Read reviewed text strictly: UTF-8, no byte-order mark, not empty."""
    data = path.read_bytes()
    if data.startswith(b"\xef\xbb\xbf"):
        raise LegalError(f"{label}: UTF-8 byte-order mark is not supported")
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise LegalError(f"{label}: not valid UTF-8 ({error})") from error
    if not text.strip():
        raise LegalError(f"Required text is empty: {label}")
    return text


def _build_output(entry, top):
    if entry.parent == top and (entry.name in SKIPPED_ROOT_DIRECTORIES or entry.name.endswith(".xcodeproj")):
        return True
    # SwiftPM's own build directory beside a local package manifest.
    return entry.name == ".build" and (entry.parent / "Package.swift").is_file()


def iter_paths(root, top=None):
    """Yield every path under root, skipping ios-app build outputs and generated projects."""
    top = root if top is None else top
    for entry in sorted(root.iterdir()):
        if entry.is_dir():
            if _build_output(entry, top):
                continue
            if not entry.is_symlink():
                yield from iter_paths(entry, top)
        yield entry


# ── Markdown subset ───────────────────────────────────────────────────────────
# Blocks are separated by blank lines. A block that starts with "# ", "## " or "### "
# is a single-line heading; a block whose every line starts with "- " is a list;
# otherwise the lines join into one wrapped paragraph. "```"/"```text" opens a verbatim
# block that a line exactly "```" closes. The only inline markup is [label](url).

def _blocks(text, source):
    blocks = []
    block = []
    fence = None
    for number, line in enumerate(text.splitlines(), 1):
        if fence is not None:
            if line == FENCE_CLOSE:
                blocks.append(("fence", fence))
                fence = None
            elif line.startswith(FENCE_CLOSE):
                raise LegalError(f"{source}:{number}: nested code fence inside a code block")
            else:
                fence.append(line)
            continue
        if line in FENCE_OPENERS:
            if block:
                raise LegalError(f"{source}:{number}: a code fence must start after a blank line")
            fence = []
            continue
        if not line.strip():
            if block:
                blocks.append(("text", block))
                block = []
            continue
        block.append(line)
    if fence is not None:
        raise LegalError(f"{source}: unclosed code fence")
    if block:
        blocks.append(("text", block))
    return blocks


def _check_text_lines(lines, source):
    for line in lines:
        for pattern, label in UNSUPPORTED_LINES:
            if pattern.search(line):
                raise LegalError(f"{source}: unsupported Markdown {label}: {line.strip()!r}")
        for pattern, label in UNSUPPORTED_INLINE:
            if pattern.search(line):
                raise LegalError(f"{source}: unsupported Markdown {label}: {line.strip()!r}")
        remainder = LINK.sub("", line)
        if "[" in remainder or "]" in remainder:
            raise LegalError(
                f"{source}: unsupported Markdown link or bracket; only [label](url) is rendered: "
                f"{line.strip()!r}")


def validate_markdown(text, source):
    """Reject anything the supported subset does not render as intended."""
    for kind, lines in _blocks(text, source):
        if kind == "fence":
            continue
        _check_text_lines(lines, source)
        if lines[0].startswith(HEADING_PREFIXES):
            if len(lines) > 1:
                raise LegalError(
                    f"{source}: a heading must be one line, but {lines[0].strip()!r} is followed by "
                    f"{len(lines) - 1} more line(s)")
        elif lines[0].startswith("- "):
            # The renderer would fold any other line into the previous item.
            for line in lines[1:]:
                if not line.startswith("- "):
                    raise LegalError(f"{source}: every line of a list must start with '- ': {line.strip()!r}")
        else:
            # The renderer would reflow these into the paragraph instead of a heading or list.
            for line in lines[1:]:
                if line.startswith(HEADING_PREFIXES) or line.startswith("- "):
                    raise LegalError(
                        f"{source}: start a heading or list after a blank line: {line.strip()!r}")


# ── Reviewed inventory ────────────────────────────────────────────────────────

def load_inventory(repo):
    path = repo / INVENTORY
    try:
        registry = json.loads(read_utf8(path, INVENTORY.as_posix()))
    except json.JSONDecodeError as error:
        raise LegalError(f"{INVENTORY.as_posix()}: invalid JSON ({error})") from error
    if registry.get("schemaVersion") != 1:
        raise LegalError(f"{INVENTORY.as_posix()}: unsupported schemaVersion {registry.get('schemaVersion')!r}")
    intro = registry.get("intro")
    if not isinstance(intro, list) or not intro or not all(isinstance(p, str) and p.strip() for p in intro):
        raise LegalError(f"{INVENTORY.as_posix()}: intro must be a non-empty list of paragraphs")
    components = registry.get("components")
    if not isinstance(components, list) or not components:
        raise LegalError(f"{INVENTORY.as_posix()}: components must be a non-empty list")
    seen = set()
    for component in components:
        for key in ("kind", "name", "licenseFile", "licenseFileSha256", "license", "source", "shippedIn"):
            if not isinstance(component.get(key), str) or not component[key].strip():
                raise LegalError(f"{INVENTORY.as_posix()}: component {component.get('name')!r} needs {key}")
        name = component["name"]
        if name in seen:
            raise LegalError(f"{INVENTORY.as_posix()}: duplicate component {name!r}")
        seen.add(name)
        if component["kind"] not in ("font", "application"):
            raise LegalError(f"{INVENTORY.as_posix()}: {name} has unreviewed kind {component['kind']!r}")
        files = component.get("files")
        if not isinstance(files, list):
            raise LegalError(f"{INVENTORY.as_posix()}: {name} needs a files list")
        for entry in files:
            if not isinstance(entry, dict) or not isinstance(entry.get("path"), str) \
                    or not isinstance(entry.get("sha256"), str):
                raise LegalError(f"{INVENTORY.as_posix()}: {name} needs file path and sha256 entries")
            _reviewed_relative(entry["path"], name)
        _reviewed_relative(component["licenseFile"], name)
        reviewed = REVIEWED_LICENSES[component["kind"]]
        if component["license"] != reviewed:
            raise LegalError(
                f"{INVENTORY.as_posix()}: {name} must declare {reviewed!r}; review any other license first")
        if component["kind"] == "font":
            if not files:
                raise LegalError(f"{INVENTORY.as_posix()}: font {name} declares no files")
            for entry in files:
                if Path(entry["path"]).suffix.lower() not in FONT_SUFFIXES:
                    raise LegalError(f"{INVENTORY.as_posix()}: {name} file {entry['path']!r} is not a font")
            bundles = component.get("bundles")
            if not isinstance(bundles, list) or not bundles:
                raise LegalError(f"{INVENTORY.as_posix()}: font {name} must declare the bundles that ship it")
            for bundle in bundles:
                if not isinstance(bundle, str) or not bundle.endswith((".app", ".appex")):
                    raise LegalError(f"{INVENTORY.as_posix()}: {name} has unreviewed bundle {bundle!r}")
        elif component.get("bundles"):
            raise LegalError(f"{INVENTORY.as_posix()}: {name} has no files and must not declare bundles")
        if component.get("changes") is not None and not isinstance(component["changes"], str):
            raise LegalError(f"{INVENTORY.as_posix()}: {name} changes must be a string")
    return registry


def _reviewed_relative(value, name):
    path = Path(value)
    if path.is_absolute() or ".." in path.parts or not value.strip():
        raise LegalError(f"{INVENTORY.as_posix()}: {name} path {value!r} must be repository-relative")


# ── Sources ───────────────────────────────────────────────────────────────────

def validate_policy(repo):
    path = repo / POLICY_SOURCE
    text = read_utf8(path, POLICY_SOURCE.as_posix())
    try:
        validate_markdown(text, POLICY_SOURCE.as_posix())
    except LegalError as error:
        raise LegalError(f"{error} (the iOS app renders only the Markdown subset described in "
                         "ios-app/legal/README.md)") from error
    return path.read_bytes()


def validate_project(repo):
    """Only local first-party packages may ship; no vendored or remote binaries."""
    spec = repo / PROJECT_SPEC
    text = read_utf8(spec, PROJECT_SPEC.as_posix())
    for number, line in enumerate(text.splitlines(), 1):
        stripped = line.strip()
        if line[:1] not in (" ", "\t", "#") and re.match(r"^packages\s*:", line) and line.rstrip() != "packages:":
            raise LegalError(f"{PROJECT_SPEC.as_posix()}:{number}: use a block packages: mapping")
        binary = re.match(r"^(?:-\s*)?(carthage|framework)\s*:", stripped)
        if binary:
            raise LegalError(
                f"{PROJECT_SPEC.as_posix()}:{number}: {binary.group(1)} dependencies are not reviewed; "
                "only local Packages/ sources and Apple sdk frameworks may ship")
    packages = _project_packages(text, PROJECT_SPEC.as_posix())
    for name, path in packages:
        # project.yml paths are relative to ios-app/.
        manifest = repo / "ios-app" / path / "Package.swift"
        if not manifest.is_file():
            raise LegalError(f"ios-app/{path}: local package {name} has no Package.swift")
        package_text = read_utf8(manifest, f"ios-app/{path}/Package.swift")
        if ".package(" in package_text:
            raise LegalError(
                f"ios-app/{path}/Package.swift: external .package( dependencies are not reviewed; "
                "Flint ships no third-party Swift packages")
        if ".binaryTarget(" in package_text:
            raise LegalError(
                f"ios-app/{path}/Package.swift: .binaryTarget( artifacts are not reviewed; "
                "Flint ships no prebuilt third-party binaries")
    for entry in iter_paths(repo / "ios-app"):
        relative_path = relative(repo, entry)
        if entry.name == "Package.resolved":
            raise LegalError(f"{relative_path}: resolved remote packages are not reviewed")
        if entry.suffix in (".framework", ".xcframework", ".a", ".dylib"):
            raise LegalError(f"{relative_path}: vendored binaries are not reviewed")


def _project_packages(text, source):
    lines = text.splitlines()
    start = None
    for index, line in enumerate(lines):
        if line.rstrip() == "packages:":
            start = index
            break
    if start is None:
        return []
    declared = {}
    current = None
    for index in range(start + 1, len(lines)):
        raw = lines[index]
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        stripped = raw.strip()
        if indent == 0:
            break
        if "\t" in raw[:indent]:
            raise LegalError(f"{source}: package declarations must be space-indented")
        if indent <= 2:
            if not stripped.endswith(":") or indent != 2:
                raise LegalError(f"{source}: unsupported packages entry {stripped!r}; use a block mapping")
            name = stripped[:-1].strip()
            if not re.fullmatch(r"[A-Za-z0-9_.+-]+", name):
                raise LegalError(f"{source}: unsupported package name {name!r}")
            if name in declared:
                raise LegalError(f"{source}: duplicate package {name!r}")
            declared[name] = {}
            current = name
            continue
        if current is None:
            raise LegalError(f"{source}: packages attribute {stripped!r} has no package entry")
        key, separator, value = stripped.partition(":")
        if not separator:
            raise LegalError(f"{source}: unsupported packages line {stripped!r}")
        key = key.strip()
        value = value.strip().strip("'\"")
        if key in REMOTE_PACKAGE_KEYS:
            raise LegalError(
                f"{source}: package {current} declares {key}:; only local Packages/ paths may ship")
        if key != "path":
            raise LegalError(f"{source}: unrecognized packages key {key!r} for {current}")
        if not value:
            raise LegalError(f"{source}: package {current} has an empty path")
        if not value.startswith("Packages/") or ".." in Path(value).parts:
            raise LegalError(f"{source}: package {current} path {value!r} must be under Packages/")
        if "path" in declared[current]:
            raise LegalError(f"{source}: package {current} declares path twice")
        declared[current]["path"] = value
    for name, keys in declared.items():
        if "path" not in keys:
            raise LegalError(f"{source}: package {name} declares no local path")
    return sorted((name, keys["path"]) for name, keys in declared.items())


def local_package_products(repo):
    """Library products of the local first-party packages project.yml declares."""
    spec = read_utf8(repo / PROJECT_SPEC, PROJECT_SPEC.as_posix())
    products = set()
    for _, path in _project_packages(spec, PROJECT_SPEC.as_posix()):
        manifest = repo / "ios-app" / path / "Package.swift"
        text = read_utf8(manifest, f"ios-app/{path}/Package.swift")
        products.update(re.findall(r'\.library\(\s*name:\s*"([A-Za-z0-9_]+)"', text))
    return products


def validate_components(repo, registry):
    declared_fonts = set()
    for component in registry["components"]:
        for entry in component["files"]:
            path = repo / entry["path"]
            if not path.is_file():
                raise LegalError(f"{entry['path']}: declared file for {component['name']} is missing")
            actual = digest(path.read_bytes())
            if actual != entry["sha256"]:
                raise LegalError(
                    f"{entry['path']}: sha256 {actual} does not match the reviewed "
                    f"{entry['sha256']} for {component['name']}")
            declared_fonts.add(entry["path"])
        license_path = repo / component["licenseFile"]
        if not license_path.is_file():
            raise LegalError(f"{component['licenseFile']}: license text for {component['name']} is missing")
        actual = digest(license_path.read_bytes())
        if actual != component["licenseFileSha256"]:
            raise LegalError(
                f"{component['licenseFile']}: sha256 {actual} does not match the reviewed "
                f"{component['licenseFileSha256']} for {component['name']}")
        license_text = read_utf8(license_path, component["licenseFile"])
        for number, line in enumerate(license_text.splitlines(), 1):
            if line.startswith(FENCE_CLOSE):
                raise LegalError(
                    f"{component['licenseFile']}:{number}: a code fence cannot be embedded in the notices")
        if component["kind"] == "font" and not component["licenseFile"].startswith("design/fonts/"):
            raise LegalError(
                f"{component['licenseFile']}: font licenses must come from design/fonts/")

    found = {relative(repo, path) for path in iter_paths(repo / "ios-app")
             if path.is_file() and path.suffix.lower() in FONT_SUFFIXES}
    undeclared = sorted(found - declared_fonts)
    missing = sorted(declared_fonts - found)
    if undeclared or missing:
        raise LegalError(
            "Font license inventory mismatch: "
            f"unreviewed={undeclared or 'none'}, missing={missing or 'none'}; "
            f"review the license and update {INVENTORY.as_posix()}")

    # The copy beside each bundled font must stay identical to design/fonts/.
    for path in iter_paths(repo / "ios-app"):
        if path.is_file() and path.name.endswith("-OFL.txt"):
            canonical = repo / "design/fonts" / path.name
            if not canonical.is_file():
                raise LegalError(f"{relative(repo, path)}: no design/fonts/{path.name} to compare")
            if path.read_bytes() != canonical.read_bytes():
                raise LegalError(
                    f"{relative(repo, path)}: differs from design/fonts/{path.name}; "
                    "copy the reviewed license text")


# ── Rendering ─────────────────────────────────────────────────────────────────

def render_notices(repo, registry):
    lines = ["# Open-source licenses", ""]
    for paragraph in registry["intro"]:
        lines.extend([paragraph, ""])
    for component in registry["components"]:
        lines.extend([f"## {component['name']}", ""])
        if component["files"]:
            lines.append("- Files: " + ", ".join(Path(f["path"]).name for f in component["files"]))
        lines.append("- Shipped in: " + component["shippedIn"])
        lines.append("- Source: " + component["source"])
        lines.append("- License: " + component["license"])
        if component.get("changes"):
            lines.append("- Changes: " + component["changes"])
        lines.append("")
        license_text = read_utf8(repo / component["licenseFile"], component["licenseFile"])
        # Embedded line for line, without trailing whitespace, so the bundled Markdown
        # stays byte-stable across editors.
        body = [line.rstrip() for line in license_text.splitlines()]
        while body and not body[-1]:
            body.pop()
        lines.extend([FENCE_OPEN_TEXT, "\n".join(body), FENCE_CLOSE, ""])
    return "\n".join(lines)


def build_outputs(repo, registry):
    """Validate sources, then return the exact bytes of each bundled document."""
    policy = validate_policy(repo)
    validate_project(repo)
    validate_components(repo, registry)
    notices = render_notices(repo, registry).encode("utf-8")
    validate_markdown(notices.decode("utf-8"), f"{LEGAL_DIR.as_posix()}/{NOTICES_NAME} (generated)")
    return {POLICY_NAME: policy, NOTICES_NAME: notices}


def validate_output_directory(repo, outputs):
    target = repo / LEGAL_DIR
    if not target.is_dir():
        return
    unexpected = sorted(p.name for p in target.iterdir() if p.name not in outputs and p.name != ".DS_Store")
    if unexpected:
        raise LegalError(
            f"{LEGAL_DIR.as_posix()}: unexpected {', '.join(unexpected)}; every file there ships in the "
            "app, so only the generated documents may live there")


def write_outputs(repo, outputs):
    target = repo / LEGAL_DIR
    target.mkdir(parents=True, exist_ok=True)
    changed = []
    for name, data in sorted(outputs.items()):
        path = target / name
        if path.is_file() and path.read_bytes() == data:
            continue  # The canonical policy is never rewritten with identical bytes.
        path.write_bytes(data)
        changed.append(relative(repo, path))
    return changed


def check_outputs(repo, outputs):
    drifted = []
    for name, data in sorted(outputs.items()):
        path = repo / LEGAL_DIR / name
        try:
            current = path.read_bytes()
        except FileNotFoundError:
            current = None
        if current != data:
            drifted.append(relative(repo, path))
    return drifted


# ── Built bundle ──────────────────────────────────────────────────────────────

def verify_bundle(repo, registry, app):
    app = Path(app)
    if not app.is_dir():
        raise LegalError(f"built app not found: {app}")
    legal = repo / LEGAL_DIR
    for name in (POLICY_NAME, NOTICES_NAME):
        committed = legal / name
        if not committed.is_file():
            raise LegalError(f"{relative(repo, committed)}: missing; run `make ios-legal` first")
        bundled = app / name
        if not bundled.is_file():
            raise LegalError(f"{app.name}/{name}: missing from the built bundle")
        if bundled.read_bytes() != committed.read_bytes():
            raise LegalError(f"{app.name}/{name}: differs from {relative(repo, committed)}")
    if (app / POLICY_NAME).read_bytes() != (repo / POLICY_SOURCE).read_bytes():
        raise LegalError(f"{app.name}/{POLICY_NAME}: differs from {POLICY_SOURCE.as_posix()}")

    bundles = [app]
    test_bundles = []
    plugins = app / "PlugIns"
    if plugins.is_dir():
        bundles.extend(sorted((p for p in plugins.iterdir() if p.is_dir() and p.suffix == ".appex"),
                              key=lambda p: p.name))
        # A hosted unit-test run injects its .xctest into the DerivedData app; it never ships.
        test_bundles = sorted(p.name for p in plugins.iterdir() if p.is_dir() and p.suffix == ".xctest")
    labels = [bundle.name for bundle in bundles]
    for component in registry["components"]:
        for bundle in component.get("bundles") or []:
            if bundle not in labels:
                raise LegalError(f"{bundle}: declared for {component['name']} but the built app has no such bundle")

    declared = {}
    for component in registry["components"]:
        for entry in component["files"]:
            declared.setdefault(Path(entry["path"]).name, set()).add(entry["sha256"])

    products = local_package_products(repo)
    report = []
    for bundle in bundles:
        debug_dylibs, package_frameworks = _reject_embedded_code(bundle, products)
        fonts = [path for path in _owned_files(bundle) if path.suffix.lower() in FONT_SUFFIXES]
        for path in fonts:
            hashes = declared.get(path.name)
            actual = digest(path.read_bytes())
            if not hashes or actual not in hashes:
                raise LegalError(
                    f"{bundle.name}/{path.relative_to(bundle).as_posix()}: undeclared third-party font; "
                    f"review its license and update {INVENTORY.as_posix()}")
        report.append((bundle, fonts, debug_dylibs, package_frameworks))

    for component in registry["components"]:
        if not component["files"]:
            continue
        names = [Path(entry["path"]).name for entry in component["files"]]
        hashes = [entry["sha256"] for entry in component["files"]]
        present = set()
        for bundle in bundles:
            files = {path.name: path for path in _owned_files(bundle)}
            if all(name in files and digest(files[name].read_bytes()) == hash_
                   for name, hash_ in zip(names, hashes)):
                present.add(bundle.name)
        expected = set(component["bundles"])
        if present != expected:
            raise LegalError(
                f"{component['name']}: expected in {sorted(expected)}, found in {sorted(present)}; "
                f"update its bundles in {INVENTORY.as_posix()}")

    print(f"Verified {app.name}: {POLICY_NAME}, {NOTICES_NAME}, {len(bundles)} bundle(s), "
          "no embedded third-party code")
    for bundle, fonts, debug_dylibs, package_frameworks in report:
        names = ", ".join(sorted(path.name for path in fonts)) or "no fonts"
        detail = ""
        if debug_dylibs:
            detail = f"; Xcode Debug dylib(s): {', '.join(debug_dylibs)}"
        if package_frameworks:
            detail += f"; first-party package framework(s): {', '.join(package_frameworks)}"
        print(f"  {bundle.name}: {len(fonts)} font file(s): {names}{detail}")
    if test_bundles:
        print(f"  Skipped Xcode test bundle(s), never shipped: {', '.join(test_bundles)}")
    return 0


def _owned_paths(bundle, root=None):
    """Every path this bundle owns. PlugIns/*.appex are verified on their own and
    PlugIns/*.xctest are never shipped; any other nested bundle is unreviewed."""
    root = bundle if root is None else root
    for entry in sorted(bundle.iterdir()):
        if entry.is_dir() and entry.name.endswith(NESTED_BUNDLE_SUFFIXES):
            # Only the host app's PlugIns are enumerated and verified separately.
            if (root.suffix == ".app" and entry.parent == root / "PlugIns"
                    and entry.suffix in (".appex", ".xctest")):
                continue
            raise LegalError(f"{root.name}/{entry.relative_to(root).as_posix()}: unreviewed nested bundle")
        yield entry
        if entry.is_dir():
            yield from _owned_paths(entry, root)


def _owned_files(bundle):
    return [path for path in _owned_paths(bundle) if path.is_file()]


def _reject_embedded_code(bundle, package_products=frozenset()):
    """Fail on third-party code; return the allowed Xcode Debug dylibs and first-party
    package frameworks."""
    label = bundle.name
    executable = _executable_name(bundle)
    allowed = ALLOWED_DEBUG_DYLIBS | {f"{executable}.debug.dylib"}
    allowed_present = set()
    package_frameworks = set()
    for path in _owned_paths(bundle):
        inside = path.relative_to(bundle).as_posix()
        if path.suffix in (".framework", ".xcframework"):
            # When a test target also links a local package, Xcode re-links its product
            # dynamically into the same DerivedData app, and later plain builds keep it.
            # That is Flint's own code; any other framework is refused. Its contents are
            # still scanned (fonts, dylibs) like the rest of the bundle.
            match = PACKAGE_PRODUCT_FRAMEWORK.fullmatch(path.name)
            if (match and match.group(1) in package_products and path.is_dir()
                    and path.parent == bundle / "Frameworks"):
                package_frameworks.add(path.name)
                continue
            raise LegalError(f"{label}/{inside}: embedded {path.suffix.lstrip('.')}; "
                             "third-party code is not reviewed")
        if path.suffix == ".dylib":
            if path.parent == bundle and path.name in allowed:
                allowed_present.add(path.name)
            else:
                raise LegalError(f"{label}/{inside}: embedded dynamic library; third-party code is not reviewed")
    for frameworks in (p for p in _owned_paths(bundle) if p.is_dir() and p.name == "Frameworks"):
        unexpected = sorted(child.name for child in frameworks.iterdir()
                            if not (frameworks.parent == bundle and child.name in package_frameworks))
        if unexpected:
            raise LegalError(
                f"{label}/{frameworks.relative_to(bundle).as_posix()}: non-empty Frameworks/ directory "
                f"({', '.join(unexpected)}); third-party code is not reviewed")
    return sorted(allowed_present), sorted(package_frameworks)


def _executable_name(bundle):
    info = bundle / "Info.plist"
    if not info.is_file():
        raise LegalError(f"{bundle.name}: missing Info.plist")
    try:
        with info.open("rb") as handle:
            plist = plistlib.load(handle)
    except (plistlib.InvalidFileException, ValueError) as error:
        raise LegalError(f"{bundle.name}/Info.plist: unreadable ({error})") from error
    executable = plist.get("CFBundleExecutable")
    if not isinstance(executable, str) or not executable.strip():
        raise LegalError(f"{bundle.name}/Info.plist: no CFBundleExecutable")
    return executable


# ── Command line ──────────────────────────────────────────────────────────────

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2],
                        help="repository root (default: this checkout)")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true",
                      help="fail if the committed bundled documents drift from their sources")
    mode.add_argument("--verify-bundle", type=Path, metavar="FLINT_APP",
                      help="verify legal documents, fonts and embedded code in a built Flint.app")
    args = parser.parse_args(argv)
    repo = Path(args.repo).resolve()
    try:
        registry = load_inventory(repo)
        if args.verify_bundle is not None:
            verify_bundle(repo, registry, args.verify_bundle)
            return 0
        outputs = build_outputs(repo, registry)
        validate_output_directory(repo, outputs)
        if args.check:
            drifted = check_outputs(repo, outputs)
            if drifted:
                for path in drifted:
                    print(f"Bundled legal document is out of date: {path}", file=sys.stderr)
                print("Run `make ios-legal` and commit the result.", file=sys.stderr)
                return 1
            print(f"Bundled legal documents match their sources ({len(outputs)} files)")
            return 0
        changed = write_outputs(repo, outputs)
        if changed:
            for path in changed:
                print(f"Updated {path}")
        else:
            print("Bundled legal documents were already up to date")
    except (LegalError, OSError, UnicodeError) as error:
        print(f"iOS legal generation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
