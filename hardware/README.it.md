[Português](README.md) · [English](README.en.md) · [Español](README.es.md) · [Français](README.fr.md) · [简体中文](README.zh.md) · [日本語](README.ja.md) · **Italiano** · [Deutsch](README.de.md)

# 🔐 BJJ-Cold-Wallet — hardware e firmware

[← Presentazione del prodotto](../README.it.md) · [App mobile](../app/README.it.md)

**BJJ-Cold-Wallet** è una hardware wallet open source ESP32-S3. Genera o importa il seed sul dispositivo, conserva le chiavi private di spesa, controlla l’accesso dei telefoni e richiede revisione e approvazione fisica prima di firmare trasferimenti.

L’app comunica con la cold wallet tramite **Bluetooth Low Energy**. Gestisce le interrogazioni internet e l’invio alla blockchain. Seed e chiavi private di spesa non vengono sincronizzati. L’esportazione Monero autorizzata di sola osservazione può includere la chiave privata di visualizzazione, che non autorizza spese.

## Scheda utilizzata

La distribuzione attuale è destinata alla **LCDWIKI ES3C28P con touch capacitivo**.

| Componente | Configurazione |
|---|---|
| Processore | ESP32-S3, Xtensa LX7 dual-core, fino a 240 MHz |
| Flash | 16 MB |
| PSRAM | 8 MB OPI |
| Schermo | IPS 2,8″, controller ILI9341V, interfaccia SPI |
| Risoluzione nativa | 240 × 320 |
| Interfaccia attuale | Orizzontale, 320 × 240, trasformazione delle coordinate touch |
| Touch | Capacitivo FT6336G, interfaccia I²C |
| Comunicazione app | Bluetooth Low Energy |
| Alimentazione e recupero | USB-C; modalità fisica BOOT/RESET per la scrittura |

