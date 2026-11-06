"""Prepare report/main.tex for pandoc, so the Word report is built from the same source as the PDF.

Pandoc reads ordinary LaTeX well but not this report's custom macros, boxes, TikZ figures or table
column types. This script rewrites them into plain LaTeX plus a few markers that docx/build_docx.js
understands, resolving every \\ref and \\cite from the LaTeX run's main.aux so the numbers match the PDF.

Outputs (in report/build/docx/): main-pandoc.tex, widths.json (column widths of each table, in order).
Run after latexmk (needs main.aux) and after render-figures.sh (needs build/png/*.png).
"""
from __future__ import annotations

import json
import re
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
REPORT = ROOT / "report"
OUT = REPORT / "build" / "docx"


def read_group(s: str, i: int) -> tuple[str, int]:
    """s[i] == '{': return the balanced group's content and the index after it."""
    assert s[i] == "{", s[i:i + 40]
    depth, j = 0, i
    while True:
        c = s[j]
        if c == "\\":
            j += 2
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return s[i + 1:j], j + 1
        j += 1


def inline_inputs(text: str) -> str:
    def repl(m: re.Match[str]) -> str:
        name = m.group(1)
        if not name.startswith("sections/"):
            return m.group(0)
        return inline_inputs((REPORT / f"{name}.tex").read_text())
    return re.sub(r"\\input\{([^}]+)\}", repl, text)


def strip_comments(text: str) -> str:
    return re.sub(r"(?<!\\)%.*", "", text)


def labels() -> dict[str, str]:
    aux = (REPORT / "main.aux").read_text()
    out = {}
    for m in re.finditer(r"\\newlabel\{([^}]+)\}\{\{([^}]*)\}", aux):
        out[m.group(1)] = m.group(2)
    return out


def colspec_widths(spec: str) -> list[str]:
    """'L{3.6cm}L{2.5cm}Y' -> ['3.6', '2.5', 'X'];  'ccc' -> ['auto', 'auto', 'auto']."""
    widths, i = [], 0
    while i < len(spec):
        c = spec[i]
        if c in "LCp" and i + 1 < len(spec) and spec[i + 1] == "{":
            body, i = read_group(spec, i + 1)
            widths.append(body.replace("cm", "") + ("c" if c == "C" else ""))
            continue
        if c in "YX":
            widths.append("X")
        elif c in "lcr":
            widths.append("auto")
        elif c == "@" or c == ">" or c == "<":
            _, i = read_group(spec, i + 1)
            continue
        i += 1
    return widths


