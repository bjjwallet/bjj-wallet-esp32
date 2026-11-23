[Português](README.md) · [English](README.en.md) · [Español](README.es.md) · [Français](README.fr.md) · **简体中文** · [日本語](README.ja.md) · [Italiano](README.it.md) · [Deutsch](README.de.md)

# 📱 BJJ-Cold-Wallet 移动应用

[← 产品介绍](../README.zh.md) · [硬件与固件](../hardware/README.zh.md)

**BJJ-App-Wallet** 是 **BJJ-Cold-Wallet** 的 Android 和 iOS 配套应用，提供账户与转账界面、硬件钱包蓝牙通信、网络查询及已签名交易提交。

应用从 AirLock 演进而来。当前界面使用 Kotlin/Compose Multiplatform；代码保留 `AirLockShared`、`AirLock.xcodeproj` 和 `pub.airlock` 命名空间。

## 职责与安全流程

应用同步账户和地址，管理多个已保存钱包，查询余额，扫描收款人二维码并准备转账数据。支出签名由冷钱包在用户检查并进行实体确认后生成。

```text
应用准备付款
        ↓ 蓝牙 / 本地 RPC
冷钱包检查数据并要求在设备上确认
        ↓ 硬件生成签名
应用验证签名
        ↓ 网络 API / RPC
广播交易
```

**应用不导入或存储种子及支出私钥。** 但它会处理敏感数据，包括 PIN、访问设置和保险库信息。Monero 只读观察模式可能保存**私有查看密钥**，可观察交易，但无法签署支出。该密钥保留在加密保险库内，不发送给远程节点。

应用 PIN 保护本地解锁。冷钱包访问 PIN 用于验证设备连接，应用不会持久化保存。Transaction Phrase 是独立的保护机制；新的签名路径在硬件上请求输入。各流程见 [transfer-signing-flow.md](docs/transfer-signing-flow.md)。

## 语言、平台与工具

| 技术 | 项目版本／配置 | 用途 |
|---|---|---|
| Kotlin | 2.3.21 | 业务规则、网络、蓝牙和共享界面 |
| Kotlin Multiplatform | Kotlin 插件 2.3.21 | Android 与 iOS 共享代码 |
| Compose Multiplatform | 1.10.0 | 声明式界面和共享资源 |
| Material 3 | 1.9.0 | 视觉组件 |
| Swift / SwiftUI | Xcode SDK | 应用入口和 Apple 原生集成 |
| Android Gradle Plugin | 8.11.2 | Android 构建 |
| Android SDK | compile/target 36；最低 29 | Android 10 及以上 |
| iOS | 部署目标 16.0 | iPhone/iPad；通过 Xcode 构建 |
| Java | JVM 17 | Android 构建工具 |
| Gradle Wrapper | 项目内置 | 可复现的构建任务 |

版本声明位于 [libs.versions.toml](gradle/libs.versions.toml)、[shared/build.gradle.kts](shared/build.gradle.kts) 和 [androidApp/build.gradle.kts](androidApp/build.gradle.kts)。

## 库与 SDK

