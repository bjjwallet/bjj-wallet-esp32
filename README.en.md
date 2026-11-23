[Português](README.md) · **English** · [Español](README.es.md) · [Français](README.fr.md) · [简体中文](README.zh.md) · [日本語](README.ja.md) · [Italiano](README.it.md) · [Deutsch](README.de.md)

<picture>
  <source media="(max-width: 640px)" srcset="docs/assets/hero-mobile.png">
  <img src="docs/assets/hero.png" alt="BJJ-Wallet — affordable hardware wallets, open-source code and collaboration" width="100%">
</picture>

# BJJ-Cold-Wallet

<a id="testing"></a>

## 🧪 Wallet in testing

**BJJ-Cold-Wallet is in testing and development**. We need the community’s help to validate app and hardware flows, find bugs and improve the experience.

**Help us test!** Try setup, Bluetooth connections, synchronization, transaction review and signing on the cold wallet. Share issues and suggestions through the [BJJ-Wallet organization on GitHub](https://github.com/bjjwallet), including your phone model, app and firmware versions, and steps to reproduce.

Prefer test networks and wallets created for testing. Never share your seed, private keys, PIN or Transaction Phrase in reports, screenshots or logs.

**A cold wallet by developers, for developers. Open to everyone.**

An ESP32-S3 hardware wallet with a touchscreen, on-device transfer approval and **Bluetooth Low Energy** communication with an Android and iOS companion app.

<p align="center">
  <img src="docs/assets/badge-open-source.svg" alt="Open source" height="28">
  <img src="docs/assets/badge-affordable.svg" alt="Affordable hardware" height="28">
  <img src="docs/assets/badge-community.svg" alt="Built together" height="28">
</p>

## 👋 Who we are

We are **BJJ-Wallet**, a group of developers working to make cold wallets more accessible through affordable hardware, open-source software and shared knowledge.

**BJJ-Cold-Wallet** puts that idea into practice: a functional wallet that the community can study, build and improve. We want everyone to understand what runs on the device protecting their coins.

Our principles are accessibility, transparency, security engineering and collaboration. Developers, makers, designers and testers are welcome: read the code, question decisions, report problems and help improve the project.

Visit the [BJJ-Wallet organization on GitHub](https://github.com/bjjwallet).

[💚 Support BJJ-Cold-Wallet](#support-project)

## 📱 Mobile app

The **BJJ-Cold-Wallet** companion app runs on Android and iOS. It connects to the wallet over Bluetooth, synchronizes the data needed to track accounts, queries balances, prepares transfers and broadcasts transactions signed by the hardware.

The transfer flow is:

1. The user selects the network, asset, recipient and amount in the app.
2. The app requests a signature from the cold wallet over Bluetooth.
3. The cold wallet displays the transfer details and requires physical approval.
4. The hardware signs and returns the result to the app.
5. The app verifies the signature and submits the signed data through the corresponding API, RPC or network interface.

**The seed and private spending keys remain on the cold wallet. The app does not request or import the seed to operate the wallet.** The app processes access credentials; Monero watch-only mode may retain the private view key in the encrypted vault. That key can reveal transactions but cannot authorize spending.

The current catalog contains **23 networks and 86 assets: 23 native coins and 63 tokens**. It includes Bitcoin, Ethereum and EVM networks, Solana, Cardano, TRON, XRP Ledger, TON, Sui, Stellar, Litecoin, Dogecoin, Bitcoin Cash, Dash, eCash and Monero. The Monero flow available in the current firmware uses **stagenet**. The per-network and per-token matrix is in the app README.

The app uses **Kotlin Multiplatform and Compose Multiplatform**, with native Android and iOS integrations.

➡️ **[Explore the app: architecture, languages, libraries, networks and building](app/README.en.md)**

## 🔐 Hardware

BJJ-Cold-Wallet uses the **LCDWIKI ES3C28P board with capacitive touch**, based on the **ESP32-S3**, with **16 MB flash**, **8 MB PSRAM** and a **2.8-inch IPS display**. The panel has a native resolution of 240 × 320; the current UI runs in landscape at 320 × 240. Specifications: [manufacturer documentation](https://www.lcdwiki.com/2.8inch_ESP32-S3_Display).

The firmware uses **C/C++ with ESP-IDF**, an **LVGL** interface and a **Rust** library for Monero signing. Its cryptographic integration includes **TrezorCrypto** from Trust Wallet Core 4.4.2, with versions and source provenance recorded in the project.

The cold wallet controls phone access, presents the approval details and performs signing. In the distributed application, USB supplies power and Bluetooth handles app communication. Wi-Fi and microSD are blocked by the firmware.

### 🛒 Board and enclosure

- **[Buy the board — suggested AliExpress listing](https://pt.aliexpress.com/item/1005010470966979.html)**. Check the **ES3C28P touchscreen** variant; ES3N28P is the version without touch.
- **[3D-printable enclosure — suggested Printables reference](https://www.printables.com/model/1188149-enclosure-for-esp32-s3-touch-lcd-2)**. Check dimensions, mounting and connector access for your board before printing; this model’s mechanical compatibility has not yet been validated by the project.

➡️ **[Explore the hardware: board, TrezorCrypto, libraries, firmware and installation](hardware/README.en.md)**

The project is under development. See the [signature test results and limitations](app/docs/all-assets-signing-2026-11-21.md), [code standards](docs/CODE_STYLE.md) and the [app](app/LICENSE) and [hardware](hardware/LICENSE) licenses.

<a id="support-project"></a>

## 💚 Help keep the idea alive

We want to make cold wallets accessible through affordable hardware and open-source code. Your contribution helps keep BJJ-Cold-Wallet in development and this idea alive.

**Right now, we need help paying app store enrollment fees to publish the Android and iOS apps.**

### Choose the network before donating

| USDT · Ethereum | USDT0 · Polygon |
|:---:|:---:|
| <img src="docs/donate/usdt-ethereum.png" alt="Address QR code for USDT · Ethereum" width="220"> | <img src="docs/donate/usdt0-polygon.png" alt="Address QR code for USDT0 · Polygon" width="220"> |
| Network: **Ethereum** | Network: **Polygon** |

Both QR codes contain only the same address. Select the indicated token and network in your wallet before sending.

**Donation address**

On GitHub, use the copy button in the corner of the block below. The address is the same on both networks.

```text
0x142f6f4230934c5555772e4de1E8E39E1105CB2b
```

Thank you for supporting open development.
