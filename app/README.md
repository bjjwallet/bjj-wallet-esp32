**Português** · [English](README.en.md) · [Español](README.es.md) · [Français](README.fr.md) · [简体中文](README.zh.md) · [日本語](README.ja.md) · [Italiano](README.it.md) · [Deutsch](README.de.md)

# 📱 Aplicativo mobile da BJJ-Cold-Wallet

[← Apresentação do produto](../README.md) · [Hardware e firmware](../hardware/README.md)

O **BJJ-App-Wallet** é o aplicativo companheiro da **BJJ-Cold-Wallet**, desenvolvido para Android e iOS. Ele concentra a interface de contas e transferências, a comunicação Bluetooth com a carteira física, as consultas às redes e a transmissão das transações assinadas.

O app faz parte da evolução do projeto AirLock. A interface atual é implementada em Kotlin/Compose Multiplatform; os nomes internos `AirLockShared`, `AirLock.xcodeproj` e o namespace `pub.airlock` permanecem no código.

## Responsabilidades e fluxo de segurança

O app sincroniza contas e endereços, gerencia várias carteiras salvas, consulta saldos, lê QR codes de destinatários e prepara os dados de uma transferência. A assinatura de gasto é executada na cold wallet, depois da revisão e aprovação física.

```text
App prepara o pagamento
        ↓ Bluetooth / RPC local
Cold wallet confere os dados e solicita aprovação física
        ↓ Assinatura produzida no hardware
App verifica a assinatura
        ↓ API / RPC da rede
Transação transmitida
```

**A seed e as chaves privadas de gasto não são importadas nem armazenadas pelo app.** Há dados sensíveis que o aplicativo precisa processar: PINs, configuração de acesso e informações do cofre. O modo watch-only do Monero pode manter uma **chave privada de visualização**, que permite acompanhar movimentações e não permite assinar gastos. Ela fica no cofre criptografado e não é enviada ao nó remoto.

O PIN do app protege o desbloqueio local. O PIN de acesso da cold wallet autentica a conexão com o dispositivo e não é persistido pelo app. A Transaction Phrase é uma proteção separada; nas novas rotas de assinatura, sua entrada ocorre no hardware. Os detalhes de cada fluxo estão em [transfer-signing-flow.md](docs/transfer-signing-flow.md).

## Linguagens, plataformas e ferramentas

| Tecnologia | Versão/configuração do projeto | Uso |
|---|---|---|
| Kotlin | 2.3.21 | Regras, rede, Bluetooth e interface compartilhada |
| Kotlin Multiplatform | Plugin Kotlin 2.3.21 | Código comum para Android e iOS |
| Compose Multiplatform | 1.10.0 | Interface declarativa e recursos compartilhados |
| Material 3 | 1.9.0 | Componentes visuais |
| Swift / SwiftUI | SDK do Xcode | Entrada do app e integrações nativas Apple |
| Android Gradle Plugin | 8.11.2 | Compilação Android |
| Android SDK | compile/target 36; mínimo 29 | Android 10 ou superior |
| iOS | Deployment target 16.0 | iPhone/iPad; compilação pelo Xcode |
| Java | JVM 17 | Compilação e ferramentas Android |
| Gradle Wrapper | Incluído no projeto | Execução reproduzível das tarefas de build |

As versões efetivamente declaradas estão em [libs.versions.toml](gradle/libs.versions.toml), [shared/build.gradle.kts](shared/build.gradle.kts) e [androidApp/build.gradle.kts](androidApp/build.gradle.kts).

## Bibliotecas e SDKs

| Biblioteca / SDK | Versão | Função |
|---|---|---|
| Kotlin Coroutines | 1.10.2 | Operações assíncronas, Flow e StateFlow |
| Kotlinx Serialization | 1.9.0 | Serialização JSON dos modelos e contratos |
| Ktor Client | 3.4.3 | HTTP, APIs/RPCs; engines OkHttp no Android e Darwin no iOS |
| Kable | 0.41.0 | Conexão Bluetooth Low Energy |
| Koin | 4.1.0 | Injeção de dependências |
| Lifecycle / ViewModel | 2.9.6 | Estado e ciclo de vida das telas |
| bitcoin-kmp, ACINQ | 0.32.0 | Estruturas Bitcoin/UTXO, hashes, endereços e verificação de assinaturas |
| secp256k1-kmp JNI | 0.24.0 | Backend secp256k1 no Android e nos testes JVM |
| KotlinCrypto SHA-3 | 0.8.0 | Keccak e hashes usados nos protocolos |
| Kotlin Bignum | 0.3.10 | Suporte à aritmética de precisão arbitrária |
| Tink Android | 1.10.0 | Recursos criptográficos da integração Android |
| Coil / Coil SVG | 3.3.0 | Carregamento de imagens e ícones |
| Material Icons | 1.7.3 | Ícones de interface |
| Activity Compose | 1.10.1 | Integração da interface com a Activity Android |
| ZXing Core / Embedded | 3.5.3 / 4.3.0 | Leitura de QR codes no Android |
| Android Keystore / BiometricPrompt | SDK Android | Proteção da chave local e autenticação nativa |
| CryptoKit / Keychain / LocalAuthentication | SDK Apple | Cofre criptografado e autenticação no iOS |
| AVFoundation | SDK Apple | Captura e leitura de QR codes no iOS |
| Runtime Monero JS/WASM | Empacotado em recursos locais | Scanner watch-only, usando WebView/WKWebView como runtime |

