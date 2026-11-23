#!/usr/bin/env python3
"""Record actual legacy coverage, without calling a registered coin implemented.

This is a development-time tool, never code executed by the ESP32.
"""

import argparse
import ast
import hashlib
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
DESTINATION = ROOT / "esp32" / "migration_inventory.json"


def inventory():
    lock = json.loads((ROOT / "js/package-lock.json").read_text())
    registry = json.loads((ROOT / "registry.json").read_text())
    manager = (ROOT / "js/SignerManager.js").read_text()
    routes = dict(
        re.findall(
            r"""^\s*['"]?([^'"\s:]+)['"]?\s*:\s*require\(['"]\./signer/([^'"]+)['"]\)""",
            manager,
            re.MULTILINE,
        )
    )
    worker = (ROOT / "js/crypto_worker.js").read_text()
    exclude_match = re.search(r"const excludeCoins = (\[[^;]+\]);", worker)
    excluded = ast.literal_eval(exclude_match.group(1))
    coins = []
    for coin in registry:
        # Match original routing exactly; do not silently fix its aliases here.
        signer = routes.get(coin["id"]) or routes.get(coin["blockchain"].lower())
        methods = []
        if signer:
            source = (ROOT / "js/signer" / (signer + ".js")).read_text()
            methods = re.findall(r"^\s+(?:async\s+)?(sign\w+)\([^)]*\)\s*\{", source, re.MULTILINE)
        legacy_derived = coin["id"] not in excluded and coin["curve"] in ("secp256k1", "ed25519")
        native_status = (
            "verified_against_original"
            if legacy_derived
            else (
                "verified_against_emurgo_csl_13.2.1_icarus"
                if coin["id"] == "cardano"
                else (
                    "verified_against_wallet_core_4.4.2"
                    if coin["id"] in ("nano", "nimiq", "neo", "ontology")
                    else "pending"
                )
            )
        )
        coins.append(
            {
                "id": coin["id"],
                "coin_id": coin["coinId"],
                "blockchain": coin["blockchain"],
                "curve": coin["curve"],
                "derivation": coin["derivation"],
                "legacy_batch_derivation": legacy_derived,
                "macos_native_derivation": native_status,
                "legacy_signer": signer,
                "legacy_signer_methods": methods,
                "esp32_key_primitive": (
                    "pending_icarus_adapter"
                    if coin["id"] == "cardano"
                    else "available_four_curve_hd_backend_not_full_coin_validation"
                ),
                "esp32_derivation": (
                    "pending_icarus_adapter_and_account_backend"
                    if coin["id"] == "cardano"
                    else "pending_address_and_account_backend"
                ),
                "esp32_signing": "pending_wallet_core_and_native_signer_port",
            }
        )
    tree = ast.parse((ROOT / "core/api_handler.py").read_text())
    api_methods = set()
    for node in ast.walk(tree):
        if (
            isinstance(node, ast.Compare)
            and isinstance(node.left, ast.Name)
            and node.left.id == "method"
        ):
            for comparator in node.comparators:
                if isinstance(comparator, ast.Constant) and isinstance(comparator.value, str):
                    api_methods.add(comparator.value)
                elif isinstance(comparator, (ast.List, ast.Tuple)):
                    api_methods.update(
                        item.value for item in comparator.elts if isinstance(item, ast.Constant)
                    )
    source_paths = [
        "config.py",
        "registry.json",
        "words.txt",
        "main.py",
        "js/package-lock.json",
        "js/crypto_worker.js",
        "js/SignerManager.js",
        "js/Utils.js",
    ]
    for directory, suffix in [
        ("core", "*.py"),
        ("ui", "*.py"),
        ("hardware", "*.py"),
        ("js/signer", "*.js"),
    ]:
        source_paths += [str(p.relative_to(ROOT)) for p in sorted((ROOT / directory).glob(suffix))]
    return {
        "schema": 1,
        "status": "partial_port_esp32_offline_onboarding_encrypted_entropy_and_hd_keys",
        "esp32_crypto_backend": {
            "primitives": ["sha256", "pbkdf2_sha256", "pbkdf2_sha512", "aes_256_gcm", "ctr_drbg"],
            "bip39_word_counts": [12, 15, 18, 21, 24],
            "key_curves": ["secp256k1", "nist256p1", "ed25519", "ed25519_blake2b"],
            "secp256k1_public_serialization": ["xpub", "ypub", "zpub"],
            "source": "unmodified_wallet_core_4.4.2_trezor_crypto_pinned_sources",
            "seed_semantics": "wallet_core_4.4.2_utf8_bytes_no_normalization_reject_over_256_bytes",
            "hd_host_checks": 8077,
            "reference_wallets": 34,
            "reference_keys": 578,
            "physical_selftest": "passed_ES3N28P_BIP39_BIP32_and_four_curves_2026_11_04",
            "physical_selftest_ms": 708,
            "bip39_cache": False,
            "bip32_cache": False,
            "wallet_creation_ui": True,
            "address_builders": "initial_mainnet_receiving_addresses_13_networks",
            "wallet_storage": "AES256_GCM_encrypted_entropy_and_passphrase_in_internal_NVS",
            "signing": False,
            "cardano_icarus_adapter": False,
        },
        "esp32_initial_setup": {
            "splash": "supplied_artwork_224x224_RGB565_100352_flash_bytes",
            "word_counts": [12, 18, 24],
            "name": True,
            "optional_bip39_passphrase": True,
            "optional_pin_digits": [4, 12],
            "mandatory_transaction_phrase": True,
            "generated_backup": "words_and_optional_passphrase_on_device_only_before_commit",
            "pin_on_every_boot_if_configured": True,
            "mobile_access_policy": ["mobile_pin", "device_approval"],
            "mobile_authorization": "preference_saved_remote_account_access_still_unavailable",
            "public_account_builders": False,
            "private_key_export": False,
            "network_catalog_entries": 14,
            "offline_initial_public_key_networks": 13,
            "offline_mainnet_receiving_address_networks": 13,
            "monero_key_scheme": "pending",
            "token_identifiers": "pending_verification",
            "storage_record_bytes": 476,
            "wallet_partition": "wallet_nvs_0x810000_0x20000",
            "hardware_storage_test": "passed_isolated_public_fixture_committed_reopened_and_removed",
            "final_firmware_installation": "infos_wallet_installed_usb_hash_verified_2026_11_06",
        },
        "macos_backend": {
            "language": "C/C++",
            "wallet_core": "official_native_4.4.2",
            "generation": True,
            "import": True,
            "encrypted_keystore": True,
            "account_database": True,
            "ble": False,
            "signing": False,
            "parity_fixture_account_rows": 2692,
            "additional_fixture_account_rows": 550,
            "total_fixture_account_rows": 3242,
            "derived_registry_entries": 163,
            "cardano_passphrase": "CIP-3 Icarus via Wallet Core-linked primitives; default HDWallet API ignores passphrase",
            "mnemonic_passphrase_matrix": "12/18/24 words, with/without passphrase; 163 entries in all six cases",
        },
        "ui_flow_parity": {
            "reference": "ui/states_wallet.py + ui/components.py",
            "differential_observations": 286,
            "create_import": "verified_against_original_methods",
            "connections_and_sign_review": "controllers_tested_with_injected_services",
            "device_transport_and_signers": "esp32_nimble_public_rpc_integrated_signers_pending",
            "input_limit_bytes": 1024,
        },
        "ui_languages": ["en", "zh", "pt-BR", "fr", "it", "de", "es"],
        "public_wallet_sync": {
            "language": "C/C++",
            "methods": ["get_device_info", "get_accounts"],
            "modes": ["STANDARD", "HIDDEN"],
            "same_authenticated_encrypted_connection": True,
            "generation_pinned_per_connection": True,
            "macos_export": "read_only_existing_sqlite_tables_no_keystore_access",
            "macos_ble_adapter": "not_implemented",
            "ctest": "public_wallet_export",
            "app_fixture_wallet_core": "4.4.4",
            "hardware_fixture_wallet_core": "4.4.2",
            "app_networks": 16,
            "standard_fixture_rows": 43,
            "hidden_fixture_rows": 313,
            "esp32_transport": "nimble_gatt_integrated_physical_validation_pending",
            "esp32_accounts": "PUBLIC_DATA_UNAVAILABLE_until_internal_flash_wallet_backend",
            "esp32_pairing": "nimble_implemented_new_pairings_rejected_during_current_onboarding_scope",
            "esp32_signing": "not_implemented",
        },
        "wallet_core_version": lock["packages"]["node_modules/@trustwallet/wallet-core"]["version"],
        "target_soc": "esp32s3",
        "board_model": "ES3N28P",
        "lcd_controller": "ILI9341V",
        "board_profiles": {
            "ES3C28P": "touch_required",
            "ES3N28P": "explicit_no_touch_usb_validation",
        },
        "display_width": 240,
        "display_height": 320,
        "board_source": "https://www.lcdwiki.com/2.8inch_ESP32-S3_Display",
        "peripherals": {
            "wifi": "forbidden",
            "microsd": "forbidden",
            "bluetooth": "allowed",
            "internal_flash": "allowed",
            "touch": "absent_in_user_ES3N28P",
        },
        "touch_controller": None,
        "flash_bytes": 16 * 1024 * 1024,
        "psram_bytes": 8 * 1024 * 1024,
        "psram_mode": "OPI",
        "legacy_api_methods": sorted(api_methods),
        "source_sha256": {
            p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in sorted(source_paths)
        },
        "registry_coins": len(coins),
        "legacy_signer_classes": len(routes),
        "coins": coins,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    content = json.dumps(inventory(), ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if not DESTINATION.exists() or DESTINATION.read_text() != content:
            raise SystemExit("Migration inventory is stale; run esp32/tools/inventory.py")
        print("Inventory matches original source, registry and locked Wallet Core version.")
    else:
        DESTINATION.write_text(content, encoding="utf-8")
        print(DESTINATION)


if __name__ == "__main__":
    main()
