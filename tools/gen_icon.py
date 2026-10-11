"""Generate launcher icon densities from the supplied 512x512 PNG.

res/mipmap-<density>/ic_launcher.png is what aapt2 picks up automatically, so no
other wiring is needed beyond the manifest's android:icon.
"""
import os
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'assets', 'icon', 'icon-source.png')
RES = os.path.join(ROOT, 'app', 'res')

# density bucket -> launcher icon px
SIZES = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192,
}

im = Image.open(SRC).convert('RGBA')
print('source: %s %s' % (im.size, im.mode))

# Some launchers draw the icon into a circle/rounded square mask. Trim fully
# transparent margins so the artwork is not needlessly small inside that mask.
bbox = im.split()[3].getbbox()
if bbox and bbox != (0, 0, im.width, im.height):
    print('trimming transparent margin: %s -> %s' % (im.size, (bbox[2] - bbox[0], bbox[3] - bbox[1])))
    im = im.crop(bbox)

# Square it and pad slightly so the mask does not clip the art.
side = max(im.size)
square = Image.new('RGBA', (side, side), (0, 0, 0, 0))
square.paste(im, ((side - im.width) // 2, (side - im.height) // 2), im)

for bucket, px in SIZES.items():
    d = os.path.join(RES, 'mipmap-' + bucket)
    os.makedirs(d, exist_ok=True)
    out = os.path.join(d, 'ic_launcher.png')
    square.resize((px, px), Image.LANCZOS).save(out, 'PNG', optimize=True)
    print('  %-40s %dx%d' % (out, px, px))

print('done')
