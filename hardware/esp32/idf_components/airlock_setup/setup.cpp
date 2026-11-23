// SPDX-License-Identifier: GPL-3.0-or-later
#include "airlock_setup.hpp"
#include "core/utxo_signer.hpp"
#include "core/brz_signer.hpp"
#include "core/cardano_signer.hpp"
#include "core/ledger_signer.hpp"
#include "core/solana_signer.hpp"
#include "core/device_service.hpp"
#include "core/monero_address.hpp"
#include "core/device_name.hpp"
#include "nvs.h"
#include "nvs_flash.h"
#include "freertos/task.h"
#include <new>
#include <algorithm>
#include <set>

namespace airlock {
namespace {
constexpr char partition[] = "wallet_nvs", key[] = "wallet_v1";
bool same_public_records(crypto::Bytes a, crypto::Bytes b) {
    const auto count = [](crypto::Bytes data) -> std::size_t {
        return data.size == wallet_record_bytes ? 1
               : data.size >= 200               ? data.data[11]
                                                : max_saved_wallets + 1;
    };
    const auto n = count(a);
    if (n != count(b) || n > max_saved_wallets)
        return false;
    const std::size_t x = a.size == wallet_record_bytes ? 0 : 168,
                      y = b.size == wallet_record_bytes ? 0 : 168;
    if (x + n * wallet_record_bytes > a.size || y + n * wallet_record_bytes > b.size)
        return false;
    for (std::size_t i = 0; i < n; ++i)
        if (!std::equal(a.data + x + i * wallet_record_bytes + 107,
                        a.data + x + i * wallet_record_bytes + 444,
                        b.data + y + i * wallet_record_bytes + 107))
            return false;
    return true;
}
void prune_public(const char* space, crypto::Bytes device) {
    const std::size_t start = device.size == wallet_record_bytes ? 0 : 168;
    if (device.size < start || (device.size != wallet_record_bytes && device.size < 200))
        return;
    const std::size_t count = device.size == wallet_record_bytes ? 1 : device.data[11];
    if (start + count * wallet_record_bytes > device.size || count > max_saved_wallets)
        return;
    std::set<std::string> retained;
    constexpr char hex[] = "0123456789abcdef";
    for (std::size_t i = 0; i < count; ++i) {
        std::array<std::uint8_t, 32> hash{};
        if (!crypto::sha256({device.data + start + i * wallet_record_bytes + 107, 337}, hash))
            return;
        std::string name = "p";
        for (unsigned j = 0; j < 7; ++j) {
            name += hex[hash[j] >> 4];
            name += hex[hash[j] & 15];
        }
        retained.insert(std::move(name));
    }
    nvs_iterator_t it = nullptr;
    std::vector<std::string> stale;
    auto status = nvs_entry_find(partition, space, NVS_TYPE_BLOB, &it);
    while (status == ESP_OK) {
        nvs_entry_info_t info{};
        if (nvs_entry_info(it, &info) != ESP_OK)
            break;
        const std::string name(info.key);
        if (name.size() == 15 && name[0] == 'p' && !retained.count(name))
            stale.push_back(name);
        status = nvs_entry_next(&it);
    }
    nvs_release_iterator(it);
    nvs_handle_t handle = 0;
    if (!stale.empty() &&
        nvs_open_from_partition(partition, space, NVS_READWRITE, &handle) == ESP_OK) {
        for (const auto& name : stale)
            nvs_erase_key(handle, name.c_str());
        nvs_commit(handle);
        nvs_close(handle);
    }
}
} // namespace
NvsSetupStore::NvsSetupStore(const char* storage_namespace) : namespace_(storage_namespace) {
    // Never auto-erase a corrupt/full wallet partition. Creation remains blocked
    // on storage errors; BLE bonds live in the separate default NVS partition.
    initialized_ = nvs_flash_init_partition(partition) == ESP_OK;
}
StoreRead NvsSetupStore::read(std::vector<std::uint8_t>& data) {
    data.clear();
    if (!initialized_)
        return StoreRead::Error;
    nvs_handle_t handle = 0;
    auto status = nvs_open_from_partition(partition, namespace_, NVS_READONLY, &handle);
    if (status == ESP_ERR_NVS_NOT_FOUND)
        return StoreRead::Missing;
    if (status != ESP_OK)
        return StoreRead::Error;
    std::size_t size = 0;
    status = nvs_get_blob(handle, key, nullptr, &size);
    if (status == ESP_ERR_NVS_NOT_FOUND) {
        nvs_close(handle);
        return StoreRead::Missing;
    }
    if (status != ESP_OK || (size < min_device_record_bytes || size > max_device_record_bytes)) {
        nvs_close(handle);
        return StoreRead::Error;
    }
    data.resize(size);
    status = nvs_get_blob(handle, key, data.data(), &size);
    nvs_close(handle);
    if (status != ESP_OK || size != data.size()) {
        data.clear();
        return StoreRead::Error;
    }
    return StoreRead::Found;
}
bool NvsSetupStore::commit_if_empty(crypto::Bytes data) {
    if (!initialized_ ||
        (data.size < min_device_record_bytes || data.size > max_device_record_bytes) || !data.data)
        return false;
    nvs_handle_t handle = 0;
    if (nvs_open_from_partition(partition, namespace_, NVS_READWRITE, &handle) != ESP_OK)
        return false;
    std::size_t size = 0;
    const auto existing = nvs_get_blob(handle, key, nullptr, &size);
    if (existing != ESP_ERR_NVS_NOT_FOUND) {
        nvs_close(handle);
        return false;
    }
    const auto written = nvs_set_blob(handle, key, data.data, data.size);
    const bool ok = written == ESP_OK && nvs_commit(handle) == ESP_OK;
    nvs_close(handle);
    if (ok)
        prune_public(namespace_, data);
    return ok;
}
bool NvsSetupStore::replace(crypto::Bytes expected, crypto::Bytes data) {
    if (!initialized_ || !data.data || data.size < min_device_record_bytes ||
        data.size > max_device_record_bytes)
        return false;
    nvs_handle_t handle = 0;
    if (nvs_open_from_partition(partition, namespace_, NVS_READWRITE, &handle) != ESP_OK)
        return false;
    std::size_t size = 0;
    auto status = nvs_get_blob(handle, key, nullptr, &size);
    if (status != ESP_OK || size != expected.size) {
        nvs_close(handle);
        return false;
    }
    std::vector<std::uint8_t> current(size);
    status = nvs_get_blob(handle, key, current.data(), &size);
    if (status != ESP_OK || !std::equal(current.begin(), current.end(), expected.data)) {
        nvs_close(handle);
        return false;
    }
    status = nvs_set_blob(handle, key, data.data, data.size);
    const bool ok = status == ESP_OK && nvs_commit(handle) == ESP_OK;
    nvs_close(handle);
    if (ok && !same_public_records(expected, data))
        prune_public(namespace_, data);
    return ok;
}
StoreRead NvsSetupStore::load_language(std::string& code) {
    code.clear();
    if (!initialized_)
        return StoreRead::Error;
    nvs_handle_t handle = 0;
    auto status = nvs_open_from_partition(partition, namespace_, NVS_READONLY, &handle);
    if (status == ESP_ERR_NVS_NOT_FOUND)
        return StoreRead::Missing;
    if (status != ESP_OK)
        return StoreRead::Error;
    char value[9]{};
    std::size_t size = sizeof value;
    status = nvs_get_str(handle, "ui_language", value, &size);
    nvs_close(handle);
    if (status == ESP_ERR_NVS_NOT_FOUND)
        return StoreRead::Missing;
    if (status != ESP_OK || size < 2 || size > sizeof value)
        return StoreRead::Error;
    code.assign(value, size - 1);
    return StoreRead::Found;
}
bool NvsSetupStore::save_language(std::string_view code) {
    if (!initialized_ || code.empty() || code.size() > 8 || code.find('\0') != code.npos)
        return false;
    const std::string value(code);
    nvs_handle_t handle = 0;
    if (nvs_open_from_partition(partition, namespace_, NVS_READWRITE, &handle) != ESP_OK)
        return false;
    const auto written = nvs_set_str(handle, "ui_language", value.c_str());
    const bool ok = written == ESP_OK && nvs_commit(handle) == ESP_OK;
    nvs_close(handle);
    std::string checked;
    return ok && load_language(checked) == StoreRead::Found && checked == value;
}
StoreRead NvsSetupStore::load_device_name(std::string& name) {
    name.clear();
    if (!initialized_)
        return StoreRead::Error;
    nvs_handle_t handle = 0;
    auto status = nvs_open_from_partition(partition, namespace_, NVS_READONLY, &handle);
    if (status == ESP_ERR_NVS_NOT_FOUND)
        return StoreRead::Missing;
    if (status != ESP_OK)
        return StoreRead::Error;
    char value[device_name_max_bytes + 1]{};
    std::size_t size = sizeof value;
    status = nvs_get_str(handle, "device_name", value, &size);
    nvs_close(handle);
    if (status == ESP_ERR_NVS_NOT_FOUND)
        return StoreRead::Missing;
    if (status != ESP_OK || size < 2 || size > sizeof value || !valid_device_name(value))
        return StoreRead::Error;
    name.assign(value, size - 1);
    return StoreRead::Found;
}
bool NvsSetupStore::save_device_name(std::string_view name) {
    if (!initialized_ || !valid_device_name(name))
        return false;
    const std::string value(name);
    nvs_handle_t handle = 0;
    if (nvs_open_from_partition(partition, namespace_, NVS_READWRITE, &handle) != ESP_OK)
        return false;
    const bool ok =
        nvs_set_str(handle, "device_name", value.c_str()) == ESP_OK && nvs_commit(handle) == ESP_OK;
    nvs_close(handle);
    std::string checked;
    return ok && load_device_name(checked) == StoreRead::Found && checked == name;
}
StoreRead NvsSetupStore::read_public(std::string_view id, std::vector<std::uint8_t>& data) {
    data.clear();
    if (!initialized_ || id.size() != 64)
        return StoreRead::Error;
    const auto name = "p" + std::string(id.substr(0, 14));
    nvs_handle_t handle = 0;
    auto status = nvs_open_from_partition(partition, namespace_, NVS_READONLY, &handle);
    if (status == ESP_ERR_NVS_NOT_FOUND)
        return StoreRead::Missing;
    if (status != ESP_OK)
        return StoreRead::Error;
    std::size_t size = 0;
    status = nvs_get_blob(handle, name.c_str(), nullptr, &size);
    if (status == ESP_ERR_NVS_NOT_FOUND) {
        nvs_close(handle);
        return StoreRead::Missing;
    }
    if (status != ESP_OK || size < 69 || size > 65536) {
        nvs_close(handle);
        return StoreRead::Error;
    }
    data.resize(size);
    status = nvs_get_blob(handle, name.c_str(), data.data(), &size);
    nvs_close(handle);
    if (status != ESP_OK || size != data.size()) {
        data.clear();
        return StoreRead::Error;
    }
    return StoreRead::Found;
}
bool NvsSetupStore::write_public(std::string_view id, crypto::Bytes data) {
    if (!initialized_ || id.size() != 64 || data.size < 69 || data.size > 65536)
        return false;
    const auto name = "p" + std::string(id.substr(0, 14));
    nvs_handle_t handle = 0;
    if (nvs_open_from_partition(partition, namespace_, NVS_READWRITE, &handle) != ESP_OK)
        return false;
    const bool ok = nvs_set_blob(handle, name.c_str(), data.data, data.size) == ESP_OK &&
                    nvs_commit(handle) == ESP_OK;
    nvs_close(handle);
    return ok;
}
StoreRead NvsSetupStore::read_monero_enabled(std::string_view id, bool& enabled) {
    enabled = true;
    if (!initialized_ || id.size() != 64)
        return StoreRead::Error;
    const auto key = "x" + std::string(id.substr(0, 14));
    nvs_handle_t handle = 0;
    auto status = nvs_open_from_partition(partition, namespace_, NVS_READONLY, &handle);
    if (status == ESP_ERR_NVS_NOT_FOUND)
        return StoreRead::Missing;
    if (status != ESP_OK)
        return StoreRead::Error;
    char stored[65]{};
    std::size_t size = sizeof stored;
    status = nvs_get_str(handle, key.c_str(), stored, &size);
    nvs_close(handle);
    if (status == ESP_ERR_NVS_NOT_FOUND)
        return StoreRead::Missing;
    if (status != ESP_OK || size != sizeof stored || std::string_view(stored, 64) != id)
        return StoreRead::Error;
    enabled = false;
    return StoreRead::Found;
}
bool NvsSetupStore::write_monero_enabled(std::string_view id, bool enabled) {
    bool old = true;
    if (read_monero_enabled(id, old) == StoreRead::Error)
        return false;
    if (old == enabled)
        return true;
    const auto key = "x" + std::string(id.substr(0, 14));
    nvs_handle_t handle = 0;
    if (nvs_open_from_partition(partition, namespace_, NVS_READWRITE, &handle) != ESP_OK)
        return false;
    const auto status = enabled ? nvs_erase_key(handle, key.c_str())
                                : nvs_set_str(handle, key.c_str(), std::string(id).c_str());
    const bool ok = status == ESP_OK && nvs_commit(handle) == ESP_OK;
    nvs_close(handle);
    return ok;
}
BoardSetupService::BoardSetupService(SetupStore& store, crypto::RandomSource& random)
    : store_(store), vault_(store, random, [] { vTaskDelay(pdMS_TO_TICKS(1)); }),
      signing_random_(random) {
    mutex_ = xSemaphoreCreateMutex();
}
namespace {
PublicWalletError public_error(VaultError error) {
    switch (error) {
        case VaultError::None:
            return PublicWalletError::None;
        case VaultError::Missing:
            return PublicWalletError::Unavailable;
        case VaultError::Locked:
            return PublicWalletError::PinLocked;
        case VaultError::InvalidData:
            return PublicWalletError::InvalidData;
        default:
            return PublicWalletError::Internal;
    }
}
} // namespace
PublicWalletError BoardSetupService::public_access(std::uint64_t connection, bool& required) {
    required = false;
    if (!connection)
        return PublicWalletError::ApprovalRequired;
    // The BLE worker may receive device_info before the user has answered.
    // UI/GAP tasks remain responsive while this worker waits for the decision.
    for (unsigned wait = 0; approved_ != connection && wait < 1200; ++wait) {
        if (rejected_ == connection || (current_ && current_ != connection) ||
            (!current_ && wait > 2))
            return PublicWalletError::ApprovalRequired;
        vTaskDelay(pdMS_TO_TICKS(50));
    }
    if (approved_ != connection || current_ != connection)
        return PublicWalletError::ApprovalRequired;
    if (!mutex_)
        return PublicWalletError::Internal;
    xSemaphoreTake(mutex_, portMAX_DELAY);
    WalletProfile profile;
    auto error = busy_ ? PublicWalletError::Busy : public_error(vault_.inspect(profile));
    if (error == PublicWalletError::None && !profile.pin_policy_current)
        error = PublicWalletError::ApprovalRequired;
    if (error == PublicWalletError::None)
        required = profile.pin_enabled && profile.mobile_access == MobileAccess::Pin;
    xSemaphoreGive(mutex_);
    return error;
}
PublicWalletError BoardSetupService::verify_public_pin(std::uint64_t connection,
                                                       std::string_view pin, std::uint64_t now,
                                                       bool& accepted) {
    accepted = false;
    bool required = false;
    const auto access = public_access(connection, required);
    if (access != PublicWalletError::None)
        return access;
    if (!required)
        return PublicWalletError::InvalidParams;
    xSemaphoreTake(mutex_, portMAX_DELAY);
    if (busy_) {
        xSemaphoreGive(mutex_);
        return PublicWalletError::Busy;
    }
    busy_ = true;
    xSemaphoreGive(mutex_);
    auto session = std::make_unique<UnlockSession>();
    std::uint64_t retry = 0;
    const auto error = vault_.device_unlock(pin, now, *session, retry, true);
    session.reset();
    xSemaphoreTake(mutex_, portMAX_DELAY);
    busy_ = false;
    xSemaphoreGive(mutex_);
    if (approved_ != connection)
        return PublicWalletError::ApprovalRequired;
    accepted = error == VaultError::None;
    return error == VaultError::WrongPin ? PublicWalletError::None : public_error(error);
}
PublicWalletError BoardSetupService::public_generation(std::string& generation) const {
    generation.clear();
    if (!mutex_)
        return PublicWalletError::Internal;
    xSemaphoreTake(mutex_, portMAX_DELAY);
    std::vector<PublicWallet> wallets;
    const auto error =
        busy_ ? PublicWalletError::Busy : public_error(vault_.public_wallets(generation, wallets));
    xSemaphoreGive(mutex_);
    return error;
}
PublicWalletError BoardSetupService::public_wallets(std::string_view generation,
                                                    std::vector<PublicWallet>& wallets) const {
    wallets.clear();
    if (!mutex_)
        return PublicWalletError::Internal;
    xSemaphoreTake(mutex_, portMAX_DELAY);
    std::string current;
    std::vector<PublicWallet> result;
    auto error =
        busy_ ? PublicWalletError::Busy : public_error(vault_.public_wallets(current, result));
    if (error == PublicWalletError::None && generation != current)
        error = PublicWalletError::GenerationChanged;
    if (error == PublicWalletError::None)
        wallets = std::move(result);
    xSemaphoreGive(mutex_);
    return error;
}
PublicWalletError BoardSetupService::public_accounts(std::string_view generation,
                                                     const PublicWalletQuery& query,
                                                     std::vector<PublicAccount>& rows) const {
    rows.clear();
    if (!mutex_)
        return PublicWalletError::Internal;
    xSemaphoreTake(mutex_, portMAX_DELAY);
    const auto error = busy_
                           ? PublicWalletError::Busy
                           : vault_.export_public_accounts(generation, query, active_wallet_, rows);
    xSemaphoreGive(mutex_);
    return error;
}
void BoardSetupService::signing_session(const UnlockSession* session) {
    if (!mutex_ || xSemaphoreTake(mutex_, 0) != pdTRUE)
        return;
    if (!session || !session->authorized)
        signing_session_.reset();
    else {
        if (!signing_session_)
            signing_session_ = std::make_unique<UnlockSession>();
        signing_session_->pin_key.value = session->pin_key.value;
        signing_session_->authorized = true;
    }
    xSemaphoreGive(mutex_);
}
bool BoardSetupService::review_signing(std::string_view payload, TransactionPrompt& prompt) {
    BrzReview brz;
    SolanaReview solana;
    CardanoReview cardano;
    LedgerReview ledger;
    const bool is_ledger = review_ledger(payload, ledger);
    const bool is_cardano = review_cardano(payload, cardano);
    const bool is_solana = !is_cardano && review_solana(payload, solana);
    if (is_ledger || is_cardano || is_solana || review_brz(payload, brz)) {
        if (!mutex_)
            return false;
        std::string generation;
        std::vector<PublicWallet> wallets;
        xSemaphoreTake(mutex_, portMAX_DELAY);
        const auto status =
            busy_ ? VaultError::Storage : vault_.public_wallets(generation, wallets);
        xSemaphoreGive(mutex_);
        const auto wallet = std::find_if(wallets.begin(), wallets.end(), [&](const auto& row) {
            return row.id == (is_ledger    ? ledger.wallet_id
                              : is_cardano ? cardano.wallet_id
                              : is_solana  ? solana.wallet_id
                                           : brz.wallet_id);
        });
        if (status != VaultError::None || wallet == wallets.end())
            return false;
        prompt.request.local_phrase = true;
        prompt.wallet_name = wallet->name;
        if (is_ledger) {
            prompt.mode = std::move(ledger.mode);
            prompt.coin = std::move(ledger.coin);
            prompt.symbol = std::move(ledger.symbol);
            prompt.fee_symbol = std::move(ledger.fee_symbol);
            prompt.amount = std::move(ledger.amount);
            prompt.recipient = std::move(ledger.recipient);
            prompt.fee = std::move(ledger.fee);
            prompt.decimals = ledger.decimals;
            return true;
        }
        if (is_cardano) {
            prompt.mode = std::move(cardano.mode);
            prompt.coin = "Cardano";
            prompt.symbol = cardano.symbol;
            prompt.fee_symbol = "ADA";
            prompt.native_amount = std::move(cardano.recipient_lovelace);
            prompt.amount = std::move(cardano.amount);
            prompt.recipient = std::move(cardano.recipient);
            prompt.fee = std::move(cardano.fee);
            prompt.change = std::move(cardano.change);
            if (prompt.change != "0")
                prompt.change_address = std::move(cardano.sender);
            prompt.decimals = cardano.decimals;
            return true;
        }
        if (is_solana) {
            prompt.mode = std::move(solana.mode);
            prompt.coin = "Solana";
            prompt.symbol = solana.symbol;
            prompt.fee_symbol = "SOL";
            prompt.amount = std::move(solana.amount);
            prompt.recipient = std::move(solana.recipient);
            prompt.fee = solana.fee_amount;
            prompt.decimals = solana.decimals;
            return true;
        }
        prompt.mode = std::move(brz.mode);
        prompt.coin = brz.network_name;
        prompt.symbol = brz.symbol;
        prompt.amount = std::move(brz.amount);
        prompt.recipient = std::move(brz.recipient);
        prompt.fee = std::move(brz.fee);
        prompt.fee_symbol = brz.fee_symbol;
        prompt.decimals = brz.decimals;
        return true;
    }
    UtxoReview review;
    if (!review_utxo(payload, review))
        return false;
    for (const auto& spec : network_accounts) {
        if (spec.network == review.network) {
            prompt.coin = spec.name;
            break;
        }
    }
    prompt.symbol = std::move(review.symbol);
    prompt.amount = std::move(review.amount);
    prompt.recipient = std::move(review.recipient);
    prompt.fee = std::move(review.fee);
    prompt.change = std::move(review.change);
    if (prompt.change != "0")
        prompt.change_address = std::move(review.address);
    prompt.decimals = review.decimals;
    return true;
}
bool BoardSetupService::monero_sync_supported() const {
#ifdef AIRLOCK_MONERO_STAGENET_SYNC
    return true;
#else
    return false;
#endif
}
bool BoardSetupService::monero_transfer_supported() const {
    return monero_sync_supported() && crypto::monero_transfer_supported();
}
std::string BoardSetupService::monero_wallet_name(std::string_view id) {
    if (!mutex_ || !monero_sync_supported())
        return {};
    std::vector<PublicWallet> wallets;
    std::string generation;
    xSemaphoreTake(mutex_, portMAX_DELAY);
    const auto status = busy_ ? VaultError::Storage : vault_.public_wallets(generation, wallets);
    xSemaphoreGive(mutex_);
    if (status != VaultError::None)
        return {};
    const auto wallet =
        std::find_if(wallets.begin(), wallets.end(), [&](const auto& row) { return row.id == id; });
    return wallet == wallets.end() || !wallet->monero_enabled ? std::string{} : wallet->name;
}
bool BoardSetupService::run_monero(const MoneroRequest& request, std::string_view phrase,
                                   std::string& encoded, std::string& error) {
    error = "MONERO_UNAVAILABLE";
    if (!mutex_ || !monero_sync_supported())
        return false;
    auto session = std::make_unique<UnlockSession>();
    xSemaphoreTake(mutex_, portMAX_DELAY);
    if (busy_ || !signing_session_ || !signing_session_->authorized) {
        xSemaphoreGive(mutex_);
        error = "SIGNING_BUSY";
        return false;
    }
    std::string generation;
    std::vector<PublicWallet> wallets;
    const auto status = vault_.public_wallets(generation, wallets);
    const auto wallet = std::find_if(wallets.begin(), wallets.end(),
                                     [&](const auto& row) { return row.id == request.wallet_id; });
    if (status != VaultError::None || wallet == wallets.end()) {
        xSemaphoreGive(mutex_);
        error = status == VaultError::None ? "WALLET_NOT_FOUND" : "MONERO_FAILED";
        return false;
    }
    if (!wallet->monero_enabled) {
        xSemaphoreGive(mutex_);
        error = "MONERO_DISABLED";
        return false;
    }
    const auto index = static_cast<std::size_t>(wallet - wallets.begin());
    const auto epoch = authorization_epoch_.load();
    session->pin_key.value = signing_session_->pin_key.value;
    session->authorized = true;
    busy_ = true;
    xSemaphoreGive(mutex_);

    const auto process = [&]() -> bool {
        error = "MONERO_FAILED";
        if (request.operation == MoneroOperation::WatchWallet ||
            request.operation == MoneroOperation::ReceiveAddress) {
            Credential bundle;
            const bool include_view_key = request.operation == MoneroOperation::WatchWallet;
            const auto result = vault_.monero_view_qr(*session, phrase, index, request.mode, bundle,
                                                      include_view_key);
            if (result != VaultError::None) {
                if (result == VaultError::WrongPhrase)
                    error = "INVALID_TRANSACTION_PHRASE";
                return false;
            }
            encoded.assign(bundle.view());
            return true;
        }
        crypto::Entropy entropy;
        Credential pass, words;
        crypto::Seed seed;
        const auto result = vault_.recover(*session, phrase, entropy, pass, index);
        if (result != VaultError::None) {
            if (result == VaultError::WrongPhrase)
                error = "INVALID_TRANSACTION_PHRASE";
            return false;
        }
        if (crypto::mnemonic_from_entropy({entropy.data.value.data(), entropy.size}, words) !=
                crypto::HdError::None ||
            crypto::wallet_core_seed(words.view(),
                                     request.mode == PublicWalletMode::Hidden ? pass.view()
                                                                              : std::string_view{},
                                     seed) != crypto::HdError::None)
            return false;
        if (request.archive_hex.size() > monero_max_archive_hex) {
            error = "PAYLOAD_TOO_LARGE";
            return false;
        }
        if (request.archive_hex.empty() || request.archive_hex.size() % 2) {
            error = "INVALID_OUTPUTS";
            return false;
        }
        std::vector<std::uint8_t> archive(request.archive_hex.size() / 2);
        constexpr std::string_view alphabet = "0123456789abcdefABCDEF";
        for (std::size_t i = 0; i < archive.size(); ++i) {
            auto high = alphabet.find(request.archive_hex[2 * i]),
                 low = alphabet.find(request.archive_hex[2 * i + 1]);
            if (high == alphabet.npos || low == alphabet.npos) {
                error = "INVALID_OUTPUTS";
                return false;
            }
            if (high >= 16)
                high -= 6;
            if (low >= 16)
                low -= 6;
            archive[i] = static_cast<std::uint8_t>(high * 16 + low);
        }
        if (request.operation == MoneroOperation::SignTransfer) {
            crypto::MoneroSignedTransfer transfer;
            const auto result = crypto::sign_monero_transfer_archive(
                seed, {archive.data(), archive.size()}, request.recipient, request.amount,
                request.fee, signing_random_, transfer, [] { vTaskDelay(1); });
            if (result != crypto::MoneroTransferError::None) {
                switch (result) {
                    case crypto::MoneroTransferError::InvalidArchive:
                        error = "INVALID_MONERO_TRANSACTION";
                        break;
                    case crypto::MoneroTransferError::Unsupported:
                        error = "MONERO_TRANSACTION_UNSUPPORTED";
                        break;
                    case crypto::MoneroTransferError::ReviewMismatch:
                        error = "MONERO_REVIEW_MISMATCH";
                        break;
                    case crypto::MoneroTransferError::InputNotOwned:
                        error = "MONERO_INPUT_NOT_OWNED";
                        break;
                    case crypto::MoneroTransferError::Capacity:
                        error = "PAYLOAD_TOO_LARGE";
                        break;
                    default:
                        error = "SIGNING_FAILED";
                        break;
                }
                return false;
            }
            const auto append = [&](const auto& bytes) {
                for (const auto byte : bytes) {
                    encoded += alphabet[byte >> 4];
                    encoded += alphabet[byte & 15];
                }
            };
            encoded = "{\"wallet_id\":\"" + wallet->id + "\",\"mode\":\"" +
                      (request.mode == PublicWalletMode::Standard ? "STANDARD" : "HIDDEN") +
                      "\",\"network\":\"stagenet\",\"txid\":\"";
            append(transfer.txid);
            encoded += "\",\"tx_hex\":\"";
            append(transfer.transaction);
            encoded += "\"}";
            return true;
        }
        std::vector<crypto::MoneroSignedKeyImage> proofs;
        const auto signed_result = crypto::sign_monero_output_archive(
            seed, {archive.data(), archive.size()}, signing_random_, proofs, [] { vTaskDelay(1); });
        if (signed_result != crypto::MoneroKeyImageError::None) {
            if (signed_result == crypto::MoneroKeyImageError::TooManyOutputs)
                error = "PAYLOAD_TOO_LARGE";
            else if (signed_result == crypto::MoneroKeyImageError::InvalidOutputs)
                error = "INVALID_OUTPUTS";
            return false;
        }
        const auto append_hex = [&](const auto& bytes) {
            for (auto byte : bytes) {
                encoded += alphabet[byte >> 4];
                encoded += alphabet[byte & 15];
            }
        };
        encoded = "{\"wallet_id\":\"" + wallet->id + "\",\"mode\":\"" +
                  (request.mode == PublicWalletMode::Standard ? "STANDARD" : "HIDDEN") +
                  "\",\"offset\":0,\"signed_key_images\":[";
        for (std::size_t i = 0; i < proofs.size(); ++i) {
            if (i)
                encoded += ',';
            encoded += "{\"key_image\":\"";
            append_hex(proofs[i].key_image);
            encoded += "\",\"signature\":\"";
            append_hex(proofs[i].signature);
            encoded += "\"}";
        }
        encoded += "]}";
        return true;
    };
    const auto ok = process();
    session.reset();
    xSemaphoreTake(mutex_, portMAX_DELAY);
    busy_ = false;
    const bool authorized =
        signing_session_ && signing_session_->authorized && epoch == authorization_epoch_;
    xSemaphoreGive(mutex_);
    if (!ok || !authorized) {
        wipe(encoded.data(), encoded.size());
        encoded.clear();
        if (!authorized)
            error = "DEVICE_DISCONNECTED";
    }
    return ok && authorized;
}
bool BoardSetupService::sign_transaction(std::string_view payload, std::string_view phrase,
                                         std::string& encoded) {
    if (!mutex_)
        return false;
    auto session = std::make_unique<UnlockSession>();
    xSemaphoreTake(mutex_, portMAX_DELAY);
    if (busy_ || !signing_session_ || !signing_session_->authorized) {
        xSemaphoreGive(mutex_);
        return false;
    }
    session->pin_key.value = signing_session_->pin_key.value;
    session->authorized = true;
    auto index = static_cast<std::size_t>(active_wallet_.load());
    BrzReview brz;
    SolanaReview solana;
    CardanoReview cardano;
    LedgerReview ledger;
    const bool is_ledger = review_ledger(payload, ledger);
    const bool is_cardano = review_cardano(payload, cardano);
    const bool is_solana = !is_cardano && review_solana(payload, solana);
    const bool is_evm = !is_cardano && !is_solana && review_brz(payload, brz);
    const bool local_payment = is_ledger || is_cardano || is_solana || is_evm;
    if (local_payment) {
        std::string generation;
        std::vector<PublicWallet> wallets;
        const auto status = vault_.public_wallets(generation, wallets);
        const auto wallet = std::find_if(wallets.begin(), wallets.end(), [&](const auto& row) {
            return row.id == (is_ledger    ? ledger.wallet_id
                              : is_cardano ? cardano.wallet_id
                              : is_solana  ? solana.wallet_id
                                           : brz.wallet_id);
        });
        if (status != VaultError::None || wallet == wallets.end()) {
            xSemaphoreGive(mutex_);
            return false;
        }
        index = static_cast<std::size_t>(wallet - wallets.begin());
    }
    const auto epoch = authorization_epoch_.load();
    busy_ = true;
    xSemaphoreGive(mutex_);
    UtxoReview review;
    crypto::Entropy entropy;
    Credential pass, words;
    crypto::Seed seed;
    const bool valid = local_payment || review_utxo(payload, review);
    const auto& mode = is_ledger    ? ledger.mode
                       : is_cardano ? cardano.mode
                       : is_solana  ? solana.mode
                       : is_evm     ? brz.mode
                                    : review.mode;
    bool ok = valid && vault_.recover(*session, phrase, entropy, pass, index) == VaultError::None &&
              crypto::mnemonic_from_entropy({entropy.data.value.data(), entropy.size}, words) ==
                  crypto::HdError::None;
    const auto seed_phrase = mode == "HIDDEN" ? pass.view() : std::string_view{};
    if (ok && is_cardano)
        ok = sign_cardano(payload, words.view(), seed_phrase, encoded);
    else if (ok) {
        const crypto::Bytes bytes{seed.value.data(), seed.value.size()};
        ok = crypto::wallet_core_seed(words.view(), seed_phrase, seed) == crypto::HdError::None &&
             (is_ledger   ? sign_ledger(payload, bytes, signing_random_, encoded)
              : is_solana ? sign_solana(payload, bytes, signing_random_, encoded)
              : is_evm    ? sign_brz(payload, bytes, signing_random_, encoded)
                          : sign_utxo(payload, bytes, signing_random_, encoded));
    }
    session.reset();
    xSemaphoreTake(mutex_, portMAX_DELAY);
    busy_ = false;
    ok = ok && signing_session_ && signing_session_->authorized && epoch == authorization_epoch_ &&
         (local_payment || index == active_wallet_);
    xSemaphoreGive(mutex_);
    if (!ok)
        encoded.clear();
    return ok;
}
VaultError BoardSetupService::inspect(WalletProfile& profile) {
    if (!mutex_)
        return VaultError::Storage;
    xSemaphoreTake(mutex_, portMAX_DELAY);
    const auto status = busy_ ? VaultError::Storage : vault_.inspect(profile);
    xSemaphoreGive(mutex_);
    return status;
}
StoreRead BoardSetupService::load_language(std::string& code) {
    if (!mutex_)
        return StoreRead::Error;
    xSemaphoreTake(mutex_, portMAX_DELAY);
    const auto status = busy_ ? StoreRead::Error : store_.load_language(code);
    xSemaphoreGive(mutex_);
    return status;
}
bool BoardSetupService::save_language(std::string_view code) {
    if (!mutex_)
        return false;
    xSemaphoreTake(mutex_, portMAX_DELAY);
    const bool saved = !busy_ && store_.save_language(code);
    xSemaphoreGive(mutex_);
    return saved;
}
StoreRead BoardSetupService::load_device_name(std::string& name) {
    if (!mutex_)
        return StoreRead::Error;
    xSemaphoreTake(mutex_, portMAX_DELAY);
    const auto status = busy_ ? StoreRead::Error : store_.load_device_name(name);
    xSemaphoreGive(mutex_);
    return status;
}
bool BoardSetupService::save_device_name(std::string_view name) {
    if (!mutex_)
        return false;
    xSemaphoreTake(mutex_, portMAX_DELAY);
    const bool ok = !busy_ && store_.save_device_name(name);
    if (ok) {
        approved_ = 0;
        ++authorization_epoch_;
    }
    xSemaphoreGive(mutex_);
    return ok;
}
bool BoardSetupService::load_monero_enabled(std::size_t index, bool& enabled) {
    if (!mutex_)
        return false;
    xSemaphoreTake(mutex_, portMAX_DELAY);
    const bool ok = !busy_ && vault_.monero_enabled(index, enabled) == VaultError::None;
    xSemaphoreGive(mutex_);
    return ok;
}
bool BoardSetupService::save_monero_enabled(const UnlockSession& session, std::size_t index,
                                            bool enabled) {
    if (!mutex_)
        return false;
    xSemaphoreTake(mutex_, portMAX_DELAY);
    const bool ok =
        !busy_ && vault_.set_monero_enabled(session, index, enabled) == VaultError::None;
    if (ok) {
        approved_ = 0;
        ++authorization_epoch_;
    }
    xSemaphoreGive(mutex_);
    return ok;
}
bool BoardSetupService::submit(std::unique_ptr<SetupJob> job) {
    if (!mutex_ || !job)
        return false;
    auto result = std::unique_ptr<SetupResult>(new (std::nothrow) SetupResult);
    if (!result)
        return false;
    xSemaphoreTake(mutex_, portMAX_DELAY);
    if (busy_ || result_) {
        xSemaphoreGive(mutex_);
        return false;
    }
    if (job->work != SetupWork::Open && job->work != SetupWork::Unlock &&
        job->work != SetupWork::ReceivingAddresses && job->work != SetupWork::PublicAddresses) {
        approved_ = 0;
        ++authorization_epoch_;
    }
    pending_ = std::move(job);
    result_ = std::move(result);
    busy_ = true;
    const auto created =
        xTaskCreatePinnedToCore(worker, "wallet_setup", 16384, this, 1, nullptr, 1);
    if (created != pdPASS) {
        pending_.reset();
        result_.reset();
        busy_ = false;
    }
    xSemaphoreGive(mutex_);
    return created == pdPASS;
}
std::unique_ptr<SetupResult> BoardSetupService::poll() {
    if (!mutex_)
        return nullptr;
    xSemaphoreTake(mutex_, portMAX_DELAY);
    auto result = busy_ ? nullptr : std::move(result_);
    xSemaphoreGive(mutex_);
    return result;
}
void BoardSetupService::worker(void* context) {
    auto& self = *static_cast<BoardSetupService*>(context);
    {
        xSemaphoreTake(self.mutex_, portMAX_DELAY);
        auto job = std::move(self.pending_);
        auto result = std::move(self.result_);
        xSemaphoreGive(self.mutex_);
        run_setup_job(self.vault_, *job, *result);
        job.reset(); // Wipe credentials before publishing completion.
        xSemaphoreTake(self.mutex_, portMAX_DELAY);
        self.result_ = std::move(result);
        self.busy_ = false;
        xSemaphoreGive(self.mutex_);
    }
    vTaskDelete(nullptr);
}
} // namespace airlock
