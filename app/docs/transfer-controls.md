# Envio: RPC/API, saldo e limite do Amount

## Habilitação do SEND

O app mantém suas rotas diretas: redes EVM com RPC configurado, Solana e envio Cardano pela API Koios. Para ativos sem uma rota direta implementada, verifica a capacidade de envio da API selecionada. Uma API que apenas consulta saldo/histórico não habilita envio por si só. Tokens precisam de suporte ao formato de pagamento, não apenas de um endpoint para a rede.

Exemplos: ETH e SOL continuam habilitados com Blockstream selecionado, pois usam rotas próprias. LTC fica desabilitado com Blockstream e habilitado com Freedom Street. Bitcoin não é habilitado somente pela consulta de saldo do Moralis. Monero continua exigindo seu fluxo de conexão, sincronização e saldo desbloqueado.

A capacidade é centralizada em `ProviderCoverage.hasDirectSendRoute`, `selectedApiSupportsSend` e `canSubmit`, em `NetworkSettings.kt`. A preparação de envio também revalida a capacidade antes de consultar taxas ou pedir assinatura.

## Saldo e Amount

Na tela Send, o saldo disponível e o símbolo aparecem ao lado do logo. O limite considera o endereço e o token selecionados; Monero usa somente `unlockedBalance`, ou zero quando desconhecido. Não há soma de saldos de outros endereços.

A máscara permanece da direita para a esquerda. Uma edição que ultrapassa o saldo é recusada sem arredondar, e o campo mostra o erro. Rascunhos restaurados acima do saldo continuam visíveis com erro; o botão de avançar fica desabilitado. O ViewModel repete a verificação antes de estimar a transação. Valores iguais ao saldo são aceitos no campo; as taxas são verificadas no fluxo de preparação.

## Variável de teste

Arquivo: `shared/src/commonMain/kotlin/pub/airlock/domain/TransferTestConfig.kt`.

```kotlin
const val TEST_MODE = true
```

- `false`: Amount limitado ao saldo disponível; não permite o teste sintético.
- `true`: libera a digitação acima do saldo. Em Bitcoin, se a estimativa retornar `INSUFFICIENT_BALANCE`, prepara um teste de assinatura com entrada sintética, identificado na confirmação.

É uma configuração de compilação: altere e recompile/reinstale o app. O teste Bitcoin constrói uma transação anterior sintética usando apenas o endereço público da carteira. Ela referencia uma entrada nula inválida, não representa saldo da rede e nunca mistura UTXOs reais. O usuário confirma o teste; o app conecta à cold wallet e usa o mesmo protocolo `sign_tx`. A assinatura permanece no hardware. O app verifica criptograficamente a transação devolvida e tenta enviá-la uma vez à API selecionada.

A API deve rejeitar a transação por não haver financiamento real. HTTP 400/422 é apresentado como rejeição, separado de indisponibilidade da API. A rejeição não cria registro na blockchain e, por si só, não comprova validação da assinatura pelo nó. O diagnóstico de assinatura é a verificação criptográfica local da resposta do hardware. O teste não grava pagamento ou pendência e não altera saldos. Sem o modo de teste, o bloqueio por saldo continua normal. Outras moedas mantêm suas verificações de financiamento.

Código: `data/network/BitcoinSignatureTest.kt`, `presentation/WalletViewModel.kt` e `data/ProductionWalletNetwork.kt`. Nenhuma chave privada é solicitada ao hardware, gerada ou armazenada pelo caminho de assinatura Bitcoin do app. As chaves públicas de teste dos testes automatizados não são empacotadas no aplicativo.

Validação do teste Bitcoin: 236 testes passaram, sem falhas, erros ou ignorados; Android Debug e código compartilhado iOS compilaram. Os novos testes cobrem assinatura e verificação criptográfica da entrada sintética, alteração inválida da transação, ordem assinatura → verificação → envio, HTTP de rejeição versus indisponibilidade, ausência de pagamento/pendência e bloqueio com teste desativado. A assinatura na placa física ainda precisa ser repetida. Registro: `bitcoin-signature-test-validation.json`.

## Validação

Suíte completa: 229 testes aprovados, zero falhas, zero erros e zero testes ignorados. Os sete novos testes cobrem API versus RPC, formato de token, saldo exato em cada precisão, zero, modo de teste, Monero, taxas e bloqueio antes da estimativa. A contagem e os logs constam em `transfer-controls-validation.json`.

Android e iOS Debug compilaram. O APK foi atualizado no Samsung A54 USB com `adb install -r`, preservando os dados existentes.
