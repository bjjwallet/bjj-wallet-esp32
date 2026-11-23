[Português](README.md) · [English](README.en.md) · [Español](README.es.md) · [Français](README.fr.md) · [简体中文](README.zh.md) · **日本語** · [Italiano](README.it.md) · [Deutsch](README.de.md)

# 🔐 BJJ-Cold-Wallet — ハードウェアとファームウェア

[← 製品紹介](../README.ja.md) · [モバイルアプリ](../app/README.ja.md)

**BJJ-Cold-Wallet** は ESP32-S3 のオープンソースハードウェアウォレットです。本体でシードを生成・取り込み、支出用秘密鍵を保持し、スマートフォンのアクセスを管理して、送金署名前に確認と本体承認を要求します。

モバイルアプリは **Bluetooth Low Energy** で通信し、インターネット照会とブロックチェーンへの送信を担当します。シードと支出用秘密鍵は同期しません。承認された Monero 監視用エクスポートには秘密閲覧鍵を含む場合がありますが、支出を承認する鍵ではありません。

## 使用ボード

現在の配布対象は**静電容量タッチ対応 LCDWIKI ES3C28P** です。

| 部品 | 構成 |
|---|---|
| プロセッサー | ESP32-S3、デュアルコア Xtensa LX7、最大 240 MHz |
| Flash | 16 MB |
| PSRAM | 8 MB OPI |
| 画面 | 2.8″ IPS、ILI9341V コントローラー、SPI 接続 |
| 本来の解像度 | 240 × 320 |
| 現在の画面 | 横向き 320 × 240、タッチ座標変換あり |
| タッチ | 静電容量式 FT6336G、I²C 接続 |
| アプリ通信 | Bluetooth Low Energy |
| 電源と復旧 | USB-C；書き込み用の物理 BOOT/RESET モード |

