[Português](README.md) · **English** · [Español](README.es.md) · [Français](README.fr.md) · [简体中文](README.zh.md) · [日本語](README.ja.md) · [Italiano](README.it.md) · [Deutsch](README.de.md)

# 🔐 BJJ-Cold-Wallet — hardware and firmware

[← Product overview](../README.en.md) · [Mobile app](../app/README.en.md)

**BJJ-Cold-Wallet** is an open-source ESP32-S3 hardware wallet. It generates or imports the seed on the device, retains private spending keys, controls phone access and requires review and physical approval before signing transfers.

The mobile app communicates with the cold wallet over **Bluetooth Low Energy**. The app performs internet queries and blockchain submission. The seed and private spending keys are not synchronized. Authorized Monero watch-only export may include the private view key, which cannot authorize spending.

## Board used

The current distribution target is the **LCDWIKI ES3C28P with capacitive touch**.

| Component | Configuration |
|---|---|
| Processor | ESP32-S3, dual-core Xtensa LX7, up to 240 MHz |
| Flash | 16 MB |
| PSRAM | 8 MB OPI |
| Display | 2.8″ IPS, ILI9341V controller, SPI interface |
| Native resolution | 240 × 320 |
| Current UI | Landscape, 320 × 240, with touch-coordinate transformation |
| Touch | Capacitive FT6336G, I²C interface |
| App communication | Bluetooth Low Energy |
| Power and recovery | USB-C; physical BOOT/RESET mode for flashing |

