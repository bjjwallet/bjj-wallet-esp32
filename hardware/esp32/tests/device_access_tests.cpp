// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/device_access.hpp"
#include "core/hardware_pin_policy.hpp"
#include <cstdio>
#include <cstdlib>
#include <iostream>
using namespace airlock;
int main() {
    unsigned checks = 0;
    const auto check = [&](bool value) {
        ++checks;
        if (!value) {
            std::cerr << "Device access check " << checks << " failed\n";
            std::exit(1);
        }
    };
    for (const auto* pin : {"Aa1234", "0Ab123", "Aa123456789012345678901234567890"})
        check(valid_hardware_pin(pin));
    for (const auto* pin : {"", "123456", "abcdef", "ABC123", "abc123", "Aa123", "Aa123!", "Aa123á",
                            "Aa1234 ", "Aa123456789012345678901234567890123"})
        check(!valid_hardware_pin(pin));
    check(valid_legacy_hardware_pin("001234"));
    DeviceAccessList list;
    std::string disk;
    bool fail = false;
    const auto persist = [&](std::string_view value) {
        if (fail)
            return false;
        disk = value;
        return true;
    };
    const std::string id = "0:11:22:33:44:55:66";
    check(list.update(id, false, false, persist));
    check(list.entries().size() == 1 && !list.blocked(id));
    check(list.update(id, true, false, persist));
    check(list.blocked(id));
    check(!list.update(id, false, false, persist));
    check(list.blocked(id));
    DeviceAccessList reboot;
    check(reboot.decode(disk) && reboot.blocked(id));
    const auto original = disk;
    fail = true;
    check(!list.update(id, false, true, persist));
    check(list.blocked(id) && disk == original);
    fail = false;
    check(list.update(id, false, true, persist));
    check(!list.blocked(id) && list.entries().empty());
    check(reboot.decode(disk) && !reboot.blocked(id));
    check(list.update(id, false, false, persist));
    check(!list.update("invalid", true, false, persist));
    check(!reboot.decode("BJJDEV1\nB" + id + "\nB" + id + "\n"));
    check(!reboot.decode("BJJDEV1\nB" + id));
    check(!reboot.decode("corrupt"));
    for (unsigned i = 0; i < 32; ++i) {
        char phone[24];
        std::snprintf(phone, sizeof phone, "0:11:22:33:44:55:%02x", i);
        check(list.update(phone, i % 2, false, persist));
    }
    check(reboot.decode(disk) && reboot.entries().size() == 33);
    check(reboot.blocked("0:11:22:33:44:55:01"));
    std::cout << checks << " device access and mandatory PIN checks passed\n";
}
