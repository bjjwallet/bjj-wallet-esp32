// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "core/config.hpp"
#include "core/rules.hpp"
#include <algorithm>
#include <string>
#include <string_view>
namespace airlock {
inline constexpr std::string_view device_name_prefix = config::product_name;
// Legacy BLE scan response: 31 bytes minus the name AD header and brand prefix.
inline constexpr std::size_t device_name_max_bytes = 29 - device_name_prefix.size() - 1;
inline bool valid_device_name(std::string_view name) {
    return !name.empty() && name.size() <= device_name_max_bytes && valid_text(name) &&
           name.front() != ' ' && name.back() != ' ' &&
           std::none_of(name.begin(), name.end(),
                        [](unsigned char c) { return c < 0x20 || c == 0x7f; });
}
inline std::string full_device_name(std::string_view suffix) {
    return suffix.empty() ? std::string(device_name_prefix)
                          : std::string(device_name_prefix) + "-" + std::string(suffix);
}
} // namespace airlock
