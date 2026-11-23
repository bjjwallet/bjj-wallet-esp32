# BJJ-Cold-Wallet — porte C/C++ para ESP32-S3

## Nomenclatura de recebimento e devolução — 21/11/2026, labels1

A revisão `2026.11.21-labels1` alinha os oito idiomas do app e do hardware. O valor enviado aparece como **Valor que receberá**, e o destino como **Destinatário**. O restante que volta à própria carteira é identificado como **Valor devolvido à sua carteira** e **Endereço de devolução**. O teste de revisão distingue valores/endereço de destino e de devolução para evitar inversões. Os rótulos não alteram planos de pagamento, assinaturas ou dados das carteiras. [Pacote](../historico/instaladores/BJJ-Cold-Wallet-ES3C28P-2026-11-21-labels1.zip). Instalação concluída com carteiras, PIN e pareamentos preservados; [relatório](docs/labels-installation-2026-11-21.json).

## Assinatura e autenticação por sessão — 21/11/2026, sign2

O app agora valida o PIN de acesso na mesma conexão Bluetooth usada para solicitar a assinatura. O PIN de acesso e a frase de transação são credenciais separadas. A revisão `2026.11.21-sign2` pergunta **“App: assinar transferência?”** e mostra valor/moeda, destinatário, taxa e troco. Mantém landscape, Monero e USB somente para alimentação. Foi instalada preservando NVS de carteiras/pareamentos por comparação de hashes, sem backup retido. [Relatório](docs/sign2-installation-2026-11-21.json) · [Pacote](../historico/instaladores/BJJ-Cold-Wallet-ES3C28P-2026-11-21-sign2.zip).

## Revisão de assinatura de transferência — 21/11/2026

A revisão `2026.11.21-landscape1` muda toda a interface para landscape (320 × 240), com rotação do display, transformação das coordenadas do toque, QR codes ao lado do endereço, teclado e splash adaptados. Também explicita na tela que o app solicita uma assinatura e mostra o valor como campo de revisão, além de destinatário, taxa e troco. A aprovação continua física e a chave privada de gasto permanece na cold wallet. Mantém o assinador e os pareamentos Monero, USB somente para alimentação e as proteções de acesso. O novo pacote está em `../historico/instaladores/BJJ-Cold-Wallet-ES3C28P-2026-11-21.zip`. A instalação física desta revisão depende do modo BOOT; a geração do pacote não altera a placa. Nesta sessão, a aplicação foi instalada em 21/11/2026 e conferida por leitura; NVS de pareamentos e carteiras e a tabela de partições permaneceram idênticas. Não houve alteração de eFuses. Relatório: `docs/landscape-installation-2026-11-21.json`. O usuário confirmou na placa física que o landscape e o toque funcionam corretamente.

## Exclusão de dispositivos e instalação limpa — 20/11/2026, reset1

A revisão `2026.11.20-reset1` corrige a exclusão de pareamentos enquanto o rádio
está anunciando. O NimBLE devolvia `BLE_HS_EBUSY` ao remover uma chave IRK com
anúncios ativos. A remoção agora é serializada na tarefa do host Bluetooth,
pausa os anúncios durante a operação e os retoma quando aplicável. Um teste de
regressão cobre rádio ativo/inativo, falha na remoção e falha ao pausar anúncios.
O firmware completo com Monero passou na auditoria de USB, Wi-Fi e microSD.
O modo DIO foi alinhado às opções do instalador também na geração das imagens.

Nesta sessão o usuário solicitou explicitamente zerar a flash e começar do zero.
Foi salvo e verificado um backup integral protegido de 16 MiB antes de apagar.
A flash foi apagada e o bootloader, a tabela e a aplicação foram reinstalados e
conferidos por leitura. NVS de pareamentos e de carteiras estavam vazias antes do
primeiro início. O reinício por watchdog foi concluído e quatro leituras confirmaram
a ausência da porta USB. O resultado fica em
`docs/factory-reset-2026-11-20.json`. Isso é separado da atualização comum:
o instalador normal continua preservando carteiras, apelido e pareamentos.

## Controle de acesso e PIN obrigatório — 20/11/2026

A interface atual passa a oferecer **Configurações → Dispositivos** no hardware.
Cada linha mostra o ID Bluetooth e o estado conectado, registrado ou bloqueado.
Ao abrir o item, **Excluir dispositivo** remove o registro e o pareamento; uma
nova conexão poderá ser aprovada fisicamente. **Bloquear dispositivo** grava uma
recusa persistente e encerra a conexão atual desse ID. Uma recusa explícita no
pedido inicial também grava o bloqueio. Novas tentativas de um ID bloqueado são
encerradas antes do pedido de PIN ou aceite. Excluir um registro bloqueado libera
esse ID para solicitar um novo pareamento. Falhas de gravação impedem continuar
admitindo celulares, em vez de liberar acessos sem persistir a decisão.

A lista de acessos é armazenada na NVS do hardware separadamente do cache de
quatro chaves Bluetooth. Rotacionar uma chave não apaga o registro de bloqueio.
O bloqueio se aplica ao ID Bluetooth apresentado/resolvido, não a uma identidade
física infalsificável do telefone. IDs aleatórios novos ou trocados não podem ser
associados a um bloqueio anterior antes de revelar a identidade: continuam sujeitos
à confirmação física e ao PIN. As chaves de aparelhos bloqueados são preservadas
quando presentes no cache para permitir resolver endereços privados conhecidos.

O PIN da cold wallet agora é obrigatório na criação e troca: **6 a 32 caracteres
ASCII, com ao menos uma letra maiúscula, uma minúscula e um número**, somente
A–Z, a–z e 0–9. Não é possível removê-lo ou tornar o PIN remoto opcional. O mobile
usa teclado alfanumérico, valida o mesmo formato e recusa dispositivos que informem
acesso sem PIN. O PIN local do aplicativo continua sendo uma configuração separada.
Todas as operações RPC protegidas, inclusive assinaturas, exigem verificar o PIN.

Instalações antigas conservam o desbloqueio local com o PIN anterior (ou sem PIN,
se assim estavam configuradas), mas devem cadastrar o novo PIN antes de liberar
o uso normal e o Bluetooth. A chave base e os registros criptografados das
carteiras são preservados; o marcador da nova política é autenticado no envelope.
As confirmações específicas e o pareamento seguro do Monero permanecem ativos.

Validação: testes de lista persistente, bloqueio, exclusão, falha de armazenamento,
PIN obrigatório e atualização de PIN legado; telas renderizadas em 240×320;
compilação dos módulos alterados para ESP32-S3 com a configuração Monero; APK e
testes de autenticação/sincronização/Monero do mobile. O teste amplo antigo de
interface ainda falhou na seleção de USDT; a validação específica de acesso passou.
A biblioteca de assinatura Monero foi recuperada em 20/11/2026 da revisão fixada
no projeto, e a imagem completa com Monero e o ZIP foram gerados e auditados.
A instalação física foi concluída em 20/11/2026, gravando somente a aplicação.
O firmware foi conferido na flash; as regiões NVS de pareamentos/configurações e
das carteiras ficaram idênticas byte por byte, assim como a tabela de partições.
O reinício por watchdog foi solicitado e quatro leituras confirmaram a ausência
da porta USB durante a aplicação. A conferência visual do visor permanece com o usuário.
O pacote novo está em
`hardware/historico/instaladores/BJJ-Cold-Wallet-ES3C28P-2026-11-20.zip` a partir da raiz. As seções históricas abaixo descrevem comportamentos anteriores.

## Celulares e confirmação física — 20/11/2026

O firmware permite cadastrar novos celulares sucessivamente, sem limitar o
total de aparelhos que podem usar a carteira. Há uma conexão BLE por vez e
**cada conexão precisa de aceite no visor**, inclusive de um celular já pareado.
O PIN de acesso, quando configurado, continua obrigatório.

A memória Bluetooth mantém um cache de quatro pareamentos persistentes. Quando
ele fica cheio, o novo pareamento só pode substituir o registro mais antigo de
outro celular depois da comparação numérica aceita fisicamente e da conclusão
do vínculo autenticado com criptografia e chave de 16 bytes. Uma solicitação
sem aceite, recusada, expirada ou de outra sessão não pode remover registros.
O registro do celular conectado nunca é escolhido para substituição. A atualização
não apaga o cache; a rotação só acontece ao admitir um novo pareamento aprovado.

O pareamento seguro e as confirmações específicas do Monero permanecem ativos.
Um celular cujo registro saiu do cache pode precisar esquecer a cold wallet nas
configurações Bluetooth do telefone e parear novamente, confirmando nos dois
aparelhos. Carteiras e dados do aplicativo não são apagados por essa rotação.
O tratamento de repetição de pareamento continua recusando a troca silenciosa
de uma chave ainda cadastrada na cold wallet.

Validação desta mudança: testes de host da autorização de rotação cobrem 32
sessões sucessivas, ausência de aceite, vínculo sem criptografia, desconexão e
tentativa de reutilizar o aceite em outra sessão. Os cinco CTests de host passaram
com ASan/UBSan; o módulo BLE compilou para ESP32-S3 com a configuração Monero.
A imagem completa, o novo ZIP e a validação física com mais de quatro celulares
continuam pendentes: a biblioteca estática do assinador Monero usada no build
anterior não está presente no ambiente local. O instalador distribuído continua
com a imagem anterior.

## Regra do instalador: USB apenas para alimentação

