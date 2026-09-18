from pathlib import Path

from PIL import Image

root = Path(__file__).resolve().parents[1]
src = root / "branding" / "spatial-launcher-icon-source.png"
res = root / "app" / "src" / "main" / "res"
img = Image.open(src).convert("RGBA")

w, h = img.size
side = min(w, h)
left = (w - side) // 2
top = (h - side) // 2
sq = img.crop((left, top, left + side, top + side))
WHITE = (255, 255, 255, 255)


def fit(canvas_size, art_ratio=1.0, bg=WHITE):
    canvas = Image.new("RGBA", (canvas_size, canvas_size), bg)
    art_size = max(1, int(canvas_size * art_ratio))
    art = sq.resize((art_size, art_size), Image.Resampling.LANCZOS)
    off = (canvas_size - art_size) // 2
    canvas.alpha_composite(art, (off, off))
    return canvas


drawable = res / "drawable"
drawable.mkdir(parents=True, exist_ok=True)
# Extra inset so the landscape goggles do not fill the square tile.
fit(1080, art_ratio=0.58, bg=WHITE).save(drawable / "ic_launcher_foreground.png", optimize=True)

sizes = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
for folder, px in sizes.items():
    d = res / folder
    d.mkdir(parents=True, exist_ok=True)
    out = fit(px, art_ratio=0.78, bg=WHITE)
    out.save(d / "ic_launcher.png", optimize=True)
    out.save(d / "ic_launcher_round.png", optimize=True)

# Meta Horizon Store listing icon (180x180 from the same source art).
fit(180, art_ratio=0.78, bg=WHITE).convert("RGB").save(
    root / "branding" / "store-icon-180.png", optimize=True
)

# Windows desktop icon (multi-size ICO from the same source art).
# Transparent background: the tray/taskbar sit on dark surfaces, so a baked-in
# white tile would show as a white box (Android tiles keep WHITE above).
TRANSPARENT = (0, 0, 0, 0)
desktop_assets = root / "desktop" / "src" / "SpatialLauncher.Desktop" / "Assets"
desktop_assets.mkdir(parents=True, exist_ok=True)
ico_canvas = fit(256, art_ratio=0.92, bg=TRANSPARENT)
ico_canvas.save(
    desktop_assets / "app.ico",
    sizes=[(16, 16), (32, 32), (48, 48), (256, 256)],
)

print("exported launcher icons")
