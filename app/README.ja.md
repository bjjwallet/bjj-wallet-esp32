[Português](README.md) · [English](README.en.md) · [Español](README.es.md) · [Français](README.fr.md) · [简体中文](README.zh.md) · **日本語** · [Italiano](README.it.md) · [Deutsch](README.de.md)

# 📱 BJJ-Cold-Wallet モバイルアプリ

[← 製品紹介](../README.ja.md) · [ハードウェアとファームウェア](../hardware/README.ja.md)

**BJJ-App-Wallet** は **BJJ-Cold-Wallet** の Android・iOS 向けアプリです。口座・送金画面、ハードウェアウォレットとの Bluetooth 通信、ネットワーク照会、署名済みトランザクションの送信を提供します。

AirLock から発展したアプリです。現在の画面は Kotlin/Compose Multiplatform を使用し、内部名 `AirLockShared`、`AirLock.xcodeproj` と名前空間 `pub.airlock` は引き継がれています。

## 役割とセキュリティの流れ

アプリは口座とアドレスの同期、複数ウォレットの保存・管理、残高照会、送金先 QR の読み取り、送金データの準備を行います。支出署名は、内容確認と本体での承認後にコールドウォレットで生成します。

```text
アプリが支払いを準備
        ↓ Bluetooth / ローカル RPC
コールドウォレットがデータを確認し本体承認を要求
        ↓ ハードウェアが署名を生成
アプリが署名を検証
        ↓ ネットワーク API / RPC
トランザクションを配信
```

**アプリはシードと支出用秘密鍵を取り込み・保存しません。** ただし PIN、アクセス設定、保管庫情報などの機密データは処理します。Monero の監視専用モードでは**秘密閲覧鍵**を保存する場合があります。取引の監視は可能ですが、支出署名はできません。鍵は暗号化保管庫に留まり、リモートノードには送信しません。

アプリ PIN はローカルのロック解除を保護します。コールドウォレットのアクセス PIN は機器接続を認証し、アプリには永続保存されません。Transaction Phrase は別の保護機構で、新しい署名経路ではハードウェア上で要求します。各処理は [transfer-signing-flow.md](docs/transfer-signing-flow.md) を参照してください。

## 言語、プラットフォーム、ツール

| 技術 | プロジェクトのバージョン／設定 | 用途 |
|---|---|---|
| Kotlin | 2.3.21 | ルール、ネットワーク、Bluetooth、共有画面 |
| Kotlin Multiplatform | Kotlin プラグイン 2.3.21 | Android・iOS 共通コード |
| Compose Multiplatform | 1.10.0 | 宣言的 UI と共通リソース |
| Material 3 | 1.9.0 | 画面部品 |
| Swift / SwiftUI | Xcode SDK | アプリの入口と Apple ネイティブ連携 |
| Android Gradle Plugin | 8.11.2 | Android ビルド |
| Android SDK | compile/target 36；最小 29 | Android 10 以降 |
| iOS | デプロイ対象 16.0 | iPhone/iPad；Xcode でビルド |
| Java | JVM 17 | Android ビルドツール |
| Gradle Wrapper | プロジェクトに同梱 | 再現可能なビルドタスク |

宣言バージョンは [libs.versions.toml](gradle/libs.versions.toml)、[shared/build.gradle.kts](shared/build.gradle.kts)、[androidApp/build.gradle.kts](androidApp/build.gradle.kts) にあります。

## ライブラリと SDK

