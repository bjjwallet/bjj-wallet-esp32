// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/cardano_signer.hpp"
#include "core/json_memory.hpp"
#include "core/native_signing_catalog.hpp"
#include "cJSON.h"
#include <TrezorCrypto/ed25519.h>
#include <array>
#include <cstdlib>
#include <iostream>
#include <fstream>

using namespace airlock;

namespace {
void check(bool result, const char* message) {
    if (!result) {
        std::cerr << message << '\n';
        std::exit(1);
    }
}
std::string replace(std::string text, std::string_view before, std::string_view after) {
    text.replace(text.find(before), before.size(), after);
    return text;
}
template <std::size_t Size> std::array<unsigned char, Size> hex(std::string_view text) {
    constexpr std::string_view digits = "0123456789abcdef";
    std::array<unsigned char, Size> result{};
    for (std::size_t i = 0; i < Size; ++i)
        result[i] = (digits.find(text[2 * i]) << 4) | digits.find(text[2 * i + 1]);
    return result;
}
} // namespace

int main(int argc, char** argv) {
    init_json();
    const std::string plan = R"({
        "network":"cardano-mainnet",
        "wallet_id":"1111111111111111111111111111111111111111111111111111111111111111",
        "mode":"STANDARD",
        "sender":"addr1qy8ac7qqy0vtulyl7wntmsxc6wex80gvcyjy33qffrhm7sh927ysx5sftuw0dlft05dz3c7revpf7jx0xnlcjz3g69mq4afdhv",
        "recipient":"addr1qx2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3n0d3vllmyqwsx5wktcd8cc3sq835lu7drv2xwl2wywfgse35a3x",
        "amount_atomic":"2000000",
        "fee_atomic":"168581",
        "change_atomic":"2831419",
        "ttl":"203351900",
        "inputs":[{"tx_hash":"1111111111111111111111111111111111111111111111111111111111111111","tx_index":"0","value":"5000000"}]
    })";
    CardanoReview review;
    check(review_cardano(plan, review) && review.amount == "2000000" && review.change == "2831419",
          "review ADA payment");
    check(!review_cardano(replace(plan, "2831419", "2831420"), review), "reject mismatched change");
    check(!review_cardano(replace(plan, "203351900", "0"), review), "reject invalid expiry");
    check(!review_cardano(replace(plan, "5000000", "5000001"), review),
          "reject mismatched input value");
    check(!review_cardano(replace(plan, "se35a3x", "se35a3q"), review),
          "reject bad recipient checksum");
    const auto mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon "
                          "abandon abandon about";
    std::string witness;
    check(!sign_cardano(plan, mnemonic, "wrong passphrase", witness),
          "reject sender outside approved wallet mode");
    check(sign_cardano(plan, mnemonic, "", witness) && witness.size() == 192, "sign ADA natively");
    // The canonical body was independently encoded and hashed with Python hashlib.
    const auto digest = hex<32>("c65e5892f1b66f42b3e21ea7cd28cb972d962de2cbb8b243b3cbaa73d98079e0");
    const auto signed_witness = hex<96>(witness);
    check(ed25519_sign_open(digest.data(), digest.size(), signed_witness.data(),
                            signed_witness.data() + 32) == 0,
          "verify the witness against the independent Cardano transaction body");
    auto* fixtures = cJSON_CreateArray();
    for (const auto& token : native_signing_tokens) {
        if (token.network != "cardano")
            continue;
        auto* request = parse_json(plan);
        cJSON_AddStringToObject(request, "asset", std::string(token.symbol).c_str());
        cJSON_AddStringToObject(request, "recipient_lovelace", "2000000");
        cJSON_ReplaceItemInObjectCaseSensitive(request, "amount_atomic",
                                               cJSON_CreateString("1250000"));
        auto* input = cJSON_GetArrayItem(cJSON_GetObjectItemCaseSensitive(request, "inputs"), 0);
        cJSON_AddStringToObject(input, "token_quantity", "2500000");
        char* text = cJSON_PrintUnformatted(request);
        std::string witness;
        check(review_cardano(text, review) && review.symbol == token.symbol &&
                  review.decimals == token.decimals,
              "review registered Cardano token");
        check(sign_cardano(text, mnemonic, "", witness), "sign registered Cardano token");
        std::string rejected;
        check(!sign_cardano(text, mnemonic, "wrong", rejected),
              "token must belong to selected wallet");
        json_free(text);
        auto* fixture = cJSON_CreateObject();
        cJSON_AddItemToObject(fixture, "request", request);
        cJSON_AddStringToObject(fixture, "witness", witness.c_str());
        cJSON_AddItemToArray(fixtures, fixture);
        cJSON_ReplaceItemInObjectCaseSensitive(input, "token_quantity", cJSON_CreateString("1"));
        text = cJSON_PrintUnformatted(request);
        check(!review_cardano(text, review), "reject insufficient token inputs");
        json_free(text);
        cJSON_ReplaceItemInObjectCaseSensitive(input, "token_quantity",
                                               cJSON_CreateString("2500000"));
    }
    if (argc == 2) {
        char* text = cJSON_PrintUnformatted(fixtures);
        std::ofstream(argv[1]) << text << '\n';
        json_free(text);
    }
    cJSON_Delete(fixtures);
}
