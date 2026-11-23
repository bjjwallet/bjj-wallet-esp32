[Português](README.md) · [English](README.en.md) · **Español** · [Français](README.fr.md) · [简体中文](README.zh.md) · [日本語](README.ja.md) · [Italiano](README.it.md) · [Deutsch](README.de.md)

# 🔐 BJJ-Cold-Wallet — hardware y firmware

[← Presentación del producto](../README.es.md) · [Aplicación móvil](../app/README.es.md)

**BJJ-Cold-Wallet** es una cartera física de código abierto basada en ESP32-S3. Genera o importa la seed en el dispositivo, conserva las claves privadas de gasto, controla el acceso de móviles y exige revisión y aprobación física antes de firmar transferencias.

La aplicación se comunica con la cold wallet por **Bluetooth Low Energy**. Las consultas de internet y la transmisión a las blockchains corresponden a la aplicación. La sincronización no incluye la seed ni las claves privadas de gasto. La exportación watch-only de Monero puede incluir, con autorización, la clave privada de visualización, que no permite gastar.

## Placa utilizada

El objetivo actual de distribución es **LCDWIKI ES3C28P con pantalla táctil capacitiva**.

| Componente | Configuración |
|---|---|
| Procesador | ESP32-S3, dual-core Xtensa LX7, hasta 240 MHz |
| Flash | 16 MB |
| PSRAM | 8 MB OPI |
| Pantalla | IPS de 2,8″, controlador ILI9341V, interfaz SPI |
| Resolución nativa | 240 × 320 |
| Interfaz actual | Horizontal, 320 × 240, con transformación de coordenadas táctiles |
| Touch | Capacitivo FT6336G, interfaz I²C |
| Comunicación con la aplicación | Bluetooth Low Energy |
| Alimentación y recuperación | USB-C; modo físico BOOT/RESET para grabación |

