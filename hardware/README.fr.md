[Português](README.md) · [English](README.en.md) · [Español](README.es.md) · **Français** · [简体中文](README.zh.md) · [日本語](README.ja.md) · [Italiano](README.it.md) · [Deutsch](README.de.md)

# 🔐 BJJ-Cold-Wallet — matériel et firmware

[← Présentation du produit](../README.fr.md) · [Application mobile](../app/README.fr.md)

**BJJ-Cold-Wallet** est un portefeuille matériel libre basé sur ESP32-S3. Il génère ou importe la seed sur l’appareil, conserve les clés privées de dépense, contrôle l’accès des téléphones et exige une vérification et approbation physiques avant signature.

L’application communique avec la cold wallet par **Bluetooth Low Energy**. Elle effectue les requêtes internet et la diffusion aux blockchains. La synchronisation n’inclut ni seed ni clés privées de dépense. L’export watch-only Monero peut inclure, avec autorisation, la clé privée de visualisation, qui ne permet pas de dépenser.

## Carte utilisée

La cible actuelle de distribution est la **LCDWIKI ES3C28P à écran tactile capacitif**.

| Composant | Configuration |
|---|---|
| Processeur | ESP32-S3, double cœur Xtensa LX7, jusqu’à 240 MHz |
| Flash | 16 MB |
| PSRAM | 8 MB OPI |
| Écran | IPS 2,8″, contrôleur ILI9341V, interface SPI |
| Résolution native | 240 × 320 |
| Interface actuelle | Paysage, 320 × 240, avec transformation des coordonnées tactiles |
| Tactile | Capacitif FT6336G, interface I²C |
| Communication avec l’application | Bluetooth Low Energy |
| Alimentation et récupération | USB-C ; mode physique BOOT/RESET pour le flash |

