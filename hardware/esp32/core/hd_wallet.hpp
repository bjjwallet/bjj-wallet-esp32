// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "core/crypto.hpp"
#include <string>

namespace airlock::crypto {
enum class HdError {
    None,
    InvalidMnemonic,
    InvalidEntropy,
    InvalidPath,
    UnsupportedCurve,
    PassphraseTooLong,
    InvalidText,
    Random,
    Crypto
};
enum class Curve { Secp256k1, Nist256p1, Ed25519, Ed25519Blake2b };
struct Entropy {
    Sensitive<32> data;
    std::size_t size = 0;
    void clear() {
        wipe(data.value.data(), data.value.size());
        size = 0;
    }
};
struct DerivedKey {
    Curve curve = Curve::Secp256k1;
    Key private_key;
    Key chain_code;
    std::array<std::uint8_t, 33> public_key{};
    std::size_t public_key_size = 0;
    std::uint32_t child = 0;
    std::uint32_t parent_fingerprint = 0;
    std::uint8_t depth = 0;
    void clear();
};

// English BIP39, canonical single-space spelling; all five standard lengths.
HdError mnemonic_entropy(std::string_view mnemonic, Entropy& output);
HdError mnemonic_from_entropy(Bytes entropy, Credential& output);
HdError generate_mnemonic(std::size_t word_count, RandomSource& random, Credential& output);
// Exact Wallet Core 4.4.2 seed semantics for valid UTF-8 <=256 bytes. No Unicode
// normalization; >256 bytes are rejected instead of upstream silent truncation.
HdError wallet_core_seed(std::string_view mnemonic, std::string_view passphrase, Seed& output);
// Strict m[/index[']...] paths, at most 16 indices. Ed25519 requires every index
// hardened. No signing or addresses here; returned private buffers wipe on exit.
// The RNG must be cryptographically secure: upstream uses it for EC blinding.
// RNG failure in that void-returning upstream interface aborts, never falls back.
HdError derive_key(Bytes seed, Curve curve, std::string_view path, RandomSource& random,
                   DerivedKey& output);
// Serialize a secp256k1 derived key as public BIP32 data; never serializes xprv.
HdError extended_public(const DerivedKey& key, std::uint32_t version, std::string& output);
bool hd_self_test(RandomSource& random);
} // namespace airlock::crypto
