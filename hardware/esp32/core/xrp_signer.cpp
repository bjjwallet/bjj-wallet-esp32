// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/xrp_signer.hpp"
#include "core/native_signing_catalog.hpp"
#include "core/network_accounts.hpp"
#include "core/trezor_random_scope.hpp"
#include "core/json_memory.hpp"
#include "cJSON.h"
#include <TrezorCrypto/base58.h>
#include <TrezorCrypto/ecdsa.h>
#include <TrezorCrypto/secp256k1.h>
#include <TrezorCrypto/sha2.h>
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
bool address(std::string_view text, Bytes& b) {
    constexpr std::string_view xrp = "rpshnaf39wBUDNEGHJKLM4PQRST7VWXYZ2bcdeCg65jkm8oFqi1tuvAxyz",
                               btc = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    if (text.size() < 25 || text.size() > 35) {
        return false;
    }
    std::string encoded;
    for (auto c : text) {
        auto i = xrp.find(c);
        if (i == std::string_view::npos)
            return false;
        encoded += btc[i];
    }
    std::array<std::uint8_t, 21> raw{};
    if (base58_decode_check(encoded.c_str(), HASHER_SHA2D, raw.data(), raw.size()) != 21 || raw[0])
        return false;
    b.assign(raw.begin() + 1, raw.end());
    return true;
}
void be(Bytes& b, std::uint64_t n, unsigned size) {
    for (unsigned i = size; i; --i)
        b.push_back(n >> ((i - 1) * 8));
}
struct Payment {
    LedgerReview r;
    Bytes from, to, amount;
    std::uint64_t fee = 0, sequence = 0, last = 0;
};
bool parse(std::string_view payload, Payment& p) {
    if (payload.size() > 4096) {
        return false;
    }
    std::unique_ptr<cJSON, decltype(&cJSON_Delete)> o(parse_json(payload), cJSON_Delete);
    if (!cJSON_IsObject(o.get()) || cJSON_GetArraySize(o.get()) != 10 ||
        field(o.get(), "network") != "ripple-mainnet")
        return false;
    auto& r = p.r;
    r.wallet_id = field(o.get(), "wallet_id");
    r.mode = field(o.get(), "mode");
    r.sender = field(o.get(), "sender");
    r.recipient = field(o.get(), "recipient");
    r.symbol = field(o.get(), "asset");
    r.amount = field(o.get(), "amount_atomic");
    r.fee = field(o.get(), "fee_atomic");
    r.coin = "XRP Ledger";
    r.fee_symbol = "XRP";
    r.decimals = 6;
    std::uint64_t amount = 0;
    if (r.wallet_id.size() != 64 ||
        r.wallet_id.find_first_not_of("0123456789abcdef") != std::string::npos ||
        (r.mode != "STANDARD" && r.mode != "HIDDEN") || !address(r.sender, p.from) ||
        !address(r.recipient, p.to) || p.from == p.to || !number(r.amount, amount) || !amount ||
        !number(r.fee, p.fee) || p.fee < 1 || p.fee > 1000000 ||
        !number(field(o.get(), "sequence"), p.sequence) || p.sequence < 1 ||
        p.sequence > UINT32_MAX || !number(field(o.get(), "last_ledger"), p.last) || !p.last ||
        p.last > UINT32_MAX)
        return false;
    auto* token = native_signing_token("ripple", r.symbol);
    if (!token && r.symbol != "XRP")
        return false;
    if (token) {
        r.decimals = token->decimals;
        int exponent = -static_cast<int>(token->decimals);
        while (amount < 1000000000000000ULL) {
            amount *= 10;
            --exponent;
        }
        while (amount > 9999999999999999ULL) {
            if (amount % 10)
                return false;
            amount /= 10;
            ++exponent;
        }
        if (exponent < -96 || exponent > 80)
            return false;
        be(p.amount,
           0xc000000000000000ULL | (static_cast<std::uint64_t>(exponent + 97) << 54) | amount, 8);
        constexpr std::string_view digits = "0123456789ABCDEF";
        auto currency = token->contract.substr(0, 40);
        if (currency.size() != 40 || currency.find_first_not_of(digits) != std::string_view::npos)
            return false;
        for (std::size_t i = 0; i < 40; i += 2)
            p.amount.push_back((digits.find(currency[i]) << 4) | digits.find(currency[i + 1]));
        Bytes issuer;
        if (!address(token->contract.substr(41), issuer))
            return false;
        p.amount.insert(p.amount.end(), issuer.begin(), issuer.end());
    } else {
        if (amount > 100000000000000000ULL)
            return false;
        be(p.amount, 0x4000000000000000ULL | amount, 8);
    }
    return true;
}
Bytes body(const Payment& p, const crypto::DerivedKey& key) {
    Bytes b{0x12, 0, 0, 0x22};
    be(b, 0x80000000, 4);
    b.push_back(0x24);
    be(b, p.sequence, 4);
    b.insert(b.end(), {0x20, 0x1b});
    be(b, p.last, 4);
    b.push_back(0x61);
    b.insert(b.end(), p.amount.begin(), p.amount.end());
    b.push_back(0x68);
    be(b, 0x4000000000000000ULL | p.fee, 8);
    b.insert(b.end(), {0x73, 33});
    b.insert(b.end(), key.public_key.begin(), key.public_key.begin() + 33);
    b.insert(b.end(), {0x81, 20});
    b.insert(b.end(), p.from.begin(), p.from.end());
    b.insert(b.end(), {0x83, 20});
    b.insert(b.end(), p.to.begin(), p.to.end());
    return b;
}
} // namespace
bool review_xrp(std::string_view payload, LedgerReview& r) {
    Payment p;
    if (!parse(payload, p))
        return false;
    r = std::move(p.r);
    return true;
}
bool sign_xrp(std::string_view payload, crypto::Bytes seed, crypto::RandomSource& random,
              std::string& encoded) {
    encoded.clear();
    Payment p;
    if (!parse(payload, p))
        return false;
    crypto::TrezorRandomScope binding(random);
    crypto::DerivedKey key;
    std::string sender;
    if (crypto::derive_key(seed, crypto::Curve::Secp256k1, "m/44'/144'/0'/0/0", random, key) !=
            crypto::HdError::None ||
        encode_receiving_address(Network::Ripple, {key.public_key, key.public_key_size}, sender) !=
            crypto::HdError::None ||
        sender != p.r.sender)
        return false;
    Bytes base{0x53, 0x54, 0x58, 0};
    auto raw = body(p, key);
    base.insert(base.end(), raw.begin(), raw.end());
    std::array<std::uint8_t, 64> digest{};
    sha512_Raw(base.data(), base.size(), digest.data());
    crypto::Sensitive<64> signature;
    std::uint8_t recovery = 0;
    if (ecdsa_sign_digest(&secp256k1, key.private_key.value.data(), digest.data(),
                          signature.value.data(), &recovery,
                          [](std::uint8_t id, std::uint8_t*) -> int { return id < 2; }) != 0)
        return false;
    const auto put = [&](std::uint8_t b) {
        encoded += "0123456789abcdef"[b >> 4];
        encoded += "0123456789abcdef"[b & 15];
    };
    for (auto b : key.public_key) {
        put(b);
    }
    for (auto b : signature.value) {
        put(b);
    }
    put(recovery);
    return true;
}
} // namespace airlock
