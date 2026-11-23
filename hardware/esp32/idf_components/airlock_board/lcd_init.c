// SPDX-License-Identifier: GPL-3.0-or-later
#include "lcd_init.h"

// Panel register values supplied by LCDWIKI for this exact IPS module:
// https://www.lcdwiki.com/res/ES3C28P/ILI9341V_Init.txt
// BGR/RGB565 are configured by the Espressif panel driver. Portrait orientation.
// Reset is software (LCD reset is tied to ESP32 EN). Backlight stays off until
// the first LVGL screen is rendered. The application enables display afterward.
static const ili9341_lcd_init_cmd_t commands[] = {
    {0xCF, (const uint8_t[]){0x00, 0xC1, 0x30}, 3, 0},
    {0xED, (const uint8_t[]){0x64, 0x03, 0x12, 0x81}, 4, 0},
    {0xE8, (const uint8_t[]){0x85, 0x00, 0x78}, 3, 0},
    {0xCB, (const uint8_t[]){0x39, 0x2C, 0x00, 0x34, 0x02}, 5, 0},
    {0xF7, (const uint8_t[]){0x20}, 1, 0},
    {0xEA, (const uint8_t[]){0x00, 0x00}, 2, 0},
    {0xC0, (const uint8_t[]){0x13}, 1, 0},
    {0xC1, (const uint8_t[]){0x13}, 1, 0},
    {0xC5, (const uint8_t[]){0x22, 0x35}, 2, 0},
    {0xC7, (const uint8_t[]){0xBD}, 1, 0},
    {0x21, NULL, 0, 0},
    {0xB6, (const uint8_t[]){0x0A, 0xA2}, 2, 0},
    {0xF6, (const uint8_t[]){0x01, 0x30}, 2, 0},
    {0xB1, (const uint8_t[]){0x00, 0x1B}, 2, 0},
    {0xF2, (const uint8_t[]){0x00}, 1, 0},
    {0x26, (const uint8_t[]){0x01}, 1, 0},
    {0xE0,
     (const uint8_t[]){0x0F, 0x35, 0x31, 0x0B, 0x0E, 0x06, 0x49, 0xA7, 0x33, 0x07, 0x0F, 0x03, 0x0C,
                       0x0A, 0x00},
     15, 0},
    {0xE1,
     (const uint8_t[]){0x00, 0x0A, 0x0F, 0x04, 0x11, 0x08, 0x36, 0x58, 0x4D, 0x07, 0x10, 0x0C, 0x32,
                       0x34, 0x0F},
     15, 0},
    {0x11, NULL, 0, 120},
};
const ili9341_vendor_config_t airlock_lcd_config = {
    .init_cmds = commands,
    .init_cmds_size = sizeof(commands) / sizeof(commands[0]),
};
