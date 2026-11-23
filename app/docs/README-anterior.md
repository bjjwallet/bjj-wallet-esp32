# Registro anterior do README do app

Documento preservado em 22/11/2026 para consulta histórica. Algumas seções descrevem etapas anteriores da migração e da instalação. Consulte o [README atual do app](../README.md) para a visão vigente do projeto.

---

# BJJ-App-Wallet — Kotlin Multiplatform

BJJ-App-Wallet é a migração do aplicativo AirLock, originalmente em Vue/Quasar/Cordova, para Android e iOS com Compose Multiplatform. O nome exibido no aparelho e nas telas é **BJJ-App-Wallet**.

**Estado: versão de desenvolvimento (0.1.0), com integração física ainda em validação.** Debug e release usam Bluetooth, derivação pública e rede reais. Os adaptadores simulados existem apenas nos testes. O primeiro acesso começa na busca Bluetooth; a proteção opcional do app é oferecida depois da conexão. O PIN da cold wallet é separado e validado exclusivamente pelo dispositivo. O contrato novo de PIN remoto está documentado em [MIGRATION.md](MIGRATION.md).

## Executar

Requisitos: JDK 17 ou 21, Android SDK 36 e, para iOS, macOS com Xcode. Alvos mínimos iniciais: Android 10 e iOS 16. O projeto inclui Gradle Wrapper e catálogo de versões fixas.

1. Abra esta pasta no Android Studio.
2. Configure `local.properties` com `sdk.dir=/caminho/do/Android/sdk` (arquivo local, ignorado pelo Git).
3. Abra **View → Tool Windows → Build Variants** e selecione **debug** na linha `androidApp`.
4. Na barra de execução, selecione a configuração `androidApp`, escolha o aparelho ou emulador e pressione **Run**.

Se o botão Run abrir **Edit Configurations**, confira primeiro a variante em **Build Variants**. O `release` ainda não tem assinatura de distribuição configurada; o `debug` usa a assinatura de desenvolvimento e os serviços reais. Na configuração do tipo **Android App**, o módulo deve ser `BJJ-App-Wallet.androidApp` e **Launch** deve estar em **Default Activity**.

```sh
./gradlew :androidApp:assembleDebug
./gradlew :shared:testDebugUnitTest
```