| 库／SDK | 版本 | 用途 |
|---|---|---|
| Kotlin Coroutines | 1.10.2 | 异步操作、Flow 和 StateFlow |
| Kotlinx Serialization | 1.9.0 | 模型和协议契约的 JSON 序列化 |
| Ktor Client | 3.4.3 | HTTP、API/RPC；Android 使用 OkHttp，iOS 使用 Darwin |
| Kable | 0.41.0 | 蓝牙低功耗连接 |
| Koin | 4.1.0 | 依赖注入 |
| Lifecycle / ViewModel | 2.9.6 | 屏幕状态与生命周期 |
| bitcoin-kmp, ACINQ | 0.32.0 | Bitcoin/UTXO 结构、哈希、地址和签名验证 |
| secp256k1-kmp JNI | 0.24.0 | Android 及 JVM 测试的 secp256k1 后端 |
| KotlinCrypto SHA-3 | 0.8.0 | Keccak 和协议哈希 |
| Kotlin Bignum | 0.3.10 | 任意精度运算支持 |
| Tink Android | 1.10.0 | Android 集成的密码学功能 |
| Coil / Coil SVG | 3.3.0 | 图片和图标加载 |
| Material Icons | 1.7.3 | 界面图标 |
| Activity Compose | 1.10.1 | Compose 与 Android Activity 集成 |
| ZXing Core / Embedded | 3.5.3 / 4.3.0 | Android 二维码扫描 |
| Android Keystore / BiometricPrompt | SDK Android | 本地密钥保护和原生身份验证 |
| CryptoKit / Keychain / LocalAuthentication | SDK Apple | 加密保险库和 iOS 身份验证 |
| AVFoundation | SDK Apple | iOS 二维码采集与扫描 |
| Monero JS/WASM 运行时 | 作为本地资源打包 | 以 WebView/WKWebView 运行的只读观察扫描器 |

Monero 运行时和许可位于 [composeResources/files/monero](shared/src/commonMain/composeResources/files/monero)。它在不接收种子或支出私钥的情况下跟踪钱包。

## 项目结构

```text
app/
├── androidApp/        # Android 入口、权限和二维码扫描器
├── iosApp/            # Xcode 项目、SwiftUI、Keychain 与界面测试
├── shared/
│   └── src/
│       ├── commonMain/       # 共享功能
│       ├── androidMain/      # Android 原生适配器
│       ├── iosMain/          # iOS 原生适配器
│       ├── commonTest/       # 共享测试
│       ├── androidUnitTest/  # JVM 测试与密码学检查
│       └── test/             # 公开参考向量
├── gradle/           # Wrapper 和版本目录
├── scripts/          # 检查与资源生成
├── docs/             # 架构、协议契约和测试结果
└── hardware-patches/ # 硬件集成适配记录
```

`shared/src/commonMain/kotlin/pub/airlock` 内部：

| 目录 | 职责 |
|---|---|
| `domain` | 模型、目录、接口、规则及精确金额 |
| `data` | 保险库、仓库、网络集成和冷钱包适配器 |
| `ble` | 蓝牙传输与本地协议消息 |
| `presentation` | ViewModel、屏幕状态和流程协调 |
| `ui` | Compose 页面、组件、主题及错误展示 |
| `di` | 应用依赖装配 |

<a id="supported-networks"></a>

## 支持的网络与币种

当前目录包含 **23 个网络、23 种原生币和 63 种代币**，合计 **86 种资产**。不同网络上同一符号对应独立集成。

| 网络 | 原生币 | 已登记代币 |
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
| Monero——当前流程使用 stagenet | XMR | — |
| Stellar | XLM | USDC, BRLP, BRZ |
| HyperEVM | HYPE | USDC, LINK |
| Robinhood Chain | ETH | USDG |

合约、发行方、mint 和精度定义位于 [CoinCatalog.kt](shared/src/commonMain/kotlin/pub/airlock/domain/CoinCatalog.kt) 和 [Stablecoins.kt](shared/src/commonMain/kotlin/pub/airlock/domain/Stablecoins.kt)。这些文件也用于生成固件目录。

全部 86 种资产的签名已在本地验证。这不表示每条区块链上的真实转账均已确认。当前 Monero 流程使用 stagenet。Cardano 代币选择使用纯 ADA UTXO 或仅含所选代币的 UTXO；混有其他资产的 UTXO 保持不动。详见[签名矩阵与限制](docs/all-assets-signing-2026-11-21.md)。

## API、RPC 与广播

应用将提供商查询与各协议的交易构造和验证分开：