Os arquivos do runtime Monero e suas licenças estão em [composeResources/files/monero](shared/src/commonMain/composeResources/files/monero). Ele acompanha a carteira sem receber a seed ou a chave privada de gasto.

## Estrutura do projeto

```text
app/
├── androidApp/        # Entrada Android, permissões e scanner de QR
├── iosApp/            # Projeto Xcode, SwiftUI, Keychain e testes de interface
├── shared/
│   └── src/
│       ├── commonMain/       # Funcionalidades compartilhadas
│       ├── androidMain/      # Adaptadores nativos Android
│       ├── iosMain/          # Adaptadores nativos iOS
│       ├── commonTest/       # Testes compartilhados
│       ├── androidUnitTest/  # Testes JVM e verificações criptográficas
│       └── test/             # Vetores públicos de referência
├── gradle/           # Wrapper e catálogo de versões
├── scripts/          # Verificações e geração de recursos
├── docs/             # Arquitetura, contratos e resultados de testes
└── hardware-patches/ # Registros de adaptações para integração com o hardware
```

Dentro de `shared/src/commonMain/kotlin/pub/airlock`:

| Pasta | Responsabilidade |
|---|---|
| `domain` | Modelos, catálogos, interfaces, regras e valores monetários exatos |
| `data` | Cofre, repositórios, integrações de rede e adaptador da cold wallet |
| `ble` | Transporte Bluetooth e mensagens do protocolo local |
| `presentation` | ViewModel, estado das telas e coordenação dos fluxos |
| `ui` | Telas Compose, componentes, temas e apresentação dos erros |
| `di` | Montagem das dependências do app |

<a id="supported-networks"></a>

## Redes e moedas compatíveis

O catálogo atual contém **23 redes, 23 moedas nativas e 63 tokens**, totalizando **86 ativos**. Um mesmo símbolo em duas redes representa integrações diferentes.

| Rede | Moeda nativa | Tokens cadastrados |
|---|---|---|
| Bitcoin | BTC | — |
| Ethereum | ETH | BRZ, BNB, USDT, USDC, USDS, USDe, RLUSD, EURC |
| Tron | TRX | USDC, USDT |
| BNB Smart Chain | BNB | BRZ, USDT, LINK, USDC, USDe, XRP |
| XRP Ledger | XRP | RLUSD, USDC |
| Dogecoin | DOGE | — |
| Solana | SOL | BRLP, XRP, MON, USDT, USDC, BRZ, SKR, USDS, PYUSD, EURC, LINK, JUP |
| Cardano | ADA | SNEK, MIN, IAG, WMTX, DJED |
| Bitcoin Cash | BCH | — |
| TON | TON | USDT |
| Avalanche C-Chain | AVAX | USDT, USDC |
| Ethereum Classic | ETC | — |
| Litecoin | LTC | — |
| Arbitrum | ETH | BRZ, USDC, ARB, USDT, USDS, LINK, USDe |
| Polygon | POL | USDC, USDT, LINK, BRZ |
| Sui | SUI | USDC |
| Dash | DASH | — |
| eCash | XEC | — |
| Base | ETH | USDC, USDS, EURC, LINK, BRZ, USDe, USDT |
| Monero — stagenet no fluxo Monero atual | XMR | — |
| Stellar | XLM | USDC, BRLP, BRZ |
| HyperEVM | HYPE | USDC, LINK |
| Robinhood Chain | ETH | USDG |

Os contratos, emissores, mints e precisões são definidos em [CoinCatalog.kt](shared/src/commonMain/kotlin/pub/airlock/domain/CoinCatalog.kt) e [Stablecoins.kt](shared/src/commonMain/kotlin/pub/airlock/domain/Stablecoins.kt). Esses arquivos também alimentam os catálogos do firmware.

As assinaturas dos 86 ativos foram verificadas localmente. Isso não equivale a uma confirmação de transferência real em cada blockchain. O fluxo Monero atual é stagenet. Na Cardano, a seleção de tokens usa UTXOs de ADA puro ou contendo apenas o token escolhido; UTXOs misturados com outros ativos são preservados. Consulte a [matriz de assinatura e seus limites](docs/all-assets-signing-2026-11-21.md).

## APIs, RPCs e transmissão

