// SPDX-License-Identifier: GPL-3.0-or-later
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
using Hash = std::array<std::uint8_t, 32>;
struct Cell {
    Bytes data;
    unsigned bits = 0;
    std::vector<Cell> refs;
    bool opaque = false;
    Hash known{};
    unsigned known_depth = 0;
    void uint(std::uint64_t n, unsigned count) {
        for (unsigned i = count; i; --i) {
            if (bits % 8 == 0)
                data.push_back(0);
            data.back() |= ((n >> ((i - 1) % 64)) & 1) << (7 - bits % 8);
            ++bits;
        }
    }
    void bytes(const Bytes& bytes) {
        for (auto b : bytes)
            uint(b, 8);
    }
    void address(const Bytes& hash) {
        uint(4, 3);
        uint(0, 8);
        bytes(hash);
    }
    void coins(std::uint64_t n) {
        unsigned size = 0;
        for (auto v = n; v; v >>= 8)
            ++size;
        uint(size, 4);
        uint(n, size * 8);
    }
    unsigned depth() const {
        unsigned n = 0;
        for (const auto& r : refs)
            n = std::max(n, r.depth() + 1);
        return opaque ? known_depth : n;
    }
    Hash hash() const {
        if (opaque) {
            return known;
        }
        Bytes repr{static_cast<std::uint8_t>(refs.size()),
                   static_cast<std::uint8_t>(bits / 8 + (bits + 7) / 8)};
        Bytes padded = data;
        if (bits % 8)
            padded.back() |= 1 << (7 - bits % 8);
        repr.insert(repr.end(), padded.begin(), padded.end());
        for (const auto& r : refs) {
            auto d = r.depth();
            repr.push_back(d >> 8);
            repr.push_back(d);
        }
        for (const auto& r : refs) {
            auto h = r.hash();
            repr.insert(repr.end(), h.begin(), h.end());
        }
        Hash h{};
        crypto::sha256({repr.data(), repr.size()}, h);
        return h;
    }
};
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
bool address(std::string_view s, Bytes& hash) {
    if (s.size() == 66 && s.substr(0, 2) == "0:") {
        s.remove_prefix(2);
        if (s.find_first_not_of("0123456789abcdef") != std::string_view::npos)
            return false;
        for (unsigned i = 0; i < 64; i += 2)
            hash.push_back((std::string_view("0123456789abcdef").find(s[i]) << 4) |
                           std::string_view("0123456789abcdef").find(s[i + 1]));
        return true;
    }
    if (s.size() != 48) {
        return false;
    }
    constexpr std::string_view alphabet =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    Bytes raw;
    for (unsigned i = 0; i < 48; i += 4) {
        unsigned n = 0;
        for (unsigned j = 0; j < 4; ++j) {
            auto k = alphabet.find(s[i + j]);
            if (k == std::string_view::npos)
                return false;
            n = (n << 6) | k;
        }
        raw.push_back(n >> 16);
        raw.push_back(n >> 8);
        raw.push_back(n);
    }
    if ((raw[0] != 0x11 && raw[0] != 0x51) || raw[1]) {
        return false;
    }
    std::uint16_t crc = 0;
    for (unsigned i = 0; i < 34; ++i) {
        crc ^= static_cast<unsigned>(raw[i]) << 8;
        for (unsigned j = 0; j < 8; ++j)
            crc = (crc << 1) ^ ((crc & 0x8000) ? 0x1021 : 0);
    }
    if (raw[34] != (crc >> 8) || raw[35] != (crc & 255))
        return false;
    hash.assign(raw.begin() + 2, raw.begin() + 34);
    return true;
}
Hash jetton(const Bytes& owner, const Bytes& master) {
    // USDT's deployed library-reference cell. Pinned public mainnet getter,
    // verified against ton-blockchain/stablecoin-contract storage layout.
    const Hash code_hash{0x89, 0x46, 0x8f, 0x02, 0xc7, 0x8e, 0x57, 0x08, 0x02, 0xe3, 0x99,
                         0x79, 0xc8, 0x51, 0x6f, 0xc3, 0x8d, 0xf0, 0x7e, 0xa7, 0x6a, 0x48,
                         0x35, 0x7e, 0x05, 0x36, 0xf2, 0xba, 0x7b, 0x3e, 0xe3, 0x7b};
    Cell code;
    code.opaque = true;
    code.known = code_hash;
    Cell data;
    data.uint(0, 4);
    data.coins(0);
    data.address(owner);
    data.address(master);
    Cell state;
    state.uint(6, 5);
    state.refs = {code, data};
    return state.hash();
}
struct Payment {
    LedgerReview r;
    Cell signing;
};
bool parse(std::string_view payload, Payment& p) {
    if (payload.size() > 4096) {
        return false;
    }
    std::unique_ptr<cJSON, decltype(&cJSON_Delete)> o(parse_json(payload), cJSON_Delete);
    if (!cJSON_IsObject(o.get()) || cJSON_GetArraySize(o.get()) != 11 ||
        field(o.get(), "network") != "ton-mainnet")
        return false;
    auto& r = p.r;
    r.wallet_id = field(o.get(), "wallet_id");
    r.mode = field(o.get(), "mode");
    r.sender = field(o.get(), "sender");
    r.recipient = field(o.get(), "recipient");
    r.symbol = field(o.get(), "asset");
    r.amount = field(o.get(), "amount_atomic");
    r.fee = field(o.get(), "fee_atomic");
    r.coin = "TON";
    r.fee_symbol = "TON";
    r.decimals = 9;
    Bytes from, to, wallet;
    std::uint64_t amount = 0, fee = 0, seq = 0, expires = 0;
    if (r.wallet_id.size() != 64 ||
        r.wallet_id.find_first_not_of("0123456789abcdef") != std::string::npos ||
        (r.mode != "STANDARD" && r.mode != "HIDDEN") || !address(r.sender, from) ||
        !address(r.recipient, to) || from == to || !number(r.amount, amount) || !amount ||
        !number(r.fee, fee) || !fee || fee > 1000000000 ||
        !number(field(o.get(), "sequence"), seq) || seq > UINT32_MAX ||
        !number(field(o.get(), "expires"), expires) || !expires || expires > UINT32_MAX)
        return false;
    auto* token = native_signing_token("ton", r.symbol);
    if (!token && r.symbol != "TON")
        return false;
    Cell internal;
    if (token) {
        r.decimals = token->decimals;
        Bytes master;
        if (fee < 50000000 || !address(token->contract, master) ||
            !address(field(o.get(), "jetton_wallet"), wallet))
            return false;
        auto expected = jetton(from, master);
        if (!std::equal(wallet.begin(), wallet.end(), expected.begin()))
            return false;
        Cell transfer;
        transfer.uint(0x0f8a7ea5, 32);
        transfer.uint(seq, 64);
        transfer.coins(amount);
        transfer.address(to);
        transfer.address(from);
        transfer.uint(0, 1);
        transfer.coins(1);
        transfer.uint(0, 1);
        internal.uint(6, 4);
        internal.uint(0, 2);
        internal.address(wallet);
        internal.coins(50000000);
        internal.uint(0, 1);
        internal.coins(0);
        internal.coins(0);
        internal.uint(0, 64);
        internal.uint(0, 32);
        internal.uint(0, 1);
        internal.uint(1, 1);
        internal.refs.push_back(transfer);
    } else {
        if (!field(o.get(), "jetton_wallet").empty())
            return false;
        internal.uint(4, 4);
        internal.uint(0, 2);
        internal.address(to);
        internal.coins(amount);
        internal.uint(0, 1);
        internal.coins(0);
        internal.coins(0);
        internal.uint(0, 64);
        internal.uint(0, 32);
        internal.uint(0, 1);
        internal.uint(0, 1);
    }
    p.signing.uint(698983191, 32);
    p.signing.uint(expires, 32);
    p.signing.uint(seq, 32);
    p.signing.uint(0, 8);
    p.signing.uint(3, 8);
    p.signing.refs.push_back(internal);
    return true;
}
} // namespace
bool review_ton(std::string_view payload, LedgerReview& r) {
    Payment p;
    if (!parse(payload, p))
        return false;
    r = std::move(p.r);
    return true;
}
bool sign_ton(std::string_view payload, crypto::Bytes seed, crypto::RandomSource& random,
              std::string& encoded) {
    encoded.clear();
    Payment p;
    if (!parse(payload, p))
        return false;
    crypto::DerivedKey key;
    std::string sender;
    Bytes requested, derived;
    if (crypto::derive_key(seed, crypto::Curve::Ed25519, "m/44'/607'/0'", random, key) !=
            crypto::HdError::None ||
        encode_receiving_address(Network::Ton, {key.public_key, key.public_key_size}, sender) !=
            crypto::HdError::None ||
        !address(sender, derived) || !address(p.r.sender, requested) || derived != requested)
        return false;
    auto digest = p.signing.hash();
    std::array<std::uint8_t, 64> signature{};
    ed25519_sign(digest.data(), 32, key.private_key.value.data(), signature.data());
    const auto put = [&](std::uint8_t b) {
        encoded += "0123456789abcdef"[b >> 4];
        encoded += "0123456789abcdef"[b & 15];
    };
    for (unsigned i = 0; i < 32; ++i)
        put(key.public_key[i]);
    for (auto b : signature)
        put(b);
    return true;
}
} // namespace airlock
