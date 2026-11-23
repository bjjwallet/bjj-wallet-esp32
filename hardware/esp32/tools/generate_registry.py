#!/usr/bin/env python3
"""Compile the unchanged original registry into native C++ constants."""

import json
import pathlib
import sys

source, target = map(pathlib.Path, sys.argv[1:])
coins = json.loads(source.read_text())
versions = {"xpub": 0x0488B21E, "zpub": 0x04B24746, "dpub": 0x02FDA926, "dgub": 0x02FACAFD}
lines = [
    "// Generated from registry.json; do not edit.",
    "#pragma once",
    "#include <cstdint>",
    "namespace airlock {",
    "struct CoinRecord { const char *id, *name, *symbol, *path, *blockchain, *curve; std::uint32_t coin, version; int decimals; };",
    "inline constexpr CoinRecord coin_registry[] = {",
]
for c in coins:
    d = c["derivation"][0]
    values = [c["id"], c["name"], c["symbol"], d["path"], c["blockchain"], c["curve"]]
    lines.append(
        "{"
        + ",".join(json.dumps(s, ensure_ascii=True) for s in values)
        + f",{c['coinId']}U,{versions[d.get('xpub', 'xpub')]}U,{c['decimals']}"
        + "},"
    )
lines.extend(["};", "} // namespace airlock", ""])
target.write_text("\n".join(lines))
