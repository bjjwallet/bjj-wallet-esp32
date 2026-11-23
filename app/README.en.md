[Português](README.md) · **English** · [Español](README.es.md) · [Français](README.fr.md) · [简体中文](README.zh.md) · [日本語](README.ja.md) · [Italiano](README.it.md) · [Deutsch](README.de.md)

# 📱 BJJ-Cold-Wallet mobile app

[← Product overview](../README.en.md) · [Hardware and firmware](../hardware/README.en.md)

**BJJ-App-Wallet** is the Android and iOS companion app for **BJJ-Cold-Wallet**. It provides the account and transfer interface, Bluetooth communication with the hardware wallet, network queries and submission of signed transactions.

The app is part of the evolution of AirLock. The current interface uses Kotlin/Compose Multiplatform; the internal names `AirLockShared`, `AirLock.xcodeproj` and namespace `pub.airlock` remain in the code.

## Responsibilities and security flow

The app synchronizes accounts and addresses, manages multiple saved wallets, queries balances, scans recipient QR codes and prepares transfer data. Spending signatures are produced on the cold wallet after review and physical approval.

```text
App prepares the payment
        ↓ Bluetooth / local RPC
Cold wallet checks the data and requests physical approval
        ↓ Signature produced on hardware
App verifies the signature
        ↓ Network API / RPC
Transaction broadcast
```

**The app does not import or store the seed or private spending keys.** It does need to process sensitive data: PINs, access settings and vault information. Monero watch-only mode may retain a **private view key**, which allows tracking transactions but cannot sign spending transactions. It stays in the encrypted vault and is not sent to the remote node.

The app PIN protects local unlocking. The cold-wallet access PIN authenticates the device connection and is not persisted by the app. The Transaction Phrase is a separate protection; the new signing routes request it on the hardware. See [transfer-signing-flow.md](docs/transfer-signing-flow.md) for each flow.

## Languages, platforms and tools

| Technology | Project version/configuration | Purpose |
|---|---|---|
| Kotlin | 2.3.21 | Rules, networking, Bluetooth and shared UI |
| Kotlin Multiplatform | Kotlin plugin 2.3.21 | Shared Android and iOS code |
| Compose Multiplatform | 1.10.0 | Declarative UI and shared resources |
| Material 3 | 1.9.0 | Visual components |
| Swift / SwiftUI | Xcode SDK | App entry point and native Apple integrations |
| Android Gradle Plugin | 8.11.2 | Android builds |
| Android SDK | compile/target 36; minimum 29 | Android 10 or later |
| iOS | Deployment target 16.0 | iPhone/iPad; built with Xcode |
| Java | JVM 17 | Android build tools |
| Gradle Wrapper | Included in the project | Reproducible build tasks |

Declared versions are in [libs.versions.toml](gradle/libs.versions.toml), [shared/build.gradle.kts](shared/build.gradle.kts) and [androidApp/build.gradle.kts](androidApp/build.gradle.kts).

## Libraries and SDKs

| Library / SDK | Version | Purpose |
|---|---|---|
| Kotlin Coroutines | 1.10.2 | Asynchronous operations, Flow and StateFlow |
| Kotlinx Serialization | 1.9.0 | JSON serialization for models and contracts |
| Ktor Client | 3.4.3 | HTTP, APIs/RPCs; OkHttp engine on Android and Darwin on iOS |
| Kable | 0.41.0 | Bluetooth Low Energy connections |
| Koin | 4.1.0 | Dependency injection |
| Lifecycle / ViewModel | 2.9.6 | Screen state and lifecycle |
| bitcoin-kmp, ACINQ | 0.32.0 | Bitcoin/UTXO structures, hashes, addresses and signature verification |
| secp256k1-kmp JNI | 0.24.0 | secp256k1 backend on Android and in JVM tests |
| KotlinCrypto SHA-3 | 0.8.0 | Keccak and protocol hashes |
| Kotlin Bignum | 0.3.10 | Arbitrary-precision arithmetic support |
| Tink Android | 1.10.0 | Cryptographic facilities for the Android integration |
| Coil / Coil SVG | 3.3.0 | Image and icon loading |
| Material Icons | 1.7.3 | Interface icons |
| Activity Compose | 1.10.1 | Compose integration with the Android Activity |
| ZXing Core / Embedded | 3.5.3 / 4.3.0 | QR scanning on Android |
| Android Keystore / BiometricPrompt | SDK Android | Local key protection and native authentication |
| CryptoKit / Keychain / LocalAuthentication | SDK Apple | Encrypted vault and iOS authentication |
| AVFoundation | SDK Apple | QR capture and scanning on iOS |
| Monero JS/WASM runtime | Bundled as local resources | Watch-only scanner, using WebView/WKWebView as its runtime |

