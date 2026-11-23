[Português](README.md) · [English](README.en.md) · [Español](README.es.md) · [Français](README.fr.md) · [简体中文](README.zh.md) · [日本語](README.ja.md) · [Italiano](README.it.md) · **Deutsch**

# 🔐 BJJ-Cold-Wallet — Hardware und Firmware

[← Produktübersicht](../README.de.md) · [Mobile App](../app/README.de.md)

**BJJ-Cold-Wallet** ist eine quelloffene ESP32-S3-Hardware-Wallet. Sie erzeugt oder importiert den Seed auf dem Gerät, verwahrt private Ausgabeschlüssel, kontrolliert Telefonzugriffe und verlangt Prüfung und physische Bestätigung vor Überweisungssignaturen.

Die App kommuniziert per **Bluetooth Low Energy**. Sie übernimmt Internetabfragen und Blockchain-Übermittlung. Seed und private Ausgabeschlüssel werden nicht synchronisiert. Ein autorisierter Monero-Beobachtungsexport kann den privaten Ansichtsschlüssel enthalten, der keine Ausgaben autorisiert.

## Verwendete Platine

Aktuelles Auslieferungsziel ist **LCDWIKI ES3C28P mit kapazitivem Touchscreen**.

| Bauteil | Konfiguration |
|---|---|
| Prozessor | ESP32-S3, Dual-Core Xtensa LX7, bis 240 MHz |
| Flash | 16 MB |
| PSRAM | 8 MB OPI |
| Display | 2,8″ IPS, ILI9341V-Controller, SPI-Schnittstelle |
| Native Auflösung | 240 × 320 |
| Aktuelle Oberfläche | Querformat, 320 × 240, mit Touch-Koordinatenumrechnung |
| Touch | Kapazitiv, FT6336G, I²C-Schnittstelle |
| App-Kommunikation | Bluetooth Low Energy |
| Stromversorgung und Wiederherstellung | USB-C; physischer BOOT/RESET-Modus zum Flashen |

