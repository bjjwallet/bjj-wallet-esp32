// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/ledger_signer.hpp"
#include "core/network_accounts.hpp"
#include "core/native_signing_catalog.hpp"
#include "core/json_memory.hpp"
#include "cJSON.h"
#include <cstdlib>
#include <fstream>
#include <iostream>
using namespace airlock;
struct Random : crypto::RandomSource {
    bool fill(crypto::Output o) override {
        for (std::size_t i = 0; i < o.size; ++i)
            o.data[i] = std::rand() % 256;
        return true;
    }
};
void check(bool v) {
    if (!v) {
        std::cerr << "ledger signer check failed\n";
        std::exit(1);
    }
}
int main(int argc, char** argv) {
    init_json();
    Random random;
    crypto::Seed seed;
    check(crypto::wallet_core_seed("abandon abandon abandon abandon abandon abandon abandon "
                                   "abandon abandon abandon abandon about",
                                   "", seed) == crypto::HdError::None);
    crypto::DerivedKey key;
    check(crypto::derive_key({seed.value.data(), 64}, crypto::Curve::Ed25519, "m/44'/148'/0'",
                             random, key) == crypto::HdError::None);
    std::string sender;
    check(encode_receiving_address(Network::Stellar, {key.public_key, key.public_key_size},
                                   sender) == crypto::HdError::None);
    auto* fixtures = cJSON_CreateArray();
    const auto run = [&](std::string symbol) {
        auto* plan = cJSON_CreateObject();
        for (const auto& row :
             {std::pair{"network", "stellar-mainnet"},
              {"wallet_id", "1111111111111111111111111111111111111111111111111111111111111111"},
              {"mode", "STANDARD"},
              {"recipient", "GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF"},
              {"amount_atomic", "1250000"},
              {"fee_atomic", "100"},
              {"sequence", "42"},
              {"expires", "2100000000"}})
            cJSON_AddStringToObject(plan, row.first, row.second);
        cJSON_AddStringToObject(plan, "sender", sender.c_str());
        cJSON_AddStringToObject(plan, "asset", symbol.c_str());
        char* payload = cJSON_PrintUnformatted(plan);
        LedgerReview review;
        std::string signature;
        check(review_ledger(payload, review) && review.symbol == symbol);
        check(sign_ledger(payload, {seed.value.data(), 64}, random, signature) &&
              signature.size() == 128);
        json_free(payload);
        cJSON_ReplaceItemInObjectCaseSensitive(
            plan, "sender",
            cJSON_CreateString("GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF"));
        payload = cJSON_PrintUnformatted(plan);
        std::string rejected;
        check(!sign_ledger(payload, {seed.value.data(), 64}, random, rejected));
        json_free(payload);
        cJSON_ReplaceItemInObjectCaseSensitive(plan, "sender", cJSON_CreateString(sender.c_str()));
        auto* row = cJSON_CreateObject();
        cJSON_AddItemToObject(row, "request", plan);
        cJSON_AddStringToObject(row, "signature", signature.c_str());
        cJSON_AddItemToArray(fixtures, row);
    };
    run("XLM");
    for (const auto& token : native_signing_tokens)
        if (token.network == "stellar")
            run(std::string(token.symbol));
    crypto::DerivedKey tron;
    check(crypto::derive_key({seed.value.data(), 64}, crypto::Curve::Secp256k1, "m/44'/195'/0'/0/0",
                             random, tron) == crypto::HdError::None);
    std::string tron_sender;
    check(encode_receiving_address(Network::Tron, {tron.public_key, tron.public_key_size},
                                   tron_sender) == crypto::HdError::None);
    const auto run_tron = [&](std::string symbol) {
        auto* plan = cJSON_CreateObject();
        for (const auto& row :
             {std::pair{"network", "tron-mainnet"},
              {"wallet_id", "1111111111111111111111111111111111111111111111111111111111111111"},
              {"mode", "STANDARD"},
              {"recipient", "TJRabPrwbZy45sbavfcjinPJC18kjpRTv8"},
              {"amount_atomic", "1250000"},
              {"fee_atomic", "100000000"},
              {"ref_block_bytes", "1234"},
              {"ref_block_hash", "1111111111111111"},
              {"timestamp", "1800000000000"},
              {"expires", "1800000600000"}})
            cJSON_AddStringToObject(plan, row.first, row.second);
        cJSON_AddStringToObject(plan, "sender", tron_sender.c_str());
        cJSON_AddStringToObject(plan, "asset", symbol.c_str());
        char* text = cJSON_PrintUnformatted(plan);
        LedgerReview review;
        std::string signature;
        check(review_ledger(text, review) && review.symbol == symbol);
        check(sign_ledger(text, {seed.value.data(), 64}, random, signature) &&
              signature.size() == 130);
        json_free(text);
        auto* row = cJSON_CreateObject();
        cJSON_AddItemToObject(row, "request", plan);
        cJSON_AddStringToObject(row, "signature", signature.c_str());
        cJSON_AddItemToArray(fixtures, row);
    };
    run_tron("TRX");
    for (const auto& token : native_signing_tokens)
        if (token.network == "tron")
            run_tron(std::string(token.symbol));
    crypto::DerivedKey xrp;
    check(crypto::derive_key({seed.value.data(), 64}, crypto::Curve::Secp256k1, "m/44'/144'/0'/0/0",
                             random, xrp) == crypto::HdError::None);
    std::string xrp_sender;
    check(encode_receiving_address(Network::Ripple, {xrp.public_key, xrp.public_key_size},
                                   xrp_sender) == crypto::HdError::None);
    const auto run_xrp = [&](std::string symbol) {
        auto* plan = cJSON_CreateObject();
        for (const auto& row :
             {std::pair{"network", "ripple-mainnet"},
              {"wallet_id", "1111111111111111111111111111111111111111111111111111111111111111"},
              {"mode", "STANDARD"},
              {"recipient", "rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTh"},
              {"amount_atomic", "1250000"},
              {"fee_atomic", "12"},
              {"sequence", "42"},
              {"last_ledger", "100000000"}})
            cJSON_AddStringToObject(plan, row.first, row.second);
        cJSON_AddStringToObject(plan, "sender", xrp_sender.c_str());
        cJSON_AddStringToObject(plan, "asset", symbol.c_str());
        char* text = cJSON_PrintUnformatted(plan);
        LedgerReview review;
        std::string signature;
        check(review_ledger(text, review) && review.symbol == symbol);
        check(sign_ledger(text, {seed.value.data(), 64}, random, signature) &&
              signature.size() == 196);
        json_free(text);
        auto* row = cJSON_CreateObject();
        cJSON_AddItemToObject(row, "request", plan);
        cJSON_AddStringToObject(row, "signature", signature.c_str());
        cJSON_AddItemToArray(fixtures, row);
    };
    run_xrp("XRP");
    for (const auto& token : native_signing_tokens)
        if (token.network == "ripple")
            run_xrp(std::string(token.symbol));
    crypto::DerivedKey sui;
    check(crypto::derive_key({seed.value.data(), 64}, crypto::Curve::Ed25519, "m/44'/784'/0'/0'/0'",
                             random, sui) == crypto::HdError::None);
    std::string sui_sender;
    check(encode_receiving_address(Network::Sui, {sui.public_key, sui.public_key_size},
                                   sui_sender) == crypto::HdError::None);
    const auto run_sui = [&](std::string symbol, bool merge) {
        auto* plan = cJSON_CreateObject();
        for (const auto& row :
             {std::pair{"network", "sui-mainnet"},
              {"wallet_id", "1111111111111111111111111111111111111111111111111111111111111111"},
              {"mode", "STANDARD"},
              {"recipient", "0x1111111111111111111111111111111111111111111111111111111111111111"},
              {"amount_atomic", "1250000"},
              {"fee_atomic", "10000000"},
              {"gas_price", "100"},
              {"epoch", "1300"}})
            cJSON_AddStringToObject(plan, row.first, row.second);
        cJSON_AddStringToObject(plan, "sender", sui_sender.c_str());
        cJSON_AddStringToObject(plan, "asset", symbol.c_str());
        const auto ref = [](char id) {
            auto* r = cJSON_CreateObject();
            cJSON_AddStringToObject(r, "id", ("0x" + std::string(64, id)).c_str());
            cJSON_AddStringToObject(r, "version", "1");
            cJSON_AddStringToObject(r, "digest", std::string(64, '3').c_str());
            cJSON_AddStringToObject(r, "balance", "100000000000");
            return r;
        };
        auto* gas = cJSON_CreateArray();
        cJSON_AddItemToArray(gas, ref('1'));
        cJSON_AddItemToObject(plan, "gas", gas);
        auto* coins = cJSON_CreateArray();
        if (symbol != "SUI") {
            cJSON_AddItemToArray(coins, ref('2'));
            if (merge)
                cJSON_AddItemToArray(coins, ref('4'));
        }
        cJSON_AddItemToObject(plan, "coins", coins);
        char* text = cJSON_PrintUnformatted(plan);
        LedgerReview review;
        std::string signature;
        check(review_ledger(text, review) && review.symbol == symbol);
        check(sign_ledger(text, {seed.value.data(), 64}, random, signature) &&
              signature.size() == 192);
        json_free(text);
        auto* row = cJSON_CreateObject();
        cJSON_AddItemToObject(row, "request", plan);
        cJSON_AddStringToObject(row, "signature", signature.c_str());
        cJSON_AddItemToArray(fixtures, row);
    };
    run_sui("SUI", false);
    run_sui("USDC", false);
    run_sui("USDC", true);
    crypto::DerivedKey ton;
    check(crypto::derive_key({seed.value.data(), 64}, crypto::Curve::Ed25519, "m/44'/607'/0'",
                             random, ton) == crypto::HdError::None);
    std::string ton_sender;
    check(encode_receiving_address(Network::Ton, {ton.public_key, ton.public_key_size},
                                   ton_sender) == crypto::HdError::None);
    check(ton_sender == "UQAzWZa6nM5mJev91wGc7VCSfBoIsYRqKJpV78N8Add9-RKY");
    const auto run_ton = [&](std::string symbol, std::string sequence) {
        auto* plan = cJSON_CreateObject();
        for (const auto& row :
             {std::pair{"network", "ton-mainnet"},
              {"wallet_id", "1111111111111111111111111111111111111111111111111111111111111111"},
              {"mode", "STANDARD"},
              {"recipient", "0:1111111111111111111111111111111111111111111111111111111111111111"},
              {"amount_atomic", "1250000"},
              {"fee_atomic", "60000000"},
              {"expires", "2100000000"}})
            cJSON_AddStringToObject(plan, row.first, row.second);
        cJSON_AddStringToObject(plan, "sender", ton_sender.c_str());
        cJSON_AddStringToObject(plan, "asset", symbol.c_str());
        cJSON_AddStringToObject(plan, "sequence", sequence.c_str());
        cJSON_AddStringToObject(
            plan, "jetton_wallet",
            symbol == "TON" ? ""
                            : "0:b2f18d7c8a26faaa0f064b3a2e2873b1368a7a7ded502d279e37eabe9ca30ecf");
        char* text = cJSON_PrintUnformatted(plan);
        LedgerReview review;
        std::string signature;
        check(review_ledger(text, review) && review.symbol == symbol);
        check(sign_ledger(text, {seed.value.data(), 64}, random, signature) &&
              signature.size() == 192);
        json_free(text);
        auto* row = cJSON_CreateObject();
        cJSON_AddItemToObject(row, "request", plan);
        cJSON_AddStringToObject(row, "signature", signature.c_str());
        cJSON_AddItemToArray(fixtures, row);
    };
    for (const auto& seq : {"0", "42"}) {
        run_ton("TON", seq);
        run_ton("USDT", seq);
    }
    if (argc == 2) {
        char* text = cJSON_PrintUnformatted(fixtures);
        std::ofstream(argv[1]) << text << '\n';
        json_free(text);
    }
    cJSON_Delete(fixtures);
}
