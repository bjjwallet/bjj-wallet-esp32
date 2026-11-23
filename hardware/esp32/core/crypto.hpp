// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "core/secret.hpp"
#include <array>
#include <cstdint>

namespace airlock::crypto {
struct Bytes {
    const std::uint8_t* data;
    std::size_t size;
};
struct Output {
    std::uint8_t* data;
    std::size_t size;
};
class RandomSource {
  public:
    virtual ~RandomSource() = default;
    virtual bool fill(Output output) = 0;
};
inline Bytes bytes(std::string_view text) {
    return {reinterpret_cast<const std::uint8_t*>(text.data()), text.size()};
}
template <std::size_t N> struct Sensitive {
    std::array<std::uint8_t, N> value{};
    Sensitive() = default;
    ~Sensitive() {
        wipe(value.data(), value.size());
    }
    Sensitive(const Sensitive&) = delete;
    Sensitive& operator=(const Sensitive&) = delete;
};
using Key = Sensitive<32>;
using Seed = Sensitive<64>;
using Salt = std::array<std::uint8_t, 16>;
using Nonce = std::array<std::uint8_t, 12>;
using Tag = std::array<std::uint8_t, 16>;
inline constexpr std::size_t max_plaintext = 8192;

// Caller owns buffers; input and output must not overlap. These are primitives,
// not mnemonic validation, Unicode normalization, account derivation or storage.
bool sha256(Bytes input, std::array<std::uint8_t, 32>& digest);
bool pbkdf2_sha256(Bytes password, Bytes salt, std::uint32_t iterations, Key& key);
bool pbkdf2_sha512(Bytes password, Bytes salt, std::uint32_t iterations, Seed& seed);
bool keystore_key(Bytes password, const Salt& salt, Key& key);
// Same 100,000-round PBKDF2-SHA256, in audited TrezorCrypto incremental chunks.
// The worker yields between chunks so UI/idle tasks and watchdogs stay alive.
bool keystore_key_cooperative(Bytes password, const Salt& salt, Key& key, void (*yield)());
// AES-256-GCM, 96-bit nonce, full 128-bit tag, no AAD: original keystore format.
// Each encryption under the same key MUST have a fresh nonce.
// Output is erased on failure; unauthenticated plaintext must never be used.
bool seal(const Key& key, const Nonce& nonce, Bytes plaintext, Output ciphertext, Tag& tag,
          Bytes aad = {nullptr, 0});
bool open(const Key& key, const Nonce& nonce, Bytes ciphertext, const Tag& tag, Output plaintext,
          Bytes aad = {nullptr, 0});

// Public known-answer vectors only. Shared by host tests and board startup.
bool self_test();
} // namespace airlock::crypto
