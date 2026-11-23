#!/usr/bin/env python3
"""Download catalog brand icons as deduplicated 32px PNGs, never firmware data.

Requires Pillow. Originals stay in memory; metadata never enables an asset.
"""

import concurrent.futures
import hashlib
import io
import json
import pathlib
import re
import urllib.request
from PIL import Image, ImageOps

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEST = ROOT / "ui/assets/currency_icons"
RAW = "https://raw.githubusercontent.com/trustwallet/assets/master/"
ALIASES = {
    "bitcoindiamond": "btcdiamond",
    "fetchai": "fetch",
    "bsc": "smartchain",
    "tbinance": "binance",
    "Nebl": "neblio",
    "kaia": "klaytn",
    "viction": "tomochain",
    "zeneon": "zen",
    "zklinknova": "zklink",
}
TOKEN_ADDRESSES = {
    "USDC": ("ethereum", "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"),
    "USDT": ("ethereum", "0xdAC17F958D2ee523a2206206994597C13D831ec7"),
    "LINK": ("ethereum", "0x514910771AF9Ca656af840dff83E8264EcF986CA"),
    "USDS": ("ethereum", "0xdC035D45d973E3EC169d2276DDab16f1e407384F"),
    "USDe": ("ethereum", "0x4c9EDD5852cd905f086C759E8383e09bff1E68B3"),
    "PYUSD": ("ethereum", "0x6c3ea9036406852006290770BEdFcAbA0e23A0e8"),
    "EURC": ("ethereum", "0x1aBaEA1f7C830bD89Acc67eC4af516284b1bC33c"),
    "RLUSD": ("ethereum", "0x8292Bb45bf1Ee4d140127049757C2E0fF06317eD"),
    "USDG": ("ethereum", "0xe343167631d89B6Ffc58B88d6b7fB0228795491D"),
    "BRZ": ("ethereum", "0x420412E765BFa6d85aaaC94b4f7b708C89be2e2B"),
    "JUP": ("solana", "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN"),
    "SKR": ("solana", "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"),
}


def get(url):
    request = urllib.request.Request(url, headers={"User-Agent": "Airlock-Icon-Catalog/1"})
    with urllib.request.urlopen(request, timeout=25) as response:
        data = response.read(4 * 1024 * 1024 + 1)
    if len(data) > 4 * 1024 * 1024:
        raise ValueError("Original exceeds transient download limit")
    return data


