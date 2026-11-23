# Auditoria local de transferências — 21/11/2026

245 testes do app e 22 testes do firmware aprovados, sem falhas ou testes ignorados.

Preenchimento: 86 ativos (23 moedas nativas e 63 tokens), nos oito idiomas — 688 combinações. Testes automatizados de digitação, exclusão, substituição, restauração do valor, precisão decimal, leitura de endereço pelo parser de QR, limite pelo saldo e exceção explícita do modo de teste. O teste de QR cobre a extração do endereço; não equivale a validar a câmera ou o checksum de cada rede.

Assinatura criptográfica comprovada localmente em 66 ativos: 46 EVM (9 nativos e 37 tokens), 13 Solana (SOL e 12 tokens), 6 UTXO e ADA. Inclui Bitcoin como referência. Dados recém-gerados pelos assinadores C++ coincidem com os vetores verificados pelo app: 46 EVM, 24 SPL (com/sem criação da conta associada) e 12 UTXO (com/sem devolução). SOL e ADA também passam nos testes nativos e na verificação Ed25519 do app.

Casos negativos cobrem alterações nos dados aprovados e recusa de assinaturas inválidas; chamadas de envio são interceptadas por MockEngine. Disponibilidade do SEND foi testada por ativo/provedor; o Monero usa seu fluxo específico, já coberto por MoneroSyncTest.

## Limites observados

- Monero: fluxo de autorização, persistência/reenvio e provas de key images cobertos. O retorno da transferência no teste do app é simulado: não há comprovação local completa de CLSAG para uma transferência nesta suíte.
- 19 ativos ainda não têm envio/assinatura implementado: TRX/XRP/TON/SUI/XLM e seus tokens, além dos cinco tokens Cardano. ADA nativo possui suporte.
- Esta execução é local: não testa BLE físico, toque, câmera, disponibilidade real dos provedores ou confirmação em blockchain.
- Não houve alteração da configuração de teste, instalação, reset ou transmissão de transações.

## Matriz por ativo

