// SPDX-License-Identifier: GPL-3.0-or-later
// Wire format preserved from core/ble_impl.py (Airlock, Le Wang).
#pragma once
#include "core/config.hpp"
#include <cstdint>
#include <functional>
#include <string>
#include <string_view>

namespace airlock {
enum class ReceiveStatus { Ok, Overflow, Expired, Disconnected, ClockError };
class BleFraming {
  public:
    using Receiver = std::function<void(std::string_view)>;
    explicit BleFraming(std::size_t max_payload = config::max_packet_bytes);
    ~BleFraming();
    BleFraming(const BleFraming&) = delete;
    BleFraming& operator=(const BleFraming&) = delete;
    // A fault poisons this session until reset on reconnect. A valid-looking tail
    // after a truncated/oversized request must never become a new command.
    ReceiveStatus receive(std::string_view bytes, std::uint64_t now_ms, const Receiver& receive);
    ReceiveStatus poll(std::uint64_t now_ms);
    void reset();
    std::size_t buffered_bytes() const {
        return buffer_.size();
    }

  private:
    std::size_t max_payload_;
    std::string buffer_;
    std::uint64_t last_rx_ms_ = 0;
    ReceiveStatus status_ = ReceiveStatus::Ok;
    void clear_buffer();
};

// The transport supplies an authenticated, encrypted connection and handles
// backpressure/notification pacing (50 ms in the original implementation).
// Return false on the first unsent fragment; never silently drop a notification.
bool send_frame(std::string_view json, std::uint16_t att_mtu,
                const std::function<bool(std::string_view)>& notify);
} // namespace airlock
