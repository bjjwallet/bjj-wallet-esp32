[Português](README.md) · [English](README.en.md) · [Español](README.es.md) · [Français](README.fr.md) · [简体中文](README.zh.md) · [日本語](README.ja.md) · [Italiano](README.it.md) · **Deutsch**

# 📱 BJJ-Cold-Wallet: Mobile App

[← Produktübersicht](../README.de.md) · [Hardware und Firmware](../hardware/README.de.md)

**BJJ-App-Wallet** ist die Android- und iOS-Begleit-App für **BJJ-Cold-Wallet**. Sie bietet Konten- und Überweisungsoberflächen, Bluetooth-Kommunikation mit der Hardware-Wallet, Netzwerkabfragen und die Übermittlung signierter Transaktionen.

Die App ist eine Weiterentwicklung von AirLock. Die aktuelle Oberfläche verwendet Kotlin/Compose Multiplatform; interne Namen wie `AirLockShared`, `AirLock.xcodeproj` und der Namespace `pub.airlock` bleiben im Code erhalten.

## Aufgaben und Sicherheitsablauf

Die App synchronisiert Konten und Adressen, verwaltet mehrere gespeicherte Wallets, fragt Guthaben ab, liest Empfänger-QR-Codes und bereitet Überweisungsdaten vor. Ausgabesignaturen entstehen nach Prüfung und physischer Bestätigung auf der Cold Wallet.

```text
App bereitet Zahlung vor
        ↓ Bluetooth / lokale RPC
Cold Wallet prüft Daten und verlangt physische Bestätigung
        ↓ Signatur auf der Hardware
App prüft die Signatur
        ↓ Netzwerk-API / RPC
Transaktion übertragen
```

**Die App importiert oder speichert weder Seed noch private Ausgabeschlüssel.** Sie verarbeitet jedoch sensible Daten: PINs, Zugangseinstellungen und Tresorinformationen. Der Monero-Beobachtungsmodus kann einen **privaten Ansichtsschlüssel** speichern, der Bewegungen sichtbar macht, aber keine Ausgaben signiert. Er bleibt im verschlüsselten Tresor und wird nicht an den entfernten Knoten gesendet.

Die App-PIN schützt die lokale Entsperrung. Die Cold-Wallet-PIN authentifiziert die Geräteverbindung und wird von der App nicht dauerhaft gespeichert. Die Transaction Phrase ist ein eigener Schutz; neue Signaturrouten fragen sie auf der Hardware ab. Details: [transfer-signing-flow.md](docs/transfer-signing-flow.md).

## Sprachen, Plattformen und Werkzeuge

| Technologie | Projektversion/Konfiguration | Aufgabe |
|---|---|---|
| Kotlin | 2.3.21 | Regeln, Netzwerk, Bluetooth und gemeinsame Oberfläche |
| Kotlin Multiplatform | Kotlin-Plugin 2.3.21 | Gemeinsamer Android- und iOS-Code |
| Compose Multiplatform | 1.10.0 | Deklarative Oberfläche und gemeinsame Ressourcen |
| Material 3 | 1.9.0 | Visuelle Komponenten |
| Swift / SwiftUI | Xcode-SDK | App-Einstieg und native Apple-Integrationen |
| Android Gradle Plugin | 8.11.2 | Android-Builds |
| Android SDK | compile/target 36; mindestens 29 | Android 10 oder neuer |
| iOS | Deployment-Ziel 16.0 | iPhone/iPad; Build mit Xcode |
| Java | JVM 17 | Android-Buildwerkzeuge |
| Gradle Wrapper | Im Projekt enthalten | Reproduzierbare Buildaufgaben |

Deklarierte Versionen stehen in [libs.versions.toml](gradle/libs.versions.toml), [shared/build.gradle.kts](shared/build.gradle.kts) und [androidApp/build.gradle.kts](androidApp/build.gradle.kts).

## Bibliotheken und SDKs