O firmware de distribuição usa `AIRLOCK_USB_POWER_ONLY=ON`, `CONFIG_ESP_CONSOLE_NONE=y`, `CONFIG_USJ_ENABLE_USB_SERIAL_JTAG` desabilitado e `AIRLOCK_BOARD_AGENT_CONTROL=OFF`. Ao iniciar a aplicação, os dados do USB nativo são desconectados antes de abrir a carteira; o cabo mantém a alimentação e o app se comunica por Bluetooth. Wi-Fi e SD/microSD permanecem bloqueados.

A porta USB pode aparecer durante o boot e no modo ROM de recuperação. Para uma atualização posterior, mantenha BOOT pressionado, pressione e solte RESET e solte BOOT antes de executar o instalador. Nenhum eFuse é queimado: não se trata de um bloqueio físico/permanente de USB/JTAG. A atualização comum grava somente a aplicação, preservando carteiras e apelido.

Para gerar o ZIP, use `python3 hardware/esp32/tools/package_installer.py CAMINHO_DO_BUILD CAMINHO_DA_PASTA_DO_INSTALADOR` a partir da raiz. O empacotador confere a configuração de distribuição, a política do ELF, os hashes e a integridade do ZIP, recusando builds com controle USB de validação. O README incluído no ZIP contém as instruções para Windows, Linux e macOS.

Em 17/11/2026, a instalação na placa touch preservou byte por byte as regiões de carteiras e configurações. O reset por RTS permaneceu no carregador; a saída pelo watchdog do esptool iniciou a aplicação. Seis consultas em dez segundos confirmaram a ausência da ESP32 nas portas seriais e na árvore USB do Mac. O instalador foi ajustado para essa saída por watchdog. A conferência visual após a última gravação e um novo ciclo físico de recuperação BOOT/RESET ficaram pendentes para a próxima utilização. Evidência sem segredos: `docs/usb-power-only-validation-2026-11-17.json`.

**O simulador no Mac agora cria e importa carteiras reais**, usando o
**Trust Wallet Core 4.4.2 nativo**. Ele executa os fluxos da carteira original,
com teclado touch, backup, confirmação, armazenamento criptografado e derivação
dos mesmos registros de contas. Não executa Python, Node.js ou WASM em runtime.

**A ESP32 agora tem fluxo inicial de criação/importação e cofre criptografado.**
O firmware mostra o splash e, sem carteira salva, oferece criar ou importar.
A placa gera localmente o primeiro endereço de 13 redes. A autorização do
mobile e a assinatura de transações estão pendentes. `get_accounts` retorna `PUBLIC_DATA_UNAVAILABLE` e novos pareamentos
são recusados nesta etapa. A carteira completa ainda não está pronta.

## Idiomas e terminologia — 08/11/2026

Os textos da interface em português usam **carteira**. Ao iniciar um aparelho
sem idioma salvo, a primeira etapa interativa é escolher entre **português,
inglês, espanhol, francês, mandarim, japonês, italiano e alemão**, identificados
pelos seus nomes nativos. A escolha é salva antes da configuração e permanece
até o fim da criação/importação e nos próximos reinícios.

**Configurações → Idiomas** permite trocar com um toque; a tradução de toda a
interface é aplicada imediatamente e persistida na chave NVS `ui_language`,
separada do cofre. Nomes cadastrados pelo usuário, palavras BIP39, PINs, frases,
passphrases BIP39 e endereços não são alterados pela tradução. A marca própria
BJJ-Cold-Wallet foi preservada.

As strings estão em `ui/locales/`, um catálogo por idioma, com geração estrita
sem fallback silencioso. A fonte foi ampliada e validada para os caracteres de
mandarim e japonês. O simulador também reconhece os oito idiomas e pede a seleção
quando ainda não há preferência salva.

Validação: 10 CTests de host, mais 6 verificações do simulador, troca e reinício
nos oito idiomas, falhas de leitura/gravação sem alterar o cofre e configuração
completa em espanhol mantendo o idioma após concluir e reiniciar. As telas dos
oito idiomas foram renderizadas e conferidas em 240×320. A versão foi instalada com hashes verificados e backup da partição criptografada.
Na placa, a primeira etapa exibiu os oito idiomas; cada escolha em Configurações
foi aplicada imediatamente e persistiu após reinício USB. O fluxo de nova
carteira e o aviso de seed também foram verificados em japonês, sem salvar outra
carteira. Ao fim, português foi selecionado e o PIN e a frase originais de
**Minha wallet** foram validados. Nenhuma carteira ou credencial foi alterada.
Relatório: `build-flow-board/language-hardware-report.json`.

## Inicialização com carteiras salvas — 08/11/2026

O fluxo atual da ESP32 é **splash → PIN (se configurado) → Main**.
O Main oferece **Minhas carteiras** e **Configurações**. A opção **Meus saldos**
foi removida por solicitação do usuário.
A Home lista até 16 carteiras pelo nome. Clicar em uma carteira continua reservado
para a próxima etapa; **Deletar wallet** já possui fluxo completo.

O **PIN é único do aparelho** e a **transactionPhrase é individual por carteira**.
Criar/importar pelas configurações reaproveita o PIN e pede a frase da nova
carteira; a gravação só ocorre após confirmar o backup e Concluir retorna à Home.
Editar PIN exige o PIN atual, permite remover ou configurar outro PIN e retorna
à Home. Editar transactionPhrase pede a carteira, o PIN atual quando habilitado,
a frase antiga e exibe **Usar em todas as wallets** na tela de criação da nova frase.
Desmarcada, apenas a carteira selecionada é alterada. Marcada, o usuário cria e repete a nova frase e depois confirma a transactionPhrase atual
de cada outra carteira, identificada pelo nome na tela. Todas são revalidadas no
backend e o envelope inteiro é gravado uma única vez. Cancelar, errar duas vezes
ou falhar a preparação/gravação impede qualquer atualização parcial. A passphrase
BIP39 da seed nunca é solicitada ou alterada por esse fluxo. Duas falhas no PIN das
configurações ou na frase antiga encerram a sessão e retornam ao PIN inicial.

Cada erro no PIN mostra o splash com contador: **30, 30, 60, 60, 120, 120… segundos**.
Falhas e espera pendente são persistidas em NVS antes de permitir outra tentativa;
só um PIN correto e a gravação bem-sucedida zeram as falhas. Como não há relógio
persistente, religar reinicia a espera pendente completa, preservando a quantidade
de falhas. O tempo desligado não reduz a espera.

O controlador BLE permanece inicializado para fornecer entropia ao backend,
mas não anuncia nem aceita a conexão enquanto a sessão estiver bloqueada.
Bloquear encerra uma conexão existente. Após desbloquear, anúncios e transporte
são liberados; as regras de pareamento/autorização do mobile continuam pendentes.

O envelope v2 guarda a lista e as credenciais do aparelho atomicamente. Migra o
registro anterior após desbloqueio, preservando seed e passphrase. A chave usada
pelos cofres é protegida por AES-GCM sob uma chave derivada do PIN, permitindo
alterar o PIN sem pedir todas as frases individuais. O salt/verificador legado
é movido para um cabeçalho criptografado. Um firmware antigo não lê o envelope v2.

Validação de host: 10 CTests com ASan/UBSan, incluindo 36 combinações do primeiro
cadastro, reinícios, falhas de armazenamento, navegação, adição de carteiras,
troca/remoção de PIN e troca isolada de frase preservando a seed e passphrase.
Build ESP-IDF 5.4.2 aprovado e instalado via USB em `build-flow-board`, com
verificação de hashes e backup local criptografado da partição NVS (modo 0600).
Na placa real, PIN correto abriu Main; Home, saldos e configurações passaram,
incluindo **Deletar wallet** e rolagem. Um reinício USB durante o bloqueio
preservou falhas e espera (34,7 s incluindo boot); as esperas seguintes foram
30,0 s e 60,0 s. Após acerto, o próximo erro voltou a 29,9 s, confirmando o reset
do contador. Ao terminar, o Main estava desbloqueado. Na validação inicial não foram
alteradas as credenciais nem criadas carteiras no aparelho.

**Editar PIN também passou na placa real em 08/11/2026:** o primeiro erro no
PIN atual aguardou 30 s e retornou à confirmação; o segundo aguardou 30 s e
retornou ao desbloqueio inicial. O PIN foi trocado por um temporário; após
reiniciar, o anterior foi recusado e o temporário abriu o Main. A exclusão
exigiu o PIN atual, retornou à Home e o reinício seguinte abriu Main sem pedir
PIN. Ao fim, o PIN original e a preferência de PIN no celular foram restaurados,
com novo reinício e desbloqueio confirmados. Nenhuma carteira foi criada ou
apagada. Relatório: `build-flow-board/edit-pin-hardware-report.json`.

**Atualização de transactionPhrase individual/conjunta:** os 10 CTests passaram,
incluindo preservação de seed e passphrase BIP39, frase incorreta na última
carteira, índices ausentes/duplicados, falha de RNG, falha de armazenamento,
cancelamento e duas falhas ao autenticar outra carteira. As telas de escopo e
confirmação da frase de cada carteira foram verificadas em 240×320.
Foi criada na placa, com autorização explícita, a carteira auxiliar
**Teste isolamento phrase**, importando a fixture pública BIP39 de 12 palavras.
A imagem foi instalada com hashes verificados e backup da partição criptografada,
sem apagar as carteiras. A validação física completa passou: dois erros no PIN,
dois erros em frases de tamanho válido, troca individual persistente após reboot
sem afetar a auxiliar, cancelamento da aplicação conjunta e dois erros na frase
da outra carteira sem atualização parcial. Confirmar corretamente a frase da
auxiliar permitiu aplicar a mesma frase às duas, e ambas abriram após reiniciar.
Ao fim, o PIN original e as duas transactionPhrases originais foram validados
novamente após reinício. A carteira auxiliar permaneceu salva ao concluir esse teste e foi removida
posteriormente pelo fluxo de exclusão solicitado pelo usuário (seção abaixo).
O Main foi conferido na placa sem **Meus saldos**. Relatório detalhado:
`build-flow-board/edit-phrase-hardware-report.json`.

