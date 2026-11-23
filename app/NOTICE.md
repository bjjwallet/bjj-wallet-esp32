# Origem dos recursos

Este projeto é derivado de AirLock, Copyright (C) 2026 Le Wang, distribuído sob GPL-3.0-or-later. A licença original foi copiada integralmente para `LICENSE`.

Repositório original: https://github.com/airlock-wallet/airlock

Recursos importados sem alteração do conteúdo:

- `app/public/coins/*` → `shared/src/commonMain/composeResources/files/coins/`
- `app/public/images/*` → `shared/src/commonMain/composeResources/files/images/`
- Logo PNG também disponível como recurso Compose `airlock_logo`.
- `app/src/assets/registry.json` → `shared/src/commonMain/composeResources/files/registry.json`
- Dicionários `en-US` e `zh-CN`: conversão de objetos JavaScript para JSON, preservando textos em `docs/original-i18n`. Os catálogos ativos de BJJ-App-Wallet foram adaptados às telas implementadas e ampliados para sete idiomas.
- Roboto 400/500/700: tabelas descomprimidas dos WOFF originais para TTF, preservando os desenhos e métricas das fontes. Licença em `docs/licenses/Roboto-LICENSE.txt`.
- Ícones Material: biblioteca Compose Material Icons, correspondente à família usada pelo original. Licença em `docs/licenses/MaterialIcons-LICENSE.txt`.

- Bootstrap Icons: caminhos SVG de `wallet2`, `cpu`, `person-circle`, `github`, `link` e `arrow-right-square` extraídos da biblioteca `@quasar/extras/bootstrap-icons` instalada no projeto original e convertidos para VectorDrawable sem alterar os caminhos. Licença MIT preservada em `docs/licenses/Bootstrap-Icons-LICENSE.txt`.

Os ícones de moedas preservam suas marcas e atribuições originais. As alterações e o novo código Kotlin/Swift desta migração são fornecidos sob GPL-3.0-or-later.

Os ícones HYPE, BRZ, USDS, EURC, JUP e USDG vêm do [catálogo Trust Wallet Assets](https://github.com/trustwallet/assets). SKR e RLUSD vêm do catálogo de imagens da CoinGecko. Os ícones USDT0 e BRLP vêm do [Media Kit da USDT0](https://docs.usdt0.to/resources/mediakit) e do [site do BRLP](https://brlp.money/), respectivamente.

O ícone da Robinhood Chain vem do [kit oficial da rede](https://docs.robinhood.com/chain/brand-guidelines/).

- Motor local de Monero: `monero-ts` 0.11.22 (MIT), [código-fonte](https://github.com/woodser/monero-ts), com wallet2 do [Monero](https://github.com/monero-project/monero). O recurso `files/monero/wallet2.js` vem de `dist/monero.js` do pacote npm. O WASM embutido foi descomprimido para compatibilidade com WebViews sem `DecompressionStream`. As licenças acompanham os recursos em `files/monero/`.

- Leitor de QR no Android: [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded), Journey Mobile, versão 4.3.0, Apache-2.0, com ZXing Core 3.5.3. A câmera é integrada ao app; não depende de um aplicativo de leitura externo.
