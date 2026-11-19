"""Generate Flint's streak flame from first-party geometry.

The flame is a union of three lobed tongues built from cubic curves. Each frame deforms
those curves, so the animation is authored here rather than sampled or traced from another
product's artwork. The palette, silhouette direction and 60 ms timing match the approved
streak design.

Usage:
    python3 design/tools/generate_streak_flame.py --out /tmp/flint-flame

Then copy the outputs into the app trees:
    cp /tmp/flint-flame/flint_streak_sheet.png \
       android-app/core/core-common/src/main/res/drawable-nodpi/flint_streak_sheet.png
    cp /tmp/flint-flame/flint_streak_fire.gif \
       ios-app/Flint/Resources/flint_streak_fire.gif

Both renderers assume the same contract: 45 frames of 156x187 at 60 ms, the Android atlas
in a 9-column grid. `StreakAtlasTest` and `FocusStreakCopyTests` pin those values.
"""

import argparse
import math
import os

from PIL import Image, ImageDraw, ImageFilter
import numpy as np

W, H = 156, 187
S = 4                       # supersampling factor
FRAMES = 45
FRAME_MS = 60
ATLAS_COLUMNS = 9

# Warm streak palette: deep red rim, red-orange body, amber band, pale core.
RIM_TOP = (196, 36, 7)
RIM_MID = (217, 50, 8)
RIM_BASE = (168, 20, 4)
BODY_TOP = (224, 46, 10)
BODY_MID = (239, 82, 13)
BODY_LOW = (243, 128, 28)
BODY_BASE = (245, 156, 40)
WARM_TOP = (246, 150, 40)
WARM_MID = (250, 188, 66)
WARM_BASE = (252, 204, 88)
CORE_TOP = (255, 200, 92)
CORE_MID = (255, 226, 140)
CORE_BASE = (255, 242, 190)
HIGHLIGHT = (255, 214, 140)
HIGHLIGHT_LOW = (255, 170, 70)


def bezier(p0, p1, p2, p3, steps=48):
    """Sample a cubic curve; control points keep the silhouette smooth and readable."""
    points = []
    for i in range(steps + 1):
        t = i / steps
        u = 1 - t
        x = u**3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t**3 * p3[0]
        y = u**3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t**3 * p3[1]
        points.append((x, y))
    return points


def tongue(cx_base, y_base, cx_tip, y_tip, half_w, lean=0.0, tip_bend=0.0):
    """One lobed flame tongue: rounded base, waisted body, pointed tip."""
    height = y_base - y_tip
    right = bezier(
        (cx_base + half_w, y_base),
        (cx_base + half_w * 1.22, y_base - height * 0.34),
        (cx_tip + half_w * 0.30 + lean + tip_bend, y_tip + height * 0.26),
        (cx_tip + lean, y_tip),
    )
    left = bezier(
        (cx_tip + lean, y_tip),
        (cx_tip - half_w * 0.22 + lean + tip_bend, y_tip + height * 0.30),
        (cx_base - half_w * 1.14, y_base - height * 0.30),
        (cx_base - half_w, y_base),
    )
    base = bezier(
        (cx_base - half_w, y_base),
        (cx_base - half_w * 0.55, y_base + half_w * 0.34),
        (cx_base + half_w * 0.55, y_base + half_w * 0.34),
        (cx_base + half_w, y_base),
    )
    return right + left[1:] + base[1:]


def mask_from(polygons, blur=0.6):
    """Union of normalized polygons, rasterized at S times the output resolution."""
    canvas = Image.new("L", (W * S, H * S), 0)
    draw = ImageDraw.Draw(canvas)
    for points in polygons:
        draw.polygon([(x * W * S, y * H * S) for x, y in points], fill=255)
    if blur:
        canvas = canvas.filter(ImageFilter.GaussianBlur(blur * S))
    return canvas


