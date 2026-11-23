[Português](README.md) · [English](README.en.md) · [Español](README.es.md) · **Français** · [简体中文](README.zh.md) · [日本語](README.ja.md) · [Italiano](README.it.md) · [Deutsch](README.de.md)

# 📱 Application mobile BJJ-Cold-Wallet

[← Présentation du produit](../README.fr.md) · [Matériel et firmware](../hardware/README.fr.md)

**BJJ-App-Wallet** est l’application compagnon Android/iOS de **BJJ-Cold-Wallet**. Elle fournit l’interface des comptes et transferts, la communication Bluetooth avec le portefeuille matériel, les requêtes réseau et la diffusion des transactions signées.

L’application s’inscrit dans l’évolution d’AirLock. L’interface actuelle utilise Kotlin/Compose Multiplatform ; les noms internes `AirLockShared`, `AirLock.xcodeproj` et l’espace de noms `pub.airlock` sont conservés.

## Responsabilités et sécurité

L’application synchronise comptes et adresses, gère plusieurs portefeuilles enregistrés, consulte les soldes, lit les QR des destinataires et prépare les transferts. Le matériel signe les dépenses après vérification et approbation physique.

```text
L’application prépare le paiement
        ↓ Bluetooth / RPC local
La cold wallet vérifie les données et demande l’approbation physique
        ↓ Signature produite par le matériel
L’application vérifie la signature
        ↓ API / RPC du réseau
Transaction diffusée
```

**L’application n’importe ni ne stocke la seed ou les clés privées de dépense.** Elle doit traiter des données sensibles : PIN, paramètres d’accès et données du coffre. Le mode watch-only Monero peut conserver une **clé privée de visualisation**, permettant de suivre les mouvements, pas de signer des dépenses. Elle reste dans le coffre chiffré et n’est pas envoyée au nœud distant.

Le PIN de l’application protège le déverrouillage local. Le PIN d’accès de la cold wallet authentifie la connexion et n’est pas enregistré par l’application. La Transaction Phrase est une protection distincte ; les nouvelles procédures de signature la demandent sur le matériel. Voir [transfer-signing-flow.md](docs/transfer-signing-flow.md).

## Langages, plateformes et outils

| Technologie | Version/configuration | Usage |
|---|---|---|
| Kotlin | 2.3.21 | Règles, réseau, Bluetooth et interface partagée |
| Kotlin Multiplatform | Plugin Kotlin 2.3.21 | Code commun Android et iOS |
| Compose Multiplatform | 1.10.0 | Interface déclarative et ressources communes |
| Material 3 | 1.9.0 | Composants visuels |
| Swift / SwiftUI | SDK Xcode | Point d’entrée et intégrations natives Apple |
| Android Gradle Plugin | 8.11.2 | Compilation Android |
| Android SDK | compile/target 36 ; minimum 29 | Android 10 ou ultérieur |
| iOS | Deployment target 16.0 | iPhone/iPad ; compilation Xcode |
| Java | JVM 17 | Compilation et outils Android |
| Gradle Wrapper | Inclus dans le projet | Tâches de compilation reproductibles |

Les versions déclarées figurent dans [libs.versions.toml](gradle/libs.versions.toml), [shared/build.gradle.kts](shared/build.gradle.kts) et [androidApp/build.gradle.kts](androidApp/build.gradle.kts).

## Bibliothèques et SDK

| Bibliothèque / SDK | Version | Rôle |
|---|---|---|
| Kotlin Coroutines | 1.10.2 | Opérations asynchrones, Flow et StateFlow |
| Kotlinx Serialization | 1.9.0 | Sérialisation JSON des modèles et contrats |
| Ktor Client | 3.4.3 | HTTP et APIs/RPCs ; OkHttp sur Android, Darwin sur iOS |
| Kable | 0.41.0 | Connexions Bluetooth Low Energy |
| Koin | 4.1.0 | Injection de dépendances |
| Lifecycle / ViewModel | 2.9.6 | État et cycle de vie des écrans |
| bitcoin-kmp, ACINQ | 0.32.0 | Structures Bitcoin/UTXO, hashes, adresses et vérification des signatures |
| secp256k1-kmp JNI | 0.24.0 | Backend secp256k1 Android et tests JVM |
| KotlinCrypto SHA-3 | 0.8.0 | Keccak et hashes des protocoles |
| Kotlin Bignum | 0.3.10 | Arithmétique à précision arbitraire |
| Tink Android | 1.10.0 | Fonctions cryptographiques Android |
| Coil / Coil SVG | 3.3.0 | Chargement des images et icônes |
| Material Icons | 1.7.3 | Icônes d’interface |
| Activity Compose | 1.10.1 | Intégration Compose avec l’Activity Android |
| ZXing Core / Embedded | 3.5.3 / 4.3.0 | Lecture des QR sur Android |
| Android Keystore / BiometricPrompt | SDK Android | Protection de la clé locale et authentification native |
| CryptoKit / Keychain / LocalAuthentication | SDK Apple | Coffre chiffré et authentification iOS |
| AVFoundation | SDK Apple | Capture et lecture des QR sur iOS |
| Runtime Monero JS/WASM | Intégré aux ressources locales | Scanner watch-only exécuté dans WebView/WKWebView |

