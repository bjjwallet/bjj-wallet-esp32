#!/usr/bin/env python3
"""Read-only audit of public caches in a local ESP-IDF NVS backup.

Never decrypts wallet records, never derives a seed/private key and never emits
encoded wallet blobs. Output contains only identifiers, public configuration,
counts and public-address fingerprints (full public accounts only when asked).
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("backup", type=Path)
parser.add_argument("--idf", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--include-public-accounts", action="store_true")
args = parser.parse_args()
sys.path.insert(0, str(args.idf / "components/nvs_flash/nvs_partition_tool"))
from nvs_parser import NVS_Partition

partition = NVS_Partition(args.backup.name, bytearray(args.backup.read_bytes()))
entries = [
    e
    for p in sorted(partition.pages, key=lambda p: p.header["page_index"])
    for e in p.entries
    if e.state == "Written"
]
spaces = {e.data["value"]: e.key for e in entries if e.metadata["namespace"] == 0 and e.data}
namespace = next(n for n, name in spaces.items() if name == "bjj_wallet")
entries = [e for e in entries if e.metadata["namespace"] == namespace]


def contents(entry):
    return b"".join(bytes(child.raw) for child in entry.children)[: entry.data["size"]]


def blob(key):
    indexes = [e for e in entries if e.key == key and e.metadata["type"] in ("blob_index", "blob")]
    if not indexes:
        raise ValueError("Required blob missing: " + key)
    index = indexes[-1]
    if index.metadata["type"] == "blob":
        return contents(index)
    chunks = []
    for number in range(
        index.data["chunk_start"], index.data["chunk_start"] + index.data["chunk_count"]
    ):
        matches = [
            e
            for e in entries
            if e.key == key
            and e.metadata["type"] == "blob_data"
            and e.metadata["chunk_index"] == number
        ]
        if len(matches) != 1:
            raise ValueError("Ambiguous/incomplete public storage")
        chunks.append(contents(matches[0]))
    result = b"".join(chunks)
    assert len(result) == index.data["size"]
    return result


def setting(key):
    found = [e for e in entries if e.key == key and e.metadata["type"] == "string"]
    return contents(found[-1]).rstrip(b"\0").decode("utf-8") if found else None


encoded = blob("wallet_v1")
if len(encoded) == 476:
    count = 1
    offset = 0
    pin = bool(encoded[8] & 1)
    mobile_pin = bool(encoded[8] & 8)
else:
    assert len(encoded) >= 200 and hashlib.sha256(encoded[:-32]).digest() == encoded[-32:]
    count = encoded[11]
    offset = 168
    assert len(encoded) == 200 + count * 476
    pin = bool(encoded[8])
    mobile_pin = bool(encoded[9])
wallets = []
for index in range(count):
    record = encoded[offset + index * 476 : offset + (index + 1) * 476]
    assert record[:7] == b"BJJWLT1" and hashlib.sha256(record[:444]).digest() == record[444:]
    public_id = hashlib.sha256(record[107:444]).digest()
    data = blob("p" + public_id.hex()[:14])
    assert data[:3] == b"PUB" and data[3] in (1, 2) and data[4:36] == public_id
    assert hashlib.sha256(data[:-32]).digest() == data[-32:]
    count_at = 68 if data[3] == 2 else 36
    pos = count_at + 1
    stable = data[36:68] if data[3] == 2 else public_id
    dictionary = []

    def text():
        global pos
        size = int.from_bytes(data[pos : pos + 2], "big")
        pos += 2
        if size == 65535:
            idx = int.from_bytes(data[pos : pos + 2], "big")
            pos += 2
            return dictionary[idx]
        assert size <= 512 and pos + size <= len(data) - 32
        value = data[pos : pos + size].decode("utf-8")
        pos += size
        dictionary.append(value)
        return value

    modes = {"STANDARD": [], "HIDDEN": []}
    for _ in range(data[count_at]):
        mode, decimals, indexed = data[pos : pos + 3]
        pos += 3
        assert mode in (0, 1) and indexed in (0, 1)
        row = {
            key: text()
            for key in ("coin", "symbol", "name", "address", "xpub", "path", "blockchain", "curve")
        }
        row.update(decimals=decimals, is_main=mode)
        if indexed:
            row["address_index"] = 0
        assert not row["xpub"] or row["xpub"].startswith(("xpub", "ypub", "zpub", "dgub", "dpub"))
        modes["HIDDEN" if mode else "STANDARD"].append(row)
    assert pos == len(data) - 32
    for rows in modes.values():
        rows.sort(key=lambda r: (r["coin"], r.get("address_index", 0)))
        assert len({r["coin"] for r in rows}) == len(rows)
    entry = {
        "id": stable.hex(),
        "name": record[11 : 11 + record[10]].decode("utf-8"),
        "config": {
            "word_count": record[9],
            "passphrase_enabled": bool(record[8] & 2),
            "imported": bool(record[8] & 4),
        },
        "modes": {
            mode: {
                "account_count": len(rows),
                "coins": [r["coin"] for r in rows],
                "address_set_sha256": hashlib.sha256(
                    json.dumps(rows, sort_keys=True).encode()
                ).hexdigest(),
            }
            for mode, rows in modes.items()
        },
    }
    if args.include_public_accounts:
        entry["public_accounts"] = modes
    wallets.append(entry)
assert len({w["id"] for w in wallets}) == len(wallets)
result = {
    "source": "read-only public-cache audit of device backup; not a BLE capture",
    "device_name": "BJJ-Cold-Wallet"
    + ("-" + setting("device_name") if setting("device_name") else ""),
    "pin_required": pin and mobile_pin,
    "wallet_count": len(wallets),
    "wallets": wallets,
}
os.umask(0o077)
args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
os.chmod(args.output, 0o600)
for wallet in wallets:
    print(wallet["name"], {mode: v["account_count"] for mode, v in wallet["modes"].items()})
print("Public cache audit saved; no wallet blob, credential, seed or private key exported.")
