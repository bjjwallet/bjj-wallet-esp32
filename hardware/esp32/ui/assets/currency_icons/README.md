# Ícones de redes e moedas

Os arquivos foram baixados em 07/11/2026 para uso futuro. Não estão embutidos
no firmware e não adicionam suporte a redes, tokens ou assinaturas.

O catálogo reúne as 163 entradas do registro original e as três redes adicionais
do catálogo da placa: Monero, HyperEVM e Robinhood Chain. São 166 entradas de
redes e 151 símbolos de moedas, compartilhando 172 imagens.

## Tamanho

- PNGs de 32 × 32 pixels, até 64 cores e transparência quando presente na origem.
- Cada PNG ocupa no máximo 2 KiB; não são guardados os originais grandes.
- Todas as imagens: 120.879 bytes, aproximadamente 118 KiB.
- Seleção da ESP32: 14 redes, 26 símbolos de moedas e 28 imagens únicas.
- Imagens da seleção: 20.199 bytes. Índice: 3.491 bytes.
- Seleção completa: **23.690 bytes, aproximadamente 23,1 KiB**.
- Limites do processo: 48 KiB para os PNGs da placa e 192 KiB para todos os PNGs.
- Ao habilitar a exibição, carregar apenas o ícone necessário: 2 KiB para um
  buffer RGB565 ou 4 KiB para RGBA. PNG não é um buffer LVGL pronto; a integração
  futura precisa escolher um formato/decodificador e medir seu custo no firmware.

`board_catalog.json` é o índice pequeno da seleção da placa. `manifest.json`
registra o catálogo completo, as fontes e os hashes para auditoria no computador.
As imagens ficam em `images/`, com nomes derivados de seu conteúdo. Uma mesma
imagem atende várias redes/moedas, sem cópias adicionais. O manifesto de origem
nunca precisa acompanhar o firmware.

`NATIVE` é um alias de `SOL`, confirmado pelo usuário, e não tem imagem própria.
ETH tem o ícone de Ether mesmo em Base ou Arbitrum; cada rede tem seu próprio
ícone. As imagens de tokens representam a marca, não validam contratos, mints,
emissores ou disponibilidade em cada rede. MON utiliza a marca Monad; o ativo
Solana correspondente permanece sujeito à identificação do mint.

## Fontes

- [Trust Wallet Assets](https://github.com/trustwallet/assets): maioria das redes
  e tokens. Os metadados de tokens foram conferidos por símbolo.
- [Cryptocurrency Icons](https://github.com/spothq/cryptocurrency-icons): Monero,
  Monacoin e WAX.
- [BRLP](https://brlp.money/): ícone do site oficial.
- [CoinGecko](https://www.coingecko.com/): dYdX Chain, Polymesh, Theta Fuel,
  Ripple USD e Seeker, identificados pelo ativo correspondente.
- [DefiLlama](https://defillama.com/): Arbitrum Nova e LightLink.

O manifesto registra a URL exata e os hashes dos bytes originais e do PNG pequeno.
As marcas pertencem aos respectivos projetos. Os termos dos repositórios e das
marcas continuam aplicáveis; a coleta não transfere direitos sobre logotipos.

Para atualizar no computador, executar `tools/fetch_asset_icons.py` com Python
e Pillow. O script reaproveita os arquivos existentes e verifica os limites de
tamanho. O download é executado no computador, nunca na ESP32.
