// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <array>
#include <cstddef>
#include <cstdint>

namespace airlock::es3c28p {
// LCDWIKI ES3C28P (capacitive touch), N16R8. Not the ES3N28P variant.
// Pinout: https://www.lcdwiki.com/2.8inch_ESP32-S3_Display
inline constexpr char model[] = "ES3C28P";
inline constexpr std::size_t flash_bytes = 16 * 1024 * 1024;
inline constexpr std::size_t psram_bytes = 8 * 1024 * 1024;
inline constexpr std::uint16_t touch_width = 240, touch_height = 320;
// Landscape, clockwise from the original panel/touch coordinate system.
inline constexpr std::uint16_t width = touch_height, height = touch_width;
inline constexpr int lcd_cs = 10, lcd_dc = 46, lcd_sck = 12;
inline constexpr int lcd_mosi = 11, lcd_miso = 13, lcd_backlight = 45;
// LCD reset is wired to CHIP_PU/EN. Use ILI9341 software reset, NEVER GPIO 0.
inline constexpr int lcd_reset = -1;
inline constexpr int touch_sda = 16, touch_scl = 15;
inline constexpr int touch_reset = 18, touch_interrupt = 17;
inline constexpr std::uint8_t touch_address = 0x38;
inline constexpr int audio_enable = 1; // active low; keep high while unused
// This board uses a dedicated 4-bit SDIO bus, not the LCD SPI bus.
inline constexpr int sd_clock = 38;
inline constexpr std::array<int, 5> sd_command_data{40, 39, 41, 48, 47};
inline constexpr std::array<int, 6> sd_pins{38, 40, 39, 41, 48, 47};
inline constexpr std::size_t draw_buffer_bytes = width * 20 * sizeof(std::uint16_t);
} // namespace airlock::es3c28p