Adição de carteiras foi validada com fixtures públicas no host; desconexão
física do cabo e pareamento BLE ainda não foram testados.
Evidências e hashes em `setup-flow-report.json`.

## transactionPhrase comum e aviso de seed — 08/11/2026

A tela de criação da transactionPhrase oferece **Usar em todas as wallets**,
na criação, importação e edição. A escolha fica salva no aparelho. Ao ativá-la
com carteiras já existentes, é preciso confirmar a transactionPhrase atual de
cada uma; a atualização conjunta e a adição da nova wallet são publicadas em
uma única gravação, somente após confirmar o backup. Cancelar ou errar não
altera as frases existentes nem salva a nova carteira.

Se a opção já estiver ativa, as próximas criações/importações pedem a
**transactionPhrase comum existente**, sem criar nem repetir uma nova frase.
A frase é validada contra os cofres existentes; esses registros não são
recriptografados ao adicionar outra wallet. Duas falhas voltam ao PIN inicial.
Desmarcar ao editar altera apenas a escolhida e desativa o uso comum; desmarcar
na criação/importação permite definir uma frase individual para a nova wallet.

Antes de cada exibição das palavras — revisão e backup final — aparece
**Guarde suas palavras**, explicando que, depois de concluir o fluxo, nunca
mais será possível visualizá-las no aparelho. As palavras só são mostradas após
**Entendi, mostrar**. É possível cancelar antes de visualizar ou salvar.

Os testes de host cobrem criação/importação, confirmação das frases existentes,
reutilização da frase comum após reiniciar, desativação da preferência, cancelamento
sem gravação e bloqueio da seed antes do aceite. A versão foi instalada com hashes verificados e passou na placa: a preferência
persistiu após reiniciar, a importação pediu a frase comum existente e rejeitou
uma incorreta. Os avisos precederam as palavras geradas, a revisão importada e o
backup final. Cancelar o backup não criou nenhuma wallet. Ao fim, a preferência
original, o PIN, a transactionPhrase e a presença exclusiva de **Minha wallet**
foram conferidos após outro reinício. Nenhuma carteira nova ficou salva.
Relatório: `build-flow-board/common-phrase-seed-notice-hardware-report.json`.

## Exclusão individual de wallet — 08/11/2026

Em **Configurações → Deletar wallet**, selecione o nome da carteira, confirme o
PIN atual do aparelho (quando configurado) e informe a transactionPhrase dessa
carteira. A tela final mostra o nome e exige **Excluir esta wallet**; **Cancelar**
preserva os dados. Duas falhas no PIN ou na frase encerram o fluxo e voltam ao
PIN inicial, aplicando ao PIN a espera persistente já existente.

A confirmação é vinculada ao registro exato selecionado. O backend revalida a
frase e substitui o cofre ativo atomicamente, copiando as demais carteiras sem
alterar seus ciphertexts, seeds ou passphrases BIP39. Após **Wallet excluída**,
**Concluir** retorna à Home atualizada. Se a última wallet for removida, o PIN e
as preferências do aparelho permanecem; a Home informa **Nenhuma carteira** e
Configurações permite criar ou importar outra usando as credenciais do aparelho.

Os 10 CTests passaram, incluindo cancelamento antes/depois da autenticação,
erros de PIN/frase, falha de armazenamento, seleção obsoleta, preservação exata
dos registros restantes, exclusão do registro legado e da última wallet,
reinício com lista vazia e adição posterior de uma carteira. Build ESP-IDF 5.4.2
aprovado e instalado com hashes verificados e backup prévio da partição
criptografada. Na placa, PIN/frase incorretos e cancelamentos preservaram as duas
carteiras. Após a confirmação autorizada, somente **Teste isolamento phrase**
foi removida. Dois reinícios confirmaram a ausência da auxiliar e a presença de
**Minha wallet**; o PIN e a transactionPhrase originais de **Minha wallet** foram
validados novamente sem alteração. A tela final ficou na Home com apenas
**Minha wallet**. A exclusão da última wallet foi testada somente com fixtures
no host; a carteira do usuário nunca foi excluída. Evidências:
`build-flow-board/delete-wallet-hardware-report.json`.

## Derivação offline por rede

### Catálogo compartilhado com o app

O menu de recebimento usa `ui/address_catalog.hpp`, gerado diretamente de `CoinCatalog.kt` e `Stablecoins.kt` do app KMP. São 23 entradas de rede/moeda nativa e 63 tokens, totalizando 86 combinações de rede e ativo. Tokens com o mesmo símbolo em redes diferentes são entradas distintas.

Na raiz do sistema, execute `python3 hardware/esp32/tools/generate_address_catalog.py` após alterar as listas. Execute o mesmo comando com `--check` para conferir a igualdade dos catálogos e a presença dos ícones do app e do hardware. A verificação também faz parte dos testes de host e do build do firmware; um catálogo desatualizado impede o build.

A igualdade do catálogo de recebimento não habilita automaticamente saldo, envio ou assinatura. Cada integração precisa validar seus contratos, mints ou emissores e seu formato de transação.

`core/network_accounts.hpp` cataloga as 14 redes solicitadas e os símbolos por
rede. `derive_account_public_key` recebe a seed BIP39 de 64 bytes e devolve
somente a chave pública da primeira conta em 13 redes. A chave privada
intermediária é descartada. A derivação usa o backend local e o RNG seguro
para blinding. Os caminhos padrão incluem Bitcoin SegWit nativo
(`m/84'/0'/0'/0/0`), Solana (`m/44'/501'/0'/0'`) e o caminho Ethereum
(`m/44'/60'/0'/0/0`) compartilhado pelas redes EVM.

Os tokens usam a conta da rede. O catálogo de símbolos é a lista de trabalho
para verificar contratos, mints ou emissores antes de habilitar cada ativo.
`SOL` é a moeda nativa da Solana e `Solana` é o nome da rede.
O antigo item `NATIVE` foi identificado pelo usuário como SOL e removido da lista de tokens. O Monero está catalogado com a derivação
pendente de um esquema específico para as chaves de gasto e visualização.

O backend de **Ver endereços** permite informar a frase de transação
localmente para abrir o cofre. Essa tela do fluxo anterior não está exposta no
novo Main; o clique na carteira será implementado posteriormente. A placa usa as palavras e a passphrase BIP39
guardadas para calcular o primeiro endereço mainnet por rede. Se o PIN estiver
ativo, desbloqueie primeiro. A lista identifica a rede mesmo quando as redes EVM
usam o mesmo endereço. A lista fica em memória até voltar à tela da carteira.

## Fluxo inicial na ESP32 — versão pronta para instalar

1. Splash com a imagem fornecida, convertida para RGB565 de 224 × 224 pixels
   (100.352 bytes), durante dois segundos. A tela de ausência de touch foi removida.
2. Escolher **Criar wallet** ou **Importar wallet** e informar o nome.
3. Escolher **12, 18 ou 24 palavras BIP39 em inglês**. Na criação, o aparelho
   gera as palavras localmente; na importação, digitar cada palavra na ordem
   original. Palavras e checksum são validados antes de continuar.
4. Percorrer **Confira as palavras** e escolher **Está correto** para continuar.
   **Corrigir** volta à última palavra na importação (é possível voltar mais
   palavras); na criação, volta à quantidade de palavras para gerar outra seed.
   Voltar da passphrase reabre a revisão sem gerar outra seed.
5. Configurar e repetir a **passphrase BIP39 opcional**, ou escolher **Não usar**.
   Na importação, informar a mesma passphrase da carteira original, se houver.
   Mantém os bytes UTF-8 compatíveis com Wallet Core, com limite de 256 bytes,
   sem normalização Unicode.
6. Configurar e repetir um **PIN opcional de 4 a 12 números**, ou escolher
   **Sem PIN**. Com PIN, escolher a preferência **Pedir PIN no celular**:
   marcada, o mobile deverá informar o PIN; desmarcada, cada acesso deverá ser
   aprovado no aparelho. Sem PIN, a preferência é aprovação no aparelho.
7. Criar e repetir a **frase de transação obrigatória**, com pelo menos oito
   caracteres. Será exigida pelo futuro fluxo de assinatura no mobile; esse
   fluxo de assinatura ainda não está implementado.
8. Nos dois caminhos, percorrer todo o **Backup da wallet**, com as mesmas
   palavras revisadas e a passphrase configurada, e confirmar **Anotei tudo**.
   Somente essa confirmação grava o cofre. O console nunca imprime o backup.

Tudo é processado localmente, sem servidor ou internet. Wi-Fi e microSD continuam
bloqueados; BLE é somente comunicação local. A preferência de acesso do mobile
é persistida, mas sua aplicação ao protocolo BLE ainda será implementada.

O cofre usa AES-256-GCM com metadados autenticados, PBKDF2-SHA256 de 100.000
iterações e partição `wallet_nvs` separada dos vínculos BLE. Não persiste PIN nem
frase de transação em texto aberto. Registros corrompidos bloqueiam o fluxo,
sem apagar ou substituir silenciosamente uma carteira. Buffers sensíveis usam
RAM interna e são limpos ao liberar. Flash encryption/secure boot não foram
habilitados, nem houve alteração de eFuses; criptografia do cofre não equivale
à proteção física completa de um produto final.

