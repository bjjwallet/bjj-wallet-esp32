// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/monero_archive_crypto.hpp"
#include <TrezorCrypto/aes.h>
#include <TrezorCrypto/blake256.h>
#include <TrezorCrypto/sha3.h>
#include "int-util.h"
#include <cstdlib>
#include <cstring>
#include <memory>
#if defined(ESP_PLATFORM)
#include "esp_heap_caps.h"
#endif

extern "C" {
void aesb_single_round(const uint8_t*, uint8_t*, uint8_t*);
void aesb_pseudo_round(const uint8_t*, uint8_t*, uint8_t*);
void groestl(const uint8_t*, unsigned long long, uint8_t*);
int jh_hash(int, const uint8_t*, unsigned long long, uint8_t*);
int skein_hash(int, const uint8_t*, size_t, uint8_t*);
}

namespace airlock::crypto {
namespace {
constexpr std::size_t scratch_bytes = 2 * 1024 * 1024;
using Block = std::array<std::uint64_t, 2>;
struct State {
    SHA3_CTX keccak{};
    aes_encrypt_ctx aes{};
    std::array<Block, 8> text{};
    Block a{}, b{}, c{}, next{};
    ~State() {
        wipe(this, sizeof *this);
    }
};
void release(Block* scratch) {
    if (!scratch)
        return;
    wipe(scratch, scratch_bytes);
    std::free(scratch);
}
std::uint8_t* bytes(Block& block) {
    return reinterpret_cast<std::uint8_t*>(block.data());
}
std::size_t index(const Block& block) {
    return (block[0] >> 4) & (scratch_bytes / sizeof(Block) - 1);
}
} // namespace

bool monero_archive_key(Bytes input, Key& key, void (*yield)()) {
    wipe(key.value.data(), key.value.size());
    if (!input.data)
        return false;
#if defined(ESP_PLATFORM)
    auto* memory = heap_caps_malloc(scratch_bytes, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
#else
    auto* memory = std::malloc(scratch_bytes);
#endif
    std::unique_ptr<Block, decltype(&release)> scratch(static_cast<Block*>(memory), release);
    if (!scratch)
        return false;

    State state;
    auto* words = state.keccak.hash;
    auto* raw = reinterpret_cast<std::uint8_t*>(words);
    keccak_256_Init(&state.keccak);
    keccak_Update(&state.keccak, input.data, input.size);
    alignas(8) std::array<std::uint8_t, SHA3_256_BLOCK_LENGTH> padding{};
    const auto pad = padding.size() - state.keccak.rest;
    padding.front() = 1;
    padding[pad - 1] |= 0x80;
    keccak_Update(&state.keccak, padding.data(), pad);

    auto* schedule = reinterpret_cast<std::uint8_t*>(state.aes.ks);
    if (aes_encrypt_key256(raw, &state.aes) != 0)
        return false;
    std::memcpy(state.text.data(), raw + 64, sizeof(state.text));
    for (std::size_t i = 0; i < scratch_bytes / sizeof(state.text); ++i) {
        for (auto& block : state.text)
            aesb_pseudo_round(bytes(block), bytes(block), schedule);
        std::memcpy(scratch.get() + i * state.text.size(), state.text.data(), sizeof(state.text));
        if (yield && (i & 255) == 0)
            yield();
    }

    state.a = {words[0] ^ words[4], words[1] ^ words[5]};
    state.b = {words[2] ^ words[6], words[3] ^ words[7]};
    for (std::size_t i = 0; i < (1U << 19); ++i) {
        auto& first = scratch.get()[index(state.a)];
        aesb_single_round(bytes(first), bytes(state.c), bytes(state.a));
        first = {state.c[0] ^ state.b[0], state.c[1] ^ state.b[1]};
        auto& second = scratch.get()[index(state.c)];
        std::uint64_t high = 0;
        const auto low = mul128(state.c[0], second[0], &high);
        state.next = {state.a[0] + high, state.a[1] + low};
        state.a = {state.next[0] ^ second[0], state.next[1] ^ second[1]};
        second = state.next;
        state.b = state.c;
        if (yield && (i & 4095) == 0)
            yield();
    }

    if (aes_encrypt_key256(raw + 32, &state.aes) != 0)
        return false;
    std::memcpy(state.text.data(), raw + 64, sizeof(state.text));
    for (std::size_t i = 0; i < scratch_bytes / sizeof(state.text); ++i) {
        for (std::size_t j = 0; j < state.text.size(); ++j) {
            auto& block = state.text[j];
            const auto& source = scratch.get()[i * state.text.size() + j];
            block[0] ^= source[0];
            block[1] ^= source[1];
            aesb_pseudo_round(bytes(block), bytes(block), schedule);
        }
        if (yield && (i & 255) == 0)
            yield();
    }
    std::memcpy(raw + 64, state.text.data(), sizeof(state.text));
    padding.fill(0);
    keccak_Update(&state.keccak, padding.data(), padding.size());
    auto* digest = key.value.data();
    constexpr std::size_t state_size = sizeof(state.keccak.hash);
    switch (raw[0] & 3) {
        case 0:
            blake256(raw, state_size, digest);
            return true;
        case 1:
            groestl(raw, state_size * 8, digest);
            return true;
        case 2:
            return jh_hash(256, raw, state_size * 8, digest) == 0;
        case 3:
            return skein_hash(256, raw, state_size * 8, digest) == 0;
    }
    return false;
}
} // namespace airlock::crypto
