"""Add an adaptive launcher icon (API 26+).

The supplied art is already a circle on a transparent square. Launchers mask
adaptive icons to their own shape, so we compose:
    background = solid colour sampled from the art
    foreground = the art, scaled to sit inside the 66/108 safe zone
    plus an <inset> so the circle is not clipped when masked
Older devices keep using the plain mipmap-*/ic_launcher.png.
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'assets', 'icon', 'icon-source.png')
RES = os.path.join(ROOT, 'app', 'res')

# adaptive icon grid: 108dp canvas, centre 72dp visible, 66dp guaranteed safe
SAFE = 66.0 / 108.0
FOREGROUND_INSET = 24  # percent, keeps the circle inside the safe zone
DENSITIES = {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}

art = Image.open(SRC).convert('RGBA')
bbox = art.split()[3].getbbox()
if bbox:
    art = art.crop(bbox)
side = max(art.size)
sq = Image.new('RGBA', (side, side), (0, 0, 0, 0))
sq.paste(art, ((side - art.width) // 2, (side - art.height) // 2), art)
print('art: %s -> squared %s' % (bbox, sq.size))

# dominant-ish background colour: average of the outer ring of the art
small = sq.resize((64, 64), Image.LANCZOS)
px = small.load()
rs = gs = bs = n = 0
for x in range(64):
    for y in range(64):
        r, g, b, a = px[x, y]
        # only count opaque pixels from the outer ring
        if a > 200 and (x < 8 or x > 55 or y < 8 or y > 55):
            rs += r; gs += g; bs += b; n += 1
if n == 0:
    rs, gs, bs, n = 44, 62, 80, 1
bg = (rs // n, gs // n, bs // n)
print('sampled background colour: %s (from %d px)' % (bg, n))

# 1) foreground PNGs at each density, with transparent padding for the safe zone
for bucket, px_size in DENSITIES.items():
    d = os.path.join(RES, 'mipmap-' + bucket)
    os.makedirs(d, exist_ok=True)
    inner = int(px_size * SAFE * (1.0 - FOREGROUND_INSET / 100.0))
    canvas = Image.new('RGBA', (px_size, px_size), (0, 0, 0, 0))
    scaled = sq.resize((inner, inner), Image.LANCZOS)
    off = (px_size - inner) // 2
    canvas.paste(scaled, (off, off), scaled)
    out = os.path.join(d, 'ic_launcher_foreground.png')
    canvas.save(out, 'PNG', optimize=True)
    print('  %-46s %dx%d (art %d)' % (out, px_size, px_size, inner))

# 2) background colour resource
values = os.path.join(RES, 'values')
os.makedirs(values, exist_ok=True)
with open(os.path.join(values, 'colors.xml'), 'w', encoding='utf-8', newline='\n') as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n')
    f.write('<resources>\n')
    f.write('    <color name="ic_launcher_background">#%02X%02X%02X</color>\n' % bg)
    f.write('</resources>\n')

# 3) adaptive icon XML for API 26+
mipmap_anydpi = os.path.join(RES, 'mipmap-anydpi-v26')
os.makedirs(mipmap_anydpi, exist_ok=True)
with open(os.path.join(mipmap_anydpi, 'ic_launcher.xml'), 'w', encoding='utf-8', newline='\n') as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n')
    f.write('<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n')
    f.write('    <background android:drawable="@color/ic_launcher_background" />\n')
    f.write('    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n')
    f.write('</adaptive-icon>\n')
print('wrote mipmap-anydpi-v26/ic_launcher.xml')
print('done')
