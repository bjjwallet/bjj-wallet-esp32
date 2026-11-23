// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/brz_signer.hpp"
#include "core/evm_signing_catalog.hpp"
#include "core/json_memory.hpp"
#include "core/network_accounts.hpp"
#include "core/trezor_random_scope.hpp"
#include "cJSON.h"
extern "C" {
#include <TrezorCrypto/ecdsa.h>
#include <TrezorCrypto/secp256k1.h>
#include <TrezorCrypto/sha3.h>
}
#include <algorithm>
#include <charconv>
#include <limits>
#include <memory>
#include <vector>

namespace airlock {
namespace {
using Bytes = std::vector<std::uint8_t>;
struct Plan {
    BrzReview review;
    Bytes amount;
    std::uint64_t nonce = 0, gas_price = 0, gas_limit = 0;
};

std::string get(const cJSON* object, const char* key) {
    const auto* field = cJSON_GetObjectItemCaseSensitive(object, key);
    return cJSON_IsString(field) && field->valuestring ? field->valuestring : "";
}

bool lower_hex(std::string_view value) {
    return value.find_first_not_of("0123456789abcdef") == std::string_view::npos;
}

bool address(std::string& value) {
    if (value.size() != 42 || value.substr(0, 2) != "0x")
        return false;
    for (auto& digit : value)
        if (digit >= 'A' && digit <= 'F')
            digit += 'a' - 'A';
    return lower_hex(std::string_view(value).substr(2)) &&
           value != "0x0000000000000000000000000000000000000000";
}

bool integer(std::string_view value, std::uint64_t& out, int base) {
    if (value.empty() || (value.size() > 1 && value.front() == '0'))
        return false;
    const auto end = value.data() + value.size();
    const auto parsed = std::from_chars(value.data(), end, out, base);
    return parsed.ec == std::errc{} && parsed.ptr == end;
}

bool quantity(std::string_view value, std::uint64_t& out) {
    return value.substr(0, 2) == "0x" && lower_hex(value.substr(2)) &&
           integer(value.substr(2), out, 16);
}

bool decimal_word(std::string_view value, Bytes& out) {
    if (value.empty() || value.size() > 78 || value.front() == '0')
        return false;
    out.assign(32, 0);
    for (const auto digit : value) {
        if (digit < '0' || digit > '9')
            return false;
        unsigned carry = digit - '0';
        for (auto i = out.rbegin(); i != out.rend(); ++i) {
            carry += *i * 10U;
            *i = carry & 255;
            carry >>= 8;
        }
        if (carry)
            return false;
    }
    return true;
}

bool parse(std::string_view payload, Plan& plan) {
    if (payload.size() > 1024)
        return false;
    const std::unique_ptr<cJSON, decltype(&cJSON_Delete)> object(parse_json(payload), cJSON_Delete);
    if (!cJSON_IsObject(object.get()))
        return false;
    for (const auto* field = object->child; field; field = field->next)
        for (const auto* other = field->next; other; other = other->next)
            if (!field->string || !other->string ||
                std::string_view(field->string) == other->string)
                return false;
    const auto* chain = cJSON_GetObjectItemCaseSensitive(object.get(), "chain_id");
    auto& review = plan.review;
    const auto chain_id = get(object.get(), "chain_id");
    const auto* asset = cJSON_GetObjectItemCaseSensitive(object.get(), "asset");
    std::uint64_t chain_number = 56;
    if (chain && (!integer(chain_id, chain_number, 10) || chain_number > 0xffffffffULL))
        return false;
    // Legacy methods remain byte-compatible. The generic method always supplies a symbol.
    if (!asset && chain && chain_number != 1 && chain_number != 137)
        return false;
    const auto symbol = asset ? get(object.get(), "asset") : chain_number == 137 ? "POL" : "BRZ";
    const auto* identity = evm_signing_asset(chain_number, symbol);
    if (!identity)
        return false;
    review.chain_id = identity->chain_id;
    review.symbol = identity->symbol;
    review.contract = identity->contract;
    review.network_name = identity->network_name;
    review.fee_symbol = identity->native_symbol;
    review.decimals = identity->decimals;
    review.token = !review.contract.empty();
    review.ethereum = chain_number == 1;
    review.polygon = chain_number == 137;
    review.arbitrum = chain_number == 42161;
    review.native_ethereum = review.ethereum && !review.token;
    const auto* extra = cJSON_GetObjectItemCaseSensitive(object.get(), "extra_fee_atomic");
    if ((chain_number == 8453) != (extra != nullptr) ||
        cJSON_GetArraySize(object.get()) != (chain ? 10 : 9) + (asset ? 1 : 0) + (extra ? 1 : 0))
        return false;
    review.wallet_id = get(object.get(), "wallet_id");
    review.mode = get(object.get(), "mode");
    review.sender = get(object.get(), "sender");
    review.recipient = get(object.get(), "recipient");
    review.path = get(object.get(), "path");
    review.amount = get(object.get(), "amount_atomic");
    const std::string_view path = review.path;
    const auto path_prefix = identity->path_prefix;
    std::uint64_t index;
    if (review.wallet_id.size() != 64 || !lower_hex(review.wallet_id) ||
        (review.mode != "STANDARD" && review.mode != "HIDDEN") || !address(review.sender) ||
        !address(review.recipient) || path.substr(0, path_prefix.size()) != path_prefix ||
        !integer(path.substr(path_prefix.size()), index, 10) || index > 0x7fffffff ||
        !decimal_word(review.amount, plan.amount) ||
        !quantity(get(object.get(), "nonce"), plan.nonce) ||
        !quantity(get(object.get(), "gas_price"), plan.gas_price) ||
        !quantity(get(object.get(), "gas_limit"), plan.gas_limit) || !plan.gas_price ||
        plan.gas_limit < 21000 ||
        plan.gas_limit > std::numeric_limits<std::uint64_t>::max() / plan.gas_price)
        return false;
    auto fee = plan.gas_price * plan.gas_limit;
    if (extra) {
        std::uint64_t addition = 0;
        if (!integer(get(object.get(), "extra_fee_atomic"), addition, 10) ||
            addition > std::numeric_limits<std::uint64_t>::max() - fee)
            return false;
        fee += addition;
    }
    review.fee = std::to_string(fee);
    return true;
}

Bytes decode_hex(std::string_view value) {
    constexpr std::string_view digits = "0123456789abcdef";
    Bytes out;
    for (std::size_t i = 0; i < value.size(); i += 2)
        out.push_back((digits.find(value[i]) << 4) | digits.find(value[i + 1]));
    return out;
}

Bytes quantity_bytes(std::uint64_t value) {
    Bytes out;
    while (value) {
        out.insert(out.begin(), value & 255);
        value >>= 8;
    }
    return out;
}

Bytes minimal(const std::uint8_t* data) {
    return {std::find_if(data, data + 32, [](auto byte) { return byte != 0; }), data + 32};
}

void rlp(Bytes& output, const Bytes& value, bool list = false) {
    if (!list && value.size() == 1 && value.front() < 128) {
        output.push_back(value.front());
        return;
    }
    const auto base = list ? 192 : 128;
    if (value.size() <= 55)
        output.push_back(base + value.size());
    else {
        const auto size = quantity_bytes(value.size());
        output.push_back(base + 55 + size.size());
        output.insert(output.end(), size.begin(), size.end());
    }
    output.insert(output.end(), value.begin(), value.end());
}

Bytes transaction(const Plan& plan, unsigned v, const Bytes& r, const Bytes& s) {
    const auto recipient = decode_hex(std::string_view(plan.review.recipient).substr(2));
    Bytes data;
    const bool native = !plan.review.token;
    const auto& contract = plan.review.contract;
    if (!native) {
        data = {0xa9, 0x05, 0x9c, 0xbb};
        data.insert(data.end(), 12, 0);
        data.insert(data.end(), recipient.begin(), recipient.end());
        data.insert(data.end(), plan.amount.begin(), plan.amount.end());
    }
    Bytes fields;
    for (const auto& field :
         {quantity_bytes(plan.nonce), quantity_bytes(plan.gas_price),
          quantity_bytes(plan.gas_limit), native ? recipient : decode_hex(contract),
          native ? minimal(plan.amount.data()) : Bytes{}, data, quantity_bytes(v), r, s})
        rlp(fields, field);
    Bytes output;
    rlp(output, fields, true);
    return output;
}
} // namespace

bool review_brz(std::string_view payload, BrzReview& review) {
    Plan plan;
    if (!parse(payload, plan))
        return false;
    review = std::move(plan.review);
    return true;
}

bool sign_brz(std::string_view payload, crypto::Bytes seed, crypto::RandomSource& random,
              std::string& encoded) {
    encoded.clear();
    Plan plan;
    if (!parse(payload, plan))
        return false;
    crypto::TrezorRandomScope binding(random);
    crypto::DerivedKey key;
    if (crypto::derive_key(seed, crypto::Curve::Secp256k1, plan.review.path, random, key) !=
        crypto::HdError::None)
        return false;
    std::string sender;
    const auto network =
        Network::Ethereum; // Address encoding is identical; derivation path is checked per chain.
    if (encode_receiving_address(network, {key.public_key, key.public_key_size}, sender) !=
            crypto::HdError::None ||
        !address(sender) || sender != plan.review.sender)
        return false;

    const unsigned chain_id = plan.review.chain_id;
    const auto unsigned_tx = transaction(plan, chain_id, {}, {});
    std::array<std::uint8_t, 32> digest{};
    keccak_256(unsigned_tx.data(), unsigned_tx.size(), digest.data());
    crypto::Sensitive<64> signature;
    std::uint8_t recovery = 0;
    if (ecdsa_sign_digest(&secp256k1, key.private_key.value.data(), digest.data(),
                          signature.value.data(), &recovery,
                          [](std::uint8_t id, std::uint8_t*) -> int { return id < 2; }) != 0)
        return false;
    const auto raw =
        transaction(plan, 2 * chain_id + 35 + recovery, minimal(signature.value.data()),
                    minimal(signature.value.data() + 32));
    constexpr char digits[] = "0123456789abcdef";
    encoded = "0x";
    for (auto byte : raw) {
        encoded += digits[byte >> 4];
        encoded += digits[byte & 15];
    }
    return true;
}
} // namespace airlock
