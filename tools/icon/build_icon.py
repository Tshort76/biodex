#!/usr/bin/env python3
"""Write the launcher icon's vector layers: a scissor-tailed flycatcher seen from below, half
revealed and half silhouette, on a cream face with a walnut rim (D100).

    python3 tools/icon/build_icon.py

It writes `drawable/ic_launcher_fg.xml` (the colour mark) and `drawable/ic_launcher_mono.xml`
(the one-colour mark that themed launchers tint). The walnut rim is the adaptive icon's
background layer, `@color/ic_launcher_border`; the face is drawn over it, and the rim is what
the mask keeps of the background around the face.

**The face takes the mask's own shape, so the rim is one width all the way round.** That is
D37's lesson, kept from when the icon was a photograph: a shape that does not follow the mask
leaves an edge thick at the diagonals and thin at the sides. On this launcher the mask keeps
the central 72dp of the 108dp layer and is close to a superellipse of exponent 2.6; both were
measured by painting the art in bands of a different colour per tenth of the canvas,
installing, and seeing which bands survived. If the rim ever renders uneven, measure again
rather than reason: thin rings alias at drawer size and can be misread by a whole step, and
the launcher's icon cache is keyed by package version, so a same-version reinstall can show
the old icon and look like a failure.

The bird is drawn once for its left half and mirrored. The left half is revealed in light oak,
copper and rust; the right half is the dex's silhouette slate, the way a species looks before
it is caught.
"""

import math
from pathlib import Path

CANVAS_DP = 108
MASK_DP = 72.0
# The walnut rim, as it reads once the mask has cut it.
RIM_DP = 4.0
EXPONENT = 2.6

WALNUT_DEEP = "#3E271A"
CREAM = "#F3E9D8"
OAK = "#E2B77E"
COPPER = "#D07A45"
RUST = "#A64B2A"
SLATE = "#3A4148"

# The bird, drawn on the 108 canvas; it sits on the face scaled about (54, 55) and moved down 1.
WING = "M49,36 C40,31 28,27 15,28 C17,35 24,42 33,46 C40,49 46,50 50,49z"
PATCH = "M46,43 C44,45 45,48 49,48 L50,44z"
TAIL = "M50,54 C46,66 38,76 27,86 C40,80 49,69 55,58z"
FAN = "M50,55 L54,65 L58,55z"
BODY = "M47.4,44a6.6,12.5 0 1,0 13.2,0a6.6,12.5 0 1,0 -13.2,0z"
HEAD = "M49,31a5,5 0 1,0 10,0a5,5 0 1,0 -10,0z"
BEAK = "M51.8,28 L54,23.6 L56.2,28z"
SCALE = 0.74

HERE = Path(__file__).resolve().parent
DRAWABLE = HERE.parent.parent / "app" / "src" / "main" / "res" / "drawable"


def face_path(points: int = 144) -> str:
    """The face: a superellipse in the mask's shape, RIM_DP inside it."""
    r = MASK_DP / 2 - RIM_DP
    c = CANVAS_DP / 2
    out = []
    for i in range(points):
        t = 2 * math.pi * i / points
        ct, st = math.cos(t), math.sin(t)
        x = c + r * math.copysign(abs(ct) ** (2 / EXPONENT), ct)
        y = c + r * math.copysign(abs(st) ** (2 / EXPONENT), st)
        out.append(f"{x:.2f},{y:.2f}")
    return "M" + " L".join(out) + "z"


def path(fill: str, d: str, indent: str = "        ") -> str:
    return f'{indent}<path android:fillColor="{fill}" android:pathData="{d}" />'


def bird(wing: str, patch: str, tail: str, body: str, head: str) -> str:
    def half(indent: str) -> list[str]:
        return [path(wing, WING, indent), path(patch, PATCH, indent), path(tail, TAIL, indent)]
    return "\n".join([
        *half("        "),
        '        <group android:scaleX="-1" android:pivotX="54">',
        *half("            "),
        "        </group>",
        path(tail, FAN), path(body, BODY), path(head, HEAD), path(head, BEAK),
    ])


def placed(inner: str, clip: str | None = None) -> str:
    clip_line = f'\n        <clip-path android:pathData="{clip}" />' if clip else ""
    return (f'    <group android:scaleX="{SCALE}" android:scaleY="{SCALE}" android:pivotX="54" '
            f'android:pivotY="55" android:translateY="1">{clip_line}\n{inner}\n    </group>')


def vector(comment: str, body: str) -> str:
    return (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- {comment}\n     Written by tools/icon/build_icon.py; '
            f'edit that, not this. -->\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{CANVAS_DP}dp" android:height="{CANVAS_DP}dp"\n'
            f'    android:viewportWidth="{CANVAS_DP}" android:viewportHeight="{CANVAS_DP}">\n{body}\n</vector>\n')


def main() -> None:
    left, right = "M0,0 H54 V108 H0z", "M54,0 H108 V108 H54z"
    fg = "\n".join([
        f'    <path android:fillColor="{CREAM}" android:pathData="{face_path()}" />',
        placed(bird(OAK, COPPER, COPPER, RUST, WALNUT_DEEP), left),
        placed(bird(*[SLATE] * 5), right),
    ])
    mono = placed(bird(*["#FFFFFF"] * 5))
    (DRAWABLE / "ic_launcher_fg.xml").write_text(vector(
        "The BioDex mark (D100): a scissor-tailed flycatcher from below, half revealed and half\n"
        "     silhouette, on a cream face in the mask's shape.", fg))
    (DRAWABLE / "ic_launcher_mono.xml").write_text(vector(
        "The themed launcher icon (D100): the flycatcher alone, in one colour the launcher tints.", mono))
    print("wrote drawable/ic_launcher_fg.xml and drawable/ic_launcher_mono.xml")


if __name__ == "__main__":
    main()
