// SPDX-License-Identifier: GPL-3.0-or-later
#include "hardware/ble_framing.hpp"
#include "core/secret.hpp"
#include <algorithm>
#include <array>

namespace airlock {
BleFraming::BleFraming(std::size_t limit)
    : max_payload_(std::min(limit, config::max_packet_bytes)) {
    // Prevent reallocations from leaving un-wiped copies of a password-bearing
    // request. A 1 MiB budget requires a verified external-memory allocation on
    // ESP32; a smaller explicit budget must be reported as a protocol limit.
    buffer_.reserve(max_payload_ + config::eof_marker.size());
}
BleFraming::~BleFraming() {
    clear_buffer();
}
void BleFraming::clear_buffer() {
    if (!buffer_.empty())
        wipe(buffer_.data(), buffer_.size());
    buffer_.clear();
}
void BleFraming::reset() {
    clear_buffer();
    last_rx_ms_ = 0;
    status_ = ReceiveStatus::Ok;
}
ReceiveStatus BleFraming::poll(std::uint64_t now) {
    if (status_ != ReceiveStatus::Ok)
        return status_;
    if (!buffer_.empty()) {
        if (now < last_rx_ms_)
            status_ = ReceiveStatus::ClockError;
        else if (now - last_rx_ms_ >= config::rx_timeout_ms)
            status_ = ReceiveStatus::Expired;
    }
    if (status_ != ReceiveStatus::Ok)
        clear_buffer();
    return status_;
}
ReceiveStatus BleFraming::receive(std::string_view bytes, std::uint64_t now,
                                  const Receiver& receiver) {
    if (poll(now) != ReceiveStatus::Ok)
        return status_;
    constexpr auto marker = config::eof_marker;
    for (char c : bytes) {
        buffer_.push_back(c);
        last_rx_ms_ = now;
        if (buffer_.size() >= marker.size() &&
            buffer_.compare(buffer_.size() - marker.size(), marker.size(), marker) == 0) {
            const auto length = buffer_.size() - marker.size();
            if (length > max_payload_) {
                status_ = ReceiveStatus::Overflow;
                clear_buffer();
                return status_;
            }
            // Synchronous callback: consume/copy before return; do not reenter.
            receiver(std::string_view(buffer_.data(), length));
            clear_buffer();
        } else {
            // Discount only a suffix which can still become an EOF delimiter.
            std::size_t prefix = std::min(buffer_.size(), marker.size() - 1);
            while (prefix &&
                   buffer_.compare(buffer_.size() - prefix, prefix, marker.substr(0, prefix)) != 0)
                --prefix;
            if (buffer_.size() - prefix > max_payload_) {
                status_ = ReceiveStatus::Overflow;
                clear_buffer();
                return status_;
            }
        }
    }
    return status_;
}
bool send_frame(std::string_view json, std::uint16_t mtu,
                const std::function<bool(std::string_view)>& notify) {
    // An EOF marker inside a JSON string must be escaped by the JSON serializer.
    if (mtu < 23 || json.size() > config::max_packet_bytes ||
        json.find(config::eof_marker) != std::string_view::npos)
        return false;
    const auto chunk_size = std::min<std::size_t>(config::notification_bytes, mtu - 3);
    std::array<char, config::notification_bytes> chunk{};
    const auto total = json.size() + config::eof_marker.size();
    for (std::size_t offset = 0; offset < total; offset += chunk_size) {
        const auto size = std::min(chunk_size, total - offset);
        for (std::size_t i = 0; i < size; ++i) {
            auto position = offset + i;
            chunk[i] = position < json.size() ? json[position]
                                              : config::eof_marker[position - json.size()];
        }
        const bool sent = notify({chunk.data(), size});
        wipe(chunk.data(), chunk.size());
        if (!sent)
            return false;
    }
    return true;
}
} // namespace airlock
