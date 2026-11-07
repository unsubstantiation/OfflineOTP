#!/usr/bin/env python3
"""Say what is actually inside the unsigned iOS .ipa, and refuse an incomplete one.

"2.5 MB looks incomplete" is a fair question about a build nobody can open without a Mac. This
reads the archive that CI uploads — not the directory it was made from — and reports its contents
as a single run annotation, so the answer is on the run page instead of behind a download:

    python3 scripts/ios/report-ipa-contents.py --ipa artifacts/OfflineOTP-ios-unsigned.ipa \
        --link-map-dir ios/build/DerivedData --annotate

It also turns two silent failures into build failures:

* **OpenPGP compiled out.** `ios/Package.swift` decides at manifest-load time whether the Rust
  XCFramework exists. If it does not, `OFFLINEOTP_OPENPGP_NATIVE` is never defined, the port throws
  `backendUnavailable`, and the app still builds green — just without the cryptography. CI always
  builds the framework first, so a shipped app must prove the Rust objects are in the binary. The
  link map (`LD_GENERATE_MAP_FILE=YES`) is the direct evidence; embedded Rust panic paths are the
  fallback when no map is available.
* **No app icon.** Before the asset catalog existed the bundle carried no icon at all and iOS drew
  a blank placeholder. `Assets.car` plus `CFBundleIconName`/`CFBundleIcons` is what a real icon
  looks like in a built bundle.

Everything here is stdlib: the zip, the property list and the Mach-O header are parsed directly, so
the checks run on any platform and can be tested without Xcode.
"""
from __future__ import annotations

import argparse
import plistlib
import re
import struct
import sys
import zipfile
from pathlib import Path

CPU_TYPE_ARM64 = 0x0100000C
# Panic locations Rust embeds for unwrap/expect, plus the registry path of the rpgp crate.
RUST_MARKERS = re.compile(rb"offlineotp-openpgp/src|\.cargo/registry|pgp-0\.21\.0")
OBJECT_LINE = re.compile(r"^\[\s*(\d+)\]\s+(.+?)\s*$")
SYMBOL_LINE = re.compile(r"^0x[0-9A-Fa-f]+\s+0x([0-9A-Fa-f]+)\s+\[\s*(\d+)\]")


def human(count: float) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if count < 1024 or unit == "GB":
            return f"{count:.0f} {unit}" if unit == "B" else f"{count:.1f} {unit}"
        count /= 1024
    return f"{count:.1f} GB"


def mach_o_cpu_types(data: bytes) -> list[int]:
    """CPU types in a thin or fat Mach-O, read straight from the header."""
    if len(data) < 8:
        return []
    magic = data[:4]
    if magic in (b"\xcf\xfa\xed\xfe", b"\xce\xfa\xed\xfe"):      # MH_MAGIC_64 / MH_MAGIC, LE
        return [struct.unpack_from("<I", data, 4)[0]]
    if magic in (b"\xfe\xed\xfa\xcf", b"\xfe\xed\xfa\xce"):      # byte-swapped
        return [struct.unpack_from(">I", data, 4)[0]]
    if magic == b"\xca\xfe\xba\xbe":                             # FAT_MAGIC, big endian
        count = struct.unpack_from(">I", data, 4)[0]
        return [struct.unpack_from(">I", data, 8 + i * 20)[0] for i in range(min(count, 32))]
    if magic == b"\xca\xfe\xba\xbf":                             # FAT_MAGIC_64
        count = struct.unpack_from(">I", data, 4)[0]
        return [struct.unpack_from(">I", data, 8 + i * 32)[0] for i in range(min(count, 32))]
    return []


def parse_link_map(text: str) -> tuple[int, int, int, list[str]]:
    """(total bytes, OpenPGP bytes, OpenPGP object count, archive names) from an ld64 link map."""
    objects: dict[int, str] = {}
    sizes: dict[int, int] = {}
    section = ""
    for line in text.splitlines():
        if line.startswith("#"):
            # Only the section headings change state. "# Address Size File Name" is a column
            # header *inside* Symbols, and treating it as a heading silently dropped every size.
            heading = line.strip("# :").strip().lower()
            if heading in ("object files", "sections", "symbols"):
                section = heading
            continue
        if section == "object files":
            match = OBJECT_LINE.match(line)
            if match:
                objects[int(match.group(1))] = match.group(2)
        elif section == "symbols":
            match = SYMBOL_LINE.match(line)
            if match:
                index = int(match.group(2))
                sizes[index] = sizes.get(index, 0) + int(match.group(1), 16)
    openpgp = {i: path for i, path in objects.items() if "offlineotp_openpgp" in path.lower()}
    archives = sorted({re.sub(r"\(.*\)$", "", path) for path in openpgp.values()})
    return (sum(sizes.values()), sum(sizes.get(i, 0) for i in openpgp), len(openpgp), archives)


def find_link_map(directory: Path | None) -> Path | None:
    if directory is None or not directory.is_dir():
        return None
    maps = sorted(directory.rglob("*LinkMap*.txt"), key=lambda p: p.stat().st_size, reverse=True)
    return maps[0] if maps else None