**Estado da instalação:** em 07/11/2026, a versão com controle exclusivo pelo agente,
sem terminal interativo, com os dois fluxos separados,
revisão das palavras antes das credenciais e confirmação final de backup em ambos
foi compilada com ESP-IDF 5.4.2 e instalada via USB-C, com verificação dos hashes.
O boot passou nos autotestes Crypto/HD e exibiu **Criar wallet** e
**Importar wallet**. A partição da carteira foi preservada e nenhuma carteira foi
criada durante esta instalação. A validação manual dos fluxos pelo usuário está
pendente. A imagem daquela instalação foi substituída pelo build de inicialização de
08/11 em `build-flow-board`; o instalador consulta esse
caminho no relatório. Para reinstalar, feche o console e abra
`Instalar_fluxo_inicial.command`. Para testar, solicite as ações pelo chat.

Os [ícones de redes e moedas](ui/assets/currency_icons/README.md) já estão
preparados em PNG de 32 × 32 pixels. A seleção da ESP32 ocupa 23.690 bytes com
o índice; não foi embutida no firmware. `NATIVE` foi consolidado em `SOL`.

A suíte do novo alvo cobre o cofre, o fluxo de criação/importação e os
vetores das chaves públicas por rede. O simulador tem uma suíte separada. As 8.077 verificações
HD e 217 de primitivas também passaram. As capturas usam apenas fixtures públicas:
[prévia do splash e fluxo](build-setup/onboarding-preview.png) e
[explicação do PIN](build-setup/onboarding-pin-preview.png).
Evidências e hashes: `setup-flow-report.json`.

## Placa conectada: ES3N28P sem touch

Em 04/11/2026 o usuário confirmou que a placa comprada é a variante **sem
touch**. A conexão USB identificou ESP32-S3 revisão 0.2, 16 MB flash QSPI e
8 MB PSRAM. A ES3C28P continua sendo o perfil com touch; não se deve procurar
FT6336G na ES3N28P nem tratar sua ausência como defeito dessa variante.

A imagem de validação pode ser compilada explicitamente sem touch, com controle
pelo console USB. O modo não é ativado automaticamente quando um touch falha.
O console é exclusivo desta aplicação de testes; não é um canal de confirmação
para uma futura carteira de produção. Para reproduzir com ESP-IDF 5.4.2:

```sh
idf.py -C esp32/firmware -B "$PWD/esp32/build-crypto-board" \
  -DSDKCONFIG="$PWD/esp32/build-crypto-board/sdkconfig" \
  -DAIRLOCK_BOARD_NO_TOUCH=ON -DAIRLOCK_BOARD_AGENT_CONTROL=ON build
idf.py -C esp32/firmware -B "$PWD/esp32/build-crypto-board" \
  -p /dev/cu.usbmodem14101 flash
```

O nome da porta pode mudar. São gravados bootloader, tabela de partições e app
nos offsets de `flasher_args.json`, sem `erase_flash` completo ou alteração de
eFuses. A primeira tentativa de backup integral foi interrompida por uma falha
de conexão USB; **não existe backup completo do firmware anterior**.

O controle manual por teclado/Terminal foi removido em 07/11/2026.
A interação com esta placa sem touch é solicitada pelo chat e executada pelo
agente através de `tools/board_agent.py`, sem loop de entrada ou captura de teclas.
O firmware aceita apenas o protocolo interno com prefixo `agent ` e rejeita
teclas, setas e comandos do console antigo. O canal permanece exclusivo da
imagem de desenvolvimento. Respostas não incluem o conteúdo dos campos ou backup.

O agente pode consultar a tela, selecionar um botão visível, preencher um campo,
confirmar, voltar, cancelar e rolar uma revisão. A revisão completa continua
obrigatória antes de salvar; o canal não ignora botões ocultos ou bloqueados.

**Instalação verificada em 04/11/2026:** bootloader, tabela de partições e app
foram gravados pela USB-C, com verificação de hash. O boot confirmou PSRAM OK,
inicializou LCD e Bluetooth, e a navegação real por USB passou por menu,
informações, teclado com texto público e retorno ao menu. A aparência e a
orientação da tela ainda precisam de confirmação visual do usuário. Os 11
CTests do simulador passaram na primeira instalação. A atualização de teclas
diretas acrescenta testes do foco, navegação espacial, teclado LVGL, volta,
cancelamento, bloqueio de revisão e decodificação das setas do terminal.

## Backend criptográfico e derivações na ESP32

`core/crypto_mbedtls.cpp` e `idf_components/airlock_crypto/` iniciam o backend
usando **mbedTLS 3.6.3 do ESP-IDF 5.4.2**: SHA256, PBKDF2-HMAC-SHA256/SHA512,
AES-256-GCM e CTR-DRBG. O gerador é semeado antes de BLE/ADC usando a fonte SAR
de entropia; depois, novas coletas exigem o controlador BLE habilitado. Não usa
Wi-Fi. Contextos e buffers de chaves são limpos; falha de autenticação apaga a
saída de plaintext. O KDF do keystore preserva as 100.000 iterações originais.

Os 217 checks de host com ASan/UBSan verificam vetores conhecidos,
compatibilidade bidirecional com OpenSSL, limites e adulteração de ciphertext,
tag, nonce e chave. A coleta de entropia física ainda requer validação própria.

`core/hd_wallet.*` acrescenta validação de checksum e conversão de entropia BIP39
em inglês (12/15/18/21/24 palavras), geração com fonte de entropia explícita,
seed compatível com Wallet Core 4.4.2, derivação privada/pública e chain code
para **secp256k1, NIST P-256, Ed25519 e Ed25519 Blake2b/Nano**, além de XPUB/ZPUB
para secp256k1. Os caminhos são canônicos `m/...`, com até 16 índices; Ed25519
exige todos os índices endurecidos. Derivar chaves não significa que os endereços,
contas ou assinaturas das 163 entradas do registro estejam implementados na placa.

As primitivas de curvas vêm de fontes **inalteradas** do TrezorCrypto incluído
no Wallet Core 4.4.2, com hashes fixados em `vendor/trezor-crypto-4.4.2/provenance.json`.
O build verifica os hashes e a igualdade das 2.048 palavras com `words.txt`.
Caches de mnemonic/passphrase/seed e BIP32 estão desligados. O RNG POSIX original
não é compilado; cada operação vincula uma fonte segura por escopo local à thread.
Falha dessa fonte no blinding EC interrompe a execução, sem fallback determinístico.

**Compatibilidade de passphrase:** a API `wallet_core_seed` preserva os bytes UTF-8
do Wallet Core 4.4.2, sem normalização Unicode; acima de **256 bytes** retorna
`PassphraseTooLong`, em vez do truncamento silencioso da biblioteca original.
Ela não deve ser apresentada como derivação Unicode NFKD genérica. Uma passphrase
composta e sua forma decomposta podem produzir seeds diferentes, como na original.
O fluxo inicial aplica esse limite e preserva essa compatibilidade na criação/importação.

Os testes de HD passaram em **8.077 verificações** com ASan/UBSan: 24 vetores BIP39,
34 carteiras públicas de referência e **578 chaves**, incluindo passphrases UTF-8
e o limite de 256 bytes, XPUB/ZPUB, caminhos inválidos, falha de RNG e duas threads.
O oráculo `tools/derivation_oracle.cpp` roda em processo separado, ligado somente
ao binário Apple original, evitando colisões de símbolos com as novas fontes.
Os dados de referência ficam em `tests/hd_wallet_vectors.json` e não contêm dados
de carteiras do usuário.

```sh
cmake -S esp32 -B esp32/build-crypto \
  -DAIRLOCK_MBEDTLS_DIR=/private/tmp/airlock-esp-idf/components/mbedtls/mbedtls \
  -DAIRLOCK_SANITIZERS=ON -DOPENSSL_ROOT_DIR=/usr/local/opt/openssl@3
cmake --build esp32/build-crypto --parallel 6
ctest --test-dir esp32/build-crypto --output-on-failure
```

Os autotestes de boot são opcionais (`-DAIRLOCK_CRYPTO_CHECK=ON`) e estão
**ligados** na imagem instalada em 04/11/2026. Na ES3N28P real, os vetores
publicados de SHA256/PBKDF2/AES-GCM/BIP39/BIP32 e as quatro curvas passaram em
**708 ms**, antes de iniciar a interface e o BLE. Não foi criada nem persistida
uma carteira. O registro fica em `build-crypto-board/hardware-hd-boot.log`;
`crypto-backend-report.json` registra os hashes e o escopo validado.
O framework Apple completo do Wallet Core não foi portado para Xtensa.

Ainda faltam o adaptador Cardano Icarus, builders de endereços/contas e assinatura. O backend público da placa continua retornando
`PUBLIC_DATA_UNAVAILABLE`; esta etapa não publica contas de teste pelo BLE.

## Abrir no Mac

Abra **`Abrir_simulador.command`** com dois cliques. Se o simulador antigo estiver
aberto, feche-o antes de abrir novamente. Pelo Terminal, a partir de `hardware/`:

```sh
./esp32/Abrir_simulador.command
```

- Mouse equivale a toque; segure e arraste para rolar.
- Use o teclado touch ou o teclado do Mac. Enter envia o campo; Esc cancela.
- O menu mantém a ordem original: leia primeiro, conexões, nova carteira,
  importar carteira, idioma, informações, site, desligar e reiniciar.
