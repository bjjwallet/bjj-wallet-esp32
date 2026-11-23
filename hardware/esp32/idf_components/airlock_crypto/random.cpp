// SPDX-License-Identifier: GPL-3.0-or-later
#include "airlock_random.hpp"
#include "bootloader_random.h"
#include "esp_bt.h"
#include "esp_random.h"

namespace airlock::crypto {
BoardRandom::BoardRandom() {
    mbedtls_ctr_drbg_init(&context_);
}
BoardRandom::~BoardRandom() {
    mbedtls_ctr_drbg_free(&context_);
}
int BoardRandom::entropy(void* context, unsigned char* output, std::size_t size) {
    const auto& self = *static_cast<BoardRandom*>(context);
    if (!self.collecting_boot_entropy_ &&
        esp_bt_controller_get_status() != ESP_BT_CONTROLLER_STATUS_ENABLED)
        return MBEDTLS_ERR_CTR_DRBG_ENTROPY_SOURCE_FAILED;
    esp_fill_random(output, size);
    return 0;
}
bool BoardRandom::start_before_bluetooth() {
    if (ready_ || esp_bt_controller_get_status() != ESP_BT_CONTROLLER_STATUS_IDLE)
        return false;
    constexpr unsigned char personalization[] = "Bjj-AirLock/ESP32-S3/crypto-v1";
    bootloader_random_enable();
    collecting_boot_entropy_ = true;
    const auto status = mbedtls_ctr_drbg_seed(&context_, entropy, this, personalization,
                                              sizeof(personalization) - 1);
    collecting_boot_entropy_ = false;
    bootloader_random_disable(); // Required before board peripherals and NimBLE.
    ready_ = status == 0;
    return ready_;
}
bool BoardRandom::fill(Output output) {
    if (!ready_ || !output.data || output.size == 0 || output.size > MBEDTLS_CTR_DRBG_MAX_REQUEST ||
        mbedtls_ctr_drbg_random(&context_, output.data, output.size) != 0) {
        if (output.data)
            wipe(output.data, output.size);
        return false;
    }
    return true;
}
} // namespace airlock::crypto
