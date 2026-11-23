# Política obrigatória: Wi-Fi e microSD desabilitados

O firmware distribuído no ZIP também deve ser compilado com `AIRLOCK_USB_POWER_ONLY=ON`, `CONFIG_ESP_CONSOLE_NONE=y` e `AIRLOCK_BOARD_AGENT_CONTROL=OFF`. Em `app_main`, antes de acessar o cofre, `bjj_usb_power_only_init` desliga os pads de dados do USB Serial/JTAG nativo do ESP32-S3. O build recusa console ativo ou controle USB de validação; a auditoria ELF exige o inicializador e recusa drivers USB de aplicação.

O cabo mantém a alimentação. Esse bloqueio vale durante a aplicação; o modo ROM de recuperação por BOOT/reset permanece disponível e nenhum eFuse é alterado. Não representa desativação física ou permanente de USB/JTAG. Consulte o README do instalador para atualizar uma placa cuja porta serial já desapareceu.

Também é obrigatório desabilitar `CONFIG_USJ_ENABLE_USB_SERIAL_JTAG`. O inicializador remove os pull-ups de D+/D-, seleciona GPIO19/20 como entradas e desliga o clock do módulo USB. A validação física de 17/11/2026 confirmou a ausência da interface USB após sair do carregador pelo watchdog; o reset por RTS isoladamente manteve a placa no carregador.

Este componente consta em `REQUIRES airlock_policy` no `firmware/main` e deve
permanecer obrigatório na aplicação completa da carteira. O projeto ESP-IDF
inclui `idf_components/` em `EXTRA_COMPONENT_DIRS`.

As opções não são configuráveis pelo usuário da wallet: Wi-Fi e microSD ficam
proibidos. Os wrappers de link retornam `ESP_ERR_NOT_SUPPORTED` para inicialização,
conexão, varredura e transmissão Wi-Fi e inicialização do host/cartão SD. Não há
fallback para as funções originais. Nenhum driver próprio de SD deve ser incluído.
Na ES3C28P o slot usa SDIO dedicado: CLK GPIO38 fica baixo; CMD GPIO40 e dados
GPIO39/41/48/47 ficam como entradas. O display usa SPI separado. Esses pinos
foram confirmados no manual/esquema LCDWIKI e constam em `hardware/es3c28p.hpp`.

Bluetooth, tela, touch e flash interna continuam permitidos. Desabilitar Wi-Fi não
significa desligar todo o rádio: BLE é necessário para o protocolo da wallet e
também pode fornecer entropia ao RNG. Antes de iniciar BLE, a geração de segredos
precisa de uma fonte de entropia habilitada segundo a documentação da Espressif.

Não se usa `CONFIG_ESP_WIFI_ENABLED=n` como garantia: no ESP-IDF 5.4.2 esse símbolo
é interno ao Kconfig e tem valor padrão derivado do SoC. Disponibilidade do driver
e atividade do rádio são coisas diferentes. O bloqueio aqui é feito no código e
no link. O alvo obrigatório `airlock_check_policy` verifica os dez wrappers e a
ausência das entradas originais proibidas no ELF final. O teste físico ainda
precisa ser realizado antes da liberação.

O bloqueio impede o uso pelo firmware; não desliga fisicamente a alimentação do
slot. Não altera eFuses nem a memória flash interna necessária para a wallet.

Referências: [Kconfig oficial de Wi-Fi](https://github.com/espressif/esp-idf/blob/v5.4.2/components/esp_wifi/Kconfig),
[RNG do ESP32-S3](https://docs.espressif.com/projects/esp-idf/en/stable/esp32s3/api-reference/system/random.html).
