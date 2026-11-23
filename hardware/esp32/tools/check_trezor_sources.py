#!/usr/bin/env python3
"""Verify the unmodified, pinned source subset and original English wordlist."""

import hashlib
import json
import pathlib
import re

root = pathlib.Path(__file__).resolve().parents[1]
vendor = root / "vendor/trezor-crypto-4.4.2"
manifest = json.loads((vendor / "provenance.json").read_text())
for name, expected in manifest["files_sha256"].items():
    path = vendor / name
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise SystemExit(f"Pinned TrezorCrypto source differs: {name}")
header = (vendor / "include/TrezorCrypto/bip39_english.h").read_text()
upstream_words = re.findall(r'"([a-z]+)"', header)
original_words = (root.parent / "words.txt").read_text().split()
if upstream_words != original_words or len(upstream_words) != 2048:
    raise SystemExit("Upstream BIP39 wordlist differs from the original wallet")
print(f"Verified {len(manifest['files_sha256'])} pinned files and 2048 original BIP39 words.")