- Desligar fecha **apenas o simulador**. Reiniciar volta ao menu e mantém a carteira.
- Na primeira execução, escolha o idioma. A preferência é preservada entre sessões.
- Idiomas: **Português, Inglês, Espanhol, Francês, Mandarim, Japonês,
  Italiano e Alemão**. As palavras BIP39 continuam em inglês, como na
  original; a tradução nunca altera uma passphrase ou palavra de recuperação.

Os dados do simulador ficam em **`esp32/simulator-wallets/`**, separados dos dados
da carteira original. Cada gravação cria uma pasta com `keystore.json`,
`keystore_24.json` e `wallet.db`. O arquivo `CURRENT` identifica a versão ativa.
A seleção de idioma fica em `.LANGUAGE`. O diretório tem permissão 0700 e os
arquivos da carteira, 0600. Uma segunda instância não pode gravar a mesma carteira.

A geração usa entropia do sistema operacional, sem seed fixa. A frase exibida é
real, não é uma fixture de demonstração. Nenhuma senha ou palavra é enviada ao
console. O backend mantém os modos com passphrase e sem passphrase da original.
O código não acessa a rede para criar ou importar uma carteira.

## Fluxos e compatibilidade com a original

A referência preservada é `/Users/felippe/Desktop/airlock/hardware`. Os arquivos
Python/JavaScript de referência e da raiz desta cópia não foram alterados.

**Criar:** aviso de substituição se já existir carteira → instrução sobre
passphrase → entrada e confirmação → instrução sobre senha de pagamento →
entrada e confirmação → geração, derivação de contas e gravação criptografada →
instrução e exibição das 24 palavras → verificação de cada palavra → verificação
da passphrase e da senha → confirmação de sucesso. A gravação ocorre antes do
backup, como em `WalletService.generate_mnemonic` da original.

**Importar:** aviso de substituição → escolha de 12, 15, 18, 21 ou 24 palavras →
nova senha de pagamento e confirmação → entrada das palavras em ordem →
passphrase original, inclusive vazia → validação BIP39 pelo Wallet Core →
derivação, gravação e confirmação. A entrada aceita palavras não vazias; grafia e
checksum são verificados ao finalizar. Em caso de falha, a tela volta à instrução
das palavras e conserva a senha já escolhida, como na original.

O backend C++ reutiliza o registro original e preserva os resultados das 158
entradas antes derivadas por `js/Utils.js`. As cinco antes ignoradas agora também
geram contas na criação e na importação:

- 136 entradas secp256k1 com XPUB e endereço, nos dois modos.
- 27 entradas por endereço, com 100 contas no modo principal e 10 no padrão:
  as 22 Ed25519 originais mais Nimiq, Nano, NEO, Ontology e Cardano.
- Total de **3.242 registros** por criação/importação: 2.692 originais preservados
  e 550 novos. As **163 entradas** geram contas nos dois modos.
- Nano e Nimiq incrementam o último índice endurecido; NEO, Ontology e Cardano
  incrementam o último índice sem endurecimento, respeitando o registro.
- Cardano usa Icarus/CIP-3 e CIP-1852, com endereço base Shelley mainnet e
  staking `m/1852'/1815'/conta'/2/0`. Ver observação sobre passphrase abaixo.
- Mesmo esquema de `wallets_xpub` e `wallets_address`, incluindo `is_main`.
- Mesmo formato de keystore: PBKDF2-HMAC-SHA256, 100.000 iterações, salt de
  16 bytes, AES-256-GCM, nonce de 12 bytes, tag de 16 bytes, JSON/Base64 e
  campos `mnemonic`, `passphrase` e `isImported`.

Comportamentos restaurados da original:

- A operação de criação/importação limpa a carteira ativa quando o processamento
  começa. Uma importação inválida também deixa a carteira ativa vazia. O aviso
  de substituição continua vindo antes das entradas; nenhuma carteira de usuário
  foi aberta ou substituída durante os testes.
- Senhas e passphrases são exibidas durante a entrada. A tela de erro da
  verificação mostra a entrada e o valor correto, como `_handle_verify_error`.
- Confirmação divergente reinicia a entrada da senha/passphrase, não apenas sua
  confirmação. Senha igual à passphrase é recusada depois da confirmação.
- A senha de importação exige 12 caracteres, maiúscula, minúscula e número;
  não exige símbolo, conforme `_validate_rules` da original.
- Voltar na instrução da senha da criação retorna à instrução da passphrase.
  A lista de backup não aceita cancelar. A geração/importação não aceita
  interrupção pelo botão de cancelar; telas de entrada continuam canceláveis.
- A geração reproduz SHA256(entropia do SO + SHA256(passphrase-senha-relógio
  monotônico)), com 256 bits, preservando o Wallet Core 4.4.2.

Adaptações à plataforma: teclado touch e rolagem em 240×320 substituem as teclas
físicas; não há exigência de teclado USB. Nome e idiomas seguem o solicitado.
Entradas têm limite de 1.024 bytes e limpeza de memória. A flash da ESP32 ainda
precisa de backend próprio; no Mac, o armazenamento usa diretórios de geração e
`CURRENT`, com publicação conjunta das duas carteiras e do banco. A geração
ativa anterior é removida no início da substituição. Diretórios históricos
inativos de versões anteriores do simulador não são varridos nem apagados.

**Conexões e assinatura:** os controladores C++ agora incluem lista de dispositivos,
confirmação de remoção, código de pareamento, aceitar/rejeitar, cancelamento remoto,
revisão da transação, recusa, timeout de 60 segundos, processamento e resultado por
3 segundos antes do menu. São ligados pela interface `core/device_service.hpp`.
O simulador normal ainda não possui implementação dessa interface: mostra a lista
vazia prevista pela original e não recebe nem inventa pares ou transações. Os testes
injetam serviços de teste somente para validar essas telas e decisões. Os serviços
NimBLE/GATT e o dispatcher público estão integrados ao firmware de validação;
o transporte BLE do Mac e os signers reais continuam pendentes.

## Sincronização pública em C++

`PublicWalletService` separa a consulta pública do backend privado.
`NativeWallet::public_accounts` abre somente para leitura as tabelas
`wallets_xpub` e `wallets_address` da geração indicada por `CURRENT`, resolvido
internamente. As consultas usam parâmetros vinculados de modo e rede; não
descriptografam keystores nem chamam `unlock()`.

`PublicWalletRpc` implementa `get_device_info`, `verify_pin` e `get_accounts`, reutilizando
os UUIDs de `core/config.hpp` e `hardware/ble_framing.*`: JSON UTF-8, terminador
`###EOF###`, limite de 1 MiB e fragmentos de até `min(180, MTU - 3)` bytes.
O terminador é escapado se aparecer em uma string JSON. A autorização vem do
estado real do transporte; campos da requisição não autorizam a conexão.

Exemplo de consulta:

```json
{"id":"sync-2","method":"get_accounts","params":{"mode":"STANDARD","coins":["bitcoin","solana","ton","sui"],"include_all_addresses":true}}
```

Na mesma conexão autenticada e criptografada, a sequência é `get_device_info`,
STANDARD e HIDDEN. A primeira consulta com carteira disponível fixa a geração;
criação/importação ou troca da geração impede continuar a sessão antiga.
O dispatcher retorna somente o esquema público esperado pelo app, com `is_main`
inteiro, `xpub` vazio e `address_index` nos lotes, preservando os identificadores
das redes. Modos, tipos e limites inválidos, dados incompletos e duplicatas
produzem erro sem um lote parcial.

Para as 16 redes atuais do app, o banco de teste fornece **43 registros STANDARD**
e **313 HIDDEN**: 13 xpubs por modo, mais 10/100 endereços existentes de cada uma
das redes Solana, TON e Sui. `include_all_addresses: false` fornece apenas o
índice zero de cada lote. Nenhuma derivação privada adicional é feita na consulta.
O protocolo público não contém operação de assinatura nem campos de segredos.
Os signers futuros continuam sendo responsabilidade do hardware.

O CTest `public_wallet_export` importa uma fixture BIP39 pública em diretório
temporário e compara as respostas do Wallet Core 4.4.2 com os vetores públicos
4.4.4 do app (`tests/public_wallet_vectors.json`). Verifica filtros, ambos os modos,
xpub/endereço/caminho, campos permitidos, corrupção, parâmetros inválidos,
fragmentação UTF-8, MTU, desconexão, autorização e troca de geração. Também
verifica que o banco não muda e que a consulta funciona sem keystores acessíveis.

```sh
ctest --test-dir esp32/build-simulator -R '^public_wallet_export$' --output-on-failure
```

## Auditoria de 12, 18 e 24 palavras

Foram importadas seis fixtures BIP39 públicas, cobrindo 12, 18 e 24 palavras,
cada quantidade com passphrase vazia e com passphrase não vazia. Todas passaram
pelo backend C++ real do Mac e leitura dos dois keystores. Todos os campos do
banco são comparados: 158 entradas com `js/Utils.js` original, quatro novas com
Wallet Core WASM 4.4.2 e Cardano com Emurgo CSL 13.2.1 (referência independente).

| Palavras importadas | Sem passphrase | Com passphrase |
|---|---|---|
| 12 | 163/163 entradas com derivação verificada | 163/163 entradas com derivação verificada |
| 18 | 163/163 entradas com derivação verificada | 163/163 entradas com derivação verificada |
| 24 | 163/163 entradas com derivação verificada | 163/163 entradas com derivação verificada |

