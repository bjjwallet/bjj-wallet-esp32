# Auditoria de tokens e RPCs

A leitura de saldo, a preparação e a transmissão dos ativos suportados de redes EVM e Solana priorizam seus próprios RPCs. A seleção de AirLock ou Moralis não troca esses endpoints. O histórico Moralis continua usando sua API quando selecionada; preços AirLock permanecem independentes.

O catálogo atual tem 49 tokens em redes com RPC de envio: 37 EVM e 12 Solana. Ethereum Classic tem rota nativa ETC, sem tokens cadastrados no catálogo atual.

| Rede | Tokens cadastrados | RPC |
| --- | --- | --- |
| ethereum | BRZ, BNB, USDT, USDC, USDS, USDe, RLUSD, EURC | https://ethereum-rpc.publicnode.com |
| bsc | BRZ, USDT, LINK, USDC, USDe, XRP | https://bsc-dataseed.bnbchain.org |
| polygon | USDC, USDT, LINK, BRZ | https://polygon.drpc.org |
| arbitrum | BRZ, USDC, ARB, USDT, USDS, LINK, USDe | https://arb1.arbitrum.io/rpc |
| avalanche | USDT, USDC | https://api.avax.network/ext/bc/C/rpc |
| ethereumclassic | Nenhum | https://rpc.etcplanets.com |
| base | USDC, USDS, EURC, LINK, BRZ, USDe, USDT | https://mainnet.base.org |
| hyperevm | USDC, LINK | https://rpc.hyperliquid.xyz/evm |
| robinhood | USDG | https://rpc.mainnet.chain.robinhood.com |
| solana | BRLP, XRP, MON, USDT, USDC, BRZ, SKR, USDS, PYUSD, EURC, LINK, JUP | https://api.mainnet.solana.com |

Na Base, `eth_sendRawTransaction` usa o sequenciador `https://mainnet-sequencer.base.org`; consultas e preparação usam o RPC Base. Na Solana, SPL e Token-2022 são conferidos pelo mint, programa, precisão e extensões na preparação. PYUSD mantém os controles de taxa e hook; respostas incompatíveis continuam recusadas.

O SEND considera o suporte ao pagamento do ativo, não apenas a existência de um endpoint. Tokens desconhecidos ou redes sem preparação/assinatura implementadas permanecem desabilitados. A disponibilidade ao vivo de um serviço não é presumida pelos testes.

## Verificações

- 233 testes aprovados, sem falhas, erros ou testes ignorados.
- Cada um dos 49 tokens é conferido com as cinco opções de servidor selecionadas: saldo individual, saldo em lote, preparação, precisão, taxa na moeda nativa e SEND habilitado.
- Moedas nativas EVM e SOL também usam o próprio RPC com qualquer servidor selecionado.
- Os 37 tokens EVM passam pela validação de transações sintéticas com recuperação de assinatura e transmissão de bytes idênticos ao RPC da rede, em HTTP simulado.
- Os 12 tokens Solana passam por preparação e teste de transporte em HTTP simulado. Assinaturas inválidas são recusadas antes de qualquer requisição de transmissão. O teste de transporte usa bytes fictícios e não substitui um teste de assinatura física na cold wallet.
- Os decodificadores AirLock/Moralis continuam testados isoladamente. Não são usados para contornar o RPC próprio no saldo desses ativos.
- Android e iOS Debug compilaram. `TEST_MODE = true`, encontrado na configuração atual, foi preservado; o teste de limite normal injeta explicitamente `false`.

Bitcoin continua usando a API do explorador selecionado: Blockstream, mempool.space ou Freedom Street. Nenhum envio de fundos reais foi executado nesta auditoria.

Contagens e rotas: `token-rpc-audit.json`.

APK atualizado no Samsung A54 USB com `adb install -r`, preservando os dados.
