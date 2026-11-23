# Leitura do QR do destinatário

O campo de destino da tela de envio inclui um botão de leitura de QR no Android e no iOS. O resultado preenche somente o endereço. O valor digitado, a revisão da transação e a autorização no hardware continuam no fluxo existente.

- Android: câmera integrada à mesma Activity, com ZXing Android Embedded 4.3.0 e permissão solicitada ao tocar no botão.
- iOS: AVFoundation com apresentação sobre a tela existente e descrição de uso da câmera no Info.plist.
- Decodificação local, sem gravar imagens nem abrir URLs encontradas no QR.
- Cancelar preserva o destino anterior. Falhas e permissão negada mostram uma mensagem; há um atalho para as configurações do app.
- A câmera é encerrada ao sair do leitor ou colocar o app em segundo plano. O bloqueio do app em segundo plano permanece ativo.

São aceitos endereços simples e URIs de endereço com esquema correspondente à moeda selecionada, como `bitcoin:ENDERECO?amount=...`. Parâmetros opcionais não alteram o valor da transação. Instruções obrigatórias desconhecidas (`req-`), URLs e pedidos complexos são recusados. A validação de rede/endereço da preparação de envio permanece necessária.

## Validação

- Catálogos e referências de tradução: oito idiomas verificados.
- Testes focados: RecipientQrTest (5), UtxoPaymentFlowTest (2) e BuildParityTest (4) aprovados.
- iOS: build Xcode Debug para iOS Simulator e verificação de tipos Swift aprovados.
- APK debug compilado e atualizado no celular de teste USB NM7R110255 com `adb install -r`, preservando os dados.
- A execução inicial da suíte completa apresentou 19 falhas em testes de provedores/cobertura de rede (AccountVisibilityTest, BrzBscTest, CoinbaseProviderTest, NetworkProviderTest e NetworkRecoveryTest). Esses testes não foram alterados nesta tarefa. O log da execução está em `/private/tmp/bjj-qr-android-build.log`.
- A leitura com a câmera física precisa ser conferida com um QR de teste; a compilação e os testes de extração do destinatário não substituem esse teste.

A suíte completa foi posteriormente corrigida: 222 testes aprovados, nenhuma falha ou teste ignorado. Ver [network-validation.md](network-validation.md).
