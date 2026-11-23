// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/utxo_signer.hpp"
#include "core/json_memory.hpp"
#include "cJSON.h"
extern "C" {
#include <TrezorCrypto/hasher.h>
}
#include <cstdlib>
#include <iostream>
#include <memory>
namespace {
using namespace airlock;
struct Random : crypto::RandomSource {
    bool fill(crypto::Output o) override {
        for (std::size_t i = 0; i < o.size; ++i)
            o.data[i] = std::rand() % 256;
        return true;
    }
};
void check(bool b) {
    if (!b) {
        std::cerr << "UTXO check failed\n";
        std::exit(1);
    }
}
std::string hex(const std::vector<std::uint8_t>& b) {
    std::string s;
    for (auto v : b) {
        s += "0123456789abcdef"[v >> 4];
        s += "0123456789abcdef"[v & 15];
    }
    return s;
}
void le(std::vector<std::uint8_t>& b, std::uint64_t n, unsigned count) {
    for (unsigned i = 0; i < count; ++i) {
        b.push_back(n & 255);
        n >>= 8;
    }
}
std::vector<std::uint8_t> script(const crypto::DerivedKey& key, bool witness) {
    std::array<std::uint8_t, 32> h{};
    hasher_Raw(HASHER_SHA2_RIPEMD, key.public_key.data(), key.public_key_size, h.data());
    std::vector<std::uint8_t> s =
        witness ? std::vector<std::uint8_t>{0, 20} : std::vector<std::uint8_t>{0x76, 0xa9, 20};
    s.insert(s.end(), h.begin(), h.begin() + 20);
    if (!witness) {
        s.push_back(0x88);
        s.push_back(0xac);
    }
    return s;
}
std::string print(cJSON* o) {
    char* s = cJSON_PrintUnformatted(o);
    check(s);
    std::string v = s;
    json_free(s);
    return v;
}
} // namespace
int main() {
    init_json();
    Random random;
    crypto::Seed seed;
    check(crypto::wallet_core_seed("abandon abandon abandon abandon abandon abandon abandon "
                                   "abandon abandon abandon abandon about",
                                   "", seed) == crypto::HdError::None);
    std::unique_ptr<cJSON, decltype(&cJSON_Delete)> fixtures(cJSON_CreateArray(), cJSON_Delete);
    for (auto network : {Network::Bitcoin, Network::Litecoin, Network::Dogecoin, Network::Dash,
                         Network::BitcoinCash, Network::Ecash})
        for (bool nochange : {false, true}) {
            const NetworkAccountSpec* spec = nullptr;
            for (const auto& s : network_accounts)
                if (s.network == network)
                    spec = &s;
            check(spec);
            const std::string coin = network == Network::Bitcoin    ? "bitcoin"
                                     : network == Network::Litecoin ? "litecoin"
                                     : network == Network::Dogecoin ? "doge"
                                     : network == Network::Dash     ? "dash"
                                     : network == Network::Ecash    ? "ecash"
                                                                    : "bitcoincash";
            crypto::DerivedKey key, other;
            std::string path(spec->first_account_path),
                toPath = path.substr(0, path.size() - 1) + "1";
            check(crypto::derive_key({seed.value.data(), 64}, crypto::Curve::Secp256k1, path,
                                     random, key) == crypto::HdError::None);
            check(crypto::derive_key({seed.value.data(), 64}, crypto::Curve::Secp256k1, toPath,
                                     random, other) == crypto::HdError::None);
            const bool witness = network == Network::Bitcoin || network == Network::Litecoin;
            auto sender = script(key, witness), recipient = script(other, witness);
            std::string from, to;
            check(encode_receiving_address(network, {key.public_key, key.public_key_size}, from) ==
                  crypto::HdError::None);
            check(encode_receiving_address(network, {other.public_key, other.public_key_size},
                                           to) == crypto::HdError::None);
            // Synthetic public funding transaction. It is never submitted to a network.
            std::vector<std::uint8_t> parent;
            le(parent, 2, 4);
            parent.push_back(1);
            parent.insert(parent.end(), 32, 0x11);
            le(parent, 0, 4);
            parent.push_back(0);
            le(parent, 0xffffffff, 4);
            parent.push_back(1);
            le(parent, 100000000, 8);
            parent.push_back(sender.size());
            parent.insert(parent.end(), sender.begin(), sender.end());
            le(parent, 0, 4);
            std::array<std::uint8_t, 32> hash1{}, hash2{};
            check(crypto::sha256({parent.data(), parent.size()}, hash1));
            check(crypto::sha256({hash1.data(), 32}, hash2));
            std::vector<std::uint8_t> id(hash2.rbegin(), hash2.rend());
            const auto fee = network == Network::Dogecoin ? 1000000 : 10000;
            const auto amount = nochange ? 100000000 - fee : 50000000;
            const auto change = 100000000 - amount - fee;
            const std::string payload =
                "{\"mode\":\"STANDARD\",\"asset\":{\"coin\":\"" + coin + "\",\"symbol\":\"" +
                std::string(spec->native_symbol) +
                "\",\"decimals\":" + std::to_string(network == Network::Ecash ? 2 : 8) +
                ",\"contract\":\"\",\"address\":\"" + from + "\",\"derivation_path\":\"" + path +
                "\"},\"txData\":{\"utxoVersion\":1,\"amount\":\"" + std::to_string(amount) +
                "\",\"fee\":\"" + std::to_string(fee) + "\",\"changeAmount\":\"" +
                std::to_string(change) + "\",\"byteFee\":\"1\",\"toAddress\":\"" + to +
                "\",\"changeAddress\":\"" + from + "\",\"recipientScript\":\"" + hex(recipient) +
                "\",\"changeScript\":\"" + hex(sender) + "\",\"utxos\":[{\"txHash\":\"" + hex(id) +
                "\",\"index\":0,\"amount\":\"100000000\",\"script\":\"" + hex(sender) +
                "\",\"sequence\":4294967295,\"previousTransaction\":\"" + hex(parent) + "\"}]}}";
            UtxoReview review;
            check(review_utxo(payload, review));
            std::string signedtx;
            check(sign_utxo(payload, {seed.value.data(), 64}, random, signedtx));
            check(!signedtx.empty());
            auto* fixture = cJSON_CreateObject();
            auto* request = parse_json(payload);
            check(request);
            cJSON_AddItemToObject(fixture, "request", request);
            cJSON_AddStringToObject(fixture, "signed", signedtx.c_str());
            cJSON_AddItemToArray(fixtures.get(), fixture);
            // Any change to a reviewed fee, address, parent amount, input duplication or path must
            // fail.
            for (const auto* field : {"fee", "changeAmount", "amount", "recipientScript"}) {
                auto* mut = cJSON_Duplicate(request, true);
                auto* tx = cJSON_GetObjectItemCaseSensitive(mut, "txData");
                cJSON_ReplaceItemInObjectCaseSensitive(tx, field, cJSON_CreateString("1"));
                check(!review_utxo(print(mut), review));
                cJSON_Delete(mut);
            }
            auto* mut = cJSON_Duplicate(request, true);
            auto* rows = cJSON_GetObjectItemCaseSensitive(
                cJSON_GetObjectItemCaseSensitive(mut, "txData"), "utxos");
            cJSON_AddItemToArray(rows, cJSON_Duplicate(rows->child, true));
            check(!review_utxo(print(mut), review));
            cJSON_Delete(mut);
            crypto::Seed wrong;
            check(!sign_utxo(payload, {wrong.value.data(), 64}, random, signedtx));
        }
    std::cout << print(fixtures.get()) << "\n";
}
