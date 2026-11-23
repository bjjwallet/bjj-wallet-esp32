// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/cardano_signer.hpp"
#include "core/json_memory.hpp"
#include "core/network_accounts.hpp"
#include "core/native_signing_catalog.hpp"
#include "cJSON.h"
#include <TrezorCrypto/blake2b.h>
#include <algorithm>
#include <charconv>
#include <memory>
#include <set>
#include <vector>

namespace airlock {
namespace {
using Bytes = std::vector<std::uint8_t>;
constexpr std::uint64_t max_supply = 45000000000000000ULL;
constexpr std::uint64_t max_fee = 2000000;
struct Input {
    std::string hash;
    std::uint64_t index;
    bool operator<(const Input& other) const {
        return hash == other.hash ? index < other.index : hash < other.hash;
    }
};
struct Payment {
    CardanoReview review;
    Bytes sender, recipient;
    std::set<Input> inputs;
    std::uint64_t amount = 0, fee = 0, change = 0, ttl = 0;
    const NativeSigningToken* token = nullptr;
    std::uint64_t tokens = 0, token_total = 0;
};

std::string field(const cJSON* object, const char* key) {
    const auto* value = cJSON_GetObjectItemCaseSensitive(object, key);
    return cJSON_IsString(value) ? value->valuestring : "";
}
bool decimal(std::string_view text, std::uint64_t& value) {
    if (text.empty() || (text.size() > 1 && text.front() == '0'))
        return false;
    const auto* last = text.data() + text.size();
    const auto parsed = std::from_chars(text.data(), last, value);
    return parsed.ec == std::errc{} && parsed.ptr == last;
}
bool hash_hex(std::string_view value) {
    return value.size() == 64 &&
           value.find_first_not_of("0123456789abcdef") == std::string_view::npos;
}
bool address(std::string_view text, Bytes& bytes, bool sender) {
    if (text.substr(0, 5) != "addr1" || (text.size() != 103 && text.size() != 58))
        return false;
    constexpr std::string_view alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    constexpr std::uint32_t generators[] = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd,
                                            0x2a1462b3};
    std::uint32_t checksum = 1, acc = 0;
    const auto feed = [&](unsigned value) {
        const auto top = checksum >> 25;
        checksum = ((checksum & 0x1ffffff) << 5) ^ value;
        for (unsigned j = 0; j < 5; ++j)
            if ((top >> j) & 1)
                checksum ^= generators[j];
    };
    for (auto c : text.substr(0, 4))
        feed(c >> 5);
    feed(0);
    for (auto c : text.substr(0, 4))
        feed(c & 31);
    unsigned bits = 0;
    for (std::size_t i = 5; i < text.size(); ++i) {
        const auto value = alphabet.find(text[i]);
        if (value == std::string_view::npos)
            return false;
        feed(value);
        if (i >= text.size() - 6)
            continue;
        acc = (acc << 5) | value;
        bits += 5;
        if (bits >= 8) {
            bits -= 8;
            bytes.push_back(acc >> bits);
        }
    }
    if (checksum != 1 || (acc & ((1U << bits) - 1)))
        return false;
    return (bytes.size() == 57 && (bytes[0] == 1 || (!sender && bytes[0] == 0x21))) ||
           (!sender && bytes.size() == 29 && bytes[0] == 0x61);
}
bool parse(std::string_view payload, Payment& p) {
    if (payload.size() > 8192)
        return false;
    const std::unique_ptr<cJSON, decltype(&cJSON_Delete)> obj(parse_json(payload), cJSON_Delete);
    p.token = native_signing_token("cardano", field(obj.get(), "asset"));
    if (!cJSON_IsObject(obj.get()) || cJSON_GetArraySize(obj.get()) != (p.token ? 12 : 10) ||
        field(obj.get(), "network") != "cardano-mainnet")
        return false;
    auto& r = p.review;
    r.wallet_id = field(obj.get(), "wallet_id");
    r.mode = field(obj.get(), "mode");
    r.sender = field(obj.get(), "sender");
    r.recipient = field(obj.get(), "recipient");
    r.amount = field(obj.get(), "amount_atomic");
    r.fee = field(obj.get(), "fee_atomic");
    r.change = field(obj.get(), "change_atomic");
    if (!hash_hex(r.wallet_id) || (r.mode != "STANDARD" && r.mode != "HIDDEN") ||
        !address(r.sender, p.sender, true) || !address(r.recipient, p.recipient, false) ||
        p.sender == p.recipient || !decimal(r.amount, p.amount) || p.amount == 0 ||
        (p.token ? p.amount > INT64_MAX : p.amount > max_supply) || !decimal(r.fee, p.fee) ||
        p.fee == 0 || p.fee > max_fee || !decimal(r.change, p.change) || p.change > max_supply ||
        !decimal(field(obj.get(), "ttl"), p.ttl) || p.ttl == 0)
        return false;
    if (p.token) {
        p.tokens = p.amount;
        r.symbol = p.token->symbol;
        r.decimals = p.token->decimals;
        r.recipient_lovelace = field(obj.get(), "recipient_lovelace");
        if (!decimal(r.recipient_lovelace, p.amount) || p.amount < 1000000 || p.amount > max_supply)
            return false;
    }
    const auto* inputs = cJSON_GetObjectItemCaseSensitive(obj.get(), "inputs");
    if (!cJSON_IsArray(inputs) || cJSON_GetArraySize(inputs) < 1 || cJSON_GetArraySize(inputs) > 16)
        return false;
    std::uint64_t total = 0;
    for (auto* row = inputs->child; row; row = row->next) {
        auto hash = field(row, "tx_hash");
        std::uint64_t index = 0, value = 0;
        std::uint64_t quantity = 0;
        if (p.token && (!decimal(field(row, "token_quantity"), quantity) ||
                        quantity > INT64_MAX - p.token_total))
            return false;
        p.token_total += quantity;
        if (!cJSON_IsObject(row) || cJSON_GetArraySize(row) != (p.token ? 4 : 3) ||
            !hash_hex(hash) || !decimal(field(row, "tx_index"), index) || index > 65535 ||
            !decimal(field(row, "value"), value) || value == 0 || value > max_supply - total ||
            !p.inputs.insert({std::move(hash), index}).second)
            return false;
        total += value;
    }
    return total == p.amount + p.fee + p.change &&
           (!p.token || (p.token_total >= p.tokens && (p.token_total == p.tokens || p.change > 0)));
}

void cbor(Bytes& output, unsigned major, std::uint64_t value) {
    const unsigned prefix = major << 5;
    if (value < 24) {
        output.push_back(prefix | value);
        return;
    }
    const unsigned size = value <= 0xff ? 1 : value <= 0xffff ? 2 : value <= 0xffffffffULL ? 4 : 8;
    output.push_back(prefix | (size == 1 ? 24 : size == 2 ? 25 : size == 4 ? 26 : 27));
    for (unsigned i = size; i > 0; --i)
        output.push_back(value >> ((i - 1) * 8));
}
void bytes(Bytes& out, const Bytes& value) {
    cbor(out, 2, value.size());
    out.insert(out.end(), value.begin(), value.end());
}
Bytes body(const Payment& p) {
    Bytes out;
    cbor(out, 5, 4);
    cbor(out, 0, 0);
    cbor(out, 6, 258);
    cbor(out, 4, p.inputs.size());
    constexpr std::string_view hex = "0123456789abcdef";
    for (const auto& i : p.inputs) {
        Bytes hash;
        for (std::size_t j = 0; j < i.hash.size(); j += 2)
            hash.push_back((hex.find(i.hash[j]) << 4) | hex.find(i.hash[j + 1]));
        cbor(out, 4, 2);
        bytes(out, hash);
        cbor(out, 0, i.index);
    }
    cbor(out, 0, 1);
    cbor(out, 4, p.change ? 2 : 1);
    const auto output = [&](const Bytes& to, std::uint64_t value, std::uint64_t quantity) {
        cbor(out, 5, 2);
        cbor(out, 0, 0);
        bytes(out, to);
        cbor(out, 0, 1);
        if (!p.token || quantity == 0) {
            cbor(out, 0, value);
            return;
        }
        cbor(out, 4, 2);
        cbor(out, 0, value);
        cbor(out, 5, 1);
        const auto decode = [&](std::string_view text) {
            Bytes value;
            for (std::size_t j = 0; j < text.size(); j += 2)
                value.push_back((hex.find(text[j]) << 4) | hex.find(text[j + 1]));
            return value;
        };
        bytes(out, decode(p.token->contract.substr(0, 56)));
        cbor(out, 5, 1);
        bytes(out, decode(p.token->contract.substr(56)));
        cbor(out, 0, quantity);
    };
    output(p.recipient, p.amount, p.tokens);
    if (p.change)
        output(p.sender, p.change, p.token_total - p.tokens);
    cbor(out, 0, 2);
    cbor(out, 0, p.fee);
    cbor(out, 0, 3);
    cbor(out, 0, p.ttl);
    return out;
}
} // namespace
bool review_cardano(std::string_view payload, CardanoReview& review) {
    Payment p;
    if (!parse(payload, p))
        return false;
    review = std::move(p.review);
    return true;
}
bool sign_cardano(std::string_view payload, std::string_view mnemonic, std::string_view passphrase,
                  std::string& encoded) {
    encoded.clear();
    Payment p;
    if (!parse(payload, p))
        return false;
    const auto tx = body(p);
    std::array<std::uint8_t, 32> digest{};
    std::array<std::uint8_t, 96> witness{};
    if (tc_blake2b(tx.data(), tx.size(), digest.data(), digest.size()) != 0 ||
        !cardano_payment_witness(mnemonic, passphrase, p.review.sender, digest, witness))
        return false;
    constexpr std::string_view hex = "0123456789abcdef";
    for (auto b : witness) {
        encoded += hex[b >> 4];
        encoded += hex[b & 15];
    }
    return true;
}
} // namespace airlock