- **UTXO：** Bitcoin 使用 Blockstream Esplora 和 mempool.space；Freedom Street 用于其适配器覆盖的币种。
- **EVM：** 使用各网络自身的 RPC，包含已登记代币。
- **Solana：** SOL 和支持的代币使用 Solana RPC。
- **Cardano：** Koios API 准备与提交交易。
- **TRON：** TronGrid 支持 TRX 和 TRC-20 代币。
- **XRP Ledger：** XRPL JSON-RPC API 支持 XRP 和已登记的发行资产。
- **Stellar：** Horizon API。
- **Sui：** GraphQL 查询、准备与执行。
- **TON：** TON Center API 支持 TON 和 USDT。
- **Monero：** 专用扫描器及只读观察流程，硬件签名；当前固件使用 stagenet。

AirLock 和 Moralis 继续用于各自适配器支持的操作。选择 Bitcoin 浏览器不会替换其他网络自身的 RPC。SEND 按钮要求该资产已实现提交路径；能查询余额不等于能广播交易。

需要提供商凭据时，由用户配置并在保险库中保护。路径和能力位于 [NetworkSettings.kt](shared/src/commonMain/kotlin/pub/airlock/domain/NetworkSettings.kt) 和 [data/network](shared/src/commonMain/kotlin/pub/airlock/data/network)。

## 持久化与用户体验

保险库使用带版本的 JSON、AES-256-GCM 认证加密与原子写入。Android Keystore 或 Keychain 保护本地密钥；身份验证失败不会静默创建空钱包。

应用支持多个已保存钱包，按钱包隔离账户及待确认交易，原生生物识别，以及八种语言：葡萄牙语、英语、西班牙语、法语、简体中文、日语、意大利语和德语。金额使用各资产精确小数位，不转换为浮点数。测试模式可关闭 Amount 与余额的界面限制，但不会产生资金，也不能强制网络接受交易。

同步另一个钱包会保留已有钱包。重新开始同步会在确认后清除本地保险库，但不会清除冷钱包，也不会撤销已广播交易。

## 构建 Android 应用

要求：JDK 17、Android SDK 36 和 Gradle 依赖。在当前目录运行：

```sh
./gradlew :androidApp:assembleDebug
./gradlew :shared:testDebugUnitTest
```

APK 输出到 [`androidApp/build/outputs/apk/debug/androidApp-debug.apk`](androidApp/build/outputs/apk/debug/androidApp-debug.apk)。

调试包标识为 `pub.airlock.kmp.dev`，发布包为 `pub.airlock.kmp`。两者都使用真实集成；测试模拟适配器不会替代生产服务。发布分发需要配置 Android 签名。

## 构建 iOS 应用

要求：macOS、Xcode 和 Kotlin/Native 依赖。打开 [`iosApp/AirLock.xcodeproj`](iosApp/AirLock.xcodeproj)，选择 **AirLock** scheme。Xcode 通过 Gradle 嵌入 Kotlin 框架 `AirLockShared`，不使用 CocoaPods。实体设备需要配置签名团队。

```sh
./gradlew :shared:compileKotlinIosSimulatorArm64

xcodebuild -project iosApp/AirLock.xcodeproj -scheme AirLock \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath iosApp/DerivedData build
```

## 测试、文档与维护

测试使用公开向量、模拟 HTTP 传输及固件签名器生成的签名。本地成功不代表真实提供商接受交易，也不能代替蓝牙、触摸及设备审批的实体测试。

- [架构与集成背景](docs/BJJ_AIRLOCK.md)
- [迁移与集成契约](docs/MIGRATION.md)
- [签名流程](docs/transfer-signing-flow.md)
- [UTXO 签名](docs/UTXO_SIGNING.md)
- [完整目录：签名与限制](docs/all-assets-signing-2026-11-21.md)
- [金额输入](docs/amount-mask.md)和[收款人二维码](docs/recipient-qr.md)
- [注释与格式规范](../docs/CODE_STYLE.md)
- [保留作为历史参考的旧文档](docs/README-anterior.md)

贡献时宜采用小范围修改、行为测试，并在协议变化时更新契约。从 AirLock 复用的材料保留原作者信息及许可：[GPL-3.0](LICENSE)、[NOTICE](NOTICE.md) 和[资源许可](docs/licenses)。
