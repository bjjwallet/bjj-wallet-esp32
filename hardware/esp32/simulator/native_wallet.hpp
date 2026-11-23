// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "core/wallet_service.hpp"
#include "core/public_wallet_service.hpp"
#include <atomic>
#include <filesystem>
#include <mutex>
#include <thread>

namespace airlock {
// macOS implementation of the original WalletService. Uses the official native
// Wallet Core 4.4.2 archive, OpenSSL AES-GCM/PBKDF2 and the original SQLite schema.
// This adapter is never linked into the ESP32 image.
class NativeWallet final : public WalletService, public PublicWalletService {
  public:
    explicit NativeWallet(std::filesystem::path directory);
    ~NativeWallet() override;
    bool available() const override {
        return lock_fd_ >= 0;
    }
    bool exists() const override;
    bool busy() const override {
        return busy_.load();
    }
    bool submit(WalletJob job) override;
    std::optional<WalletResult> poll() override;
    const std::filesystem::path& directory() const {
        return directory_;
    }
    std::string language() const;
    bool save_language(std::string_view code);
    PublicWalletError public_generation(std::string& generation) const override;
    PublicWalletError public_accounts(std::string_view generation, const PublicWalletQuery& query,
                                      std::vector<PublicAccount>& accounts) const override;
    // Read-only decryption for local verification; never logs secrets or exposes
    // them through an SDL/UI callback. Matches the original HIDDEN/standard files.
    bool unlock(std::string_view password, bool hidden, Credential& mnemonic,
                Credential& passphrase, bool& imported) const;

  private:
    std::filesystem::path directory_;
    int lock_fd_ = -1;
    std::atomic<bool> busy_{false};
    std::mutex mutex_;
    // Serializes public reads with acceptance of a create/import job. Once busy
    // is set under this lock, no public reader touches files being replaced.
    mutable std::mutex public_mutex_;
    std::uint64_t public_revision_ = 0;
    std::thread worker_;
    std::optional<WalletResult> result_;
    WalletResult execute(const WalletJob& job);
};
} // namespace airlock
