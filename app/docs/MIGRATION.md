# Plano e estado da migração

Origem: `/Users/felippe/Desktop/airlock/app` (Vue 3, Quasar e Cordova).
Destino: `/Users/felippe/Desktop/bjj-airlock/app`.
Referência: https://www.airlock.pub/docs/en/app

Commit de referência encontrado no repositório original: `a1fa522312c22ff6be4908644451cb4b725dd2d6`. A inspeção considerou os arquivos locais, que podem incluir mudanças ainda não commitadas.

## Decisões confirmadas

- Nome do aplicativo: **BJJ-App-Wallet**.
- Sete idiomas em XMLs de Compose Resources: português do Brasil, inglês, chinês simplificado, espanhol, francês, italiano e alemão. A primeira abertura segue o aparelho; o seletor permite salvar um idioma ou voltar ao automático.
- Android e iOS; lógica e UI compartilhadas com Compose Multiplatform.
- MVVM, Koin, Coroutines/StateFlow e Ktor.
- Preservar paleta, recursos, funcionalidades e fluxos do original como critério final de aceitação.
- Instalação nova e nova sincronização; sem importação dos dados Cordova.
- Hardware AirLock indisponível; debug injeta serviços mock, release nunca usa mocks como fallback. A UI e o fluxo de operações são compartilhados.
- Nenhuma chave privada da carteira sai do hardware. O app usa chaves públicas, constrói requisições e recebe transações assinadas.
- Servidor escolhido manualmente entre AirLock, Alchemy, Ankr e Coinbase; nunca mudar servidor ou blockchain automaticamente. A lista acompanha a cobertura integrada, preservando todos os dados no cofre. Pendências incompatíveis permanecem visíveis com retomada desabilitada. URL somente leitura; campos e valores de credenciais acompanham o servidor selecionado.
- Antes de transmitir ou retomar, consultar o hash no servidor escolhido. Retomada reutiliza os bytes assinados salvos antes da primeira tentativa; falha na consulta não equivale a transação inexistente.

## Matriz de equivalência

| Funcionalidade original | Estado nesta entrega | Trabalho restante |
|---|---|---|
| Android | Projeto e APK debug | Teste em aparelho e configuração de release |
| iOS | Host SwiftUI + Compose compilado; fluxo inicial aprovado no simulador | Validar em aparelho físico |
| Setup de dois PINs | Implementado, quatro passos e teclado do sistema com validação compartilhada de caracteres | Comparação visual detalhada com Cordova |
| Login e modos STANDARD/HIDDEN | Implementado e testado | Política persistente de tentativas e revisão de privacidade do modo oculto |
| Persistência criptografada | AES-GCM nativo em arquivo atômico | Testes nativos de adulteração/restauração; avaliar SQLCipher para volume real |
| Contas e sincronização | DTOs públicos, GATT, 16 redes exportadas e Base derivada da xpub Ethereum; ativação offline, índices persistentes e modos separados | Validação com firmware atualizado e hardware físico |
| Ativos, busca e filtro de saldo | Catálogo USDT/USDC; consultas AirLock/Alchemy/Ankr/Coinbase e filtro por cobertura do adaptador; falhas preservam saldo anterior | Validar com APIs reais; paginação, filtros completos e catálogo remoto |
| Detalhes e histórico | Histórico local distingue simulação, envio aceito, recibo de sucesso e falha na rede | Histórico remoto, acompanhamento de confirmações e finalidade por rede |
| Envio | EVM legado EIP-155 em seis redes: estimativa, validação de payload/assinante, journal persistente e consulta por hash antes do broadcast; retomada manual sem nova assinatura; Base inclui L1 e operador | Assinatura física de produção, EIP-1559, estratégias das outras redes e validação real ponta a ponta |
| Recebimento | Derivação pública em 14 redes, lote público em três; QR/cópia/compartilhamento; mock apenas no debug | Validar com hardware físico e confirmar endereços no dispositivo |
| Dispositivo | Seis campos e menu de atualização/reinício/desligamento, com confirmação; dados/comandos mock | Testar scan/permissões/pairing físico; implementar reconexão de comandos e ações do dispositivo |
| API | Adaptadores separados AirLock/Alchemy/Ankr/Coinbase ligados ao cofre no release; testes HTTP controlados; seleção sem fallback automático | Testes com credenciais/backend reais e ampliação de cobertura por operação |
| BLE | Kable sobre GATT/CoreBluetooth, scan, permissões, pairing, MTU e RPC; conexão encerrada após sincronização | Testes físicos Android/iPhone/AirLock e recuperação de conexão |
| Derivação pública | ACINQ bitcoin-kmp/secp256k1 + Keccak; endereços comparados com vetores públicos independentes do Wallet Core 4.4.4 | Validar hardware físico; estratégias de assinatura permanecem no hardware |
| Ajustes | Lista de servidores, aviso de cobertura, credenciais no cofre, servidor AirLock HTTPS, idioma, alteração de PIN e links | Teste de conexão, atualizações, compartilhamento real |
| Cores, imagens e fontes | Paleta original, todos os arquivos de logos/moedas e Roboto importados | Ícones Bootstrap originais importados; comparação visual final em diferentes tamanhos de tela |
| Idiomas | Sete catálogos completos para as telas implementadas, seletor antes do login e nos ajustes, preferência persistida | Ampliar os sete catálogos junto com as funcionalidades restantes e revisar traduções com falantes nativos |

