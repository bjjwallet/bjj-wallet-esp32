#!/usr/bin/env python3
"""Generate immutable RGB565 coin icons with alpha from the checked-in PNG catalog. Requires Pillow."""

from pathlib import Path
from PIL import Image
import json

root = Path(__file__).resolve().parents[1] / "ui/assets/currency_icons"
cat = json.loads((root / "board_catalog.json").read_text())
out = [
    "// Generated from the existing currency icon catalog.",
    "// SPDX-License-Identifier: GPL-3.0-or-later",
    "#pragma once",
    '#include "lvgl.h"',
    "#include <cstring>",
    "namespace airlock {",
]
network_icons = {spec["name"]: spec["icon"] for spec in cat["networks"].values()}
icons = {**cat["coins"], **network_icons}
image_indices = {key: i for i, key in enumerate(dict.fromkeys(icons.values()))}
for key, i in image_indices.items():
    im = Image.open(root / cat["images"][key]).convert("RGBA")
    if im.size != (32, 32):
        raise ValueError(f"Expected a 32x32 icon for {key}")
    data = []
    alpha = []
    for r, g, b, a in im.getdata():
        v = (r >> 3) << 11 | (g >> 2) << 5 | b >> 3
        data.extend([v & 255, v >> 8])
        alpha.append(a)
    out += [
        f"alignas(4) inline const unsigned char coin_pixels_{i}[] = {{"
        + ",".join(map(str, data + alpha))
        + "};",
        f"inline const lv_image_dsc_t coin_image_{i} = [] {{ lv_image_dsc_t d{{}}; d.header.magic=LV_IMAGE_HEADER_MAGIC; d.header.cf=LV_COLOR_FORMAT_RGB565A8; d.header.w=32; d.header.h=32; d.header.stride=64; d.data_size=sizeof coin_pixels_{i}; d.data=coin_pixels_{i}; return d; }}();",
    ]
out += ["inline const lv_image_dsc_t* coin_icon(const char* name) {"]
for name, key in icons.items():
    out += [f'    if (std::strcmp(name,"{name}")==0) return &coin_image_{image_indices[key]};']
out += ["    return nullptr;", "}", "}"]
(root / "coin_icons.hpp").write_text("\n".join(out) + "\n")
