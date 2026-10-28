#!/usr/bin/env python3
"""Reproduce the vendored desugar 2.1.5 source notices from its pinned source archive/JAR.

Source: google/desugar_jdk_libs commit 73170c345e6a762fc6a1f0301bb15218850023ef
(the upstream 2.1.5 release-preparation commit). No downloads occur in this script.
"""
import argparse
from pathlib import Path
import re
import tarfile
from zipfile import ZipFile


def collect(archive_path, jar_path):
    with ZipFile(jar_path) as jar:
        sources = {n.split("$")[0].removesuffix(".class") + ".java"
                   for n in jar.namelist() if n.endswith(".class")}
    # These top-level classes are generated from the corresponding upstream source.
    sources |= {str(Path(n).with_name(Path(n).name.removeprefix("Desugar"))) for n in sources}
    sources.add("java/util/ImmutableCollections.java")  # package-private CollSer
    headers = {}
    with tarfile.open(archive_path) as archive:
        for member in archive.getmembers():
            if not member.isfile() or not member.name.endswith(".java") or "/test" in member.name:
                continue
            if not any(member.name.endswith("/" + name) for name in sources):
                continue
            data = archive.extractfile(member).read().decode("utf-8")
            match = re.match(r"\s*((?:(?:/\*[\s\S]*?\*/|//[^\n]*(?:\n|$))\s*)+)", data)
            if not match:
                raise ValueError(f"No notice header: {member.name}")
            headers.setdefault(match[1].strip(), []).append(member.name.split("/", 1)[1])
        root = archive.getmembers()[0].name.split("/", 1)[0]
        root_texts = {name: archive.extractfile(root + "/" + name).read()
                      for name in ("LICENSE", "ADDITIONAL_LICENSE_INFO", "ASSEMBLY_EXCEPTION")}
    lines = ["desugar_jdk_libs 2.1.5 source notices", "",
             "Source snapshot: https://github.com/google/desugar_jdk_libs/tree/73170c345e6a762fc6a1f0301bb15218850023ef",
             "Includes upstream source headers corresponding to release JAR classes (and generated-class sources).",
             "Both upstream OpenJDK and Android source variants are retained conservatively.", ""]
    for header, names in sorted(headers.items(), key=lambda item: sorted(item[1])[0]):
        lines.extend(["Sources:", *sorted(names), "", header, ""])
    return "\n".join(lines), root_texts


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("jar", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    text, root_texts = collect(args.archive, args.jar)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "SOURCE-NOTICES.txt").write_text(text)
    for name, data in root_texts.items():
        (args.output / name).write_bytes(data)
