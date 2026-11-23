// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/network_accounts.hpp"
#include <algorithm>

namespace airlock::crypto {
namespace {
bool hex(Bytes bytes, std::string_view expected) {
    constexpr char digits[] = "0123456789abcdef";
    if (expected.size() != 2 * bytes.size)
        return false;
    for (std::size_t i = 0; i < bytes.size; ++i)
        if (digits[bytes.data[i] >> 4] != expected[2 * i] ||
            digits[bytes.data[i] & 15] != expected[2 * i + 1])
            return false;
    return true;
}
template <std::size_t N>
bool hex(const std::array<std::uint8_t, N>& bytes, std::string_view expected) {
    return hex({bytes.data(), bytes.size()}, expected);
}
} // namespace
bool hd_self_test(RandomSource& random) {
    // Only published fixtures. Never generate, persist or print a device seed.
    constexpr std::string_view phrase = "abandon abandon abandon abandon abandon abandon abandon "
                                        "abandon abandon abandon abandon about";
    Entropy entropy;
    if (mnemonic_entropy(phrase, entropy) != HdError::None || entropy.size != 16 ||
        !std::all_of(entropy.data.value.begin(), entropy.data.value.end(),
                     [](auto value) { return value == 0; }))
        return false;
    Credential mnemonic;
    if (mnemonic_from_entropy({entropy.data.value.data(), entropy.size}, mnemonic) !=
            HdError::None ||
        !mnemonic.equals(phrase))
        return false;
    Seed seed;
    if (wallet_core_seed(phrase, "TREZOR", seed) != HdError::None ||
        !hex(seed.value, "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f"
                         "09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04"))
        return false;
    // BIP32 test vector 1, master and m/0'.
    constexpr std::array<std::uint8_t, 16> bip32_seed{0, 1, 2,  3,  4,  5,  6,  7,
                                                      8, 9, 10, 11, 12, 13, 14, 15};
    DerivedKey key;
    if (derive_key({bip32_seed.data(), bip32_seed.size()}, Curve::Secp256k1, "m", random, key) !=
            HdError::None ||
        !hex(key.private_key.value,
             "e8f32e723decf4051aefac8e2c93c9c5b214313817cdb01a1494b917c8436b35") ||
        !hex(key.chain_code.value,
             "873dff81c02f525623fd1fe5167eac3a55a049de3d314bb42ee227ffed37d508") ||
        !hex(key.public_key, "0339a36013301597daef41fbe593a02cc513d0b55527ec2df1050e2e8ff49c85c2"))
        return false;
    if (derive_key({bip32_seed.data(), bip32_seed.size()}, Curve::Secp256k1, "m/0'", random, key) !=
            HdError::None ||
        !hex(key.private_key.value,
             "edb2e14f9ee77d26dd93b4ecede8d16ed408ce149b6cd80b0715a2d911a0afea") ||
        !hex(key.chain_code.value,
             "47fdacbd0f1097043b78c63c20c34ef4ed9a111d980047ad16282c7ae6236141"))
        return false;
    if (derive_key({bip32_seed.data(), bip32_seed.size()}, Curve::Ed25519, "m/0", random, key) !=
        HdError::InvalidPath)
        return false;
    // Public fixtures from the separate original WalletCoreCommon 4.4.2 oracle.
    struct Vector {
        Curve curve;
        const char* path;
        const char* private_key;
        const char* public_key;
    };
    constexpr Vector vectors[] = {
        {Curve::Secp256k1, "m/84'/0'/0'/0/0",
         "697e2dcb4c29ef4af9c937cd915c3872afc35217ad240794df09dd6fccdfb059",
         "02a0f073d11f80811fb4e6d2b0299695c866a0988c1acf9f82a96ebb925524f328"},
        {Curve::Nist256p1, "m/44'/888'/0'/0/0",
         "bfa500767f72aa8c252e3b46db98ecc6004d751ff79999f9e30a0f200cb688cf",
         "026070adeed60533df3e1c42bcca932f0619c046772d175ed9605a068047377532"},
        {Curve::Ed25519, "m/44'/501'/0'",
         "d3072f7e41ab7d8605246f465706270d39269da22f922c169bc00ec4bc597daa",
         "824bc9660d39a7c64bcd02cdcc4fcce746051db378254d8e6185a8826c3bf2ab"},
        {Curve::Ed25519Blake2b, "m/44'/165'/0'",
         "222549f4201eb25228a4b8a6c73d46fbaeb13742d9c4e21a7daa83fc85d4a964",
         "3b8c735a0298c917dcc01907072e6929931a6119305536aa74f26387f25714eb"}};
    for (const auto& vector : vectors)
        if (derive_key({seed.value.data(), seed.value.size()}, vector.curve, vector.path, random,
                       key) != HdError::None ||
            !hex(key.private_key.value, vector.private_key) ||
            !hex({key.public_key.data(), key.public_key_size}, vector.public_key))
            return false;
    // Initial network account keys for the same BIP39 vector above.
    // Reference: BIP32/SLIP-0010 HMAC-SHA512 and OpenSSL public keys.
    struct AccountVector {
        Network network;
        std::string_view public_key;
    };
    constexpr AccountVector accounts[] = {
        {Network::Bitcoin, "02a0f073d11f80811fb4e6d2b0299695c866a0988c1acf9f82a96ebb925524f328"},
        {Network::Dogecoin, "029c8654f657a5a926900a33b75afc53af2cc6829fb2cc8c5c265b1878771937db"},
        {Network::Solana, "67dd5d619b5b95909578651d3cc3723f19d90cb03ac3c8d64a5ef391b2c2a973"},
        {Network::Stellar, "6e89416b679c6b5b34fc84d2e8ce4ca0b3a69b47fcfd6d3e40e4932884333404"},
        {Network::Ripple, "02be56cc0b66d75d45eec7683b5090ed127315bc33ac4a7091acff472b33a08cbe"},
        {Network::Tron, "02111c4267dff570eaf2d2be12e0bdb9e3a7dbdb8a765ba86bf4879aa2a5595964"},
    };
    AccountPublicKey account_key;
    for (const auto& vector : accounts) {
        if (derive_account_public_key(vector.network, seed, random, account_key) != HdError::None ||
            !hex({account_key.bytes.data(), account_key.size}, vector.public_key))
            return false;
    }
    constexpr auto evm_key = "03986dee3b8afe24cb8ccb2ac23dac3f8c43d22850d14b809b26d6b8aa5a1f4778";
    for (const auto network :
         {Network::Ethereum, Network::Base, Network::HyperEvm, Network::Arbitrum, Network::Bnb,
          Network::Polygon, Network::Robinhood}) {
        if (derive_account_public_key(network, seed, random, account_key) != HdError::None ||
            !hex({account_key.bytes.data(), account_key.size}, evm_key))
            return false;
    }
    if (derive_account_public_key(Network::Monero, seed, random, account_key) !=
            HdError::UnsupportedCurve ||
        account_key.size != 0)
        return false;
    return true;
}
} // namespace airlock::crypto
