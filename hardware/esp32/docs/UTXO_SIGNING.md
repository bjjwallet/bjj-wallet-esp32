# Assinatura e envio UTXO

O app e ambos os firmwares usam `sign_tx` com `txData.utxoVersion = 1`. `get_device_info` anuncia `utxo_signing_version = 1`. A assinatura só é pedida após a consulta desse recurso; firmware anterior não pode assinar silenciosamente usando outro contrato.

## Plano público

`asset` identifica a moeda do hardware (`doge` para DOGE), endereço e caminho público, símbolo e oito casas decimais. `mode` escolhe STANDARD/HIDDEN. `password` é a senha de pagamento, enviada pelo BLE autenticado, nunca uma chave privada.

`txData` contém `amount`, `fee`, `changeAmount`, `byteFee`, `toAddress`, `changeAddress`, `recipientScript`, `changeScript`, `useMax: false` e `utxos`. Quantias são strings inteiras em unidades mínimas. Cada entrada contém `txHash` na ordem do explorador, `index`, `amount`, `script`, `sequence: 4294967295` e `previousTransaction` em hex.

O destino e o troco são decodificados para a mainnet da moeda. Os valores/scripts de cada entrada devem coincidir com a transação de origem autenticada pelo txid. Não se aceitam entradas duplicadas, caminhos de outra moeda, troco para terceiros ou diferença entre soma de entradas e valor + taxa + troco. O troco retorna ao endereço remetente. O hardware mostra valor, taxa, troco e endereços antes da confirmação local.

BTC e LTC usam os caminhos BIP84 existentes e entradas P2WPKH. DOGE, DASH e BCH usam BIP44/P2PKH. BCH assina com SIGHASH_ALL | FORKID (0x41); os demais usam SIGHASH_ALL (0x01). Saídas podem ser P2PKH/P2SH ou witness válidos da rede. Entradas Taproot, tokens/CashTokens, inscrições e fundos de múltiplos endereços não são suportados. O plano é limitado a 16 entradas e 160.000 caracteres hex de transações de origem; uma origem individual tem no máximo 200.000 caracteres. Não há envio máximo automático.

## Persistência e recuperação

O app valida a transação assinada e seu hash antes do POST, verificando entradas, saídas, taxa e assinaturas. Os bytes e o plano são salvos no cofre como `PendingSubmission` antes da primeira chamada de transmissão. A retomada não altera o plano nem solicita nova assinatura.

Cada tentativa consulta o hash primeiro. HTTP 404 é ausência; erros de consulta bloqueiam o POST. Uma resposta incerta mantém a pendência até uma consulta ou retomada manual. A resposta do broadcast deve coincidir com o hash local. Nenhum servidor é selecionado automaticamente. Uma resposta encontrada distingue SUBMITTED de CONFIRMED.

## Implementações e validação

- ESP32: `core/utxo_signer.cpp`, TrezorCrypto fixado no projeto e SHA-256 do backend existente. O worker BLE solicita revisão à UI FirstLogin. Só a confirmação física cria o trabalho de assinatura. A recuperação da seed usa a sessão local desbloqueada e a senha de pagamento, com a carteira e o modo selecionados. Credenciais e chaves usam os buffers existentes que apagam seu conteúdo. A assinatura não bloqueia o loop da UI.
- Original Python/Wallet Core: `core/service.py` valida o plano público antes da revisão. `js/UtxoPlan.js` autentica as origens e verifica as saídas assinadas. BitcoinSigner/BitcoinCashSigner fornecem `TransactionPlan` explícito, evitando uma nova seleção de entradas ou taxa.
- Os testes cruzados usam uma frase BIP39 pública de referência e transações sintéticas, sem fundos. São dez cenários por motor: as cinco moedas, com e sem troco. O verificador criptográfico do app aceita os resultados de ambos.
- Testes do app cobrem o contrato BLE, firmware incompatível, rejeição, alteração de valores/troco, rede errada, falhas de consulta, diário anterior ao HTTP e retomada após reinício sem nova assinatura ou débito duplicado.

Comandos: `./gradlew :shared:testDebugUnitTest --tests 'pub.airlock.Utxo*' :shared:compileKotlinIosSimulatorArm64`; no hardware, `node js/utxo-plan.test.js` após instalar as dependências existentes; CTest `utxo_signer` com `AIRLOCK_MBEDTLS_DIR` configurado. O build ESP-IDF valida também o caminho BLE/UI da placa. Instalação, confirmação em dispositivo físico e transmissão real não fazem parte dos testes locais.
