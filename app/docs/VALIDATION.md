# Validação de BJJ AirLock

## Atualização: Coinbase/Base e campos do servidor

Data: 20/10/2026. Coinbase foi integrada como quarto adaptador, para Base Mainnet (ETH e USDC nativo). Alchemy/Ankr também receberam Base, totalizando 13/9 redes de consulta; Coinbase cobre uma, AirLock mantém as 16 originais. A exportação pública continua com 16 redes por modo; Base reutiliza a xpub Ethereum sem hardware adicional, com saldos e índices separados.

A seleção de servidor apresenta URL HTTPS somente leitura e apenas a credencial exigida: nenhuma para AirLock, API key para Alchemy/Ankr, ClientAPIKey pública para Coinbase. URL e valor da chave acompanham a seleção; rascunhos de credenciais são preservados por servidor. As credenciais permanecem no cofre criptografado e deixam o estado da UI ao bloquear.

| Verificação | Resultado |
|---|---|
| `:shared:testDebugUnitTest` | 107 testes; zero falhas, erros ou testes ignorados |
| Novos testes Coinbase/Base | 10 testes com HTTP controlado, dados públicos e cofre de teste |
| `:androidApp:assembleDebug` e `:androidApp:assembleRelease` | Aprovados; release sem assinatura de distribuição |
| `:shared:linkDebugFrameworkIosSimulatorArm64` | Compilação e link aprovados |
| `scripts/check-translations.py` | Sete idiomas, 192 textos por idioma; chaves, parâmetros e referências conferidos |
| APIs reais, hardware físico, XCTest e capturas | Não executados nesta atualização |

Os cenários novos cobrem endpoint/chave CDP, chain ID 8453, recusa de Sepolia, saldos ETH/USDC, erro de autenticação sem troca de serviço, taxa Base com L1/operador, ausência do oráculo e aumento de taxa antes da transmissão. Validam nonce `latest` com bloqueio de outro pagamento local ainda pendente, consulta por hash/recibo antes de transmitir, retomada manual pela Alchemy com os mesmos bytes e rejeição de cotação sem taxa L1 ou de outra rede. Também cobrem migração pública dos dois modos, endereços/índices independentes, persistência, filtros reversíveis e bloqueio de retomada de Ethereum quando Coinbase está selecionada.

Um teste antigo de colisão foi atualizado para comparar o cofre completo antes/depois da tentativa recusada, incluindo a nova conta Base. O vetor de assinatura Base usa `r/s` públicos EIP-155 e recuperação da chave pública para a mensagem Base; não há chave privada nem assinatura de fundos nos testes.

**Limites:** não houve validação visual desta tela, acesso a credenciais reais ou envio de fundos. A assinatura em `ProductionHardwareWallet` permanece indisponível, e o envio real completo ainda depende do agente do firmware e da validação física. Coinbase integra somente Base; EIP-1559 e acompanhamento contínuo de finalidade continuam pendentes. A taxa Base é uma estimativa, e o controle local de nonce não identifica pagamentos pendentes criados em outros aplicativos.

Relatório: `shared/build/reports/tests/testDebugUnitTest/index.html`. Logs: `/tmp/airlock-coinbase-build.log` e `/tmp/airlock-coinbase-tests.log`. O segundo log corresponde à execução dos 107 testes após incluir o cenário adicional de aumento de taxa; os fontes de produção não mudaram depois dos builds.

## Atualização: Alchemy, Ankr e retomada manual

Data: 20/10/2026. AirLock, Alchemy e Ankr têm adaptadores separados e seleção persistida no cofre. A lista de contas, tokens e redes acompanha a cobertura integrada do servidor escolhido, sem excluir os dados salvos. O aviso de seleção informa essa diferença. A tela principal preserva saldos antigos quando a consulta falha e apresenta pendências mesmo quando sua moeda fica fora da lista atual. A retomada incompatível fica desabilitada e também é bloqueada no ViewModel.

