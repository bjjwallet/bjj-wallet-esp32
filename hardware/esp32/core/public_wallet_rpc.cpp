#include "core/hardware_pin_policy.hpp"
// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/public_wallet_rpc.hpp"
#include "core/evm_signing_catalog.hpp"
#include "core/json_memory.hpp"
#include "core/rules.hpp"
#include "core/secret.hpp"
#include "cJSON.h"
#include <algorithm>
#include <cmath>
#include <charconv>
#include <cstring>
#include <memory>
#include <set>
#include <utility>

namespace airlock {
namespace {
using Json = std::unique_ptr<cJSON, decltype(&cJSON_Delete)>;
using Text = std::unique_ptr<char, decltype(&json_free)>;
const char* error_text(PublicWalletError error) {
    switch (error) {
        case PublicWalletError::ApprovalRequired:
            return "DEVICE_APPROVAL_REQUIRED";
        case PublicWalletError::PinRequired:
            return "PIN_REQUIRED";
        case PublicWalletError::PinLocked:
            return "PIN_LOCKED";
        case PublicWalletError::WalletNotFound:
            return "WALLET_NOT_FOUND";
        case PublicWalletError::Internal:
            return "INTERNAL_ERROR";
        case PublicWalletError::Busy:
            return "WALLET_BUSY";
        case PublicWalletError::GenerationChanged:
            return "WALLET_GENERATION_CHANGED";
        case PublicWalletError::InvalidData:
            return "PUBLIC_DATA_INVALID";
        case PublicWalletError::InvalidParams:
            return "INVALID_PARAMS";
        default:
            return "PUBLIC_DATA_UNAVAILABLE";
    }
}
bool unique_fields(const cJSON* object) {
    if (!cJSON_IsObject(object))
        return false;
    std::set<std::string> names;
    for (auto* item = object->child; item; item = item->next)
        if (!item->string || !names.emplace(item->string).second)
            return false;
    return true;
}
bool text(const cJSON* value, std::size_t limit, bool empty = false) {
    return cJSON_IsString(value) && value->valuestring && (empty || *value->valuestring) &&
           std::strlen(value->valuestring) <= limit && valid_text(value->valuestring);
}
bool valid_id(const cJSON* id) {
    return text(id, 128, true) || (cJSON_IsNumber(id) && std::isfinite(id->valuedouble) &&
                                   std::abs(id->valuedouble) <= 9007199254740991.0 &&
                                   std::floor(id->valuedouble) == id->valuedouble);
}
bool coin_id(std::string_view coin) {
    return !coin.empty() && coin.size() <= 64 && std::all_of(coin.begin(), coin.end(), [](char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    });
}
bool valid_wallet_id(std::string_view id) {
    return id.size() == 64 && std::all_of(id.begin(), id.end(), [](char c) {
               return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
           });
}
bool valid_wallets(const std::vector<PublicWallet>& wallets) {
    if (wallets.size() > 128)
        return false;
    std::set<std::string> ids;
    for (const auto& wallet : wallets)
        if (!valid_wallet_id(wallet.id) || wallet.name.empty() || wallet.name.size() > 256 ||
            !valid_text(wallet.name) || !ids.insert(wallet.id).second ||
            (wallet.word_count != 12 && wallet.word_count != 15 && wallet.word_count != 18 &&
             wallet.word_count != 21 && wallet.word_count != 24))
            return false;
    return true;
}
bool read_query(cJSON* params, PublicWalletQuery& query) {
    if (!unique_fields(params))
        return false;
    const auto* mode = cJSON_GetObjectItemCaseSensitive(params, "mode");
    if (!text(mode, 8))
        return false;
    const std::string_view mode_name(mode->valuestring);
    if (mode_name != "STANDARD" && mode_name != "HIDDEN")
        return false;
    query.mode = mode_name == "HIDDEN" ? PublicWalletMode::Hidden : PublicWalletMode::Standard;
    const auto* all = cJSON_GetObjectItemCaseSensitive(params, "include_all_addresses");
    if (all && !cJSON_IsBool(all))
        return false;
    query.include_all_addresses = cJSON_IsTrue(all);
    const auto* wallet = cJSON_GetObjectItemCaseSensitive(params, "wallet_id");
    if (wallet) {
        if (!text(wallet, 64) || !valid_wallet_id(wallet->valuestring))
            return false;
        query.wallet_id = wallet->valuestring;
    }
    const auto* every = cJSON_GetObjectItemCaseSensitive(params, "all_wallets");
    if (every && !cJSON_IsBool(every))
        return false;
    query.all_wallets = cJSON_IsTrue(every);
    if (query.all_wallets && !query.wallet_id.empty())
        return false;
    const auto* coins = cJSON_GetObjectItemCaseSensitive(params, "coins");
    if (!coins)
        return true; // Omitted/empty selection exports nothing.
    if (!cJSON_IsArray(coins) || cJSON_GetArraySize(coins) > 128)
        return false;
    for (const auto* item = coins->child; item; item = item->next) {
        if (!text(item, 64) || !coin_id(item->valuestring))
            return false;
        if (std::find(query.coins.begin(), query.coins.end(), item->valuestring) ==
            query.coins.end())
            query.coins.emplace_back(item->valuestring);
    }
    return true;
}
bool public_extended_key(std::string_view key) {
    const auto prefix = key.substr(0, 4);
    return prefix == "xpub" || prefix == "ypub" || prefix == "zpub" || prefix == "dgub" ||
           prefix == "dpub";
}
bool valid_accounts(const PublicWalletQuery& query, std::vector<PublicAccount>& rows) {
    if (rows.size() > 4096)
        return false;
    std::set<std::pair<std::string, std::string>> addresses, paths;
    std::set<std::pair<std::string, std::int64_t>> indices;
    std::set<std::string> found, initial;
    for (const auto& row : rows) {
        if (row.mode != query.mode || row.decimals > 255 ||
            std::find(query.coins.begin(), query.coins.end(), row.coin) == query.coins.end())
            return false;
        for (const auto* value : {&row.coin, &row.symbol, &row.name, &row.address, &row.path,
                                  &row.blockchain, &row.curve})
            if (value->empty() || !valid_text(*value))
                return false;
        if (row.address.size() < 20 || row.address.size() > 128 || row.path.size() > 128 ||
            row.path.substr(0, 2) != "m/" ||
            row.address.find_first_of(" \t\r\n") != std::string::npos)
            return false;
        if (row.address_index) {
            if (!row.xpub.empty() || row.curve == "secp256k1" ||
                *row.address_index >= 0x80000000U ||
                (!query.include_all_addresses && *row.address_index != 0))
                return false;
            if (*row.address_index == 0)
                initial.insert(row.coin);
        } else {
            if (row.curve != "secp256k1" || row.xpub.size() < 80 || row.xpub.size() > 128 ||
                !valid_text(row.xpub) || !public_extended_key(row.xpub))
                return false;
            initial.insert(row.coin);
        }
        if (!addresses.emplace(row.coin, row.address).second ||
            !paths.emplace(row.coin, row.path).second ||
            !indices.emplace(row.coin, row.address_index ? *row.address_index : -1LL).second)
            return false;
        found.insert(row.coin);
    }
    for (const auto& coin : query.coins) {
        // Older public caches can still export the accounts they contain.
        if ((coin == "ecash" || coin == "cardano") && !found.count(coin))
            continue;
        if (!found.count(coin) || !initial.count(coin))
            return false;
    }
    std::sort(rows.begin(), rows.end(), [](const auto& a, const auto& b) {
        return a.coin != b.coin ? a.coin < b.coin : a.address_index < b.address_index;
    });
    return true;
}
cJSON* accounts_json(const std::vector<PublicAccount>& rows) {
    Json array(cJSON_CreateArray(), cJSON_Delete);
    if (!array)
        return nullptr;
    for (const auto& row : rows) {
        Json item(cJSON_CreateObject(), cJSON_Delete);
        if (!item)
            return nullptr;
        for (const auto& field : {std::pair{"coin", &row.coin},
                                  {"symbol", &row.symbol},
                                  {"name", &row.name},
                                  {"address", &row.address},
                                  {"xpub", &row.xpub},
                                  {"path", &row.path},
                                  {"blockchain", &row.blockchain},
                                  {"curve", &row.curve}})
            if (!cJSON_AddStringToObject(item.get(), field.first, field.second->c_str()))
                return nullptr;
        if (!cJSON_AddNumberToObject(item.get(), "decimals", row.decimals) ||
            !cJSON_AddNumberToObject(item.get(), "is_main",
                                     row.mode == PublicWalletMode::Hidden ? 1 : 0) ||
            (row.address_index &&
             !cJSON_AddNumberToObject(item.get(), "address_index", *row.address_index)))
            return nullptr;
        if (!cJSON_AddItemToArray(array.get(), item.get()))
            return nullptr;
        item.release();
    }
    return array.release();
}
cJSON* wallet_config_json(const PublicWallet& wallet) {
    Json config(cJSON_CreateObject(), cJSON_Delete);
    if (!config || !cJSON_AddNumberToObject(config.get(), "word_count", wallet.word_count) ||
        !cJSON_AddBoolToObject(config.get(), "passphrase_enabled", wallet.passphrase_enabled) ||
        !cJSON_AddBoolToObject(config.get(), "imported", wallet.imported) ||
        !cJSON_AddBoolToObject(config.get(), "monero_enabled", wallet.monero_enabled))
        return nullptr;
    return config.release();
}
cJSON* wallets_json(const std::vector<PublicWallet>& wallets) {
    Json array(cJSON_CreateArray(), cJSON_Delete);
    if (!array)
        return nullptr;
    for (const auto& wallet : wallets) {
        Json item(cJSON_CreateObject(), cJSON_Delete),
            config(wallet_config_json(wallet), cJSON_Delete);
        if (!item || !config || !cJSON_AddItemToObject(item.get(), "config", config.get()))
            return nullptr;
        config.release();
        if (!cJSON_AddStringToObject(item.get(), "id", wallet.id.c_str()) ||
            !cJSON_AddStringToObject(item.get(), "name", wallet.name.c_str()) ||
            !cJSON_AddItemToArray(array.get(), item.get()))
            return nullptr;
        item.release();
    }
    return array.release();
}
cJSON* wallet_accounts_json(const PublicWallet& wallet, const std::vector<PublicAccount>& rows) {
    Json item(cJSON_CreateObject(), cJSON_Delete), accounts(accounts_json(rows), cJSON_Delete),
        config(wallet_config_json(wallet), cJSON_Delete);
    if (!item || !accounts || !config || !cJSON_AddItemToObject(item.get(), "config", config.get()))
        return nullptr;
    config.release();
    if (!cJSON_AddStringToObject(item.get(), "wallet_id", wallet.id.c_str()) ||
        !cJSON_AddStringToObject(item.get(), "wallet_name", wallet.name.c_str()) ||
        !cJSON_AddItemToObject(item.get(), "accounts", accounts.get()))
        return nullptr;
    accounts.release();
    return item.release();
}
std::string response(const cJSON* id, cJSON* data, const char* error = nullptr) {
    Json payload(data, cJSON_Delete);
    Json envelope(cJSON_CreateObject(), cJSON_Delete);
    Json copy(id ? cJSON_Duplicate(id, true) : cJSON_CreateNull(), cJSON_Delete);
    if (!envelope || !copy || !cJSON_AddItemToObject(envelope.get(), "id", copy.get()))
        return {};
    copy.release();
    if (!cJSON_AddStringToObject(envelope.get(), "status", error ? "error" : "success"))
        return {};
    if (error) {
        if (!cJSON_AddStringToObject(envelope.get(), "error", error))
            return {};
    } else {
        if (!payload || !cJSON_AddItemToObject(envelope.get(), "data", payload.get()))
            return {};
        payload.release();
    }
    Text encoded(cJSON_PrintUnformatted(envelope.get()), json_free);
    if (!encoded)
        return {};
    std::string result(encoded.get());
    // Preserve string values while ensuring the delimiter never occurs in JSON.
    for (auto pos = result.find(config::eof_marker); pos != std::string::npos;
         pos = result.find(config::eof_marker, pos + 6))
        result.replace(pos, 1, "\\u0023");
    return result.size() <= config::max_packet_bytes ? result : std::string{};
}
} // namespace
bool validate_public_accounts(const PublicWalletQuery& query, std::vector<PublicAccount>& rows) {
    return valid_accounts(query, rows);
}
PublicWalletRpc::PublicWalletRpc(PublicWalletService& service, PublicDeviceInfo info,
                                 PublicWalletTransport& transport)
    : service_(service), info_(std::move(info)), transport_(transport) {
    init_json();
    const auto peer = transport_.public_peer();
    connection_ = peer.connection;
    active_ = peer.connected && peer.authenticated && peer.encrypted;
}
bool PublicWalletRpc::authorized() const {
    const auto peer = transport_.public_peer();
    return active_ && peer.connected && peer.connection == connection_ && peer.authenticated &&
           peer.encrypted;
}
void PublicWalletRpc::disconnect() {
    active_ = false;
    generation_.clear();
    pin_verified_ = false;
    framing_.reset();
}
ReceiveStatus PublicWalletRpc::poll(std::uint64_t now_ms) {
    if (!authorized()) {
        disconnect();
        return ReceiveStatus::Disconnected;
    }
    const auto status = framing_.poll(now_ms);
    if (status != ReceiveStatus::Ok)
        disconnect();
    return status;
}
ReceiveStatus PublicWalletRpc::receive(std::string_view fragment, std::uint64_t now_ms) {
    if (!authorized()) {
        disconnect();
        return ReceiveStatus::Disconnected;
    }
    now_ = now_ms;
    const auto status = framing_.receive(fragment, now_ms, [&](auto request) {
        if (!authorized()) {
            active_ = false;
            return;
        }
        auto reply = dispatch(request);
        if (reply.empty() || !send_frame(reply, transport_.public_peer().mtu, [&](auto chunk) {
                return authorized() &&
                       authorization_epoch_ == service_.public_authorization_epoch() &&
                       transport_.notify_public(chunk);
            }))
            active_ = false;
        wipe(reply.data(), reply.size());
        if (syncing_) {
            syncing_ = false;
            transport_.public_sync_progress(false);
        }
    });
    if (status != ReceiveStatus::Ok || !active_) {
        disconnect();
        return status == ReceiveStatus::Ok ? ReceiveStatus::Disconnected : status;
    }
    return status;
}
std::string PublicWalletRpc::dispatch(std::string_view request) {
    Json object(parse_json(request), cJSON_Delete);
    if (!object || !unique_fields(object.get()))
        return response(nullptr, nullptr, "INVALID_REQUEST");
    const auto* id = cJSON_GetObjectItemCaseSensitive(object.get(), "id");
    if (!valid_id(id))
        return response(nullptr, nullptr, "INVALID_REQUEST");
    const auto* method = cJSON_GetObjectItemCaseSensitive(object.get(), "method");
    auto* params = cJSON_GetObjectItemCaseSensitive(object.get(), "params");
    if (!text(method, 64) || (params && !unique_fields(params)))
        return response(id, nullptr, "INVALID_PARAMS");
    const std::string_view name(method->valuestring);
    const bool receive_address = name == "monero_get_receive_address";
    const bool key_images = name == "monero_sign_key_images";
    const bool sign_monero = name == "monero_sign_transfer";
    const bool monero =
        name == "monero_get_watch_wallet" || receive_address || key_images || sign_monero;
    const bool ethereum_brz = name == "eth_sign_brz";
    const bool brz = name == "bsc_sign_brz" || ethereum_brz;
    const bool polygon = name == "polygon_sign_pol";
    const bool ethereum_native = name == "eth_sign_native";
    const bool evm_token = name == "evm_sign_token";
    const bool evm_transfer = name == "evm_sign_transfer";
    const bool evm_payment = brz || polygon || ethereum_native || evm_token || evm_transfer;
    const bool solana = name == "solana_sign_transfer";
    const bool cardano = name == "cardano_sign_transfer";
    const bool ledger = name == "ledger_sign_transfer";
    const bool local_payment = evm_payment || solana || cardano || ledger;
    PublicWalletQuery query;
    if (!monero && name != "get_accounts" && name != "get_wallets" && name != "get_device_info" &&
        name != "verify_pin" && name != "sign_tx" && !local_payment)
        return response(id, nullptr, "METHOD_NOT_FOUND");
    if (name == "get_accounts" && !read_query(params, query))
        return response(id, nullptr, "INVALID_PARAMS");
    if (name == "get_wallets" && !service_.multi_wallet_sync_supported())
        return response(id, nullptr, "METHOD_NOT_FOUND");
    if (name == "get_accounts" && (query.all_wallets || !query.wallet_id.empty()) &&
        !service_.multi_wallet_sync_supported())
        return response(id, nullptr, "INVALID_PARAMS");
    bool pin_required = false;
    const auto access = service_.public_access(connection_, pin_required);
    const auto epoch = service_.public_authorization_epoch();
    if (epoch != authorization_epoch_) {
        pin_verified_ = false;
        authorization_epoch_ = epoch;
    }
    if (access != PublicWalletError::None) {
        pin_verified_ = false;
        return response(id, nullptr, error_text(access));
    }
    if (name == "verify_pin") {
        const auto* pin = params ? cJSON_GetObjectItemCaseSensitive(params, "pin") : nullptr;
        if (!text(pin, 32) || !valid_hardware_pin(pin->valuestring))
            return response(id, nullptr, "INVALID_PARAMS");
        bool accepted = false;
        const auto verified =
            service_.verify_public_pin(connection_, pin->valuestring, now_, accepted);
        pin_verified_ = verified == PublicWalletError::None && accepted &&
                        epoch == service_.public_authorization_epoch();
        if (epoch != service_.public_authorization_epoch())
            return response(id, nullptr, "DEVICE_APPROVAL_REQUIRED");
        if (verified != PublicWalletError::None)
            return response(id, nullptr, error_text(verified));
        Json result(cJSON_CreateObject(), cJSON_Delete);
        if (!result || !cJSON_AddBoolToObject(result.get(), "accepted", accepted))
            return {};
        return response(id, result.release());
    }
    if (name == "get_device_info") {
        const auto device = transport_.read_device_info(info_);
        Json info(cJSON_CreateObject(), cJSON_Delete);
        if (!info || !cJSON_AddBoolToObject(info.get(), "pin_required", pin_required))
            return {};
        for (const auto& field : {std::pair{"name", &device.name},
                                  {"version", &device.version},
                                  {"platform", &device.platform},
                                  {"hardware", &device.hardware},
                                  {"screen", &device.screen},
                                  {"CPU", &device.cpu}})
            if (!field.second->empty() &&
                (!valid_text(*field.second) ||
                 !cJSON_AddStringToObject(info.get(), field.first, field.second->c_str())))
                return {};
        if (service_.multi_wallet_sync_supported() &&
            !cJSON_AddNumberToObject(info.get(), "wallet_sync_version", 1))
            return {};
        if (service_.brz_signing_supported() &&
            (!cJSON_AddNumberToObject(info.get(), "brz_signing_version", 1) ||
             !cJSON_AddNumberToObject(info.get(), "ethereum_brz_signing_version", 1)))
            return {};
        if (service_.utxo_signing_supported() &&
            !cJSON_AddNumberToObject(info.get(), "utxo_signing_version", 1))
            return {};
        if (service_.utxo_signing_supported() &&
            !cJSON_AddNumberToObject(info.get(), "ecash_signing_version", 1))
            return {};
        if (service_.polygon_signing_supported() &&
            !cJSON_AddNumberToObject(info.get(), "polygon_signing_version", 1))
            return {};
        if (service_.ethereum_native_signing_supported() &&
            !cJSON_AddNumberToObject(info.get(), "ethereum_native_signing_version", 1))
            return {};
        if (service_.brz_signing_supported() &&
            !cJSON_AddNumberToObject(info.get(), "evm_token_signing_version", 1))
            return {};
        if (service_.brz_signing_supported() &&
            !cJSON_AddNumberToObject(info.get(), "evm_transfer_signing_version", 1))
            return {};
        if (service_.solana_signing_supported() &&
            !cJSON_AddNumberToObject(info.get(), "solana_signing_version", 1))
            return {};
        if (service_.solana_signing_supported() &&
            !cJSON_AddNumberToObject(info.get(), "solana_token_signing_version", 1))
            return {};
        if (service_.cardano_signing_supported() &&
            !cJSON_AddNumberToObject(info.get(), "cardano_signing_version", 1))
            return {};
        if (service_.utxo_signing_supported() &&
            !cJSON_AddNumberToObject(info.get(), "ledger_signing_version", 1))
            return {};
        if (service_.monero_sync_supported() &&
            (!cJSON_AddNumberToObject(info.get(), "monero_sync_version", 1) ||
             !cJSON_AddNumberToObject(info.get(), "monero_receive_address_version", 1) ||
             !cJSON_AddStringToObject(info.get(), "monero_network", "stagenet")))
            return {};
        if (service_.monero_transfer_supported() &&
            !cJSON_AddNumberToObject(info.get(), "monero_signing_version", 1))
            return {};
        return response(id, info.release());
    }
    if (pin_required && !pin_verified_)
        return response(id, nullptr, "PIN_REQUIRED");
    if (name == "sign_tx" || local_payment) {
        const bool supported = cardano           ? service_.cardano_signing_supported()
                               : ethereum_native ? service_.ethereum_native_signing_supported()
                               : solana          ? service_.solana_signing_supported()
                               : polygon         ? service_.polygon_signing_supported()
                               : brz || evm_token || evm_transfer
                                   ? service_.brz_signing_supported()
                                   : service_.utxo_signing_supported();
        if (!supported)
            return response(id, nullptr, "UNSUPPORTED_SIGNING");
        if (!params ||
            local_payment == (cJSON_GetObjectItemCaseSensitive(params, "password") != nullptr))
            return response(id, nullptr, "INVALID_PARAMS");
        if (evm_payment) {
            if (cJSON_GetObjectItemCaseSensitive(params, "network"))
                return response(id, nullptr, "INVALID_PARAMS");
            const auto* asset = cJSON_GetObjectItemCaseSensitive(params, "asset");
            if (evm_transfer) {
                const auto* chain = cJSON_GetObjectItemCaseSensitive(params, "chain_id");
                if (!text(asset, 8) || !text(chain, 10))
                    return response(id, nullptr, "INVALID_PARAMS");
                std::uint64_t value = 0;
                const std::string_view encoded(chain->valuestring);
                const auto result =
                    std::from_chars(encoded.data(), encoded.data() + encoded.size(), value);
                if (result.ec != std::errc{} || result.ptr != encoded.data() + encoded.size() ||
                    std::to_string(value) != encoded ||
                    !evm_signing_asset(value, asset->valuestring))
                    return response(id, nullptr, "INVALID_PARAMS");
            } else if (evm_token) {
                const auto* chain = cJSON_GetObjectItemCaseSensitive(params, "chain_id");
                if (!text(asset, 3) || !text(chain, 5) ||
                    !((std::string_view(asset->valuestring) == "BNB" &&
                       std::string_view(chain->valuestring) == "1") ||
                      (std::string_view(asset->valuestring) == "BRZ" &&
                       std::string_view(chain->valuestring) == "42161")))
                    return response(id, nullptr, "INVALID_PARAMS");
            } else if (ethereum_native
                           ? (!text(asset, 3) || std::string_view(asset->valuestring) != "ETH")
                           : asset != nullptr)
                return response(id, nullptr, "INVALID_PARAMS");
            const auto* chain = cJSON_GetObjectItemCaseSensitive(params, "chain_id");
            const std::string_view expected = polygon                           ? "137"
                                              : ethereum_brz || ethereum_native ? "1"
                                                                                : "";
            if (!evm_token && !evm_transfer &&
                (expected.empty() ? chain != nullptr
                                  : (!text(chain, expected.size()) ||
                                     std::string_view(chain->valuestring) != expected)))
                return response(id, nullptr, "INVALID_PARAMS");
        }
        if (solana || cardano) {
            const auto* network = cJSON_GetObjectItemCaseSensitive(params, "network");
            const std::string_view expected = cardano ? "cardano-mainnet" : "solana-mainnet";
            if (!text(network, expected.size()) ||
                std::string_view(network->valuestring) != expected)
                return response(id, nullptr, "INVALID_PARAMS");
        }
        if (ledger) {
            const auto* network = cJSON_GetObjectItemCaseSensitive(params, "network");
            if (!text(network, 20) ||
                (std::string_view(network->valuestring) != "stellar-mainnet" &&
                 std::string_view(network->valuestring) != "tron-mainnet" &&
                 std::string_view(network->valuestring) != "ripple-mainnet" &&
                 std::string_view(network->valuestring) != "sui-mainnet" &&
                 std::string_view(network->valuestring) != "ton-mainnet"))
                return response(id, nullptr, "INVALID_PARAMS");
        }
        Text payload(cJSON_PrintUnformatted(params), json_free);
        if (!payload)
            return response(id, nullptr, "INVALID_PARAMS");
        std::string error;
        auto encoded = transport_.request_signature(payload.get(), connection_, error);
        wipe(payload.get(), std::strlen(payload.get()));
        if (!authorized() || epoch != service_.public_authorization_epoch())
            return response(id, nullptr, "DEVICE_APPROVAL_REQUIRED");
        return encoded.empty()
                   ? response(id, nullptr, error.empty() ? "SIGNING_FAILED" : error.c_str())
                   : response(id, cJSON_CreateString(encoded.c_str()));
    }
    if (monero) {
        if (!service_.monero_sync_supported() ||
            (sign_monero && !service_.monero_transfer_supported()))
            return response(id, nullptr, "MONERO_UNAVAILABLE");
        const auto* wallet = cJSON_GetObjectItemCaseSensitive(params, "wallet_id");
        const auto* mode = cJSON_GetObjectItemCaseSensitive(params, "mode");
        const auto* outputs = cJSON_GetObjectItemCaseSensitive(
            params, sign_monero ? "unsigned_txset" : "outputs_data_hex");
        if (!text(wallet, 64) || !valid_wallet_id(wallet->valuestring) || !text(mode, 8))
            return response(id, nullptr, "INVALID_PARAMS");
        const std::string_view mode_name(mode->valuestring);
        if (mode_name != "STANDARD" && mode_name != "HIDDEN")
            return response(id, nullptr, "INVALID_PARAMS");
        const bool has_archive = key_images || sign_monero;
        if (has_archive ? !cJSON_IsString(outputs) : outputs != nullptr)
            return response(id, nullptr, "INVALID_PARAMS");
        const std::string_view hex = has_archive ? outputs->valuestring : std::string_view{};
        const auto limit = sign_monero ? 2 * std::size_t{128 * 1024} : monero_max_archive_hex;
        if (hex.size() > limit)
            return response(id, nullptr, "PAYLOAD_TOO_LARGE");
        if (has_archive &&
            (hex.empty() || hex.size() % 2 || !std::all_of(hex.begin(), hex.end(), [](char c) {
                 return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
             })))
            return response(id, nullptr,
                            sign_monero ? "INVALID_MONERO_TRANSACTION" : "INVALID_OUTPUTS");
        const auto kind = sign_monero       ? MoneroOperation::SignTransfer
                          : key_images      ? MoneroOperation::KeyImages
                          : receive_address ? MoneroOperation::ReceiveAddress
                                            : MoneroOperation::WatchWallet;
        MoneroRequest operation{
            kind, mode_name == "STANDARD" ? PublicWalletMode::Standard : PublicWalletMode::Hidden,
            wallet->valuestring, hex};
        if (sign_monero) {
            const auto* network = cJSON_GetObjectItemCaseSensitive(params, "network");
            const auto* recipient = cJSON_GetObjectItemCaseSensitive(params, "recipient");
            const auto amount = [&](const char* field, std::uint64_t& result) {
                const auto* value = cJSON_GetObjectItemCaseSensitive(params, field);
                if (!text(value, 20) || value->valuestring[0] == '0')
                    return false;
                const auto* end = value->valuestring + std::strlen(value->valuestring);
                const auto parsed = std::from_chars(value->valuestring, end, result);
                return parsed.ec == std::errc{} && parsed.ptr == end && result != 0;
            };
            constexpr std::string_view base58 =
                "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
            if (cJSON_GetArraySize(params) != 7 || !text(network, 8) ||
                std::string_view(network->valuestring) != "stagenet" || !text(recipient, 95) ||
                std::strlen(recipient->valuestring) != 95 ||
                std::string_view(recipient->valuestring).find_first_not_of(base58) !=
                    std::string_view::npos ||
                !amount("amount_atomic", operation.amount) || !amount("fee_atomic", operation.fee))
                return response(id, nullptr, "INVALID_PARAMS");
            operation.recipient = recipient->valuestring;
        }
        std::string error;
        auto encoded = transport_.request_monero(operation, connection_, error);
        Json data(encoded.empty() ? nullptr : parse_json(encoded), cJSON_Delete);
        wipe(encoded.data(), encoded.size());
        if (!authorized() || epoch != service_.public_authorization_epoch())
            return response(id, nullptr, "DEVICE_APPROVAL_REQUIRED");
        if (!data || !cJSON_IsObject(data.get()))
            return response(id, nullptr, error.empty() ? "MONERO_FAILED" : error.c_str());
        if (receive_address) {
            const auto* address = cJSON_GetObjectItemCaseSensitive(data.get(), "address");
            const auto* exported_wallet = cJSON_GetObjectItemCaseSensitive(data.get(), "wallet_id");
            const auto* exported_mode = cJSON_GetObjectItemCaseSensitive(data.get(), "mode");
            const auto* network = cJSON_GetObjectItemCaseSensitive(data.get(), "network");
            if (!unique_fields(data.get()) || cJSON_GetArraySize(data.get()) != 4 ||
                !text(address, 95) || std::strlen(address->valuestring) != 95 ||
                !text(exported_wallet, 64) ||
                std::string_view(wallet->valuestring) != exported_wallet->valuestring ||
                !text(exported_mode, 8) || mode_name != exported_mode->valuestring ||
                !text(network, 8) || std::string_view(network->valuestring) != "stagenet")
                return response(id, nullptr, "MONERO_FAILED");
        }
        return response(id, data.release());
    }
    if (stale_)
        return response(id, nullptr, "WALLET_GENERATION_CHANGED");
    syncing_ = true;
    transport_.public_sync_progress(true);
    std::string generation;
    auto error = service_.public_generation(generation);
    if (error == PublicWalletError::None) {
        if (generation.empty())
            error = PublicWalletError::Unavailable;
        else if (generation_.empty())
            generation_ = generation;
        else if (generation_ != generation)
            error = PublicWalletError::GenerationChanged;
    }
    if (error == PublicWalletError::None && (name == "get_wallets" || query.all_wallets)) {
        std::vector<PublicWallet> wallets;
        error = service_.public_wallets(generation_, wallets);
        if (error == PublicWalletError::None && !valid_wallets(wallets))
            error = PublicWalletError::InvalidData;
        if (error == PublicWalletError::None && name == "get_wallets")
            return response(id, wallets_json(wallets));
        if (error == PublicWalletError::None) {
            Json groups(cJSON_CreateArray(), cJSON_Delete);
            if (!groups)
                return {};
            for (const auto& wallet : wallets) {
                auto selected = query;
                selected.wallet_id = wallet.id;
                selected.all_wallets = false;
                std::vector<PublicAccount> rows;
                error = service_.public_accounts(generation_, selected, rows);
                if (error != PublicWalletError::None)
                    break;
                if (!valid_accounts(selected, rows)) {
                    error = PublicWalletError::InvalidData;
                    break;
                }
                Json group(wallet_accounts_json(wallet, rows), cJSON_Delete);
                if (!group || !cJSON_AddItemToArray(groups.get(), group.get()))
                    return {};
                group.release();
            }
            // Publish only a complete snapshot. Never send the first wallet
            // when a later wallet is unavailable, corrupt or has changed.
            if (error == PublicWalletError::None)
                return response(id, groups.release());
        }
    } else if (error == PublicWalletError::None && name == "get_accounts") {
        std::vector<PublicAccount> rows;
        error = service_.public_accounts(generation_, query, rows);
        if (error == PublicWalletError::None) {
            if (!valid_accounts(query, rows))
                error = PublicWalletError::InvalidData;
            else
                return response(id, accounts_json(rows));
        }
    }
    if (!generation_.empty() &&
        (error == PublicWalletError::GenerationChanged || error == PublicWalletError::Busy ||
         error == PublicWalletError::Unavailable))
        stale_ = true;
    return response(id, nullptr, error_text(error));
}
} // namespace airlock
