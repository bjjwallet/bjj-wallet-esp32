# BJJ AirLock

O BJJ-App-Wallet e o BJJ-Cold-Wallet são os dois componentes do BJJ AirLock. A pasta comum é `bjj-airlock/`. Os nomes dos produtos são usados nas respectivas interfaces.

| Componente | Pasta | Responsabilidade |
|---|---|---|
| BJJ-App-Wallet | `app/` | App Android e iOS em Kotlin/Compose Multiplatform. Sincroniza contas, consulta provedores, prepara transações, valida as assinaturas recebidas e transmite pela rede. [README e build](../README.md) |
| BJJ-Cold-Wallet | `hardware/` | Firmware C/C++ para ESP32-S3 em `esp32/`, com simulador macOS. Cria e recupera carteiras, protege as chaves de gasto, revisa transações no visor e assina localmente. A pasta também contém a implementação original Python/JavaScript. [README do ESP32](../../hardware/esp32/README.md) |

## Contratos entre app e firmware

- **Primeiro acesso e PINs.** O app começa pela busca BLE. A conexão requer aceite local na cold wallet e, se configurado, o PIN da cold wallet. O desbloqueio do app pode usar PIN próprio ou biometria. O app mantém a assinatura de notificações BLE até terminar a sessão de sincronização e descarta o PIN da cold wallet após a autenticação. [Validação do primeiro acesso e da sessão BLE](VALIDATION.md#fluxo-completo-e-confirmação-da-conexão--2026-11-08)
- **Sincronização pública v1.** Após autorização, o cliente lê `get_device_info`, consulta o manifesto `get_wallets` e exporta contas com `get_accounts` nos modos `STANDARD` e `HIDDEN`, na mesma sessão BLE. O ID estável da cold identifica a carteira e o modo delimita as contas. O app grava os dois modos juntos após verificar a consistência. A exportação inclui endereços, caminhos e chaves estendidas públicas conforme a rede. O visor da cold controla a carteira utilizada na assinatura. [Contrato no app](../README.md#contrato-de-sincronização-pública-v1) · [Contrato no firmware](../../hardware/esp32/README.md#sincronização-de-múltiplas-carteiras)
- **Assinatura UTXO.** BTC, LTC, DOGE, DASH e BCH usam o contrato `utxo_signing_version` com revisão e aprovação locais. O app valida a resposta e guarda os bytes assinados antes da transmissão para poder retomar uma tentativa de resultado incerto. [Contrato, limites e testes cruzados](UTXO_SIGNING.md) · [Implementação ESP32](../../hardware/esp32/docs/UTXO_SIGNING.md)
- **Monero.** O firmware de stagenet anuncia capacidades próprias. A sincronização de visualização usa autorização explícita na cold, e o saldo reconciliado usa imagens de chave assinadas. A chave privada de gasto fica no hardware. O contrato de assinatura de stagenet tem limites próprios de formato, entradas e destinatário. [Contrato e build Monero](../../hardware/esp32/README.md#monero-stagenet-por-bluetooth)

## Decisões e pendências das conversas

- **Contas e provedores.** A navegação distingue redes com ativos de moedas que abrem diretamente no histórico. A cobertura depende do par rede/ativo, do provedor selecionado e da operação solicitada. A Moralis tem saldo e histórico de BTC e das redes EVM configuradas. Em Solana, a entrega registrou consultas de saldo e histórico completo pendente. O envio usa outro provedor. A validação com chave real da Moralis ficou pendente. [Melhorar seleção de redes e moedas](codex://threads/01a1dcfc-0e05-7681-80c3-bca9630d42c9)
- **BRZ na BNB Smart Chain.** A entrega cruzou consulta por RPC, revisão e assinatura pela ESP32 e validação do resultado no app. Os testes focados passaram com RPC simulado e assinatura produzida pelo código do firmware. A validação com a placa e uma transferência real ficou pendente. [Testar fluxo de inicialização da wallet](codex://threads/01a1bbc1-9be9-7883-8441-01ab90db0673)
- **Bluetooth físico.** A correção da sessão BLE foi testada, mas a confirmação na placa real seguia pendente no relato. O teste de Monero também registrou um caso de descoberta Bluetooth no Android em que o dispositivo sequer apareceu para seleção. [Implementar fluxo de conexão com wallet](codex://threads/01a1bd5a-def1-7d02-8b4a-7afbd15816a2) · [Atualizar redes de consulta](codex://threads/01a1c392-cd6e-7562-b519-a31c0c90d9ff)
- **Notificações noturnas de transações.** O plano foi retirado do escopo a pedido do usuário. [Planejar notificações de transações](codex://threads/01a1dcc7-8a61-7573-a306-19da502df54c)

As validações acima refletem as entregas registradas. Para uma mudança nova, use a [matriz de migração](MIGRATION.md), o [registro de validação mobile](VALIDATION.md) e a documentação da capacidade correspondente no firmware.