| Bibliothek / SDK | Version | Aufgabe |
|---|---|---|
| Kotlin Coroutines | 1.10.2 | Asynchrone Vorgänge, Flow und StateFlow |
| Kotlinx Serialization | 1.9.0 | JSON-Serialisierung für Modelle und Verträge |
| Ktor Client | 3.4.3 | HTTP, APIs/RPCs; OkHttp unter Android und Darwin unter iOS |
| Kable | 0.41.0 | Bluetooth-Low-Energy-Verbindungen |
| Koin | 4.1.0 | Dependency Injection |
| Lifecycle / ViewModel | 2.9.6 | Bildschirmzustand und Lebenszyklus |
| bitcoin-kmp, ACINQ | 0.32.0 | Bitcoin-/UTXO-Strukturen, Hashes, Adressen und Signaturprüfung |
| secp256k1-kmp JNI | 0.24.0 | secp256k1-Backend für Android und JVM-Tests |
| KotlinCrypto SHA-3 | 0.8.0 | Keccak und Protokoll-Hashes |
| Kotlin Bignum | 0.3.10 | Arithmetik mit beliebiger Genauigkeit |
| Tink Android | 1.10.0 | Kryptografische Funktionen für Android |
| Coil / Coil SVG | 3.3.0 | Laden von Bildern und Symbolen |
| Material Icons | 1.7.3 | Oberflächensymbole |
| Activity Compose | 1.10.1 | Compose-Integration mit Android Activity |
| ZXing Core / Embedded | 3.5.3 / 4.3.0 | QR-Erfassung unter Android |
| Android Keystore / BiometricPrompt | SDK Android | Schutz lokaler Schlüssel und native Authentifizierung |
| CryptoKit / Keychain / LocalAuthentication | SDK Apple | Verschlüsselter Tresor und iOS-Authentifizierung |
| AVFoundation | SDK Apple | QR-Aufnahme und -Erkennung unter iOS |
| Monero-JS/WASM-Laufzeit | Als lokale Ressourcen enthalten | Beobachtungsscanner mit WebView/WKWebView als Laufzeit |

Monero-Laufzeit und Lizenzen liegen unter [composeResources/files/monero](shared/src/commonMain/composeResources/files/monero). Sie beobachtet die Wallet, ohne Seed oder privaten Ausgabeschlüssel zu erhalten.

## Projektstruktur

```text
app/
├── androidApp/        # Android-Einstieg, Berechtigungen und QR-Scanner
├── iosApp/            # Xcode-Projekt, SwiftUI, Keychain und UI-Tests
├── shared/
│   └── src/
│       ├── commonMain/       # Gemeinsame Funktionen
│       ├── androidMain/      # Native Android-Adapter
│       ├── iosMain/          # Native iOS-Adapter
│       ├── commonTest/       # Gemeinsame Tests
│       ├── androidUnitTest/  # JVM-Tests und kryptografische Prüfungen
│       └── test/             # Öffentliche Referenzvektoren
├── gradle/           # Wrapper und Versionskatalog
├── scripts/          # Prüfungen und Ressourcengenerierung
├── docs/             # Architektur, Verträge und Testergebnisse
└── hardware-patches/ # Dokumentation der Hardware-Integrationsanpassungen
```

Unter `shared/src/commonMain/kotlin/pub/airlock`:

| Ordner | Verantwortung |
|---|---|
| `domain` | Modelle, Kataloge, Schnittstellen, Regeln und exakte Geldbeträge |
| `data` | Tresor, Repositories, Netzwerkintegrationen und Cold-Wallet-Adapter |
| `ble` | Bluetooth-Transport und lokale Protokollnachrichten |
| `presentation` | ViewModel, Bildschirmzustand und Ablaufkoordination |
| `ui` | Compose-Bildschirme, Komponenten, Designs und Fehleranzeige |
| `di` | Verdrahtung der App-Abhängigkeiten |

<a id="supported-networks"></a>

## Unterstützte Netzwerke und Coins

Der Katalog enthält **23 Netzwerke, 23 native Coins und 63 Token**, insgesamt **86 Assets**. Gleiche Symbole auf verschiedenen Netzwerken sind getrennte Integrationen.

| Netzwerk | Nativer Coin | Registrierte Token |
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
| Monero — Stagenet im aktuellen Ablauf | XMR | — |
| Stellar | XLM | USDC, BRLP, BRZ |
| HyperEVM | HYPE | USDC, LINK |
| Robinhood Chain | ETH | USDG |

Verträge, Emittenten, Mints und Dezimalstellen sind in [CoinCatalog.kt](shared/src/commonMain/kotlin/pub/airlock/domain/CoinCatalog.kt) und [Stablecoins.kt](shared/src/commonMain/kotlin/pub/airlock/domain/Stablecoins.kt) definiert. Diese Dateien speisen auch die Firmware-Kataloge.

Signaturen aller 86 Assets wurden lokal geprüft. Das belegt keine Bestätigung einer echten Überweisung auf jeder Blockchain. Monero verwendet derzeit Stagenet. Cardano wählt reine ADA-UTXOs oder UTXOs mit ausschließlich dem gewählten Token; UTXOs mit weiteren Assets bleiben unberührt. Siehe [Signaturmatrix und Grenzen](docs/all-assets-signing-2026-11-21.md).

## APIs, RPCs und Übermittlung

Die App trennt Anbieterabfragen von Aufbau und Prüfung der Transaktionen je Protokoll:

- **UTXO:** Blockstream Esplora und mempool.space für Bitcoin; Freedom Street für Coins seines Adapters.
- **EVM:** Eigene RPC jedes Netzwerks, einschließlich registrierter Token.
- **Solana:** Solana-RPC für SOL und unterstützte Token.
- **Cardano:** Koios-API für Vorbereitung und Übermittlung.
- **TRON:** TronGrid für TRX und TRC-20-Token.
- **XRP Ledger:** XRPL-JSON-RPC-API für XRP und registrierte ausgegebene Assets.
- **Stellar:** Horizon-API.
- **Sui:** GraphQL für Abfragen, Vorbereitung und Ausführung.
- **TON:** TON-Center-APIs für TON und USDT.
- **Monero:** Eigener Scanner und Beobachtungsablauf, Hardwaresignatur und Stagenet in der aktuellen Firmware.

