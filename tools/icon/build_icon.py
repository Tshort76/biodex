#!/usr/bin/env python3
"""Cut the launcher art to the adaptive icon's own mask shape, one size per density.

`ic_launcher_art.png` beside this script is the artwork: a square, opaque photograph of
the carved mark, composed for a 108dp canvas whose outer margin is bleed — the emblem
sits in the middle and the wood around it is there to be cut. What ships is that art at
its authored scale with white painted over everything outside a squircle, so the icon
reads as the mark with a thin white edge (D35 as revised by D37).

**The art is never rescaled, only painted over.** Shrinking it to fit the edge inside the
mask pulls the bleed into view, and the icon turns into a wall of planks with the emblem
somewhere in it.

**The white is painted into the foreground, and the layer stays opaque edge to edge.**
The obvious way to get an edge is to inset a smaller foreground over the white
background layer and let the background show around it. That is what this icon did
first, and on a Pixel 7 Pro it does not work: leave a transparent margin in the
foreground and the launcher scales the art back up until it fills the mask again, so the
edge vanishes. Keeping every pixel opaque is what stops it — the launcher has no
transparent margin to measure, and the white ring survives to the screen.

The scale is the whole trick. A bitmap foreground fills the 108dp layer, and the mask
keeps the central 72dp of it — the safe zone the guideline names. So the art has to be
sized against 72dp, not 108dp, and only about four ninths of the bitmap's area is ever
seen.

The first version of this icon inset a square photograph by 21dp instead, and that gets
the geometry wrong twice over. The corners are the visible half of the mistake: a square
inside a rounded mask leaves white wedges at the diagonals and a hairline along the
sides, which reads as a photo pasted on a white tile rather than a mark with a border.
The size was wrong too, and less obviously — `inset` resolves 21dp against the layer
bounds the launcher hands it, which are drawn smaller than 108dp of screen, so the art
came out near two thirds of the mask instead of the nine tenths the arithmetic promised.

If the edge ever renders wrong, measure rather than reason: paint the art in bands of a
different colour per tenth of the canvas, install, and look at which bands survive. The
band that sits at the edge names the mask. Thin rings are not enough — at drawer size
they alias into each other and can be read off by a whole step.

Clipping the art rather than insetting it is what makes the edge even. An inset square
inside a rounded mask is thick at the corners and thin at the sides; a squircle inside a
squircle is the same width all the way round.

On a launcher whose mask is smaller than the one measured here the mask simply cuts
inside the art and the edge disappears — the icon loses its border but never gains a
tile, which is the failure worth having.

    python3 tools/icon/build_icon.py
"""

from pathlib import Path

from PIL import Image, ImageChops

# The adaptive icon's layer canvas.
CANVAS_DP = 108
# What the mask keeps of that canvas: the central 72dp, confirmed by the band test above.
MASK_DP = 72.0
# How wide the white edge should read once the mask has cut it. 3dp is a hairline at
# every launcher size without becoming a frame.
EDGE_DP = 3.0
# The mask is close to a superellipse of this exponent. Fitting a screenshot of the
# rendered icon at 15-degree steps put it between 2.55 and 2.94 depending on the angle, so
# the shape is not exactly a superellipse; 2.6 is the value that measured back as an even
# edge, where 3.0 left it 3.3dp on the axes and 1dp at the diagonals.
EXPONENT = 2.6
# One output per density bucket: pixels per dp.
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

HERE = Path(__file__).resolve().parent
RES = HERE.parent.parent / "app" / "src" / "main" / "res"


def mask(size: int, scale: float, supersample: int = 4) -> Image.Image:
    """An 8-bit alpha mask: a superellipse filling `scale` of a `size` square.

    Drawn at `supersample` times the final resolution and reduced, because the shape is
    computed per pixel rather than stroked — there is no path to hand to an antialiaser.
    """
    n = size * supersample
    half = n / 2
    radius = half * scale
    alpha = Image.new("L", (n, n), 0)
    px = alpha.load()
    for y in range(n):
        dy = abs(y + 0.5 - half)
        if dy > radius:
            continue
        # The superellipse solved for x: |x|^e + |y|^e = r^e.
        dx = (radius**EXPONENT - dy**EXPONENT) ** (1 / EXPONENT)
        lo = max(0, int(half - dx))
        hi = min(n - 1, int(half + dx))
        for x in range(lo, hi + 1):
            px[x, y] = 255
    return alpha.resize((size, size), Image.LANCZOS)


def main() -> None:
    art = Image.open(HERE / "ic_launcher_art.png").convert("RGBA")
    scale = (MASK_DP - 2 * EDGE_DP) / CANVAS_DP
    for bucket, per_dp in DENSITIES.items():
        size = int(round(CANVAS_DP * per_dp))
        face = art.resize((size, size), Image.LANCZOS)
        # White over everything outside the shape, all the way to the corners of the
        # canvas: the mask cuts where it likes, and every pixel it keeps has to be either
        # art or edge. Painting over rather than insetting is what keeps the art's scale.
        edge = Image.new("RGBA", (size, size), (255, 255, 255, 255))
        edge.putalpha(ImageChops.invert(mask(size, scale)))
        out = RES / f"mipmap-{bucket}" / "ic_launcher_art.png"
        Image.alpha_composite(face, edge).save(out)
        print(
            f"{out.relative_to(RES.parent.parent.parent)}: {size}px, "
            f"art out to {int(round(size * scale))}px"
        )


if __name__ == "__main__":
    main()
