**Português** · [English](README.en.md) · [Español](README.es.md) · [Français](README.fr.md) · [简体中文](README.zh.md) · [日本語](README.ja.md) · [Italiano](README.it.md) · [Deutsch](README.de.md)

# 🔐 BJJ-Cold-Wallet — hardware e firmware

[← Apresentação do produto](../README.md) · [Aplicativo mobile](../app/README.md)

A **BJJ-Cold-Wallet** é uma carteira física de código aberto construída sobre ESP32-S3. Ela gera ou importa a seed no próprio dispositivo, mantém as chaves privadas de gasto, controla o acesso dos celulares e exige revisão e aprovação física antes de assinar transferências.

O aplicativo mobile se comunica com a cold wallet por **Bluetooth Low Energy**. As consultas à internet e a transmissão às blockchains são feitas pelo app. A seed e as chaves privadas de gasto não fazem parte da sincronização. A exportação watch-only do Monero pode incluir sua chave privada de visualização, mediante autorização; ela não permite gastar.

## Placa utilizada

O alvo atual de distribuição é a **LCDWIKI ES3C28P com touch capacitivo**.

| Componente | Configuração |
|---|---|
| Processador | ESP32-S3, dual-core Xtensa LX7, até 240 MHz |
| Flash | 16 MB |
| PSRAM | 8 MB OPI |
| Tela | IPS de 2,8″, controlador ILI9341V, interface SPI |
| Resolução nativa | 240 × 320 |
| Interface atual | Landscape, 320 × 240, com transformação do toque |
| Touch | Capacitivo FT6336G, interface I²C |
| Comunicação com o app | Bluetooth Low Energy |
| Alimentação e recuperação | USB-C; modo BOOT/RESET físico para gravação |

