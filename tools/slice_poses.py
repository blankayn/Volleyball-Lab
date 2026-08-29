"""Slices the five-pose sheet into one transparent PNG per skill.

Run from the project root, with any Blender 4.x/5.x:

    blender --background --factory-startup --python tools/slice_poses.py -- \
        app/src/main/assets/cardlayout.png app/src/main/res/drawable /tmp/preview

The source sheet is `assets/cardlayout.png`; the five outputs are
`res/drawable/skill_*.png`. Re-run this after regenerating the artwork - the
drawables are derived files and editing them by hand is work that the next
regeneration silently throws away.

    blender --background --factory-startup --python slice_poses.py -- <sheet.png> <out_dir> <preview_dir>

Each region is a hand-set rectangle - the figures sit in a known layout - and is
then trimmed to its own content, so the exact rectangle only has to be roughly
right and every output is tight to its own artwork rather than to the grid.

Colour management is pinned to Non-Color on both read and write. Blender would
otherwise apply an sRGB transform on the way in and again on the way out, and the
slices would come back visibly lighter than the sheet they were cut from.
"""
import bpy
import sys

argv = sys.argv[sys.argv.index("--") + 1:]
SRC, OUT_DIR, PREVIEW_DIR = argv[0], argv[1], argv[2]

# Fractions of the sheet: wide enough to hold each figure's motion trails, tight
# enough to exclude its neighbours'. The gaps between figures are narrower than
# they look - a stray shoe from the row above lands inside a generous rectangle
# and the trim below then faithfully preserves it.
#
# That is why receiving stops at 0.884 rather than somewhere past its trails:
# the blocking figure's head reaches up into that row and its topmost five rows
# sit at x 1363-1397, which a wider rectangle catches. The trim cannot help -
# it keeps whatever is inside - and the stray then holds the slice's canvas open
# past its own artwork, which pushes the figure left of the others on the card,
# since the cards align their illustrations to the trailing edge. Columns
# 1337-1362 are empty in this band; the cut goes there.
REGIONS = {
    "serving":   (0.05, 0.00, 0.47, 0.495),
    "receiving": (0.49, 0.06, 0.884, 0.495),
    "setting":   (0.01, 0.505, 0.355, 1.00),
    "spiking":   (0.36, 0.505, 0.655, 1.00),
    "blocking":  (0.665, 0.485, 1.00, 1.00),
}

ALPHA_FLOOR = 0.045
PADDING = 14

source = bpy.data.images.load(SRC)
source.colorspace_settings.name = "Non-Color"
width, height = source.size
pixels = list(source.pixels)


def alpha_at(x, y):
    """Alpha in image coordinates. Blender stores rows bottom-up."""
    return pixels[((height - 1 - y) * width + x) * 4 + 3]


def trim(x0, y0, x1, y1):
    """Shrinks a rectangle onto the artwork actually inside it."""
    left, right, top, bottom = x1, x0, y1, y0
    for y in range(y0, y1):
        for x in range(x0, x1):
            if alpha_at(x, y) > ALPHA_FLOOR:
                if x < left: left = x
                if x > right: right = x
                if y < top: top = y
                if y > bottom: bottom = y
    if left > right or top > bottom:
        return None
    return (
        max(x0, left - PADDING),
        max(y0, top - PADDING),
        min(x1, right + PADDING + 1),
        min(y1, bottom + PADDING + 1),
    )


for name, (fx0, fy0, fx1, fy1) in REGIONS.items():
    box = trim(
        int(fx0 * width), int(fy0 * height),
        int(fx1 * width), int(fy1 * height),
    )
    if box is None:
        print(f"EMPTY {name}")
        continue
    x0, y0, x1, y1 = box
    crop_w, crop_h = x1 - x0, y1 - y0

    out = bpy.data.images.new(name, crop_w, crop_h, alpha=True)
    out.colorspace_settings.name = "Non-Color"

    # Built bottom-up to match Blender's own row order.
    buffer = [0.0] * (crop_w * crop_h * 4)
    for row in range(crop_h):
        src_y = y1 - 1 - row
        src_start = ((height - 1 - src_y) * width + x0) * 4
        dst_start = (row * crop_w) * 4
        buffer[dst_start:dst_start + crop_w * 4] = pixels[src_start:src_start + crop_w * 4]

    out.pixels = buffer
    out.file_format = "PNG"
    out.filepath_raw = f"{OUT_DIR}/skill_{name}.png"
    out.save()
    print(f"SLICED {name}: {crop_w}x{crop_h} from ({x0},{y0})")

    # A white-composited copy, purely so the result can be looked at: the real
    # slice is transparent and reviewing it against a dark viewer background is
    # what caused the sheet to look wrong in the first place.
    preview = bpy.data.images.new(f"{name}_preview", crop_w, crop_h, alpha=False)
    preview.colorspace_settings.name = "Non-Color"
    flat = list(buffer)
    for i in range(0, len(flat), 4):
        a = flat[i + 3]
        for channel in range(3):
            flat[i + channel] = flat[i + channel] * a + (1.0 - a)
        flat[i + 3] = 1.0
    preview.pixels = flat
    preview.file_format = "PNG"
    preview.filepath_raw = f"{PREVIEW_DIR}/{name}.png"
    preview.save()