Fonte: [specifiche LCDWIKI](https://www.lcdwiki.com/2.8inch_ESP32-S3_Display). Configurazione driver in [esp32/hardware](esp32/hardware) e [esp32/idf_components/airlock_board](esp32/idf_components/airlock_board). [ES3C28P.md](esp32/hardware/ES3C28P.md) conserva pinout e storia delle varianti; questo README descrive orientamento e politica USB attuali.

**ES3N28P è la variante senza touch.** Le sue compilazioni di validazione hanno impostazioni separate; il pacchetto ES3C28P distribuito richiede il touch.

### Acquisto e custodia

- **[Scheda su AliExpress — inserzione suggerita](https://pt.aliexpress.com/item/1005010470966979.html)**. Selezionare ES3C28P con touchscreen e controllare le specifiche.
- **[Custodia 3D su Printables — riferimento suggerito](https://www.printables.com/model/1188149-enclosure-for-esp32-s3-touch-lcd-2)**. Compatibilità meccanica con la revisione ES3C28P utilizzata non validata; verificare dimensioni, fissaggi e aperture.

## Linguaggi, SDK e librerie

| Tecnologia | Versione/provenienza | Funzione |
|---|---|---|
| C / C++ | Nucleo portabile compatibile C++17 | Driver, interfaccia, regole, memoria e firmatari |
| ESP-IDF | 5.4.2 | SDK Espressif, inizializzazione scheda e servizi embedded |
| FreeRTOS | Fornito da ESP-IDF | Attività, sincronizzazione ed esecuzione servizi |
| NimBLE | Integrato in ESP-IDF | Bluetooth LE, GATT e associazione |
| LVGL | 9.2.2 | Componenti visivi e interazione touch |
| esp_lcd_ili9341 | 2.0.1 | Driver pannello ILI9341V |
| mbedTLS | 3.6.3, da ESP-IDF 5.4.2 | Primitive crittografiche e protezione della memoria |
| TrezorCrypto | Sottoinsieme fissato da Wallet Core 4.4.2 | Derivazione chiavi, curve, hash e firme |
| cJSON | 1.7.18, incluso nel progetto | Contratti JSON del protocollo locale |
| Rust | Crate edizione 2021; rust-version 1.89 | Firmatario Monero; compilazione Xtensa con toolchain esp-rs |
| Monero Oxide / monero-wallet | Revisione Git fissata in Cargo.toml | Costruzione e firma RingCT, CLSAG e Bulletproof+ |
| curve25519-dalek | 4.1.3 | Operazioni sulle curve del firmatario Monero |
| rand_core / rand_chacha | 0.6.4 / 0.3.1 | Interfaccia e generazione di casualità crittografica |
| zeroize | 1.8, risolto da Cargo.lock | Azzeramento dei valori sensibili in Rust |
| Routine C Monero | Tag 0.18.5.4 | AES, Groestl, JH e Skein per Monero |
| CMake / Ninja / Python | Strumenti di sviluppo | Compilazione, cataloghi, validazione e installazione |
| SDL2 / OpenSSL 3 / SQLite | Ambiente di sviluppo | Simulatore desktop e test |

Dipendenze gestite ESP-IDF in [idf_component.yml](esp32/firmware/main/idf_component.yml). Dipendenze Rust in [Cargo.toml](esp32/monero_signer/Cargo.toml) e [Cargo.lock](esp32/monero_signer/Cargo.lock).

## Integrazione Trezor: TrezorCrypto

La tecnologia Trezor utilizzata è la **libreria crittografica TrezorCrypto**, tramite il sottoinsieme incluso in **Trust Wallet Core 4.4.2**, in [esp32/vendor/trezor-crypto-4.4.2](esp32/vendor/trezor-crypto-4.4.2).

Fornisce primitive utilizzate dal firmware, tra cui:

- BIP-39 e lista mnemonica inglese.
- Derivazione gerarchica e operazioni secp256k1, NIST P-256 ed Ed25519 secondo l’integrazione.
- ECDSA, Ed25519, HMAC, PBKDF2, hash e codifiche richieste dai protocolli.
- Routine Cardano della versione fissata.

Fonte esatta, hash dell’archivio upstream e hash dei singoli file sono registrati in [provenance.json](esp32/vendor/trezor-crypto-4.4.2/provenance.json). Il sottoinsieme è conservato senza modifiche; l’adattatore di compilazione è [trezor_crypto.cmake](esp32/cmake/trezor_crypto.cmake).

La compilazione usa `USE_BIP39_CACHE=0` e `USE_BIP32_CACHE=0`, disattivando le cache. L’adattatore POSIX originale per la casualità è sostituito da [trezor_random.cpp](esp32/core/trezor_random.cpp), collegato alla sorgente della piattaforma. I guasti della sorgente non vengono sostituiti con numeri prevedibili.

**BJJ-Cold-Wallet** ricostruisce e valida i pagamenti, applica le regole di accesso e presenta la revisione fisica; TrezorCrypto fornisce le primitive crittografiche utilizzate. Non si tratta dell’SDK completo, del firmware o di una certificazione di un dispositivo Trezor.

Per verificare i file fissati, eseguire da questa cartella:

```sh
python3 esp32/tools/check_trezor_sources.py
```

Il framework nativo macOS Wallet Core e il pacchetto JavaScript/WASM hanno ruoli separati nei riferimenti, nel simulatore e nell’implementazione precedente. Il binario macOS non è una libreria ESP32. Vedi [vendor/README.md](esp32/vendor/README.md).

## Architettura

```text
hardware/
├── esp32/                  # Firmware attuale
│   ├── core/               # Wallet, validazione e firmatari
│   ├── ui/                 # Schermate, approvazione, lingue, icone e font
│   ├── hardware/           # Protocolli e astrazioni dei componenti fisici
│   ├── idf_components/     # Integrazione di servizi e driver con ESP-IDF
│   ├── firmware/           # Progetto principale di compilazione scheda
│   ├── monero_signer/      # Libreria Rust di firma Monero
│   ├── vendor/             # Dipendenze esterne e licenze
│   ├── cmake/              # Configurazione ausiliaria di compilazione
│   ├── simulator/          # Simulatore desktop
│   ├── tests/              # Test e vettori pubblici
│   ├── tools/              # Generazione, verifica, scrittura e pacchetti
│   └── docs/               # Rapporti e documentazione tecnica
├── instaladores/           # ZIP della versione attuale
├── historico/instaladores/ # Versioni precedenti
├── core/                   # Servizi dell’implementazione Python originale
├── hardware/               # Driver dell’implementazione originale
├── ui/                     # Interfaccia Python/MicroPython originale
└── js/                     # Firmatari JavaScript / Wallet Core originali
```

`build` e `build-*` contengono gli output di compilazione. `device-updates`, `device-resets` e `device-backups` conservano i registri di operazioni precedenti sulla scheda. `simulator-wallets` appartiene all’ambiente del simulatore.

Il nucleo ESP32 attuale usa C/C++ e Rust. Le cartelle Python/MicroPython e JavaScript conservano implementazione originale e riferimenti di compatibilità; il loro runtime non è il percorso di firma del firmware C/C++ distribuito.

## Approvazione, chiavi e accesso dei telefoni

Il dispositivo controlla associazione e autorizzazione di ogni connessione. Telefoni autorizzati e bloccati sono elencati nelle impostazioni hardware. Rifiuto, blocco, disconnessione o scadenza non devono produrre una firma approvata.

Prima di firmare, il firmware valida il piano, identifica la wallet e mostra asset, importo, destinatario e commissioni. L’eventuale resto restituito alla wallet è distinto dal destinatario. L’hardware usa seed e chiavi private di spesa; l’app riceve e verifica la firma prima della trasmissione.

Nel firmware distribuito:

- `AIRLOCK_USB_POWER_ONLY=ON`: USB fornisce solo alimentazione durante l’applicazione.
- `CONFIG_ESP_CONSOLE_NONE=y`: console disattivata.
- `CONFIG_USJ_ENABLE_USB_SERIAL_JTAG` disattivato.
- `AIRLOCK_BOARD_AGENT_CONTROL=OFF`: nessun agente USB remoto.
- Wi-Fi e SD/microSD bloccati dalla politica firmware.
- Recupero fisico BOOT/RESET preservato; gli aggiornamenti normali non modificano le eFuse.

I dati USB si disconnettono prima di aprire le wallet o avviare Bluetooth. La modalità ROM di scrittura resta fisicamente accessibile; la politica dell’applicazione non scollega permanentemente le interfacce elettriche.

## Reti, monete e firmatari

Il catalogo condiviso comprende **23 reti e 86 asset**, incluse monete native e 63 token registrati. La [matrice completa reti/token](../app/README.it.md#supported-networks) è nel README dell’app.

| Famiglia | Implementazione e integrazione |
|---|---|
| Bitcoin, Litecoin, Dogecoin, Dash, Bitcoin Cash e eCash | Validazione ingressi/uscite, firma UTXO e regole hash di rete |
| Ethereum e reti EVM | Transazioni legate al chain ID; token ai contratti registrati |
| Solana | SOL e token SPL/Token-2022 registrati; messaggi e conti verificati |
| Cardano | ADA e cinque token registrati, con commissioni e resto in ADA |
| TRON | TRX e contratti TRC-20 registrati |
| XRP Ledger | XRP e asset emessi identificati da valuta/emittente |
| Stellar | XLM e asset identificati da codice/emittente |
| Sui | SUI e USDC con tipi espliciti nella costruzione |
| TON | Wallet V4R2 e USDT, identità jetton-wallet derivata e verificata |
| Monero | Archivio wallet2, firma RingCT CLSAG/Bulletproof+; flusso attuale su stagenet |

I cataloghi indirizzi e token del firmware sono generati da `CoinCatalog.kt` e `Stablecoins.kt` dell’app. I controlli rifiutano cataloghi divergenti. Per generare o verificare, dalla radice:

```sh
python3 hardware/esp32/tools/generate_address_catalog.py --check
python3 hardware/esp32/tools/generate_signing_catalog.py --check
```

Il [rapporto sulle firme](../app/docs/all-assets-signing-2026-11-21.md) spiega copertura locale, limiti di selezione UTXO Cardano e differenza fra firma valida e accettazione della rete.

## Libreria di firma Monero

Firmatario Rust in [esp32/monero_signer](esp32/monero_signer). `monero-wallet` punta alla revisione `a6b46701fb9b331006d7f2f2278bf51f123b1447` di [Monero Oxide](https://github.com/monero-oxide/monero-oxide); `Cargo.lock` fissa la risoluzione delle dipendenze.

La compilazione Xtensa produce `libairlock_monero_signer.a`, collegata tramite `AIRLOCK_MONERO_SIGNER_ARCHIVE`. L’integrazione usa allocatori della piattaforma, azzeramento della memoria e approvazione fisica. Il modulo completo richiede la toolchain Rust Xtensa/ESP32-S3; seguire [BUILD.md](esp32/monero_signer/BUILD.md).

## Compilazione firmware

Requisiti: **ESP-IDF 5.4.2**, toolchain ESP32-S3, CMake, Ninja, Python e libreria Rust Monero già compilata per Xtensa. Con ambiente ESP-IDF caricato e libreria nel percorso seguente, eseguire da questa cartella:

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

L’esempio presume la libreria compilata nella directory indicata. I file `sdkconfig` devono mantenere console e USB Serial/JTAG disattivati; CMake verifica le politiche di distribuzione. Progetto principale in [esp32/firmware](esp32/firmware).

## Test locali e simulatore

I test portabili usano gli stessi firmatari e controllano piani, firme, destinatari/importi modificati, permessi, associazione e stati UI. Gli SDK indipendenti Stellar, TRON, XRPL, Sui e TON sono dipendenze di sviluppo fissate in [tests/protocol-reference/package.json](esp32/tests/protocol-reference/package.json).

Con le dipendenze di sviluppo disponibili, eseguire da questa cartella:

```sh
cmake -S esp32 -B esp32/build-host \
  -DAIRLOCK_MBEDTLS_DIR="$IDF_PATH/components/mbedtls/mbedtls" \
  -DAIRLOCK_LVGL_DIR="$PWD/esp32/firmware/managed_components/lvgl__lvgl"
cmake --build esp32/build-host --parallel
ctest --test-dir esp32/build-host --output-on-failure

cargo test --locked --manifest-path esp32/monero_signer/Cargo.toml
```

Il percorso LVGL presume dipendenze gestite ESP-IDF già risolte. Per l’interfaccia desktop interattiva, vedi [compilazione del simulatore](esp32/README.md#compilar-e-testar-o-simulador). I test usano dati pubblici/sintetici e non dimostrano un trasferimento blockchain reale.

## Firmware installabile

Pacchetto incluso per la versione `2026.11.21-allassets1`:

**[BJJ-Cold-Wallet-ES3C28P-2026-11-21-allassets1.zip](instaladores/BJJ-Cold-Wallet-ES3C28P-2026-11-21-allassets1.zip)**

Le versioni precedenti sono nello [storico degli installatori](historico/instaladores/). La cartella `instaladores` contiene solo lo ZIP attuale.

Nel pacchetto, `instalar.py` scrive i componenti agli indirizzi corretti. `firmware` contiene `airlock_board_check.bin`, bootloader, tabella partizioni, hash SHA-256 e politica release. Gli aggiornamenti normali devono preservare wallet e associazioni; reset/cancellazione è un’operazione separata.

Generare un pacchetto non identifica la versione installata su una specifica scheda. La scrittura richiede l’ingresso fisico in modalità BOOT.


<a id="installation"></a>

### Installazione passo per passo

1. Scarica ed estrai lo ZIP sopra. Mantieni tutti i file insieme; `instalar.py` è l’installatore e `Instalar.command` il collegamento macOS. Servono Python 3 e un cavo USB-C dati.
2. Entra in BOOT: con USB collegato, tieni BOOT premuto, premi e rilascia RESET (RST/EN), poi rilascia BOOT. Senza RESET accessibile, tieni BOOT premuto collegando USB e rilascialo dopo il rilevamento.
3. Apri il terminale nella cartella estratta e prepara le dipendenze:

**macOS e Linux**

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
```

**Windows (PowerShell)**

```powershell
py -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

4. Con la scheda in BOOT, esegui il comando di aggiornamento del tuo sistema:

**macOS e Linux**

```sh
.venv/bin/python instalar.py
```

**Windows (PowerShell)**

```powershell
.\.venv\Scripts\python.exe instalar.py
```

Con più schede, aggiungi `--porta` e la porta corretta: `/dev/cu.usbmodem14101` su macOS, `/dev/ttyACM0` su Linux o `COM3` su Windows.

Per una scheda nuova con tabella partizioni vuota, aggiungi `--placa-nova`. Non usarlo su una wallet configurata. Per controllare pacchetto e tabella senza scrivere, aggiungi `--verificar` al comando della modalità scelta.

5. Attendi la fine e il riavvio. L’aggiornamento normale scrive solo l’applicazione e verifica la compatibilità della tabella, preservando wallet, nome, PIN e associazioni. All’avvio la porta USB scompare: USB alimenta la scheda e l’app usa Bluetooth. Se resta nel bootloader, rilascia BOOT e premi RESET una volta.

Lo ZIP include anche un `README.md` con istruzioni dettagliate e diagnosi.

## Documentazione, manutenzione e licenze

- [Dettagli e storia del port ESP32](esp32/README.md): revisioni precedenti.
- [Provenienza TrezorCrypto](esp32/vendor/trezor-crypto-4.4.2/provenance.json) e [licenze upstream](esp32/vendor/trezor-crypto-4.4.2/crypto/LICENSE).
- [Politica periferiche](esp32/idf_components/airlock_policy/README.md).
- [Standard di commenti e formattazione](../docs/CODE_STYLE.md).
- [Regole firmware distribuito](AGENTS.md).

Il codice derivato da AirLock conserva attribuzione e [licenza GPL-3.0](LICENSE) originali. Le librerie esterne mantengono licenze e avvisi propri. Quando si aggiorna una libreria crittografica o un contratto registrato, documentare versione/provenienza, aggiornare il riferimento ed eseguire i controlli corrispondenti.
