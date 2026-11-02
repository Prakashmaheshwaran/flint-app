#!/usr/bin/env python3
"""Generate offline legal assets from Gradle's resolved release artifacts, never the web."""

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET
import zipfile


class LegalError(ValueError):
    pass


def digest(data):
    return hashlib.sha256(data).hexdigest()


def license_family(name, url):
    """Explicit supported declarations. Unknown metadata must stop the build."""
    if name in {"The Apache Software License, Version 2.0", "The Apache License, Version 2.0", "Apache License, Version 2.0",
                "Apache 2.0", "Apache-2.0", "Apache License 2.0", "Apache License, version 2.0"}:
        return "Apache-2.0"
    if name == "GNU General Public License, version 2, with the Classpath Exception":
        return "GPL-2.0-with-classpath-exception"
    if name == "BSD-3-Clause":
        return "BSD-3-Clause"
    raise LegalError(f"Unknown license family: {name!r} ({url}); review and bundle its full text first")


def pom_root(path):
    root = ET.fromstring(path.read_bytes())
    # Maven POMs may use the Maven namespace or no namespace.
    for node in root.iter():
        node.tag = node.tag.rsplit("}", 1)[-1]
    return root


def pom_metadata(path, parents=()):
    root = pom_root(path)
    license_root = root
    inherited = None
    for parent in parents:
        if license_root.findall("./licenses/license"):
            break
        license_root = pom_root(Path(parent["pom"]))
        inherited = parent["coordinate"]
    licenses = []
    for node in license_root.findall("./licenses/license"):
        name = (node.findtext("name") or "").strip()
        url = (node.findtext("url") or "").strip()
        licenses.append(dict(name=name, url=url, family=license_family(name, url)))
    if not licenses:
        raise LegalError(f"Missing license declaration in {path.name}; inherited metadata needs explicit review")
    return dict(name=(root.findtext("name") or "").strip(),
                url=(root.findtext("url") or "").strip(), licenses=licenses,
                inheritedLicenseFrom=inherited)


def embedded_notices(data, prefix=""):
    """Preserve notice/license files, including those inside AAR classes/libs JARs."""
    result = []
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name in sorted(archive.namelist()):
            if name.endswith("/"):
                continue
            basename = name.rsplit("/", 1)[-1]
            if re.fullmatch(r"(?i)(license|licence|notice|copying|copyright)([._-].*)?", basename):
                # Class files or native binaries with similar names are not notices.
                if Path(basename).suffix.lower() in {".class", ".so"}:
                    continue
                text = archive.read(name).decode("utf-8-sig").strip()
                if text:
                    result.append((prefix + name, text))
            elif name.endswith(".jar"):
                result.extend(embedded_notices(archive.read(name), prefix + name + "!/"))
    return result


