"""Renders the text labels of the countdown poster (CountdownImageController).

The server draws the digits itself as seven-segment shapes, so it needs no
fonts; the few words are these pre-rendered PNGs (white on transparent).
Run from ingest-api/:  python tools/countdown_labels.py
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/countdown"
FONT = "C:/Windows/Fonts/arialbd.ttf"

LABELS = {
    "ends-in.png": ("OFFER ENDS IN", 34),
    "ended.png": ("THIS OFFER HAS ENDED", 40),
    "days.png": ("DAYS", 18),
    "hrs.png": ("HRS", 18),
    "min.png": ("MIN", 18),
    "sec.png": ("SEC", 18),
}


def render(text: str, size: int) -> Image.Image:
    font = ImageFont.truetype(FONT, size)
    left, top, right, bottom = font.getbbox(text)
    spacing = round(size * 0.12)                      # letter-spaced, as festive banners are
    width = sum(font.getlength(ch) for ch in text) + spacing * (len(text) - 1)
    img = Image.new("RGBA", (int(width) + 4, bottom - top + 4), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    x = 2
    for ch in text:
        draw.text((x, 2 - top), ch, font=font, fill=(255, 255, 255, 255))
        x += font.getlength(ch) + spacing
    return img


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for name, (text, size) in LABELS.items():
        render(text, size).save(OUT / name, optimize=True)
        print(name)
