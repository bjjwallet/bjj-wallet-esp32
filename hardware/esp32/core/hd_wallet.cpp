// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/hd_wallet.hpp"
#include "core/rules.hpp"
#include "core/trezor_random_scope.hpp"
#include <TrezorCrypto/bip32.h>
#include <TrezorCrypto/bip39.h>
#include <TrezorCrypto/curves.h>
#include <TrezorCrypto/ed25519-donna/ed25519-blake2b.h>
#include <algorithm>
#include <charconv>
#include <cstring>

namespace airlock::crypto {
namespace {
struct Node {
    HDNode value{};
    ~Node() {
        wipe(&value, sizeof(value));
    }
};
bool parse_path(std::string_view path, std::array<std::uint32_t, 16>& indices, std::size_t& size) {
    size = 0;
    if (path == "m")
        return true;
    if (path.size() > 192 || path.substr(0, 2) != "m/")
        return false;
    path.remove_prefix(2);
    while (!path.empty()) {
        if (size == indices.size())
            return false;
        const auto slash = path.find('/');
        auto component = path.substr(0, slash);
        const bool hardened = !component.empty() && component.back() == '\'';
        if (hardened)
            component.remove_suffix(1);
        if (component.empty() || (component.size() > 1 && component.front() == '0') ||
            component.front() < '0' || component.front() > '9')
            return false;
        std::uint32_t value = 0;
        const auto parsed =
            std::from_chars(component.data(), component.data() + component.size(), value);
        if (parsed.ec != std::errc{} || parsed.ptr != component.data() + component.size() ||
            value >= 0x80000000U)
            return false;
        indices[size++] = value | (hardened ? 0x80000000U : 0);
        if (slash == std::string_view::npos)
            return true;
        path.remove_prefix(slash + 1);
    }
    return false;
}
const char* curve_name(Curve curve) {
    switch (curve) {
        case Curve::Secp256k1:
            return SECP256K1_NAME;
        case Curve::Nist256p1:
            return NIST256P1_NAME;
        case Curve::Ed25519:
            return ED25519_NAME;
        case Curve::Ed25519Blake2b:
            return ED25519_BLAKE2B_NANO_NAME;
    }
    return nullptr;
}
} // namespace
void DerivedKey::clear() {
    wipe(private_key.value.data(), private_key.value.size());
    wipe(chain_code.value.data(), chain_code.value.size());
    public_key.fill(0);
    public_key_size = 0;
    child = parent_fingerprint = 0;
    depth = 0;
}
HdError mnemonic_entropy(std::string_view mnemonic, Entropy& output) {
    output.clear();
    Credential text;
    if (mnemonic.empty() || !valid_text(mnemonic) || !text.assign(mnemonic) ||
        mnemonic_check(text.view().data()) != 1)
        return HdError::InvalidMnemonic;
    Sensitive<33> bits;
    const auto count = mnemonic_to_bits(text.view().data(), bits.value.data());
    if (count < 132 || count > 264 || count % 33 != 0)
        return HdError::InvalidMnemonic;
    output.size = static_cast<std::size_t>(count / 33 * 4);
    std::copy_n(bits.value.begin(), output.size, output.data.value.begin());
    return HdError::None;
}
HdError mnemonic_from_entropy(Bytes entropy, Credential& output) {
    output.clear();
    if (!entropy.data || entropy.size < 16 || entropy.size > 32 || entropy.size % 4)
        return HdError::InvalidEntropy;
    Sensitive<256> text;
    const auto* result = mnemonic_from_data(entropy.data, static_cast<int>(entropy.size),
                                            reinterpret_cast<char*>(text.value.data()),
                                            static_cast<int>(text.value.size()));
    if (!result || !output.assign(result))
        return HdError::Crypto;
    return HdError::None;
}
HdError generate_mnemonic(std::size_t word_count, RandomSource& random, Credential& output) {
    output.clear();
    if (!valid_word_count(word_count))
        return HdError::InvalidEntropy;
    Entropy entropy;
    entropy.size = word_count / 3 * 4;
    if (!random.fill({entropy.data.value.data(), entropy.size}))
        return HdError::Random;
    return mnemonic_from_entropy({entropy.data.value.data(), entropy.size}, output);
}
HdError wallet_core_seed(std::string_view mnemonic, std::string_view passphrase, Seed& output) {
    wipe(output.value.data(), output.value.size());
    if (passphrase.size() > 256)
        return HdError::PassphraseTooLong;
    if (!valid_text(passphrase))
        return HdError::InvalidText;
    Entropy entropy;
    const auto status = mnemonic_entropy(mnemonic, entropy);
    if (status != HdError::None)
        return status;
    Sensitive<264> salt;
    std::memcpy(salt.value.data(), "mnemonic", 8);
    if (!passphrase.empty())
        std::memcpy(salt.value.data() + 8, passphrase.data(), passphrase.size());
    return pbkdf2_sha512(bytes(mnemonic), {salt.value.data(), passphrase.size() + 8}, 2048, output)
               ? HdError::None
               : HdError::Crypto;
}
HdError derive_key(Bytes seed, Curve curve, std::string_view path, RandomSource& random,
                   DerivedKey& output) {
    output.clear();
    if (!seed.data || seed.size < 16 || seed.size > 64)
        return HdError::InvalidEntropy;
    const auto* name = curve_name(curve);
    if (!name)
        return HdError::UnsupportedCurve;
    std::array<std::uint32_t, 16> indices{};
    std::size_t count = 0;
    if (!parse_path(path, indices, count))
        return HdError::InvalidPath;
    const bool edwards = curve == Curve::Ed25519 || curve == Curve::Ed25519Blake2b;
    if (edwards && std::any_of(indices.begin(), indices.begin() + count,
                               [](auto index) { return index < 0x80000000U; }))
        return HdError::InvalidPath;
    TrezorRandomScope binding(random);
    Node node;
    if (hdnode_from_seed(seed.data, static_cast<int>(seed.size), name, &node.value) != 1)
        return HdError::Crypto;
    std::uint32_t parent = 0;
    for (std::size_t i = 0; i < count; ++i) {
        if (!edwards && i + 1 == count)
            parent = hdnode_fingerprint(&node.value);
        if (hdnode_private_ckd(&node.value, indices[i]) != 1)
            return HdError::Crypto;
    }
    if (edwards) {
        if (curve == Curve::Ed25519)
            ed25519_publickey(node.value.private_key, output.public_key.data());
        else
            ed25519_publickey_blake2b(node.value.private_key, output.public_key.data());
        output.public_key_size = 32;
    } else {
        if (hdnode_fill_public_key(&node.value) != 0)
            return HdError::Crypto;
        std::copy_n(node.value.public_key, 33, output.public_key.begin());
        output.public_key_size = 33;
    }
    std::copy_n(node.value.private_key, 32, output.private_key.value.begin());
    std::copy_n(node.value.chain_code, 32, output.chain_code.value.begin());
    output.depth = static_cast<std::uint8_t>(count);
    output.curve = curve;
    output.child = count ? indices[count - 1] : 0;
    output.parent_fingerprint = parent;
    return HdError::None;
}
HdError extended_public(const DerivedKey& key, std::uint32_t version, std::string& output) {
    output.clear();
    if (key.curve != Curve::Secp256k1 || key.public_key_size != 33 ||
        (key.public_key[0] != 2 && key.public_key[0] != 3))
        return HdError::UnsupportedCurve;
    if (version != 0x0488b21eU && version != 0x049d7cb2U && version != 0x04b24746U &&
        version != 0x02facafdU)
        return HdError::UnsupportedCurve;
    Node node;
    // Public-only node: serialization never receives private key bytes.
    if (hdnode_from_xpub(key.depth, key.child, key.chain_code.value.data(), key.public_key.data(),
                         SECP256K1_NAME, &node.value) != 1)
        return HdError::Crypto;
    std::array<char, XPUB_MAXLEN> text{};
    if (hdnode_serialize_public(&node.value, key.parent_fingerprint, version, text.data(),
                                text.size()) <= 0)
        return HdError::Crypto;
    output = text.data();
    return HdError::None;
}
} // namespace airlock::crypto