O APK fica em `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

O app e o firmware atualizado da ESP32 oferecem os mesmos oito idiomas, na mesma ordem — Português, English, Español, Français, 简体中文, 日本語, Italiano e Deutsch. A tradução japonesa do app inclui todo o catálogo de textos. A opção **Idioma do aparelho** acompanha o idioma do Android ou iOS.

O catálogo público do app é usado para gerar a lista de redes e moedas do hardware. A disponibilidade de envio e saldo é verificada por ativo e provedor. Tokens que aguardam identificação do emissor ou integração permanecem no catálogo de desenvolvimento até sua validação.

Para atualizar preservando os dados, instale sobre o app existente com o mesmo identificador de pacote e assinatura. Em Android, use `adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk` para atualizar a variante debug. Em iOS, instale a compilação do mesmo bundle ID e equipe de assinatura sobre a instalação existente.

Para iOS, abra `iosApp/AirLock.xcodeproj`, selecione o scheme `AirLock` e um simulador. O projeto Xcode compila e incorpora o framework Kotlin automaticamente pelo Gradle; **não é necessário executar `pod install`**. O nome interno do projeto/scheme continua `AirLock`. Para aparelho físico, configure seu Development Team no Xcode.

```sh
xcodebuild -project iosApp/AirLock.xcodeproj -scheme AirLock \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath iosApp/DerivedData build
```

Mantenha a assinatura local padrão do simulador para exercitar o Keychain. Desativar `CODE_SIGNING_ALLOWED` serve apenas para verificar compilação e pode impedir o armazenamento protegido de funcionar em execução.

Os testes de interface estão no target `AirLockUITests`. O teste de primeiro acesso verifica a tela Bluetooth sem PIN, o alinhamento dos seletores e a persistência do idioma. Os testes compartilhados verificam os fluxos de segurança, inclusive bloqueio progressivo e retomada após reinício. A conexão e a biometria físicas precisam ser verificadas em aparelhos compatíveis.

## Experimentar o fluxo

O PIN de acesso da cold wallet é obrigatório: 6 a 32 caracteres, somente letras
ASCII e números, contendo maiúscula, minúscula e número. O teclado do mobile é
alfanumérico. Dispositivos que informem PIN opcional ou ausente são recusados.
PINs numéricos antigos devem ser atualizados fisicamente na cold wallet; isso
preserva as carteiras. A lista de celulares, exclusões e bloqueios fica em
**Configurações → Dispositivos do hardware**. O PIN local do app continua separado
e opcional. Essa política exige o novo firmware de 20/11/2026, já compilado com a biblioteca
de assinatura Monero recuperada. A instalação física da cold wallet foi concluída e verificada, preservando as
partições de carteiras e pareamentos.


1. Escolha idioma e tema nos seletores centralizados no topo.
2. Toque em **Conectar e sincronizar carteira** e selecione a cold wallet encontrada. Sem resultado, a tela permanece disponível para escanear novamente.
3. Após conectar, escolha se deseja proteger o app. Para ativar, digite o PIN e confirme em uma segunda etapa. Nas próximas aberturas, use a biometria nativa ou esse PIN.
4. Após o dispositivo confirmar `pin_required: true`, digite o PIN da cold wallet. A terceira rejeição bloqueia por 10 segundos; cada erro seguinte dobra o prazo. O prazo persiste após fechar o app e só o acerto zera a progressão.
5. Após autorização do dispositivo, o app importa os conjuntos públicos e grava o verificador do PIN de segurança do app junto com eles, na mesma operação atômica do cofre criptografado. O PIN da cold wallet nunca é persistido. Uma falha na sincronização preserva os dados e a proteção anteriores. O PIN local não seleciona uma carteira oculta.

No primeiro pareamento Android, confirme o mesmo código no celular e na cold wallet. Se o Android apresentar apenas uma notificação de solicitação de pareamento, abra-a e confirme antes de o prazo expirar. A sincronização comum inicia o pareamento explicitamente antes da leitura criptografada, assim como o fluxo Monero. A confirmação somente na cold wallet não conclui o pareamento do celular.

Não existe PIN padrão nem saldo de demonstração. As implementações de teste não entram nos binários do app. Registros públicos antigos identificados pelos marcadores `MOCK_`/`MOCK-`/`DEMO_`/`DEMO-` são removidos na migração para a configuração nova, preservando registros reais.

## Debug e release

Ambos usam os mesmos serviços reais e o mesmo fluxo de segurança. Operações de produção ainda não integradas falham explicitamente. Os identificadores continuam separados (`pub.airlock.kmp.dev` e `pub.airlock.kmp`).

A validação com hardware físico continua pendente. O firmware local já implementa `pin_required`/`verify_pin`; a versão da placa ainda precisa ser confirmada. As notificações BLE permanecem habilitadas por toda a sessão de autenticação e exportação pública. Consulte o contrato e as limitações em [MIGRATION.md](MIGRATION.md).

## Servidores e recuperação de falhas

Em **Ajustes → Servidor**, estão disponíveis **Blockstream Esplora**, **mempool.space**, **Freedom Street**, **AirLock** e **Moralis**. Os adaptadores Alchemy, Ankr e Coinbase foram removidos. Cofres com seleção antiga migram para Blockstream e descartam as chaves de API antigas, preservando contas, saldos e pendências.

| Servidor | Consulta de saldo |
| --- | --- |
| Blockstream Esplora | Bitcoin mainnet |
| mempool.space | Bitcoin mainnet |
| Freedom Street | Bitcoin, Bitcoin Cash, Litecoin, Dogecoin, Dash e eCash |
| AirLock | Contrato REST para as redes/ativos cobertos pela integração |
| Moralis | Saldo e histórico nas redes cobertas pela integração; exige chave de API |

A tela **Accounts → Account List (N)** exibe somente as redes/moedas adicionadas e habilitadas na carteira atual; a busca e o contador consideram essa lista. **Accounts → Add Account** mantém o catálogo completo para adicionar ou remover contas. A seleção do servidor não inclui automaticamente redes na Account List. O botão Enviar depende da integração da API com a moeda e a cold wallet. Para eCash (XEC), o saldo e o histórico ficam disponíveis na Freedom Street após sincronizar o endereço público.

Endpoints e contratos: [Esplora](https://github.com/Blockstream/esplora/blob/master/API.md), [mempool.space](https://mempool.space/docs/api/rest), [Freedom Street](https://docs.freedom.st/api). Os três exploradores públicos não exigem cadastro ou chave. Moralis exige sua própria chave de API.

A consulta inclui movimentos não confirmados e usa aritmética decimal exata. Respostas inválidas não substituem o saldo salvo. Não há troca automática de servidor. Accounts exibe todas as contas habilitadas, e Adicionar moeda oferece todas as moedas do catálogo ainda fora da lista, independentemente do servidor. Moedas sem dados públicos salvos também podem entrar na lista, com endereço pendente e saldo indisponível até a sincronização. Para moedas fora da cobertura, os saldos salvos e os endereços continuam visíveis; o app indica a limitação e não consulta nem envia por um servidor incompatível. Cotações USD não estão integradas aos exploradores nem ao Moralis; o contrato AirLock mantém a consulta de preços. As redes EVM e Solana usam seus próprios RPCs para consultar saldos, preparar e transmitir pagamentos dos ativos suportados. Essa prioridade também vale com AirLock ou Moralis selecionado; o histórico Moralis permanece separado. A escolha do explorador Bitcoin não troca a rede nem o endpoint EVM.

O envio pela tela consulta UTXOs e taxas, seleciona até 16 entradas do endereço escolhido e autentica seus valores/scripts pelas transações de origem. A confirmação mostra destinatário, valor, taxa e troco. O troco retorna ao mesmo endereço. Entradas P2WPKH são usadas para BTC/LTC e P2PKH para DOGE/DASH/BCH; tokens, inscrições, consolidação entre endereços e entradas Taproot não fazem parte deste fluxo.

A assinatura exige firmware com `utxo_signing_version: 1`. O app transmite ao hardware somente o plano público e a senha de pagamento, preservando as chaves no dispositivo. Os dois firmwares validam o plano e pedem confirmação física. O app confere os bytes devolvidos, entradas, saídas, taxa, assinaturas SIGHASH_ALL e proteção FORKID do BCH antes de gravar o diário e fazer qualquer POST. Firmware antigo não recebe pedidos de assinatura UTXO.

Antes de transmitir, o app consulta o hash calculado localmente. Somente HTTP 404 indica ausência; erro de rede, JSON inválido ou resposta inconsistente mantém o envio pendente. Depois de uma resposta incerta, **Consultar e retomar** usa os mesmos bytes salvos no cofre. Uma transação já encontrada não é retransmitida. Não há troca automática de servidor nem nova assinatura na retomada.

Contrato, testes e limites: [UTXO_SIGNING.md](UTXO_SIGNING.md). A validação usa transações sintéticas e não transmite fundos reais; a instalação e o teste físico do novo firmware continuam necessários.

## Reiniciar a sincronização pelo menu Me

A primeira opção de **Me** é **Sincronizar com a cold wallet**. Ela abre uma confirmação informando que o app será zerado. Cancelar preserva os dados. Confirmar apaga, em uma gravação atômica no cofre local, todas as carteiras salvas, endereços, saldos em cache, histórico, pendências, PIN do app e preferências. O app retorna ao fluxo inicial de sincronização, incluindo a escolha da proteção do app após conectar.

O reset não envia comandos de apagamento à cold wallet e não cancela transações já enviadas à blockchain. Se a gravação falhar, a carteira anterior permanece disponível e o fluxo de sincronização não começa. Para adicionar outra carteira preservando as existentes, use **Sincronizar outra carteira** no seletor do topo.

## Várias carteiras no app mobile

O nome da carteira ativa aparece no topo do app. Toque nele para abrir o bottom sheet, selecionar outra carteira, renomear uma carteira salva ou sincronizar outra. A seleção ativa e os nomes persistem após fechar o app.

Cada carteira mantém suas contas, endereços, saldos, índices de recebimento, histórico e transações pendentes separados, incluindo os dois modos exportados. Com `wallet_sync_version: 1`, o app usa o `wallet_id` da cold como identidade, e recebe os nomes e a configuração pública de `get_wallets`. Nomes iguais não juntam carteiras e renomear não altera a identidade. A carteira da instalação anterior é migrada automaticamente e vinculada ao ID da cold somente quando seus dados públicos permitem identificá-la sem ambiguidade. Dispositivos sem a nova capacidade continuam usando o formato anterior.

A troca descarta somente o rascunho de pagamento em tela e é bloqueada durante uma operação em andamento. Pendências assinadas continuam salvas na carteira de origem. A escolha no app não altera a carteira selecionada fisicamente no dispositivo; para sincronizar outra, selecione-a no dispositivo antes de importar seus dados públicos. Esta funcionalidade não modifica o firmware.

### Contrato de sincronização pública v1

Depois do aceite no dispositivo e do PIN quando necessário, o app consulta `get_device_info`. Se `wallet_sync_version` for `1`, consulta `get_wallets` e, na mesma conexão autorizada, faz `get_accounts` com `all_wallets: true`, `include_all_addresses: true`, todas as moedas públicas integradas e `mode: STANDARD`, repetindo com `mode: HIDDEN`.

O app valida que ambos os modos contêm exatamente os IDs do manifesto e que nomes/configurações são consistentes durante a operação. Ele rejeita IDs duplicados, grupos ausentes, modos trocados, campos secretos e configurações inválidas. Somente depois da validação completa faz uma gravação atômica no cofre, junto da proteção do app quando configurada no primeiro acesso. Uma falha de download, validação ou gravação preserva as carteiras anteriores.

No armazenamento, cada perfil possui `coldWalletId` e delimita o namespace de contas por modo/moeda e de endereços por caminho/índice. IDs locais de contas podem se repetir entre perfis sem misturar saldos ou histórico. A configuração salva usa `word_count`, `passphrase_enabled` e `imported`; não inclui a passphrase BIP39, seed, chave privada, PIN da cold nem transactionPhrase.

Para consulta direcionada, o adaptador oferece `synchronizeWallet(walletId, mode)`, usando `wallet_id` sem `all_wallets` e recebendo o array simples de contas. O enquadramento `###EOF###`, os IDs das requisições e a autenticação BLE permanecem no cliente existente. A assinatura continua exigindo a carteira correta selecionada no dispositivo; o contrato v1 descrito aqui cobre sincronização e consultas públicas.

