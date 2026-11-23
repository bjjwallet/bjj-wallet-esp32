// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "core/crypto.hpp"
#include <mbedtls/ctr_drbg.h>

namespace airlock::crypto {
// Startup only: seed with the SAR ADC entropy source before BLE/ADC start.
// Runtime reseeding requires an enabled BLE controller; no Wi-Fi is needed.
// Do not call start concurrently or during ADC/radio use. Own on an internal
// task stack, or allocate in internal RAM; never persist the DRBG state.
class BoardRandom final : public RandomSource {
  public:
    BoardRandom();
    ~BoardRandom();
    BoardRandom(const BoardRandom&) = delete;
    BoardRandom& operator=(const BoardRandom&) = delete;
    bool start_before_bluetooth();
    // At most 1024 bytes per request. Failure erases the entire output.
    bool fill(Output output) override;

  private:
    static int entropy(void* context, unsigned char* output, std::size_t size);
    mbedtls_ctr_drbg_context context_;
    bool collecting_boot_entropy_ = false;
    bool ready_ = false;
};
} // namespace airlock::crypto