Fuente: [especificaciones LCDWIKI](https://www.lcdwiki.com/2.8inch_ESP32-S3_Display). Configuración de drivers: [esp32/hardware](esp32/hardware) y [esp32/idf_components/airlock_board](esp32/idf_components/airlock_board). [ES3C28P.md](esp32/hardware/ES3C28P.md) conserva el pinout y registros históricos; este README describe la orientación y política USB actuales.

**ES3N28P es la variante sin touch.** Sus builds de validación tienen configuración propia; el paquete ES3C28P distribuido exige touch.

### Compra y carcasa

- **[Placa en AliExpress — anuncio indicado para el proyecto](https://pt.aliexpress.com/item/1005010470966979.html)**. Selecciona ES3C28P con touch y comprueba las especificaciones.
- **[Carcasa 3D en Printables — referencia indicada](https://www.printables.com/model/1188149-enclosure-for-esp32-s3-touch-lcd-2)**. No se ha validado la compatibilidad mecánica con la revisión ES3C28P utilizada; comprueba medidas, fijación y recortes.

## Lenguajes, SDK y bibliotecas

| Tecnología | Versión/origen | Uso |
|---|---|---|
| C / C++ | Núcleo portátil compatible con C++17 | Drivers, interfaz, reglas, almacenamiento y firmantes |
| ESP-IDF | 5.4.2 | SDK Espressif, inicialización y servicios embebidos |
| FreeRTOS | Proporcionado por ESP-IDF | Tareas, sincronización y ejecución de servicios |
| NimBLE | Integrado en ESP-IDF | Bluetooth LE, GATT y emparejamientos |
| LVGL | 9.2.2 | Componentes visuales e interacción táctil |
| esp_lcd_ili9341 | 2.0.1 | Driver del panel ILI9341V |
| mbedTLS | 3.6.3, de ESP-IDF 5.4.2 | Primitivas criptográficas y protección de almacenamiento |
| TrezorCrypto | Subconjunto fijado de Wallet Core 4.4.2 | Derivación de claves, curvas, hashes y firmas |
| cJSON | 1.7.18, incluido en vendor | Contratos JSON del protocolo local |
| Rust | Crate edición 2021; rust-version 1.89 | Firmante Monero; compilación Xtensa con esp-rs |
| Monero Oxide / monero-wallet | Revisión Git fijada en Cargo.toml | Construcción y firma RingCT, CLSAG y Bulletproof+ |
| curve25519-dalek | 4.1.3 | Operaciones de curva del firmante Monero |
| rand_core / rand_chacha | 0.6.4 / 0.3.1 | Interfaz y generación de aleatoriedad criptográfica |
| zeroize | 1.8, resuelto mediante Cargo.lock | Borrado de valores sensibles en Rust |
| Rutinas Monero C | Tag 0.18.5.4 | AES, Groestl, JH y Skein para Monero |
| CMake / Ninja / Python | Herramientas de desarrollo | Compilación, generación de catálogos, validación e instalador |
| SDL2 / OpenSSL 3 / SQLite | Entorno de desarrollo | Simulador y pruebas de escritorio |

Las dependencias ESP-IDF gestionadas están en [idf_component.yml](esp32/firmware/main/idf_component.yml). Las de Rust están en [Cargo.toml](esp32/monero_signer/Cargo.toml) y [Cargo.lock](esp32/monero_signer/Cargo.lock).

## Integración Trezor: TrezorCrypto

La tecnología Trezor utilizada es la **biblioteca criptográfica TrezorCrypto**, incorporada mediante el subconjunto de **Trust Wallet Core 4.4.2**. Está en [esp32/vendor/trezor-crypto-4.4.2](esp32/vendor/trezor-crypto-4.4.2).

Proporciona primitivas utilizadas por el firmware, incluidas:

- BIP-39 y la lista inglesa de palabras mnemónicas.
- Derivación jerárquica y operaciones secp256k1, NIST P-256 y Ed25519 según la integración.
- ECDSA, Ed25519, HMAC, PBKDF2, hashes y codificaciones de los protocolos.
- Rutinas Cardano de la versión fijada.

El origen, hash del archivo upstream y hashes individuales están en [provenance.json](esp32/vendor/trezor-crypto-4.4.2/provenance.json). Este subconjunto se conserva sin modificaciones; el adaptador de compilación es [trezor_crypto.cmake](esp32/cmake/trezor_crypto.cmake).

El build usa `USE_BIP39_CACHE=0` y `USE_BIP32_CACHE=0` para desactivar esas cachés. El adaptador POSIX de aleatoriedad se sustituye por [trezor_random.cpp](esp32/core/trezor_random.cpp), ligado a la fuente de la plataforma. Sus fallos no se sustituyen por números predecibles.

**BJJ-Cold-Wallet** reconstruye y valida pagos, aplica reglas de acceso y presenta la revisión física; TrezorCrypto proporciona las primitivas criptográficas. Esta dependencia no constituye el SDK completo, firmware ni certificación de un dispositivo Trezor.

Para verificar los archivos fijados, ejecuta desde esta carpeta:

```sh
python3 esp32/tools/check_trezor_sources.py
```

El framework nativo macOS de Wallet Core y el paquete JavaScript/WASM tienen funciones separadas en referencias, simulador e implementación legada. El binario macOS no es una biblioteca para ESP32. Consulta [vendor/README.md](esp32/vendor/README.md).

## Arquitectura

```text
hardware/
├── esp32/                  # Firmware actual
│   ├── core/               # Carteras, validación y firmantes
│   ├── ui/                 # Pantallas, aprobación, idiomas, iconos y fuentes
│   ├── hardware/           # Protocolos y abstracciones de componentes físicos
│   ├── idf_components/     # Integración de servicios y drivers con ESP-IDF
│   ├── firmware/           # Proyecto principal de compilación de la placa
│   ├── monero_signer/      # Biblioteca Rust de firma Monero
│   ├── vendor/             # Dependencias externas y licencias
│   ├── cmake/              # Configuración auxiliar de compilación
│   ├── simulator/          # Simulador de escritorio
│   ├── tests/              # Pruebas y vectores públicos
│   ├── tools/              # Generación, verificación, grabación y empaquetado
│   └── docs/               # Informes y documentación técnica
├── instaladores/           # ZIP de la versión actual
├── historico/instaladores/ # Versiones anteriores
├── core/                   # Servicios originales Python
├── hardware/               # Drivers originales
├── ui/                     # Interfaz original Python/MicroPython
└── js/                     # Firmantes originales JavaScript / Wallet Core
```

`build` y `build-*` contienen resultados de compilación. `device-updates`, `device-resets` y `device-backups` conservan registros de operaciones anteriores. `simulator-wallets` pertenece al simulador.

El núcleo ESP32 actual usa C/C++ y Rust. Las carpetas Python/MicroPython y JavaScript conservan la implementación original y referencias de compatibilidad; su runtime no es la ruta de firma del firmware C/C++ distribuido.

## Aprobación, claves y acceso de móviles

El dispositivo controla los emparejamientos y la autorización de cada conexión. La lista de móviles permitidos y bloqueados está en los ajustes del hardware. El rechazo, bloqueo, desconexión o caducidad no debe producir una firma aprobada.

Antes de firmar, el firmware valida el plan, identifica la cartera y muestra activo, importe, destinatario y tasas. La devolución a la propia cartera se muestra separada del destinatario. El hardware usa la seed y las claves privadas de gasto; la aplicación recibe la firma y la verifica antes de transmitir.

En el firmware distribuido:

- `AIRLOCK_USB_POWER_ONLY=ON`: USB solo alimenta durante la aplicación.
- `CONFIG_ESP_CONSOLE_NONE=y`: consola desactivada.
- `CONFIG_USJ_ENABLE_USB_SERIAL_JTAG` desactivado.
- `AIRLOCK_BOARD_AGENT_CONTROL=OFF`: sin control remoto USB de agentes.
- Wi-Fi y SD/microSD bloqueados por la política.
- Recuperación física BOOT/RESET preservada; las actualizaciones normales no modifican eFuses.

Los datos USB se desconectan antes de abrir carteras o iniciar Bluetooth. El modo ROM de grabación sigue accesible físicamente; la política de la aplicación no supone un corte eléctrico permanente.

## Redes, monedas y firmantes

El catálogo compartido contiene **23 redes y 86 activos**, con las monedas nativas y los 63 tokens registrados. La [matriz completa por red y token](../app/README.es.md#supported-networks) está en el README de la aplicación.

| Familia | Implementación e integración |
|---|---|
| Bitcoin, Litecoin, Dogecoin, Dash, Bitcoin Cash e eCash | Validación de entradas/salidas, firma UTXO y reglas de hash por red |
| Ethereum y redes EVM | Transacciones ligadas a chain ID; tokens ligados a contratos registrados |
| Solana | SOL y tokens SPL/Token-2022 registrados; mensajes y cuentas verificados |
| Cardano | ADA y cinco tokens registrados, con tasas y devolución en ADA |
| TRON | TRX y contratos TRC-20 registrados |
| XRP Ledger | XRP y activos emitidos identificados por moneda/emisor |
| Stellar | XLM y activos identificados por código/emisor |
| Sui | SUI y USDC con tipos explícitos al construir pagos |
| TON | Cartera V4R2 y USDT, con identidad jetton derivada y verificada |
| Monero | Archivo wallet2, firma RingCT con CLSAG/Bulletproof+; stagenet actual |

Los catálogos de direcciones y tokens se generan a partir de `CoinCatalog.kt` y `Stablecoins.kt` de la aplicación. Las verificaciones rechazan divergencias. Para generar o comprobar, ejecuta desde la raíz del repositorio:

```sh
python3 hardware/esp32/tools/generate_address_catalog.py --check
python3 hardware/esp32/tools/generate_signing_catalog.py --check
```

El [informe de firmas](../app/docs/all-assets-signing-2026-11-21.md) explica la cobertura local, los límites de selección UTXO Cardano y la diferencia entre firma válida y aceptación en la red.

## Biblioteca de firma Monero

El firmante Rust está en [esp32/monero_signer](esp32/monero_signer). `monero-wallet` apunta a la revisión `a6b46701fb9b331006d7f2f2278bf51f123b1447` de [Monero Oxide](https://github.com/monero-oxide/monero-oxide); `Cargo.lock` fija la resolución de dependencias.

La compilación Xtensa produce `libairlock_monero_signer.a`, enlazada mediante `AIRLOCK_MONERO_SIGNER_ARCHIVE`. La integración utiliza asignadores de la plataforma, borrado de memoria y aprobación física. El módulo completo requiere toolchain Rust Xtensa/ESP32-S3; sigue [BUILD.md](esp32/monero_signer/BUILD.md).

## Compilar el firmware

Requisitos: **ESP-IDF 5.4.2**, toolchain ESP32-S3, CMake, Ninja, Python y biblioteca Rust Monero previamente compilada para Xtensa. Con el entorno ESP-IDF cargado y la biblioteca en la ruta siguiente, ejecuta desde esta carpeta:

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

El ejemplo presupone que Rust generó la biblioteca en el directorio indicado. `sdkconfig` debe mantener consola y USB Serial/JTAG desactivados; CMake valida las políticas de distribución. Proyecto principal: [esp32/firmware](esp32/firmware).

## Pruebas locales y simulador

Las pruebas portátiles usan los mismos firmantes y verifican planes, firmas, cambios de destinatario/importe, permisos, emparejamientos y estados de interfaz. Los SDK independientes de Stellar, TRON, XRPL, Sui y TON son dependencias de desarrollo fijadas en [tests/protocol-reference/package.json](esp32/tests/protocol-reference/package.json).

Con las dependencias de desarrollo disponibles, ejecuta desde esta carpeta:

```sh
cmake -S esp32 -B esp32/build-host \
  -DAIRLOCK_MBEDTLS_DIR="$IDF_PATH/components/mbedtls/mbedtls" \
  -DAIRLOCK_LVGL_DIR="$PWD/esp32/firmware/managed_components/lvgl__lvgl"
cmake --build esp32/build-host --parallel
ctest --test-dir esp32/build-host --output-on-failure

cargo test --locked --manifest-path esp32/monero_signer/Cargo.toml
```

La ruta LVGL presupone que las dependencias gestionadas ESP-IDF ya están resueltas. Para la interfaz interactiva, consulta [compilación del simulador](esp32/README.md#compilar-e-testar-o-simulador). Las pruebas usan datos públicos/sintéticos y no demuestran transferencias reales en blockchain.

## Firmware listo para instalar

El paquete incluido para la versión `2026.11.21-allassets1` está en:

**[BJJ-Cold-Wallet-ES3C28P-2026-11-21-allassets1.zip](instaladores/BJJ-Cold-Wallet-ES3C28P-2026-11-21-allassets1.zip)**

Las versiones anteriores están en el [historial de instaladores](historico/instaladores/). La carpeta `instaladores` contiene solo el ZIP actual.

En el paquete, `instalar.py` graba cada componente en su dirección correcta. `firmware` contiene `airlock_board_check.bin`, bootloader, tabla de particiones, hashes SHA-256 y política de release. La actualización normal conserva carteras y emparejamientos; borrar/restablecer es una operación separada.

Generar un paquete no identifica la versión instalada en una placa concreta. La grabación requiere entrar físicamente en BOOT.


<a id="installation"></a>

### Instalación paso a paso

1. Descarga y extrae el ZIP anterior. Mantén todos los archivos juntos; `instalar.py` es el instalador e `Instalar.command` el acceso para macOS. Usa Python 3 y un cable USB-C de datos.
2. Activa BOOT: con USB conectado, mantén BOOT pulsado, pulsa y suelta RESET (RST/EN) y suelta BOOT. Sin RESET accesible, mantén BOOT al conectar USB y suéltalo tras la detección.
3. Abre un terminal en la carpeta extraída y prepara las dependencias:

**macOS y Linux**

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
```

**Windows (PowerShell)**

```powershell
py -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

4. Con la placa en BOOT, ejecuta el comando de actualización de tu sistema:

**macOS y Linux**

```sh
.venv/bin/python instalar.py
```

**Windows (PowerShell)**

```powershell
.\.venv\Scripts\python.exe instalar.py
```

Si hay varias placas, añade `--porta` y el puerto correcto: `/dev/cu.usbmodem14101` en macOS, `/dev/ttyACM0` en Linux o `COM3` en Windows.

Para una placa nueva con tabla de particiones vacía, añade `--placa-nova`. No uses esta opción en una cartera configurada. Para verificar el paquete y la tabla sin grabar, añade `--verificar` al comando del modo elegido.

5. Espera a que termine y se reinicie. La actualización normal graba solo la aplicación y comprueba la compatibilidad de la tabla, conservando carteras, nombre, PIN y emparejamientos. Al iniciar la cartera, desaparece el puerto USB: queda para alimentación y la app usa Bluetooth. Si sigue en el cargador, suelta BOOT y pulsa RESET una vez.

El ZIP también incluye un `README.md` con instrucciones detalladas y diagnóstico.

## Documentación, mantenimiento y licencias

- [Detalles e historia del port ESP32](esp32/README.md): registros de revisiones anteriores.
- [Procedencia TrezorCrypto](esp32/vendor/trezor-crypto-4.4.2/provenance.json) y [licencias upstream](esp32/vendor/trezor-crypto-4.4.2/crypto/LICENSE).
- [Política de periféricos](esp32/idf_components/airlock_policy/README.md).
- [Normas de comentarios y formato](../docs/CODE_STYLE.md).
- [Reglas de distribución del firmware](AGENTS.md).

El código de AirLock conserva autoría original y [licencia GPL-3.0](LICENSE). Las bibliotecas externas mantienen sus licencias y avisos. Al actualizar criptografía o contratos, registra versión/origen, actualiza las referencias y ejecuta las verificaciones.