def inspect(ipa: Path, link_map: Path | None) -> tuple[list[str], list[str]]:
    """(report lines, problems) for the archive CI is about to upload."""
    problems: list[str] = []
    report: list[str] = []
    with zipfile.ZipFile(ipa) as archive:
        entries = [i for i in archive.infolist() if not i.is_dir()]
        names = [i.filename for i in entries]
        bundles = {n.split("/")[1] for n in names
                   if n.startswith("Payload/") and n.split("/")[1].endswith(".app")}
        if len(bundles) != 1:
            problems.append(f"expected exactly one Payload/*.app, found {sorted(bundles)}")
            return report, problems
        bundle = f"Payload/{next(iter(bundles))}/"
        stray = [n for n in names if not n.startswith(bundle)]
        if stray:
            problems.append(f"the archive carries files outside the app bundle: {stray[:5]}")

        members = [i for i in entries if i.filename.startswith(bundle)]
        unpacked = sum(i.file_size for i in members)
        report.append(f"{ipa.name}: {human(ipa.stat().st_size)} compressed, "
                      f"{len(members)} files, {human(unpacked)} installed")

        plist_name = bundle + "Info.plist"
        if plist_name not in names:
            problems.append("the bundle has no Info.plist")
            return report, problems
        plist = plistlib.loads(archive.read(plist_name))
        executable_name = plist.get("CFBundleExecutable", "")
        report.append(f"{plist.get('CFBundleIdentifier', '?')} "
                      f"{plist.get('CFBundleShortVersionString', '?')} "
                      f"({plist.get('CFBundleVersion', '?')}), "
                      f"{plist.get('DTPlatformName', '?')}, "
                      f"iOS {plist.get('MinimumOSVersion', '?')}+")
        if plist.get("DTPlatformName") != "iphoneos":
            problems.append(f"DTPlatformName is {plist.get('DTPlatformName')!r}, not iphoneos")

        executable = bundle + executable_name
        if executable not in names:
            problems.append(f"the declared executable {executable_name!r} is not in the bundle")
            binary = b""
        else:
            binary = archive.read(executable)
            cpus = mach_o_cpu_types(binary)
            if cpus != [CPU_TYPE_ARM64]:
                problems.append(f"{executable_name} is not a thin arm64 Mach-O (cpu types {cpus})")
            report.append(f"{executable_name}: {human(len(binary))}, arm64")

        resources = [i for i in members
                     if i.filename != executable and i.filename != plist_name]
        largest = sorted(resources, key=lambda i: i.file_size, reverse=True)[:3]
        if largest:
            report.append("resources: " + ", ".join(
                f"{i.filename[len(bundle):]} {human(i.file_size)}" for i in largest))
        else:
            report.append("resources: none")

        icon_keys = [k for k in ("CFBundleIconName", "CFBundleIcons") if k in plist]
        # actool may put the icon in Assets.car, copy resized AppIcon*.png files beside it, or both,
        # depending on the catalog and the deployment target. The Info.plist key is the part that
        # proves an icon was actually chosen; either form of payload satisfies the rest.
        car_name = bundle + "Assets.car"
        icon_files = [n[len(bundle):] for n in names
                      if n.startswith(bundle + "AppIcon") and n.endswith(".png")]
        if not icon_keys or (car_name not in names and not icon_files):
            problems.append("the app ships no compiled app icon "
                            "(needs CFBundleIconName/CFBundleIcons plus Assets.car or AppIcon*.png); "
                            "run scripts/ios/generate-app-icon.py before the build")
        else:
            payload = (f"Assets.car {human(archive.getinfo(car_name).file_size)}"
                       if car_name in names else ", ".join(sorted(icon_files)[:3]))
            named = plist.get("CFBundleIconName")
            detail = f"CFBundleIconName={named!r}" if named else "CFBundleIcons"
            report.append(f"app icon: {payload}, {detail}")

        embedded = [n[len(bundle):] for n in names if n.startswith(bundle + "Frameworks/")]
        report.append(f"embedded frameworks: {len(embedded)}")

    parsed = None
    if link_map is not None:
        parsed = parse_link_map(link_map.read_text(encoding="utf-8", errors="replace"))
    if parsed and parsed[2]:
        total, openpgp_bytes, count, archives = parsed
        share = f"{openpgp_bytes / total:.0%}" if total else "?"
        report.append(f"OpenPGP: linked — {Path(archives[0]).name} contributes "
                      f"{human(openpgp_bytes)} in {count} objects ({share} of the linked image)")
    elif binary and RUST_MARKERS.search(binary):
        hits = len(RUST_MARKERS.findall(binary))
        report.append(f"OpenPGP: linked — {hits} Rust source paths in the binary"
                      + (" (no link map found)" if link_map is None else ""))
    else:
        problems.append("the Rust OpenPGP backend is not in the app: neither the link map nor the "
                        "binary mentions offlineotp_openpgp. Package.swift silently compiles the "
                        "port out when ios/Frameworks/offlineotp_openpgp.xcframework is missing.")
    return report, problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ipa", type=Path, required=True)
    parser.add_argument("--link-map-dir", type=Path, default=None)
    parser.add_argument("--annotate", action="store_true",
                        help="also publish the report as one GitHub run annotation")
    args = parser.parse_args()

    if not args.ipa.is_file():
        print(f"::error title=iOS bundle::{args.ipa} does not exist", file=sys.stderr)
        return 1
    report, problems = inspect(args.ipa, find_link_map(args.link_map_dir))
    for line in report:
        print(line)
    if args.annotate and report:
        # One annotation, not one per line: %0A is how a workflow command carries a newline.
        print("::notice title=iOS bundle::" + "%0A".join(report))
    for problem in problems:
        print(f"::error title=iOS bundle::{problem}")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
