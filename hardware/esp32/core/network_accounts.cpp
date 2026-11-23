// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/network_accounts.hpp"
#include "core/ton_code.hpp"
#include <TrezorCrypto/blake2b.h>
#include <TrezorCrypto/cardano.h>
#include <TrezorCrypto/base32.h>
extern "C" {
#include <TrezorCrypto/base58.h>
#include <TrezorCrypto/ed25519.h>
}
#include <TrezorCrypto/secp256k1.h>
#include <TrezorCrypto/sha3.h>
#include <algorithm>

namespace airlock {
namespace {
const NetworkAccountSpec* account_spec(Network network) {
    for (const auto& spec : network_accounts)
        if (spec.network == network && !spec.first_account_path.empty())
            return &spec;
    return nullptr;
}

std::array<std::uint8_t, 20> hash160(const AccountPublicKey& key) {
    std::array<std::uint8_t, 32> digest{};
    hasher_Raw(HASHER_SHA2_RIPEMD, key.bytes.data(), key.size, digest.data());
    std::array<std::uint8_t, 20> result{};
    std::copy_n(digest.begin(), result.size(), result.begin());
    return result;
}

std::string bech32_address(crypto::Bytes payload, std::string_view hrp, bool witness_v0) {
    constexpr std::string_view alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    constexpr std::uint32_t generators[] = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd,
                                            0x2a1462b3};
    std::uint32_t checksum = 1;
    const auto feed = [&](std::uint32_t value) {
        const auto top = checksum >> 25;
        checksum = ((checksum & 0x1ffffffU) << 5) ^ value;
        for (unsigned i = 0; i < 5; ++i)
            if ((top >> i) & 1U)
                checksum ^= generators[i];
    };
    for (auto c : hrp)
        feed(static_cast<unsigned char>(c) >> 5);
    feed(0);
    for (auto c : hrp)
        feed(c & 31);
    std::string address = std::string(hrp) + "1";
    if (witness_v0) {
        address += 'q';
        feed(0);
    }
    std::uint32_t accumulator = 0;
    unsigned bits = 0;
    for (std::size_t i = 0; i < payload.size; ++i) {
        const auto byte = payload.data[i];
        accumulator = (accumulator << 8) | byte;
        bits += 8;
        while (bits >= 5) {
            bits -= 5;
            const auto group = (accumulator >> bits) & 31U;
            address += alphabet[group];
            feed(group);
        }
    }
    if (bits) {
        const auto group = (accumulator << (5 - bits)) & 31U;
        address += alphabet[group];
        feed(group);
    }
    for (unsigned i = 0; i < 6; ++i)
        feed(0);
    checksum ^= 1;
    for (int shift = 25; shift >= 0; shift -= 5)
        address += alphabet[(checksum >> shift) & 31U];
    return address;
}

std::string bitcoin_address(const std::array<std::uint8_t, 20>& program, std::string_view hrp) {
    return bech32_address({program.data(), program.size()}, hrp, true);
}

std::string checked_base58(std::uint8_t prefix, const std::array<std::uint8_t, 20>& id) {
    std::array<std::uint8_t, 21> payload{};
    payload[0] = prefix;
    std::copy(id.begin(), id.end(), payload.begin() + 1);
    char address[45]{};
    if (!base58_encode_check(payload.data(), payload.size(), HASHER_SHA2D, address, sizeof address))
        return {};
    return address;
}

std::string stellar_address(const AccountPublicKey& key) {
    // Stellar StrKey, Ed25519 public account (G...), CRC16-XMODEM little endian.
    std::array<std::uint8_t, 35> payload{};
    payload[0] = 6 << 3;
    std::copy_n(key.bytes.begin(), 32, payload.begin() + 1);
    std::uint16_t crc = 0;
    for (std::size_t i = 0; i < 33; ++i) {
        crc ^= static_cast<unsigned>(payload[i]) << 8;
        for (unsigned bit = 0; bit < 8; ++bit)
            crc = (static_cast<unsigned>(crc) << 1) ^ ((crc & 0x8000U) ? 0x1021U : 0U);
    }
    payload[33] = crc & 0xff;
    payload[34] = crc >> 8;
    char address[57]{};
    return base32_encode(payload.data(), payload.size(), address, sizeof address,
                         BASE32_ALPHABET_RFC4648)
               ? std::string(address)
               : std::string{};
}

std::string evm_checksum(const std::array<std::uint8_t, 20>& id) {
    constexpr std::string_view hex = "0123456789abcdef";
    std::string address = "0x";
    for (const auto byte : id) {
        address += hex[byte >> 4];
        address += hex[byte & 15];
    }
    std::array<std::uint8_t, 32> digest{};
    keccak_256(reinterpret_cast<const std::uint8_t*>(address.data() + 2), 40, digest.data());
    for (std::size_t i = 0; i < 40; ++i) {
        auto& character = address[i + 2];
        if (character >= 'a' && (digest[i / 2] & (i % 2 ? 0x08 : 0x80)))
            character -= 'a' - 'A';
    }
    return address;
}
std::string cash_address(std::string_view prefix, const std::array<std::uint8_t, 20>& hash) {
    constexpr char alphabet[] = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    constexpr std::uint64_t generators[] = {0x98f2bc8e61ULL, 0x79b76d99e2ULL, 0xf33e5fb3c4ULL,
                                            0xae2eabe2a8ULL, 0x1e4f43e470ULL};
    std::uint64_t checksum = 1;
    const auto feed = [&](unsigned value) {
        const auto top = checksum >> 35;
        checksum = ((checksum & 0x7ffffffffULL) << 5) ^ value;
        for (unsigned i = 0; i < 5; ++i)
            if ((top >> i) & 1)
                checksum ^= generators[i];
    };
    for (char c : prefix)
        feed(c & 31);
    feed(0);
    std::string out = std::string(prefix) + ":";
    unsigned bits = 0;
    std::uint32_t acc = 0;
    std::array<std::uint8_t, 21> payload{};
    std::copy(hash.begin(), hash.end(), payload.begin() + 1);
    for (auto byte : payload) {
        acc = (acc << 8) | byte;
        bits += 8;
        while (bits >= 5) {
            bits -= 5;
            auto v = (acc >> bits) & 31;
            out += alphabet[v];
            feed(v);
        }
    }
    if (bits) {
        auto v = (acc << (5 - bits)) & 31;
        out += alphabet[v];
        feed(v);
    }
    for (unsigned i = 0; i < 8; ++i)
        feed(0);
    checksum ^= 1;
    for (int shift = 35; shift >= 0; shift -= 5)
        out += alphabet[(checksum >> shift) & 31];
    return out;
}
std::string ton_address(const AccountPublicKey& key) {
    // Fixed V4R2 initial data (seqno=0, wallet_id=698983191, empty plugins).
    std::array<std::uint8_t, 43> data{};
    data[1] = 81;
    data[6] = 0x29;
    data[7] = 0xa9;
    data[8] = 0xa3;
    data[9] = 0x17;
    std::copy_n(key.bytes.begin(), 32, data.begin() + 10);
    data[42] = 0x40;
    std::array<std::uint8_t, 32> data_hash{}, state_hash{};
    if (!crypto::sha256({data.data(), data.size()}, data_hash))
        return {};
    std::array<std::uint8_t, 71> state{};
    state[0] = 2;
    state[1] = 1;
    state[2] = 0x34;
    state[3] = ton_code_depth >> 8;
    state[4] = ton_code_depth;
    std::copy(ton_code_hash.begin(), ton_code_hash.end(), state.begin() + 7);
    std::copy(data_hash.begin(), data_hash.end(), state.begin() + 39);
    if (!crypto::sha256({state.data(), state.size()}, state_hash))
        return {};
    std::array<std::uint8_t, 36> payload{};
    payload[0] = 0x51; // non-bounceable mainnet, Wallet Core default
    std::copy(state_hash.begin(), state_hash.end(), payload.begin() + 2);
    std::uint16_t crc = 0;
    for (unsigned i = 0; i < 34; ++i) {
        crc ^= static_cast<unsigned>(payload[i]) << 8;
        for (unsigned j = 0; j < 8; ++j)
            crc = (crc << 1) ^ ((crc & 0x8000) ? 0x1021 : 0);
    }
    payload[34] = crc >> 8;
    payload[35] = crc;
    constexpr char b64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    std::string out;
    for (unsigned i = 0; i < 36; i += 3) {
        unsigned v = (payload[i] << 16) | (payload[i + 1] << 8) | payload[i + 2];
        for (int shift = 18; shift >= 0; shift -= 6)
            out += b64[(v >> shift) & 63];
    }
    return out;
}
struct CardanoNode {
    HDNode value{};
    ~CardanoNode() {
        wipe(&value, sizeof(value));
    }
};
crypto::HdError cardano_account(std::string_view mnemonic, std::string_view passphrase,
                                CardanoNode& payment, std::string& output) {
    output.clear();
    if (passphrase.size() > 256)
        return crypto::HdError::PassphraseTooLong;
    crypto::Entropy entropy;
    const auto status = crypto::mnemonic_entropy(mnemonic, entropy);
    if (status != crypto::HdError::None)
        return status;
    crypto::Sensitive<CARDANO_SECRET_LENGTH> secret;
    CardanoNode stake;
    if (!secret_from_entropy_cardano_icarus(
            crypto::bytes(passphrase).data, static_cast<int>(passphrase.size()),
            entropy.data.value.data(), static_cast<int>(entropy.size), secret.value.data(),
            nullptr) ||
        !hdnode_from_secret_cardano(secret.value.data(), &payment.value))
        return crypto::HdError::Crypto;
    constexpr std::uint32_t hardened = 0x80000000U;
    for (const auto index : {1852U, 1815U, 0U})
        if (!hdnode_private_ckd_cardano(&payment.value, index | hardened))
            return crypto::HdError::Crypto;
    stake.value = payment.value;
    if (!hdnode_private_ckd_cardano(&payment.value, 0) ||
        !hdnode_private_ckd_cardano(&payment.value, 0) ||
        !hdnode_private_ckd_cardano(&stake.value, 2) ||
        !hdnode_private_ckd_cardano(&stake.value, 0) ||
        hdnode_fill_public_key(&payment.value) != 0 || hdnode_fill_public_key(&stake.value) != 0)
        return crypto::HdError::Crypto;
    // CIP-19 mainnet base address, with payment and staking key hashes.
    std::array<std::uint8_t, 57> payload{};
    payload[0] = 1;
    if (tc_blake2b(payment.value.public_key + 1, 32, payload.data() + 1, 28) != 0 ||
        tc_blake2b(stake.value.public_key + 1, 32, payload.data() + 29, 28) != 0)
        return crypto::HdError::Crypto;
    output = bech32_address({payload.data(), payload.size()}, "addr", false);
    return crypto::HdError::None;
}
} // namespace