| Verificação | Resultado |
|---|---|
| `:shared:testDebugUnitTest` | 97 testes; zero falhas, erros ou testes ignorados |
| Testes de provedores/recuperação | 25 testes novos; HTTP via MockEngine e cofre de teste |
| `:androidApp:assembleDebug` e `:androidApp:assembleRelease` | Aprovados; release sem assinatura de distribuição |
| `:shared:linkDebugFrameworkIosSimulatorArm64` | Compilação e link aprovados |
| Catálogos XML | Sete idiomas, 187 textos por idioma; chaves, parâmetros e referências conferidos |
| APIs reais, hardware físico, XCTest e capturas | Não executados nesta atualização |

Os testes verificam isolamento por provedor, troca apenas manual, erros HTTP/autenticação/limite sem fallback, cancelamento, chain ID incorreto, quantias exatas e ABI ERC-20. Consultas de saldo incluem EVM, UTXO Alchemy, múltiplas contas de token Solana, Tron e Sui. Preços usam contratos específicos de cada serviço.

O fluxo EVM legado usa o vetor público EIP-155 para validar hash, recuperação do assinante e rejeição de quantidade, taxa, destino, rede ou payload alterados. Antes de enviar, consulta hash e recibo; consulta indisponível impede envio. Timeout e erro “nonce too low” não são tratados como sucesso. Um recibo de falha impede reenvio, assim como uma transação já localizada. A tentativa manual em outro provedor reutiliza exatamente os mesmos bytes.

Os cenários de recuperação verificam gravação anterior ao broadcast, falha de armazenamento impedindo a primeira transmissão, reinício sem reenvio automático, isolamento dos modos normal/oculto, nenhuma nova assinatura na retomada e histórico sem duplicação. Também cobrem filtro reversível da lista, pendência incompatível preservada e bloqueada, rascunho incompatível sem nova consulta/envio, remoção das credenciais da UI ao bloquear e compatibilidade com cofres antigos.

