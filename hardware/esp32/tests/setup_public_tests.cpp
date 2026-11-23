// SPDX-License-Identifier: GPL-3.0-or-later
// Published BIP39 fixtures only. Never opens the device or its real storage.
#include "core/setup_vault.hpp"
#include "core/public_wallet_rpc.hpp"
#include "core/json_memory.hpp"
#include "cJSON.h"
#include <algorithm>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <functional>
#include <map>
#include <set>
using namespace airlock;
#define CHECK(x)                                                                                   \
    do {                                                                                           \
        if (!(x)) {                                                                                \
            std::cerr << "Public firmware check failed line " << __LINE__ << ": " #x "\n";         \
            std::exit(1);                                                                          \
        }                                                                                          \
    } while (false)
class Random final : public crypto::RandomSource {
    unsigned n = 0;

  public:
    bool fill(crypto::Output out) override {
        for (std::size_t i = 0; i < out.size; ++i)
            out.data[i] = ++n;
        return true;
    }
};
class Store final : public SetupStore {
  public:
    std::vector<std::uint8_t> blob;
    std::map<std::string, std::vector<std::uint8_t>> cache;
    std::set<std::string> disabled_monero;
    StoreRead read_monero_enabled(std::string_view id, bool& enabled) override {
        enabled = disabled_monero.count(std::string(id)) == 0;
        return enabled ? StoreRead::Missing : StoreRead::Found;
    }
    bool write_monero_enabled(std::string_view id, bool enabled) override {
        if (enabled)
            disabled_monero.erase(std::string(id));
        else
            disabled_monero.insert(std::string(id));
        return true;
    }
    StoreRead read(std::vector<std::uint8_t>& out) override {
        out = blob;
        return blob.empty() ? StoreRead::Missing : StoreRead::Found;
    }
    bool commit_if_empty(crypto::Bytes in) override {
        if (!blob.empty())
            return false;
        blob.assign(in.data, in.data + in.size);
        return true;
    }
    bool replace(crypto::Bytes expected, crypto::Bytes in) override {
        if (blob.size() != expected.size || !std::equal(blob.begin(), blob.end(), expected.data))
            return false;
        blob.assign(in.data, in.data + in.size);
        return true;
    }
    bool public_cache_enabled() const override {
        return true;
    }
    StoreRead read_public(std::string_view id, std::vector<std::uint8_t>& out) override {
        auto it = cache.find(std::string(id));
        if (it == cache.end())
            return StoreRead::Missing;
        out = it->second;
        return StoreRead::Found;
    }
    bool write_public(std::string_view id, crypto::Bytes in) override {
        cache[std::string(id)] = {in.data, in.data + in.size};
        return true;
    }
};
std::string field(const cJSON* v, const char* name) {
    const auto* x = cJSON_GetObjectItemCaseSensitive(v, name);
    CHECK(cJSON_IsString(x));
    return x->valuestring;
}
class Transport final : public PublicWalletTransport {
  public:
    PublicPeer peer{7, true, true, true, 247};
    std::string output;
    std::optional<PublicDeviceInfo> telemetry;
    std::vector<bool> progress;
    bool fail_notify = false;
    std::function<std::string(const MoneroRequest&, std::uint64_t, std::string&)> monero;
    std::function<std::string(std::string_view, std::uint64_t, std::string&)> signature;
    std::string request_signature(std::string_view payload, std::uint64_t connection,
                                  std::string& error) override {
        return signature ? signature(payload, connection, error) : std::string{};
    }
    std::string request_monero(const MoneroRequest& request, std::uint64_t connection,
                               std::string& error) override {
        return monero ? monero(request, connection, error) : std::string{};
    }
    void public_sync_progress(bool active) override {
        progress.push_back(active);
    }
    PublicDeviceInfo read_device_info(const PublicDeviceInfo& fallback) const override {
        return telemetry ? *telemetry : fallback;
    }
    PublicPeer public_peer() const override {
        return peer;
    }
    bool notify_public(std::string_view s) override {
        output += s;
        return !fail_notify;
    }
};
class Service final : public PublicWalletService {
  public:
    SetupVault& vault;
    bool multi = false, private_row = false;
    std::size_t selected = 0;
    bool approved = false, required = true, fail = false;
    unsigned tries = 0;
    std::uint64_t epoch = 0;
    bool monero = false, monero_transfer = false, polygon = false;
    bool monero_sync_supported() const override {
        return monero;
    }
    bool monero_transfer_supported() const override {
        return monero_transfer;
    }
    bool polygon_signing_supported() const override {
        return polygon;
    }
    std::uint64_t public_authorization_epoch() const override {
        return epoch;
    }
    explicit Service(SetupVault& v) : vault(v) {}
    PublicWalletError public_access(std::uint64_t, bool& pin) override {
        pin = required;
        return approved ? PublicWalletError::None : PublicWalletError::ApprovalRequired;
    }
    PublicWalletError verify_public_pin(std::uint64_t, std::string_view pin, std::uint64_t,
                                        bool& accepted) override {
        ++tries;
        accepted = pin == "Aa123456";
        return fail ? PublicWalletError::Internal : PublicWalletError::None;
    }
    bool multi_wallet_sync_supported() const override {
        return multi;
    }
    PublicWalletError public_wallets(std::string_view generation,
                                     std::vector<PublicWallet>& out) const override {
        std::string current;
        if (vault.public_wallets(current, out) != VaultError::None) {
            out.clear();
            return PublicWalletError::InvalidData;
        }
        if (current != generation) {
            out.clear();
            return PublicWalletError::GenerationChanged;
        }
        return PublicWalletError::None;
    }
    PublicWalletError public_generation(std::string& out) const override {
        if (multi) {
            std::vector<PublicWallet> wallets;
            return vault.public_wallets(out, wallets) == VaultError::None
                       ? PublicWalletError::None
                       : PublicWalletError::InvalidData;
        }
        std::vector<PublicAccount> rows;
        return vault.public_accounts(0, out, rows) == VaultError::None
                   ? PublicWalletError::None
                   : PublicWalletError::Unavailable;
    }
    PublicWalletError public_accounts(std::string_view id, const PublicWalletQuery& query,
                                      std::vector<PublicAccount>& out) const override {
        if (multi) {
            const auto error = vault.export_public_accounts(id, query, selected, out);
            if (private_row && !out.empty())
                out.front().xpub = "xprv" + std::string(107, '1');
            return error;
        }
        std::string gen;
        std::vector<PublicAccount> rows;
        if (vault.public_accounts(0, gen, rows) != VaultError::None)
            return PublicWalletError::Unavailable;
        if (gen != id)
            return PublicWalletError::GenerationChanged;
        for (const auto& row : rows)
            if (row.mode == query.mode &&
                std::find(query.coins.begin(), query.coins.end(), row.coin) != query.coins.end())
                out.push_back(row);
        return PublicWalletError::None;
    }
};
std::string exchange(PublicWalletRpc& rpc, Transport& transport, std::string request) {
    transport.output.clear();
    request += "###EOF###";
    CHECK(rpc.receive(request, 1) == ReceiveStatus::Ok);
    CHECK(transport.output.size() >= 9 &&
          transport.output.substr(transport.output.size() - 9) == "###EOF###");
    return transport.output;
}
using Reply = std::unique_ptr<cJSON, decltype(&cJSON_Delete)>;
Reply json_exchange(PublicWalletRpc& rpc, Transport& transport, std::string request) {
    const auto frame = exchange(rpc, transport, std::move(request));
    return Reply(parse_json(frame.substr(0, frame.size() - 9)), cJSON_Delete);
}
void unlock_rpc(PublicWalletRpc& rpc, Transport& transport) {
    auto reply = json_exchange(
        rpc, transport, R"({"id":"unlock","method":"verify_pin","params":{"pin":"Aa123456"}})");
    CHECK(reply && field(reply.get(), "status") == "success");
    CHECK(cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(
        cJSON_GetObjectItemCaseSensitive(reply.get(), "data"), "accepted")));
}
void multi_wallet_sync() {
    Store store;
    Random random;
    SetupVault vault(store, random);
    auto a = std::make_unique<SetupRequest>();
    a->name = "Minha wallet";
    a->imported = true;
    a->mnemonic.assign("abandon abandon abandon abandon abandon abandon abandon abandon abandon "
                       "abandon abandon about");
    a->passphrase.assign("TREZOR");
    a->pin.assign("Aa123456");
    a->transaction_phrase.assign("Public Shared Phrase");
    a->mobile_access = MobileAccess::Pin;
    auto prepared = std::make_unique<SetupCandidate>();
    CHECK(vault.prepare(*a, *prepared) == VaultError::None &&
          vault.commit(prepared->record) == VaultError::None);
    UnlockSession session;
    std::uint64_t retry = 0;
    CHECK(vault.device_unlock("Aa123456", 0, session, retry) == VaultError::None);
    auto b = std::make_unique<SetupRequest>();
    b->name = "Carteira 2";
    b->imported = true;
    b->mnemonic.assign(
        "legal winner thank year wave sausage worth useful legal winner thank yellow");
    b->passphrase.assign("Second Public Hidden");
    b->transaction_phrase.assign("Public Shared Phrase");
    CHECK(vault.prepare_additional(*b, session, *prepared) == VaultError::None &&
          vault.append(prepared->record, session) == VaultError::None);
    std::string generation;
    std::vector<PublicWallet> wallets;
    CHECK(vault.public_wallets(generation, wallets) == VaultError::None && wallets.size() == 2);
    CHECK(vault.set_monero_enabled(session, 1, false) == VaultError::None);
    CHECK(vault.public_wallets(generation, wallets) == VaultError::None &&
          wallets[0].monero_enabled && !wallets[1].monero_enabled);
    const auto first = wallets[0].id, second = wallets[1].id;
    CHECK(first != second && wallets[0].name == "Minha wallet" && wallets[1].name == "Carteira 2");
    CHECK(wallets[0].word_count == 12 && wallets[1].word_count == 12 &&
          wallets[0].passphrase_enabled && wallets[1].imported);
    // Old PUB1 caches remain readable; lazy regeneration upgrades without changing IDs.
    for (auto& entry : store.cache) {
        auto& bytes = entry.second;
        CHECK(bytes[3] == 2);
        bytes.erase(bytes.begin() + 36, bytes.begin() + 68);
        bytes[3] = 1;
        std::array<std::uint8_t, 32> hash{};
        CHECK(crypto::sha256({bytes.data(), bytes.size() - 32}, hash));
        std::copy(hash.begin(), hash.end(), bytes.end() - 32);
    }
    CHECK(vault.public_wallets(generation, wallets) == VaultError::None && wallets[0].id == first &&
          wallets[1].id == second);
    Service service(vault);
    service.multi = true;
    service.monero = true;
    Transport transport;
    PublicWalletRpc rpc(
        service, {"BJJ-Cold-Wallet-Flips", "1", "ESP32-S3", "240x320", "", "ESP-IDF fixture"},
        transport);
    auto reply = json_exchange(rpc, transport, R"({"id":"list","method":"get_wallets"})");
    CHECK(field(reply.get(), "error") == "DEVICE_APPROVAL_REQUIRED");
    service.approved = true;
    reply = json_exchange(rpc, transport, R"({"id":"list","method":"get_wallets"})");
    CHECK(field(reply.get(), "error") == "PIN_REQUIRED");
    reply = json_exchange(rpc, transport, R"({"id":"info","method":"get_device_info"})");
    CHECK(cJSON_GetObjectItemCaseSensitive(cJSON_GetObjectItemCaseSensitive(reply.get(), "data"),
                                           "wallet_sync_version")
              ->valueint == 1);
    CHECK(cJSON_GetObjectItemCaseSensitive(cJSON_GetObjectItemCaseSensitive(reply.get(), "data"),
                                           "monero_sync_version")
              ->valueint == 1);
    CHECK(cJSON_GetObjectItemCaseSensitive(cJSON_GetObjectItemCaseSensitive(reply.get(), "data"),
                                           "monero_receive_address_version")
              ->valueint == 1);
    const auto pol_request = [](std::string_view chain, std::string_view extra = {}) {
        return "{\"id\":\"pol\",\"method\":\"polygon_sign_pol\",\"params\":{\"chain_id\":\"" +
               std::string(chain) + "\"" + std::string(extra) + "}}";
    };
    CHECK(field(json_exchange(rpc, transport, pol_request("137")).get(), "error") ==
          "PIN_REQUIRED");
    service.polygon = true;
    reply = json_exchange(rpc, transport, R"({"id":"info","method":"get_device_info"})");
    const auto* pol_version = cJSON_GetObjectItemCaseSensitive(
        cJSON_GetObjectItemCaseSensitive(reply.get(), "data"), "polygon_signing_version");
    CHECK(cJSON_IsNumber(pol_version) && pol_version->valueint == 1);
    unsigned polygon_prompts = 0;
    transport.signature = [&](std::string_view payload, std::uint64_t connection,
                              std::string& error) -> std::string {
        CHECK(payload == R"({"chain_id":"137"})" && connection == 7);
        ++polygon_prompts;
        error = "USER_REJECTED";
        return {};
    };
    CHECK(field(json_exchange(rpc, transport, pol_request("1")).get(), "error") == "PIN_REQUIRED");
    CHECK(field(json_exchange(rpc, transport, pol_request("137", ",\"password\":\"test\"")).get(),
                "error") == "PIN_REQUIRED");
    CHECK(polygon_prompts == 0);
    CHECK(field(json_exchange(rpc, transport, pol_request("137")).get(), "error") ==
          "PIN_REQUIRED");
    CHECK(polygon_prompts == 0);
    const auto address_request = [&](std::string_view wallet_id) {
        return "{\"id\":\"xmr-address\",\"method\":\"monero_get_receive_address\",\"params\":{"
               "\"wallet_id\":\"" +
               std::string(wallet_id) + "\",\"mode\":\"STANDARD\"}}";
    };
    const auto watch_request = [&](std::string_view wallet_id) {
        return "{\"id\":\"xmr-1\",\"method\":\"monero_get_watch_wallet\",\"params\":{\"wallet_id\":"
               "\"" +
               std::string(wallet_id) + "\",\"mode\":\"STANDARD\"}}";
    };
    CHECK(field(json_exchange(rpc, transport, watch_request(first)).get(), "error") ==
          "PIN_REQUIRED");
    CHECK(field(json_exchange(rpc, transport, address_request(first)).get(), "error") ==
          "PIN_REQUIRED");
    unlock_rpc(rpc, transport);
    CHECK(field(json_exchange(rpc, transport, pol_request("137")).get(), "error") ==
          "USER_REJECTED");
    CHECK(polygon_prompts == 1);
    transport.signature = {};
    CHECK(transport.progress.empty());
    reply = json_exchange(rpc, transport, R"({"id":"list","method":"get_wallets"})");
    CHECK((transport.progress == std::vector<bool>{true, false}));
    const auto* list = cJSON_GetObjectItemCaseSensitive(reply.get(), "data");
    CHECK(cJSON_GetArraySize(list) == 2);
    CHECK(field(cJSON_GetArrayItem(list, 0), "id") == first &&
          field(cJSON_GetArrayItem(list, 1), "id") == second);
    CHECK(cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(
        cJSON_GetObjectItemCaseSensitive(cJSON_GetArrayItem(list, 0), "config"),
        "monero_enabled")));
    CHECK(cJSON_IsFalse(cJSON_GetObjectItemCaseSensitive(
        cJSON_GetObjectItemCaseSensitive(cJSON_GetArrayItem(list, 1), "config"),
        "monero_enabled")));
    bool reject_monero = true;
    bool force_view_key = false;
    transport.monero = [&](const MoneroRequest& request, std::uint64_t connection,
                           std::string& error) -> std::string {
        CHECK(connection == 7 && request.mode == PublicWalletMode::Standard &&
              request.operation != MoneroOperation::KeyImages);
        if (request.wallet_id != first) {
            error = "MONERO_DISABLED";
            return {};
        }
        if (reject_monero) {
            error = "USER_REJECTED";
            return {};
        }
        Credential payload;
        CHECK(vault.monero_view_qr(session, "Public Shared Phrase", 0, request.mode, payload,
                                   force_view_key ||
                                       request.operation == MoneroOperation::WatchWallet) ==
              VaultError::None);
        return std::string(payload.view());
    };
    reply = json_exchange(rpc, transport, watch_request(first));
    CHECK(field(reply.get(), "id") == "xmr-1" && field(reply.get(), "error") == "USER_REJECTED");
    reject_monero = false;
    reply = json_exchange(rpc, transport, watch_request(first));
    const auto* watch = cJSON_GetObjectItemCaseSensitive(reply.get(), "data");
    CHECK(field(reply.get(), "id") == "xmr-1" && cJSON_GetArraySize(watch) == 6 &&
          field(watch, "wallet_id") == first);
    CHECK(field(watch, "network") == "stagenet" && field(watch, "mode") == "STANDARD" &&
          field(watch, "private_view_key").size() == 64);
    CHECK(field(json_exchange(rpc, transport, watch_request(second)).get(), "error") ==
          "MONERO_DISABLED");
    const auto address_reply = json_exchange(rpc, transport, address_request(first));
    const auto* public_address = cJSON_GetObjectItemCaseSensitive(address_reply.get(), "data");
    CHECK(field(address_reply.get(), "id") == "xmr-address" &&
          cJSON_GetArraySize(public_address) == 4);
    CHECK(field(public_address, "address") == field(watch, "address") &&
          field(public_address, "network") == "stagenet");
    CHECK(field(json_exchange(rpc, transport, address_request(second)).get(), "error") ==
          "MONERO_DISABLED");
    force_view_key = true;
    const auto rejected_address = exchange(rpc, transport, address_request(first));
    CHECK(rejected_address.find("MONERO_FAILED") != rejected_address.npos &&
          rejected_address.find("private_view_key") == rejected_address.npos);
    force_view_key = false;
    const std::string recipient = field(public_address, "address");
    const auto sign_request = [&](std::string_view amount, std::string_view extra = {}) {
        return "{\"id\":\"sign-xmr\",\"method\":\"monero_sign_transfer\",\"params\":{\"wallet_id\":"
               "\"" +
               first + "\",\"mode\":\"STANDARD\",\"network\":\"stagenet\",\"recipient\":\"" +
               recipient + "\",\"unsigned_txset\":\"0123\",\"amount_atomic\":\"" +
               std::string(amount) + "\",\"fee_atomic\":\"2500000000\"" + std::string(extra) + "}}";
    };
    CHECK(field(json_exchange(rpc, transport, sign_request("1000000000000")).get(), "error") ==
          "MONERO_UNAVAILABLE");
    service.monero_transfer = true;
    auto existing = std::move(transport.monero);
    unsigned sign_calls = 0;
    transport.monero = [&](const MoneroRequest& operation, std::uint64_t connection,
                           std::string& error) -> std::string {
        CHECK(connection == 7 && operation.wallet_id == first &&
              operation.mode == PublicWalletMode::Standard);
        CHECK(operation.operation == MoneroOperation::SignTransfer &&
              operation.archive_hex == "0123");
        CHECK(operation.recipient == recipient && operation.amount == 1000000000000ull &&
              operation.fee == 2500000000ull);
        ++sign_calls;
        error = "USER_REJECTED";
        return {};
    };
    CHECK(field(json_exchange(rpc, transport, sign_request("01")).get(), "error") ==
          "INVALID_PARAMS");
    CHECK(field(json_exchange(rpc, transport, sign_request("18446744073709551616")).get(),
                "error") == "INVALID_PARAMS");
    CHECK(field(json_exchange(rpc, transport,
                              sign_request("1000000000000", ",\"private_spend_key\":\"test\""))
                    .get(),
                "error") == "INVALID_PARAMS");
    CHECK(sign_calls == 0);
    const auto refused = json_exchange(rpc, transport, sign_request("1000000000000"));
    CHECK(field(refused.get(), "id") == "sign-xmr" &&
          field(refused.get(), "error") == "USER_REJECTED");
    CHECK(sign_calls == 1);
    transport.monero = std::move(existing);
    service.monero_transfer = false;
    // Full wire-format examples generated by the real RPC, using public fixtures.
    if (const auto* output = std::getenv("AIRLOCK_MULTI_JSON_OUTPUT")) {
        std::vector<std::string> exchanges;
        auto capture = [&](const std::string& request) {
            const auto wire = exchange(rpc, transport, request);
            auto response = Reply(parse_json(wire.substr(0, wire.size() - 9)), cJSON_Delete);
            CHECK(response && field(response.get(), "status") == "success");
            exchanges.push_back("{\"request\":" + request +
                                ",\"response\":" + wire.substr(0, wire.size() - 9) + "}");
        };
        transport.telemetry = PublicDeviceInfo{"BJJ-Cold-Wallet-Flips",
                                               "1",
                                               "ESP32-S3 ES3N28P",
                                               "240x320",
                                               "42.3 °C",
                                               "ESP-IDF v5.4.2 / FreeRTOS V10.5.1"};
        capture(R"({"id":"info-1","method":"get_device_info","params":{}})");
        capture(R"({"id":"wallets-1","method":"get_wallets","params":{}})");
        std::set<std::string> coins;
        std::string record_generation;
        std::vector<PublicAccount> all_rows;
        CHECK(vault.public_accounts(0, record_generation, all_rows) == VaultError::None);
        for (const auto& row : all_rows)
            coins.insert(row.coin);
        std::string selection = "[";
        for (const auto& coin : coins) {
            if (selection.size() > 1)
                selection += ",";
            selection += "\"" + coin + "\"";
        }
        selection += "]";
        for (const auto& mode : {std::string("STANDARD"), std::string("HIDDEN")})
            capture("{\"id\":\"all-" + mode +
                    "\",\"method\":\"get_accounts\",\"params\":{\"mode\":\"" + mode +
                    "\",\"all_wallets\":true,\"include_all_addresses\":true,\"coins\":" +
                    selection + "}}");
        std::ofstream file(output);
        CHECK(file.good());
        file << "{\"example\":true,\"data_source\":\"Public BIP39 test fixtures, not the real "
                "device\",\"terminator\":\"###EOF###\",\"exchanges\":[";
        for (std::size_t i = 0; i < exchanges.size(); ++i) {
            if (i)
                file << ",";
            file << exchanges[i];
        }
        file << "]}";
        CHECK(file.good());
        transport.telemetry.reset();
    }
    std::array<std::string, 2> standard_addresses;
    for (const auto& mode : {std::string("STANDARD"), std::string("HIDDEN")}) {
        const auto request =
            "{\"id\":\"all-" + mode +
            "\",\"method\":\"get_accounts\",\"params\":{\"all_wallets\":true,\"mode\":\"" + mode +
            "\",\"include_all_addresses\":true,\"coins\":[\"bitcoin\",\"ethereum\"]}}";
        const auto wire = exchange(rpc, transport, request);
        CHECK(wire.find("Public Shared Phrase") == wire.npos &&
              wire.find("mnemonic") == wire.npos && wire.find("private_key") == wire.npos &&
              wire.find("private_view_key") == wire.npos);
        reply = Reply(parse_json(wire.substr(0, wire.size() - 9)), cJSON_Delete);
        const auto* groups = cJSON_GetObjectItemCaseSensitive(reply.get(), "data");
        CHECK(cJSON_GetArraySize(groups) == 2);
        CHECK(field(reply.get(), "id") == "all-" + mode);
        std::array<std::string, 2> addresses;
        for (unsigned i = 0; i < 2; ++i) {
            const auto* group = cJSON_GetArrayItem(groups, i);
            CHECK(field(group, "wallet_id") == wallets[i].id &&
                  field(group, "wallet_name") == wallets[i].name);
            const auto* flag = cJSON_GetObjectItemCaseSensitive(
                cJSON_GetObjectItemCaseSensitive(group, "config"), "monero_enabled");
            CHECK(cJSON_IsBool(flag) && (cJSON_IsTrue(flag) == (i == 0)));
            const auto* accounts = cJSON_GetObjectItemCaseSensitive(group, "accounts");
            CHECK(cJSON_GetArraySize(accounts) == 2);
            const auto* btc = cJSON_GetArrayItem(accounts, 0);
            CHECK(field(btc, "coin") == "bitcoin");
            addresses[i] = field(btc, "address");
            CHECK(!cJSON_GetObjectItemCaseSensitive(btc, "wallet_id"));
            CHECK(cJSON_GetObjectItemCaseSensitive(btc, "is_main")->valueint ==
                  (mode == "HIDDEN" ? 1 : 0));
            std::string record_generation;
            std::vector<PublicAccount> direct;
            CHECK(vault.public_accounts(i, record_generation, direct) == VaultError::None);
            const auto found = std::find_if(direct.begin(), direct.end(), [&](const auto& row) {
                return row.coin == "bitcoin" &&
                       (row.mode == PublicWalletMode::Hidden) == (mode == "HIDDEN");
            });
            CHECK(found != direct.end() && found->address == addresses[i] &&
                  found->xpub == field(btc, "xpub"));
            auto scoped = json_exchange(
                rpc, transport,
                "{\"id\":\"scoped\",\"method\":\"get_accounts\",\"params\":{\"wallet_id\":\"" +
                    wallets[i].id + "\",\"mode\":\"" + mode + "\",\"coins\":[\"bitcoin\"]}}");
            CHECK(
                field(cJSON_GetArrayItem(cJSON_GetObjectItemCaseSensitive(scoped.get(), "data"), 0),
                      "address") == addresses[i]);
        }
        CHECK(addresses[0] != addresses[1]);
        if (mode == "STANDARD")
            standard_addresses = addresses;
    }
    const std::string legacy =
        R"({"id":"legacy","method":"get_accounts","params":{"mode":"STANDARD","coins":["bitcoin"]}})";
    reply = json_exchange(rpc, transport, legacy);
    CHECK(field(cJSON_GetArrayItem(cJSON_GetObjectItemCaseSensitive(reply.get(), "data"), 0),
                "address") == standard_addresses[0]);
    service.selected = 1;
    reply = json_exchange(rpc, transport, legacy);
    CHECK(field(cJSON_GetArrayItem(cJSON_GetObjectItemCaseSensitive(reply.get(), "data"), 0),
                "address") == standard_addresses[1]);
    reply = json_exchange(
        rpc, transport,
        "{\"id\":\"unknown\",\"method\":\"get_accounts\",\"params\":{\"wallet_id\":\"" +
            std::string(64, '0') + "\",\"mode\":\"STANDARD\",\"coins\":[\"bitcoin\"]}}");
    CHECK(field(reply.get(), "error") == "WALLET_NOT_FOUND" &&
          !cJSON_GetObjectItemCaseSensitive(reply.get(), "data"));
    for (const auto params :
         {R"({"mode":"STANDARD","all_wallets":"true"})", R"({"mode":"STANDARD","wallet_id":17})",
          R"({"mode":"STANDARD","wallet_id":"short"})"}) {
        reply = json_exchange(rpc, transport,
                              "{\"id\":\"invalid\",\"method\":\"get_accounts\",\"params\":" +
                                  std::string(params) + "}");
        CHECK(field(reply.get(), "error") == "INVALID_PARAMS");
    }
    const std::string all =
        R"({"id":"atomic","method":"get_accounts","params":{"mode":"STANDARD","all_wallets":true,"coins":["bitcoin"]}})";
    service.private_row = true;
    reply = json_exchange(rpc, transport, all);
    CHECK(field(reply.get(), "error") == "PUBLIC_DATA_INVALID" &&
          !cJSON_GetObjectItemCaseSensitive(reply.get(), "data"));
    service.private_row = false;
    auto saved = store.cache[second];
    store.cache[second][40] ^= 1;
    reply = json_exchange(rpc, transport, all);
    CHECK(field(reply.get(), "status") == "error" &&
          !cJSON_GetObjectItemCaseSensitive(reply.get(), "data"));
    store.cache[second] = saved;
    store.cache.erase(second);
    reply = json_exchange(rpc, transport, all);
    CHECK(field(reply.get(), "status") == "error" &&
          !cJSON_GetObjectItemCaseSensitive(reply.get(), "data"));
    store.cache[second] = saved;
    PublicWalletRpc fresh(service, {"BJJ-Cold-Wallet-Flips", "", "", "", ""}, transport);
    unlock_rpc(fresh, transport);
    reply = json_exchange(fresh, transport, all);
    CHECK(cJSON_GetArraySize(cJSON_GetObjectItemCaseSensitive(reply.get(), "data")) == 2);
    CHECK(vault.change_phrase(session, "Public Shared Phrase", "New Public Phrase", 0) ==
          VaultError::None);
    std::string changed;
    CHECK(vault.public_wallets(changed, wallets) == VaultError::None && changed != generation);
    CHECK(wallets[0].id == first && wallets[1].id == second);
    CHECK(vault.verify_phrase(session, "New Public Phrase", 0) == VaultError::None &&
          vault.verify_phrase(session, "Public Shared Phrase", 0) == VaultError::WrongPhrase);
    std::string record_generation;
    std::vector<PublicAccount> unchanged;
    CHECK(vault.public_accounts(0, record_generation, unchanged) == VaultError::None);
    const auto btc = std::find_if(unchanged.begin(), unchanged.end(), [](const auto& row) {
        return row.coin == "bitcoin" && row.mode == PublicWalletMode::Standard;
    });
    CHECK(btc != unchanged.end() && btc->address == standard_addresses[0]);
    reply = json_exchange(fresh, transport, all);
    CHECK(field(reply.get(), "error") == "WALLET_GENERATION_CHANGED");
    PublicWalletRpc failed_send(service, {}, transport);
    unlock_rpc(failed_send, transport);
    transport.progress.clear();
    transport.fail_notify = true;
    CHECK(failed_send.receive(all + "###EOF###", 1) == ReceiveStatus::Disconnected);
    CHECK((transport.progress == std::vector<bool>{true, false}));
    transport.fail_notify = false;
    SetupVault reboot(store, random);
    std::vector<PublicWallet> restored;
    std::string reboot_generation;
    CHECK(reboot.public_wallets(reboot_generation, restored) == VaultError::None &&
          restored[0].id == first && restored[1].id == second);
    std::vector<WalletProfile> profiles;
    CHECK(vault.device_status(1, profiles, retry) == VaultError::None);
    CHECK(vault.delete_wallet(session, "Public Shared Phrase", 1, profiles[1].record_id) ==
          VaultError::None);
    CHECK(vault.public_wallets(changed, wallets) == VaultError::None && wallets.size() == 1 &&
          wallets[0].id == first);
    std::vector<PublicAccount> none;
    PublicWalletQuery deleted;
    deleted.wallet_id = second;
    deleted.coins = {"bitcoin"};
    CHECK(vault.export_public_accounts(changed, deleted, 0, none) ==
              PublicWalletError::WalletNotFound &&
          none.empty());
    std::cout << "Multi-wallet: both modes, distinct per-wallet keys, legacy compatibility, stable "
                 "IDs, restart, deletion and atomic failures passed\n";
}
int main(int argc, char** argv) {
    CHECK(argc == 2);
    init_json();
    multi_wallet_sync();
    Store store;
    Random random;
    SetupVault vault(store, random);
    auto request = std::make_unique<SetupRequest>();
    request->name = "Public fixture";
    request->imported = true;
    request->mnemonic.assign("abandon abandon abandon abandon abandon abandon abandon abandon "
                             "abandon abandon abandon about");
    request->passphrase.assign("TREZOR");
    request->pin.assign("Aa123456");
    request->transaction_phrase.assign("Public Phrase");
    request->mobile_access = MobileAccess::Pin;
    auto candidate = std::make_unique<SetupCandidate>();
    CHECK(vault.prepare(*request, *candidate) == VaultError::None);
    CHECK(vault.commit(candidate->record) == VaultError::None);
    std::ifstream input(argv[1]);
    std::string text{std::istreambuf_iterator<char>(input), {}};
    auto* fixtures = parse_json(text);
    CHECK(fixtures);
    std::string generation;
    std::vector<PublicAccount> rows;
    CHECK(vault.public_accounts(0, generation, rows) == VaultError::None);
    CHECK(rows.size() == 44);
    // Independent Shelley address from additional_coin_vectors.json (12 words, no passphrase).
    const auto cardano = std::find_if(rows.begin(), rows.end(), [](const auto& row) {
        return row.coin == "cardano" && row.mode == PublicWalletMode::Standard;
    });
    CHECK(cardano != rows.end() && cardano->symbol == "ADA" && cardano->decimals == 6);
    CHECK(cardano->address == "addr1qy8ac7qqy0vtulyl7wntmsxc6wex80gvcyjy33qffrhm7sh927ysx5sftuw0dlf"
                              "t05dz3c7revpf7jx0xnlcjz3g69mq4afdhv");
    CHECK(cardano->xpub.empty() && cardano->address_index == 0 &&
          cardano->path == cardano_receiving_path);
    std::string derived;
    CHECK(cardano_receiving_address("eight country switch draw meat scout mystery blade tip drift "
                                    "useless good keep usage title",
                                    "foo", derived) == crypto::HdError::None);
    CHECK(derived == "addr1qyg0jkjfylxa07p64jw78ydhjznkpt3xgwvxjmjkypv4cd9uzzwj9v0pejdf5qv6mnyvc3wz"
                     "53ac5phzynav7hrgj36qtlmyls");
    for (auto mode : {PublicWalletMode::Standard, PublicWalletMode::Hidden}) {
        const auto ecash = std::find_if(rows.begin(), rows.end(), [mode](const auto& row) {
            return row.coin == "ecash" && row.mode == mode;
        });
        CHECK(ecash != rows.end() && ecash->symbol == "XEC" && ecash->decimals == 2);
        CHECK(ecash->path == "m/44'/899'/0'/0/0" && ecash->address.rfind("ecash:", 0) == 0);
        CHECK(ecash->xpub.rfind("xpub", 0) == 0);
        auto* expected = cJSON_GetObjectItemCaseSensitive(
            fixtures, mode == PublicWalletMode::Standard ? "standard" : "hidden");
        CHECK(cJSON_IsArray(expected));
        for (auto* item = expected->child; item; item = item->next) {
            auto it = std::find_if(rows.begin(), rows.end(), [&](const auto& r) {
                return r.mode == mode && r.coin == field(item, "hardwareCoin");
            });
            CHECK(it != rows.end());
            if (it->xpub != field(item, "xpub"))
                std::cerr << "xpub mismatch " << it->coin << '\n';
            CHECK(it->xpub == field(item, "xpub"));
            CHECK(it->path == field(item, "path"));
            const auto* first =
                cJSON_GetArrayItem(cJSON_GetObjectItemCaseSensitive(item, "addresses"), 0);
            if (it->address != field(first, "address"))
                std::cerr << "address mismatch " << it->coin << " " << it->address << '\n';
            CHECK(it->address == field(first, "address"));
        }
    }
    cJSON_Delete(fixtures);
    Service service(vault);
    Transport transport;
    PublicWalletRpc rpc(service, {"BJJ-Cold-Wallet", "", "", "", ""}, transport);
    CHECK(exchange(rpc, transport, R"({"id":"kmp-1","method":"get_device_info"})")
              .find("DEVICE_APPROVAL_REQUIRED") != std::string::npos);
    service.approved = true;
    auto info = exchange(rpc, transport, R"({"id":"kmp-1","method":"get_device_info"})");
    CHECK(info.find("\"pin_required\":true") != info.npos &&
          info.find("\"id\":\"kmp-1\"") != info.npos);
    transport.telemetry = PublicDeviceInfo{"BJJ-Cold-Wallet-Flips",
                                           "1.2.3",
                                           "ESP32-S3 ES3N28P",
                                           "240x320",
                                           "42.3 °C",
                                           "ESP-IDF fixture / FreeRTOS fixture"};
    const auto metadata =
        exchange(rpc, transport, R"({"id":"metadata-1","method":"get_device_info","params":{}})");
    auto* envelope = parse_json(metadata.substr(0, metadata.size() - 9));
    CHECK(envelope);
    CHECK(field(envelope, "id") == "metadata-1" && field(envelope, "status") == "success");
    const auto* data = cJSON_GetObjectItemCaseSensitive(envelope, "data");
    CHECK(data);
    CHECK(field(data, "name") == "BJJ-Cold-Wallet-Flips" && field(data, "version") == "1.2.3");
    CHECK(field(data, "hardware") == "ESP32-S3 ES3N28P" && field(data, "screen") == "240x320");
    CHECK(field(data, "platform") == "ESP-IDF fixture / FreeRTOS fixture" &&
          field(data, "CPU") == "42.3 °C");
    CHECK(cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(data, "pin_required")));
    cJSON_Delete(envelope);
    transport.telemetry->cpu = "43.1 °C";
    CHECK(
        exchange(rpc, transport, R"({"id":"fresh","method":"get_device_info"})").find("43.1 °C") !=
        std::string::npos);
    transport.telemetry->cpu.clear();
    const auto unavailable =
        exchange(rpc, transport, R"({"id":"sensor-unavailable","method":"get_device_info"})");
    envelope = parse_json(unavailable.substr(0, unavailable.size() - 9));
    CHECK(envelope);
    CHECK(!cJSON_GetObjectItemCaseSensitive(cJSON_GetObjectItemCaseSensitive(envelope, "data"),
                                            "CPU"));
    CHECK(field(cJSON_GetObjectItemCaseSensitive(envelope, "data"), "platform") ==
          "ESP-IDF fixture / FreeRTOS fixture");
    cJSON_Delete(envelope);
    const std::string accounts =
        R"({"id":"kmp-3","method":"get_accounts","params":{"mode":"STANDARD","coins":["bitcoin"],"include_all_addresses":true}})";
    CHECK(exchange(rpc, transport, accounts).find("PIN_REQUIRED") != std::string::npos);
    CHECK(exchange(rpc, transport,
                   R"({"id":"kmp-2","method":"verify_pin","params":{"pin":"Bb000000"}})")
              .find("\"accepted\":false") != std::string::npos);
    service.fail = true;
    auto error =
        exchange(rpc, transport, R"({"id":22,"method":"verify_pin","params":{"pin":"Aa123456"}})");
    CHECK(error.find("INTERNAL_ERROR") != error.npos && error.find("accepted") == error.npos);
    service.fail = false;
    CHECK(exchange(rpc, transport,
                   R"({"id":"kmp-2","method":"verify_pin","params":{"pin":"Aa123456"}})")
              .find("\"accepted\":true") != std::string::npos);
    CHECK(exchange(rpc, transport, accounts).find("\"is_main\":0") != std::string::npos);
    CHECK(
        exchange(
            rpc, transport,
            R"({"id":"hidden","method":"get_accounts","params":{"mode":"HIDDEN","coins":["bitcoin"]}})")
            .find("\"is_main\":1") != std::string::npos);
    ++service.epoch;
    CHECK(exchange(rpc, transport, accounts).find("PIN_REQUIRED") != std::string::npos);
    ++transport.peer.connection;
    PublicWalletRpc reconnected(service, {"BJJ-Cold-Wallet", "", "", "", ""}, transport);
    CHECK(exchange(reconnected, transport, accounts).find("PIN_REQUIRED") != std::string::npos);
    service.required = false;
    CHECK(exchange(reconnected, transport, accounts).find("\"is_main\":0") != std::string::npos);
    // Firmware backoff cannot be reset by reconnect/reboot, even with a modified app.
    UnlockSession session;
    std::uint64_t retry = 0;
    CHECK(vault.device_unlock("Bb000000", 0, session, retry, true) == VaultError::WrongPin &&
          retry == 0);
    CHECK(vault.device_unlock("Bb000000", 1, session, retry, true) == VaultError::WrongPin &&
          retry == 0);
    CHECK(vault.device_unlock("Bb000000", 2, session, retry, true) == VaultError::WrongPin &&
          retry == 10000);
    SetupVault reboot(store, random);
    CHECK(reboot.device_unlock("Aa123456", 3, session, retry, true) == VaultError::Locked &&
          retry == 10000);
    CHECK(reboot.device_unlock("Bb000000", 10003, session, retry, true) == VaultError::WrongPin &&
          retry == 20000);
    CHECK(reboot.device_unlock("Aa123456", 30003, session, retry, true) == VaultError::None &&
          retry == 0);
    store.cache[generation][40] ^= 1;
    CHECK(vault.public_accounts(0, generation, rows) == VaultError::InvalidData && rows.empty());
    std::cout << "Public firmware contract, all app network vectors, authorization and persistent "
                 "PIN gate passed\n";
}
