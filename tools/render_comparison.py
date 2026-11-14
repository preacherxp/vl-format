#!/usr/bin/env python3
"""Render a side-by-side before/after PNG for a source file.

Usage:
    python3 tools/render_comparison.py <messy-source-file> <output.png> [title]

Runs the vl-format fat jar on the input (--stdout), renders both versions as
a dark-themed side-by-side SVG with light syntax highlighting, and converts
to PNG with rsvg-convert. Stdlib only.
"""
import html
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

# ---- syntax highlighting (display-quality, not a real lexer) ----------------

KEYWORDS = {
    # shared + kotlin + java, display only
    "abstract", "as", "assert", "boolean", "break", "byte", "case", "catch", "char",
    "class", "companion", "const", "continue", "data", "default", "do", "double",
    "else", "enum", "extends", "final", "finally", "float", "for", "fun", "get",
    "if", "implements", "import", "in", "init", "instanceof", "int", "interface",
    "internal", "is", "lateinit", "long", "new", "null", "object", "operator",
    "override", "package", "private", "protected", "public", "record", "return",
    "sealed", "set", "short", "static", "super", "suspend", "switch", "this",
    "throw", "throws", "try", "typealias", "val", "var", "vararg", "void",
    "volatile", "when", "while", "by", "out", "where", "true", "false",
}

C_BG = "#0d1117"
C_PANEL = "#161b22"
C_BORDER = "#30363d"
C_TEXT = "#e6edf3"
C_KW = "#ff7b72"
C_STR = "#a5d6ff"
C_COM = "#8b949e"
C_NUM = "#79c0ff"
C_LINENO = "#484f58"
C_ACCENT = "#2dd4bf"
C_TITLE = "#e6edf3"

TOKEN_RE = re.compile(
    r"(?P<com>//[^\n]*|/\*.*?\*/)"
    r"|(?P<str>\"\"\".*?\"\"\"|\"(?:\\.|[^\"\\\n])*\"|'(?:\\.|[^'\\\n])*')"
    r"|(?P<num>\b\d[\w.]*\b)"
    r"|(?P<word>\b\w+\b)",
    re.DOTALL,
)

FONT = "SF Mono, Menlo, Consolas, monospace"
FS = 13          # font size
CW = 7.83        # monospace char width at FS
LH = 19          # line height
PAD = 14         # panel padding
GUTTER = 34      # line-number gutter


def color_spans(line: str):
    """Yield (text, color) runs for one line."""
    pos = 0
    for m in TOKEN_RE.finditer(line):
        if m.start() > pos:
            yield line[pos:m.start()], C_TEXT
        text = m.group(0)
        if m.lastgroup == "com":
            yield text, C_COM
        elif m.lastgroup == "str":
            yield text, C_STR
        elif m.lastgroup == "num":
            yield text, C_NUM
        elif m.lastgroup == "word":
            yield text, C_KW if text in KEYWORDS else C_TEXT
        pos = m.end()
    if pos < len(line):
        yield line[pos:], C_TEXT


def panel_svg(x: int, y: int, w: int, h: int, label: str, lines: list[str], label_color: str) -> str:
    parts = [
        f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" fill="{C_PANEL}" stroke="{C_BORDER}"/>',
        f'<text x="{x + PAD}" y="{y + 26}" font-family="{FONT}" font-size="12" '
        f'font-weight="bold" fill="{label_color}">{html.escape(label)}</text>',
    ]
    ty = y + 26 + LH
    for i, line in enumerate(lines, 1):
        parts.append(
            f'<text x="{x + PAD}" y="{ty}" font-family="{FONT}" font-size="{FS}" '
            f'fill="{C_LINENO}" text-anchor="end" dx="{GUTTER - 10}">{i}</text>'
        )
        tx = x + PAD + GUTTER
        run = [f'<text x="{tx}" y="{ty}" font-family="{FONT}" font-size="{FS}" xml:space="preserve">']
        for text, color in color_spans(line.rstrip("\n")):
            run.append(f'<tspan fill="{color}">{html.escape(text)}</tspan>')
        run.append("</text>")
        parts.append("".join(run))
        ty += LH
    return "\n".join(parts)


def render(before: str, after: str, title: str, out_png: pathlib.Path) -> None:
    b_lines = before.rstrip("\n").split("\n")
    a_lines = after.rstrip("\n").split("\n")
    rows = max(len(b_lines), len(a_lines))
    cols = max(
        max((len(l) for l in b_lines), default=0),
        max((len(l) for l in a_lines), default=0),
        40,
    )
    panel_w = int(PAD * 2 + GUTTER + cols * CW)
    panel_h = int(26 + LH * (rows + 1) + PAD)
    margin = 22
    header = 46
    width = margin * 2 + panel_w * 2 + 18
    height = header + panel_h + margin * 2

    svg = f"""<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}">
<rect width="{width}" height="{height}" fill="{C_BG}"/>
<text x="{margin}" y="{margin + 14}" font-family="{FONT}" font-size="16" font-weight="bold"
      fill="{C_TITLE}">{html.escape(title)}</text>
<text x="{width - margin}" y="{margin + 14}" font-family="{FONT}" font-size="12"
      fill="{C_ACCENT}" text-anchor="end">vl-format</text>
{panel_svg(margin, margin + header - 10, panel_w, panel_h, "BEFORE", b_lines, C_COM)}
{panel_svg(margin + panel_w + 18, margin + header - 10, panel_w, panel_h, "AFTER — vl-format", a_lines, C_ACCENT)}
</svg>"""

    out_svg = out_png.with_suffix(".svg")
    out_svg.write_text(svg)
    subprocess.run(
        ["rsvg-convert", "-o", str(out_png), "--dpi-x", "144", "--dpi-y", "144",
         "-z", "2", str(out_svg)],
        check=True,
    )
    print(f"wrote {out_png} ({rows} lines) and {out_svg}")


def main() -> None:
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    src = pathlib.Path(sys.argv[1])
    out_png = pathlib.Path(sys.argv[2])
    title = sys.argv[3] if len(sys.argv) > 3 else src.name

    jars = sorted((ROOT / "cli/build/libs").glob("vl-format-*.jar"))
    if not jars:
        sys.exit("build the jar first: ./gradlew :cli:fatJar")
    after = subprocess.run(
        ["java", "-jar", str(jars[-1]), "--stdout", str(src)],
        check=True, capture_output=True, text=True,
    ).stdout
    render(src.read_text(), after, title, out_png)


if __name__ == "__main__":
    main()