def gradient_fill(mask, stops):
    """Vertical gradient through a mask; stops are (position, rgb) with 0 at the top."""
    alpha = np.asarray(mask, dtype=np.float32) / 255.0
    height = alpha.shape[0]
    position = np.linspace(0.0, 1.0, height, dtype=np.float32)[:, None]
    rgb = np.zeros((height, 1, 3), dtype=np.float32)
    ordered = sorted(stops, key=lambda stop: stop[0])
    for (t0, c0), (t1, c1) in zip(ordered, ordered[1:]):
        in_segment = (position >= t0) & (position <= t1)
        local = np.clip((position - t0) / max(t1 - t0, 1e-6), 0, 1)
        for channel in range(3):
            rgb[:, 0, channel] = np.where(
                in_segment[:, 0],
                c0[channel] + (c1[channel] - c0[channel]) * local[:, 0],
                rgb[:, 0, channel],
            )
    rgb = np.repeat(rgb, alpha.shape[1], axis=1)
    return np.dstack([rgb, alpha * 255.0])


def over(base, layer):
    """Source-over composite for float RGBA arrays."""
    sa = layer[:, :, 3:4] / 255.0
    da = base[:, :, 3:4] / 255.0
    out_a = sa + da * (1 - sa)
    rgb = (layer[:, :, :3] * sa + base[:, :, :3] * da * (1 - sa)) / np.maximum(out_a, 1e-6)
    return np.dstack([rgb, out_a * 255.0])


def make_frame(index):
    t = index / FRAMES
    breathe = 1.0 + 0.018 * math.sin(2 * math.pi * t)
    sway = 0.010 * math.sin(2 * math.pi * t + 0.7)

    cx = 0.50 + 0.006 * math.sin(4 * math.pi * t + 0.4)
    base_y = 0.905

    # Dominant central tongue, leaning right; the side licks breath out of phase.
    central = tongue(
        cx_base=cx,
        y_base=base_y,
        cx_tip=cx + 0.072 + 0.018 * math.sin(2 * math.pi * t + 1.1),
        y_tip=0.080 + 0.020 * math.sin(4 * math.pi * t + 0.3),
        half_w=0.270 * breathe,
        lean=sway,
        tip_bend=0.012 * math.sin(6 * math.pi * t + 0.9),
    )
    left = tongue(
        cx_base=cx - 0.160,
        y_base=base_y - 0.005,
        cx_tip=cx - 0.300 + 0.015 * math.sin(4 * math.pi * t + 2.0),
        y_tip=0.500 + 0.030 * math.sin(4 * math.pi * t + 1.4),
        half_w=0.165 * breathe,
        lean=sway * 0.6,
    )
    right = tongue(
        cx_base=cx + 0.170,
        y_base=base_y,
        cx_tip=cx + 0.305 + 0.014 * math.sin(4 * math.pi * t + 3.1),
        y_tip=0.335 + 0.028 * math.sin(4 * math.pi * t + 2.2),
        half_w=0.175 * breathe,
        lean=sway * 0.5,
    )

    body = mask_from([central, left, right], blur=0.45)
    rim = body.filter(ImageFilter.MaxFilter(5 * S + 1)).filter(ImageFilter.GaussianBlur(0.5 * S))
    inner = body.filter(ImageFilter.MinFilter(2 * S + 1)).filter(ImageFilter.GaussianBlur(0.8 * S))

    canvas = np.zeros((H * S, W * S, 4), dtype=np.float32)
    canvas = over(canvas, gradient_fill(rim, [(0.0, RIM_TOP), (0.5, RIM_MID), (1.0, RIM_BASE)]))
    canvas = over(canvas, gradient_fill(
        inner,
        [(0.0, BODY_TOP), (0.42, BODY_MID), (0.78, BODY_LOW), (1.0, BODY_BASE)],
    ))

    warm = mask_from([tongue(
        cx_base=cx + 0.002,
        y_base=base_y - 0.02,
        cx_tip=cx + 0.028 + 0.012 * math.sin(4 * math.pi * t + 1.9),
        y_tip=0.470 + 0.024 * math.sin(4 * math.pi * t + 1.2),
        half_w=0.150 * breathe,
        lean=sway * 0.3,
    )], blur=1.6)
    canvas = over(canvas, gradient_fill(
        warm,
        [(0.0, WARM_TOP), (0.55, WARM_MID), (1.0, WARM_BASE)],
    ))

    core = mask_from([tongue(
        cx_base=cx + 0.008,
        y_base=base_y - 0.035,
        cx_tip=cx + 0.036 + 0.010 * math.sin(4 * math.pi * t + 1.7),
        y_tip=0.560 + 0.022 * math.sin(4 * math.pi * t + 1.0),
        half_w=0.120 * breathe,
        lean=sway * 0.3,
    )], blur=1.4)
    canvas = over(canvas, gradient_fill(
        core,
        [(0.0, CORE_TOP), (0.45, CORE_MID), (1.0, CORE_BASE)],
    ))

    highlight = mask_from([tongue(
        cx_base=cx - 0.145,
        y_base=base_y - 0.06,
        cx_tip=cx - 0.030 + 0.006 * math.sin(4 * math.pi * t + 0.2),
        y_tip=0.245 + 0.015 * math.sin(4 * math.pi * t + 0.8),
        half_w=0.022,
        lean=sway * 0.4,
    )], blur=1.4).point(lambda value: int(value * 0.45))
    canvas = over(canvas, gradient_fill(highlight, [(0.0, HIGHLIGHT), (1.0, HIGHLIGHT_LOW)]))

    image = Image.fromarray(np.clip(canvas, 0, 255).astype(np.uint8), "RGBA")
    return image.resize((W, H), Image.LANCZOS)


