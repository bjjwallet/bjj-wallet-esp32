// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <string_view>
namespace airlock {
inline bool valid_hardware_pin(std::string_view pin) {
    if (pin.size() < 6 || pin.size() > 32)
        return false;
    bool upper = false, lower = false, digit = false;
    for (const char c : pin) {
        if (c >= 'A' && c <= 'Z')
            upper = true;
        else if (c >= 'a' && c <= 'z')
            lower = true;
        else if (c >= '0' && c <= '9')
            digit = true;
        else
            return false;
    }
    return upper && lower && digit;
}
// Existing wallets remain locally recoverable until their PIN is upgraded.
inline bool valid_legacy_hardware_pin(std::string_view pin) {
    if (pin.size() < 4 || pin.size() > 12)
        return false;
    for (char c : pin)
        if (c < '0' || c > '9')
            return false;
    return true;
}
} // namespace airlock
