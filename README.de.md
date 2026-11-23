[Português](README.md) · [English](README.en.md) · [Español](README.es.md) · [Français](README.fr.md) · [简体中文](README.zh.md) · [日本語](README.ja.md) · [Italiano](README.it.md) · **Deutsch**

<picture>
  <source media="(max-width: 640px)" srcset="docs/assets/hero-mobile.png">
  <img src="docs/assets/hero.png" alt="BJJ-Wallet — erschwingliche Hardware-Wallets, offener Quellcode und Zusammenarbeit" width="100%">
</picture>

# BJJ-Cold-Wallet

<a id="testing"></a>

## 🧪 Wallet in der Testphase

**BJJ-Cold-Wallet befindet sich in der Test- und Entwicklungsphase**. Wir brauchen die Hilfe der Community, um App- und Hardwareabläufe zu prüfen, Fehler zu finden und die Bedienung zu verbessern.

**Hilf beim Testen!** Probiere Einrichtung, Bluetooth-Verbindung, Synchronisierung sowie Transaktionsprüfung und Signatur auf der Cold Wallet aus. Teile Probleme und Vorschläge über die [BJJ-Wallet-Organisation auf GitHub](https://github.com/bjjwallet) und nenne Telefonmodell, App- und Firmwareversion sowie Schritte zur Reproduktion.

Verwende bevorzugt Testnetzwerke und eigens angelegte Test-Wallets. Teile niemals Seed, private Schlüssel, PIN oder Transaction Phrase in Berichten, Screenshots oder Protokollen.

**Eine Cold Wallet von Entwicklern für Entwickler. Offen für alle.**

ESP32-S3-Hardware-Wallet mit Touchscreen, Freigabe von Überweisungen auf dem Gerät und **Bluetooth Low Energy** zur Kommunikation mit einer Android- und iOS-App.

<p align="center">
  <img src="docs/assets/badge-open-source.svg" alt="Open Source" height="28">
  <img src="docs/assets/badge-affordable.svg" alt="Erschwingliche Hardware" height="28">
  <img src="docs/assets/badge-community.svg" alt="Gemeinsam entwickelt" height="28">
</p>

## 👋 Wer wir sind

Wir sind **BJJ-Wallet**, eine Gruppe von Entwicklern, die Cold Wallets durch günstige Hardware, quelloffene Software und gemeinsames Wissen zugänglicher machen möchte.

**BJJ-Cold-Wallet** setzt diese Idee um: eine funktionsfähige Wallet, die die Community untersuchen, bauen und verbessern kann. Jeder soll verstehen können, was auf dem Gerät läuft, das seine Coins schützt.

Unsere Grundsätze sind Zugänglichkeit, Transparenz, Sicherheit bei der Entwicklung und Zusammenarbeit. Entwickler, Maker, Designer und Tester sind willkommen: Lest den Code, hinterfragt Entscheidungen, meldet Probleme und helft bei der Verbesserung.

Besucht die [BJJ-Wallet-Organisation auf GitHub](https://github.com/bjjwallet).

[💚 BJJ-Cold-Wallet unterstützen](#support-project)

## 📱 Mobile App

Die App für **BJJ-Cold-Wallet** läuft auf Android und iOS. Sie verbindet sich per Bluetooth, synchronisiert Daten zur Kontobeobachtung, fragt Guthaben ab, bereitet Überweisungen vor und überträgt die von der Hardware signierten Transaktionen.

Ablauf einer Überweisung:

1. Der Nutzer wählt Netzwerk, Asset, Empfänger und Betrag in der App.
2. Die App fordert per Bluetooth eine Signatur von der Cold Wallet an.
3. Die Cold Wallet zeigt die Details und verlangt eine physische Bestätigung.
4. Die Hardware signiert und gibt das Ergebnis an die App zurück.
5. Die App prüft die Signatur und übermittelt die signierten Daten über die jeweilige API, RPC oder Netzwerkschnittstelle.

**Seed und private Ausgabeschlüssel bleiben auf der Cold Wallet. Die App fordert den Seed für den Betrieb weder an noch importiert sie ihn.** Die App verarbeitet Zugangsdaten; im Monero-Beobachtungsmodus kann der private Ansichtsschlüssel im verschlüsselten Tresor liegen. Er erlaubt Einblick in Bewegungen, aber keine Ausgaben.

Der aktuelle Katalog umfasst **23 Netzwerke und 86 Assets: 23 native Coins und 63 Token**. Dazu gehören Bitcoin, Ethereum und EVM-Netzwerke, Solana, Cardano, TRON, XRP Ledger, TON, Sui, Stellar, Litecoin, Dogecoin, Bitcoin Cash, Dash, eCash und Monero. Der Monero-Ablauf der aktuellen Firmware verwendet **Stagenet**. Die Netzwerk-/Token-Matrix steht in der App-README.

Die App verwendet **Kotlin Multiplatform und Compose Multiplatform** sowie native Android- und iOS-Integrationen.

➡️ **[Zur App: Architektur, Sprachen, Bibliotheken, Netzwerke und Build](app/README.de.md)**

## 🔐 Hardware

BJJ-Cold-Wallet verwendet das **LCDWIKI ES3C28P mit kapazitivem Touchscreen**, **ESP32-S3**, **16 MB Flash**, **8 MB PSRAM** und **2,8-Zoll-IPS-Display**. Native Auflösung: 240 × 320; aktuelle Oberfläche: Querformat mit 320 × 240. Technische Daten: [Herstellerdokumentation](https://www.lcdwiki.com/2.8inch_ESP32-S3_Display).

Die Firmware nutzt **C/C++ mit ESP-IDF**, eine **LVGL**-Oberfläche und eine **Rust**-Bibliothek für Monero-Signaturen. Sie integriert **TrezorCrypto** aus Trust Wallet Core 4.4.2; Version und Herkunft sind im Projekt dokumentiert.

Die Cold Wallet verwaltet Telefonzugriffe, zeigt Freigabedetails und signiert. In der ausgelieferten Anwendung liefert USB Strom; Bluetooth übernimmt die App-Kommunikation. WLAN und microSD sind durch die Firmware gesperrt.

### 🛒 Platine und Gehäuse

- **[Platine kaufen — vorgeschlagenes AliExpress-Angebot](https://pt.aliexpress.com/item/1005010470966979.html)**. Die Variante **ES3C28P mit Touchscreen** prüfen; ES3N28P hat keinen Touchscreen.
- **[3D-Druck-Gehäuse — vorgeschlagene Printables-Vorlage](https://www.printables.com/model/1188149-enclosure-for-esp32-s3-touch-lcd-2)**. Vor dem Druck Abmessungen, Befestigung und Anschlusszugang prüfen; die mechanische Kompatibilität wurde vom Projekt noch nicht bestätigt.

➡️ **[Zur Hardware: Platine, TrezorCrypto, Bibliotheken, Firmware und Installation](hardware/README.de.md)**

Das Projekt wird entwickelt. Siehe [Signaturtestergebnisse und Grenzen](app/docs/all-assets-signing-2026-11-21.md), [Codestandards](docs/CODE_STYLE.md) sowie Lizenzen von [App](app/LICENSE) und [Hardware](hardware/LICENSE).

<a id="support-project"></a>

## 💚 Hilf mit, die Idee am Leben zu halten

Wir möchten Cold Wallets mit günstiger Hardware und offenem Code zugänglich machen. Dein Beitrag unterstützt die Entwicklung von BJJ-Cold-Wallet und hält diese Idee am Leben.

**Aktuell benötigen wir Hilfe bei den Anmeldegebühren der App-Stores, um die Android- und iOS-Apps zu veröffentlichen.**

### Vor der Spende das Netzwerk wählen

| USDT · Ethereum | USDT0 · Polygon |
|:---:|:---:|
| <img src="docs/donate/usdt-ethereum.png" alt="Adress-QR-Code für USDT · Ethereum" width="220"> | <img src="docs/donate/usdt0-polygon.png" alt="Adress-QR-Code für USDT0 · Polygon" width="220"> |
| Netzwerk: **Ethereum** | Netzwerk: **Polygon** |

Beide QR-Codes enthalten nur dieselbe Adresse. Vor dem Senden in deiner Wallet den angegebenen Token und das Netzwerk wählen.

**Spendenadresse**

Auf GitHub die Kopierschaltfläche in der Ecke des folgenden Blocks verwenden. Die Adresse ist auf beiden Netzwerken gleich.

```text
0x142f6f4230934c5555772e4de1E8E39E1105CB2b
```

Danke für deine Unterstützung offener Entwicklung.