| Rede | Ativo | Casas | Preenchimento | Assinatura local |
|---|---|---:|---|---|
| bitcoin | BTC | 8 | Aprovado | Verificada (UTXO) |
| ethereum | ETH | 18 | Aprovado | Verificada (EVM) |
| ethereum | BNB (token) | 18 | Aprovado | Verificada (EVM) |
| ethereum | USDT (token) | 6 | Aprovado | Verificada (EVM) |
| ethereum | USDC (token) | 6 | Aprovado | Verificada (EVM) |
| ethereum | USDS (token) | 18 | Aprovado | Verificada (EVM) |
| ethereum | USDe (token) | 18 | Aprovado | Verificada (EVM) |
| ethereum | RLUSD (token) | 18 | Aprovado | Verificada (EVM) |
| ethereum | EURC (token) | 6 | Aprovado | Verificada (EVM) |
| ethereum | BRZ (token) | 18 | Aprovado | Verificada (EVM) |
| tron | TRX | 6 | Aprovado | Envio não implementado |
| tron | USDC (token) | 6 | Aprovado | Envio não implementado |
| tron | USDT (token) | 6 | Aprovado | Envio não implementado |
| bsc | BNB | 18 | Aprovado | Verificada (EVM) |
| bsc | BRZ (token) | 18 | Aprovado | Verificada (EVM) |
| bsc | USDT (token) | 18 | Aprovado | Verificada (EVM) |
| bsc | LINK (token) | 18 | Aprovado | Verificada (EVM) |
| bsc | USDC (token) | 18 | Aprovado | Verificada (EVM) |
| bsc | USDe (token) | 18 | Aprovado | Verificada (EVM) |
| bsc | XRP (token) | 18 | Aprovado | Verificada (EVM) |
| ripple | XRP | 6 | Aprovado | Envio não implementado |
| ripple | RLUSD (token) | 15 | Aprovado | Envio não implementado |
| ripple | USDC (token) | 15 | Aprovado | Envio não implementado |
| dogecoin | DOGE | 8 | Aprovado | Verificada (UTXO) |
| solana | SOL | 9 | Aprovado | Verificada (Ed25519) |
| solana | BRLP (token) | 6 | Aprovado | Verificada (Ed25519) |
| solana | XRP (token) | 6 | Aprovado | Verificada (Ed25519) |
| solana | MON (token) | 8 | Aprovado | Verificada (Ed25519) |
| solana | USDT (token) | 6 | Aprovado | Verificada (Ed25519) |
| solana | USDC (token) | 6 | Aprovado | Verificada (Ed25519) |
| solana | BRZ (token) | 4 | Aprovado | Verificada (Ed25519) |
| solana | SKR (token) | 6 | Aprovado | Verificada (Ed25519) |
| solana | USDS (token) | 6 | Aprovado | Verificada (Ed25519) |
| solana | PYUSD (token) | 6 | Aprovado | Verificada (Ed25519) |
| solana | EURC (token) | 6 | Aprovado | Verificada (Ed25519) |
| solana | LINK (token) | 9 | Aprovado | Verificada (Ed25519) |
| solana | JUP (token) | 6 | Aprovado | Verificada (Ed25519) |
| cardano | ADA | 6 | Aprovado | Verificada (ADA) |
| cardano | SNEK (token) | 0 | Aprovado | Envio não implementado |
| cardano | MIN (token) | 6 | Aprovado | Envio não implementado |
| cardano | IAG (token) | 6 | Aprovado | Envio não implementado |
| cardano | WMTX (token) | 6 | Aprovado | Envio não implementado |
| cardano | DJED (token) | 6 | Aprovado | Envio não implementado |
| bitcoincash | BCH | 8 | Aprovado | Verificada (UTXO) |
| ton | TON | 9 | Aprovado | Envio não implementado |
| ton | USDT (token) | 6 | Aprovado | Envio não implementado |
| avalanche | AVAX | 18 | Aprovado | Verificada (EVM) |
| avalanche | USDT (token) | 6 | Aprovado | Verificada (EVM) |
| avalanche | USDC (token) | 6 | Aprovado | Verificada (EVM) |
| ethereumclassic | ETC | 18 | Aprovado | Verificada (EVM) |
| litecoin | LTC | 8 | Aprovado | Verificada (UTXO) |
| arbitrum | ETH | 18 | Aprovado | Verificada (EVM) |
| arbitrum | BRZ (token) | 18 | Aprovado | Verificada (EVM) |
| arbitrum | USDC (token) | 6 | Aprovado | Verificada (EVM) |
| arbitrum | ARB (token) | 18 | Aprovado | Verificada (EVM) |
| arbitrum | USDT (token) | 6 | Aprovado | Verificada (EVM) |
| arbitrum | USDS (token) | 18 | Aprovado | Verificada (EVM) |
| arbitrum | LINK (token) | 18 | Aprovado | Verificada (EVM) |
| arbitrum | USDe (token) | 18 | Aprovado | Verificada (EVM) |
| polygon | POL | 18 | Aprovado | Verificada (EVM) |
| polygon | USDC (token) | 6 | Aprovado | Verificada (EVM) |
| polygon | USDT (token) | 6 | Aprovado | Verificada (EVM) |
| polygon | LINK (token) | 18 | Aprovado | Verificada (EVM) |
| polygon | BRZ (token) | 18 | Aprovado | Verificada (EVM) |
| sui | SUI | 9 | Aprovado | Envio não implementado |
| sui | USDC (token) | 6 | Aprovado | Envio não implementado |
| dash | DASH | 8 | Aprovado | Verificada (UTXO) |
| ecash | XEC | 2 | Aprovado | Verificada (UTXO) |
| base | ETH | 18 | Aprovado | Verificada (EVM) |
| base | USDC (token) | 6 | Aprovado | Verificada (EVM) |
| base | USDS (token) | 18 | Aprovado | Verificada (EVM) |
| base | EURC (token) | 6 | Aprovado | Verificada (EVM) |
| base | LINK (token) | 18 | Aprovado | Verificada (EVM) |
| base | BRZ (token) | 18 | Aprovado | Verificada (EVM) |
| base | USDe (token) | 18 | Aprovado | Verificada (EVM) |
| base | USDT (token) | 6 | Aprovado | Verificada (EVM) |
| monero | XMR | 12 | Aprovado | Pendente: CLSAG completa |
| stellar | XLM | 7 | Aprovado | Envio não implementado |
| stellar | USDC (token) | 7 | Aprovado | Envio não implementado |
| stellar | BRLP (token) | 7 | Aprovado | Envio não implementado |
| stellar | BRZ (token) | 7 | Aprovado | Envio não implementado |
| hyperevm | HYPE | 18 | Aprovado | Verificada (EVM) |
| hyperevm | USDC (token) | 6 | Aprovado | Verificada (EVM) |
| hyperevm | LINK (token) | 18 | Aprovado | Verificada (EVM) |
| robinhood | ETH | 18 | Aprovado | Verificada (EVM) |
| robinhood | USDG (token) | 6 | Aprovado | Verificada (EVM) |