The Monero runtime and its licenses are in [composeResources/files/monero](shared/src/commonMain/composeResources/files/monero). It tracks the wallet without receiving the seed or private spending key.

## Project structure

```text
app/
├── androidApp/        # Android entry point, permissions and QR scanner
├── iosApp/            # Xcode project, SwiftUI, Keychain and UI tests
├── shared/
│   └── src/
│       ├── commonMain/       # Shared functionality
│       ├── androidMain/      # Native Android adapters
│       ├── iosMain/          # Native iOS adapters
│       ├── commonTest/       # Shared tests
│       ├── androidUnitTest/  # JVM tests and cryptographic checks
│       └── test/             # Public reference vectors
├── gradle/           # Wrapper and version catalog
├── scripts/          # Checks and resource generation
├── docs/             # Architecture, contracts and test results
└── hardware-patches/ # Hardware integration adaptation records
```

Inside `shared/src/commonMain/kotlin/pub/airlock`:

| Folder | Responsibility |
|---|---|
| `domain` | Models, catalogs, interfaces, rules and exact monetary values |
| `data` | Vault, repositories, network integrations and cold-wallet adapter |
| `ble` | Bluetooth transport and local protocol messages |
| `presentation` | ViewModel, screen state and flow coordination |
| `ui` | Compose screens, components, themes and error presentation |
| `di` | App dependency wiring |

<a id="supported-networks"></a>

## Supported networks and coins

The current catalog contains **23 networks, 23 native coins and 63 tokens**, totaling **86 assets**. The same symbol on two networks represents separate integrations.

| Network | Native coin | Registered tokens |
|---|---|---|
| Bitcoin | BTC | — |
| Ethereum | ETH | BRZ, BNB, USDT, USDC, USDS, USDe, RLUSD, EURC |
| Tron | TRX | USDC, USDT |
| BNB Smart Chain | BNB | BRZ, USDT, LINK, USDC, USDe, XRP |
| XRP Ledger | XRP | RLUSD, USDC |
| Dogecoin | DOGE | — |
| Solana | SOL | BRLP, XRP, MON, USDT, USDC, BRZ, SKR, USDS, PYUSD, EURC, LINK, JUP |
| Cardano | ADA | SNEK, MIN, IAG, WMTX, DJED |
| Bitcoin Cash | BCH | — |
| TON | TON | USDT |
| Avalanche C-Chain | AVAX | USDT, USDC |
| Ethereum Classic | ETC | — |
| Litecoin | LTC | — |
| Arbitrum | ETH | BRZ, USDC, ARB, USDT, USDS, LINK, USDe |
| Polygon | POL | USDC, USDT, LINK, BRZ |
| Sui | SUI | USDC |
| Dash | DASH | — |
| eCash | XEC | — |
| Base | ETH | USDC, USDS, EURC, LINK, BRZ, USDe, USDT |
| Monero — stagenet in the current Monero flow | XMR | — |
| Stellar | XLM | USDC, BRLP, BRZ |
| HyperEVM | HYPE | USDC, LINK |
| Robinhood Chain | ETH | USDG |

Contracts, issuers, mints and precisions are defined in [CoinCatalog.kt](shared/src/commonMain/kotlin/pub/airlock/domain/CoinCatalog.kt) and [Stablecoins.kt](shared/src/commonMain/kotlin/pub/airlock/domain/Stablecoins.kt). These files also feed the firmware catalogs.

Signatures for all 86 assets were verified locally. This does not demonstrate a real transfer confirmation on every blockchain. The current Monero flow uses stagenet. Cardano token selection uses pure ADA UTXOs or UTXOs containing only the selected token; UTXOs mixed with other assets are left untouched. See the [signature matrix and limitations](docs/all-assets-signing-2026-11-21.md).

