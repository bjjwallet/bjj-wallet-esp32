// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/sui_signer.hpp"
#include "core/native_signing_catalog.hpp"
#include "core/network_accounts.hpp"
#include "core/json_memory.hpp"
#include "cJSON.h"
#include <TrezorCrypto/ed25519.h>
#include <TrezorCrypto/blake2b.h>
#include <algorithm>
#include <charconv>
#include <memory>
#include <set>
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
bool hex(std::string_view s, Bytes& b) {
    if (s.substr(0, 2) == "0x")
        s.remove_prefix(2);
    if (s.size() != 64 || s.find_first_not_of("0123456789abcdef") != std::string_view::npos)
        return false;
    for (std::size_t i = 0; i < s.size(); i += 2)
        b.push_back((std::string_view("0123456789abcdef").find(s[i]) << 4) |
                    std::string_view("0123456789abcdef").find(s[i + 1]));
    return true;
}
void le(Bytes& b, std::uint64_t n, unsigned size) {
    for (unsigned i = 0; i < size; ++i)
        b.push_back(n >> (i * 8));
}
void string(Bytes& b, std::string_view s) {
    b.push_back(s.size());
    b.insert(b.end(), s.begin(), s.end());
}
void input(Bytes& b, unsigned n) {
    b.push_back(1);
    le(b, n, 2);
}
struct Ref {
    Bytes id, digest;
    std::uint64_t version = 0, balance = 0;
};
struct Payment {
    LedgerReview r;
    Bytes raw;
};
bool parse(std::string_view payload, Payment& p) {
    if (payload.size() > 16384) {
        return false;
    }
    std::unique_ptr<cJSON, decltype(&cJSON_Delete)> o(parse_json(payload), cJSON_Delete);
    if (!cJSON_IsObject(o.get()) || cJSON_GetArraySize(o.get()) != 12 ||
        field(o.get(), "network") != "sui-mainnet")
        return false;
    auto& r = p.r;
    r.wallet_id = field(o.get(), "wallet_id");
    r.mode = field(o.get(), "mode");
    r.sender = field(o.get(), "sender");
    r.recipient = field(o.get(), "recipient");
    r.symbol = field(o.get(), "asset");
    r.amount = field(o.get(), "amount_atomic");
    r.fee = field(o.get(), "fee_atomic");
    r.coin = "Sui";
    r.fee_symbol = "SUI";
    r.decimals = 9;
    Bytes from, to;
    std::uint64_t amount = 0, fee = 0, price = 0, epoch = 0;
    if (r.wallet_id.size() != 64 ||
        r.wallet_id.find_first_not_of("0123456789abcdef") != std::string::npos ||
        (r.mode != "STANDARD" && r.mode != "HIDDEN") || !hex(r.sender, from) ||
        !hex(r.recipient, to) || from == to || !number(r.amount, amount) || !amount ||
        !number(r.fee, fee) || fee < 1 || fee > 1000000000 ||
        !number(field(o.get(), "gas_price"), price) || !price || price > 10000000 ||
        !number(field(o.get(), "epoch"), epoch) || !epoch)
        return false;
    auto* token = native_signing_token("sui", r.symbol);
    if (!token && r.symbol != "SUI")
        return false;
    if (token)
        r.decimals = token->decimals;
    std::vector<Ref> gas, coins;
    std::set<Bytes> seen;
    const auto refs = [&](const char* name, std::vector<Ref>& out) {
        auto* a = cJSON_GetObjectItemCaseSensitive(o.get(), name);
        if (!cJSON_IsArray(a) || cJSON_GetArraySize(a) > 16)
            return false;
        for (auto* v = a->child; v; v = v->next) {
            Ref ref;
            if (!cJSON_IsObject(v) || cJSON_GetArraySize(v) != 4 || !hex(field(v, "id"), ref.id) ||
                !hex(field(v, "digest"), ref.digest) || !number(field(v, "version"), ref.version) ||
                !ref.version || !number(field(v, "balance"), ref.balance) || !ref.balance ||
                !seen.insert(ref.id).second)
                return false;
            out.push_back(std::move(ref));
        }
        return true;
    };
    if (!refs("gas", gas) || !refs("coins", coins) || gas.empty() ||
        (token ? coins.empty() : !coins.empty()))
        return false;
    const auto total = [](const std::vector<Ref>& refs) {
        std::uint64_t n = 0;
        for (const auto& r : refs) {
            if (r.balance > INT64_MAX - n)
                return std::uint64_t{0};
            n += r.balance;
        }
        return n;
    };
    if (total(gas) < fee || (!token && total(gas) - fee < amount) ||
        (token && total(coins) < amount))
        return false;
    const auto ref = [](Bytes& b, const Ref& r) {
        b.insert(b.end(), r.id.begin(), r.id.end());
        le(b, r.version, 8);
        b.push_back(32);
        b.insert(b.end(), r.digest.begin(), r.digest.end());
    };
    auto& b = p.raw;
    b.insert(b.end(), {0, 0});
    b.push_back(coins.size() + 2);
    for (const auto& c : coins) {
        b.insert(b.end(), {1, 0});
        ref(b, c);
    }
    b.insert(b.end(), {0, 8});
    le(b, amount, 8);
    b.insert(b.end(), {0, 32});
    b.insert(b.end(), to.begin(), to.end());
    bool merge = coins.size() > 1;
    b.push_back(merge ? 3 : 2);
    if (merge) {
        b.push_back(3);
        input(b, 0);
        b.push_back(coins.size() - 1);
        for (unsigned i = 1; i < coins.size(); ++i)
            input(b, i);
    }
    if (token) {
        b.push_back(0);
        Bytes package(32, 0);
        package.back() = 2;
        b.insert(b.end(), package.begin(), package.end());
        string(b, "coin");
        string(b, "split");
        b.insert(b.end(), {1, 7});
        const auto split = token->contract.find("::");
        const auto name = token->contract.find("::", split + 2);
        Bytes type;
        if (split == std::string_view::npos || name == std::string_view::npos ||
            !hex(token->contract.substr(0, split), type))
            return false;
        b.insert(b.end(), type.begin(), type.end());
        string(b, token->contract.substr(split + 2, name - split - 2));
        string(b, token->contract.substr(name + 2));
        b.push_back(0);
        b.push_back(2);
        input(b, 0);
        input(b, coins.size());
    } else {
        b.insert(b.end(), {2, 0, 1});
        input(b, 0);
    }
    b.insert(b.end(), {1, 1});
    b.push_back(token ? 2 : 3);
    le(b, merge ? 1 : 0, 2);
    if (!token)
        le(b, 0, 2);
    input(b, coins.size() + 1);
    b.insert(b.end(), from.begin(), from.end());
    b.push_back(gas.size());
    for (const auto& g : gas)
        ref(b, g);
    b.insert(b.end(), from.begin(), from.end());
    le(b, price, 8);
    le(b, fee, 8);
    b.push_back(1);
    le(b, epoch, 8);
    return true;
}
} // namespace
bool review_sui(std::string_view payload, LedgerReview& r) {
    Payment p;
    if (!parse(payload, p))
        return false;
    r = std::move(p.r);
    return true;
}
bool sign_sui(std::string_view payload, crypto::Bytes seed, crypto::RandomSource& random,
              std::string& encoded) {
    encoded.clear();
    Payment p;
    if (!parse(payload, p))
        return false;
    crypto::DerivedKey key;
    std::string sender;
    if (crypto::derive_key(seed, crypto::Curve::Ed25519, "m/44'/784'/0'/0'/0'", random, key) !=
            crypto::HdError::None ||
        encode_receiving_address(Network::Sui, {key.public_key, key.public_key_size}, sender) !=
            crypto::HdError::None ||
        sender != p.r.sender)
        return false;
    Bytes base{0, 0, 0};
    base.insert(base.end(), p.raw.begin(), p.raw.end());
    std::array<std::uint8_t, 32> digest{};
    if (tc_blake2b(base.data(), base.size(), digest.data(), 32) != 0)
        return false;
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