O app separa a consulta a provedores da construção e verificação de transações por protocolo:

- **UTXO:** Blockstream Esplora e mempool.space para Bitcoin; Freedom Street para as moedas cobertas pelo adaptador.
- **EVM:** RPC próprio de cada rede, incluindo os tokens cadastrados nela.
- **Solana:** RPC Solana para SOL e tokens suportados.
- **Cardano:** API Koios para preparação e envio.
- **TRON:** TronGrid para TRX e tokens TRC-20.
- **XRP Ledger:** API JSON-RPC XRPL para XRP e ativos emitidos cadastrados.
- **Stellar:** API Horizon.
- **Sui:** GraphQL para consulta, preparação e execução.
- **TON:** APIs TON Center para TON e USDT.
- **Monero:** scanner e fluxo watch-only próprios, com assinatura no hardware e rede stagenet no firmware atual.

AirLock e Moralis continuam disponíveis nas operações cobertas por seus adaptadores. A escolha de um explorador Bitcoin não substitui o RPC próprio de outra rede. O botão SEND depende da existência de uma rota de envio para o ativo; consultar um saldo não é suficiente para autorizar a transmissão.

Credenciais de provedores, quando exigidas, são configuradas pelo usuário e protegidas no cofre. As rotas e capacidades estão em [NetworkSettings.kt](shared/src/commonMain/kotlin/pub/airlock/domain/NetworkSettings.kt) e [data/network](shared/src/commonMain/kotlin/pub/airlock/data/network).

## Persistência e experiência de uso

O cofre é um JSON versionado com criptografia autenticada AES-256-GCM e gravação atômica. A chave local é protegida por Android Keystore ou Keychain; falhas de autenticação não criam silenciosamente uma carteira vazia.

O app oferece múltiplas carteiras salvas, isolamento de contas e pendências por carteira, biometria nativa e oito idiomas: português, inglês, espanhol, francês, chinês simplificado, japonês, italiano e alemão. Os valores monetários usam precisão exata por ativo, sem conversão para ponto flutuante. O modo de teste pode desativar o limite visual de Amount pelo saldo; isso não cria fundos nem obriga uma rede a aceitar a transação.

Sincronizar outra carteira preserva as existentes. Já a opção de reiniciar a sincronização apaga o cofre local após confirmação; ela não apaga a cold wallet nem cancela transações que já foram transmitidas.

## Compilar para Android

Requisitos: JDK 17, Android SDK 36 e as dependências do Gradle. Execute nesta pasta:

```sh
./gradlew :androidApp:assembleDebug
./gradlew :shared:testDebugUnitTest
```

O APK fica em [`androidApp/build/outputs/apk/debug/androidApp-debug.apk`](androidApp/build/outputs/apk/debug/androidApp-debug.apk).

Os identificadores são `pub.airlock.kmp.dev` para debug e `pub.airlock.kmp` para release. Ambos utilizam as integrações reais; os adaptadores simulados de testes não substituem os serviços de produção. A distribuição release requer a configuração da assinatura Android.

## Compilar para iOS

Requisitos: macOS, Xcode e as dependências Kotlin/Native. Abra [`iosApp/AirLock.xcodeproj`](iosApp/AirLock.xcodeproj) e selecione o scheme **AirLock**. O Xcode incorpora o framework Kotlin `AirLockShared` pelo Gradle, sem CocoaPods. Para um dispositivo físico, configure a equipe de assinatura.

```sh
./gradlew :shared:compileKotlinIosSimulatorArm64

xcodebuild -project iosApp/AirLock.xcodeproj -scheme AirLock \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath iosApp/DerivedData build
```

## Testes, documentação e manutenção

Os testes usam vetores públicos, transportes HTTP simulados e assinaturas produzidas pelos assinadores do firmware. Aprovação local não demonstra aceitação por um provedor real nem substitui a validação física de Bluetooth, toque e aprovação no dispositivo.

- [Arquitetura e contexto integrado](docs/BJJ_AIRLOCK.md)
- [Contrato de migração e integração](docs/MIGRATION.md)
- [Fluxo de assinatura](docs/transfer-signing-flow.md)
- [Assinatura UTXO](docs/UTXO_SIGNING.md)
- [Catálogo completo: assinaturas e limites](docs/all-assets-signing-2026-11-21.md)
- [Preenchimento de valores](docs/amount-mask.md) e [QR de destinatário](docs/recipient-qr.md)
- [Padrões de comentários e formatação](../docs/CODE_STYLE.md)
- [Documentação anterior preservada para consulta histórica](docs/README-anterior.md)

Para contribuir, prefira mudanças pequenas, testes que exercitem o comportamento e atualizações dos contratos quando houver alterações no protocolo. Recursos reaproveitados do AirLock preservam a autoria e a licença: [GPL-3.0](LICENSE), [NOTICE](NOTICE.md) e [licenças dos recursos](docs/licenses).
