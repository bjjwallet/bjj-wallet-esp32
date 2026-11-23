#!/usr/bin/env python3
"""Embed the existing BIP39 English wordlist; no new wallet/wordlist source."""

import pathlib
import sys

source, destination = map(pathlib.Path, sys.argv[1:])
words = source.read_text(encoding="utf-8").split()
if (
    len(words) != 2048
    or words != sorted(set(words))
    or not all(w.isascii() and w.isalpha() and w.islower() for w in words)
):
    raise SystemExit("Expected the original sorted 2048-word BIP39 English list")
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(
    "// Generated from words.txt. Do not edit.\n#pragma once\n"
    "#include <array>\n#include <string_view>\nnamespace airlock {\n"
    "inline constexpr std::array<std::string_view, 2048> words = {\n"
    + "".join(f'    "{word}",\n' for word in words)
    + "};\n}\n",
    encoding="utf-8",
)
