# Correções da suíte de rede

Resultado final: **222 testes aprovados, zero falhas, zero erros e zero testes ignorados**. A contagem por classe está em [network-validation.json](network-validation.json).

## Causas e correções

A execução anterior tinha 19 falhas. Os testes ainda refletiam a arquitetura antiga com Alchemy, Ankr e Coinbase, filtros que ocultavam contas conforme o servidor e envio BRZ pelo REST AirLock. A implementação atual usa os exploradores disponíveis e RPCs fixos por rede, mantendo os dados das contas visíveis.

- **Configuração:** `NetworkSettings.validated()` rejeita serviços fora de `NetworkProvider.available`. A migração de cofres antigos continua usando Blockstream e limpa as credenciais antigas. Um teste adicional verifica contas, saldos e bytes assinados pendentes após a migração e reinicialização.
- **Nonce EVM:** a preparação confere envios locais ainda submetidos mesmo quando o RPC usa `pending`. Um nonce retornado igual ou anterior ao de um envio salvo do mesmo endereço/rede bloqueia a nova preparação. O teste também cobre um envio de outro token do mesmo endereço.
- **Contratos HTTP:** o cliente simulado instala a mesma negociação JSON necessária ao contrato AirLock, e os fixtures incluem `chain`, endereço e contrato. As verificações de vinculação dos saldos permanecem ativas.
- **Rotas atuais:** cenários de Alchemy/Ankr/Coinbase foram migrados para exploradores e RPCs públicos fixos. Base mantém as verificações de chain ID, taxas L1/operador e endpoint do sequenciador para transmissão. BRZ mantém os vetores assinados pelo firmware, a validação da rede e a prevenção de retransmissão.
- **Cobertura:** carteira, histórico e pendências ficam visíveis ao trocar servidor. Retomadas incompatíveis continuam bloqueadas. O teste de rascunho usa hardware simulado com `simulated = false`, para exercitar as restrições reais de cobertura.
- **Decodificadores:** os testes unitários de trustlines XRP e tipo de token Sui foram separados da seleção de adaptadores que não oferecem esses ativos. Continuam conferindo os dados, e também verificam a recusa da operação não integrada antes de qualquer HTTP.

Nenhum teste foi desabilitado e as verificações de assinatura, quantidade, rede, consulta de hash/recibo e retomada dos mesmos bytes foram mantidas.

## Execução

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :shared:testDebugUnitTest :androidApp:assembleDebug
python3 scripts/check-translations.py
```

Android debug, iOS Debug para simulador e catálogos dos oito idiomas: aprovados.

Os testes de rede usam HTTP controlado (`MockEngine`), sem enviar fundos nem testar a disponibilidade atual dos serviços públicos. A tentativa de atualizar o celular USB NM7R110255 não foi concluída porque o dispositivo estava desconectado. APK gerado em `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

Logs: `/private/tmp/bjj-network-fixes-final.log` e `/private/tmp/bjj-network-fixes-ios.log`.

Referências de contrato: [Esplora](https://github.com/Blockstream/esplora/blob/master/API.md), [Ethereum JSON-RPC](https://ethereum.org/developers/docs/apis/json-rpc/). A configuração de rotas do app é a referência para a seleção de endpoint; não se trata de uma validação de disponibilidade desses serviços.
