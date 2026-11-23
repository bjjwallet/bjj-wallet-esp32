// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/crypto.hpp"
#include <algorithm>

namespace airlock::crypto {
namespace {
template <std::size_t N>
bool matches(const std::array<std::uint8_t, N>& data, std::string_view hex) {
    constexpr char digits[] = "0123456789abcdef";
    if (hex.size() != N * 2)
        return false;
    for (std::size_t i = 0; i < N; ++i)
        if (digits[data[i] >> 4] != hex[i * 2] || digits[data[i] & 15] != hex[i * 2 + 1])
            return false;
    return true;
}
} // namespace
bool self_test() {
    std::array<std::uint8_t, 32> digest{};
    Key key;
    Seed seed;
    if (!sha256(bytes("abc"), digest) ||
        !matches(digest, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad") ||
        !pbkdf2_sha256(bytes("password"), bytes("salt"), 1, key) ||
        !matches(key.value, "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"))
        return false;
    // BIP39/Trezor public vector: this tests the KDF, not mnemonic validation.
    if (!pbkdf2_sha512(bytes("abandon abandon abandon abandon abandon abandon abandon abandon "
                             "abandon abandon abandon about"),
                       bytes("mnemonicTREZOR"), 2048, seed) ||
        !matches(seed.value, "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f"
                             "09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04"))
        return false;
    // NIST AES-256-GCM public zero-key/zero-IV vector.
    key.value.fill(0);
    Nonce nonce{};
    Tag tag{};
    std::array<std::uint8_t, 16> plain{}, cipher{}, recovered{};
    if (!seal(key, nonce, {plain.data(), plain.size()}, {cipher.data(), cipher.size()}, tag) ||
        !matches(cipher, "cea7403d4d606b6e074ec5d3baf39d18") ||
        !matches(tag, "d0d1c8a799996bf0265b98b5d48ab919") ||
        !open(key, nonce, {cipher.data(), cipher.size()}, tag,
              {recovered.data(), recovered.size()}) ||
        recovered != plain)
        return false;
    // Every authentication failure must erase any previously returned plaintext.
    tag[0] ^= 1;
    recovered.fill(0xa5);
    if (open(key, nonce, {cipher.data(), cipher.size()}, tag,
             {recovered.data(), recovered.size()}) ||
        !std::all_of(recovered.begin(), recovered.end(), [](auto byte) { return byte == 0; }))
        return false;
    return true;
}
} // namespace airlock::crypto