A cobertura efetiva implementada está no [README](../README.md#servidores-e-recuperação-de-falhas): Alchemy consulta 12 redes do catálogo, Ankr oito, e AirLock mantém o contrato REST das 16. As duas novas integrações preparam/transmitem somente nas cinco redes EVM indicadas. Isso não descreve toda a oferta comercial dos provedores nem confirma as permissões de um plano.

**Limites:** a assinatura no `ProductionHardwareWallet` continua indisponível; o envio real completo ainda depende da integração com o agente do firmware e da validação física. Não houve acesso a credenciais, assinatura real ou movimentação de fundos. Não foi validada a renderização das novas telas em aparelho/simulador. Ainda faltam EIP-1559, estratégias de envio das demais redes, histórico remoto e acompanhamento contínuo de confirmação/finalidade. Aceitação pelo servidor permanece identificada como confirmação pendente, sem débito local artificial nos saldos de produção.

Relatório: `shared/build/reports/tests/testDebugUnitTest/index.html`. Log: `/tmp/airlock-network-build.log`. Após esses builds foram ajustados somente documentação e dois comentários de código, sem mudança de comportamento.

## Atualização: sincronização pública e endereços offline

Data: 20/10/2026. Uma conexão BLE solicita os dados públicos das carteiras `STANDARD` e `HIDDEN`, com as 16 redes de cada uma. A gravação é conjunta e atômica; o PIN exibe somente o respectivo conjunto. Cinco contas ficam visíveis inicialmente; as demais são ativadas offline em **Contas → +**. Novos endereços são derivados de chaves públicas em 13 redes, ou retirados dos lotes exportados de Solana, TON e Sui. O mobile não recebe seed, palavras, passphrase ou chave privada.

| Verificação | Resultado |
|---|---|
| `:shared:testDebugUnitTest` | 72 testes, zero falhas, erros ou testes ignorados |
| `:androidApp:assembleDebug` e `:androidApp:assembleRelease` | Aprovados; APKs atualizados, release sem assinatura de distribuição |
| `:shared:linkDebugFrameworkIosSimulatorArm64` | Compilação e link do framework aprovados, incluindo bibliotecas nativas de derivação e BLE |
| Vetores públicos | 160 comparações em 16 redes e dois conjuntos distintos: 130 derivações secp256k1 e 30 consultas ao lote Ed25519; índices 0, 1, 2, 17 e 1024 |
| Firmware C/C++ | Integração não validada. A verificação anterior usou a referência Python/JS incorreta; patch e script retirados |
| Catálogos XML | Sete idiomas, 154 textos em cada um; chaves, parâmetros e referências validados |
| Hardware físico / XCTest | Não executados nesta atualização; nenhum pareamento físico, instalação ou captura de tela |

Os testes verificam chaves diferentes para carteiras com/sem passphrase, validação de versão/checksum/profundidade/caminho, rejeição de versões privadas e campos secretos, índice não endurecido, equivalência com fixtures públicas independentes do Wallet Core 4.4.4 e vetores publicados do BIP84. O algoritmo criptográfico usa ACINQ bitcoin-kmp/secp256k1; o app implementa a seleção de rede e a codificação de endereços.

Também foram verificados: as duas exportações na mesma conexão antes da desconexão; persistência de 32 contas públicas; visibilidade por PIN após reiniciar sem outra sincronização; criação concorrente sem repetir índices; falha de gravação sem consumir um endereço; preservação de saldos/índices na ressincronização; rejeição atômica de dados incompatíveis; fim do lote sem modificar o cofre; ativação e criação offline sem chamada ao hardware; USDT/USDC seguindo o mesmo endereço e caminho da rede.

**Correção da referência do hardware:** o código correto é `/Users/felippe/Desktop/bjj-airlock/hardware/esp32`, em C/C++. O patch anterior foi preparado para a implementação de referência Python/JavaScript e não se aplica a esse firmware; foi removido junto com seu script de validação. Os resultados dos testes mobile continuam válidos, mas não comprovam integração com a ESP32. O simulador C++ já deriva e salva os registros públicos; backend da placa, BLE e dispatcher ainda estão pendentes. As [instruções corrigidas](MIGRATION.md#alterações-no-hardware-para-sincronização-pública) destinam-se ao agente do hardware. Nenhum arquivo do hardware foi modificado.

A compilação e os vetores não comprovam o pareamento nem a exportação num AirLock físico. O debug continua simulado; a sincronização pública/derivação real está no adaptador de produção. Consultas reais de saldo/histórico, taxas, assinatura e transmissão de transações pelo mobile continuam pendentes. Não houve assinatura ou movimentação real de fundos. Relatório: `shared/build/reports/tests/testDebugUnitTest/index.html`.


## Atualização: USDT, USDC e seleção de rede

Data: 20/10/2026. O catálogo inicial inclui onze combinações de token/rede, com cadastro automático nos endereços compatíveis. O envio permite escolher rede e endereço de origem, identifica o contrato e desconta a taxa da moeda nativa do mesmo endereço. A busca de contas encontra tokens; o recebimento identifica a rede.

| Verificação | Resultado |
|---|---|
| Testes unitários `:shared:testDebugUnitTest` | 53 testes, zero falhas, erros ou testes ignorados |
| Android `:androidApp:assembleDebug` | Aprovado; APK atualizado |
| Kotlin iOS `:shared:compileKotlinIosSimulatorArm64` | Aprovado |
| Catálogos XML | Sete idiomas, 144 textos em cada um; chaves, parâmetros e referências validados |
| XCTest | Cenário ampliado para recebimento USDT, seleção Ethereum/Tron, taxa em TRX e envio simulado; execução não autorizada, portanto não validado no simulador nesta atualização |

Os testes novos verificam precisão para todas as onze combinações, débito separado de token e taxa, impossibilidade de usar saldo nativo de outro endereço/rede, rejeição de contrato e cotação incompatíveis, preservação de saldos e IDs ao cadastrar tokens, persistência após envio, isolamento de modos, invalidação de confirmação e prevenção de envio duplicado por toques durante uma operação. A adição de endereços conta somente ativos nativos, sem saltar índices por causa dos tokens.

Não houve assinatura, envio real, acesso a API de produção, instalação em aparelho ou captura de tela. O debug permanece simulado; as estratégias reais de tokens e demais integrações de produção continuam pendentes. Relatório: `shared/build/reports/tests/testDebugUnitTest/index.html`.

## Atualização: strings XML e idioma do aparelho

Data: 15/10/2026. Os textos agora usam `composeResources/values*/strings.xml` e referências geradas `Res.string`, com acesso por `stringResource`. Foram removidos o carregador de traduções JSON e a substituição de parâmetros por regex. Os 945 textos anteriores (135 por idioma) foram preservados, convertendo apenas os parâmetros para o formato XML; cada catálogo recebeu também a opção **Idioma do aparelho**, totalizando 136 textos.

| Verificação | Resultado |
|---|---|
| Catálogos XML | Sete idiomas, 136 strings por idioma; chaves, parâmetros e referências validados |
| Testes unitários | 46 testes, zero falhas |
| Android `assembleDebug` | Aprovado; APK com sete catálogos XML compilados e sem JSONs de tradução |
| Kotlin iOS ARM64 | Compilação aprovada |
| Xcode e XCTest | Build aprovado; quatro testes de interface, zero falhas; pacote final com sete catálogos XML compilados e sem JSONs de tradução |

O teste de primeira abertura verificou os sete idiomas a partir das preferências nativas do simulador, incluindo variantes regionais, e o fallback para inglês em japonês. O teste de troca de idioma verificou os sete catálogos, a tradução de um erro já visível, a persistência da escolha após reiniciar com outro idioma no aparelho e o retorno ao automático, também após reiniciar. Os testes unitários cobrem a ordem das preferências do sistema, instalações novas, preservação de escolhas já salvas e falha ao gravar uma nova escolha.

Também passaram os testes de caracteres de PIN e do fluxo de configuração, desbloqueio, sincronização, abas, recebimento, envio simulado e persistência. Resultado XCTest: `iosApp/build/NativeStrings-20261015.xcresult`. Foi usado o plano `NoCapture`, sem capturas explícitas ou retenção de capturas automáticas. Nenhuma captura foi solicitada ou consultada nesta atualização.

O APK final está em `androidApp/build/outputs/apk/debug/androidApp-debug.apk`. Não houve instalação ou execução em aparelho físico nesta atualização; essa verificação fica com o usuário. As limitações das integrações de produção continuam registradas em `MIGRATION.md`.

## Diagnóstico: execução Android pelo IDE

Data: 13/10/2026. Após relato de que o app não executava, `:androidApp:assembleDebug` concluiu com sucesso, incluindo os últimos ajustes do ícone BNB e imports. O APK gerado foi inspecionado com `aapt2`: nome BJJ AirLock, pacote `pub.airlock.kmp.dev` e atividade inicial `pub.airlock.app.MainActivity`. O arquivo de redirecionamento do IDE aponta para um `output-metadata.json` válido e para o APK existente.

O log do IDE contém uma falha anterior, às 16:38: `Unable to list apks`, após concluir a instalação e antes de iniciar a atividade. A sincronização mais recente inspecionada terminou sem erro. O Android conectado foi reconhecido e o buffer de crashes consultado estava vazio. Esses resultados não confirmam a causa do relato atual nem a abertura pelo botão Run; a confirmação manual do usuário ainda está pendente. Não foram alterados o código do app ou os dados do aparelho neste diagnóstico.

Após o usuário informar que Run abria **Edit Configurations**, foi inspecionado o modelo Gradle persistido pelo IDE: `AirLock.androidApp` e `AirLock.androidApp.main` estavam com `SELECTED_BUILD_VARIANT=release`. Essa variante não tem assinatura configurada no projeto. Foi orientada a seleção de `debug` em **Build Variants**, e o README passou a explicitar esse passo. A troca no IDE e a execução ainda dependem da confirmação do usuário.

## Atualização: fluxo compartilhado, ícones e valores decimais

Data: 13/10/2026. Debug usa serviços simulados por injeção de dependência, com as mesmas telas e regras do release. Os adaptadores de produção ainda informam integração indisponível; compilar release não confirma uma carteira operacional.

| Verificação | Resultado |
|---|---|
| Testes unitários | 44 testes, zero falhas |
| Android `assembleDebug` e `assembleRelease` | Aprovados após a correção de entrada decimal |
| Kotlin iOS ARM64 | Compilação aprovada |
| Xcode e XCTest | Build aprovado; três testes de interface, zero falhas |
| Catálogos de tradução | Sete idiomas, 135 chaves em cada um; parâmetros e cobertura validados |
| Ícones das três abas | Caminhos vetoriais comparados com Bootstrap Icons do Cordova original |

Os testes novos cobrem separação de ambientes, adição de conta/endereço, cotação/assinatura/envio simulados, saldo incluindo taxa e aritmética monetária exata. A entrada decimal aceita vírgula ou ponto, apresenta o separador do idioma e respeita a precisão de cada moeda, incluindo limites de 6, 8, 9 e 18 casas. Colagens ambíguas e excesso de casas são rejeitados sem arredondar silenciosamente.

O XCTest percorreu criação/desbloqueio/sincronização, navegação nas abas, filtro, recebimento, envio simulado com validação de casas decimais, configurações e persistência após reiniciar. Também passaram os testes dos sete idiomas e de caracteres de PIN. Resultado: `iosApp/build/NoCaptureValidation.xcresult`. Esta execução usou o plano `NoCapture`, com capturas automáticas desativadas e sem chamadas explícitas de captura nos testes. Novas capturas dependem de solicitação do usuário.

Após esses builds houve somente a troca do recurso do ícone BNB para `bnb.svg` e remoção de imports sem uso. A recompilação final foi cancelada, portanto esses últimos ajustes não fazem parte dos binários validados acima. O teste manual nos aparelhos ficará com o usuário ao executar pelo Android Studio/Xcode. Hardware AirLock, iPhone físico e integrações de produção continuam pendentes.

## Correção: regex das traduções

Data: 13/10/2026. A expressão de parâmetros nomeados agora escapa ambas as chaves (`\{(\w+)\}`). A chave de fechamento sem escape causava `PatternSyntaxException` no mecanismo ICU do Android ao renderizar as traduções.

| Verificação | Resultado |
|---|---|
| Testes unitários | 35 testes, zero falhas; inclui preservação de chaves literais e parâmetros desconhecidos |
| Android `assembleDebug` | Aprovado; APK atualizado |
| Android físico | Samsung Galaxy A54 (SM-A546B), Android 16/API 36; atualização instalada preservando os dados, abertura e tela de contas confirmadas, sem erro de regex no log do processo |
| Kotlin iOS ARM64 | Compilação aprovada |
| Simulador iOS | Teste de troca dos sete idiomas e persistência da seleção aprovado |
| Catálogos de tradução | Sete idiomas e 110 chaves por idioma validados |

Resultado XCTest: `iosApp/build/RegexFix.xcresult`. O APK atualizado está em `androidApp/build/outputs/apk/debug/androidApp-debug.apk`. Esta verificação confirma a execução no Android físico; iPhone físico, teclado externo e hardware AirLock continuam pendentes.

## Atualização: nome, sete idiomas e teclado do sistema

Data: 13/10/2026. Simulador iPhone 16 Pro, iOS 26.3.1.

| Verificação | Resultado |
|---|---|
| Nome nos metadados Android/iOS | BJJ AirLock |
| Catálogos de tradução | Sete idiomas, 110 chaves em cada um; cobertura e parâmetros validados |
| Testes unitários | 34 testes, zero falhas |
| Android `assembleDebug` | Aprovado; APK atualizado |
| Kotlin iOS ARM64 | Compilação aprovada |
| Build Xcode e XCTest | Três testes de interface, zero falhas |

Os testes de interface verificam troca dos sete idiomas com tradução de um erro já visível, idioma preservado após reiniciar, entrada pelo teclado do sistema, rejeição de caractere acentuado com aviso visível, criação de PINs com letras/números/símbolos e o fluxo de desbloqueio/sincronização/persistência. Os testes unitários incluem limites de tamanho, símbolos permitidos, espaços preservados, rejeição de emojis/caracteres de controle e validação no repositório sem depender da UI.

Resultado aprovado: `iosApp/build/NativeKeyboard-3.xcresult`. Foram corrigidos o espaçamento duplicado do teclado no iOS e um aviso de entrada inválida que desaparecia durante digitação rápida. A suite completa passou após essas correções.

Capturas atuais: [português](screenshots/bjj-airlock/language-pt.png), [inglês](screenshots/bjj-airlock/language-en.png), [chinês](screenshots/bjj-airlock/language-zh.png), [espanhol](screenshots/bjj-airlock/language-es.png), [francês](screenshots/bjj-airlock/language-fr.png), [italiano](screenshots/bjj-airlock/language-it.png), [alemão](screenshots/bjj-airlock/language-de.png), [aviso de caractere inválido](screenshots/bjj-airlock/native-pin-validation.png) e [login BJJ AirLock](screenshots/bjj-airlock/02-login.png).

Não houve teste com teclado físico externo ou aparelho Android nesta atualização; o suporte usa o campo de texto padrão da plataforma. Os limites de hardware/API descritos abaixo continuam válidos.

## Registro da primeira entrega

Data: 13/10/2026. Ambiente: macOS Intel, JDK 21/17, Android SDK 36, Xcode 26.3 e simulador iPhone 16 Pro com iOS 26.3.

## Resultados

| Verificação | Resultado |
|---|---|
| Android `assembleDebug` | Aprovado; APK debug gerado |
| Testes JVM/Android do módulo compartilhado | 25 testes, zero falhas |
| Compilação Kotlin iOS x86_64 | Aprovada |
| Framework iOS ARM64 | Gerado com sucesso; compilação novamente verificada após ajuste do teclado |
| Aplicativo Swift + Compose no Xcode | Build aprovado |
| XCTest de interface | 1 teste, zero falhas |
| Recursos importados | 359 arquivos de moedas e quatro imagens comparados byte a byte com os originais |

Os testes cobrem aritmética exata, PINs, isolamento entre modos, colisões de identificadores, falha de escrita, leitura corrompida, contratos HTTP, fragmentação BLE/UTF-8, timeout sem reenvio de assinatura, rejeição do hardware e fluxo do ViewModel. O teste Android de PBKDF2 compara com um vetor produzido independentemente por `hashlib`, incluindo caracteres Unicode.

O teste de interface percorreu: criar PIN normal → confirmar → criar PIN oculto → confirmar → desbloquear → sincronizar contas simuladas → terminar o processo → reabrir → desbloquear → verificar contas persistidas. Isso exercitou o armazenamento real CryptoKit/Keychain do simulador, não um repositório em memória.

O simulador usa assinatura local ad hoc. Compilação sem assinatura passou, mas não permitiu concluir a gravação no Keychain; os testes aprovados usaram a assinatura padrão do Xcode. O teclado foi ajustado para que tocar no campo sempre o abra.

## Evidências

- Relatório unitário local: `shared/build/reports/tests/testDebugUnitTest/index.html`.
- Resultado XCTest aprovado: `iosApp/build/FlowTests-4.xcresult`.
- Capturas: [setup](screenshots/01-setup.png), [login](screenshots/02-login.png), [contas](screenshots/03-accounts.png), [contas após reabrir](screenshots/04-persisted-accounts.png).
- APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

Os diretórios de build são regeneráveis e ignorados pelo Git. As capturas foram preservadas em `docs/screenshots`.

## Limites da validação

Na primeira entrega, não foram feitos testes em Android físico, iPhone físico ou hardware AirLock. O Android foi compilado e teve testes JVM executados; não foi iniciado em emulador naquela entrega. A validação posterior em Android físico está registrada acima. Nenhuma API de produção foi exercitada, nenhuma assinatura real foi solicitada e nenhuma transação foi transmitida.

As capturas confirmam a renderização das primeiras telas e dos SVGs importados no iOS. Ainda falta a comparação visual completa contra o Cordova. As pendências funcionais estão na matriz de migração, sem classificação de simulações como integrações concluídas.


## Fluxo completo e confirmação da conexão — 2026-11-08

Executado `./gradlew :shared:testDebugUnitTest :androidApp:assembleDebug :shared:compileKotlinIosSimulatorArm64 --offline`: **133 testes, 0 falhas**, APK gerado e código compartilhado iOS compilado.

`FullWalletFlowTest` acrescenta oito cenários de primeira conexão, importação pública e reabertura com novas instâncias de ViewModel/repositório: PIN local, retorno positivo de Face ID, retorno positivo de digital, biometria cancelada, biometria indisponível, cold wallet sem PIN com acesso local por PIN ou biometria, e proteção local opcional desativada. Usa `ProductionHardwareWallet`, importação/derivação pública de produção e `AndroidPinCrypto` (PBKDF2 real). Respostas BLE, callbacks biométricos e armazenamento são controlados **exclusivamente no código de teste**; não há adaptadores de demonstração novos no app.

Após sincronizar, o transporte do teste fica indisponível. A reabertura precisa mostrar as mesmas contas e ativos sem consultar o hardware. Todas as gravações serializadas, antes da camada de criptografia nativa, são inspecionadas: nenhum PIN da cold wallet, nenhum PIN local em texto puro e nenhum campo `hardwareCredentials`. Somente o verificador derivado do PIN local fica persistido. Os testes existentes também cobrem migração que apaga credenciais de hardware antigas, falhas de exportação/escrita, cancelamento e bloqueio progressivo.

Os botões “Set app PIN” e “Continue without” ficam desabilitados até uma resposta RPC válida de `get_device_info` confirmar a conexão. Conectar o GATT sozinho não habilita essas ações. O resultado `pin_required` é mantido na sessão para decidir a próxima tela. Dois testes adicionais verificam confirmação demorada e resposta de erro, inclusive bloqueio da ação no ViewModel.

Executado também `FlowTests/testFirstRunShowsBluetoothBeforeAnyPinAndKeepsLanguage` por XCTest no **iPhone 17 Pro simulado, iOS 26.3.1**, com cofre isolado: **1 teste, 0 falhas**. Verifica tela Bluetooth antes de qualquer PIN, alinhamento vertical dos seletores e persistência do idioma ao encerrar/reabrir o processo. Resultado local: `/tmp/AirLockFirstRunFlow.xcresult`.

**Limite da evidência:** o teste iOS de interface não percorre Bluetooth físico nem biometria nativa. Face ID/digital nos testes de fluxo são callbacks controlados; o sensor real e a integração com uma cold wallet física continuam pendentes de uma sessão acompanhada no aparelho. Foi detectado um Android SM-A546B conectado, mas nenhuma credencial, dado ou configuração desse aparelho foi alterado nesta validação.


## Correção da sessão BLE entre conexão, PIN e exportação — 2026-11-08

Diagnóstico no código local: `NimbleDeviceService`, em `hardware/esp32/idf_components/airlock_ble/nimble_device.cpp`, trata `BLE_GAP_EVENT_SUBSCRIBE` e encerra a conexão se o cliente desativar notificações. O mobile cancelava seu coletor `device.observe` no `finally` de cada requisição RPC, desativando o CCCD após `get_device_info`. A consulta seguinte, `verify_pin`, podia encontrar a conexão já encerrada. Além disso, uma `CancellationException` originada no fluxo nativo podia ser tratada como cancelamento da operação, sem mensagem visível.

`BleRpcClient` agora mantém um coletor de notificações por sessão, sob um escopo próprio da conexão. As respostas passam por um canal limitado e são correlacionadas por ID; terminar uma requisição não cancela a observação física. O coletor só é encerrado no desligamento explícito da sessão. Cancelamentos inesperados do stream são reportados como desconexão, preservando o cancelamento explícito pelo ciclo de vida. Nenhum comando de PIN é repetido automaticamente e nenhum PIN é persistido.

A UI identifica validação do PIN e importação pública como etapas visíveis. Perda de conexão ao validar o PIN volta à busca e informa o erro, sem incrementar o contador de PINs incorretos.

Validação: **136 testes, 0 falhas**, APK Android gerado e código compartilhado iOS compilado. Regressões novas verificam uma única assinatura ao longo de `get_device_info → verify_pin → get_device_info → get_accounts → get_accounts`, encerramento somente no fechamento da sessão, cancelamento de stream reportado como desconexão, progresso em respostas demoradas e recuperação para nova busca. O sucesso físico na placa ainda depende de repetir o fluxo com o APK atualizado.

Os oito cenários de `FullWalletFlowTest` também foram repetidos usando `BleRpcClient`, montagem/desmontagem real dos frames e uma assinatura de notificações que encerra a conexão ao ser cancelada, reproduzindo a regra do firmware. Todos passaram, com inspeção de cada gravação do cofre. O APK corrigido foi instalado com `adb install -r` no Android conectado e iniciado; não houve limpeza dos dados do aplicativo. A repetição física do PIN foi solicitada ao usuário, sem pedir ou capturar o valor do PIN.