Os **19.452 registros de contas** coincidiram com as referências: 16.152 registros
originais e 3.300 novos. Sem passphrase,
os modos principal/padrão têm as mesmas chaves para os mesmos caminhos. Com
passphrase não vazia, seus endereços/chaves públicas diferem em todas as 163
entradas. O lote pré-gera 100 contas por endereço no modo principal e 10 no
padrão, inclusive quando ambos representam a mesma carteira.

As cinco entradas adicionadas mantêm os caminhos iniciais do registro:

- **Nimiq:** `m/44'/242'/0'/0'`, Ed25519.
- **Nano:** `m/44'/165'/0'`, Ed25519 Blake2b.
- **NEO:** `m/44'/888'/0'/0/0`, NIST P-256 (formato NEO do registro original).
- **Ontology:** `m/44'/1024'/0'/0/0`, NIST P-256.
- **Cardano:** `m/1852'/1815'/0'/0/0`, Ed25519 estendida Icarus.

**Passphrase de Cardano:** `HDWallet.cpp` da versão 4.4.2 fornece uma passphrase
vazia à função Icarus, ignorando a informada. `core/cardano_icarus.cpp` aplica a
passphrase UTF-8 real usando as mesmas funções Cardano/TrezorCrypto já presentes
na biblioteca oficial WalletCoreCommon. Não foi criada uma implementação própria
das primitivas criptográficas nem alterado o binário do Wallet Core. Os headers
necessários, sem mudanças, estão fixados por hash em
`vendor/wallet-core-4.4.2/trezor-include/provenance.json`.
Sem passphrase, os resultados também coincidem com o Cardano padrão do Wallet
Core. Com passphrase, são verificados independentemente contra Emurgo CSL.
Testes adicionais cobrem UTF-8, entropia não nula, conta/change/índice diferentes
de zero e rejeição de caminhos incompatíveis.

