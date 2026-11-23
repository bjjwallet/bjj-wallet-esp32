// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/tron_signer.hpp"
#include "core/trezor_random_scope.hpp"
#include "core/native_signing_catalog.hpp"
#include "core/network_accounts.hpp"
#include "core/json_memory.hpp"
#include "cJSON.h"
#include <TrezorCrypto/base58.h>
#include <TrezorCrypto/ecdsa.h>
#include <TrezorCrypto/secp256k1.h>
#include <algorithm>
#include <charconv>
#include <memory>
#include <vector>
namespace airlock {
namespace {
using Bytes = std::vector<std::uint8_t>;
std::string field(const cJSON* o, const char* k) {
    auto* v = cJSON_GetObjectItemCaseSensitive(o, k);
    return cJSON_IsString(v) ? v->valuestring : "";
}
bool number(std::string_view s, std::uint64_t& n) {
    if (s.empty() || (s.size() > 1 && s[0] == '0'))
        return false;
    auto r = std::from_chars(s.data(), s.data() + s.size(), n);
    return r.ec == std::errc{} && r.ptr == s.data() + s.size() && n <= INT64_MAX;
}
bool address(std::string_view s, Bytes& b) {
    if (s.size() != 34)
        return false;
    b.resize(21);
    return base58_decode_check(std::string(s).c_str(), HASHER_SHA2D, b.data(), b.size()) == 21 &&
           b[0] == 0x41;
}
bool hex(std::string_view s, Bytes& b, std::size_t size) {
    if (s.size() != size * 2 || s.find_first_not_of("0123456789abcdef") != std::string_view::npos)
        return false;
    for (std::size_t i = 0; i < s.size(); i += 2)
        b.push_back((std::string_view("0123456789abcdef").find(s[i]) << 4) |
                    std::string_view("0123456789abcdef").find(s[i + 1]));
    return true;
}
void varint(Bytes& b, std::uint64_t n) {
    while (n >= 128) {
        b.push_back((n & 127) | 128);
        n >>= 7;
    }
    b.push_back(n);
}
void integer(Bytes& b, unsigned field, std::uint64_t n) {
    varint(b, field << 3);
    varint(b, n);
}
void bytes(Bytes& b, unsigned field, const Bytes& v) {
    varint(b, (field << 3) | 2);
    varint(b, v.size());
    b.insert(b.end(), v.begin(), v.end());
}
struct Payment {
    LedgerReview r;
    Bytes raw;
};
bool parse(std::string_view payload, Payment& p) {
    if (payload.size() > 4096)
        return false;
    std::unique_ptr<cJSON, decltype(&cJSON_Delete)> o(parse_json(payload), cJSON_Delete);
    if (!cJSON_IsObject(o.get()) || cJSON_GetArraySize(o.get()) != 12 ||
        field(o.get(), "network") != "tron-mainnet")
        return false;
    auto& r = p.r;
    r.wallet_id = field(o.get(), "wallet_id");
    r.mode = field(o.get(), "mode");
    r.sender = field(o.get(), "sender");
    r.recipient = field(o.get(), "recipient");
    r.symbol = field(o.get(), "asset");
    r.amount = field(o.get(), "amount_atomic");
    r.fee = field(o.get(), "fee_atomic");
    r.coin = "TRON";
    r.fee_symbol = "TRX";
    r.decimals = 6;
    Bytes from, to, ref, hash;
    std::uint64_t amount = 0, fee = 0, time = 0, expires = 0;
    if (r.wallet_id.size() != 64 ||
        r.wallet_id.find_first_not_of("0123456789abcdef") != std::string::npos ||
        (r.mode != "STANDARD" && r.mode != "HIDDEN") || !address(r.sender, from) ||
        !address(r.recipient, to) || from == to || !number(r.amount, amount) || !amount ||
        !number(r.fee, fee) || fee < 1 || fee > 1000000000 ||
        !number(field(o.get(), "timestamp"), time) || !number(field(o.get(), "expires"), expires) ||
        expires <= time || expires - time > 3600000 ||
        !hex(field(o.get(), "ref_block_bytes"), ref, 2) ||
        !hex(field(o.get(), "ref_block_hash"), hash, 8))
        return false;
    auto* token = native_signing_token("tron", r.symbol);
    if (r.symbol != "TRX" && !token)
        return false;
    Bytes value;
    bytes(value, 1, from);
    const std::string url = token ? "type.googleapis.com/protocol.TriggerSmartContract"
                                  : "type.googleapis.com/protocol.TransferContract";
    if (token) {
        Bytes contract;
        if (!address(token->contract, contract))
            return false;
        bytes(value, 2, contract);
        Bytes abi{0xa9, 0x05, 0x9c, 0xbb};
        abi.insert(abi.end(), 12, 0);
        abi.insert(abi.end(), to.begin() + 1, to.end());
        abi.insert(abi.end(), 24, 0);
        for (unsigned i = 8; i; --i)
            abi.push_back(amount >> ((i - 1) * 8));
        bytes(value, 4, abi);
    } else {
        bytes(value, 2, to);
        integer(value, 3, amount);
    }
    Bytes any;
    bytes(any, 1, Bytes(url.begin(), url.end()));
    bytes(any, 2, value);
    Bytes contract;
    integer(contract, 1, token ? 31 : 1);
    bytes(contract, 2, any);
    bytes(p.raw, 1, ref);
    bytes(p.raw, 4, hash);
    integer(p.raw, 8, expires);
    bytes(p.raw, 11, contract);
    integer(p.raw, 14, time);
    if (token)
        integer(p.raw, 18, fee);
    return true;
}
} // namespace
bool review_tron(std::string_view payload, LedgerReview& r) {
    Payment p;
    if (!parse(payload, p))
        return false;
    r = std::move(p.r);
    return true;
}
bool sign_tron(std::string_view payload, crypto::Bytes seed, crypto::RandomSource& random,
               std::string& encoded) {
    crypto::TrezorRandomScope binding(random);
    encoded.clear();
    Payment p;
    if (!parse(payload, p))
        return false;
    crypto::DerivedKey key;
    std::string sender;
    if (crypto::derive_key(seed, crypto::Curve::Secp256k1, "m/44'/195'/0'/0/0", random, key) !=
            crypto::HdError::None ||
        encode_receiving_address(Network::Tron, {key.public_key, key.public_key_size}, sender) !=
            crypto::HdError::None ||
        sender != p.r.sender)
        return false;
    std::array<std::uint8_t, 32> digest{};
    if (!crypto::sha256({p.raw.data(), p.raw.size()}, digest))
        return false;
    crypto::Sensitive<64> signature;
    std::uint8_t recovery = 0;
    if (ecdsa_sign_digest(&secp256k1, key.private_key.value.data(), digest.data(),
                          signature.value.data(), &recovery,
                          [](std::uint8_t id, std::uint8_t*) -> int { return id < 2; }) != 0)
        return false;
    for (auto b : signature.value) {
        encoded += "0123456789abcdef"[b >> 4];
        encoded += "0123456789abcdef"[b & 15];
    }
    encoded += "0123456789abcdef"[recovery >> 4];
    encoded += "0123456789abcdef"[recovery & 15];
    return true;
}
} // namespace airlock
