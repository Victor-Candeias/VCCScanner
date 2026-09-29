"""Regenerates the launcher icon resources from Docs/VCCScannere.png.

Run from the repository root with Pillow installed:

    python Docs/generate-launcher-icons.py

It writes the adaptive icon foreground plus the legacy square and round icons
into app/src/main/res/mipmap-*. The adaptive background is the vector gradient
in app/src/main/res/drawable/ic_launcher_background.xml, whose stops must stay
in sync with GRADIENT_TOP / GRADIENT_BOTTOM below.
"""

from pathlib import Path
from PIL import Image, ImageDraw

SOURCE = Path("Docs/VCCScannere.png")
RES = Path("app/src/main/res")

# Bounds of the blue rounded square inside the source artwork, excluding its drop shadow.
SQUARE = (67, 76, 1186, 1167)
CORNER_RADIUS = 220 / 1119

# Sampled from the square's own vertical gradient and extrapolated to its top and bottom edges.
GRADIENT_TOP = (37, 147, 255)
GRADIENT_BOTTOM = (0, 24, 94)

# Size of the artwork inside the 108dp adaptive layer. The masked viewport is the central 72dp,
# so 76dp covers it completely while keeping the scan brackets inside the 66dp safe zone.
FOREGROUND_SPAN = 76 / 108

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def artwork(size):
    """The rounded square cropped out of the source, on transparency, at the requested size."""
    square = Image.open(SOURCE).convert("RGBA").crop(SQUARE).resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size * 4 - 1, size * 4 - 1), radius=round(CORNER_RADIUS * size * 4), fill=255
    )
    square.putalpha(mask.resize((size, size), Image.LANCZOS))
    return square


def gradient(size, top, bottom):
    image = Image.new("RGBA", (size, size))
    draw = ImageDraw.Draw(image)
    for y in range(size):
        t = y / max(1, size - 1)
        draw.line(
            [(0, y), (size, y)],
            fill=tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)) + (255,),
        )
    return image


def write(image, folder, name):
    target = RES / f"mipmap-{folder}"
    target.mkdir(parents=True, exist_ok=True)
    image.save(target / f"{name}.png", optimize=True)


def main():
    for density, scale in DENSITIES.items():
        layer = round(108 * scale)
        foreground = Image.new("RGBA", (layer, layer))
        span = round(layer * FOREGROUND_SPAN)
        art = artwork(span)
        foreground.paste(art, ((layer - span) // 2,) * 2, art)
        write(foreground, density, "ic_launcher_foreground")

        legacy = round(48 * scale)
        write(artwork(legacy), density, "ic_launcher")

        # The round icon repeats what the circular adaptive mask shows: the artwork enlarged so the
        # circle never reaches its corners, over the same gradient that the background layer uses.
        round_icon = gradient(legacy, GRADIENT_TOP, GRADIENT_BOTTOM)
        span = round(legacy * 76 / 72)
        art = artwork(span)
        round_icon.paste(art, ((legacy - span) // 2,) * 2, art)
        mask = Image.new("L", (legacy * 4, legacy * 4), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, legacy * 4 - 1, legacy * 4 - 1), fill=255)
        round_icon.putalpha(mask.resize((legacy, legacy), Image.LANCZOS))
        write(round_icon, density, "ic_launcher_round")


if __name__ == "__main__":
    main()
