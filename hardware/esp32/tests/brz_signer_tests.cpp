// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/brz_signer.hpp"
#include "core/json_memory.hpp"
#include "core/evm_signing_catalog.hpp"
#include "core/network_accounts.hpp"
#include "cJSON.h"
#include <fstream>
#include <cstdlib>
#include <iostream>

namespace {
using namespace airlock;
struct TestRandom : crypto::RandomSource {
    bool fill(crypto::Output output) override {
        for (std::size_t i = 0; i < output.size; ++i)
            output.data[i] = std::rand() % 256;
        return true;
    }
};
void check(bool result, const char* message) {
    if (!result) {
        std::cerr << message << '\n';
        std::exit(1);
    }
}
std::string replace(std::string value, std::string_view from, std::string_view to) {
    value.replace(value.find(from), from.size(), to);
    return value;
}
} // namespace

int main(int argc, char** argv) {
    init_json();
    TestRandom random;
    crypto::Seed seed;
    const auto mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon "
                          "abandon abandon about";
    check(crypto::wallet_core_seed(mnemonic, "", seed) == crypto::HdError::None,
          "derive public test seed");
    const std::string request = R"({
        "wallet_id":"1111111111111111111111111111111111111111111111111111111111111111",
        "mode":"STANDARD",
        "sender":"0x9858EfFD232B4033E47d90003D41EC34EcaEda94",
        "recipient":"0x1111111111111111111111111111111111111111",
        "path":"m/44'/60'/0'/0/0",
        "amount_atomic":"1250000000000000000",
        "nonce":"0x0",
        "gas_price":"0x3b9aca00",
        "gas_limit":"0xc350"
    })";
    BrzReview review;
    check(review_brz(request, review) && review.fee == "50000000000000" &&
              review.amount == "1250000000000000000",
          "review exact BRZ and maximum BNB fee");
    check(!review_brz(replace(request, "0xc350", "0x1"), review),
          "reject gas limit below intrinsic gas");
    check(!review_brz(replace(request, "0x3b9aca00", "0x0"), review), "reject zero gas price");
    check(!review_brz(replace(request, "1250000000000000000", "0"), review), "reject zero amount");
    check(!review_brz(replace(request, "m/44'/60'/", "m/44'/61'/"), review),
          "reject path on different coin type");
    check(!review_brz(replace(request, "\"nonce\"", "\"gas_price\""), review),
          "reject duplicated and missing fields");
    const crypto::Bytes bytes{seed.value.data(), seed.value.size()};
    std::string raw;
    check(!sign_brz(replace(request, "m/44'/60'/0'/0/0", "m/44'/60'/0'/0/1"), bytes, random, raw),
          "reject sender from another path");
    check(sign_brz(request, bytes, random, raw), "sign the BRZ transfer");
    std::cout << raw << '\n';

    auto* vectors = cJSON_CreateArray();
    for (const auto& asset : evm_signing_assets) {
        const std::string path = std::string(asset.path_prefix) + "0";
        crypto::DerivedKey derived;
        check(crypto::derive_key(bytes, crypto::Curve::Secp256k1, path, random, derived) ==
                  crypto::HdError::None,
              "derive fixture sender");
        std::string sender;
        check(encode_receiving_address(Network::Ethereum,
                                       {derived.public_key, derived.public_key_size},
                                       sender) == crypto::HdError::None,
              "encode fixture sender");
        auto generic = replace(request, "0x9858EfFD232B4033E47d90003D41EC34EcaEda94", sender);
        generic = replace(generic, "m/44'/60'/0'/0/0", path);
        generic.insert(generic.find('{') + 1, "\"chain_id\":\"" + std::to_string(asset.chain_id) +
                                                  "\",\"asset\":\"" + std::string(asset.symbol) +
                                                  "\",");
        if (asset.chain_id == 8453)
            generic.insert(generic.find('{') + 1, "\"extra_fee_atomic\":\"12345\",");
        check(review_brz(generic, review) && review.symbol == asset.symbol &&
                  review.decimals == asset.decimals && review.contract == asset.contract &&
                  review.network_name == asset.network_name &&
                  review.fee_symbol == asset.native_symbol,
              "review each registered asset with its exact network, decimals, contract and fee "
              "currency");
        check(sign_brz(generic, bytes, random, raw), "sign each registered EVM asset");
        check(!review_brz(replace(generic, "\"asset\":\"" + std::string(asset.symbol) + "\"",
                                  "\"asset\":\"FAKE\""),
                          review),
              "reject unregistered symbols");
        check(
            !review_brz(replace(generic, "\"chain_id\":\"" + std::to_string(asset.chain_id) + "\"",
                                "\"chain_id\":\"31337\""),
                        review),
            "reject unregistered chain IDs");
        check(!sign_brz(replace(generic,
                                "\"recipient\":\"0x1111111111111111111111111111111111111111\"",
                                "\"recipient\":\"0x0000000000000000000000000000000000000000\""),
                        bytes, random, raw),
              "reject zero destination");
        check(sign_brz(generic, bytes, random, raw), "fixture survives invalid request");
        auto* row = cJSON_CreateObject();
        cJSON_AddNumberToObject(row, "chain_id", asset.chain_id);
        cJSON_AddStringToObject(row, "symbol", std::string(asset.symbol).c_str());
        cJSON_AddStringToObject(row, "contract", std::string(asset.contract).c_str());
        cJSON_AddStringToObject(row, "sender", sender.c_str());
        cJSON_AddStringToObject(row, "path", path.c_str());
        cJSON_AddStringToObject(row, "raw", raw.c_str());
        cJSON_AddItemToArray(vectors, row);
    }
    if (argc == 2) {
        char* json = cJSON_Print(vectors);
        check(json != nullptr, "encode cross-platform fixtures");
        std::ofstream(argv[1]) << json << '\n';
        json_free(json);
    }
    cJSON_Delete(vectors);
    auto ethereum = request;
    ethereum.insert(ethereum.find('{') + 1, R"("chain_id":"1",)");
    check(review_brz(ethereum, review) && review.ethereum && review.fee == "50000000000000",
          "review Ethereum BRZ and its maximum ETH fee");
    check(!review_brz(replace(ethereum, R"("chain_id":"1")", R"("chain_id":"56")"), review),
          "reject another explicit chain ID");
    check(sign_brz(ethereum, bytes, random, raw), "sign Ethereum BRZ");
    check(raw.find("01d33fd36ec67c6ada32cf36b31e88ee190b1839") != std::string::npos,
          "bind the signed transaction to the Ethereum BRZ contract");
    std::cout << raw << '\n';
    auto polygon = request;
    polygon.insert(polygon.find('{') + 1, R"("chain_id":"137",)");
    check(review_brz(polygon, review) && review.polygon && review.fee == "50000000000000",
          "review native POL and its maximum POL fee");
    check(!review_brz(replace(polygon, R"("chain_id":"137")", R"("chain_id":"80002")"), review),
          "reject other Polygon chain IDs");
    check(!sign_brz(replace(polygon, "m/44'/60'/0'/0/0", "m/44'/60'/0'/0/1"), bytes, random, raw),
          "reject a POL sender from another path");
    check(sign_brz(polygon, bytes, random, raw), "sign native POL");
    // Destination, amount in wei, empty calldata, and EIP-155 v=309 or 310.
    check(raw.find("941111111111111111111111111111111111111111881158e460913d00008082013") !=
              std::string::npos,
          "native POL signs value and binds chain ID with empty calldata");
    std::cout << raw << '\n';

    auto native_ethereum = ethereum;
    native_ethereum.insert(native_ethereum.find('{') + 1, R"("asset":"ETH",)");
    check(review_brz(native_ethereum, review) && review.native_ethereum &&
              review.fee == "50000000000000",
          "review native ETH and its maximum ETH fee");
    check(!review_brz(replace(native_ethereum, R"("chain_id":"1")", R"("chain_id":"137")"), review),
          "reject native ETH on the Polygon signing route");
    check(sign_brz(native_ethereum, bytes, random, raw), "sign native ETH");
    // EIP-155 v=37 or 38 after the destination, value and empty calldata.
    constexpr std::string_view fields =
        "941111111111111111111111111111111111111111881158e460913d000080";
    const auto offset = raw.find(fields);
    const auto v = offset == std::string::npos
                       ? std::string_view{}
                       : std::string_view(raw).substr(offset + fields.size(), 2);
    check(v == "25" || v == "26", "native ETH signs value and binds chain ID with empty calldata");
    std::cout << raw << '\n';
}