Source: [LCDWIKI manufacturer specifications](https://www.lcdwiki.com/2.8inch_ESP32-S3_Display). Driver configuration is in [esp32/hardware](esp32/hardware) and [esp32/idf_components/airlock_board](esp32/idf_components/airlock_board). [ES3C28P.md](esp32/hardware/ES3C28P.md) preserves pinouts and historical variant records; this README describes the current orientation and USB policy.

**ES3N28P is the variant without touch.** Its validation builds have separate settings; the distributed ES3C28P package requires touch.

### Purchase and enclosure

- **[Board on AliExpress — suggested project listing](https://pt.aliexpress.com/item/1005010470966979.html)**. Select the ES3C28P touchscreen variant and check the listing specifications.
- **[3D-printable case on Printables — suggested reference](https://www.printables.com/model/1188149-enclosure-for-esp32-s3-touch-lcd-2)**. Mechanical compatibility with the ES3C28P revision used has not been validated; check dimensions, mounting and cutouts.

## Languages, SDKs and libraries

| Technology | Version/source | Purpose |
|---|---|---|
| C / C++ | Portable core compatible with C++17 | Drivers, UI, rules, storage and signers |
| ESP-IDF | 5.4.2 | Espressif SDK, board initialization and embedded services |
| FreeRTOS | Provided by ESP-IDF | Tasks, synchronization and service execution |
| NimBLE | Integrated into ESP-IDF | Bluetooth LE, GATT and pairing |
| LVGL | 9.2.2 | Visual components and touch interaction |
| esp_lcd_ili9341 | 2.0.1 | ILI9341V panel driver |
| mbedTLS | 3.6.3, from ESP-IDF 5.4.2 | Cryptographic primitives and storage protection |
| TrezorCrypto | Pinned subset from Wallet Core 4.4.2 | Key derivation, curves, hashes and signatures |
| cJSON | 1.7.18, vendored | Local protocol JSON contracts |
| Rust | Edition 2021 crate; rust-version 1.89 | Monero signer; Xtensa build with the esp-rs toolchain |
| Monero Oxide / monero-wallet | Git revision pinned in Cargo.toml | RingCT construction and signing, CLSAG and Bulletproof+ |
| curve25519-dalek | 4.1.3 | Curve operations in the Monero signer |
| rand_core / rand_chacha | 0.6.4 / 0.3.1 | Cryptographic randomness interface and generation in the signer |
| zeroize | 1.8, resolved through Cargo.lock | Sensitive-value zeroization in Rust |
| Monero C routines | Tag 0.18.5.4 | AES, Groestl, JH and Skein for Monero integration |
| CMake / Ninja / Python | Development tools | Builds, catalog generation, validation and installer |
| SDL2 / OpenSSL 3 / SQLite | Development environment | Desktop simulator and tests |

Managed ESP-IDF dependencies are in [idf_component.yml](esp32/firmware/main/idf_component.yml). Rust dependencies are in [Cargo.toml](esp32/monero_signer/Cargo.toml) and [Cargo.lock](esp32/monero_signer/Cargo.lock).

## Trezor integration: TrezorCrypto

The Trezor technology used is the **TrezorCrypto cryptographic library**, incorporated through the subset included in **Trust Wallet Core 4.4.2**. Its implementation is in [esp32/vendor/trezor-crypto-4.4.2](esp32/vendor/trezor-crypto-4.4.2).

It provides primitives used by the firmware, including:

- BIP-39 and the English mnemonic wordlist.
- Hierarchical derivation and secp256k1, NIST P-256 and Ed25519 operations, as integrated.
- ECDSA, Ed25519, HMAC, PBKDF2, hashes and encodings required by the protocols.
- Cardano routines available in the pinned version.

The exact source, upstream archive hash and individual file hashes are recorded in [provenance.json](esp32/vendor/trezor-crypto-4.4.2/provenance.json). This source subset is retained without modification; its build adapter is [trezor_crypto.cmake](esp32/cmake/trezor_crypto.cmake).

The project builds with `USE_BIP39_CACHE=0` and `USE_BIP32_CACHE=0`, disabling those caches. The original POSIX randomness adapter is replaced by [trezor_random.cpp](esp32/core/trezor_random.cpp), connected to the platform randomness source. Source failures are not replaced with predictable numbers.

**BJJ-Cold-Wallet** reconstructs and validates payments, applies access rules and presents physical review; TrezorCrypto supplies the cryptographic primitives used by those operations. This dependency does not represent the full SDK, firmware or certification of a Trezor device.

To verify the pinned files, run from this folder:

```sh
python3 esp32/tools/check_trezor_sources.py
```

The native macOS Wallet Core framework and JavaScript/WASM package serve separate roles in references, the simulator and legacy implementation. The macOS binary is not a library for the ESP32. See [vendor/README.md](esp32/vendor/README.md).

## Architecture

```text
hardware/
├── esp32/                  # Current firmware
│   ├── core/               # Wallets, validation and signers
│   ├── ui/                 # Screens, approval, languages, icons and fonts
│   ├── hardware/           # Physical component protocols and abstractions
│   ├── idf_components/     # Service and driver integration with ESP-IDF
│   ├── firmware/           # Main board build project
│   ├── monero_signer/      # Rust Monero signing library
│   ├── vendor/             # Third-party dependencies and licenses
│   ├── cmake/              # Auxiliary build configuration
│   ├── simulator/          # Desktop simulator
│   ├── tests/              # Tests and public vectors
│   ├── tools/              # Generation, verification, flashing and packaging
│   └── docs/               # Reports and technical documentation
├── instaladores/           # Current release ZIP
├── historico/instaladores/ # Previous releases
├── core/                   # Original Python implementation services
├── hardware/               # Original implementation drivers
├── ui/                     # Original Python/MicroPython interface
└── js/                     # Original JavaScript / Wallet Core signers
```

The `build` and `build-*` folders contain build outputs. `device-updates`, `device-resets` and `device-backups` retain records of previous board operations. `simulator-wallets` belongs to the simulator environment.

The current ESP32 core uses C/C++ and Rust. Python/MicroPython and JavaScript folders preserve the original implementation and compatibility references; their runtime is not the signing path of the distributed C/C++ firmware.

## Approval, keys and phone access

The device controls pairing and authorization for each connection. Allowed and blocked phones are listed in the hardware settings. Rejection, blocking, disconnection or expiration must not produce an approved transfer signature.

Before signing, the firmware validates the plan, identifies the wallet and displays the asset, amount, recipient and fees. Any change returning to the wallet is shown separately from the recipient. The hardware uses the seed and private spending keys; the app receives the signature and verifies it before broadcasting.

In distributed firmware:

- `AIRLOCK_USB_POWER_ONLY=ON`: USB supplies power only while the application runs.
- `CONFIG_ESP_CONSOLE_NONE=y`: console disabled.
- `CONFIG_USJ_ENABLE_USB_SERIAL_JTAG` disabled.
- `AIRLOCK_BOARD_AGENT_CONTROL=OFF`: no remote USB agent control.
- Wi-Fi and SD/microSD blocked by firmware policy.
- Physical BOOT/RESET recovery preserved; normal updates do not modify eFuses.

USB data disconnects before wallets are opened or Bluetooth starts. ROM flashing mode remains physically accessible; application policy does not permanently disconnect the interfaces electrically.

## Networks, coins and signers

The catalog shared with the app contains **23 networks and 86 assets**, including native coins and the 63 registered tokens. The [complete network/token matrix](../app/README.en.md#supported-networks) is in the app README.

| Family | Implementation and integration |
|---|---|
| Bitcoin, Litecoin, Dogecoin, Dash, Bitcoin Cash e eCash | Input/output validation, UTXO signing and per-network hash rules |
| Ethereum and EVM networks | Transactions bound to chain ID; tokens bound to registered contracts |
| Solana | SOL and registered SPL/Token-2022 tokens; verified messages and accounts |
| Cardano | ADA and the five registered tokens, with ADA fees and change |
| TRON | TRX and registered TRC-20 contracts |
| XRP Ledger | XRP and issued assets identified by currency/issuer |
| Stellar | XLM and assets identified by code/issuer |
| Sui | SUI and USDC, using explicit types when constructing payments |
| TON | V4R2 wallet and USDT, with derived and verified jetton-wallet identity |
| Monero | wallet2 archive, RingCT signing with CLSAG/Bulletproof+; stagenet in the current flow |

Firmware address and token catalogs are generated from the app’s `CoinCatalog.kt` and `Stablecoins.kt`. Checks reject divergent catalogs. To generate or check them, run from the repository root:

```sh
python3 hardware/esp32/tools/generate_address_catalog.py --check
python3 hardware/esp32/tools/generate_signing_catalog.py --check
```

The [signature report](../app/docs/all-assets-signing-2026-11-21.md) explains local coverage, Cardano UTXO-selection limitations and the difference between a valid signature and network acceptance.

## Monero signing library

The Rust signer is in [esp32/monero_signer](esp32/monero_signer). The `monero-wallet` dependency points to revision `a6b46701fb9b331006d7f2f2278bf51f123b1447` of [Monero Oxide](https://github.com/monero-oxide/monero-oxide), and `Cargo.lock` pins dependency resolution.

The Xtensa build produces `libairlock_monero_signer.a`, linked into the firmware through `AIRLOCK_MONERO_SIGNER_ARCHIVE`. The integration uses platform allocators, memory zeroization and physical approval. Building this complete module requires the Xtensa/ESP32-S3 Rust toolchain; follow [BUILD.md](esp32/monero_signer/BUILD.md).

## Build the firmware

Requirements: **ESP-IDF 5.4.2**, ESP32-S3 toolchain, CMake, Ninja, Python and the Rust Monero library already built for Xtensa. With the ESP-IDF environment loaded and the library at the path below, run from this folder:

```sh
idf.py -C esp32/firmware -B "$PWD/esp32/build-release" \
  -DAIRLOCK_USB_POWER_ONLY=ON \
  -DAIRLOCK_BOARD_AGENT_CONTROL=OFF \
  -DAIRLOCK_BOARD_NO_TOUCH=OFF \
  -DAIRLOCK_SETUP_SELFTEST=OFF \
  -DAIRLOCK_MONERO_STAGENET_SYNC=ON \
  -DAIRLOCK_MONERO_SIGNER_ARCHIVE="$PWD/esp32/build-monero-signer-release/xtensa-esp32s3-none-elf/release/libairlock_monero_signer.a" \
  build
```

The example assumes the Rust toolchain produced the library in the specified directory. The `sdkconfig` files must keep the console and USB Serial/JTAG disabled; CMake validates distribution policies. The main project is in [esp32/firmware](esp32/firmware).

## Local tests and simulator

Portable tests use the same signers and check plans, signatures, changed recipients/amounts, permissions, pairing and UI states. Independent Stellar, TRON, XRPL, Sui and TON SDKs are development dependencies pinned in [tests/protocol-reference/package.json](esp32/tests/protocol-reference/package.json).

With development dependencies available, run from this folder:

```sh
cmake -S esp32 -B esp32/build-host \
  -DAIRLOCK_MBEDTLS_DIR="$IDF_PATH/components/mbedtls/mbedtls" \
  -DAIRLOCK_LVGL_DIR="$PWD/esp32/firmware/managed_components/lvgl__lvgl"
cmake --build esp32/build-host --parallel
ctest --test-dir esp32/build-host --output-on-failure

cargo test --locked --manifest-path esp32/monero_signer/Cargo.toml
```

The LVGL path assumes ESP-IDF managed dependencies have already been resolved. For the interactive desktop UI, see [simulator build instructions](esp32/README.md#compilar-e-testar-o-simulador). Tests use public/synthetic data and do not demonstrate a real blockchain transfer.

## Installable firmware

The included package for version `2026.11.21-allassets1` is:

**[BJJ-Cold-Wallet-ES3C28P-2026-11-21-allassets1.zip](instaladores/BJJ-Cold-Wallet-ES3C28P-2026-11-21-allassets1.zip)**

Previous releases are in the [installer history](historico/instaladores/). The `instaladores` folder contains only the current ZIP.

Inside the package, `instalar.py` flashes components at the correct addresses. The `firmware` folder contains `airlock_board_check.bin`, bootloader, partition table, SHA-256 hashes and release policy. Normal updates should preserve wallets and pairing; resetting/erasing is a separate operation.

Generating a package does not identify the version installed on any particular board. Flashing requires physically entering BOOT mode.


<a id="installation"></a>

### Installation steps

1. Download and extract the ZIP above. Keep all files together; `instalar.py` is the installer and `Instalar.command` is the macOS shortcut. Use Python 3 and a USB-C data cable.
2. Enter BOOT mode: with USB connected, hold BOOT, press and release RESET (RST/EN), then release BOOT. If RESET is inaccessible, hold BOOT while connecting USB and release it after detection.
3. Open a terminal in the extracted folder and prepare dependencies for your system:

**macOS and Linux**

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
```

**Windows (PowerShell)**

```powershell
py -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

4. With the board in BOOT mode, run your system’s update command:

**macOS and Linux**

```sh
.venv/bin/python instalar.py
```

**Windows (PowerShell)**

```powershell
.\.venv\Scripts\python.exe instalar.py
```

For multiple boards, add `--porta` and the correct port, such as `/dev/cu.usbmodem14101` on macOS, `/dev/ttyACM0` on Linux or `COM3` on Windows.

For a new board with an empty partition table, add `--placa-nova`. Do not use this option on a configured wallet. To check the package and board partition table without flashing, add `--verificar` to the command for your chosen mode.

5. Wait for completion and restart. Normal updates write only the application and check partition-table compatibility, preserving wallets, nickname, PIN and pairing. Once the wallet starts, the USB port disappears: USB supplies power and the app uses Bluetooth. If the board remains in the bootloader, release BOOT and press RESET once.

The ZIP also includes a `README.md` with detailed instructions and troubleshooting.

## Documentation, maintenance and licenses

- [ESP32 port details and history](esp32/README.md): includes previous revision records.
- [TrezorCrypto provenance](esp32/vendor/trezor-crypto-4.4.2/provenance.json) and [upstream licenses](esp32/vendor/trezor-crypto-4.4.2/crypto/LICENSE).
- [Peripheral policy](esp32/idf_components/airlock_policy/README.md).
- [Comments and formatting standards](../docs/CODE_STYLE.md).
- [Distributed firmware rules](AGENTS.md).

Code reused from AirLock retains its original authorship and [GPL-3.0 license](LICENSE). Third-party libraries keep their own licenses and notices. When updating a cryptographic library or registered contract, record its version/source, update the reference and run the corresponding checks.