def save_gif(frames, path):
    """Single global palette with one transparent index and an infinite loop."""
    sheet = Image.new("RGB", (W * len(frames), H), (0, 0, 0))
    for index, frame in enumerate(frames):
        flat = Image.new("RGB", (W, H), (0, 0, 0))
        flat.paste(frame, (0, 0), frame)
        sheet.paste(flat, (index * W, 0))
    palette_source = sheet.quantize(colors=127, method=Image.MEDIANCUT)
    transparent = 127

    output = []
    for frame in frames:
        flattened = Image.new("RGBA", (W, H), (0, 0, 0, 255))
        flattened.alpha_composite(frame)
        indexed = flattened.convert("RGB").quantize(palette=palette_source, dither=Image.NONE)
        values = np.asarray(indexed).copy()
        values[np.asarray(frame)[:, :, 3] < 128] = transparent
        image = Image.fromarray(values, "P")
        image.putpalette(palette_source.getpalette()[: 127 * 3] + [0, 0, 0])
        output.append(image)

    output[0].save(path, save_all=True, append_images=output[1:], duration=FRAME_MS,
                   loop=0, transparency=transparent, disposal=2, optimize=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True, help="directory for the generated frames")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)

    frames = [make_frame(index) for index in range(FRAMES)]
    for index, frame in enumerate(frames):
        frame.save(os.path.join(args.out, f"frame_{index:02d}.png"))

    atlas = Image.new("RGBA", (W * ATLAS_COLUMNS, H * math.ceil(FRAMES / ATLAS_COLUMNS)), (0, 0, 0, 0))
    for index, frame in enumerate(frames):
        atlas.paste(frame, ((index % ATLAS_COLUMNS) * W, (index // ATLAS_COLUMNS) * H))
    atlas.save(os.path.join(args.out, "flint_streak_sheet.png"))

    save_gif(frames, os.path.join(args.out, "flint_streak_fire.gif"))
    print(f"frames={FRAMES} atlas={atlas.size} "
          f"gif={os.path.getsize(os.path.join(args.out, 'flint_streak_fire.gif'))} bytes")


if __name__ == "__main__":
    main()
