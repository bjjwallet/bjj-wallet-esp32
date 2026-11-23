# Firmware distribuído

- O firmware distribuído no ZIP do instalador deve usar USB apenas para alimentação durante a execução da carteira: `AIRLOCK_USB_POWER_ONLY=ON`, `CONFIG_ESP_CONSOLE_NONE=y` e `AIRLOCK_BOARD_AGENT_CONTROL=OFF`.
- Desabilitar também `CONFIG_USJ_ENABLE_USB_SERIAL_JTAG`; remover pull-ups USB de D+/D- e desligar o clock do periférico. Conferir o desaparecimento da interface durante a aplicação, distinguindo-a do modo BOOT solicitado fisicamente pelo usuário.
- A desconexão USB deve ocorrer antes de acessar as carteiras ou iniciar o Bluetooth. Não incluir USB CDC, armazenamento USB, comandos seriais ou controle remoto por USB nessa versão.
- Preservar carteiras, apelido e pareamentos nas atualizações. Não queimar eFuses nem bloquear permanentemente o modo de recuperação como parte da instalação comum.
- O modo ROM de gravação/recuperação, acessado fisicamente por BOOT/reset, continua disponível. Documentar que o bloqueio de dados vale durante a execução do firmware e não é um corte físico ou permanente do USB/JTAG.
- Manter README, hashes e ZIP atualizados quando o firmware distribuído mudar. Não empacotar builds de validação com controle USB.
- Na ES3C28P testada, o reset por RTS após BOOT permaneceu no carregador. O instalador deve sair do carregador usando o watchdog do esptool, limpando apenas o sinal volátil de download forçado. Preservar flash/eFuses e tratar a ausência da porta depois de iniciar a carteira como esperada.