def placeholder_png(name: str, label: str) -> Path:
    path = OUT / f"placeholder-{name}"
    w, h = 430, 900
    img = Image.new("RGB", (w, h), "white")
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([4, 4, w - 5, h - 5], radius=40, fill=(239, 239, 239), outline=(138, 138, 138), width=4)
    d.rounded_rectangle([165, 30, 265, 44], radius=6, outline=(138, 138, 138), width=3)
    try:
        f = ImageFont.truetype("/usr/share/fonts/truetype/liberation/LiberationSerif-Bold.ttf", 30)
        g = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf", 19)
    except OSError:
        f = g = ImageFont.load_default()
    y = 360
    for text, font in [(label, f), ("", g), ("screenshot to add:", g), ("report/screens/", g), (name, g)]:
        tw = d.textlength(text, font=font)
        d.text(((w - tw) / 2, y), text, font=font, fill=(110, 110, 110))
        y += 40
    img.save(path, dpi=(150, 150))
    return path


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    src = (REPORT / "main.tex").read_text()
    body = src[src.index(r"\begin{document}") + len(r"\begin{document}"):src.index(r"\end{document}")]
    body = re.sub(r"% BEGIN-TITLEPAGE.*?% END-TITLEPAGE", "", body, flags=re.S)
    body = re.sub(r"% BEGIN-TOC.*?% END-TOC", "", body, flags=re.S)
    body = strip_comments(inline_inputs(body))
    lab = labels()

    # bibliography -> numbered paragraphs (order of \bibitem defines the numbers)
    keys = re.findall(r"\\bibitem\{([^}]+)\}", body)
    num = {k: i + 1 for i, k in enumerate(keys)}
    bib = re.search(r"\{\\renewcommand\{\\section\}\[2\]\{\}\s*\\begin\{thebibliography\}\{99\}\\small(.*?)\\end\{thebibliography\}\}", body, flags=re.S)
    assert bib, "bibliography block not found"
    items = re.split(r"\\bibitem\{([^}]+)\}", bib.group(1))[1:]
    paras = [f"BIBITEM@@{num[items[i]]}@@ {items[i + 1].strip()}\n\n" for i in range(0, len(items), 2)]
    body = body[:bib.start()] + "".join(paras) + body[bib.end():]

    # citations and references
    body = re.sub(r"\\cite\{([^}]+)\}", lambda m: "[" + ", ".join(str(num[k.strip()]) for k in m.group(1).split(",")) + "]", body)
    body = re.sub(r"\\ref\{([^}]+)\}", lambda m: lab[m.group(1)], body)

    # numbered equations -> display math with a tag the renderer right-aligns
    def eq(m: re.Match[str]) -> str:
        inner = m.group(1)
        lm = re.search(r"\\label\{([^}]+)\}", inner)
        tag = lab[lm.group(1)] if lm else ""
        inner = re.sub(r"\\label\{[^}]+\}", "", inner).strip()
        return f"\\[{inner} \\eqtag{{{tag}}}\\]"
    body = re.sub(r"\\begin\{equation\}(.*?)\\end\{equation\}", eq, body, flags=re.S)

    # figures
    body = re.sub(r"\\resizebox\{([^}]*)\}\{!\}\{\\fig\{([^}]+)\}\}", r"\\includegraphics[width=\1]{build/png/\2.png}", body)
    body = re.sub(r"\\fig\{([^}]+)\}", r"\\includegraphics[width=\\linewidth]{build/png/\1.png}", body)

    def appshot(m: re.Match[str]) -> str:
        name, label = m.group(1), m.group(2)
        real = REPORT / "screens" / name
        path = real if real.exists() else placeholder_png(name, label)
        return rf"\includegraphics[width=0.2\linewidth]{{{path.relative_to(REPORT)}}}"
    body = re.sub(r"\\appshot\{([^}]+)\}\{([^}]+)\}", appshot, body)

    # boxes -> quote with a marker paragraph
    body = re.sub(r"\\begin\{pbox\}\{(\w+)\}\{(.*?)\}\n", lambda m: f"\\begin{{quote}}\nBOX@@{m.group(1)}@@{m.group(2)}\n\n", body)
    body = body.replace(r"\end{pbox}", r"\end{quote}")

    # step lists
    body = re.sub(r"\\begin\{enumerate\}\[label=\\textbf\{Step \\arabic\*:\}[^\]]*\]\s*\\item", r"\\begin{enumerate}\n\\item STEPLIST@@ ", body)

    # listings options
    body = re.sub(r"\\begin\{lstlisting\}\[[^\]]*\]", r"\\begin{lstlisting}", body)

    # tables: column widths to a side file, plain colspecs for pandoc; emphasise rows after \hline
    widths: list[list[str]] = []
    out, pos = [], 0
    for m in re.finditer(r"\\begin\{(tabularx|tabular)\}", body):
        i = m.end()
        if m.group(1) == "tabularx":
            _, i = read_group(body, i)                 # {\linewidth}
        spec, j = read_group(body, i)
        w = colspec_widths(spec)
        widths.append(w)
        out.append(body[pos:m.start()])
        out.append(r"\begin{tabular}{" + "l" * len(w) + "}")
        pos = j
    out.append(body[pos:])
    body = "".join(out).replace(r"\end{tabularx}", r"\end{tabular}")
    body = re.sub(r"\\hline\s*(\\rowcolor\{[^}]*\})?\s*", "ROWEM@@", body)
    body = re.sub(r"\\\\\[[0-9.]+pt\]", r"\\\\", body)

    # macros pandoc does not know
    simple = {
        r"\headrow": "", r"\stripes": "", r"\small": "", r"\footnotesize": "", r"\smallskip": "",
        r"\medskip": "", r"\centering": "", r"\noindent": "", r"\clearpage": "",
    }
    for a, b in simple.items():
        body = re.sub(re.escape(a) + r"(?![A-Za-z])\s*", b, body)
    body = re.sub(r"\\rowcolors\{\d+\}\{[^}]*\}\{[^}]*\}", "", body)
    body = re.sub(r"\\rowcolor\{[^}]*\}", "", body)
    body = re.sub(r"\\hd\{([^}]*)\}", r"\1", body)
    body = re.sub(r"\\cm(?![A-Za-z])", "✓", body)
    body = re.sub(r"\\xm(?![A-Za-z])", "✗", body)
    body = re.sub(r"\\pa(?![A-Za-z])", "Partial", body)

    while True:
        m = re.search(r"\\ep\{", body)
        if not m:
            break
        inner, j = read_group(body, m.end() - 1)
        esc = inner.replace("_", r"\_").replace("{", r"\{").replace("}", r"\}")
        body = body[:m.start()] + r"\texttt{" + esc + "}" + body[j:]

    pre = "\\documentclass{article}\n\\begin{document}\n"
    (OUT / "main-pandoc.tex").write_text(pre + body + "\n\\end{document}\n")
    (OUT / "widths.json").write_text(json.dumps(widths))
    print(f"wrote {OUT.relative_to(ROOT)}/main-pandoc.tex ({len(widths)} tables, {len(keys)} references)")


if __name__ == "__main__":
    main()
