#!/usr/bin/env python3
"""Regenerate branding from the approved artwork (requires Pillow).

Run from any directory: python scripts/branding/generate_icons.py
Adaptive artwork is inset into a reflected forest bleed so launcher masks and
motion do not crop the lock. SVG files embed raster art; they are not vectors.
"""
import base64
import io
from pathlib import Path
import re

from PIL import Image, ImageDraw, ImageOps

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'design/forest-lock-no-circle.png'
DENSITIES = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}


def save(image, path, size):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.resize((size, size), Image.Resampling.LANCZOS).save(path)


def embedded(image):
    output = io.BytesIO()
    image.save(output, format='PNG')
    return 'data:image/png;base64,' + base64.b64encode(output.getvalue()).decode()


def main():
    art = Image.open(SOURCE).convert('RGBA')
    # Supply an opaque forest-colored base beneath the source's rounded corners.
    base = Image.new('RGBA', art.size, '#101A14')
    base.alpha_composite(art)
    art = base.convert('RGB')
    for path, size in [('assets/logo.png', 256), ('assets/logo_legacy.png', 96),
                       ('fastlane/metadata/android/en-US/images/icon.png', 512)]:
        save(art, ROOT / path, size)

    # Desktop uses the same artwork both as a Swing classpath resource and in jpackage.
    # Windows needs an ICO with multiple resolutions; Linux jpackage accepts the PNG.
    desktop_icon = ROOT / 'desktop/src/main/resources/offlineotp.png'
    save(art, desktop_icon, 256)
    ico = ROOT / 'desktop/packaging/offlineotp.ico'
    ico.parent.mkdir(parents=True, exist_ok=True)
    art.resize((256, 256), Image.Resampling.LANCZOS).save(
        ico, format='ICO', sizes=[(size, size) for size in (16, 24, 32, 48, 64, 128, 256)])

    # 108dp canvas: the approved image occupies the central 72dp. Reflected
    # forest continues to the edges; only the OS launcher supplies a mask.
    tile = art.resize((288, 288), Image.Resampling.LANCZOS)
    adaptive = Image.new('RGB', (432, 432))
    for x in (-216, 72, 360):
        for y in (-216, 72, 360):
            patch = tile
            if x != 72:
                patch = ImageOps.mirror(patch)
            if y != 72:
                patch = ImageOps.flip(patch)
            adaptive.paste(patch, (x, y))

    for variant in ('main', 'debug'):
        folder = ROOT / f'app/src/{variant}'
        save(art, folder / 'ic_launcher-web.png', 512)
        for density, size in DENSITIES.items():
            for name in ('ic_launcher', 'ic_launcher_round'):
                image = art.convert('RGBA')
                if name.endswith('_round'):
                    mask = Image.new('L', image.size)
                    ImageDraw.Draw(mask).ellipse((0, 0, image.width-1, image.height-1), fill=255)
                    image.putalpha(mask)
                save(image, folder / f'res/mipmap-{density}/{name}.png', size)
        # Replace the old vector resource, rather than leaving duplicate names.
        vector = folder / 'res/drawable/ic_launcher_foreground.xml'
        vector.unlink(missing_ok=True)
        save(adaptive, folder / 'res/drawable-nodpi/ic_launcher_foreground.png', 432)
        (folder / 'res/values/ic_launcher_background.xml').write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
            '    <color name="ic_launcher_background">#101A14</color>\n</resources>\n')

    for name, image in [('logo_legacy', art), ('logo_adaptive_foreground', adaptive)]:
        (ROOT / f'assets/{name}.svg').write_text(
            '<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">\n'
            f'  <image width="108" height="108" href="{embedded(image)}"/>\n</svg>\n')

    # Preserve banner typography and layout while replacing its old logo.
    banner_path = ROOT / 'assets/feature_graphic.png'
    banner = Image.open(banner_path).convert('RGB')
    ImageDraw.Draw(banner).rectangle((0, 0, 335, 499), fill='white')
    banner.paste(art.resize((254, 254), Image.Resampling.LANCZOS), (60, 123))
    banner.save(banner_path)
    banner.resize((512, 250), Image.Resampling.LANCZOS).save(ROOT / 'assets/feature_graphic_small.png')
    banner.save(ROOT / 'fastlane/metadata/android/en-US/images/featureGraphic.png')
    svg_path = ROOT / 'assets/feature_graphic.svg'
    svg = svg_path.read_text()
    svg = re.sub(r'  <g\b.*?</g>',
                 f'  <image x="60" y="123" width="254" height="254" href="{embedded(art)}"/>',
                 svg, flags=re.S)
    # Subsequent runs refresh the embedded image as well.
    svg = re.sub(r'  <image[^>]+/>',
                 f'  <image x="60" y="123" width="254" height="254" href="{embedded(art)}"/>', svg)
    svg_path.write_text(svg)


if __name__ == '__main__':
    main()