出典：[LCDWIKI メーカー仕様](https://www.lcdwiki.com/2.8inch_ESP32-S3_Display)。ドライバー設定は [esp32/hardware](esp32/hardware) と [esp32/idf_components/airlock_board](esp32/idf_components/airlock_board) にあります。[ES3C28P.md](esp32/hardware/ES3C28P.md) はピン配置と機種履歴を保存しています。本 README は現在の画面方向と USB 方針を記載します。

**ES3N28P はタッチ非対応機種です。** 検証ビルドは別設定で、配布 ES3C28P パッケージはタッチを必要とします。

### 購入とケース

- **[AliExpress ボード — 推奨商品](https://pt.aliexpress.com/item/1005010470966979.html)**。ES3C28P タッチスクリーン機種を選び、商品仕様を確認してください。
- **[Printables 3D プリントケース — 推奨参考モデル](https://www.printables.com/model/1188149-enclosure-for-esp32-s3-touch-lcd-2)**。使用する ES3C28P リビジョンとの機械的互換性は未検証です。寸法、固定、開口を確認してください。

## 言語、SDK、ライブラリ

| 技術 | バージョン／出典 | 用途 |
|---|---|---|
| C / C++ | C++17 対応の移植可能コア | ドライバー、画面、ルール、保存、署名器 |
| ESP-IDF | 5.4.2 | Espressif SDK、ボード初期化、組み込みサービス |
| FreeRTOS | ESP-IDF 付属 | タスク、同期、サービス実行 |
| NimBLE | ESP-IDF に統合 | Bluetooth LE、GATT、ペアリング |
| LVGL | 9.2.2 | 画面部品とタッチ操作 |
| esp_lcd_ili9341 | 2.0.1 | ILI9341V パネルドライバー |
| mbedTLS | ESP-IDF 5.4.2 の 3.6.3 | 暗号プリミティブと保存保護 |
| TrezorCrypto | Wallet Core 4.4.2 の固定サブセット | 鍵導出、曲線、ハッシュ、署名 |
| cJSON | 1.7.18、プロジェクトに同梱 | ローカルプロトコルの JSON 契約 |
| Rust | Edition 2021 crate；rust-version 1.89 | Monero 署名器；esp-rs ツールチェーンで Xtensa ビルド |
| Monero Oxide / monero-wallet | Cargo.toml で Git リビジョン固定 | RingCT 構築・署名、CLSAG、Bulletproof+ |
| curve25519-dalek | 4.1.3 | Monero 署名器の曲線演算 |
| rand_core / rand_chacha | 0.6.4 / 0.3.1 | 署名器の暗号乱数インターフェースと生成 |
| zeroize | 1.8、Cargo.lock で解決 | Rust の機密値ゼロ化 |
| Monero C ルーチン | タグ 0.18.5.4 | Monero 連携用 AES、Groestl、JH、Skein |
| CMake / Ninja / Python | 開発ツール | ビルド、カタログ生成、検証、インストーラー |
| SDL2 / OpenSSL 3 / SQLite | 開発環境 | デスクトップシミュレーターとテスト |

ESP-IDF の管理依存関係は [idf_component.yml](esp32/firmware/main/idf_component.yml)、Rust は [Cargo.toml](esp32/monero_signer/Cargo.toml) と [Cargo.lock](esp32/monero_signer/Cargo.lock) にあります。

## Trezor 連携：TrezorCrypto

使用する Trezor 技術は**暗号ライブラリ TrezorCrypto** です。**Trust Wallet Core 4.4.2** に含まれるサブセットを取り込み、[esp32/vendor/trezor-crypto-4.4.2](esp32/vendor/trezor-crypto-4.4.2) に配置しています。

ファームウェアで使う主なプリミティブ：

- BIP-39 と英語のニーモニック単語リスト。
- 階層導出、および統合済みの secp256k1、NIST P-256、Ed25519 演算。
- ECDSA、Ed25519、HMAC、PBKDF2、必要なハッシュとエンコード。
- 固定バージョンに含まれる Cardano ルーチン。

正確な出典、上流アーカイブのハッシュ、各ファイルのハッシュは [provenance.json](esp32/vendor/trezor-crypto-4.4.2/provenance.json) に記録しています。ソースのサブセットは無変更で保持し、ビルドアダプターは [trezor_crypto.cmake](esp32/cmake/trezor_crypto.cmake) です。

`USE_BIP39_CACHE=0` と `USE_BIP32_CACHE=0` でキャッシュを無効化します。元の POSIX 乱数アダプターは、プラットフォーム乱数源に接続した [trezor_random.cpp](esp32/core/trezor_random.cpp) で置き換えます。乱数源が失敗しても予測可能な数値で代替しません。

**BJJ-Cold-Wallet** は支払いを再構築・検証し、アクセス規則を適用して本体確認を表示します。TrezorCrypto は暗号プリミティブを提供します。Trezor の SDK 全体、ファームウェア、機器認証を意味するものではありません。

固定ファイルを検証するには、このフォルダーで実行：

```sh
python3 esp32/tools/check_trezor_sources.py
```

macOS ネイティブ Wallet Core フレームワークと JavaScript/WASM パッケージは、参考資料、シミュレーター、旧実装で別の役割を持ちます。macOS バイナリーは ESP32 用ライブラリではありません。[vendor/README.md](esp32/vendor/README.md) を参照してください。

## アーキテクチャ

```text
hardware/
├── esp32/                  # 現在のファームウェア
│   ├── core/               # ウォレット、検証、署名器
│   ├── ui/                 # 画面、承認、言語、アイコン、フォント
│   ├── hardware/           # 物理部品のプロトコルと抽象化
│   ├── idf_components/     # ESP-IDF サービスとドライバーの統合
│   ├── firmware/           # ボード用主要ビルドプロジェクト
│   ├── monero_signer/      # Rust Monero 署名ライブラリ
│   ├── vendor/             # 第三者依存関係とライセンス
│   ├── cmake/              # 補助ビルド設定
│   ├── simulator/          # デスクトップシミュレーター
│   ├── tests/              # テストと公開ベクトル
│   ├── tools/              # 生成、検証、書き込み、パッケージ作成
│   └── docs/               # 報告と技術文書
├── instaladores/           # 現行バージョンの ZIP
├── historico/instaladores/ # 過去のバージョン
├── core/                   # 元の Python 実装のサービス
├── hardware/               # 元の実装のドライバー
├── ui/                     # 元の Python/MicroPython 画面
└── js/                     # 元の JavaScript / Wallet Core 署名器
```

`build` と `build-*` はビルド出力です。`device-updates`、`device-resets`、`device-backups` は過去のボード操作記録です。`simulator-wallets` はシミュレーター用です。

現在の ESP32 コアは C/C++ と Rust を使用します。Python/MicroPython と JavaScript フォルダーは元実装と互換性資料を保存しており、配布 C/C++ ファームウェアの署名経路ではありません。

## 承認、鍵、スマートフォンのアクセス

機器は接続ごとにペアリングと権限を管理します。許可・ブロックしたスマートフォンは本体設定で表示します。拒否、ブロック、切断、期限切れで承認済みの送金署名を生成してはいけません。

署名前に計画を検証し、ウォレットを特定して資産、金額、送金先、手数料を表示します。自分のウォレットへのお釣りは送金先と区別します。ハードウェアがシードと支出用秘密鍵を使い、アプリは署名を受け取って配信前に検証します。

配布ファームウェアの設定：

- `AIRLOCK_USB_POWER_ONLY=ON`：アプリ実行中は USB 給電のみ。
- `CONFIG_ESP_CONSOLE_NONE=y`：コンソール無効。
- `CONFIG_USJ_ENABLE_USB_SERIAL_JTAG` 無効。
- `AIRLOCK_BOARD_AGENT_CONTROL=OFF`：USB エージェント遠隔操作なし。
- Wi-Fi と SD/microSD はファームウェア方針で無効。
- 物理 BOOT/RESET 復旧を維持し、通常更新では eFuse を変更しません。

ウォレットを開く前、または Bluetooth 起動前に USB データを切断します。ROM 書き込みモードには物理操作で引き続きアクセスできます。アプリの方針は電気的な恒久切断ではありません。

## ネットワーク、通貨、署名器

共通カタログは **23 ネットワーク、86 資産**で、ネイティブコインと登録トークン 63 種を含みます。[全ネットワーク／トークン一覧](../app/README.ja.md#supported-networks)はアプリ README にあります。

| 分類 | 実装と連携 |
|---|---|
| Bitcoin, Litecoin, Dogecoin, Dash, Bitcoin Cash e eCash | 入出力検証、UTXO 署名、ネットワーク別ハッシュ規則 |
| Ethereum と EVM ネットワーク | チェーン ID に結び付く取引、登録コントラクトに結び付くトークン |
| Solana | SOL と登録 SPL/Token-2022 トークン、メッセージ・口座の検証 |
| Cardano | ADA と登録トークン 5 種、ADA の手数料とお釣り |
| TRON | TRX と登録 TRC-20 コントラクト |
| XRP Ledger | XRP と通貨／発行者で識別する発行資産 |
| Stellar | XLM とコード／発行者で識別する資産 |
| Sui | SUI と USDC、支払い構築に明示的な型を使用 |
| TON | V4R2 ウォレットと USDT、jetton-wallet ID の導出・検証 |
| Monero | wallet2 アーカイブ、CLSAG/Bulletproof+ の RingCT 署名、現在は stagenet |

ファームウェアのアドレス・トークンカタログはアプリの `CoinCatalog.kt` と `Stablecoins.kt` から生成します。不一致は検査で拒否します。生成・検査はルートで実行：

```sh
python3 hardware/esp32/tools/generate_address_catalog.py --check
python3 hardware/esp32/tools/generate_signing_catalog.py --check
```

[署名報告](../app/docs/all-assets-signing-2026-11-21.md)にローカル検証範囲、Cardano UTXO 選択の制限、有効署名とネットワーク受理の違いを記載しています。

## Monero 署名ライブラリ

Rust 署名器は [esp32/monero_signer](esp32/monero_signer) にあります。`monero-wallet` は [Monero Oxide](https://github.com/monero-oxide/monero-oxide) のリビジョン `a6b46701fb9b331006d7f2f2278bf51f123b1447` を参照し、`Cargo.lock` で依存解決を固定します。

Xtensa ビルドで `libairlock_monero_signer.a` を生成し、`AIRLOCK_MONERO_SIGNER_ARCHIVE` で組み込みます。プラットフォームアロケーター、メモリーゼロ化、本体承認を利用します。完全なモジュールのビルドには Xtensa/ESP32-S3 Rust ツールチェーンが必要です。[BUILD.md](esp32/monero_signer/BUILD.md) に従ってください。

## ファームウェアのビルド

必要環境：**ESP-IDF 5.4.2**、ESP32-S3 ツールチェーン、CMake、Ninja、Python、Xtensa 用にビルド済みの Rust Monero ライブラリ。ESP-IDF 環境を読み込み、下記パスにライブラリを置いてこのフォルダーで実行：

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

例では指定場所にライブラリが生成済みであることを想定します。`sdkconfig` はコンソールと USB Serial/JTAG を無効のまま維持し、CMake が配布方針を検証します。主要プロジェクトは [esp32/firmware](esp32/firmware) です。

## ローカルテストとシミュレーター

移植可能なテストは同じ署名器で計画、署名、変更された送金先／金額、権限、ペアリング、画面状態を検証します。独立した Stellar、TRON、XRPL、Sui、TON SDK は [tests/protocol-reference/package.json](esp32/tests/protocol-reference/package.json) に固定した開発依存関係です。

開発依存関係を用意して、このフォルダーで実行：

```sh
cmake -S esp32 -B esp32/build-host \
  -DAIRLOCK_MBEDTLS_DIR="$IDF_PATH/components/mbedtls/mbedtls" \
  -DAIRLOCK_LVGL_DIR="$PWD/esp32/firmware/managed_components/lvgl__lvgl"
cmake --build esp32/build-host --parallel
ctest --test-dir esp32/build-host --output-on-failure

cargo test --locked --manifest-path esp32/monero_signer/Cargo.toml
```

LVGL パスは ESP-IDF 管理依存関係が解決済みである前提です。操作可能なデスクトップ画面は[シミュレーターのビルド手順](esp32/README.md#compilar-e-testar-o-simulador)を参照してください。テストは公開・合成データを使用し、実ブロックチェーン送金を証明しません。

## インストール用ファームウェア

同梱バージョン `2026.11.21-allassets1` のパッケージ：

**[BJJ-Cold-Wallet-ES3C28P-2026-11-21-allassets1.zip](instaladores/BJJ-Cold-Wallet-ES3C28P-2026-11-21-allassets1.zip)**

過去のバージョンは[インストーラー履歴](historico/instaladores/)にあります。`instaladores` フォルダーには現行 ZIP のみを置いています。

パッケージの `instalar.py` が各部品を正しいアドレスへ書き込みます。`firmware` には `airlock_board_check.bin`、ブートローダー、パーティション表、SHA-256 ハッシュ、配布方針があります。通常更新はウォレットとペアリングを保持すべきで、リセット／消去は別操作です。

パッケージ生成だけでは特定ボードのインストール済みバージョンは分かりません。書き込みには物理的に BOOT モードへ入る必要があります。


<a id="installation"></a>

### インストール手順

1. 上記 ZIP をダウンロードして展開します。ファイルはまとめたままにしてください。インストーラーは `instalar.py`、macOS 用ショートカットは `Instalar.command` です。Python 3 とデータ通信対応 USB-C ケーブルが必要です。
2. BOOT モードに入ります。USB 接続中に BOOT を押したまま RESET（RST/EN）を押して離し、BOOT を離します。RESET が使えない場合は BOOT を押しながら USB を接続し、検出後に離します。
3. 展開フォルダーでターミナルを開き、依存関係を準備します。

**macOS と Linux**

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
```

**Windows（PowerShell）**

```powershell
py -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

4. BOOT モードで、OS に応じた更新コマンドを実行します。

**macOS と Linux**

```sh
.venv/bin/python instalar.py
```

**Windows（PowerShell）**

```powershell
.\.venv\Scripts\python.exe instalar.py
```

複数のボードがある場合、`--porta` と正しいポートを追加します。例：macOS `/dev/cu.usbmodem14101`、Linux `/dev/ttyACM0`、Windows `COM3`。

パーティション表が空の新しいボードのみ `--placa-nova` を追加します。設定済みウォレットでは使用しないでください。書き込まずにパッケージとパーティション表を確認するには、選択したモードのコマンドに `--verificar` を追加します。

5. 完了と再起動を待ちます。通常更新はアプリのみを書き込み、パーティション表の互換性を確認して、ウォレット、名前、PIN、ペアリングを維持します。起動後に USB ポートが消えるのは正常です。USB は給電のみ、アプリは Bluetooth を使用します。ブートローダーに留まる場合は BOOT を離して RESET を一度押します。

ZIP 内の `README.md` に詳しい手順とトラブル対処を記載しています。

## 文書、保守、ライセンス

- [ESP32 移植の詳細と履歴](esp32/README.md)：過去リビジョンを含みます。
- [TrezorCrypto の出典](esp32/vendor/trezor-crypto-4.4.2/provenance.json)と[上流ライセンス](esp32/vendor/trezor-crypto-4.4.2/crypto/LICENSE)。
- [周辺機器の方針](esp32/idf_components/airlock_policy/README.md)。
- [コメントと書式の規約](../docs/CODE_STYLE.md)。
- [配布ファームウェアの規則](AGENTS.md)。

AirLock から再利用したコードは元の著作者表示と [GPL-3.0 ライセンス](LICENSE)を維持します。第三者ライブラリは独自のライセンスと通知を保持します。暗号ライブラリや登録コントラクト更新時はバージョンと出典を記録し、参照を更新して該当検査を実行してください。
