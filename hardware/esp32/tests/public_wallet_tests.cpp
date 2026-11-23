// SPDX-License-Identifier: GPL-3.0-or-later
// Only published test vectors and a disposable database. Never opens simulator-wallets.
#include "simulator/native_wallet.hpp"
#include "core/public_wallet_rpc.hpp"
#include "core/json_memory.hpp"
#include "cJSON.h"
#include <sqlite3.h>
#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <memory>
#include <set>
#include <thread>
#include <unistd.h>

using namespace airlock;
namespace {
int checks = 0;
#define CHECK(x)                                                                                   \
    do {                                                                                           \
        ++checks;                                                                                  \
        if (!(x)) {                                                                                \
            std::cerr << "Public sync check failed at line " << __LINE__ << ": " #x "\n";          \
            std::exit(1);                                                                          \
        }                                                                                          \
    } while (false)
using Json = std::unique_ptr<cJSON, decltype(&cJSON_Delete)>;
constexpr auto coins =
    R"(["bitcoin","ethereum","tron","smartchain","ripple","doge","solana","bitcoincash","ton","avalanchec","classic","litecoin","arbitrum","polygon","sui","dash"])";
std::string read(const std::filesystem::path& path) {
    std::ifstream file(path);
    return {std::istreambuf_iterator<char>(file), {}};
}
const cJSON* item(const cJSON* value, const char* key) {
    auto* result = cJSON_GetObjectItemCaseSensitive(value, key);
    CHECK(result);
    return result;
}
std::string text(const cJSON* value, const char* key) {
    const auto* result = item(value, key);
    CHECK(cJSON_IsString(result));
    return result->valuestring;
}
WalletJob imported(std::uint64_t token) {
    WalletJob job;
    job.kind = WalletWork::Import;
    job.token = token;
    job.word_count = 12;
    // BIP39/BIP84 public mnemonic and BIP39 reference passphrase used by the app.
    job.passphrase.assign("TREZOR");
    job.password.assign("Public-Password1!");
    for (std::size_t i = 0; i < 11; ++i)
        job.words[i].assign("abandon");
    job.words[11].assign("about");
    return job;
}
void wait(NativeWallet& wallet) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(60);
    for (;;) {
        if (auto result = wallet.poll()) {
            CHECK(result->error == WalletError::None && result->accounts == 3242);
            return;
        }
        CHECK(std::chrono::steady_clock::now() < deadline);
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
}
struct Transport : PublicWalletTransport {
    PublicPeer peer{7, true, true, true, 247};
    std::vector<std::string> fragments;
    bool fail = false, disconnect_after_first = false;
    PublicPeer public_peer() const override {
        return peer;
    }
    bool notify_public(std::string_view fragment) override {
        CHECK(fragment.size() <= std::min<std::size_t>(180, peer.mtu - 3));
        if (fail)
            return false;
        fragments.emplace_back(fragment);
        if (disconnect_after_first)
            peer.connected = false;
        return true;
    }
};
PublicDeviceInfo info() {
    return {"Simulador de teste 中文", "", "macOS fixture", "240x320", ""};
}
std::string request(std::string_view mode, bool all = true) {
    return std::string(R"({"id":"opaque-sync","method":"get_accounts","params":{"mode":")") +
           std::string(mode) + R"(","include_all_addresses":)" + (all ? "true" : "false") +
           R"(,"coins":)" + coins + "}}";
}
Json exchange(PublicWalletRpc& rpc, Transport& transport, std::string_view json,
              std::size_t step = 7) {
    transport.fragments.clear();
    std::string frame(json);
    frame += config::eof_marker;
    for (std::size_t pos = 0; pos < frame.size(); pos += step)
        CHECK(rpc.receive(std::string_view(frame).substr(pos, step), 100) == ReceiveStatus::Ok);
    BleFraming decoder;
    std::vector<std::string> messages;
    for (const auto& fragment : transport.fragments)
        CHECK(decoder.receive(fragment, 100, [&](auto message) {
            messages.emplace_back(message);
        }) == ReceiveStatus::Ok);
    CHECK(messages.size() == 1);
    CHECK(messages[0].find("Public-Password1!") == std::string::npos);
    CHECK(messages[0].find("abandon abandon") == std::string::npos);
    CHECK(messages[0].find("TREZOR") == std::string::npos);
    Json result(parse_json(messages[0]), cJSON_Delete);
    CHECK(result);
    return result;
}
void error_is(const Json& response, std::string_view error) {
    CHECK(text(response.get(), "status") == "error");
    CHECK(text(response.get(), "error") == error);
    CHECK(!cJSON_GetObjectItemCaseSensitive(response.get(), "data"));
}
void verify_rows(const cJSON* response, bool hidden, bool all, const cJSON* vectors) {
    CHECK(text(response, "status") == "success");
    CHECK(text(response, "id") == "opaque-sync");
    const auto* rows = item(response, "data");
    CHECK(cJSON_IsArray(rows) && cJSON_GetArraySize(rows) == (all ? (hidden ? 313 : 43) : 16));
    std::set<std::string> networks;
    std::set<std::pair<std::string, std::string>> addresses;
    std::set<std::pair<std::string, int>> indices;
    const std::set<std::string> allowed{"coin",     "symbol",  "name",         "address",
                                        "xpub",     "path",    "blockchain",   "curve",
                                        "decimals", "is_main", "address_index"};
    std::string previous_coin;
    int previous_index = -1;
    for (const auto* row = rows->child; row; row = row->next) {
        CHECK(cJSON_IsObject(row));
        for (auto* field = row->child; field; field = field->next)
            CHECK(allowed.count(field->string) == 1);
        CHECK(cJSON_IsNumber(item(row, "is_main")) &&
              item(row, "is_main")->valuedouble == (hidden ? 1 : 0));
        const auto coin = text(row, "coin");
        const auto address = text(row, "address");
        CHECK(addresses.emplace(coin, address).second);
        networks.insert(coin);
        const cJSON* vector = nullptr;
        for (const auto* candidate = vectors->child; candidate; candidate = candidate->next)
            if (text(candidate, "hardwareCoin") == coin)
                vector = candidate;
        CHECK(vector);
        CHECK(text(row, "symbol") == text(vector, "symbol"));
        CHECK(text(row, "curve") == text(vector, "curve"));
        CHECK(item(row, "decimals")->valuedouble == item(vector, "decimals")->valuedouble);
        CHECK(text(row, "xpub") == text(vector, "xpub"));
        const auto* index_json = cJSON_GetObjectItemCaseSensitive(row, "address_index");
        const int index = index_json ? index_json->valueint : 0;
        if (text(row, "xpub").empty()) {
            CHECK(index_json && cJSON_IsNumber(index_json));
            CHECK(index >= 0 && index < (all ? (hidden ? 100 : 10) : 1));
            CHECK(indices.emplace(coin, index).second);
        } else
            CHECK(!index_json);
        CHECK(previous_coin <= coin);
        if (coin == previous_coin)
            CHECK(previous_index < index);
        previous_coin = coin;
        previous_index = index;
        for (const auto* expected = item(vector, "addresses")->child; expected;
             expected = expected->next)
            if (item(expected, "index")->valueint == index) {
                CHECK(address == text(expected, "address"));
                CHECK(text(row, "path") == text(expected, "path"));
            }
    }
    CHECK(networks.size() == 16);
    for (const auto* coin : {"solana", "ton", "sui"})
        CHECK(indices.count({coin, 0}) == 1);
}
void sql(const std::filesystem::path& path, const char* command) {
    sqlite3* database = nullptr;
    CHECK(sqlite3_open_v2(path.c_str(), &database, SQLITE_OPEN_READWRITE, nullptr) == SQLITE_OK);
    CHECK(sqlite3_exec(database, command, nullptr, nullptr, nullptr) == SQLITE_OK);
    CHECK(sqlite3_close(database) == SQLITE_OK);
}
void exercise(NativeWallet& wallet, const std::filesystem::path& directory, const cJSON* vectors) {
    Transport transport;
    PublicWalletRpc rpc(wallet, info(), transport);
    auto device = exchange(rpc, transport,
                           R"({"id":"informação 中文","method":"get_device_info","params":{}})", 1);
    CHECK(text(device.get(), "id") == "informação 中文");
    CHECK(text(item(device.get(), "data"), "name") == "Simulador de teste 中文");
    CHECK(!cJSON_GetObjectItemCaseSensitive(item(device.get(), "data"), "version"));
    const auto generation = read(directory / "CURRENT");
    const auto snapshot = directory / generation;
    const auto database = snapshot / "wallet.db";
    const auto before = read(database);
    std::string initial_generation;
    CHECK(wallet.public_generation(initial_generation) == PublicWalletError::None);
    Json requested(parse_json(coins), cJSON_Delete);
    for (const auto* coin = requested->child; coin; coin = coin->next) {
        std::vector<PublicAccount> result;
        const auto error = wallet.public_accounts(
            initial_generation, {PublicWalletMode::Standard, true, {coin->valuestring}}, result);
        if (error != PublicWalletError::None)
            std::cerr << "Public fixture query failed for " << coin->valuestring << ", code "
                      << static_cast<int>(error) << "\n";
        CHECK(error == PublicWalletError::None);
    }
    // Public export must work even when both keystores are absent.
    for (const auto* file : {"keystore.json", "keystore_24.json"})
        std::filesystem::rename(snapshot / file, snapshot / (std::string(file) + ".parked"));
    for (bool hidden : {false, true}) {
        const auto* mode_vectors = item(vectors, hidden ? "hidden" : "standard");
        for (bool all : {true, false}) {
            auto response = exchange(rpc, transport, request(hidden ? "HIDDEN" : "STANDARD", all));
            verify_rows(response.get(), hidden, all, mode_vectors);
        }
    }
    CHECK(read(database) == before);
    for (const auto* file : {"keystore.json", "keystore_24.json"})
        std::filesystem::rename(snapshot / (std::string(file) + ".parked"), snapshot / file);
    for (const char* params :
         {"null", "[]", "false", "{}", R"({"mode":"BOTH"})", R"({"mode":"hidden"})",
          R"({"mode":false})", R"({"mode":"STANDARD","include_all_addresses":1})",
          R"({"mode":"STANDARD","include_all_addresses":"true"})",
          R"({"mode":"STANDARD","coins":null})", R"({"mode":"STANDARD","coins":false})",
          R"({"mode":"STANDARD","coins":"bitcoin"})", R"({"mode":"STANDARD","coins":[1]})",
          R"({"mode":"STANDARD","coins":["../wallet.db"]})",
          R"({"mode":"STANDARD","mode":"HIDDEN","coins":["bitcoin"]})",
          R"({"mode":"STANDARD","coins":["unknown"]})"}) {
        auto response = exchange(
            rpc, transport,
            std::string(R"({"id":"invalid","method":"get_accounts","params":)") + params + "}");
        error_is(response, "INVALID_PARAMS");
    }
    std::string too_many =
        R"({"id":8,"method":"get_accounts","params":{"mode":"STANDARD","coins":[)";
    for (int i = 0; i < 129; ++i)
        too_many += (i ? ",\"bitcoin\"" : "\"bitcoin\"");
    auto excessive = exchange(rpc, transport, too_many + "]}}");
    error_is(excessive, "INVALID_PARAMS");
    for (const char* malformed : {"[]", "null", "{", "{}{}", R"({"id":"x","id":"y"})",
                                  R"({"id":"x\u0000y","method":"get_device_info","params":{}})"}) {
        auto response = exchange(rpc, transport, malformed);
        error_is(response, "INVALID_REQUEST");
    }
    auto deep = exchange(rpc, transport, std::string(17, '[') + "0" + std::string(17, ']'));
    error_is(deep, "INVALID_REQUEST");
    auto empty =
        exchange(rpc, transport,
                 R"({"id":123,"method":"get_accounts","params":{"mode":"STANDARD","coins":[]}})");
    CHECK(item(empty.get(), "id")->valueint == 123 &&
          cJSON_GetArraySize(item(empty.get(), "data")) == 0);
    auto duplicates = exchange(
        rpc, transport,
        R"({"id":"same","method":"get_accounts","params":{"mode":"STANDARD","coins":["bitcoin","bitcoin"]}})");
    CHECK(cJSON_GetArraySize(item(duplicates.get(), "data")) == 1);
    auto marker = exchange(rpc, transport,
                           R"({"id":"\u0023##EOF###","method":"get_device_info","params":{}})");
    CHECK(text(marker.get(), "id") == config::eof_marker);
    auto literal_escape =
        exchange(rpc, transport, R"({"id":"\\u0000","method":"get_device_info","params":{}})");
    CHECK(text(literal_escape.get(), "id") == "\\u0000");
    auto unsupported = exchange(rpc, transport, R"({"id":1,"method":"sign_tx","params":{}})");
    error_is(unsupported, "UNSUPPORTED_SIGNING");
    // Response fragmentation also works with the minimum ATT MTU.
    transport.peer.mtu = 23;
    auto minimum = exchange(rpc, transport, request("STANDARD"));
    verify_rows(minimum.get(), false, true, item(vectors, "standard"));
    transport.peer.mtu = 247;

    const auto backup = directory / "fixture.db.backup";
    std::filesystem::copy_file(database, backup);
    for (const char* corruption : {
             "INSERT INTO wallets_address SELECT "
             "NULL,coin,symbol,name,address,path,address_index,decimals,blockchain,curve,is_main "
             "FROM wallets_address WHERE coin='solana' AND is_main=0 AND address_index=0",
             "UPDATE wallets_address SET path='' WHERE coin='solana' AND is_main=0 AND "
             "address_index=0",
             "UPDATE wallets_address SET address='incomplete' WHERE coin='solana' AND is_main=0 "
             "AND address_index=0",
             "UPDATE wallets_address SET symbol='BAD' WHERE coin='solana' AND is_main=0 AND "
             "address_index=0",
             "DELETE FROM wallets_address WHERE coin='solana' AND is_main=0 AND address_index=0",
             "UPDATE wallets_xpub SET extended_public_key='xprv-invalid-private-record' WHERE "
             "coin='bitcoin' AND is_main=0",
             "UPDATE wallets_xpub SET address='bc1q000000000000000000000000000000000000000' WHERE "
             "coin='bitcoin' AND is_main=0",
         }) {
        sql(database, corruption);
        auto invalid = exchange(rpc, transport, request("STANDARD"));
        error_is(invalid, "PUBLIC_DATA_INVALID");
        std::filesystem::copy_file(backup, database,
                                   std::filesystem::copy_options::overwrite_existing);
    }
    // A missing DB must never be created implicitly by a public read.
    std::filesystem::rename(database, snapshot / "parked.db");
    Transport missing_transport;
    PublicWalletRpc missing(wallet, info(), missing_transport);
    auto unavailable = exchange(missing, missing_transport, request("STANDARD"));
    error_is(unavailable, "PUBLIC_DATA_UNAVAILABLE");
    CHECK(!std::filesystem::exists(database));
    std::filesystem::rename(snapshot / "parked.db", database);
    // A link pointing elsewhere is not a valid active snapshot.
    std::filesystem::rename(snapshot, directory / "parked-generation");
    std::filesystem::create_directory_symlink(directory / "parked-generation", snapshot);
    Transport symlink_transport;
    PublicWalletRpc symlink_rpc(wallet, info(), symlink_transport);
    auto symlink = exchange(symlink_rpc, symlink_transport, request("STANDARD"));
    error_is(symlink, "PUBLIC_DATA_UNAVAILABLE");
    std::filesystem::remove(snapshot);
    std::filesystem::rename(directory / "parked-generation", snapshot);

    // Replacing the generation invalidates both a pinned query token and the RPC session.
    std::string token;
    CHECK(wallet.public_generation(token) == PublicWalletError::None);
    CHECK(wallet.submit(imported(2)));
    auto busy = exchange(rpc, transport, request("HIDDEN"));
    CHECK(text(busy.get(), "status") == "error");
    wait(wallet);
    auto stale = exchange(rpc, transport, request("HIDDEN"));
    error_is(stale, "WALLET_GENERATION_CHANGED");
    std::vector<PublicAccount> output;
    CHECK(wallet.public_accounts(token, {PublicWalletMode::Hidden, true, {"solana"}}, output) ==
          PublicWalletError::GenerationChanged);
    CHECK(output.empty());
    Transport fresh_transport;
    PublicWalletRpc fresh(wallet, info(), fresh_transport);
    auto new_generation = exchange(fresh, fresh_transport, request("HIDDEN"));
    verify_rows(new_generation.get(), true, true, item(vectors, "hidden"));
}
void security(NativeWallet& wallet) {
    const auto frame = request("STANDARD") + std::string(config::eof_marker);
    for (int denied = 0; denied < 3; ++denied) {
        Transport transport;
        if (denied == 0)
            transport.peer.authenticated = false;
        if (denied == 1)
            transport.peer.encrypted = false;
        if (denied == 2)
            transport.peer.connected = false;
        PublicWalletRpc rpc(wallet, info(), transport);
        CHECK(rpc.receive(frame, 100) == ReceiveStatus::Disconnected);
        CHECK(transport.fragments.empty());
    }
    for (int event = 0; event < 6; ++event) {
        Transport transport;
        PublicWalletRpc rpc(wallet, info(), transport);
        CHECK(rpc.receive(std::string_view(frame).substr(0, 10), 100) == ReceiveStatus::Ok);
        if (event == 0)
            transport.peer.authenticated = false;
        if (event == 1)
            transport.peer.encrypted = false;
        if (event == 2)
            transport.peer.connected = false;
        if (event == 3)
            ++transport.peer.connection;
        if (event == 4)
            transport.fail = true;
        if (event == 5)
            transport.disconnect_after_first = true;
        CHECK(rpc.receive(std::string_view(frame).substr(10), 100) == ReceiveStatus::Disconnected);
        CHECK(transport.fragments.size() <= (event == 5 ? 1U : 0U));
        transport.peer = {7, true, true, true, 247};
        CHECK(rpc.receive(frame, 100) == ReceiveStatus::Disconnected);
    }
    Transport transport;
    PublicWalletRpc expired(wallet, info(), transport);
    CHECK(expired.receive("{", 100) == ReceiveStatus::Ok);
    CHECK(expired.poll(10100) == ReceiveStatus::Expired && !expired.active());
    PublicWalletRpc oversized(wallet, info(), transport);
    CHECK(oversized.receive(std::string(config::max_packet_bytes + 1, 'x'), 100) ==
          ReceiveStatus::Overflow);
    CHECK(!oversized.active() && transport.fragments.empty());
    PublicWalletTransport default_transport;
    PublicWalletRpc denied(wallet, info(), default_transport);
    CHECK(!denied.active());
}
} // namespace
int main(int argc, char** argv) {
    CHECK(argc == 2);
    init_json();
    Json vectors(parse_json(read(argv[1])), cJSON_Delete);
    CHECK(vectors);
    char temporary[] = "/tmp/bjj-airlock-public-sync-XXXXXX";
    CHECK(mkdtemp(temporary));
    const auto directory = std::filesystem::path(temporary) / "wallet";
    {
        NativeWallet wallet(directory);
        CHECK(wallet.available());
        Transport transport;
        PublicWalletRpc empty(wallet, info(), transport);
        auto device = exchange(empty, transport,
                               R"({"id":"empty-device","method":"get_device_info","params":{}})");
        CHECK(text(device.get(), "status") == "success");
        CHECK(text(item(device.get(), "data"), "name") == "Simulador de teste 中文");
        auto missing = exchange(empty, transport, request("STANDARD"));
        error_is(missing, "PUBLIC_DATA_UNAVAILABLE");
        CHECK(wallet.submit(imported(1)));
        wait(wallet);
        exercise(wallet, directory, vectors.get());
        security(wallet);
    }
    std::filesystem::remove_all(temporary);
    std::cout << checks
              << " public sync checks passed: native 4.4.2 versus app 4.4.4, 43/313 rows, session "
                 "isolation and framed RPC.\n";
}
