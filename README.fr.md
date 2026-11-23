[Português](README.md) · [English](README.en.md) · [Español](README.es.md) · **Français** · [简体中文](README.zh.md) · [日本語](README.ja.md) · [Italiano](README.it.md) · [Deutsch](README.de.md)

<picture>
  <source media="(max-width: 640px)" srcset="docs/assets/hero-mobile.png">
  <img src="docs/assets/hero.png" alt="BJJ-Wallet — portefeuilles matériels abordables, code ouvert et collaboration" width="100%">
</picture>

# BJJ-Cold-Wallet

<a id="testing"></a>

## 🧪 Portefeuille en phase de test

**BJJ-Cold-Wallet est en phase de test et de développement**. Nous avons besoin de la communauté pour valider les parcours de l’application et du matériel, repérer les anomalies et améliorer l’expérience.

**Aidez-nous à tester !** Essayez la configuration, la connexion Bluetooth, la synchronisation, la vérification et la signature des transactions sur la cold wallet. Partagez vos problèmes et suggestions auprès de [l’organisation BJJ-Wallet sur GitHub](https://github.com/bjjwallet), en précisant le modèle du téléphone, les versions de l’application et du firmware et les étapes de reproduction.

Privilégiez les réseaux de test et les portefeuilles créés pour les essais. Ne partagez jamais votre seed, vos clés privées, votre PIN ou votre Transaction Phrase dans les rapports, captures d’écran ou journaux.

**Une cold wallet créée par des développeurs, pour des développeurs. Ouverte à tous.**

Portefeuille matériel ESP32-S3 avec écran tactile, approbation des transferts sur l’appareil et communication **Bluetooth Low Energy** avec une application Android et iOS.

<p align="center">
  <img src="docs/assets/badge-open-source.svg" alt="Code ouvert" height="28">
  <img src="docs/assets/badge-affordable.svg" alt="Matériel abordable" height="28">
  <img src="docs/assets/badge-community.svg" alt="Construit ensemble" height="28">
</p>

## 👋 Qui sommes-nous ?

Nous sommes **BJJ-Wallet**, un groupe de développeurs qui souhaite démocratiser les cold wallets grâce à du matériel abordable, des logiciels libres et au partage des connaissances.

**BJJ-Cold-Wallet** concrétise cette idée : un portefeuille fonctionnel que la communauté peut étudier, construire et améliorer. Chacun doit pouvoir comprendre ce qui fonctionne dans l’appareil qui protège ses monnaies.

Nos principes sont l’accessibilité, la transparence, l’ingénierie de sécurité et la collaboration. Développeurs, makers, designers et testeurs sont les bienvenus : lisez le code, questionnez les choix, signalez les problèmes et améliorez le projet.

Découvrez [BJJ-Wallet sur GitHub](https://github.com/bjjwallet).

[💚 Soutenez BJJ-Cold-Wallet](#support-project)

## 📱 Application mobile

L’application compagnon de **BJJ-Cold-Wallet** fonctionne sur Android et iOS. Elle se connecte en Bluetooth, synchronise les données nécessaires au suivi des comptes, consulte les soldes, prépare les transferts et diffuse les transactions signées par le matériel.

Déroulement d’un transfert :

1. L’utilisateur choisit le réseau, l’actif, le destinataire et le montant.
2. L’application demande une signature à la cold wallet par Bluetooth.
3. La cold wallet affiche les détails et exige une approbation physique.
4. Le matériel signe et renvoie le résultat.
5. L’application vérifie la signature et transmet les données signées via l’API, le RPC ou l’interface du réseau concerné.

**La seed et les clés privées de dépense restent dans la cold wallet. L’application ne demande ni n’importe la seed pour fonctionner.** Elle traite les identifiants d’accès ; le mode watch-only Monero peut conserver la clé privée de visualisation dans le coffre chiffré. Cette clé permet de consulter les mouvements, pas d’autoriser des dépenses.

Le catalogue comprend **23 réseaux et 86 actifs : 23 monnaies natives et 63 tokens**. Il inclut Bitcoin, Ethereum et les réseaux EVM, Solana, Cardano, TRON, XRP Ledger, TON, Sui, Stellar, Litecoin, Dogecoin, Bitcoin Cash, Dash, eCash et Monero. Le firmware actuel utilise **stagenet** pour Monero. La matrice par réseau et token figure dans le README de l’application.

L’application utilise **Kotlin Multiplatform et Compose Multiplatform**, avec des intégrations natives Android et iOS.

➡️ **[Application : architecture, langages, bibliothèques, réseaux et compilation](app/README.fr.md)**

## 🔐 Matériel

BJJ-Cold-Wallet utilise la carte **LCDWIKI ES3C28P à écran tactile capacitif**, basée sur **ESP32-S3**, avec **16 MB de flash**, **8 MB de PSRAM** et un écran **IPS de 2,8 pouces**. Sa résolution native est 240 × 320 ; l’interface actuelle fonctionne en paysage à 320 × 240. Voir la [documentation du fabricant](https://www.lcdwiki.com/2.8inch_ESP32-S3_Display).

Le firmware utilise **C/C++ avec ESP-IDF**, une interface **LVGL** et une bibliothèque **Rust** pour signer les transactions Monero. La cryptographie comprend **TrezorCrypto**, provenant de Trust Wallet Core 4.4.2, avec versions et provenance consignées dans le projet.

La cold wallet contrôle l’accès des téléphones, présente les données à approuver et signe. Dans l’application distribuée, USB alimente l’appareil et Bluetooth assure la communication. Wi-Fi et microSD sont bloqués par le firmware.

### 🛒 Carte et boîtier

- **[Acheter la carte — annonce AliExpress indiquée](https://pt.aliexpress.com/item/1005010470966979.html)**. Vérifiez la variante **ES3C28P tactile** ; ES3N28P est la version sans tactile.
- **[Boîtier à imprimer en 3D — référence Printables](https://www.printables.com/model/1188149-enclosure-for-esp32-s3-touch-lcd-2)**. Vérifiez dimensions, fixation et accès aux connecteurs avant impression ; la compatibilité mécanique de ce modèle n’a pas encore été validée par le projet.

➡️ **[Matériel : carte, TrezorCrypto, bibliothèques, firmware et installation](hardware/README.fr.md)**

Le projet est en développement. Consultez les [résultats et limites des tests de signature](app/docs/all-assets-signing-2026-11-21.md), les [règles de code](docs/CODE_STYLE.md) et les licences de l’[application](app/LICENSE) et du [matériel](hardware/LICENSE).

<a id="support-project"></a>

## 💚 Aidez-nous à faire vivre cette idée

Nous voulons démocratiser les cold wallets avec du matériel abordable et du code ouvert. Votre contribution aide à poursuivre le développement de BJJ-Cold-Wallet et à faire vivre cette idée.

**Nous avons actuellement besoin d’aide pour payer les frais d’inscription aux boutiques et publier les applications Android et iOS.**

### Choisissez le réseau avant de donner

| USDT · Ethereum | USDT0 · Polygon |
|:---:|:---:|
| <img src="docs/donate/usdt-ethereum.png" alt="QR code de l’adresse pour USDT · Ethereum" width="220"> | <img src="docs/donate/usdt0-polygon.png" alt="QR code de l’adresse pour USDT0 · Polygon" width="220"> |
| Réseau: **Ethereum** | Réseau: **Polygon** |

Les deux QR codes contiennent uniquement la même adresse. Sélectionnez le jeton et le réseau indiqués dans votre portefeuille avant l’envoi.

**Adresse de don**

Sur GitHub, utilisez le bouton de copie dans le coin du bloc ci-dessous. L’adresse est la même sur les deux réseaux.

```text
0x142f6f4230934c5555772e4de1E8E39E1105CB2b
```

Merci de soutenir le développement ouvert.
