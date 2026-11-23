[Português](README.md) · [English](README.en.md) · **Español** · [Français](README.fr.md) · [简体中文](README.zh.md) · [日本語](README.ja.md) · [Italiano](README.it.md) · [Deutsch](README.de.md)

# 📱 Aplicación móvil de BJJ-Cold-Wallet

[← Presentación del producto](../README.es.md) · [Hardware y firmware](../hardware/README.es.md)

**BJJ-App-Wallet** es la aplicación compañera de **BJJ-Cold-Wallet** para Android e iOS. Ofrece la interfaz de cuentas y transferencias, comunicación Bluetooth con la cartera física, consultas a las redes y transmisión de transacciones firmadas.

La aplicación forma parte de la evolución de AirLock. La interfaz actual usa Kotlin/Compose Multiplatform; los nombres internos `AirLockShared`, `AirLock.xcodeproj` y el espacio de nombres `pub.airlock` permanecen en el código.

## Responsabilidades y flujo de seguridad

La aplicación sincroniza cuentas y direcciones, gestiona varias carteras guardadas, consulta saldos, escanea QR de destinatarios y prepara transferencias. La cold wallet firma los gastos después de la revisión y aprobación física.

```text
La aplicación prepara el pago
        ↓ Bluetooth / RPC local
La cold wallet verifica los datos y solicita aprobación física
        ↓ Firma producida en el hardware
La aplicación verifica la firma
        ↓ API / RPC de la red
Transacción transmitida
```

**La aplicación no importa ni almacena la seed ni las claves privadas de gasto.** Necesita procesar datos sensibles: PIN, ajustes de acceso e información de la bóveda. El modo watch-only de Monero puede conservar una **clave privada de visualización** para seguir movimientos, que no permite firmar gastos. Se guarda en la bóveda cifrada y no se envía al nodo remoto.

El PIN de la aplicación protege el desbloqueo local. El PIN de acceso de la cold wallet autentica la conexión y no se conserva en la aplicación. La Transaction Phrase es una protección separada; las nuevas rutas de firma la solicitan en el hardware. Los flujos se detallan en [transfer-signing-flow.md](docs/transfer-signing-flow.md).

## Lenguajes, plataformas y herramientas

| Tecnología | Versión/configuración | Uso |
|---|---|---|
| Kotlin | 2.3.21 | Reglas, red, Bluetooth e interfaz compartida |
| Kotlin Multiplatform | Plugin Kotlin 2.3.21 | Código común para Android e iOS |
| Compose Multiplatform | 1.10.0 | Interfaz declarativa y recursos compartidos |
| Material 3 | 1.9.0 | Componentes visuales |
| Swift / SwiftUI | SDK de Xcode | Entrada de la aplicación e integraciones nativas Apple |
| Android Gradle Plugin | 8.11.2 | Compilación Android |
| Android SDK | compile/target 36; mínimo 29 | Android 10 o superior |
| iOS | Deployment target 16.0 | iPhone/iPad; compilación con Xcode |
| Java | JVM 17 | Compilación y herramientas Android |
| Gradle Wrapper | Incluido en el proyecto | Tareas de compilación reproducibles |

Las versiones declaradas están en [libs.versions.toml](gradle/libs.versions.toml), [shared/build.gradle.kts](shared/build.gradle.kts) y [androidApp/build.gradle.kts](androidApp/build.gradle.kts).

## Bibliotecas y SDK

