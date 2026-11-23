// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/ledger_signer.hpp"
#include "core/tron_signer.hpp"
#include "core/xrp_signer.hpp"
#include "core/sui_signer.hpp"
#include "core/ton_signer.hpp"
#include "core/native_signing_catalog.hpp"
#include "core/network_accounts.hpp"
#include "core/json_memory.hpp"
#include "cJSON.h"
#include <TrezorCrypto/ed25519.h>
#include <algorithm>
#include <charconv>
#include <memory>
#include <vector>

namespace airlock {
namespace {
using Bytes = std::vector<std::uint8_t>;
struct Payment {
    LedgerReview r;
    Bytes from, to, body;
    std::uint64_t amount = 0, fee = 0, sequence = 0, expires = 0;
};
std::string field(const cJSON* o, const char* key) {
    auto* v = cJSON_GetObjectItemCaseSensitive(o, key);
    return cJSON_IsString(v) ? v->valuestring : "";
}
bool number(std::string_view s, std::uint64_t& n) {
    if (s.empty() || (s.size() > 1 && s[0] == '0'))
        return false;
    auto r = std::from_chars(s.data(), s.data() + s.size(), n);
    return r.ec == std::errc{} && r.ptr == s.data() + s.size() && n <= INT64_MAX;
}
bool strkey(std::string_view s, Bytes& key) {
    if (s.size() != 56)
        return false;
    constexpr std::string_view alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    Bytes raw;
    unsigned bits = 0, acc = 0;
    for (auto c : s) {
        auto v = alphabet.find(c);
        if (v == std::string_view::npos)
            return false;
        acc = (acc << 5) | v;
        bits += 5;
        if (bits >= 8) {
            bits -= 8;
            raw.push_back(acc >> bits);
        }
    }
    if (raw.size() != 35 || raw[0] != 48)
        return false;
    std::uint16_t crc = 0;
    for (unsigned i = 0; i < 33; ++i) {
        crc ^= static_cast<unsigned>(raw[i]) << 8;
        for (unsigned j = 0; j < 8; ++j)
            crc = (crc << 1) ^ ((crc & 0x8000) ? 0x1021 : 0);
    }
    if (raw[33] != (crc & 255) || raw[34] != (crc >> 8))
        return false;
    key.assign(raw.begin() + 1, raw.begin() + 33);
    return true;
}
void be(Bytes& out, std::uint64_t v, unsigned n) {
    for (unsigned i = n; i; --i)
        out.push_back(v >> ((i - 1) * 8));
}
bool parse(std::string_view payload, Payment& p) {
    if (payload.size() > 4096)
        return false;
    std::unique_ptr<cJSON, decltype(&cJSON_Delete)> obj(parse_json(payload), cJSON_Delete);
    if (!cJSON_IsObject(obj.get()) || cJSON_GetArraySize(obj.get()) != 10 ||
        field(obj.get(), "network") != "stellar-mainnet")
        return false;
    auto& r = p.r;
    r.wallet_id = field(obj.get(), "wallet_id");
    r.mode = field(obj.get(), "mode");
    r.sender = field(obj.get(), "sender");
    r.recipient = field(obj.get(), "recipient");
    r.symbol = field(obj.get(), "asset");
    r.amount = field(obj.get(), "amount_atomic");
    r.fee = field(obj.get(), "fee_atomic");
    r.coin = "Stellar";
    r.fee_symbol = "XLM";
    r.decimals = 7;
    if (r.wallet_id.size() != 64 ||
        r.wallet_id.find_first_not_of("0123456789abcdef") != std::string::npos ||
        (r.mode != "STANDARD" && r.mode != "HIDDEN") || !strkey(r.sender, p.from) ||
        !strkey(r.recipient, p.to) || p.from == p.to || !number(r.amount, p.amount) || !p.amount ||
        !number(r.fee, p.fee) || p.fee < 100 || p.fee > 1000000 ||
        !number(field(obj.get(), "sequence"), p.sequence) || !p.sequence ||
        !number(field(obj.get(), "expires"), p.expires) || !p.expires)
        return false;
    const auto* token = native_signing_token("stellar", r.symbol);
    if (r.symbol != "XLM" && !token)
        return false;
    Bytes asset;
    if (token) {
        auto split = token->contract.find(':');
        auto code = token->contract.substr(0, split);
        Bytes issuer;
        if (split == std::string_view::npos || code.empty() || code.size() > 12 ||
            !strkey(token->contract.substr(split + 1), issuer))
            return false;
        unsigned size = code.size() <= 4 ? 4 : 12;
        be(asset, size == 4 ? 1 : 2, 4);
        asset.insert(asset.end(), code.begin(), code.end());
        asset.insert(asset.end(), size - code.size(), 0);
        be(asset, 0, 4);
        asset.insert(asset.end(), issuer.begin(), issuer.end());
    } else
        be(asset, 0, 4);
    auto& b = p.body;
    be(b, 0, 4);
    b.insert(b.end(), p.from.begin(), p.from.end());
    be(b, p.fee, 4);
    be(b, p.sequence, 8);
    be(b, 1, 4);
    be(b, 0, 8);
    be(b, p.expires, 8); // TimeBounds
    be(b, 0, 4);
    be(b, 1, 4);
    be(b, 0, 4);
    be(b, 1, 4); // memo none, one Payment, no alternate source
    be(b, 0, 4);
    b.insert(b.end(), p.to.begin(), p.to.end());
    b.insert(b.end(), asset.begin(), asset.end());
    be(b, p.amount, 8);
    be(b, 0, 4);
    return true;
}
} // namespace
bool review_ledger(std::string_view payload, LedgerReview& review) {
    Payment p;
    if (!parse(payload, p))
        return review_tron(payload, review) || review_xrp(payload, review) ||
               review_sui(payload, review) || review_ton(payload, review);
    review = std::move(p.r);
    return true;
}
bool sign_ledger(std::string_view payload, crypto::Bytes seed, crypto::RandomSource& random,
                 std::string& encoded) {
    encoded.clear();
    Payment p;
    if (!parse(payload, p))
        return sign_tron(payload, seed, random, encoded) ||
               sign_xrp(payload, seed, random, encoded) ||
               sign_sui(payload, seed, random, encoded) || sign_ton(payload, seed, random, encoded);
    crypto::DerivedKey key;
    if (crypto::derive_key(seed, crypto::Curve::Ed25519, "m/44'/148'/0'", random, key) !=
            crypto::HdError::None ||
        key.public_key_size != 32 ||
        !std::equal(p.from.begin(), p.from.end(), key.public_key.begin()))
        return false;
    constexpr std::string_view passphrase = "Public Global Stellar Network ; September 2015";
    std::array<std::uint8_t, 32> network{}, digest{};
    if (!crypto::sha256(
            {reinterpret_cast<const std::uint8_t*>(passphrase.data()), passphrase.size()}, network))
        return false;
    Bytes base(network.begin(), network.end());
    be(base, 2, 4);
    base.insert(base.end(), p.body.begin(), p.body.end());
    if (!crypto::sha256({base.data(), base.size()}, digest))
        return false;
    std::array<std::uint8_t, 64> signature{};
    ed25519_sign(digest.data(), digest.size(), key.private_key.value.data(), signature.data());
    for (auto b : signature) {
        encoded += "0123456789abcdef"[b >> 4];
        encoded += "0123456789abcdef"[b & 15];
    }
    return true;
}
} // namespace airlock
