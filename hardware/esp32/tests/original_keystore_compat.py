#!/usr/bin/env python3
"""Development parity check: execute the original save/load methods unchanged.

Uses only a public BIP39 fixture and temporary directories. Python is not used by
the C++ simulator or firmware; this independent test requires cryptography.
"""

import ast
import base64
import gc
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from typing import Dict, Any, Optional
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

root = Path(__file__).resolve().parents[2]
tree = ast.parse((root / "core/service.py").read_text())
original = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == "WalletService")
methods = [
    n
    for n in original.body
    if isinstance(n, ast.FunctionDef) and n.name in ("save_keystore", "load_keystore")
]
assert len(methods) == 2
original.body = methods
module = ast.fix_missing_locations(ast.Module(body=[original], type_ignores=[]))
exec(compile(module, str(root / "core/service.py"), "exec"), globals())
service = WalletService()
binary = str(Path(sys.argv[1]).resolve())
mnemonic = (
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
)
phrase = "Public-Passphrase1!"
password = "Public-Password1!"
with tempfile.TemporaryDirectory(prefix="bjj-keystore-parity-") as temp:
    temp = Path(temp)
    subprocess.run([binary, str(temp / "native")], check=True)
    for hidden, filename in [(True, "keystore.json"), (False, "keystore_24.json")]:
        expected = {
            "mnemonic": mnemonic,
            "passphrase": phrase if hidden else "",
            "isImported": True,
        }
        assert service.load_keystore(password, str(temp / "native" / filename)) == expected
    generation = "f" * 32
    snapshot = temp / "original" / generation
    snapshot.mkdir(parents=True)
    (snapshot.parent / "CURRENT").write_text(generation)
    for hidden, filename in [(True, "keystore.json"), (False, "keystore_24.json")]:
        assert service.save_keystore(
            {"mnemonic": mnemonic, "passphrase": phrase if hidden else "", "isImported": True},
            password,
            str(snapshot / filename),
        )
    subprocess.run([binary, "--verify-original", str(snapshot.parent)], check=True)
print("Keystore compatibility passed in both directions using the original methods.")