## Contratos já extraídos

### BLE

Serviço `0000beef-0000-1000-8000-00805f9b34fb`, notify `bef0`, write `bef1`, sufixo UUID Bluetooth padrão. JSON UTF-8 com terminador `###EOF###`, fragmentos de até 180 bytes, limitados adicionalmente pelo tamanho aceito pelo transporte. O decoder trabalha em bytes e limita o buffer a 1 MiB.

Requisição: `{ "id": "...", "method": "...", "params": {} }`.
Resposta: `{ "id": "...", "status": "success", "data": ... }` ou erro.

Métodos usados no original: `ping`, `get_accounts`, `get_account`, `create_account`, `get_device_info`, `sign_tx`, `reboot`, `shutdown`.

Comandos com efeito não são reenviados automaticamente. Timeout de assinatura representa resultado desconhecido; uma futura UI real deve orientar reconciliação antes de repetir. Kable gerencia notificações e transporte. Android pede permissões de Bluetooth (localização até API 30) e negocia MTU; iOS negocia MTU pelo CoreBluetooth. A leitura da característica criptografada provoca o pareamento do sistema. A confirmação física e a resistência a desconexões ainda precisam ser exercitadas com o AirLock.

### API

Servidor original: `https://api.airlock.pub`. A tela agora apresenta a URL de cada adaptador somente para leitura, preservando um endereço AirLock anteriormente salvo. Alchemy e Ankr exigem suas respectivas chaves; Coinbase exige `ClientAPIKey` pública do CDP. Só aparece o campo da credencial exigida pelo servidor selecionado.

Contratos: `/config/tokens`, `/prices`, `/balance/{coin}/{address}`, `/transaction/{coin}/{address}`, `/utxo/{coin}/{address}`, `/block/{coin}/{address}`, `/fee/{coin}`, `/seqno/{coin}/{address}`, `/nonce/{coin}/{address}`, `/estimateGas/{coin}/{address}`, `/accountResource/{coin}/{address}`, `/broadcast/{coin}`, `/version`.

Broadcast usa POST `{ "tx_hex": ... }` e espera `txid`. Tokens usam parâmetro `contract`. O cliente tem timeouts, HTTPS obrigatório e redirects desativados. Nenhuma chamada real de broadcast foi feita durante o desenvolvimento.

### Transações

EVM legado EIP-155 implementado para Ethereum, BNB Chain, Polygon, Arbitrum, Avalanche e Base por Alchemy/Ankr; Coinbase somente Base Mainnet (8453), com ETH e USDC nativo. Estimar nonce, gas price e gas limit com margem de 20%; validar chain ID, transferência nativa/ERC-20, taxa e assinante recuperado antes de persistir/transmitir. A transação pública assinada fica no cofre junto com a cotação e modo; consultas por hash e recibo precedem qualquer envio. Sucesso HTTP é aceitação, não confirmação final. Retomada manual nunca cria novo pagamento ou nova assinatura. Rejeição/timeout exige reconciliação; nenhuma troca automática ocorre.

