// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/network_accounts.hpp"
#include "core/monero_address.hpp"
#include "cJSON.h"
#include <openssl/rand.h>
#include <algorithm>
#include <atomic>
#include <csignal>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <memory>
#include <thread>
#include <vector>
#include <sys/resource.h>
#include <sys/wait.h>
#include <unistd.h>

namespace c = airlock::crypto;
namespace {
unsigned checks = 0;
#define CHECK(value)                                                                               \
    do {                                                                                           \
        ++checks;                                                                                  \
        if (!(value)) {                                                                            \
            std::cerr << "HD wallet check failed: line " << __LINE__ << '\n';                      \
            std::exit(1);                                                                          \
        }                                                                                          \
    } while (false)
class SystemRandom final : public c::RandomSource {
  public:
    std::atomic<unsigned> calls{0};
    bool fill(c::Output output) override {
        ++calls;
        return RAND_bytes(output.data, static_cast<int>(output.size)) == 1;
    }
};
class UnavailableRandom final : public c::RandomSource {
  public:
    bool fill(c::Output) override {
        return false;
    }
};
class PublicTestEntropy final : public c::RandomSource {
  public:
    bool fill(c::Output output) override {
        std::fill_n(output.data, output.size, 0);
        return true;
    }
};
using Json = std::unique_ptr<cJSON, decltype(&cJSON_Delete)>;
Json read(const char* path) {
    std::ifstream input(path);
    const std::string text{std::istreambuf_iterator<char>(input), {}};
    Json result(cJSON_Parse(text.c_str()), cJSON_Delete);
    CHECK(result);
    return result;
}
const char* field(cJSON* object, const char* name) {
    auto* item = cJSON_GetObjectItemCaseSensitive(object, name);
    CHECK(cJSON_IsString(item));
    return item->valuestring;
}
std::string hex(c::Bytes bytes) {
    constexpr char digits[] = "0123456789abcdef";
    std::string result;
    for (std::size_t i = 0; i < bytes.size; ++i) {
        result += digits[bytes.data[i] >> 4];
        result += digits[bytes.data[i] & 15];
    }
    return result;
}
template <std::size_t N> std::string hex(const std::array<std::uint8_t, N>& bytes) {
    return hex({bytes.data(), bytes.size()});
}
std::vector<std::uint8_t> unhex(std::string_view input) {
    std::vector<std::uint8_t> result;
    CHECK(input.size() % 2 == 0);
    constexpr std::string_view digits = "0123456789abcdef";
    for (std::size_t i = 0; i < input.size(); i += 2) {
        auto high = digits.find(input[i]), low = digits.find(input[i + 1]);
        CHECK(high != digits.npos && low != digits.npos);
        result.push_back(static_cast<std::uint8_t>(high * 16 + low));
    }
    return result;
}
void monero_key_images() {
    class ReferenceNonce final : public c::RandomSource {
      public:
        bool fill(c::Output output) override {
            std::fill_n(output.data, output.size, 0);
            output.data[0] = 1; // Fixed only for the public reference vectors.
            return true;
        }
    } random;
    c::Seed seed;
    constexpr auto mnemonic =
        "alcohol woman abuse must during monitor noble actual mixed trade anger aisle";
    CHECK(c::wallet_core_seed(mnemonic, "", seed) == c::HdError::None);
    const auto point = [](std::string_view value) {
        const auto bytes = unhex(value);
        c::MoneroPoint decoded{};
        CHECK(bytes.size() == decoded.size());
        std::copy(bytes.begin(), bytes.end(), decoded.begin());
        return decoded;
    };
    // Independently generated with Monero v0.18.5.4 crypto-ops and OpenSSL Keccak.
    const auto primary =
        c::MoneroOutput{point("4bd35d0f2c16a81a01d8225784453c81f7855f0aa8d90173efb3c3dded34f942"),
                        point("b862409fb5c4c4123df2abf7462b88f041ad36dd6864ce872fd5472be363c5b1"),
                        {},
                        300,
                        0,
                        0};
    const auto subaddress =
        c::MoneroOutput{point("a80c23f34341c1d300757cbb5e70ba77e16d42bd13eeba8538e255fe45d50c65"),
                        primary.transaction_key,
                        point("c0f1225584444ec730446e231390781ffdd2f256e9fcbeb2f40dddc2c2233d7f"),
                        2,
                        1,
                        3};
    std::vector<c::MoneroSignedKeyImage> proofs;
    CHECK(c::sign_monero_key_images(seed, {primary, subaddress}, random, proofs) ==
          c::MoneroKeyImageError::None);
    CHECK(proofs.size() == 2);
    CHECK(hex(proofs[0].key_image) ==
          "5279e7dfa6533ceaf292ddd78a8ab77a799479c06b09c174c87071574f24e372");
    CHECK(hex(proofs[0].signature) ==
          "6d2de20504fa3ed33a302a4dafe60d2820fe4262c0ac150d9f775d8194c5cc0e684c37e2678f7d1f4af9b0c4"
          "806c9f04cc12ada8ef12f62e1ff5af1a2b62600c");
    CHECK(hex(proofs[1].key_image) ==
          "3c814aaf5a71dab8bc7d3886200b2f6c0b8c81b2cd489b00fdf489a36a37cecf");
    CHECK(hex(proofs[1].signature) ==
          "d5bb6a675f899572a49756722fe5170e85503f2e7a757065bfa17fbf1e332e03e2f40e78338c0d938c8507d3"
          "5210d99a097538ccb80e8d3a2a7a812edcb6b000");
    auto tampered = subaddress;
    ++tampered.output_index;
    CHECK(c::sign_monero_key_images(seed, {primary, tampered}, random, proofs) ==
              c::MoneroKeyImageError::InvalidOutputs &&
          proofs.empty());
    CHECK(c::sign_monero_key_images(seed, {primary, primary}, random, proofs) ==
              c::MoneroKeyImageError::InvalidOutputs &&
          proofs.empty());
    auto torsion = primary;
    torsion.transaction_key = {1};
    CHECK(c::sign_monero_key_images(seed, {torsion}, random, proofs) ==
              c::MoneroKeyImageError::InvalidOutputs &&
          proofs.empty());
    CHECK(c::wallet_core_seed(mnemonic, "public test mode", seed) == c::HdError::None);
    CHECK(c::sign_monero_key_images(seed, {primary}, random, proofs) ==
              c::MoneroKeyImageError::InvalidOutputs &&
          proofs.empty());
}
void monero_archive(cJSON* fixture, SystemRandom& random) {
    // This export was created by Monero Wallet RPC v0.18.5.4 after a receive
    // and spend on an isolated stagenet node using the public Trezor mnemonic.
    auto archive = unhex(field(fixture, "outputs_data_hex"));
    auto* expected = cJSON_GetObjectItemCaseSensitive(fixture, "key_images");
    constexpr auto mnemonic =
        "alcohol woman abuse must during monitor noble actual mixed trade anger aisle";
    c::Seed seed;
    CHECK(c::wallet_core_seed(mnemonic, "", seed) == c::HdError::None);
    std::vector<c::MoneroSignedKeyImage> proofs;
    const auto sign = [&] {
        return c::sign_monero_output_archive(seed, {archive.data(), archive.size()}, random,
                                             proofs);
    };
    CHECK(sign() == c::MoneroKeyImageError::None);
    CHECK(cJSON_IsArray(expected) &&
          proofs.size() == static_cast<std::size_t>(cJSON_GetArraySize(expected)));
    for (std::size_t i = 0; i < proofs.size(); ++i) {
        const auto* image = cJSON_GetArrayItem(expected, i);
        CHECK(cJSON_IsString(image) && hex(proofs[i].key_image) == image->valuestring);
    }
    archive[50] ^= 1;
    CHECK(sign() == c::MoneroKeyImageError::InvalidOutputs && proofs.empty());
    archive[50] ^= 1;
    CHECK(c::wallet_core_seed(mnemonic, "wrong mode fixture", seed) == c::HdError::None);
    CHECK(sign() == c::MoneroKeyImageError::InvalidOutputs && proofs.empty());
}
c::Curve curve(std::string_view name) {
    if (name == "secp256k1")
        return c::Curve::Secp256k1;
    if (name == "nist256p1")
        return c::Curve::Nist256p1;
    if (name == "ed25519")
        return c::Curve::Ed25519;
    CHECK(name == "ed25519-blake2b");
    return c::Curve::Ed25519Blake2b;
}
void receiving_addresses(SystemRandom& random) {
    using airlock::Network;
    // Public BIP39 vector, with address strings independently encoded by
    // WalletCoreCommon 4.4.2 from the corresponding public keys.
    constexpr std::string_view mnemonic = "abandon abandon abandon abandon abandon abandon abandon "
                                          "abandon abandon abandon abandon about";
    c::Seed seed;
    CHECK(c::wallet_core_seed(mnemonic, "TREZOR", seed) == c::HdError::None);
    constexpr std::string_view evm = "0x9c32F71D4DB8Fb9e1A58B0a80dF79935e7256FA6";
    struct Vector {
        Network network;
        std::string_view address;
    };
    constexpr Vector vectors[] = {
        {Network::Bitcoin, "bc1qv5rmq0kt9yz3pm36wvzct7p3x6mtgehjul0feu"},
        {Network::Dogecoin, "DMn7J63QSZUR9XNxsUJtvsttZVzV9Am4qM"},
        {Network::Solana, "7zSmbu6gKkb6HB7UDPtHYjwCWuBHU1D4TpNZFm4sndQe"},
        {Network::Stellar, "GBXISQLLM6OGWWZU7SCNF2GOJSQLHJU3I76P23J6IDSJGKEEGM2AJYG5"},
        {Network::Ripple, "rfBUajJsZotvNwZz5yUk7GNqcWehxc2YAs"},
        {Network::Tron, "TAyDUYP5rcf56xFwrg8cU1qQwvnWpkeapM"},
        {Network::Ethereum, evm},
        {Network::Base, evm},
        {Network::HyperEvm, evm},
        {Network::Arbitrum, evm},
        {Network::Bnb, evm},
        {Network::Polygon, evm},
        {Network::Robinhood, evm},
    };
    std::string address;
    for (const auto& vector : vectors) {
        CHECK(airlock::derive_receiving_address(vector.network, seed, random, address) ==
              c::HdError::None);
        CHECK(address == vector.address);
    }
    CHECK(airlock::derive_receiving_address(Network::Monero, seed, random, address) ==
          c::HdError::UnsupportedCurve);
    CHECK(address.empty());

    // Public reference: Trezor test_getaddress.py, MNEMONIC12, account zero.
    constexpr std::string_view monero_mnemonic =
        "alcohol woman abuse must during monitor noble actual mixed trade anger aisle";
    CHECK(c::wallet_core_seed(monero_mnemonic, "", seed) == c::HdError::None);
    CHECK(c::derive_monero_address(seed, c::MoneroNetwork::Mainnet, random, address) ==
          c::HdError::None);
    CHECK(address == "4Ahp23WfMrMFK3wYL2hLWQFGt87ZTeRkufS6JoQZu6MEFDokAQeGWmu9MA3GFq1yVLSJQbKJqVAn9"
                     "F9DLYGpRzRAEXqAXKM");
    CHECK(c::derive_monero_address(seed, c::MoneroNetwork::Stagenet, random, address) ==
          c::HdError::None);
    // The same reference public keys with the official stagenet prefix, using
    // an independently computed Keccak checksum (OpenSSL).
    CHECK(address == "5Aur6tRd1TTFK3wYL2hLWQFGt87ZTeRkufS6JoQZu6MEFDokAQeGWmu9MA3GFq1yVLSJQbKJqVAn9"
                     "F9DLYGpRzRAETHfXkW");
    monero_key_images();
}
void bip39(cJSON* vectors) {
    auto* english = cJSON_GetObjectItemCaseSensitive(vectors, "english");
    CHECK(cJSON_IsArray(english) && cJSON_GetArraySize(english) == 24);
    for (auto* row = english->child; row; row = row->next) {
        const auto* entropy_hex = cJSON_GetArrayItem(row, 0)->valuestring;
        const auto* mnemonic = cJSON_GetArrayItem(row, 1)->valuestring;
        const auto* expected_seed = cJSON_GetArrayItem(row, 2)->valuestring;
        const auto entropy = unhex(entropy_hex);
        airlock::Credential generated;
        CHECK(c::mnemonic_from_entropy({entropy.data(), entropy.size()}, generated) ==
              c::HdError::None);
        CHECK(generated.equals(mnemonic));
        c::Entropy decoded;
        CHECK(c::mnemonic_entropy(mnemonic, decoded) == c::HdError::None);
        CHECK(hex({decoded.data.value.data(), decoded.size}) == entropy_hex);
        c::Seed seed;
        CHECK(c::wallet_core_seed(mnemonic, "TREZOR", seed) == c::HdError::None);
        CHECK(hex(seed.value) == expected_seed);
    }
}
void differential(cJSON* vectors, SystemRandom& random) {
    auto* cases = cJSON_GetObjectItemCaseSensitive(vectors, "cases");
    CHECK(cJSON_IsArray(cases) && cJSON_GetArraySize(cases) == 34);
    unsigned derived = 0;
    for (auto* item = cases->child; item; item = item->next) {
        c::Entropy entropy;
        const auto* mnemonic = field(item, "mnemonic");
        CHECK(c::mnemonic_entropy(mnemonic, entropy) == c::HdError::None);
        CHECK(hex({entropy.data.value.data(), entropy.size}) == field(item, "entropy"));
        c::Seed seed;
        CHECK(c::wallet_core_seed(mnemonic, field(item, "passphrase"), seed) == c::HdError::None);
        CHECK(hex(seed.value) == field(item, "seed"));
        auto* keys = cJSON_GetObjectItemCaseSensitive(item, "keys");
        CHECK(cJSON_IsArray(keys));
        for (auto* row = keys->child; row; row = row->next) {
            c::DerivedKey key;
            const auto kind = curve(field(row, "curve"));
            CHECK(c::derive_key({seed.value.data(), seed.value.size()}, kind, field(row, "path"),
                                random, key) == c::HdError::None);
            CHECK(hex(key.private_key.value) == field(row, "private_key"));
            CHECK(hex(key.chain_code.value) == field(row, "chain_code"));
            CHECK(hex({key.public_key.data(), key.public_key_size}) == field(row, "public_key"));
            if (kind == c::Curve::Secp256k1) {
                std::string xpub;
                CHECK(c::extended_public(key, 0x0488b21eU, xpub) == c::HdError::None);
                CHECK(xpub == field(row, "xpub"));
                CHECK(c::extended_public(key, 0x04b24746U, xpub) == c::HdError::None);
                CHECK(xpub == field(row, "zpub"));
            }
            ++derived;
        }
    }
    CHECK(derived == 578);
}
void invalid_and_random(SystemRandom& random) {
    constexpr std::string_view mnemonic = "abandon abandon abandon abandon abandon abandon abandon "
                                          "abandon abandon abandon abandon about";
    c::Seed seed;
    CHECK(c::wallet_core_seed(mnemonic, "", seed) == c::HdError::None);
    c::DerivedKey key;
    for (const auto* path : {"", "M", "m/", "m//1", "m/-1", "m/+1", "m/01", "m/2147483648", "m/1h",
                             "m/0/", "m/1''", "m/0 /1", "m/0\n"}) {
        key.private_key.value.fill(0xa5);
        CHECK(c::derive_key({seed.value.data(), seed.value.size()}, c::Curve::Secp256k1, path,
                            random, key) == c::HdError::InvalidPath);
        CHECK(std::all_of(key.private_key.value.begin(), key.private_key.value.end(),
                          [](auto b) { return b == 0; }));
        CHECK(key.public_key_size == 0);
    }
    std::string deep = "m";
    for (unsigned i = 0; i < 17; ++i)
        deep += "/0'";
    CHECK(c::derive_key({seed.value.data(), seed.value.size()}, c::Curve::Secp256k1, deep, random,
                        key) == c::HdError::InvalidPath);
    CHECK(c::derive_key({seed.value.data(), seed.value.size()}, c::Curve::Ed25519, "m/0'/1", random,
                        key) == c::HdError::InvalidPath);
    CHECK(c::derive_key({nullptr, 64}, c::Curve::Secp256k1, "m", random, key) ==
          c::HdError::InvalidEntropy);
    CHECK(c::derive_key({seed.value.data(), 15}, c::Curve::Secp256k1, "m", random, key) ==
          c::HdError::InvalidEntropy);
    CHECK(c::derive_key({seed.value.data(), seed.value.size()}, static_cast<c::Curve>(99), "m",
                        random, key) == c::HdError::UnsupportedCurve);
    CHECK(c::wallet_core_seed(mnemonic, std::string(257, 'a'), seed) ==
          c::HdError::PassphraseTooLong);
    CHECK(std::all_of(seed.value.begin(), seed.value.end(), [](auto b) { return b == 0; }));
    CHECK(c::wallet_core_seed(mnemonic, std::string_view("a\0b", 3), seed) ==
          c::HdError::InvalidText);
    CHECK(c::wallet_core_seed("abandon abandon abandon abandon abandon abandon abandon abandon "
                              "abandon abandon abandon abandon",
                              "", seed) == c::HdError::InvalidMnemonic);
    c::Entropy entropy;
    CHECK(c::mnemonic_entropy("abandon  abandon", entropy) == c::HdError::InvalidMnemonic);
    CHECK(entropy.size == 0);
    airlock::Credential generated;
    PublicTestEntropy fixture;
    for (auto words : {12U, 15U, 18U, 21U, 24U}) {
        CHECK(c::generate_mnemonic(words, fixture, generated) == c::HdError::None);
        CHECK(c::mnemonic_entropy(generated.view(), entropy) == c::HdError::None);
        CHECK(entropy.size == words / 3 * 4);
        CHECK(std::all_of(entropy.data.value.begin(), entropy.data.value.end(),
                          [](auto b) { return b == 0; }));
    }
    UnavailableRandom missing;
    CHECK(c::generate_mnemonic(24, missing, generated) == c::HdError::Random);
    CHECK(generated.view().empty());
    CHECK(c::generate_mnemonic(13, fixture, generated) == c::HdError::InvalidEntropy);
    CHECK(c::mnemonic_from_entropy({nullptr, 16}, generated) == c::HdError::InvalidEntropy);
    CHECK(c::mnemonic_from_entropy({seed.value.data(), 17}, generated) ==
          c::HdError::InvalidEntropy);
    CHECK(c::wallet_core_seed(mnemonic, "", seed) == c::HdError::None);
    CHECK(c::derive_key({seed.value.data(), seed.value.size()}, c::Curve::Nist256p1, "m", random,
                        key) == c::HdError::None);
    std::string xpub = "stale";
    CHECK(c::extended_public(key, 0x0488b21eU, xpub) == c::HdError::UnsupportedCurve);
    CHECK(xpub.empty());
    // No deterministic fallback is permitted if EC blinding loses entropy.
    const auto child = fork();
    CHECK(child >= 0);
    if (child == 0) {
        const rlimit limit{0, 0};
        setrlimit(RLIMIT_CORE, &limit);
        c::derive_key({seed.value.data(), seed.value.size()}, c::Curve::Secp256k1, "m", missing,
                      key);
        _exit(99);
    }
    int status = 0;
    CHECK(waitpid(child, &status, 0) == child);
    CHECK(WIFSIGNALED(status) && WTERMSIG(status) == SIGABRT);
}
} // namespace
int main(int argc, char** argv) {
    CHECK(argc == 4);
    SystemRandom random;
    CHECK(c::hd_self_test(random));
    bip39(read(argv[1]).get());
    differential(read(argv[2]).get(), random);
    invalid_and_random(random);
    receiving_addresses(random);
    monero_archive(read(argv[3]).get(), random);
    SystemRandom left, right;
    bool left_ok = false, right_ok = false;
    std::thread a([&] { left_ok = c::hd_self_test(left); });
    std::thread b([&] { right_ok = c::hd_self_test(right); });
    a.join();
    b.join();
    CHECK(left_ok && right_ok && left.calls > 0 && right.calls > 0);
    std::cout << checks
              << " HD checks passed: 24 BIP39 vectors, 34 reference wallets, 578 derived keys, "
                 "XPUB/ZPUB, path rejection, RNG failure and concurrent scope isolation.\n";
}