Fonte: [especificações do fabricante LCDWIKI](https://www.lcdwiki.com/2.8inch_ESP32-S3_Display). A configuração do driver está em [esp32/hardware](esp32/hardware) e [esp32/idf_components/airlock_board](esp32/idf_components/airlock_board). O documento [ES3C28P.md](esp32/hardware/ES3C28P.md) preserva pinagem e registros históricos de variantes; a orientação e a política USB atuais são as descritas neste README.

A **ES3N28P é a variante sem touch**. Os builds de validação dessa variante têm configuração própria; o pacote distribuído para a ES3C28P exige touch.

### Compra e gabinete

- **[Placa no AliExpress — anúncio indicado para o projeto](https://pt.aliexpress.com/item/1005010470966979.html)**. Selecione a variante ES3C28P com touch e confira as especificações do anúncio.
- **[Case para impressão 3D no Printables — referência indicada](https://www.printables.com/model/1188149-enclosure-for-esp32-s3-touch-lcd-2)**. A compatibilidade mecânica desse case com a revisão da ES3C28P utilizada ainda não foi validada; confira medidas, fixação e recortes.

## Linguagens, SDKs e bibliotecas

| Tecnologia | Versão/origem | Uso |
|---|---|---|
| C / C++ | Núcleo portátil compatível com C++17 | Drivers, UI, regras, armazenamento e assinadores |
| ESP-IDF | 5.4.2 | SDK Espressif, inicialização da placa e serviços embarcados |
| FreeRTOS | Fornecido pelo ESP-IDF | Tarefas, sincronização e execução dos serviços |
| NimBLE | Integrado ao ESP-IDF | Bluetooth LE, GATT e pareamentos |
| LVGL | 9.2.2 | Componentes visuais e interação touch |
| esp_lcd_ili9341 | 2.0.1 | Driver do painel ILI9341V |
| mbedTLS | 3.6.3, do ESP-IDF 5.4.2 | Primitivas criptográficas e proteção do armazenamento |
| TrezorCrypto | Subconjunto fixado do Wallet Core 4.4.2 | Derivação de chaves, curvas, hashes e assinaturas |
| cJSON | 1.7.18, vendorizado | Contratos JSON do protocolo local |
| Rust | Crate edição 2021; rust-version 1.89 | Assinador Monero; build Xtensa com toolchain esp-rs |
| Monero Oxide / monero-wallet | Revisão Git fixada em Cargo.toml | Construção e assinatura RingCT, CLSAG e Bulletproof+ |
| curve25519-dalek | 4.1.3 | Operações de curva no assinador Monero |
| rand_core / rand_chacha | 0.6.4 / 0.3.1 | Interface e geração criptográfica de aleatoriedade no assinador |
| zeroize | 1.8, com resolução no Cargo.lock | Limpeza de valores sensíveis em Rust |
| Rotinas Monero C | Tag 0.18.5.4 | AES, Groestl, JH e Skein da integração Monero |
| CMake / Ninja / Python | Ferramentas de desenvolvimento | Compilação, geração de catálogos, validação e instalador |
| SDL2 / OpenSSL 3 / SQLite | Ambiente de desenvolvimento | Simulador e testes no computador |

As dependências ESP-IDF gerenciadas estão em [idf_component.yml](esp32/firmware/main/idf_component.yml). As dependências Rust ficam em [Cargo.toml](esp32/monero_signer/Cargo.toml) e [Cargo.lock](esp32/monero_signer/Cargo.lock).

## Integração da Trezor: TrezorCrypto

A parte da tecnologia Trezor utilizada é a **biblioteca criptográfica TrezorCrypto**, incorporada ao projeto por meio do subconjunto presente no **Trust Wallet Core 4.4.2**. A implementação está em [esp32/vendor/trezor-crypto-4.4.2](esp32/vendor/trezor-crypto-4.4.2).

Ela fornece primitivas usadas pelo firmware, incluindo:

- BIP-39 e a lista inglesa de palavras para mnemônicos.
- Derivação hierárquica e operações com secp256k1, NIST P-256 e Ed25519, conforme a integração.
- ECDSA, Ed25519, HMAC, PBKDF2, hashes e codificações necessárias aos protocolos.
- Rotinas Cardano disponíveis na versão fixada.

A origem exata, o hash do arquivo upstream e os hashes individuais estão em [provenance.json](esp32/vendor/trezor-crypto-4.4.2/provenance.json). Os fontes desse subconjunto são preservados sem modificações; o adaptador de compilação é [trezor_crypto.cmake](esp32/cmake/trezor_crypto.cmake).

No build do projeto, `USE_BIP39_CACHE=0` e `USE_BIP32_CACHE=0` desativam esses caches. O adaptador original de aleatoriedade POSIX é substituído por [trezor_random.cpp](esp32/core/trezor_random.cpp), ligado à fonte de aleatoriedade da plataforma. Falhas da fonte não são substituídas por números previsíveis.

O código da **BJJ-Cold-Wallet** reconstrói e valida os pagamentos, aplica as regras de acesso e apresenta a revisão física; TrezorCrypto fornece as primitivas criptográficas utilizadas nessas operações. Essa dependência não representa o SDK completo, o firmware ou uma certificação de um dispositivo Trezor.

Para conferir os arquivos fixados, execute nesta pasta:

```sh
python3 esp32/tools/check_trezor_sources.py
```

O framework nativo macOS do Wallet Core e o pacote JavaScript/WASM têm papéis separados em referências, simulador e implementação legada. O binário macOS não é usado como biblioteca da ESP32. Consulte [vendor/README.md](esp32/vendor/README.md).

## Arquitetura

```text
hardware/
├── esp32/                  # Firmware atual
│   ├── core/               # Carteiras, validação e assinadores
│   ├── ui/                 # Telas, aprovação, idiomas, ícones e fontes
│   ├── hardware/           # Protocolos e abstrações dos componentes físicos
│   ├── idf_components/     # Integração dos serviços e drivers com ESP-IDF
│   ├── firmware/           # Projeto principal de compilação da placa
│   ├── monero_signer/      # Biblioteca Rust de assinatura Monero
│   ├── vendor/             # Dependências de terceiros e suas licenças
│   ├── cmake/              # Configuração auxiliar de compilação
│   ├── simulator/          # Simulador no computador
│   ├── tests/              # Testes e vetores públicos
│   ├── tools/              # Geração, verificação, gravação e empacotamento
│   └── docs/               # Relatórios e documentação técnica
├── instaladores/           # ZIP da versão atual
├── historico/instaladores/ # Versões anteriores
├── core/                   # Serviços da implementação original Python
├── hardware/               # Drivers da implementação original
├── ui/                     # Interface original Python/MicroPython
└── js/                     # Assinadores originais JavaScript / Wallet Core
```

As pastas `build` e `build-*` contêm saídas de compilação. `device-updates`, `device-resets` e `device-backups` guardam registros das operações anteriores com a placa. `simulator-wallets` pertence ao ambiente do simulador.

O núcleo atual da ESP32 usa C/C++ e Rust. As pastas Python/MicroPython e JavaScript preservam a implementação original e referências de compatibilidade; seu runtime não é o caminho de assinatura do firmware C/C++ distribuído.

## Aprovação, chaves e acesso de celulares

O dispositivo controla os pareamentos e a autorização de cada conexão. A lista de celulares autorizados ou bloqueados fica nas configurações do hardware. Uma rejeição, bloqueio, desconexão ou expiração não pode produzir uma assinatura de transferência aprovada.

Antes de assinar, o firmware valida o plano, identifica a carteira e apresenta o ativo, o valor, o destinatário e as taxas. Quando existe devolução para a própria carteira, ela aparece separada do destinatário. A seed e as chaves privadas de gasto são utilizadas no hardware; o app recebe o resultado da assinatura e faz sua verificação antes de transmitir.

No firmware de distribuição:

- `AIRLOCK_USB_POWER_ONLY=ON`: USB apenas para alimentação durante a aplicação.
- `CONFIG_ESP_CONSOLE_NONE=y`: console desativado.
- `CONFIG_USJ_ENABLE_USB_SERIAL_JTAG` desabilitado.
- `AIRLOCK_BOARD_AGENT_CONTROL=OFF`: sem controle remoto de agente por USB.
- Wi-Fi e SD/microSD bloqueados pela política do firmware.
- Recuperação física por BOOT/RESET preservada; atualizações comuns não alteram eFuses.

A desconexão USB ocorre antes de abrir as carteiras ou iniciar o Bluetooth. O modo ROM de gravação continua disponível quando acionado fisicamente; a política da aplicação não equivale a um corte elétrico permanente das interfaces.

## Redes, moedas e assinadores

O catálogo compartilhado com o app contém **23 redes e 86 ativos**, incluindo as moedas nativas e os 63 tokens cadastrados. A [matriz completa por rede e token](../app/README.md#supported-networks) fica no README do app.

| Família | Implementação e integração |
|---|---|
| Bitcoin, Litecoin, Dogecoin, Dash, Bitcoin Cash e eCash | Validação das entradas/saídas, assinatura UTXO e regras de hash de cada rede |
| Ethereum e redes EVM | Transações vinculadas à chain ID; tokens vinculados aos contratos cadastrados |
| Solana | SOL e tokens SPL/Token-2022 registrados; mensagens e contas verificadas |
| Cardano | ADA e os cinco tokens cadastrados, com taxas e devolução em ADA |
| TRON | TRX e contratos TRC-20 cadastrados |
| XRP Ledger | XRP e ativos emitidos identificados por moeda/emissor |
| Stellar | XLM e ativos identificados por código/emissor |
| Sui | SUI e USDC, com tipos explícitos na construção do pagamento |
| TON | Carteira V4R2 e USDT, com identidade da carteira jetton derivada e verificada |
| Monero | Arquivo wallet2, assinatura RingCT com CLSAG/Bulletproof+; stagenet no fluxo atual |

Os catálogos de endereços e tokens do firmware são gerados dos arquivos `CoinCatalog.kt` e `Stablecoins.kt` do app. As verificações recusam catálogos divergentes. Para gerar ou conferir, execute na raiz do repositório:

```sh
python3 hardware/esp32/tools/generate_address_catalog.py --check
python3 hardware/esp32/tools/generate_signing_catalog.py --check
```

O [relatório de assinaturas](../app/docs/all-assets-signing-2026-11-21.md) descreve a cobertura local, os limites da seleção de UTXOs Cardano e a distinção entre assinatura válida e aceitação da transação pela rede.

## Biblioteca de assinatura Monero

O assinador Rust está em [esp32/monero_signer](esp32/monero_signer). A dependência `monero-wallet` aponta para a revisão `a6b46701fb9b331006d7f2f2278bf51f123b1447` do [Monero Oxide](https://github.com/monero-oxide/monero-oxide), e o `Cargo.lock` fixa a resolução das dependências.

A compilação para Xtensa produz `libairlock_monero_signer.a`, ligada ao firmware por `AIRLOCK_MONERO_SIGNER_ARCHIVE`. A integração usa os alocadores da plataforma, limpeza de memória e aprovação física. O build completo desse módulo requer toolchain Rust para Xtensa/ESP32-S3; siga [BUILD.md](esp32/monero_signer/BUILD.md).

## Compilar o firmware

Requisitos: **ESP-IDF 5.4.2**, toolchain ESP32-S3, CMake, Ninja, Python e a biblioteca Rust Monero previamente compilada para Xtensa. Com o ambiente do ESP-IDF carregado e a biblioteca no caminho abaixo, execute nesta pasta:

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

O exemplo pressupõe que o toolchain Rust gerou a biblioteca no diretório indicado. Os arquivos `sdkconfig` devem manter console e USB Serial/JTAG desativados; o CMake valida as políticas de distribuição. O projeto principal está em [esp32/firmware](esp32/firmware).

## Testes locais e simulador

Os testes portáteis usam os mesmos assinadores e verificam planos, assinaturas, alteração de destinatário/valor, permissões, pareamentos e estados da interface. Os SDKs independentes de Stellar, TRON, XRPL, Sui e TON são dependências de desenvolvimento fixadas em [tests/protocol-reference/package.json](esp32/tests/protocol-reference/package.json).

Com as dependências de desenvolvimento disponíveis, execute nesta pasta:

```sh
cmake -S esp32 -B esp32/build-host \
  -DAIRLOCK_MBEDTLS_DIR="$IDF_PATH/components/mbedtls/mbedtls" \
  -DAIRLOCK_LVGL_DIR="$PWD/esp32/firmware/managed_components/lvgl__lvgl"
cmake --build esp32/build-host --parallel
ctest --test-dir esp32/build-host --output-on-failure

cargo test --locked --manifest-path esp32/monero_signer/Cargo.toml
```

O caminho LVGL pressupõe que as dependências gerenciadas do projeto ESP-IDF já foram resolvidas. Para a interface interativa no computador, consulte [compilação do simulador](esp32/README.md#compilar-e-testar-o-simulador). Os testes usam dados públicos/sintéticos e não comprovam uma transferência real em blockchain.

## Firmware pronto para instalar

O pacote incluído para a versão `2026.11.21-allassets1` está em:

**[BJJ-Cold-Wallet-ES3C28P-2026-11-21-allassets1.zip](instaladores/BJJ-Cold-Wallet-ES3C28P-2026-11-21-allassets1.zip)**

As versões anteriores estão no [histórico de instaladores](historico/instaladores/). A pasta `instaladores` contém somente o ZIP atual.

Dentro do pacote, `instalar.py` grava os componentes nos endereços corretos. A pasta `firmware` contém `airlock_board_check.bin`, bootloader, tabela de partições, hashes SHA-256 e a política da release. A atualização comum deve preservar carteiras e pareamentos; reset/apagamento é uma operação separada.

A geração de um pacote não informa qual versão está instalada em uma placa específica. O modo BOOT precisa ser acionado fisicamente para a gravação.


<a id="installation"></a>

### Passo a passo de instalação

1. Baixe e extraia o ZIP acima. Mantenha todos os arquivos juntos; o instalador é `instalar.py`, e `Instalar.command` é o atalho para macOS. Use Python 3 e um cabo USB-C com transmissão de dados.
2. Coloque a placa em BOOT: com o USB conectado, segure BOOT, pressione e solte RESET (RST/EN) e solte BOOT. Sem RESET acessível, segure BOOT ao conectar o USB e solte após a detecção.
3. Abra o terminal na pasta extraída e prepare as dependências conforme seu sistema:

**macOS e Linux**

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
```

**Windows (PowerShell)**

```powershell
py -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

4. Com a placa em BOOT, execute o comando de atualização do seu sistema:

**macOS e Linux**

```sh
.venv/bin/python instalar.py
```

**Windows (PowerShell)**

```powershell
.\.venv\Scripts\python.exe instalar.py
```

Se houver mais de uma placa, acrescente `--porta` e a porta correta, por exemplo `/dev/cu.usbmodem14101` no macOS, `/dev/ttyACM0` no Linux ou `COM3` no Windows.

Para uma placa nova com tabela de partições vazia, acrescente `--placa-nova`. Não use essa opção em uma carteira já configurada. Para verificar o pacote e a tabela da placa sem gravar, acrescente `--verificar` ao comando do modo escolhido.

5. Aguarde a conclusão e o reinício. A atualização normal grava apenas a aplicação e verifica a compatibilidade da tabela de partições, preservando carteiras, apelido, PIN e pareamentos. Com a carteira iniciada, a porta USB desaparece: o USB fica apenas para alimentação e o app usa Bluetooth. Se a placa permanecer no carregador, solte BOOT e pressione RESET uma vez.

O ZIP também inclui um `README.md` com instruções detalhadas e diagnóstico.

## Documentação, manutenção e licenças

- [Detalhes e histórico do porte ESP32](esp32/README.md): contém registros de revisões anteriores.
- [Proveniência da TrezorCrypto](esp32/vendor/trezor-crypto-4.4.2/provenance.json) e [licenças upstream](esp32/vendor/trezor-crypto-4.4.2/crypto/LICENSE).
- [Política de periféricos](esp32/idf_components/airlock_policy/README.md).
- [Padrões de comentários e formatação](../docs/CODE_STYLE.md).
- [Regras para firmware distribuído](AGENTS.md).

O código reaproveitado do AirLock preserva a autoria e a licença [GPL-3.0](LICENSE). Bibliotecas de terceiros mantêm suas próprias licenças e avisos. Ao atualizar uma biblioteca criptográfica ou um contrato cadastrado, registre a versão/origem, atualize a referência e execute as verificações correspondentes.
