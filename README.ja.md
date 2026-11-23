[Português](README.md) · [English](README.en.md) · [Español](README.es.md) · [Français](README.fr.md) · [简体中文](README.zh.md) · **日本語** · [Italiano](README.it.md) · [Deutsch](README.de.md)

<picture>
  <source media="(max-width: 640px)" srcset="docs/assets/hero-mobile.png">
  <img src="docs/assets/hero.png" alt="BJJ-Wallet — 手頃なハードウェアウォレット、公開コードと共同開発" width="100%">
</picture>

# BJJ-Cold-Wallet

<a id="testing"></a>

## 🧪 ウォレットはテスト段階です

**BJJ-Cold-Wallet は現在テスト・開発段階です。** アプリとハードウェアの動作検証、不具合の発見、使いやすさの改善には、コミュニティの協力が必要です。

**テストにご協力ください！** 初期設定、Bluetooth 接続、同期、コールドウォレット上での取引確認と署名をお試しください。[GitHub の BJJ-Wallet 組織](https://github.com/bjjwallet)で、不具合や改善案を共有してください。スマートフォンの機種、アプリとファームウェアのバージョン、再現手順を添えてください。

テストネットとテスト専用ウォレットを優先してください。報告、スクリーンショット、ログにシード、秘密鍵、PIN、Transaction Phrase を含めないでください。

**開発者が開発者のために作るコールドウォレット。すべての人に開かれています。**

ESP32-S3 を使用したタッチスクリーン付きハードウェアウォレットです。本体で送金を承認し、**Bluetooth Low Energy** で Android・iOS アプリと通信します。

<p align="center">
  <img src="docs/assets/badge-open-source.svg" alt="オープンソース" height="28">
  <img src="docs/assets/badge-affordable.svg" alt="手頃なハードウェア" height="28">
  <img src="docs/assets/badge-community.svg" alt="みんなで開発" height="28">
</p>

## 👋 私たちについて

私たち **BJJ-Wallet** は、安価なハードウェア、オープンソースソフトウェア、知識の共有を通じて、コールドウォレットをより身近にする開発者グループです。

**BJJ-Cold-Wallet** はこの考えを形にした、コミュニティが調べ、作り、改良できる実用的なウォレットです。資産を守る機器で何が動いているのか、誰もが理解できることを目指しています。

私たちの原則は、利用しやすさ、透明性、セキュリティ設計、協力です。開発者、メイカー、デザイナー、テスターの皆さんを歓迎します。コードを読み、設計を議論し、問題を報告して改善に参加してください。

[GitHub の BJJ-Wallet 組織](https://github.com/bjjwallet)をご覧ください。

[💚 BJJ-Cold-Wallet を支援](#support-project)

## 📱 モバイルアプリ

**BJJ-Cold-Wallet** のアプリは Android と iOS に対応します。Bluetooth で接続し、口座の確認に必要なデータを同期して残高を照会し、送金を準備し、ハードウェアが署名したトランザクションを配信します。

送金の流れ：

1. アプリでネットワーク、資産、送金先、金額を選びます。
2. アプリが Bluetooth 経由でコールドウォレットに署名を要求します。
3. コールドウォレットが送金内容を表示し、本体での承認を求めます。
4. ハードウェアが署名し、結果をアプリに返します。
5. アプリが署名を検証し、対応する API、RPC、その他ネットワークインターフェースで署名済みデータを送信します。

**シードと支出用秘密鍵はコールドウォレット内に留まります。アプリは動作のためにシードを要求・取り込みません。** アプリはアクセス認証情報を扱います。Monero の監視専用モードでは、暗号化保管庫に秘密閲覧鍵を保存する場合があります。この鍵で取引を確認できますが、支出は承認できません。

現在のカタログは **23 ネットワーク、86 資産（ネイティブコイン 23、トークン 63）**です。Bitcoin、Ethereum と EVM ネットワーク、Solana、Cardano、TRON、XRP Ledger、TON、Sui、Stellar、Litecoin、Dogecoin、Bitcoin Cash、Dash、eCash、Monero を含みます。現在のファームウェアの Monero 処理は **stagenet** を使用します。ネットワーク別・トークン別の一覧はアプリの README にあります。

アプリは **Kotlin Multiplatform と Compose Multiplatform** を使用し、Android・iOS のネイティブ機能と連携します。

➡️ **[アプリ：構成、言語、ライブラリ、ネットワークとビルド](app/README.ja.md)**

## 🔐 ハードウェア

BJJ-Cold-Wallet は、**ESP32-S3** を搭載した**静電容量タッチ対応 LCDWIKI ES3C28P** ボードを使用します。**16 MB フラッシュ**、**8 MB PSRAM**、**2.8 インチ IPS 画面**を備えます。パネル本来の解像度は 240 × 320、現在の画面は横向き 320 × 240 です。[メーカーの仕様書](https://www.lcdwiki.com/2.8inch_ESP32-S3_Display)を参照してください。

ファームウェアは **ESP-IDF と C/C++**、**LVGL** の画面、Monero 署名用 **Rust** ライブラリを使用します。暗号処理には Trust Wallet Core 4.4.2 由来の **TrezorCrypto** を含み、バージョンと出典を記録しています。

コールドウォレットがスマートフォンのアクセスを管理し、承認内容を表示して署名します。配布アプリケーションでは USB が給電、Bluetooth がアプリ通信を担当します。Wi-Fi と microSD はファームウェアで無効化されています。

### 🛒 ボードとケース

- **[ボード購入 — 推奨 AliExpress 商品](https://pt.aliexpress.com/item/1005010470966979.html)**。**タッチスクリーン付き ES3C28P** を確認してください。ES3N28P はタッチ非対応です。
- **[3D プリントケース — 推奨 Printables 参考モデル](https://www.printables.com/model/1188149-enclosure-for-esp32-s3-touch-lcd-2)**。印刷前にボードの寸法、固定方法、端子開口を確認してください。このモデルの機械的互換性はプロジェクトでは未検証です。

➡️ **[ハードウェア：ボード、TrezorCrypto、ライブラリ、ファームウェアと導入](hardware/README.ja.md)**

開発中のプロジェクトです。[署名テスト結果と制限](app/docs/all-assets-signing-2026-11-21.md)、[コード規約](docs/CODE_STYLE.md)、[アプリ](app/LICENSE)と[ハードウェア](hardware/LICENSE)のライセンスを参照してください。

<a id="support-project"></a>

## 💚 このアイデアを続けるために

手頃なハードウェアと公開コードで、コールドウォレットを身近にしたいと考えています。寄付は BJJ-Cold-Wallet の開発と、このアイデアの継続を支えます。

**現在、Android・iOS アプリを公開するためのアプリストア登録費用への支援が必要です。**

### 寄付前にネットワークを選択

| USDT · Ethereum | USDT0 · Polygon |
|:---:|:---:|
| <img src="docs/donate/usdt-ethereum.png" alt="アドレス QR コード： USDT · Ethereum" width="220"> | <img src="docs/donate/usdt0-polygon.png" alt="アドレス QR コード： USDT0 · Polygon" width="220"> |
| ネットワーク: **Ethereum** | ネットワーク: **Polygon** |

両方の QR コードには同じアドレスのみが含まれます。送信前に、ウォレットで表示されたトークンとネットワークを選択してください。

**寄付先アドレス**

GitHub では下のコードブロックの隅にあるコピーボタンを使用してください。両ネットワークのアドレスは同じです。

```text
0x142f6f4230934c5555772e4de1E8E39E1105CB2b
```

オープンな開発へのご支援ありがとうございます。