Le runtime Monero et ses licences sont dans [composeResources/files/monero](shared/src/commonMain/composeResources/files/monero). Il suit le portefeuille sans recevoir la seed ou la clé privée de dépense.

## Structure du projet

```text
app/
├── androidApp/        # Entrée Android, permissions et lecteur QR
├── iosApp/            # Projet Xcode, SwiftUI, Keychain et tests d’interface
├── shared/
│   └── src/
│       ├── commonMain/       # Fonctionnalités partagées
│       ├── androidMain/      # Adaptateurs natifs Android
│       ├── iosMain/          # Adaptateurs natifs iOS
│       ├── commonTest/       # Tests partagés
│       ├── androidUnitTest/  # Tests JVM et vérifications cryptographiques
│       └── test/             # Vecteurs publics de référence
├── gradle/           # Wrapper et catalogue des versions
├── scripts/          # Vérifications et génération des ressources
├── docs/             # Architecture, contrats et résultats de tests
└── hardware-patches/ # Historique des adaptations d’intégration matérielle
```

Dans `shared/src/commonMain/kotlin/pub/airlock` :

| Dossier | Responsabilité |
|---|---|
| `domain` | Modèles, catalogues, interfaces, règles et montants exacts |
| `data` | Coffre, dépôts, intégrations réseau et adaptateur matériel |
| `ble` | Transport Bluetooth et messages du protocole local |
| `presentation` | ViewModel, état des écrans et coordination des flux |
| `ui` | Écrans Compose, composants, thèmes et présentation des erreurs |
| `di` | Assemblage des dépendances |

<a id="supported-networks"></a>

## Réseaux et monnaies compatibles

Le catalogue comprend **23 réseaux, 23 monnaies natives et 63 tokens**, soit **86 actifs**. Un même symbole sur deux réseaux correspond à des intégrations distinctes.

| Réseau | Monnaie native | Tokens enregistrés |
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
| Monero — stagenet pour le flux Monero actuel | XMR | — |
| Stellar | XLM | USDC, BRLP, BRZ |
| HyperEVM | HYPE | USDC, LINK |
| Robinhood Chain | ETH | USDG |

Contrats, émetteurs, mints et précisions sont définis dans [CoinCatalog.kt](shared/src/commonMain/kotlin/pub/airlock/domain/CoinCatalog.kt) et [Stablecoins.kt](shared/src/commonMain/kotlin/pub/airlock/domain/Stablecoins.kt), qui alimentent aussi les catalogues du firmware.

Les signatures des 86 actifs ont été vérifiées localement. Cela ne prouve pas une confirmation réelle sur chaque blockchain. Monero utilise stagenet. Cardano sélectionne des UTXOs d’ADA pur ou contenant uniquement le token choisi ; les UTXOs mélangés à d’autres actifs restent intacts. Voir la [matrice de signatures et ses limites](docs/all-assets-signing-2026-11-21.md).

## APIs, RPCs et diffusion

L’application distingue les requêtes aux fournisseurs de la construction et vérification des transactions par protocole :

- **UTXO :** Blockstream Esplora et mempool.space pour Bitcoin ; Freedom Street pour les monnaies couvertes.
- **EVM :** RPC propre à chaque réseau et à ses tokens enregistrés.
- **Solana :** RPC Solana pour SOL et les tokens pris en charge.
- **Cardano :** API Koios pour préparer et soumettre.
- **TRON :** TronGrid pour TRX et les tokens TRC-20.
- **XRP Ledger :** API JSON-RPC XRPL pour XRP et les actifs émis enregistrés.
- **Stellar :** API Horizon.
- **Sui :** GraphQL pour consulter, préparer et exécuter.
- **TON :** APIs TON Center pour TON et USDT.
- **Monero :** Scanner et flux watch-only dédiés, signature matérielle et stagenet dans le firmware actuel.

