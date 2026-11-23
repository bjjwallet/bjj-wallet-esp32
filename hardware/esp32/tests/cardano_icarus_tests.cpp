// SPDX-License-Identifier: GPL-3.0-or-later
// Public independent vectors only. No user's wallet is opened.
#include "core/cardano_icarus.hpp"
#include <TrustWalletCore/TWAnyAddress.h>
#include "cJSON.h"
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <memory>
#include <string>

namespace {
int checks = 0;
#define CHECK(x)                                                                                   \
    do {                                                                                           \
        ++checks;                                                                                  \
        if (!(x)) {                                                                                \
            std::cerr << "Cardano check failed at line " << __LINE__ << "\n";                      \
            std::exit(1);                                                                          \
        }                                                                                          \
    } while (false)
using String = std::unique_ptr<TWString, decltype(&TWStringDelete)>;
using Wallet = std::unique_ptr<TWHDWallet, decltype(&TWHDWalletDelete)>;
using Key = std::unique_ptr<TWPrivateKey, decltype(&TWPrivateKeyDelete)>;
String tw(const char* text) {
    return String(TWStringCreateWithUTF8Bytes(text), TWStringDelete);
}
const char* field(cJSON* object, const char* name) {
    auto* v = cJSON_GetObjectItemCaseSensitive(object, name);
    CHECK(cJSON_IsString(v));
    return v->valuestring;
}
} // namespace
int main(int argc, char** argv) {
    CHECK(argc == 2);
    std::ifstream input(argv[1]);
    const std::string text{std::istreambuf_iterator<char>(input), {}};
    std::unique_ptr<cJSON, decltype(&cJSON_Delete)> vectors(cJSON_Parse(text.c_str()),
                                                            cJSON_Delete);
    CHECK(vectors);
    auto* cases = cJSON_GetObjectItemCaseSensitive(vectors.get(), "cardano_checks");
    CHECK(cJSON_IsArray(cases) && cJSON_GetArraySize(cases) == 6);
    for (int i = 0; i < cJSON_GetArraySize(cases); ++i) {
        auto* v = cJSON_GetArrayItem(cases, i);
        auto mnemonic = tw(field(v, "mnemonic")), phrase = tw(field(v, "passphrase")),
             path = tw(field(v, "path"));
        Wallet wallet(TWHDWalletCreateWithMnemonic(mnemonic.get(), phrase.get()), TWHDWalletDelete);
        CHECK(wallet);
        Key key(airlock::cardano_icarus_key(wallet.get(), field(v, "passphrase"), path.get()),
                TWPrivateKeyDelete);
        CHECK(key);
        std::unique_ptr<TWPublicKey, decltype(&TWPublicKeyDelete)> pub(
            TWPrivateKeyGetPublicKey(key.get(), TWCoinTypeCardano), TWPublicKeyDelete);
        CHECK(pub);
        std::unique_ptr<TWAnyAddress, decltype(&TWAnyAddressDelete)> addr(
            TWAnyAddressCreateWithPublicKey(pub.get(), TWCoinTypeCardano), TWAnyAddressDelete);
        CHECK(addr);
        String address(TWAnyAddressDescription(addr.get()), TWStringDelete);
        CHECK(address && std::string_view(TWStringUTF8Bytes(address.get())) == field(v, "address"));
        CHECK(TWAnyAddressIsValid(address.get(), TWCoinTypeCardano));
        for (const auto* invalid :
             {"m", "m/1852'/1815'/0'", "m/1852'/1815'/0'/0/0'", "m/1852'/1815'/0'/2/0",
              "m/1852'/1815'/0/0/0", "m/44'/1815'/0'/0/0", "m/1852'/0'/0'/0/0",
              "m/1852'/1815'/0'/0/0/0"}) {
            auto bad = tw(invalid);
            Key rejected(
                airlock::cardano_icarus_key(wallet.get(), field(v, "passphrase"), bad.get()),
                TWPrivateKeyDelete);
            CHECK(!rejected);
        }
    }
    std::cout << checks
              << " Cardano checks passed: independent Icarus addresses, UTF-8 passphrases, "
                 "account/change/index, path rejection.\n";
}
