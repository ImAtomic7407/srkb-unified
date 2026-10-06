"""Generates the PWA / iOS / macOS icon set into static/icons/.

Run:  C:\\Python314\\python.exe static/gen_pwa_icons.py
Only Pillow is required; no external art assets.

WHY THIS EXISTS AS A SCRIPT rather than four checked-in binaries with no
provenance: the motif has to stay in step with the desktop wrapper
(attendease-desktop/build/gen_icon.py) and the Android splash, which are both
indigo #4f46e5 with a white clipboard + green check. When the brand changes,
this regenerates every size instead of someone hand-editing PNGs and the
platforms drifting apart. The PNGs ARE committed — the script is the recipe,
not a build step anyone has to remember to run.

THE THREE VARIANTS ARE NOT INTERCHANGEABLE. This is the part that is easy to
get wrong and looks fine on the machine you tested it on:

  icon-<n>.png          purpose "any"      — rounded corners, TRANSPARENT outside.
                        Chrome/Android draws this as-is on the launcher.

  icon-maskable-512.png purpose "maskable" — full-bleed, NO transparency, motif
                        shrunk into the inner 80% safe zone. Android crops this
                        to whatever shape the launcher uses (circle, squircle,
                        teardrop). Feeding a rounded+transparent icon to a
                        maskable slot gets the corners cropped off the artwork.

  apple-touch-icon.png  180x180 — full-bleed, square, OPAQUE, saved as RGB with
                        no alpha channel at all. iOS applies its own rounded
                        mask. If you hand iOS an icon that already has rounded
                        corners with transparency, it composites the alpha over
                        BLACK and you get an icon with black notches in every
                        corner. This is the single most common iOS PWA icon bug
                        and it is invisible until you look at a real home screen,
                        which is why gen_pwa_icons verifies the saved file has
                        mode "RGB" before it exits.
"""
import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "icons")

# Same palette as attendease-desktop/build/gen_icon.py and the Capacitor splash.
INDIGO_TOP = (99, 102, 241)   # #6366f1
INDIGO_BOT = (67, 56, 202)    # #4338ca
WHITE = (255, 255, 255, 255)
GREEN = (34, 197, 94, 255)    # #22c55e
INK = (79, 70, 229, 255)      # #4f46e5


def vertical_gradient(size, top, bot):
    grad = Image.new("RGB", (1, size))
    for y in range(size):
        t = y / (size - 1)
        grad.putpixel((0, y), tuple(int(top[i] * (1 - t) + bot[i] * t) for i in range(3)))
    return grad.resize((size, size))


def rounded_mask(size, radius):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    return m


def draw_motif(icon, S, scale=1.0):
    """Clipboard + green check, centred, drawn at `scale` of the full canvas.

    `scale` exists for the maskable variant: Android may crop up to the outer
    20%, so the artwork has to live inside the inner circle or it loses its
    edges on a circular launcher.
    """
    d = ImageDraw.Draw(icon)
    off = int(S * (1 - scale) / 2)

    def px(v):
        return int(v * scale) + off

    bw, bh = int(S * 0.46 * scale), int(S * 0.56 * scale)
    bx = px(S * 0.27)
    by = px(S * 0.24)
    d.rounded_rectangle([bx, by, bx + bw, by + bh], radius=int(S * 0.05 * scale), fill=WHITE)

    # Clip at the top of the clipboard
    cw, ch = int(bw * 0.42), int(S * 0.09 * scale)
    cx = bx + (bw - cw) // 2
    cy = by - ch // 2
    d.rounded_rectangle([cx, cy, cx + cw, cy + ch], radius=int(ch * 0.4), fill=INK)

    # Two faint ruled lines
    for fy in (0.40, 0.55):
        ly = by + int(bh * fy)
        d.rounded_rectangle(
            [bx + int(bw * 0.16), ly, bx + int(bw * 0.84), ly + max(1, int(S * 0.018 * scale))],
            radius=max(1, int(S * 0.01 * scale)), fill=(203, 213, 225, 255))

    # Green check badge
    ccx = bx + int(bw * 0.50)
    ccy = by + int(bh * 0.72)
    r = int(bw * 0.30)
    d.ellipse([ccx - r, ccy - r, ccx + r, ccy + r], fill=GREEN)
    d.line([(ccx - int(r * 0.45), ccy + int(r * 0.02)),
            (ccx - int(r * 0.08), ccy + int(r * 0.38)),
            (ccx + int(r * 0.48), ccy - int(r * 0.34))],
           fill=WHITE, width=max(1, int(S * 0.028 * scale)), joint="curve")


def render(size, *, rounded, transparent, scale=1.0, supersample=4):
    S = size * supersample
    icon = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    base = vertical_gradient(S, INDIGO_TOP, INDIGO_BOT).convert("RGBA")
    icon.paste(base, (0, 0), rounded_mask(S, int(S * 0.22)) if rounded else None)
    draw_motif(icon, S, scale=scale)
    icon = icon.resize((size, size), Image.LANCZOS)
    if not transparent:
        # Flatten onto the brand indigo, then DROP the alpha channel entirely.
        flat = Image.new("RGB", (size, size), INDIGO_BOT)
        flat.paste(icon, (0, 0), icon)
        return flat
    return icon


def main():
    os.makedirs(OUT, exist_ok=True)
    written = []

    for n in (192, 512):
        p = os.path.join(OUT, f"icon-{n}.png")
        render(n, rounded=True, transparent=True).save(p, "PNG")
        written.append(p)

    p = os.path.join(OUT, "icon-maskable-512.png")
    render(512, rounded=False, transparent=False, scale=0.80).save(p, "PNG")
    written.append(p)

    apple = os.path.join(OUT, "apple-touch-icon.png")
    render(180, rounded=False, transparent=False).save(apple, "PNG")
    written.append(apple)

    for p in written:
        im = Image.open(p)
        print(f"  {os.path.relpath(p, HERE):44} {im.size[0]}x{im.size[1]} {im.mode}")
        im.close()

    # Guard the iOS black-corner bug at generation time rather than trusting the
    # code above stayed correct — a stray alpha channel here is invisible in every
    # browser and only shows up on a physical home screen.
    im = Image.open(apple)
    assert im.mode == "RGB", f"apple-touch-icon must be opaque RGB, got {im.mode}"
    assert im.size == (180, 180), im.size
    im.close()
    print("\nOK — apple-touch-icon is opaque RGB 180x180 (no black corners on iOS).")


if __name__ == "__main__":
    main()
