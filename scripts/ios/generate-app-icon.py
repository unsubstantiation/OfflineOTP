#!/usr/bin/env python3
"""Generate the iOS app icon from the shared artwork, the way macos.yml generates the .icns.

The iOS client was the only one shipping no icon at all: the built app carried no asset catalog,
so iOS drew the blank placeholder on the Home Screen. Every other client already ships this mark —
Android as `mipmap-*/ic_launcher.png`, the desktop as a jpackage `.icns` built in CI — and the
highest-resolution copy of it is the PNG embedded in `assets/logo_legacy.svg` (1254x1254, opaque
sRGB). `assets/logo.png` and `desktop/src/main/resources/offlineotp.png` are the same image at
256x256, too small for the 1024 slot iOS asks for.

Nothing binary is committed: this script writes `AppIcon.png` into the app icon set before the
Xcode project is generated, and `.gitignore` keeps the result out of the tree. Run it on macOS with
`sips` (what the runner and any dev Mac have), or anywhere with ImageMagick, which is only a
convenience for checking the pipeline on Linux:

    python3 scripts/ios/generate-app-icon.py

Xcode 14 and later take a single 1024x1024 "universal" icon and derive every device size from it,
which is what `AppIcon.appiconset/Contents.json` declares. The deployment target is iOS 17, so no
classic 20/29/40/60@2x/@3x ladder is needed.
"""
from __future__ import annotations

import argparse
import base64
import re
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE_SVG = ROOT / "assets/logo_legacy.svg"
ICON_SET = ROOT / "ios/App/Resources/Assets.xcassets/AppIcon.appiconset"
ICON_PATH = ICON_SET / "AppIcon.png"
SIZE = 1024
# The name Contents.json points at. Kept here so a guard can compare the two without guessing.
ICON_FILENAME = "AppIcon.png"


def embedded_png(svg_text: str) -> bytes:
    """The artwork is an <image href="data:image/png;base64,..."> wrapper, not vector paths."""
    match = re.search(r'href="data:image/png;base64,([A-Za-z0-9+/=\s]+)"', svg_text)
    if not match:
        raise SystemExit(f"{SOURCE_SVG} carries no embedded PNG; the shared artwork changed shape")
    return base64.b64decode(re.sub(r"\s+", "", match.group(1)))


def png_header(data: bytes) -> tuple[int, int, int]:
    """(width, height, colour type) from the IHDR chunk, without any imaging dependency."""
    if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise SystemExit("the embedded artwork is not a PNG")
    width, height = struct.unpack(">II", data[16:24])
    return width, height, data[25]


def resizer() -> list[str]:
    """sips on macOS; ImageMagick elsewhere. Neither is a build dependency of the Swift package."""
    if shutil.which("sips"):
        return ["sips"]
    for tool in ("magick", "convert"):
        if shutil.which(tool):
            return [tool]
    raise SystemExit(
        "no image resizer found: run this on macOS (sips) or install ImageMagick. "
        "The iOS app icon is generated, never committed."
    )


def resize(source: Path, destination: Path) -> None:
    tool = resizer()[0]
    destination.parent.mkdir(parents=True, exist_ok=True)
    if tool == "sips":
        command = ["sips", "-z", str(SIZE), str(SIZE), str(source), "--out", str(destination)]
    elif tool == "magick":
        command = ["magick", str(source), "-resize", f"{SIZE}x{SIZE}!", str(destination)]
    else:
        command = ["convert", str(source), "-resize", f"{SIZE}x{SIZE}!", str(destination)]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit(f"{tool} failed ({result.returncode}): {result.stderr.strip()}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="verify the generated icon instead of writing it")
    args = parser.parse_args()

    if args.check:
        if not ICON_PATH.is_file():
            print(f"missing {ICON_PATH.relative_to(ROOT)}; run scripts/ios/generate-app-icon.py",
                  file=sys.stderr)
            return 1
        width, height, colour = png_header(ICON_PATH.read_bytes())
        if (width, height) != (SIZE, SIZE) or colour in (4, 6):
            print(f"{ICON_PATH.name} is {width}x{height} colour type {colour}; "
                  f"expected opaque {SIZE}x{SIZE}", file=sys.stderr)
            return 1
        print(f"{ICON_PATH.relative_to(ROOT)}: {width}x{height}, opaque")
        return 0

    width, height, colour = png_header(embedded_png(SOURCE_SVG.read_text(encoding="utf-8")))
    if width < SIZE or height < SIZE:
        raise SystemExit(f"{SOURCE_SVG.name} embeds {width}x{height}; iOS needs {SIZE}x{SIZE}. "
                         "Upscaling the mark would ship a soft icon.")
    # iOS rejects an app icon with an alpha channel; the shared mark is opaque, and this refuses
    # artwork that stops being opaque rather than letting actool fail later with a vaguer message.
    if colour in (4, 6):
        raise SystemExit(f"{SOURCE_SVG.name} embeds a PNG with alpha (colour type {colour}); "
                         "the iOS icon must be opaque")

    with tempfile.TemporaryDirectory() as temporary:
        source = Path(temporary) / "artwork.png"
        source.write_bytes(embedded_png(SOURCE_SVG.read_text(encoding="utf-8")))
        resize(source, ICON_PATH)

    written_width, written_height, written_colour = png_header(ICON_PATH.read_bytes())
    if (written_width, written_height) != (SIZE, SIZE) or written_colour in (4, 6):
        raise SystemExit(f"generated icon is {written_width}x{written_height} colour type "
                         f"{written_colour}; expected opaque {SIZE}x{SIZE}")
    print(f"Wrote {ICON_PATH.relative_to(ROOT)} ({written_width}x{written_height}, "
          f"{ICON_PATH.stat().st_size} bytes) from {SOURCE_SVG.relative_to(ROOT)} "
          f"({width}x{height})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