## Contas e endereços sem o hardware

Uma única conexão busca os dados públicos das 16 redes suportadas para as duas carteiras, normal e com passphrase. Cada modo é solicitado explicitamente ao hardware e os dois conjuntos são gravados juntos; se uma exportação falhar, o cofre anterior é preservado. Cinco contas aparecem inicialmente; **Contas → +** ativa as demais usando os dados salvos, sem Bluetooth ou internet. O botão “+” de endereços salva o endereço e o próximo índice na mesma gravação criptografada. Reiniciar o app ou sincronizar novamente preserva esse progresso.

- Derivação pública local: Bitcoin, Ethereum, Tron, BNB Smart Chain, XRP, Dogecoin, Bitcoin Cash, Avalanche C-Chain, Ethereum Classic, Litecoin, Arbitrum, Polygon, Dash e Base.
- Lote de endereços exportados: Solana, TON e Sui. Seus caminhos atuais têm derivação endurecida; quando o lote acaba, o app pede novos dados do hardware. Reconectar só fornece novos endereços se o hardware tiver ampliado esse lote.

O PIN seleciona qual carteira aparece no app; as duas mantêm chaves, contas, endereços e índices separados. Não é necessário sincronizar novamente ao abrir o outro PIN. Chaves estendidas pertencem a uma conta e caminho de derivação: a implementação cobre a conta `0` e os caminhos padrão do firmware, sem criar novas contas BIP32 endurecidas. Redes EVM que usam o mesmo caminho podem compartilhar a mesma chave e endereço, mas seus ativos e saldos permanecem separados por rede. USDT/USDC usam o endereço da rede correspondente.

