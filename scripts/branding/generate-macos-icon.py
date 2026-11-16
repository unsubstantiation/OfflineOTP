#!/usr/bin/env python3
"""Build a macOS icon from the same full-resolution artwork as Android and iOS.
Requires macOS sips/iconutil; no Pillow or network access is needed.
"""
import argparse
import base64
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "assets/logo_legacy.svg"


def generate(destination):
    match = re.search(r'href="data:image/png;base64,([A-Za-z0-9+/=\s]+)"', SOURCE.read_text())
    if match is None:
        raise ValueError("Shared Android artwork contains no embedded PNG")
    destination = destination.resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as directory:
        temporary = Path(directory)
        source = temporary / "source.png"
        source.write_bytes(base64.b64decode(re.sub(r"\s+", "", match.group(1)), validate=True))
        iconset = temporary / "OfflineOTP.iconset"
        iconset.mkdir()
        for size in (16, 32, 128, 256, 512):
            for scale, suffix in ((1, ""), (2, "@2x")):
                pixels = str(size * scale)
                subprocess.run(["sips", "-z", pixels, pixels, str(source), "--out",
                                str(iconset / f"icon_{size}x{size}{suffix}.png")], check=True)
        subprocess.run(["iconutil", "-c", "icns", str(iconset), "-o", str(destination)], check=True)
        if not destination.is_file() or destination.stat().st_size == 0:
            raise RuntimeError("iconutil did not produce an icon")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    generate(parser.parse_args().output)
