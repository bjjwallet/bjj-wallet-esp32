#!/usr/bin/env python3
"""Audit all registered coins for 12/18/24 words, with/without a passphrase.

Uses the real native Mac WalletService, isolated public fixtures and the pinned
original-WASM oracle plus independent references for the five additions.
Does not read user wallets or claim transaction support.
"""

import argparse
import collections
import hashlib
import json
import pathlib
import re
import sqlite3
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
QUERY = """SELECT coin,symbol,name,address,extended_public_key,path,decimals,blockchain,curve,is_main,-1 FROM wallets_xpub
UNION ALL SELECT coin,symbol,name,address,'',path,decimals,blockchain,curve,is_main,address_index FROM wallets_address"""


def digest(rows):
    return hashlib.sha256(
        "".join(sorted("\t".join(map(str, row)) + "\n" for row in rows)).encode()
    ).hexdigest()


def audit(binary, oracle, additions):
    registry = json.loads((ROOT / "registry.json").read_text())
    manager = (ROOT / "js/SignerManager.js").read_text()
    routes = dict(re.findall(r"'([^']+)': require\('\./signer/([^']+)'\)", manager))
    expected = {c["id"]: c for c in oracle["cases"]}
    added = {c["id"]: c for c in additions["cases"]}
    added_ids = {"nano", "nimiq", "neo", "ontology", "cardano"}
    report = dict(
        wallet_core="4.4.2",
        platform="macOS native C/C++",
        scope="Import, derivation, original 158-entry parity, five new entries against independent references, and encrypted keystore readback; no transaction signing or ESP32 execution",
        fixtures="Public zero-entropy BIP39 vectors; never use for funds",
        reference_source_sha256={
            name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
            for name in [
                "registry.json",
                "js/Utils.js",
                "js/crypto_worker.js",
                "js/SignerManager.js",
            ]
        },
        new_wallet_words=[24],
        new_wallet_requires_passphrase=True,
        import_word_counts=[12, 15, 18, 21, 24],
        audited_word_counts=[12, 18, 24],
        native_signing_implemented=False,
        esp32_crypto_backend_implemented=False,
        default_path_only=True,
        cases=[],
        coins=[],
    )
    assert (
        report["reference_source_sha256"] == oracle["source_sha256"]
    ), "Original source changed; regenerate the WASM reference before checking parity"
    assert additions["registry_sha256"] == oracle["source_sha256"]["registry.json"]
    report["additional_reference"] = {
        k: v for k, v in additions.items() if k not in ["cases", "cardano_checks"]
    }
    by_case = {}
    with tempfile.TemporaryDirectory(prefix="bjj-coin-audit-") as temp:
        subprocess.run(
            [str(pathlib.Path(binary).resolve()), temp], text=True, capture_output=True, check=True
        )
        for count in [12, 18, 24]:
            for with_phrase in [False, True]:
                case = f"{count}-" + ("with" if with_phrase else "without")
                directory = pathlib.Path(temp) / case
                current = (directory / "CURRENT").read_text()
                assert re.fullmatch("[0-9a-f]{32}", current)
                with sqlite3.connect(
                    f'file:{directory/current/"wallet.db"}?mode=ro', uri=True
                ) as db:
                    rows = db.execute(QUERY).fetchall()
                groups = collections.defaultdict(list)
                for row in rows:
                    assert row[3], (case, row[0], "empty address")
                    groups[row[0]].append(row)
                reference = expected[case]
                extra = added[case]
                assert not reference["failures"], reference["failures"]
                original_rows = [v for v in rows if v[0] not in added_ids]
                new_rows = [v for v in rows if v[0] in added_ids]
                assert digest(original_rows) == reference["sha256"], (
                    case,
                    "original/native mismatch",
                )
                assert len(original_rows) == reference["rows"]
                assert digest(new_rows) == extra["sha256"], (
                    case,
                    "additional reference/native mismatch",
                )
                assert len(new_rows) == extra["rows"]
                assert set(groups) == {c["id"] for c in registry}, (case, "missing/unknown coin")
                for coin in registry:
                    cid = coin["id"]
                    values = groups.get(cid, [])
                    ref = extra if cid in added_ids else reference
                    assert digest(values) == ref["coins"][cid]["sha256"], (case, cid, "mismatch")
                    main = {(v[5], v[10]): (v[3], v[4]) for v in values if v[9] == 1}
                    standard = {(v[5], v[10]): (v[3], v[4]) for v in values if v[9] == 0}
                    assert len(main) == (1 if coin["curve"] == "secp256k1" else 100)
                    assert len(standard) == (1 if coin["curve"] == "secp256k1" else 10)
                    if cid in added_ids:
                        assert len({v[3] for v in values if v[9] == 1}) == 100, (
                            case,
                            cid,
                            "duplicate addresses",
                        )
                        for v in values:
                            expected_path = (
                                coin["derivation"][0]["path"].rsplit("/", 1)[0]
                                + "/"
                                + str(v[10])
                                + ("'" if cid in ["nano", "nimiq"] else "")
                            )
                            assert v[5] == expected_path and not v[4], (
                                case,
                                cid,
                                "path/xpub mismatch",
                            )
                    for key, value in standard.items():
                        assert (value != main[key]) == with_phrase, (
                            case,
                            cid,
                            "passphrase mode not separated as expected",
                        )
                    if with_phrase:
                        previous = [v for v in by_case[f"{count}-without"][cid] if v[9] == 0]
                        assert previous == [v for v in values if v[9] == 0], (
                            case,
                            cid,
                            "standard changed",
                        )
                by_case[case] = groups
                report["cases"].append(
                    dict(
                        id=case,
                        words=count,
                        passphrase=with_phrase,
                        registry_entries=len(registry),
                        derived_coins=len(groups),
                        not_derived=len(registry) - len(groups),
                        account_rows=len(rows),
                        keystores_verified=True,
                        original_parity=True,
                        original_parity_entries=len(reference["coins"]),
                        additional_reference_entries=len(extra["coins"]),
                        sha256=digest(rows),
                        main_and_standard=(
                            "different"
                            if with_phrase
                            else "same wallet; different pre-generated account counts"
                        ),
                    )
                )
        # No keystores, seeds, user input or secret records survive the fixture directory.
    for coin in registry:
        cid = coin["id"]
        entry = dict(
            id=cid,
            name=coin["name"],
            symbol=coin["symbol"],
            curve=coin["curve"],
            derivation_reference=(
                "Emurgo CSL 13.2.1 / CIP-3 Icarus"
                if cid == "cardano"
                else (
                    "Wallet Core 4.4.2"
                    if cid in added_ids
                    else "Unmodified original js/Utils.js / Wallet Core 4.4.2"
                )
            ),
            path_tested=coin["derivation"][0]["path"],
            other_registered_paths_not_tested=[p["path"] for p in coin["derivation"][1:]],
            original_signer_route=routes.get(cid) or routes.get(coin["blockchain"].lower()),
            original_signer_execution_tested=False,
            native_transaction_signing=False,
            esp32_derivation=False,
            combinations={},
        )
        for case, groups in by_case.items():
            entry["combinations"][case] = dict(
                status="pass" if cid in groups else "not_derived", rows=len(groups.get(cid, []))
            )
        if cid == "cardano":
            entry["passphrase_handling"] = (
                "CIP-3 Icarus UTF-8 passphrase applied via Wallet Core-linked Cardano primitives; upstream 4.4.2 HDWallet ignores it for this coin"
            )
            entry["recovery_scheme"] = "Icarus, not Ledger or Icarus-Trezor 24-word variant"
        report["coins"].append(entry)
    report["summary"] = dict(
        registry_entries=len(registry),
        pass_all_six=[
            c["id"]
            for c in report["coins"]
            if all(x["status"] == "pass" for x in c["combinations"].values())
        ],
        not_derived=[
            c["id"]
            for c in report["coins"]
            if any(x["status"] != "pass" for x in c["combinations"].values())
        ],
        original_missing_signer_routes=[
            c["id"] for c in report["coins"] if not c["original_signer_route"]
        ],
        observed_account_rows=sum(c["account_rows"] for c in report["cases"]),
    )
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", required=True)
    parser.add_argument(
        "--oracle", type=pathlib.Path, default=ROOT / "esp32/tests/coin_support_vectors.json"
    )
    parser.add_argument(
        "--additional-oracle",
        type=pathlib.Path,
        default=ROOT / "esp32/tests/additional_coin_vectors.json",
    )
    parser.add_argument("--report", type=pathlib.Path, required=True)
    args = parser.parse_args()
    report = audit(
        args.binary,
        json.loads(args.oracle.read_text()),
        json.loads(args.additional_oracle.read_text()),
    )
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(
        f"Six-combination audit passed: {len(report['summary']['pass_all_six'])}/{len(report['coins'])} coins derived in every case; {report['summary']['observed_account_rows']} rows match references (158 original + 5 added entries)."
    )
    print("Not derived: " + ", ".join(report["summary"]["not_derived"]))
    print("Native transaction signing and ESP32 crypto backend remain unimplemented.")