## APIs, RPCs and broadcasting

The app separates provider queries from transaction construction and verification for each protocol:

- **UTXO:** Blockstream Esplora and mempool.space for Bitcoin; Freedom Street for coins covered by its adapter.
- **EVM:** Each network’s own RPC, including its registered tokens.
- **Solana:** Solana RPC for SOL and supported tokens.
- **Cardano:** Koios API for preparation and submission.
- **TRON:** TronGrid for TRX and TRC-20 tokens.
- **XRP Ledger:** XRPL JSON-RPC API for XRP and registered issued assets.
- **Stellar:** Horizon API.
- **Sui:** GraphQL for queries, preparation and execution.
- **TON:** TON Center APIs for TON and USDT.
- **Monero:** Dedicated scanner and watch-only flow, with hardware signing and stagenet in the current firmware.

AirLock and Moralis remain available for operations covered by their adapters. Selecting a Bitcoin explorer does not replace another network’s own RPC. The SEND button requires an implemented submission route for the asset; reading a balance alone does not authorize broadcasting.

Provider credentials, when required, are configured by the user and protected in the vault. Routes and capabilities are in [NetworkSettings.kt](shared/src/commonMain/kotlin/pub/airlock/domain/NetworkSettings.kt) and [data/network](shared/src/commonMain/kotlin/pub/airlock/data/network).

## Persistence and user experience

The vault is versioned JSON with AES-256-GCM authenticated encryption and atomic writes. Android Keystore or Keychain protects the local key; authentication failures do not silently create an empty wallet.

The app supports multiple saved wallets, per-wallet account and pending-transaction isolation, native biometrics and eight languages: Portuguese, English, Spanish, French, Simplified Chinese, Japanese, Italian and German. Monetary values use exact per-asset precision without floating-point conversion. Test mode can disable the visual Amount-versus-balance limit; it does not create funds or force a network to accept a transaction.

Synchronizing another wallet preserves existing wallets. Restarting synchronization clears the local vault after confirmation; it does not erase the cold wallet or cancel transactions already broadcast.

## Build for Android

Requirements: JDK 17, Android SDK 36 and the Gradle dependencies. Run from this folder:

```sh
./gradlew :androidApp:assembleDebug
./gradlew :shared:testDebugUnitTest
```

The APK is written to [`androidApp/build/outputs/apk/debug/androidApp-debug.apk`](androidApp/build/outputs/apk/debug/androidApp-debug.apk).

Package identifiers are `pub.airlock.kmp.dev` for debug and `pub.airlock.kmp` for release. Both use real integrations; simulated test adapters do not replace production services. Release distribution requires Android signing configuration.

## Build for iOS

Requirements: macOS, Xcode and Kotlin/Native dependencies. Open [`iosApp/AirLock.xcodeproj`](iosApp/AirLock.xcodeproj) and select the **AirLock** scheme. Xcode embeds the Kotlin framework `AirLockShared` through Gradle, without CocoaPods. Configure the signing team for a physical device.

```sh
./gradlew :shared:compileKotlinIosSimulatorArm64

xcodebuild -project iosApp/AirLock.xcodeproj -scheme AirLock \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath iosApp/DerivedData build
```

## Tests, documentation and maintenance

Tests use public vectors, mocked HTTP transports and signatures produced by the firmware signers. Local success does not demonstrate acceptance by a real provider or replace physical validation of Bluetooth, touch and on-device approval.

- [Architecture and integrated context](docs/BJJ_AIRLOCK.md)
- [Migration and integration contract](docs/MIGRATION.md)
- [Signing flow](docs/transfer-signing-flow.md)
- [UTXO signing](docs/UTXO_SIGNING.md)
- [Complete catalog: signatures and limitations](docs/all-assets-signing-2026-11-21.md)
- [Amount input](docs/amount-mask.md) and [recipient QR](docs/recipient-qr.md)
- [Comments and formatting standards](../docs/CODE_STYLE.md)
- [Previous documentation preserved for historical reference](docs/README-anterior.md)

When contributing, prefer small changes, behavioral tests and contract updates when the protocol changes. Material reused from AirLock retains its original authorship and license: [GPL-3.0](LICENSE), [NOTICE](NOTICE.md) and [resource licenses](docs/licenses).
