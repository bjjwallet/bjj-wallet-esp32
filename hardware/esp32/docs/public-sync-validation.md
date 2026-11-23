# Validação do firmware público — 2026-11-08

- Build ESP-IDF passou, incluindo auditoria do ELF: Wi-Fi e microSD bloqueados.
- Suíte de setup: 11 testes; 10 passaram inicialmente, o teste de interface identificou a recusa por Back ignorada. Após a correção, `first_login_flow` e `setup_public` passaram novamente.
- `setup_public`: vetores reais de endereço e xpub das 16 redes do app, STANDARD/HIDDEN, IDs/framing, PIN obrigatório/opcional, PIN errado versus falha interna, revogação por época de autorização, reconexão, bloqueios remotos persistentes e corrupção do cache.
- Renderização da tela Bitcoin inspecionada, incluindo a linha em branco solicitada após a borda do QR: ícone/nome, QR e endereço completo. Captura em `build-setup/device-public-qr.ppm`, somente carteira de fixture pública.
- Placa: imagem gravada com hash verificado e partição de carteiras preservada; backup anterior cifrado mantido localmente com permissão 0600.
- Placa: PIN existente desbloqueou; “Minha wallet” preservada; carteira auxiliar continuou ausente. Dados públicos provisionados usando a transactionPhrase somente na interface local.
- QR da captura pública decodificado com Vision no macOS, confirmando o mesmo endereço mostrado.
- Placa: 20 redes disponíveis; Bitcoin abriu a tela de QR.
- Placa: Alemão selecionado, interface em alemão e preferência preservada após reiniciar. Português restaurado ao final.
- Placa: depois de reiniciar, lista de moedas abriu a partir do cache, sem pedir novamente transactionPhrase. Aparelho deixado na tela Bitcoin.
- Suíte do simulador: 11/12 testes passaram. `coin_support_matrix` está bloqueado pela referência antiga de hash de `js/crypto_worker.js`, arquivo não alterado nesta implementação. O teste público `public_wallet_export` passou.
- Limite de verificação: ainda não houve uma conexão ponta a ponta com o aplicativo móvel neste teste. Aceite/recusa foram exercitados no teste da interface; mensagens e PIN, nos testes do núcleo e cofre usados pelo firmware.

O cache contém apenas endereços/xpubs. Não registrar PIN, transactionPhrase,
passphrase BIP39, palavras ou chaves privadas nos relatórios.

O teste `first_login_flow` passou novamente após o ajuste final de espaçamento (77,48s).

Correção de descoberta Bluetooth: respeitar o tipo UUID16/32/128 retornado pelo NimBLE ao montar o anúncio. Na placa, confirmou-se ausência de anúncio antes do PIN local e início de anúncio com serviço BEEF após o desbloqueio. Log: `build-flow-board/ble-discovery-live.log`. Aguardando nova busca no aplicativo móvel.
