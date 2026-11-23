# Assinatura e envio dos ativos cadastrados — 21/11/2026

## Resultado

86 ativos do catálogo (23 moedas nativas e 63 tokens em 23 redes), todos com assinatura local verificada. Nenhuma moeda ou rede foi adicionada ao catálogo.

- 251 testes do app aprovados, sem falhas ou testes ignorados.
- 23 testes CTest do firmware aprovados.
- 2 testes Rust do Monero aprovados: transação RingCT real com CLSAG, Bulletproof+ e conservação de compromissos; rejeição de revisão alterada, chave incorreta e arquivo truncado.
- 17 vetores dos novos protocolos conferidos por SDKs independentes, incluindo consolidação USDC/Sui e inicialização da carteira TON.
- APK Android, compilação iOS Simulator ARM64 e firmware ESP32-S3 concluídos.

## Implementação

Os 19 ativos antes pendentes são TRX/USDC/USDT na TRON; XRP/RLUSD/USDC no XRP Ledger; TON/USDT na TON; SUI/USDC na Sui; XLM/USDC/BRLP/BRZ na Stellar; e SNEK/MIN/IAG/WMTX/DJED na Cardano.

As rotas novas passam pelo PIN da conexão, revisão física na cold wallet e Transaction Phrase preenchida no hardware. O firmware reconstrói os dados do pagamento e confere a carteira remetente. O app recebe somente dados públicos e a assinatura, verifica a assinatura e só então prepara o envio. Rejeição no hardware impede o envio. Os testes usam um transporte simulado para aprovar/rejeitar; o fluxo não foi repetido fisicamente nesta execução.

Identidades dos tokens ficam fixadas no catálogo gerado do firmware. Sui usa GraphQL; seus tokens usam chamada com tipo explícito, impedindo que um objeto de outra moeda seja apresentado como USDC. USDT/TON deriva a carteira jetton no firmware com o master cadastrado e hash de código fixado. Cardano conserva o ADA da saída e da devolução; outros ativos em UTXOs não selecionáveis ficam intactos. A tela de revisão formata taxas pela precisão da moeda nativa.

## Limites dos testes

Não houve transmissão para rede real, instalação, reset ou movimentação de fundos. A aprovação nos testes comprova a assinatura e a serialização local; não comprova aceitação de APIs ou confirmação em blockchain.

Monero mantém a rede stagenet existente. As entradas sintéticas pertencem a chaves públicas de teste; os segredos usados para produzi-las aparecem somente no teste Rust do firmware, sem entrar no app.

Cardano seleciona UTXOs de ADA puro ou que contenham apenas o token escolhido. UTXOs misturados com outros tokens são ignorados e podem exigir consolidação antes de um envio. TRON e TON usam estimativas conservadoras de taxa; o custo efetivo depende da execução. O hash de código da carteira USDT/TON está fixado e precisa de revisão se o emissor alterar sua implementação.

## Artefatos

- APK: `app/androidApp/build/outputs/apk/debug/androidApp-debug.apk`
- Firmware: `hardware/instaladores/BJJ-Cold-Wallet-ES3C28P-2026-11-21-allassets1.zip`
- Versão: `2026.11.21-allassets1`
- Hashes de todos os binários e política USB no diretório `firmware` do instalador. USB somente para alimentação, sem controle serial, sem alteração de eFuses. Carteiras e pareamentos preservados por atualização normal.

## Reprodução

```sh
cd app
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :shared:testDebugUnitTest :androidApp:assembleDebug :shared:compileKotlinIosSimulatorArm64 --offline
```

```sh
cargo test --offline --locked --manifest-path hardware/esp32/monero_signer/Cargo.toml --target-dir /tmp/airlock-monero-host-target
ctest --test-dir /tmp/airlock-access-full --output-on-failure
```

SDKs e versões fixados em `hardware/esp32/tests/protocol-reference/package-lock.json`. O script `hardware/esp32/tools/verify_ledger_vectors.cjs` usa somente chaves públicas e assinaturas dos vetores gerados pelo teste C++; não assina nem envia.

## Fontes dos formatos