Quelle: [LCDWIKI-Herstellerdaten](https://www.lcdwiki.com/2.8inch_ESP32-S3_Display). Treiberkonfiguration unter [esp32/hardware](esp32/hardware) und [esp32/idf_components/airlock_board](esp32/idf_components/airlock_board). [ES3C28P.md](esp32/hardware/ES3C28P.md) bewahrt Pinbelegung und Variantengeschichte; diese README beschreibt die aktuelle Ausrichtung und USB-Richtlinie.

**ES3N28P hat keinen Touchscreen.** Dafür existieren getrennte Validierungs-Builds; das ausgelieferte ES3C28P-Paket benötigt Touch.

### Kauf und Gehäuse

- **[Platine auf AliExpress — vorgeschlagenes Angebot](https://pt.aliexpress.com/item/1005010470966979.html)**. ES3C28P mit Touchscreen auswählen und Angebotsdaten prüfen.
- **[3D-Druck-Gehäuse auf Printables — vorgeschlagene Vorlage](https://www.printables.com/model/1188149-enclosure-for-esp32-s3-touch-lcd-2)**. Mechanische Kompatibilität mit der verwendeten ES3C28P-Revision ist nicht validiert; Abmessungen, Befestigung und Aussparungen prüfen.

## Sprachen, SDKs und Bibliotheken

| Technologie | Version/Herkunft | Aufgabe |
|---|---|---|
| C / C++ | Portabler Kern mit C++17-Kompatibilität | Treiber, Oberfläche, Regeln, Speicherung und Signierer |
| ESP-IDF | 5.4.2 | Espressif-SDK, Boardinitialisierung und eingebettete Dienste |
| FreeRTOS | Von ESP-IDF bereitgestellt | Tasks, Synchronisierung und Dienstausführung |
| NimBLE | In ESP-IDF integriert | Bluetooth LE, GATT und Kopplung |
| LVGL | 9.2.2 | Visuelle Komponenten und Touch-Interaktion |
| esp_lcd_ili9341 | 2.0.1 | ILI9341V-Displaytreiber |
| mbedTLS | 3.6.3 aus ESP-IDF 5.4.2 | Kryptografische Primitive und Speicherschutz |
| TrezorCrypto | Fixierte Teilmenge aus Wallet Core 4.4.2 | Schlüsselableitung, Kurven, Hashes und Signaturen |
| cJSON | 1.7.18, im Projekt enthalten | JSON-Verträge des lokalen Protokolls |
| Rust | Crate Edition 2021; rust-version 1.89 | Monero-Signierer; Xtensa-Build mit esp-rs-Toolchain |
| Monero Oxide / monero-wallet | Git-Revision in Cargo.toml fixiert | RingCT-Aufbau und Signatur, CLSAG und Bulletproof+ |
| curve25519-dalek | 4.1.3 | Kurvenoperationen im Monero-Signierer |
| rand_core / rand_chacha | 0.6.4 / 0.3.1 | Schnittstelle und Erzeugung kryptografischen Zufalls |
| zeroize | 1.8, durch Cargo.lock aufgelöst | Löschen sensibler Werte in Rust |
| Monero-C-Routinen | Tag 0.18.5.4 | AES, Groestl, JH und Skein für Monero |
| CMake / Ninja / Python | Entwicklungswerkzeuge | Build, Kataloggenerierung, Prüfung und Installer |
| SDL2 / OpenSSL 3 / SQLite | Entwicklungsumgebung | Desktop-Simulator und Tests |

Verwaltete ESP-IDF-Abhängigkeiten stehen in [idf_component.yml](esp32/firmware/main/idf_component.yml). Rust-Abhängigkeiten in [Cargo.toml](esp32/monero_signer/Cargo.toml) und [Cargo.lock](esp32/monero_signer/Cargo.lock).

## Trezor-Integration: TrezorCrypto

Verwendet wird die **Kryptografiebibliothek TrezorCrypto**, als Teilmenge aus **Trust Wallet Core 4.4.2**. Implementierung: [esp32/vendor/trezor-crypto-4.4.2](esp32/vendor/trezor-crypto-4.4.2).

Sie stellt unter anderem folgende Primitive bereit:

- BIP-39 mit englischer Wortliste.
- Hierarchische Ableitung sowie integrierte secp256k1-, NIST-P-256- und Ed25519-Operationen.
- ECDSA, Ed25519, HMAC, PBKDF2 sowie benötigte Hashes und Kodierungen.
- Cardano-Routinen der fixierten Version.

Genaue Herkunft, Hash des Upstream-Archivs und Einzeldatei-Hashes stehen in [provenance.json](esp32/vendor/trezor-crypto-4.4.2/provenance.json). Die Teilmenge bleibt unverändert; Buildadapter: [trezor_crypto.cmake](esp32/cmake/trezor_crypto.cmake).

Der Build nutzt `USE_BIP39_CACHE=0` und `USE_BIP32_CACHE=0` zum Abschalten dieser Caches. Den ursprünglichen POSIX-Zufallsadapter ersetzt [trezor_random.cpp](esp32/core/trezor_random.cpp), angebunden an die Plattform-Zufallsquelle. Fehler der Quelle werden nicht durch vorhersehbare Zahlen ersetzt.

**BJJ-Cold-Wallet** rekonstruiert und prüft Zahlungen, erzwingt Zugriffsregeln und zeigt die physische Prüfung an. TrezorCrypto liefert die kryptografischen Primitive. Dies ist weder das vollständige Trezor-SDK noch dessen Firmware oder eine Trezor-Gerätezertifizierung.

Zum Prüfen der fixierten Dateien in diesem Ordner ausführen:

```sh
python3 esp32/tools/check_trezor_sources.py
```

Das native macOS-Wallet-Core-Framework und das JavaScript/WASM-Paket erfüllen eigene Aufgaben in Referenzen, Simulator und Altimplementierung. Die macOS-Binärdatei ist keine ESP32-Bibliothek. Siehe [vendor/README.md](esp32/vendor/README.md).

## Architektur

```text
hardware/
├── esp32/                  # Aktuelle Firmware
│   ├── core/               # Wallets, Validierung und Signierer
│   ├── ui/                 # Bildschirme, Freigabe, Sprachen, Symbole und Schriften
│   ├── hardware/           # Protokolle und Abstraktionen physischer Komponenten
│   ├── idf_components/     # ESP-IDF-Dienst- und Treiberintegration
│   ├── firmware/           # Hauptprojekt für den Platinen-Build
│   ├── monero_signer/      # Rust-Bibliothek für Monero-Signaturen
│   ├── vendor/             # Fremdbibliotheken und Lizenzen
│   ├── cmake/              # Zusätzliche Buildkonfiguration
│   ├── simulator/          # Desktop-Simulator
│   ├── tests/              # Tests und öffentliche Vektoren
│   ├── tools/              # Generierung, Prüfung, Flashen und Paketierung
│   └── docs/               # Berichte und technische Dokumentation
├── instaladores/           # ZIP der aktuellen Version
├── historico/instaladores/ # Frühere Versionen
├── core/                   # Dienste der ursprünglichen Python-Implementierung
├── hardware/               # Treiber der ursprünglichen Implementierung
├── ui/                     # Ursprüngliche Python-/MicroPython-Oberfläche
└── js/                     # Ursprüngliche JavaScript-/Wallet-Core-Signierer
```

`build` und `build-*` enthalten Buildausgaben. `device-updates`, `device-resets` und `device-backups` speichern frühere Platinenoperationen. `simulator-wallets` gehört zur Simulatorumgebung.

Der aktuelle ESP32-Kern verwendet C/C++ und Rust. Python-/MicroPython- und JavaScript-Ordner bewahren Altimplementierung und Kompatibilitätsreferenzen; deren Laufzeit ist nicht der Signaturpfad der ausgelieferten C/C++-Firmware.

## Freigabe, Schlüssel und Telefonzugriff

Das Gerät kontrolliert Kopplung und Berechtigung jeder Verbindung. Erlaubte und gesperrte Telefone stehen in den Hardware-Einstellungen. Ablehnung, Sperrung, Trennung oder Ablauf dürfen keine freigegebene Überweisungssignatur erzeugen.

Vor dem Signieren prüft die Firmware den Plan, identifiziert die Wallet und zeigt Asset, Betrag, Empfänger und Gebühren. Wechselgeld an die eigene Wallet wird getrennt vom Empfänger angezeigt. Die Hardware nutzt Seed und private Ausgabeschlüssel; die App erhält und prüft die Signatur vor der Übermittlung.

In der ausgelieferten Firmware:

- `AIRLOCK_USB_POWER_ONLY=ON`: USB liefert während der Anwendung nur Strom.
- `CONFIG_ESP_CONSOLE_NONE=y`: Konsole deaktiviert.
- `CONFIG_USJ_ENABLE_USB_SERIAL_JTAG` deaktiviert.
- `AIRLOCK_BOARD_AGENT_CONTROL=OFF`: keine Fernsteuerung per USB-Agent.
- WLAN und SD/microSD durch Firmware-Richtlinie gesperrt.
- Physische BOOT/RESET-Wiederherstellung bleibt erhalten; normale Updates ändern keine eFuses.

USB-Daten werden vor dem Öffnen von Wallets oder Bluetooth-Start getrennt. Der ROM-Flashmodus bleibt physisch erreichbar; die Anwendung trennt Schnittstellen nicht dauerhaft elektrisch.

## Netzwerke, Coins und Signierer

Der mit der App geteilte Katalog umfasst **23 Netzwerke und 86 Assets**, darunter native Coins und 63 registrierte Token. Die [vollständige Netzwerk-/Token-Matrix](../app/README.de.md#supported-networks) steht in der App-README.

| Familie | Implementierung und Integration |
|---|---|
| Bitcoin, Litecoin, Dogecoin, Dash, Bitcoin Cash e eCash | Ein-/Ausgangsprüfung, UTXO-Signaturen und netzspezifische Hashregeln |
| Ethereum und EVM-Netzwerke | Transaktionen an Chain-ID gebunden; Token an registrierte Verträge |
| Solana | SOL und registrierte SPL-/Token-2022-Token; geprüfte Nachrichten und Konten |
| Cardano | ADA und fünf registrierte Token, Gebühren und Wechselgeld in ADA |
| TRON | TRX und registrierte TRC-20-Verträge |
| XRP Ledger | XRP und ausgegebene Assets nach Währung/Emittent |
| Stellar | XLM und Assets nach Code/Emittent |
| Sui | SUI und USDC mit expliziten Typen beim Zahlungsaufbau |
| TON | V4R2-Wallet und USDT, abgeleitete und geprüfte Jetton-Wallet-Identität |
| Monero | wallet2-Archiv, RingCT mit CLSAG/Bulletproof+; derzeit Stagenet |

Firmware-Adress- und Tokenkataloge entstehen aus `CoinCatalog.kt` und `Stablecoins.kt` der App. Prüfungen lehnen abweichende Kataloge ab. Erzeugen oder prüfen im Projektstamm:

```sh
python3 hardware/esp32/tools/generate_address_catalog.py --check
python3 hardware/esp32/tools/generate_signing_catalog.py --check
```

Der [Signaturbericht](../app/docs/all-assets-signing-2026-11-21.md) erklärt lokale Abdeckung, Cardano-UTXO-Auswahlgrenzen sowie gültige Signaturen gegenüber Netzwerkannahme.

## Monero-Signaturbibliothek

Rust-Signierer: [esp32/monero_signer](esp32/monero_signer). `monero-wallet` verweist auf Revision `a6b46701fb9b331006d7f2f2278bf51f123b1447` von [Monero Oxide](https://github.com/monero-oxide/monero-oxide); `Cargo.lock` fixiert die Abhängigkeitsauflösung.

Der Xtensa-Build erzeugt `libairlock_monero_signer.a`, eingebunden über `AIRLOCK_MONERO_SIGNER_ARCHIVE`. Die Integration nutzt Plattform-Allokatoren, Speicherlöschung und physische Bestätigung. Das vollständige Modul benötigt die Rust-Toolchain für Xtensa/ESP32-S3; siehe [BUILD.md](esp32/monero_signer/BUILD.md).

## Firmware bauen

Voraussetzungen: **ESP-IDF 5.4.2**, ESP32-S3-Toolchain, CMake, Ninja, Python und bereits für Xtensa gebaute Rust-Monero-Bibliothek. Mit geladener ESP-IDF-Umgebung und Bibliothek am folgenden Pfad in diesem Ordner ausführen:

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

Das Beispiel setzt die Bibliothek im angegebenen Verzeichnis voraus. `sdkconfig` muss Konsole und USB Serial/JTAG deaktiviert halten; CMake prüft die Auslieferungsrichtlinien. Hauptprojekt: [esp32/firmware](esp32/firmware).

## Lokale Tests und Simulator

Portable Tests nutzen dieselben Signierer und prüfen Pläne, Signaturen, geänderte Empfänger/Beträge, Rechte, Kopplung und UI-Zustände. Unabhängige Stellar-, TRON-, XRPL-, Sui- und TON-SDKs sind Entwicklungsabhängigkeiten in [tests/protocol-reference/package.json](esp32/tests/protocol-reference/package.json).

Mit verfügbaren Entwicklungsabhängigkeiten in diesem Ordner ausführen:

```sh
cmake -S esp32 -B esp32/build-host \
  -DAIRLOCK_MBEDTLS_DIR="$IDF_PATH/components/mbedtls/mbedtls" \
  -DAIRLOCK_LVGL_DIR="$PWD/esp32/firmware/managed_components/lvgl__lvgl"
cmake --build esp32/build-host --parallel
ctest --test-dir esp32/build-host --output-on-failure

cargo test --locked --manifest-path esp32/monero_signer/Cargo.toml
```

Der LVGL-Pfad setzt aufgelöste verwaltete ESP-IDF-Abhängigkeiten voraus. Interaktive Desktop-Oberfläche: [Simulator bauen](esp32/README.md#compilar-e-testar-o-simulador). Tests verwenden öffentliche/synthetische Daten und belegen keine echte Blockchain-Überweisung.

## Installierbare Firmware

Enthaltenes Paket für Version `2026.11.21-allassets1`:

**[BJJ-Cold-Wallet-ES3C28P-2026-11-21-allassets1.zip](instaladores/BJJ-Cold-Wallet-ES3C28P-2026-11-21-allassets1.zip)**

Frühere Versionen liegen im [Installer-Archiv](historico/instaladores/). Der Ordner `instaladores` enthält nur die aktuelle ZIP-Datei.

`instalar.py` schreibt die Komponenten an die richtigen Adressen. `firmware` enthält `airlock_board_check.bin`, Bootloader, Partitionstabelle, SHA-256-Hashes und Release-Richtlinie. Normale Updates sollen Wallets und Kopplungen erhalten; Zurücksetzen/Löschen ist ein eigener Vorgang.

Ein erzeugtes Paket belegt nicht die auf einer bestimmten Platine installierte Version. Flashen erfordert den physischen BOOT-Modus.


<a id="installation"></a>

### Installation Schritt für Schritt

1. Obige ZIP herunterladen und entpacken. Alle Dateien zusammenlassen; `instalar.py` ist der Installer, `Instalar.command` die macOS-Verknüpfung. Python 3 und ein USB-C-Datenkabel werden benötigt.
2. BOOT-Modus aktivieren: Bei angeschlossenem USB BOOT halten, RESET (RST/EN) drücken und loslassen, dann BOOT loslassen. Ohne erreichbaren RESET BOOT beim USB-Anschließen halten und nach Erkennung loslassen.
3. Im entpackten Ordner ein Terminal öffnen und Abhängigkeiten vorbereiten:

**macOS und Linux**

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
```

**Windows (PowerShell)**

```powershell
py -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

4. Im BOOT-Modus den Updatebefehl deines Systems ausführen:

**macOS und Linux**

```sh
.venv/bin/python instalar.py
```

**Windows (PowerShell)**

```powershell
.\.venv\Scripts\python.exe instalar.py
```

Bei mehreren Platinen `--porta` mit dem richtigen Port ergänzen: etwa `/dev/cu.usbmodem14101` für macOS, `/dev/ttyACM0` für Linux oder `COM3` für Windows.

Nur für neue Platinen mit leerer Partitionstabelle `--placa-nova` ergänzen. Nicht für eingerichtete Wallets verwenden. Zum Prüfen von Paket und Partitionstabelle ohne Schreiben `--verificar` an den Befehl des gewählten Modus anhängen.

5. Abschluss und Neustart abwarten. Normale Updates schreiben nur die Anwendung und prüfen die Tabellenkompatibilität; Wallets, Name, PIN und Kopplungen bleiben erhalten. Nach dem Start verschwindet der USB-Port: USB liefert Strom, die App nutzt Bluetooth. Bleibt die Platine im Bootloader, BOOT loslassen und RESET einmal drücken.

Die ZIP enthält außerdem eine `README.md` mit ausführlicher Anleitung und Fehlerdiagnose.

## Dokumentation, Pflege und Lizenzen

- [ESP32-Port und Geschichte](esp32/README.md): einschließlich älterer Revisionen.
- [TrezorCrypto-Herkunft](esp32/vendor/trezor-crypto-4.4.2/provenance.json) und [Upstream-Lizenzen](esp32/vendor/trezor-crypto-4.4.2/crypto/LICENSE).
- [Peripherierichtlinie](esp32/idf_components/airlock_policy/README.md).
- [Kommentar- und Formatierungsstandards](../docs/CODE_STYLE.md).
- [Regeln für ausgelieferte Firmware](AGENTS.md).

Aus AirLock übernommener Code behält Urheberschaft und [GPL-3.0-Lizenz](LICENSE). Fremdbibliotheken behalten eigene Lizenzen und Hinweise. Bei Änderungen an Kryptografiebibliotheken oder registrierten Verträgen Version/Herkunft dokumentieren, Referenzen aktualisieren und passende Prüfungen ausführen.
