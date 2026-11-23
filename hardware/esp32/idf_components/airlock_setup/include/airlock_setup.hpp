// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "core/setup_vault.hpp"
#include "freertos/FreeRTOS.h"
#include <atomic>
#include "freertos/semphr.h"

namespace airlock {
class NvsSetupStore final : public SetupStore {
  public:
    explicit NvsSetupStore(const char* storage_namespace = "bjj_wallet");
    StoreRead read(std::vector<std::uint8_t>& data) override;
    bool commit_if_empty(crypto::Bytes data) override;
    bool replace(crypto::Bytes expected, crypto::Bytes data) override;
    StoreRead load_language(std::string& code) override;
    bool save_language(std::string_view code) override;
    StoreRead load_device_name(std::string& name) override;
    bool save_device_name(std::string_view name) override;
    bool public_cache_enabled() const override {
        return true;
    }
    StoreRead read_public(std::string_view id, std::vector<std::uint8_t>& data) override;
    bool write_public(std::string_view id, crypto::Bytes data) override;
    StoreRead read_monero_enabled(std::string_view id, bool& enabled) override;
    bool write_monero_enabled(std::string_view id, bool enabled) override;

  private:
    bool initialized_ = false;
    const char* namespace_;
};
class BoardSetupService final : public SetupService, public PublicWalletService {
  public:
    BoardSetupService(SetupStore& store, crypto::RandomSource& random);
    void connection_state(std::uint64_t connection) {
        current_ = connection;
        if (!connection && approved_.exchange(0))
            ++authorization_epoch_;
    }
    void reject_connection(std::uint64_t connection) {
        rejected_ = connection;
        approved_ = 0;
        ++authorization_epoch_;
    }
    bool connection_approved(std::uint64_t connection) const {
        return connection && approved_ == connection;
    }
    void approve_connection(std::uint64_t connection) {
        approved_ = connection;
        ++authorization_epoch_;
    }
    void select_public_wallet(std::size_t index) {
        approved_ = 0;
        ++authorization_epoch_;
        active_wallet_ = index;
    }
    std::uint64_t public_authorization_epoch() const override {
        return authorization_epoch_;
    }
    PublicWalletError public_access(std::uint64_t connection, bool& required) override;
    PublicWalletError verify_public_pin(std::uint64_t connection, std::string_view pin,
                                        std::uint64_t now, bool& accepted) override;
    bool multi_wallet_sync_supported() const override {
        return true;
    }
    PublicWalletError public_wallets(std::string_view generation,
                                     std::vector<PublicWallet>& wallets) const override;
    PublicWalletError public_generation(std::string& generation) const override;
    PublicWalletError public_accounts(std::string_view generation, const PublicWalletQuery& query,
                                      std::vector<PublicAccount>& rows) const override;
    bool brz_signing_supported() const override {
        return true;
    }
    bool polygon_signing_supported() const override {
        return true;
    }
    bool ethereum_native_signing_supported() const override {
        return true;
    }
    bool solana_signing_supported() const override {
        return true;
    }
    bool cardano_signing_supported() const override {
        return true;
    }
    bool utxo_signing_supported() const override {
        return true;
    }
    bool monero_sync_supported() const override;
    bool monero_transfer_supported() const override;
    std::string monero_wallet_name(std::string_view id) override;
    bool run_monero(const MoneroRequest&, std::string_view, std::string&, std::string&) override;
    bool review_signing(std::string_view, TransactionPrompt&) override;
    bool sign_transaction(std::string_view, std::string_view, std::string&) override;
    void signing_session(const UnlockSession* session);
    VaultError inspect(WalletProfile& profile) override;
    StoreRead load_language(std::string& code) override;
    bool save_language(std::string_view code) override;
    StoreRead load_device_name(std::string& name) override;
    bool save_device_name(std::string_view name) override;
    bool load_monero_enabled(std::size_t index, bool& enabled) override;
    bool save_monero_enabled(const UnlockSession&, std::size_t index, bool enabled) override;
    bool submit(std::unique_ptr<SetupJob> job) override;
    std::unique_ptr<SetupResult> poll() override;

  private:
    std::atomic<std::uint64_t> approved_{0}, rejected_{0}, current_{0}, authorization_epoch_{0};
    std::atomic<std::size_t> active_wallet_{0};
    SetupStore& store_;
    mutable SetupVault vault_;
    crypto::RandomSource& signing_random_;
    std::unique_ptr<UnlockSession> signing_session_;
    SemaphoreHandle_t mutex_ = nullptr;
    bool busy_ = false;
    std::unique_ptr<SetupJob> pending_;
    std::unique_ptr<SetupResult> result_;
    static void worker(void* context);
};
#ifdef AIRLOCK_SETUP_SELFTEST
bool setup_storage_self_test(crypto::RandomSource& random);
#endif
} // namespace airlock
