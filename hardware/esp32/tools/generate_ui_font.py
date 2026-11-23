#!/usr/bin/env python3
"""Development-only: subset the original TTC for the seven UI languages.

Requires fonttools 4.60.1 and lv_font_conv 1.5.3. The emitted C file is checked in;
normal macOS and ESP32 builds never need either package or Node.js.
"""

from pathlib import Path
from fontTools.ttLib import TTCollection
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix="bjj-font-") as temporary:
    temporary = Path(temporary)
    strings = temporary / "strings.hpp"
    subprocess.run(
        [sys.executable, str(root / "esp32/tools/generate_ui_strings.py"), str(root), str(strings)],
        check=True,
    )
    collection = TTCollection(root / "ui/font/font.ttc")
    ttf = temporary / "original.ttf"
    collection.fonts[0].save(ttf)
    content = strings.read_text()
    content += (temporary / "setup_strings.hpp").read_text()
    for source in ["wallet_app.cpp", "touch_view.cpp", "language.hpp"]:
        content += (root / "esp32/ui" / source).read_text()
    symbols = "".join(sorted({c for c in content if ord(c) > 127}))
    available = collection.fonts[0].getBestCmap()
    missing = [hex(ord(c)) for c in symbols if ord(c) not in available]
    if missing:
        raise SystemExit("Missing source glyphs: " + ", ".join(missing))
    subprocess.run(
        [
            sys.argv[1],
            "--size",
            "16",
            "--bpp",
            "4",
            "--format",
            "lvgl",
            "--font",
            str(ttf),
            "-r",
            "0x20-0x7e",
            "--symbols",
            symbols,
            "--no-compress",
            "--lv-font-name",
            "airlock_chinese_16",
            "-o",
            str(root / "esp32/ui/fonts/airlock_chinese_16.c"),
        ],
        check=True,
    )