| ライブラリ／SDK | バージョン | 用途 |
|---|---|---|
| Kotlin Coroutines | 1.10.2 | 非同期処理、Flow、StateFlow |
| Kotlinx Serialization | 1.9.0 | モデルと契約の JSON シリアライズ |
| Ktor Client | 3.4.3 | HTTP、API/RPC；Android は OkHttp、iOS は Darwin |
| Kable | 0.41.0 | Bluetooth Low Energy 接続 |
| Koin | 4.1.0 | 依存性注入 |
| Lifecycle / ViewModel | 2.9.6 | 画面状態とライフサイクル |
| bitcoin-kmp, ACINQ | 0.32.0 | Bitcoin/UTXO 構造、ハッシュ、アドレスと署名検証 |
| secp256k1-kmp JNI | 0.24.0 | Android・JVM テストの secp256k1 バックエンド |
| KotlinCrypto SHA-3 | 0.8.0 | Keccak とプロトコルハッシュ |
| Kotlin Bignum | 0.3.10 | 任意精度演算 |
| Tink Android | 1.10.0 | Android 連携の暗号機能 |
| Coil / Coil SVG | 3.3.0 | 画像とアイコンの読み込み |
| Material Icons | 1.7.3 | 画面アイコン |
| Activity Compose | 1.10.1 | Compose と Android Activity の連携 |
| ZXing Core / Embedded | 3.5.3 / 4.3.0 | Android での QR スキャン |
| Android Keystore / BiometricPrompt | SDK Android | ローカル鍵の保護とネイティブ認証 |
| CryptoKit / Keychain / LocalAuthentication | SDK Apple | 暗号化保管庫と iOS 認証 |
| AVFoundation | SDK Apple | iOS での QR 撮影・読み取り |
| Monero JS/WASM 実行環境 | ローカルリソースとして同梱 | WebView/WKWebView を実行環境とする監視専用スキャナー |

Monero 実行環境とライセンスは [composeResources/files/monero](shared/src/commonMain/composeResources/files/monero) にあります。シードや支出用秘密鍵を受け取らずにウォレットを追跡します。

## プロジェクト構成

```text
app/
├── androidApp/        # Android 入口、権限、QR スキャナー
├── iosApp/            # Xcode プロジェクト、SwiftUI、Keychain、UI テスト
├── shared/
│   └── src/
│       ├── commonMain/       # 共通機能
│       ├── androidMain/      # Android ネイティブアダプター
│       ├── iosMain/          # iOS ネイティブアダプター
│       ├── commonTest/       # 共通テスト
│       ├── androidUnitTest/  # JVM テストと暗号処理の検証
│       └── test/             # 公開参照ベクトル
├── gradle/           # Wrapper とバージョンカタログ
├── scripts/          # 検査とリソース生成
├── docs/             # 構成、契約、テスト結果
└── hardware-patches/ # ハードウェア連携の調整記録
```

`shared/src/commonMain/kotlin/pub/airlock` 内：

| フォルダー | 役割 |
|---|---|
| `domain` | モデル、カタログ、インターフェース、ルール、正確な金額 |
| `data` | 保管庫、リポジトリ、ネットワーク連携、コールドウォレットアダプター |
| `ble` | Bluetooth 通信とローカルプロトコルメッセージ |
| `presentation` | ViewModel、画面状態、処理の調整 |
| `ui` | Compose 画面、部品、テーマ、エラー表示 |
| `di` | アプリの依存関係設定 |

<a id="supported-networks"></a>

## 対応ネットワークと通貨

現在は **23 ネットワーク、ネイティブコイン 23、トークン 63、合計 86 資産**を登録しています。別ネットワークの同じシンボルは別々の連携です。

| ネットワーク | ネイティブコイン | 登録済みトークン |
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
| Monero — 現在の処理は stagenet | XMR | — |
| Stellar | XLM | USDC, BRLP, BRZ |
| HyperEVM | HYPE | USDC, LINK |
| Robinhood Chain | ETH | USDG |

コントラクト、発行者、mint、小数桁数は [CoinCatalog.kt](shared/src/commonMain/kotlin/pub/airlock/domain/CoinCatalog.kt) と [Stablecoins.kt](shared/src/commonMain/kotlin/pub/airlock/domain/Stablecoins.kt) で定義し、ファームウェアのカタログ生成にも使用します。

86 資産すべての署名をローカル検証済みです。各ブロックチェーンで実際の送金が確定したことは示しません。Monero は現在 stagenet を使用します。Cardano は ADA のみ、または選択トークンのみを含む UTXO を選び、他資産が混在した UTXO は変更しません。[署名一覧と制限](docs/all-assets-signing-2026-11-21.md)を参照してください。

## API、RPC、配信

プロバイダーへの照会は、各プロトコルのトランザクション構築・検証と分離しています。