crypto::HdError cardano_receiving_address(std::string_view mnemonic, std::string_view passphrase,
                                          std::string& output) {
    CardanoNode payment;
    return cardano_account(mnemonic, passphrase, payment, output);
}

bool cardano_payment_witness(std::string_view mnemonic, std::string_view passphrase,
                             std::string_view sender, const std::array<std::uint8_t, 32>& digest,
                             std::array<std::uint8_t, 96>& witness) {
    CardanoNode payment;
    std::string actual;
    if (cardano_account(mnemonic, passphrase, payment, actual) != crypto::HdError::None ||
        sender != actual)
        return false;
    std::copy_n(payment.value.public_key + 1, 32, witness.begin());
    ed25519_sign_ext(digest.data(), digest.size(), payment.value.private_key,
                     payment.value.private_key_extension, witness.data() + 32);
    return true;
}

crypto::HdError derive_account_public_key(Network network, const crypto::Seed& seed,
                                          crypto::RandomSource& random, AccountPublicKey& output) {
    output = {};
    const auto* spec = account_spec(network);
    if (!spec)
        return crypto::HdError::UnsupportedCurve;
    crypto::DerivedKey key;
    const auto error = crypto::derive_key({seed.value.data(), seed.value.size()}, spec->curve,
                                          spec->first_account_path, random, key);
    if (error != crypto::HdError::None)
        return error;
    output.bytes = key.public_key;
    output.size = key.public_key_size;
    return crypto::HdError::None;
}

