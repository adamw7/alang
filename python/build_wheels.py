#!/usr/bin/env python3
"""Builds the wheels of the alang-toolchain Python package (pip install alang-toolchain).

    python3 python/build_wheels.py --jar target/alang.jar --out dist/python
    python3 python/build_wheels.py --native linux-x64=target/native/alang --out dist/python

--jar makes the wheel for every platform (py3-none-any), which runs the jar on Java 21 or newer; each
--native PLATFORM=BINARY makes the wheel for that platform, which carries the native binary and needs no
Java. pip picks the platform's wheel where there is one, and the jar's anywhere else.

The standard library only, and reproducible: the same inputs give the same bytes (SOURCE_DATE_EPOCH sets
the files' time, 1980-01-01 by default). The version is pom.xml's unless --version gives another.
"""

import argparse
import base64
import hashlib
import os
import re
import sys
import time
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

DIST_NAME = "alang-toolchain"
WHEEL_NAME = DIST_NAME.replace("-", "_")
ROOT = Path(__file__).resolve().parent.parent
PACKAGE = Path(__file__).resolve().parent / "alang"

# The native binaries the release builds (ci.yml's native job), with their wheel platform tags. The Linux
# ones need glibc 2.35 or newer, as they are built on Ubuntu 22.04.
PLATFORMS = {
    "linux-x64": ("manylinux_2_35_x86_64", "alang"),
    "linux-arm64": ("manylinux_2_35_aarch64", "alang"),
    "macos-arm64": ("macosx_11_0_arm64", "alang"),
    "windows-x64": ("win_amd64", "alang.exe"),
}

SUMMARY = "ALang, an agent-first programming language: no heap, fully deterministic, always terminates"


def pom_version():
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    return ET.parse(ROOT / "pom.xml").getroot().find("m:version", ns).text.strip()


def metadata(version):
    readme = (Path(__file__).resolve().parent / "README.md").read_text(encoding="utf-8")
    headers = [
        ("Metadata-Version", "2.4"),
        ("Name", DIST_NAME),
        ("Version", version),
        ("Summary", SUMMARY),
        ("Author", "Adam Witkowski"),
        ("License-Expression", "MIT"),
        ("License-File", "LICENSE"),
        ("License-File", "THIRD-PARTY-NOTICES"),
        ("Project-URL", "Homepage, https://github.com/adamw7/alang"),
        ("Project-URL", "Documentation, https://github.com/adamw7/alang/blob/main/AGENTS.md"),
        ("Project-URL", "Issues, https://github.com/adamw7/alang/issues"),
        ("Keywords", "programming-language,agents,llm,mcp,deterministic,sandbox"),
        ("Classifier", "Development Status :: 4 - Beta"),
        ("Classifier", "Intended Audience :: Developers"),
        ("Classifier", "Programming Language :: Python :: 3"),
        ("Classifier", "Topic :: Software Development :: Compilers"),
        ("Classifier", "Topic :: Software Development :: Interpreters"),
        ("Requires-Python", ">=3.9"),
        # The same in every wheel, as installers expect of one version; only the jar's wheel uses it.
        ("Provides-Extra", "java"),
        ("Requires-Dist", 'jdk4py>=21; extra == "java"'),
        ("Description-Content-Type", "text/markdown"),
    ]
    return "".join(f"{k}: {v}\n" for k, v in headers) + "\n" + readme


def record_hash(data):
    digest = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode()
    return f"sha256={digest}"


def build(out, version, tag, payload):
    """Writes one wheel; payload maps a path in the wheel to (bytes, executable)."""
    info = f"{WHEEL_NAME}-{version}.dist-info"
    pure = tag == "any"
    files = dict(payload)
    for name in sorted(p.name for p in PACKAGE.glob("*.py")):
        files[f"alang/{name}"] = ((PACKAGE / name).read_bytes(), False)
    files["alang/AGENTS.md"] = ((ROOT / "AGENTS.md").read_bytes(), False)
    meta = {
        f"{info}/METADATA": metadata(version),
        f"{info}/WHEEL": (
            "Wheel-Version: 1.0\n"
            "Generator: alang build_wheels.py\n"
            f"Root-Is-Purelib: {'true' if pure else 'false'}\n"
            f"Tag: py3-none-{tag}\n"
        ),
        f"{info}/entry_points.txt": "[console_scripts]\nalang = alang:main\n",
        f"{info}/licenses/LICENSE": (ROOT / "LICENSE").read_text(encoding="utf-8"),
        f"{info}/licenses/THIRD-PARTY-NOTICES": (ROOT / "THIRD-PARTY-NOTICES").read_text(encoding="utf-8"),
    }
    for path, text in meta.items():
        files[path] = (text.encode("utf-8"), False)

    epoch = int(os.environ.get("SOURCE_DATE_EPOCH", "315532800"))
    stamp = time.gmtime(max(epoch, 315532800))[:6]
    wheel = out / f"{WHEEL_NAME}-{version}-py3-none-{tag}.whl"
    record = []
    with zipfile.ZipFile(wheel, "w", zipfile.ZIP_DEFLATED) as zf:
        # The package first and the .dist-info last, RECORD at the very end, as the wheel spec advises.
        for path in sorted(files, key=lambda p: (p.startswith(info), p)):
            data, executable = files[path]
            entry = zipfile.ZipInfo(path, stamp)
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = (0o100755 if executable else 0o100644) << 16
            zf.writestr(entry, data)
            record.append(f"{path},{record_hash(data)},{len(data)}")
        record.append(f"{info}/RECORD,,")
        entry = zipfile.ZipInfo(f"{info}/RECORD", stamp)
        entry.compress_type = zipfile.ZIP_DEFLATED
        entry.external_attr = 0o100644 << 16
        zf.writestr(entry, "\n".join(record) + "\n")
    return wheel


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--jar", type=Path, help="the toolchain's jar (target/alang.jar): the wheel for every platform")
    parser.add_argument(
        "--native",
        action="append",
        default=[],
        metavar="PLATFORM=BINARY",
        help=f"a native binary and its platform, one of {', '.join(PLATFORMS)}; may be repeated",
    )
    parser.add_argument("--version", help="the package version (default: pom.xml's)")
    parser.add_argument("--out", type=Path, required=True, help="the directory to write the wheels to")
    args = parser.parse_args()
    if args.jar is None and not args.native:
        parser.error("give --jar, --native or both")
    version = args.version or pom_version()
    if not re.fullmatch(r"\d+(\.\d+)*((a|b|rc)\d+)?(\.post\d+)?(\.dev\d+)?", version):
        parser.error(f"{version!r} is not a plain PEP 440 version such as 0.7.0")

    args.out.mkdir(parents=True, exist_ok=True)
    wheels = []
    if args.jar is not None:
        wheels.append(build(args.out, version, "any", {"alang/lib/alang.jar": (args.jar.read_bytes(), False)}))
    for spec in args.native:
        platform, sep, binary = spec.partition("=")
        if not sep or platform not in PLATFORMS:
            parser.error(f"--native {spec!r}: expected PLATFORM=BINARY with PLATFORM one of {', '.join(PLATFORMS)}")
        tag, name = PLATFORMS[platform]
        wheels.append(build(args.out, version, tag, {f"alang/bin/{name}": (Path(binary).read_bytes(), True)}))
    for wheel in wheels:
        print(wheel)
    return 0


if __name__ == "__main__":
    sys.exit(main())