O esquema de recuperação de Cardano é **Icarus**; não é o esquema Ledger nem a
variante Icarus-Trezor de 24 palavras. O futuro signer Cardano deve reutilizar
`cardano_icarus_key` para obter a mesma chave da conta exibida, nunca recorrer ao
`TWHDWalletGetKey` original com passphrase. Referências:
[Wallet Core 4.4.2](https://github.com/trustwallet/wallet-core/blob/4.4.2/src/HDWallet.cpp),
[CIP-3 Icarus](https://github.com/cardano-foundation/CIPs/blob/master/CIP-0003/Icarus.md).

As inclusões valem para novas criações/importações. Esta mudança não abre nem
migra carteiras já salvas. Falha em qualquer derivação impede publicar um lote
incompleto como uma carteira criada com sucesso.

**Escopo:** esta matriz verifica importação, derivação padrão e armazenamento no
Mac. Não verifica assinaturas, funcionamento das redes, tokens específicos,
execução na ESP32 nem os caminhos alternativos registrados para quatro moedas.
O dispatcher original também não tem rota de assinatura para 14 entradas; a
lista está no relatório. No porte C++, os signers reais ainda não estão ligados.
A opção **Nova carteira** continua gerando somente 24 palavras e exigindo uma
passphrase, como a original; a matriz de 12/18/24 refere-se à **importação**.

Relatório de cada entrada e combinação: [`coin-support-audit.json`](coin-support-audit.json).
Teste reproduzível, sem carteiras reais ou acesso à rede:

```sh
cmake -S esp32 -B esp32/build-simulator
cmake --build esp32/build-simulator --target airlock_coin_support_fixture --parallel 6
ctest --test-dir esp32/build-simulator -R '^coin_support_matrix$' --output-on-failure
```

`tests/coin_support_vectors.json` mantém os hashes obtidos da original, sem
alteração. `tests/additional_coin_vectors.json` contém as referências das cinco
inclusões. Para regenerar esta segunda referência em desenvolvimento:

```sh
node esp32/tools/additional_coin_oracle.js /caminho/wallet-core-4.4.2/dist/lib/wallet-core.js /caminho/cardano-serialization-lib-nodejs-13.2.1/cardano_serialization_lib.js
```

Os testes locais usam os vetores já salvos, sem baixar dependências. Node/WASM e
Emurgo CSL são ferramentas de verificação; não entram no runtime C/C++ da carteira.

## Arquitetura

| Parte | Implementação | Situação |
|---|---|---|
| `core/rules.*`, `core/secret.hpp` | Regras, dicionário e buffers de segredos | C++ portável |
| `core/wallet_service.hpp` | Jobs assíncronos de geração e importação | Interface comum |
| `ui/states_wallet.*` | Estados e verificação do backup | Usados pelo fluxo real |
| `ui/wallet_app.*` | Navegação e ligação dos estados aos serviços | Usada pelo simulador; compila para ESP32 |
| `ui/touch_view.*` | Componentes LVGL, teclado, rolagem e confirmação | 240×320, compartilhados com a placa |
| `ui/translations.json`, `ui/fonts/` | Cinco idiomas novos e fonte da original | Sete idiomas na interface |
| `simulator/native_wallet.*` | Wallet Core nativo, AES-GCM, SQLite e commit | macOS, sem Python/Node em runtime |
| `core/public_wallet_service.hpp`, `core/public_wallet_rpc.*` | Consulta pública e dispatcher com geração fixa | Testados no Mac e ligados ao firmware |
| `hardware/ble_framing.*` | Framing, MTU e expiração | Compartilhados pelo teste e NimBLE |
| `idf_components/airlock_ble/` | GATT, criptografia, confirmação na tela, vínculos e notificações | Integrado à ESP32; falta teste físico |
| `core/device_service.hpp`, `ui/state_sign.*` | Pareamento, conexões, revisão, aprovação e timeout | NimBLE implementa a parte pública; signers pendentes |
| `idf_components/airlock_board/` | LCD SPI/DMA e touch I²C | Compilado; falta validar na placa |
| `idf_components/airlock_policy/` | Bloqueios Wi-Fi e microSD | Auditados no ELF do firmware |

As traduções inglesa/chinesa são extraídas dos dicionários originais durante o
build, sem importar Pygame. Scripts Python e conversores de fonte são ferramentas
de desenvolvimento; não são instalados na ESP32 nem executados pelo simulador.

## Compilar e testar o simulador

Dependências: CMake, compilador C++, SDL2, OpenSSL 3, SQLite e a distribuição
nativa oficial do Wallet Core 4.4.2 em `vendor/`. A versão e o hash do pacote
estão em `vendor/README.md`. LVGL é fixado em **9.2.2**, igual ao firmware.
O projeto LVGL 10 em `/Users/felippe/Downloads/lv_port_pc_vscode` permanece intacto;
aproveitamos o SDL2 já instalado no Mac.

```sh
cmake -S esp32 -B esp32/build-simulator \
  -DAIRLOCK_SIMULATOR=ON -DAIRLOCK_SANITIZERS=ON \
  -DAIRLOCK_LVGL_DIR="$PWD/esp32/firmware/managed_components/lvgl__lvgl" \
  -DOPENSSL_ROOT_DIR=/usr/local/opt/openssl@3 \
  -DCMAKE_BUILD_TYPE=Debug
cmake --build esp32/build-simulator --parallel 6
ctest --test-dir esp32/build-simulator --output-on-failure
```

Os testes usam somente fixtures públicas ou carteiras temporárias, sem tocar em
`simulator-wallets/` nem em carteiras do usuário. Verificam os estados, entrada
por SDL/touch, criação e importação completas pela UI, checksum inválido,
backup, senha errada, ciphertext adulterado, persistência e idiomas. Um teste
diferencial executa os métodos originais de `ui/states_wallet.py` e
`ui/components.py` sem alterá-los e compara 286 observações com a UI C++: textos,
visibilidade dos campos, erros, sequência, retorno, cancelamento e momento da
gravação. A renderização e os serviços desse teste usam fixtures públicas.
Os fluxos de dispositivo também verificam pareamento expirado, remoção cancelada,
recusa, timeout, resultado de assinatura e desconexão antes da confirmação.
ASan/UBSan verificam nosso código; o binário oficial pré-compilado do Wallet Core
não foi instrumentado por este build.

A comparação de **todos os campos dos 2.692 registros** usa um hash obtido da
execução de `js/Utils.js` original com Wallet Core WASM 4.4.2. Vetor e amostras:
`tests/original_wallet_vectors.json`; gerador de desenvolvimento:
`tools/original_wallet_vectors.js`. Node é usado apenas por esse teste de referência.

Também foi verificada a leitura dos keystores nos dois sentidos usando os métodos
`save_keystore` e `load_keystore` originais, sem alterações. Para repetir com um
Python de desenvolvimento que tenha `cryptography`:

```sh
python3 esp32/tests/original_keystore_compat.py esp32/build-simulator/airlock_native_wallet_tests
```

`wallet_flows` produz capturas PPM dos menus, instruções e entradas vazias nos
sete idiomas, sem capturar palavras ou senhas. `simulator_smoke` usa
`SDL_VIDEODRIVER=dummy`; eventos de texto sintéticos passam pelo mesmo dispatcher
real devido à limitação de `SDL_PushEvent` no SDL2-compat 2.32.74 instalado.

## Firmware da ES3C28P

Perfis LCDWIKI **ES3C28P** (touch FT6336G/I²C) e **ES3N28P** (placa do usuário,
sem touch), ESP32-S3, LCD ILI9341V/SPI de 2,8 polegadas, 240×320,
16 MB flash QSPI e 8 MB PSRAM OPI.
Pinos e fontes estão em `hardware/ES3C28P.md`.

Com **ESP-IDF 5.4.2** carregado pelo seu `export.sh`:

```sh
idf.py -C esp32/firmware build
```

Nesta alteração, a compilação ESP-IDF 5.4.2 foi verificada em diretório separado,
gerando `esp32/build-public-sync/airlock_board_check.bin`, sem substituir a imagem
antiga em `firmware/build/`. Para reproduzir esse build a partir de `hardware/`:

```sh
idf.py -C esp32/firmware -B "$PWD/esp32/build-public-sync" \
  -DSDKCONFIG="$PWD/esp32/build-public-sync/sdkconfig" build
```

A validação do host passou nos nove CTests `portable_core`, `migration_inventory`,
`touch_view`, `native_wallet`, `public_wallet_export`, `coin_support_matrix`,
`cardano_icarus`, `wallet_flows` e `original_flow_parity`, com ASan/UBSan habilitados.
O simulador interativo não foi iniciado. Na imagem Xtensa, a auditoria do ELF
confirmou os dez bloqueios de Wi-Fi/microSD; os testes físicos continuam pendentes.

Na etapa histórica de integração BLE descrita nesta seção, a imagem era somente
de validação de LCD, touch, memória e Bluetooth. O fluxo inicial atual está
descrito no início deste documento. O transporte anuncia os UUIDs
existentes, aceita leitura de `bef0` para iniciar o pareamento e comandos em
`bef1`, e envia as respostas em notificações `bef0`. Exige LE Secure Connections,
MITM e chave de 128 bits; o código numérico precisa de confirmação no touch.
Vínculos ficam em NVS, com remoção explícita no menu; NVS não é apagada
automaticamente e vínculos antigos não são expulsos para aceitar novos.

O serviço usa fila limitada, expiração, controle de pressão de buffers e envio
no task do host NimBLE para impedir que notificações ou decisões de pareamento
antigas sejam aplicadas a um identificador de conexão reutilizado. O MTU preferido
é 247. A troca STANDARD/HIDDEN usa uma única instância de RPC por conexão.

`get_device_info` fornece nome baseado no MAC, versão da aplicação, modelo e
tela reais. `get_accounts` chega ao dispatcher, mas o backend de placa é
explicitamente indisponível: retorna `PUBLIC_DATA_UNAVAILABLE`, sem incluir
fixtures no firmware. Não há sincronização completa de contas reais na ESP32
até implementar os builders de contas e a autorização de acesso. O código pode então receber
a implementação de `PublicWalletService` sem mudar o contrato BLE.
A gravação requer também bootloader e tabela de partições; o `.bin` isolado não
é uma imagem mesclada para offset zero. Nenhuma placa foi gravada nesta etapa.

**Wi-Fi e SD/microSD permanecem desabilitados por código porque a wallet é
inteiramente offline**, com o comentário solicitado junto aos bloqueios. BLE
é permitido para intercâmbio com o aplicativo; só o aplicativo acessa a rede.
O target obrigatório `airlock_check_policy` exige dez wrappers no ELF e rejeita
as entradas originais proibidas.

## Trabalho restante para equivalência completa

1. Completar o backend criptográfico de **Xtensa/ESP32-S3**: adaptar Cardano
   Icarus e os formatos de endereços/contas de cada rede. BIP39 e derivações
   de quatro curvas já usam TrezorCrypto 4.4.2 compilado para Xtensa e passaram
   nos autotestes físicos. O framework Apple e os signers completos não foram portados.
2. Ampliar a validação física do cofre já implementado: interrupções de energia,
   resistência a falhas, RAM e duração das derivações.
3. Ligar `get_accounts` ao armazenamento real da placa. NimBLE/GATT, pareamento,
   vínculos e o dispatcher público já estão integrados. As demais operações
   (`ping`, `get_account`, `create_account`, `sign_tx`, `reboot` e `shutdown`)
   continuam fora do dispatcher público desta etapa.
4. Portar os builders/signers JavaScript e a revisão vinculada à transação
   efetivamente assinada. O ciclo de confirmação existente não substitui essa
   validação. O inventário mantém as 56 classes e as lacunas do original.
5. Executar na placa real e verificar orientação, cores, toque, memória, energia
   e reconexão. Compilar não comprova comportamento físico.

`migration_inventory.json` separa explicitamente as derivações verificadas no
Mac daquelas ainda pendentes na ESP32. Reproduzir com `tools/inventory.py`.

## Animação de abertura

LVGL aceita imagens, `animimg` e GIF embutidos na flash, sem Wi-Fi ou microSD.
Ainda não foi criada uma animação de marca. A interface de 240×320 deve continuar
responsiva enquanto os serviços são inicializados.

O código derivado mantém GPL-3.0-or-later do Airlock. Bibliotecas e fontes
preservam suas próprias licenças.


### Fluxo público na ESP32 e PIN de conexão (2026-11-08)

A imagem `build-flow-board/airlock_board_check.bin` usa o cofre NVS da placa.
Após o desbloqueio local, cada conexão Bluetooth exige aceite no aparelho,
inclusive reconexões com vínculo salvo. A tela mostra o identificador BLE;
no primeiro pareamento também mostra o código de comparação numérica. O nome
remoto do celular não é inferido desse endereço. Recusar ou expirar o pareamento
não autoriza acesso público. O transporte exige Secure Connections autenticado,
MITM, criptografia e chave de 128 bits.

As respostas JSON preservam o `id` recebido e terminam em `###EOF###`.
`get_device_info` retorna `name: "BJJ-Cold-Wallet"` e `pin_required` booleano.
Esse booleano é verdadeiro quando existe PIN global e a política salva é
"Pedir PIN no celular". Após o aceite local, a política sem PIN permite
`get_accounts` diretamente; a política com PIN exige
`verify_pin` com `params.pin`. Somente PIN errado retorna sucesso com
`data.accepted: false`. PIN correto retorna `true`. Parâmetros inválidos,
armazenamento, indisponibilidade e bloqueio retornam `status: "error"`.
O PIN recebido não é salvo no celular por esse contrato e nunca é devolvido.

A validação vale para a conexão atual. Desconectar, bloquear o aparelho,
trocar credenciais ou mudar a carteira selecionada revoga a autorização.
O firmware aplica também o bloqueio remoto persistente: os dois primeiros erros
não esperam; do terceiro em diante, 10s, 20s, 40s… (`PIN_LOCKED` enquanto
bloqueado). O contador é compartilhado com o desbloqueio local, cujo erro
continua impondo 30s, 30s, 60s, 60s…; um acerto reinicia o contador.
Reiniciar o aparelho reinicia a espera pendente completa, não zera os erros.

A carteira selecionada em Minhas carteiras é a fonte da sincronização; ao ligar,
a seleção padrão é a primeira. `STANDARD` deriva BIP39 sem passphrase e retorna
`is_main: 0`; `HIDDEN` usa a passphrase BIP39 salva e retorna `is_main: 1`.
Sem passphrase, os modos possuem as mesmas chaves. A transactionPhrase protege
o cofre local e não é uma passphrase BIP39 nem parâmetro do Bluetooth.

Endereços e xpubs são calculados localmente e gravados em cache somente público,
vinculado à identidade do registro cifrado. Criações/importações e alterações
de frase preparam o cache antes de publicar a carteira. Carteiras anteriores
a esta versão pedem a transactionPhrase localmente uma vez ao abrir a lista de
moedas; depois o cache persiste e o Bluetooth não precisa dessa frase.
Excluir uma carteira remove seu cache no próximo commit NVS. Nenhuma consulta
pública deriva sementes ou recebe PIN salvo, seed, chave privada ou frase.

Estão incluídas as 16 redes obrigatórias do app, Stellar e as redes Base,
HyperEVM e Robinhood. Os endereços/xpubs iniciais das 16 redes são comparados
com `tests/public_wallet_vectors.json` nos dois modos. Bitcoin e Litecoin usam
zpub; Dogecoin usa dgub; os demais secp256k1 usam xpub. Solana, TON, Sui e Stellar
retornam `xpub: ""` e `address_index: 0`; atualmente a placa provisiona apenas
esse endereço inicial. `include_all_addresses: true` retorna os endereços
públicos provisionados, sem inventar um conjunto de endereços adicionais.
Redes não disponíveis produzem erro em vez de um lote incompleto.

A codificação TON V4R2 segue o [Wallet Core 4.4.2](https://github.com/trustwallet/wallet-core/blob/4.4.2/rust/chains/tw_ton/src/wallet/wallet_v4.rs):
`core/ton_code.hpp` registra hash e profundidade do contrato fixado nessa versão.
Os testes não usam a carteira real nem a seed do aparelho.

A tela da moeda apresenta ícone/nome, QR do endereço de recebimento e o mesmo
endereço completo abaixo, separado por uma linha em branco após toda a borda do QR. Alemão aparece como "Deutsch (Alemão)" no seletor
inicial e em Configurações > Idiomas; a escolha persiste e as seeds permanecem
inglesas.


### Nome do dispositivo

Novas configurações pedem o nome do dispositivo imediatamente após escolher o
idioma. O nome completo é `BJJ-Cold-Wallet-{nome}`. A opção
Configurações > Editar nome do dispositivo permite substituí-lo posteriormente.
O nome é salvo na chave NVS `device_name`, separada da configuração de idioma,
dos nomes das carteiras, do PIN e do cofre cifrado.

O nome completo é usado no anúncio Bluetooth, no serviço GAP e em
`get_device_info`. Alterar o nome encerra uma conexão ativa, revoga a autorização
e reinicia o anúncio com a nova identidade. O anúncio continua desabilitado
até o desbloqueio local. Instalações anteriores sem nome salvo mantêm
`BJJ-Cold-Wallet` e o fluxo de PIN existente, até editar o nome em Configurações.

O sufixo deve conter texto UTF-8 válido, sem controles nem espaços nas pontas.
Seu limite é 13 bytes, para que o nome completo caiba sem corte nos 29 bytes do
campo de nome da resposta de busca BLE legada. Erros de validação e de gravação
mantêm a tela de edição e o nome anterior. As mensagens foram traduzidas nos oito
catálogos; o nome escolhido pelo usuário não é traduzido.

Dispositivo configurado como `BJJ-Cold-Wallet-Flips` em 2026-11-10. A imagem
foi instalada após autorização, e o nome foi salvo pela interface e verificado
após reiniciar. Carteira e PIN preservados.


### Informações reais de Device (2026-11-10)

`get_device_info` inclui `platform`, com as versões de ESP-IDF e FreeRTOS usadas
pelo firmware. `version` vem de `esp_app_get_description`; `screen`, da resolução
do display LVGL ativo; `hardware`, do modelo de placa configurado e validado;
`name`, da configuração persistente do dispositivo.

`CPU` é uma string em graus Celsius obtida do sensor interno do chip a cada
requisição, na tarefa RPC. É omitida se o driver não puder fornecer uma leitura
finita; uma medição anterior não é reaproveitada nesse caso. A ausência do sensor
não impede consultar os outros metadados. O indicador `pin_required`, a aprovação
local, a validação de PIN e o terminador `###EOF###` permanecem no contrato.

O app compartilhado agora mapeia `platform` para `DeviceInfo.platform`, com
fallback `—` para firmware antigo. Sete testes de sincronização passaram,
incluindo preservação de todos os campos após buscar os dois modos e desconectar.
APK atualizado gerado; não instalado no celular nesta validação.

Detalhes e hashes: `device-metadata-report.json`.

### Sincronização de múltiplas carteiras

O contrato público versão 1 anuncia `wallet_sync_version: 1` em
`get_device_info`. Depois do desbloqueio local, aceite da conexão e PIN móvel
quando configurado, o cliente pode consultar `get_wallets`:

```json
{"id":"list-1","method":"get_wallets","params":{}}
```

A resposta `data` é uma lista de `{id, name, config}`. `config` contém somente
`word_count`, `passphrase_enabled` (passphrase BIP39 adicional) e `imported`.
Nenhum desses campos contém as palavras ou as credenciais. Os identificadores
são opacos, únicos por registro e persistem após reinício, troca de PIN e troca
de transactionPhrase. O nome não deve ser usado como chave de identidade.

Para baixar todas as carteiras, incluindo uma lista separada de contas por
carteira, usar `get_accounts` com `all_wallets: true`:

```json
{"id":"sync-standard","method":"get_accounts","params":{"all_wallets":true,"mode":"STANDARD","include_all_addresses":true,"coins":["bitcoin","ethereum"]}}
```

O array `data` passa a conter `{wallet_id, wallet_name, config, accounts}`.
Cada `accounts` contém apenas os registros públicos da carteira indicada.
Repetir a consulta com `mode: "HIDDEN"` na mesma conexão autorizada. O campo
`is_main` continua sendo 0 para STANDARD e 1 para HIDDEN; modos não são carteiras.
A lista `coins` define o escopo e deve conter todas as moedas desejadas. As 20
redes atualmente provisionadas estão no JSON completo de exemplo.

Alternativamente, informar `wallet_id` em `get_accounts`, sem `all_wallets`,
para receber somente o array de contas daquela carteira. Sem ambos os parâmetros,
o contrato anterior continua exportando a carteira selecionada no aparelho.
`all_wallets` e `wallet_id` não podem ser combinados. Um ID desconhecido retorna
`WALLET_NOT_FOUND`; não ocorre fallback silencioso para outra carteira.

A coleção fica vinculada a uma geração na primeira consulta pública. Se ela
mudar durante a sincronização, retornar `WALLET_GENERATION_CHANGED` e reiniciar
a sincronização em nova conexão. O cliente só deve publicar os dados depois de
receber todas as carteiras e modos. Se qualquer carteira estiver indisponível,
corrompida ou incompleta para o escopo solicitado, toda a resposta é erro, sem
publicar o grupo anterior. O escopo explícito não altera a seleção na tela da
cold; o fluxo de assinatura continua vinculado à carteira selecionada nela.

O enquadramento permanece JSON UTF-8, mesmo ID da requisição, terminador
`###EOF###`. A autorização de conexão é para os dados públicos das carteiras do
dispositivo, cujos nomes aparecem no aceite local. Seeds, chaves privadas, PIN,
transactionPhrase e passphrase não fazem parte da exportação pública. O gerador
serializa somente nós públicos, e o RPC rejeita prefixos de chave estendida
privada no campo `xpub`.

Exemplo completo, gerado pelo RPC com fixtures públicas de teste:
`docs/multi-wallet-sync-example.json`. O arquivo reúne quatro trocas completas
(requisição/resposta): informações do dispositivo, lista de carteiras e contas
STANDARD/HIDDEN. Ele não é uma captura Bluetooth da carteira real.

O agente do app deve ler `wallet_id`, manter as contas por carteira/modo e usar
`all_wallets: true` ou consultar explicitamente cada ID. O firmware não consegue
obrigar um cliente antigo a solicitar, armazenar ou mostrar as duas carteiras.

### Monero stagenet por Bluetooth

A imagem de teste com `AIRLOCK_MONERO_STAGENET_SYNC=ON` anuncia
`monero_sync_version: 1`. Em **Configurações → Monero (stagenet)**, o usuário
pode habilitar ou desabilitar Monero por carteira. O padrão é habilitado.
`get_wallets` e os grupos de `get_accounts` retornam o mesmo
`config.monero_enabled`.

Na sessão usada para as contas públicas, após o PIN Bluetooth quando
configurado, o app chama `monero_get_watch_wallet` para cada carteira
habilitada, usando o ID estável e o modo STANDARD. A cold mostra carteira
e modo, solicita confirmação e a frase de transação local e devolve a chave
de visualização apenas nessa resposta. O app pode continuar importando as
demais moedas se o usuário recusar.

`monero_sign_key_images` recebe `outputs_data_hex` de
`export_outputs` do Monero Wallet RPC v0.18.5.4, com `all: true` e
`start: 0`. O firmware aceita até 128 saídas e 256 KiB de conteúdo
descriptografado por arquivo. Retorna o lote completo com `offset: 0`
ou um erro, preservando o ID e o enquadramento `###EOF###`.

Para testar envio em stagenet, compile a biblioteca estática com o compilador
Xtensa e o componente `rust-src` da mesma versão (validado com 1.90.0.0).
A partir de `hardware`:

```sh
cargo +esp -Z build-std=core,alloc build --locked --release --no-default-features \
  --target xtensa-esp32s3-none-elf --manifest-path esp32/monero_signer/Cargo.toml

idf.py -C esp32/firmware -B "$PWD/esp32/build-monero-board" \
  -DAIRLOCK_MONERO_STAGENET_SYNC=ON \
  -DAIRLOCK_MONERO_SIGNER_ARCHIVE="$PWD/esp32/monero_signer/target/xtensa-esp32s3-none-elf/release/libairlock_monero_signer.a" build
```

Essa imagem anuncia `monero_signing_version: 1`. `monero_sign_transfer`
recebe `wallet_id`, `mode`, `network: "stagenet"`, `recipient`, `amount_atomic`,
`fee_atomic` e `unsigned_txset`. Valor e taxa são strings inteiras em
unidades atômicas. O visor mostra carteira, modo, rede, destinatário completo,
valor e taxa. A frase de transação é digitada na cold. O assinador compara os
dados confirmados ao pacote wallet2 e exige que o troco volte ao endereço
principal da carteira. A resposta leva `wallet_id`, `mode`, `network`, `txid`
e `tx_hex`, sem transportar as chaves de gasto ou de visualização.

O perfil inicial aceita uma transação RingCT CLSAG/Bulletproof+ com um
destinatário de endereço padrão, troco positivo, até 16 entradas e um pacote
wallet2 v5 de até 128 KiB. O app registra os bytes assinados antes de
transmitir e reutiliza esses bytes após uma resposta de rede incerta.


## Assinatura de todos os ativos — 2026.11.21-allassets1

Firmware com assinatura e revisão física para os 86 ativos já cadastrados no app, incluindo os 19 antes sem envio. Protocolos novos: TRON, XRPL, Stellar, Sui, TON e os cinco tokens Cardano. Monero: teste real de CLSAG/Bulletproof+ aprovado; mantém stagenet. Chaves privadas e recuperação da seed permanecem no hardware.

Instalador: `../instaladores/BJJ-Cold-Wallet-ES3C28P-2026-11-21-allassets1.zip`. Binários, hashes SHA-256 e política USB estão no subdiretório `firmware` do pacote. Atualização comum preserva carteiras e pareamentos; não exige nem realiza apagamento. Esta execução gerou o pacote, sem gravar a placa. USB apenas alimentação durante a aplicação, console/JTAG USB e controle de agente desativados; sem eFuses.

Relatório e limites: [`../../app/docs/all-assets-signing-2026-11-21.md`](../../app/docs/all-assets-signing-2026-11-21.md). Testes criptográficos usam fixtures públicas; nenhuma transação é transmitida. Dependências dos verificadores independentes fixadas em `tests/protocol-reference`.
