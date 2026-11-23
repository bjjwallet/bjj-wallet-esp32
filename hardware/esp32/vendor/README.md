# Dependências nativas de desenvolvimento

`wallet-core-4.4.2/` contém somente os headers e o framework macOS da
distribuição **oficial** do Trust Wallet Core 4.4.2. É ligado estaticamente ao
simulador C++. Não usa o pacote WASM nem executa Node/Python em runtime.
Esse framework Apple **não pode ser instalado na ESP32**.

- Origem: https://github.com/trustwallet/wallet-core/releases/download/4.4.2/TrustWalletCore-4.4.2.tar.xz
- SHA-256 conferido contra os metadados da release:
  `08adf033497a18d86768ff878b0b918023f54e1e62b4b9783310ef9c34c8b7d3`
- Componentes extraídos: `include/`, `License`,
  `WalletCoreCommon.xcframework/macos-arm64_x86_64/` e `Info.plist`.
- Licença: `wallet-core-4.4.2/License`.

`cjson/` é a cópia sem alterações do cJSON 1.7.18 incluído no ESP-IDF 5.4.2
usado neste projeto. Licença MIT em `cjson/LICENSE`. Hashes:

- `cJSON.c`: `f892717f26cf3d21b7108de50cea95b6e48092950427615fe1d8d9c46ea09cca`
- `cJSON.h`: `fda63720ffad1d007802f62204831018d167084c26e2ce08ad36f11529a90682`

O simulador também depende de SDL2, OpenSSL 3 e SQLite. O adaptador de
armazenamento e o backend Wallet Core para Xtensa continuam pendentes.

`monero-0.18.5.4/` contém as rotinas AES, Groestl, JH e Skein da tag
[`v0.18.5.4`](https://github.com/monero-project/monero/tree/v0.18.5.4/src/crypto)
e o cabeçalho `contrib/epee/include/int-util.h` da mesma tag. As licenças
estão nos arquivos de origem. O build renomeia a tabela `t_fn` para evitar
colisão com a implementação AES da Trezor.
