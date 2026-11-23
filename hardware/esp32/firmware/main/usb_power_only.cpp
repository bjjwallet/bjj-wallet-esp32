// SPDX-License-Identifier: GPL-3.0-or-later
#include "hal/usb_serial_jtag_ll.h"
#include "driver/gpio.h"
#include "esp_private/periph_ctrl.h"
#include <cstdlib>

// Software-only disconnect of the ESP32-S3 native USB data interface. VBUS
// remains connected for power. Run before opening the wallet or starting BLE.
// ROM download mode after a physical BOOT/reset remains available; no eFuses
// are burned and no claim of a permanent hardware USB/JTAG lock is made.
extern "C" void bjj_usb_power_only_init() {
    // Startup can already have gated the peripheral clock. Enable it only
    // while applying the disconnect, under the shared-register RCC lock.
    PERIPH_RCC_ATOMIC() {
        usb_serial_jtag_ll_enable_bus_clock(true);
    }
    const usb_serial_jtag_pull_override_vals_t no_pulls{};
    usb_serial_jtag_ll_phy_enable_pull_override(&no_pulls);
    usb_serial_jtag_ll_phy_enable_pad(false);
    if (usb_serial_jtag_ll_phy_is_pad_enabled())
        std::abort();
    // Explicitly remove the USB attach pull-up and select ordinary GPIO input
    // on D-/D+ (19/20 on ESP32-S3); neither pin is used by this touch board.
    gpio_config_t pins{};
    pins.pin_bit_mask = (1ULL << GPIO_NUM_19) | (1ULL << GPIO_NUM_20);
    pins.mode = GPIO_MODE_INPUT;
    pins.pull_up_en = GPIO_PULLUP_DISABLE;
    pins.pull_down_en = GPIO_PULLDOWN_DISABLE;
    pins.intr_type = GPIO_INTR_DISABLE;
    if (gpio_config(&pins) != ESP_OK)
        std::abort();
    PERIPH_RCC_ATOMIC() {
        usb_serial_jtag_ll_enable_bus_clock(false);
    }
}