def save_icon(url):
    original = get(url)
    with Image.open(io.BytesIO(original)) as source:
        source.load()
        image = ImageOps.contain(source.convert("RGBA"), (32, 32), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (32, 32))
    canvas.alpha_composite(image, ((32 - image.width) // 2, (32 - image.height) // 2))
    output = io.BytesIO()
    canvas.quantize(colors=64, method=Image.Quantize.FASTOCTREE).save(
        output, format="PNG", optimize=True
    )
    data = output.getvalue()
    if len(data) > 2048:
        raise ValueError("Icon exceeds 2 KiB budget")
    digest = hashlib.sha256(data).hexdigest()
    name = "images/" + digest[:20] + ".png"
    (DEST / name).write_bytes(data)
    return {
        "path": name,
        "bytes": len(data),
        "sha256": digest,
        "source_url": url,
        "source_sha256": hashlib.sha256(original).hexdigest(),
        "width": 32,
        "height": 32,
    }


def main():
    (DEST / "images").mkdir(parents=True, exist_ok=True)
    registry = json.loads((ROOT.parent / "registry.json").read_text())
    header = (ROOT / "core/network_accounts.hpp").read_text()
    board = re.findall(r'\{Network::(\w+), "([^"]+)", "([^"]+)"', header)
    tokens = set(
        re.findall(
            r'"([A-Za-z][A-Za-z0-9]*)"', header.split("network_accounts{{", 1)[1].split("}};", 1)[0]
        )
    )
    tokens -= {x[2] for x in board}
    tokens &= {
        "NATIVE",
        "BRLP",
        "BRZ",
        "USDC",
        "USDT",
        "SKR",
        "USDS",
        "PYUSD",
        "EURC",
        "LINK",
        "JUP",
        "MON",
        "USDe",
        "RLUSD",
        "ARB",
        "USDG",
        "BNB",
        "XRP",
    }
    plans = {}
    for item in registry:
        chain = ALIASES.get(item["id"], item["id"])
        plans[item["id"]] = {
            "name": item["name"],
            "symbol": item["symbol"],
            "urls": [RAW + f"blockchains/{chain}/info/logo.png"],
            "scope": "original_registry",
        }
    special = {
        "Bitcoin": "bitcoin",
        "Dogecoin": "doge",
        "Monero": "monero",
        "Solana": "solana",
        "Ethereum": "ethereum",
        "Stellar": "stellar",
        "Base": "base",
        "Ripple": "ripple",
        "HyperEvm": "hyperevm",
        "Arbitrum": "arbitrum",
        "Bnb": "smartchain",
        "Polygon": "polygon",
        "Tron": "tron",
        "Robinhood": "robinhoodchain",
    }
    for enum, name, symbol in board:
        chain = special[enum]
        plans.setdefault(
            chain,
            {"name": name, "symbol": symbol, "urls": [RAW + f"blockchains/{chain}/info/logo.png"]},
        )["board_enum"] = enum
    plans["monero"]["urls"] = [
        "https://raw.githubusercontent.com/spothq/cryptocurrency-icons/master/128/color/xmr.png"
    ]
    fallback = {
        "tfuelevm": "https://coin-images.coingecko.com/coins/images/8029/large/1_0YusgngOrriVg4ZYx4wOFQ.png",
        "monacoin": "https://raw.githubusercontent.com/spothq/cryptocurrency-icons/master/128/color/mona.png",
        "wax": "https://raw.githubusercontent.com/spothq/cryptocurrency-icons/master/128/color/wax.png",
        "dydx": "https://coin-images.coingecko.com/coins/images/32594/large/dydx.png",
        "arbitrumnova": "https://icons.llamao.fi/icons/chains/rsz_arbitrum-nova.jpg",
        "lightlink": "https://icons.llamao.fi/icons/chains/rsz_lightlink.jpg",
        "polymesh": "https://coin-images.coingecko.com/coins/images/23496/large/Polymesh-symbol.png",
    }
    for key, url in fallback.items():
        plans[key]["urls"].append(url)
    existing_path = DEST / "manifest.json"
    existing = json.loads(existing_path.read_text()) if existing_path.exists() else {}
    if existing.get("schema") == 2:
        for group in ["networks", "coins"]:
            for entry in existing[group].values():
                if entry.get("icon"):
                    entry["icon"] = existing["icons"][entry["icon"]]
    results = {}

    def download(pair):
        key, plan = pair
        cached = existing.get("networks", {}).get(key, {})
        if (
            cached.get("status") == "downloaded"
            and cached["icon"]["source_url"] in plan["urls"]
            and (DEST / cached["icon"]["path"]).exists()
        ):
            return key, cached
        for url in plan["urls"]:
            try:
                return key, {k: v for k, v in plan.items() if k != "urls"} | {
                    "icon": save_icon(url),
                    "status": "downloaded",
                }
            except Exception as error:
                last = type(error).__name__ + ": " + str(error)
        return key, {k: v for k, v in plan.items() if k != "urls"} | {
            "status": "pending",
            "reason": last,
        }

    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        results.update(pool.map(download, plans.items()))
    coins = {}
    preferred = {
        "ETH": "ethereum",
        "BNB": "smartchain",
        "BTC": "bitcoin",
        "ZEN": "zen",
        "IOTX": "iotex",
        "KAVA": "kava",
        "ACA": "acala",
        "CRO": "cryptoorg",
        "BCH": "bitcoincash",
        "ZETA": "zetachain",
    }
    for key, network in results.items():
        symbol = network["symbol"]
        if symbol not in coins or preferred.get(symbol) == key:
            coins[symbol] = {
                "name": network["name"],
                "icon": network.get("icon"),
                "status": network["status"],
                "brand_from_network": key,
            }
    # A chain's native ETH reuses the Ethereum coin icon; the chain has its own.
    coins["BOBAETH"] = dict(coins["ETH"], name="Ether on Boba")
    token_results = {}
    for symbol, (chain, address) in TOKEN_ADDRESSES.items():
        cached = existing.get("coins", {}).get(symbol, {})
        if cached.get("status") == "downloaded" and (DEST / cached["icon"]["path"]).exists():
            token_results[symbol] = cached
            continue
        base = RAW + f"blockchains/{chain}/assets/{address}/"
        try:
            info = json.loads(get(base + "info.json"))
            if info["symbol"].casefold() != symbol.casefold():
                raise ValueError("Symbol mismatch")
            token_results[symbol] = {
                "name": info["name"],
                "icon": save_icon(base + "logo.png"),
                "status": "downloaded",
                "metadata_url": base + "info.json",
            }
        except Exception as error:
            token_results[symbol] = {"status": "pending", "reason": str(error)}
    token_results["BRLP"] = {
        "name": "BRLP",
        "icon": save_icon("https://brlp.money/favicon-96x96.png"),
        "status": "downloaded",
    }
    token_results["ARB"] = {
        "name": "Arbitrum",
        "icon": results["arbitrum"].get("icon"),
        "status": results["arbitrum"]["status"],
    }
    token_results["MON"] = {
        "name": "Monad",
        "icon": results["monad"].get("icon"),
        "status": results["monad"]["status"],
        "note": "Brand only; Solana token identity remains unverified",
    }
    for symbol, url in {
        "RLUSD": "https://coin-images.coingecko.com/coins/images/39651/large/RLUSD_200x200_%281%29.png",
        "SKR": "https://coin-images.coingecko.com/coins/images/70974/large/seeker-logo.jpg",
    }.items():
        if token_results[symbol]["status"] != "downloaded":
            token_results[symbol] = {
                "name": "Ripple USD" if symbol == "RLUSD" else "Seeker",
                "status": "downloaded",
                "icon": save_icon(url),
            }
    coins.update(token_results)
    used = {
        entry["icon"]["path"]
        for entry in list(results.values()) + list(coins.values())
        if entry.get("icon")
    }
    board_symbols = {x[2] for x in board} | tokens
    board_paths = {
        entry["icon"]["path"]
        for entry in results.values()
        if "board_enum" in entry and entry.get("icon")
    }
    board_paths |= {
        coins[s]["icon"]["path"] for s in board_symbols if s in coins and coins[s].get("icon")
    }
    total = sum((DEST / path).stat().st_size for path in used)
    board_bytes = sum((DEST / path).stat().st_size for path in board_paths)
    if total > 192 * 1024 or board_bytes > 48 * 1024:
        raise ValueError("Catalog exceeds storage budget")
    manifest = {
        "schema": 1,
        "downloaded_on": "2026-11-07",
        "firmware_embedded": False,
        "aliases": {"NATIVE": "SOL"},
        "note": "Icons identify brands only. No token contracts, support or signing are enabled by this catalog.",
        "networks": results,
        "coins": coins,
        "board_coin_symbols": sorted(board_symbols),
        "storage": {
            "unique_images": len(used),
            "all_png_bytes": total,
            "board_png_bytes": board_bytes,
            "board_unique_images": len(board_paths),
            "per_icon_limit_bytes": 2048,
            "single_decoded_rgba_bytes": 4096,
            "single_decoded_rgb565_bytes": 2048,
            "all_budget_bytes": 192 * 1024,
            "board_budget_bytes": 48 * 1024,
        },
    }
    # Keep provenance once per bitmap instead of repeating it for each alias.
    icons = {}
    for entry in list(results.values()) + list(coins.values()):
        if entry.get("icon"):
            icon = entry["icon"]
            key = icon["sha256"][:20]
            icons[key] = icon
            entry["icon"] = key
    manifest["schema"] = 2
    manifest["icons"] = icons
    runtime = {
        "schema": 1,
        "size": 32,
        "aliases": {"NATIVE": "SOL"},
        "networks": {
            key: {"name": entry["name"], "native": entry["symbol"], "icon": entry.get("icon")}
            for key, entry in results.items()
            if "board_enum" in entry
        },
        "coins": {
            symbol: coins[symbol].get("icon") for symbol in sorted(board_symbols) if symbol in coins
        },
        "images": {key: icon["path"] for key, icon in icons.items() if icon["path"] in board_paths},
    }
    runtime_data = json.dumps(runtime, ensure_ascii=False, separators=(",", ":")).encode() + b"\n"
    (DEST / "board_catalog.json").write_bytes(runtime_data)
    manifest["storage"]["board_index_bytes"] = len(runtime_data)
    manifest["storage"]["board_png_and_index_bytes"] = board_bytes + len(runtime_data)
    (DEST / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, separators=(",", ":")) + "\n"
    )
    print(json.dumps(manifest["storage"], indent=2))
    print(
        "Pending:",
        [
            (k, v.get("reason"))
            for k, v in list(results.items()) + list(coins.items())
            if v["status"] != "downloaded"
        ],
    )


if __name__ == "__main__":
    main()
