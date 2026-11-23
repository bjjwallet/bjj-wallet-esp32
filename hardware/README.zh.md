[Português](README.md) · [English](README.en.md) · [Español](README.es.md) · [Français](README.fr.md) · **简体中文** · [日本語](README.ja.md) · [Italiano](README.it.md) · [Deutsch](README.de.md)

# 🔐 BJJ-Cold-Wallet——硬件与固件

[← 产品介绍](../README.zh.md) · [移动应用](../app/README.zh.md)

**BJJ-Cold-Wallet** 是基于 ESP32-S3 的开源硬件钱包。它在设备上生成或导入种子，保留支出私钥，控制手机访问，并要求在签署转账前检查和进行实体确认。

应用通过 **Bluetooth Low Energy** 与冷钱包通信，负责互联网查询和向区块链提交。种子和支出私钥不会同步。授权的 Monero 只读导出可能包含私有查看密钥，但该密钥无法授权支出。

## 使用的开发板

当前发行目标为**带电容触摸的 LCDWIKI ES3C28P**。

| 组件 | 配置 |
|---|---|
| 处理器 | ESP32-S3，双核 Xtensa LX7，最高 240 MHz |
| Flash | 16 MB |
| PSRAM | 8 MB OPI |
| 显示屏 | 2.8″ IPS，ILI9341V 控制器，SPI 接口 |
| 原生分辨率 | 240 × 320 |
| 当前界面 | 横屏 320 × 240，触摸坐标转换 |
| 触摸 | 电容式 FT6336G，I²C 接口 |
| 应用通信 | Bluetooth Low Energy |
| 供电与恢复 | USB-C；通过实体 BOOT/RESET 进入烧录模式 |