Source : [spécifications LCDWIKI](https://www.lcdwiki.com/2.8inch_ESP32-S3_Display). Configuration des pilotes : [esp32/hardware](esp32/hardware) et [esp32/idf_components/airlock_board](esp32/idf_components/airlock_board). [ES3C28P.md](esp32/hardware/ES3C28P.md) conserve brochage et variantes historiques ; ce README décrit l’orientation et la politique USB actuelles.

**ES3N28P est la variante sans tactile.** Ses builds de validation ont leur propre configuration ; le paquet ES3C28P distribué exige le tactile.

### Achat et boîtier

- **[Carte AliExpress — annonce indiquée pour le projet](https://pt.aliexpress.com/item/1005010470966979.html)**. Choisissez ES3C28P tactile et vérifiez les caractéristiques.
- **[Boîtier 3D sur Printables — référence indiquée](https://www.printables.com/model/1188149-enclosure-for-esp32-s3-touch-lcd-2)**. La compatibilité mécanique avec la révision ES3C28P utilisée n’est pas validée ; vérifiez dimensions, fixation et découpes.

## Langages, SDK et bibliothèques

| Technologie | Version/origine | Usage |
|---|---|---|
| C / C++ | Noyau portable compatible C++17 | Pilotes, interface, règles, stockage et signature |
| ESP-IDF | 5.4.2 | SDK Espressif, initialisation et services embarqués |
| FreeRTOS | Fourni par ESP-IDF | Tâches, synchronisation et exécution des services |
| NimBLE | Intégré à ESP-IDF | Bluetooth LE, GATT et appairage |
| LVGL | 9.2.2 | Composants visuels et interaction tactile |
| esp_lcd_ili9341 | 2.0.1 | Pilote du panneau ILI9341V |
| mbedTLS | 3.6.3, fourni par ESP-IDF 5.4.2 | Primitives cryptographiques et protection du stockage |
| TrezorCrypto | Sous-ensemble fixé de Wallet Core 4.4.2 | Dérivation des clés, courbes, hashes et signatures |
| cJSON | 1.7.18, intégré au dépôt | Contrats JSON du protocole local |
| Rust | Crate édition 2021 ; rust-version 1.89 | Signature Monero ; build Xtensa avec esp-rs |
| Monero Oxide / monero-wallet | Révision Git fixée dans Cargo.toml | Construction et signature RingCT, CLSAG et Bulletproof+ |
| curve25519-dalek | 4.1.3 | Opérations de courbe du module Monero |
| rand_core / rand_chacha | 0.6.4 / 0.3.1 | Interface et génération d’aléa cryptographique |
| zeroize | 1.8, résolution dans Cargo.lock | Effacement des valeurs sensibles en Rust |
| Routines Monero C | Tag 0.18.5.4 | AES, Groestl, JH et Skein pour Monero |
| CMake / Ninja / Python | Outils de développement | Compilation, génération des catalogues, validation et installation |
| SDL2 / OpenSSL 3 / SQLite | Environnement de développement | Simulateur et tests sur ordinateur |

Les dépendances ESP-IDF gérées figurent dans [idf_component.yml](esp32/firmware/main/idf_component.yml), celles de Rust dans [Cargo.toml](esp32/monero_signer/Cargo.toml) et [Cargo.lock](esp32/monero_signer/Cargo.lock).

## Intégration Trezor : TrezorCrypto

La technologie Trezor utilisée est la **bibliothèque cryptographique TrezorCrypto**, intégrée via le sous-ensemble de **Trust Wallet Core 4.4.2**, dans [esp32/vendor/trezor-crypto-4.4.2](esp32/vendor/trezor-crypto-4.4.2).

Elle fournit notamment les primitives suivantes :

- BIP-39 et liste anglaise des mots mnémoniques.
- Dérivation hiérarchique et opérations secp256k1, NIST P-256 et Ed25519 selon l’intégration.
- ECDSA, Ed25519, HMAC, PBKDF2, hashes et encodages des protocoles.
- Routines Cardano de la version fixée.

La source exacte, le hash de l’archive upstream et les hashes des fichiers sont consignés dans [provenance.json](esp32/vendor/trezor-crypto-4.4.2/provenance.json). Les sources sont conservées sans modification ; l’adaptateur de compilation est [trezor_crypto.cmake](esp32/cmake/trezor_crypto.cmake).

`USE_BIP39_CACHE=0` et `USE_BIP32_CACHE=0` désactivent les caches concernés. L’adaptateur d’aléa POSIX est remplacé par [trezor_random.cpp](esp32/core/trezor_random.cpp), relié à la source de la plateforme. Une défaillance ne produit pas de nombres prévisibles de remplacement.

**BJJ-Cold-Wallet** reconstruit et valide les paiements, applique les droits d’accès et présente la vérification physique ; TrezorCrypto fournit les primitives cryptographiques. Cette dépendance ne constitue pas le SDK complet, le firmware ou une certification d’un appareil Trezor.

Pour vérifier les fichiers fixés, depuis ce dossier :

```sh
python3 esp32/tools/check_trezor_sources.py
```

Le framework macOS natif Wallet Core et le paquet JavaScript/WASM servent séparément aux références, au simulateur et à l’implémentation historique. Le binaire macOS n’est pas une bibliothèque pour ESP32. Voir [vendor/README.md](esp32/vendor/README.md).

## Architecture

```text
hardware/
├── esp32/                  # Firmware actuel
│   ├── core/               # Portefeuilles, validation et signatures
│   ├── ui/                 # Écrans, approbation, langues, icônes et polices
│   ├── hardware/           # Protocoles et abstractions des composants physiques
│   ├── idf_components/     # Intégration des services et pilotes ESP-IDF
│   ├── firmware/           # Projet principal de compilation de la carte
│   ├── monero_signer/      # Bibliothèque Rust de signature Monero
│   ├── vendor/             # Dépendances tierces et licences
│   ├── cmake/              # Configuration auxiliaire de compilation
│   ├── simulator/          # Simulateur sur ordinateur
│   ├── tests/              # Tests et vecteurs publics
│   ├── tools/              # Génération, vérification, flash et empaquetage
│   └── docs/               # Rapports et documentation technique
├── instaladores/           # ZIP de la version actuelle
├── historico/instaladores/ # Versions précédentes
├── core/                   # Services Python d’origine
├── hardware/               # Pilotes d’origine
├── ui/                     # Interface Python/MicroPython d’origine
└── js/                     # Signatures JavaScript / Wallet Core d’origine
```

`build` et `build-*` contiennent les sorties de compilation. `device-updates`, `device-resets` et `device-backups` conservent les opérations précédentes sur la carte. `simulator-wallets` appartient au simulateur.

Le noyau ESP32 actuel utilise C/C++ et Rust. Les dossiers Python/MicroPython et JavaScript conservent l’implémentation d’origine et les références de compatibilité ; leur runtime n’est pas la voie de signature du firmware C/C++ distribué.

## Approbation, clés et accès des téléphones

L’appareil contrôle l’appairage et l’autorisation de chaque connexion. Les téléphones autorisés ou bloqués figurent dans ses paramètres. Refus, blocage, déconnexion ou expiration ne doivent pas produire de signature approuvée.

Avant signature, le firmware valide le plan, identifie le portefeuille et affiche actif, montant, destinataire et frais. Le change vers le portefeuille est séparé du destinataire. Le matériel utilise seed et clés privées de dépense ; l’application reçoit la signature et la vérifie avant diffusion.

Dans le firmware distribué :

- `AIRLOCK_USB_POWER_ONLY=ON` : USB alimente seulement pendant l’application.
- `CONFIG_ESP_CONSOLE_NONE=y` : console désactivée.
- `CONFIG_USJ_ENABLE_USB_SERIAL_JTAG` désactivé.
- `AIRLOCK_BOARD_AGENT_CONTROL=OFF` : aucun contrôle d’agent distant par USB.
- Wi-Fi et SD/microSD bloqués par la politique.
- Récupération physique BOOT/RESET préservée ; les mises à jour normales ne modifient pas les eFuses.

Les données USB sont déconnectées avant ouverture des portefeuilles ou démarrage Bluetooth. Le mode ROM de flash reste accessible physiquement ; la politique de l’application n’est pas une coupure électrique permanente.

## Réseaux, monnaies et signature

Le catalogue commun comporte **23 réseaux et 86 actifs**, dont les monnaies natives et 63 tokens enregistrés. La [matrice complète par réseau et token](../app/README.fr.md#supported-networks) figure dans le README de l’application.

| Famille | Implémentation et intégration |
|---|---|
| Bitcoin, Litecoin, Dogecoin, Dash, Bitcoin Cash e eCash | Validation des entrées/sorties, signatures UTXO et hashes propres aux réseaux |
| Ethereum et réseaux EVM | Transactions liées à la chain ID ; tokens liés aux contrats enregistrés |
| Solana | SOL et tokens SPL/Token-2022 enregistrés ; messages et comptes vérifiés |
| Cardano | ADA et cinq tokens enregistrés, frais et change en ADA |
| TRON | TRX et contrats TRC-20 enregistrés |
| XRP Ledger | XRP et actifs émis identifiés par monnaie/émetteur |
| Stellar | XLM et actifs identifiés par code/émetteur |
| Sui | SUI et USDC avec types explicites dans les paiements |
| TON | Portefeuille V4R2 et USDT, identité jetton dérivée et vérifiée |
| Monero | Archive wallet2, signature RingCT CLSAG/Bulletproof+ ; stagenet actuel |

Les catalogues d’adresses et tokens du firmware proviennent de `CoinCatalog.kt` et `Stablecoins.kt`. Les contrôles refusent les divergences. Pour générer ou vérifier, depuis la racine du dépôt :

```sh
python3 hardware/esp32/tools/generate_address_catalog.py --check
python3 hardware/esp32/tools/generate_signing_catalog.py --check
```

Le [rapport de signatures](../app/docs/all-assets-signing-2026-11-21.md) décrit la couverture locale, les limites des UTXOs Cardano et la différence entre signature valide et acceptation réseau.

## Bibliothèque de signature Monero

Le module Rust est dans [esp32/monero_signer](esp32/monero_signer). `monero-wallet` utilise la révision `a6b46701fb9b331006d7f2f2278bf51f123b1447` de [Monero Oxide](https://github.com/monero-oxide/monero-oxide) ; `Cargo.lock` fixe la résolution des dépendances.

La compilation Xtensa produit `libairlock_monero_signer.a`, liée via `AIRLOCK_MONERO_SIGNER_ARCHIVE`. L’intégration utilise les allocateurs de plateforme, l’effacement mémoire et l’approbation physique. Le module complet exige le toolchain Rust Xtensa/ESP32-S3 ; suivez [BUILD.md](esp32/monero_signer/BUILD.md).

## Compiler le firmware

Prérequis : **ESP-IDF 5.4.2**, toolchain ESP32-S3, CMake, Ninja, Python et bibliothèque Rust Monero déjà compilée pour Xtensa. Avec l’environnement ESP-IDF chargé et la bibliothèque au chemin suivant, depuis ce dossier :

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

L’exemple suppose que Rust a produit la bibliothèque dans le dossier indiqué. `sdkconfig` doit désactiver console et USB Serial/JTAG ; CMake vérifie les politiques de distribution. Projet principal : [esp32/firmware](esp32/firmware).

## Tests locaux et simulateur

Les tests portables utilisent les mêmes modules de signature et contrôlent plans, signatures, modifications destinataire/montant, permissions, appairages et interface. Les SDK indépendants Stellar, TRON, XRPL, Sui et TON sont fixés dans [tests/protocol-reference/package.json](esp32/tests/protocol-reference/package.json).

Avec les dépendances de développement disponibles, depuis ce dossier :

```sh
cmake -S esp32 -B esp32/build-host \
  -DAIRLOCK_MBEDTLS_DIR="$IDF_PATH/components/mbedtls/mbedtls" \
  -DAIRLOCK_LVGL_DIR="$PWD/esp32/firmware/managed_components/lvgl__lvgl"
cmake --build esp32/build-host --parallel
ctest --test-dir esp32/build-host --output-on-failure

cargo test --locked --manifest-path esp32/monero_signer/Cargo.toml
```

Le chemin LVGL suppose les dépendances gérées ESP-IDF déjà résolues. Pour l’interface interactive, voir la [compilation du simulateur](esp32/README.md#compilar-e-testar-o-simulador). Les données de test sont publiques/synthétiques et ne prouvent pas un transfert réel sur blockchain.

## Firmware prêt à installer

Le paquet fourni pour la version `2026.11.21-allassets1` est :

**[BJJ-Cold-Wallet-ES3C28P-2026-11-21-allassets1.zip](instaladores/BJJ-Cold-Wallet-ES3C28P-2026-11-21-allassets1.zip)**

Les versions précédentes sont dans [l’historique des installateurs](historico/instaladores/). Le dossier `instaladores` contient uniquement le ZIP actuel.

`instalar.py` flashe les composants aux bonnes adresses. `firmware` contient `airlock_board_check.bin`, bootloader, table des partitions, hashes SHA-256 et politique de release. Une mise à jour normale préserve portefeuilles et appairages ; réinitialisation/effacement est une opération séparée.

Générer un paquet n’identifie pas la version installée sur une carte particulière. Le flash exige le mode BOOT physique.


<a id="installation"></a>

### Installation pas à pas

1. Téléchargez et extrayez le ZIP ci-dessus. Gardez tous les fichiers ensemble ; `instalar.py` est l’installateur et `Instalar.command` le raccourci macOS. Utilisez Python 3 et un câble USB-C de données.
2. Passez en BOOT : USB connecté, maintenez BOOT, appuyez puis relâchez RESET (RST/EN), puis relâchez BOOT. Si RESET est inaccessible, maintenez BOOT en branchant USB et relâchez après détection.
3. Ouvrez un terminal dans le dossier extrait et préparez les dépendances :

**macOS et Linux**

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
```

**Windows (PowerShell)**

```powershell
py -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

4. La carte en BOOT, lancez la commande de mise à jour de votre système :

**macOS et Linux**

```sh
.venv/bin/python instalar.py
```

**Windows (PowerShell)**

```powershell
.\.venv\Scripts\python.exe instalar.py
```

Avec plusieurs cartes, ajoutez `--porta` et le bon port : `/dev/cu.usbmodem14101` sous macOS, `/dev/ttyACM0` sous Linux ou `COM3` sous Windows.

Pour une carte neuve dont la table de partitions est vide, ajoutez `--placa-nova`. Ne l’utilisez pas sur un portefeuille configuré. Pour vérifier le paquet et la table sans écriture, ajoutez `--verificar` à la commande du mode choisi.

5. Attendez la fin et le redémarrage. La mise à jour normale écrit seulement l’application et vérifie la compatibilité de la table, en conservant portefeuilles, nom, PIN et appairages. Au démarrage, le port USB disparaît : USB alimente la carte et l’application utilise Bluetooth. Si le chargeur reste actif, relâchez BOOT et appuyez une fois sur RESET.

Le ZIP contient aussi un `README.md` avec des instructions détaillées et un diagnostic.

## Documentation, maintenance et licences

- [Détails et historique du port ESP32](esp32/README.md) : révisions précédentes.
- [Provenance TrezorCrypto](esp32/vendor/trezor-crypto-4.4.2/provenance.json) et [licences upstream](esp32/vendor/trezor-crypto-4.4.2/crypto/LICENSE).
- [Politique des périphériques](esp32/idf_components/airlock_policy/README.md).
- [Règles de commentaires et formatage](../docs/CODE_STYLE.md).
- [Règles du firmware distribué](AGENTS.md).

Le code AirLock conserve son auteur et sa [licence GPL-3.0](LICENSE). Les bibliothèques tierces conservent leurs licences et mentions. Toute mise à jour cryptographique ou de contrat doit enregistrer version/origine, actualiser la référence et exécuter les contrôles.
