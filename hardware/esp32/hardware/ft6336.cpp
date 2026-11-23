// SPDX-License-Identifier: GPL-3.0-or-later
#include "hardware/ft6336.hpp"
#include "hardware/es3c28p.hpp"

namespace airlock {
TouchSample Ft6336Decoder::read(const std::array<std::uint8_t, 5>& r, bool io_ok) {
    auto cancel = [this]() {
        blocked_ = true;
        active_ = false;
        return TouchSample{false, true, 0, 0};
    };
    if (!io_ok || (r[0] & 0xf0) != 0)
        return cancel();
    const auto count = r[0] & 0x0f;
    if (count == 0) {
        blocked_ = false;
        active_ = false;
        return {};
    }
    if (count != 1)
        return cancel();
    const auto event = r[1] >> 6;
    const auto id = r[3] >> 4;
    const auto x = static_cast<std::uint16_t>(((r[1] & 0x0f) << 8) | r[2]);
    const auto y = static_cast<std::uint16_t>(((r[3] & 0x0f) << 8) | r[4]);
    if (x >= es3c28p::touch_width || y >= es3c28p::touch_height || event == 3 ||
        (active_ && track_id_ != id))
        return cancel();
    if (blocked_)
        return {false, true, 0, 0};
    if (event == 1) {
        active_ = false;
        return {};
    }
    active_ = true;
    track_id_ = id;
    return {true, false, y, static_cast<std::uint16_t>(es3c28p::touch_width - 1 - x)};
}
} // namespace airlock
