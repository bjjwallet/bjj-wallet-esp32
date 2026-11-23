[Português](README.md) · [English](README.en.md) · [Español](README.es.md) · [Français](README.fr.md) · [简体中文](README.zh.md) · [日本語](README.ja.md) · **Italiano** · [Deutsch](README.de.md)

# 📱 App mobile BJJ-Cold-Wallet

[← Presentazione del prodotto](../README.it.md) · [Hardware e firmware](../hardware/README.it.md)

**BJJ-App-Wallet** è l’app Android e iOS di **BJJ-Cold-Wallet**. Offre l’interfaccia per conti e trasferimenti, comunicazione Bluetooth con la hardware wallet, interrogazioni della rete e invio di transazioni firmate.

L’app deriva dall’evoluzione di AirLock. L’interfaccia attuale usa Kotlin/Compose Multiplatform; i nomi interni `AirLockShared`, `AirLock.xcodeproj` e il namespace `pub.airlock` sono mantenuti nel codice.

## Responsabilità e flusso di sicurezza

L’app sincronizza conti e indirizzi, gestisce più wallet salvate, consulta saldi, legge QR del destinatario e prepara i dati del trasferimento. Le firme di spesa vengono prodotte sulla cold wallet dopo revisione e approvazione fisica.

```text
L’app prepara il pagamento
        ↓ Bluetooth / RPC locale
La cold wallet verifica i dati e richiede approvazione fisica
        ↓ Firma prodotta sull’hardware
L’app verifica la firma
        ↓ API / RPC della rete
Trasmissione della transazione
```

**L’app non importa né memorizza il seed o le chiavi private di spesa.** Elabora però dati sensibili: PIN, impostazioni di accesso e informazioni del vault. La modalità Monero di sola osservazione può conservare una **chiave privata di visualizzazione**, che permette di seguire i movimenti ma non di firmare spese. Rimane nel vault cifrato e non viene inviata al nodo remoto.

Il PIN dell’app protegge lo sblocco locale. Il PIN della cold wallet autentica la connessione e non viene memorizzato dall’app. La Transaction Phrase è una protezione separata; i nuovi percorsi di firma la richiedono sull’hardware. Consulta [transfer-signing-flow.md](docs/transfer-signing-flow.md) per ciascun flusso.

## Linguaggi, piattaforme e strumenti

| Tecnologia | Versione/configurazione del progetto | Funzione |
|---|---|---|
| Kotlin | 2.3.21 | Regole, rete, Bluetooth e interfaccia condivisa |
| Kotlin Multiplatform | Plugin Kotlin 2.3.21 | Codice condiviso Android e iOS |
| Compose Multiplatform | 1.10.0 | Interfaccia dichiarativa e risorse condivise |
| Material 3 | 1.9.0 | Componenti visivi |
| Swift / SwiftUI | SDK Xcode | Avvio dell’app e integrazioni native Apple |
| Android Gradle Plugin | 8.11.2 | Compilazione Android |
| Android SDK | compile/target 36; minimo 29 | Android 10 o successivo |
| iOS | Deployment target 16.0 | iPhone/iPad; compilazione con Xcode |
| Java | JVM 17 | Strumenti di compilazione Android |
| Gradle Wrapper | Incluso nel progetto | Attività di compilazione riproducibili |

Le versioni dichiarate sono in [libs.versions.toml](gradle/libs.versions.toml), [shared/build.gradle.kts](shared/build.gradle.kts) e [androidApp/build.gradle.kts](androidApp/build.gradle.kts).

## Librerie e SDK