AirLock et Moralis restent disponibles pour les opérations de leurs adaptateurs. Choisir un explorateur Bitcoin ne remplace pas le RPC d’un autre réseau. SEND exige une route d’envoi implémentée pour l’actif ; consulter un solde ne suffit pas à autoriser une diffusion.

Les identifiants des fournisseurs sont configurés par l’utilisateur si nécessaire et protégés dans le coffre. Routes et capacités : [NetworkSettings.kt](shared/src/commonMain/kotlin/pub/airlock/domain/NetworkSettings.kt) et [data/network](shared/src/commonMain/kotlin/pub/airlock/data/network).

## Persistance et utilisation

Le coffre est un JSON versionné avec chiffrement authentifié AES-256-GCM et écritures atomiques. Android Keystore ou Keychain protège sa clé ; un échec d’authentification ne crée pas silencieusement un portefeuille vide.

L’application propose plusieurs portefeuilles, l’isolation des comptes et transactions en attente par portefeuille, la biométrie native et huit langues : portugais, anglais, espagnol, français, chinois simplifié, japonais, italien et allemand. Les montants utilisent la précision exacte de l’actif sans virgule flottante. Le mode test peut désactiver la limite visuelle Amount/solde ; il ne crée pas de fonds et ne force pas l’acceptation par un réseau.

Synchroniser un autre portefeuille conserve les précédents. Redémarrer la synchronisation efface le coffre local après confirmation ; cela n’efface pas la cold wallet et n’annule pas les transactions déjà diffusées.

## Compiler pour Android

Prérequis : JDK 17, Android SDK 36 et dépendances Gradle. Depuis ce dossier :

```sh
./gradlew :androidApp:assembleDebug
./gradlew :shared:testDebugUnitTest
```

L’APK est généré dans [`androidApp/build/outputs/apk/debug/androidApp-debug.apk`](androidApp/build/outputs/apk/debug/androidApp-debug.apk).

Les identifiants sont `pub.airlock.kmp.dev` pour debug et `pub.airlock.kmp` pour release. Les deux utilisent les intégrations réelles ; les adaptateurs simulés des tests ne remplacent pas les services de production. Une release distribuée exige la configuration de signature Android.

## Compiler pour iOS

Prérequis : macOS, Xcode et dépendances Kotlin/Native. Ouvrez [`iosApp/AirLock.xcodeproj`](iosApp/AirLock.xcodeproj), scheme **AirLock**. Xcode intègre le framework Kotlin `AirLockShared` via Gradle, sans CocoaPods. Configurez l’équipe de signature pour un appareil physique.

```sh
./gradlew :shared:compileKotlinIosSimulatorArm64

xcodebuild -project iosApp/AirLock.xcodeproj -scheme AirLock \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath iosApp/DerivedData build
```

## Tests, documentation et maintenance

Les tests utilisent des vecteurs publics, des transports HTTP simulés et des signatures du firmware. Une réussite locale ne prouve pas l’acceptation d’un fournisseur réel et ne remplace pas les essais physiques Bluetooth, tactiles et d’approbation.

- [Architecture et contexte intégré](docs/BJJ_AIRLOCK.md)
- [Contrat de migration et intégration](docs/MIGRATION.md)
- [Flux de signature](docs/transfer-signing-flow.md)
- [Signature UTXO](docs/UTXO_SIGNING.md)
- [Catalogue complet : signatures et limites](docs/all-assets-signing-2026-11-21.md)
- [Saisie des montants](docs/amount-mask.md) et [QR du destinataire](docs/recipient-qr.md)
- [Règles de commentaires et formatage](../docs/CODE_STYLE.md)
- [Documentation antérieure conservée comme historique](docs/README-anterior.md)

Pour contribuer, privilégiez les petits changements, les tests de comportement et la mise à jour des contrats lorsque le protocole évolue. Les ressources AirLock conservent leur auteur et licence : [GPL-3.0](LICENSE), [NOTICE](NOTICE.md) et [licences des ressources](docs/licenses).
