// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/trezor_random_scope.hpp"
#include <TrezorCrypto/rand.h>
#include <cstdlib>

namespace {
// Each synchronous operation binds its own source; concurrent tasks cannot
// borrow each other's RNG or leave a pointer to a destroyed caller object.
thread_local airlock::crypto::RandomSource* source = nullptr;
} // namespace
namespace airlock::crypto {
TrezorRandomScope::TrezorRandomScope(RandomSource& random) : previous_(source) {
    source = &random;
}
TrezorRandomScope::~TrezorRandomScope() {
    source = previous_;
}
} // namespace airlock::crypto
extern "C" void random_buffer(std::uint8_t* output, std::size_t size) {
    if (!source || !source->fill({output, size})) {
        if (output)
            airlock::wipe(output, size);
        std::abort(); // Upstream API cannot propagate entropy failure safely.
    }
}
extern "C" std::uint32_t random32() {
    std::uint32_t value = 0;
    random_buffer(reinterpret_cast<std::uint8_t*>(&value), sizeof(value));
    return value;
}