来源：[LCDWIKI 制造商规格](https://www.lcdwiki.com/2.8inch_ESP32-S3_Display)。驱动配置位于 [esp32/hardware](esp32/hardware) 和 [esp32/idf_components/airlock_board](esp32/idf_components/airlock_board)。[ES3C28P.md](esp32/hardware/ES3C28P.md) 保留引脚与变体历史；本 README 说明当前显示方向和 USB 策略。

**ES3N28P 不带触摸。** 其验证构建使用独立配置；发行的 ES3C28P 包要求触摸功能。

### 购买与外壳

- **[AliExpress 开发板——项目推荐链接](https://pt.aliexpress.com/item/1005010470966979.html)**。选择 ES3C28P 触摸屏版本并核对商品规格。
- **[Printables 3D 打印外壳——推荐参考模型](https://www.printables.com/model/1188149-enclosure-for-esp32-s3-touch-lcd-2)**。尚未验证与所用 ES3C28P 硬件修订版的机械兼容性；请核对尺寸、固定和开孔。

## 语言、SDK 与库

| 技术 | 版本／来源 | 用途 |
|---|---|---|
| C / C++ | 兼容 C++17 的可移植核心 | 驱动、界面、规则、存储与签名器 |
| ESP-IDF | 5.4.2 | Espressif SDK、开发板初始化及嵌入式服务 |
| FreeRTOS | 由 ESP-IDF 提供 | 任务、同步与服务运行 |
| NimBLE | 集成于 ESP-IDF | 蓝牙 LE、GATT 与配对 |
| LVGL | 9.2.2 | 视觉组件与触摸交互 |
| esp_lcd_ili9341 | 2.0.1 | ILI9341V 显示屏驱动 |
| mbedTLS | 3.6.3，来自 ESP-IDF 5.4.2 | 密码学原语与存储保护 |
| TrezorCrypto | 固定为 Wallet Core 4.4.2 子集 | 密钥派生、曲线、哈希与签名 |
| cJSON | 1.7.18，随项目提供 | 本地协议 JSON 契约 |
| Rust | Rust 2021 crate；rust-version 1.89 | Monero 签名器；esp-rs 工具链构建 Xtensa |
| Monero Oxide / monero-wallet | Cargo.toml 固定的 Git 修订版 | RingCT 构造与签名、CLSAG 和 Bulletproof+ |
| curve25519-dalek | 4.1.3 | Monero 签名器的曲线运算 |
| rand_core / rand_chacha | 0.6.4 / 0.3.1 | 签名器的密码学随机接口与生成 |
| zeroize | 1.8，通过 Cargo.lock 解析 | Rust 中敏感值清零 |
| Monero C 例程 | 标签 0.18.5.4 | Monero 集成的 AES、Groestl、JH 和 Skein |
| CMake / Ninja / Python | 开发工具 | 构建、目录生成、验证与安装程序 |
| SDL2 / OpenSSL 3 / SQLite | 开发环境 | 桌面模拟器与测试 |

ESP-IDF 托管依赖见 [idf_component.yml](esp32/firmware/main/idf_component.yml)。Rust 依赖见 [Cargo.toml](esp32/monero_signer/Cargo.toml) 和 [Cargo.lock](esp32/monero_signer/Cargo.lock)。

## Trezor 集成：TrezorCrypto

使用的 Trezor 技术是 **TrezorCrypto 密码学库**，通过 **Trust Wallet Core 4.4.2** 中的子集引入。实现位于 [esp32/vendor/trezor-crypto-4.4.2](esp32/vendor/trezor-crypto-4.4.2)。

它提供固件使用的原语，包括：

- BIP-39 和英文助记词表。
- 层级派生及已集成的 secp256k1、NIST P-256、Ed25519 运算。
- 协议所需的 ECDSA、Ed25519、HMAC、PBKDF2、哈希及编码。
- 固定版本中的 Cardano 例程。

准确来源、上游压缩包哈希及各文件哈希记录在 [provenance.json](esp32/vendor/trezor-crypto-4.4.2/provenance.json)。该源码子集保持未修改；构建适配器为 [trezor_crypto.cmake](esp32/cmake/trezor_crypto.cmake)。

构建使用 `USE_BIP39_CACHE=0` 和 `USE_BIP32_CACHE=0` 禁用缓存。原 POSIX 随机适配器由 [trezor_random.cpp](esp32/core/trezor_random.cpp) 替代，接入平台随机源。随机源故障不会用可预测数字替代。

**BJJ-Cold-Wallet** 重建并验证付款、实施访问规则并提供实体检查；TrezorCrypto 提供所用密码学原语。这不代表采用完整 Trezor SDK、固件或获得 Trezor 设备认证。

在此目录运行以验证固定文件：

```sh
python3 esp32/tools/check_trezor_sources.py
```

原生 macOS Wallet Core 框架与 JavaScript/WASM 包分别用于参考、模拟器及旧实现。macOS 二进制不是 ESP32 库。参阅 [vendor/README.md](esp32/vendor/README.md)。

## 架构

```text
hardware/
├── esp32/                  # 当前固件
│   ├── core/               # 钱包、验证与签名器
│   ├── ui/                 # 页面、审批、语言、图标和字体
│   ├── hardware/           # 物理组件协议与抽象
│   ├── idf_components/     # ESP-IDF 服务与驱动集成
│   ├── firmware/           # 开发板主要构建项目
│   ├── monero_signer/      # Rust Monero 签名库
│   ├── vendor/             # 第三方依赖与许可
│   ├── cmake/              # 辅助构建配置
│   ├── simulator/          # 桌面模拟器
│   ├── tests/              # 测试与公开向量
│   ├── tools/              # 生成、验证、烧录与打包
│   └── docs/               # 报告与技术文档
├── instaladores/           # 当前版本 ZIP
├── historico/instaladores/ # 旧版本
├── core/                   # 原 Python 实现的服务
├── hardware/               # 原实现的驱动
├── ui/                     # 原 Python/MicroPython 界面
└── js/                     # 原 JavaScript / Wallet Core 签名器
```

`build` 和 `build-*` 保存构建产物。`device-updates`、`device-resets`、`device-backups` 保留此前开发板操作记录。`simulator-wallets` 属于模拟器环境。

当前 ESP32 核心使用 C/C++ 和 Rust。Python/MicroPython 及 JavaScript 目录保留旧实现和兼容性参考；其运行时不是发行 C/C++ 固件的签名路径。

## 审批、密钥与手机访问

设备控制每次连接的配对和授权。允许及阻止的手机列在硬件设置中。拒绝、阻止、断开或过期都不得产生已批准的转账签名。

签名前，固件验证计划、识别钱包，并显示资产、金额、收款人和费用。返还本钱包的找零与收款人分开显示。硬件使用种子和支出私钥；应用接收签名并在广播前验证。

发行固件中：

- `AIRLOCK_USB_POWER_ONLY=ON`：应用运行时 USB 仅供电。
- `CONFIG_ESP_CONSOLE_NONE=y`：禁用控制台。
- 禁用 `CONFIG_USJ_ENABLE_USB_SERIAL_JTAG`。
- `AIRLOCK_BOARD_AGENT_CONTROL=OFF`：关闭远程 USB 代理控制。
- 固件策略阻止 Wi-Fi 和 SD/microSD。
- 保留实体 BOOT/RESET 恢复；正常更新不修改 eFuse。

打开钱包或启动蓝牙前即断开 USB 数据。ROM 烧录模式仍可通过实体操作进入；应用策略不会在电气上永久切断接口。

## 网络、币种与签名器

与应用共享的目录包含 **23 个网络和 86 种资产**，包括原生币和 63 种登记代币。[完整网络／代币矩阵](../app/README.zh.md#supported-networks)见应用 README。

| 类别 | 实现与集成 |
|---|---|
| Bitcoin, Litecoin, Dogecoin, Dash, Bitcoin Cash e eCash | 输入／输出验证、UTXO 签名和各网络哈希规则 |
| Ethereum 与 EVM 网络 | 交易绑定 chain ID；代币绑定登记合约 |
| Solana | SOL 和登记的 SPL/Token-2022 代币；验证消息和账户 |
| Cardano | ADA 和五种登记代币，以 ADA 支付费用和找零 |
| TRON | TRX 和登记的 TRC-20 合约 |
| XRP Ledger | XRP 和按币种／发行方识别的发行资产 |
| Stellar | XLM 和按代码／发行方识别的资产 |
| Sui | SUI 和 USDC，构建付款时使用显式类型 |
| TON | V4R2 钱包及 USDT，派生并验证 jetton-wallet 身份 |
| Monero | wallet2 静态库，RingCT CLSAG/Bulletproof+ 签名；当前使用 stagenet |

固件地址和代币目录从应用的 `CoinCatalog.kt` 与 `Stablecoins.kt` 生成。检查会拒绝不一致目录。生成或检查时从项目根目录运行：

```sh
python3 hardware/esp32/tools/generate_address_catalog.py --check
python3 hardware/esp32/tools/generate_signing_catalog.py --check
```

[签名报告](../app/docs/all-assets-signing-2026-11-21.md)说明本地覆盖范围、Cardano UTXO 选择限制，以及有效签名与网络接受之间的区别。

## Monero 签名库

Rust 签名器位于 [esp32/monero_signer](esp32/monero_signer)。`monero-wallet` 依赖指向 [Monero Oxide](https://github.com/monero-oxide/monero-oxide) 修订版 `a6b46701fb9b331006d7f2f2278bf51f123b1447`；`Cargo.lock` 固定依赖解析。

Xtensa 构建生成 `libairlock_monero_signer.a`，通过 `AIRLOCK_MONERO_SIGNER_ARCHIVE` 链接到固件。集成采用平台分配器、内存清零和实体审批。完整模块要求 Xtensa/ESP32-S3 Rust 工具链；参阅 [BUILD.md](esp32/monero_signer/BUILD.md)。

## 构建固件

要求：**ESP-IDF 5.4.2**、ESP32-S3 工具链、CMake、Ninja、Python 和已为 Xtensa 编译的 Rust Monero 库。加载 ESP-IDF 环境并将库放到下列路径后，在此目录运行：

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

示例假定 Rust 工具链已在指定目录生成库。`sdkconfig` 必须保持控制台和 USB Serial/JTAG 禁用；CMake 验证发行策略。主项目位于 [esp32/firmware](esp32/firmware)。

## 本地测试与模拟器

可移植测试使用相同签名器，检查计划、签名、被修改的收款人／金额、权限、配对和界面状态。独立 Stellar、TRON、XRPL、Sui、TON SDK 是固定在 [tests/protocol-reference/package.json](esp32/tests/protocol-reference/package.json) 的开发依赖。

准备开发依赖后，在此目录运行：

```sh
cmake -S esp32 -B esp32/build-host \
  -DAIRLOCK_MBEDTLS_DIR="$IDF_PATH/components/mbedtls/mbedtls" \
  -DAIRLOCK_LVGL_DIR="$PWD/esp32/firmware/managed_components/lvgl__lvgl"
cmake --build esp32/build-host --parallel
ctest --test-dir esp32/build-host --output-on-failure

cargo test --locked --manifest-path esp32/monero_signer/Cargo.toml
```

LVGL 路径假设 ESP-IDF 托管依赖已解析。交互式桌面界面见[模拟器构建说明](esp32/README.md#compilar-e-testar-o-simulador)。测试使用公开／合成数据，不代表真实区块链转账。

## 可安装固件

包含的 `2026.11.21-allassets1` 版本包：

**[BJJ-Cold-Wallet-ES3C28P-2026-11-21-allassets1.zip](instaladores/BJJ-Cold-Wallet-ES3C28P-2026-11-21-allassets1.zip)**

旧版本位于[安装包历史目录](historico/instaladores/)。`instaladores` 目录仅保留当前 ZIP。

包内 `instalar.py` 按正确地址烧录组件。`firmware` 包含 `airlock_board_check.bin`、引导程序、分区表、SHA-256 哈希和发行策略。正常更新应保留钱包与配对；重置／擦除是独立操作。

生成包不代表已确认某块开发板的安装版本。烧录需通过实体操作进入 BOOT 模式。


<a id="installation"></a>

### 安装步骤

1. 下载并解压上面的 ZIP。所有文件需保持在一起；安装程序是 `instalar.py`，macOS 快捷入口是 `Instalar.command`。需要 Python 3 和支持数据传输的 USB-C 线。
2. 进入 BOOT：USB 连接后，按住 BOOT，按下并松开 RESET（RST/EN），再松开 BOOT。如果无法使用 RESET，请按住 BOOT 连接 USB，检测到设备后松开。
3. 在解压目录打开终端并准备对应系统的依赖：

**macOS 与 Linux**

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
```

**Windows（PowerShell）**

```powershell
py -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

4. 开发板处于 BOOT 模式时，运行对应系统的更新命令：

**macOS 与 Linux**

```sh
.venv/bin/python instalar.py
```

**Windows（PowerShell）**

```powershell
.\.venv\Scripts\python.exe instalar.py
```

连接多个开发板时，添加 `--porta` 和正确端口，例如 macOS 的 `/dev/cu.usbmodem14101`、Linux 的 `/dev/ttyACM0` 或 Windows 的 `COM3`。

仅对分区表为空的新开发板添加 `--placa-nova`。已配置钱包不得使用该选项。只检查安装包和开发板分区表而不写入时，在所选模式命令后添加 `--verificar`。

5. 等待完成并重启。正常更新只写入应用并检查分区表兼容性，保留钱包、名称、PIN 和配对。钱包启动后 USB 端口消失是正常现象：USB 仅供电，应用通过蓝牙通信。如果仍停留在引导程序，松开 BOOT 并按一次 RESET。

ZIP 内还包含 `README.md`，提供详细安装说明和故障排查。

## 文档、维护与许可

- [ESP32 移植详情与历史](esp32/README.md)：包含先前修订记录。
- [TrezorCrypto 来源](esp32/vendor/trezor-crypto-4.4.2/provenance.json)和[上游许可](esp32/vendor/trezor-crypto-4.4.2/crypto/LICENSE)。
- [外设策略](esp32/idf_components/airlock_policy/README.md)。
- [注释与格式规范](../docs/CODE_STYLE.md)。
- [发行固件规则](AGENTS.md)。

从 AirLock 复用的代码保留原作者信息和 [GPL-3.0 许可](LICENSE)。第三方库保留其许可和声明。更新密码学库或登记合约时，应记录版本／来源、更新参考并运行对应检查。