O catálogo tem 17 redes. A exportação do hardware continua solicitando as 16 originais: Base usa a xpub Ethereum do respectivo modo e o mesmo caminho `m/44'/60'/0'/0/index`. A conta Base é registrada desativada, com saldo zero e índice próprio; ativá-la não copia saldos nem transações de Ethereum. Cofres existentes recebem esse registro ao desbloquear, se já contiverem os dados públicos Ethereum compatíveis.

O mobile recebe apenas chaves públicas estendidas (`xpub`, `zpub` ou `dgub`) e endereços. A assinatura continua sendo responsabilidade exclusiva do hardware. No projeto C/C++ inspecionado, o backend da placa, BLE e o dispatcher ainda precisam ser integrados. Veja as [instruções para o agente do firmware C/C++](MIGRATION.md#alterações-no-hardware-para-sincronização-pública). A sincronização pública é independente dos adaptadores de consulta descritos acima.

## USDT, USDC e escolha de rede

Os tokens são listados dentro da conta de sua rede. A busca de contas também encontra USDT e USDC. Ao desbloquear, sincronizar ou adicionar uma conta/endereço, o catálogo registra os tokens compatíveis preservando o endereço, o caminho de derivação e os saldos já existentes. A inclusão em um cofre anterior começa com saldo zero, preservando os saldos já salvos.

| Token | Redes do catálogo inicial |
|---|---|
| USDT | Ethereum, Tron, Solana, Avalanche C-Chain e TON |
| USDC | Ethereum, Arbitrum, Avalanche C-Chain, Polygon, Solana, Sui e Base |

O catálogo usa os identificadores publicados pelos emissores: [Tether](https://tether.to/en/supported-protocols/) e [Circle](https://developers.circle.com/stablecoins/usdc-contract-addresses/), consultados em 20/10/2026. Não inclui versões de bridges, ativos com lastro mantido por terceiros nem todas as redes dos emissores. Cada combinação de token e rede é um ativo distinto; o símbolo sozinho não identifica um contrato.

Em **Enviar**, o seletor mostra as redes do token disponíveis no adaptador escolhido e permite usar aquelas cadastradas no modo de carteira aberto. Redes compatíveis ainda não adicionadas aparecem desabilitadas; use **Contas → +** para adicionar a conta correspondente. Se houver vários endereços na rede, escolha também o endereço de origem. Trocar a rede ou o endereço limpa destinatário e quantidade e exige uma nova estimativa. A seleção não faz bridge nem agrega saldos de redes diferentes.

A confirmação mostra rede, origem, destino, identificador do token e taxa na moeda nativa. O envio de tokens desconta a quantidade do token e a taxa do saldo nativo do mesmo endereço e rede. Saldo em outro endereço ou rede não cobre essa taxa. O app bloqueia a confirmação se faltar saldo do token ou da moeda nativa. USDT e USDC usam seis casas decimais; taxas mantêm a precisão da respectiva rede.

Novos endereços e tokens começam com saldo zero; os provedores reais atualizam os valores. A validação com hardware e APIs reais permanece necessária.

## Referência visual

As oito imagens de [App Setup](https://www.airlock.pub/docs/en/app) e os componentes Vue locais orientam o layout. As abas usam os próprios caminhos vetoriais Bootstrap `wallet2`, `cpu` e `person-circle`, extraídos de `@quasar/extras` do original, com ícones de 24 dp, texto de 12 sp e altura mínima de 72 dp. Não há indicador em formato de cápsula nem faixa de demonstração no topo.

O fluxo é contas → lista de ativos → detalhes → receber/enviar. Os detalhes substituem as abas por ações de recebimento/envio no rodapé. O botão “+” de contas abre o seletor de moedas; o da lista de ativos gera um endereço com a chave pública salva ou usa o próximo endereço do lote exportado pelo hardware. A tela de dispositivo mantém os seis campos e menu de ações; Ajustes preserva os grupos e cores dos ícones. Controles de formulário e superfícies modais continuam sendo Compose, portanto a equivalência visual integral ainda deve ser revisada em aparelhos de tamanhos diferentes.

## Entrada de valores

A quantidade aceita no máximo `Asset.decimals`: BTC 8, ETH/BNB 18, TRX/XRP/USDT/USDC 6, SOL/TON 9 nos dados atuais. A precisão vem do ativo, e não de uma regra global de duas casas. O mesmo componente atende ao envio e ao filtro de saldo mínimo.

Vírgula e ponto são aceitos como separador decimal e exibidos conforme o idioma escolhido. Durante a edição, os zeros finais são preservados, não se inserem separadores de milhar e não há arredondamento nem conversão para `Double`. Letras, notação científica, sinais, separadores duplicados e excesso de casas são rejeitados com aviso. Ao confirmar, o valor é convertido para uma string decimal canônica e depois para unidades inteiras da moeda. Taxas e débitos também usam aritmética exata.

Os testes de interface usam o plano `NoCapture`: captura automática desativada e sem chamadas explícitas de screenshot. Novas capturas só devem ser feitas quando solicitadas pelo usuário.

## Idiomas

Android e iOS compartilham os mesmos catálogos e seletor:

| Idioma | Nome no seletor | Código |
|---|---|---|
| Português do Brasil | Português | `pt` |
| Inglês | English | `en` |
| Chinês simplificado | 简体中文 | `zh` |
| Espanhol | Español | `es` |
| Francês | Français | `fr` |
| Italiano | Italiano | `it` |
| Alemão | Deutsch | `de` |

Novas instalações começam em **Idioma do aparelho**: o app usa o primeiro idioma suportado da lista de preferências do sistema, com inglês como fallback quando nenhum dos sete está disponível. Variações regionais, como `pt-BR`, `es-MX` e `fr-CA`, usam os textos do respectivo idioma.

O seletor fica disponível na configuração inicial, no login e em **Ajustes → Idioma**. A troca é aplicada sem reiniciar o app e a escolha explícita é salva no armazenamento criptografado, inclusive antes da criação dos PINs. A opção **Idioma do aparelho** remove essa preferência e volta a acompanhar o sistema. Instalações existentes preservam o idioma anteriormente salvo; podem voltar ao automático pelo seletor. O formato decimal acompanha o idioma efetivamente usado.

Os 154 textos de cada idioma cobrem as telas implementadas, diálogos, validações e campos de PIN. Nomes de moedas, símbolos, endereços e dados recebidos do hardware não são traduzidos. Documentação e política de privacidade externas continuam nos sites originais.

Os textos usam **Compose Multiplatform Resources**, em `shared/src/commonMain/composeResources/values/strings.xml` (inglês/fallback) e `values-pt`, `values-zh`, `values-es`, `values-fr`, `values-it`, `values-de`. As telas acessam referências geradas, como `stringResource(Res.string.app_name)`, e parâmetros posicionais XML, como `%1$s`. Não há carregador JSON nem substituição de traduções por regex. Os adaptadores Android/iOS aplicam o locale nativo consultado pelo Compose Resources; os mesmos XMLs atendem às duas plataformas.

Os dicionários completos importados do Cordova permanecem em `docs/original-i18n` apenas como referência histórica, fora dos recursos empacotados do aplicativo. Novas funcionalidades devem receber traduções nos sete XMLs ativos.

Execute `python3 scripts/check-translations.py` para validar chaves, parâmetros e cobertura das telas. `scripts/check.sh` inclui essa verificação, os testes unitários e o build Android (requer Python 3 e JDK).

## Entrada de PIN

O **PIN de acesso à cold wallet** é o mesmo usado para desbloquear o visor do hardware: de **4 a 12 dígitos numéricos**. Zeros à esquerda são preservados. O app envia esse texto ao dispositivo para validação e nunca o salva. Tanto o botão Desbloquear quanto a tecla Concluído do teclado enviam o pedido.

Somente uma resposta explícita `accepted: false` é considerada PIN incorreto. Bloqueio temporário, dispositivo ocupado e pedido de confirmação no visor recebem mensagens próprias. Se aparecer **PIN aceito. Sincronizando os dados públicos**, a validação já terminou; uma falha posterior pertence à sincronização. Os dados anteriores só são substituídos após validar e gravar a importação completa.

O app usa o teclado do sistema e aceita também um teclado físico conectado. Os campos ocultam os caracteres e desativam autocorreção e capitalização automática. O teclado desenhado dentro do app foi removido.

O **PIN local do app** tem de **6 a 32 caracteres**. São permitidos `A–Z`, `a–z`, `0–9`, espaço comum e os símbolos do teclado original: pontuação ASCII, exceto o acento grave isolado (U+0060), mais `€`, `£`, `¥` e `•`. Acentos, emojis, caracteres de outras escritas, quebras de linha, tabulações e caracteres invisíveis são bloqueados. Maiúsculas/minúsculas e espaços são preservados exatamente.

Uma digitação ou colagem inválida é rejeitada por inteiro, com aviso traduzido; o app não remove caracteres nem trunca o PIN silenciosamente. A mesma validação se aplica no repositório ao criar ou alterar um PIN.

## Campo Amount e casas decimais

O campo de quantidade usa a precisão da moeda ou do token na rede selecionada. O exemplo mostrado no campo acompanha essa precisão; valores com menos casas continuam válidos. Digitação, colagem e rascunhos restaurados passam pela mesma validação. Ponto e vírgula são aceitos como separador decimal e apresentados conforme o idioma. Casas excedentes são rejeitadas, sem truncamento ou arredondamento; os valores nunca passam por `Float` ou `Double`.

| Ativo/rede | Casas decimais |
| --- | ---: |
| BTC, LTC, DOGE, BCH e DASH | 8 |
| ADA e TRX | 6 |
| SOL | 9 |
| ETH, POL, BNB e AVAX | 18 |
| XMR | 12 |
| XEC | 2 |
| SNEK/Cardano | 0, somente inteiros |
| BRZ/Solana | 4 |
| USDC/Solana e Ethereum | 6 |
| USDC/BNB Smart Chain | 18 |
| USDC/Stellar | 7 |

As precisões completas estão em `CoinCatalog.kt` e `Stablecoins.kt`. Os testes de `DecimalInputTest` cobrem cada combinação cadastrada nos oito idiomas.

## Organização

- `androidApp`: aplicação Android e inicialização dos adaptadores nativos.
- `iosApp`: aplicação SwiftUI que hospeda Compose e implementa criptografia com CryptoKit/Keychain.
- `shared/domain`: modelos, interfaces e aritmética decimal exata.
- `shared/data`: repositório, persistência versionada, cliente HTTP e integração Bluetooth.
- `shared/ble`: UUIDs, enquadramento de mensagens e cliente RPC do protocolo original.
- `shared/presentation`: ViewModel e estado imutável com StateFlow.
- `shared/ui`: telas Compose, tema, recursos e campos de PIN com teclado do sistema.
- `shared/di`: grafo Koin isolado por aplicação.
- `shared/commonTest`: testes compartilhados de comportamento.

Os caminhos acima são relativos a `shared/src/commonMain/kotlin/pub/airlock`, exceto `commonTest`, que está em `shared/src/commonTest`.

## Persistência

Esta etapa usa **arquivo JSON versionado, criptografado e autenticado com AES-256-GCM**, com gravação atômica. Android protege a chave com Android Keystore; iOS usa Keychain com `WhenUnlockedThisDeviceOnly`. O arquivo é excluído dos backups. Falhas de leitura, autenticação ou versão não criam silenciosamente um banco vazio.

Os verificadores de PIN usam PBKDF2-HMAC-SHA256, salt aleatório de 16 bytes e 100.000 iterações, preservando o algoritmo do Cordova. A seed e as chaves privadas de gasto permanecem no hardware. Apenas para Monero, conforme autorizado, o app pode guardar a chave privada de visualização no cofre criptografado e usá-la no scanner local. Ela não permite gastar e não é enviada ao nó remoto. O PIN do app controla o desbloqueio local; a chave de criptografia é protegida pelo sistema operacional, não derivada do PIN. O PIN da cold wallet é usado somente na autenticação com o dispositivo e nunca é persistido. Apenas o verificador derivado do PIN do app é salvo no cofre AES-GCM, com chave no Android Keystore ou Keychain iOS. Na primeira conexão, esse verificador é gravado junto com a sincronização pública concluída. Face ID ou digital podem desbloquear o app sem digitar esse PIN.

Esse armazenamento atende à primeira fatia e ao requisito de armazenamento criptografado. A estratégia para grandes históricos, proteção adicional do modo oculto e eventual SQLDelight/SQLCipher está registrada em `docs/MIGRATION.md`. Não houve migração dos dados de uma instalação antiga, conforme combinado.

## Continuidade

### Mesmo catálogo no app e na cold wallet

`CoinCatalog.kt` e `Stablecoins.kt`, em `shared/src/commonMain/kotlin/pub/airlock/domain`, definem as redes e moedas das duas plataformas. O menu de recebimento do hardware é gerado dessas listas; cada token é identificado junto de sua rede.

Após alterar o catálogo, execute na raiz do sistema:

```sh
python3 hardware/esp32/tools/generate_address_catalog.py
python3 hardware/esp32/tools/generate_address_catalog.py --check
```

A verificação confere a lista do firmware e a existência dos ícones nas duas plataformas. O build do firmware falha se o menu estiver desatualizado. A presença no catálogo não indica que envio e assinatura estejam concluídos: essas capacidades dependem da integração de cada rede/token.

O [contexto integrado do BJJ AirLock](BJJ_AIRLOCK.md) reúne as decisões, os contratos de integração e o estado conhecido do app e do firmware.

Consulte [a matriz de migração](MIGRATION.md) para o que foi implementado, limites atuais e próximas entregas. Os testes com hardware físico dependem da disponibilidade dos aparelhos; integração das estratégias de transação e assinatura ainda é necessária.

Código e recursos reaproveitados do AirLock preservam a licença GPL-3.0-or-later e a autoria original. Veja `LICENSE`, `NOTICE.md` e `docs/licenses`.
