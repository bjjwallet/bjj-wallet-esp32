// SPDX-License-Identifier: GPL-3.0-or-later
// Fixed PUBLIC fixtures. Separate process links only the original Apple binary.
#include <TrustWalletCore/TWHDWallet.h>
#include <TrustWalletCore/TWPrivateKey.h>
#include <TrustWalletCore/TWPublicKey.h>
#include <TrustWalletCore/TWSolanaAddress.h>
#include <TrustWalletCore/TWAnySigner.h>
#include <TrezorCrypto/bip32.h>
#include "cJSON.h"
#include <array>
#include <algorithm>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

namespace {
void require(bool condition) {
    if (!condition) {
        std::cerr << "Reference generation failed\n";
        std::exit(1);
    }
}
using String = std::unique_ptr<TWString, decltype(&TWStringDelete)>;
using Data = std::unique_ptr<TWData, decltype(&TWDataDelete)>;
String tw(const std::string& text) {
    return String(TWStringCreateWithUTF8Bytes(text.c_str()), TWStringDelete);
}
std::string hex(const std::uint8_t* data, std::size_t size) {
    constexpr char digits[] = "0123456789abcdef";
    std::string result;
    for (std::size_t i = 0; i < size; ++i) {
        result += digits[data[i] >> 4];
        result += digits[data[i] & 15];
    }
    return result;
}
void text(cJSON* object, const char* name, const std::string& value) {
    require(cJSON_AddStringToObject(object, name, value.c_str()));
}
void raw(cJSON* object, const char* name, TWData* value) {
    Data data(value, TWDataDelete);
    require(bool(data));
    text(object, name, hex(TWDataBytes(data.get()), TWDataSize(data.get())));
}
struct Derivation {
    const char* name;
    TWCurve curve;
    TWPublicKeyType public_type;
    const char* native_name;
    const char* path;
};
using ProtoBytes = std::vector<std::uint8_t>;
void varint(ProtoBytes& out, std::uint64_t value) {
    do {
        out.push_back((value & 127) | (value > 127 ? 128 : 0));
        value >>= 7;
    } while (value);
}
void integer(ProtoBytes& out, unsigned field, std::uint64_t value) {
    varint(out, field << 3);
    varint(out, value);
}
void bytes(ProtoBytes& out, unsigned field, const ProtoBytes& value) {
    varint(out, (field << 3) | 2);
    varint(out, value.size());
    out.insert(out.end(), value.begin(), value.end());
}
void bytes(ProtoBytes& out, unsigned field, const std::string& value) {
    bytes(out, field, ProtoBytes(value.begin(), value.end()));
}
ProtoBytes unhex(const std::string& value) {
    require(value.size() % 2 == 0);
    ProtoBytes out;
    for (std::size_t i = 0; i < value.size(); i += 2)
        out.push_back(std::stoul(value.substr(i, 2), nullptr, 16));
    return out;
}
void utxo_reference(const char* file) {
    std::ifstream stream(file);
    const std::string source((std::istreambuf_iterator<char>(stream)), {});
    std::unique_ptr<cJSON, decltype(&cJSON_Delete)> fixtures(cJSON_Parse(source.c_str()),
                                                             cJSON_Delete);
    require(cJSON_IsArray(fixtures.get()));
    const auto mnemonic = tw("abandon abandon abandon abandon abandon abandon abandon abandon "
                             "abandon abandon abandon about"),
               phrase = tw("");
    std::unique_ptr<TWHDWallet, decltype(&TWHDWalletDelete)> wallet(
        TWHDWalletCreateWithMnemonic(mnemonic.get(), phrase.get()), TWHDWalletDelete);
    require(bool(wallet));
    const auto value = [](const cJSON* object, const char* key) {
        const auto* item = cJSON_GetObjectItemCaseSensitive(object, key);
        require(cJSON_IsString(item));
        return std::string(item->valuestring);
    };
    for (auto* row = fixtures->child; row; row = row->next) {
        const auto* request = cJSON_GetObjectItemCaseSensitive(row, "request");
        const auto* asset = cJSON_GetObjectItemCaseSensitive(request, "asset");
        const auto* tx = cJSON_GetObjectItemCaseSensitive(request, "txData");
        const auto coin = value(asset, "coin");
        const auto type = coin == "bitcoin"       ? TWCoinTypeBitcoin
                          : coin == "litecoin"    ? TWCoinTypeLitecoin
                          : coin == "doge"        ? TWCoinTypeDogecoin
                          : coin == "dash"        ? TWCoinTypeDash
                          : coin == "bitcoincash" ? TWCoinTypeBitcoinCash
                                                  : TWCoinTypeECash;
        require(coin == "bitcoin" || coin == "litecoin" || coin == "doge" || coin == "dash" ||
                coin == "bitcoincash" || coin == "ecash");
        const auto path = tw(value(asset, "derivation_path"));
        std::unique_ptr<TWPrivateKey, decltype(&TWPrivateKeyDelete)> key(
            TWHDWalletGetKey(wallet.get(), type, path.get()), TWPrivateKeyDelete);
        require(bool(key));
        Data secret(TWPrivateKeyData(key.get()), TWDataDelete);
        ProtoBytes input, plan;
        integer(input, 1, (coin == "bitcoincash" || coin == "ecash") ? 65 : 1);
        integer(input, 2, std::stoull(value(tx, "amount")));
        integer(input, 3, 1);
        bytes(input, 4, value(tx, "toAddress"));
        bytes(input, 5, value(tx, "changeAddress"));
        bytes(input, 6,
              ProtoBytes(TWDataBytes(secret.get()),
                         TWDataBytes(secret.get()) + TWDataSize(secret.get())));
        integer(input, 10, static_cast<unsigned>(type));
        std::uint64_t available = 0;
        for (const auto* item = cJSON_GetObjectItemCaseSensitive(tx, "utxos")->child; item;
             item = item->next) {
            ProtoBytes outpoint, utxo;
            auto txid = unhex(value(item, "txHash"));
            std::reverse(txid.begin(), txid.end());
            bytes(outpoint, 1, txid);
            integer(outpoint, 2, cJSON_GetObjectItemCaseSensitive(item, "index")->valuedouble);
            integer(outpoint, 3, 0xffffffff);
            bytes(utxo, 1, outpoint);
            bytes(utxo, 2, unhex(value(item, "script")));
            const auto amount = std::stoull(value(item, "amount"));
            available += amount;
            integer(utxo, 3, amount);
            bytes(input, 8, utxo);
            bytes(plan, 5, utxo);
        }
        integer(plan, 1, std::stoull(value(tx, "amount")));
        integer(plan, 2, available);
        integer(plan, 3, std::stoull(value(tx, "fee")));
        integer(plan, 4, std::stoull(value(tx, "changeAmount")));
        bytes(input, 11, plan);
        Data encoded(TWDataCreateWithBytes(input.data(), input.size()), TWDataDelete);
        Data signed_data(TWAnySignerSign(encoded.get(), type), TWDataDelete);
        require(bool(signed_data));
        const auto* data = TWDataBytes(signed_data.get());
        const auto size = TWDataSize(signed_data.get());
        std::size_t pos = 0;
        auto read = [&]() {
            std::uint64_t n = 0;
            unsigned shift = 0;
            do {
                require(pos < size && shift < 64);
                const auto b = data[pos++];
                n |= std::uint64_t(b & 127) << shift;
                shift += 7;
                if (!(b & 128))
                    return n;
            } while (true);
        };
        std::string signed_tx;
        while (pos < size) {
            const auto field = read();
            if ((field & 7) == 0) {
                const auto n = read();
                require((field >> 3) != 4 || n == 0);
            } else {
                require((field & 7) == 2);
                const auto length = read();
                require(length <= size - pos);
                if ((field >> 3) == 2)
                    signed_tx = hex(data + pos, length);
                pos += length;
            }
        }
        require(!signed_tx.empty());
        text(row, "walletCoreSigned", signed_tx);
    }
    char* result = cJSON_PrintUnformatted(fixtures.get());
    require(result);
    std::cout << result << '\n';
    cJSON_free(result);
}
const Derivation derivations[] = {
    {"secp256k1", TWCurveSECP256k1, TWPublicKeyTypeSECP256k1, "secp256k1", "m"},
    {"secp256k1", TWCurveSECP256k1, TWPublicKeyTypeSECP256k1, "secp256k1", "m/0'"},
    {"secp256k1", TWCurveSECP256k1, TWPublicKeyTypeSECP256k1, "secp256k1", "m/0'/1/2'"},
    {"secp256k1", TWCurveSECP256k1, TWPublicKeyTypeSECP256k1, "secp256k1", "m/84'/0'/0'"},
    {"secp256k1", TWCurveSECP256k1, TWPublicKeyTypeSECP256k1, "secp256k1", "m/84'/0'/0'/0/0"},
    {"secp256k1", TWCurveSECP256k1, TWPublicKeyTypeSECP256k1, "secp256k1", "m/44'/60'/0'/0/0"},
    {"secp256k1", TWCurveSECP256k1, TWPublicKeyTypeSECP256k1, "secp256k1", "m/44'/60'/3'/1/9"},
    {"secp256k1", TWCurveSECP256k1, TWPublicKeyTypeSECP256k1, "secp256k1",
     "m/2147483647'/2147483647"},
    {"nist256p1", TWCurveNIST256p1, TWPublicKeyTypeNIST256p1, "nist256p1", "m"},
    {"nist256p1", TWCurveNIST256p1, TWPublicKeyTypeNIST256p1, "nist256p1", "m/44'/888'/0'/0/0"},
    {"nist256p1", TWCurveNIST256p1, TWPublicKeyTypeNIST256p1, "nist256p1", "m/44'/1024'/3'/1/7"},
    {"ed25519", TWCurveED25519, TWPublicKeyTypeED25519, "ed25519", "m"},
    {"ed25519", TWCurveED25519, TWPublicKeyTypeED25519, "ed25519", "m/44'/501'/0'"},
    {"ed25519", TWCurveED25519, TWPublicKeyTypeED25519, "ed25519", "m/44'/501'/9'"},
    {"ed25519-blake2b", TWCurveED25519Blake2bNano, TWPublicKeyTypeED25519Blake2b,
     "ed25519-blake2b-nano", "m"},
    {"ed25519-blake2b", TWCurveED25519Blake2bNano, TWPublicKeyTypeED25519Blake2b,
     "ed25519-blake2b-nano", "m/44'/165'/0'"},
    {"ed25519-blake2b", TWCurveED25519Blake2bNano, TWPublicKeyTypeED25519Blake2b,
     "ed25519-blake2b-nano", "m/44'/165'/19'"}};
void add_case(cJSON* cases, unsigned words, unsigned pattern, const std::string& passphrase) {
    std::array<std::uint8_t, 32> entropy{};
    const auto size = words / 3 * 4;
    if (pattern)
        for (unsigned i = 0; i < size; ++i)
            entropy[i] = static_cast<std::uint8_t>(i * 7 + 3);
    Data encoded(TWDataCreateWithBytes(entropy.data(), size), TWDataDelete);
    auto pass = tw(passphrase);
    std::unique_ptr<TWHDWallet, decltype(&TWHDWalletDelete)> wallet(
        TWHDWalletCreateWithEntropy(encoded.get(), pass.get()), TWHDWalletDelete);
    require(bool(wallet));
    auto* item = cJSON_CreateObject();
    require(item && cJSON_AddItemToArray(cases, item));
    String mnemonic(TWHDWalletMnemonic(wallet.get()), TWStringDelete);
    text(item, "mnemonic", TWStringUTF8Bytes(mnemonic.get()));
    text(item, "passphrase", passphrase);
    text(item, "entropy", hex(entropy.data(), size));
    raw(item, "seed", TWHDWalletSeed(wallet.get()));
    auto* keys = cJSON_AddArrayToObject(item, "keys");
    require(keys);
    Data seed(TWHDWalletSeed(wallet.get()), TWDataDelete);
    for (const auto& derivation : derivations) {
        auto path = tw(derivation.path);
        std::unique_ptr<TWPrivateKey, decltype(&TWPrivateKeyDelete)> key(
            TWHDWalletGetKeyByCurve(wallet.get(), derivation.curve, path.get()),
            TWPrivateKeyDelete);
        require(bool(key));
        std::unique_ptr<TWPublicKey, decltype(&TWPublicKeyDelete)> pub(
            TWPrivateKeyGetPublicKeyByType(key.get(), derivation.public_type), TWPublicKeyDelete);
        require(bool(pub));
        auto* row = cJSON_CreateObject();
        require(row && cJSON_AddItemToArray(keys, row));
        text(row, "curve", derivation.name);
        text(row, "path", derivation.path);
        raw(row, "private_key", TWPrivateKeyData(key.get()));
        raw(row, "public_key", TWPublicKeyData(pub.get()));
        HDNode node{};
        require(hdnode_from_seed(TWDataBytes(seed.get()), static_cast<int>(TWDataSize(seed.get())),
                                 derivation.native_name, &node) == 1);
        std::string remaining(derivation.path);
        if (remaining != "m")
            remaining.erase(0, 2);
        else
            remaining.clear();
        std::uint32_t parent = 0;
        while (!remaining.empty()) {
            const auto slash = remaining.find('/');
            auto component = remaining.substr(0, slash);
            const bool hardened = component.back() == '\'';
            if (hardened)
                component.pop_back();
            auto index = static_cast<std::uint32_t>(std::stoul(component));
            if (hardened)
                index |= 0x80000000U;
            if (slash == std::string::npos && derivation.curve == TWCurveSECP256k1)
                parent = hdnode_fingerprint(&node);
            require(hdnode_private_ckd(&node, index) == 1);
            if (slash == std::string::npos)
                remaining.clear();
            else
                remaining.erase(0, slash + 1);
        }
        text(row, "chain_code", hex(node.chain_code, 32));
        if (derivation.curve == TWCurveSECP256k1) {
            require(hdnode_fill_public_key(&node) == 0);
            for (const auto& version : {std::pair{"xpub", 0x0488b21eU}, {"zpub", 0x04b24746U}}) {
                std::array<char, XPUB_MAXLEN> out{};
                require(hdnode_serialize_public(&node, parent, version.second, out.data(),
                                                out.size()) > 0);
                text(row, version.first, out.data());
            }
        }
    }
}
} // namespace
int main(int argc, char** argv) {
    if (argc == 3 && std::string(argv[1]) == "--utxo-sign-reference") {
        utxo_reference(argv[2]);
        return 0;
    }
    if (argc == 5 && std::string(argv[1]) == "--solana-ata") {
        const auto owner = tw(argv[2]);
        auto* address = TWSolanaAddressCreateWithString(owner.get());
        require(address != nullptr);
        const auto mint = tw(argv[3]);
        const bool extensions = std::string(argv[4]) == "2022";
        require(extensions || std::string(argv[4]) == "classic");
        String result(extensions ? TWSolanaAddressToken2022Address(address, mint.get())
                                 : TWSolanaAddressDefaultTokenAddress(address, mint.get()),
                      TWStringDelete);
        require(result != nullptr);
        std::cout << TWStringUTF8Bytes(result.get()) << '\n';
        TWSolanaAddressDelete(address);
        return 0;
    }
    require(argc == 2);
    std::unique_ptr<cJSON, decltype(&cJSON_Delete)> root(cJSON_CreateObject(), cJSON_Delete);
    text(root.get(), "source",
         "Unmodified official WalletCoreCommon 4.4.2 Apple binary; separate oracle process; PUBLIC "
         "fixtures only");
    auto* cases = cJSON_AddArrayToObject(root.get(), "cases");
    require(cases);
    for (auto words : {12U, 15U, 18U, 21U, 24U})
        for (auto pattern : {0U, 1U})
            for (const auto* passphrase : {"", "TREZOR", "Public-Passphrase1!"})
                add_case(cases, words, pattern, passphrase);
    std::string utf8_edge;
    for (unsigned i = 0; i < 128; ++i)
        utf8_edge += u8"é";
    for (const auto& passphrase :
         {std::string(u8"café"), std::string(u8"café"), std::string(256, 'a'), utf8_edge})
        add_case(cases, 12, 1, passphrase);
    char* json = cJSON_Print(root.get());
    require(json);
    std::ofstream output(argv[1]);
    output << json << '\n';
    cJSON_free(json);
    require(output.good());
    std::cout << cJSON_GetArraySize(cases)
              << " public reference cases generated using the original Apple binary.\n";
}