Coinbase usa nonce `latest`, conforme os block tags documentados pelo CDP. O app persiste o nonce enviado e bloqueia outro pagamento local enquanto o nonce confirmado não avançar. Transações pendentes externas não são descobertas por esse controle. Alchemy/Ankr usam `pending`. Na Base, `GasPriceOracle` (`0x420000000000000000000000000000000000000f`) fornece `getL1FeeUpperBound(uint256)` e `getOperatorFee(uint256)`; ambos recebem margem de 20%. A confirmação apresenta taxa estimada, pois L1/operador não são limitados pelo campo de gas L2 assinado. Antes de transmitir, esses componentes são reconsultados e um aumento acima da estimativa aceita bloqueia a tentativa, preservando a pendência. Ausência do oráculo não vira taxa zero.

`BaseAccounts.register` migra cofres públicos existentes e complementa novas sincronizações, separadamente em STANDARD/HIDDEN. A xpub e o primeiro endereço vêm de Ethereum; saldo zero, ativação e índice próprios. Os campos novos de chave Coinbase, nonce de histórico e estimativas Base têm defaults compatíveis com o schema 1. Nenhuma nova chave privada, seed ou passphrase é criada/recebida pelo mobile.

O adaptador `ProductionHardwareWallet` ainda não implementa `sign`; portanto não existe envio real completo pelo app nesta entrega. Faltam assinatura física, EIP-1559, acompanhamento de finalização e estratégias UTXO, Tron, TON, Solana, XRP e Sui. AirLock mantém envio bloqueado até integrar consulta confiável por hash. Valores continuam como inteiros/decimais exatos, nunca Double. A cobertura de saldos por adaptador está no [README](../README.md#servidores-e-recuperação-de-falhas).

Referências consultadas em 20/10/2026: [Alchemy Node](https://www.alchemy.com/docs/reference/node-supported-chains), [Alchemy UTXO](https://www.alchemy.com/docs/bitcoin/utxo-migration-guide), [Ankr RPC](https://www.ankr.com/docs/rpc-service/chains/chains-list/), [Ankr preços](https://www.ankr.com/docs/advanced-api/token-methods/), [Ethereum JSON-RPC](https://ethereum.org/developers/docs/apis/json-rpc/) e [vetor público EIP-155](https://eips.ethereum.org/EIPS/eip-155). Os provedores podem oferecer mais redes que as já integradas neste app.

Referências Coinbase/Base: [CDP Node](https://docs.cdp.coinbase.com/data/node/quickstart), [autenticação ClientAPIKey](https://docs.cdp.coinbase.com/api-reference/v2/authentication), [RPC e block tags](https://docs.cdp.coinbase.com/api-reference/json-rpc-api/core), [preços públicos](https://docs.cdp.coinbase.com/coinbase-app/track-apis/prices), [taxas Base](https://docs.base.org/specifications/transactions/network-fees), [GasPriceOracle](https://github.com/ethereum-optimism/optimism/blob/develop/packages/contracts-bedrock/src/L2/GasPriceOracle.sol) e [USDC Circle](https://developers.circle.com/stablecoins/usdc-contract-addresses).

### Orientação adicional ao agente do hardware: Base

Não é necessária uma nova exportação pública para Base: o app reutiliza a xpub Ethereum de cada modo e deriva `m/44'/60'/0'/0/index`. Ao implementar `sign_tx` no C/C++, aceitar Base Mainnet com chain ID **8453**, confirmar a rede e o destinatário na tela do hardware e assinar o plano EVM legado EIP-155 com a chave derivada internamente. Validar transferências ETH e ERC-20; o USDC nativo cadastrado usa seis casas decimais e contrato `0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913`. Não enviar credenciais do provedor ao hardware. As estimativas L1/operador são metadados de apresentação e não campos assinados do payload legado; exibi-las como estimativa, sem prometer teto total de taxa. A implementação e a validação física da assinatura continuam a cargo do agente do firmware.

## Próximas entregas

1. Integrar a exportação pública ao dispatcher C++ e ao serviço BLE da ESP32; testar scan, permissões, pareamento e desconexão em Android/iPhone.
2. Validar os endereços derivados com o hardware e testar ampliação dos lotes Ed25519.
3. Validar as consultas implementadas com credenciais reais e ampliar cobertura; implementar histórico remoto e monitoramento de confirmações.
4. Integrar assinatura confirmada no hardware, EIP-1559 e estratégias de transação das outras redes.
5. Scanner, paginação, filtros por rede/token e ajustes restantes; validar QR/cópia/compartilhamento em aparelhos.
6. Comparação visual e funcional com o Cordova; testes físicos Android/iPhone/hardware e builds de distribuição.

## Critério de conclusão

Uma funcionalidade só será marcada como equivalente depois de passar por seus cenários normais e de erro. Build, mock e simulação não comprovam compatibilidade com hardware real. Esta entrega não deve ser tratada como carteira pronta para movimentar fundos.

Resultados da primeira entrega: [VALIDATION.md](VALIDATION.md).

## Alterações no hardware para sincronização pública

**Orientação corrigida em 20/10/2026.** O projeto correto é `/Users/felippe/Desktop/bjj-airlock/hardware/esp32`, em C/C++. O patch anterior para `/Users/felippe/Desktop/airlock/hardware` era da referência Python/JavaScript e foi retirado. Não o aplicar. As alterações abaixo devem ser feitas pelo agente responsável pelo hardware; nenhum arquivo desse projeto foi modificado por esta tarefa.

### Mensagem para o agente do hardware

Implementar a exportação pública das carteiras `STANDARD` (sem passphrase) e `HIDDEN` (com passphrase) na mesma conexão BLE, para que o app salve as chaves públicas e obtenha endereços offline. Não exportar palavras, seed, passphrase, senha de pagamento, chave privada ou keystores. O app não precisa desses dados. Toda derivação privada e assinatura permanece no hardware.

O código C++ já gera e salva chaves públicas estendidas e endereços dos dois modos em `simulator/native_wallet.cpp`. Ele usa corretamente `TWDerivationDefault`; não precisa da correção de enumeração do patch antigo. O simulador usa Wallet Core **4.4.2** nativo. O app foi comparado com vetores públicos 4.4.4; incluir comparação com os registros produzidos pelo C++ 4.4.2 nos testes de contrato.

O projeto documenta 163 entradas no hardware; o app solicita **16 redes na exportação pública**. Filtrar as redes solicitadas. Base é a 17ª rede do catálogo mobile e reutiliza os dados públicos Ethereum, sem ampliar essa solicitação ao hardware.

### Arquivos e responsabilidades

| Arquivo existente ou novo sugerido | Trabalho |
|---|---|
| `esp32/simulator/native_wallet.hpp` e `.cpp` | Expor consulta somente de leitura às tabelas públicas da geração ativa. Usar `CURRENT` resolvido internamente; nunca aceitar caminho de banco recebido pelo BLE. Não chamar `unlock()` para exportar dados já públicos. |
| Novo `esp32/core/public_wallet_service.hpp` | Definir registros públicos e interface de consulta independente de SQLite, filesystem e Wallet Core. O backend Mac pode consultar SQLite; a implementação ESP32 usará armazenamento interno. |
| Novo dispatcher C++, por exemplo `esp32/core/public_wallet_rpc.*` | Validar parâmetros e implementar `get_accounts` e `get_device_info` no envelope abaixo. Integrar à infraestrutura de dispatcher se outro agente já a tiver criado. |
| `esp32/core/device_service.hpp` | Ligar as requisições à conexão autenticada e aos controladores existentes de pareamento. A interface atual ainda não fornece um servidor BLE. |
| `esp32/hardware/ble_framing.*` e `esp32/core/config.hpp` | Reutilizar UUIDs, terminador, fragmentação e limites existentes. Não criar um segundo protocolo. |
| Serviço NimBLE/GATT, ainda pendente | Implementar scan/advertising, pareamento confirmado, vínculo, criptografia, escrita de comandos e notificações com controle de fluxo. Preferir MTU 247; o app atual negocia MTU e espera capacidade de notificações de 180 bytes. |
| CMake do host e componentes ESP-IDF | Adicionar os novos módulos nos alvos apropriados, sem ligar as bibliotecas Apple/SQLite do simulador à ESP32. |

**Limite atual confirmado no código:** `esp32/firmware/main/main.cpp` é a imagem de validação da placa; não executa uma carteira nem inicializa BLE. O porte do backend criptográfico/armazenamento para Xtensa, o serviço BLE e o dispatcher estão pendentes. Criar somente a consulta no simulador não conclui a integração no dispositivo. Reportar separadamente o que foi implementado/testado no Mac e na ESP32.

### Sequência exigida pelo app

Na mesma conexão autenticada, o app envia sequencialmente:

1. `get_device_info`.
2. `get_accounts`, `mode: STANDARD`, `include_all_addresses: true`.
3. `get_accounts`, `mode: HIDDEN`, `include_all_addresses: true`.
4. Após validar ambos, grava os dois conjuntos no cofre mobile e encerra a conexão.

O firmware deve permitir as duas consultas sem exigir reconexão e manter a mesma geração de carteira durante ambas. Se uma criação/importação substituir a geração durante a sessão, recusar a continuação; não misturar os dados públicos de carteiras diferentes. Coordenar leitura e jobs de geração/importação. Não apagar, recriar ou reimportar carteiras existentes como parte desta tarefa.

### Contrato JSON e Bluetooth

Os UUIDs já definidos em `core/config.hpp` coincidem com o app:

- Serviço: `0000beef-0000-1000-8000-00805f9b34fb`.
- Notificações: `0000bef0-0000-1000-8000-00805f9b34fb`.
- Comandos: `0000bef1-0000-1000-8000-00805f9b34fb`.
- Frame: JSON UTF-8 seguido por `###EOF###`; limite de 1 MiB. Usar `send_frame`, respeitando MTU, controle de fluxo e desconexão. Nunca inserir o terminador literal dentro de um campo JSON.
- Exigir conexão autenticada e criptografada antes de exportar. A leitura da característica `bef0` deve ser suportada porque o app a usa para iniciar o pareamento do sistema. Isso pode retornar um status curto; respostas RPC seguem pelas notificações.

Primeira requisição:

```json
{"id":"kmp-1","method":"get_device_info","params":{}}
```

Responder `{"id":"kmp-1","status":"success","data":{...}}`. Os campos de informação lidos atualmente pelo app são strings: `name`, `version`, `hardware`, `screen`, `CPU`. Informar valores reais; um campo indisponível pode ser omitido. `version` é a versão do firmware, não a versão do Wallet Core.

Requisição de dados públicos:

```json
{
  "id": "kmp-2",
  "method": "get_accounts",
  "params": {
    "mode": "STANDARD",
    "include_all_addresses": true,
    "coins": ["bitcoin", "ethereum", "tron", "smartchain", "ripple", "doge", "solana", "bitcoincash", "ton", "avalanchec", "classic", "litecoin", "arbitrum", "polygon", "sui", "dash"]
  }
}
```

Depois, repetir com `id: kmp-3` e `mode: HIDDEN`. O ID é opaco: devolver exatamente o recebido, sem depender do prefixo `kmp-` nem dos números destes exemplos. `mode` deve aceitar somente `STANDARD` ou `HIDDEN`; `include_all_addresses` deve ser booleano e `coins` uma lista de identificadores válidos, com limites de quantidade/tamanho. Rejeitar parâmetros inválidos com resposta de erro.

O campo `data` de sucesso é um **array**, contendo somente os registros públicos do modo e das redes solicitadas. Cada registro tem este contrato:

| Campo JSON | Tipo e origem |
|---|---|
| `coin`, `symbol`, `name` | Strings do registro da rede; preservar identificador e símbolo |
| `address` | String do endereço exportado pelo hardware |
| `xpub` | `extended_public_key` de `wallets_xpub`; string vazia para registros de `wallets_address` |
| `path` | Caminho completo do endereço, não apenas o caminho da conta BIP32 |
| `decimals` | Inteiro do registro |
| `blockchain`, `curve` | Strings do registro; `secp256k1` ou `ed25519` nas 16 redes atuais |
| `is_main` | Inteiro `0` para STANDARD e `1` para HIDDEN; não enviar booleano |
| `address_index` | Inteiro obrigatório para registros de `wallets_address`; omitir nos registros com xpub |

O app rejeita campos desconhecidos nos registros; não incluir IDs internos, dados de keystore ou metadados extras ali. Envelope de erro: `{"id":"kmp-2","status":"error","error":"PUBLIC_DATA_UNAVAILABLE"}`. Não colocar segredos na mensagem ou nos logs. O cliente usa `status` para decidir sucesso e ignora o conteúdo de `data` em erro.

### Consulta e derivação

No backend SQLite do Mac, usar statements preparados com `is_main = 0/1` e filtro das redes solicitadas. Consultar `wallets_xpub` para registros estendidos e `wallets_address` para lotes de endereços. `include_all_addresses: true` remove o filtro `address_index = 0`, mantendo obrigatoriamente os filtros de modo e rede. Ordenar o lote por `address_index` e recusar registros incompletos ou repetidos. Nunca consultar/descriptografar keystores para esta exportação.

As chaves estendidas das redes abaixo são de profundidade 3, conta endurecida `0`; o app deriva filhos públicos `/0/índice`. `path` no JSON identifica o primeiro endereço, conforme a tabela. Preservar as versões públicas atuais, sem converter para chave privada ou mudar a derivação.

| `coin` no protocolo | Caminho inicial | Exportação |
|---|---|---|
| `bitcoin` | `m/84'/0'/0'/0/0` | zpub |
| `ethereum`, `smartchain`, `avalanchec`, `arbitrum`, `polygon` | `m/44'/60'/0'/0/0` | xpub |
| `tron` | `m/44'/195'/0'/0/0` | xpub |
| `ripple` | `m/44'/144'/0'/0/0` | xpub |
| `doge` | `m/44'/3'/0'/0/0` | dgub |
| `bitcoincash` | `m/44'/145'/0'/0/0` | xpub |
| `classic` | `m/44'/61'/0'/0/0` | xpub |
| `litecoin` | `m/84'/2'/0'/0/0` | zpub |
| `dash` | `m/44'/5'/0'/0/0` | xpub |
| `solana` | `m/44'/501'/0'` | Lote público, último índice endurecido |
| `ton` | `m/44'/607'/0'` | Lote público, último índice endurecido |
| `sui` | `m/44'/784'/0'/0'/0'` | Lote público, último índice endurecido |

Os aliases do app são `smartchain → bsc`, `doge → dogecoin`, `avalanchec → avalanche` e `classic → ethereumclassic`. O firmware deve manter os identificadores da tabela. USDT/USDC compartilham o endereço da respectiva rede; não gerar xpubs específicas para esses tokens.

`core/config.hpp` define atualmente 10 endereços por rede de lote em STANDARD e 100 em HIDDEN. Para as 16 redes pedidas, isso corresponde a **43 registros públicos em STANDARD** e **313 em HIDDEN**, se os lotes estiverem completos. Consultar os registros existentes; aumentar as constantes não amplia automaticamente carteiras já salvas. Se for implementada ampliação, ela deve acontecer inteiramente no hardware, preservar índices anteriores e publicar o novo lote atomicamente. Não derivar filhos endurecidos de xpub no celular.

### Testes e comandos para o agente

Adicionar um teste C++ de contrato, sugerido como `public_wallet_export` no CTest, que use apenas fixtures públicas e banco temporário. Verificar: ambos os modos na mesma sessão; filtragem de 16 entre as 163 entradas; 43/313 registros quando configurado; correspondência de xpub/endereço/caminho; ausência de segredos; índices únicos; parâmetros e modo inválidos; banco indisponível; geração substituída durante a sessão; desconexão; JSON UTF-8 fragmentado; autorização/criptografia exigidas. Comparar a resposta com `PublicAccountImporter.kt`, `PublicNetworks.kt` e os testes públicos do app. Manter compatíveis os testes já existentes.

Comandos de build/teste do host, a executar pelo agente após implementar, sem iniciar o simulador nem abrir carteiras do usuário:

```sh
cd /Users/felippe/Desktop/bjj-airlock/hardware
cmake -S esp32 -B esp32/build-simulator \
  -DAIRLOCK_SIMULATOR=ON -DAIRLOCK_SANITIZERS=ON \
  -DAIRLOCK_LVGL_DIR="$PWD/esp32/firmware/managed_components/lvgl__lvgl" \
  -DOPENSSL_ROOT_DIR=/usr/local/opt/openssl@3 \
  -DCMAKE_BUILD_TYPE=Debug
cmake --build esp32/build-simulator --parallel 6
ctest --test-dir esp32/build-simulator \
  -R '^(portable_core|native_wallet|coin_support_matrix|public_wallet_export)$' \
  --output-on-failure
```

`public_wallet_export` é o novo teste a criar/registrar; ainda não existe no checkout inspecionado. Não considerar um filtro sem testes encontrados como validação. Os demais nomes foram verificados no CMake existente.

Após carregar o ambiente **ESP-IDF 5.4.2** já usado pelo projeto, compilar a placa:

```sh
cd /Users/felippe/Desktop/bjj-airlock/hardware
idf.py -C esp32/firmware build
```

Esse comando **não grava a placa**. O alvo atual ainda é de validação da ES3C28P; compilar esse alvo sozinho não prova que a sincronização está ligada. O agente deve relatar qual imagem integra o backend, o dispatcher e BLE e quais testes físicos foram executados. Preservar os bloqueios existentes de Wi-Fi e SD/microSD. Não acessar `esp32/simulator-wallets/` nem substituir carteiras reais nos testes.

### Acesso ao app e PIN opcional da cold wallet (novembro de 2026)

O primeiro acesso mobile começa em busca Bluetooth. A conexão selecionada permanece aberta enquanto o usuário escolhe a proteção do app (opcional, um PIN com confirmação em duas telas). O PIN local é derivado com salt pelo cofre nativo; não é enviado ao dispositivo. Nas próximas aberturas, a autenticação nativa tenta Face ID/Touch ID no iOS ou a biometria disponível no Android; cancelamento, indisponibilidade ou falha deixam o PIN do app disponível. O Android seleciona a modalidade no prompt do sistema.

Após essa escolha, `get_device_info` pode retornar `pin_required: true` (booleano). Ausência do campo mantém compatibilidade com o firmware de exportação pública existente, que não exige PIN. Se o campo for `false`, o app exporta os dois conjuntos públicos diretamente. Valor de tipo inválido é recusado.

Para firmware que exige PIN, implementar na **mesma sessão BLE**:

```json
{"id":"kmp-N","method":"verify_pin","params":{"pin":"<PIN digitado>"}}
```

Retornar `status: "success"`, `data: {"accepted": true}` ou `data: {"accepted": false}`. `false` significa somente PIN incorreto. Desconexões, falhas internas e erros de protocolo não devem se passar por rejeição de PIN. Não registrar o PIN. `accepted: true` autoriza os `get_accounts` subsequentes nessa sessão. O PIN da cold wallet não é persistido: é enviado somente ao dispositivo para validação. O mobile guarda apenas o verificador derivado do PIN de segurança do app, protegido pelo cofre nativo. A escolha de proteção fica em memória até a sincronização pública válida; então o verificador e os dados públicos são gravados juntos de forma atômica. Face ID ou digital permitem abrir o app sem digitar o PIN local. Cofres de uma versão anterior que continham `hardwareCredentials` são regravados sem esse campo na leitura, preservando os demais dados.

A primeira e segunda rejeições permitem tentar novamente. A terceira impõe 10 s; a quarta, 20 s; a quinta, 40 s, sucessivamente. O app persiste contador e prazo no cofre, bloqueia navegação durante a contagem e só zera ambos após `accepted: true`. Fechar/reabrir não reinicia o prazo. O sistema operacional continua permitindo suspender/encerrar o app. A proteção contra alteração do relógio ou cliente modificado precisa também ser imposta pelo firmware.

O firmware C++ local em `core/public_wallet_rpc.cpp` já despacha `get_device_info`, `verify_pin` e `get_accounts`. Isso confirma a implementação local do contrato; a versão gravada na placa e o fluxo físico ainda precisam ser validados. Nenhum firmware fora da pasta do app foi alterado nesta mudança.

Os adaptadores Demo/Mock agora estão exclusivamente em `commonTest`. Android e iOS usam BLE e rede reais também em Debug. Ao abrir um cofre antigo sem a configuração de proteção nova, registros públicos com marcadores `MOCK_PUBLIC_KEY_`/`MOCK-ADDRESS-` são removidos; registros reais são preservados.

O PIN de acesso à cold wallet aceita **4 a 12 dígitos ASCII (0–9)**. O telefone valida o formato antes de enviar `verify_pin`, preserva zeros à esquerda e não contabiliza formato inválido como tentativa rejeitada pelo dispositivo. Os campos de PIN oferecem um botão de olho para exibir/ocultar, iniciando ocultos. A política do PIN local do app permanece separada.

As escolhas de proteção do app só são habilitadas após resposta RPC válida da cold wallet (`get_device_info`); a conexão GATT por si só não basta. A confirmação da conexão é distinta da aceitação do PIN da cold wallet: a escolha da proteção local continua anterior à entrada do PIN remoto, quando solicitado.

**Ciclo de vida BLE:** mantenha as notificações habilitadas durante toda a sessão, desde `get_device_info`, passando por `verify_pin` quando exigido, até ambos os `get_accounts`. O firmware encerra a conexão ao desabilitar notificações. O cliente mobile foi corrigido para não cancelar a observação entre requisições. O agente do firmware deve preservar essa regra de segurança; não é necessário afrouxá-la.
