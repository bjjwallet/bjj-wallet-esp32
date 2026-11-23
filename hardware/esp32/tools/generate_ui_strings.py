#!/usr/bin/env python3
"""Generate strict, complete C++ language catalogs from ui/locales/*.json."""

import json
from pathlib import Path
import re
import sys

root, output = map(Path, sys.argv[1:])
manifest = json.loads((root / "esp32/ui/translations.json").read_text())
codes = ["en", "zh", "pt-BR", "fr", "it", "de", "es", "ja"]
locales = {
    d["code"]: d
    for name in manifest["catalogs"]
    for d in [json.loads((root / "esp32/ui/locales" / name).read_text())]
}
assert set(locales) == set(codes)
reference = locales["pt-BR"]
for group in ["setup", "original", "custom"]:
    keys = set(reference[group])
    for code in codes:
        assert set(locales[code][group]) == keys, (code, group, keys ^ set(locales[code][group]))
        for key, value in locales[code][group].items():
            assert isinstance(value, str) and value, (code, key)
            assert value.count("{}") == reference[group][key].count("{}"), (code, key)
            assert re.findall(r"%(?:llu|u)", value) == re.findall(
                r"%(?:llu|u)", reference[group][key]
            ), (code, key)


def quoted(value):
    return json.dumps(value, ensure_ascii=False)


def table(group):
    return [
        "{" + quoted(key) + ",{" + ",".join(quoted(locales[c][group][key]) for c in codes) + "}},"
        for key in reference[group]
    ]


header = [
    "// Generated from ui/locales/*.json. Do not edit.",
    "#pragma once",
    "#include <string_view>",
    '#include "ui/language.hpp"',
    "namespace airlock {",
    "enum class Text {",
    *[key + "," for key in reference["setup"]],
    "};",
    "inline const char* locale_text(Text text, Language language) {",
    "static constexpr const char* values[][8] = {",
    *[
        "{" + ",".join(quoted(locales[c]["setup"][key]) for c in codes) + "},"
        for key in reference["setup"]
    ],
    "};",
    "return values[static_cast<std::size_t>(text)][static_cast<std::size_t>(language)];",
    "}",
    "}",
]
(output.parent / "setup_strings.hpp").write_text("\n".join(header) + "\n")
legacy = [
    "// Generated from ui/locales/*.json. Do not edit.",
    "#pragma once",
    "#include <string_view>",
    '#include "ui/language.hpp"',
    "namespace airlock {",
    "struct LocalizedEntry { const char* key; const char* values[8]; };",
    "inline const char* original_text(std::string_view group, std::string_view key, Language language) {",
    "static constexpr LocalizedEntry entries[] = {",
    *table("original"),
    "};",
    "for (const auto& e : entries) { const std::string_view full(e.key); const auto dot = full.find('.'); if (full.substr(0, dot) == group && full.substr(dot + 1) == key) return e.values[static_cast<std::size_t>(language)]; }",
    'return "";',
    "}",
    "inline const char* custom_text(std::string_view key, const char*, Language language) {",
    "static constexpr LocalizedEntry entries[] = {",
    *table("custom"),
    "};",
    "for (const auto& e : entries) if (key == e.key) return e.values[static_cast<std::size_t>(language)];",
    'return "";',
    "}",
    "}",
]
output.write_text("\n".join(legacy) + "\n")