| Biblioteca / SDK | Versión | Función |
|---|---|---|
| Kotlin Coroutines | 1.10.2 | Operaciones asíncronas, Flow y StateFlow |
| Kotlinx Serialization | 1.9.0 | Serialización JSON de modelos y contratos |
| Ktor Client | 3.4.3 | HTTP y APIs/RPCs; OkHttp en Android y Darwin en iOS |
| Kable | 0.41.0 | Conexiones Bluetooth Low Energy |
| Koin | 4.1.0 | Inyección de dependencias |
| Lifecycle / ViewModel | 2.9.6 | Estado y ciclo de vida de las pantallas |
| bitcoin-kmp, ACINQ | 0.32.0 | Estructuras Bitcoin/UTXO, hashes, direcciones y verificación de firmas |
| secp256k1-kmp JNI | 0.24.0 | Backend secp256k1 en Android y pruebas JVM |
| KotlinCrypto SHA-3 | 0.8.0 | Keccak y hashes de protocolos |
| Kotlin Bignum | 0.3.10 | Soporte de aritmética de precisión arbitraria |
| Tink Android | 1.10.0 | Recursos criptográficos para Android |
| Coil / Coil SVG | 3.3.0 | Carga de imágenes e iconos |
| Material Icons | 1.7.3 | Iconos de interfaz |
| Activity Compose | 1.10.1 | Integración Compose con la Activity Android |
| ZXing Core / Embedded | 3.5.3 / 4.3.0 | Escaneo QR en Android |
| Android Keystore / BiometricPrompt | SDK Android | Protección de la clave local y autenticación nativa |
| CryptoKit / Keychain / LocalAuthentication | SDK Apple | Bóveda cifrada y autenticación en iOS |
| AVFoundation | SDK Apple | Captura y escaneo QR en iOS |
| Runtime Monero JS/WASM | Incluido como recursos locales | Escáner watch-only con runtime WebView/WKWebView |

El runtime Monero y sus licencias están en [composeResources/files/monero](shared/src/commonMain/composeResources/files/monero). Sigue la cartera sin recibir la seed ni la clave privada de gasto.

## Estructura del proyecto

```text
app/
├── androidApp/        # Entrada Android, permisos y escáner QR
├── iosApp/            # Proyecto Xcode, SwiftUI, Keychain y pruebas de interfaz
├── shared/
│   └── src/
│       ├── commonMain/       # Funcionalidad compartida
│       ├── androidMain/      # Adaptadores nativos Android
│       ├── iosMain/          # Adaptadores nativos iOS
│       ├── commonTest/       # Pruebas compartidas
│       ├── androidUnitTest/  # Pruebas JVM y comprobaciones criptográficas
│       └── test/             # Vectores públicos de referencia
├── gradle/           # Wrapper y catálogo de versiones
├── scripts/          # Verificaciones y generación de recursos
├── docs/             # Arquitectura, contratos y resultados de pruebas
└── hardware-patches/ # Registros de adaptaciones de integración con hardware
```

Dentro de `shared/src/commonMain/kotlin/pub/airlock`:

| Carpeta | Responsabilidad |
|---|---|
| `domain` | Modelos, catálogos, interfaces, reglas y valores monetarios exactos |
| `data` | Bóveda, repositorios, integraciones de red y adaptador de la cold wallet |
| `ble` | Transporte Bluetooth y mensajes del protocolo local |
| `presentation` | ViewModel, estado de pantallas y coordinación de flujos |
| `ui` | Pantallas Compose, componentes, temas y presentación de errores |
| `di` | Configuración de dependencias de la aplicación |

<a id="supported-networks"></a>

## Redes y monedas compatibles

El catálogo actual contiene **23 redes, 23 monedas nativas y 63 tokens**, con **86 activos** en total. Un mismo símbolo en dos redes representa integraciones diferentes.

| Red | Moneda nativa | Tokens registrados |
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
| Monero — stagenet en el flujo Monero actual | XMR | — |
| Stellar | XLM | USDC, BRLP, BRZ |
| HyperEVM | HYPE | USDC, LINK |
| Robinhood Chain | ETH | USDG |

Los contratos, emisores, mints y precisiones se definen en [CoinCatalog.kt](shared/src/commonMain/kotlin/pub/airlock/domain/CoinCatalog.kt) y [Stablecoins.kt](shared/src/commonMain/kotlin/pub/airlock/domain/Stablecoins.kt). Estos archivos también alimentan los catálogos del firmware.

Las firmas de los 86 activos se verificaron localmente. Esto no demuestra una transferencia real confirmada en cada blockchain. Monero utiliza stagenet. En Cardano, se seleccionan UTXOs de ADA puro o que contengan solo el token elegido; los UTXOs mezclados con otros activos se mantienen intactos. Consulta la [matriz de firmas y sus límites](docs/all-assets-signing-2026-11-21.md).

## APIs, RPCs y transmisión

La aplicación separa las consultas a proveedores de la construcción y verificación de transacciones de cada protocolo:

- **UTXO:** Blockstream Esplora y mempool.space para Bitcoin; Freedom Street para las monedas cubiertas.
- **EVM:** RPC propio de cada red y de sus tokens registrados.
- **Solana:** RPC Solana para SOL y tokens compatibles.
- **Cardano:** API Koios para preparación y envío.
- **TRON:** TronGrid para TRX y tokens TRC-20.
- **XRP Ledger:** API JSON-RPC XRPL para XRP y activos emitidos registrados.
- **Stellar:** API Horizon.
- **Sui:** GraphQL para consultas, preparación y ejecución.
- **TON:** APIs TON Center para TON y USDT.
- **Monero:** Escáner y flujo watch-only propios, firma en hardware y stagenet en el firmware actual.

AirLock y Moralis siguen disponibles para las operaciones de sus adaptadores. Elegir un explorador Bitcoin no sustituye el RPC de otra red. SEND requiere una ruta de envío implementada para el activo; poder consultar el saldo no autoriza por sí solo la transmisión.

El usuario configura las credenciales de proveedores, cuando se requieren, y la bóveda las protege. Rutas y capacidades: [NetworkSettings.kt](shared/src/commonMain/kotlin/pub/airlock/domain/NetworkSettings.kt) y [data/network](shared/src/commonMain/kotlin/pub/airlock/data/network).

## Persistencia y experiencia de uso

La bóveda es un JSON versionado con cifrado autenticado AES-256-GCM y escritura atómica. Android Keystore o Keychain protege la clave local; los fallos de autenticación no crean silenciosamente una cartera vacía.

La aplicación ofrece varias carteras guardadas, aislamiento de cuentas y transacciones pendientes por cartera, biometría nativa y ocho idiomas: portugués, inglés, español, francés, chino simplificado, japonés, italiano y alemán. Los importes usan precisión exacta por activo, sin punto flotante. El modo de prueba puede desactivar el límite visual de Amount según el saldo; no crea fondos ni obliga a una red a aceptar transacciones.

Sincronizar otra cartera conserva las existentes. Reiniciar la sincronización borra la bóveda local tras confirmar; no borra la cold wallet ni cancela transacciones transmitidas.

## Compilar para Android

Requisitos: JDK 17, Android SDK 36 y dependencias Gradle. Ejecuta desde esta carpeta:

```sh
./gradlew :androidApp:assembleDebug
./gradlew :shared:testDebugUnitTest
```

El APK se genera en [`androidApp/build/outputs/apk/debug/androidApp-debug.apk`](androidApp/build/outputs/apk/debug/androidApp-debug.apk).

Los identificadores son `pub.airlock.kmp.dev` para debug y `pub.airlock.kmp` para release. Ambos usan integraciones reales; los adaptadores simulados de pruebas no sustituyen los servicios de producción. La distribución release requiere configurar la firma Android.

## Compilar para iOS

Requisitos: macOS, Xcode y dependencias Kotlin/Native. Abre [`iosApp/AirLock.xcodeproj`](iosApp/AirLock.xcodeproj) y selecciona **AirLock**. Xcode incorpora el framework Kotlin `AirLockShared` mediante Gradle, sin CocoaPods. Configura el equipo de firma para dispositivos físicos.

```sh
./gradlew :shared:compileKotlinIosSimulatorArm64

xcodebuild -project iosApp/AirLock.xcodeproj -scheme AirLock \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath iosApp/DerivedData build
```

## Pruebas, documentación y mantenimiento

Las pruebas usan vectores públicos, transportes HTTP simulados y firmas producidas por los firmantes del firmware. Su aprobación local no demuestra aceptación de un proveedor real ni sustituye la validación física de Bluetooth, touch y aprobación.

- [Arquitectura y contexto integrado](docs/BJJ_AIRLOCK.md)
- [Contrato de migración e integración](docs/MIGRATION.md)
- [Flujo de firma](docs/transfer-signing-flow.md)
- [Firma UTXO](docs/UTXO_SIGNING.md)
- [Catálogo completo: firmas y límites](docs/all-assets-signing-2026-11-21.md)
- [Introducción de importes](docs/amount-mask.md) y [QR del destinatario](docs/recipient-qr.md)
- [Normas de comentarios y formato](../docs/CODE_STYLE.md)
- [Documentación anterior para consulta histórica](docs/README-anterior.md)

Para contribuir, prioriza cambios pequeños, pruebas de comportamiento y actualizaciones de contratos cuando cambie el protocolo. Los recursos de AirLock conservan autoría y licencia: [GPL-3.0](LICENSE), [NOTICE](NOTICE.md) y [licencias de recursos](docs/licenses).
