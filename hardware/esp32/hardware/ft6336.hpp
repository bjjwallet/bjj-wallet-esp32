// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <array>
#include <cstdint>

namespace airlock {
struct TouchSample {
    bool pressed = false;
    bool cancel = false;
    std::uint16_t x = 0, y = 0;
};

// Decode TD_STATUS and the first point (registers 0x02..0x06) in one I2C read.
// Single-finger interaction only. Invalid reports cancel, rather than completing
// a click at a stale coordinate. Wait for all fingers to lift before resuming.
class Ft6336Decoder {
  public:
    TouchSample read(const std::array<std::uint8_t, 5>& report, bool io_ok = true);

  private:
    bool blocked_ = true; // a finger held during boot must not activate a button
    bool active_ = false;
    std::uint8_t track_id_ = 0;
};
} // namespace airlock
