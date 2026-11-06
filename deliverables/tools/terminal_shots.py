"""Render the saved terminal transcripts (report/screens/src/*.txt) as terminal-style PNG screenshots.

The transcripts are real output from a run of the dev stack (photon-dev) with photon-cli, the test
suite and the benchmark suite. Run: python3 tools/terminal_shots.py
"""
from __future__ import annotations

import re
import textwrap
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "report" / "screens" / "src"
OUT = ROOT / "report" / "screens"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"
BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf"

TITLES = {
    "demo-share": "alice and bob: sign in, create, join, upload (photon-cli)",
    "demo-receive": "bob: live events over the WebSocket, snapshot, byte-exact copies",
    "demo-recall": "recall and end: downloads refused, events delivered",
    "server-log": "photon-dev: HTTP and WebSocket requests on the server",
    "tests-bench": "test suite and HTTP announce benchmark",
}
BG, BAR, FG, DIM = (30, 32, 36), (52, 55, 61), (220, 223, 228), (140, 146, 156)
COLORS = [
    (r"^\$ .*", (126, 211, 135)),
    (r"\b(STORED|PHOTO_STORED|passed|200 OK|204 No Content|\[accepted\])\b", (126, 211, 135)),
    (r"\b(ALREADY_UPLOADED|PHOTO_RECALLED)\b", (240, 196, 92)),
    (r"\b(SESSION_ENDED|stopped)\b", (200, 150, 245)),
    (r"(error: .*|410 \w+)", (240, 113, 113)),
]
WRAP = 100


def colour_spans(line: str) -> list[tuple[str, tuple[int, int, int]]]:
    if line.startswith("$ "):
        return [(line, COLORS[0][1])]
    marks = [FG] * len(line)
    for pattern, colour in COLORS[1:]:
        for m in re.finditer(pattern, line):
            for i in range(m.start(), m.end()):
                marks[i] = colour
    spans: list[tuple[str, tuple[int, int, int]]] = []
    for ch, c in zip(line, marks):
        if spans and spans[-1][1] == c:
            spans[-1] = (spans[-1][0] + ch, c)
        else:
            spans.append((ch, c))
    return spans


def render(name: str, text: str) -> None:
    font, bold = ImageFont.truetype(FONT, 26), ImageFont.truetype(BOLD, 24)
    lines: list[str] = []
    for raw in text.rstrip("\n").split("\n"):
        lines.extend(textwrap.wrap(raw, WRAP, subsequent_indent="  ", break_long_words=True) or [""])
    cw = font.getbbox("M")[2]
    lh = 36
    width = max(1500, 60 + cw * min(WRAP + 2, max(len(l) for l in lines)))
    height = 70 + 30 + lh * len(lines) + 30
    img = Image.new("RGB", (width, height), (255, 255, 255))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, width - 1, height - 1], radius=18, fill=BG)
    d.rounded_rectangle([0, 0, width - 1, 70], radius=18, fill=BAR)
    d.rectangle([0, 50, width - 1, 70], fill=BAR)
    for i, c in enumerate([(255, 95, 86), (255, 189, 46), (39, 201, 63)]):
        d.ellipse([28 + i * 38, 22, 52 + i * 38, 46], fill=c)
    title = TITLES.get(name, name)
    tw = d.textlength(title, font=bold)
    d.text(((width - tw) / 2, 20), title, font=bold, fill=DIM)
    y = 70 + 30
    for line in lines:
        x = 30
        for chunk, colour in colour_spans(line):
            d.text((x, y), chunk, font=font, fill=colour)
            x += d.textlength(chunk, font=font)
        y += lh
    img.save(OUT / f"{name}.png", dpi=(220, 220))
    print(f"wrote screens/{name}.png ({width}x{height})")


def main() -> None:
    for path in sorted(SRC.glob("*.txt")):
        render(path.stem, path.read_text())


if __name__ == "__main__":
    main()