- [TRON: assinatura e envio](https://developers.tron.network/docs/api-signature-and-broadcast-flow)
- [XRPL: formato binário](https://xrpl.org/docs/references/protocol/binary-format)
- [Cardano: requisitos de serialização para hardware wallets](https://cips.cardano.org/cip/CIP-21)
- [Sui: GraphQL](https://docs.sui.io/develop/accessing-data/graphql/query-with-graphql)
- [TON: Wallet V4](https://docs.ton.org/contracts/standard/wallets/v4)
- [TON: armazenamento e derivação da carteira jetton](https://github.com/ton-blockchain/stablecoin-contract/blob/main/contracts/jetton-utils.fc)

## Matriz por ativo

| Rede | Ativo | Casas | Assinatura local |
|---|---|---:|---|
| bitcoin | BTC | 8 | Verificada |
| ethereum | ETH | 18 | Verificada |
| ethereum | BNB (token) | 18 | Verificada |
| ethereum | USDT (token) | 6 | Verificada |
| ethereum | USDC (token) | 6 | Verificada |
| ethereum | USDS (token) | 18 | Verificada |
| ethereum | USDe (token) | 18 | Verificada |
| ethereum | RLUSD (token) | 18 | Verificada |
| ethereum | EURC (token) | 6 | Verificada |
| ethereum | BRZ (token) | 18 | Verificada |
| tron | TRX | 6 | Verificada |
| tron | USDC (token) | 6 | Verificada |
| tron | USDT (token) | 6 | Verificada |
| bsc | BNB | 18 | Verificada |
| bsc | BRZ (token) | 18 | Verificada |
| bsc | USDT (token) | 18 | Verificada |
| bsc | LINK (token) | 18 | Verificada |
| bsc | USDC (token) | 18 | Verificada |
| bsc | USDe (token) | 18 | Verificada |
| bsc | XRP (token) | 18 | Verificada |
| ripple | XRP | 6 | Verificada |
| ripple | RLUSD (token) | 15 | Verificada |
| ripple | USDC (token) | 15 | Verificada |
| dogecoin | DOGE | 8 | Verificada |
| solana | SOL | 9 | Verificada |
| solana | BRLP (token) | 6 | Verificada |
| solana | XRP (token) | 6 | Verificada |
| solana | MON (token) | 8 | Verificada |
| solana | USDT (token) | 6 | Verificada |
| solana | USDC (token) | 6 | Verificada |
| solana | BRZ (token) | 4 | Verificada |
| solana | SKR (token) | 6 | Verificada |
| solana | USDS (token) | 6 | Verificada |
| solana | PYUSD (token) | 6 | Verificada |
| solana | EURC (token) | 6 | Verificada |
| solana | LINK (token) | 9 | Verificada |
| solana | JUP (token) | 6 | Verificada |
| cardano | ADA | 6 | Verificada |
| cardano | SNEK (token) | 0 | Verificada |
| cardano | MIN (token) | 6 | Verificada |
| cardano | IAG (token) | 6 | Verificada |
| cardano | WMTX (token) | 6 | Verificada |
| cardano | DJED (token) | 6 | Verificada |
| bitcoincash | BCH | 8 | Verificada |
| ton | TON | 9 | Verificada |
| ton | USDT (token) | 6 | Verificada |
| avalanche | AVAX | 18 | Verificada |
| avalanche | USDT (token) | 6 | Verificada |
| avalanche | USDC (token) | 6 | Verificada |
| ethereumclassic | ETC | 18 | Verificada |
| litecoin | LTC | 8 | Verificada |
| arbitrum | ETH | 18 | Verificada |
| arbitrum | BRZ (token) | 18 | Verificada |
| arbitrum | USDC (token) | 6 | Verificada |
| arbitrum | ARB (token) | 18 | Verificada |
| arbitrum | USDT (token) | 6 | Verificada |
| arbitrum | USDS (token) | 18 | Verificada |
| arbitrum | LINK (token) | 18 | Verificada |
| arbitrum | USDe (token) | 18 | Verificada |
| polygon | POL | 18 | Verificada |
| polygon | USDC (token) | 6 | Verificada |
| polygon | USDT (token) | 6 | Verificada |
| polygon | LINK (token) | 18 | Verificada |
| polygon | BRZ (token) | 18 | Verificada |
| sui | SUI | 9 | Verificada |
| sui | USDC (token) | 6 | Verificada |
| dash | DASH | 8 | Verificada |
| ecash | XEC | 2 | Verificada |
| base | ETH | 18 | Verificada |
| base | USDC (token) | 6 | Verificada |
| base | USDS (token) | 18 | Verificada |
| base | EURC (token) | 6 | Verificada |
| base | LINK (token) | 18 | Verificada |
| base | BRZ (token) | 18 | Verificada |
| base | USDe (token) | 18 | Verificada |
| base | USDT (token) | 6 | Verificada |
| monero | XMR | 12 | Verificada |
| stellar | XLM | 7 | Verificada |
| stellar | USDC (token) | 7 | Verificada |
| stellar | BRLP (token) | 7 | Verificada |
| stellar | BRZ (token) | 7 | Verificada |
| hyperevm | HYPE | 18 | Verificada |
| hyperevm | USDC (token) | 6 | Verificada |
| hyperevm | LINK (token) | 18 | Verificada |
| robinhood | ETH | 18 | Verificada |
| robinhood | USDG (token) | 6 | Verificada |
