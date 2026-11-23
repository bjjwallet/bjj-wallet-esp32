# Assinador Monero para ESP32-S3

A dependência é fixada em `Cargo.toml` na revisão
`a6b46701fb9b331006d7f2f2278bf51f123b1447`; use sempre `--locked`.
A reconstrução de 20/11/2026 usou o toolchain oficial esp-rs v1.90.0.0
(Rust 1.90.0-nightly abf50ae2e), com seu `rust-src` correspondente.

Com `RUSTC` e `RUSTDOC` apontando para esse toolchain:

```sh
cargo build -Zbuild-std=core,alloc --release --no-default-features \
  --target xtensa-esp32s3-none-elf --locked \
  --manifest-path hardware/esp32/monero_signer/Cargo.toml \
  --target-dir hardware/esp32/build-monero-signer-release
```

A saída é `xtensa-esp32s3-none-elf/release/libairlock_monero_signer.a`
dentro do diretório de destino. Passe seu caminho absoluto ao CMake em
`AIRLOCK_MONERO_SIGNER_ARCHIVE` e habilite `AIRLOCK_MONERO_STAGENET_SYNC=ON`.
A configuração de distribuição deve manter `AIRLOCK_USB_POWER_ONLY=ON`,
`AIRLOCK_BOARD_AGENT_CONTROL=OFF`, `AIRLOCK_BOARD_NO_TOUCH=OFF` e
`AIRLOCK_SETUP_SELFTEST=OFF`.

O relatório em `../docs/monero-signer-build-2026-11-20.json` contém os
hashes da biblioteca, do lockfile e do código. O build completo de distribuição
fica em `../build-release-2026-11-20`; o instalador correspondente fica em
`hardware/historico/instaladores/BJJ-Cold-Wallet-ES3C28P-2026-11-20` a partir da raiz.

A biblioteca não contém seeds, PINs nem dados de carteiras do usuário.
A integração usa os alocadores e a limpeza de memória do firmware e conserva
a confirmação física e a validação do plano de pagamento.
