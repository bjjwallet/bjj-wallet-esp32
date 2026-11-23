# Perfil da placa ES3C28P

**Atualização de 04/11/2026:** a placa física comprada foi confirmada pelo usuário
como **ES3N28P, sem touch**. Este documento preserva o perfil ES3C28P com touch.
Para testar a ES3N28P, use o build explícito `AIRLOCK_BOARD_NO_TOUCH=ON` junto
de `AIRLOCK_BOARD_AGENT_CONTROL=ON`, conforme `../README.md`. A ausência de
touch não libera silenciosamente a inicialização de um build ES3C28P.

Modelo com toque capacitivo, identificado pelos dados fornecidos pelo usuário e
confirmado na [documentação do fabricante](https://www.lcdwiki.com/2.8inch_ESP32-S3_Display).
A versão **ES3N28P** não tem touch e não é alvo deste firmware.

| Componente | Configuração |
|---|---|
| SoC | ESP32-S3, dual core Xtensa LX7, 240 MHz |
| Flash | 16 MB QSPI, QIO a 80 MHz |
| PSRAM | 8 MB OPI, octal a 80 MHz; autoteste no boot |
| LCD | ILI9341V IPS, 240×320, retrato, RGB565; SPI2 a 20 MHz |
| Touch | FT6336G, endereço I²C de 7 bits `0x38`; I²C0 a 100 kHz |
| Renderização | LVGL 9.2.2; dois buffers DMA internos de 9.600 bytes |
| USB | Serial/JTAG nativo nos GPIO19/20; usado pelo ESP-IDF para console/programação |

As frequências de SPI/I²C são escolhas conservadoras do driver. Pinagem,
controladores e capacidades de memória vêm da documentação da placa.

| Sinal | GPIO |
|---|---|
| LCD CS / DC | 10 / 46 |
| LCD SCK / MOSI / MISO | 12 / 11 / 13 |
| LCD backlight, ativo alto | 45 |
| LCD reset | Ligado a CHIP_PU/EN; usar reset por comando, sem GPIO dedicado |
| Touch SDA / SCL | 16 / 15 |
| Touch reset / interrupção | 18 / 17, ambos ativos baixos |
| microSD CLK / CMD | 38 / 40, uso proibido |
| microSD D0 / D1 / D2 / D3 | 39 / 41 / 48 / 47, uso proibido |
| Amplificador de áudio | 1, mantido alto (desabilitado) enquanto não utilizado |

A sequência de registradores de LCDWIKI, específica deste painel IPS, está em
`idf_components/airlock_board/lcd_init.c`. O driver genérico ILI9341 da Espressif
recebe essa sequência. A orientação inicial não espelha os eixos; alinhamento
com o toque precisa ser confirmado no dispositivo físico.

O touch aguarda 310 ms após sair do reset (mínimo de 300 ms no datasheet), lê
status e coordenadas em uma transação I²C e envia eventos de ponteiro ao LVGL.
Toque mantido durante o boot, dois dedos, mudança de identificador, posição fora
da tela ou falha do barramento cancelam a interação. É preciso levantar todos
os dedos para retomar; um erro não pode se transformar em confirmação.

Wi-Fi e microSD são desabilitados porque a carteira opera offline. O SDIO tem
barramento próprio: CLK fica baixo e CMD/D0–D3 em entrada, sem inicializar
host/cartão. Os wrappers negam as chamadas do SDK e o build audita o ELF.
A alimentação elétrica do slot não é cortada por essa política. BLE e flash
interna permanecem permitidos, mas o alvo de teste não inicializa BLE nem grava
segredos. Não se altera eFuse nem se ativa rádio para o teste de display.

## Validação física pendente

Na primeira execução, verificar cores RGB, legibilidade, todos os cantos e as
extremidades do teclado, arraste/rolagem, segundo dedo e toque mantido no boot.
O boot deve recusar memória abaixo de 16 MB flash/8 MB PSRAM ou ausência do
touch. A compilação por si só não confirma esses comportamentos no hardware.

## Fontes

- [Pinagem e capacidades de memória — LCDWIKI](https://www.lcdwiki.com/2.8inch_ESP32-S3_Display).
- [Manual dos exemplos ESP-IDF — LCDWIKI](https://www.lcdwiki.com/res/ES3C28P/2.8inch_ES3C28P_ES3N28P_ESP-IDF_Demo_Instructions.pdf).
- [Esquema ES3C28P/ES3N28P — LCDWIKI](https://www.lcdwiki.com/res/ES3C28P/2.8inch_ESP32-S3_Display_Schematic.pdf).
- [Valores de inicialização ILI9341V — LCDWIKI](https://www.lcdwiki.com/res/ES3C28P/ILI9341V_Init.txt).
- [Reset e interface FT6336G — FocalTech, hospedado pelo fabricante da placa](https://www.lcdwiki.com/res/PublicFile/D-FT6336G-DataSheet-V1.0.pdf).
- [Protocolo de coordenadas FT5x06 — implementação Espressif](https://github.com/espressif/esp-bsp/blob/master/components/lcd_touch/esp_lcd_touch_ft5x06/esp_lcd_touch_ft5x06.c).
- [Endereço FT6336G e pinagem ES3C28P — implementação Bruce](https://github.com/BruceDevices/firmware/blob/main/boards/ES3C28P/interface.cpp).

Observação: o texto comercial menciona Bluetooth clássico/BR-EDR, mas o
[ESP32-S3 oferece Bluetooth LE](https://www.espressif.com/en/products/socs/esp32-s3),
que é o protocolo usado pela carteira original.
