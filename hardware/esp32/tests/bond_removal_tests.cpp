// SPDX-License-Identifier: GPL-3.0-or-later
#include "hardware/bond_removal.hpp"
#include <cstdlib>
#include <iostream>
using namespace airlock;
int main() {
    unsigned checks = 0;
    const auto check = [&](bool value) {
        ++checks;
        if (!value)
            std::exit(1);
    };
    for (bool advertising : {false, true})
        for (int result : {0, 7}) {
            const bool initially_advertising = advertising;
            unsigned stops = 0, removals = 0, resumes = 0;
            const int status = without_advertising(
                advertising,
                [&] {
                    ++stops;
                    advertising = false;
                    return 0;
                },
                [&] {
                    ++removals;
                    check(!advertising);
                    return result;
                },
                [&] {
                    ++resumes;
                    advertising = true;
                });
            check(status == result && removals == 1);
            check(stops == unsigned(initially_advertising) && resumes == stops);
            check(advertising == initially_advertising);
        }
    bool removed = false, resumed = false;
    check(without_advertising(
              true, [] { return 5; },
              [&] {
                  removed = true;
                  return 0;
              },
              [&] { resumed = true; }) == 5);
    check(!removed && !resumed);
    std::cout << checks << " bond removal advertising regression checks passed\n";
}
