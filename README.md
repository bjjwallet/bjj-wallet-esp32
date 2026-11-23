**Português** · [English](README.en.md) · [Español](README.es.md) · [Français](README.fr.md) · [简体中文](README.zh.md) · [日本語](README.ja.md) · [Italiano](README.it.md) · [Deutsch](README.de.md)

<picture>
  <source media="(max-width: 640px)" srcset="docs/assets/hero-mobile.png">
  <img src="docs/assets/hero.png" alt="BJJ-Wallet — carteiras físicas acessíveis, código aberto e colaboração" width="100%">
</picture>

# BJJ-Cold-Wallet

<a id="testing"></a>

## 🧪 Carteira em fase de testes

A **BJJ-Cold-Wallet está em fase de testes e desenvolvimento**. Ainda precisamos da ajuda da comunidade para validar os fluxos do app e do hardware, identificar falhas e melhorar a experiência.

**Ajude a testar!** Teste a configuração, a conexão Bluetooth, a sincronização, a revisão e a assinatura de transações na cold wallet. Compartilhe problemas e sugestões na [organização BJJ-Wallet no GitHub](https://github.com/bjjwallet), informando o modelo do celular, as versões do app e do firmware e os passos para reproduzir.

Prefira redes de teste e carteiras criadas para testes. Nunca compartilhe sua seed, chaves privadas, PIN ou Transaction Phrase em relatos, capturas de tela ou logs.

**Uma cold wallet de desenvolvedores para desenvolvedores. Aberta a todos.**

Carteira física baseada em ESP32-S3, com tela touch, aprovação de transferências no próprio dispositivo e comunicação **Bluetooth Low Energy** com um aplicativo para Android e iOS.

<p align="center">
  <img src="docs/assets/badge-open-source.svg" alt="Código aberto" height="28">
  <img src="docs/assets/badge-affordable.svg" alt="Hardware acessível" height="28">
  <img src="docs/assets/badge-community.svg" alt="Construído em comunidade" height="28">
</p>

## 👋 Quem somos

Somos a **BJJ-Wallet**, um grupo de desenvolvedores que trabalha para democratizar as cold wallets com hardware acessível, software de código aberto e conhecimento compartilhado.

A **BJJ-Cold-Wallet** materializa essa proposta: uma carteira funcional, que possa ser estudada, construída e melhorada pela comunidade. Queremos que cada pessoa possa entender o que está rodando no dispositivo que protege suas moedas.

Nossos princípios são acessibilidade, transparência, engenharia de segurança e colaboração. Desenvolvedores, makers, designers e pessoas interessadas em testar são bem-vindos: leia o código, questione decisões, relate problemas e ajude a melhorar o projeto.

Conheça a [organização BJJ-Wallet no GitHub](https://github.com/bjjwallet).

[💚 Apoie a BJJ-Cold-Wallet](#support-project)

## 📱 App mobile

O aplicativo companheiro da **BJJ-Cold-Wallet** funciona em Android e iOS. Ele conecta à carteira por Bluetooth, sincroniza os dados necessários para acompanhar as contas, consulta saldos, prepara transferências e transmite à rede as transações assinadas pelo hardware.

O fluxo de transferência é:

1. O usuário escolhe a rede, o ativo, o destinatário e o valor no app.
2. O app solicita a assinatura à cold wallet por Bluetooth.
3. A cold wallet mostra os dados da transferência e exige aprovação física.
4. O hardware assina e devolve o resultado ao app.
5. O app verifica a assinatura e transmite os dados assinados pela API, RPC ou interface de rede correspondente.

**A seed e as chaves privadas de gasto permanecem na cold wallet. O app não solicita nem importa a seed para operar a carteira.** Credenciais de acesso são processadas pelo app; no modo watch-only do Monero, a chave privada de visualização pode ser mantida no cofre criptografado. Essa chave permite consultar movimentações e não autoriza gastos.

O catálogo atual contém **23 redes e 86 ativos: 23 moedas nativas e 63 tokens**. Inclui Bitcoin, Ethereum e redes EVM, Solana, Cardano, TRON, XRP Ledger, TON, Sui, Stellar, Litecoin, Dogecoin, Bitcoin Cash, Dash, eCash e Monero. O fluxo Monero disponível no firmware atual utiliza **stagenet**. A matriz por rede e token está no README do aplicativo.

O app usa **Kotlin Multiplatform e Compose Multiplatform**, com integrações nativas para Android e iOS.

➡️ **[Conheça o app: arquitetura, linguagens, bibliotecas, redes e compilação](app/README.md)**

## 🔐 Hardware

A BJJ-Cold-Wallet utiliza a placa **LCDWIKI ES3C28P com touch capacitivo**, baseada no **ESP32-S3**, com **16 MB de flash**, **8 MB de PSRAM** e tela **IPS de 2,8 polegadas**. O painel tem resolução nativa de 240 × 320; a interface atual funciona em landscape, com 320 × 240. Especificações: [documentação do fabricante](https://www.lcdwiki.com/2.8inch_ESP32-S3_Display).

O firmware utiliza **C/C++ com ESP-IDF**, interface **LVGL** e uma biblioteca **Rust** para a assinatura Monero. A integração criptográfica inclui **TrezorCrypto**, proveniente do Trust Wallet Core 4.4.2, com versões e arquivos de origem registrados no projeto.

A cold wallet controla o acesso dos celulares, apresenta os dados para aprovação e executa a assinatura. Na aplicação distribuída, o USB fornece alimentação e o Bluetooth faz a comunicação com o app. Wi-Fi e microSD ficam bloqueados pelo firmware.

### 🛒 Placa e case

- **[Comprar a placa — anúncio indicado no AliExpress](https://pt.aliexpress.com/item/1005010470966979.html)**. Confira a variante **ES3C28P com touch**; ES3N28P é a variante sem touch.
- **[Case para impressão 3D — referência indicada no Printables](https://www.printables.com/model/1188149-enclosure-for-esp32-s3-touch-lcd-2)**. Verifique dimensões, fixação e acesso aos conectores para a placa escolhida antes de imprimir; a compatibilidade mecânica desse modelo ainda não foi validada no projeto.

➡️ **[Conheça o hardware: placa, TrezorCrypto, bibliotecas, firmware e instalação](hardware/README.md)**

O projeto está em desenvolvimento. Consulte os [resultados e limites dos testes de assinatura](app/docs/all-assets-signing-2026-11-21.md), os [padrões de código](docs/CODE_STYLE.md) e as licenças do [app](app/LICENSE) e do [hardware](hardware/LICENSE).

<a id="support-project"></a>

## 💚 Ajude a manter a ideia viva

Queremos democratizar as cold wallets com hardware acessível e código aberto. Sua contribuição ajuda a manter a BJJ-Cold-Wallet em desenvolvimento e essa ideia viva.

**Neste momento, precisamos de ajuda para pagar as inscrições das lojas de aplicativos e publicar os apps para Android e iOS.**

### Escolha a rede antes de doar

| USDT · Ethereum | USDT0 · Polygon |
|:---:|:---:|
| <img src="docs/donate/usdt-ethereum.png" alt="QR code do endereço para USDT · Ethereum" width="220"> | <img src="docs/donate/usdt0-polygon.png" alt="QR code do endereço para USDT0 · Polygon" width="220"> |
| Rede: **Ethereum** | Rede: **Polygon** |

Os dois QR codes contêm somente o mesmo endereço. Selecione o token e a rede indicados na sua carteira antes de enviar.

**Endereço de doação**

No GitHub, use o botão de copiar no canto do bloco abaixo. O endereço é o mesmo nas duas redes.

```text
0x142f6f4230934c5555772e4de1E8E39E1105CB2b
```

Obrigado por apoiar o desenvolvimento aberto.
