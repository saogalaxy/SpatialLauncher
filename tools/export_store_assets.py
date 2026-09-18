"""Build Meta Horizon Store listing assets from the icon source + screenshots.

Outputs docs/store-assets/ (git-tracked so the team shares one set):
  icon-512.png, spatialized-bg-180.png, spatialized-fg-180.png,
  hero-3000x900.png, cover-landscape.png, cover-square.png,
  cover-portrait.png, mini-landscape.png, logo-transparent.png,
  shot-*.png (2560x1440 upscales of the in-headset captures).

Dashboard upload + AI-expand/crop refinements stay manual (needs the
org's Developer Center session). Trailer MP4 must come from the real
YouTube demo source file — it cannot be generated here.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

root = Path(__file__).resolve().parents[1]
SRC = root / "branding" / "spatial-launcher-icon-source.png"
SHOTS = root / "docs" / "screenshots"
OUT = root / "docs" / "store-assets"
OUT.mkdir(parents=True, exist_ok=True)

ARIAL_BOLD = r"C:\Windows\Fonts\arialbd.ttf"
TITLE = "Spatial Launcher"
NAVY_TOP = (11, 17, 54)
NAVY_BOTTOM = (4, 6, 26)
WHITE = (255, 255, 255, 255)

art = Image.open(SRC).convert("RGBA")


def vgradient(w, h, top, bottom):
    base = Image.new("RGB", (w, h), top)
    px = base.load()
    for y in range(h):
        t = y / max(1, h - 1)
        px_line = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        for x in range(w):
            px[x, y] = px_line
    return base.convert("RGBA")


def paste_center(canvas, img, cx, cy, max_h):
    scale = max_h / img.size[1]
    nw = max(1, int(img.size[0] * scale))
    nh = max(1, int(img.size[1] * scale))
    r = img.resize((nw, nh), Image.Resampling.LANCZOS)
    canvas.alpha_composite(r, (int(cx - nw / 2), int(cy - nh / 2)))
    return nw, nh


def glow(canvas, cx, cy, rw, rh):
    g = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(g)
    d.ellipse([cx - rw, cy - rh, cx + rw, cy + rh], fill=(40, 140, 255, 70))
    canvas.alpha_composite(g.filter(ImageFilter.GaussianBlur(60)))


def title_font(px):
    return ImageFont.truetype(ARIAL_BOLD, px)


def draw_title(canvas, text, cx, cy, px, fill=(255, 255, 255, 255)):
    d = ImageDraw.Draw(canvas)
    f = title_font(px)
    d.text((cx, cy), text, font=f, fill=fill, anchor="mm")


# All focal art + titles are composed inside a 12% inset on every side
# (Meta safe-area / bleed practice). Nothing important touches an edge.


def cover(w, h, art_h_ratio=0.50, title_px=None, art_cy_ratio=0.38,
          title_cy_ratio=0.78, side_by_side=False):
    canvas = vgradient(w, h, NAVY_TOP, NAVY_BOTTOM)
    if side_by_side:
        ax, tx = int(w * 0.26), int(w * 0.64)
        glow(canvas, ax, h // 2, int(w * 0.13), int(h * 0.28))
        paste_center(canvas, art, ax, h // 2, int(h * art_h_ratio))
        draw_title(canvas, TITLE, tx, h // 2, title_px or int(h * 0.13))
    else:
        ax = w // 2
        ay = int(h * art_cy_ratio)
        glow(canvas, ax, ay, int(w * 0.24), int(h * 0.20))
        paste_center(canvas, art, ax, ay, int(h * art_h_ratio))
        draw_title(canvas, TITLE, ax, int(h * title_cy_ratio),
                   title_px or int(h * 0.095))
    return canvas.convert("RGB")


def save(img, name):
    p = OUT / name
    img.save(p, optimize=True)
    print(name, img.size)


# Icon 512 (solid, squared, 24-bit).
icon = Image.new("RGBA", (512, 512), WHITE)
paste_center(icon, art, 256, 256, int(512 * 0.78))
save(icon.convert("RGB"), "icon-512.png")

# Spatialized tile set: solid navy bg + transparent fg with padding.
bg = Image.new("RGB", (180, 180), (10, 16, 48))
save(bg, "spatialized-bg-180.png")
fg = Image.new("RGBA", (180, 180), (0, 0, 0, 0))
paste_center(fg, art, 90, 90, int(180 * 0.72))
fg.save(OUT / "spatialized-fg-180.png")
print("spatialized-fg-180.png", fg.size)

# Covers (consistent navy/art/title system, all content inside 12% insets).
save(cover(3000, 900, art_h_ratio=0.66, side_by_side=True), "hero-3000x900.png")
save(cover(2560, 1440, art_h_ratio=0.50), "cover-landscape.png")
save(cover(1440, 1440, art_h_ratio=0.46, art_cy_ratio=0.36, title_cy_ratio=0.78,
           title_px=100), "cover-square.png")
save(cover(1008, 1440, art_h_ratio=0.34, art_cy_ratio=0.30, title_cy_ratio=0.60,
           title_px=72), "cover-portrait.png")
save(cover(1080, 360, art_h_ratio=0.68, side_by_side=True), "mini-landscape.png")

# Logo lockup (transparent, white for dark surfaces).
f = title_font(300)
bbox = ImageDraw.Draw(Image.new("RGBA", (10, 10))).textbbox((0, 0), TITLE, font=f)
lw, lh = bbox[2] - bbox[0] + 80, bbox[3] - bbox[1] + 80
logo = Image.new("RGBA", (lw, lh), (0, 0, 0, 0))
draw_title(logo, TITLE, lw // 2, lh // 2, 300)
logo.save(OUT / "logo-transparent.png")
print("logo-transparent.png", logo.size)

# Screenshots: upscale in-headset captures to 2560x1440 PNG (first drafts —
# replace with native-res captures or dashboard AI-expand before submit).
picks = ["01-dock-home", "02-cast-source-open", "05-ocr-zones",
         "07-listen", "08-browser", "10-my-books"]
for i, stem in enumerate(picks, 1):
    src = SHOTS / (stem + ".jpg")
    if not src.exists():
        continue
    im = Image.open(src).convert("RGB").resize((2560, 1440), Image.Resampling.LANCZOS)
    save(im, f"shot-{i:02d}-{stem}.png")

print("store assets done ->", OUT)