AirLock und Moralis bleiben für die von ihren Adaptern unterstützten Vorgänge verfügbar. Die Wahl eines Bitcoin-Explorers ersetzt keine RPC eines anderen Netzwerks. SEND erfordert eine implementierte Übermittlungsroute für das Asset; eine Guthabenabfrage allein genügt nicht.

Erforderliche Anbieterzugangsdaten werden vom Nutzer eingerichtet und im Tresor geschützt. Routen und Fähigkeiten stehen in [NetworkSettings.kt](shared/src/commonMain/kotlin/pub/airlock/domain/NetworkSettings.kt) und [data/network](shared/src/commonMain/kotlin/pub/airlock/data/network).

## Speicherung und Bedienung

Der Tresor verwendet versioniertes JSON, authentifizierte AES-256-GCM-Verschlüsselung und atomare Schreibvorgänge. Android Keystore bzw. Keychain schützt den lokalen Schlüssel; Authentifizierungsfehler erzeugen nicht stillschweigend eine leere Wallet.

Die App unterstützt mehrere gespeicherte Wallets, getrennte Konten und ausstehende Transaktionen je Wallet, native Biometrie und acht Sprachen: Portugiesisch, Englisch, Spanisch, Französisch, vereinfachtes Chinesisch, Japanisch, Italienisch und Deutsch. Geldbeträge werden mit der exakten Asset-Genauigkeit ohne Fließkommakonvertierung verarbeitet. Der Testmodus kann die sichtbare Amount-/Guthabenbegrenzung aufheben; er erzeugt weder Guthaben noch eine Annahme durch das Netzwerk.

Eine weitere Wallet zu synchronisieren erhält bestehende Wallets. Ein Neustart der Synchronisierung löscht nach Bestätigung den lokalen Tresor, aber weder die Cold Wallet noch bereits übertragene Transaktionen.

## Android bauen

Voraussetzungen: JDK 17, Android SDK 36 und Gradle-Abhängigkeiten. In diesem Ordner ausführen:

```sh
./gradlew :androidApp:assembleDebug
./gradlew :shared:testDebugUnitTest
```

Die APK entsteht unter [`androidApp/build/outputs/apk/debug/androidApp-debug.apk`](androidApp/build/outputs/apk/debug/androidApp-debug.apk).

Paketkennungen: `pub.airlock.kmp.dev` für Debug und `pub.airlock.kmp` für Release. Beide nutzen echte Integrationen; simulierte Testadapter ersetzen keine Produktionsdienste. Für Releases ist eine Android-Signierkonfiguration erforderlich.

## iOS bauen

Voraussetzungen: macOS, Xcode und Kotlin/Native-Abhängigkeiten. [`iosApp/AirLock.xcodeproj`](iosApp/AirLock.xcodeproj) öffnen und Schema **AirLock** wählen. Xcode bindet das Kotlin-Framework `AirLockShared` über Gradle ohne CocoaPods ein. Für physische Geräte das Signierteam konfigurieren.

```sh
./gradlew :shared:compileKotlinIosSimulatorArm64

xcodebuild -project iosApp/AirLock.xcodeproj -scheme AirLock \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath iosApp/DerivedData build
```

## Tests, Dokumentation und Pflege

Tests verwenden öffentliche Vektoren, simulierte HTTP-Transporte und Signaturen der Firmware-Signierer. Lokaler Erfolg belegt keine Annahme durch einen echten Anbieter und ersetzt keine physische Prüfung von Bluetooth, Touch und Gerätefreigabe.

- [Architektur und integrierter Kontext](docs/BJJ_AIRLOCK.md)
- [Migration und Integrationsvertrag](docs/MIGRATION.md)
- [Signaturablauf](docs/transfer-signing-flow.md)
- [UTXO-Signaturen](docs/UTXO_SIGNING.md)
- [Vollständiger Katalog: Signaturen und Grenzen](docs/all-assets-signing-2026-11-21.md)
- [Betragseingabe](docs/amount-mask.md) und [Empfänger-QR](docs/recipient-qr.md)
- [Kommentar- und Formatierungsstandards](../docs/CODE_STYLE.md)
- [Frühere Dokumentation als historische Referenz](docs/README-anterior.md)

Beiträge sollten kleine Änderungen, Verhaltenstests und bei Protokolländerungen aktualisierte Verträge enthalten. Aus AirLock übernommenes Material behält Urheberschaft und Lizenz: [GPL-3.0](LICENSE), [NOTICE](NOTICE.md) und [Ressourcenlizenzen](docs/licenses).
