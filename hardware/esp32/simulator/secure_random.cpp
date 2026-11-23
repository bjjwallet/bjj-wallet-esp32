// SPDX-License-Identifier: GPL-3.0-or-later
#include <Security/Security.h>
#include <cstdint>
#include <cstdlib>

// Wallet Core's official Apple archive expects these platform entropy hooks,
// just as Sources/SecRandom.m in its distribution does. Never use std::rand.
extern "C" void random_buffer(std::uint8_t* buffer, std::size_t size) {
    if (SecRandomCopyBytes(kSecRandomDefault, size, buffer) != errSecSuccess)
        std::abort();
}
extern "C" std::uint32_t random32() {
    std::uint32_t value = 0;
    random_buffer(reinterpret_cast<std::uint8_t*>(&value), sizeof(value));
    return value;
}