crypto::HdError derive_receiving_address(Network network, const crypto::Seed& seed,
                                         crypto::RandomSource& random, std::string& output) {
    output.clear();
    const auto* spec = account_spec(network);
    if (!spec)
        return crypto::HdError::UnsupportedCurve;
    AccountPublicKey key;
    const auto error = derive_account_public_key(network, seed, random, key);
    if (error != crypto::HdError::None)
        return error;

    return encode_receiving_address(network, key, output);
}
crypto::HdError encode_receiving_address(Network network, const AccountPublicKey& key,
                                         std::string& output) {
    output.clear();
    const auto* spec = account_spec(network);
    if (!spec || key.size != (spec->curve == crypto::Curve::Secp256k1 ? 33U : 32U))
        return crypto::HdError::InvalidPath;
    if (spec->evm_chain_id || network == Network::Tron) {
        std::array<std::uint8_t, 65> full{};
        if (!ecdsa_uncompress_pubkey(&secp256k1, key.bytes.data(), full.data()))
            return crypto::HdError::Crypto;
        std::array<std::uint8_t, 32> digest{};
        keccak_256(full.data() + 1, full.size() - 1, digest.data());
        std::array<std::uint8_t, 20> id{};
        std::copy(digest.end() - id.size(), digest.end(), id.begin());
        output = network == Network::Tron ? checked_base58(0x41, id) : evm_checksum(id);
    } else if (network == Network::Bitcoin || network == Network::Litecoin) {
        output = bitcoin_address(hash160(key), network == Network::Litecoin ? "ltc" : "bc");
    } else if (network == Network::BitcoinCash || network == Network::Ecash) {
        output = cash_address(network == Network::Ecash ? "ecash" : "bitcoincash", hash160(key));
    } else if (network == Network::Dash) {
        output = checked_base58(0x4c, hash160(key));
    } else if (network == Network::Ton) {
        output = ton_address(key);
    } else if (network == Network::Sui) {
        std::array<std::uint8_t, 33> payload{};
        std::copy_n(key.bytes.begin(), 32, payload.begin() + 1);
        std::array<std::uint8_t, 32> hash{};
        if (tc_blake2b(payload.data(), payload.size(), hash.data(), hash.size()) != 0)
            return crypto::HdError::Crypto;
        constexpr char hex[] = "0123456789abcdef";
        output = "0x";
        for (auto b : hash) {
            output += hex[b >> 4];
            output += hex[b & 15];
        }
    } else if (network == Network::Dogecoin) {
        output = checked_base58(0x1e, hash160(key));
    } else if (network == Network::Ripple) {
        output = checked_base58(0, hash160(key));
        constexpr std::string_view xrp_alphabet =
            "rpshnaf39wBUDNEGHJKLM4PQRST7VWXYZ2bcdeCg65jkm8oFqi1tuvAxyz";
        const std::string_view btc_alphabet(b58digits_ordered);
        for (auto& digit : output)
            digit = xrp_alphabet[btc_alphabet.find(digit)];
    } else if (network == Network::Stellar) {
        output = stellar_address(key);
    } else if (network == Network::Solana) {
        char address[45]{};
        std::size_t size = sizeof address;
        if (b58enc(address, &size, key.bytes.data(), key.size))
            output = address;
    }
    return output.empty() ? crypto::HdError::Crypto : crypto::HdError::None;
}
} // namespace airlock