def generate(inventory_path, repo, output):
    legal = repo / "android-app/legal"
    registry = json.loads((legal / "supplemental.json").read_text())
    inventory = json.loads(inventory_path.read_text())
    if not inventory:
        raise LegalError("Release dependency inventory is empty")
    texts = {}

    def add_text(text, source):
        if not text.strip():
            raise LegalError(f"Required license/notice text is empty: {source}")
        normalized = text.strip() + "\n"
        key = digest(normalized.encode())
        texts[key] = normalized
        return key

    components = []
    for item in sorted(inventory, key=lambda item: item["coordinate"]):
        coordinate = item["coordinate"]
        try:
            metadata = pom_metadata(Path(item["pom"]), item.get("parents", []))
        except LegalError as error:
            raise LegalError(f"{coordinate}: {error}") from error
        notices = []
        artifacts = []
        embedded = []
        for filename in sorted(item["artifacts"]):
            path = Path(filename)
            data = path.read_bytes()
            artifacts.append(dict(name=path.name, sha256=digest(data)))
            for source, text in embedded_notices(data):
                embedded.append(text)
                notices.append(dict(source=path.name + "!/" + source, text=add_text(text, path.name + "!/" + source)))
        supplements = [s for s in registry["supplements"] if re.fullmatch(s["coordinate"], coordinate)]
        reviewed_bsd = []
        for supplement in supplements:
            if supplement.get("licenseFamily") == "BSD-3-Clause":
                for filename in supplement["files"]:
                    text = (legal / filename).read_text()
                    add_text(text, filename)  # Empty/missing source text must still fail.
                    if (text.lstrip().startswith("Copyright")
                            and "Redistribution and use in source and binary forms" in text
                            and "Neither" in text and "MERCHANTABILITY" in text):
                        reviewed_bsd.append(text)
        for declaration in metadata["licenses"]:
            family = declaration["family"]
            filename = registry["families"].get(family)
            if filename:
                notices.append(dict(source=filename, text=add_text((legal / filename).read_text(), filename)))
            elif family == "BSD-3-Clause" and any(
                    "Redistribution and use in source and binary forms" in text
                    and "Neither" in text and "MERCHANTABILITY" in text for text in embedded + reviewed_bsd):
                # BSD has holder-specific text. Preserve the actual artifact's license,
                # never fill a generic template with an invented copyright holder.
                pass
            else:
                raise LegalError(f"No bundled full license text for {coordinate}: {family}")
        for supplement in registry["supplements"]:
            if re.fullmatch(supplement["coordinate"], coordinate):
                for filename in supplement["files"]:
                    notices.append(dict(source=supplement["source"] + "/" + filename.rsplit("/", 1)[-1],
                                        text=add_text((legal / filename).read_text(), filename)))
        # These artifacts omit upstream notices in their binary JAR. Only reviewed
        # versions have a source snapshot; an upgrade must not silently lose it.
        if (coordinate.startswith(("com.android.tools:desugar_jdk_libs:",
                                   "org.jetbrains.kotlinx:kotlinx-coroutines-",
                                   "org.jetbrains.kotlinx:kotlinx-serialization-",
                                   "androidx.datastore:datastore-preferences-external-protobuf:",
                                   "androidx.glance:glance-appwidget-external-protobuf:",
                                   "org.jetbrains.kotlin:kotlin-stdlib"))
                and not any(re.fullmatch(s["coordinate"], coordinate) for s in registry["supplements"])):
            raise LegalError(f"Missing reviewed upstream notices for {coordinate}; update supplemental.json")
        notices = [dict(source=source, text=text) for source, text in
                   sorted({(notice["source"], notice["text"]) for notice in notices})]
        components.append(dict(coordinate=coordinate, scopes=sorted(item["scopes"]), **metadata,
                               pomSha256=digest(Path(item["pom"]).read_bytes()),
                               parentPoms=[dict(coordinate=p["coordinate"], sha256=digest(Path(p["pom"]).read_bytes()))
                                           for p in item.get("parents", [])],
                               artifacts=sorted(artifacts, key=lambda a: (a["name"], a["sha256"])), notices=notices))

    fonts = registry["fonts"]
    bundled_fonts = repo / "android-app/core/core-common/src/main/res/font"
    actual = {p.name for p in bundled_fonts.iterdir() if p.is_file()}
    declared = {name for font in fonts for name in font["files"]}
    if actual != declared:
        raise LegalError(f"Bundled font license inventory mismatch: unreviewed={actual - declared}, missing={declared - actual}")
    font_entries = []
    for font in fonts:
        license_path = repo / font["license"]
        font_entries.append(dict(name=font["name"], source=font["source"],
                                 files=[dict(name=n, sha256=digest((bundled_fonts / n).read_bytes()))
                                        for n in font["files"]],
                                 notices=[dict(source=font["license"], text=add_text(license_path.read_text(), font["license"]))]))

    lines = ["Open-source licenses", "", "Resolved Android release libraries and core-library desugaring inputs.",
             "Debug/test-only libraries are excluded. Font notices follow the dependency list.", ""]
    text_labels = {key: f"L{index:03d}" for index, key in enumerate(sorted(texts), 1)}
    for component in components:
        lines.extend([component["coordinate"], component["name"], component["url"]])
        if component["inheritedLicenseFrom"]:
            lines.append(f"License declaration inherited from {component['inheritedLicenseFrom']}")
        for declaration in component["licenses"]:
            lines.append(f"License: {declaration['name']} ({declaration['url']})")
        for notice in component["notices"]:
            lines.append(f"License/notice {text_labels[notice['text']]}: {notice['source']}")
        lines.append("")
    for font in font_entries:
        lines.extend([font["name"], font["source"], "Files: " + ", ".join(f["name"] for f in font["files"])])
        for notice in font["notices"]:
            lines.append(f"License/notice {text_labels[notice['text']]}: {notice['source']}")
        lines.append("")
    # Each full text is included once, with a stable reference above every use.
    for key, text in sorted(texts.items()):
        lines.extend([f"License/notice {text_labels[key]}", "", text, ""])
    output = output / "legal"
    output.mkdir(parents=True, exist_ok=True)
    policy = repo / "docs/privacy-policy.md"
    shutil.copyfile(policy, output / "privacy-policy.md")
    (output / "open-source-licenses.txt").write_text("\n".join(lines), encoding="utf-8")
    (output / "dependency-list.json").write_text(json.dumps(dict(
        schemaVersion=1, privacyPolicySha256=digest(policy.read_bytes()),
        components=components, fonts=font_entries), indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Bundled canonical policy, {len(components)} release libraries, {len(font_entries)} fonts, {len(texts)} full notice texts")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", required=True, type=Path)
    parser.add_argument("--repo", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        generate(args.inventory, args.repo, args.output)
    except (LegalError, ET.ParseError, UnicodeError, zipfile.BadZipFile, OSError) as error:
        parser.exit(1, f"Offline legal generation failed: {error}\n")
