# Máscara do valor de envio

O campo Amount usa uma máscara da direita para a esquerda, de acordo com a precisão da moeda/token selecionado. O símbolo do ativo aparece ao lado do valor, com separadores do idioma do app.

Exemplos para Bitcoin em português:

| Dígitos | Exibição |
| --- | --- |
| `1` | `0,00000001 BTC` |
| `12` | `0,00000012 BTC` |
| `12345678` | `0,12345678 BTC` |
| `123456789` | `1,23456789 BTC` |
| `100000000` | `1,00000000 BTC` |

O teclado numérico insere apenas dígitos; os separadores aparecem automaticamente. Excluir o último dígito desloca a máscara no sentido inverso. Colagens com pontuação são recusadas para evitar interpretar um valor de forma ambígua.

A edição usa dígitos de unidades mínimas, mas o estado do formulário e o fluxo de envio recebem o valor decimal exato em moedas. Um rascunho de 1 BTC continua valendo 1 BTC ao ser restaurado. Não há arredondamento nem ponto flutuante.

## Testes

- AmountMaskTest: 9 testes aprovados de digitação, exclusão, edição no meio, seleção, zeros, restauração, colagem inválida, valores grandes e mapeamento do cursor.
- Precisão e conversão verificadas para as moedas/tokens do catálogo e os oito idiomas.
- DecimalInputTest (7), AmountTest (4), RecipientQrTest (5) e UtxoPaymentFlowTest (2): aprovados. O fluxo Bitcoin usa o valor convertido pela máscara antes de preparar e assinar a transação com serviços simulados.
- Suíte completa: 221 testes, 202 aprovações e 19 falhas. Os 19 testes que falharam são exatamente os mesmos da execução anterior, em provedores/cobertura de rede. Esse é o resultado histórico em `amount-mask-validation.json`; as falhas foram corrigidas depois, com 222 testes aprovados na suíte completa. Ver `network-validation.md`.
- Build iOS Debug para simulador: aprovado.
- APK Android debug compilado e instalado no celular USB NM7R110255 com `adb install -r`, preservando os dados.

Log Android: `/private/tmp/bjj-amount-reverse-android.log`.
Log iOS: `/private/tmp/bjj-amount-reverse-ios.log`.
