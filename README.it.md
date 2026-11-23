[Português](README.md) · [English](README.en.md) · [Español](README.es.md) · [Français](README.fr.md) · [简体中文](README.zh.md) · [日本語](README.ja.md) · **Italiano** · [Deutsch](README.de.md)

<picture>
  <source media="(max-width: 640px)" srcset="docs/assets/hero-mobile.png">
  <img src="docs/assets/hero.png" alt="BJJ-Wallet — hardware wallet accessibili, codice aperto e collaborazione" width="100%">
</picture>

# BJJ-Cold-Wallet

<a id="testing"></a>

## 🧪 Wallet in fase di test

**BJJ-Cold-Wallet è in fase di test e sviluppo**. Abbiamo bisogno dell’aiuto della comunità per verificare i flussi dell’app e dell’hardware, individuare problemi e migliorare l’esperienza.

**Aiutaci a testarla!** Prova la configurazione, la connessione Bluetooth, la sincronizzazione, la revisione e la firma delle transazioni sulla cold wallet. Condividi problemi e suggerimenti tramite [l’organizzazione BJJ-Wallet su GitHub](https://github.com/bjjwallet), indicando modello del telefono, versioni dell’app e del firmware e passaggi per riprodurre il problema.

Preferisci reti di prova e wallet create per i test. Non condividere mai seed, chiavi private, PIN o Transaction Phrase in segnalazioni, screenshot o log.

**Una cold wallet creata da sviluppatori, per sviluppatori. Aperta a tutti.**

Hardware wallet ESP32-S3 con touchscreen, approvazione dei trasferimenti sul dispositivo e comunicazione **Bluetooth Low Energy** con un’app per Android e iOS.

<p align="center">
  <img src="docs/assets/badge-open-source.svg" alt="Codice aperto" height="28">
  <img src="docs/assets/badge-affordable.svg" alt="Hardware accessibile" height="28">
  <img src="docs/assets/badge-community.svg" alt="Costruita insieme" height="28">
</p>

## 👋 Chi siamo

Siamo **BJJ-Wallet**, un gruppo di sviluppatori che vuole rendere le cold wallet più accessibili attraverso hardware economico, software open source e conoscenze condivise.

**BJJ-Cold-Wallet** concretizza questa idea: una wallet funzionale che la comunità può studiare, costruire e migliorare. Vogliamo che tutti comprendano il codice eseguito sul dispositivo che protegge le loro monete.

I nostri principi sono accessibilità, trasparenza, progettazione della sicurezza e collaborazione. Sviluppatori, maker, designer e tester sono benvenuti: leggete il codice, discutete le scelte, segnalate problemi e contribuite a migliorare il progetto.

Visita [l’organizzazione BJJ-Wallet su GitHub](https://github.com/bjjwallet).

[💚 Sostieni BJJ-Cold-Wallet](#support-project)

## 📱 App mobile

L’app di **BJJ-Cold-Wallet** funziona su Android e iOS. Si collega alla wallet tramite Bluetooth, sincronizza i dati necessari per seguire i conti, consulta i saldi, prepara trasferimenti e trasmette le transazioni firmate dall’hardware.

Il flusso di trasferimento:

1. L’utente seleziona rete, asset, destinatario e importo nell’app.
2. L’app richiede una firma alla cold wallet tramite Bluetooth.
3. La cold wallet mostra i dettagli e richiede l’approvazione fisica.
4. L’hardware firma e restituisce il risultato all’app.
5. L’app verifica la firma e invia i dati firmati tramite API, RPC o interfaccia della rete corrispondente.

**Il seed e le chiavi private di spesa rimangono sulla cold wallet. L’app non richiede né importa il seed per funzionare.** L’app gestisce credenziali di accesso; la modalità Monero di sola osservazione può conservare la chiave privata di visualizzazione nel vault cifrato. Questa chiave permette di vedere i movimenti, ma non di autorizzare spese.

Il catalogo attuale comprende **23 reti e 86 asset: 23 monete native e 63 token**. Include Bitcoin, Ethereum e reti EVM, Solana, Cardano, TRON, XRP Ledger, TON, Sui, Stellar, Litecoin, Dogecoin, Bitcoin Cash, Dash, eCash e Monero. Il flusso Monero disponibile nel firmware attuale usa **stagenet**. La matrice per rete e token è nel README dell’app.

L’app utilizza **Kotlin Multiplatform e Compose Multiplatform**, con integrazioni native Android e iOS.

➡️ **[Scopri l’app: architettura, linguaggi, librerie, reti e compilazione](app/README.it.md)**

## 🔐 Hardware

BJJ-Cold-Wallet utilizza la **scheda LCDWIKI ES3C28P con touch capacitivo**, basata su **ESP32-S3**, con **16 MB di flash**, **8 MB di PSRAM** e **schermo IPS da 2,8 pollici**. La risoluzione nativa è 240 × 320; l’interfaccia attuale è orizzontale, 320 × 240. Specifiche: [documentazione del produttore](https://www.lcdwiki.com/2.8inch_ESP32-S3_Display).

Il firmware usa **C/C++ con ESP-IDF**, interfaccia **LVGL** e una libreria **Rust** per le firme Monero. Include **TrezorCrypto** proveniente da Trust Wallet Core 4.4.2, con versioni e provenienza registrate nel progetto.

La cold wallet controlla l’accesso dei telefoni, presenta i dettagli da approvare ed esegue la firma. Nell’applicazione distribuita USB fornisce alimentazione e Bluetooth gestisce la comunicazione. Wi-Fi e microSD sono bloccati dal firmware.

### 🛒 Scheda e custodia

- **[Acquista la scheda — inserzione AliExpress suggerita](https://pt.aliexpress.com/item/1005010470966979.html)**. Verifica la variante **ES3C28P con touchscreen**; ES3N28P non ha il touch.
- **[Custodia stampabile in 3D — riferimento Printables suggerito](https://www.printables.com/model/1188149-enclosure-for-esp32-s3-touch-lcd-2)**. Prima della stampa verifica dimensioni, fissaggi e accesso ai connettori della tua scheda; il progetto non ha ancora validato la compatibilità meccanica di questo modello.

➡️ **[Scopri l’hardware: scheda, TrezorCrypto, librerie, firmware e installazione](hardware/README.it.md)**

Il progetto è in sviluppo. Consulta [risultati e limiti dei test di firma](app/docs/all-assets-signing-2026-11-21.md), [standard del codice](docs/CODE_STYLE.md) e licenze di [app](app/LICENSE) e [hardware](hardware/LICENSE).

<a id="support-project"></a>

## 💚 Aiutaci a mantenere viva l’idea

Vogliamo democratizzare le cold wallet con hardware accessibile e codice aperto. Il tuo contributo sostiene lo sviluppo di BJJ-Cold-Wallet e mantiene viva questa idea.

**In questo momento abbiamo bisogno di aiuto per pagare le iscrizioni agli store e pubblicare le app Android e iOS.**

### Scegli la rete prima di donare

| USDT · Ethereum | USDT0 · Polygon |
|:---:|:---:|
| <img src="docs/donate/usdt-ethereum.png" alt="QR dell’indirizzo per USDT · Ethereum" width="220"> | <img src="docs/donate/usdt0-polygon.png" alt="QR dell’indirizzo per USDT0 · Polygon" width="220"> |
| Rete: **Ethereum** | Rete: **Polygon** |

Entrambi i QR contengono soltanto lo stesso indirizzo. Seleziona il token e la rete indicati nella tua wallet prima di inviare.

**Indirizzo per le donazioni**

Su GitHub, usa il pulsante di copia nell’angolo del blocco seguente. L’indirizzo è lo stesso su entrambe le reti.

```text
0x142f6f4230934c5555772e4de1E8E39E1105CB2b
```

Grazie per sostenere lo sviluppo aperto.