| Libreria / SDK | Versione | Funzione |
|---|---|---|
| Kotlin Coroutines | 1.10.2 | Operazioni asincrone, Flow e StateFlow |
| Kotlinx Serialization | 1.9.0 | Serializzazione JSON di modelli e contratti |
| Ktor Client | 3.4.3 | HTTP, API/RPC; motore OkHttp su Android e Darwin su iOS |
| Kable | 0.41.0 | Connessioni Bluetooth Low Energy |
| Koin | 4.1.0 | Iniezione delle dipendenze |
| Lifecycle / ViewModel | 2.9.6 | Stato delle schermate e ciclo di vita |
| bitcoin-kmp, ACINQ | 0.32.0 | Strutture Bitcoin/UTXO, hash, indirizzi e verifica firme |
| secp256k1-kmp JNI | 0.24.0 | Backend secp256k1 su Android e nei test JVM |
| KotlinCrypto SHA-3 | 0.8.0 | Keccak e hash dei protocolli |
| Kotlin Bignum | 0.3.10 | Supporto aritmetico a precisione arbitraria |
| Tink Android | 1.10.0 | Funzioni crittografiche per l’integrazione Android |
| Coil / Coil SVG | 3.3.0 | Caricamento immagini e icone |
| Material Icons | 1.7.3 | Icone dell’interfaccia |
| Activity Compose | 1.10.1 | Integrazione Compose con Activity Android |
| ZXing Core / Embedded | 3.5.3 / 4.3.0 | Scansione QR su Android |
| Android Keystore / BiometricPrompt | SDK Android | Protezione delle chiavi locali e autenticazione nativa |
| CryptoKit / Keychain / LocalAuthentication | SDK Apple | Vault cifrato e autenticazione iOS |
| AVFoundation | SDK Apple | Acquisizione e scansione QR su iOS |
| Runtime Monero JS/WASM | Incluso nelle risorse locali | Scanner di sola osservazione con runtime WebView/WKWebView |

Il runtime Monero e le sue licenze si trovano in [composeResources/files/monero](shared/src/commonMain/composeResources/files/monero). Segue la wallet senza ricevere il seed o la chiave privata di spesa.

## Struttura del progetto

```text
app/
├── androidApp/        # Avvio Android, permessi e lettore QR
├── iosApp/            # Progetto Xcode, SwiftUI, Keychain e test UI
├── shared/
│   └── src/
│       ├── commonMain/       # Funzionalità condivise
│       ├── androidMain/      # Adattatori nativi Android
│       ├── iosMain/          # Adattatori nativi iOS
│       ├── commonTest/       # Test condivisi
│       ├── androidUnitTest/  # Test JVM e verifiche crittografiche
│       └── test/             # Vettori pubblici di riferimento
├── gradle/           # Wrapper e catalogo versioni
├── scripts/          # Verifiche e generazione risorse
├── docs/             # Architettura, contratti e risultati dei test
└── hardware-patches/ # Registri degli adattamenti per l’integrazione hardware
```

All’interno di `shared/src/commonMain/kotlin/pub/airlock`:

| Cartella | Responsabilità |
|---|---|
| `domain` | Modelli, cataloghi, interfacce, regole e valori monetari esatti |
| `data` | Vault, repository, integrazioni di rete e adattatore cold wallet |
| `ble` | Trasporto Bluetooth e messaggi del protocollo locale |
| `presentation` | ViewModel, stato delle schermate e coordinamento dei flussi |
| `ui` | Schermate Compose, componenti, temi e presentazione errori |
| `di` | Configurazione delle dipendenze dell’app |

<a id="supported-networks"></a>

## Reti e monete compatibili

Il catalogo attuale comprende **23 reti, 23 monete native e 63 token**, per un totale di **86 asset**. Lo stesso simbolo su due reti rappresenta integrazioni distinte.

| Rete | Moneta nativa | Token registrati |
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
| Monero — stagenet nel flusso attuale | XMR | — |
| Stellar | XLM | USDC, BRLP, BRZ |
| HyperEVM | HYPE | USDC, LINK |
| Robinhood Chain | ETH | USDG |

Contratti, emittenti, mint e precisioni sono definiti in [CoinCatalog.kt](shared/src/commonMain/kotlin/pub/airlock/domain/CoinCatalog.kt) e [Stablecoins.kt](shared/src/commonMain/kotlin/pub/airlock/domain/Stablecoins.kt). Questi file alimentano anche i cataloghi del firmware.

Le firme di tutti gli 86 asset sono state verificate localmente. Ciò non dimostra la conferma di un trasferimento reale su ogni blockchain. Il flusso Monero attuale usa stagenet. La selezione dei token Cardano usa UTXO di soli ADA o contenenti soltanto il token selezionato; gli UTXO con altri asset restano intatti. Vedi [matrice delle firme e limiti](docs/all-assets-signing-2026-11-21.md).

## API, RPC e trasmissione

L’app separa le interrogazioni ai provider dalla costruzione e verifica delle transazioni per protocollo:

- **UTXO:** Blockstream Esplora e mempool.space per Bitcoin; Freedom Street per le monete coperte dal relativo adattatore.
- **EVM:** RPC della rete corrispondente, inclusi i token registrati.
- **Solana:** RPC Solana per SOL e token supportati.
- **Cardano:** API Koios per preparazione e invio.
- **TRON:** TronGrid per TRX e token TRC-20.
- **XRP Ledger:** API XRPL JSON-RPC per XRP e asset emessi registrati.
- **Stellar:** API Horizon.
- **Sui:** GraphQL per interrogazioni, preparazione ed esecuzione.
- **TON:** API TON Center per TON e USDT.
- **Monero:** Scanner e flusso di sola osservazione dedicati, firma sull’hardware e stagenet nel firmware attuale.

AirLock e Moralis restano disponibili per le operazioni coperte dai loro adattatori. Scegliere un explorer Bitcoin non sostituisce l’RPC di un’altra rete. SEND richiede un percorso di invio implementato per l’asset; la sola lettura del saldo non autorizza la trasmissione.

Le eventuali credenziali dei provider vengono configurate dall’utente e protette nel vault. Percorsi e capacità sono in [NetworkSettings.kt](shared/src/commonMain/kotlin/pub/airlock/domain/NetworkSettings.kt) e [data/network](shared/src/commonMain/kotlin/pub/airlock/data/network).

## Persistenza ed esperienza utente

Il vault è JSON versionato con cifratura autenticata AES-256-GCM e scritture atomiche. Android Keystore o Keychain protegge la chiave locale; gli errori di autenticazione non creano silenziosamente una wallet vuota.

Sono supportate più wallet salvate, isolamento di conti e transazioni pendenti per wallet, biometria nativa e otto lingue: portoghese, inglese, spagnolo, francese, cinese semplificato, giapponese, italiano e tedesco. I valori monetari usano la precisione esatta dell’asset senza conversione in virgola mobile. La modalità test può disattivare il limite visivo Amount/saldo; non crea fondi e non forza l’accettazione sulla rete.

Sincronizzare un’altra wallet conserva le precedenti. Riavviare la sincronizzazione cancella il vault locale dopo conferma; non cancella la cold wallet né annulla transazioni già trasmesse.

## Compilazione per Android

Requisiti: JDK 17, Android SDK 36 e dipendenze Gradle. Eseguire da questa cartella:

```sh
./gradlew :androidApp:assembleDebug
./gradlew :shared:testDebugUnitTest
```

L’APK viene scritto in [`androidApp/build/outputs/apk/debug/androidApp-debug.apk`](androidApp/build/outputs/apk/debug/androidApp-debug.apk).

Identificatori: `pub.airlock.kmp.dev` per debug e `pub.airlock.kmp` per release. Entrambi usano integrazioni reali; gli adattatori simulati dei test non sostituiscono i servizi di produzione. La distribuzione release richiede la configurazione della firma Android.

## Compilazione per iOS

Requisiti: macOS, Xcode e dipendenze Kotlin/Native. Aprire [`iosApp/AirLock.xcodeproj`](iosApp/AirLock.xcodeproj) e selezionare lo schema **AirLock**. Xcode incorpora il framework Kotlin `AirLockShared` tramite Gradle, senza CocoaPods. Configurare il team di firma per un dispositivo fisico.

```sh
./gradlew :shared:compileKotlinIosSimulatorArm64

xcodebuild -project iosApp/AirLock.xcodeproj -scheme AirLock \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath iosApp/DerivedData build
```

## Test, documentazione e manutenzione

I test usano vettori pubblici, trasporti HTTP simulati e firme dei firmatari del firmware. Il successo locale non dimostra l’accettazione da parte di un provider reale e non sostituisce la verifica fisica di Bluetooth, touch e approvazione sul dispositivo.

- [Architettura e contesto integrato](docs/BJJ_AIRLOCK.md)
- [Migrazione e contratto di integrazione](docs/MIGRATION.md)
- [Flusso di firma](docs/transfer-signing-flow.md)
- [Firma UTXO](docs/UTXO_SIGNING.md)
- [Catalogo completo: firme e limiti](docs/all-assets-signing-2026-11-21.md)
- [Inserimento importo](docs/amount-mask.md) e [QR destinatario](docs/recipient-qr.md)
- [Standard di commenti e formattazione](../docs/CODE_STYLE.md)
- [Documentazione precedente per riferimento storico](docs/README-anterior.md)

Per contribuire, preferire modifiche piccole, test comportamentali e aggiornamenti dei contratti quando cambia il protocollo. Il materiale derivato da AirLock conserva attribuzione e licenza originali: [GPL-3.0](LICENSE), [NOTICE](NOTICE.md) e [licenze delle risorse](docs/licenses).