- **UTXO：** Bitcoin は Blockstream Esplora と mempool.space。Freedom Street はそのアダプター対象の通貨に使用。
- **EVM：** 登録トークンも含め各ネットワーク独自の RPC を使用。
- **Solana：** SOL と対応トークンは Solana RPC。
- **Cardano：** 準備・送信に Koios API。
- **TRON：** TRX と TRC-20 トークンに TronGrid。
- **XRP Ledger：** XRP と登録済み発行資産に XRPL JSON-RPC API。
- **Stellar：** Horizon API。
- **Sui：** 照会・準備・実行に GraphQL。
- **TON：** TON と USDT に TON Center API。
- **Monero：** 専用スキャナーと監視専用処理、ハードウェア署名。現在のファームウェアは stagenet。

AirLock と Moralis は各アダプターが対応する操作に利用できます。Bitcoin エクスプローラーを選んでも別ネットワークの RPC は置き換わりません。SEND は対象資産の送信経路が実装されている場合に有効になります。残高を読めるだけでは配信できません。

必要なプロバイダー認証情報はユーザーが設定し、保管庫で保護します。経路と機能は [NetworkSettings.kt](shared/src/commonMain/kotlin/pub/airlock/domain/NetworkSettings.kt) と [data/network](shared/src/commonMain/kotlin/pub/airlock/data/network) にあります。

## 保存と操作性

保管庫はバージョン付き JSON、AES-256-GCM 認証付き暗号、アトミック書き込みを使用します。ローカル鍵は Android Keystore または Keychain で保護し、認証失敗時に空ウォレットを黙って作成しません。

複数ウォレットの保存、ウォレットごとの口座・未確定取引の分離、ネイティブ生体認証、8 言語（ポルトガル語、英語、スペイン語、フランス語、簡体字中国語、日本語、イタリア語、ドイツ語）に対応します。金額は資産ごとの正確な精度を使い、浮動小数点へ変換しません。テストモードでは Amount と残高の画面上の制限を解除できますが、資金の生成やネットワーク受理の強制はできません。

別ウォレットを同期しても既存ウォレットは保持します。同期のやり直しは確認後にローカル保管庫を消去しますが、コールドウォレットや配信済みトランザクションは消しません。

## Android ビルド

必要環境：JDK 17、Android SDK 36、Gradle 依存関係。このフォルダーで実行：

```sh
./gradlew :androidApp:assembleDebug
./gradlew :shared:testDebugUnitTest
```

APK 出力先は [`androidApp/build/outputs/apk/debug/androidApp-debug.apk`](androidApp/build/outputs/apk/debug/androidApp-debug.apk) です。

パッケージ ID はデバッグ用 `pub.airlock.kmp.dev`、リリース用 `pub.airlock.kmp`。どちらも実連携を使用し、テストの模擬アダプターは本番サービスを置き換えません。リリース配布には Android 署名設定が必要です。

## iOS ビルド

必要環境：macOS、Xcode、Kotlin/Native 依存関係。[`iosApp/AirLock.xcodeproj`](iosApp/AirLock.xcodeproj) を開き、**AirLock** スキームを選択します。Xcode は Gradle 経由で Kotlin フレームワーク `AirLockShared` を組み込み、CocoaPods は使用しません。実機では署名チームを設定してください。

```sh
./gradlew :shared:compileKotlinIosSimulatorArm64

xcodebuild -project iosApp/AirLock.xcodeproj -scheme AirLock \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath iosApp/DerivedData build
```

## テスト、文書、保守

テストは公開ベクトル、模擬 HTTP 通信、ファームウェア署名器の署名を使用します。ローカル成功は実プロバイダーの受理を証明せず、Bluetooth、タッチ、実機承認の検証にも代わりません。

- [構成と統合背景](docs/BJJ_AIRLOCK.md)
- [移行と連携契約](docs/MIGRATION.md)
- [署名フロー](docs/transfer-signing-flow.md)
- [UTXO 署名](docs/UTXO_SIGNING.md)
- [完全カタログ：署名と制限](docs/all-assets-signing-2026-11-21.md)
- [金額入力](docs/amount-mask.md)と[送金先 QR](docs/recipient-qr.md)
- [コメントと書式の規約](../docs/CODE_STYLE.md)
- [歴史的参考として保存した旧文書](docs/README-anterior.md)

貢献では小さな変更、動作テスト、プロトコル変更時の契約更新を推奨します。AirLock から再利用した資料は元の著作者表示とライセンスを維持します：[GPL-3.0](LICENSE)、[NOTICE](NOTICE.md)、[リソースライセンス](docs/licenses)。
